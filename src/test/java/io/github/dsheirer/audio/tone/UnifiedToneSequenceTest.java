package io.github.dsheirer.audio.tone;

import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.protocol.Protocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedToneSequenceTest
{
    @Test
    void qualifiedP2FramesBecomeOneDetachedCompleteSequence() throws Exception
    {
        AtomicInteger fftCalls = new AtomicInteger();
        P25PagingToneService service = service(64, fftCalls);
        var registration = service.registerEncoded(3);
        List<CompletedToneSequence> outcomes = new ArrayList<>();
        service.addCompletedToneListener(outcomes::add);
        AudioCallSnapshot snapshot = snapshot(1,DecoderType.P25_PHASE2);
        start(service,registration,snapshot);
        frame(service,registration,snapshot,0,AmbeTone.HZ_1000_00);
        frame(service,registration,snapshot,1,AmbeTone.HZ_1000_00);
        frame(service,registration,snapshot,2,AmbeTone.HZ_1000_00);
        frame(service,registration,snapshot,3,null);
        frame(service,registration,snapshot,4,AmbeTone.KNOX_0);
        frame(service,registration,snapshot,5,AmbeTone.KNOX_0); //P2 two-frame artifact does not qualify.
        frame(service,registration,snapshot,6,null);
        frame(service,registration,snapshot,7,AmbeTone.DTMF_5);
        frame(service,registration,snapshot,8,AmbeTone.DTMF_5);
        frame(service,registration,snapshot,9,AmbeTone.DTMF_5);
        assertTrue(outcomes.isEmpty());
        complete(service,registration,snapshot);
        assertEquals(1,outcomes.size());
        var sequence = outcomes.getFirst();
        assertSame(sequence,service.lookupCompleted(snapshot));
        assertEquals(Protocol.APCO25_PHASE2,sequence.protocol());
        assertEquals(1000,sequence.firstVoiceSampleTimestamp());
        assertEquals(2,sequence.tones().size());
        assertEquals(60,sequence.tones().getFirst().durationMilliseconds());
        assertEquals(0L,sequence.tones().getFirst().offsetMilliseconds().longValue());
        assertEquals(140L,sequence.tones().get(1).offsetMilliseconds().longValue());
        assertEquals(List.of(770000,1336000),sequence.tones().get(1).frequenciesMilliHertz());
        assertNull(sequence.tones().get(1).confidence());
        assertEquals(0,fftCalls.get());
        assertThrows(UnsupportedOperationException.class,() -> sequence.tones().clear());
        assertThrows(UnsupportedOperationException.class,() -> sequence.snapshot().identifierCollection().setTimeslot(4));
    }

    @Test
    void dmrAndNxdnMetadataContinueWhenPcmIsOffWithNoInventedAnchor() throws Exception
    {
        AtomicInteger fftCalls = new AtomicInteger();
        P25PagingToneService service = service(64,fftCalls);
        List<CompletedToneSequence> outcomes = new ArrayList<>();
        service.addCompletedToneListener(outcomes::add);
        service.setEnabled(false);
        for(DecoderType decoder: List.of(DecoderType.DMR,DecoderType.NXDN))
        {
            var registration = service.registerEncoded(1);
            var snapshot = snapshot(outcomes.size()+2,decoder);
            start(service,registration,snapshot);
            frame(service,registration,snapshot,0,AmbeTone.KNOX_0);
            complete(service,registration,snapshot);
            assertEquals(0,outcomes.getLast().firstVoiceSampleTimestamp());
            assertEquals(20,outcomes.getLast().tones().getFirst().durationMilliseconds());
            assertEquals(decoder.getProtocol(),outcomes.getLast().protocol());
            assertEquals(List.of(820000,1162000),outcomes.getLast().tones().getFirst().frequenciesMilliHertz());
        }
        assertEquals(2,outcomes.size());assertEquals(0,fftCalls.get());assertEquals(0,service.retainedBytes());
    }

    @Test
    void fullQueueAndSequenceOverflowSuppressWholeOutcome() throws Exception
    {
        P25PagingToneService full = service(2,new AtomicInteger());
        List<CompletedToneSequence> outcomes = new ArrayList<>();full.addCompletedToneListener(outcomes::add);
        var registration = full.registerEncoded(1);var snapshot = snapshot(4,DecoderType.DMR);
        registration.receive(event(AudioCallEventType.CALL_CREATED,snapshot,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,snapshot,0));
        assertTimeoutPreemptively(Duration.ofMillis(250),() -> registration.receiveEncodedFrame(snapshot,AmbeTone.HZ_1000_00));
        full.drain();registration.receive(event(AudioCallEventType.CALL_COMPLETED,snapshot,0));full.drain();
        assertTrue(outcomes.isEmpty());assertTrue(full.droppedEvents()>0);

        P25PagingToneService overflow = service(64,new AtomicInteger());overflow.addCompletedToneListener(outcomes::add);
        var many = overflow.registerEncoded(1);var longCall = snapshot(5,DecoderType.DMR);
        start(overflow,many,longCall);
        for(int index=0;index<33;index++)
        {
            frame(overflow,many,longCall,index*2,AmbeTone.HZ_1000_00);
            frame(overflow,many,longCall,index*2+1,null);
        }
        complete(overflow,many,longCall);
        assertTrue(outcomes.isEmpty());assertNull(overflow.lookupCompleted(longCall));assertTrue(overflow.droppedEvents()>0);
    }

    @Test
    void contextResetAndCloseRejectUnfinishedOrStaleEvidence() throws Exception
    {
        P25PagingToneService service = service(64,new AtomicInteger());
        List<CompletedToneSequence> outcomes = new ArrayList<>();service.addCompletedToneListener(outcomes::add);
        var registration = service.registerEncoded(1);var abandoned = snapshot(6,DecoderType.DMR);
        start(service,registration,abandoned);frame(service,registration,abandoned,0,AmbeTone.DTMF_0);
        registration.resetUnfinishedCall();service.drain();complete(service,registration,abandoned);
        assertTrue(outcomes.isEmpty());
        var next = snapshot(7,DecoderType.DMR);start(service,registration,next);
        frame(service,registration,next,0,AmbeTone.HZ_1000_00);complete(service,registration,next);
        assertEquals(1,outcomes.size());assertEquals("HZ_1000_00",outcomes.getFirst().tones().getFirst().codecSymbol());
        assertSame(outcomes.getFirst(),service.lookupCompleted(next));
        assertNull(service.lookupCompleted(snapshot(7,DecoderType.NXDN)));
        registration.close();assertSame(outcomes.getFirst(),service.lookupCompleted(next)); //Published native evidence survives owner disposal.
    }

    @Test
    void slowCompletedObserverDoesNotBackpressureOffersOrDisposal() throws Exception
    {
        P25PagingToneService service = service(8,new AtomicInteger());
        CountDownLatch entered = new CountDownLatch(1),release = new CountDownLatch(1);
        AutoCloseable listener = service.addCompletedToneListener(sequence -> {
            entered.countDown();
            try { release.await(2,TimeUnit.SECONDS); } catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        var registration = service.registerEncoded(1);var first = snapshot(8,DecoderType.DMR);
        start(service,registration,first);frame(service,registration,first,0,AmbeTone.HZ_1000_00);
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,first,0));
        Thread worker = new Thread(service::drain);worker.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
        try
        {
            assertTimeoutPreemptively(Duration.ofMillis(250),() -> {
                var another = service.registerEncoded(1);var next = snapshot(9,DecoderType.NXDN);
                another.receive(event(AudioCallEventType.CALL_CREATED,next,0));
                for(int index=0;index<100;index++) another.receive(event(AudioCallEventType.AUDIO_FRAME,next,index));
                registration.close();listener.close();
            });
            assertTrue(service.droppedEvents()>0);assertTrue(service.queuedEvents()<=8);
        }
        finally {release.countDown();worker.join(2000);}
        assertFalse(worker.isAlive());assertNotNull(service.lookupCompleted(first));
    }

    @Test
    void sourceMappingRetainsCodesAndRejectsConflictingMetadata()
    {
        assertEquals(AmbeTone.DTMF_5,EncodedToneMetadata.tone(Map.of("DTMF","5","jmbe_quality","ignored")));
        assertNull(EncodedToneMetadata.tone(Map.of("TONE","reserved")));
        assertEquals(AmbeTone.INVALID,EncodedToneMetadata.tone(Map.of("DTMF","5","TONE","1000.00")));
        assertEquals(List.of(1000000),EncodedToneMetadata.frequencies(AmbeTone.HZ_1000_00));
        assertEquals(List.of(350000,490000),EncodedToneMetadata.frequencies(AmbeTone.CALL_PROGRESS));
    }

    @Test
    void pcmCacheCannotBecomeCurrentAgainAfterOffOn() throws Exception
    {
        P25PagingToneService service = new P25PagingToneService(64,64000,8,buffers -> List.of(
            new P25PagingToneDetector.DetectedTone(800,0,200,.99,"decoded_audio","p25_pcm_tone_v1")));
        var registration = service.register();registration.setMessageListener(message -> {});
        var snapshot = snapshot(10,DecoderType.P25_PHASE1);
        registration.receive(event(AudioCallEventType.CALL_CREATED,snapshot,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,snapshot,0));
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,snapshot,0));service.drain();
        assertNotNull(service.lookupCompleted(snapshot));
        service.setEnabled(false);assertNull(service.lookupCompleted(snapshot));
        service.setEnabled(true);assertNull(service.lookupCompleted(snapshot));service.drain();
        assertNull(service.lookupCompleted(snapshot));
    }

    @Test
    void shutdownBarrierFollowsCompletedListenerDelivery() throws Exception
    {
        P25PagingToneService service = new P25PagingToneService(64,64000,8,buffers -> List.of(),true);
        AtomicInteger delivered = new AtomicInteger();
        AutoCloseable listener = service.addCompletedToneListener(sequence -> delivered.incrementAndGet());
        try
        {
            var registration = service.registerEncoded(1);var snapshot = snapshot(11,DecoderType.NXDN);
            registration.receive(event(AudioCallEventType.CALL_CREATED,snapshot,0));
            registration.receive(event(AudioCallEventType.AUDIO_FRAME,snapshot,0));
            registration.receiveEncodedFrame(snapshot,AmbeTone.HZ_1000_00);
            registration.receive(event(AudioCallEventType.CALL_COMPLETED,snapshot,0));
            assertTrue(service.awaitWorkerDrain(1,TimeUnit.SECONDS));
            assertEquals(1,delivered.get());assertNotNull(service.lookupCompleted(snapshot));
            registration.close();
        }
        finally {listener.close();service.stopWorker();assertTrue(service.awaitWorkerStopped(1000));}
    }

    @Test
    void acceptedCompletePcmSurvivesModuleDisposeButLegacyMessageDoesNot() throws Exception
    {
        P25PagingToneService service = new P25PagingToneService(64,64000,8,buffers -> List.of(
            new P25PagingToneDetector.DetectedTone(800,0,200,.99,"decoded_audio","p25_pcm_tone_v1")));
        List<CompletedToneSequence> outcomes = new ArrayList<>();service.addCompletedToneListener(outcomes::add);
        AtomicInteger legacy = new AtomicInteger();var registration = service.register();
        registration.setMessageListener(message -> legacy.incrementAndGet());var snapshot = snapshot(12,DecoderType.P25_PHASE1);
        registration.receive(event(AudioCallEventType.CALL_CREATED,snapshot,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,snapshot,0));
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,snapshot,0));
        registration.close();service.drain();
        assertEquals(1,outcomes.size());assertEquals(0,legacy.get());
        assertSame(outcomes.getFirst(),service.lookupCompleted(snapshot));
        assertSame(outcomes.getFirst(),service.awaitCompleted(snapshot,500,TimeUnit.MILLISECONDS));
    }

    @Test
    void recordingWaitIsBoundedAndInterruptedWorkerKeepsItsInterrupt() throws Exception
    {
        P25PagingToneService service = service(64,new AtomicInteger());
        var registration = service.registerEncoded(1);var snapshot = snapshot(13,DecoderType.DMR);
        start(service,registration,snapshot);frame(service,registration,snapshot,0,AmbeTone.HZ_1000_00);
        assertTimeoutPreemptively(Duration.ofMillis(200),() -> assertNull(service.awaitCompleted(snapshot,10,TimeUnit.MILLISECONDS)));
        assertThrows(IllegalArgumentException.class,() -> service.awaitCompleted(snapshot,501,TimeUnit.MILLISECONDS));
        Thread.currentThread().interrupt();
        try {assertNull(service.awaitCompleted(snapshot,500,TimeUnit.MILLISECONDS));assertTrue(Thread.currentThread().isInterrupted());}
        finally {Thread.interrupted();}
        complete(service,registration,snapshot);
        assertNotNull(service.awaitCompleted(snapshot,500,TimeUnit.MILLISECONDS));
        assertTimeoutPreemptively(Duration.ofMillis(100),() -> assertNull(service.awaitCompleted(snapshot(14,DecoderType.NXDN),500,TimeUnit.MILLISECONDS)));
    }

    @Test
    void nextCallLossCannotEraseAnAlreadyAcceptedCompletedCall() throws Exception
    {
        P25PagingToneService service = service(4,new AtomicInteger());
        var registration = service.registerEncoded(1);var first = snapshot(15,DecoderType.DMR);
        start(service,registration,first);frame(service,registration,first,0,AmbeTone.HZ_1000_00);
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,first,0));
        var next = snapshot(16,DecoderType.DMR);
        registration.receive(event(AudioCallEventType.CALL_CREATED,next,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,next,0));
        registration.receiveEncodedFrame(next,AmbeTone.DTMF_5);
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,next,1)); //Overflows only the later seal.
        assertTrue(service.droppedEvents()>0);service.drain();
        assertNotNull(service.lookupCompleted(first));assertNull(service.lookupCompleted(next));
        assertNotNull(service.awaitCompleted(first,500,TimeUnit.MILLISECONDS));
    }

    @Test
    void malformedEarlierCompleteCallCannotInvalidateLaterQueuedCompleteCall() throws Exception
    {
        P25PagingToneService service = service(32,new AtomicInteger());
        var registration = service.registerEncoded(1);var bad = snapshot(17,DecoderType.DMR);
        registration.receive(event(AudioCallEventType.CALL_CREATED,bad,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,bad,0));
        registration.receiveEncodedFrame(bad,AmbeTone.INVALID);
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,bad,0));
        var good = snapshot(18,DecoderType.DMR);
        registration.receive(event(AudioCallEventType.CALL_CREATED,good,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,good,0));
        registration.receiveEncodedFrame(good,AmbeTone.HZ_1000_00);
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,good,0));service.drain();
        assertNull(service.lookupCompleted(bad));assertNotNull(service.lookupCompleted(good));
        assertEquals(0,service.retainedBytes());
    }

    @Test
    void globalPcmDoesNotDependOnMessageAudienceAndUsesCapturedLegacyGeneration() throws Exception
    {
        P25PagingToneService service = new P25PagingToneService(64,64000,8,buffers -> List.of(
            new P25PagingToneDetector.DetectedTone(800,0,200,.99,"decoded_audio","p25_pcm_tone_v1")));
        var registration = service.registerGlobalPcm();var first = snapshot(19,DecoderType.P25_PHASE1);
        registration.receive(event(AudioCallEventType.CALL_CREATED,first,0));
        registration.receive(event(AudioCallEventType.ACTIVITY,first,0));
        registration.receive(event(AudioCallEventType.METADATA_UPDATED,first,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,first,0));
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,first,0));service.drain();
        assertNotNull(service.lookupCompleted(first));
        AtomicInteger oldMessages = new AtomicInteger(),newMessages = new AtomicInteger();
        registration.setLegacyMessageListener(message -> oldMessages.incrementAndGet());
        var second = snapshot(20,DecoderType.P25_PHASE1);
        registration.receive(event(AudioCallEventType.CALL_CREATED,second,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,second,0));
        registration.setLegacyMessageListener(message -> newMessages.incrementAndGet());
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,second,0));service.drain();
        assertNotNull(service.lookupCompleted(second));assertEquals(0,oldMessages.get());assertEquals(0,newMessages.get());
        var third = snapshot(21,DecoderType.P25_PHASE1);
        registration.receive(event(AudioCallEventType.CALL_CREATED,third,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,third,0));
        registration.setLegacyMessageListener(null);
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,third,0));service.drain();
        assertNotNull(service.lookupCompleted(third));assertEquals(0,newMessages.get());
    }

    @Test
    void encodedGlobalObservationIgnoresOptionalLegacyAudienceRemoval() throws Exception
    {
        P25PagingToneService service = service(64,new AtomicInteger());
        var registration = service.registerEncoded(3);var snapshot = snapshot(22,DecoderType.P25_PHASE2);
        start(service,registration,snapshot);frame(service,registration,snapshot,0,AmbeTone.HZ_1000_00);
        registration.setMessageListener(null);
        frame(service,registration,snapshot,1,AmbeTone.HZ_1000_00);frame(service,registration,snapshot,2,AmbeTone.HZ_1000_00);
        complete(service,registration,snapshot);assertNotNull(service.lookupCompleted(snapshot));
    }

    @Test
    void recordingWaitBarrierSeesCompletedCallStillQueuedBehindSlowWorker() throws Exception
    {
        P25PagingToneService service = new P25PagingToneService(64,64000,8,buffers -> List.of(),true);
        CountDownLatch entered = new CountDownLatch(1),release = new CountDownLatch(1);
        AutoCloseable listener = service.addCompletedToneListener(sequence -> {
            if(sequence.callId().sequence()==23)
            {
                entered.countDown();
                try { release.await(2,TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        });
        var waiter = Executors.newSingleThreadExecutor();
        try
        {
            var registration = service.registerEncoded(1);var first = snapshot(23,DecoderType.DMR);
            registration.receive(event(AudioCallEventType.CALL_CREATED,first,0));
            registration.receive(event(AudioCallEventType.AUDIO_FRAME,first,0));
            registration.receiveEncodedFrame(first,AmbeTone.HZ_1000_00);
            registration.receive(event(AudioCallEventType.CALL_COMPLETED,first,0));assertTrue(entered.await(1,TimeUnit.SECONDS));
            var next = snapshot(24,DecoderType.DMR);
            registration.receive(event(AudioCallEventType.CALL_CREATED,next,0));
            registration.receive(event(AudioCallEventType.AUDIO_FRAME,next,0));
            registration.receiveEncodedFrame(next,AmbeTone.HZ_1000_00);
            registration.receive(event(AudioCallEventType.CALL_COMPLETED,next,0));
            var result = waiter.submit(() -> service.awaitCompleted(next,500,TimeUnit.MILLISECONDS));
            long deadline = System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);
            while(service.queuedEvents()<5 && System.nanoTime()<deadline) Thread.sleep(1);
            assertTrue(service.queuedEvents()>=5);assertFalse(result.isDone());
            release.countDown();assertNotNull(result.get(1,TimeUnit.SECONDS));
            assertTrue(service.awaitWorkerDrain(1,TimeUnit.SECONDS));
        }
        finally { release.countDown();listener.close();waiter.shutdownNow();service.stopWorker();assertTrue(service.awaitWorkerStopped(1000)); }
    }

    @Test
    void completedCacheRetainsOnlyDetachedEvidenceAndPrimitiveValidity() throws Exception
    {
        P25PagingToneService service = new P25PagingToneService(64,64000,8,buffers -> List.of(
            new P25PagingToneDetector.DetectedTone(800,0,200,.99,"decoded_audio","p25_pcm_tone_v1")));
        var registration = service.registerGlobalPcm();registration.setLegacyMessageListener(message -> {});
        var snapshot = snapshot(25,DecoderType.P25_PHASE1);
        registration.receive(event(AudioCallEventType.CALL_CREATED,snapshot,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,snapshot,0));
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,snapshot,0));service.drain();
        var cacheField = P25PagingToneService.class.getDeclaredField("mCompleted");cacheField.setAccessible(true);
        Object cached = ((Map<?,?>)cacheField.get(service)).get(snapshot.callLegId());assertNotNull(cached);
        assertEquals(Set.of(CompletedToneSequence.class,long.class,AtomicInteger.class),
            java.util.Arrays.stream(cached.getClass().getRecordComponents()).map(java.lang.reflect.RecordComponent::getType)
                .filter(type -> !type.getSimpleName().equals("RuntimeEpoch")).collect(java.util.stream.Collectors.toSet()));
        var sealField = registration.getClass().getDeclaredField("mCallSeal");sealField.setAccessible(true);
        Object seal = ((java.util.concurrent.atomic.AtomicReference<?>)sealField.get(registration)).get();
        var legacyField = seal.getClass().getDeclaredField("legacyBinding");legacyField.setAccessible(true);
        assertNull(legacyField.get(seal)); //No audience graph survives completion or owner disposal in a cached outcome.
        registration.close();assertNotNull(service.lookupCompleted(snapshot));
        assertNull(service.lookupCompleted(snapshot).snapshot().aliasList());
        assertTrue(service.lookupCompleted(snapshot).snapshot().broadcastChannels().isEmpty());
    }

    @Test
    void encodedQueueAccountsOwnedPcmButActiveEncodedContextStoresNone() throws Exception
    {
        AtomicInteger fftCalls = new AtomicInteger();
        P25PagingToneService service = new P25PagingToneService(64,640,8,buffers -> {fftCalls.incrementAndGet();return List.of();});
        var registration = service.registerEncoded(1);var overBudget = snapshot(26,DecoderType.DMR);
        registration.receive(event(AudioCallEventType.CALL_CREATED,overBudget,0));
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,overBudget,0));
        registration.receiveEncodedFrame(overBudget,AmbeTone.HZ_1000_00);
        assertEquals(640,service.retainedBytes());
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,overBudget,1));
        assertEquals(640,service.retainedBytes());assertTrue(service.droppedEvents()>0);
        service.drain();assertEquals(0,service.retainedBytes());
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,overBudget,0));service.drain();
        assertNull(service.lookupCompleted(overBudget));
        var next = snapshot(27,DecoderType.DMR);start(service,registration,next);
        frame(service,registration,next,0,AmbeTone.HZ_1000_00);
        assertEquals(0,service.retainedBytes()); //An unfinished encoded context has no PCM reservation or copy.
        complete(service,registration,next);assertNotNull(service.lookupCompleted(next));assertEquals(0,fftCalls.get());
    }

    private static P25PagingToneService service(int queue,AtomicInteger fftCalls)
    {
        return new P25PagingToneService(queue,64000,8,buffers -> {fftCalls.incrementAndGet();return List.of();});
    }
    private static AudioCallSnapshot snapshot(long id,DecoderType decoder)
    {
        AudioCallId call = new AudioCallId(99,id,decoder==DecoderType.P25_PHASE2?1:0);
        CallLegSource source = new CallLegSource(decoder,"tone-test",null,null,0,null,null,null,false);
        return new AudioCallSnapshot(call,null,null,new IdentifierCollection(),Set.of(),1000,4000,1,1,1000,4000,
            false,true,CallEncryptionState.CLEAR,false,null,VoiceCallQuality.EMPTY,CallLegId.from(call),source,null);
    }
    private static AudioCallEvent event(AudioCallEventType type,AudioCallSnapshot snapshot,int frame)
    {
        return new AudioCallEvent(type,snapshot,type==AudioCallEventType.AUDIO_FRAME?new float[160]:null,false,0,
            type==AudioCallEventType.AUDIO_FRAME?1020+frame*20L:0);
    }
    private static void start(P25PagingToneService service,P25PagingToneService.Registration registration,AudioCallSnapshot snapshot)
    {
        registration.receive(event(AudioCallEventType.CALL_CREATED,snapshot,0));service.drain();
    }
    private static void frame(P25PagingToneService service,P25PagingToneService.Registration registration,AudioCallSnapshot snapshot,int frame,AmbeTone tone)
    {
        registration.receive(event(AudioCallEventType.AUDIO_FRAME,snapshot,frame));registration.receiveEncodedFrame(snapshot,tone);service.drain();
    }
    private static void complete(P25PagingToneService service,P25PagingToneService.Registration registration,AudioCallSnapshot snapshot)
    {
        registration.receive(event(AudioCallEventType.CALL_COMPLETED,snapshot,0));service.drain();
    }
}
