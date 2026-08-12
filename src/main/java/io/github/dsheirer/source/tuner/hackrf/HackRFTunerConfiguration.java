/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
package io.github.dsheirer.source.tuner.hackrf;


import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController.HackRFLNAGain;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController.HackRFSampleRate;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController.HackRFVGAGain;

public class HackRFTunerConfiguration extends TunerConfiguration
{
    private HackRFSampleRate mSampleRate = HackRFSampleRate.RATE_5_0;
    private HackRFLNAGain mLNAGain = HackRFLNAGain.GAIN_16;  // We can see some signal at this gain
    private HackRFVGAGain mVGAGain = HackRFVGAGain.GAIN_16;  // We can see some signal at this gain
    private boolean mAmplifierEnabled = false;  //Probably should start off disabled

    /**
     * Default constructor for deserialization
     */
    public HackRFTunerConfiguration()
    {
        super(HackRFTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ, HackRFTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ);
    }

    public HackRFTunerConfiguration(String uniqueID)
    {
        super(uniqueID);
    }

    @JsonIgnore
    @Override
    public TunerType getTunerType()
    {
        return TunerType.HACKRF_ONE;
    }

    public boolean getAmplifierEnabled()
    {
        return mAmplifierEnabled;
    }

    public void setAmplifierEnabled(boolean enabled)
    {
        mAmplifierEnabled = enabled;
    }

    public HackRFLNAGain getLNAGain()
    {
        return mLNAGain;
    }

    public void setLNAGain(HackRFLNAGain lnaGain)
    {
        mLNAGain = lnaGain;
    }

    public HackRFVGAGain getVGAGain()
    {
        return mVGAGain;
    }

    public void setVGAGain(HackRFVGAGain vgaGain)
    {
        mVGAGain = vgaGain;
    }

    public HackRFSampleRate getSampleRate()
    {
        return mSampleRate;
    }

    @JsonIgnore
    @Override
    public int getConfiguredSampleRate()
    {
        return getSampleRate() != null ? getSampleRate().getRate() : 0;
    }

    public void setSampleRate(HackRFSampleRate sampleRate)
    {
        mSampleRate = sampleRate;
    }
}
