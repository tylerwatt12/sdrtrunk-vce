/*******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2017 Dennis Sheirer
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
 *
 ******************************************************************************/
package io.github.dsheirer.dsp.filter.fir.remez;

import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import org.apache.commons.math3.util.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PolyphaseChannelizerFilterFactory
{
    private static final Logger mLog = LoggerFactory.getLogger(PolyphaseChannelizerFilterFactory.class);

    private static final double OBJECTIVE_BAND_EDGE_COEFFICIENT_AMPLITUDE = FastMath.sqrt(2.0) / 2.0; //.707xxx

    private PolyphaseChannelizerFilterFactory()
    {
    }

    public static float[] getFilter(int sampleRate, int channelBandwidth, double alpha)
    {
        int passBandStart = 0;
        int gridDensity = 60;

        int rolloffFrequency = (int)(alpha * channelBandwidth);

        int passBandStop = channelBandwidth - rolloffFrequency;

        //Set the stop band to start at 40% into the adjacent channel, since we're oversampling by 2.
        int stopBandStart = channelBandwidth + rolloffFrequency;

        double passRipple = 0.01d;
        double transitionRipple = 0.02d;
        double stopRipple = 0.001d; //Approximately 90 dB of attenuation

        int order = FIRFilterSpecification.estimateFilterOrder( sampleRate, passBandStop, stopBandStart,
            passRipple, stopRipple );

        // Ensure odd order since we're designing a Type 2 filter
        if(order % 2 == 0)
        {
            order++;
        }

        mLog.info("Filter Order: {}", order);

        FIRFilterSpecification specification = new PolyphaseChannelizerDesigner(order, gridDensity);

        FIRFilterSpecification.FrequencyBand passBand = new FIRFilterSpecification.FrequencyBand(sampleRate, passBandStart,
            passBandStop, 1.0, passRipple, 1.0);

        FIRFilterSpecification.FrequencyBand transitionBand = new FIRFilterSpecification.FrequencyBand(sampleRate,
            channelBandwidth, channelBandwidth, OBJECTIVE_BAND_EDGE_COEFFICIENT_AMPLITUDE, transitionRipple, 5.0);

        FIRFilterSpecification.FrequencyBand stopBand = new FIRFilterSpecification.FrequencyBand(sampleRate,
            stopBandStart, (sampleRate / 2), 0.0, stopRipple);

        specification.addFrequencyBand(passBand);
        specification.addFrequencyBand(transitionBand);
        specification.addFrequencyBand(stopBand);

        double bandEdgeFrequency = FastMath.cos(FastMath.PI * channelBandwidth / (sampleRate / 2.0));
        float[] filter = null;

        try
        {
            RemezFIRFilterDesignerWithLagrange designer = new RemezFIRFilterDesignerWithLagrange(specification);
            filter = designer.getImpulseResponse();
            double bandEdgeAmplitude = designer.getFrequencyResponse(bandEdgeFrequency);
            mLog.debug("Coefficient Amplitude at Band Edge is: {} for frequency: {}", bandEdgeAmplitude, passBandStop);
        }
        catch(Exception e)
        {
            mLog.error("Error designing filter", e);
        }

        return filter;
    }
}
