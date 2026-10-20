/* Copyright (C) 2026 Dennis Sheirer. GNU General Public License, version 3 or later. */
package io.github.dsheirer.stats;

import io.github.dsheirer.stats.activity.ActivityCommitJournal;
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
    private static final Logger mLog = LoggerFactory.getLogger(P25ActivityLiveService.class);
    static final int TOPIC = 7;
    static final int PAGE_LIMIT = 500;
    private static final int MAXIMUM_PENDING_CATCHUPS = 256;
    private static final int MAXIMUM_IDS_PER_TICK = 2_000;
    private static final int MAXIMUM_PART_IDS = 100;
    private static final long TICK_MILLISECONDS = 250;
    interface Source
    {
        List<Map<String,Object>> rows(List<Long> ids);
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
    private record CatchupKey(String epoch, long after, Long through, long afterId, int limit) { }
    private record Catchup(String epoch, long after, Long through, long afterId, int limit,
                           CompletableFuture<Map<String,Object>> result) { }
    private final Supplier<ActivityCommitJournal> mJournalSupplier;
    private final Source mSource;
    private final IntSupplier mTimeout;
    private final BooleanSupplier mEnabled;
    private final StatsLiveEventHub mHub;
    private final ArrayBlockingQueue<Catchup> mCatchups = new ArrayBlockingQueue<>(MAXIMUM_PENDING_CATCHUPS);
    private volatile State mState = new State(0, false, "unavailable", 0, 1_000, false);
    private volatile boolean mClosed;
    private Thread mReader;
    private ActivityCommitJournal mJournal;
    private String mEpoch;
    private long mCursor;
    private long mPendingThrough;
    private List<Long> mPendingIds = List.of();
    private int mPendingOffset;
    private boolean mFailed;

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

    Map<String,Object> catchup(String epoch, long after, Long through, long afterId, int limit)
    {
        if(epoch == null || epoch.length() > 128 || after < 0 || afterId < 0 || through != null && through < after ||
            limit < 1 || limit > PAGE_LIMIT) throw new StatsApiException(400, "Live Activity cursor is invalid");
        CompletableFuture<Map<String,Object>> result = new CompletableFuture<>();
        if(mClosed || !mHub.hasSubscribers() || !mCatchups.offer(new Catchup(epoch, after, through, afterId, limit, result)))
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
                    if(mJournal != null) drain();
                    refreshMetadata();
                    processCatchups();
                }
                else
                {
                    // Keep the writer's bounded receipts, but perform no database or wire work without viewers.
                    mJournal = null;
                    mPendingIds = List.of();
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
            boundary == null && "unavailable".equals(mEpoch))) return;
        mJournal = journal;
        mPendingIds = List.of();
        mPendingOffset = 0;
        mEpoch = boundary != null ? boundary.sourceEpoch() : "unavailable";
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
            StatsLiveEventHub.LiveEvent event = new StatsLiveEventHub.LiveEvent("activity_delta", Map.copyOf(data));
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
            mState = new State(previous.revision() + 1, previous.initialized(), previous.epoch(), previous.sequence(), timeout, enabled);
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
                    request.afterId(), request.limit()), ignored -> new ArrayList<>()).add(request);
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
                for(Catchup request: group) request.result().complete(page);
            }
            catch(RuntimeException exception)
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
            if(!"unavailable".equals(request.epoch()) || request.after() != 0 ||
                request.through() != null && request.through() != 0) return resetPage();
            Map<String,Object> page = new LinkedHashMap<>(resetPage());
            page.put("resync_required", false);
            page.put("server_time_ms", System.currentTimeMillis());
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
        page.put("resync_required", false);
        page.put("server_time_ms", System.currentTimeMillis());
        page.put("traffic_grant_age_out_milliseconds", Math.max(100, mTimeout.getAsInt()));
        return Map.copyOf(page);
    }

    private Map<String,Object> resetPage()
    {
        ActivityCommitJournal journal = mJournalSupplier.get();
        ActivityCommitJournal.Boundary boundary = journal != null ? journal.boundary() : null;
        return Map.of("source_epoch", boundary != null ? boundary.sourceEpoch() : "unavailable",
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
        if(mReader != null) mReader.interrupt();
        Catchup request;
        while((request = mCatchups.poll()) != null)
            request.result().completeExceptionally(new IllegalStateException("Live Activity closed"));
    }
}
