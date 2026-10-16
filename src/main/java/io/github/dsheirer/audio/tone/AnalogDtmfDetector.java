/* Copyright (C) 2026. Licensed under the GNU General Public License, version 3 or later. */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.identifier.tone.AmbeTone;
import java.util.ArrayList;
import java.util.List;

/**
 * Worker-only waveform DTMF analysis of bounded 8 kHz analog audio. Each 50 ms window must contain two dominant
 * standard DTMF frequencies (within 1.5%), balanced within 6 dB, with at least 80% of its energy in that pair.
 * At least 50 ms of consecutive qualified window centers is required. A rejected window ends the run, so a missing
 * digit cannot be manufactured by bridging a squelch gap. No low-frequency/CTCSS evidence is produced here.
 */
public final class AnalogDtmfDetector
{
    private static final int WINDOW = 400;
    private static final int HOP = 80;
    private static final int MINIMUM_QUALIFIED_SAMPLES = 400;
    private static final int[] FREQUENCIES = {697,770,852,941,1209,1336,1477,1633};
    private static final String[][] SYMBOLS = {{"1","2","3","A"},{"4","5","6","B"},
        {"7","8","9","C"},{"STAR","0","POUND","D"}};
    private static final double[][] COEFFICIENTS = new double[FREQUENCIES.length][5];
    static
    {
        for(int frequency = 0; frequency < FREQUENCIES.length; frequency++)
            for(int offset = 0; offset < 5; offset++)
                COEFFICIENTS[frequency][offset] = 2 * Math.cos(2 * Math.PI * FREQUENCIES[frequency] *
                    (1 + (offset - 2) * .0075) / P25PagingToneDetector.SAMPLE_RATE);
    }

    public List<CompletedToneSequence.Element> detect(List<float[]> buffers)
    {
        if(buffers == null || buffers.size() > 6000) return List.of();
        int length = 0;
        for(float[] buffer: buffers)
        {
            if(buffer == null || buffer.length > P25PagingToneDetector.MAXIMUM_SAMPLES - length) return List.of();
            length += buffer.length;
        }
        if(length < WINDOW) return List.of();
        float[] samples = new float[length];
        int index = 0;
        for(float[] buffer: buffers)
            for(float sample: buffer)
            {
                if(!Float.isFinite(sample)) return List.of();
                samples[index++] = sample;
            }
        List<CompletedToneSequence.Element> result = new ArrayList<>();
        Run run = null;
        for(int start = 0; start <= length - WINDOW; start += HOP)
        {
            Candidate candidate = analyze(samples, start);
            if(candidate != null && run != null && candidate.tone == run.tone)
                run.add(start, candidate.confidence);
            else
            {
                qualify(run, result);
                run = candidate != null ? new Run(candidate.tone, start, candidate.confidence) : null;
            }
        }
        qualify(run, result);
        return List.copyOf(result);
    }

    private Candidate analyze(float[] samples, int start)
    {
        double mean = 0;
        for(int sample = start; sample < start + WINDOW; sample++) mean += samples[sample];
        mean /= WINDOW;
        double energy = 0;
        double[] quarterEnergy = new double[4];
        double[] values = new double[WINDOW];
        for(int sample = 0; sample < WINDOW; sample++)
        {
            double value = samples[start + sample] - mean;
            values[sample] = value;
            energy += value * value;
            quarterEnergy[sample / (WINDOW / 4)] += value * value;
        }
        if(energy < WINDOW * .002 * .002) return null;
        double minimumQuarter = Double.POSITIVE_INFINITY, maximumQuarter = 0;
        for(double quarter: quarterEnergy)
        {
            minimumQuarter = Math.min(minimumQuarter, quarter);
            maximumQuarter = Math.max(maximumQuarter, quarter);
        }
        if(minimumQuarter < maximumQuarter * .4) return null;
        double[] powers = new double[FREQUENCIES.length];
        for(int frequency = 0; frequency < powers.length; frequency++)
            for(double coefficient: COEFFICIENTS[frequency])
            {
                double previous = 0, older = 0;
                for(double value: values)
                {
                    double current = value + coefficient * previous - older;
                    older = previous;
                    previous = current;
                }
                powers[frequency] = Math.max(powers[frequency], previous * previous + older * older - coefficient * previous * older);
            }
        int low = strongest(powers, 0), high = strongest(powers, 4);
        double lowPower = powers[low], highPower = powers[high];
        double ratio = lowPower / highPower;
        double fraction = 2 * (lowPower + highPower) / (WINDOW * energy);
        if(ratio < .25 || ratio > 4 || fraction < .8) return null;
        for(int frequency = 0; frequency < powers.length; frequency++)
            if(frequency != low && frequency != high && powers[frequency] * 6.3 > (frequency < 4 ? lowPower : highPower)) return null;
        return new Candidate(AmbeTone.valueOf("DTMF_" + SYMBOLS[low][high - 4]), Math.min(1, fraction));
    }

    private static int strongest(double[] powers, int start)
    {
        int result = start;
        for(int index = start + 1; index < start + 4; index++) if(powers[index] > powers[result]) result = index;
        return result;
    }

    private static void qualify(Run run, List<CompletedToneSequence.Element> result)
    {
        if(run == null || run.last - run.first + HOP < MINIMUM_QUALIFIED_SAMPLES) return;
        if(result.size() >= CompletedToneSequence.MAXIMUM_TONES)
            throw new IllegalArgumentException("Incomplete analog DTMF sequence exceeds bound");
        result.add(new CompletedToneSequence.Element(EncodedToneMetadata.frequencies(run.tone), run.tone.name(),
            (run.first + WINDOW / 2 - HOP / 2) / 8L, (run.last - run.first + HOP) / 8L, run.confidence / run.count));
    }

    private record Candidate(AmbeTone tone, double confidence) { }
    private static final class Run
    {
        private final AmbeTone tone;
        private final int first;
        private int last;
        private double confidence;
        private int count;
        private Run(AmbeTone tone, int start, double score)
        {
            this.tone = tone; first = start; add(start, score);
        }
        private void add(int start, double score) { last = start; confidence += score; count++; }
    }
}
