/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityTableState;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class StatsLiveServiceTest
{
    @Test
    void browserSubscribersDoNotOwnReceiverActivityLifetime() throws Exception
    {
        ChannelProcessingManager manager = managerForManualActivityEvents();
        StatsLiveService service = new StatsLiveService(manager);
        Channel channel = trunkedDmrChannel();
        assertTrue(manager.getChannelActivityModel().isWorkerAlive());

        service.start();

        try(StatsLiveEventHub.Subscription first = service.subscribeSystems();
            StatsLiveEventHub.Subscription second = service.subscribeSystems())
        {
            assertNotNull(first);
            assertNotNull(second);
            manager.getChannelActivityModel().channelStarted(channel, List.of());
            waitUntil(() -> manager.getChannelActivityModel().getSnapshotSet().tables().size() == 2);
            assertEquals(2, manager.getChannelActivityModel().getSnapshotSet().tables().size());
        }

        assertTrue(manager.getChannelActivityModel().isWorkerAlive());
        assertEquals(2, manager.getChannelActivityModel().getSnapshotSet().tables().size(),
            "closing every browser must not clear receiver activity state");
        service.stop();
        assertTrue(manager.getChannelActivityModel().isWorkerAlive());
        service.close();
        manager.close();
    }

    @Test
    void webAdapterUsesTheCoreSnapshotWithOneLowPriorityProjectionWorker() throws Exception
    {
        ChannelProcessingManager manager = managerForManualActivityEvents();
        StatsLiveService service = new StatsLiveService(manager);
        Channel channel = trunkedDmrChannel();

        try
        {
            service.start();
            manager.getChannelActivityModel().channelStarted(channel, List.of());
            waitUntil(() -> tables(service).stream()
                .filter(table -> !"conventional".equals(table.get("table_id")))
                .map(table -> (List<?>)table.get("rows"))
                .anyMatch(rows -> rows != null && !rows.isEmpty()));

            try(StatsLiveEventHub.Subscription ignored = service.subscribeSystems())
            {
                assertEquals(2, tables(service).size());
                Map<String,Object> trunked = tables(service).stream()
                    .filter(table -> !"conventional".equals(table.get("table_id"))).findFirst().orElseThrow();
                @SuppressWarnings("unchecked")
                List<Map<String,Object>> rows = (List<Map<String,Object>>)trunked.get("rows");
                assertFalse(rows.isEmpty(), "the configured control row supplies the wideband channel marker");
                List<Thread> projectionWorkers = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.isAlive() && thread.getName().startsWith("stats live projection"))
                    .toList();
                assertEquals(1, projectionWorkers.size());
                assertTrue(projectionWorkers.getFirst().isDaemon());
                assertEquals(Thread.NORM_PRIORITY - 1, projectionWorkers.getFirst().getPriority());
            }
        }
        finally
        {
            service.close();
            manager.close();
        }
    }

    @Test
    void recoverySnapshotDoesNotRestoreAStoppedTrunkedChannel() throws Exception
    {
        ChannelProcessingManager manager = managerForManualActivityEvents();
        StatsLiveService service = new StatsLiveService(manager);
        Channel channel = trunkedDmrChannel();

        try
        {
            service.start();
            manager.getChannelActivityModel().channelStarted(channel, List.of());
            waitUntil(() -> tables(service).size() == 2);

            String stoppedTableId = manager.getChannelActivityModel().getSnapshotSet().tables().stream()
                .filter(table -> !"conventional".equals(table.tableId()))
                .map(ChannelActivitySnapshot::tableId).findFirst().orElseThrow();
            manager.getChannelActivityModel().channelStopped(channel);
            waitUntil(() -> manager.getChannelActivityModel().getSnapshotSet().tables().stream()
                .anyMatch(table -> stoppedTableId.equals(table.tableId()) && !table.channelRunning()));

            assertEquals(2, manager.getChannelActivityModel().getSnapshotSet().tables().size(),
                "the desktop model may retain the stopped table for its lifecycle display");
            assertEquals(List.of("conventional"), tables(service).stream()
                .map(table -> String.valueOf(table.get("table_id"))).toList(),
                "a browser recovery snapshot must contain only active channels");
            assertFalse(new String(service.encodedSnapshot(), java.nio.charset.StandardCharsets.UTF_8)
                .contains(stoppedTableId), "refresh uses the encoded recovery snapshot");
        }
        finally
        {
            service.close();
            manager.close();
        }
    }

    @Test
    void publishesConventionalStatusChangesWithStableTableIdentity() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribeSystems())
        {
            source.publish(conventionalActivity("IDLE"));
            assertActivityStatus(subscription.poll(1, TimeUnit.SECONDS), "IDLE");

            source.publish(conventionalActivity("CALL"));
            assertActivityStatus(subscription.poll(1, TimeUnit.SECONDS), "CALL");

            source.publish(conventionalActivity("IDLE"));
            assertActivityStatus(subscription.poll(1, TimeUnit.SECONDS), "IDLE");
        }
        finally
        {
            service.close();
        }
    }

    private static Channel trunkedDmrChannel()
    {
        Channel channel = new Channel("Bus", Channel.ChannelType.STANDARD);
        channel.setSystem("Metro");
        channel.setSite("Garage");
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(DMRChannelMode.TRUNKED);
        channel.setDecodeConfiguration(configuration);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(451_000_000L);
        channel.setSourceConfiguration(source);
        return channel;
    }

    /**
     * These adapter tests inject lifecycle events directly into the activity model.  Disable the processing
     * manager's normal reconciliation so it does not correctly remove that synthetic channel for having no live
     * processing chain.
     */
    private static ChannelProcessingManager managerForManualActivityEvents()
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        manager.getChannelActivityModel().setActiveChannelSupplier(null);
        return manager;
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(5);
        }

        assertTrue(condition.getAsBoolean());
    }

    private static ChannelActivityEvent conventionalActivity(String status)
    {
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("channel-17:155730000:0",
            "Dispatch", "configuration-17", status, List.of("CONVENTIONAL"), 0L, null, 155_730_000L, null,
            null, null, 0L, 0L, 0L, 0L, 0L, 0L, 0L, null, null, null, null, null, null, null, null,
            null, null, null, null, "NBFM", null, null, "CONVENTIONAL");
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("conventional", "Conventional",
            "", "", "Conventional", null, null, false, true, List.of(), List.of(row));
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

    @SuppressWarnings("unchecked")
    private static void assertActivityStatus(StatsLiveEventHub.LiveEvent event, String expectedStatus)
    {
        assertNotNull(event);
        assertEquals("activity_table", event.name());
        Map<String,Object> update = (Map<String,Object>)event.data();
        assertEquals("upsert", update.get("operation"));
        assertEquals("conventional", update.get("table_id"));
        Map<String,Object> table = (Map<String,Object>)update.get("table");
        assertEquals("conventional", table.get("table_id"));
        List<Map<String,Object>> rows = (List<Map<String,Object>>)table.get("rows");
        assertEquals("channel-17:155730000:0", rows.getFirst().get("key"));
        assertEquals(expectedStatus, rows.getFirst().get("status"));
        assertEquals(0L, rows.getFirst().get("activation_order"));
        assertEquals("CONVENTIONAL", rows.getFirst().get("role"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> tables(StatsLiveService service)
    {
        return (List<Map<String,Object>>)service.snapshot().get("tables");
    }

}
