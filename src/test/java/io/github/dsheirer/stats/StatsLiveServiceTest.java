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
import io.github.dsheirer.remote.RemoteLinkAdministrationService;
import io.github.dsheirer.remote.RemoteOriginLookup;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

        try(StatsLiveEventHub.Subscription first = service.subscribeChannelActivity();
            StatsLiveEventHub.Subscription second = service.subscribeChannelActivity())
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

            try(StatsLiveEventHub.Subscription ignored = service.subscribeChannelActivity())
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

        try(StatsLiveEventHub.Subscription subscription = service.subscribeChannelActivity())
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

    @Test
    void unresolvedDigitalRowsExposeStableChannelScopedIdentityKeys() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribeChannelActivity())
        {
            source.publish(unresolvedDigitalActivity());
            StatsLiveEventHub.LiveEvent first = subscription.poll(1, TimeUnit.SECONDS);
            Map<String,Object> firstRow = firstRow(first);
            String sourceKey = String.valueOf(firstRow.get("source_identity_key"));
            String targetKey = String.valueOf(firstRow.get("target_identity_key"));

            assertTrue(sourceKey.startsWith("v1-local-"));
            assertTrue(targetKey.startsWith("v1-local-"));
            assertFalse(sourceKey.equals(targetKey));
            assertFalse(firstRow.containsKey("source_entity_ref"));
            assertFalse(firstRow.containsKey("target_entity_ref"));
            assertEquals("1201", firstRow.get("source_id"));
            assertEquals("101", firstRow.get("target_id"));

            source.publish(unresolvedDigitalActivity());
            Map<String,Object> repeated = firstRow(subscription.poll(1, TimeUnit.SECONDS));
            assertEquals(sourceKey, repeated.get("source_identity_key"));
            assertEquals(targetKey, repeated.get("target_identity_key"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void projectsRemoteOriginWithoutNetworkOrCredentialDetailsAndRefreshesItsHealth() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        String configurationId = "00000000-0000-0000-0000-000000000017";
        AtomicReference<RemoteOriginLookup.OriginSnapshot> origins = new AtomicReference<>(
            new RemoteOriginLookup.OriginSnapshot(1L, Map.of(configurationId,
                new RemoteOriginLookup.RemoteOrigin("sender-1", "Hilltop", "feed-1", "North",
                    RemoteLinkAdministrationService.FeedState.CONNECTED,
                    RemoteLinkAdministrationService.DependencyState.READY))));
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null, origins::get);
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribeChannelActivity())
        {
            source.publish(unresolvedDigitalActivity());
            StatsLiveEventHub.LiveEvent event = subscription.poll(1, TimeUnit.SECONDS);
            @SuppressWarnings("unchecked")
            Map<String,Object> update = (Map<String,Object>)event.data();
            @SuppressWarnings("unchecked")
            Map<String,Object> table = (Map<String,Object>)update.get("table");
            @SuppressWarnings("unchecked")
            Map<String,Object> tableOrigin = (Map<String,Object>)table.get("remote_origin");
            @SuppressWarnings("unchecked")
            Map<String,Object> rowOrigin = (Map<String,Object>)firstRow(event).get("remote_origin");
            assertEquals("Hilltop", tableOrigin.get("sender_name"));
            assertEquals("North", rowOrigin.get("feed_name"));
            assertEquals("CONNECTED", rowOrigin.get("state"));
            assertEquals("READY", rowOrigin.get("dependency_state"));
            String encoded = new String(service.encodedSnapshot(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(encoded.contains("destination_host"));
            assertFalse(encoded.contains("secret"));

            origins.set(new RemoteOriginLookup.OriginSnapshot(2L, Map.of(configurationId,
                new RemoteOriginLookup.RemoteOrigin("sender-1", "Hilltop", "feed-1", "North",
                    RemoteLinkAdministrationService.FeedState.DISCONNECTED,
                    RemoteLinkAdministrationService.DependencyState.DEGRADED))));
            StatsLiveEventHub.LiveEvent refresh = subscription.poll(1, TimeUnit.SECONDS);
            assertNotNull(refresh);
            assertEquals("activity_resync", refresh.name());
            String refreshed = new String(service.encodedSnapshot(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(refreshed.contains("\"state\":\"disconnected\""), refreshed);
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
            null, null, null, null, "NBFM", null, null, "CONVENTIONAL",
            new ChannelActivitySnapshot.Transmission("leg-17", "active", 1_050L, 1_000L, 1_075L, false,
                3L, 1_025L));
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("conventional", "Conventional",
            "", "", "Conventional", null, false, true, List.of(), List.of(row));
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

    private static ChannelActivityEvent unresolvedDigitalActivity()
    {
        String configurationId = "00000000-0000-0000-0000-000000000017";
        ChannelActivitySnapshot.Navigation navigation = new ChannelActivitySnapshot.Navigation(configurationId,
            null, null, "p25", List.of(),
            new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 1_201), List.of(),
            new ChannelActivitySnapshot.MatcherReference("talkgroup", "p25", "phase_1", 101));
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("traffic:851012500:1", null,
            configurationId, "CALL", List.of("VOICE"), 17L, null, 851_012_500L, null, null, null, 0L,
            0L, 0L, 0L, 0L, 0L, 0L, null, 1, "1201", "RADIO", null, null, null, null, "101",
            "TALKGROUP", null, null, "P25 Phase 1", null, navigation, "TRAFFIC",
            new ChannelActivitySnapshot.Transmission("leg-17", "active", 1_050L, 1_000L, 1_075L, false,
                3L, 1_025L));
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("p25-unresolved", "P25", "Metro", "North",
            "Control", configurationId, false, true, List.of(), List.of(row));
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> firstRow(StatsLiveEventHub.LiveEvent event)
    {
        assertNotNull(event);
        Map<String,Object> update = (Map<String,Object>)event.data();
        Map<String,Object> table = (Map<String,Object>)update.get("table");
        return ((List<Map<String,Object>>)table.get("rows")).getFirst();
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
        assertEquals("leg-17", rows.getFirst().get("call_leg_id"));
        assertEquals("active", rows.getFirst().get("tx_state"));
        assertEquals(1_050L, rows.getFirst().get("tx_observed_at_ms"));
        assertEquals(1_000L, rows.getFirst().get("tx_start_ms"));
        assertEquals(1_075L, rows.getFirst().get("tx_last_observed_at_ms"));
        assertEquals(false, rows.getFirst().get("tx_end_certain"));
        assertEquals(3L, rows.getFirst().get("tx_burst_generation"));
        assertEquals(1_025L, rows.getFirst().get("tx_burst_started_at_ms"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> tables(StatsLiveService service)
    {
        return (List<Map<String,Object>>)service.snapshot().get("tables");
    }

}
