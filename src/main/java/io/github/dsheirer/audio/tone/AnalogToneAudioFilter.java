/* Copyright (C) 2026. Licensed under the GNU General Public License, version 3 or later. */
package io.github.dsheirer.audio.tone;

import java.util.ArrayList;
import java.util.List;

/** Worker-only eighth-order 300 Hz Butterworth high-pass: subaudible squelch energy must not mask paging or DTMF. */
final class AnalogToneAudioFilter
{
    private AnalogToneAudioFilter() { }

    static List<float[]> filter(List<float[]> buffers)
    {
        Stage[] stages = new Stage[4];
        for(int index = 0; index < stages.length; index++)
            stages[index] = new Stage(1 / (2 * Math.cos((2 * index + 1) * Math.PI / 16)));
        List<float[]> result = new ArrayList<>(buffers.size());
        for(float[] buffer: buffers)
        {
            float[] filtered = new float[buffer.length];
            for(int index = 0; index < buffer.length; index++)
            {
                double sample = buffer[index];
                for(Stage stage: stages) sample = stage.filter(sample);
                filtered[index] = (float)sample;
            }
            result.add(filtered);
        }
        return result;
    }

    private static final class Stage
    {
        private final double b0, b1, b2, a1, a2;
        private double z1, z2;
        private Stage(double quality)
        {
            double angle = 2 * Math.PI * 300 / P25PagingToneDetector.SAMPLE_RATE;
            double cosine = Math.cos(angle), alpha = Math.sin(angle) / (2 * quality), a0 = 1 + alpha;
            b0 = (1 + cosine) / (2 * a0); b1 = -(1 + cosine) / a0; b2 = b0;
            a1 = -2 * cosine / a0; a2 = (1 - alpha) / a0;
        }
        private double filter(double input)
        {
            double output = b0 * input + z1;
            z1 = b1 * input - a1 * output + z2;
            z2 = b2 * input - a2 * output;
            return output;
        }
    }
}
