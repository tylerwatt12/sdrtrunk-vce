package io.github.dsheirer.alerts;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletedToneAlertServiceTest
{
    @TempDir Path directory;
    static final String CHANNEL="00000000-0000-4000-8000-000000000001";
    static final long START=Instant.parse("2026-10-13T12:00:00Z").toEpochMilli();

    @Test void completedWaveformAndEncodedTonesUseSameRulesWithoutActivityOrRecording() throws Exception
    {
        AtomicLong clock=new AtomicLong(START+2000);
        try(var service=configured(clock,0,AlertRule.Schedule.always()))
        {
            for(var source:List.of(CompletedToneSequence.Source.DECODED_AUDIO,CompletedToneSequence.Source.ENCODED))
            {
                var sequence=sequence(source.ordinal()+1,DecoderType.P25_PHASE1,source);
                assertFalse(sequence.snapshot().recordAudio());
                assertNull(sequence.snapshot().activityCallToken());
                service.receiveCompletedToneSequence(sequence);
                service.receiveCompletedToneSequence(sequence); //Duplicate producer delivery is harmless.
                service.drain();
            }
            assertEquals(2,service.history(null,100,0).get("total"));
            assertEquals(2,service.pendingDeliveries(100).size());
            assertEquals("200",service.pendingDeliveries(100).getFirst().body());
        }
    }

    @Test void legacyCodecEventDoesNotFireToneRuleAndAnalogDtmfSharesCompletedPath() throws Exception
    {
        AtomicLong clock=new AtomicLong(START+2000);
        try(var service=configured(clock,0,AlertRule.Schedule.always()))
        {
            var encoded=sequence(3,DecoderType.P25_PHASE2,CompletedToneSequence.Source.ENCODED);
            service.receiveEventView(DecodeEventViewService.completedToneView(encoded,null));service.drain();
            assertEquals(0,service.history(null,100,0).get("total"));
            service.receiveCompletedToneSequence(encoded);service.drain();
            assertEquals(1,service.history(null,100,0).get("total"));
            var config=service.configuration();
            service.saveRule(new AlertRule("dtmf","DTMF","fire",true,"tone","","",CHANNEL,"NBFM","",
                new AlertRule.Condition("",null,"tone","is","DTMF_5"),null,null,0,"rule",false,"DTMF","{talkgroup}","details"),config.revision());
            var snapshot=snapshot(4,DecoderType.NBFM);
            var sequence=new CompletedToneSequence(snapshot,START,CompletedToneSequence.Source.DECODED_AUDIO,
                CompletedToneSequence.Method.ANALOG_PCM_TONE_V1,List.of(new CompletedToneSequence.Element(List.of(770000,1336000),"DTMF_5",50L,100,.99)));
            service.receiveCompletedToneSequence(sequence);service.drain();
            assertEquals(2,service.history(null,100,0).get("total"));
        }
    }

    @Test void directCompletedResultsHonorScheduleCooldownAndBoundedWorkerProjection() throws Exception
    {
        AtomicLong clock=new AtomicLong(START+2000);
        try(var service=configured(clock,1,AlertRule.Schedule.always()))
        {
            AtomicBoolean projected=new AtomicBoolean();
            service.setToneProjector(sequence->{projected.set(true);return DecodeEventViewService.completedToneView(sequence,null);});
            service.receiveCompletedToneSequence(sequence(5,DecoderType.P25_PHASE1,CompletedToneSequence.Source.DECODED_AUDIO));
            assertFalse(projected.get(),"No projection runs on the tone observer callback");
            service.drain();assertTrue(projected.get());
            service.receiveCompletedToneSequence(sequence(6,DecoderType.P25_PHASE1,CompletedToneSequence.Source.DECODED_AUDIO));service.drain();
            assertEquals(1,service.history(null,100,0).get("total"));
            clock.addAndGet(60000);
            service.receiveCompletedToneSequence(sequence(7,DecoderType.P25_PHASE1,CompletedToneSequence.Source.DECODED_AUDIO));service.drain();
            assertEquals(2,service.history(null,100,0).get("total"));
            for(int index=0;index<TopicAlertService.INGRESS_CAPACITY;index++)
                service.receiveCompletedToneSequence(sequence(100+index,DecoderType.P25_PHASE1,CompletedToneSequence.Source.DECODED_AUDIO));
            assertTimeoutPreemptively(Duration.ofMillis(250),()->service.receiveCompletedToneSequence(sequence(2000,DecoderType.P25_PHASE1,CompletedToneSequence.Source.DECODED_AUDIO)));
            assertTrue(service.droppedObservations()>0);
        }
        try(var service=configured(clock,0,new AlertRule.Schedule("custom",List.of(2),"00:00","00:01","UTC")))
        {
            service.receiveCompletedToneSequence(sequence(8,DecoderType.P25_PHASE1,CompletedToneSequence.Source.DECODED_AUDIO));service.drain();
            assertEquals(0,service.history(null,100,0).get("total"));
        }
    }

    @Test void nativeAndFallbackDigitalSystemSiteRulesMatchCompletedEvidence() throws Exception
    {
        AtomicLong clock=new AtomicLong(START+2000);
        for(var decoder:List.of(DecoderType.DMR,DecoderType.NXDN))
            for(boolean nativeSystem:List.of(true,false))
                try(var service=configured(clock,0,AlertRule.Schedule.always()))
                {
                    String key=nativeSystem?(decoder==DecoderType.DMR?"dmr:tier3:small:1":"nxdn-c:global:1"):
                        (decoder==DecoderType.DMR?"dmr":"nxdn-c")+":channel:"+CHANNEL;
                    var rule=new AlertRule("native","Native paging","fire",true,"tone",key,"3",CHANNEL,decoder.name(),"",
                        new AlertRule.Condition("",null,"tone","is","HZ_437_50"),null,null,0,"rule",false,"Native tone","{site}","details");
                    service.saveRule(rule,service.configuration().revision());
                    var id=new AudioCallId(76,nativeSystem?1:2,0);
                    // A legacy source with no key retains deterministic saved-channel ownership, never current live facts.
                    var source=new CallLegSource(decoder,CHANNEL,"Native",null,0,null,null,null,false,nativeSystem?key:null).withServingSiteId("3");
                    var snapshot=new AudioCallSnapshot(id,null,null,new IdentifierCollection(),Set.of(),START,START+2000,1,0,START,START+2000,false,true,
                        CallEncryptionState.CLEAR,false,null,VoiceCallQuality.EMPTY,CallLegId.from(id),source,null);
                    var sequence=new CompletedToneSequence(snapshot,0,CompletedToneSequence.Source.ENCODED,CompletedToneSequence.Method.AMBE_TONE_V1,
                        List.of(new CompletedToneSequence.Element(List.of(437500),"HZ_437_50",0L,100,null)));
                    service.receiveCompletedToneSequence(sequence);service.drain();
                    assertEquals(1,service.history(null,100,0).get("total"),decoder.name()+" "+key);
                    assertEquals("3",service.pendingDeliveries(100).getFirst().body());
                }
    }

    @Test void concurrentStartAndCloseReleaseTheSharedToneSubscription() throws Exception
    {
        Path database=directory.resolve("lifecycle.sqlite");SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var listeners=io.github.dsheirer.audio.tone.P25PagingToneService.class.getDeclaredField("mCompletedListeners");
        listeners.setAccessible(true);
        var shared=io.github.dsheirer.audio.tone.P25PagingToneService.getInstance();
        int before=((java.util.Collection<?>)listeners.get(shared)).size();
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try
        {
            for(int attempt=0;attempt<12;attempt++)
            {
                var service=new TopicAlertService(new TopicAlertStore(database),()->START);
                var start=new java.util.concurrent.CyclicBarrier(2);
                var opened=workers.submit(()->{start.await();service.start();return null;});
                var closed=workers.submit(()->{start.await();service.close();return null;});
                opened.get(2,java.util.concurrent.TimeUnit.SECONDS);closed.get(2,java.util.concurrent.TimeUnit.SECONDS);
                service.close();
                assertEquals(before,((java.util.Collection<?>)listeners.get(shared)).size());
            }
        }
        finally{workers.shutdownNow();}
    }

    TopicAlertService configured(AtomicLong clock,int cooldown,AlertRule.Schedule schedule)throws Exception
    {
        Path database=directory.resolve("tone-"+System.nanoTime()+".sqlite");SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var service=new TopicAlertService(new TopicAlertStore(database),clock::get);
        var rule=new AlertRule("tone","SW ALERT","fire",true,"tone","p25:00001:002","1:2",CHANNEL,"P25","",
            new AlertRule.Condition("all",List.of(new AlertRule.Condition("",null,"tone","is","HZ_437_50"),
                new AlertRule.Condition("",null,"tone","is","HZ_593_75"),new AlertRule.Condition("",null,"talkgroup","is","200")),"","",""),
            null,schedule,cooldown,"rule",false,"Tone","{talkgroup}","details");
        service.updateConfiguration(new TopicAlertConfiguration(1,new TopicAlertConfiguration.PushSettings(true,"proxy","","VCE"),
            List.of(AlertTopic.administratorAlerts(),new AlertTopic("fire","Fire","",true,true,false)),List.of(rule)),1);
        service.saveDevice(new AlertDevice("phone","Phone","proxy","remote-phone","","","","a".repeat(64),List.of("fire"),false));
        return service;
    }
    static CompletedToneSequence sequence(long sequence,DecoderType decoder,CompletedToneSequence.Source source)
    {
        var tones=List.of(new CompletedToneSequence.Element(List.of(437500),source==CompletedToneSequence.Source.ENCODED?"HZ_437_50":null,50L,250,source==CompletedToneSequence.Source.ENCODED?null:.99),
            new CompletedToneSequence.Element(List.of(593750),source==CompletedToneSequence.Source.ENCODED?"HZ_593_75":null,400L,250,source==CompletedToneSequence.Source.ENCODED?null:.99));
        return new CompletedToneSequence(snapshot(sequence,decoder),START,source,source==CompletedToneSequence.Source.ENCODED?
            CompletedToneSequence.Method.AMBE_TONE_V1:CompletedToneSequence.Method.P25_PCM_AMBE_TONE_V2,tones);
    }
    static AudioCallSnapshot snapshot(long sequence,DecoderType decoder)
    {
        var id=new AudioCallId(42,sequence,0);
        var identifiers=new IdentifierCollection(List.of(APCO25Talkgroup.create(200)));
        boolean p25=decoder==DecoderType.P25_PHASE1||decoder==DecoderType.P25_PHASE2;
        var source=new CallLegSource(decoder,CHANNEL,"Southwest",null,0,p25?new P25SiteIdentity(1,2,1,2):null,null,null,false,p25?"p25:00001:002":null);
        return new AudioCallSnapshot(id,null,null,identifiers,Set.of(),START,START+2000,1,0,START,START+2000,false,true,
            CallEncryptionState.CLEAR,false,null,VoiceCallQuality.EMPTY,new CallLegId(42,sequence,0),source,null);
    }
}
