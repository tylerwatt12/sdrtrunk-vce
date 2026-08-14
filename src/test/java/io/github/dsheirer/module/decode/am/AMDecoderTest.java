/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.am;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.dsp.gain.AudioGainAndDcFilter;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog.Bandwidth;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AMDecoderTest
{
    private static final List<AmMode> SUPPORTED_AM_MODES = List.of(
        new AmMode(Bandwidth.BW_3_0, 25_000.0),
        new AmMode(Bandwidth.BW_5_0, 25_000.0),
        new AmMode(Bandwidth.BW_8_33, 25_000.0),
        new AmMode(Bandwidth.BW_15_0, 50_000.0),
        new AmMode(Bandwidth.BW_25_0, 50_000.0));

    @Test
    void demodulatesAmThroughTheSharedAnalogAudioPath()
    {
        DecodeConfigAM config = new DecodeConfigAM();
        AMDecoder decoder = new AMDecoder(config);
        List<DecoderStateEvent> stateEvents = new ArrayList<>();
        List<float[]> audio = new ArrayList<>();
        decoder.setDecoderStateListener(stateEvents::add);
        decoder.setBufferListener(audio::add);
        decoder.getSourceEventListener().receive(SourceEvent.sampleRateChange(50_000.0));
        decoder.setSquelchOverride(true);

        double phase = 0.0;
        for(int buffer = 0; buffer < 8; buffer++)
        {
            float[] i = new float[1_000];
            float[] q = new float[i.length];
            for(int x = 0; x < i.length; x++)
            {
                i[x] = 0.6f + 0.3f * (float)Math.sin(phase);
                phase += 2.0 * Math.PI * 1_000.0 / 50_000.0;
            }
            decoder.receive(new ComplexSamples(i, q, buffer * 20L));
        }

        assertEquals(DecoderType.AM, decoder.getDecoderType());
        assertTrue(stateEvents.stream().anyMatch(event -> event.getEvent() == DecoderStateEvent.Event.START));
        assertFalse(audio.isEmpty());
        float maximum = 0.0f;
        for(float[] samples: audio)
        {
            for(float sample: samples)
            {
                maximum = Math.max(maximum, Math.abs(sample));
            }
        }
        assertTrue(maximum > 0.01f);
        assertTrue(maximum <= 0.95001f);
    }

    @Test
    void idleFirstDetectsTwoRealCarriersAndDoesNotSegmentIdleNoise()
    {
        DecodeConfigAM config = new DecodeConfigAM();
        TrackingAudioGain audioGain = new TrackingAudioGain();
        AMDecoder decoder = new AMDecoder(config, audioGain);
        List<DecoderStateEvent> stateEvents = new ArrayList<>();
        List<float[]> audio = new ArrayList<>();
        Random random = new Random(0x5D47A11L);
        decoder.setDecoderStateListener(stateEvents::add);
        decoder.setBufferListener(audio::add);
        decoder.getSourceEventListener().receive(SourceEvent.sampleRateChange(50_000.0));
        int initialResetCount = audioGain.mResetCount;
        long timestamp = 0;

        timestamp = send(decoder, random, timestamp, 20, false);
        timestamp = send(decoder, random, timestamp, 20, true);
        timestamp = send(decoder, random, timestamp, 20, false);
        long startsAfterFirstCall = stateEvents.stream().filter(AMDecoderTest::isStart).count();
        long endsAfterFirstCall = stateEvents.stream().filter(AMDecoderTest::isEnd).count();
        timestamp = send(decoder, random, timestamp, 100, false);
        assertEquals(startsAfterFirstCall, stateEvents.stream().filter(AMDecoderTest::isStart).count(),
            "Idle noise must not start a segmented false call");
        assertEquals(endsAfterFirstCall, stateEvents.stream().filter(AMDecoderTest::isEnd).count(),
            "Idle noise must not end a segmented false call");
        timestamp = send(decoder, random, timestamp, 20, true);
        send(decoder, random, timestamp, 20, false);

        assertEquals(2, stateEvents.stream().filter(AMDecoderTest::isStart).count());
        assertEquals(2, stateEvents.stream().filter(AMDecoderTest::isEnd).count());
        assertEquals(initialResetCount + 2, audioGain.mResetCount, "AM gain must reset at each real call start");
        assertFalse(audio.isEmpty());
    }

    @Test
    void carrierFirstStartsAndEndsOneCallAtEverySupportedBandwidth()
    {
        for(AmMode mode: SUPPORTED_AM_MODES)
        {
            TrackingAudioGain audioGain = new TrackingAudioGain();
            DecodeConfigAM config = new DecodeConfigAM();
            config.setBandwidth(mode.bandwidth());
            AMDecoder decoder = new AMDecoder(config, audioGain);
            List<DecoderStateEvent> stateEvents = new ArrayList<>();
            List<float[]> audio = new ArrayList<>();
            decoder.setDecoderStateListener(stateEvents::add);
            decoder.setBufferListener(audio::add);
            decoder.getSourceEventListener().receive(SourceEvent.sampleRateChange(mode.sampleRate()));
            int initialResetCount = audioGain.mResetCount;

            long timestamp = send(decoder, new Random(1L), 0, 20, true, 0.95f, mode.sampleRate());
            send(decoder, new Random(2L), timestamp, 20, false, 0.0f, mode.sampleRate());

            assertEquals(1, stateEvents.stream().filter(AMDecoderTest::isStart).count(), mode.toString());
            assertEquals(1, stateEvents.stream().filter(AMDecoderTest::isEnd).count(), mode.toString());
            assertEquals(initialResetCount + 1, audioGain.mResetCount, mode.toString());
            assertFalse(audio.isEmpty(), mode.toString());
        }
    }

    @Test
    void idleFirstDoesNotFalseOpenAtEverySupportedBandwidth()
    {
        for(AmMode mode: SUPPORTED_AM_MODES)
        {
            DecodeConfigAM config = new DecodeConfigAM();
            config.setBandwidth(mode.bandwidth());
            AMDecoder decoder = new AMDecoder(config);
            List<DecoderStateEvent> stateEvents = new ArrayList<>();
            Random random = new Random(0xA11D1E5L);
            decoder.setDecoderStateListener(stateEvents::add);
            decoder.setBufferListener(audio -> { });
            decoder.getSourceEventListener().receive(SourceEvent.sampleRateChange(mode.sampleRate()));

            long timestamp = send(decoder, random, 0, 100, false, 0.0f, mode.sampleRate());
            assertEquals(0, stateEvents.stream().filter(AMDecoderTest::isStart).count(), mode.toString());
            assertEquals(0, stateEvents.stream().filter(AMDecoderTest::isEnd).count(), mode.toString());

            timestamp = send(decoder, random, timestamp, 20, true, 0.95f, mode.sampleRate());
            send(decoder, random, timestamp, 20, false, 0.0f, mode.sampleRate());
            assertEquals(1, stateEvents.stream().filter(AMDecoderTest::isStart).count(), mode.toString());
            assertEquals(1, stateEvents.stream().filter(AMDecoderTest::isEnd).count(), mode.toString());
        }
    }

    private static boolean isStart(DecoderStateEvent event)
    {
        return event.getEvent() == DecoderStateEvent.Event.START;
    }

    private static boolean isEnd(DecoderStateEvent event)
    {
        return event.getEvent() == DecoderStateEvent.Event.END;
    }

    private static long send(AMDecoder decoder, Random random, long timestamp, int buffers, boolean carrier)
    {
        return send(decoder, random, timestamp, buffers, carrier, 0.3f, 50_000.0);
    }

    private static long send(AMDecoder decoder, Random random, long timestamp, int buffers, boolean carrier,
                             float modulationDepth, double sampleRate)
    {
        double carrierPhase = 0.0;
        double audioPhase = 0.0;
        int bufferLength = (int)Math.round(sampleRate / 50.0);

        for(int buffer = 0; buffer < buffers; buffer++)
        {
            float[] i = new float[bufferLength];
            float[] q = new float[i.length];

            for(int x = 0; x < i.length; x++)
            {
                if(carrier)
                {
                    float envelope = 0.20f * (1.0f + modulationDepth * (float)Math.sin(audioPhase));
                    i[x] = envelope * (float)Math.cos(carrierPhase);
                    q[x] = envelope * (float)Math.sin(carrierPhase);
                    carrierPhase += 2.0 * Math.PI * 500.0 / sampleRate;
                    audioPhase += 2.0 * Math.PI * 1_000.0 / sampleRate;
                }
                else
                {
                    i[x] = (float)(random.nextGaussian() * 0.004);
                    q[x] = (float)(random.nextGaussian() * 0.004);
                }
            }

            decoder.receive(new ComplexSamples(i, q, timestamp));
            timestamp += 20;
        }

        return timestamp;
    }

    private record AmMode(Bandwidth bandwidth, double sampleRate)
    {
    }

    private static class TrackingAudioGain extends AudioGainAndDcFilter
    {
        private int mResetCount;

        private TrackingAudioGain()
        {
            super(0.5f, 16.0f, 0.75f);
        }

        @Override
        public void reset()
        {
            super.reset();
            mResetCount++;
        }
    }
}
