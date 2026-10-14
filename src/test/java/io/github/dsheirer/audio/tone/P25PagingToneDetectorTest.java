/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.audio.tone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class P25PagingToneDetectorTest
{
    @Test
    void messageFrequencyKeepsTheExactAllowedQuarterHertzValue()
    {
        var tone = new P25PagingToneDetector.DetectedTone(343.183, 514, 900, .99,
            "decoded_audio", "p25_pcm_ambe_tone_v2");
        assertEquals("343.75 Hz (900 ms)", new io.github.dsheirer.identifier.tone.DetectedPagingToneIdentifier(
            List.of(tone)).toString());
    }

    private final P25PagingToneDetector mDetector = new P25PagingToneDetector();

    @Test
    void detectsTwoSustainedPagingLikeTonesAndLabelsInferredEvidence()
    {
        float[] samples = new float[16000];
        tone(samples, 1600, 4000, 600.0, 0.1, 0.7);
        tone(samples, 5600, 5600, 880.0, 0.1, 1.4);
        var result = mDetector.detect(frames(samples, 160));

        assertEquals(2, result.size());
        assertEquals(593.75, result.get(0).frequencyHz());
        assertEquals(875.0, result.get(1).frequencyHz());
        assertTrue(result.get(0).offsetMilliseconds() >= 200);
        assertTrue(result.get(1).offsetMilliseconds() >= 700);
        assertTrue(result.get(0).durationMilliseconds() >= 200);
        assertTrue(result.get(1).durationMilliseconds() >= 200);
        assertEquals("decoded_audio", result.get(0).source());
        assertEquals("p25_pcm_ambe_tone_v2", result.get(0).method());
        assertTrue(result.stream().allMatch(value -> value.confidence() >= 0.90));
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void reportsOffGridInputAtTheNearestAmbeNominalAfterQualification()
    {
        float[] samples = new float[8000];
        tone(samples, 0, samples.length, 731.37, 0.04, 2.1);
        var result = mDetector.detect(List.of(samples));

        assertEquals(1, result.size());
        assertEquals(718.75, result.getFirst().frequencyHz());
        assertTrue(result.getFirst().durationMilliseconds() >= 800);
    }

    @Test
    void sustainedToneSweepReportsOnlyExpectedAmbeNominals()
    {
        double[][] frequencies = {{200.5,187.5},{205,218.75},{218.75,218.75},{343.183,343.75},
            {387.660,375},{435.251,437.5},{566.379,562.5},{598.065,593.75},{731.37,718.75},
            {800,812.5},{914.305,906.25},{1003.41,1000},{2687.5,2687.5},{2718.75,2718.75},{2999,3000}};
        for(double[] frequency: frequencies)
        {
            float[] samples = new float[8000];
            tone(samples, 0, samples.length, frequency[0], 0.1, 0.3);
            var result = mDetector.detect(frames(samples, 160));
            assertEquals(1, result.size(), "Raw input " + frequency[0]);
            assertEquals(frequency[1], result.getFirst().frequencyHz(), "Raw input " + frequency[0]);
            assertEquals("p25_pcm_ambe_tone_v2", result.getFirst().method());
        }
    }

    @Test
    void differentRawPitchesInOneNominalBandRemainSeparateAndCannotBorrowQualificationTime()
    {
        //Both pitches map to 812.5 Hz, but their 16 Hz separation exceeds the raw 4 Hz continuity guard.
        float[] qualified = new float[8000];
        tone(qualified, 0, 4000, 804, 0.1, 0.2);
        tone(qualified, 4000, 4000, 820, 0.1, 0.7);
        var separate = mDetector.detect(frames(qualified, 160));
        assertEquals(2, separate.size());
        assertTrue(separate.stream().allMatch(value -> value.frequencyHz() == 812.5));
        assertTrue(separate.getFirst().offsetMilliseconds() + separate.getFirst().durationMilliseconds() <=
            separate.getLast().offsetMilliseconds());

        float[] shortAlternating = new float[12800];
        for(int index = 0; index < 10; index++)
            tone(shortAlternating, index * 1280, 1280, index % 2 == 0 ? 804 : 820, 0.1, 0.2);
        assertTrue(mDetector.detect(frames(shortAlternating, 160)).isEmpty(),
            "Ten 160 ms pitches may not form one nominal-band qualification run");
    }

    @Test
    void reportedToneConstructorAcceptsFullNominalRangeAndRejectsOutsideBeforeRounding()
    {
        assertEquals(156.25, reported(156.25).frequencyHz());
        assertEquals(3812.5, reported(3812.5).frequencyHz());
        assertEquals(562.5, reported(578.125).frequencyHz());
        assertEquals(593.75, reported(578.126).frequencyHz());
        assertEquals("decoded_audio", reported(800).source());
        assertEquals("p25_pcm_ambe_tone_v2", reported(800).method());
        for(double invalid: new double[]{156.2499,3812.5001,0,-1,Double.NaN,
            Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> reported(invalid));
    }

    @Test
    void preservesPhaseAndSampleClockAcrossDifferentFrameBoundaries()
    {
        float[] samples = new float[9600];
        tone(samples, 0, samples.length, 1003.41, 0.1, 2.9);
        float[] original = samples.clone();
        var expected = mDetector.detect(List.of(samples));

        assertEquals(1, expected.size());
        assertEquals(1000.0, expected.getFirst().frequencyHz());
        assertEquals(expected, mDetector.detect(frames(samples, 160)));
        assertEquals(expected, mDetector.detect(frames(samples, 73)));
        assertTrue(Arrays.equals(original, samples));
    }

    @Test
    void rejectsBroadbandNoiseAndSpeechLikeHarmonics()
    {
        float[] noise = new float[16000];
        Random random = new Random(51);

        for(int index = 0; index < noise.length; index++)
        {
            noise[index] = (float)(random.nextGaussian() * 0.05);
        }

        assertTrue(mDetector.detect(frames(noise, 160)).isEmpty());
        float[] harmonics = new float[16000];
        tone(harmonics, 0, harmonics.length, 400, 0.08, 0.3);
        tone(harmonics, 0, harmonics.length, 800, 0.06, 1.2);
        tone(harmonics, 0, harmonics.length, 1200, 0.05, 2.1);
        assertTrue(mDetector.detect(frames(harmonics, 160)).isEmpty());
    }

    @Test
    void rejectsShortHitsAndDoesNotBridgeSilenceGaps()
    {
        float[] samples = new float[12000];
        tone(samples, 800, 800, 800, 0.1, 0.0);
        tone(samples, 4000, 1520, 800, 0.1, 0.0);
        tone(samples, 7200, 800, 800, 0.1, 0.0);
        assertTrue(mDetector.detect(frames(samples, 160)).isEmpty());
    }

    @Test
    void rejectsChangingFrequencyAndUnsupportedBands()
    {
        float[] chirp = new float[8000];

        for(int index = 0; index < chirp.length; index++)
        {
            double seconds = index / 8000.0;
            chirp[index] = (float)(0.1 * Math.sin(2.0 * Math.PI * (600 * seconds + 300 * seconds * seconds)));
        }

        assertTrue(mDetector.detect(List.of(chirp)).isEmpty());

        for(double frequency: new double[]{100, 3600})
        {
            float[] samples = new float[8000];
            tone(samples, 0, samples.length, frequency, 0.1, 0.0);
            assertTrue(mDetector.detect(List.of(samples)).isEmpty());
        }
    }

    @Test
    void rejectsSilenceNonFiniteAndOversizedInput()
    {
        assertTrue(mDetector.detect(List.of(new float[8000])).isEmpty());
        assertTrue(mDetector.detect(List.of()).isEmpty());
        assertTrue(mDetector.detect(null).isEmpty());
        assertTrue(mDetector.detect(List.of(new float[P25PagingToneDetector.MAXIMUM_SAMPLES + 1])).isEmpty());

        for(float invalid: new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY})
        {
            float[] samples = new float[8000];
            tone(samples, 0, samples.length, 800, 0.1, 0.0);
            samples[samples.length - 1] = invalid;
            assertTrue(mDetector.detect(List.of(samples)).isEmpty());
        }
    }

    @Test
    void suppressesWholeIncompleteSequenceAboveThirtyTwoTones()
    {
        float[] samples = new float[40 * 4400];

        for(int index = 0; index < 40; index++)
        {
            tone(samples, index * 4400, 3200, index % 2 == 0 ? 600 : 900, 0.1, 0.2);
        }

        assertThrows(IllegalArgumentException.class, () -> mDetector.detect(frames(samples, 160)));
    }

    private static void tone(float[] samples, int offset, int length, double frequency, double amplitude, double phase)
    {
        for(int index = 0; index < length; index++)
        {
            samples[offset + index] += (float)(amplitude * Math.sin(phase + 2.0 * Math.PI * frequency * index / 8000));
        }
    }

    private static P25PagingToneDetector.DetectedTone reported(double frequency)
    {
        return new P25PagingToneDetector.DetectedTone(frequency, 0, 200, 0.99, "ignored", "ignored");
    }

    private static List<float[]> frames(float[] samples, int frameLength)
    {
        List<float[]> result = new ArrayList<>();

        for(int index = 0; index < samples.length; index += frameLength)
        {
            result.add(Arrays.copyOfRange(samples, index, Math.min(samples.length, index + frameLength)));
        }

        return result;
    }
}
