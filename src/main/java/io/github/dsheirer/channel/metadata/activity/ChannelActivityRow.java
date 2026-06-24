/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import java.util.Collections;
import java.util.List;

/**
 * Session-only row for the Now Playing activity tables.
 */
public class ChannelActivityRow
{
    public enum Role
    {
        CONVENTIONAL,
        CURRENT_CONTROL,
        ALTERNATE_CONTROL,
        TRAFFIC
    }

    private final String mKey;
    private Channel mChannel;
    private Role mRole;
    private State mState = State.IDLE;
    private Integer mLcn;
    private long mFrequency;
    private Integer mTimeslot;
    private Identifier<?> mSource;
    private List<Alias> mSourceAliases = Collections.emptyList();
    private Identifier<?> mTarget;
    private List<Alias> mTargetAliases = Collections.emptyList();
    private String mDecoder;

    public ChannelActivityRow(String key, Channel channel, Role role, long frequency, Integer timeslot)
    {
        mKey = key;
        mChannel = channel;
        mRole = role;
        mFrequency = frequency;
        mTimeslot = timeslot;
    }

    public String getKey()
    {
        return mKey;
    }

    public Channel getChannel()
    {
        return mChannel;
    }

    public void setChannel(Channel channel)
    {
        mChannel = channel;
    }

    public Role getRole()
    {
        return mRole;
    }

    public void setRole(Role role)
    {
        mRole = role;
    }

    public State getState()
    {
        return mState;
    }

    public void setState(State state)
    {
        mState = state != null ? state : State.IDLE;
    }

    public Integer getLcn()
    {
        return mLcn;
    }

    public void setLcn(Integer lcn)
    {
        mLcn = lcn;
    }

    public long getFrequency()
    {
        return mFrequency;
    }

    public void setFrequency(long frequency)
    {
        mFrequency = frequency;
    }

    public Integer getTimeslot()
    {
        return mTimeslot;
    }

    public void setTimeslot(Integer timeslot)
    {
        mTimeslot = timeslot;
    }

    public Identifier<?> getSource()
    {
        return mSource;
    }

    public void setSource(Identifier<?> source)
    {
        mSource = source;
    }

    public List<Alias> getSourceAliases()
    {
        return mSourceAliases;
    }

    public void setSourceAliases(List<Alias> sourceAliases)
    {
        mSourceAliases = sourceAliases != null ? sourceAliases : Collections.emptyList();
    }

    public Identifier<?> getTarget()
    {
        return mTarget;
    }

    public void setTarget(Identifier<?> target)
    {
        mTarget = target;
    }

    public List<Alias> getTargetAliases()
    {
        return mTargetAliases;
    }

    public void setTargetAliases(List<Alias> targetAliases)
    {
        mTargetAliases = targetAliases != null ? targetAliases : Collections.emptyList();
    }

    public String getDecoder()
    {
        return mDecoder;
    }

    public void setDecoder(String decoder)
    {
        mDecoder = decoder;
    }

    public void clearCallDetails()
    {
        mSource = null;
        mSourceAliases = Collections.emptyList();
        mTarget = null;
        mTargetAliases = Collections.emptyList();
    }
}
