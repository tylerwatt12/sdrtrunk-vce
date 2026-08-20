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
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Bounded web adapter for the authoritative channel-activity snapshot and committed activity events.
 * Channel state is owned by {@link ChannelActivityModel}; this class never starts another projection worker or
 * changes receiver-side activity lifetime when browser subscribers connect or disconnect.
 */
final class StatsLiveService implements AutoCloseable
{
    private static final int MAXIMUM_LIVE_SUBSCRIBERS = 32;
    private static final int LIVE_SUBSCRIBER_QUEUE_CAPACITY = 256;
    static final int EVENT_QUEUE_CAPACITY = 64;
    static final int MAXIMUM_LIVE_TABLES = 128;
    static final int MAXIMUM_ROWS_PER_TABLE = 256;
    static final int MAXIMUM_TOTAL_LIVE_ROWS = 2_048;
    static final int MAXIMUM_SYSTEM_SNAPSHOT_BYTES = 1024 * 1024;
    private static final int MAXIMUM_LIVE_TEXT_LENGTH = 256;
    private static final int MAXIMUM_LIVE_IDENTIFIERS = 16;
    private static final int MAXIMUM_LIVE_TAGS = 16;
    private static final int MAXIMUM_LIVE_TAG_LENGTH = 64;
    private static final int MAXIMUM_LIVE_ALIAS_REFERENCES = 8;
    private final StatsWebDatabase mDatabase;
    private final ChannelActivityModel mActivityModel;
    private final StatsLiveEventHub mSystemsHub =
        new StatsLiveEventHub(MAXIMUM_LIVE_SUBSCRIBERS, LIVE_SUBSCRIBER_QUEUE_CAPACITY);
    private final StatsLiveEventHub mActivityHub =
        new StatsLiveEventHub(MAXIMUM_LIVE_SUBSCRIBERS, LIVE_SUBSCRIBER_QUEUE_CAPACITY);
    private final ExecutorService mActivityCommitExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY), new NamingThreadFactory("stats live events"),
        new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean mActivityResetPending = new AtomicBoolean();
    private final AtomicLong mRunGeneration = new AtomicLong();
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final Object mLifecycleLock = new Object();
    private final Object mEncodedSnapshotLock = new Object();
    private final Listener<ChannelActivityEvent> mChannelActivityListener = this::receiveChannelActivity;

    /* Package-private standalone state exists only for bounded projection tests without a receiver manager. */
    private final Map<String,ChannelActivitySnapshot> mStandaloneSnapshots = new LinkedHashMap<>();
    private long mStandaloneRevision;
    private volatile EncodedSnapshot mEncodedSystemSnapshot;

    StatsLiveService(StatsWebDatabase database, ChannelProcessingManager channelProcessingManager)
    {
        mDatabase = database;
        mActivityModel = channelProcessingManager != null ? channelProcessingManager.getChannelActivityModel() : null;
    }

    void start()
    {
        synchronized(mLifecycleLock)
        {
            if(mRunning.compareAndSet(false, true))
            {
                mRunGeneration.incrementAndGet();

                if(mActivityModel != null)
                {
                    mActivityModel.addActivityListener(mChannelActivityListener);
                }
            }
        }
    }

    void stop()
    {
        synchronized(mLifecycleLock)
        {
            if(mRunning.compareAndSet(true, false))
            {
                mRunGeneration.incrementAndGet();

                if(mActivityModel != null)
                {
                    mActivityModel.removeActivityListener(mChannelActivityListener);
                }
            }

            mSystemsHub.close();
            mActivityHub.close();
            mActivityResetPending.set(false);
            mEncodedSystemSnapshot = null;
        }
    }

    void activityCommitted(List<Long> rowIds)
    {
        if(rowIds == null || rowIds.isEmpty() || !mActivityHub.hasSubscribers())
        {
            return;
        }

        List<Long> committed = rowIds.stream().filter(Objects::nonNull).distinct()
            .limit(StatsWebDatabase.MAXIMUM_ACTIVITY_EVENT_BATCH).toList();

        if(committed.isEmpty())
        {
            return;
        }

        long generation = mRunGeneration.get();
        executeActivityCommit(() ->
        {
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

    StatsLiveEventHub.Subscription subscribeSystems()
    {
        synchronized(mLifecycleLock)
        {
            return mRunning.get() ? mSystemsHub.subscribe() : null;
        }
    }

    StatsLiveEventHub.Subscription subscribeActivity(Predicate<StatsLiveEventHub.LiveEvent> filter)
    {
        synchronized(mLifecycleLock)
        {
            return mRunning.get() ? mActivityHub.subscribe(filter) : null;
        }
    }

    void receiveChannelActivity(ChannelActivityEvent event)
    {
        if(!mRunning.get() || event == null || event.snapshot() == null || event.operation() == null)
        {
            return;
        }

        PreparedActivityEvent prepared = prepare(event);

        if(prepared == null)
        {
            return;
        }

        mEncodedSystemSnapshot = null;
        LinkedHashMap<String,Object> update = new LinkedHashMap<>();
        update.put("operation", prepared.operation().name().toLowerCase());
        update.put("table_id", prepared.tableId());

        if(prepared.table() != null)
        {
            update.put("table", prepared.table());
        }

        update.put("revision", event.revision() > 0 ? event.revision() : currentSnapshotSet().revision());
        mSystemsHub.publish("activity_table", Map.copyOf(update));
    }

    /** Test-only direct projection path. Production snapshots always come from ChannelActivityModel. */
    void process(ChannelActivityEvent event)
    {
        if(event == null || event.snapshot() == null || event.operation() == null)
        {
            return;
        }

        synchronized(mStandaloneSnapshots)
        {
            if(event.operation() == ChannelActivityEvent.Operation.REMOVE)
            {
                mStandaloneSnapshots.remove(event.snapshot().tableId());
            }
            else
            {
                mStandaloneSnapshots.put(event.snapshot().tableId(), event.snapshot());
            }

            mStandaloneRevision++;
        }

        receiveChannelActivity(new ChannelActivityEvent(event.operation(), event.snapshot(), mStandaloneRevision));
    }

    Map<String,Object> snapshot()
    {
        return snapshot(currentSnapshotSet(), MAXIMUM_TOTAL_LIVE_ROWS);
    }

    private Map<String,Object> snapshot(ChannelActivityModel.SnapshotSet source, int maximumRows)
    {
        List<ChannelActivitySnapshot> snapshots = source.tables().stream().sorted(Comparator
            .comparingInt((ChannelActivitySnapshot table) -> "conventional".equals(table.tableId()) ? 0 : 1)
            .thenComparing(ChannelActivitySnapshot::tableId)).toList();
        List<Map<String,Object>> tables = new ArrayList<>(Math.min(snapshots.size(), MAXIMUM_LIVE_TABLES));
        int rowsIncluded = 0;
        long rowsTotal = snapshots.stream().mapToLong(table -> table.rows().size()).sum();
        int tableCount = Math.min(snapshots.size(), MAXIMUM_LIVE_TABLES);

        for(int index = 0; index < tableCount; index++)
        {
            ChannelActivitySnapshot table = snapshots.get(index);
            int available = Math.max(0, maximumRows - rowsIncluded);
            int rowLimit = Math.min(MAXIMUM_ROWS_PER_TABLE, available);
            Map<String,Object> projected = activityTable(table, rowLimit);
            tables.add(projected);
            int included = projected.get("rows") instanceof List<?> rows ? rows.size() : 0;
            rowsIncluded += included;
        }

        LinkedHashMap<String,Object> response = new LinkedHashMap<>();
        response.put("tables", List.copyOf(tables));
        response.put("table_limit", MAXIMUM_LIVE_TABLES);
        response.put("row_limit_per_table", MAXIMUM_ROWS_PER_TABLE);
        response.put("row_limit_total", MAXIMUM_TOTAL_LIVE_ROWS);
        response.put("encoded_byte_limit", MAXIMUM_SYSTEM_SNAPSHOT_BYTES);
        response.put("tables_included", tables.size());
        response.put("tables_omitted_at_least", Math.max(0, snapshots.size() - tableCount));
        response.put("rows_total", rowsTotal);
        response.put("rows_included", rowsIncluded);
        response.put("rows_omitted", Math.max(0L, rowsTotal - rowsIncluded));
        response.put("truncated", snapshots.size() > tableCount || rowsTotal > rowsIncluded);
        response.put("revision", source.revision());
        return Map.copyOf(response);
    }

    byte[] encodedSnapshot() throws IOException
    {
        ChannelActivityModel.SnapshotSet source = currentSnapshotSet();
        EncodedSnapshot cached = mEncodedSystemSnapshot;

        if(cached != null && cached.revision() == source.revision())
        {
            return cached.payload();
        }

        synchronized(mEncodedSnapshotLock)
        {
            cached = mEncodedSystemSnapshot;

            if(cached != null && cached.revision() == source.revision())
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
                    StatsApiV1Payload.present(snapshot(source, candidateLimit)));

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

            EncodedSnapshot encoded = new EncodedSnapshot(source.revision(), best);
            mEncodedSystemSnapshot = encoded;
            return encoded.payload();
        }
    }

    private ChannelActivityModel.SnapshotSet currentSnapshotSet()
    {
        if(mActivityModel != null)
        {
            return mActivityModel.getSnapshotSet();
        }

        synchronized(mStandaloneSnapshots)
        {
            return new ChannelActivityModel.SnapshotSet(mStandaloneRevision,
                List.copyOf(mStandaloneSnapshots.values()));
        }
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

    private static PreparedActivityEvent prepare(ChannelActivityEvent event)
    {
        String tableId = boundedText(event.snapshot().tableId(), MAXIMUM_LIVE_TEXT_LENGTH);

        if(tableId.isBlank())
        {
            return null;
        }

        Map<String,Object> table = event.operation() == ChannelActivityEvent.Operation.REMOVE ? null :
            activityTable(event.snapshot(), MAXIMUM_ROWS_PER_TABLE);
        return new PreparedActivityEvent(event.operation(), tableId, table);
    }

    private static Map<String,Object> activityTable(ChannelActivitySnapshot snapshot, int maximumRows)
    {
        LinkedHashMap<String,Object> table = new LinkedHashMap<>();
        table.put("table_id", boundedText(snapshot.tableId(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("title", boundedText(snapshot.title(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("system_name", boundedText(snapshot.systemName(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("site_name", boundedText(snapshot.siteName(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("channel_name", boundedText(snapshot.channelName(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(table, "configuration_id", snapshot.configurationId(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(table, "guid", snapshot.guid(), MAXIMUM_LIVE_TEXT_LENGTH);
        table.put("control_active", snapshot.controlActive());
        table.put("channel_running", snapshot.channelRunning());
        table.put("identifiers", snapshot.identifiers().stream().limit(MAXIMUM_LIVE_IDENTIFIERS)
            .map(StatsLiveService::activityIdentifier).toList());
        int rowCount = snapshot.rows().size();
        int included = Math.min(rowCount, Math.max(0, maximumRows));
        table.put("rows", snapshot.rows().stream().limit(included).map(StatsLiveService::activityRow).toList());
        table.put("rows_total", rowCount);
        table.put("rows_omitted", rowCount - included);
        table.put("rows_truncated", rowCount > included);
        return Map.copyOf(table);
    }

    private static Map<String,Object> activityIdentifier(ChannelActivitySnapshot.IdentifierField identifier)
    {
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        value.put("group", boundedText(identifier.group(), MAXIMUM_LIVE_TEXT_LENGTH));
        value.put("label", boundedText(identifier.label(), MAXIMUM_LIVE_TEXT_LENGTH));
        value.put("value", boundedText(identifier.value(), MAXIMUM_LIVE_TEXT_LENGTH));
        return Map.copyOf(value);
    }

    private static Map<String,Object> activityRow(ChannelActivitySnapshot.Row snapshot)
    {
        LinkedHashMap<String,Object> row = new LinkedHashMap<>();
        row.put("key", boundedText(snapshot.key(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(row, "channel_name", snapshot.channelName(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "configuration_id", snapshot.configurationId(), MAXIMUM_LIVE_TEXT_LENGTH);
        row.put("status", boundedText(snapshot.status(), MAXIMUM_LIVE_TEXT_LENGTH));
        List<String> tags = snapshot.tags() != null ? snapshot.tags().stream().filter(Objects::nonNull)
            .limit(MAXIMUM_LIVE_TAGS).map(tag -> boundedText(tag, MAXIMUM_LIVE_TAG_LENGTH)).toList() : List.of();
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
        putText(row, "source_form", snapshot.sourceForm(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "source_alias", snapshot.sourceAlias(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "source_alias_description", snapshot.sourceAliasDescription(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "talker_alias", snapshot.talkerAlias(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "source_alias_display", snapshot.sourceAliasDisplay(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "target_id", snapshot.targetId(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "target_form", snapshot.targetForm(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "target_alias", snapshot.targetAlias(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "target_alias_description", snapshot.targetAliasDescription(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "callsign", snapshot.callsign(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "decoder", snapshot.decoder(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "encryption_details", snapshot.encryptionDetails(), MAXIMUM_LIVE_TEXT_LENGTH);

        ChannelActivitySnapshot.Navigation navigation = snapshot.navigation();

        if(navigation != null)
        {
            putText(row, "context_key", navigation.contextKey(), MAXIMUM_LIVE_TEXT_LENGTH);
            putText(row, "alias_list_name", navigation.aliasListName(), MAXIMUM_LIVE_TEXT_LENGTH);
            putText(row, "protocol", navigation.protocol(), MAXIMUM_LIVE_TEXT_LENGTH);
            row.put("source_aliases", navigation.sourceAliases().stream().limit(MAXIMUM_LIVE_ALIAS_REFERENCES)
                .map(StatsLiveService::activityAliasReference).toList());
            row.put("target_aliases", navigation.targetAliases().stream().limit(MAXIMUM_LIVE_ALIAS_REFERENCES)
                .map(StatsLiveService::activityAliasReference).toList());
            put(row, "source_matcher", activityMatcherReference(navigation.sourceMatcher()));
            put(row, "target_matcher", activityMatcherReference(navigation.targetMatcher()));
        }

        return Map.copyOf(row);
    }

    private static Map<String,Object> activityAliasReference(ChannelActivitySnapshot.AliasReference reference)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("alias_id", reference.aliasId());
        value.put("alias_list_id", reference.aliasListId());
        value.put("name", boundedText(reference.name(), MAXIMUM_LIVE_TEXT_LENGTH));
        return Map.copyOf(value);
    }

    private static Map<String,Object> activityMatcherReference(ChannelActivitySnapshot.MatcherReference reference)
    {
        if(reference == null)
        {
            return null;
        }

        Map<String,Object> value = new LinkedHashMap<>();
        value.put("type", boundedText(reference.type(), MAXIMUM_LIVE_TEXT_LENGTH));
        value.put("protocol", boundedText(reference.protocol(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(value, "variant", reference.variant(), MAXIMUM_LIVE_TEXT_LENGTH);
        value.put("value", reference.value());
        return Map.copyOf(value);
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
    }

    private record PreparedActivityEvent(ChannelActivityEvent.Operation operation, String tableId,
                                         Map<String,Object> table)
    {
    }

    private record EncodedSnapshot(long revision, byte[] payload)
    {
    }
}
