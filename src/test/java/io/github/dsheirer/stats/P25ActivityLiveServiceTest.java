package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.stats.activity.ActivityCommitJournal;
import io.github.dsheirer.stats.activity.ActivityJournalTestAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class P25ActivityLiveServiceTest
{
    @Test void twoHundredViewersShareOneReadAndOnePreparedFrameAndEnrichmentIsAnUpsert() throws Exception
    {
        Fixture fixture = new Fixture();
        List<StatsLiveEventHub.Subscription> viewers = new ArrayList<>();
        try(P25ActivityLiveService service = fixture.service())
        {
            Thread.sleep(280);
            assertEquals(0, fixture.reads.get(), "no viewers means no database projection");
            for(int index = 0; index < 200; index++) viewers.add(service.subscribe());
            await(() -> service.state().initialized());
            fixture.rows.put(1L, row(1, "initial"));
            ActivityJournalTestAccess.commit(fixture.journal.get(), 1);
            StatsLiveEventHub.LiveEvent first = viewers.getFirst().poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals("activity_delta", first.name());
            for(int index = 1; index < viewers.size(); index++)
                assertSame(first, viewers.get(index).poll(1, TimeUnit.SECONDS));
            assertEquals(1, fixture.reads.get());
            LiveMultiplexFrame frame = first.frame(7);
            assertSame(frame, first.frame(7));
            fixture.rows.put(1L, row(1, "enriched"));
            ActivityJournalTestAccess.commit(fixture.journal.get(), 1);
            StatsLiveEventHub.LiveEvent update = viewers.getFirst().poll(2, TimeUnit.SECONDS);
            assertEquals("activity_delta", update.name());
            assertEquals("enriched", rows(update).getFirst().get("label"));
            fixture.rows.remove(1L);
            ActivityJournalTestAccess.commit(fixture.journal.get(), 1);
            var removed = viewers.getFirst().poll(2, TimeUnit.SECONDS);
            assertEquals(List.of(1L), ((Map<?,?>)removed.data()).get("removed_ids"));
            viewers.forEach(StatsLiveEventHub.Subscription::close);
            Thread.sleep(350);
            int reads = fixture.reads.get();
            ActivityJournalTestAccess.commit(fixture.journal.get(), 2);
            Thread.sleep(350);
            assertEquals(reads, fixture.reads.get());
        }
        finally { viewers.forEach(StatsLiveEventHub.Subscription::close); }
    }

    @Test void catchupPinsPagesFreshlyReadsExistingIdsAndRejectsExpiredOrResetRanges() throws Exception
    {
        Fixture fixture = new Fixture();
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe())
        {
            await(() -> service.state().initialized());
            String epoch = service.state().epoch();
            fixture.rows.put(1L, row(1, "newer than seed"));
            fixture.rows.put(3L, row(3, "third"));
            ActivityJournalTestAccess.commit(fixture.journal.get(), 3, 2, 1);
            Map<String,Object> first = service.catchup(epoch, 0, null, 0, 1);
            assertFalse((boolean)first.get("resync_required"));
            assertEquals("newer than seed", ((List<Map<String,Object>>)first.get("rows")).getFirst().get("label"));
            long through = ((Number)first.get("through_sequence")).longValue();
            fixture.rows.put(4L, row(4, "outside pin"));
            ActivityJournalTestAccess.commit(fixture.journal.get(), 4);
            Map<String,Object> second = service.catchup(epoch, 0, through, 1, 1);
            assertEquals(List.of(2L), second.get("removed_ids"));
            assertEquals(through, ((Number)second.get("through_sequence")).longValue());
            Map<String,Object> third = service.catchup(epoch, 0, through, 2, 1);
            assertFalse((boolean)third.get("has_more"));
            assertEquals(3L, ((Number)third.get("next_after_id")).longValue());
            for(int index = 0; index < 257; index++) ActivityJournalTestAccess.commit(fixture.journal.get(), 8);
            assertTrue((boolean)service.catchup(epoch, 0, through, 0, 500).get("resync_required"));
            ActivityJournalTestAccess.reset(fixture.journal.get());
            await(() -> !service.state().epoch().equals(epoch));
            assertTrue((boolean)service.catchup(epoch, 0, null, 0, 500).get("resync_required"));
        }
    }

    @Test void blockedReadCannotBlockJournalAndRebindDoesNotPublishOldSourceRows() throws Exception
    {
        Fixture fixture = new Fixture();
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        fixture.beforeRead.set(() -> {
            reading.countDown();
            try { release.await(); } catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe())
        {
            await(() -> service.state().initialized());
            String old = service.state().epoch();
            fixture.rows.put(1L, row(1, "retired"));
            ActivityJournalTestAccess.commit(fixture.journal.get(), 1);
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            for(int index = 0; index < 300; index++) ActivityJournalTestAccess.commit(fixture.journal.get(), 1);
            ActivityCommitJournal replacement = new ActivityCommitJournal();
            fixture.journal.set(replacement);
            fixture.beforeRead.set(() -> { });
            release.countDown();
            await(() -> service.state().epoch().equals(replacement.boundary().sourceEpoch()));
            assertNull(viewer.poll(20, TimeUnit.MILLISECONDS), "retired source frame must not escape");
            assertTrue((boolean)service.catchup(old, 0, null, 0, 500).get("resync_required"));
        }
        finally { release.countDown(); }
    }

    @Test void multipartIsAtomicAndJournalOverrunDuringPendingPartsRequiresANewBoundary() throws Exception
    {
        Fixture fixture = new Fixture();
        CountDownLatch readingSecondTick = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        fixture.beforeRead.set(() -> {
            if(calls.incrementAndGet() == 21)
            {
                readingSecondTick.countDown();
                try { release.await(); } catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        });
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe())
        {
            await(() -> service.state().initialized());
            String epoch = service.state().epoch();
            long[] ids = java.util.stream.LongStream.rangeClosed(1, 2_101).toArray();
            for(long id: ids) fixture.rows.put(id, row(id, "part"));
            ActivityJournalTestAccess.commit(fixture.journal.get(), ids);
            int count = 0;
            for(int part = 0; part < 20; part++) count += assertPart(viewer.poll(2, TimeUnit.SECONDS), part);
            assertTrue(readingSecondTick.await(2, TimeUnit.SECONDS));
            for(int index = 0; index < 257; index++) ActivityJournalTestAccess.commit(fixture.journal.get(), 1);
            release.countDown();
            count += assertPart(viewer.poll(2, TimeUnit.SECONDS), 20);
            count += assertPart(viewer.poll(2, TimeUnit.SECONDS), 21);
            assertEquals(2_101, count);
            StatsLiveEventHub.LiveEvent gap = viewer.poll(2, TimeUnit.SECONDS);
            assertEquals("resync_required", gap.name());
            assertEquals("journal_gap", ((Map<?,?>)gap.data()).get("reason"));
            assertEquals(258L, ((Number)((Map<?,?>)gap.data()).get("sequence")).longValue());
            assertEquals(epoch, service.state().epoch());
            assertEquals(258, service.state().sequence());
            assertFalse((boolean)service.catchup(epoch, 258, null, 0, 500).get("resync_required"));
        }
        finally { release.countDown(); }
    }

    @Test void twoHundredConcurrentCatchupsShareAlreadyQueuedWork() throws Exception
    {
        Fixture fixture = new Fixture();
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe();
            var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
        {
            await(() -> service.state().initialized());
            String epoch = service.state().epoch();
            fixture.rows.put(1L, row(1, "shared catchup"));
            ActivityJournalTestAccess.commit(fixture.journal.get(), 1);
            assertNotNull(viewer.poll(2, TimeUnit.SECONDS));
            int before = fixture.reads.get();
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<Map<String,Object>>> results = new ArrayList<>();
            for(int index = 0; index < 200; index++) results.add(requests.submit(() -> {
                start.await();
                return service.catchup(epoch, 0, 1L, 0, 500);
            }));
            start.countDown();
            for(var result: results)
            {
                Map<String,Object> page = result.get(4, TimeUnit.SECONDS);
                assertFalse((boolean)page.get("resync_required"));
                assertEquals(1, ((List<?>)page.get("rows")).size());
            }
            assertTrue(fixture.reads.get() - before < 16, "queued identical requests share projection work");
        }
    }

    private static int assertPart(StatsLiveEventHub.LiveEvent event, int part)
    {
        assertNotNull(event);
        assertEquals("activity_delta", event.name());
        Map<?,?> data = (Map<?,?>)event.data();
        assertEquals(0L, ((Number)data.get("base_sequence")).longValue());
        assertEquals(1L, ((Number)data.get("sequence")).longValue());
        assertEquals(part, data.get("part"));
        assertEquals(22, data.get("parts"));
        return ((List<?>)data.get("rows")).size();
    }

    @Test void quietBoundaryUsesCurrentSerializationTime()
    {
        var state = new P25ActivityLiveService.State(1, true, "source", 3, 1_000, true);
        long before = System.currentTimeMillis();
        long serialized = ((Number)state.payload("subscriber").get("server_time_ms")).longValue();
        assertTrue(serialized >= before);
        assertTrue(serialized <= System.currentTimeMillis());
    }

    @Test void slowSubscriberLosesFramesWithoutBlockingOtherViewersAndCanCatchUp() throws Exception
    {
        Fixture fixture = new Fixture();
        try(P25ActivityLiveService service = new P25ActivityLiveService(fixture.journal::get, fixture,
            () -> 1_000, () -> true, 4, 1); var slow = service.subscribe(); var fast = service.subscribe())
        {
            await(() -> service.state().initialized());
            String epoch = service.state().epoch();
            for(int index = 1; index <= 3; index++)
            {
                fixture.rows.put((long)index, row(index, "row"));
                ActivityJournalTestAccess.commit(fixture.journal.get(), index);
                assertNotNull(fast.poll(2, TimeUnit.SECONDS));
            }
            assertTrue(slow.droppedCount() > 0);
            Map<String,Object> page = service.catchup(epoch, 0, null, 0, 500);
            assertFalse((boolean)page.get("resync_required"));
            assertEquals(3, ((List<?>)page.get("rows")).size());
        }
    }

    @Test void metadataChangesWithoutRowsAndAbsentCollectionCanStillShowRetainedSeed() throws Exception
    {
        Fixture fixture = new Fixture();
        AtomicBoolean enabled = new AtomicBoolean(true);
        try(P25ActivityLiveService service = new P25ActivityLiveService(fixture.journal::get, fixture,
            () -> 1_000, enabled::get); var viewer = service.subscribe())
        {
            await(() -> service.state().initialized());
            long revision = service.state().revision();
            enabled.set(false);
            await(() -> service.state().revision() > revision);
            assertFalse(service.state().enabled());
            fixture.journal.set(null);
            await(() -> service.state().epoch().equals("unavailable"));
            assertFalse((boolean)service.catchup("unavailable", 0, null, 0, 500).get("resync_required"));
        }
    }

    private static Map<String,Object> row(long id, String label) { return Map.of("id", id, "label", label); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(StatsLiveEventHub.LiveEvent event)
    { return (List<Map<String,Object>>)((Map<?,?>)event.data()).get("rows"); }
    private static void await(BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    private static final class Fixture implements P25ActivityLiveService.Source
    {
        private final AtomicReference<ActivityCommitJournal> journal = new AtomicReference<>(new ActivityCommitJournal());
        private final ConcurrentHashMap<Long,Map<String,Object>> rows = new ConcurrentHashMap<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicReference<Runnable> beforeRead = new AtomicReference<>(() -> { });
        @Override public List<Map<String,Object>> rows(List<Long> ids)
        {
            reads.incrementAndGet(); beforeRead.get().run();
            return ids.stream().map(rows::get).filter(java.util.Objects::nonNull).toList();
        }
        P25ActivityLiveService service() { return new P25ActivityLiveService(journal::get, this, () -> 1_000, () -> true); }
    }
}
