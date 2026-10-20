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

    @Test void twoHundredSeedsSharePreparedBytesAndCachedSequenceCoversLaterEnrichment() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.rows.put(1L, row(1, "seed"));
        for(long id=1;id<=200;id++) fixture.affiliations.put(id,affiliation(id,"group-a"));
        ActivityJournalTestAccess.commit(fixture.journal.get(), 1);
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe();
            var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
        {
            await(() -> service.state().initialized());
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<io.github.dsheirer.web.http.ApiHttpResponse.PreparedJson>> requests = new ArrayList<>();
            for(int index = 0; index < 200; index++) requests.add(executor.submit(() -> { start.await(); return service.seed(); }));
            start.countDown();
            var prepared = requests.getFirst().get(4, TimeUnit.SECONDS);
            for(var request: requests) assertSame(prepared, request.get(4, TimeUnit.SECONDS));
            assertEquals(1, fixture.recentReads.get(), "200 viewers share one recent database read and encoding");
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(prepared.identity()).path("data");
            assertEquals(1, json.path("sequence").asLong());
            assertEquals(1, json.path("rows").size());
            assertEquals(200,json.path("affiliations").size());
            assertTrue(json.path("affiliations_available").asBoolean());
            assertEquals(1,fixture.affiliationPresentations.get());
            assertTrue(json.has("captured_at_ms"));
            assertFalse(json.has("server_time_ms"));
            assertNotNull(prepared.gzip());
            fixture.rows.put(1L, row(1, "enriched after cached seed"));
            ActivityJournalTestAccess.commit(fixture.journal.get(), 1);
            await(() -> service.state().sequence() == 2);
            assertSame(prepared, service.seed(), "cached S can precede a new subscriber boundary N");
            Map<String,Object> catchup = service.catchup(service.state().epoch(), json.path("sequence").asLong(), null, 0, 500);
            assertEquals("enriched after cached seed", ((List<Map<String,Object>>)catchup.get("rows")).getFirst().get("label"));
            Thread.sleep(2_050);
            var fresh = service.seed();
            assertNotSame(prepared, fresh);
            assertEquals(2, fixture.recentReads.get());
            ActivityJournalTestAccess.reset(fixture.journal.get());
            var reset = service.seed();
            assertNotSame(fresh, reset);
            assertEquals(3, fixture.recentReads.get());
        }
    }

    @Test void seedCapturesSequenceBeforeReadAndRejectsResetDuringRead() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.rows.put(1L, row(1, "saved"));
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe())
        {
            await(() -> service.state().initialized());
            fixture.beforeRecent.set(() -> ActivityJournalTestAccess.commit(fixture.journal.get(), 1));
            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(service.seed().identity()).path("data");
            assertEquals(0, data.path("sequence").asLong());
            assertEquals(1, ((Number)service.catchup(data.path("source_epoch").asText(), 0, null, 0, 500).get("through_sequence")).longValue());
            ActivityJournalTestAccess.reset(fixture.journal.get());
            fixture.beforeRecent.set(() -> ActivityJournalTestAccess.reset(fixture.journal.get()));
            assertEquals(503, assertThrows(StatsApiException.class, service::seed).status());
            fixture.beforeRecent.set(() -> { });
            assertNotNull(service.seed());
            viewer.close();
            assertEquals(503, assertThrows(StatsApiException.class, service::seed).status());
        }
    }

    @Test void currentAffiliationStateWorksWithoutDetailedHistoryAndRetainsLastCompleteMapOnFailure() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.journal.set(null);
        fixture.affiliations.put(1L,affiliation(1,"old group"));
        try(P25ActivityLiveService service = new P25ActivityLiveService(fixture.journal::get,fixture,() -> 1000,() -> false);
            var viewer = service.subscribe())
        {
            var seed = json(service.seed());
            assertTrue(seed.path("affiliation_revision").asLong() > 0);
            assertTrue(seed.path("affiliations_available").asBoolean());
            assertTrue(seed.path("affiliations_complete").asBoolean());
            assertFalse(seed.path("collection_enabled").asBoolean());
            assertEquals(1,seed.path("affiliations").size());
            await(() -> fixture.affiliationReads.get() >= 2);
            assertEquals(1,fixture.affiliationPresentations.get(),"quiet raw scan must skip Alias/name work");
            assertNull(viewer.poll(20,TimeUnit.MILLISECONDS));
            fixture.affiliations.put(1L,affiliation(1,"new group"));
            var changed = affiliationData(viewer.poll(2,TimeUnit.SECONDS));
            assertTrue(((Number)changed.get("affiliation_revision")).longValue() > seed.path("affiliation_revision").asLong());
            assertEquals(1,((List<?>)changed.get("upserts")).size());
            assertEquals(0,service.state().sequence(),"state updates have no Activity sequence");
            fixture.failAffiliations.set(true);
            var unavailable = affiliationData(viewer.poll(2,TimeUnit.SECONDS));
            assertEquals(false,unavailable.get("affiliations_available"));
            assertEquals(List.of(),unavailable.get("removed_radio_keys"));
            var recovery = service.catchup(service.state().epoch(),0,null,0,500);
            assertEquals(false,recovery.get("affiliations_available"));
            assertEquals(true,recovery.get("affiliations_complete"));
            assertEquals(1,json(service.seed()).path("affiliations").size(),"retain last complete state, never report false empty");
            fixture.failAffiliations.set(false);
            fixture.affiliations.clear();
            var cleared = affiliationData(viewer.poll(2,TimeUnit.SECONDS));
            assertEquals(true,cleared.get("affiliations_available"));
            assertEquals(List.of(Map.of("radio_system_key","p25:bee00:49f","radio_identity_key","v1-r-bee00-49f-1")),
                cleared.get("removed_radio_keys"),"logout/retention deletion removes only the authoritative state key");
            viewer.close();
            int reads = fixture.affiliationReads.get();
            Thread.sleep(1100);
            assertEquals(reads,fixture.affiliationReads.get(),"last viewer releases current-state SQL demand");
        }
    }

    @Test void currentStateBudgetsAndPresentationInvalidationRemainSharedAndExplicit() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.affiliations.put(1L,affiliation(1,"radio"));
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe())
        {
            var first = json(service.seed());
            assertEquals(1,first.path("affiliations").size());
            fixture.presentationGeneration.incrementAndGet();
            fixture.alias.set("renamed");
            var name = affiliationData(viewer.poll(2,TimeUnit.SECONDS));
            assertEquals("renamed",((List<Map<String,Object>>)name.get("upserts")).getFirst().get("radio_alias_name"));
            fixture.oversizedAffiliations.set(true);
            var unavailable = affiliationData(viewer.poll(2,TimeUnit.SECONDS));
            assertEquals(false,unavailable.get("affiliations_available"));
            assertEquals(1,json(service.seed()).path("affiliations").size());
            fixture.oversizedAffiliations.set(false);
            fixture.alias.set("external rename");
            var refreshed = json(service.seed(true));
            assertEquals("external rename",refreshed.path("affiliations").get(0).path("radio_alias_name").asText());
            assertTrue(refreshed.path("affiliations_available").asBoolean());
            int present = fixture.affiliationPresentations.get();
            try(var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            {
                List<java.util.concurrent.Future<?>> requests = new ArrayList<>();
                for(int index=0;index<20;index++) requests.add(executor.submit(() -> service.seed(true)));
                for(var request: requests) request.get(2,TimeUnit.SECONDS);
            }
            assertEquals(present,fixture.affiliationPresentations.get(),"explicit refresh is coalesced/rate bounded");
            ActivityJournalTestAccess.reset(fixture.journal.get());
            fixture.affiliations.clear();
            var reset = json(service.seed());
            assertNotEquals(first.path("source_epoch"),reset.path("source_epoch"));
            assertEquals(0,reset.path("affiliations").size(),"new epoch must clear prior current map");
        }
    }

    @Test void resubscribeWithinSameEpochPreservesMonotonicCurrentRevision() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.affiliations.put(1L,affiliation(1,"first"));
        try(P25ActivityLiveService service = fixture.service())
        {
            var firstViewer = service.subscribe();
            var seed = json(service.seed());
            fixture.affiliations.put(1L,affiliation(1,"second"));
            var update = affiliationData(firstViewer.poll(2,TimeUnit.SECONDS));
            long revision = ((Number)update.get("affiliation_revision")).longValue();
            assertTrue(revision > 1);
            firstViewer.close();
            Thread.sleep(350);
            int reads = fixture.affiliationReads.get();
            fixture.affiliations.put(1L,affiliation(1,"while idle"));
            Thread.sleep(1100);
            assertEquals(reads,fixture.affiliationReads.get());
            try(var next = service.subscribe())
            {
                var resumed = json(service.seed());
                assertEquals(seed.path("source_epoch"),resumed.path("source_epoch"));
                assertTrue(resumed.path("affiliation_revision").asLong() > revision);
                assertEquals("while idle",resumed.path("affiliations").get(0).path("talkgroup_identity_key").asText());
            }
        }
    }

    @Test void twoHundredDistinctEventRecoveryKeysNeverEncodeTheCompleteCurrentMap() throws Exception
    {
        Fixture fixture = new Fixture();
        for(long id=1;id<=1565;id++) fixture.affiliations.put(id,affiliation(id,"current"));
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe();
            var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
        {
            var seed = service.seed();
            var data = json(seed);
            long revision = data.path("affiliation_revision").asLong();
            List<java.util.concurrent.Future<io.github.dsheirer.web.http.ApiHttpResponse.PreparedJson>> requests = new ArrayList<>();
            for(int index=0;index<200;index++)
            {
                long differentRevision = revision+index+1;
                requests.add(executor.submit(() -> service.preparedCatchup(service.state().epoch(),0,null,0,500,differentRevision)));
            }
            for(var request: requests)
            {
                var response = request.get(4,TimeUnit.SECONDS);
                assertTrue(response.identity().length < 2000,"per-key recovery must never include the complete map");
                var body = json(response);
                assertFalse(body.has("affiliations"));
                assertTrue(body.path("affiliations_refresh_required").asBoolean());
            }
            assertSame(seed,service.seed(),"all state-only refreshes reuse the same prepared seed bytes");
            assertEquals(1,fixture.recentReads.get());
            assertEquals(1,fixture.affiliationPresentations.get());
        }
    }

    @Test void serviceRestartWithinWriterEpochKeepsCurrentRevisionsMonotonic() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.affiliations.put(1L,affiliation(1,"current"));
        long revision;
        String epoch;
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe())
        { var seed=json(service.seed()); revision=seed.path("affiliation_revision").asLong(); epoch=seed.path("source_epoch").asText(); }
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe())
        { var seed=json(service.seed()); assertEquals(epoch,seed.path("source_epoch").asText()); assertTrue(seed.path("affiliation_revision").asLong()>revision); }
    }

    @Test void currentStateMultipartAndByteFailureKeepCompleteRevisions() throws Exception
    {
        Fixture fixture = new Fixture();
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe())
        {
            long previous = json(service.seed()).path("affiliation_revision").asLong();
            for(long id=1;id<=201;id++) fixture.affiliations.put(id,affiliation(id,"current"));
            int rows = 0;
            long revision = -1;
            for(int part=0;part<3;part++)
            {
                var data=affiliationData(viewer.poll(2,TimeUnit.SECONDS));
                assertEquals(previous,((Number)data.get("base_affiliation_revision")).longValue());
                if(revision<0) revision=((Number)data.get("affiliation_revision")).longValue();
                assertEquals(revision,((Number)data.get("affiliation_revision")).longValue());
                assertEquals(part,data.get("part")); assertEquals(3,data.get("parts"));
                rows+=((List<?>)data.get("upserts")).size();
            }
            assertEquals(201,rows);
        }
        Fixture byteFixture = new Fixture();
        byteFixture.affiliations.put(1L,affiliation(1,"current"));
        try(P25ActivityLiveService service = byteFixture.service(); var viewer = service.subscribe())
        {
            var complete=json(service.seed());
            byteFixture.alias.set("x".repeat(8*1024*1024));
            byteFixture.presentationGeneration.incrementAndGet();
            var unavailable=affiliationData(viewer.poll(2,TimeUnit.SECONDS));
            assertEquals(false,unavailable.get("affiliations_available"));
            var retained=json(service.seed());
            assertEquals(complete.path("affiliations"),retained.path("affiliations"));
            assertFalse(retained.path("affiliations_available").asBoolean());
        }
    }

    @Test void unknownEmptyAfterRestartDiffersFromRetainedCompleteEmpty() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.affiliations.put(1L,affiliation(1,"old client group"));
        long priorRevision;
        String epoch;
        try(P25ActivityLiveService previous = fixture.service(); var viewer = previous.subscribe())
        {
            var known = json(previous.seed());
            assertTrue(known.path("affiliations_complete").asBoolean());
            priorRevision = known.path("affiliation_revision").asLong();
            epoch = known.path("source_epoch").asText();
        }
        fixture.failAffiliations.set(true);
        try(P25ActivityLiveService restarted = fixture.service(); var viewer = restarted.subscribe())
        {
            var unknown = json(restarted.seed());
            assertEquals(epoch,unknown.path("source_epoch").asText());
            assertTrue(unknown.path("affiliation_revision").asLong() > priorRevision);
            assertFalse(unknown.path("affiliations_available").asBoolean());
            assertFalse(unknown.path("affiliations_complete").asBoolean());
            assertTrue(unknown.path("affiliations").isEmpty());
            var metadata = json(restarted.preparedCatchup(epoch,0,null,0,500,priorRevision));
            assertFalse(metadata.path("affiliations_complete").asBoolean());
            assertFalse(metadata.has("affiliations"));
            fixture.affiliations.clear();
            fixture.failAffiliations.set(false);
            affiliationData(viewer.poll(2,TimeUnit.SECONDS));
            var knownEmpty = json(restarted.seed());
            assertTrue(knownEmpty.path("affiliations_complete").asBoolean());
            assertTrue(knownEmpty.path("affiliations_available").asBoolean());
            assertTrue(knownEmpty.path("affiliations").isEmpty());
            fixture.failAffiliations.set(true);
            affiliationData(viewer.poll(2,TimeUnit.SECONDS));
            var retainedEmpty = json(restarted.seed());
            assertTrue(retainedEmpty.path("affiliations_complete").asBoolean());
            assertFalse(retainedEmpty.path("affiliations_available").asBoolean());
            assertTrue(retainedEmpty.path("affiliations").isEmpty());
        }
    }

    @Test void writerFreeSourceEpochChangesAcrossServiceRestartAndRejectsPriorFallback() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.journal.set(null);
        fixture.affiliations.put(1L,affiliation(1,"retained"));
        String priorEpoch;
        try(P25ActivityLiveService previous = fixture.service())
        {
            var viewer = previous.subscribe();
            priorEpoch = json(previous.seed()).path("source_epoch").asText();
            assertNotNull(java.util.UUID.fromString(priorEpoch));
            viewer.close();
            Thread.sleep(350);
            try(var resumed = previous.subscribe())
            {
                assertEquals(priorEpoch,json(previous.seed()).path("source_epoch").asText(),
                    "idle resubscribe must keep the same writer-free source epoch");
            }
        }
        try(P25ActivityLiveService restarted = fixture.service(); var viewer = restarted.subscribe())
        {
            var snapshot = json(restarted.seed());
            String currentEpoch = snapshot.path("source_epoch").asText();
            assertNotNull(java.util.UUID.fromString(currentEpoch));
            assertNotEquals(priorEpoch,currentEpoch,"writer-free restart must invalidate retained browser revisions");
            assertTrue((boolean)restarted.catchup(priorEpoch,0,null,0,500).get("resync_required"));
            assertFalse((boolean)restarted.catchup(currentEpoch,0,null,0,500).get("resync_required"));
            assertEquals(1,snapshot.path("affiliations").size());
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode json(io.github.dsheirer.web.http.ApiHttpResponse.PreparedJson prepared) throws Exception
    { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(prepared.identity()).path("data"); }
    private static Map<?,?> affiliationData(StatsLiveEventHub.LiveEvent event)
    { assertNotNull(event); assertEquals("affiliation_delta",event.name()); return (Map<?,?>)event.data(); }
    private static Map<String,Object> affiliation(long id,String group)
    { return Map.of("radio_system_key","p25:bee00:49f","source_identity_key","v1-r-bee00-49f-"+id,
        "target_identity_key",group,"configuration_id","site-a","confirmed_at_ms",100L); }

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
            String writerEpoch = service.state().epoch();
            fixture.journal.set(null);
            await(() -> !service.state().epoch().equals(writerEpoch));
            assertFalse((boolean)service.catchup(service.state().epoch(), 0, null, 0, 500).get("resync_required"));
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
        private final AtomicInteger recentReads = new AtomicInteger();
        private final ConcurrentHashMap<Long,Map<String,Object>> affiliations = new ConcurrentHashMap<>();
        private final AtomicInteger affiliationReads = new AtomicInteger(),affiliationPresentations = new AtomicInteger();
        private final AtomicBoolean failAffiliations = new AtomicBoolean(),oversizedAffiliations = new AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicLong presentationGeneration = new java.util.concurrent.atomic.AtomicLong();
        private final AtomicReference<String> alias = new AtomicReference<>("original");
        private final AtomicReference<Runnable> beforeRecent = new AtomicReference<>(() -> { });
        private final AtomicReference<Runnable> beforeRead = new AtomicReference<>(() -> { });
        @Override public List<Map<String,Object>> rows(List<Long> ids)
        {
            reads.incrementAndGet(); beforeRead.get().run();
            return ids.stream().map(rows::get).filter(java.util.Objects::nonNull).toList();
        }
        @Override public List<Map<String,Object>> recentRows()
        {
            recentReads.incrementAndGet(); beforeRecent.get().run();
            return rows.entrySet().stream().sorted(Map.Entry.<Long,Map<String,Object>>comparingByKey().reversed())
                .limit(P25ActivityLiveService.SEED_LIMIT).map(Map.Entry::getValue).toList();
        }
        @Override public long presentationGeneration() { return presentationGeneration.get(); }
        @Override public List<Map<String,Object>> affiliationRows()
        {
            affiliationReads.incrementAndGet();
            if(failAffiliations.get()) throw new StatsApiException(503,"fixture unavailable");
            if(oversizedAffiliations.get()) return java.util.Collections.nCopies(10001,affiliation(1,"oversized"));
            return List.copyOf(affiliations.values());
        }
        @Override public List<Map<String,Object>> presentAffiliations(List<Map<String,Object>> rows)
        {
            if(!rows.isEmpty()) affiliationPresentations.incrementAndGet();
            return rows.stream().map(row -> Map.<String,Object>of("radio_system_key",row.get("radio_system_key"),
                "radio_identity_key",row.get("source_identity_key"),"talkgroup_identity_key",row.get("target_identity_key"),
                "radio_alias_name",alias.get(),"confirmed_at_ms",row.get("confirmed_at_ms"))).toList();
        }
        P25ActivityLiveService service() { return new P25ActivityLiveService(journal::get, this, () -> 1_000, () -> true); }
    }
}
