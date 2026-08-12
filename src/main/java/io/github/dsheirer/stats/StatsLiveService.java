/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight owner of live Systems and committed activity events for the Stats Server.
 */
final class StatsLiveService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(StatsLiveService.class);
    private static final String CHANNEL_ACTIVITY_CONSUMER = "stats-web-live";
    private static final int MAXIMUM_LIVE_SUBSCRIBERS = 32;
    private static final int LIVE_SUBSCRIBER_QUEUE_CAPACITY = 256;
    static final int EVENT_QUEUE_CAPACITY = 64;
    private static final int RAW_EVENT_QUEUE_CAPACITY = 64;
    static final int MAXIMUM_LIVE_TABLES = 128;
    static final int MAXIMUM_ROWS_PER_TABLE = 256;
    static final int MAXIMUM_TOTAL_LIVE_ROWS = 2_048;
    static final int MAXIMUM_SYSTEM_SNAPSHOT_BYTES = 1024 * 1024;
    private static final int MAXIMUM_LIVE_TEXT_LENGTH = 256;
    private static final int MAXIMUM_LIVE_TAGS = 16;
    private static final int MAXIMUM_LIVE_TAG_LENGTH = 64;
    private final StatsWebDatabase mDatabase;
    private final ChannelActivityModel mActivityModel;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final Consumer<Object> mRawProjectionObserver;
    private final Object mDemandLock = new Object();
    private final StatsLiveEventHub mSystemsHub =
        new StatsLiveEventHub(MAXIMUM_LIVE_SUBSCRIBERS, LIVE_SUBSCRIBER_QUEUE_CAPACITY);
    private final StatsLiveEventHub mActivityHub =
        new StatsLiveEventHub(MAXIMUM_LIVE_SUBSCRIBERS, LIVE_SUBSCRIBER_QUEUE_CAPACITY);
    private final Map<String,Map<String,Object>> mTables = new ConcurrentHashMap<>();
    private final AtomicLong mSystemsRevision = new AtomicLong();
    private final Object mEncodedSnapshotLock = new Object();
    private final ExecutorService mActivityCommitExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY), new NamingThreadFactory("stats live events"),
        new ThreadPoolExecutor.AbortPolicy());
    private final BoundedMpscReferenceQueue<Object> mRawEventQueue =
        new BoundedMpscReferenceQueue<>(RAW_EVENT_QUEUE_CAPACITY);
    private final AtomicLong mDroppedRawEventCount = new AtomicLong();
    private final AtomicBoolean mActivityResyncNeeded = new AtomicBoolean();
    private final AtomicBoolean mActivityResetPending = new AtomicBoolean();
    private final AtomicLong mRunGeneration = new AtomicLong();
    private final Listener<ChannelActivityEvent> mChannelActivityListener = this::receiveChannelActivity;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private volatile boolean mRawWorkerRunning;
    private volatile Thread mRawWorker;
    private volatile boolean mTablesTruncated;
    private volatile int mRetainedActivityRows;
    private volatile int mSystemsDemand;
    private volatile long mAppliedActivitySourceRevision;
    private volatile EncodedSnapshot mEncodedSystemSnapshot;

    StatsLiveService(StatsWebDatabase database, ChannelProcessingManager channelProcessingManager)
    {
        this(database, channelProcessingManager, null);
    }

    StatsLiveService(StatsWebDatabase database, ChannelProcessingManager channelProcessingManager,
                     Consumer<Object> rawProjectionObserver)
    {
        mDatabase = database;
        mChannelProcessingManager = channelProcessingManager;
        mActivityModel = channelProcessingManager != null ? channelProcessingManager.getChannelActivityModel() : null;
        mRawProjectionObserver = rawProjectionObserver != null ? rawProjectionObserver : ignored -> {};
    }

    void start()
    {
        synchronized(mDemandLock)
        {
            if(mRunning.compareAndSet(false, true))
            {
                mRunGeneration.incrementAndGet();
            }
        }
    }

    void stop()
    {
        synchronized(mDemandLock)
        {
            if(mRunning.compareAndSet(true, false))
            {
                mRunGeneration.incrementAndGet();
            }

            //Closing the reusable hubs invokes each Systems subscription's exactly-once demand release hook.
            mSystemsHub.close();
            mActivityHub.close();
            mActivityResetPending.set(false);
            releaseAllSystemsDemand();
            resetSystemsState();
        }
    }

    private synchronized void resetSystemsState()
    {
        mTables.clear();
        mTablesTruncated = false;
        mRetainedActivityRows = 0;
        mSystemsRevision.incrementAndGet();
        mEncodedSystemSnapshot = null;
    }

    void activityCommitted(List<Long> rowIds)
    {
        if(rowIds != null && !rowIds.isEmpty() && mActivityHub.hasSubscribers())
        {
            List<Long> committed = rowIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(StatsWebDatabase.MAXIMUM_ACTIVITY_EVENT_BATCH)
                .toList();

            if(committed.isEmpty())
            {
                return;
            }

            long generation = mRunGeneration.get();
            executeActivityCommit(() -> {
                if(!isCurrentRun(generation))
                {
                    return;
                }

                for(Map<String,Object> row: mDatabase.activityByIds(committed))
                {
                    if(!isCurrentRun(generation))
                    {
                        return;
                    }

                    mActivityHub.publish("activity", row);
                }
            });
        }
    }

    StatsLiveEventHub.Subscription subscribeSystems()
    {
        synchronized(mDemandLock)
        {
            if(!mRunning.get())
            {
                return null;
            }

            StatsLiveEventHub.Subscription subscription = mSystemsHub.subscribe(event -> true,
                this::releaseSystemsDemand);

            if(subscription == null)
            {
                return null;
            }

            try
            {
                acquireSystemsDemand();
                return subscription;
            }
            catch(RuntimeException exception)
            {
                subscription.close();
                throw exception;
            }
        }
    }

    StatsLiveEventHub.Subscription subscribeActivity(Predicate<StatsLiveEventHub.LiveEvent> filter)
    {
        synchronized(mDemandLock)
        {
            return mRunning.get() ? mActivityHub.subscribe(filter) : null;
        }
    }

    private void acquireSystemsDemand()
    {
        if(++mSystemsDemand > 1)
        {
            return;
        }

        try
        {
            if(mChannelProcessingManager != null)
            {
                mChannelProcessingManager.setChannelActivityEnabled(CHANNEL_ACTIVITY_CONSUMER, true);
            }

            if(mActivityModel != null)
            {
                mActivityModel.addActivityListener(mChannelActivityListener);
                mActivityResyncNeeded.set(true);
            }

            startRawWorker();
        }
        catch(RuntimeException exception)
        {
            mSystemsDemand = 0;
            releaseAllSystemsDemand();
            throw exception;
        }
    }

    private void releaseSystemsDemand()
    {
        synchronized(mDemandLock)
        {
            if(mSystemsDemand <= 0 || --mSystemsDemand > 0)
            {
                return;
            }

            releaseAllSystemsDemand();
            resetSystemsState();
        }
    }

    private void releaseAllSystemsDemand()
    {
        mSystemsDemand = 0;

        if(mActivityModel != null)
        {
            mActivityModel.removeActivityListener(mChannelActivityListener);
        }

        if(mChannelProcessingManager != null)
        {
            mChannelProcessingManager.setChannelActivityEnabled(CHANNEL_ACTIVITY_CONSUMER, false);
        }

        stopRawWorker();
        mActivityResyncNeeded.set(false);
        mAppliedActivitySourceRevision = 0;
    }

    void receiveChannelActivity(ChannelActivityEvent event)
    {
        offerRawEvent(event);
    }

    Map<String,Object> snapshot()
    {
        return snapshot(MAXIMUM_TOTAL_LIVE_ROWS);
    }

    private Map<String,Object> snapshot(int maximumRows)
    {
        List<Map<String,Object>> tables = new ArrayList<>(mTables.values());
        tables.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault("table_id", ""))));
        List<Map<String,Object>> bounded = new ArrayList<>(tables.size());
        int rowsIncluded = 0;
        long rowsTotal = 0;

        for(Map<String,Object> table: tables)
        {
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> rows = table.get("rows") instanceof List<?> values ?
                (List<Map<String,Object>>)(List<?>)values : List.of();
            int originalTotal = table.get("rows_total") instanceof Number number ?
                Math.max(0, number.intValue()) : rows.size();
            int available = Math.max(0, maximumRows - rowsIncluded);
            int included = Math.min(rows.size(), available);
            LinkedHashMap<String,Object> copy = new LinkedHashMap<>(table);
            copy.put("rows", included == rows.size() ? rows : List.copyOf(rows.subList(0, included)));
            copy.put("rows_truncated", originalTotal > included);
            copy.put("rows_omitted", Math.max(0, originalTotal - included));
            bounded.add(Map.copyOf(copy));
            rowsIncluded += included;
            rowsTotal += originalTotal;
        }

        LinkedHashMap<String,Object> response = new LinkedHashMap<>();
        response.put("tables", List.copyOf(bounded));
        response.put("table_limit", MAXIMUM_LIVE_TABLES);
        response.put("row_limit_per_table", MAXIMUM_ROWS_PER_TABLE);
        response.put("row_limit_total", MAXIMUM_TOTAL_LIVE_ROWS);
        response.put("encoded_byte_limit", MAXIMUM_SYSTEM_SNAPSHOT_BYTES);
        response.put("tables_included", bounded.size());
        response.put("tables_omitted_at_least", mTablesTruncated ? 1 : 0);
        response.put("rows_total", rowsTotal);
        response.put("rows_included", rowsIncluded);
        response.put("rows_omitted", Math.max(0L, rowsTotal - rowsIncluded));
        response.put("truncated", mTablesTruncated || rowsTotal > rowsIncluded);
        response.put("revision", mSystemsRevision.get());
        return Map.copyOf(response);
    }

    /**
     * Encodes and caches the initial channel-activity snapshot once per revision.  A binary search lowers the global
     * row allowance when necessary, so the actual wire payload never exceeds the byte budget.
     */
    byte[] encodedSnapshot() throws IOException
    {
        long revision = mSystemsRevision.get();
        EncodedSnapshot cached = mEncodedSystemSnapshot;

        if(cached != null && cached.revision() == revision)
        {
            return cached.payload();
        }

        synchronized(mEncodedSnapshotLock)
        {
            cached = mEncodedSystemSnapshot;

            if(cached != null && cached.revision() == revision)
            {
                return cached.payload();
            }

            int low = 0;
            int high = MAXIMUM_TOTAL_LIVE_ROWS;
            byte[] best = null;

            while(low <= high)
            {
                int candidateLimit = low + (high - low) / 2;
                byte[] candidate = ApiHttpResponse.encodePayload(
                    StatsApiV1Payload.present(snapshot(candidateLimit)));

                if(candidate.length <= MAXIMUM_SYSTEM_SNAPSHOT_BYTES)
                {
                    best = candidate;
                    low = candidateLimit + 1;
                }
                else
                {
                    high = candidateLimit - 1;
                }
            }

            if(best == null)
            {
                throw new IOException("Live channel-activity metadata exceeds the snapshot byte budget");
            }

            EncodedSnapshot encoded = new EncodedSnapshot(revision, best);
            mEncodedSystemSnapshot = encoded;
            return encoded.payload();
        }
    }

    private void offerRawEvent(Object event)
    {
        if(event == null || !mRunning.get() || mSystemsDemand <= 0)
        {
            return;
        }

        if(mRawEventQueue.offer(event))
        {
            Thread worker = mRawWorker;

            if(worker != null)
            {
                LockSupport.unpark(worker);
            }
        }
        else
        {
            mDroppedRawEventCount.incrementAndGet();

            if(event instanceof ChannelActivityEvent)
            {
                mActivityResyncNeeded.set(true);
            }

            Thread worker = mRawWorker;

            if(worker != null)
            {
                LockSupport.unpark(worker);
            }
        }
    }

    private void startRawWorker()
    {
        if(mRawWorkerRunning)
        {
            return;
        }

        Thread previous = mRawWorker;

        if(previous != null && previous.isAlive())
        {
            throw new IllegalStateException("Previous stats live projection worker has not terminated");
        }

        mRawWorkerRunning = true;
        mRawWorker = new ObserverThreadFactory("stats live projection").newThread(this::runRawWorker);
        mRawWorker.start();
    }

    private void runRawWorker()
    {
        Thread current = Thread.currentThread();

        try
        {
            while(mRawWorkerRunning)
            {
                if(resyncActivityIfNeeded())
                {
                    continue;
                }

                Object event = mRawEventQueue.poll();

                try
                {
                    if(event != null)
                    {
                        mRawProjectionObserver.accept(event);
                    }

                    if(event instanceof ChannelActivityEvent activityEvent)
                    {
                        long sourceRevision = activityEvent.revision();
                        PreparedActivityEvent prepared = sourceRevision <= 0 ||
                            sourceRevision > mAppliedActivitySourceRevision ? prepare(activityEvent) : null;

                        if(prepared != null && mRunning.get())
                        {
                            process(prepared);

                            if(sourceRevision > 0)
                            {
                                mAppliedActivitySourceRevision = sourceRevision;
                            }
                        }
                    }
                    else
                    {
                        LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                    }
                }
                catch(RuntimeException runtimeException)
                {
                    if(event == null || event instanceof ChannelActivityEvent)
                    {
                        mActivityResyncNeeded.set(true);
                    }

                    mLog.error("Error projecting stats live event", runtimeException);
                }
            }
        }
        finally
        {
            mRawEventQueue.clear();

            if(current == mRawWorker)
            {
                mRawWorkerRunning = false;
                mRawWorker = null;
            }
        }
    }

    private void stopRawWorker()
    {
        mRawWorkerRunning = false;
        Thread worker = mRawWorker;

        if(worker != null)
        {
            LockSupport.unpark(worker);

            if(worker != Thread.currentThread())
            {
                try
                {
                    worker.join(TimeUnit.SECONDS.toMillis(2));
                }
                catch(InterruptedException interruptedException)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if(worker == null || !worker.isAlive())
        {
            mRawWorker = null;
        }
    }

    long getDroppedRawEventCount()
    {
        return mDroppedRawEventCount.get();
    }

    boolean isRawWorkerAlive()
    {
        Thread worker = mRawWorker;
        return worker != null && worker.isAlive();
    }

    int getSystemsDemandCount()
    {
        return mSystemsDemand;
    }

    private boolean resyncActivityIfNeeded()
    {
        if(mRunning.get() && mActivityModel != null && mActivityResyncNeeded.compareAndSet(true, false))
        {
            mRawEventQueue.clear();
            ChannelActivityModel.SnapshotSet snapshotSet = mActivityModel.getSnapshotSet();
            replaceActivitySnapshot(snapshotSet);
            mAppliedActivitySourceRevision = snapshotSet.revision();
            return true;
        }

        return false;
    }

    private synchronized void replaceActivitySnapshot(ChannelActivityModel.SnapshotSet snapshotSet)
    {
        if(snapshotSet == null)
        {
            return;
        }

        mTables.clear();
        mRetainedActivityRows = 0;
        mTablesTruncated = false;

        for(ChannelActivitySnapshot snapshot: snapshotSet.tables())
        {
            PreparedActivityEvent prepared = prepare(new ChannelActivityEvent(
                ChannelActivityEvent.Operation.UPSERT, snapshot, snapshotSet.revision()));

            if(prepared != null)
            {
                process(prepared, false);
            }
        }

        systemsChanged();
        mSystemsHub.publish("activity_resync", Map.of("source_revision", snapshotSet.revision(),
            "snapshot", snapshot()));
    }

    private void executeActivityCommit(Runnable task)
    {
        try
        {
            mActivityCommitExecutor.execute(() ->
            {
                mActivityResetPending.set(false);
                task.run();
            });
        }
        catch(RejectedExecutionException exception)
        {
            //Committed rows are already durable.  Tell the browser to refetch the bounded authoritative page instead
            //of silently losing an update when the optional live projection worker is saturated.
            if(mRunning.get() && !mActivityCommitExecutor.isShutdown() &&
                mActivityResetPending.compareAndSet(false, true))
            {
                mActivityHub.publish("activity_reset", Map.of("reason", "source_overflow"));
            }
        }
    }

    private boolean isCurrentRun(long generation)
    {
        return mRunning.get() && mRunGeneration.get() == generation;
    }

    void process(ChannelActivityEvent event)
    {
        PreparedActivityEvent prepared = prepare(event);

        if(prepared != null)
        {
            process(prepared);
        }
    }

    private synchronized void process(PreparedActivityEvent event)
    {
        process(event, true);
    }

    /**
     * Applies one already-projected table mutation while holding this service's state lock.  Snapshot recovery uses
     * the same mutation path without publishing every intermediate table; consumers receive one authoritative
     * resync after the replacement is complete.
     */
    private void process(PreparedActivityEvent event, boolean publish)
    {
        String tableId = event.tableId();
        Map<String,Object> table;

        if(event.operation() == ChannelActivityEvent.Operation.REMOVE)
        {
            table = mTables.remove(tableId);

            if(table == null)
            {
                return;
            }

            mRetainedActivityRows = Math.max(0, mRetainedActivityRows - retainedRows(table));
        }
        else
        {
            if(!mTables.containsKey(tableId) && mTables.size() >= MAXIMUM_LIVE_TABLES)
            {
                if(!mTablesTruncated)
                {
                    mTablesTruncated = true;

                    if(publish)
                    {
                        systemsChanged();
                        mSystemsHub.publish("activity_resync", Map.of("snapshot", snapshot()));
                    }
                }

                return;
            }

            Map<String,Object> existing = mTables.get(tableId);
            int existingRows = retainedRows(existing);
            int available = Math.max(0, MAXIMUM_TOTAL_LIVE_ROWS - (mRetainedActivityRows - existingRows));
            table = limitActivityTable(event.table(), Math.min(MAXIMUM_ROWS_PER_TABLE, available));
            Map<String,Object> previous = mTables.put(tableId, table);

            if(table.equals(previous))
            {
                return;
            }

            mRetainedActivityRows = Math.max(0, mRetainedActivityRows - existingRows + retainedRows(table));
        }

        if(publish)
        {
            systemsChanged();
            mSystemsHub.publish("activity_table", Map.of("operation", event.operation().name().toLowerCase(),
                "table_id", tableId, "table", table, "revision", mSystemsRevision.get()));
        }
    }

    private static PreparedActivityEvent prepare(ChannelActivityEvent event)
    {
        if(event == null || event.snapshot() == null || event.operation() == null)
        {
            return null;
        }

        String tableId = boundedText(event.snapshot().tableId(), MAXIMUM_LIVE_TEXT_LENGTH);

        if(tableId.isBlank())
        {
            return null;
        }

        Map<String,Object> table = event.operation() == ChannelActivityEvent.Operation.REMOVE ? null :
            activityTable(event.snapshot(), MAXIMUM_ROWS_PER_TABLE);
        return new PreparedActivityEvent(event.operation(), tableId, table);
    }

    private void systemsChanged()
    {
        mSystemsRevision.incrementAndGet();
        mEncodedSystemSnapshot = null;
    }

    private static int retainedRows(Map<String,Object> table)
    {
        return table != null && table.get("rows") instanceof List<?> rows ? rows.size() : 0;
    }

    private static Map<String,Object> limitActivityTable(Map<String,Object> table, int maximumRows)
    {
        if(table == null)
        {
            return Map.of();
        }

        @SuppressWarnings("unchecked")
        List<Map<String,Object>> rows = table.get("rows") instanceof List<?> values ?
            (List<Map<String,Object>>)(List<?>)values : List.of();
        int included = Math.min(rows.size(), Math.max(0, maximumRows));
        int total = table.get("rows_total") instanceof Number number ? Math.max(0, number.intValue()) : rows.size();
        LinkedHashMap<String,Object> bounded = new LinkedHashMap<>(table);
        bounded.put("rows", included == rows.size() ? rows : List.copyOf(rows.subList(0, included)));
        bounded.put("rows_total", total);
        bounded.put("rows_omitted", Math.max(0, total - included));
        bounded.put("rows_truncated", total > included);
        return Map.copyOf(bounded);
    }

    private static Map<String,Object> activityTable(ChannelActivitySnapshot snapshot, int maximumRows)
    {
        LinkedHashMap<String,Object> table = new LinkedHashMap<>();
        table.put("table_id", boundedText(snapshot.tableId(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("title", boundedText(snapshot.title(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("channel_name", boundedText(snapshot.channelName(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(table, "configuration_id", snapshot.configurationId(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(table, "guid", snapshot.guid(), MAXIMUM_LIVE_TEXT_LENGTH);
        table.put("closeable", snapshot.closeable());
        table.put("control_active", snapshot.controlActive());
        int rowCount = snapshot.rows().size();
        int rowLimit = Math.min(MAXIMUM_ROWS_PER_TABLE, Math.max(0, maximumRows));
        table.put("rows", snapshot.rows().stream().limit(rowLimit)
            .map(StatsLiveService::activityRow).toList());
        table.put("rows_total", rowCount);
        table.put("rows_omitted", Math.max(0, rowCount - rowLimit));
        table.put("rows_truncated", rowCount > rowLimit);
        return Map.copyOf(table);
    }

    private static Map<String,Object> activityRow(ChannelActivitySnapshot.Row snapshot)
    {
        LinkedHashMap<String,Object> row = new LinkedHashMap<>();
        row.put("key", boundedText(snapshot.key(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(row, "channel_name", snapshot.channelName(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "configuration_id", snapshot.configurationId(), MAXIMUM_LIVE_TEXT_LENGTH);
        row.put("status", boundedText(snapshot.status(), MAXIMUM_LIVE_TEXT_LENGTH));
        List<String> tags = snapshot.tags() != null ? snapshot.tags().stream()
            .filter(Objects::nonNull)
            .limit(MAXIMUM_LIVE_TAGS)
            .map(tag -> boundedText(tag, MAXIMUM_LIVE_TAG_LENGTH))
            .toList() : List.of();
        row.put("tags", tags);
        row.put("tags_truncated", snapshot.tags() != null && (snapshot.tags().size() > tags.size() ||
            snapshot.tags().stream().filter(Objects::nonNull)
                .anyMatch(tag -> tag.length() > MAXIMUM_LIVE_TAG_LENGTH)));
        putText(row, "lcn", snapshot.lcn(), MAXIMUM_LIVE_TEXT_LENGTH);
        row.put("frequency_hz", snapshot.frequencyHz());
        put(row, "signal_dbfs", snapshot.signalDbfs());
        put(row, "decode_health_pct", snapshot.decodeHealthPercent());
        if(snapshot.decodeHealthPercent() != null)
        {
            row.put("cc_valid_frames", snapshot.controlValidFrames());
            row.put("cc_invalid_frames", snapshot.controlInvalidFrames());
            row.put("cc_corrected_bits", snapshot.controlCorrectedBits());
            row.put("cc_sync_loss_bits", snapshot.controlSyncLossBits());
            row.put("cc_dropped_bits", snapshot.controlDroppedBits());
        }
        if(snapshot.voiceQuality() != null && snapshot.voiceQuality().hasMeasurements())
        {
            row.put("vc_quality_pct", snapshot.voiceQuality().qualityPercent());
            row.put("vc_decoded_frames", snapshot.voiceQuality().decodedFrameCount());
            row.put("vc_repeated_frames", snapshot.voiceQuality().repeatedFrameCount());
            row.put("vc_concealed_frames", snapshot.voiceQuality().concealedFrameCount());
            row.put("vc_missing_frames", snapshot.voiceQuality().missingFrameCount());
            row.put("vc_fec_errors", snapshot.voiceQuality().fecErrorCount());
            row.put("vc_fec_protected_bits", snapshot.voiceQuality().fecProtectedBitCount());
        }
        if(snapshot.qualityObservedAtMs() > 0)
        {
            row.put("quality_observed_at_ms", snapshot.qualityObservedAtMs());
        }
        put(row, "timeslot", snapshot.timeslot());
        putText(row, "source_id", snapshot.sourceId(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "source_alias", snapshot.sourceAlias(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "talker_alias", snapshot.talkerAlias(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "source_alias_display", snapshot.sourceAliasDisplay(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "target_id", snapshot.targetId(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "target_alias", snapshot.targetAlias(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "decoder", snapshot.decoder(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "encryption_details", snapshot.encryptionDetails(), MAXIMUM_LIVE_TEXT_LENGTH);
        return Map.copyOf(row);
    }

    private static void putText(Map<String,Object> values, String key, Object value, int maximumLength)
    {
        if(value != null)
        {
            values.put(key, boundedText(value, maximumLength));
        }
    }

    private static String boundedText(Object value, int maximumLength)
    {
        String text = value != null ? String.valueOf(value) : "";
        return text.length() <= maximumLength ? text : text.substring(0, maximumLength);
    }

    private static void put(Map<String,Object> values, String key, Object value)
    {
        if(value != null)
        {
            values.put(key, value);
        }
    }

    @Override
    public void close()
    {
        stop();
        mActivityCommitExecutor.shutdownNow();
        mSystemsHub.close();
        mActivityHub.close();
    }

    private record PreparedActivityEvent(ChannelActivityEvent.Operation operation, String tableId,
                                         Map<String,Object> table)
    {
    }

    private record EncodedSnapshot(long revision, byte[] payload)
    {
    }
}
