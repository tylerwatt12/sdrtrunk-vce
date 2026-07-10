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
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight owner of live Systems and committed activity events for the Stats Server.
 */
final class StatsLiveService implements AutoCloseable
{
    private static final int MAXIMUM_SSE_CLIENTS = 32;
    private static final int SSE_QUEUE_CAPACITY = 256;
    private final StatsWebDatabase mDatabase;
    private final ChannelActivityModel mActivityModel;
    private final StatsLiveEventHub mSystemsHub = new StatsLiveEventHub(MAXIMUM_SSE_CLIENTS, SSE_QUEUE_CAPACITY);
    private final StatsLiveEventHub mActivityHub = new StatsLiveEventHub(MAXIMUM_SSE_CLIENTS, SSE_QUEUE_CAPACITY);
    private final Map<String,Map<String,Object>> mTables = new ConcurrentHashMap<>();
    private final ExecutorService mEventExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2048), new NamingThreadFactory("stats live events"),
        new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Listener<ChannelActivityEvent> mChannelActivityListener = event -> execute(() -> process(event));
    private final AtomicBoolean mRunning = new AtomicBoolean();

    StatsLiveService(StatsWebDatabase database, ChannelActivityModel activityModel)
    {
        mDatabase = database;
        mActivityModel = activityModel;
    }

    void start()
    {
        if(mRunning.compareAndSet(false, true) && mActivityModel != null)
        {
            mActivityModel.addActivityListener(mChannelActivityListener);
        }
    }

    void stop()
    {
        if(mRunning.compareAndSet(true, false) && mActivityModel != null)
        {
            mActivityModel.removeActivityListener(mChannelActivityListener);
        }

        mTables.clear();
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

    Map<String,Object> snapshot()
    {
        List<Map<String,Object>> tables = new ArrayList<>(mTables.values());
        tables.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault("table_id", ""))));
        return Map.of("tables", tables);
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

    private void process(ChannelActivityEvent event)
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
    }

    private static Map<String,Object> activityTable(ChannelActivitySnapshot snapshot)
    {
        LinkedHashMap<String,Object> table = new LinkedHashMap<>();
        table.put("table_id", snapshot.tableId());
        table.put("title", snapshot.title());
        table.put("channel_name", snapshot.channelName());
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
        row.put("status", snapshot.status());
        row.put("role", snapshot.role());
        row.put("control_role", snapshot.controlRole());
        put(row, "lcn", snapshot.lcn());
        row.put("frequency_hz", snapshot.frequencyHz());
        put(row, "timeslot", snapshot.timeslot());
        put(row, "source_id", snapshot.sourceId());
        put(row, "source_alias", snapshot.sourceAlias());
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
        mSystemsHub.close();
        mActivityHub.close();
    }
}
