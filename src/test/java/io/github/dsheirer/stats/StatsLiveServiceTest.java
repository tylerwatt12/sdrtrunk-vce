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
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
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
        manager.shutdown();
    }

    @Test
    void webAdapterUsesTheCoreSnapshotWithoutASecondProjectionWorker() throws Exception
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        StatsLiveService service = new StatsLiveService(manager);
        Channel channel = trunkedDmrChannel();

        try
        {
            service.start();
            manager.getChannelActivityModel().channelStarted(channel, List.of());
            waitUntil(() -> manager.getChannelActivityModel().getSnapshotSet().tables().size() == 2);

            try(StatsLiveEventHub.Subscription ignored = service.subscribeSystems())
            {
                assertEquals(2, tables(service).size());
                Map<String,Object> trunked = tables(service).stream()
                    .filter(table -> !"conventional".equals(table.get("table_id"))).findFirst().orElseThrow();
                @SuppressWarnings("unchecked")
                List<Map<String,Object>> rows = (List<Map<String,Object>>)trunked.get("rows");
                assertFalse(rows.isEmpty(), "the configured control row supplies the wideband channel marker");
                assertTrue(Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("stats live projection")));
            }
        }
        finally
        {
            service.close();
            manager.shutdown();
        }
    }

    @Test
    void publishesConventionalStatusChangesWithStableTableIdentity() throws Exception
    {
        StatsLiveService service = new StatsLiveService(null);
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribeSystems())
        {
            service.process(conventionalActivity("IDLE"));
            assertActivityStatus(subscription.poll(1, TimeUnit.SECONDS), "IDLE");

            service.process(conventionalActivity("CALL"));
            assertActivityStatus(subscription.poll(1, TimeUnit.SECONDS), "CALL");

            service.process(conventionalActivity("IDLE"));
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
            "Dispatch", "configuration-17", status, List.of("CONVENTIONAL"), null, 155_730_000L, null,
            null, null, 0L, 0L, 0L, 0L, 0L, 0L, 0L, null, null, null, null, null, null, null, null,
            null, null, "NBFM", null);
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("conventional", "Conventional",
            "", "", "Conventional", null, null, false, List.of(), List.of(row));
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
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> tables(StatsLiveService service)
    {
        return (List<Map<String,Object>>)service.snapshot().get("tables");
    }

}
