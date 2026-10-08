/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class SavedActivityLiveServiceTest
{
    @Test
    void startsAtLiveEdgeAndSharesOneReaderWhileDemandTracksLastViewer() throws Exception
    {
        FakeSource source = new FakeSource(10);
        List<Boolean> demand = new ArrayList<>();
        try(SavedActivityLiveService service = new SavedActivityLiveService(source, demand::add, () -> 1500, () -> true))
        {
            assertEquals(0, source.reads.get());
            try(var first = service.subscribe(); var second = service.subscribe())
            {
                awaitInitialized(service);
                assertEquals(10, service.state().watermarkId());
                assertNull(first.poll(0, TimeUnit.MILLISECONDS), "The seed must never be replayed as live activity");
                source.maximum.set(11);
                service.signalCommit();
                assertEquals(11L, rowId(first.poll(2, TimeUnit.SECONDS)));
                assertEquals(11L, rowId(second.poll(2, TimeUnit.SECONDS)));
                assertEquals(1, source.reads.get(), "Readers are shared across browsers");
                first.close();
                assertEquals(List.of(true), demand);
            }
            assertEquals(List.of(true, false), demand);
        }
    }

    @Test
    void postCommitSignalNeverWaitsForBlockedReaderAndClosedEpochCannotPublish() throws Exception
    {
        FakeSource source = new FakeSource(3);
        source.readGate = new CountDownLatch(1);
        try(SavedActivityLiveService service = new SavedActivityLiveService(source, ignored -> {}, () -> 1000, () -> true))
        {
            var old = service.subscribe();
            awaitInitialized(service);
            source.maximum.set(4);
            service.signalCommit();
            assertTrue(source.readEntered.await(2, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(1), () -> {
                for(int index = 0; index < 10_000; index++) service.signalCommit();
            });
            old.close();
            source.maximum.set(5);
            var replacement = service.subscribe();
            source.readGate.countDown();
            awaitInitialized(service);
            assertEquals(5, service.state().watermarkId());
            assertNull(replacement.poll(50, TimeUnit.MILLISECONDS), "An old reader cannot publish into a new epoch");
            replacement.close();
        }
    }

    @Test
    void commitDuringContinuationIsDrainedAfterTheFixedWatermark() throws Exception
    {
        FakeSource source = new FakeSource(1);
        source.pageSize = 1;
        AtomicReference<SavedActivityLiveService> serviceReference = new AtomicReference<>();
        source.onRead = after -> {
            if(after == 5)
            {
                source.maximum.set(8);
                serviceReference.get().signalCommit();
            }
        };
        try(SavedActivityLiveService service = new SavedActivityLiveService(source, ignored -> {}, () -> 1000, () -> true);
            var subscription = service.subscribe())
        {
            serviceReference.set(service);
            awaitInitialized(service);
            source.maximum.set(7);
            service.signalCommit();
            for(long id = 2; id <= 8; id++) assertEquals(id, rowId(subscription.poll(2, TimeUnit.SECONDS)));
            assertNull(subscription.poll(20, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void unchangedClockDoesNotCreateSourceChangesAndSettingsRefreshWithoutDatabaseReads() throws Exception
    {
        FakeSource source = new FakeSource(10);
        AtomicInteger timeout = new AtomicInteger(1000);
        CountDownLatch refreshed = new CountDownLatch(2);
        try(SavedActivityLiveService service = new SavedActivityLiveService(source, ignored -> {}, () -> {
            int value = timeout.get();
            if(value == 1750) refreshed.countDown();
            return value;
        }, () -> true); var subscription = service.subscribe())
        {
            awaitInitialized(service);
            long previousRevision = service.state().revision();
            timeout.set(1750);
            assertTrue(refreshed.await(3, TimeUnit.SECONDS));
            assertEquals(previousRevision + 1, service.state().revision(),
                "Periodic server-time refresh must not force repeated HTTP catch-up");
            assertEquals(1750, service.state().grantTimeoutMs());
            assertEquals(0, source.reads.get(), "Idle settings refresh never re-reads history");
        }
    }

    @Test
    void slowBrowserQueueIsBoundedAndSourceMetadataUsesCurrentServerTime() throws Exception
    {
        FakeSource source = new FakeSource(1);
        source.pageSize = 1;
        AtomicInteger timeout = new AtomicInteger(1200);
        try(SavedActivityLiveService service = new SavedActivityLiveService(source, ignored -> {}, timeout::get,
            () -> true, 2, 1); var subscription = service.subscribe())
        {
            awaitInitialized(service);
            source.maximum.set(4);
            service.signalCommit();
            assertTrue(source.threeReads.await(2, TimeUnit.SECONDS));
            awaitDrops(subscription, 2);
            assertEquals(4L, rowId(subscription.poll(1, TimeUnit.SECONDS)));
            assertEquals(2, subscription.droppedCount());
            var staleState = new SavedActivityLiveService.State(1, true, 4, 1, 1200, true);
            long before = System.currentTimeMillis();
            Map<String,Object> payload = staleState.payload("view");
            assertTrue(((Number)payload.get("server_time_ms")).longValue() >= before);
            assertEquals(1200, payload.get("traffic_grant_age_out_milliseconds"));
            assertEquals(java.util.Set.of(io.github.dsheirer.web.auth.WebCapability.RADIO_VIEW,
                io.github.dsheirer.web.auth.WebCapability.DASHBOARD_VIEW),
                StatsWebServerService.capabilitiesForTopic("saved_activity"));
        }
    }

    private static long rowId(StatsLiveEventHub.LiveEvent event)
    {
        assertNotNull(event);
        assertEquals("activity_append", event.name());
        Map<?,?> payload = (Map<?,?>)event.data();
        Map<?,?> row = (Map<?,?>)((List<?>)payload.get("rows")).getFirst();
        return ((Number)row.get("id")).longValue();
    }

    private static void awaitInitialized(SavedActivityLiveService service)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while(!service.state().initialized() && System.nanoTime() < deadline) LockSupport.parkNanos(100_000);
        assertTrue(service.state().initialized());
    }

    private static void awaitDrops(StatsLiveEventHub.Subscription subscription, long expected)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while(subscription.droppedCount() < expected && System.nanoTime() < deadline) LockSupport.parkNanos(100_000);
        assertEquals(expected, subscription.droppedCount());
    }

    private static final class FakeSource implements SavedActivityLiveService.Source
    {
        final AtomicLong maximum;
        final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch threeReads = new CountDownLatch(3);
        volatile CountDownLatch readGate;
        int pageSize = 64;
        java.util.function.LongConsumer onRead = ignored -> {};

        FakeSource(long maximum) { this.maximum = new AtomicLong(maximum); }
        @Override public long watermark() { return maximum.get(); }
        @Override public Map<String,Object> after(long after, Long requestedWatermark)
        {
            reads.incrementAndGet();
            readEntered.countDown();
            if(readGate != null)
            {
                try { readGate.await(); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            long watermark = requestedWatermark != null ? requestedWatermark : maximum.get();
            onRead.accept(after);
            List<Map<String,Object>> rows = new ArrayList<>();
            for(long id = after + 1; id <= Math.min(watermark, after + pageSize); id++) rows.add(Map.of("id", id));
            long next = rows.isEmpty() ? watermark : ((Number)rows.getLast().get("id")).longValue();
            threeReads.countDown();
            return Map.of("rows", rows, "next_after_id", next, "watermark_id", watermark,
                "has_more", next < watermark, "reset_required", false);
        }
    }
}
