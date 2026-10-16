package io.github.dsheirer.audio.tone;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AnalogToneSequenceTest
{
    @Test void arbitraryAnalogBlocksProduceNominalSingleAndDtmfOnOneSampleClock() throws Exception
    {
        for(DecoderType decoder:List.of(DecoderType.AM,DecoderType.NBFM))
        {
            var service = new P25PagingToneService(256, 1_000_000, 8, new P25PagingToneDetector()::detect);
            var registration = service.registerAnalogPcm();
            List<CompletedToneSequence> completed = new ArrayList<>();
            service.addCompletedToneListener(completed::add);
            float[] samples = new float[9600];
            System.arraycopy(AnalogDtmfDetectorTest.signal(440,0,.3,0,4000),0,samples,0,4000);
            System.arraycopy(AnalogDtmfDetectorTest.signal(770,1336,.25,.25,2400),0,samples,4800,2400);
            send(service,registration,snapshot(1,decoder),samples,237);
            assertEquals(1,completed.size());
            var sequence=completed.getFirst();
            assertEquals(CompletedToneSequence.Method.ANALOG_PCM_TONE_V1,sequence.method());
            assertEquals(1000,sequence.firstVoiceSampleTimestamp());
            assertEquals(2,sequence.tones().size());
            assertEquals(List.of(437500),sequence.tones().getFirst().frequenciesMilliHertz());
            assertEquals("DTMF_5",sequence.tones().getLast().codecSymbol());
            assertEquals(List.of(770000,1336000),sequence.tones().getLast().frequenciesMilliHertz());
            assertEquals(decoder.getProtocol(),sequence.protocol());
            assertEquals(0,service.retainedBytes());
            assertNotNull(ToneSummaryPayload.summary(sequence));
        }
    }

    @Test void ctcssAndP1WaveformDtmfProduceNoToneEvidence() throws Exception
    {
        var service = new P25PagingToneService(256,1_000_000,8,new P25PagingToneDetector()::detect);
        var analog=service.registerAnalogPcm();
        var p1=service.registerGlobalPcm();
        List<CompletedToneSequence> completed = new ArrayList<>();
        service.addCompletedToneListener(completed::add);
        send(service,analog,snapshot(2,DecoderType.NBFM),AnalogDtmfDetectorTest.signal(254.1,0,.3,0,8000),237);
        send(service,p1,snapshot(3,DecoderType.P25_PHASE1),AnalogDtmfDetectorTest.signal(770,1336,.25,.25,8000),160);
        assertEquals(2,completed.size());
        assertTrue(completed.stream().allMatch(sequence->sequence.tones().isEmpty()));
    }

    @Test void mixedCtcssDoesNotBecomeEvidenceOrMaskPagingAndDtmf() throws Exception
    {
        var service = new P25PagingToneService(256,1_000_000,8,new P25PagingToneDetector()::detect);
        var registration = service.registerAnalogPcm();
        List<CompletedToneSequence> completed = new ArrayList<>();service.addCompletedToneListener(completed::add);
        float[] samples = AnalogDtmfDetectorTest.signal(254.1,0,.25,0,9600);
        float[] paging = AnalogDtmfDetectorTest.signal(440,0,.3,0,4000);
        float[] dtmf = AnalogDtmfDetectorTest.signal(770,1336,.25,.25,2400);
        for(int index=0;index<paging.length;index++)samples[index]+=paging[index];
        for(int index=0;index<dtmf.length;index++)samples[4800+index]+=dtmf[index];
        send(service,registration,snapshot(7,DecoderType.NBFM),samples,237);
        assertEquals(2,completed.getFirst().tones().size());
        assertEquals(List.of(437500),completed.getFirst().tones().getFirst().frequenciesMilliHertz());
        assertEquals("DTMF_5",completed.getFirst().tones().getLast().codecSymbol());
    }

    @Test void lowestAudibleNominalSingleIsPreservedWhileCtcssBandIsExcluded() throws Exception
    {
        var service = new P25PagingToneService(256,1_000_000,8,new P25PagingToneDetector()::detect);
        var registration = service.registerAnalogPcm();
        List<CompletedToneSequence> completed = new ArrayList<>();service.addCompletedToneListener(completed::add);
        send(service,registration,snapshot(8,DecoderType.AM),AnalogDtmfDetectorTest.signal(281.25,0,.3,0,8000),237);
        assertEquals(List.of(281250),completed.getFirst().tones().getFirst().frequenciesMilliHertz());
    }

    @Test void queueLossResetAndRebindNeverPublishIncompleteAnalogCall() throws Exception
    {
        var service = new P25PagingToneService(2,1_000_000,8,new P25PagingToneDetector()::detect);
        var registration=service.registerAnalogPcm();
        List<CompletedToneSequence> completed = new ArrayList<>();service.addCompletedToneListener(completed::add);
        var snapshot=snapshot(4,DecoderType.AM);
        registration.receive(event(AudioCallEventType.CALL_CREATED,snapshot,null,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,snapshot,new float[237],1030));
        assertTimeoutPreemptively(Duration.ofMillis(250),()->registration.receive(event(AudioCallEventType.AUDIO_FRAME,snapshot,new float[237],1059)));
        service.drain();
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,snapshot,null,0));service.drain();
        assertTrue(completed.isEmpty());assertTrue(service.droppedEvents()>0);assertEquals(0,service.retainedBytes());
        registration.resetUnfinishedCall();registration.close();service.drain();
        assertTrue(completed.isEmpty());assertEquals(0,service.retainedBytes());
    }

    @Test void slowMultipleListenersCannotHoldAnalogProducerOrClose() throws Exception
    {
        var service=new P25PagingToneService(256,1_000_000,8,new P25PagingToneDetector()::detect);
        var registration=service.registerAnalogPcm();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var subscription=service.addCompletedToneListener(sequence->{entered.countDown();try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        List<CompletedToneSequence> second=new ArrayList<>();service.addCompletedToneListener(second::add);
        var worker=Executors.newSingleThreadExecutor();
        try
        {
            send(null,registration,snapshot(5,DecoderType.NBFM),AnalogDtmfDetectorTest.signal(440,0,.3,0,4000),237);
            var draining=worker.submit(service::drain);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(250),()->{
                subscription.close();registration.close();
                var next=service.registerAnalogPcm();
                send(null,next,snapshot(6,DecoderType.AM),new float[1600],237);next.close();
            });
            release.countDown();draining.get(2,TimeUnit.SECONDS);
            assertEquals(2,second.size(),"Accepted completed calls survive producer disposal");
            assertEquals(0,service.retainedBytes());
        }
        finally{release.countDown();worker.shutdownNow();}
    }

    static AudioCallSnapshot snapshot(long sequence,DecoderType decoder)
    {
        var id=new AudioCallId(42,sequence,0);
        return new AudioCallSnapshot(id,null,null,new IdentifierCollection(),Set.of(),1000,3000,1,0,1000,3000,false,true,
            CallEncryptionState.CLEAR,false,null,VoiceCallQuality.EMPTY,new CallLegId(42,sequence,0),
            new CallLegSource(decoder,"00000000-0000-4000-8000-000000000001","Analog",null,0,null,null,null,false),null);
    }
    static AudioCallEvent event(AudioCallEventType type,AudioCallSnapshot snapshot,float[] samples,long timestamp)
    {return new AudioCallEvent(type,snapshot,samples,false,0,timestamp);}
    static void send(P25PagingToneService service,P25PagingToneService.Registration registration,AudioCallSnapshot snapshot,float[] samples,int blockSize)
    {
        registration.receive(event(AudioCallEventType.CALL_CREATED,snapshot,null,0));
        int consumed=0;
        for(float[] block:AnalogDtmfDetectorTest.blocks(samples,blockSize))
        {
            consumed+=block.length;registration.receive(event(AudioCallEventType.AUDIO_FRAME,snapshot,block,1000+consumed/8));
            if(service!=null)service.drain();
        }
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,snapshot,null,0));if(service!=null)service.drain();
    }
}
