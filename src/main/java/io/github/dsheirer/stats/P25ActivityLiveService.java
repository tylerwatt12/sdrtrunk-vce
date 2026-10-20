/* Copyright (C) 2026 Dennis Sheirer. GNU General Public License, version 3 or later. */
package io.github.dsheirer.stats;

import io.github.dsheirer.stats.activity.ActivityCommitJournal;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One demand-driven reader/encoder for committed P25 Activity, shared by every browser. */
final class P25ActivityLiveService implements AutoCloseable
{
    private static final java.util.concurrent.atomic.AtomicLong AFFILIATION_REVISIONS = new java.util.concurrent.atomic.AtomicLong();
    private static final Logger mLog = LoggerFactory.getLogger(P25ActivityLiveService.class);
    static final int TOPIC = 7;
    static final int PAGE_LIMIT = 500;
    static final int SEED_LIMIT = 500;
    private static final long SEED_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final int MAXIMUM_SEED_BYTES = 12 * 1024 * 1024;
    static final int MAXIMUM_AFFILIATIONS = 10_000;
    private static final int MAXIMUM_AFFILIATION_BYTES = 8 * 1024 * 1024;
    private static final int MAXIMUM_PENDING_CATCHUPS = 256;
    private static final int MAXIMUM_IDS_PER_TICK = 2_000;
    private static final int MAXIMUM_PART_IDS = 100;
    private static final long TICK_MILLISECONDS = 250;
    interface Source
    {
        List<Map<String,Object>> rows(List<Long> ids);
        List<Map<String,Object>> recentRows();
        default long presentationGeneration() { return 0; }
        List<Map<String,Object>> affiliationRows();
        List<Map<String,Object>> presentAffiliations(List<Map<String,Object>> rows);
    }
    record State(long revision, boolean initialized, String epoch, long sequence, int timeout, boolean enabled)
    {
        Map<String,Object> payload(String subscriptionId)
        {
            return Map.of("subscription_id", subscriptionId, "source_epoch", epoch, "sequence", sequence,
                "server_time_ms", System.currentTimeMillis(),
                "traffic_grant_age_out_milliseconds", timeout, "collection_enabled", enabled);
        }
    }
    private record RadioKey(String system, String radio)
    {
        Map<String,Object> payload() { return Map.of("radio_system_key",system,"radio_identity_key",radio); }
    }
    private record Recent(ActivityCommitJournal journal,String epoch,long sequence,long preparedAt,long capturedAt,
                          List<Map<String,Object>> rows) { }
    private record Seed(ActivityCommitJournal journal, String epoch, long sequence, long preparedAt,
                        ApiHttpResponse.PreparedJson response) { }
    private record CatchupResult(Map<String,Object> data, ApiHttpResponse.PreparedJson response) { }
    private record CatchupKey(String epoch, long after, Long through, long afterId, int limit, long affiliationRevision) { }
    private record Catchup(String epoch, long after, Long through, long afterId, int limit, long affiliationRevision,
                           CompletableFuture<CatchupResult> result) { }
    private final Supplier<ActivityCommitJournal> mJournalSupplier;
    private final Source mSource;
    private final IntSupplier mTimeout;
    private final BooleanSupplier mEnabled;
    private final StatsLiveEventHub mHub;
    private final ArrayBlockingQueue<CompletableFuture<ApiHttpResponse.PreparedJson>> mSeeds =
        new ArrayBlockingQueue<>(MAXIMUM_PENDING_CATCHUPS);
    private Seed mSeed;
    private Recent mRecent;
    private final java.util.concurrent.atomic.AtomicBoolean mRefreshPresentation = new java.util.concurrent.atomic.AtomicBoolean();
    private long mLastPresentationRefresh;
    private final ArrayBlockingQueue<Catchup> mCatchups = new ArrayBlockingQueue<>(MAXIMUM_PENDING_CATCHUPS);
    private final String mFallbackEpoch = java.util.UUID.randomUUID().toString();
    private volatile State mState = new State(0, false, "", 0, 1_000, false);
    private volatile boolean mClosed;
    private Thread mReader;
    private ActivityCommitJournal mJournal;
    private String mEpoch;
    private long mCursor;
    private long mPendingThrough;
    private List<Long> mPendingIds = List.of();
    private int mPendingOffset;
    private boolean mFailed;
    private boolean mSeedFailed;
    private long mAffiliationPollAt;
    private long mAffiliationPresentationGeneration = -1;
    private volatile long mAffiliationRevision;
    private Map<RadioKey,Integer> mAffiliationRowBytes = Map.of();
    private long mAffiliationBytes;
    private boolean mAffiliationsAvailable;
    private boolean mAffiliationsComplete;
    private boolean mAffiliationExposed;
    private boolean mAffiliationFailed;
    private Map<RadioKey,Map<String,Object>> mAffiliationCore = Map.of();
    private Map<RadioKey,Map<String,Object>> mAffiliations = Map.of();
    private List<Map<String,Object>> mAffiliationRows = List.of();

    P25ActivityLiveService(Supplier<ActivityCommitJournal> journal, Source source, IntSupplier timeout,
                           BooleanSupplier enabled)
    {
        this(journal, source, timeout, enabled, StatsLiveEventHub.MAXIMUM_BROWSER_SUBSCRIBERS, 128);
    }

    P25ActivityLiveService(Supplier<ActivityCommitJournal> journal, Source source, IntSupplier timeout,
                           BooleanSupplier enabled, int maximumSubscribers, int queueCapacity)
    {
        mJournalSupplier = journal;
        mSource = source;
        mTimeout = timeout;
        mEnabled = enabled;
        mHub = new StatsLiveEventHub(maximumSubscribers, queueCapacity);
    }

    synchronized StatsLiveEventHub.Subscription subscribe()
    {
        if(mClosed) return null;
        StatsLiveEventHub.Subscription subscription = mHub.subscribe();
        if(subscription != null && mReader == null)
        {
            mReader = new ObserverThreadFactory("p25 committed activity reader").newThread(this::run);
            mReader.start();
        }
        return subscription;
    }

    State state() { return mState; }

    ApiHttpResponse.PreparedJson seed() { return seed(false); }

    ApiHttpResponse.PreparedJson seed(boolean refresh)
    {
        CompletableFuture<ApiHttpResponse.PreparedJson> request = new CompletableFuture<>();
        if(mClosed || !mHub.hasSubscribers() || !mSeeds.offer(request))
            throw new StatsApiException(503, "Live Activity is busy; retry shortly");
        if(refresh) mRefreshPresentation.set(true);
        try { return request.get(5, TimeUnit.SECONDS); }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            request.cancel(false);
            throw new StatsApiException(503, "Recent Activity loading was interrupted");
        }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception)
        {
            request.cancel(false);
            throw new StatsApiException(503, "Recent Activity is temporarily unavailable");
        }
    }

    Map<String,Object> catchup(String epoch, long after, Long through, long afterId, int limit)
    {
        return requestCatchup(epoch, after, through, afterId, limit, mAffiliationRevision).data();
    }

    ApiHttpResponse.PreparedJson preparedCatchup(String epoch, long after, Long through, long afterId, int limit, long affiliationRevision)
    {
        return requestCatchup(epoch, after, through, afterId, limit, affiliationRevision).response();
    }

    private CatchupResult requestCatchup(String epoch, long after, Long through, long afterId, int limit, long affiliationRevision)
    {
        if(epoch == null || epoch.length() > 128 || affiliationRevision < 0 || after < 0 || afterId < 0 || through != null && through < after ||
            limit < 1 || limit > PAGE_LIMIT) throw new StatsApiException(400, "Live Activity cursor is invalid");
        CompletableFuture<CatchupResult> result = new CompletableFuture<>();
        if(mClosed || !mHub.hasSubscribers() || !mCatchups.offer(new Catchup(epoch, after, through, afterId, limit, affiliationRevision, result)))
            throw new StatsApiException(503, "Live Activity is busy; retry shortly");
        try { return result.get(5, TimeUnit.SECONDS); }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            result.cancel(false);
            throw new StatsApiException(503, "Live Activity catchup was interrupted");
        }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception)
        {
            result.cancel(false);
            throw new StatsApiException(503, "Live Activity catchup is temporarily unavailable");
        }
    }

    private void run()
    {
        while(!mClosed)
        {
            try
            {
                if(mHub.hasSubscribers())
                {
                    bind();
                    pollAffiliations();
                    if(mJournal != null) drain();
                    refreshMetadata();
                    processCatchups();
                    processSeeds();
                }
                else
                {
                    // Keep the writer's bounded receipts, but perform no database or wire work without viewers.
                    mJournal = null;
                    mSeed = null;
                    mPendingIds = List.of();
                    CompletableFuture<ApiHttpResponse.PreparedJson> seed;
                    while((seed = mSeeds.poll()) != null)
                        seed.completeExceptionally(new IllegalStateException("No live Activity viewers"));
                    Catchup request;
                    while((request = mCatchups.poll()) != null)
                        request.result().completeExceptionally(new IllegalStateException("No live Activity viewers"));
                }
                Thread.sleep(TICK_MILLISECONDS);
            }
            catch(InterruptedException exception) { if(mClosed) return; }
            catch(RuntimeException | IOException exception)
            {
                if(!mFailed && mHub.hasSubscribers())
                {
                    mLog.warn("Committed P25 Activity feed is temporarily unavailable", exception);
                    mHub.publish("resync_required", Map.of("reason", "saved_activity_unavailable",
                        "source_epoch", mState.epoch(), "sequence", mState.sequence()));
                }
                mFailed = true;
                try { Thread.sleep(TICK_MILLISECONDS); }
                catch(InterruptedException interrupted) { if(mClosed) return; }
            }
        }
    }

    private void bind()
    {
        ActivityCommitJournal journal = mJournalSupplier.get();
        ActivityCommitJournal.Boundary boundary = journal != null ? journal.boundary() : null;
        if(journal == mJournal && (boundary != null && boundary.sourceEpoch().equals(mEpoch) ||
            boundary == null && mFallbackEpoch.equals(mEpoch))) return;
        String epoch = boundary != null ? boundary.sourceEpoch() : mFallbackEpoch;
        boolean sourceChanged = !Objects.equals(mEpoch,epoch);
        mJournal = journal;
        mAffiliationPollAt = 0;
        if(sourceChanged)
        {
            mAffiliationPresentationGeneration = -1;
            mAffiliationRevision = AFFILIATION_REVISIONS.incrementAndGet();
            mRecent = null;
            mAffiliationsAvailable = false;
            mAffiliationsComplete = false;
            mAffiliationExposed = false;
            mAffiliationFailed = false;
            mAffiliationCore = Map.of();
            mAffiliations = Map.of();
            mAffiliationRows = List.of();
            mAffiliationRowBytes = Map.of();
            mAffiliationBytes = 0;
        }
        mSeed = null;
        mPendingIds = List.of();
        mPendingOffset = 0;
        mEpoch = epoch;
        mCursor = boundary != null ? boundary.sequence() : 0;
        mState = new State(mState.revision() + 1, true, mEpoch, mCursor, Math.max(100, mTimeout.getAsInt()), mEnabled.getAsBoolean());
        mFailed = false;
    }

    private void drain() throws IOException
    {
        if(mPendingIds.isEmpty())
        {
            ActivityCommitJournal.Range range = mJournal.range(mEpoch, mCursor, null);
            if(!range.available())
            {
                mCursor = range.boundary().sequence();
                State previous = mState;
                mState = new State(previous.revision() + 1, true, mEpoch, mCursor, Math.max(100, mTimeout.getAsInt()), mEnabled.getAsBoolean());
                mHub.publish("resync_required", Map.of("reason", "journal_gap", "source_epoch", mEpoch,
                    "sequence", mCursor));
                return;
            }
            if(range.boundary().sequence() == mCursor) return;
            mPendingThrough = range.boundary().sequence();
            mPendingIds = range.ids();
            mPendingOffset = 0;
        }
        // A single revision can span parts; bound both each frame and work in one reader turn.
        int totalParts = Math.max(1, (mPendingIds.size() + MAXIMUM_PART_IDS - 1) / MAXIMUM_PART_IDS);
        int until = Math.min(mPendingIds.size(), mPendingOffset + MAXIMUM_IDS_PER_TICK);
        do
        {
            int end = Math.min(mPendingIds.size(), mPendingOffset + MAXIMUM_PART_IDS);
            List<Long> ids = mPendingIds.subList(mPendingOffset, end);
            List<Map<String,Object>> rows = read(ids);
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("source_epoch", mEpoch);
            data.put("base_sequence", mCursor);
            data.put("sequence", mPendingThrough);
            data.put("rows", rows);
            data.put("removed_ids", removed(ids, rows));
            data.put("part", mPendingOffset / MAXIMUM_PART_IDS);
            data.put("parts", totalParts);
            StatsLiveEventHub.LiveEvent event = StatsLiveEventHub.LiveEvent.prepared("activity_delta", Map.copyOf(data), TOPIC);
            LiveMultiplexFrame frame = event.frame(TOPIC);
            if(frame.bytes(false).length > StatsWebServerService.MultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC)
                throw new IOException("P25 Activity frame exceeds its byte budget");
            frame.bytes(true); // Shared compression is prepared here, never on a database/receiver producer.
            if(!current()) { mPendingIds = List.of(); return; }
            mHub.publish(event);
            mPendingOffset = end;
        }
        while(mPendingOffset < until);
        if(mPendingOffset == mPendingIds.size())
        {
            mCursor = mPendingThrough;
            State previous = mState;
            mState = new State(previous.revision(), true, mEpoch, mCursor, Math.max(100, mTimeout.getAsInt()), mEnabled.getAsBoolean());
            mPendingIds = List.of();
            mFailed = false;
        }
    }

    private void refreshMetadata()
    {
        State previous = mState;
        int timeout = Math.max(100, mTimeout.getAsInt());
        boolean enabled = mEnabled.getAsBoolean();
        if(previous.timeout() != timeout || previous.enabled() != enabled)
        {
            mSeed = null;
            mState = new State(previous.revision() + 1, previous.initialized(), previous.epoch(), previous.sequence(), timeout, enabled);
        }
    }

    private void addAffiliations(Map<String,Object> payload)
    {
        mAffiliationExposed = true;
        payload.put("affiliation_revision",mAffiliationRevision);
        payload.put("affiliations_available",mAffiliationsAvailable);
        payload.put("affiliations_complete",mAffiliationsComplete);
        payload.put("affiliations",mAffiliationRows);
    }

    private boolean affiliationSourceCurrent()
    {
        ActivityCommitJournal current = mJournalSupplier.get();
        return !mClosed && mHub.hasSubscribers() && current == mJournal && (current == null ? mFallbackEpoch.equals(mEpoch) :
            current.boundary().sourceEpoch().equals(mEpoch));
    }

    /** Independent current-state revision: optional Activity rows never establish or clear an affiliation. */
    private void pollAffiliations() throws IOException
    {
        if(System.nanoTime() < mAffiliationPollAt) return;
        mAffiliationPollAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try
        {
            List<Map<String,Object>> rows = mSource.affiliationRows();
            if(rows.size() > MAXIMUM_AFFILIATIONS) throw new IllegalStateException("Current affiliation row budget exceeded");
            Map<RadioKey,Map<String,Object>> core = new LinkedHashMap<>();
            for(Map<String,Object> row: rows)
            {
                RadioKey key = new RadioKey((String)row.get("radio_system_key"),(String)row.get("source_identity_key"));
                if(key.system() == null || key.radio() == null || core.put(key,row) != null)
                    throw new IllegalStateException("Current affiliation identity is invalid");
            }
            if(!affiliationSourceCurrent()) return;
            long presentation = mSource.presentationGeneration();
            List<Map<String,Object>> changed = core.entrySet().stream()
                .filter(entry -> presentation != mAffiliationPresentationGeneration || !entry.getValue().equals(mAffiliationCore.get(entry.getKey())))
                .map(Map.Entry::getValue).toList();
            List<RadioKey> removed = mAffiliations.keySet().stream().filter(key -> !core.containsKey(key)).toList();
            if(changed.isEmpty() && removed.isEmpty() && mAffiliationsAvailable) return;
            List<Map<String,Object>> upserts = mSource.presentAffiliations(changed);
            if(upserts.size() != changed.size()) throw new IllegalStateException("Current affiliation projection is incomplete");
            Map<RadioKey,Map<String,Object>> publicRows = new LinkedHashMap<>(mAffiliations);
            Map<RadioKey,Integer> rowBytes = new LinkedHashMap<>(mAffiliationRowBytes);
            long bytes = mAffiliationBytes;
            for(RadioKey key: removed)
            {
                publicRows.remove(key);
                bytes -= rowBytes.remove(key);
            }
            for(Map<String,Object> row: upserts)
            {
                RadioKey key = new RadioKey((String)row.get("radio_system_key"),(String)row.get("radio_identity_key"));
                if(!core.containsKey(key)) throw new IllegalStateException("Current affiliation projection changed its identity");
                publicRows.put(key,row);
                int encoded = ApiHttpResponse.encodeNormalizedPayload(row).length;
                Integer oldBytes = rowBytes.put(key,encoded);
                bytes += encoded - (oldBytes != null ? oldBytes : 0);
                if(bytes + publicRows.size() + 2 > MAXIMUM_AFFILIATION_BYTES)
                    throw new IllegalStateException("Current affiliation byte budget exceeded");
            }
            List<Map<String,Object>> complete = List.copyOf(publicRows.values());
            if(!affiliationSourceCurrent()) return;
            long previous = mAffiliationRevision;
            mAffiliationPresentationGeneration = presentation;
            mAffiliationCore = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(core));
            mAffiliations = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(publicRows));
            mAffiliationRows = complete;
            mAffiliationRowBytes = java.util.Collections.unmodifiableMap(rowBytes);
            mAffiliationBytes = bytes;
            mAffiliationsAvailable = true;
            mAffiliationsComplete = true;
            mAffiliationFailed = false;
            mAffiliationRevision = AFFILIATION_REVISIONS.incrementAndGet();
            mSeed = null;
            if(mAffiliationExposed) publishAffiliations(previous,upserts,removed);
        }
        catch(RuntimeException exception)
        {
            if(!affiliationSourceCurrent()) return;
            if(!mAffiliationFailed) mLog.warn("Current P25 affiliations are temporarily unavailable",exception);
            mAffiliationFailed = true;
            if(mAffiliationsAvailable)
            {
                long previous = mAffiliationRevision;
                mAffiliationsAvailable = false;
                mAffiliationRevision = AFFILIATION_REVISIONS.incrementAndGet();
                mSeed = null;
                if(mAffiliationExposed) publishAffiliations(previous,List.of(),List.of());
            }
        }
    }

    private void publishAffiliations(long previous, List<Map<String,Object>> upserts, List<RadioKey> removed)
        throws IOException
    {
        int changes = upserts.size() + removed.size();
        int parts = Math.max(1,(changes + MAXIMUM_PART_IDS - 1) / MAXIMUM_PART_IDS);
        for(int part = 0; part < parts; part++)
        {
            int start = part * MAXIMUM_PART_IDS, end = Math.min(changes,start + MAXIMUM_PART_IDS);
            List<Map<String,Object>> rows = upserts.subList(Math.min(start,upserts.size()),Math.min(end,upserts.size()));
            List<Map<String,Object>> deleted = removed.subList(Math.max(0,start-upserts.size()),Math.max(0,end-upserts.size()))
                .stream().map(RadioKey::payload).toList();
            StatsLiveEventHub.LiveEvent event = StatsLiveEventHub.LiveEvent.prepared("affiliation_delta",Map.of(
                "source_epoch",mEpoch,"base_affiliation_revision",previous,"affiliation_revision",mAffiliationRevision,
                "affiliations_available",mAffiliationsAvailable,"upserts",rows,"removed_radio_keys",deleted,
                "part",part,"parts",parts),TOPIC);
            LiveMultiplexFrame frame = event.frame(TOPIC);
            if(frame.bytes(false).length > StatsWebServerService.MultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC)
                throw new IOException("Current affiliation frame exceeds its byte budget");
            frame.bytes(true);
            if(!affiliationSourceCurrent()) return;
            mHub.publish(event);
        }
    }

    private void processSeeds()
    {
        if(mSeeds.isEmpty()) return;
        List<CompletableFuture<ApiHttpResponse.PreparedJson>> requests = new ArrayList<>();
        mSeeds.drainTo(requests, MAXIMUM_PENDING_CATCHUPS);
        if(requests.stream().allMatch(CompletableFuture::isCancelled)) return;
        try
        {
            if(mRefreshPresentation.getAndSet(false) && System.nanoTime() - mLastPresentationRefresh >= SEED_LIFETIME_NANOS)
            {
                mLastPresentationRefresh = System.nanoTime();
                mAffiliationPresentationGeneration = -1;
                mAffiliationPollAt = 0;
                pollAffiliations();
                mSeed = null;
                mRecent = null;
            }
            Seed seed = mSeed;
            if(seed == null || System.nanoTime() - seed.preparedAt() >= SEED_LIFETIME_NANOS ||
                !seedCurrent(seed))
            {
                Recent recent = mRecent;
                if(recent == null || System.nanoTime() - recent.preparedAt() >= SEED_LIFETIME_NANOS ||
                    !recentCurrent(recent))
                {
                    ActivityCommitJournal journal = mJournal;
                    ActivityCommitJournal.Boundary boundary = journal != null ? journal.boundary() : null;
                    String epoch = boundary != null ? boundary.sourceEpoch() : mFallbackEpoch;
                    long sequence = boundary != null ? boundary.sequence() : 0;
                    long captured = System.currentTimeMillis();
                    List<Map<String,Object>> rows = mSource.recentRows();
                    if(rows.size() > SEED_LIMIT) throw new IllegalStateException("Recent Activity exceeded its row budget");
                    recent = new Recent(journal,epoch,sequence,System.nanoTime(),captured,rows);
                    if(!recentCurrent(recent)) throw new IllegalStateException("Recent Activity source changed while loading");
                    mRecent = recent;
                }
                Map<String,Object> payload = new LinkedHashMap<>(Map.of(
                    "source_epoch", recent.epoch(), "sequence", recent.sequence(), "rows", recent.rows(), "captured_at_ms", recent.capturedAt(),
                    "traffic_grant_age_out_milliseconds", mState.timeout(), "collection_enabled", mState.enabled()));
                addAffiliations(payload);
                ApiHttpResponse.PreparedJson prepared = ApiHttpResponse.prepareNormalizedData(payload);
                if(prepared.identity().length > MAXIMUM_SEED_BYTES)
                    throw new IllegalStateException("Recent Activity exceeded its byte budget");
                seed = new Seed(recent.journal(),recent.epoch(),recent.sequence(),recent.preparedAt(),prepared);
                if(!seedCurrent(seed)) throw new IllegalStateException("Recent Activity source changed while loading");
                mSeed = seed;
            }
            for(var request: requests) request.complete(seed.response());
            mSeedFailed = false;
        }
        catch(RuntimeException | IOException exception)
        {
            mSeed = null;
            if(!mSeedFailed) mLog.warn("Recent P25 Activity seed is temporarily unavailable", exception);
            mSeedFailed = true;
            for(var request: requests) request.completeExceptionally(exception);
        }
    }

    private boolean recentCurrent(Recent recent)
    {
        if(mClosed || !mHub.hasSubscribers() || mJournalSupplier.get() != recent.journal()) return false;
        return recent.journal() == null ? mFallbackEpoch.equals(mEpoch) && mEpoch.equals(recent.epoch()) :
            recent.journal().range(recent.epoch(),recent.sequence(),null).available();
    }

    private boolean seedCurrent(Seed seed)
    {
        if(mClosed || !mHub.hasSubscribers() || mJournalSupplier.get() != seed.journal()) return false;
        return seed.journal() == null ? mFallbackEpoch.equals(mEpoch) && mEpoch.equals(seed.epoch()) :
            seed.journal().range(seed.epoch(), seed.sequence(), null).available();
    }

    private void processCatchups()
    {
        List<Catchup> pending = new ArrayList<>();
        mCatchups.drainTo(pending, MAXIMUM_PENDING_CATCHUPS);
        Map<CatchupKey,List<Catchup>> groups = new LinkedHashMap<>();
        for(Catchup request: pending)
        {
            if(!request.result().isCancelled())
                groups.computeIfAbsent(new CatchupKey(request.epoch(), request.after(), request.through(),
                    request.afterId(), request.limit(),request.affiliationRevision()), ignored -> new ArrayList<>()).add(request);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
        boolean processed = false;
        for(List<Catchup> group: groups.values())
        {
            if(processed && System.nanoTime() >= deadline)
            {
                for(Catchup request: group)
                    if(!mCatchups.offer(request)) request.result().completeExceptionally(
                        new IllegalStateException("Live Activity catchup capacity is in use"));
                continue;
            }
            try
            {
                Map<String,Object> page = catchupPage(group.getFirst());
                CatchupResult result = new CatchupResult(page, ApiHttpResponse.prepareNormalizedData(page));
                for(Catchup request: group) request.result().complete(result);
            }
            catch(RuntimeException | IOException exception)
            {
                for(Catchup request: group) request.result().completeExceptionally(exception);
            }
            processed = true;
        }
    }

    private Map<String,Object> catchupPage(Catchup request)
    {
        if(mJournal == null)
        {
            if(!affiliationSourceCurrent() || !Objects.equals(mEpoch,request.epoch()) || request.after() != 0 ||
                request.through() != null && request.through() != 0) return resetPage();
            Map<String,Object> page = new LinkedHashMap<>(resetPage());
            page.put("resync_required", false);
            page.put("server_time_ms", System.currentTimeMillis());
            addAffiliationRecovery(page,request);
            return Map.copyOf(page);
        }
        ActivityCommitJournal.Range range = mJournal.range(request.epoch(), request.after(), request.through());
        if(!range.available()) return resetPage();
        List<Long> eligible = range.ids().stream().filter(id -> id > request.afterId()).limit(request.limit() + 1L).toList();
        boolean more = eligible.size() > request.limit();
        List<Long> ids = more ? eligible.subList(0, request.limit()) : eligible;
        List<Map<String,Object>> rows = read(ids);
        if(!current() || !mJournal.range(request.epoch(), request.after(), range.boundary().sequence()).available())
            return resetPage();
        Map<String,Object> page = new LinkedHashMap<>();
        page.put("source_epoch", request.epoch());
        page.put("through_sequence", range.boundary().sequence());
        page.put("rows", rows);
        page.put("removed_ids", removed(ids, rows));
        page.put("next_after_id", ids.isEmpty() ? request.afterId() : ids.getLast());
        page.put("has_more", more);
        if(!more) addAffiliationRecovery(page,request);
        page.put("resync_required", false);
        page.put("server_time_ms", System.currentTimeMillis());
        page.put("traffic_grant_age_out_milliseconds", Math.max(100, mTimeout.getAsInt()));
        return Map.copyOf(page);
    }

    private void addAffiliationRecovery(Map<String,Object> page,Catchup request)
    {
        page.put("affiliation_revision",mAffiliationRevision);
        page.put("affiliations_available",mAffiliationsAvailable);
        page.put("affiliations_complete",mAffiliationsComplete);
        page.put("affiliations_refresh_required",request.affiliationRevision() != mAffiliationRevision);
    }

    private Map<String,Object> resetPage()
    {
        ActivityCommitJournal journal = mJournalSupplier.get();
        ActivityCommitJournal.Boundary boundary = journal != null ? journal.boundary() : null;
        return Map.of("source_epoch", boundary != null ? boundary.sourceEpoch() : mFallbackEpoch,
            "through_sequence", boundary != null ? boundary.sequence() : 0, "rows", List.of(),
            "removed_ids", List.of(), "next_after_id", 0, "has_more", false, "resync_required", true);
    }

    private List<Map<String,Object>> read(List<Long> ids)
    {
        if(ids.isEmpty()) return List.of();
        return mSource.rows(ids);
    }

    private boolean current()
    {
        return !mClosed && mJournalSupplier.get() == mJournal && mJournal != null &&
            Objects.equals(mEpoch, mJournal.boundary().sourceEpoch());
    }

    private static List<Long> removed(List<Long> ids, List<Map<String,Object>> rows)
    {
        HashSet<Long> present = new HashSet<>();
        for(Map<String,Object> row: rows) present.add(((Number)row.get("id")).longValue());
        return ids.stream().filter(id -> !present.contains(id)).toList();
    }

    @Override public synchronized void close()
    {
        mClosed = true;
        mHub.close();
        CompletableFuture<ApiHttpResponse.PreparedJson> seed;
        while((seed = mSeeds.poll()) != null)
            seed.completeExceptionally(new IllegalStateException("Live Activity closed"));
        if(mReader != null) mReader.interrupt();
        Catchup request;
        while((request = mCatchups.poll()) != null)
            request.result().completeExceptionally(new IllegalStateException("Live Activity closed"));
    }
}
