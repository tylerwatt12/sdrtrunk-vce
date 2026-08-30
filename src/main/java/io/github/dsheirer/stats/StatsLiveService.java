/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded web adapter for the authoritative channel-activity snapshot.
 * Channel state is owned by {@link ChannelActivityModel}; one low-priority web worker performs bounded projection,
 * and browser subscribers never change receiver-side activity lifetime.
 */
final class StatsLiveService implements AutoCloseable
{
    private static final int MAXIMUM_LIVE_SUBSCRIBERS = 32;
    private static final int LIVE_SUBSCRIBER_QUEUE_CAPACITY = 256;
    static final int MAXIMUM_LIVE_TABLES = 128;
    static final int MAXIMUM_ROWS_PER_TABLE = 256;
    static final int MAXIMUM_TOTAL_LIVE_ROWS = 2_048;
    static final int MAXIMUM_SYSTEM_SNAPSHOT_BYTES = 1024 * 1024;
    private static final int MAXIMUM_LIVE_TEXT_LENGTH = 256;
    private static final int MAXIMUM_LIVE_IDENTIFIERS = 16;
    private static final int MAXIMUM_LIVE_TAGS = 16;
    private static final int MAXIMUM_LIVE_TAG_LENGTH = 64;
    private static final int MAXIMUM_LIVE_ALIAS_REFERENCES = 8;
    private final ActivitySource mActivitySource;
    private final WebEntityNavigationCatalog mNavigationCatalog;
    private final StatsLiveEventHub mSystemsHub =
        new StatsLiveEventHub(MAXIMUM_LIVE_SUBSCRIBERS, LIVE_SUBSCRIBER_QUEUE_CAPACITY);
    private final AtomicBoolean mRunning = new AtomicBoolean();
    /** Single latest-value handoff. Saturation coalesces stale web updates instead of delaying channel activity. */
    private final AtomicReference<ChannelActivityEvent> mPendingActivity = new AtomicReference<>();
    private final AtomicLong mDroppedProjectionEvents = new AtomicLong();
    private final AtomicBoolean mProjectionResyncRequired = new AtomicBoolean();
    private final Object mLifecycleLock = new Object();
    private final Object mEncodedSnapshotLock = new Object();
    private final Listener<ChannelActivityEvent> mChannelActivityListener = this::receiveChannelActivity;

    private volatile EncodedSnapshot mEncodedSystemSnapshot;
    private volatile WebEntityNavigationCatalog.Snapshot mPublishedNavigation =
        WebEntityNavigationCatalog.Snapshot.empty();
    private volatile Thread mProjectionWorker;

    StatsLiveService(ChannelProcessingManager channelProcessingManager)
    {
        this(channelProcessingManager, null);
    }

    StatsLiveService(ChannelProcessingManager channelProcessingManager,
                     WebEntityNavigationCatalog navigationCatalog)
    {
        this(channelProcessingManager != null ?
            new ModelActivitySource(channelProcessingManager.getChannelActivityModel()) : ActivitySource.EMPTY,
            navigationCatalog);
    }

    private StatsLiveService(ActivitySource activitySource, WebEntityNavigationCatalog navigationCatalog)
    {
        mActivitySource = Objects.requireNonNull(activitySource, "Channel activity source cannot be null");
        mNavigationCatalog = navigationCatalog;
    }

    static StatsLiveService fromActivitySource(ActivitySource activitySource,
                                               WebEntityNavigationCatalog navigationCatalog)
    {
        return new StatsLiveService(activitySource, navigationCatalog);
    }

    void start()
    {
        synchronized(mLifecycleLock)
        {
            if(mRunning.compareAndSet(false, true))
            {
                if(mNavigationCatalog != null)
                {
                    mNavigationCatalog.start();
                }

                mPublishedNavigation = navigationSnapshot();

                Thread worker = new ObserverThreadFactory("stats live projection").newThread(this::projectionLoop);
                mProjectionWorker = worker;
                worker.start();

                mActivitySource.addListener(mChannelActivityListener);
            }
        }
    }

    void stop()
    {
        Thread worker = null;

        synchronized(mLifecycleLock)
        {
            if(mRunning.compareAndSet(true, false))
            {
                mActivitySource.removeListener(mChannelActivityListener);

                mPendingActivity.set(null);
                worker = mProjectionWorker;
                mProjectionWorker = null;

                if(worker != null)
                {
                    worker.interrupt();
                    LockSupport.unpark(worker);
                }

                if(mNavigationCatalog != null)
                {
                    mNavigationCatalog.stop();
                }
            }

            mSystemsHub.close();
            mEncodedSystemSnapshot = null;
            mPublishedNavigation = WebEntityNavigationCatalog.Snapshot.empty();
        }

        if(worker != null && worker != Thread.currentThread())
        {
            try
            {
                worker.join(1_000L);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    StatsLiveEventHub.Subscription subscribeSystems()
    {
        synchronized(mLifecycleLock)
        {
            return mRunning.get() ? mSystemsHub.subscribe() : null;
        }
    }

    void receiveChannelActivity(ChannelActivityEvent event)
    {
        if(!mRunning.get() || event == null || event.snapshot() == null || event.operation() == null)
        {
            return;
        }

        if(mPendingActivity.getAndSet(event) != null)
        {
            mDroppedProjectionEvents.incrementAndGet();
            mProjectionResyncRequired.set(true);
        }

        LockSupport.unpark(mProjectionWorker);
    }

    private void projectionLoop()
    {
        Thread current = Thread.currentThread();

        while(mRunning.get() && mProjectionWorker == current)
        {
            ChannelActivityEvent event = mPendingActivity.getAndSet(null);

            if(event == null)
            {
                publishNavigationRefreshIfNeeded();
                LockSupport.parkNanos(this, 100_000_000L);
            }
            else
            {
                try
                {
                    projectAndPublish(event);
                }
                catch(RuntimeException exception)
                {
                    //One malformed optional projection is discarded. The worker remains available for the next
                    //authoritative snapshot and never pushes the failure back onto a receiver callback.
                    mProjectionResyncRequired.set(true);
                }
            }
        }
    }

    private void projectAndPublish(ChannelActivityEvent event)
    {
        WebEntityNavigationCatalog.Snapshot navigation = navigationSnapshot();
        PreparedActivityEvent prepared = prepare(event, navigation);

        if(prepared == null)
        {
            return;
        }

        LinkedHashMap<String,Object> update = new LinkedHashMap<>();
        update.put("operation", prepared.operation().name().toLowerCase());
        update.put("table_id", prepared.tableId());

        if(prepared.table() != null)
        {
            update.put("table", prepared.table());
        }

        update.put("revision", event.revision() > 0 ? event.revision() : currentSnapshotSet().revision());
        synchronized(mLifecycleLock)
        {
            if(!mRunning.get())
            {
                return;
            }

            mEncodedSystemSnapshot = null;
            mPublishedNavigation = navigation;
            mSystemsHub.publish("activity_table", Map.copyOf(update));
        }

        if(mProjectionResyncRequired.getAndSet(false))
        {
            Map<String,Object> authoritative = snapshot();

            synchronized(mLifecycleLock)
            {
                if(mRunning.get())
                {
                    mSystemsHub.publish("activity_resync", Map.of("snapshot", authoritative));
                }
            }
        }
    }

    private void publishNavigationRefreshIfNeeded()
    {
        WebEntityNavigationCatalog.Snapshot navigation = navigationSnapshot();

        if(mPublishedNavigation == navigation)
        {
            return;
        }

        ChannelActivityModel.SnapshotSet source = currentSnapshotSet();
        Map<String,Object> authoritative = snapshot(source, MAXIMUM_TOTAL_LIVE_ROWS, navigation);

        synchronized(mLifecycleLock)
        {
            if(mRunning.get() && mPublishedNavigation != navigation)
            {
                mEncodedSystemSnapshot = null;
                mPublishedNavigation = navigation;
                mSystemsHub.publish("activity_resync", Map.of("snapshot", authoritative));
            }
        }
    }

    long droppedProjectionEvents()
    {
        return mDroppedProjectionEvents.get();
    }

    Map<String,Object> snapshot()
    {
        return snapshot(currentSnapshotSet(), MAXIMUM_TOTAL_LIVE_ROWS, navigationSnapshot());
    }

    private Map<String,Object> snapshot(ChannelActivityModel.SnapshotSet source, int maximumRows,
                                        WebEntityNavigationCatalog.Snapshot navigation)
    {
        List<ChannelActivitySnapshot> snapshots = source.tables().stream()
            .filter(StatsLiveService::isVisibleLiveTable).sorted(Comparator
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
            Map<String,Object> projected = activityTable(table, rowLimit, navigation);
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

    /**
     * A stopped trunked channel remains in the desktop activity model long enough for the current live browser to
     * show its stopped state and close control.  It is not active receiver state, however, so recovery snapshots must
     * not restore it after a browser refresh or reconnect.
     */
    private static boolean isVisibleLiveTable(ChannelActivitySnapshot snapshot)
    {
        return snapshot != null && ("conventional".equals(snapshot.tableId()) || snapshot.channelRunning());
    }

    byte[] encodedSnapshot() throws IOException
    {
        ChannelActivityModel.SnapshotSet source = currentSnapshotSet();
        WebEntityNavigationCatalog.Snapshot navigation = navigationSnapshot();
        EncodedSnapshot cached = mEncodedSystemSnapshot;

        if(cached != null && cached.revision() == source.revision() && cached.navigation() == navigation)
        {
            return cached.payload();
        }

        synchronized(mEncodedSnapshotLock)
        {
            cached = mEncodedSystemSnapshot;

            if(cached != null && cached.revision() == source.revision() && cached.navigation() == navigation)
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
                    StatsApiV1Payload.present(snapshot(source, candidateLimit, navigation)));

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

            EncodedSnapshot encoded = new EncodedSnapshot(source.revision(), navigation, best);
            mEncodedSystemSnapshot = encoded;
            return encoded.payload();
        }
    }

    private ChannelActivityModel.SnapshotSet currentSnapshotSet()
    {
        return mActivitySource.snapshot();
    }

    private PreparedActivityEvent prepare(ChannelActivityEvent event,
                                          WebEntityNavigationCatalog.Snapshot navigation)
    {
        String tableId = boundedText(event.snapshot().tableId(), MAXIMUM_LIVE_TEXT_LENGTH);

        if(tableId.isBlank())
        {
            return null;
        }

        Map<String,Object> table = event.operation() == ChannelActivityEvent.Operation.REMOVE ? null :
            activityTable(event.snapshot(), MAXIMUM_ROWS_PER_TABLE, navigation);
        return new PreparedActivityEvent(event.operation(), tableId, table);
    }

    private WebEntityNavigationCatalog.Snapshot navigationSnapshot()
    {
        return mNavigationCatalog != null ? mNavigationCatalog.snapshot() :
            WebEntityNavigationCatalog.Snapshot.empty();
    }

    private static Map<String,Object> activityTable(ChannelActivitySnapshot snapshot, int maximumRows,
                                                    WebEntityNavigationCatalog.Snapshot navigation)
    {
        LinkedHashMap<String,Object> table = new LinkedHashMap<>();
        WebEntityNavigationCatalog.Channel tableChannel =
            navigation.channel(snapshot.configurationId(), snapshot.guid());
        table.put("table_id", boundedText(snapshot.tableId(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("title", boundedText(snapshot.title(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("system_name", boundedText(snapshot.systemName(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("site_name", boundedText(snapshot.siteName(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("channel_name", boundedText(snapshot.channelName(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(table, "configuration_id", snapshot.configurationId(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(table, "guid", snapshot.guid(), MAXIMUM_LIVE_TEXT_LENGTH);
        WebEntityRef.put(table, tableChannel != null ? tableChannel.entityRef() : null);
        table.put("control_active", snapshot.controlActive());
        table.put("channel_running", snapshot.channelRunning());
        table.put("identifiers", snapshot.identifiers().stream().limit(MAXIMUM_LIVE_IDENTIFIERS)
            .map(StatsLiveService::activityIdentifier).toList());
        int rowCount = snapshot.rows().size();
        int included = Math.min(rowCount, Math.max(0, maximumRows));
        table.put("rows", snapshot.rows().stream().limit(included)
            .map(row -> activityRow(row, tableChannel, navigation)).toList());
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

    private static Map<String,Object> activityRow(ChannelActivitySnapshot.Row snapshot,
                                                  WebEntityNavigationCatalog.Channel tableChannel,
                                                  WebEntityNavigationCatalog.Snapshot catalog)
    {
        LinkedHashMap<String,Object> row = new LinkedHashMap<>();
        WebEntityNavigationCatalog.Channel rowChannel = catalog.channel(snapshot.configurationId(), null);

        if(rowChannel == null)
        {
            rowChannel = tableChannel;
        }

        row.put("key", boundedText(snapshot.key(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(row, "channel_name", snapshot.channelName(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "configuration_id", snapshot.configurationId(), MAXIMUM_LIVE_TEXT_LENGTH);
        WebEntityRef.put(row, rowChannel != null ? rowChannel.entityRef() : null);
        row.put("status", boundedText(snapshot.status(), MAXIMUM_LIVE_TEXT_LENGTH));
        row.put("activation_order", snapshot.activationOrder());
        putText(row, "role", snapshot.role(), MAXIMUM_LIVE_TEXT_LENGTH);
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

        if(snapshot.controlLastValidDecodeMs() > 0)
        {
            row.put("cc_last_valid_decode_ms", snapshot.controlLastValidDecodeMs());
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
            if(rowChannel != null)
            {
                WebEntityRef sourceReference = rowChannel.identity(navigation.sourceMatcher());
                WebEntityRef targetReference = rowChannel.identity(navigation.targetMatcher());

                if(sourceReference != null)
                {
                    row.put("source_entity_ref", sourceReference.toMap());
                }
                if(targetReference != null)
                {
                    row.put("target_entity_ref", targetReference.toMap());
                }
            }
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
    }

    private record PreparedActivityEvent(ChannelActivityEvent.Operation operation, String tableId,
                                         Map<String,Object> table)
    {
    }

    private record EncodedSnapshot(long revision, WebEntityNavigationCatalog.Snapshot navigation, byte[] payload)
    {
    }

    interface ActivitySource
    {
        ActivitySource EMPTY = new ActivitySource()
        {
            private final ChannelActivityModel.SnapshotSet mEmpty =
                new ChannelActivityModel.SnapshotSet(0, List.of());

            @Override
            public ChannelActivityModel.SnapshotSet snapshot()
            {
                return mEmpty;
            }

            @Override
            public void addListener(Listener<ChannelActivityEvent> listener)
            {
            }

            @Override
            public void removeListener(Listener<ChannelActivityEvent> listener)
            {
            }
        };

        ChannelActivityModel.SnapshotSet snapshot();

        void addListener(Listener<ChannelActivityEvent> listener);

        void removeListener(Listener<ChannelActivityEvent> listener);
    }

    private record ModelActivitySource(ChannelActivityModel model) implements ActivitySource
    {
        private ModelActivitySource
        {
            Objects.requireNonNull(model, "Channel activity model cannot be null");
        }

        @Override
        public ChannelActivityModel.SnapshotSet snapshot()
        {
            return model.getSnapshotSet();
        }

        @Override
        public void addListener(Listener<ChannelActivityEvent> listener)
        {
            model.addActivityListener(listener);
        }

        @Override
        public void removeListener(Listener<ChannelActivityEvent> listener)
        {
            model.removeActivityListener(listener);
        }
    }
}
