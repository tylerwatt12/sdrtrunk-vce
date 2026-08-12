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
package io.github.dsheirer.module.decode.p25.phase1;


import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * APCO25 Phase 1 decoder configuration
 */
public class DecodeConfigP25Phase1 extends DecodeConfigP25
{
    /**
     * P25 control acquisition needs enough dwell to synchronize and, in Auto mode, evaluate both 500 ms waveform
     * trials before a frequency change resets the selector.
     */
    public static final int CHANNEL_ROTATION_DELAY_MINIMUM_MS = 2000;
    public static final int CHANNEL_ROTATION_DELAY_DEFAULT_MS = 2000;
    public static final int CHANNEL_ROTATION_DELAY_MAXIMUM_MS = 10000;

    private Modulation mModulation = Modulation.AUTO;
    private Modulation mAutoPreferredModulation = Modulation.C4FM;
    private volatile Modulation mEffectiveModulation = Modulation.C4FM;

    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    public Modulation getModulation()
    {
        return mModulation;
    }

    public void setModulation(Modulation modulation)
    {
        mModulation = modulation != null ? modulation : Modulation.AUTO;
        mEffectiveModulation = mModulation == Modulation.AUTO ? mAutoPreferredModulation : mModulation;
    }

    /**
     * Preferred first decoder for automatic selection. RadioReference imports use known simulcast metadata as a
     * starting hint; the automatic selector still verifies the received waveform.
     */
    public Modulation getAutoPreferredModulation()
    {
        return mAutoPreferredModulation;
    }

    public void setAutoPreferredModulation(Modulation modulation)
    {
        mAutoPreferredModulation = modulation == Modulation.CQPSK ? Modulation.CQPSK : Modulation.C4FM;

        if(mModulation == Modulation.AUTO)
        {
            mEffectiveModulation = mAutoPreferredModulation;
        }
    }

    /**
     * Current fixed or automatically selected decoder profile. This is runtime state and is not persisted.
     */
    @JsonIgnore
    public Modulation getEffectiveModulation()
    {
        return mEffectiveModulation;
    }

    void setEffectiveModulation(Modulation modulation)
    {
        if(mModulation == Modulation.AUTO && (modulation == Modulation.C4FM || modulation == Modulation.CQPSK))
        {
            mEffectiveModulation = modulation;
        }
    }

    /**
     * Source channel specification for this decoder
     */
    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        return new ChannelSpecification(50000.0, 12500, 5750.0, 6500.0);
    }
}
