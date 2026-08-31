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
package io.github.dsheirer.module.decode.p25.phase1;


import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DecodeConfigP25Phase1.class, name = "decodeConfigP25Phase1"),
        @JsonSubTypes.Type(value = DecodeConfigP25Phase2.class, name = "decodeConfigP25Phase2"),
})
public abstract class DecodeConfigP25 extends DecodeConfiguration
{
    private int mTrafficChannelPoolSize = TRAFFIC_CHANNEL_LIMIT_DEFAULT;
    private boolean mIgnoreDataCalls = false;
    private boolean mLearnAnnouncedControlChannels = false;
    private boolean mUseP25BandplanOverride = false;
    private List<Long> mLearnedControlFrequencies = new CopyOnWriteArrayList<>();

    protected DecodeConfigP25()
    {
    }

    public boolean getIgnoreDataCalls()
    {
        return mIgnoreDataCalls;
    }

    public void setIgnoreDataCalls(boolean ignore)
    {
        mIgnoreDataCalls = ignore;
    }

    public boolean getLearnAnnouncedControlChannels()
    {
        return mLearnAnnouncedControlChannels;
    }

    @JsonAlias({"learn_control_channels", "learnControlChannels"})
    public void setLearnAnnouncedControlChannels(boolean learn)
    {
        mLearnAnnouncedControlChannels = learn;
    }

    /**
     * Frequencies owned by automatic site learning. Manually configured frequencies are deliberately not included so
     * that reconciliation can safely remove obsolete learned entries across application restarts.
     */
    public List<Long> getLearnedControlFrequencies()
    {
        return new ArrayList<>(mLearnedControlFrequencies);
    }

    public void setLearnedControlFrequencies(List<Long> frequencies)
    {
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();

        if(frequencies != null)
        {
            for(Long frequency: frequencies)
            {
                if(frequency != null && frequency > 0)
                {
                    normalized.add(frequency);
                }
            }
        }

        mLearnedControlFrequencies = new CopyOnWriteArrayList<>(normalized);
    }

    public boolean addLearnedControlFrequency(long frequency)
    {
        if(frequency > 0 && !mLearnedControlFrequencies.contains(frequency))
        {
            mLearnedControlFrequencies.add(frequency);
            return true;
        }

        return false;
    }

    public boolean removeLearnedControlFrequency(long frequency)
    {
        return mLearnedControlFrequencies.remove(frequency);
    }

    public boolean getUseP25BandplanOverride()
    {
        return mUseP25BandplanOverride;
    }

    public void setUseP25BandplanOverride(boolean useP25BandplanOverride)
    {
        mUseP25BandplanOverride = useP25BandplanOverride;
    }


    public int getTrafficChannelPoolSize()
    {
        return mTrafficChannelPoolSize;
    }

    /**
     * Sets the traffic channel pool size which is the maximum number of
     * simultaneous traffic channels that can be allocated.
     *
     * This limits the maximum calls so that busy systems won't cause more
     * traffic channels to be allocated than the decoder/software/host computer
     * can support.
     */
    public void setTrafficChannelPoolSize(int size)
    {
        mTrafficChannelPoolSize = size;
    }
}
