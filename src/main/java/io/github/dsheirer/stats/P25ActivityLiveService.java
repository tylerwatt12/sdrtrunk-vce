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
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One demand-driven snapshot/diff reader; receiver producers have no dependency on this service. */
final class P25ActivityLiveService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(P25ActivityLiveService.class);
    static final int TOPIC = 7;
    static final int SEED_LIMIT = 500;
    static final int MAXIMUM_AFFILIATIONS = 10_000;
    private static final int MAXIMUM_SEED_BYTES = 12 * 1024 * 1024;
    private static final long POLL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(2);

    record RawSnapshot(Object identity, List<Map<String,Object>> rows, List<Map<String,Object>> affiliations) {}

    interface Source
    {
        RawSnapshot snapshot();
        Object identity();
        long presentationGeneration();
        /** Transfers fresh public trees to this reader; presenters never mutate returned trees again. */
        List<ObjectNode> presentRows(List<Map<String,Object>> rows);
        List<ObjectNode> presentAffiliations(List<Map<String,Object>> rows);
    }

    record State(long revision, String epoch, long sequence, int timeout, boolean enabled,
                 boolean available, boolean affiliationsTruncated)
    {
        Map<String,Object> payload(String id)
        {
            return Map.of("subscription_id", id, "source_epoch", epoch, "sequence", sequence,
                "server_time_ms", System.currentTimeMillis(), "traffic_grant_age_out_milliseconds", timeout,
                "collection_enabled", enabled, "available", available, "affiliations_truncated", affiliationsTruncated);
        }
    }

    private record RadioKey(String system, String radio)
    {
        Map<String,Object> payload()
        {
            return Map.of("radio_system_key", system, "radio_identity_key", radio);
        }
    }

    private record Row(Map<String,Object> core, ObjectNode value, int bytes) {}

    private record Table<K>(Map<K,Row> entries, List<ObjectNode> values, long bytes)
    {
        static <K> Table<K> empty()
        {
            return new Table<>(Map.of(), List.of(), 0);
        }
    }

    /** Reader-owned immutable dataset; every viewer shares its one lazily prepared seed. */
    private static final class PublishedState
    {
        final String epoch;
        final long sequence;
        final long captured;
        final Table<Long> rows;
        final Table<RadioKey> affiliations;
        final boolean available;
        final boolean affiliationsTruncated;
        final boolean enabled;
        final int timeout;
        ApiHttpResponse.PreparedJson prepared;

        PublishedState(String epoch, long sequence, long captured, Table<Long> rows, Table<RadioKey> affiliations,
                       boolean available, int timeout, boolean enabled, boolean affiliationsTruncated)
        {
            this.epoch = epoch;
            this.sequence = sequence;
            this.captured = captured;
            this.rows = rows;
            this.affiliations = affiliations;
            this.available = available;
            this.affiliationsTruncated = affiliationsTruncated;
            this.timeout = timeout;
            this.enabled = enabled;
        }

        ApiHttpResponse.PreparedJson seed() throws IOException
        {
            if(prepared == null)
            {
                prepared = ApiHttpResponse.prepareNormalizedData(Map.of("source_epoch", epoch, "sequence", sequence,
                    "rows", rows.values(), "affiliations", affiliations.values(), "available", available,
                    "captured_at_ms", captured, "traffic_grant_age_out_milliseconds", timeout,
                    "collection_enabled", enabled, "affiliations_truncated", affiliationsTruncated));
                if(prepared.identity().length > MAXIMUM_SEED_BYTES)
                {
                    throw new IOException("P25 state exceeds its byte budget");
                }
            }
            return prepared;
        }
    }

    private record Change(int kind, Object value, int bytes) {}
    private final Source mSource;
    private final IntSupplier mTimeout;
    private final BooleanSupplier mEnabled;
    private final StatsLiveEventHub mHub;
    private final ArrayBlockingQueue<CompletableFuture<ApiHttpResponse.PreparedJson>> mSeeds = new ArrayBlockingQueue<>(256);
    private volatile boolean mClosed;
    private volatile boolean mFreshRequired;
    private volatile boolean mRefreshRequested;
    private Thread mReader;
    private String mEpoch = UUID.randomUUID().toString();
    private volatile State mState;
    private PublishedState mPublished;
    private Table<Long> mRows = Table.empty();
    private Table<RadioKey> mAffiliations = Table.empty();
    private Object mIdentity;
    private long mNextPoll;
    private long mLastRefresh;
    private long mPresentation = -1;
    private boolean mFailed;

    P25ActivityLiveService(Source source, IntSupplier timeout, BooleanSupplier enabled)
    {
        this(source, timeout, enabled, StatsLiveEventHub.MAXIMUM_BROWSER_SUBSCRIBERS, 128);
    }

    P25ActivityLiveService(Source source, IntSupplier timeout, BooleanSupplier enabled,
                           int maximumSubscribers, int queueCapacity)
    {
        mSource = source;
        mTimeout = timeout;
        mEnabled = enabled;
        mHub = new StatsLiveEventHub(maximumSubscribers, queueCapacity);
        mState = new State(0, mEpoch, 0, Math.max(100, timeout.getAsInt()), enabled.getAsBoolean(), false, false);
    }

    synchronized StatsLiveEventHub.Subscription subscribe()
    {
        if(mClosed)
        {
            return null;
        }
        boolean first = !mHub.hasSubscribers();
        var subscriber = mHub.subscribe();
        if(subscriber != null)
        {
            if(first)
            {
                mFreshRequired = true;
            }
            if(mReader == null)
            {
                mReader = new ObserverThreadFactory("p25 shared state reader").newThread(this::run);
                mReader.start();
            }
            else
            {
                LockSupport.unpark(mReader);
            }
        }
        return subscriber;
    }

    State state()
    {
        return mState;
    }

    ApiHttpResponse.PreparedJson seed()
    {
        return seed(false);
    }

    ApiHttpResponse.PreparedJson seed(boolean refresh)
    {
        var request = new CompletableFuture<ApiHttpResponse.PreparedJson>();
        if(mClosed || !mHub.hasSubscribers() || !mSeeds.offer(request))
        {
            throw new StatsApiException(503, "P25 state is busy; retry shortly");
        }
        if(refresh)
        {
            mRefreshRequested = true;
        }
        LockSupport.unpark(mReader);
        try
        {
            return request.get(5, TimeUnit.SECONDS);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            request.cancel(false);
        }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception)
        {
            request.cancel(false);
        }
        throw new StatsApiException(503, "P25 state is temporarily unavailable");
    }

    private void run()
    {
        while(!mClosed)
        {
            if(mHub.hasSubscribers())
            {
                boolean refresh = mRefreshRequested && System.nanoTime() - mLastRefresh >= REFRESH_NANOS;
                mRefreshRequested = false;
                if(refresh)
                {
                    mLastRefresh = System.nanoTime();
                }
                if(refresh || mFreshRequired || System.nanoTime() >= mNextPoll)
                {
                    mNextPoll = System.nanoTime() + POLL_NANOS;
                    try
                    {
                        poll(refresh);
                    }
                    catch(RuntimeException | IOException exception)
                    {
                        if(!mFailed)
                        {
                            mLog.warn("Shared P25 state is temporarily unavailable", exception);
                        }
                        mFailed = true;
                        if(mPublished != null)
                        {
                            install(mPublished.rows, mPublished.affiliations, false, mPublished.captured, List.of(), false,
                                mPublished.affiliationsTruncated);
                        }
                    }
                    mFreshRequired = false;
                }
                serveSeeds();
            }
            else
            {
                failSeeds();
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
            if(Thread.interrupted() && mClosed)
            {
                return;
            }
        }
    }

    private void poll(boolean refresh) throws IOException
    {
        RawSnapshot raw = mSource.snapshot();
        if(raw.identity() == null || raw.rows().size() > SEED_LIMIT || raw.affiliations().size() > MAXIMUM_AFFILIATIONS + 1)
        {
            throw new IllegalStateException("P25 snapshot exceeded its row budget");
        }
        boolean truncated = raw.affiliations().size() > MAXIMUM_AFFILIATIONS;
        List<Map<String,Object>> nativeRows = truncated ? raw.affiliations().subList(0, MAXIMUM_AFFILIATIONS) : raw.affiliations();
        boolean replaced = mIdentity != null && !mIdentity.equals(raw.identity());
        long generation = mSource.presentationGeneration();
        boolean names = refresh || replaced || generation != mPresentation;
        Table<Long> rows = table(raw.rows(), replaced ? Table.empty() : mRows, names, mSource::presentRows,
            row -> ((Number)row.get("id")).longValue(), row -> {
                JsonNode id = row.required("id");
                if(!id.isNumber()) throw new IllegalStateException("Invalid public P25 activity key");
                return id.longValue();
            });
        Table<RadioKey> affiliations = table(nativeRows, replaced ? Table.empty() : mAffiliations, names,
            mSource::presentAffiliations, row -> key(row, "source_identity_key"), row -> key(row, "radio_identity_key"));
        if(rows.bytes() + affiliations.bytes() + rows.entries().size() + affiliations.entries().size() + 4096 > MAXIMUM_SEED_BYTES)
        {
            throw new IllegalStateException("P25 snapshot exceeded its byte budget");
        }
        if(!Objects.equals(raw.identity(), mSource.identity()))
        {
            throw new IllegalStateException("P25 source changed during its read");
        }
        if(!mHub.hasSubscribers() || mClosed)
        {
            return;
        }
        List<Change> changes = new ArrayList<>();
        if(!replaced && mPublished != null)
        {
            diff(mRows, rows, 0, 1, changes);
            diff(mAffiliations, affiliations, 2, 3, changes);
        }
        install(rows, affiliations, true, System.currentTimeMillis(), changes, replaced, truncated);
        // Commit reader caches only after the complete public revision and its frames were prepared.
        mRows = rows;
        mAffiliations = affiliations;
        mIdentity = raw.identity();
        mPresentation = generation;
        mFailed = false;
    }

    private static RadioKey key(Map<String,Object> row, String radioField)
    {
        if(!(row.get("radio_system_key") instanceof String system) || !(row.get(radioField) instanceof String radio))
        {
            throw new IllegalStateException("Invalid native P25 radio key");
        }
        return new RadioKey(system, radio);
    }

    private static RadioKey key(ObjectNode row, String radioField)
    {
        JsonNode system = row.get("radio_system_key");
        JsonNode radio = row.get(radioField);
        if(system == null || !system.isTextual() || radio == null || !radio.isTextual())
            throw new IllegalStateException("Invalid public P25 radio key");
        return new RadioKey(system.textValue(), radio.textValue());
    }

    private static <K> Table<K> table(List<Map<String,Object>> raw, Table<K> previous, boolean names,
                                    Function<List<Map<String,Object>>,List<ObjectNode>> present,
                                    Function<Map<String,Object>,K> coreKey, Function<ObjectNode,K> publicKey)
        throws IOException
    {
        Map<K,Map<String,Object>> core = new LinkedHashMap<>();
        List<Map<String,Object>> changed = new ArrayList<>();
        for(var value: raw)
        {
            K id = coreKey.apply(value);
            if(id == null || core.put(id, value) != null)
            {
                throw new IllegalStateException("Duplicate P25 identity");
            }
            Row old = previous.entries().get(id);
            if(names || old == null || !old.core().equals(value))
            {
                changed.add(value);
            }
        }
        Map<K,ObjectNode> projected = new LinkedHashMap<>();
        for(var value: present.apply(changed))
        {
            if(projected.put(publicKey.apply(value), value) != null)
            {
                throw new IllegalStateException("Duplicate P25 projection");
            }
        }
        if(projected.size() != changed.size())
        {
            throw new IllegalStateException("Incomplete P25 projection");
        }
        Map<K,Row> next = new LinkedHashMap<>();
        List<ObjectNode> values = new ArrayList<>();
        long bytes = 0;
        for(var entry: core.entrySet())
        {
            Row value = previous.entries().get(entry.getKey());
            var replacement = projected.get(entry.getKey());
            if(replacement != null)
            {
                value = new Row(Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())), replacement,
                    ApiHttpResponse.encodeNormalizedPayload(replacement).length);
            }
            if(value == null)
            {
                throw new IllegalStateException("P25 projection changed its identity");
            }
            next.put(entry.getKey(), value);
            values.add(value.value());
            bytes += value.bytes();
        }
        return new Table<>(Collections.unmodifiableMap(next), List.copyOf(values), bytes);
    }

    private static <K> void diff(Table<K> before, Table<K> after, int upsert, int removal, List<Change> changes)
    {
        for(var entry: after.entries().entrySet())
        {
            Row old = before.entries().get(entry.getKey());
            Row value = entry.getValue();
            if(old == null || !old.value().equals(P25ActivityLiveService::compareValues, value.value()))
            {
                changes.add(new Change(upsert, value.value(), value.bytes()));
            }
        }
        for(K id: before.entries().keySet())
        {
            if(!after.entries().containsKey(id))
            {
                changes.add(new Change(removal, id instanceof RadioKey radio ? radio.payload() : id, 256));
            }
        }
    }

    /** Integral node widths do not change a public JSON number after the former Map conversion. */
    private static int compareValues(JsonNode left, JsonNode right)
    {
        return left.isIntegralNumber() && right.isIntegralNumber() ?
            left.bigIntegerValue().compareTo(right.bigIntegerValue()) : left.equals(right) ? 0 : 1;
    }

    private void install(Table<Long> rows, Table<RadioKey> affiliations, boolean available, long captured,
                         List<Change> changes, boolean replaced, boolean truncated)
    {
        int timeout = Math.max(100, mTimeout.getAsInt());
        boolean enabled = mEnabled.getAsBoolean();
        PublishedState old = mPublished;
        if(old != null && !replaced && changes.isEmpty() && old.available == available && old.timeout == timeout &&
            old.enabled == enabled && old.affiliationsTruncated == truncated)
        {
            return;
        }
        long sequence = old != null ? old.sequence + 1 : 1;
        String epoch = replaced ? UUID.randomUUID().toString() : mEpoch;
        var next = new PublishedState(epoch, sequence, captured, rows, affiliations, available, timeout, enabled, truncated);
        List<StatsLiveEventHub.LiveEvent> frames;
        try
        {
            frames = old == null || replaced ? List.of() : frames(next, old.sequence, changes);
        }
        catch(IOException exception)
        {
            throw new IllegalStateException("Unable to prepare bounded P25 state frames", exception);
        }
        mPublished = next;
        mEpoch = epoch;
        State state = mState;
        boolean boundary = !epoch.equals(state.epoch()) || state.timeout() != timeout || state.enabled() != enabled;
        mState = new State(state.revision() + (boundary ? 1 : 0), epoch, sequence, timeout, enabled, available, truncated);
        // A seed requested after any broadcast observes this exact or a later immutable dataset.
        for(var frame: frames)
        {
            mHub.publish(frame);
        }
    }

    private static List<StatsLiveEventHub.LiveEvent> frames(PublishedState state, long base, List<Change> changes)
        throws IOException
    {
        List<List<Change>> parts = new ArrayList<>();
        List<Change> current = new ArrayList<>();
        int bytes = 1024;
        for(Change change: changes)
        {
            if(change.bytes() + 1024 > LiveMultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC)
            {
                return resync(state);
            }
            if(current.size() == 100 ||
                bytes + change.bytes() > LiveMultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC - 1024)
            {
                parts.add(current);
                current = new ArrayList<>();
                bytes = 1024;
            }
            current.add(change);
            bytes += change.bytes() + 1;
        }
        if(!current.isEmpty() || parts.isEmpty())
        {
            parts.add(current);
        }
        if(parts.size() > 256)
        {
            return resync(state);
        }
        List<StatsLiveEventHub.LiveEvent> frames = new ArrayList<>();
        for(int part = 0; part < parts.size(); part++)
        {
            List<Object> rows = new ArrayList<>();
            List<Object> removed = new ArrayList<>();
            List<Object> affiliations = new ArrayList<>();
            List<Object> radios = new ArrayList<>();
            for(Change change: parts.get(part))
            {
                switch(change.kind())
                {
                    case 0 -> rows.add(change.value());
                    case 1 -> removed.add(change.value());
                    case 2 -> affiliations.add(change.value());
                    case 3 -> radios.add(change.value());
                    default -> throw new IllegalStateException("Unknown P25 change type");
                }
            }
            Map<String,Object> data = new LinkedHashMap<>(Map.of("source_epoch", state.epoch, "base_sequence", base,
                "sequence", state.sequence, "rows", rows, "removed_ids", removed, "affiliations", affiliations,
                "removed_radio_keys", radios, "available", state.available, "part", part, "parts", parts.size()));
            data.put("affiliations_truncated", state.affiliationsTruncated);
            data.put("collection_enabled", state.enabled);
            data.put("traffic_grant_age_out_milliseconds", state.timeout);
            var frame = StatsLiveEventHub.LiveEvent.prepared("p25_state_delta", Map.copyOf(data), TOPIC);
            if(frame.frame(TOPIC).bytes(false).length > LiveMultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC)
            {
                return resync(state);
            }
            frame.frame(TOPIC).bytes(true);
            frames.add(frame);
        }
        return frames;
    }

    private static List<StatsLiveEventHub.LiveEvent> resync(PublishedState state) throws IOException
    {
        return List.of(StatsLiveEventHub.LiveEvent.prepared("resync_required", Map.of("reason", "state_frame_budget",
            "source_epoch", state.epoch, "sequence", state.sequence), TOPIC));
    }

    private void serveSeeds()
    {
        var requests = new ArrayList<CompletableFuture<ApiHttpResponse.PreparedJson>>();
        mSeeds.drainTo(requests);
        if(requests.isEmpty())
        {
            return;
        }
        try
        {
            if(mPublished == null || mFreshRequired || mClosed || !mHub.hasSubscribers())
            {
                throw new IOException("No complete P25 snapshot");
            }
            var prepared = mPublished.seed();
            for(var request: requests)
            {
                request.complete(prepared);
            }
        }
        catch(IOException exception)
        {
            for(var request: requests)
            {
                request.completeExceptionally(exception);
            }
        }
    }

    private void failSeeds()
    {
        CompletableFuture<ApiHttpResponse.PreparedJson> request;
        while((request = mSeeds.poll()) != null)
        {
            request.completeExceptionally(new IOException("No P25 viewers"));
        }
    }

    @Override
    public synchronized void close()
    {
        mClosed = true;
        mHub.close();
        failSeeds();
        if(mReader != null)
        {
            LockSupport.unpark(mReader);
        }
    }
}
