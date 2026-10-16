package io.github.dsheirer.audio;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.audio.tone.P25PagingToneService;
import io.github.dsheirer.module.decode.DecoderType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AnalogAudioToneObservationTest
{
    @Test void rawTapSurvivesPresentationMutationsAndUsesCorrectRolloverSnapshot() throws Exception
    {
        var tones=P25PagingToneService.getInstance();
        var source=new CallLegSource(DecoderType.NBFM,"00000000-0000-4000-8000-000000000001","Analog",null,0,null,null,null,false);
        var module=new AudioModule(null,0,500,true,source);
        List<AudioCallEvent> normal=new ArrayList<>();module.setAudioCallEventListener(normal::add);
        var completed=new ConcurrentLinkedQueue<CompletedToneSequence>();
        try(var subscription=tones.addCompletedToneListener(completed::add))
        {
            module.getSquelchStateListener().receive(SquelchStateEvent.create(SquelchState.UNSQUELCH));
            for(int frame=0;frame<10;frame++)
            {
                float[] raw=signal(frame<5?440:600,800,frame*800);
                module.receiveRawAudio(raw);
                java.util.Arrays.fill(raw,0); //Upstream presentation processing may mutate its borrowed buffer.
                module.receive(new float[800]);
            }
            module.getSquelchStateListener().receive(SquelchStateEvent.create(SquelchState.SQUELCH));
            assertTrue(tones.awaitWorkerDrain(500,TimeUnit.MILLISECONDS));
            var results=List.copyOf(completed);
            var ends=normal.stream().filter(event->event.eventType()==AudioCallEventType.CALL_COMPLETED).toList();
            assertEquals(2,ends.size());assertEquals(2,results.size());
            assertEquals(ends.getFirst().callId(),results.getFirst().callId());
            assertEquals(ends.getLast().callId(),results.getLast().callId());
            assertEquals(List.of(437500),results.getFirst().tones().getFirst().frequenciesMilliHertz());
            assertEquals(List.of(593750),results.getLast().tones().getFirst().frequenciesMilliHertz());
            assertTrue(normal.stream().filter(event->event.eventType()==AudioCallEventType.AUDIO_FRAME)
                .allMatch(event->java.util.Arrays.equals(new float[800],event.audioFrame())));
        }
        finally{module.dispose();}
    }

    @Test void missingRawBlockDiscardsWholeCallAndRebindingStartsFresh() throws Exception
    {
        var tones=P25PagingToneService.getInstance();
        var module=new AudioModule(null,0,60000,false,new CallLegSource(DecoderType.AM,"00000000-0000-4000-8000-000000000001","Analog",null,0,null,null,null,false));
        var completed=new ConcurrentLinkedQueue<CompletedToneSequence>();
        try(var subscription=tones.addCompletedToneListener(completed::add))
        {
            module.getSquelchStateListener().receive(SquelchStateEvent.create(SquelchState.UNSQUELCH));
            module.receiveRawAudio(signal(440,2400,0));module.receive(new float[2400]);
            module.receive(new float[160]);
            module.receiveRawAudio(signal(440,2400,2400));module.receive(new float[2400]);
            module.getSquelchStateListener().receive(SquelchStateEvent.create(SquelchState.SQUELCH));
            assertTrue(tones.awaitWorkerDrain(500,TimeUnit.MILLISECONDS));
            assertTrue(completed.isEmpty(),"Audio across an unobserved gap cannot become evidence");
            module.removeAudioCallEventListener();
            module.getSquelchStateListener().receive(SquelchStateEvent.create(SquelchState.UNSQUELCH));
            module.receiveRawAudio(signal(600,4000,0));module.receive(new float[4000]);
            module.getSquelchStateListener().receive(SquelchStateEvent.create(SquelchState.SQUELCH));
            assertTrue(tones.awaitWorkerDrain(500,TimeUnit.MILLISECONDS));
            assertEquals(1,completed.size(),"The tone observer is independent of normal audio subscribers");
            assertEquals(List.of(593750),completed.peek().tones().getFirst().frequenciesMilliHertz());
        }
        finally{module.dispose();}
    }

    static float[] signal(double frequency,int length,int offset)
    {
        float[] result=new float[length];
        for(int sample=0;sample<length;sample++)result[sample]=(float)(.3*Math.sin(2*Math.PI*frequency*(sample+offset)/8000));
        return result;
    }
}
