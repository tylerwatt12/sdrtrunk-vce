/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.tone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalogPagingToneDetectorTest
{
    private final P25PagingToneDetector mDetector = new P25PagingToneDetector();

    @Test void pagingHarmonicDoesNotHideTheFirstAnalogToneOrChangeP25Qualification()
    {
        float[] samples = new float[40_000];
        tone(samples,1600,8000,382.3,.30,.3);
        tone(samples,1600,8000,764.6,.085,1.1);
        tone(samples,9600,24_000,1232,.30,.7);
        var analog = detect(samples);
        assertEquals(List.of(382.3,1232.),analog.stream().map(P25PagingToneDetector.DetectedTone::frequencyHz).toList());
        assertTrue(analog.getFirst().durationMilliseconds() >= 850);
        assertTrue(analog.getLast().durationMilliseconds() >= 2800);
        assertTrue(analog.stream().allMatch(tone -> tone.method().equals("analog_pcm_tone_v1")));
        assertEquals(List.of(1218.75),mDetector.detect(blocks(samples,160)).stream()
            .map(P25PagingToneDetector.DetectedTone::frequencyHz).toList(),"P25 retains its original spectral gates");
    }

    @Test void analogNoiseDoesNotFragmentAContinuousLowPagingTone()
    {
        float[] samples = new float[40_000];
        tone(samples,1600,8000,510.5,.30,.3);
        tone(samples,9600,24_000,330.5,.30,.7);
        tone(samples,9600,24_000,661,.06,1.1);
        noise(samples,9600,24_000,.11,57553);
        var tones = detect(samples);
        assertEquals(List.of(510.5,330.5),tones.stream().map(P25PagingToneDetector.DetectedTone::frequencyHz).toList());
        assertTrue(tones.getLast().durationMilliseconds() >= 2800);
        assertTrue(mDetector.detect(blocks(samples,160)).stream().noneMatch(tone -> tone.frequencyHz() < 400),
            "The analog tolerance must not change the digital detector");
        assertEquals(tones,mDetector.detectAnalog(AnalogToneAudioFilter.filter(blocks(samples,73))),
            "Buffer boundaries do not change the continuous sample clock");
    }

    @Test void speechLikeHarmonicsBalancedPairsAndBroadbandNoiseRemainExcluded()
    {
        float[] noise = new float[16_000];
        noise(noise,0,noise.length,.15,51);
        assertTrue(detect(noise).isEmpty());

        float[] harmonics = new float[16_000];
        tone(harmonics,0,harmonics.length,400,.08,.3);
        tone(harmonics,0,harmonics.length,800,.06,1.2);
        tone(harmonics,0,harmonics.length,1200,.05,2.1);
        assertTrue(detect(harmonics).isEmpty());

        float[] pair = new float[16_000];
        tone(pair,0,pair.length,510.5,.12,.3);
        tone(pair,0,pair.length,1232,.12,1.2);
        assertTrue(detect(pair).isEmpty(),"Two comparable spectral peaks are not a single paging tone");

        float[] strongHarmonic = new float[16_000];
        tone(strongHarmonic,0,strongHarmonic.length,400,.30,.3);
        tone(strongHarmonic,0,strongHarmonic.length,800,.15,1.2);
        assertTrue(detect(strongHarmonic).isEmpty(),"Dominance remains required even when concentration is adequate");
    }

    @Test void clippedAnalogPagingKeepsTwoSustainedFrequencies()
    {
        float[] samples = new float[40_000];
        tone(samples,1600,8000,510.5,.30,.3);
        tone(samples,9600,24_000,330.5,.30,.7);
        for(int index=0;index<samples.length;index++)samples[index]=Math.max(-.1f,Math.min(.1f,samples[index]));
        var tones = detect(samples);
        assertEquals(List.of(510.5,330.5),tones.stream().map(P25PagingToneDetector.DetectedTone::frequencyHz).toList());
        assertTrue(tones.getFirst().durationMilliseconds()>=850);
        assertTrue(tones.getLast().durationMilliseconds()>=2800);
    }

    @Test void analogToleranceDoesNotBorrowTimeAcrossShortHitsPitchChangesOrSilence()
    {
        float[] shortHits = new float[12_000];
        tone(shortHits,800,800,800,.1,0);
        tone(shortHits,4000,1520,800,.1,0);
        tone(shortHits,7200,800,800,.1,0);
        assertTrue(detect(shortHits).isEmpty());
        float[] alternating = new float[12_800];
        for(int index=0;index<10;index++)tone(alternating,index*1280,1280,index%2==0?804:820,.1,.2);
        assertTrue(detect(alternating).isEmpty());

        float[] separated = new float[12_000];
        tone(separated,0,4000,382.3,.2,0);
        tone(separated,4800,4000,382.3,.2,0);
        var tones = detect(separated);
        assertEquals(2,tones.size());
        assertTrue(tones.getFirst().offsetMilliseconds()+tones.getFirst().durationMilliseconds()<
            tones.getLast().offsetMilliseconds());
    }

    @Test void ctcssAndNonfiniteInputRemainOutsideAnalogEvidence()
    {
        float[] ctcss = new float[8000];
        tone(ctcss,0,ctcss.length,254.1,.3,.2);
        assertTrue(detect(ctcss).stream().noneMatch(tone -> tone.frequencyHz()>=281.25));
        for(float value: new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
        {
            float[] invalid = new float[8000];
            tone(invalid,0,invalid.length,382.3,.3,.2);
            invalid[invalid.length/2] = value;
            assertTrue(mDetector.detectAnalog(blocks(invalid,237)).isEmpty());
        }
    }

    private List<P25PagingToneDetector.DetectedTone> detect(float[] samples)
    {
        return mDetector.detectAnalog(AnalogToneAudioFilter.filter(blocks(samples,237)));
    }

    private static void tone(float[] samples,int offset,int length,double frequency,double amplitude,double phase)
    {
        for(int index=0;index<length;index++)samples[offset+index]+=(float)(amplitude*Math.sin(
            phase+2*Math.PI*frequency*index/P25PagingToneDetector.SAMPLE_RATE));
    }

    private static void noise(float[] samples,int offset,int length,double rms,long seed)
    {
        Random random = new Random(seed);
        for(int index=0;index<length;index++)samples[offset+index]+=(float)(random.nextGaussian()*rms);
    }

    private static List<float[]> blocks(float[] samples,int count)
    {
        List<float[]> result = new ArrayList<>();
        for(int start=0;start<samples.length;start+=count)
            result.add(Arrays.copyOfRange(samples,start,Math.min(samples.length,start+count)));
        return result;
    }
}
