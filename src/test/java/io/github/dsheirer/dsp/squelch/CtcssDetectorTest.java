/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.dsp.squelch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CtcssDetectorTest
{
    @Test
    void qualifiesEveryStandardToneAtActualAnalogSampleRatesWithArbitraryBuffers()
    {
        for(double rate: new double[] {8_000.0, 25_000.0, 50_000.0})
        {
            for(double frequency: CtcssFrequencies.STANDARD)
            {
                CtcssDetector detector = new CtcssDetector(frequency);
                detector.setSampleRate(rate);
                feed(detector, rate, frequency, 0.9, 317, true);
                assertTrue(detector.isQualified(), frequency + " Hz at " + rate);
                detector.reset();
                assertFalse(detector.isQualified());
                detector.setSampleRate(rate);
                assertFalse(detector.isQualified(), "Sample-rate changes must discard prior qualification");
            }
        }
    }

    @Test
    void rejectsNeighboringStandardTonesAndClosesAfterToneLoss()
    {
        for(double frequency: new double[] {67.0, 100.0, 159.8, 254.1})
        {
            int index = CtcssFrequencies.STANDARD.indexOf(frequency);
            double wrong = CtcssFrequencies.STANDARD.get(index == 0 ? index + 1 : index - 1);
            CtcssDetector detector = new CtcssDetector(frequency);
            detector.setSampleRate(25_000.0);
            feed(detector, 25_000.0, wrong, 1.0, 257, false);
            assertFalse(detector.isQualified(), "Must reject adjacent tone " + wrong + " for " + frequency);
            feed(detector, 25_000.0, frequency, 0.8, 257, false);
            assertTrue(detector.isQualified());
            feed(detector, 25_000.0, 0.0, 0.6, 257, true);
            assertFalse(detector.isQualified(), "Voice without the receive tone must close squelch");
        }
    }

    @Test
    void varyingPitchHarmonicSpeechDoesNotQualifyAsTheSelectedReceiveTone()
    {
        double rate = 25_000.0;
        CtcssDetector detector = new CtcssDetector(100.0);
        detector.setSampleRate(rate);
        double phase = 0.0;
        Random random = new Random(547L);
        int count = (int)(rate * 3.0);
        for(int start = 0; start < count; start += 317)
        {
            float[] speech = new float[Math.min(317, count - start)];
            for(int x = 0; x < speech.length; x++)
            {
                double time = (start + x) / rate;
                double pitch = 125.0 + 35.0 * Math.sin(2.0 * Math.PI * 1.1 * time) +
                    8.0 * Math.sin(2.0 * Math.PI * 4.7 * time);
                phase += 2.0 * Math.PI * pitch / rate;
                double syllable = 0.55 + 0.45 * Math.pow(Math.sin(2.0 * Math.PI * 2.3 * time), 2);
                speech[x] = (float)(0.2 + syllable * (0.2 * Math.sin(phase) +
                    0.1 * Math.sin(2.0 * phase) + 0.06 * Math.sin(3.0 * phase)) + random.nextGaussian() * 0.002);
            }
            detector.process(speech);
            assertFalse(detector.isQualified(), "Changing voiced pitch and harmonics must not open 100 Hz tone squelch");
        }
    }

    @Test
    void rejectsInvalidSettings()
    {
        assertThrows(IllegalArgumentException.class, () -> new CtcssDetector(0.0));
        assertThrows(IllegalArgumentException.class, () -> new CtcssDetector(99.0));
        assertThrows(IllegalArgumentException.class, () -> new CtcssDetector(Double.NaN));
    }

    private static void feed(CtcssDetector detector, double rate, double frequency, double seconds, int bufferSize,
                             boolean voice)
    {
        int count = (int)(rate * seconds);
        Random random = new Random(1234L);
        for(int start = 0; start < count; start += bufferSize)
        {
            float[] audio = new float[Math.min(bufferSize, count - start)];
            for(int x = 0; x < audio.length; x++)
            {
                double time = (start + x) / rate;
                double signal = frequency > 0.0 ? 0.1 * Math.sin(2.0 * Math.PI * frequency * time) : 0.0;
                if(voice)
                {
                    signal += 0.25 * Math.sin(2.0 * Math.PI * 870.0 * time) + random.nextGaussian() * 0.004;
                }
                audio[x] = (float)(0.2 + signal); //AM's demodulated carrier/DC must not look like a tone.
            }
            detector.process(audio);
        }
    }
}
