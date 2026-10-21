/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.remote.RemoteOriginLookup;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StatsLiveBatchTest
{
    @Test
    void unattendedResyncSkipsWireEncodingAndLaterViewerGetsTheCurrentBoundary() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        source.publish(update("a", 0));
        var origins = new AtomicReference<>(RemoteOriginLookup.OriginSnapshot.EMPTY);
        AtomicInteger changedOriginReads = new AtomicInteger();
        CountDownLatch afterResync = new CountDownLatch(1);
        try(StatsLiveService service = StatsLiveService.fromActivitySource(source, null, () -> {
            var current = origins.get();
            if(current.revision() == 1 && Thread.currentThread().getName().startsWith("stats live projection") &&
                changedOriginReads.incrementAndGet() == 2) afterResync.countDown();
            return current;
        }))
        {
            service.start();
            assertEquals(1, service.snapshotWire().revision());
            var mapperField = ApiHttpResponse.class.getDeclaredField("OBJECT_MAPPER");
            mapperField.setAccessible(true);
            var factory = ((com.fasterxml.jackson.databind.ObjectMapper)mapperField.get(null)).getFactory();
            var constraints = factory.streamWriteConstraints();
            try
            {
                // Make any attempted wire sizing fail, without adding a production counter or serializer hook.
                factory.setStreamWriteConstraints(com.fasterxml.jackson.core.StreamWriteConstraints.builder()
                    .maxNestingDepth(1).build());
                assertThrows(java.io.IOException.class, () -> ApiHttpResponse.encodePayload(
                    Map.of("outer", Map.of("inner", Map.of("value", 1)))));
                source.publish(update("b", 2));
                origins.set(new RemoteOriginLookup.OriginSnapshot(1, Map.of()));
                assertTrue(afterResync.await(3, TimeUnit.SECONDS), "The observer must return after resynchronizing");
                assertEquals(0, service.projectionFailures(), "Unattended resync must not serialize a wire document");
                factory.setStreamWriteConstraints(constraints);

                // Match the HTTP ordering: subscribe first, then capture the already-published boundary.
                try(var subscription = service.subscribeChannelActivity())
                {
                    var snapshot = service.snapshotWire();
                    assertEquals(2, snapshot.revision());
                    byte[] bytes = snapshot.frame().bytes(false);
                    var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        java.util.Arrays.copyOfRange(bytes, LiveMultiplexFrame.HEADER_BYTES, bytes.length)).path("data");
                    assertEquals(2, data.path("tables").size());
                    assertEquals("0", data.at("/tables/0/rows/0/source_id").asText());
                    assertEquals("2", data.at("/tables/1/rows/0/source_id").asText());
                    source.publish(update("a", 3));
                    var following = subscription.poll(2, TimeUnit.SECONDS);
                    assertNotNull(following);
                    assertEquals("activity_batch", following.name());
                    assertEquals(2L, ((Map<?,?>)following.data()).get("base_revision"));
                    assertEquals(3L, ((Map<?,?>)following.data()).get("revision"));
                }
            }
            finally { factory.setStreamWriteConstraints(constraints); }
        }
    }

    @Test
    void publishesWhileNotificationsContinueWithoutWaitingForSourceQuiescence() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        AtomicBoolean running = new AtomicBoolean(true);
        try(StatsLiveService service = StatsLiveService.fromActivitySource(source, null))
        {
            service.start();
            service.snapshotWire();
            try(var subscription = service.subscribeChannelActivity())
            {
                Thread producer = Thread.startVirtualThread(() -> {
                    int value = 0;
                    while(running.get())
                    {
                        source.publish(update("continuous", ++value));
                        try { Thread.sleep(2); }
                        catch(InterruptedException exception) { return; }
                    }
                });
                try
                {
                    long revision = 0;
                    for(int index = 0; index < 3; index++)
                    {
                        var event = subscription.poll(1, TimeUnit.SECONDS);
                        assertNotNull(event, "Publication must not wait for a quiet receiver");
                        assertTrue(producer.isAlive());
                        Map<?,?> batch = (Map<?,?>)event.data();
                        assertEquals(revision, batch.get("base_revision"));
                        revision = ((Number)batch.get("revision")).longValue();
                    }
                }
                finally { running.set(false); producer.interrupt(); producer.join(1_000); }
            }
        }
    }

    @Test
    void publishedWireSnapshotRetainsOmissionCountsBeyondTheTableLimit() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        for(int index = 0; index <= StatsLiveService.MAXIMUM_LIVE_TABLES; index++)
            source.publish(update("table-%03d".formatted(index), 0));
        try(StatsLiveService service = StatsLiveService.fromActivitySource(source, null))
        {
            service.start();
            var wire = service.snapshotWire();
            byte[] frame = wire.frame().bytes(false);
            var document = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                java.util.Arrays.copyOfRange(frame, 16, frame.length)).path("data");
            assertEquals(129, wire.revision());
            assertEquals(128, document.path("tables_included").intValue());
            assertEquals(1, document.path("tables_omitted_at_least").intValue());
            assertEquals(129, document.path("rows_total").intValue());
            assertEquals(1, document.path("rows_omitted").intValue());
            assertTrue(document.path("truncated").booleanValue());
        }
    }

    @Test
    void twoHundredBrowsersShareCoalescedChangesAndKeepTheGlobalRevisionChain() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        source.publish(update("a", 0));
        source.publish(update("b", 0));
        List<StatsLiveEventHub.Subscription> subscriptions = new ArrayList<>();
        try(StatsLiveService service = StatsLiveService.fromActivitySource(source, null))
        {
            service.start();
            assertEquals(2, service.snapshotWire().revision());
            for(int index = 0; index < 200; index++) subscriptions.add(service.subscribeChannelActivity());
            assertTrue(subscriptions.stream().allMatch(java.util.Objects::nonNull));
            for(int value = 1; value <= 100; value++)
            {
                source.publish(update("a", value));
                source.publish(update("b", value));
            }
            long revision = 2;
            int batches = 0;
            Map<String,Map<String,Object>> latestRows = new LinkedHashMap<>();
            while(revision < 202)
            {
                StatsLiveEventHub.LiveEvent event = subscriptions.getFirst().poll(2, TimeUnit.SECONDS);
                assertNotNull(event);
                assertEquals("activity_batch", event.name());
                Map<?,?> batch = (Map<?,?>)event.data();
                assertEquals(revision, ((Number)batch.get("base_revision")).longValue());
                long next = ((Number)batch.get("revision")).longValue();
                assertTrue(next > revision);
                assertTrue(event.frame(1).bytes(false).length <=
                    LiveMultiplexOutput.EVENT_BYTE_CAPACITY_PER_TOPIC);
                for(int index = 1; index < subscriptions.size(); index++)
                {
                    var shared = subscriptions.get(index).poll(1, TimeUnit.SECONDS);
                    assertSame(event, shared, "One shared batch, rather than one projection per visitor");
                    assertSame(event.frame(1), shared.frame(1));
                }
                for(Map<String,Object> change: changes(event))
                {
                    assertTrue(((List<?>)change.get("rows")).size() <= 1);
                    @SuppressWarnings("unchecked")
                    var rows = (List<Map<String,Object>>)change.get("rows");
                    if(!rows.isEmpty()) latestRows.put((String)change.get("table_id"), rows.getFirst());
                }
                revision = next;
                batches++;
            }
            assertTrue(batches < 20, "200 source notifications must not become 200 browser frames");
            assertEquals("100", latestRows.get("a").get("source_id"));
            assertEquals("100", latestRows.get("b").get("source_id"));
            assertEquals(202, service.snapshotWire().revision());

            source.publish(new ChannelActivityEvent(ChannelActivityEvent.Operation.REMOVE, update("a", 100).snapshot()));
            source.publish(update("b", 101));
            var following = subscriptions.getFirst().poll(2, TimeUnit.SECONDS);
            assertNotNull(following);
            assertEquals(202L, ((Map<?,?>)following.data()).get("base_revision"));
            assertEquals(204L, ((Map<?,?>)following.data()).get("revision"));
            assertTrue(changes(following).stream().anyMatch(change ->
                "a".equals(change.get("table_id")) && "remove".equals(change.get("operation"))));
        }
        finally { subscriptions.forEach(subscription -> { if(subscription != null) subscription.close(); }); }
    }

    @Test
    void markerAndFullBaselinesUseTheSameBatchRevisionAndRemoveOptionalFields() throws Exception
    {
        TestChannelActivitySource source = new TestChannelActivitySource();
        source.publish(update("site", 0));
        try(StatsLiveService service = StatsLiveService.fromActivitySource(source, null))
        {
            service.start();
            assertEquals(1, service.snapshotWire().revision());
            try(var normal = service.subscribeChannelActivity(); var marker = service.subscribeChannelActivity(true))
            {
                source.publish(update("site", 1));
                var full = normal.poll(2, TimeUnit.SECONDS);
                var compact = marker.poll(2, TimeUnit.SECONDS);
                assertNotNull(full);
                assertNotNull(compact);
                assertEquals(((Map<?,?>)full.data()).get("base_revision"), ((Map<?,?>)compact.data()).get("base_revision"));
                assertEquals(((Map<?,?>)full.data()).get("revision"), ((Map<?,?>)compact.data()).get("revision"));
                Map<?,?> row = (Map<?,?>)((List<?>)changes(full).getFirst().get("rows")).getFirst();
                Map<?,?> markerRow = (Map<?,?>)((List<?>)changes(compact).getFirst().get("rows")).getFirst();
                assertFalse(row.containsKey("source_alias"), "A changed row replaces optional fields, not merges them");
                assertTrue(row.containsKey("cc_valid_frames"));
                assertFalse(markerRow.containsKey("cc_valid_frames"));
                assertEquals(true, changes(full.baseline()).getFirst().get("replace"));
                assertEquals(true, changes(compact.baseline()).getFirst().get("replace"));
                assertSame(full.baseline().frame(1), full.baseline().frame(1));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> changes(StatsLiveEventHub.LiveEvent event)
    {
        return (List<Map<String,Object>>)((Map<?,?>)event.data()).get("changes");
    }

    private static ChannelActivityEvent update(String id, int value)
    {
        var row = new ChannelActivitySnapshot.Row("row", "Control", null, "ACTIVE", List.of("CONTROL"),
            1L, "1", 451_000_000L, null, -25.5, 98.0, 1_000L, value, 0L, 0L, 0L, 0L, 1_000L,
            null, null, String.valueOf(value), "RADIO", value == 0 ? "Old alias" : null,
            null, null, null, null, null, null, null, "DMR", null, null, "CURRENT_CONTROL", null, null, null);
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT,
            new ChannelActivitySnapshot(id, "Live", "System", "Site", "Control", null,
                true, true, List.of(), List.of(row)));
    }
}
