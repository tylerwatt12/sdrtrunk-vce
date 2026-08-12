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
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AMDecoderTest
{
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
}
