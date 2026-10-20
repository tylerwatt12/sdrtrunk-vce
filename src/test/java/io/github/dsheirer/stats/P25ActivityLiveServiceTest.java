package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test void twoHundredViewersShareSnapshotsPreparedSeedsAndFramesAndIdleDoesNoWork() throws Exception
    {
        Fixture fixture=new Fixture();fixture.set(List.of(row(1,"first")),List.of(affiliation(1,11)));
        try(P25ActivityLiveService service=fixture.service();var executor=Executors.newVirtualThreadPerTaskExecutor())
        {
            Thread.sleep(280);assertEquals(0,fixture.reads.get());
            fixture.entered=new CountDownLatch(1);fixture.release=new CountDownLatch(1);
            List<StatsLiveEventHub.Subscription> viewers=new ArrayList<>();
            for(int index=0;index<200;index++) viewers.add(service.subscribe());
            assertTrue(fixture.entered.await(2,TimeUnit.SECONDS));
            var seeds=new ArrayList<java.util.concurrent.Future<io.github.dsheirer.web.http.ApiHttpResponse.PreparedJson>>();
            for(int index=0;index<200;index++) seeds.add(executor.submit(() -> { return service.seed(); }));
            Thread.sleep(100);fixture.release.countDown();
            var prepared=seeds.getFirst().get(2,TimeUnit.SECONDS);
            for(var seed:seeds) assertSame(prepared,seed.get(2,TimeUnit.SECONDS));
            assertEquals(1,fixture.reads.get());assertEquals(1,fixture.eventPresentations.get());
            assertEquals(1,fixture.nativePresentations.get());assertNotNull(prepared.gzip());
            fixture.set(List.of(row(1,"enriched")),List.of(affiliation(1,12)));
            var event=viewers.getFirst().poll(2,TimeUnit.SECONDS);assertNotNull(event);
            assertEquals("p25_state_delta",event.name());
            for(int index=1;index<200;index++) assertSame(event,viewers.get(index).poll(1,TimeUnit.SECONDS));
            assertEquals("enriched",list(event,"rows").getFirst().get("label"));
            assertEquals(12,list(event,"affiliations").getFirst().get("talkgroup_id"));
            var latest=seed(service);assertEquals(data(event).get("sequence"),latest.path("sequence").asLong());
            assertSame(event.frame(7),event.frame(7));assertSame(event.frame(7).bytes(true),event.frame(7).bytes(true));
            Thread.sleep(1100);assertEquals(2,fixture.eventPresentations.get(),"quiet raw rows skip aliases/encoding");
            viewers.forEach(StatsLiveEventHub.Subscription::close);Thread.sleep(300);int reads=fixture.reads.get();
            Thread.sleep(1100);assertEquals(reads,fixture.reads.get());
        }
    }

    @Test void oneRevisionAtomicallyReplacesEventScopeAndKeepsAuthoritativeAffiliationsWithCollectionOff() throws Exception
    {
        Fixture fixture=new Fixture();fixture.enabled.set(false);
        List<Map<String,Object>> events=new ArrayList<>();for(long id=500;id>0;id--) events.add(row(id,"old"));
        fixture.set(events,List.of(affiliation(1,11)));
        try(P25ActivityLiveService service=fixture.service();var viewer=service.subscribe())
        {
            var before=seed(service);assertEquals(500,before.path("rows").size());assertFalse(before.path("collection_enabled").asBoolean());
            events=new ArrayList<>(events);events.removeLast();events.addFirst(row(501,"new"));
            fixture.set(events,List.of(affiliation(1,11)));
            var event=viewer.poll(2,TimeUnit.SECONDS);assertNotNull(event);
            assertEquals(List.of(1L),data(event).get("removed_ids"));assertEquals(501L,list(event,"rows").getFirst().get("id"));
            assertTrue(list(event,"affiliations").isEmpty());
            assertEquals(1,seed(service).path("affiliations").size(),"Activity eviction cannot remove current state");
            fixture.set(List.of(),List.of());
            var parts=revision(viewer);int eventRemovals=parts.stream().mapToInt(part->((List<?>)data(part).get("removed_ids")).size()).sum();
            assertEquals(500,eventRemovals);assertEquals(1,parts.stream().mapToInt(part->list(part,"removed_radio_keys").size()).sum());
            assertTrue(seed(service).path("affiliations").isEmpty());
        }
    }

    @Test void namedNativeStateAboveEightMiBRemainsValidUnderTheCombinedBudgetAndSharesPreparedBytes() throws Exception
    {
        Fixture fixture = new Fixture();
        List<Map<String,Object>> nativeRows = new ArrayList<>();
        String label = "known-name-".repeat(90);
        for(int id = 1; id <= 9_000; id++)
        {
            Map<String,Object> row = new LinkedHashMap<>(affiliation(id, id));
            row.put("system_name", label);
            nativeRows.add(Map.copyOf(row));
        }
        fixture.set(List.of(row(1, "saved")), nativeRows);
        try(P25ActivityLiveService service = fixture.service(); var viewer = service.subscribe())
        {
            var prepared = service.seed();
            assertTrue(prepared.identity().length > 8 * 1024 * 1024);
            assertTrue(prepared.identity().length < 12 * 1024 * 1024);
            JsonNode state = JSON.readTree(prepared.identity()).path("data");
            assertTrue(state.path("available").asBoolean());
            assertEquals(9_000, state.path("affiliations").size());
            assertFalse(state.path("affiliations_truncated").asBoolean());
            for(int index = 0; index < 200; index++) assertSame(prepared, service.seed());
            assertEquals(1, fixture.nativePresentations.get(), "visitors reuse the prepared named dataset");
        }
    }

    @Test void failedFirstReadHasNoSeedAndLaterFailureRetainsBothCompleteMapsUntilRecovery() throws Exception
    {
        Fixture fixture=new Fixture();fixture.fail.set(true);
        try(P25ActivityLiveService service=fixture.service();var viewer=service.subscribe())
        {
            assertThrows(StatsApiException.class,service::seed);assertEquals(0,service.state().sequence());
            fixture.fail.set(false);fixture.set(List.of(row(1,"saved")),List.of(affiliation(1,11)));
            await(()->service.state().available());var old=seed(service);
            fixture.fail.set(true);await(()->!service.state().available());
            var stale=seed(service);assertFalse(stale.path("available").asBoolean());
            assertEquals(old.path("rows"),stale.path("rows"));assertEquals(old.path("affiliations"),stale.path("affiliations"));
            assertEquals(old.path("captured_at_ms"),stale.path("captured_at_ms"));
            fixture.fail.set(false);fixture.set(List.of(row(2,"recovered")),List.of(affiliation(2,22)));
            await(()->service.state().available());var recovered=seed(service);
            assertEquals(2,recovered.at("/rows/0/id").asInt());assertEquals(2,recovered.at("/affiliations/0/radio_id").asInt());
        }
    }

    @Test void projectionAndByteFailureCannotCommitHalfOfTheNextDataset() throws Exception
    {
        Fixture fixture=new Fixture();fixture.set(List.of(row(1,"saved")),List.of(affiliation(1,11)));
        try(P25ActivityLiveService service=fixture.service();var viewer=service.subscribe())
        {
            var old=seed(service);fixture.badProjection.set(true);
            fixture.set(List.of(row(2,"new event")),List.of(affiliation(2,22)));
            await(()->!service.state().available());var failed=seed(service);
            assertEquals(old.path("rows"),failed.path("rows"));assertEquals(old.path("affiliations"),failed.path("affiliations"));
            fixture.badProjection.set(false);fixture.set(List.of(row(2,"x".repeat(13*1024*1024))),List.of(affiliation(2,22)));
            Thread.sleep(1200);assertEquals(old.path("rows"),seed(service).path("rows"));
            fixture.set(List.of(row(2,"valid")),List.of(affiliation(2,22)));await(()->service.state().available());
            assertEquals(2,seed(service).at("/rows/0/id").asInt());
        }
    }

    @Test void idleResumeKeepsEpochAndAdvancesStateAndServiceRestartGetsANewEpoch() throws Exception
    {
        Fixture fixture=new Fixture();fixture.set(List.of(row(1,"one")),List.of(affiliation(1,11)));
        String epoch;
        try(P25ActivityLiveService service=fixture.service())
        {
            var viewer=service.subscribe();var initial=seed(service);epoch=initial.path("source_epoch").asText();
            fixture.set(List.of(row(2,"two")),List.of(affiliation(2,22)));await(()->service.state().sequence()>initial.path("sequence").asLong());
            long sequence=service.state().sequence();viewer.close();Thread.sleep(300);int reads=fixture.reads.get();
            fixture.set(List.of(row(3,"three")),List.of());Thread.sleep(1100);assertEquals(reads,fixture.reads.get());
            try(var resumed=service.subscribe())
            {
                var next=seed(service);assertEquals(epoch,next.path("source_epoch").asText());
                assertTrue(next.path("sequence").asLong()>sequence);assertEquals(3,next.at("/rows/0/id").asInt());
                assertTrue(next.path("affiliations").isEmpty());
            }
        }
        try(P25ActivityLiveService service=fixture.service();var viewer=service.subscribe())
        { assertNotEquals(epoch,seed(service).path("source_epoch").asText()); }
    }

    @Test void fileReplacementDuringProjectionIsRejectedThenRotatesEpochOnlyAfterCompleteRead() throws Exception
    {
        Fixture fixture=new Fixture();fixture.set(List.of(row(1,"one")),List.of(affiliation(1,11)));
        try(P25ActivityLiveService service=fixture.service();var viewer=service.subscribe())
        {
            var old=seed(service);fixture.swapDuringPresentation.set(true);fixture.set(List.of(row(2,"two")),List.of(affiliation(2,22)));
            await(()->!service.state().available());assertEquals(old.path("source_epoch").asText(),service.state().epoch());
            assertEquals(old.path("rows"),seed(service).path("rows"));
            await(()->service.state().available());var next=seed(service);
            assertNotEquals(old.path("source_epoch"),next.path("source_epoch"));assertEquals(2,next.at("/rows/0/id").asInt());
        }
    }

    @Test void multipartHasOneExactBaseAndAtMostOneHundredTypedChangesPerPart() throws Exception
    {
        Fixture fixture=new Fixture();fixture.set(List.of(row(1,"one")),List.of(affiliation(1,11)));
        try(P25ActivityLiveService service=fixture.service();var viewer=service.subscribe())
        {
            long base=seed(service).path("sequence").asLong();
            var nativeRows=new ArrayList<Map<String,Object>>();for(int id=2;id<=202;id++) nativeRows.add(affiliation(id,id));
            fixture.set(List.of(row(2,"two")),nativeRows);var parts=revision(viewer);assertEquals(3,parts.size());
            int total=0;for(int index=0;index<parts.size();index++)
            {
                var data=data(parts.get(index));assertEquals(base,data.get("base_sequence"));assertEquals(base+1,data.get("sequence"));
                assertEquals(index,data.get("part"));assertEquals(parts.size(),data.get("parts"));int count=0;
                for(String field:List.of("rows","removed_ids","affiliations","removed_radio_keys")) count+=((List<?>)data.get(field)).size();
                assertTrue(count<=100);total+=count;
            }
            assertEquals(204,total);assertEquals(base+1,seed(service).path("sequence").asLong());
        }
    }

    @Test void oversizedSingleFrameUsesLatestSharedSeedAndSlowSubscriberNeverBlocksReader() throws Exception
    {
        Fixture fixture=new Fixture();fixture.set(List.of(row(1,"one")),List.of());
        try(P25ActivityLiveService service=fixture.service(1);var viewer=service.subscribe())
        {
            var old=seed(service);fixture.set(List.of(row(2,"x".repeat(300_000))),List.of());
            var resync=viewer.poll(2,TimeUnit.SECONDS);assertNotNull(resync);assertEquals("resync_required",resync.name());
            var next=seed(service);assertTrue(next.path("available").asBoolean());assertEquals(2,next.at("/rows/0/id").asInt());
            var many=new ArrayList<Map<String,Object>>();for(int id=1;id<=250;id++) many.add(affiliation(id,id));
            fixture.set(List.of(row(3,"three")),many);await(()->service.state().sequence()>next.path("sequence").asLong());
            assertTrue(viewer.droppedCount()>0);assertEquals(250,seed(service).path("affiliations").size());
            assertTrue(service.state().sequence()>old.path("sequence").asLong());
        }
    }

    @Test void latestNativeScopeIsExplicitlyTruncatedAndAliasRefreshIsSharedAndBounded() throws Exception
    {
        Fixture fixture=new Fixture();fixture.set(List.of(row(1,"one")),List.of(affiliation(1,11)));
        try(P25ActivityLiveService service=fixture.service();var viewer=service.subscribe())
        {
            seed(service);fixture.alias.set("renamed");fixture.generation.incrementAndGet();
            var update=viewer.poll(2,TimeUnit.SECONDS);assertEquals("renamed",list(update,"affiliations").getFirst().get("radio_alias_name"));
            int projected=fixture.nativePresentations.get();service.seed(true);for(int index=0;index<20;index++) service.seed(true);
            assertEquals(projected+1,fixture.nativePresentations.get());
            var overflow=new ArrayList<Map<String,Object>>();for(int id=0;id<=10_000;id++) overflow.add(affiliation(id,id));
            fixture.set(List.of(),overflow);await(()->service.state().affiliationsTruncated());
            var bounded=seed(service);assertTrue(bounded.path("available").asBoolean());
            assertTrue(bounded.path("affiliations_truncated").asBoolean());assertEquals(10_000,bounded.path("affiliations").size());
            fixture.set(List.of(),List.of());await(()->!service.state().affiliationsTruncated());
            assertTrue(seed(service).path("affiliations").isEmpty());
        }
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> data(StatsLiveEventHub.LiveEvent event)
    { return (Map<String,Object>)event.data(); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(StatsLiveEventHub.LiveEvent event,String field)
    { return (List<Map<String,Object>>)data(event).get(field); }
    private static List<StatsLiveEventHub.LiveEvent> revision(StatsLiveEventHub.Subscription viewer) throws Exception
    {
        var first=viewer.poll(2,TimeUnit.SECONDS);assertNotNull(first);var parts=new ArrayList<StatsLiveEventHub.LiveEvent>();parts.add(first);
        for(int index=1;index<((Number)data(first).get("parts")).intValue();index++)
        { var next=viewer.poll(1,TimeUnit.SECONDS);assertNotNull(next);parts.add(next); }
        return parts;
    }
    private static JsonNode seed(P25ActivityLiveService service) throws Exception
    { return JSON.readTree(service.seed().identity()).path("data"); }
    private static Map<String,Object> row(long id,String label)
    { return Map.of("id",id,"observed_at_ms",id,"label",label); }
    private static Map<String,Object> affiliation(int id,int group)
    { return Map.of("radio_system_key","p25:bee00:49f","source_identity_key","v1-r-bee00-49f-"+id,
        "radio_id",id,"talkgroup_id",group,"confirmed_at_ms",1000); }
    private static void await(BooleanSupplier condition) throws Exception
    {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"shared reader did not reach expected state");
    }
    private static final class Fixture implements P25ActivityLiveService.Source
    {
        final AtomicReference<P25ActivityLiveService.RawSnapshot> raw=new AtomicReference<>(new P25ActivityLiveService.RawSnapshot("first",List.of(),List.of()));
        final AtomicReference<Object> identity=new AtomicReference<>("first");
        final AtomicInteger reads=new AtomicInteger(),eventPresentations=new AtomicInteger(),nativePresentations=new AtomicInteger();
        final AtomicLong generation=new AtomicLong();final AtomicBoolean enabled=new AtomicBoolean(true),fail=new AtomicBoolean(),badProjection=new AtomicBoolean(),swapDuringPresentation=new AtomicBoolean();
        final AtomicReference<String> alias=new AtomicReference<>("unit");
        volatile CountDownLatch entered,release;
        void set(List<Map<String,Object>> rows,List<Map<String,Object>> affiliations)
        { raw.set(new P25ActivityLiveService.RawSnapshot(identity.get(),List.copyOf(rows),List.copyOf(affiliations))); }
        P25ActivityLiveService service() { return service(128); }
        P25ActivityLiveService service(int queue) { return new P25ActivityLiveService(this,()->2000,enabled::get,256,queue); }
        @Override public P25ActivityLiveService.RawSnapshot snapshot()
        {
            reads.incrementAndGet();if(entered!=null) { entered.countDown();try { release.await(2,TimeUnit.SECONDS); } catch(InterruptedException exception) { Thread.currentThread().interrupt(); } entered=null; }
            if(fail.get()) throw new IllegalStateException("fixture read unavailable");
            return new P25ActivityLiveService.RawSnapshot(identity.get(),raw.get().rows(),raw.get().affiliations());
        }
        @Override public Object identity() { return identity.get(); }
        @Override public long presentationGeneration() { return generation.get(); }
        @Override public List<Map<String,Object>> presentRows(List<Map<String,Object>> rows)
        { if(!rows.isEmpty()) eventPresentations.incrementAndGet();return rows; }
        @Override public List<Map<String,Object>> presentAffiliations(List<Map<String,Object>> rows)
        {
            if(rows.isEmpty()) return List.of();nativePresentations.incrementAndGet();
            if(badProjection.get()) throw new IllegalStateException("fixture presentation unavailable");
            if(swapDuringPresentation.getAndSet(false)) identity.set("replacement");
            return rows.stream().map(row->{ var value=new LinkedHashMap<>(row);value.put("radio_identity_key",value.remove("source_identity_key"));value.put("radio_alias_name",alias.get());return (Map<String,Object>)Map.copyOf(value); }).toList();
        }
    }
}
