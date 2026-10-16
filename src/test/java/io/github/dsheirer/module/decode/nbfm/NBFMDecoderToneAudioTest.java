/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.module.decode.nbfm;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog.Bandwidth;
import io.github.dsheirer.source.SourceEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NBFMDecoderToneAudioTest
{
    @Test
    void rawToneTapPrecedesDeemphasisAndGainAtEveryDemodulatedRate()
    {
        for(double rate: new double[] {25_000.0, 50_000.0})
        {
            DecodeConfigNBFM config = new DecodeConfigNBFM();
            config.setBandwidth(rate == 25_000.0 ? Bandwidth.BW_12_5 : Bandwidth.BW_25_0);
            config.setDeemphasis(DecodeConfigNBFM.DeemphasisMode.US_750US);
            config.setAudioFilter(false);
            config.setLowPassEnabled(false);
            config.setVoiceEnhanceAmount(0.0f);
            config.setBassBoostDb(0.0f);
            config.setOutputGain(2.0f);
            OutputDecoder decoder = new OutputDecoder(config);
            decoder.getSourceEventListener().receive(SourceEvent.sampleRateChange(rate));
            List<float[]> raw = new ArrayList<>();
            List<float[]> output = new ArrayList<>();
            decoder.setRawAudioBufferListener(samples -> raw.add(samples.clone()));
            decoder.setBufferListener(samples -> output.add(samples.clone()));
            for(int buffer = 0; buffer < 12; buffer++)
            {
                float[] audio = sine(buffer * 512, 512);
                float[] expected = audio.clone();
                decoder.output(audio);
                assertArrayEquals(expected, raw.getLast(), "Tone input must retain the original waveform");
                assertEquals(raw.getLast().length, output.getLast().length);
            }
            double alpha = (1.0 / 8000.0) / (750.0e-6 + 1.0 / 8000.0);
            double omega = 2.0 * Math.PI * 1000.0 / 8000.0;
            double expectedGain = 2.0 * alpha / Math.sqrt(1.0 + Math.pow(1.0 - alpha, 2) -
                2.0 * (1.0 - alpha) * Math.cos(omega));
            assertEquals(expectedGain, rms(output.getLast()) / rms(raw.getLast()), 0.001,
                "The selected 750 us response must use the 8 kHz audio rate at source rate " + rate);
        }
    }

    @Test
    void registeringToneTapDoesNotChangeListeningAudio()
    {
        OutputDecoder plain = new OutputDecoder(new DecodeConfigNBFM());
        OutputDecoder tapped = new OutputDecoder(new DecodeConfigNBFM());
        plain.getSourceEventListener().receive(SourceEvent.sampleRateChange(25_000.0));
        tapped.getSourceEventListener().receive(SourceEvent.sampleRateChange(25_000.0));
        List<float[]> expected = new ArrayList<>();
        List<float[]> actual = new ArrayList<>();
        plain.setBufferListener(samples -> expected.add(samples.clone()));
        tapped.setRawAudioBufferListener(samples -> samples.clone());
        tapped.setBufferListener(samples -> actual.add(samples.clone()));
        for(int buffer = 0; buffer < 6; buffer++)
        {
            plain.output(sine(buffer * 512, 512));
            tapped.output(sine(buffer * 512, 512));
            assertArrayEquals(expected.getLast(), actual.getLast());
        }
    }

    private static float[] sine(int start, int count)
    {
        float[] audio = new float[count];
        for(int x = 0; x < count; x++) audio[x] = (float)(0.2 * Math.sin(2.0 * Math.PI * 1000 * (start + x) / 8000.0));
        return audio;
    }

    private static double rms(float[] audio)
    {
        double sum = 0.0;
        for(float sample: audio) sum += sample * sample;
        return Math.sqrt(sum / audio.length);
    }

    private static final class OutputDecoder extends NBFMDecoder
    {
        private OutputDecoder(DecodeConfigNBFM config) { super(config); }
        private void output(float[] samples) { broadcast(samples); }
    }
}
