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
import io.github.dsheirer.metadata.site.SiteMetadataSnapshot;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lightweight owner of live Systems and committed activity events for the Stats Server.
 */
final class StatsLiveService implements AutoCloseable, ProtocolSiteMetadataListener
{
    private static final int MAXIMUM_SSE_CLIENTS = 32;
    private static final int SSE_QUEUE_CAPACITY = 256;
    private static final int EVENT_QUEUE_CAPACITY = 64;
    static final int MAXIMUM_LIVE_TABLES = 128;
    static final int MAXIMUM_ROWS_PER_TABLE = 256;
    static final int MAXIMUM_TOTAL_LIVE_ROWS = 2_048;
    static final int MAXIMUM_SYSTEM_SNAPSHOT_BYTES = 1024 * 1024;
    static final int MAXIMUM_LIVE_SITES = 256;
    private static final int MAXIMUM_LIVE_TEXT_LENGTH = 256;
    private static final int MAXIMUM_LIVE_TAGS = 16;
    private static final int MAXIMUM_LIVE_TAG_LENGTH = 64;
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
    private final AtomicLong mSystemsRevision = new AtomicLong();
    private final Object mEncodedSnapshotLock = new Object();
    private final ExecutorService mEventExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY), new NamingThreadFactory("stats live events"),
        new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ScheduledExecutorService mSweepExecutor = Executors.newSingleThreadScheduledExecutor(
        new NamingThreadFactory("stats live expiry"));
    private final Listener<ChannelActivityEvent> mChannelActivityListener = event -> {
        PreparedActivityEvent prepared = prepare(event);

        if(prepared != null)
        {
            execute(() -> process(prepared));
        }
    };
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private volatile boolean mTablesTruncated;
    private volatile boolean mSitesTruncated;
    private volatile int mRetainedActivityRows;
    private volatile EncodedSnapshot mEncodedSystemSnapshot;
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
        mTablesTruncated = false;
        mSitesTruncated = false;
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

    Map<String,Object> siteSnapshot()
    {
        List<Map<String,Object>> sites = new ArrayList<>(mSites.values());
        sites.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault("guid", ""))));
        return Map.of("sites", sites, "limit", MAXIMUM_LIVE_SITES, "truncated", mSitesTruncated);
    }

    @Override
    public void receiveProtocolSiteMetadata(ProtocolSiteMetadataEvent event)
    {
        if(mRunning.get())
        {
            PreparedSiteEvent prepared = prepare(event);

            if(prepared != null)
            {
                execute(() -> process(prepared));
            }
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
        PreparedActivityEvent prepared = prepare(event);

        if(prepared != null)
        {
            process(prepared);
        }
    }

    private synchronized void process(PreparedActivityEvent event)
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
                mTablesTruncated = true;
                systemsChanged();
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

        systemsChanged();

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
        PreparedSiteEvent prepared = prepare(event);

        if(prepared != null)
        {
            process(prepared);
        }
    }

    private void process(PreparedSiteEvent event)
    {
        String guid = event.guid();
        long receivedAt = mClock.getAsLong();

        if(!mSites.containsKey(guid) && mSites.size() >= MAXIMUM_LIVE_SITES)
        {
            mSitesTruncated = true;
            return;
        }

        LinkedHashMap<String,Object> liveSite = new LinkedHashMap<>(event.site());
        Map<String,Object> quality = mQualityByGuid.get(guid);

        if(quality != null)
        {
            liveSite.putAll(quality);
        }

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
        site.put("guid", boundedText(channel.getRadresGuid(), MAXIMUM_LIVE_TEXT_LENGTH));
        putText(site, "configured_system", channel.getSystem(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(site, "configured_site", channel.getSite(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(site, "channel_name", channel.getName(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(site, "alias_list_name", channel.getAliasListName(), MAXIMUM_LIVE_TEXT_LENGTH);
        site.put("protocol", event.snapshot().protocol().name());
        site.put("protocol_code", switch(event.snapshot().protocol())
        {
            case APCO25, APCO25_PHASE2 -> 1;
            case DMR -> 3;
            case NXDN -> 4;
            default -> 0;
        });
        putText(site, "decoder", event.snapshot().decoder(), MAXIMUM_LIVE_TEXT_LENGTH);
        putText(site, "variant", event.snapshot().variant(), MAXIMUM_LIVE_TEXT_LENGTH);
        site.put("observed_at_ms", event.observedAtEpochMilliseconds());
        addProtocolSiteSummary(site, event.snapshot());

        if(quality != null)
        {
            site.putAll(quality);
        }

        return Map.copyOf(site);
    }

    /**
     * Projects decoder snapshots to a scalar, bounded live summary.  Detailed collections stay behind paged site
     * resources and are never retained in the live cache or serialized into SSE events.
     */
    private static void addProtocolSiteSummary(Map<String,Object> site, SiteMetadataSnapshot snapshot)
    {
        LinkedHashMap<String,Integer> counts = new LinkedHashMap<>();

        if(snapshot instanceof P25NetworkConfigurationSnapshot p25)
        {
            P25NetworkConfigurationSnapshot.Network network = p25.network();
            P25NetworkConfigurationSnapshot.CurrentSite current = p25.currentSite();
            P25NetworkConfigurationSnapshot.SiteStatus status = p25.siteStatus();
            put(site, "wacn", network != null ? network.wacn() : null);
            put(site, "system_id", current != null && current.system() != null ? current.system() :
                network != null ? network.system() : null);
            put(site, "nac", current != null && current.nac() != null ? current.nac() :
                network != null ? network.nac() : null);
            put(site, "rfss", current != null ? current.rfss() : null);
            put(site, "site_id", current != null ? current.site() : null);
            put(site, "lra", current != null && current.lra() != null ? current.lra() :
                network != null ? network.lra() : null);
            put(site, "rfss_network_active", current != null ? current.activeRfssNetworkConnection() : null);

            if(status != null)
            {
                put(site, "broadcast_clock_ms", status.broadcastClockEpochMilliseconds());
                put(site, "micro_slots", status.microSlots());
                put(site, "data_service", status.dataService());
                put(site, "data_access", status.dataAccess());
                put(site, "wuid_lease_minutes", status.wuidLeaseMinutes());
                put(site, "registration_service", status.registrationService());
                put(site, "mfid", status.mfid());
                put(site, "voice_service", status.voiceService());
            }

            counts.put("channels", size(p25.channels()));
            counts.put("neighbors", size(p25.neighborSites()));
            counts.put("frequency_bands", size(p25.frequencyBands()));
            counts.put("foreign_frequency_bands", size(p25.foreignSystemBands()));
            counts.put("patch_groups", size(p25.patchGroups()));
            counts.put("talker_aliases", size(p25.talkerAliases()));
        }
        else if(snapshot instanceof DMRNetworkConfigurationSnapshot dmr)
        {
            put(site, "network_id", dmr.network());
            put(site, "site_id", dmr.site());
            put(site, "color_code_ts1", dmr.colorCodeTimeslot1());
            put(site, "color_code_ts2", dmr.colorCodeTimeslot2());
            counts.put("channels", size(dmr.channels()));
            counts.put("neighbors", size(dmr.neighborSites()));
        }
        else if(snapshot instanceof NXDNNetworkConfigurationSnapshot nxdn)
        {
            NXDNNetworkConfigurationSnapshot.Location location = nxdn.currentLocation();
            put(site, "ran", nxdn.ran());
            put(site, "network_id", location != null ? location.integrator() : null);
            put(site, "system_id", location != null ? location.system() : null);
            put(site, "site_id", location != null && location.site() != null ? location.site() : nxdn.typeDSite());
            put(site, "current_repeater", nxdn.currentRepeater());
            counts.put("services", size(nxdn.services()));
            counts.put("restrictions", size(nxdn.restrictions()));
            counts.put("channels", size(nxdn.controlChannels()));
            counts.put("neighbors", size(nxdn.neighborSites()));
            counts.put("observed_repeaters", size(nxdn.observedRepeaters()));
        }

        site.put("detail_counts", Map.copyOf(counts));
        site.put("details_truncated", counts.values().stream().anyMatch(count -> count > 0));
    }

    private static int size(java.util.Collection<?> values)
    {
        return values != null ? values.size() : 0;
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

    private static PreparedSiteEvent prepare(ProtocolSiteMetadataEvent event)
    {
        if(event == null || !event.isUseful() || event.channel() == null || event.snapshot() == null)
        {
            return null;
        }

        String guid = boundedText(event.channel().getRadresGuid(), MAXIMUM_LIVE_TEXT_LENGTH);
        return guid.isBlank() ? null : new PreparedSiteEvent(guid, protocolSite(event, null));
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
        mEventExecutor.shutdownNow();
        mSweepExecutor.shutdownNow();
        mSystemsHub.close();
        mSitesHub.close();
        mActivityHub.close();
    }

    private record PreparedActivityEvent(ChannelActivityEvent.Operation operation, String tableId,
                                         Map<String,Object> table)
    {
    }

    private record PreparedSiteEvent(String guid, Map<String,Object> site)
    {
    }

    private record EncodedSnapshot(long revision, byte[] payload)
    {
    }
}
