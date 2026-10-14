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

import java.util.ArrayList;
import java.util.List;
import org.jtransforms.fft.FloatFFT_1D;

/**
 * Conservative sustained single-tone analysis of raw 8 kHz decoded PCM, before ML or presentation gain. Run only on a
 * completed-call worker. This is inferred audio evidence, not an IMBE tone frame or identification of a paging system.
 *
 * Examines 128 ms Hann windows every 20 ms. A candidate must have 90% of its spectral power within five bins of one
 * 200-3000 Hz peak, at least 12 dB separation from any other peak, energy in every 32 ms quarter-window, and continuous
 * frequency within 4 Hz for at least 200 ms of qualified window centers. Qualified tone reports use the nearest
 * allowed AMBE single-tone frequency. Continuity still uses the unsnapped spectral estimates, so different pitches
 * in the same nominal band cannot borrow each other's qualification time. Short gaps are not bridged. Offsets/durations
 * describe the qualified
 * centers, with window-limited onset accuracy; short or distorted real tones may be missed. Confidence is an evidence
 * score, not a calibrated probability. Pure sustained whistles or nearly sinusoidal speech can also qualify.
 *
 * Bounds: 120 seconds, 6000 input buffers and 32 tones. Invalid, oversized or non-finite input returns no evidence.
 * Synthetic tests cover the signal rules; accuracy on labeled real paging captures remains unvalidated.
 */
public final class P25PagingToneDetector
{
    public static final int SAMPLE_RATE = 8000;
    public static final int MAXIMUM_SAMPLES = SAMPLE_RATE * 120;
    public static final int MAXIMUM_TONES = 32;
    private static final int MAXIMUM_BUFFERS = 6000;
    private static final int WINDOW_SIZE = 1024;
    private static final int HOP_SIZE = 160;
    private static final int MINIMUM_QUALIFIED_SAMPLES = SAMPLE_RATE / 5;
    private static final double MINIMUM_FREQUENCY_HZ = 200.0;
    private static final double MAXIMUM_FREQUENCY_HZ = 3000.0;
    private static final double FREQUENCY_TOLERANCE_HZ = 4.0;
    private static final double MINIMUM_RMS = 0.002;
    private static final double MINIMUM_NARROWBAND_FRACTION = 0.90;
    private static final double MINIMUM_PEAK_RATIO = 15.848931924611133; //12 dB in power

    public record DetectedTone(double frequencyHz, long offsetMilliseconds, long durationMilliseconds,
                               double confidence, String source, String method)
    {
        public DetectedTone
        {
            if(!Double.isFinite(frequencyHz) || frequencyHz < 156.25 ||
                frequencyHz > 3812.5 || offsetMilliseconds < 0 || durationMilliseconds < 200 ||
                !Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0)
            {
                throw new IllegalArgumentException("Invalid detected tone evidence");
            }

            frequencyHz = AmbeToneFrequencyMapping.nearest((int)Math.round(frequencyHz * 1000))
                .orElseThrow(() -> new IllegalArgumentException("Tone frequency is outside the AMBE table")).frequencyHz();
            source = "decoded_audio";
            method = "p25_pcm_ambe_tone_v2";
        }
    }

    /** Detects raw PCM tones on the concatenated sample clock, independent of decoded-frame buffer boundaries. */
    public List<DetectedTone> detect(List<float[]> rawAudioBuffers)
    {
        if(rawAudioBuffers == null || rawAudioBuffers.size() > MAXIMUM_BUFFERS)
        {
            return List.of();
        }

        int length = 0;

        for(float[] buffer: rawAudioBuffers)
        {
            if(buffer == null || buffer.length > MAXIMUM_SAMPLES - length)
            {
                return List.of();
            }

            length += buffer.length;
        }

        if(length < WINDOW_SIZE)
        {
            return List.of();
        }

        float[] samples = new float[length];
        int offset = 0;

        for(float[] buffer: rawAudioBuffers)
        {
            for(float sample: buffer)
            {
                if(!Float.isFinite(sample))
                {
                    return List.of();
                }

                samples[offset++] = sample;
            }
        }

        FloatFFT_1D fft = new FloatFFT_1D(WINDOW_SIZE);
        float[] spectrum = new float[WINDOW_SIZE * 2];
        double[] power = new double[WINDOW_SIZE / 2 + 1];
        double[] window = new double[WINDOW_SIZE];

        for(int index = 0; index < window.length; index++)
        {
            window[index] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * index / (WINDOW_SIZE - 1));
        }

        List<DetectedTone> tones = new ArrayList<>();
        Run run = null;

        for(int start = 0; start <= samples.length - WINDOW_SIZE; start += HOP_SIZE)
        {
            Candidate candidate = analyze(samples, start, window, spectrum, power, fft);
            int center = start + WINDOW_SIZE / 2;

            if(candidate != null && run != null &&
                Math.abs(candidate.frequencyHz() - run.frequencyHz()) <= FREQUENCY_TOLERANCE_HZ)
            {
                run.add(candidate, center);
            }
            else
            {
                if(run != null)
                {
                    qualify(run, tones);

                    if(tones.size() > MAXIMUM_TONES)
                    {
                        throw new IllegalArgumentException("Incomplete tone sequence exceeds bound");
                    }
                }

                run = candidate != null ? new Run(candidate, center) : null;
            }
        }

        if(run != null)
        {
            qualify(run, tones);
        }

        if(tones.size() > MAXIMUM_TONES) throw new IllegalArgumentException("Incomplete tone sequence exceeds bound");
        return List.copyOf(tones);
    }

    private Candidate analyze(float[] samples, int start, double[] window, float[] spectrum, double[] power,
                               FloatFFT_1D fft)
    {
        double mean = 0.0;

        for(int index = 0; index < WINDOW_SIZE; index++)
        {
            mean += samples[start + index];
        }

        mean /= WINDOW_SIZE;
        double energy = 0.0;
        double[] quarterEnergy = new double[4];

        for(int index = 0; index < WINDOW_SIZE; index++)
        {
            double sample = samples[start + index] - mean;
            energy += sample * sample;
            quarterEnergy[index / (WINDOW_SIZE / 4)] += sample * sample;
            spectrum[index] = (float)(sample * window[index]);
        }

        if(Math.sqrt(energy / WINDOW_SIZE) < MINIMUM_RMS)
        {
            return null;
        }

        double minimumQuarterEnergy = Double.POSITIVE_INFINITY;
        double maximumQuarterEnergy = 0.0;
        for(double quarter: quarterEnergy)
        {
            minimumQuarterEnergy = Math.min(minimumQuarterEnergy, quarter);
            maximumQuarterEnergy = Math.max(maximumQuarterEnergy, quarter);
        }
        //Every quarter must retain at least 65% of the strongest quarter's RMS. A short burst cannot borrow the
        //FFT window's duration or bridge a silent interruption just because its spectral peak is dominant.
        if(minimumQuarterEnergy < maximumQuarterEnergy * 0.65 * 0.65) return null;

        fft.realForwardFull(spectrum);
        double totalPower = 0.0;
        int peakBin = 1;

        for(int bin = 1; bin < power.length; bin++)
        {
            double real = spectrum[bin * 2];
            double imaginary = spectrum[bin * 2 + 1];
            power[bin] = real * real + imaginary * imaginary;
            totalPower += power[bin];

            if(power[bin] > power[peakBin])
            {
                peakBin = bin;
            }
        }

        if(totalPower <= 0.0 || peakBin <= 1 || peakBin >= power.length - 1)
        {
            return null;
        }

        double narrowbandPower = 0.0;
        double secondPeak = 0.0;

        for(int bin = 1; bin < power.length; bin++)
        {
            if(Math.abs(bin - peakBin) <= 2)
            {
                narrowbandPower += power[bin];
            }
            else if(Math.abs(bin - peakBin) > 3)
            {
                secondPeak = Math.max(secondPeak, power[bin]);
            }
        }

        double fraction = narrowbandPower / totalPower;

        if(fraction < MINIMUM_NARROWBAND_FRACTION || power[peakBin] < secondPeak * MINIMUM_PEAK_RATIO)
        {
            return null;
        }

        double left = Math.log(Math.max(power[peakBin - 1], 1.0e-30));
        double middle = Math.log(Math.max(power[peakBin], 1.0e-30));
        double right = Math.log(Math.max(power[peakBin + 1], 1.0e-30));
        double denominator = left - 2.0 * middle + right;
        double interpolatedBin = peakBin + (denominator != 0.0 ?
            Math.max(-0.5, Math.min(0.5, 0.5 * (left - right) / denominator)) : 0.0);
        double frequency = interpolatedBin * SAMPLE_RATE / WINDOW_SIZE;

        if(!Double.isFinite(frequency) || frequency < MINIMUM_FREQUENCY_HZ || frequency > MAXIMUM_FREQUENCY_HZ)
        {
            return null;
        }

        return new Candidate(frequency, Math.min(fraction, 1.0 - secondPeak / power[peakBin]));
    }

    private void qualify(Run run, List<DetectedTone> tones)
    {
        int qualifiedSamples = run.lastCenter - run.firstCenter + HOP_SIZE;

        if(qualifiedSamples >= MINIMUM_QUALIFIED_SAMPLES)
        {
            tones.add(new DetectedTone(run.frequencyHz(),
                (run.firstCenter - HOP_SIZE / 2) * 1000L / SAMPLE_RATE,
                qualifiedSamples * 1000L / SAMPLE_RATE, run.confidenceSum / run.count,
                "decoded_audio", "p25_pcm_ambe_tone_v2"));
        }
    }

    private record Candidate(double frequencyHz, double confidence) {}

    private static final class Run
    {
        private final int firstCenter;
        private int lastCenter;
        private int count;
        private double frequencySum;
        private double confidenceSum;

        private Run(Candidate candidate, int center)
        {
            firstCenter = center;
            add(candidate, center);
        }

        private void add(Candidate candidate, int center)
        {
            lastCenter = center;
            count++;
            frequencySum += candidate.frequencyHz();
            confidenceSum += candidate.confidence();
        }

        private double frequencyHz()
        {
            return frequencySum / count;
        }
    }
}
