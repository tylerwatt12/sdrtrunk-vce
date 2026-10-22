/* Copyright (C) 2026 Dennis Sheirer. GNU General Public License, version 3 or later. */
package io.github.dsheirer.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One demand-driven web reader: committed history appends and authoritative native affiliations are independent. */
final class P25ActivityLiveService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(P25ActivityLiveService.class);
    static final int TOPIC = 7;
    private static final long POLL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(2);

    record RawSnapshot(Object identity, List<Map<String,Object>> affiliations) {}

    interface Source
    {
        RawSnapshot snapshot();
        Object identity();
        long presentationGeneration();
        long historyGeneration();
        long watermark();
        Map<String,Object> after(long afterId, Long watermarkId);
        /** Transfers fresh public trees to this reader; presenters never mutate returned trees again. */
        List<ObjectNode> presentAffiliations(List<Map<String,Object>> rows);
    }

    record State(long revision, String epoch, long sequence, int timeout, boolean enabled, boolean available,
                 long watermark, long historyGeneration)
    {
        Map<String,Object> payload(String id)
        {
            return Map.of("subscription_id", id, "source_epoch", epoch, "sequence", sequence,
                "server_time_ms", System.currentTimeMillis(), "traffic_grant_age_out_milliseconds", timeout,
                "collection_enabled", enabled, "available", available, "watermark_id", watermark,
                "history_generation", historyGeneration);
        }
    }

    private record RadioKey(String system, String radio)
    {
        Map<String,Object> payload() { return Map.of("radio_system_key", system, "radio_identity_key", radio); }
    }
    private record Row(Map<String,Object> core, ObjectNode value, int bytes) {}
    private record Table(Map<RadioKey,Row> entries, List<ObjectNode> values)
    {
        static Table empty() { return new Table(Map.of(), List.of()); }
    }

    /** All viewers reuse the same complete native dataset and its lazily prepared response. */
    private static final class PublishedState
    {
        final String epoch;
        final long sequence;
        final long captured;
        final Table affiliations;
        final boolean available;
        final boolean enabled;
        final int timeout;
        ApiHttpResponse.PreparedJson prepared;

        PublishedState(String epoch, long sequence, long captured, Table affiliations,
                       boolean available, int timeout, boolean enabled)
        {
            this.epoch = epoch; this.sequence = sequence; this.captured = captured;
            this.affiliations = affiliations; this.available = available; this.timeout = timeout; this.enabled = enabled;
        }

        ApiHttpResponse.PreparedJson seed() throws IOException
        {
            if(prepared == null)
                prepared = ApiHttpResponse.prepareNormalizedData(Map.of("source_epoch", epoch, "sequence", sequence,
                    "affiliations", affiliations.values(), "available", available, "captured_at_ms", captured,
                    "traffic_grant_age_out_milliseconds", timeout, "collection_enabled", enabled));
            return prepared;
        }
    }

    private record Change(boolean removal, Object value, int bytes) {}
    private final Source mSource;
    private final IntSupplier mTimeout;
    private final BooleanSupplier mEnabled;
    private final StatsLiveEventHub mHub;
    private final ArrayBlockingQueue<CompletableFuture<ApiHttpResponse.PreparedJson>> mSeeds = new ArrayBlockingQueue<>(256);
    private volatile boolean mClosed;
    private volatile boolean mReady;
    private volatile long mDemandGeneration;
    private long mPreparedDemand;
    private long mAttemptedDemand;
    private volatile boolean mRefreshRequested;
    private Thread mReader;
    private String mEpoch = UUID.randomUUID().toString();
    private volatile State mState;
    private PublishedState mPublished;
    private Table mAffiliations = Table.empty();
    private Object mIdentity;
    private long mNextPoll;
    private long mLastRefresh;
    private long mPresentation = -1;
    private boolean mNativeFailed;
    private boolean mHistoryFailed;
    private boolean mHistoryInitialized;
    private long mHistoryCursor;
    private long mHistoryGeneration;

    P25ActivityLiveService(Source source, IntSupplier timeout, BooleanSupplier enabled)
    { this(source, timeout, enabled, StatsLiveEventHub.MAXIMUM_BROWSER_SUBSCRIBERS, 128); }

    P25ActivityLiveService(Source source, IntSupplier timeout, BooleanSupplier enabled, int maximumSubscribers, int queueCapacity)
    {
        mSource = source; mTimeout = timeout; mEnabled = enabled;
        mHub = new StatsLiveEventHub(maximumSubscribers, queueCapacity);
        mState = new State(0, mEpoch, 0, Math.max(100, timeout.getAsInt()), enabled.getAsBoolean(), false, 0, 0);
    }

    synchronized StatsLiveEventHub.Subscription subscribe()
    {
        if(mClosed) return null;
        boolean first = !mHub.hasSubscribers();
        var subscriber = mHub.subscribe();
        if(subscriber != null)
        {
            if(first) { mReady = false; mDemandGeneration++; }
            if(mReader == null)
            {
                mReader = new ObserverThreadFactory("p25 shared reader").newThread(this::run);
                mReader.start();
            }
            else LockSupport.unpark(mReader);
        }
        return subscriber;
    }

    State state() { return mState; }
    boolean ready() { return mReady; }
    ApiHttpResponse.PreparedJson seed() { return seed(false); }

    ApiHttpResponse.PreparedJson seed(boolean refresh)
    {
        var request = new CompletableFuture<ApiHttpResponse.PreparedJson>();
        if(mClosed || !mHub.hasSubscribers() || !mSeeds.offer(request))
            throw new StatsApiException(503, "P25 affiliations are busy; retry shortly");
        if(refresh) mRefreshRequested = true;
        LockSupport.unpark(mReader);
        try { return request.get(5, TimeUnit.SECONDS); }
        catch(InterruptedException exception) { Thread.currentThread().interrupt(); request.cancel(false); }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) { request.cancel(false); }
        throw new StatsApiException(503, "P25 affiliations are temporarily unavailable");
    }

    private void run()
    {
        while(!mClosed)
        {
            if(mHub.hasSubscribers())
            {
                boolean refresh = mRefreshRequested && System.nanoTime() - mLastRefresh >= REFRESH_NANOS;
                mRefreshRequested = false;
                if(refresh) mLastRefresh = System.nanoTime();
                long demand = mDemandGeneration;
                boolean fresh = demand != mPreparedDemand;
                if(refresh || demand != mAttemptedDemand || System.nanoTime() >= mNextPoll)
                {
                    mAttemptedDemand = demand;
                    mNextPoll = System.nanoTime() + POLL_NANOS;
                    boolean nativeRead = false;
                    try { nativeRead = pollNative(refresh, fresh); }
                    catch(RuntimeException | IOException exception)
                    {
                        if(!mNativeFailed) mLog.warn("Current P25 affiliations are temporarily unavailable", exception);
                        mNativeFailed = true;
                        if(mPublished != null)
                            install(mPublished.affiliations, false, mPublished.captured, List.of(), false);
                    }
                    try { pollHistory(); }
                    catch(RuntimeException | IOException exception)
                    {
                        if(!mHistoryFailed) mLog.warn("Live saved P25 activity is temporarily unavailable", exception);
                        // Retain the cursor. A later successful read drains every unconsumed committed page.
                        mHistoryFailed = true;
                    }
                    if(nativeRead && demand == mDemandGeneration)
                    {
                        mPreparedDemand = demand;
                        mReady = mPublished != null && mHistoryInitialized;
                    }
                    else if(fresh || demand != mDemandGeneration) mReady = false;
                }
                serveSeeds();
            }
            else failSeeds();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
            if(Thread.interrupted() && mClosed) return;
        }
    }

    private boolean pollNative(boolean refresh, boolean fresh) throws IOException
    {
        RawSnapshot raw = mSource.snapshot();
        if(raw.identity() == null) throw new IllegalStateException("P25 source identity is unavailable");
        boolean replaced = mIdentity != null && !mIdentity.equals(raw.identity());
        long generation = mSource.presentationGeneration();
        Table affiliations = table(raw.affiliations(), replaced ? Table.empty() : mAffiliations,
            refresh || replaced || generation != mPresentation);
        if(!Objects.equals(raw.identity(), mSource.identity()))
            throw new IllegalStateException("P25 source changed during its read");
        if(!mHub.hasSubscribers() || mClosed) return false;
        List<Change> changes = new ArrayList<>();
        if(!replaced && mPublished != null)
        {
            for(var entry: affiliations.entries().entrySet())
            {
                Row old = mAffiliations.entries().get(entry.getKey()), value = entry.getValue();
                if(old != value && (old == null || !old.value().equals(P25ActivityLiveService::compareValues, value.value())))
                    changes.add(new Change(false, value.value(), value.bytes()));
            }
            for(RadioKey id: mAffiliations.entries().keySet())
                if(!affiliations.entries().containsKey(id)) changes.add(new Change(true, id.payload(), 256));
        }
        // Establish the append edge before source_change permits viewers to capture their REST history watermark.
        if(replaced || !mHistoryInitialized || fresh)
        {
            mReady = false;
            long generationBefore = mSource.historyGeneration();
            long watermark = mSource.watermark();
            if(generationBefore != mSource.historyGeneration() || !raw.identity().equals(mSource.identity()))
                throw new IllegalStateException("P25 history changed during initialization");
            mHistoryCursor = watermark; mHistoryGeneration = generationBefore; mHistoryInitialized = true;
        }
        install(affiliations, true, System.currentTimeMillis(), changes, replaced);
        mAffiliations = affiliations; mIdentity = raw.identity(); mPresentation = generation; mNativeFailed = false;
        return true;
    }

    private void pollHistory() throws IOException
    {
        if(mIdentity == null || !mIdentity.equals(mSource.identity())) return;
        long generation = mSource.historyGeneration(), watermark = mSource.watermark();
        if(generation != mSource.historyGeneration() || !mIdentity.equals(mSource.identity())) return;
        if(!mHistoryInitialized)
        {
            mHistoryGeneration = generation; advanceHistoryCursor(watermark); mHistoryInitialized = true;
        }
        else if(generation != mHistoryGeneration || watermark < mHistoryCursor)
        {
            historyReset(watermark, generation);
        }
        while(!mClosed && mHub.hasSubscribers() && mHistoryCursor < watermark)
        {
            Map<String,Object> page = mSource.after(mHistoryCursor, watermark);
            if(generation != mSource.historyGeneration() || !mIdentity.equals(mSource.identity())) return;
            if(Boolean.TRUE.equals(page.get("reset_required")))
            {
                long current = ((Number)page.get("watermark_id")).longValue();
                historyReset(current, generation); return;
            }
            long next = ((Number)page.get("next_after_id")).longValue();
            if(next <= mHistoryCursor || next > watermark)
                throw new IllegalStateException("Saved P25 activity cursor did not advance");
            List<ObjectNode> rows = StatsApiV1Payload.p25VisualizerRows(StatsApiV1Payload.source(page.get("rows")));
            if(!publishHistory(rows, mHistoryCursor, next, watermark)) break;
            // Yield a long catchup to the next native read. The cursor keeps every remaining page pending.
            if(System.nanoTime() >= mNextPoll) break;
        }
        mHistoryFailed = false;
    }

    private void historyReset(long watermark, long generation) throws IOException
    {
        var frame = StatsLiveEventHub.LiveEvent.prepared("activity_reset",
            Map.of("source_epoch", mEpoch, "watermark_id", watermark, "history_generation", generation), TOPIC);
        mHistoryGeneration = generation;
        advanceHistoryCursor(watermark);
        mHub.publish(frame);
    }

    private void advanceHistoryCursor(long cursor)
    {
        mHistoryCursor = cursor;
        State state = mState;
        mState = new State(state.revision(), state.epoch(), state.sequence(), state.timeout(), state.enabled(),
            state.available(), cursor, mHistoryGeneration);
    }

    private boolean publishHistory(List<ObjectNode> rows, long after, long next, long watermark) throws IOException
    {
        List<ObjectNode> part = new ArrayList<>();
        int bytes = 1024;
        for(ObjectNode row: rows)
        {
            int size = ApiHttpResponse.encodeNormalizedPayload(row).length;
            if(size + 1024 > LiveMultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC)
            {
                // The full REST history remains available; recover that selected scope rather than omit this row.
                historyReset(watermark, mHistoryGeneration); return false;
            }
            if(bytes + size > LiveMultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC - 1024 && !part.isEmpty())
            {
                long end = part.getLast().path("id").longValue();
                append(part, after, end, watermark); after = end; part = new ArrayList<>(); bytes = 1024;
            }
            part.add(row); bytes += size + 1;
        }
        // Empty filtered spans still advance continuity, so a viewer never mistakes them for missing frames.
        append(part, after, next, watermark);
        return true;
    }

    private void append(List<ObjectNode> rows, long after, long next, long watermark) throws IOException
    {
        var frame = StatsLiveEventHub.LiveEvent.prepared("activity_append", Map.of("source_epoch", mEpoch,
            "rows", List.copyOf(rows), "after_id", after, "next_after_id", next, "watermark_id", watermark,
            "history_generation", mHistoryGeneration), TOPIC);
        frame.frame(TOPIC).bytes(true); advanceHistoryCursor(next); mHub.publish(frame);
    }

    private Table table(List<Map<String,Object>> raw, Table previous, boolean names) throws IOException
    {
        Map<RadioKey,Map<String,Object>> core = new LinkedHashMap<>();
        List<Map<String,Object>> changed = new ArrayList<>();
        for(var value: raw)
        {
            RadioKey id = key(value, "source_identity_key");
            if(core.put(id, value) != null) throw new IllegalStateException("Duplicate P25 radio identity");
            Row old = previous.entries().get(id);
            if(names || old == null || !old.core().equals(value)) changed.add(value);
        }
        Map<RadioKey,ObjectNode> projected = new LinkedHashMap<>();
        for(var value: mSource.presentAffiliations(changed))
            if(projected.put(key(value, "radio_identity_key"), value) != null)
                throw new IllegalStateException("Duplicate P25 projection");
        if(projected.size() != changed.size()) throw new IllegalStateException("Incomplete P25 projection");
        Map<RadioKey,Row> next = new LinkedHashMap<>();
        List<ObjectNode> values = new ArrayList<>();
        for(var entry: core.entrySet())
        {
            Row value = previous.entries().get(entry.getKey());
            var replacement = projected.get(entry.getKey());
            if(replacement != null)
                value = new Row(Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())), replacement,
                    ApiHttpResponse.encodeNormalizedPayload(replacement).length);
            if(value == null) throw new IllegalStateException("P25 projection changed its identity");
            next.put(entry.getKey(), value); values.add(value.value());
        }
        return new Table(Collections.unmodifiableMap(next), List.copyOf(values));
    }

    private static RadioKey key(Map<String,Object> row, String radioField)
    {
        if(!(row.get("radio_system_key") instanceof String system) || !(row.get(radioField) instanceof String radio))
            throw new IllegalStateException("Invalid native P25 radio key");
        return new RadioKey(system, radio);
    }

    private static RadioKey key(ObjectNode row, String radioField)
    {
        JsonNode system = row.get("radio_system_key"), radio = row.get(radioField);
        if(system == null || !system.isTextual() || radio == null || !radio.isTextual())
            throw new IllegalStateException("Invalid public P25 radio key");
        return new RadioKey(system.textValue(), radio.textValue());
    }

    private static int compareValues(JsonNode left, JsonNode right)
    {
        return left.isIntegralNumber() && right.isIntegralNumber() ?
            left.bigIntegerValue().compareTo(right.bigIntegerValue()) : left.equals(right) ? 0 : 1;
    }

    private void install(Table affiliations, boolean available, long captured, List<Change> changes, boolean replaced)
    {
        int timeout = Math.max(100, mTimeout.getAsInt());
        boolean enabled = mEnabled.getAsBoolean();
        PublishedState old = mPublished;
        if(old != null && !replaced && changes.isEmpty() && old.available == available && old.timeout == timeout && old.enabled == enabled)
        {
            if(mState.watermark() != mHistoryCursor || mState.historyGeneration() != mHistoryGeneration)
                advanceHistoryCursor(mHistoryCursor);
            return;
        }
        long sequence = old != null ? old.sequence + 1 : 1;
        String epoch = replaced ? UUID.randomUUID().toString() : mEpoch;
        var next = new PublishedState(epoch, sequence, captured, affiliations, available, timeout, enabled);
        List<StatsLiveEventHub.LiveEvent> frames;
        try { frames = old == null || replaced ? List.of() : frames(next, old.sequence, changes, mHistoryGeneration); }
        catch(IOException exception) { throw new IllegalStateException("Unable to prepare P25 affiliation frames", exception); }
        mPublished = next; mEpoch = epoch;
        State state = mState;
        boolean boundary = !epoch.equals(state.epoch()) || state.timeout() != timeout || state.enabled() != enabled;
        mState = new State(state.revision() + (boundary ? 1 : 0), epoch, sequence, timeout, enabled, available,
            mHistoryCursor, mHistoryGeneration);
        for(var frame: frames) mHub.publish(frame);
    }

    private static List<StatsLiveEventHub.LiveEvent> frames(PublishedState state, long base, List<Change> changes,
                                                          long historyGeneration) throws IOException
    {
        List<List<Change>> parts = new ArrayList<>();
        List<Change> current = new ArrayList<>(); int bytes = 1024;
        for(Change change: changes)
        {
            if(change.bytes() + 1024 > LiveMultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC) return resync(state, historyGeneration);
            if(current.size() == 100 || bytes + change.bytes() > LiveMultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC - 1024)
            { parts.add(current); current = new ArrayList<>(); bytes = 1024; }
            current.add(change); bytes += change.bytes() + 1;
        }
        if(!current.isEmpty() || parts.isEmpty()) parts.add(current);
        if(parts.size() > 256) return resync(state, historyGeneration);
        List<StatsLiveEventHub.LiveEvent> frames = new ArrayList<>();
        for(int part = 0; part < parts.size(); part++)
        {
            List<Object> affiliations = new ArrayList<>(), radios = new ArrayList<>();
            for(Change change: parts.get(part)) (change.removal() ? radios : affiliations).add(change.value());
            var data = Map.of("source_epoch", state.epoch, "base_sequence", base, "sequence", state.sequence,
                "affiliations", affiliations, "removed_radio_keys", radios, "available", state.available,
                "part", part, "parts", parts.size(), "collection_enabled", state.enabled,
                "traffic_grant_age_out_milliseconds", state.timeout);
            var frame = StatsLiveEventHub.LiveEvent.prepared("p25_state_delta", data, TOPIC);
            if(frame.frame(TOPIC).bytes(false).length > LiveMultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC) return resync(state, historyGeneration);
            frame.frame(TOPIC).bytes(true); frames.add(frame);
        }
        return frames;
    }

    private static List<StatsLiveEventHub.LiveEvent> resync(PublishedState state, long historyGeneration) throws IOException
    {
        return List.of(StatsLiveEventHub.LiveEvent.prepared("resync_required", Map.of("reason", "state_frame_budget",
            "source_epoch", state.epoch, "sequence", state.sequence, "history_generation", historyGeneration), TOPIC));
    }

    private void serveSeeds()
    {
        var requests = new ArrayList<CompletableFuture<ApiHttpResponse.PreparedJson>>(); mSeeds.drainTo(requests);
        if(requests.isEmpty()) return;
        try
        {
            if(mPublished == null || !mReady || mClosed || !mHub.hasSubscribers())
                throw new IOException("No complete P25 affiliations");
            var prepared = mPublished.seed();
            for(var request: requests) request.complete(prepared);
        }
        catch(IOException exception) { for(var request: requests) request.completeExceptionally(exception); }
    }

    private void failSeeds()
    {
        CompletableFuture<ApiHttpResponse.PreparedJson> request;
        while((request = mSeeds.poll()) != null) request.completeExceptionally(new IOException("P25 reader is inactive"));
    }

    int subscriberCount() { return mHub.subscriberCount(); }
    long droppedEvents() { return mHub.droppedEvents(); }
    long rejectedSubscriptions() { return mHub.rejectedSubscriptions(); }

    @Override public synchronized void close()
    {
        mClosed = true; mHub.close(); failSeeds();
        if(mReader != null) { mReader.interrupt(); LockSupport.unpark(mReader); }
    }
}
