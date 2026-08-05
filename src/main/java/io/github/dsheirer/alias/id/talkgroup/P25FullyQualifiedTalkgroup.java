/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.alias.id.talkgroup;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.protocol.Protocol;

/**
 * Fully qualified talkgroup.  Note: this is only for P25.
 */
public class P25FullyQualifiedTalkgroup extends Talkgroup
{
    private int mWacn;
    private int mSystem;

    public P25FullyQualifiedTalkgroup()
    {
        //No-arg deserialization constructor
    }

    /**
     * Constructs an instance
     * @param wacn for the radio
     * @param system for the radio
     * @param talkgroup value
     */
    public P25FullyQualifiedTalkgroup(int wacn, int system, int talkgroup)
    {
        super(Protocol.APCO25, talkgroup);
        mWacn = wacn;
        mSystem = system;
    }

    @Override
    public AliasIDType getType()
    {
        return AliasIDType.P25_FULLY_QUALIFIED_TALKGROUP;
    }

    /**
     * WACN for this radio
     * @return wacn
     */
    public int getWacn()
    {
        return mWacn;
    }

    /**
     * Sets the WACN for this radio
     * @param wacn for this radio
     */
    public void setWacn(int wacn)
    {
        mWacn = wacn;
        updateValueProperty();
    }

    /**
     * System for this radio
     * @return system
     */
    public int getSystem()
    {
        return mSystem;
    }

    /**
     * P25 reserves home talkgroup IDs zero and 0xFFFF. A fully-qualified alias also needs complete bounded home
     * system coordinates before it can safely match traffic.
     */
    @Override
    public boolean isValid()
    {
        return getWacn() >= 0 && getWacn() <= 0xFFFFF && getSystem() >= 0 && getSystem() <= 0xFFF &&
            getValue() > 0 && getValue() < 0xFFFF;
    }

    /**
     * Sets the system for this radio
     * @param system for this radio
     */
    public void setSystem(int system)
    {
        mSystem = system;
        updateValueProperty();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        sb.append("Fully Qualified Radio ID:").append(getWacn());
        sb.append(".").append(getSystem());
        sb.append(".").append(getValue());
        sb.append(" Protocol:").append((getProtocol()));

        if(!isValid())
        {
            sb.append(" **NOT VALID**");
        }

        return sb.toString();
    }

    /**
     * Hashable string key for use in a lookup map
     * @return
     */
    @JsonIgnore
    public String getHashKey()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getWacn());
        sb.append(".").append(getSystem());
        sb.append(".").append(getValue());
        return sb.toString();
    }
}
