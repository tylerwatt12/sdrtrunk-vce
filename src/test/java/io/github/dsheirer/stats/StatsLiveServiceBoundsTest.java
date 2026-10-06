/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Regression coverage for the hard live-state bounds exposed to browser subscribers. */
class StatsLiveServiceBoundsTest
{
    @Test
    void registrationRaceRetainsEveryTableInTheSharedBaseline() throws Exception
    {
        TestChannelActivitySource delegate = new TestChannelActivitySource();
        StatsLiveService.ActivitySource racingSource = new StatsLiveService.ActivitySource()
        {
            @Override public io.github.dsheirer.channel.metadata.activity.ChannelActivityModel.SnapshotSet snapshot()
            { return delegate.snapshot(); }
            @Override public long droppedIngressCount() { return 0; }
            @Override public void addListener(io.github.dsheirer.sample.Listener<ChannelActivityEvent> listener)
            {
                // These two changes happen before notification registration. Capturing a baseline beforehand
                // would miss them; a later update to alpha cannot reconstruct the missing beta table.
                delegate.publish(activity("alpha", List.of(activityRow("alpha-row"))));
                delegate.publish(activity("beta", List.of(activityRow("beta-row"))));
                delegate.addListener(listener);
            }
            @Override public void removeListener(io.github.dsheirer.sample.Listener<ChannelActivityEvent> listener)
            { delegate.removeListener(listener); }
        };
        AtomicReference<WebEntityNavigationCatalog.Snapshot> loaded =
            new AtomicReference<>(WebEntityNavigationCatalog.Snapshot.empty());
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(loaded::get, 60_000L);
        catalog.refreshNow();
        StatsLiveService service = StatsLiveService.fromActivitySource(racingSource, catalog);
        try
        {
            service.start();
            try(StatsLiveEventHub.Subscription subscription = service.subscribeChannelActivity())
            {
                delegate.publish(activity("alpha", List.of(activityRow("changed-alpha"))));
                StatsLiveEventHub.LiveEvent update = subscription.poll(1, TimeUnit.SECONDS);
                assertNotNull(update);
                assertEquals("activity_table", update.name());
                String configurationId = "728d2d66-de4e-476b-a696-919f32dd4d12";
                loaded.set(WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                    configurationId, WebEntityRef.channel(configurationId), null, 0, 0, null, null))));
                catalog.refreshNow();
                StatsLiveEventHub.LiveEvent refresh = subscription.poll(1, TimeUnit.SECONDS);
                assertNotNull(refresh);
                assertEquals("activity_resync", refresh.name());
                Map<String,Object> snapshot = (Map<String,Object>)((Map<?,?>)refresh.data()).get("snapshot");
                assertEquals(List.of("alpha", "beta"), tables(snapshot).stream().map(table -> table.get("table_id")).toList());
                assertEquals("changed-alpha", rows(tables(snapshot).getFirst()).getFirst().get("key"));
            }
        }
        finally { service.close(); }
    }

    @Test
    void viewersShareEncodedEventsAndLegacyMarkerVariantsStayIndependent() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        try
        {
            service.start();
            try(StatsLiveEventHub.Subscription first = service.subscribeChannelActivity(true);
                StatsLiveEventHub.Subscription second = service.subscribeChannelActivity(true);
                StatsLiveEventHub.Subscription legacy = service.subscribeChannelActivity();
                StatsLiveEventHub.Subscription marker = service.subscribeChannelActivity(true, true))
            {
                source.publish(activity("site", List.of(activityRow("call"))));
                StatsLiveEventHub.LiveEvent firstEvent = first.poll(1, TimeUnit.SECONDS);
                StatsLiveEventHub.LiveEvent secondEvent = second.poll(1, TimeUnit.SECONDS);
                StatsLiveEventHub.LiveEvent legacyEvent = legacy.poll(1, TimeUnit.SECONDS);
                StatsLiveEventHub.LiveEvent markerEvent = marker.poll(1, TimeUnit.SECONDS);
                assertNotNull(firstEvent);
                assertNotNull(markerEvent);
                assertSame(firstEvent, secondEvent);
                assertSame(firstEvent.frame(1), secondEvent.frame(1));
                assertSame(firstEvent.baseline(), legacyEvent);
                assertEquals("activity_table", legacyEvent.name());
                assertEquals("activity_delta", markerEvent.name());
                assertTrue(markerEvent.markers());
                assertFalse(firstEvent.markers());
                assertFalse(firstEvent.frame(1) == markerEvent.frame(1));
            }
        }
        finally { service.close(); }
    }

    @Test
    void compactMarkerProjectionKeepsHoverLabelsIdentitiesAndNavigation()
    {
        Map<String,Object> row = Map.ofEntries(
            Map.entry("key", "call"), Map.entry("source_alias", "Engine 1"),
            Map.entry("source_alias_description", "Engine company one"), Map.entry("target_alias", "Dispatch"),
            Map.entry("source_canonical_identity", Map.of("wacn", 1, "system_id", 2, "subscriber_id", 3)),
            Map.entry("source_observed_working_id", 1201), Map.entry("source_entity_ref", Map.of("kind", "radio")),
            Map.entry("frequency_hz", 851_012_500L), Map.entry("signal_dbfs", -25.5),
            Map.entry("decode_health_pct", 99.5), Map.entry("vc_quality_pct", 98.5),
            Map.entry("cc_valid_frames", 2000L), Map.entry("tx_observed_at_ms", 9876L),
            Map.entry("source_aliases", List.of(Map.of("alias_id", 10))));
        Map<String,Object> table = Map.of("table_id", "site", "system_name", "County", "site_name", "Downtown",
            "identifiers", List.of(Map.of("label", "WACN", "value", "BEE00")), "rows", List.of(row));
        Map<String,Object> compact = StatsLiveService.markerTable(table, null, null);
        Map<?,?> result = (Map<?,?>)((List<?>)compact.get("rows")).getFirst();
        for(String field: List.of("source_alias", "source_alias_description", "target_alias",
            "source_canonical_identity", "source_observed_working_id", "source_entity_ref", "frequency_hz",
            "signal_dbfs", "decode_health_pct", "vc_quality_pct")) assertEquals(row.get(field), result.get(field));
        assertEquals(table.get("identifiers"), compact.get("identifiers"));
        assertFalse(result.containsKey("cc_valid_frames"));
        assertFalse(result.containsKey("tx_observed_at_ms"));
        assertFalse(result.containsKey("source_aliases"));
        Map<String,Object> unchanged = StatsLiveService.markerTable(table, table, compact);
        assertSame(result, ((List<?>)unchanged.get("rows")).getFirst(), "unchanged shared rows must be reused");
    }

    @Test
    void rowDeltasPreserveRemovalsOptionalFieldClearingAndOrdering()
    {
        Map<String,Object> unchanged = Map.of("key", "control", "status", "CONTROL");
        Map<String,Object> oldCall = Map.of("key", "call", "status", "CALL", "source_alias", "Engine 1");
        Map<String,Object> newCall = Map.of("key", "call", "status", "IDLE");
        Map<String,Object> previous = Map.of("table_id", "site", "title", "County", "rows",
            List.of(unchanged, oldCall, Map.of("key", "removed")));
        Map<String,Object> current = Map.of("table_id", "site", "title", "County", "rows",
            List.of(newCall, unchanged));
        Map<String,Object> delta = StatsLiveService.activityDelta(new StatsLiveService.PreparedActivityEvent(
            ChannelActivityEvent.Operation.UPSERT, "site", current), previous, 40, 41);
        assertEquals(40L, delta.get("base_revision"));
        assertEquals(41L, delta.get("revision"));
        assertEquals(List.of(newCall), delta.get("rows"));
        assertEquals(List.of("removed"), delta.get("removed_row_keys"));
        assertEquals(List.of("call", "control"), delta.get("row_order"));
        assertFalse(delta.containsKey("table"), "unchanged table metadata must remain in the baseline");
        assertFalse(((Map<?,?>)((List<?>)delta.get("rows")).getFirst()).containsKey("source_alias"),
            "changed rows replace the entire prior row so missing optional fields are cleared");
    }

    @Test
    void orderedObserverBurstPreservesEveryRevisionWithoutResnapshot() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> blockedTags = new AbstractList<>()
        {
            @Override public int size() { return 1; }
            @Override public String get(int index)
            {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                return "VOICE";
            }
        };
        StatsLiveService service = StatsLiveService.fromActivitySource(new TestChannelActivitySource(), null);
        try
        {
            service.start();
            try(StatsLiveEventHub.Subscription subscription = service.subscribeChannelActivity(true))
            {
                service.receiveChannelActivity(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
                    activity("site", List.of(activityRow("first", null, blockedTags, null))).snapshot(), 1));
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                for(int revision = 2; revision <= 20; revision++)
                {
                    service.receiveChannelActivity(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
                        activity("site", List.of(activityRow("call-" + revision))).snapshot(), revision));
                }
                release.countDown();
                for(long revision = 1; revision <= 20; revision++)
                {
                    StatsLiveEventHub.LiveEvent event = subscription.poll(1, TimeUnit.SECONDS);
                    assertNotNull(event);
                    assertEquals("activity_delta", event.name());
                    Map<?,?> delta = (Map<?,?>)event.data();
                    assertEquals(revision, delta.get("revision"));
                    assertEquals(revision - 1, delta.get("base_revision"));
                    assertNotNull(event.baseline(), "a shared full table is available for truncated initial baselines");
                }
                assertEquals(0, service.droppedProjectionEvents());
            }
        }
        finally
        {
            release.countDown();
            service.close();
        }
    }

    @Test
    void capsRowsWithinEachActivityTableAndReportsTheOriginalCount()
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        int total = StatsLiveService.MAXIMUM_ROWS_PER_TABLE + 17;
        List<ChannelActivitySnapshot.Row> rows = IntStream.range(0, total)
            .mapToObj(index -> activityRow("row-" + index))
            .toList();

        try
        {
            source.publish(activity("bounded-table", rows));
            Map<String,Object> table = tables(service).getFirst();
            List<Map<String,Object>> boundedRows = rows(table);
            assertEquals(StatsLiveService.MAXIMUM_ROWS_PER_TABLE, boundedRows.size());
            assertEquals(total, table.get("rows_total"));
            assertEquals(true, table.get("rows_truncated"));
            assertEquals("row-0", boundedRows.getFirst().get("key"));
            assertEquals("row-" + (StatsLiveService.MAXIMUM_ROWS_PER_TABLE - 1),
                boundedRows.getLast().get("key"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void projectsProtocolNeutralHoverDetails()
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("row", "Dispatch", null, "CALL",
            List.of("VOICE"), 1L, "0-101", 851_012_500L, "WPFF205", -22.5, null, 0L, 0L, 0L, 0L, 0L,
            0L, 4_321L, null, 2, "1201", "RADIO", "Engine 1", "Engine company one", "Portable 12",
            "Engine 1 · Portable 12", "4400", "TALKGROUP", "Fire Dispatch", "Primary dispatch",
            "P25_PHASE1", null, new ChannelActivitySnapshot.Navigation(
            "728d2d66-de4e-476b-a696-919f32dd4d12", 41L, "County",
            "p25", List.of(new ChannelActivitySnapshot.AliasReference(301L, 41L, "Engine 1")),
            new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 1201),
            List.of(new ChannelActivitySnapshot.AliasReference(302L, 41L, "Fire Dispatch")),
            new ChannelActivitySnapshot.MatcherReference("talkgroup", "p25", "phase_1", 4400)), "TRAFFIC");
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("site", "Live", "County", "Downtown",
            "Primary", null, true, true,
            List.of(new ChannelActivitySnapshot.IdentifierField("System", "WACN", "BEE00"),
                new ChannelActivitySnapshot.IdentifierField("Site", "NAC", "343")), List.of(row));

        try
        {
            source.publish(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot));
            Map<String,Object> table = tables(service).getFirst();
            List<Map<String,Object>> identifiers = (List<Map<String,Object>>)table.get("identifiers");
            Map<String,Object> projected = rows(table).getFirst();
            assertEquals("BEE00", identifiers.getFirst().get("value"));
            assertEquals("TRAFFIC", projected.get("role"));
            assertEquals(1L, projected.get("activation_order"));
            assertEquals("RADIO", projected.get("source_form"));
            assertEquals("TALKGROUP", projected.get("target_form"));
            assertEquals("WPFF205", projected.get("callsign"));
            assertEquals("Engine company one", projected.get("source_alias_description"));
            assertEquals("Primary dispatch", projected.get("target_alias_description"));
            assertEquals(4_321L, projected.get("cc_last_valid_decode_ms"));
            assertEquals(true, table.get("channel_running"));
            assertEquals("728d2d66-de4e-476b-a696-919f32dd4d12", projected.get("configuration_id"));
            assertEquals(41L, projected.get("alias_list_id"));
            assertFalse(projected.containsKey("context_key"));
            assertEquals("County", projected.get("alias_list_name"));
            assertEquals("p25", projected.get("protocol"));
            List<Map<String,Object>> sourceAliases =
                (List<Map<String,Object>>)projected.get("source_aliases");
            assertEquals(301L, sourceAliases.getFirst().get("alias_id"));
            assertEquals(41L, sourceAliases.getFirst().get("alias_list_id"));
            assertFalse(projected.containsKey("target_matcher"),
                "raw matcher hints are internal inputs, not browser navigation contracts");
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void projectsExplicitP25SubscriberIdentitySeparatelyFromObservedWorkingIds()
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        ChannelActivitySnapshot.Navigation canonicalNavigation = new ChannelActivitySnapshot.Navigation(
            null, 41L, "County", "p25", List.of(),
            new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 130_001,
                "v1-r-bee00-348-9601699", 130_001,
                new io.github.dsheirer.identifier.radio.ResolvedRadioIdentity(
                    io.github.dsheirer.protocol.Protocol.APCO25, 130_001,
                    new io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity(0xBEE00, 0x348, 9_601_699),
                    io.github.dsheirer.identifier.radio.ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT)),
            List.of(), new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 130_002,
                "v1-r-abcde-123-1234567", 130_002));
        ChannelActivitySnapshot.Navigation localNavigation = new ChannelActivitySnapshot.Navigation(
            null, 41L, "County", "p25", List.of(),
            new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 1201),
            List.of(), new ChannelActivitySnapshot.MatcherReference("talkgroup", "p25", "phase_1", 4400,
                "v1-g-bee00-348-4400"));
        ChannelActivitySnapshot.Navigation nativeNavigation = new ChannelActivitySnapshot.Navigation(
            null, 41L, "County", "p25", List.of(),
            new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 501,
                "v1-r-bee00-348-501", 501), List.of(), null);
        ChannelActivitySnapshot.Navigation identityOnlyNavigation = new ChannelActivitySnapshot.Navigation(
            null, 41L, "County", "p25", List.of(),
            new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 502,
                "v1-r-bee00-348-502", null,
                new io.github.dsheirer.identifier.radio.ResolvedRadioIdentity(
                    io.github.dsheirer.protocol.Protocol.APCO25, null,
                    new io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity(0xBEE00, 0x348, 502),
                    io.github.dsheirer.identifier.radio.ResolvedRadioIdentity.Evidence.DIRECT)), List.of(), null);

        try
        {
            source.publish(activity("identity", List.of(
                activityRow("canonical", canonicalNavigation), activityRow("local", localNavigation),
                activityRow("native", nativeNavigation), activityRow("identity-only", identityOnlyNavigation))));
            List<Map<String,Object>> projected = rows(tables(service).getFirst());
            Map<String,Object> canonical = projected.getFirst();
            assertEquals(Map.of("wacn", 0xBEE00, "system_id", 0x348, "subscriber_id", 9_601_699),
                canonical.get("source_canonical_identity"));
            assertEquals(130_001, canonical.get("source_observed_working_id"));
            assertEquals("registration_mapping", canonical.get("source_identity_source"));
            assertEquals(Map.of("wacn", 0xABCDE, "system_id", 0x123, "subscriber_id", 1_234_567),
                canonical.get("target_canonical_identity"));
            assertEquals(130_002, canonical.get("target_observed_working_id"));

            Map<String,Object> local = projected.get(1);
            assertFalse(local.containsKey("source_canonical_identity"));
            assertFalse(local.containsKey("source_observed_working_id"));
            assertFalse(local.containsKey("target_canonical_identity"),
                "a fully-qualified talkgroup key must never be presented as a subscriber");

            Map<String,Object> nativeIdentity = projected.get(2);
            assertEquals(Map.of("wacn", 0xBEE00, "system_id", 0x348, "subscriber_id", 501),
                nativeIdentity.get("source_canonical_identity"));
            assertEquals(501, nativeIdentity.get("source_observed_working_id"),
                "an explicit local field remains a separate WUID fact even when its number is equal");

            Map<String,Object> identityOnly = projected.get(3);
            assertEquals(Map.of("wacn", 0xBEE00, "system_id", 0x348, "subscriber_id", 502),
                identityOnly.get("source_canonical_identity"));
            assertFalse(identityOnly.containsKey("source_observed_working_id"),
                "a canonical identity alone must not infer an equal working assignment");
            assertEquals("explicit_identity", identityOnly.get("source_identity_source"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void capsTheAuthoritativeSnapshotWithoutBrowserOwnedCache() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);

        try
        {
            service.start();

            for(int index = 0; index < StatsLiveService.MAXIMUM_LIVE_TABLES; index++)
            {
                String tableId = "table-%03d".formatted(index);
                source.publish(activity(tableId, List.of(activityRow("row-" + index))));
            }

            try(StatsLiveEventHub.Subscription subscription = service.subscribeChannelActivity())
            {
                source.publish(activity("table-%03d".formatted(StatsLiveService.MAXIMUM_LIVE_TABLES),
                    List.of(activityRow("omitted"))));
                boolean receivedUpdate = false;

                for(int attempt = 0; attempt < 4 && !receivedUpdate; attempt++)
                {
                    StatsLiveEventHub.LiveEvent update = subscription.poll(1, TimeUnit.SECONDS);
                    receivedUpdate = update != null && "activity_table".equals(update.name());
                }

                assertTrue(receivedUpdate,
                    "the bounded adapter must publish the latest table even when earlier updates were coalesced");

                Map<String,Object> snapshot = service.snapshot();
                List<Map<String,Object>> tables = tables(snapshot);
                assertEquals(StatsLiveService.MAXIMUM_LIVE_TABLES, tables.size());
                assertEquals(StatsLiveService.MAXIMUM_LIVE_TABLES, snapshot.get("table_limit"));
                assertEquals(1, snapshot.get("tables_omitted_at_least"));
                assertEquals(true, snapshot.get("truncated"));
                assertEquals("table-000", tables.getFirst().get("table_id"));
                assertEquals("table-%03d".formatted(StatsLiveService.MAXIMUM_LIVE_TABLES - 1),
                    tables.getLast().get("table_id"));
                assertFalse(tables.stream().anyMatch(table ->
                    ("table-%03d".formatted(StatsLiveService.MAXIMUM_LIVE_TABLES)).equals(table.get("table_id"))));

                long revision = ((Number)snapshot.get("revision")).longValue();
                source.publish(activity("table-000", List.of(activityRow("updated"))));
                assertEquals(revision + 1, ((Number)service.snapshot().get("revision")).longValue(),
                    "the authoritative truncation snapshot must preserve contiguous revisions");
                assertEquals("updated", rows(tables(service).getFirst()).getFirst().get("key"));
            }

            assertEquals(StatsLiveService.MAXIMUM_LIVE_TABLES, tables(service).size(),
                "browser disconnect must not change authoritative activity state");
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void capsAliasReferencesForEachLiveIdentifier()
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        List<ChannelActivitySnapshot.AliasReference> aliases = IntStream.range(0, 20)
            .mapToObj(index -> new ChannelActivitySnapshot.AliasReference(index + 1L, 41L,
                "Alias " + index)).toList();
        ChannelActivitySnapshot.Navigation navigation = new ChannelActivitySnapshot.Navigation(null, 41L, "County",
            "dmr", aliases, new ChannelActivitySnapshot.MatcherReference("radio", "dmr", null, 1201),
            aliases, new ChannelActivitySnapshot.MatcherReference("talkgroup", "dmr", null, 4400));

        try
        {
            source.publish(activity("aliases", List.of(activityRow("row", navigation))));
            Map<String,Object> row = rows(tables(service).getFirst()).getFirst();
            assertEquals(8, ((List<?>)row.get("source_aliases")).size());
            assertEquals(8, ((List<?>)row.get("target_aliases")).size());
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void projectsOnlyCatalogOwnedCanonicalNavigation()
    {
        String configurationId = "728d2d66-de4e-476b-a696-919f32dd4d12";
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() ->
            WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                configurationId, WebEntityRef.channel(configurationId),
                WebEntityRef.radioSystem("p25:bee00:49f"), 1, 0, 0xBEE00, 0x49F))), 60_000L);
        catalog.refreshNow();
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, catalog);
        ChannelActivitySnapshot.Navigation navigation = new ChannelActivitySnapshot.Navigation(null, 41L, "County",
            "p25", List.of(), new ChannelActivitySnapshot.MatcherReference("radio", "p25", null, 1201),
            List.of(), new ChannelActivitySnapshot.MatcherReference("patch_group", "p25", null, 4400,
                "v1-p-bee00-49f-4400"));
        ChannelActivitySnapshot.Row row = activityRow("row", configurationId, List.of("VOICE"), navigation);
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("site", "Live", "County", "Downtown",
            "Primary", configurationId, true, true, List.of(), List.of(row),
            new ChannelActivitySnapshot.Site(0xBEE00, 0x49F, 3, 7, 0x293));

        try
        {
            service.start();
            source.publish(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot));
            Map<String,Object> table = tables(service).getFirst();
            Map<String,Object> projected = rows(table).getFirst();
            assertEquals(Map.of("kind", "channel", "key", configurationId), table.get("entity_ref"));
            assertEquals("p25:bee00:49f", table.get("radio_system_key"));
            assertEquals(Map.of("wacn", 0xBEE00, "system_id", 0x49F, "rfss", 3, "site", 7,
                "nac", 0x293), table.get("site"));
            assertEquals(Map.of("kind", "channel", "key", configurationId), projected.get("entity_ref"));
            assertEquals(Map.of("kind", "radio", "radio_system_key", "p25:bee00:49f",
                "identity_key", "v1-r-x-x-1201"),
                projected.get("source_entity_ref"));
            assertEquals(Map.of("kind", "patch_group", "radio_system_key", "p25:bee00:49f",
                "identity_key", "v1-p-bee00-49f-4400"),
                projected.get("target_entity_ref"));
            assertFalse(projected.containsKey("source_identity_key"));
            assertFalse(projected.containsKey("target_identity_key"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void structuredP25SiteMismatchSuppressesStaleCatalogSystemScope()
    {
        String configurationId = "728d2d66-de4e-476b-a696-919f32dd4d12";
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() ->
            WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                configurationId, WebEntityRef.channel(configurationId),
                WebEntityRef.radioSystem("p25:bee00:49f"), 1, 0, 0xBEE00, 0x49F))), 60_000L);
        catalog.refreshNow();
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, catalog);
        ChannelActivitySnapshot.Navigation navigation = new ChannelActivitySnapshot.Navigation(null, 41L, "County",
            "p25", List.of(), new ChannelActivitySnapshot.MatcherReference("radio", "p25", null, 1201),
            List.of(), new ChannelActivitySnapshot.MatcherReference("talkgroup", "p25", null, 4400));
        ChannelActivitySnapshot.Row row = activityRow("row", configurationId, List.of("VOICE"), navigation);
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("site", "Live", "Other", "Downtown",
            "Primary", configurationId, true, true, List.of(), List.of(row),
            new ChannelActivitySnapshot.Site(0xABCDE, 0x123, 3, 7, 0x293));

        try
        {
            source.publish(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot));
            Map<String,Object> table = tables(service).getFirst();
            Map<String,Object> projected = rows(table).getFirst();

            assertFalse(table.containsKey("radio_system_key"));
            assertEquals(Map.of("kind", "channel", "key", configurationId), table.get("entity_ref"));
            assertEquals(Map.of("wacn", 0xABCDE, "system_id", 0x123, "rfss", 3, "site", 7,
                "nac", 0x293), table.get("site"));
            assertFalse(projected.containsKey("source_entity_ref"));
            assertFalse(projected.containsKey("target_entity_ref"));
            assertTrue(String.valueOf(projected.get("source_identity_key")).startsWith("v1-local-"));
            assertTrue(String.valueOf(projected.get("target_identity_key")).startsWith("v1-local-"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void exposesBoundedSourceIngressDropsToTheTransportAdapter()
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);

        try
        {
            assertEquals(0, service.droppedActivityIngressEvents());
            source.recordIngressDrops(3);
            assertEquals(3, service.droppedActivityIngressEvents());
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void retainedLiveRowCannotRebindToAnotherRadioSystemUntilItsNextActivation()
    {
        String configurationId = "728d2d66-de4e-476b-a696-919f32dd4d12";
        AtomicReference<WebEntityNavigationCatalog.Snapshot> loaded = new AtomicReference<>(
            navigationSnapshot(configurationId, "p25:bee00:49f", 0x49F));
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(loaded::get, 60_000L);
        catalog.refreshNow();
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, catalog);
        ChannelActivitySnapshot.Navigation navigation = new ChannelActivitySnapshot.Navigation(null, 41L, "County",
            "p25", List.of(), new ChannelActivitySnapshot.MatcherReference("radio", "p25", null, 1201),
            List.of(), new ChannelActivitySnapshot.MatcherReference("talkgroup", "p25", null, 4400));

        try
        {
            source.publish(activity("site", List.of(activityRow("call", configurationId,
                List.of("VOICE"), navigation, 1L))));
            Map<String,Object> onSystemA = rows(tables(service).getFirst()).getFirst();
            assertEquals("p25:bee00:49f", map(onSystemA, "source_entity_ref").get("radio_system_key"));

            loaded.set(navigationSnapshot(configurationId, "p25:bee00:4a0", 0x4A0));
            catalog.refreshNow();
            Map<String,Object> retainedAfterRebind = rows(tables(service).getFirst()).getFirst();
            assertFalse(retainedAfterRebind.containsKey("source_entity_ref"));
            assertFalse(retainedAfterRebind.containsKey("target_entity_ref"));
            assertTrue(String.valueOf(retainedAfterRebind.get("source_identity_key")).startsWith("v1-local-"));
            assertTrue(String.valueOf(retainedAfterRebind.get("target_identity_key")).startsWith("v1-local-"));
            assertEquals(Map.of("kind", "channel", "key", configurationId),
                retainedAfterRebind.get("entity_ref"));

            source.publish(activity("site", List.of(activityRow("call", configurationId,
                List.of("VOICE"), navigation, 2L))));
            Map<String,Object> nextActivation = rows(tables(service).getFirst()).getFirst();
            assertEquals("p25:bee00:4a0", map(nextActivation, "source_entity_ref").get("radio_system_key"));
            assertEquals("p25:bee00:4a0", map(nextActivation, "target_entity_ref").get("radio_system_key"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void rebuiltCatalogInvalidatesTheEncodedLiveSnapshot() throws Exception
    {
        String configurationId = "728d2d66-de4e-476b-a696-919f32dd4d12";
        AtomicReference<WebEntityNavigationCatalog.Snapshot> loaded =
            new AtomicReference<>(WebEntityNavigationCatalog.Snapshot.empty());
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(loaded::get, 60_000L);
        catalog.refreshNow();
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, catalog);
        ChannelActivitySnapshot.Row row = activityRow("row", configurationId, List.of("CONTROL"), null);
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("site", "Live", "County", "Downtown",
            "Primary", configurationId, true, true, List.of(), List.of(row));

        try
        {
            source.publish(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot));
            service.start();
            byte[] withoutNavigation = service.encodedSnapshot();
            assertFalse(new String(withoutNavigation, java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"entity_ref\""));

            try(StatsLiveEventHub.Subscription subscription = service.subscribeChannelActivity())
            {
                loaded.set(WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
                    configurationId, WebEntityRef.channel(configurationId),
                    WebEntityRef.radioSystem("p25:bee00:49f"), 1, 0, 0xBEE00, 0x49F))));
                catalog.refreshNow();

                boolean resynchronized = false;

                for(int index = 0; index < 3 && !resynchronized; index++)
                {
                    StatsLiveEventHub.LiveEvent published = subscription.poll(1, TimeUnit.SECONDS);
                    resynchronized = published != null && "activity_resync".equals(published.name());
                }

                assertTrue(resynchronized,
                    "a catalog change must update already-connected live clients without waiting for activity");
            }

            byte[] withNavigation = service.encodedSnapshot();
            assertFalse(withoutNavigation == withNavigation,
                "a changed catalog must rebuild a live snapshot whose activity revision is unchanged");
            assertTrue(new String(withNavigation, java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"entity_ref\""));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void receiverHandoffNeverProjectsOnTheProducerAndCoalescesWhenSaturated() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        List<String> blockingTags = new AbstractList<>()
        {
            @Override
            public String get(int index)
            {
                projectionThread.set(Thread.currentThread());
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await(2, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return "VOICE";
            }

            @Override
            public int size()
            {
                return 1;
            }
        };
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        Thread producer = Thread.currentThread();

        try
        {
            service.start();
            try(StatsLiveEventHub.Subscription subscription = service.subscribeChannelActivity())
            {
                ChannelActivitySnapshot blocked = new ChannelActivitySnapshot("blocked", "Live", "System", "Site",
                    "Control", null, true, true, List.of(),
                    List.of(activityRow("blocked", null, blockingTags, null)));
                service.receiveChannelActivity(
                    new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, blocked, 1));
                assertTrue(projectionEntered.await(1, TimeUnit.SECONDS));
                assertFalse(producer == projectionThread.get(),
                    "projection must execute on the low-priority observer worker");

                for(int revision = 2; revision <= 400; revision++)
                {
                    service.receiveChannelActivity(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
                        activity("latest", List.of(activityRow("row"))).snapshot(), revision));
                }

                assertTrue(service.droppedProjectionEvents() > 0,
                    "a genuinely full ordered observer handoff must report loss without blocking the producer");
                releaseProjection.countDown();
                boolean resynchronized = false;

                for(int index = 0; index < 3 && !resynchronized; index++)
                {
                    StatsLiveEventHub.LiveEvent published = subscription.poll(1, TimeUnit.SECONDS);
                    resynchronized = published != null && "activity_resync".equals(published.name());
                }

                assertTrue(resynchronized,
                    "coalescing distinct revisions must publish an authoritative snapshot resync");
            }
        }
        finally
        {
            releaseProjection.countDown();
            service.close();
        }
    }

    @Test
    void stoppedProjectionGenerationCannotPublishOrOverwriteStateAfterRestart() throws Exception
    {
        CountDownLatch oldProjectionEntered = new CountDownLatch(1);
        CountDownLatch releaseOldProjection = new CountDownLatch(1);
        AtomicReference<Thread> oldProjectionThread = new AtomicReference<>();
        List<String> blockingTags = new AbstractList<>()
        {
            @Override
            public String get(int index)
            {
                oldProjectionThread.set(Thread.currentThread());
                oldProjectionEntered.countDown();
                boolean interrupted = false;

                while(true)
                {
                    try
                    {
                        releaseOldProjection.await();
                        break;
                    }
                    catch(InterruptedException exception)
                    {
                        //Deliberately hold the retired worker beyond stop's bounded join. Production projections do
                        //not normally ignore interruption, but the lifecycle must remain safe if optional work does.
                        interrupted = true;
                    }
                }

                if(interrupted)
                {
                    Thread.currentThread().interrupt();
                }

                return "VOICE";
            }

            @Override
            public int size()
            {
                return 1;
            }
        };
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        StatsLiveEventHub.Subscription oldSubscription = null;

        try
        {
            service.start();
            assertEquals(1, source.listenerCount());
            var oldListener = source.listeners().getFirst();
            oldSubscription = service.subscribeChannelActivity();
            assertNotNull(oldSubscription);

            ChannelActivitySnapshot blocked = new ChannelActivitySnapshot("old-generation", "Old", "System",
                "Site", "Control", null, true, true, List.of(),
                List.of(activityRow("old-row", null, blockingTags, null)));
            service.receiveChannelActivity(
                new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, blocked, 1));
            assertTrue(oldProjectionEntered.await(1, TimeUnit.SECONDS));

            service.stop();
            assertTrue(oldProjectionThread.get().isAlive(),
                "the test must exercise a worker that outlives stop's bounded join");
            assertTrue(oldSubscription.isClosed());
            assertEquals(0, source.listenerCount());

            service.start();
            assertEquals(1, source.listenerCount());
            try(StatsLiveEventHub.Subscription currentSubscription = service.subscribeChannelActivity())
            {
                assertNotNull(currentSubscription);
                source.publish(activity("current-generation", List.of(activityRow("current-row"))));
                StatsLiveEventHub.LiveEvent current = currentSubscription.poll(1, TimeUnit.SECONDS);
                assertNotNull(current);
                assertEquals("current-generation", tableId(current));
                byte[] encodedCurrent = service.encodedSnapshot();

                oldListener.receive(activity("stale-callback", List.of(activityRow("stale-row"))));
                assertNull(currentSubscription.poll(200, TimeUnit.MILLISECONDS),
                    "a callback retained from the stopped registration must not enter the new generation");

                releaseOldProjection.countDown();
                waitUntil(() -> !oldProjectionThread.get().isAlive());
                assertNull(currentSubscription.poll(200, TimeUnit.MILLISECONDS),
                    "the retired projection must not publish into the restarted event hub");
                assertSame(encodedCurrent, service.encodedSnapshot(),
                    "retired work must not invalidate or replace the current encoded snapshot");
            }

            service.stop();
            assertEquals(0, source.listenerCount());
        }
        finally
        {
            releaseOldProjection.countDown();
            if(oldSubscription != null)
            {
                oldSubscription.close();
            }
            service.close();
        }
    }

    @Test
    void enforcesOneGlobalRowAndEncodedByteBudgetAndReusesTheEncodedSnapshot() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        StatsLiveService service = StatsLiveService.fromActivitySource(source, null);
        List<ChannelActivitySnapshot.Row> rows = IntStream.range(0, StatsLiveService.MAXIMUM_ROWS_PER_TABLE)
            .mapToObj(index -> activityRow("x".repeat(2_000) + index))
            .toList();
        int tableCount = StatsLiveService.MAXIMUM_TOTAL_LIVE_ROWS / StatsLiveService.MAXIMUM_ROWS_PER_TABLE + 2;

        try
        {
            for(int index = 0; index < tableCount; index++)
            {
                source.publish(activity("global-" + index, rows));
            }

            Map<String,Object> snapshot = service.snapshot();
            assertEquals("System", tables(service).getFirst().get("system_name"));
            assertEquals("Site", tables(service).getFirst().get("site_name"));
            assertEquals(StatsLiveService.MAXIMUM_TOTAL_LIVE_ROWS, snapshot.get("rows_included"));
            assertEquals((long)tableCount * StatsLiveService.MAXIMUM_ROWS_PER_TABLE,
                snapshot.get("rows_total"));
            assertEquals(2L * StatsLiveService.MAXIMUM_ROWS_PER_TABLE, snapshot.get("rows_omitted"));
            assertEquals(true, snapshot.get("truncated"));

            byte[] first = service.encodedSnapshot();
            byte[] second = service.encodedSnapshot();
            assertTrue(first.length <= StatsLiveService.MAXIMUM_SYSTEM_SNAPSHOT_BYTES);
            assertSame(first, second, "unchanged subscribers should share one encoded snapshot");
        }
        finally
        {
            service.close();
        }
    }

    private static ChannelActivityEvent activity(String tableId, List<ChannelActivitySnapshot.Row> rows)
    {
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot(tableId, "Live", "System", "Site",
            "Control", null, true, true, List.of(), rows);
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

    private static ChannelActivitySnapshot.Row activityRow(String key)
    {
        return activityRow(key, null);
    }

    private static ChannelActivitySnapshot.Row activityRow(String key,
                                                            ChannelActivitySnapshot.Navigation navigation)
    {
        return activityRow(key, null, List.of("CONTROL"), navigation);
    }

    private static ChannelActivitySnapshot.Row activityRow(String key, String configurationId, List<String> tags,
                                                            ChannelActivitySnapshot.Navigation navigation)
    {
        return activityRow(key, configurationId, tags, navigation, 1L);
    }

    private static ChannelActivitySnapshot.Row activityRow(String key, String configurationId, List<String> tags,
                                                            ChannelActivitySnapshot.Navigation navigation,
                                                            long activationOrder)
    {
        return new ChannelActivitySnapshot.Row(key, "Control", configurationId, "ACTIVE", tags, activationOrder, "1",
            451_000_000L, null, -25.5, 98.0, 1_000L, 1L, 0L, 0L, 0L, 0L, 1_000L, null, null, null,
            null, null, null, null, null, null, null, null, null, "DMR", null, navigation, "CURRENT_CONTROL");
    }

    private static WebEntityNavigationCatalog.Snapshot navigationSnapshot(String configurationId,
                                                                           String radioSystemKey,
                                                                           int p25SystemId)
    {
        return WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
            configurationId, WebEntityRef.channel(configurationId), WebEntityRef.radioSystem(radioSystemKey),
            1, 0, 0xBEE00, p25SystemId)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Map<String,Object> value, String key)
    {
        return (Map<String,Object>)value.get(key);
    }

    @SuppressWarnings("unchecked")
    private static String tableId(StatsLiveEventHub.LiveEvent event)
    {
        return String.valueOf(((Map<String,Object>)event.data()).get("table_id"));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(5L);
        }

        assertTrue(condition.getAsBoolean());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> tables(StatsLiveService service)
    {
        return tables(service.snapshot());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> tables(Map<String,Object> snapshot)
    {
        return (List<Map<String,Object>>)snapshot.get("tables");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> table)
    {
        return (List<Map<String,Object>>)table.get("rows");
    }

}
