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
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class StatsLiveServiceTest
{
    @Test
    void currentReceiverUsesOpaqueReferenceAndNicknameChangesRefreshWithoutActivity() throws Exception
    {
        String nativeId = "private-hardware-identity";
        String opaqueId = "tuner-0123456789abcdef0123456789abcdef";
        var references = new AtomicReference<>(Map.of(nativeId,
            new io.github.dsheirer.web.tuner.TunerAdministrationService.ReceiverReference(opaqueId, "Roof")));
        var source = new TestChannelActivitySource();
        try(StatsLiveService service = StatsLiveService.withReceivers(source, references::get))
        {
            service.start();
            try(var subscription = service.subscribeChannelActivity();
                var markers = service.subscribeChannelActivity(true))
            {
                source.publish(conventionalActivity("IDLE", nativeId));
                var row = firstRow(subscription.poll(2, TimeUnit.SECONDS));
                assertEquals(opaqueId, row.get("receiver_id"));
                assertEquals("Roof", row.get("receiver_nickname"));
                assertFalse(row.toString().contains(nativeId));
                var marker = firstRow(markers.poll(2, TimeUnit.SECONDS));
                assertFalse(marker.containsKey("receiver_id"));
                assertFalse(marker.containsKey("receiver_nickname"));

                references.set(Map.of(nativeId,
                    new io.github.dsheirer.web.tuner.TunerAdministrationService.ReceiverReference(opaqueId, "Desk")));
                var renamed = subscription.poll(2, TimeUnit.SECONDS);
                assertNotNull(renamed);
                assertEquals("activity_resync", renamed.name());
                assertEquals("Desk", tables(service).getFirst().get("rows") instanceof List<?> rows ?
                    ((Map<?,?>)rows.getFirst()).get("receiver_nickname") : null);

                references.set(Map.of(nativeId,
                    new io.github.dsheirer.web.tuner.TunerAdministrationService.ReceiverReference(opaqueId, null)));
                assertNotNull(subscription.poll(2, TimeUnit.SECONDS));
                @SuppressWarnings("unchecked")
                var cleared = ((List<Map<String,Object>>)tables(service).getFirst().get("rows")).getFirst();
                assertEquals(opaqueId, cleared.get("receiver_id"));
                assertFalse(cleared.containsKey("receiver_nickname"));

                source.publish(conventionalActivity("IDLE"));
                var released = firstRow(subscription.poll(2, TimeUnit.SECONDS));
                assertFalse(released.containsKey("receiver_id"));
                assertFalse(released.containsKey("receiver_nickname"));
            }
        }
    }

    @Test
    void receiverCatalogIsCapturedOnceForAnEntireSnapshotAndUnresolvedIdentitiesStayPrivate()
    {
        var source = new TestChannelActivitySource();
        var event = conventionalActivity("IDLE", "private-unknown-hardware");
        source.publish(event);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        try(StatsLiveService service = StatsLiveService.withReceivers(source, () -> {
            reads.incrementAndGet();
            return Map.of();
        }))
        {
            var snapshot = service.snapshot();
            assertEquals(1, reads.get());
            assertFalse(snapshot.toString().contains("private-unknown-hardware"));
            assertFalse(snapshot.toString().contains("receiver_id"));
        }
    }

    @Test
    void twoHundredBrowsersShareTheSameProjectedActivityEvent() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        List<StatsLiveEventHub.Subscription> subscriptions = new ArrayList<>();
        try(StatsLiveService service = StatsLiveService.fromActivitySource(source, null))
        {
            service.start();
            for(int index = 0; index < 200; index++)
            {
                var subscription = service.subscribeChannelActivity();
                assertNotNull(subscription);
                subscriptions.add(subscription);
            }
            source.publish(namedDigitalActivity("Serving system", "p25:bee00:349"));
            StatsLiveEventHub.LiveEvent shared = subscriptions.getFirst().poll(1, TimeUnit.SECONDS);
            assertNotNull(shared);
            for(int index = 1; index < subscriptions.size(); index++)
                org.junit.jupiter.api.Assertions.assertSame(shared, subscriptions.get(index).poll(1, TimeUnit.SECONDS),
                    "Projection is shared across browsers");
        }
        finally { subscriptions.forEach(StatsLiveEventHub.Subscription::close); }
    }

    @Test
    void catalogNamesUseCapturedSystemsAndNeverReplaceSavedLabels() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        Map<String,String> names = Map.of("p25:bee00:349", "Serving catalog", "p25:abcde:123", "Radio home",
            "p25:abcde:124", "Group home");
        AtomicReference<Thread> lookupThread = new AtomicReference<>();
        Thread producer = Thread.currentThread();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null, RemoteOriginLookup.EMPTY,
            key -> { lookupThread.set(Thread.currentThread()); return names.get(key); });
        service.start();
        try(StatsLiveEventHub.Subscription subscription = service.subscribeChannelActivity())
        {
            source.publish(namedDigitalActivity("", "p25:bee00:349"));
            StatsLiveEventHub.LiveEvent published = subscription.poll(1, TimeUnit.SECONDS);
            assertNotNull(published);
            @SuppressWarnings("unchecked")
            Map<String,Object> table = activityTable(published);
            assertEquals("Serving catalog", table.get("system_name"));
            assertEquals("catalog", table.get("system_name_source"));
            @SuppressWarnings("unchecked")
            Map<String,Object> row = ((List<Map<String,Object>>)table.get("rows")).getFirst();
            assertEquals("Serving catalog", row.get("system_name"));
            assertEquals("p25:bee00:349", row.get("serving_system_key"));
            assertEquals("p25:bee00:349", row.get("radio_system_key"));
            assertEquals("Radio home", row.get("source_home_catalog_name"));
            assertEquals("Group home", row.get("target_home_catalog_name"));
            assertEquals("501", row.get("source_id"));
            assertEquals("77", row.get("target_id"));
            assertFalse(row.containsKey("source_home_system_name"), "Catalog fallback cannot claim a configured name");
            assertFalse(producer == lookupThread.get(), "Catalog projection belongs to the observer worker");

            String saved = "P25 BEE00-349";
            source.publish(namedDigitalActivity(saved, "p25:bee00:349"));
            waitUntil(() -> saved.equals(tables(service).getFirst().get("system_name")));
            table = tables(service).getFirst();
            assertFalse(table.containsKey("system_name_source"));
            @SuppressWarnings("unchecked")
            Map<String,Object> savedRow = ((List<Map<String,Object>>)table.get("rows")).getFirst();
            assertEquals(saved, savedRow.get("system_name"), "Even protocol-looking saved text remains authoritative");

            source.publish(namedDigitalActivity(saved, null));
            waitUntil(() -> {
                @SuppressWarnings("unchecked")
                var rows = (List<Map<String,Object>>)tables(service).getFirst().get("rows");
                return rows.getFirst().containsKey("serving_system_key") && rows.getFirst().get("serving_system_key") == null;
            });
            @SuppressWarnings("unchecked")
            var unknown = ((List<Map<String,Object>>)tables(service).getFirst().get("rows")).getFirst();
            assertFalse(unknown.containsKey("system_name"), "Configured routing cannot fill absent serving proof");
            assertFalse(unknown.containsKey("radio_system_key"));
            assertEquals("Radio home", unknown.get("source_home_catalog_name"));
        }
        finally { service.close(); }
    }

    private static ChannelActivityEvent namedDigitalActivity(String systemName, String servingKey)
    {
        String id = "00000000-0000-0000-0000-000000000017";
        var navigation = new ChannelActivitySnapshot.Navigation(id, null, null, "p25", List.of(),
            new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 501,
                "v1-r-abcde-123-42", 501), List.of(),
            new ChannelActivitySnapshot.MatcherReference("talkgroup", "p25", "phase_1", 77,
                "v1-g-abcde-124-7001"));
        var row = new ChannelActivitySnapshot.Row("traffic:851012500:1", null, id, "CALL", List.of("VOICE"),
            17L, null, 851_012_500L, null, null, null, 0L, 0L, 0L, 0L, 0L, 0L, 0L, null, 1,
            "501", "RADIO", null, null, null, null, "77", "TALKGROUP", null, null,
            "P25 Phase 1", null, navigation, "TRAFFIC", null, null, servingKey);
        var snapshot = new ChannelActivitySnapshot("catalog-site", "Control", systemName, "North", "Control", id,
            false, true, List.of(), List.of(row), new ChannelActivitySnapshot.Site(0xBEE00, 0x349, 1, 2, null));
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

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
                assertEquals(12_500, rows.getFirst().get("bandwidth_hz"));
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
    void recoverySnapshotExcludesConfiguredOnlyChannelsAndIncludesStartedChannelsWithoutQuality() throws Exception
    {
        ChannelProcessingManager manager = managerForManualActivityEvents();
        StatsLiveService service = new StatsLiveService(manager);
        Channel neverStarted = trunkedDmrChannel();
        neverStarted.setConfigurationId("00000000-0000-0000-0000-000000000021");
        Channel started = trunkedDmrChannel();
        started.setConfigurationId("00000000-0000-0000-0000-000000000022");
        String neverStartedTableId = "channel:" + neverStarted.getPersistedConfigurationId();
        String startedTableId = "channel:" + started.getPersistedConfigurationId();

        try
        {
            var model = manager.getChannelActivityModel();
            model.channelConfigurationChanged(neverStarted);
            model.channelConfigurationChanged(started);
            waitUntil(() -> model.getSnapshotSet().tables().size() == 3);
            service.start();

            assertEquals(List.of("conventional"), tables(service).stream()
                .map(table -> String.valueOf(table.get("table_id"))).toList(),
                "browser recovery must not turn discovered configurations into running channels");

            model.channelStarted(started, List.of());
            waitUntil(() -> model.getSnapshotSet().tables().stream()
                .anyMatch(table -> startedTableId.equals(table.tableId()) && table.channelRunning()));
            assertEquals(List.of("conventional", startedTableId), tables(service).stream()
                .map(table -> String.valueOf(table.get("table_id"))).toList());
            Map<String,Object> active = tables(service).stream()
                .filter(table -> startedTableId.equals(table.get("table_id"))).findFirst().orElseThrow();
            assertEquals(true, active.get("channel_running"));
            assertEquals(false, active.get("control_active"), "running does not require a quality observation");
            long expectedRevision = model.getSnapshotSet().revision();
            waitUntil(() -> {
                try { return service.snapshotWire().revision() >= expectedRevision; }
                catch(java.io.IOException initializing) { return false; }
            });
            String encoded = snapshotWireText(service);
            assertFalse(encoded.contains(neverStartedTableId));
            assertTrue(encoded.contains(startedTableId));
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
            assertFalse(snapshotWireText(service).contains(stoppedTableId),
                "refresh uses the encoded recovery snapshot");
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

            source.publish(unresolvedDigitalActivity("IDLE"));
            Map<String,Object> repeated = firstRow(subscription.poll(1, TimeUnit.SECONDS));
            assertEquals("IDLE", repeated.get("status"));
            assertEquals(sourceKey, repeated.get("source_identity_key"));
            assertEquals(targetKey, repeated.get("target_identity_key"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void projectsOnlyAGenericRemoteOriginIntoPublicLiveData() throws Exception
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
            Map<String,Object> update = firstChange(event);
            @SuppressWarnings("unchecked")
            Map<String,Object> table = activityTable(event);
            @SuppressWarnings("unchecked")
            Map<String,Object> tableOrigin = (Map<String,Object>)table.get("remote_origin");
            @SuppressWarnings("unchecked")
            Map<String,Object> rowOrigin = (Map<String,Object>)firstRow(event).get("remote_origin");
            assertEquals(Map.of("remote", true), tableOrigin);
            assertEquals(Map.of("remote", true), rowOrigin);
            String encoded = snapshotWireText(service);
            assertFalse(encoded.contains("sender_id"));
            assertFalse(encoded.contains("sender_name"));
            assertFalse(encoded.contains("feed_id"));
            assertFalse(encoded.contains("feed_name"));
            assertFalse(encoded.contains("dependency_state"));
            assertFalse(encoded.contains("destination_host"));
            assertFalse(encoded.contains("secret"));

            origins.set(new RemoteOriginLookup.OriginSnapshot(2L, Map.of(configurationId,
                new RemoteOriginLookup.RemoteOrigin("sender-1", "Hilltop", "feed-1", "North",
                    RemoteLinkAdministrationService.FeedState.DISCONNECTED,
                    RemoteLinkAdministrationService.DependencyState.DEGRADED))));
            StatsLiveEventHub.LiveEvent refresh = subscription.poll(1, TimeUnit.SECONDS);
            assertNotNull(refresh);
            assertEquals("activity_resync", refresh.name());
            @SuppressWarnings("unchecked")
            Map<String,Object> refreshedOrigin = (Map<String,Object>)tables(service).stream()
                .filter(item -> "p25-unresolved".equals(item.get("table_id"))).findFirst().orElseThrow()
                .get("remote_origin");
            assertEquals(Map.of("remote", true), refreshedOrigin);
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
        return conventionalActivity(status, null);
    }

    private static ChannelActivityEvent conventionalActivity(String status, String receiverIdentity)
    {
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("channel-17:155730000:0",
            "Dispatch", "configuration-17", status, List.of("CONVENTIONAL"), 0L, null, 155_730_000L, null,
            null, null, 0L, 0L, 0L, 0L, 0L, 0L, 0L, null, null, null, null, null, null, null, null,
            null, null, null, null, "NBFM", null, null, "CONVENTIONAL",
            new ChannelActivitySnapshot.Transmission("leg-17", "active", 1_050L, 1_000L, 1_075L, false,
                3L, 1_025L), 12_500, null, receiverIdentity);
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("conventional", "Conventional",
            "", "", "Conventional", null, false, true, List.of(), List.of(row));
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

    private static ChannelActivityEvent unresolvedDigitalActivity()
    {
        return unresolvedDigitalActivity("CALL");
    }

    private static ChannelActivityEvent unresolvedDigitalActivity(String status)
    {
        String configurationId = "00000000-0000-0000-0000-000000000017";
        ChannelActivitySnapshot.Navigation navigation = new ChannelActivitySnapshot.Navigation(configurationId,
            null, null, "p25", List.of(),
            new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 1_201), List.of(),
            new ChannelActivitySnapshot.MatcherReference("talkgroup", "p25", "phase_1", 101));
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("traffic:851012500:1", null,
            configurationId, status, List.of("VOICE"), 17L, null, 851_012_500L, null, null, null, 0L,
            0L, 0L, 0L, 0L, 0L, 0L, null, 1, "1201", "RADIO", null, null, null, null, "101",
            "TALKGROUP", null, null, "P25 Phase 1", null, navigation, "TRAFFIC",
            new ChannelActivitySnapshot.Transmission("leg-17", "active", 1_050L, 1_000L, 1_075L, false,
                3L, 1_025L));
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("p25-unresolved", "P25", "Metro", "North",
            "Control", configurationId, false, true, List.of(), List.of(row));
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> firstChange(StatsLiveEventHub.LiveEvent event)
    {
        assertNotNull(event);
        assertEquals("activity_batch", event.name());
        var changes = (List<Map<String,Object>>)((Map<?,?>)event.data()).get("changes");
        assertEquals(1, changes.size(), "this fixture changes exactly one table");
        return changes.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> activityTable(StatsLiveEventHub.LiveEvent event)
    {
        Map<String,Object> change = firstChange(event);
        Map<String,Object> table = new LinkedHashMap<>((Map<String,Object>)change.getOrDefault("table", Map.of()));
        table.put("rows", change.getOrDefault("rows", List.of()));
        return table;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> firstRow(StatsLiveEventHub.LiveEvent event)
    {
        assertNotNull(event);
        return ((List<Map<String,Object>>)firstChange(event).get("rows")).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static void assertActivityStatus(StatsLiveEventHub.LiveEvent event, String expectedStatus)
    {
        assertNotNull(event);
        assertEquals("activity_batch", event.name());
        Map<String,Object> update = firstChange(event);
        assertEquals("upsert", update.get("operation"));
        assertEquals("conventional", update.get("table_id"));
        Map<String,Object> table = activityTable(event);
        if(update.containsKey("table")) assertEquals("conventional", table.get("table_id"));
        List<Map<String,Object>> rows = (List<Map<String,Object>>)table.get("rows");
        assertEquals("channel-17:155730000:0", rows.getFirst().get("key"));
        assertEquals(expectedStatus, rows.getFirst().get("status"));
        assertEquals(0L, rows.getFirst().get("activation_order"));
        assertEquals("CONVENTIONAL", rows.getFirst().get("role"));
        assertEquals(12_500, rows.getFirst().get("bandwidth_hz"));
        assertEquals("leg-17", rows.getFirst().get("call_leg_id"));
        assertEquals("active", rows.getFirst().get("tx_state"));
        assertEquals(1_050L, rows.getFirst().get("tx_observed_at_ms"));
        assertEquals(1_000L, rows.getFirst().get("tx_start_ms"));
        assertEquals(1_075L, rows.getFirst().get("tx_last_observed_at_ms"));
        assertEquals(false, rows.getFirst().get("tx_end_certain"));
        assertEquals(3L, rows.getFirst().get("tx_burst_generation"));
        assertEquals(1_025L, rows.getFirst().get("tx_burst_started_at_ms"));
    }

    private static String snapshotWireText(StatsLiveService service) throws java.io.IOException
    {
        byte[] wire = service.snapshotWire().frame().bytes(false);
        return new String(wire, LiveMultiplexFrame.HEADER_BYTES, wire.length - LiveMultiplexFrame.HEADER_BYTES,
            java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> tables(StatsLiveService service)
    {
        return (List<Map<String,Object>>)service.snapshot().get("tables");
    }

}
