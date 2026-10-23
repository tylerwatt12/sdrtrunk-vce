package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class P25ActivityLiveServiceTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void twoHundredViewersShareNativeSeedAndPreparedFramesAndIdleDoesNoWork() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.nativeRows.set(List.of(affiliation(1, 11)));
        try(var service = fixture.service(); var executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            Thread.sleep(280); assertEquals(0, fixture.reads.get());
            fixture.entered = new CountDownLatch(1); fixture.release = new CountDownLatch(1);
            List<StatsLiveEventHub.Subscription> viewers = new ArrayList<>();
            for(int index = 0; index < 200; index++) viewers.add(service.subscribe());
            assertTrue(fixture.entered.await(2, TimeUnit.SECONDS));
            var seeds = new ArrayList<java.util.concurrent.Future<io.github.dsheirer.web.http.ApiHttpResponse.PreparedJson>>();
            for(int index = 0; index < 200; index++) seeds.add(executor.submit(() -> service.seed()));
            Thread.sleep(100); fixture.release.countDown();
            var prepared = seeds.getFirst().get(3, TimeUnit.SECONDS);
            for(var seed: seeds) assertSame(prepared, seed.get(3, TimeUnit.SECONDS));
            JsonNode document = JSON.readTree(prepared.identity()).path("data");
            assertFalse(document.has("rows")); assertFalse(document.has("affiliations_truncated"));
            assertEquals(1, fixture.nativePresentations.get()); assertNotNull(prepared.gzip());
            fixture.nativeRows.set(List.of(affiliation(1, 12)));
            var event = viewers.getFirst().poll(2, TimeUnit.SECONDS); assertNotNull(event);
            assertEquals("p25_state_delta", event.name()); assertFalse(data(event).containsKey("rows"));
            assertFalse(data(event).containsKey("removed_ids"));
            for(int index = 1; index < viewers.size(); index++) assertSame(event, viewers.get(index).poll(1, TimeUnit.SECONDS));
            assertSame(event.frame(7).bytes(true), event.frame(7).bytes(true));
            var quiet = service.seed(); int projections = fixture.nativePresentations.get();
            Thread.sleep(1100); assertSame(quiet, service.seed()); assertEquals(projections, fixture.nativePresentations.get());
            viewers.forEach(StatsLiveEventHub.Subscription::close); Thread.sleep(300); int reads = fixture.reads.get();
            Thread.sleep(1100); assertEquals(reads, fixture.reads.get());
        }
    }

    @Test void completeNativeSeedExceedsFormerRowAndByteCaps() throws Exception
    {
        Fixture fixture = new Fixture(); var rows = new ArrayList<Map<String,Object>>();
        for(int id = 1; id <= 11_001; id++)
        {
            var row = new LinkedHashMap<>(affiliation(id, id)); row.put("system_name", "named-system-".repeat(100)); rows.add(row);
        }
        fixture.nativeRows.set(rows);
        try(var service = fixture.service(); var viewer = service.subscribe())
        {
            var prepared = service.seed(); assertTrue(prepared.identity().length > 12 * 1024 * 1024);
            var seed = JSON.readTree(prepared.identity()).path("data");
            assertEquals(11_001, seed.path("affiliations").size()); assertTrue(seed.path("available").asBoolean());
            for(int id = 1; id <= 11_001; id++)
                assertEquals(id, seed.path("affiliations").get(id - 1).path("radio_id").asInt());
            assertFalse(seed.has("affiliations_truncated")); assertSame(prepared, service.seed());
        }
    }

    @Test void everyCommittedPageIsAppendedWithoutChangingNativeSequenceOrRetainingAHistorySeed() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.history.add(row(1)); fixture.nativeRows.set(List.of(affiliation(1, 11)));
        try(var service = fixture.service(); var viewer = service.subscribe())
        {
            var before = seed(service); await(service::ready);
            for(long id = 2; id <= 704; id++) fixture.history.add(row(id));
            var received = new ArrayList<Long>(); long cursor = 1;
            while(received.size() < 703)
            {
                var event = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(event); assertEquals("activity_append", event.name());
                assertEquals(cursor, data(event).get("after_id"));
                cursor = ((Number)data(event).get("next_after_id")).longValue();
                for(JsonNode row: JSON.valueToTree(data(event).get("rows"))) received.add(row.path("id").asLong());
                assertEquals(before.path("source_epoch").asText(), data(event).get("source_epoch"));
            }
            assertEquals(java.util.stream.LongStream.rangeClosed(2, 704).boxed().toList(), received);
            assertEquals(704, cursor); await(() -> service.state().watermark() == 704);
            assertTrue(fixture.pages.get() > 10); assertEquals(before.path("sequence").asLong(), service.state().sequence());
            assertFalse(seed(service).has("rows")); assertEquals(1, seed(service).path("affiliations").size());
        }
    }

    @Test void filteredEmptyPagesPublishContiguousProgressWithoutChangingNativeSequence() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.history.add(row(1));
        try(var service = fixture.service(); var viewer = service.subscribe())
        {
            seed(service); long sequence = service.state().sequence();
            assertEquals(1L, service.state().payload("viewer").get("watermark_id"));
            assertEquals(0L, service.state().payload("viewer").get("history_generation"));
            fixture.emptyPages.set(true); fixture.history.add(row(2));
            var empty = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(empty);
            assertEquals("activity_append", empty.name()); assertEquals(List.of(), data(empty).get("rows"));
            assertEquals(0L, data(empty).get("history_generation"));
            assertEquals(1L, data(empty).get("after_id")); assertEquals(2L, data(empty).get("next_after_id"));
            fixture.emptyPages.set(false); fixture.history.add(row(3));
            var next = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(next);
            assertEquals(2L, data(next).get("after_id")); assertEquals(3L, data(next).get("next_after_id"));
            assertEquals(sequence, service.state().sequence());
        }
    }

    @Test void idleResumeAdvertisesTheFreshHistoryEdgeWhenNativeAffiliationsAreUnchanged() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.history.add(row(1));
        try(var service = fixture.service())
        {
            long sequence;
            try(var first = service.subscribe()) { seed(service); sequence = service.state().sequence(); }
            fixture.history.addAll(List.of(row(2), row(3), row(4), row(5)));
            try(var viewer = service.subscribe())
            {
                seed(service);
                assertEquals(5L, service.state().payload("resumed").get("watermark_id"));
                assertEquals(sequence, service.state().sequence());
                fixture.history.add(row(6));
                var append = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(append);
                assertEquals(5L, data(append).get("after_id")); assertEquals(6L, data(append).get("next_after_id"));
            }
        }
    }

    @Test void committedInteriorDeletionInvalidatesOnlyHistoryEvenWhenMaximumIdDoesNotChange() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.history.addAll(List.of(row(1), row(2), row(3)));
        fixture.nativeRows.set(List.of(affiliation(1, 11)));
        try(var service = fixture.service(); var viewer = service.subscribe())
        {
            var before = service.seed(); await(service::ready); long nativeSequence = service.state().sequence();
            fixture.history.remove(1); fixture.historyGeneration.incrementAndGet();
            var reset = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(reset); assertEquals("activity_reset", reset.name());
            assertEquals(3L, data(reset).get("watermark_id")); assertSame(before, service.seed());
            assertEquals(1L, data(reset).get("history_generation"));
            assertEquals(1L, service.state().payload("reconnected-after-lost-reset").get("history_generation"));
            assertEquals(nativeSequence, service.state().sequence());
            fixture.history.add(row(4));
            var append = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(append); assertEquals("activity_append", append.name());
            assertEquals(1L, data(append).get("history_generation"));
            assertEquals(4, JSON.valueToTree(data(append).get("rows")).get(0).path("id").asInt());
        }
    }

    @Test void reconnectExposesAnIdleInteriorDeletionEvenWhenWatermarkAndNativeStateAreUnchanged() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.history.addAll(List.of(row(1), row(2), row(3)));
        try(var service = fixture.service())
        {
            long sequence;
            try(var first = service.subscribe())
            {
                seed(service); sequence = service.state().sequence(); assertEquals(0, service.state().historyGeneration());
            }
            fixture.history.remove(1); fixture.historyGeneration.incrementAndGet();
            try(var reconnected = service.subscribe())
            {
                seed(service);
                var boundary = service.state().payload("reconnected");
                assertEquals(3L, boundary.get("watermark_id")); assertEquals(1L, boundary.get("history_generation"));
                assertEquals(sequence, service.state().sequence());
                fixture.history.add(row(4));
                var append = reconnected.poll(2, TimeUnit.SECONDS); assertNotNull(append);
                assertEquals(1L, data(append).get("history_generation")); assertEquals(3L, data(append).get("after_id"));
            }
        }
    }

    @Test void currentAffiliationsChangeAndDisappearWhileDetailedHistoryIsOff() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.enabled.set(false); fixture.nativeRows.set(List.of(affiliation(1, 11)));
        try(var service = fixture.service(); var viewer = service.subscribe())
        {
            assertFalse(seed(service).path("collection_enabled").asBoolean());
            fixture.nativeRows.set(List.of(affiliation(1, 12)));
            var changed = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(changed);
            assertEquals(12, JSON.valueToTree(data(changed).get("affiliations")).get(0).path("talkgroup_id").asInt());
            fixture.nativeRows.set(List.of());
            var removed = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(removed);
            assertEquals(1, ((List<?>)data(removed).get("removed_radio_keys")).size());
            assertTrue(seed(service).path("affiliations").isEmpty());
        }
    }

    @Test void sourceBoundaryWaitsForHistoryEdgeAndLaterCommitIsNeverSkipped() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.history.add(row(1));
        fixture.watermarkEntered = new CountDownLatch(1); fixture.watermarkRelease = new CountDownLatch(1);
        try(var service = fixture.service(); var viewer = service.subscribe())
        {
            assertTrue(fixture.watermarkEntered.await(2, TimeUnit.SECONDS));
            assertFalse(service.ready()); assertEquals(0, service.state().sequence());
            fixture.watermarkRelease.countDown(); seed(service); await(service::ready);
            fixture.history.add(row(2));
            var append = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(append); assertEquals("activity_append", append.name());
            assertEquals(2, JSON.valueToTree(data(append).get("rows")).get(0).path("id").asInt());
        }
    }

    @Test void failedNativeReadRetainsCompleteStateAndRecoversWithoutAHistoryReset() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.nativeRows.set(List.of(affiliation(1, 11)));
        try(var service = fixture.service(); var viewer = service.subscribe())
        {
            var initial = seed(service); fixture.fail.set(true); await(() -> !service.state().available());
            var stale = seed(service); assertFalse(stale.path("available").asBoolean());
            assertEquals(initial.path("affiliations"), stale.path("affiliations"));
            fixture.fail.set(false); fixture.nativeRows.set(List.of(affiliation(2, 22))); await(() -> service.state().available());
            assertEquals(2, seed(service).at("/affiliations/0/radio_id").asInt());
        }
    }

    @Test void replacementDuringWatermarkReadNeverResetsHistoryUnderTheOldEpoch() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.history.add(row(10));
        try(var service = fixture.service(); var viewer = service.subscribe())
        {
            String epoch = seed(service).path("source_epoch").asText();
            var replaced = new CountDownLatch(1);
            fixture.onWatermark = () -> {
                fixture.entered = new CountDownLatch(1); fixture.release = new CountDownLatch(1);
                fixture.identity.set("replacement"); fixture.history.clear(); fixture.history.add(row(1)); replaced.countDown();
            };
            assertTrue(replaced.await(2, TimeUnit.SECONDS));
            assertTrue(fixture.entered.await(2, TimeUnit.SECONDS));
            assertNull(viewer.poll(100, TimeUnit.MILLISECONDS), "the old epoch must not describe the replacement database");
            fixture.release.countDown();
            await(() -> !epoch.equals(service.state().epoch()));
        }
    }

    @Test void aNewFirstSubscriberCannotLoseItsFreshBoundaryToAnOlderInflightPoll() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.history.add(row(1));
        try(var service = fixture.service())
        {
            var old = service.subscribe(); seed(service);
            fixture.afterEntered = new CountDownLatch(1); fixture.afterRelease = new CountDownLatch(1);
            fixture.history.add(row(2)); assertTrue(fixture.afterEntered.await(2, TimeUnit.SECONDS));
            old.close();
            fixture.watermarkEntered = new CountDownLatch(1); fixture.watermarkRelease = new CountDownLatch(1);
            try(var viewer = service.subscribe())
            {
                fixture.afterRelease.countDown();
                assertTrue(fixture.watermarkEntered.await(2, TimeUnit.SECONDS));
                assertFalse(service.ready(), "a previous subscription's read cannot satisfy a new boundary");
                fixture.watermarkRelease.countDown(); seed(service); await(service::ready);
                while(viewer.poll(10, TimeUnit.MILLISECONDS) != null) { }
                fixture.history.add(row(3));
                var append = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(append);
                assertEquals("activity_append", append.name());
                assertEquals(3, JSON.valueToTree(data(append).get("rows")).get(0).path("id").asInt());
            }
        }
    }

    @Test void databaseReplacementChangesEpochAndSkipsOldHistoryWhileNamesRefreshKeepsEpoch() throws Exception
    {
        Fixture fixture = new Fixture(); fixture.nativeRows.set(List.of(affiliation(1, 11)));
        try(var service = fixture.service(); var viewer = service.subscribe())
        {
            String epoch = seed(service).path("source_epoch").asText();
            fixture.alias.set("renamed"); fixture.presentation.incrementAndGet();
            var update = viewer.poll(2, TimeUnit.SECONDS); assertNotNull(update);
            assertEquals("renamed", JSON.valueToTree(data(update).get("affiliations")).get(0).path("radio_alias_name").asText());
            assertEquals(epoch, service.state().epoch());
            fixture.identity.set("replacement"); fixture.history.add(row(100));
            fixture.nativeRows.set(List.of(affiliation(2, 22)));
            await(() -> !epoch.equals(service.state().epoch()));
            assertEquals(2, seed(service).at("/affiliations/0/radio_id").asInt());
            assertNull(viewer.poll(200, TimeUnit.MILLISECONDS), "replacement history is seeded through the ordinary REST cursor");
        }
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> data(StatsLiveEventHub.LiveEvent event)
    { return (Map<String,Object>)event.data(); }
    private static JsonNode seed(P25ActivityLiveService service) throws Exception
    { return JSON.readTree(service.seed().identity()).path("data"); }
    private static Map<String,Object> row(long id)
    { return Map.of("id", id, "observed_at_ms", id, "action", "CALL", "protocol", "APCO25"); }
    private static Map<String,Object> affiliation(int id, int group)
    { return Map.of("radio_system_key", "p25:bee00:49f", "source_identity_key", "v1-r-bee00-49f-" + id,
        "radio_id", id, "talkgroup_id", group, "confirmed_at_ms", 1000); }
    private static void await(BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "shared reader did not reach expected state");
    }

    private static final class Fixture implements P25ActivityLiveService.Source
    {
        final AtomicReference<Object> identity = new AtomicReference<>("first");
        final AtomicReference<List<Map<String,Object>>> nativeRows = new AtomicReference<>(List.of());
        final CopyOnWriteArrayList<Map<String,Object>> history = new CopyOnWriteArrayList<>();
        final AtomicInteger reads = new AtomicInteger(), nativePresentations = new AtomicInteger(), pages = new AtomicInteger();
        final AtomicLong presentation = new AtomicLong(), historyGeneration = new AtomicLong();
        final AtomicBoolean enabled = new AtomicBoolean(true), fail = new AtomicBoolean(), emptyPages = new AtomicBoolean();
        final AtomicReference<String> alias = new AtomicReference<>("unit");
        volatile CountDownLatch entered, release, watermarkEntered, watermarkRelease, afterEntered, afterRelease;
        volatile Runnable onWatermark;
        P25ActivityLiveService service() { return new P25ActivityLiveService(this, () -> 2000, enabled::get, 256, 128); }
        @Override public P25ActivityLiveService.RawSnapshot snapshot()
        {
            reads.incrementAndGet();
            if(entered != null) { entered.countDown(); waitFor(release); entered = null; }
            if(fail.get()) throw new IllegalStateException("fixture read unavailable");
            return new P25ActivityLiveService.RawSnapshot(identity.get(), nativeRows.get());
        }
        @Override public Object identity() { return identity.get(); }
        @Override public long presentationGeneration() { return presentation.get(); }
        @Override public long historyGeneration() { return historyGeneration.get(); }
        @Override public long watermark()
        {
            if(watermarkEntered != null) { watermarkEntered.countDown(); waitFor(watermarkRelease); watermarkEntered = null; }
            Runnable action = onWatermark;
            if(action != null) { onWatermark = null; action.run(); }
            return history.stream().mapToLong(row -> ((Number)row.get("id")).longValue()).max().orElse(0);
        }
        @Override public Map<String,Object> after(long after, Long through)
        {
            pages.incrementAndGet();
            if(afterEntered != null) { afterEntered.countDown(); waitFor(afterRelease); afterEntered = null; }
            var eligible = history.stream().filter(row -> ((Number)row.get("id")).longValue() > after &&
                ((Number)row.get("id")).longValue() <= through).toList();
            boolean more = eligible.size() > 64; var rows = eligible.subList(0, Math.min(64, eligible.size()));
            return Map.of("rows", emptyPages.get() ? List.of() : rows, "next_after_id", more ? rows.getLast().get("id") : through,
                "watermark_id", through, "has_more", more, "reset_required", false);
        }
        @Override public List<ObjectNode> presentAffiliations(List<Map<String,Object>> rows)
        {
            if(rows.isEmpty()) return List.of(); nativePresentations.incrementAndGet();
            return rows.stream().map(row -> {
                var value = new LinkedHashMap<>(row); value.put("radio_identity_key", value.remove("source_identity_key"));
                value.put("radio_alias_name", alias.get()); return (ObjectNode)JSON.valueToTree(value);
            }).toList();
        }
        private void waitFor(CountDownLatch latch)
        {
            try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
            catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
        }
    }
}
