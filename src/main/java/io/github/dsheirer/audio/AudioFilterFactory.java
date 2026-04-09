/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.audio;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.design.FilterDesignException;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.remez.RemezFIRFilterDesigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared audio-domain FIR filter helpers for 8 kHz post-demodulation processing.
 */
public final class AudioFilterFactory
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioFilterFactory.class);
    private static float[] sAudioHighPassFilterCoefficients;

    static
    {
        FIRFilterSpecification specification = FIRFilterSpecification.highPassBuilder()
            .sampleRate(8000)
            .stopBandCutoff(200)
            .stopBandAmplitude(0.0)
            .stopBandRipple(0.025)
            .passBandStart(300)
            .passBandAmplitude(1.0)
            .passBandRipple(0.01)
            .build();

        try
        {
            RemezFIRFilterDesigner designer = new RemezFIRFilterDesigner(specification);

            if(designer.isValid())
            {
                sAudioHighPassFilterCoefficients = designer.getImpulseResponse();
            }
        }
        catch(FilterDesignException fde)
        {
            mLog.error("Filter design error", fde);
        }
    }

    private AudioFilterFactory()
    {
    }

    /**
     * Creates an 8 kHz audio high-pass filter that removes DC offset and sub-audible low-frequency energy.
     */
    public static IRealFilter getAudioHighPassFilter()
    {
        return sAudioHighPassFilterCoefficients != null ?
            FilterFactory.getRealFilter(sAudioHighPassFilterCoefficients.clone()) : null;
    }
}
