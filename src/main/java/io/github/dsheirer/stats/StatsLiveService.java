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
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataListener;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Lightweight owner of live Systems and committed activity events for the Stats Server.
 */
final class StatsLiveService implements AutoCloseable, ProtocolSiteMetadataListener
{
    private static final int MAXIMUM_SSE_CLIENTS = 32;
    private static final int SSE_QUEUE_CAPACITY = 256;
    static final long SITE_METADATA_LIVE_MILLISECONDS = 30_000;
    static final long QUALITY_LIVE_MILLISECONDS = 45_000;
    private final StatsWebDatabase mDatabase;
    private final ChannelActivityModel mActivityModel;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final LongSupplier mClock;
    private final StatsLiveEventHub mSystemsHub = new StatsLiveEventHub(MAXIMUM_SSE_CLIENTS, SSE_QUEUE_CAPACITY);
    private final StatsLiveEventHub mSitesHub = new StatsLiveEventHub(MAXIMUM_SSE_CLIENTS, SSE_QUEUE_CAPACITY);
    private final StatsLiveEventHub mActivityHub = new StatsLiveEventHub(MAXIMUM_SSE_CLIENTS, SSE_QUEUE_CAPACITY);
    private final Map<String,Map<String,Object>> mTables = new ConcurrentHashMap<>();
    private final Map<String,Map<String,Object>> mSites = new ConcurrentHashMap<>();
    private final Map<String,Map<String,Object>> mQualityByGuid = new ConcurrentHashMap<>();
    private final Map<String,Long> mSiteReceivedAtByGuid = new ConcurrentHashMap<>();
    private final Map<String,Long> mQualityReceivedAtByGuid = new ConcurrentHashMap<>();
    private final ExecutorService mEventExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2048), new NamingThreadFactory("stats live events"),
        new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ScheduledExecutorService mSweepExecutor = Executors.newSingleThreadScheduledExecutor(
        new NamingThreadFactory("stats live expiry"));
    private final Listener<ChannelActivityEvent> mChannelActivityListener = event -> execute(() -> process(event));
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private volatile ScheduledFuture<?> mSweepTask;

    StatsLiveService(StatsWebDatabase database, ChannelProcessingManager channelProcessingManager)
    {
        this(database, channelProcessingManager, System::currentTimeMillis);
    }

    StatsLiveService(StatsWebDatabase database, ChannelProcessingManager channelProcessingManager, LongSupplier clock)
    {
        mDatabase = database;
        mChannelProcessingManager = channelProcessingManager;
        mActivityModel = channelProcessingManager != null ? channelProcessingManager.getChannelActivityModel() : null;
        mClock = clock != null ? clock : System::currentTimeMillis;
    }

    void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
            if(mActivityModel != null)
            {
                mActivityModel.addActivityListener(mChannelActivityListener);
            }

            if(mChannelProcessingManager != null)
            {
                mChannelProcessingManager.addProtocolSiteMetadataListener(this);
            }

            mSweepTask = mSweepExecutor.scheduleAtFixedRate(() -> execute(this::sweepExpired),
                5, 5, TimeUnit.SECONDS);
        }
    }

    void stop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            ScheduledFuture<?> sweepTask = mSweepTask;
            mSweepTask = null;

            if(sweepTask != null)
            {
                sweepTask.cancel(false);
            }

            if(mActivityModel != null)
            {
                mActivityModel.removeActivityListener(mChannelActivityListener);
            }

            if(mChannelProcessingManager != null)
            {
                mChannelProcessingManager.removeProtocolSiteMetadataListener(this);
            }
        }

        mTables.clear();
        mSites.clear();
        mQualityByGuid.clear();
        mSiteReceivedAtByGuid.clear();
        mQualityReceivedAtByGuid.clear();
    }

    void activityCommitted(List<Long> rowIds)
    {
        if(rowIds != null && !rowIds.isEmpty() && mActivityHub.hasSubscribers())
        {
            List<Long> committed = List.copyOf(rowIds);
            execute(() -> {
                for(Map<String,Object> row: mDatabase.activityByIds(committed))
                {
                    mActivityHub.publish("activity", row);
                }
            });
        }
    }

    StatsLiveEventHub.Subscription subscribeSystems()
    {
        return mSystemsHub.subscribe();
    }

    StatsLiveEventHub.Subscription subscribeActivity()
    {
        return mActivityHub.subscribe();
    }

    StatsLiveEventHub.Subscription subscribeSites()
    {
        return mSitesHub.subscribe();
    }

    Map<String,Object> snapshot()
    {
        List<Map<String,Object>> tables = new ArrayList<>(mTables.values());
        tables.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault("table_id", ""))));
        return Map.of("tables", tables);
    }

    Map<String,Object> siteSnapshot()
    {
        List<Map<String,Object>> sites = new ArrayList<>(mSites.values());
        sites.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault("guid", ""))));
        return Map.of("sites", sites);
    }

    @Override
    public void receiveProtocolSiteMetadata(ProtocolSiteMetadataEvent event)
    {
        if(mRunning.get())
        {
            execute(() -> process(event));
        }
    }

    private void execute(Runnable task)
    {
        try
        {
            mEventExecutor.execute(task);
        }
        catch(RuntimeException e)
        {
            // Service is shutting down.
        }
    }

    void process(ChannelActivityEvent event)
    {
        if(event == null || event.snapshot() == null)
        {
            return;
        }

        Map<String,Object> table = activityTable(event.snapshot());
        String tableId = event.snapshot().tableId();

        if(event.operation() == ChannelActivityEvent.Operation.REMOVE)
        {
            if(mTables.remove(tableId) == null)
            {
                return;
            }
        }
        else
        {
            Map<String,Object> previous = mTables.put(tableId, table);

            if(table.equals(previous))
            {
                return;
            }
        }

        mSystemsHub.publish("activity_table", Map.of("operation", event.operation().name().toLowerCase(),
            "table_id", tableId, "table", table));

        if(event.operation() == ChannelActivityEvent.Operation.REMOVE)
        {
            clearSiteQuality(table.get("guid"));
        }
        else
        {
            updateSiteQuality(table);
        }
    }

    void process(ProtocolSiteMetadataEvent event)
    {
        if(event == null || !event.isUseful() || event.channel() == null || event.snapshot() == null)
        {
            return;
        }

        String guid = event.channel().getRadresGuid();

        if(guid == null || guid.isBlank())
        {
            return;
        }

        long receivedAt = mClock.getAsLong();
        LinkedHashMap<String,Object> liveSite = new LinkedHashMap<>(protocolSite(event, mQualityByGuid.get(guid)));
        liveSite.put("live_received_at_ms", receivedAt);
        Map<String,Object> site = Map.copyOf(liveSite);
        Map<String,Object> previous = mSites.put(guid, site);
        mSiteReceivedAtByGuid.put(guid, receivedAt);

        if(!site.equals(previous))
        {
            mSitesHub.publish("site_metadata", site);
        }
    }

    private void updateSiteQuality(Map<String,Object> table)
    {
        if(table == null || !(table.get("guid") instanceof String guid) || guid.isBlank() ||
            !(table.get("rows") instanceof List<?> rows))
        {
            return;
        }

        Map<String,Object> best = null;

        for(Object value: rows)
        {
            if(!(value instanceof Map<?,?> row) ||
                !row.containsKey("signal_dbfs") && !row.containsKey("decode_health_pct"))
            {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String,Object> candidate = (Map<String,Object>)row;
            String tags = String.valueOf(candidate.getOrDefault("tags", ""));

            if(best == null || tags.contains("CONTROL"))
            {
                best = candidate;
            }

            if(tags.contains("CONTROL"))
            {
                break;
            }
        }

        if(best == null)
        {
            clearSiteQuality(guid);
            return;
        }

        LinkedHashMap<String,Object> quality = new LinkedHashMap<>();
        put(quality, "frequency_hz", best.get("frequency_hz"));
        put(quality, "signal_dbfs", best.get("signal_dbfs"));
        put(quality, "decode_health_pct", best.get("decode_health_pct"));
        long observedAt = best.get("quality_observed_at_ms") instanceof Number observed ?
            observed.longValue() : 0;
        long now = mClock.getAsLong();

        if(observedAt <= 0 || (now > observedAt && now - observedAt > QUALITY_LIVE_MILLISECONDS))
        {
            clearSiteQuality(guid);
            return;
        }

        quality.put("quality_observed_at_ms", observedAt);
        Map<String,Object> previousQuality = mQualityByGuid.get(guid);
        long receivedAt = now;

        if(sameQualityObservation(previousQuality, quality) &&
            previousQuality.get("quality_received_at_ms") instanceof Number previousReceivedAt)
        {
            receivedAt = previousReceivedAt.longValue();
        }

        quality.put("quality_received_at_ms", receivedAt);
        Map<String,Object> immutableQuality = Map.copyOf(quality);
        mQualityByGuid.put(guid, immutableQuality);
        mQualityReceivedAtByGuid.put(guid, receivedAt);
        Map<String,Object> current = mSites.get(guid);

        if(current != null)
        {
            LinkedHashMap<String,Object> updated = new LinkedHashMap<>(current);
            updated.putAll(immutableQuality);
            Map<String,Object> immutable = Map.copyOf(updated);

            if(!immutable.equals(current))
            {
                mSites.put(guid, immutable);
                mSitesHub.publish("site_metadata", immutable);
            }
        }
    }

    private static boolean sameQualityObservation(Map<String,Object> previous, Map<String,Object> current)
    {
        return previous != null &&
            Objects.equals(previous.get("frequency_hz"), current.get("frequency_hz")) &&
            Objects.equals(previous.get("signal_dbfs"), current.get("signal_dbfs")) &&
            Objects.equals(previous.get("decode_health_pct"), current.get("decode_health_pct")) &&
            Objects.equals(previous.get("quality_observed_at_ms"), current.get("quality_observed_at_ms"));
    }

    void sweepExpired()
    {
        // Production callers serialize expiry with metadata and quality updates on mEventExecutor.
        long now = mClock.getAsLong();

        for(Map.Entry<String,Long> entry: mQualityReceivedAtByGuid.entrySet())
        {
            if(now - entry.getValue() > QUALITY_LIVE_MILLISECONDS &&
                mQualityReceivedAtByGuid.remove(entry.getKey(), entry.getValue()))
            {
                clearSiteQuality(entry.getKey());
            }
        }

        for(Map.Entry<String,Long> entry: mSiteReceivedAtByGuid.entrySet())
        {
            if(now - entry.getValue() > SITE_METADATA_LIVE_MILLISECONDS &&
                mSiteReceivedAtByGuid.remove(entry.getKey(), entry.getValue()))
            {
                String guid = entry.getKey();
                mSites.remove(guid);
                mQualityByGuid.remove(guid);
                mQualityReceivedAtByGuid.remove(guid);
                mSitesHub.publish("site_removed", Map.of("guid", guid));
            }
        }
    }

    private void clearSiteQuality(Object guidValue)
    {
        if(!(guidValue instanceof String guid) || guid.isBlank())
        {
            return;
        }

        Map<String,Object> removed = mQualityByGuid.remove(guid);
        mQualityReceivedAtByGuid.remove(guid);

        if(removed == null)
        {
            return;
        }

        Map<String,Object> current = mSites.get(guid);

        if(current != null)
        {
            LinkedHashMap<String,Object> updated = new LinkedHashMap<>(current);
            updated.remove("frequency_hz");
            updated.remove("signal_dbfs");
            updated.remove("decode_health_pct");
            updated.remove("quality_observed_at_ms");
            updated.remove("quality_received_at_ms");
            Map<String,Object> immutable = Map.copyOf(updated);

            if(mSites.replace(guid, current, immutable))
            {
                mSitesHub.publish("site_metadata", immutable);
            }
        }
    }

    static Map<String,Object> protocolSite(ProtocolSiteMetadataEvent event, Map<String,Object> quality)
    {
        Channel channel = event.channel();
        LinkedHashMap<String,Object> site = new LinkedHashMap<>();
        site.put("guid", channel.getRadresGuid());
        put(site, "configured_system", channel.getSystem());
        put(site, "configured_site", channel.getSite());
        put(site, "channel_name", channel.getName());
        put(site, "alias_list_name", channel.getAliasListName());
        site.put("protocol", event.snapshot().protocol().name());
        site.put("protocol_code", switch(event.snapshot().protocol())
        {
            case APCO25, APCO25_PHASE2 -> 1;
            case DMR -> 3;
            case NXDN -> 4;
            default -> 0;
        });
        put(site, "decoder", event.snapshot().decoder());
        put(site, "variant", event.snapshot().variant());
        site.put("observed_at_ms", event.observedAtEpochMilliseconds());
        site.put("metadata", event.snapshot());

        if(quality != null)
        {
            site.putAll(quality);
        }

        return Map.copyOf(site);
    }

    private static Map<String,Object> activityTable(ChannelActivitySnapshot snapshot)
    {
        LinkedHashMap<String,Object> table = new LinkedHashMap<>();
        table.put("table_id", snapshot.tableId());
        table.put("title", snapshot.title());
        table.put("channel_name", snapshot.channelName());
        put(table, "configuration_id", snapshot.configurationId());
        put(table, "guid", snapshot.guid());
        table.put("closeable", snapshot.closeable());
        table.put("control_active", snapshot.controlActive());
        table.put("rows", snapshot.rows().stream().map(StatsLiveService::activityRow).toList());
        return Map.copyOf(table);
    }

    private static Map<String,Object> activityRow(ChannelActivitySnapshot.Row snapshot)
    {
        LinkedHashMap<String,Object> row = new LinkedHashMap<>();
        row.put("key", snapshot.key());
        put(row, "channel_name", snapshot.channelName());
        put(row, "configuration_id", snapshot.configurationId());
        row.put("status", snapshot.status());
        row.put("tags", snapshot.tags());
        put(row, "lcn", snapshot.lcn());
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
        put(row, "source_id", snapshot.sourceId());
        put(row, "source_alias", snapshot.sourceAlias());
        put(row, "talker_alias", snapshot.talkerAlias());
        put(row, "source_alias_display", snapshot.sourceAliasDisplay());
        put(row, "target_id", snapshot.targetId());
        put(row, "target_alias", snapshot.targetAlias());
        put(row, "decoder", snapshot.decoder());
        put(row, "encryption_details", snapshot.encryptionDetails());
        return Map.copyOf(row);
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
        mEventExecutor.shutdownNow();
        mSweepExecutor.shutdownNow();
        mSystemsHub.close();
        mSitesHub.close();
        mActivityHub.close();
    }
}
