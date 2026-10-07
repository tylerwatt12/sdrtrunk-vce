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
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.remote.RemoteOriginLookup;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import io.github.dsheirer.web.http.ApiHttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
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
    private static final int MAXIMUM_ROW_SYSTEM_SCOPES = MAXIMUM_TOTAL_LIVE_ROWS * 2;
    private static final long PROJECTION_FAILURE_RETRY_NANOS = 1_000_000_000L;
    private final ActivitySource mActivitySource;
    private final WebEntityNavigationCatalog mNavigationCatalog;
    private final RemoteOriginLookup mRemoteOriginLookup;
    private final StatsLiveEventHub mChannelActivityHub =
        new StatsLiveEventHub(MAXIMUM_LIVE_SUBSCRIBERS, LIVE_SUBSCRIBER_QUEUE_CAPACITY);
    private final AtomicLong mDroppedProjectionEvents = new AtomicLong();
    private final AtomicLong mProjectionFailures = new AtomicLong();
    private final AtomicLong mRunGeneration = new AtomicLong();
    private final Object mLifecycleLock = new Object();
    private final Object mEncodedSnapshotLock = new Object();

    private volatile EncodedSnapshot mEncodedChannelActivitySnapshot;
    private volatile RowSystemScopeState mRowSystemScopeState = new RowSystemScopeState();
    private volatile ProjectionRun mCurrentRun;

    StatsLiveService(ChannelProcessingManager channelProcessingManager)
    {
        this(channelProcessingManager, null);
    }

    StatsLiveService(ChannelProcessingManager channelProcessingManager,
                     WebEntityNavigationCatalog navigationCatalog)
    {
        this(channelProcessingManager, navigationCatalog, RemoteOriginLookup.EMPTY);
    }

    StatsLiveService(ChannelProcessingManager channelProcessingManager,
                     WebEntityNavigationCatalog navigationCatalog, RemoteOriginLookup remoteOriginLookup)
    {
        this(channelProcessingManager != null ?
            new ModelActivitySource(channelProcessingManager.getChannelActivityModel()) : ActivitySource.EMPTY,
            navigationCatalog, remoteOriginLookup);
    }

    private StatsLiveService(ActivitySource activitySource, WebEntityNavigationCatalog navigationCatalog,
                             RemoteOriginLookup remoteOriginLookup)
    {
        mActivitySource = Objects.requireNonNull(activitySource, "Channel activity source cannot be null");
        mNavigationCatalog = navigationCatalog;
        mRemoteOriginLookup = remoteOriginLookup != null ? remoteOriginLookup : RemoteOriginLookup.EMPTY;
    }

    static StatsLiveService fromActivitySource(ActivitySource activitySource,
                                               WebEntityNavigationCatalog navigationCatalog)
    {
        return new StatsLiveService(activitySource, navigationCatalog, RemoteOriginLookup.EMPTY);
    }

    static StatsLiveService fromActivitySource(ActivitySource activitySource,
                                               WebEntityNavigationCatalog navigationCatalog,
                                               RemoteOriginLookup remoteOriginLookup)
    {
        return new StatsLiveService(activitySource, navigationCatalog, remoteOriginLookup);
    }

    void start()
    {
        synchronized(mLifecycleLock)
        {
            if(mCurrentRun == null)
            {
                if(mNavigationCatalog != null)
                {
                    mNavigationCatalog.start();
                }

                RowSystemScopeState scopes = new RowSystemScopeState();
                ProjectionRun run = new ProjectionRun(mRunGeneration.incrementAndGet(), scopes,
                    navigationSnapshot(), remoteOriginSnapshot());
                Thread worker = new ObserverThreadFactory("stats live projection")
                    .newThread(() -> projectionLoop(run));
                run.mWorker = worker;
                mRowSystemScopeState = scopes;
                mEncodedChannelActivitySnapshot = null;
                mCurrentRun = run;
                // Register before capturing the baseline: notifications arriving afterward are already queued.
                // The lifecycle lock prevents a browser subscriber from observing a partially initialized run.
                mActivitySource.addListener(run.mListener);
                try
                {
                    run.mInitialSnapshot = currentSnapshotSet();
                }
                catch(RuntimeException exception)
                {
                    projectionFailed(run);
                }
                worker.start();
            }
        }
    }

    void stop()
    {
        Thread worker = null;

        synchronized(mLifecycleLock)
        {
            ProjectionRun run = mCurrentRun;

            if(run != null)
            {
                mCurrentRun = null;
                mRunGeneration.incrementAndGet();
                mActivitySource.removeListener(run.mListener);

                worker = run.mWorker;

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

            mChannelActivityHub.close();
            mEncodedChannelActivitySnapshot = null;
            mRowSystemScopeState = new RowSystemScopeState();
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

    StatsLiveEventHub.Subscription subscribeChannelActivity()
    {
        return subscribeChannelActivity(false);
    }

    StatsLiveEventHub.Subscription subscribeChannelActivity(boolean deltas)
    {
        return subscribeChannelActivity(deltas, false);
    }

    StatsLiveEventHub.Subscription subscribeChannelActivity(boolean deltas, boolean markers)
    {
        synchronized(mLifecycleLock)
        {
            return mCurrentRun != null ? mChannelActivityHub.subscribe(event ->
                event.markers() == markers &&
                !Objects.equals(event.name(), deltas ? "activity_table" : "activity_delta")) : null;
        }
    }

    void receiveChannelActivity(ChannelActivityEvent event)
    {
        ProjectionRun run = mCurrentRun;

        receiveChannelActivity(run, event);
    }

    private void receiveChannelActivity(ProjectionRun run, ChannelActivityEvent event)
    {
        if(!isCurrentRun(run) || event == null || event.snapshot() == null || event.operation() == null)
        {
            return;
        }

        if(!run.mPendingActivity.offer(event))
        {
            mDroppedProjectionEvents.incrementAndGet();
            run.mResyncRequired.set(true);
        }

        LockSupport.unpark(run.mWorker);
    }

    private void projectionLoop(ProjectionRun run)
    {
        while(isCurrentRun(run))
        {
            long retryDelay = run.mRecoveryRetryAfterNanos - System.nanoTime();
            if(run.mRecoveryRetryAfterNanos != 0 && retryDelay > 0)
            {
                // Producers can unpark this thread, but cannot advance the deadline for retrying invalid data.
                LockSupport.parkNanos(this, Math.min(retryDelay, 100_000_000L));
                continue;
            }
            try
            {
                // Seed one shared baseline before processing ordered notifications. Browser snapshots can be
                // newer than this baseline; revision checks skip already-covered notifications safely.
                if(run.mInitialSnapshot != null)
                {
                    rebuildBaseline(run, run.mInitialSnapshot, run.mPublishedNavigation, run.mPublishedRemoteOrigins);
                    run.mInitialSnapshot = null;
                }
                ChannelActivityEvent event = run.mPendingActivity.poll();
                if(run.mResyncRequired.getAndSet(false))
                {
                    for(int count = 0; count < run.mPendingActivity.capacity(); count++)
                    {
                        if(run.mPendingActivity.poll() == null) break;
                    }
                    publishAuthoritativeResync(run, navigationSnapshot(), remoteOriginSnapshot());
                    continue;
                }
                if(event == null)
                {
                    publishNavigationRefreshIfNeeded(run);
                    LockSupport.parkNanos(this, 100_000_000L);
                }
                else projectAndPublish(run, event);
            }
            catch(RuntimeException exception)
            {
                // Initial projection, recovery and navigation are observer work too. Keep the shared worker
                // available, release a bad startup snapshot, and rebuild from current data after a bounded retry.
                projectionFailed(run);
            }
        }
    }

    private void projectionFailed(ProjectionRun run)
    {
        mProjectionFailures.incrementAndGet();
        run.mInitialSnapshot = null;
        run.mResyncRequired.set(true);
        run.mRecoveryRetryAfterNanos = System.nanoTime() + PROJECTION_FAILURE_RETRY_NANOS;
    }

    private void projectAndPublish(ProjectionRun run, ChannelActivityEvent event)
    {
        publishNavigationRefreshIfNeeded(run);
        if(event.revision() > 0 && event.revision() <= run.mPublishedRevision)
        {
            return;
        }
        WebEntityNavigationCatalog.Snapshot navigation = run.mPublishedNavigation;
        RemoteOriginLookup.OriginSnapshot remoteOrigins = run.mPublishedRemoteOrigins;
        PreparedActivityEvent prepared = prepare(event, navigation, remoteOrigins, run.mRowSystemScopes,
            run.mSourceTables.get(event.snapshot().tableId()), run.mProjectedTables.get(event.snapshot().tableId()));

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
        update.put("base_revision", run.mPublishedRevision);
        long revision = ((Number)update.get("revision")).longValue();
        Map<String,Object> delta = activityDelta(prepared, run.mProjectedTables.get(prepared.tableId()),
            run.mPublishedRevision, revision);
        Map<String,Object> markerTable = prepared.table() != null ? markerTable(prepared.table(),
            run.mProjectedTables.get(prepared.tableId()), run.mMarkerTables.get(prepared.tableId())) : null;
        LinkedHashMap<String,Object> markerUpdate = new LinkedHashMap<>(update);
        if(markerTable != null) markerUpdate.put("table", markerTable);
        Map<String,Object> markerDelta = activityDelta(new PreparedActivityEvent(prepared.operation(),
            prepared.tableId(), markerTable), run.mMarkerTables.get(prepared.tableId()),
            run.mPublishedRevision, revision);
        synchronized(mLifecycleLock)
        {
            if(!isCurrentRun(run))
            {
                return;
            }

            mEncodedChannelActivitySnapshot = null;
            StatsLiveEventHub.LiveEvent complete = new StatsLiveEventHub.LiveEvent("activity_table", Map.copyOf(update));
            mChannelActivityHub.publish(complete);
            mChannelActivityHub.publish(new StatsLiveEventHub.LiveEvent("activity_delta", delta, complete));
            StatsLiveEventHub.LiveEvent compact = new StatsLiveEventHub.LiveEvent("activity_table",
                Map.copyOf(markerUpdate), null, true);
            mChannelActivityHub.publish(compact);
            mChannelActivityHub.publish(new StatsLiveEventHub.LiveEvent("activity_delta", markerDelta, compact, true));
            run.mPublishedRevision = revision;
            if(prepared.table() == null)
            {
                run.mProjectedTables.remove(prepared.tableId());
                run.mSourceTables.remove(prepared.tableId());
                run.mMarkerTables.remove(prepared.tableId());
            }
            else
            {
                run.mProjectedTables.put(prepared.tableId(), prepared.table());
                run.mSourceTables.put(prepared.tableId(), event.snapshot());
                run.mMarkerTables.put(prepared.tableId(), markerTable);
                while(run.mProjectedTables.size() > MAXIMUM_LIVE_TABLES)
                {
                    String eldest = run.mProjectedTables.keySet().iterator().next();
                    run.mProjectedTables.remove(eldest);
                    run.mSourceTables.remove(eldest);
                    run.mMarkerTables.remove(eldest);
                }
            }
        }
    }

    private void rebuildBaseline(ProjectionRun run, ChannelActivityModel.SnapshotSet source,
                                 WebEntityNavigationCatalog.Snapshot navigation,
                                 RemoteOriginLookup.OriginSnapshot origins)
    {
        run.mProjectedTables.clear();
        run.mSourceTables.clear();
        run.mMarkerTables.clear();
        source.tables().stream().filter(StatsLiveService::isVisibleLiveTable).limit(MAXIMUM_LIVE_TABLES)
            .forEach(table -> {
                run.mSourceTables.put(table.tableId(), table);
                Map<String,Object> projected = activityTable(table, MAXIMUM_ROWS_PER_TABLE,
                    navigation, origins, run.mRowSystemScopes);
                run.mProjectedTables.put(table.tableId(), projected);
                run.mMarkerTables.put(table.tableId(), markerTable(projected, null, null));
            });
        run.mPublishedRevision = source.revision();
        run.mPublishedNavigation = navigation;
        run.mPublishedRemoteOrigins = origins;
    }

    private void publishAuthoritativeResync(ProjectionRun run, WebEntityNavigationCatalog.Snapshot navigation,
                                            RemoteOriginLookup.OriginSnapshot origins)
    {
        // Sample activity after the navigation/origin references and retain those exact references. A catalog
        // change during projection stays visible to the next refresh instead of being silently adopted.
        rebuildBaseline(run, currentSnapshotSet(), navigation, origins);
        Map<String,Object> authoritative = boundedSnapshot(new ChannelActivityModel.SnapshotSet(run.mPublishedRevision,
            List.copyOf(run.mSourceTables.values())), MAXIMUM_TOTAL_LIVE_ROWS, run.mPublishedNavigation,
            run.mPublishedRemoteOrigins, run.mRowSystemScopes);
        synchronized(mLifecycleLock)
        {
            if(isCurrentRun(run))
            {
                mEncodedChannelActivitySnapshot = null;
                mChannelActivityHub.publish("activity_resync", Map.of("snapshot", authoritative));
                mChannelActivityHub.publish(new StatsLiveEventHub.LiveEvent("activity_resync",
                    Map.of("snapshot", markerSnapshot(authoritative)), null, true));
            }
        }
    }

    /** Full changed rows keep field removal unambiguous while unchanged rows remain in the browser baseline. */
    static Map<String,Object> activityDelta(PreparedActivityEvent prepared, Map<String,Object> previous,
                                            long baseRevision, long revision)
    {
        LinkedHashMap<String,Object> delta = new LinkedHashMap<>();
        delta.put("operation", prepared.operation().name().toLowerCase());
        delta.put("table_id", prepared.tableId());
        delta.put("base_revision", baseRevision);
        delta.put("revision", revision);
        if(prepared.table() != null)
        {
            Map<String,Object> current = prepared.table();
            LinkedHashMap<String,Object> metadata = new LinkedHashMap<>(current);
            metadata.remove("rows");
            LinkedHashMap<String,Object> oldMetadata = previous != null ? new LinkedHashMap<>(previous) : null;
            if(oldMetadata != null) oldMetadata.remove("rows");
            if(!metadata.equals(oldMetadata)) delta.put("table", Map.copyOf(metadata));
            Map<String,Map<String,Object>> oldRows = rowIndex(previous);
            Map<String,Map<String,Object>> newRows = rowIndex(current);
            delta.put("rows", newRows.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(oldRows.get(entry.getKey())))
                .map(Map.Entry::getValue).toList());
            delta.put("removed_row_keys", oldRows.keySet().stream().filter(key -> !newRows.containsKey(key)).toList());
            if(!List.copyOf(oldRows.keySet()).equals(List.copyOf(newRows.keySet())))
            {
                delta.put("row_order", List.copyOf(newRows.keySet()));
            }
        }
        return Map.copyOf(delta);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Map<String,Object>> rowIndex(Map<String,Object> table)
    {
        LinkedHashMap<String,Map<String,Object>> rows = new LinkedHashMap<>();
        if(table != null && table.get("rows") instanceof List<?> values)
        {
            for(Object value: values)
            {
                Map<String,Object> row = (Map<String,Object>)value;
                rows.put(String.valueOf(row.get("key")), row);
            }
        }
        return rows;
    }

    private static final Set<String> NON_MARKER_FIELDS = Set.of("activation_order", "tags_truncated",
        "cc_valid_frames", "cc_invalid_frames", "cc_corrected_bits", "cc_sync_loss_bits", "cc_dropped_bits",
        "cc_last_valid_decode_ms", "quality_observed_at_ms", "vc_decoded_frames", "vc_repeated_frames",
        "vc_concealed_frames", "vc_missing_frames", "vc_fec_errors", "vc_fec_protected_bits",
        "call_leg_id", "tx_state", "tx_observed_at_ms", "tx_start_ms", "tx_last_observed_at_ms", "tx_end_certain",
        "tx_burst_generation", "tx_burst_started_at_ms", "source_aliases", "target_aliases",
        "alias_list_id", "alias_list_name");

    /** Spectrum hover keeps aliases, identities and navigation; raw decode/transmission counters are unrelated. */
    static Map<String,Object> markerTable(Map<String,Object> table, Map<String,Object> previous,
                                           Map<String,Object> previousMarker)
    {
        LinkedHashMap<String,Object> compact = new LinkedHashMap<>(table);
        Map<String,Map<String,Object>> oldRows = rowIndex(previous);
        Map<String,Map<String,Object>> oldMarkers = rowIndex(previousMarker);
        List<Map<String,Object>> rows = rowIndex(table).entrySet().stream().map(entry -> {
            if(entry.getValue() == oldRows.get(entry.getKey()) && oldMarkers.containsKey(entry.getKey()))
            {
                return oldMarkers.get(entry.getKey());
            }
            LinkedHashMap<String,Object> row = new LinkedHashMap<>(entry.getValue());
            NON_MARKER_FIELDS.forEach(row::remove);
            return Map.copyOf(row);
        }).toList();
        compact.put("rows", rows);
        return Map.copyOf(compact);
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> markerSnapshot(Map<String,Object> snapshot)
    {
        LinkedHashMap<String,Object> compact = new LinkedHashMap<>(snapshot);
        compact.put("tables", ((List<Map<String,Object>>)snapshot.get("tables")).stream()
            .map(table -> markerTable(table, null, null)).toList());
        return Map.copyOf(compact);
    }

    private void publishNavigationRefreshIfNeeded(ProjectionRun run)
    {
        WebEntityNavigationCatalog.Snapshot navigation = navigationSnapshot();
        RemoteOriginLookup.OriginSnapshot remoteOrigins = remoteOriginSnapshot();

        if(run.mPublishedNavigation == navigation && run.mPublishedRemoteOrigins == remoteOrigins)
        {
            return;
        }

        publishAuthoritativeResync(run, navigation, remoteOrigins);
    }

    private boolean isCurrentRun(ProjectionRun run)
    {
        return run != null && mCurrentRun == run && mRunGeneration.get() == run.mGeneration;
    }

    long droppedProjectionEvents()
    {
        return mDroppedProjectionEvents.get();
    }

    long projectionFailures()
    {
        return mProjectionFailures.get();
    }

    long droppedActivityIngressEvents()
    {
        return mActivitySource.droppedIngressCount();
    }

    Map<String,Object> snapshot()
    {
        return snapshot(currentSnapshotSet(), MAXIMUM_TOTAL_LIVE_ROWS, navigationSnapshot(), remoteOriginSnapshot(),
            mRowSystemScopeState);
    }

    /** Initial, recovery and navigation snapshots share one byte budget, including a metadata-only fallback. */
    private Map<String,Object> boundedSnapshot(ChannelActivityModel.SnapshotSet source, int maximumRows,
                                               WebEntityNavigationCatalog.Snapshot navigation,
                                               RemoteOriginLookup.OriginSnapshot origins, RowSystemScopeState scopes)
    {
        try
        {
            Map<String,Object> full = snapshot(source, maximumRows, navigation, origins, scopes);
            if(snapshotFits(full)) return full;
            Map<String,Object> metadata = snapshot(source, 0, navigation, origins, scopes);
            boolean metadataFits = snapshotFits(metadata);
            int low = 1;
            int high = maximumRows - 1;
            Map<String,Object> best = metadataFits ? metadata : null;
            while(metadataFits && low <= high)
            {
                int limit = low + (high - low) / 2;
                Map<String,Object> candidate = snapshot(source, limit, navigation, origins, scopes);
                if(snapshotFits(candidate))
                {
                    best = candidate;
                    low = limit + 1;
                }
                else high = limit - 1;
            }
            if(best != null) return best;

            // Even zero rows can exceed the budget when many tables carry long learned identifiers. Keep every
            // table if possible, explicitly report omitted identifiers, and retain the largest fitting prefix.
            low = 0;
            high = MAXIMUM_LIVE_IDENTIFIERS - 1;
            while(low <= high)
            {
                int limit = low + (high - low) / 2;
                Map<String,Object> candidate = snapshot(source, 0, MAXIMUM_LIVE_TABLES, limit,
                    navigation, origins, scopes);
                if(snapshotFits(candidate))
                {
                    best = candidate;
                    low = limit + 1;
                }
                else high = limit - 1;
            }
            if(best != null) return best;

            // Pathological table labels/references must not make the shared observer unavailable either. The
            // ordinary omission counts expose the bounded table prefix, including an empty snapshot if needed.
            low = 0;
            high = Math.min(source.tables().size(), MAXIMUM_LIVE_TABLES) - 1;
            while(low <= high)
            {
                int limit = low + (high - low) / 2;
                Map<String,Object> candidate = snapshot(source, 0, limit, 0, navigation, origins, scopes);
                if(snapshotFits(candidate))
                {
                    best = candidate;
                    low = limit + 1;
                }
                else high = limit - 1;
            }
            if(best != null) return best;
        }
        catch(IOException exception)
        {
            throw new IllegalStateException("Live snapshot encoding failed", exception);
        }
        throw new IllegalStateException("Live snapshot envelope exceeds its byte budget");
    }

    private static boolean snapshotFits(Map<String,Object> snapshot) throws IOException
    {
        // Leave room for the enclosing multiplex event object, which has the same bounded payload contract.
        return ApiHttpResponse.encodePayload(StatsApiV1Payload.present(snapshot)).length <=
            MAXIMUM_SYSTEM_SNAPSHOT_BYTES - 64;
    }

    private Map<String,Object> snapshot(ChannelActivityModel.SnapshotSet source, int maximumRows,
                                        WebEntityNavigationCatalog.Snapshot navigation,
                                        RemoteOriginLookup.OriginSnapshot remoteOrigins,
                                        RowSystemScopeState rowSystemScopes)
    {
        return snapshot(source, maximumRows, MAXIMUM_LIVE_TABLES, MAXIMUM_LIVE_IDENTIFIERS,
            navigation, remoteOrigins, rowSystemScopes);
    }

    private Map<String,Object> snapshot(ChannelActivityModel.SnapshotSet source, int maximumRows,
                                        int maximumTables, int maximumIdentifiers,
                                        WebEntityNavigationCatalog.Snapshot navigation,
                                        RemoteOriginLookup.OriginSnapshot remoteOrigins,
                                        RowSystemScopeState rowSystemScopes)
    {
        List<ChannelActivitySnapshot> snapshots = source.tables().stream()
            .filter(StatsLiveService::isVisibleLiveTable).sorted(Comparator
            .comparingInt((ChannelActivitySnapshot table) -> "conventional".equals(table.tableId()) ? 0 : 1)
            .thenComparing(ChannelActivitySnapshot::tableId)).toList();
        List<Map<String,Object>> tables = new ArrayList<>(Math.min(snapshots.size(), MAXIMUM_LIVE_TABLES));
        int rowsIncluded = 0;
        long rowsTotal = snapshots.stream().mapToLong(table -> table.rows().size()).sum();
        int tableCount = Math.min(snapshots.size(), maximumTables);
        long identifiersOmitted = 0;

        for(int index = 0; index < tableCount; index++)
        {
            ChannelActivitySnapshot table = snapshots.get(index);
            int available = Math.max(0, maximumRows - rowsIncluded);
            int rowLimit = Math.min(MAXIMUM_ROWS_PER_TABLE, available);
            Map<String,Object> projected = activityTable(table, rowLimit, navigation, remoteOrigins,
                rowSystemScopes);
            if(maximumIdentifiers < MAXIMUM_LIVE_IDENTIFIERS)
            {
                LinkedHashMap<String,Object> bounded = new LinkedHashMap<>(projected);
                List<?> identifiers = (List<?>)projected.get("identifiers");
                int included = Math.min(identifiers.size(), maximumIdentifiers);
                long omitted = Math.max(0L, (long)table.identifiers().size() - included);
                bounded.put("identifiers", List.copyOf(identifiers.subList(0, included)));
                bounded.put("identifiers_total", table.identifiers().size());
                bounded.put("identifiers_included", included);
                bounded.put("identifiers_omitted", omitted);
                bounded.put("identifiers_truncated", omitted > 0);
                identifiersOmitted += omitted;
                projected = Map.copyOf(bounded);
            }
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
        response.put("truncated", snapshots.size() > tableCount || rowsTotal > rowsIncluded || identifiersOmitted > 0);
        if(maximumIdentifiers < MAXIMUM_LIVE_IDENTIFIERS)
        {
            response.put("metadata_truncated", identifiersOmitted > 0 || snapshots.size() > tableCount);
            response.put("identifiers_omitted", identifiersOmitted);
        }
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
        return encodedSnapshotState().payload();
    }

    LiveMultiplexFrame snapshotFrame() throws IOException
    {
        return encodedSnapshotState().frame();
    }

    SnapshotWire snapshotWire() throws IOException
    {
        return snapshotWire(false);
    }

    SnapshotWire snapshotWire(boolean markers) throws IOException
    {
        EncodedSnapshot snapshot = encodedSnapshotState();
        return new SnapshotWire(snapshot.revision(), markers ? snapshot.markerFrame() : snapshot.frame());
    }

    record SnapshotWire(long revision, LiveMultiplexFrame frame)
    {
    }

    private EncodedSnapshot encodedSnapshotState() throws IOException
    {
        long generation = mRunGeneration.get();
        ChannelActivityModel.SnapshotSet source = currentSnapshotSet();
        WebEntityNavigationCatalog.Snapshot navigation = navigationSnapshot();
        RemoteOriginLookup.OriginSnapshot remoteOrigins = remoteOriginSnapshot();
        RowSystemScopeState rowSystemScopes = mRowSystemScopeState;
        EncodedSnapshot cached = mEncodedChannelActivitySnapshot;

        if(cached != null && cached.generation() == generation && cached.revision() == source.revision() &&
            cached.navigation() == navigation && cached.remoteOrigins() == remoteOrigins)
        {
            return cached;
        }

        synchronized(mEncodedSnapshotLock)
        {
            cached = mEncodedChannelActivitySnapshot;

            if(cached != null && cached.generation() == generation && cached.revision() == source.revision() &&
                cached.navigation() == navigation && cached.remoteOrigins() == remoteOrigins)
            {
                return cached;
            }

            Map<String,Object> bestProjection = boundedSnapshot(source, MAXIMUM_TOTAL_LIVE_ROWS, navigation,
                remoteOrigins, rowSystemScopes);
            JsonNode bestDocument = StatsApiV1Payload.present(bestProjection);
            byte[] best = ApiHttpResponse.encodePayload(bestDocument);

            EncodedSnapshot encoded = new EncodedSnapshot(generation, source.revision(), navigation,
                remoteOrigins, best, LiveMultiplexFrame.json(1, "snapshot", bestDocument),
                LiveMultiplexFrame.json(1, "snapshot", StatsApiV1Payload.present(markerSnapshot(bestProjection))));

            if(mRunGeneration.get() == generation && mRowSystemScopeState == rowSystemScopes)
            {
                mEncodedChannelActivitySnapshot = encoded;
            }

            return encoded;
        }
    }

    private ChannelActivityModel.SnapshotSet currentSnapshotSet()
    {
        return mActivitySource.snapshot();
    }

    private PreparedActivityEvent prepare(ChannelActivityEvent event,
                                          WebEntityNavigationCatalog.Snapshot navigation,
                                          RemoteOriginLookup.OriginSnapshot remoteOrigins,
                                          RowSystemScopeState rowSystemScopes,
                                          ChannelActivitySnapshot previousSource, Map<String,Object> previousTable)
    {
        String tableId = boundedText(event.snapshot().tableId(), MAXIMUM_LIVE_TEXT_LENGTH);

        if(tableId.isBlank())
        {
            return null;
        }

        Map<String,Object> table;
        if(event.operation() == ChannelActivityEvent.Operation.REMOVE)
        {
            clearRowSystemScopes(rowSystemScopes, tableId);
            table = null;
        }
        else
        {
            table = activityTable(event.snapshot(), MAXIMUM_ROWS_PER_TABLE, navigation, remoteOrigins,
                rowSystemScopes, previousSource, previousTable);
        }
        return new PreparedActivityEvent(event.operation(), tableId, table);
    }

    private WebEntityNavigationCatalog.Snapshot navigationSnapshot()
    {
        return mNavigationCatalog != null ? mNavigationCatalog.snapshot() :
            WebEntityNavigationCatalog.Snapshot.empty();
    }

    private RemoteOriginLookup.OriginSnapshot remoteOriginSnapshot()
    {
        try
        {
            RemoteOriginLookup.OriginSnapshot snapshot = mRemoteOriginLookup.originSnapshot();
            return snapshot != null ? snapshot : RemoteOriginLookup.OriginSnapshot.EMPTY;
        }
        catch(RuntimeException exception)
        {
            //Remote administration must never make the live projection unavailable.
            return RemoteOriginLookup.OriginSnapshot.EMPTY;
        }
    }

    private Map<String,Object> activityTable(ChannelActivitySnapshot snapshot, int maximumRows,
                                             WebEntityNavigationCatalog.Snapshot navigation,
                                             RemoteOriginLookup.OriginSnapshot remoteOrigins,
                                             RowSystemScopeState rowSystemScopes)
    {
        return activityTable(snapshot, maximumRows, navigation, remoteOrigins, rowSystemScopes, null, null);
    }

    private Map<String,Object> activityTable(ChannelActivitySnapshot snapshot, int maximumRows,
                                             WebEntityNavigationCatalog.Snapshot navigation,
                                             RemoteOriginLookup.OriginSnapshot remoteOrigins,
                                             RowSystemScopeState rowSystemScopes,
                                             ChannelActivitySnapshot previousSource, Map<String,Object> previousTable)
    {
        LinkedHashMap<String,Object> table = new LinkedHashMap<>();
        WebEntityNavigationCatalog.Channel tableChannel = navigation.channel(snapshot.configurationId());
        WebEntityNavigationCatalog.Channel tableSystemChannel =
            matchesStructuredP25Site(tableChannel, snapshot.site()) ? tableChannel : null;
        table.put("table_id", boundedText(snapshot.tableId(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("title", boundedText(snapshot.title(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("system_name", boundedText(snapshot.systemName(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("site_name", boundedText(snapshot.siteName(), MAXIMUM_LIVE_TEXT_LENGTH));
        table.put("channel_name", boundedText(snapshot.channelName(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(table, "configuration_id", snapshot.configurationId(), MAXIMUM_LIVE_TEXT_LENGTH);
        putRemoteOrigin(table, remoteOrigins.find(snapshot.configurationId()));
        WebEntityRef.put(table, tableChannel != null ? tableChannel.entityRef() : null);
        if(tableSystemChannel != null && tableSystemChannel.radioSystemRef() != null)
        {
            putText(table, "radio_system_key", tableSystemChannel.radioSystemRef().key(), MAXIMUM_LIVE_TEXT_LENGTH);
        }
        if(snapshot.site() != null)
        {
            LinkedHashMap<String,Object> site = new LinkedHashMap<>();
            put(site, "wacn", snapshot.site().wacn());
            put(site, "system_id", snapshot.site().systemId());
            put(site, "rfss", snapshot.site().rfss());
            put(site, "site", snapshot.site().siteId());
            put(site, "nac", snapshot.site().nac());
            if(!site.isEmpty())
            {
                table.put("site", Map.copyOf(site));
            }
        }
        table.put("control_active", snapshot.controlActive());
        table.put("channel_running", snapshot.channelRunning());
        table.put("identifiers", snapshot.identifiers().stream().limit(MAXIMUM_LIVE_IDENTIFIERS)
            .map(StatsLiveService::activityIdentifier).toList());
        int rowCount = snapshot.rows().size();
        int included = Math.min(rowCount, Math.max(0, maximumRows));
        LinkedHashMap<String,ChannelActivitySnapshot.Row> oldSources = new LinkedHashMap<>();
        if(previousSource != null)
        {
            previousSource.rows().stream().limit(MAXIMUM_ROWS_PER_TABLE)
                .forEach(row -> oldSources.put(row.key(), row));
        }
        Map<String,Map<String,Object>> oldRows = rowIndex(previousTable);
        boolean sameSite = previousSource != null && Objects.equals(snapshot.site(), previousSource.site()) &&
            Objects.equals(snapshot.configurationId(), previousSource.configurationId());
        table.put("rows", snapshot.rows().stream().limit(included)
            .map(row -> sameSite && row.equals(oldSources.get(row.key())) && oldRows.containsKey(row.key()) ?
                oldRows.get(row.key()) : activityRow(snapshot.tableId(), row, tableChannel, snapshot.site(), navigation,
                    remoteOrigins, rowSystemScopes)).toList());
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

    private Map<String,Object> activityRow(String tableId, ChannelActivitySnapshot.Row snapshot,
                                           WebEntityNavigationCatalog.Channel tableChannel,
                                           ChannelActivitySnapshot.Site site,
                                           WebEntityNavigationCatalog.Snapshot catalog,
                                           RemoteOriginLookup.OriginSnapshot remoteOrigins,
                                           RowSystemScopeState rowSystemScopes)
    {
        LinkedHashMap<String,Object> row = new LinkedHashMap<>();
        ChannelActivitySnapshot.Navigation navigation = snapshot.navigation();
        String configurationId = navigation != null && navigation.channelConfigurationId() != null &&
            !navigation.channelConfigurationId().isBlank() ? navigation.channelConfigurationId() :
            snapshot.configurationId();
        if((configurationId == null || configurationId.isBlank()) && tableChannel != null)
        {
            configurationId = tableChannel.configurationId();
        }
        WebEntityNavigationCatalog.Channel rowChannel = catalog.channel(configurationId);

        if(rowChannel == null && tableChannel != null &&
            Objects.equals(configurationId, tableChannel.configurationId()))
        {
            rowChannel = tableChannel;
        }
        WebEntityNavigationCatalog.Channel rowSystemChannel =
            matchesStructuredP25Site(rowChannel, site) ? rowChannel : null;
        boolean systemScopeMatches = rowSystemScopeMatches(rowSystemScopes, tableId, snapshot, configurationId,
            rowSystemChannel);

        row.put("key", boundedText(snapshot.key(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(row, "channel_name", snapshot.channelName(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(row, "configuration_id", configurationId, MAXIMUM_LIVE_TEXT_LENGTH);
        putRemoteOrigin(row, remoteOrigins.find(configurationId));
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
        put(row, "bandwidth_hz", snapshot.bandwidthHz());
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

        if(snapshot.transmission() != null)
        {
            putText(row, "call_leg_id", snapshot.transmission().callLegId(), MAXIMUM_LIVE_TEXT_LENGTH);
            putText(row, "tx_state", snapshot.transmission().state(), MAXIMUM_LIVE_TEXT_LENGTH);
            row.put("tx_observed_at_ms", snapshot.transmission().observedAtMs());
            row.put("tx_start_ms", snapshot.transmission().startMs());
            row.put("tx_last_observed_at_ms", snapshot.transmission().lastObservedAtMs());
            row.put("tx_end_certain", snapshot.transmission().endCertain());
            row.put("tx_burst_generation", snapshot.transmission().burstGeneration());
            row.put("tx_burst_started_at_ms", snapshot.transmission().burstStartMs());
        }

        if(navigation != null)
        {
            put(row, "alias_list_id", navigation.aliasListId());
            putText(row, "alias_list_name", navigation.aliasListName(), MAXIMUM_LIVE_TEXT_LENGTH);
            putText(row, "protocol", navigation.protocol(), MAXIMUM_LIVE_TEXT_LENGTH);
            putP25SubscriberIdentity(row, "source", navigation.sourceMatcher());
            putP25SubscriberIdentity(row, "target", navigation.targetMatcher());
            row.put("source_aliases", navigation.sourceAliases().stream().limit(MAXIMUM_LIVE_ALIAS_REFERENCES)
                .map(StatsLiveService::activityAliasReference).toList());
            row.put("target_aliases", navigation.targetAliases().stream().limit(MAXIMUM_LIVE_ALIAS_REFERENCES)
                .map(StatsLiveService::activityAliasReference).toList());
            WebEntityRef sourceReference = null;
            WebEntityRef targetReference = null;

            if(rowSystemChannel != null && systemScopeMatches)
            {
                sourceReference = rowSystemChannel.identity(navigation.sourceMatcher());
                targetReference = rowSystemChannel.identity(navigation.targetMatcher());
            }

            if(sourceReference != null)
            {
                row.put("source_entity_ref", sourceReference.toMap());
            }
            else
            {
                putText(row, "source_identity_key", channelScopedIdentityKey(configurationId,
                    navigation.sourceMatcher()), MAXIMUM_LIVE_TEXT_LENGTH);
            }

            if(targetReference != null)
            {
                row.put("target_entity_ref", targetReference.toMap());
            }
            else
            {
                putText(row, "target_identity_key", channelScopedIdentityKey(configurationId,
                    navigation.targetMatcher()), MAXIMUM_LIVE_TEXT_LENGTH);
            }
        }

        return Map.copyOf(row);
    }

    static boolean matchesStructuredP25Site(WebEntityNavigationCatalog.Channel channel,
                                            ChannelActivitySnapshot.Site site)
    {
        if(site == null || (site.wacn() == null && site.systemId() == null))
        {
            return true;
        }

        return channel != null && channel.hasCompatibleP25SystemScope(site.wacn(), site.systemId());
    }

    private static String channelScopedIdentityKey(String configurationId,
                                                   ChannelActivitySnapshot.MatcherReference matcher)
    {
        if(matcher == null || matcher.type() == null)
        {
            return null;
        }

        Form form = switch(matcher.type())
        {
            case "radio" -> Form.RADIO;
            case "talkgroup" -> Form.TALKGROUP;
            case "patch_group" -> Form.PATCH_GROUP;
            default -> null;
        };

        return WebIdentityKey.channelScoped(configurationId, matcher.protocol(), form, matcher.value(),
            matcher.identityKey());
    }

    /**
     * Publishes canonical P25 subscriber components only when the immutable receiver snapshot explicitly carries a
     * fully-qualified radio identity key.  Local radio matchers have no identity key and must remain working IDs;
     * this projection deliberately performs no radio-system or database inference.
     */
    private static void putP25SubscriberIdentity(Map<String,Object> row, String prefix,
                                                  ChannelActivitySnapshot.MatcherReference matcher)
    {
        if(matcher != null && "radio".equals(matcher.type()) && "p25".equals(matcher.protocol()))
        {
            row.put(prefix + "_identity_source", matcher.radioIdentity() != null ?
                switch(matcher.radioIdentity().evidence())
                {
                    case DIRECT -> "explicit_identity";
                    case CONFIRMED_ASSIGNMENT -> "registration_mapping";
                    case UNRESOLVED -> "working_id";
                    case UNKNOWN -> "unresolved";
                } : "unresolved");
        }
        if(matcher == null || !"radio".equals(matcher.type()) || !"p25".equals(matcher.protocol()) ||
            matcher.identityKey() == null)
        {
            return;
        }

        try
        {
            RadioSystemIdentityKey.Identity identity = RadioSystemIdentityKey.parse(matcher.identityKey());
            if(identity.kindCode() != RadioSystemIdentityKey.KIND_RADIO || !identity.hasHome())
            {
                return;
            }

            row.put(prefix + "_canonical_identity", Map.of(
                "wacn", identity.homeWacn(),
                "system_id", identity.homeSystemId(),
                "subscriber_id", identity.identityId()));
            Integer workingAddress = matcher.workingAddress();
            if(workingAddress != null && workingAddress > 0 &&
                workingAddress <= RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID)
            {
                row.put(prefix + "_observed_working_id", workingAddress);
            }
        }
        catch(IllegalArgumentException exception)
        {
            //Malformed or non-canonical matcher keys remain local working identifiers.
        }
    }

    /**
     * Freezes a live row's radio-system owner for one activation.  Catalog refreshes may enrich an unowned row, but
     * cannot silently move an already-rendered call from system A to system B while the retained row is still active.
     */
    private boolean rowSystemScopeMatches(RowSystemScopeState rowSystemScopes, String tableId,
                                          ChannelActivitySnapshot.Row row, String configurationId,
                                          WebEntityNavigationCatalog.Channel channel)
    {
        RowScopeKey key = new RowScopeKey(boundedText(tableId, MAXIMUM_LIVE_TEXT_LENGTH),
            boundedText(row.key(), MAXIMUM_LIVE_TEXT_LENGTH),
            boundedText(configurationId, MAXIMUM_LIVE_TEXT_LENGTH));
        String currentRadioSystemKey = channel != null && channel.radioSystemRef() != null ?
            channel.radioSystemRef().key() : null;

        synchronized(rowSystemScopes.mLock)
        {
            LinkedHashMap<RowScopeKey,RowSystemScope> scopes = rowSystemScopes.mScopes;
            RowSystemScope scope = scopes.get(key);
            if(scope != null && row.activationOrder() > 0 && scope.activationOrder() > 0 &&
                row.activationOrder() < scope.activationOrder())
            {
                //A concurrent caller projected an older immutable snapshot after a newer activation.  Never let it
                //move ownership backward or borrow the current catalog's system identity.
                return false;
            }
            if(scope == null || row.activationOrder() > 0 && row.activationOrder() != scope.activationOrder())
            {
                scope = new RowSystemScope(row.activationOrder(), currentRadioSystemKey);
                scopes.put(key, scope);
            }
            else if(scope.radioSystemKey() == null && currentRadioSystemKey != null)
            {
                scope = new RowSystemScope(scope.activationOrder(), currentRadioSystemKey);
                scopes.put(key, scope);
            }

            while(scopes.size() > MAXIMUM_ROW_SYSTEM_SCOPES)
            {
                scopes.remove(scopes.keySet().iterator().next());
            }
            return Objects.equals(scope.radioSystemKey(), currentRadioSystemKey);
        }
    }

    private void clearRowSystemScopes(RowSystemScopeState rowSystemScopes, String tableId)
    {
        synchronized(rowSystemScopes.mLock)
        {
            rowSystemScopes.mScopes.keySet().removeIf(key -> key.tableId().equals(tableId));
        }
    }

    private static Map<String,Object> activityAliasReference(ChannelActivitySnapshot.AliasReference reference)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("alias_id", reference.aliasId());
        value.put("alias_list_id", reference.aliasListId());
        value.put("name", boundedText(reference.name(), MAXIMUM_LIVE_TEXT_LENGTH));
        return Map.copyOf(value);
    }

    private static void putRemoteOrigin(Map<String,Object> values, RemoteOriginLookup.RemoteOrigin origin)
    {
        if(origin == null)
        {
            return;
        }

        // Live can be available without administrator authentication. Keep sender/feed identities and link health in
        // the administrator-only Remote Links API while retaining the generic marker used for the cloud badge.
        values.put("remote_origin", Map.of("remote", true));
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

    record PreparedActivityEvent(ChannelActivityEvent.Operation operation, String tableId,
                                         Map<String,Object> table)
    {
    }

    private record EncodedSnapshot(long generation, long revision,
                                   WebEntityNavigationCatalog.Snapshot navigation,
                                   RemoteOriginLookup.OriginSnapshot remoteOrigins,
                                   byte[] payload, LiveMultiplexFrame frame, LiveMultiplexFrame markerFrame)
    {
    }

    private record RowScopeKey(String tableId, String rowKey, String configurationId)
    {
    }

    private record RowSystemScope(long activationOrder, String radioSystemKey)
    {
    }

    /**
     * All mutable projection state belongs to one start/stop generation.  A worker that outlives stop's bounded join
     * can finish only against this retired object and cannot consume work or alter row ownership for a later run.
     */
    private final class ProjectionRun
    {
        private final long mGeneration;
        private final RowSystemScopeState mRowSystemScopes;
        private ChannelActivityModel.SnapshotSet mInitialSnapshot;
        private final BoundedMpscReferenceQueue<ChannelActivityEvent> mPendingActivity =
            new BoundedMpscReferenceQueue<>(256);
        private final LinkedHashMap<String,Map<String,Object>> mProjectedTables = new LinkedHashMap<>();
        private final LinkedHashMap<String,ChannelActivitySnapshot> mSourceTables = new LinkedHashMap<>();
        private final LinkedHashMap<String,Map<String,Object>> mMarkerTables = new LinkedHashMap<>();
        private long mPublishedRevision;
        private long mRecoveryRetryAfterNanos;
        private final AtomicBoolean mResyncRequired = new AtomicBoolean();
        private final Listener<ChannelActivityEvent> mListener = event -> receiveChannelActivity(this, event);
        private volatile WebEntityNavigationCatalog.Snapshot mPublishedNavigation;
        private volatile RemoteOriginLookup.OriginSnapshot mPublishedRemoteOrigins;
        private volatile Thread mWorker;

        private ProjectionRun(long generation, RowSystemScopeState rowSystemScopes,
                              WebEntityNavigationCatalog.Snapshot publishedNavigation,
                              RemoteOriginLookup.OriginSnapshot publishedRemoteOrigins)
        {
            mGeneration = generation;
            mRowSystemScopes = rowSystemScopes;
            mPublishedNavigation = publishedNavigation;
            mPublishedRemoteOrigins = publishedRemoteOrigins;
        }
    }

    /** Mutable row ownership cache that is replaced, rather than reused, at each service generation boundary. */
    private static final class RowSystemScopeState
    {
        private final Object mLock = new Object();
        private final LinkedHashMap<RowScopeKey,RowSystemScope> mScopes =
            new LinkedHashMap<>(MAXIMUM_ROW_SYSTEM_SCOPES, 0.75f, true);
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

        default long droppedIngressCount()
        {
            return 0;
        }

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
        public long droppedIngressCount()
        {
            return model.getDroppedIngressCount();
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
