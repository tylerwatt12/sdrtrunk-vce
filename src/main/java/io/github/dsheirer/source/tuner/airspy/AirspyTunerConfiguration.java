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
package io.github.dsheirer.source.tuner.airspy;

import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController.Gain;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import java.util.ArrayList;
import java.util.List;

public class AirspyTunerConfiguration extends TunerConfiguration
{
    private Gain mGain = AirspyTunerController.LINEARITY_GAIN_DEFAULT;
    private int mSampleRate = AirspyTunerController.DEFAULT_SAMPLE_RATE.getRate();
    private int mIFGain = AirspyTunerController.IF_GAIN_DEFAULT;
    private int mMixerGain = AirspyTunerController.MIXER_GAIN_DEFAULT;
    private int mLNAGain = AirspyTunerController.LNA_GAIN_DEFAULT;
    private boolean mMixerAGC = false;
    private boolean mLNAAGC = false;
    private List<Integer> mAvailableSampleRates = new ArrayList<>();

    /**
     * Default constructor for deserialization
     */
    public AirspyTunerConfiguration()
    {
        super(AirspyTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ, AirspyTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ);
    }

    @Override
    public TunerType getTunerType()
    {
        return TunerType.AIRSPY_R820T;
    }

    public AirspyTunerConfiguration(String uniqueID)
    {
        super(uniqueID);
    }

    public int getSampleRate()
    {
        return mSampleRate;
    }

    public void setSampleRate(int sampleRate)
    {
        mSampleRate = sampleRate;
    }

    /**
     * Last sample-rate capabilities reported by this receiver.  Retaining this small device fact lets a disabled
     * receiver expose its valid choices without reopening USB hardware merely to render a settings page.
     */
    public List<Integer> getAvailableSampleRates()
    {
        return List.copyOf(mAvailableSampleRates);
    }

    public void setAvailableSampleRates(List<Integer> availableSampleRates)
    {
        mAvailableSampleRates = availableSampleRates != null ? availableSampleRates.stream()
            .filter(rate -> rate != null && rate > 0).distinct().toList() : new ArrayList<>();
    }

    public Gain getGain()
    {
        return mGain;
    }

    public void setGain(Gain gain)
    {
        mGain = gain;
    }

    public int getIFGain()
    {
        return mIFGain;
    }

    public void setIFGain(int gain)
    {
        mIFGain = gain;
    }

    public int getMixerGain()
    {
        return mMixerGain;
    }

    public void setMixerGain(int gain)
    {
        mMixerGain = gain;
    }

    public int getLNAGain()
    {
        return mLNAGain;
    }

    public void setLNAGain(int gain)
    {
        mLNAGain = gain;
    }

    public boolean isMixerAGC()
    {
        return mMixerAGC;
    }

    public void setMixerAGC(boolean enabled)
    {
        mMixerAGC = enabled;
    }

    public boolean isLNAAGC()
    {
        return mLNAAGC;
    }

    public void setLNAAGC(boolean enabled)
    {
        mLNAAGC = enabled;
    }
}
