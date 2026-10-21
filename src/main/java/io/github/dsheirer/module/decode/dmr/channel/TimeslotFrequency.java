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

package io.github.dsheirer.module.decode.dmr.channel;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Maps a logical channel number (LCN) to a pair of channel frequency values
 */
public class TimeslotFrequency
{
    private int mNumber;
    private long mDownlinkFrequency;
    private long mUplinkFrequency;
    private String mDescription;

    /**
     * Creates a deep copy of this instance
     */
    public TimeslotFrequency copy()
    {
        TimeslotFrequency copy = new TimeslotFrequency();
        copy.setNumber(getNumber());
        copy.setDownlinkFrequency(getDownlinkFrequency());
        copy.setUplinkFrequency(getUplinkFrequency());
        return copy;
    }

    /**
     * Logical channel number (LCN) as a 1-index based counter
     *
     * Note: lsn (logical slot number) is a legacy name for this field.  The field currently holds the LCN or logical
     * channel number.  To avoid breaking serialization, we leave this field labeled as lsn.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "lsn")
    public int getNumber()
    {
        return mNumber;
    }

    /**
     * Logical channel number which is the same value as getNumber.
     * @return
     */
    @JsonIgnore
    public int getChannelNumber()
    {
        return getNumber();
    }

    @JsonIgnore
    public void updateDescription()
    {
        StringBuilder sb = new StringBuilder();

        int channel = getChannelNumber();

        if(channel > 0)
        {
            if(channel < 32) //Connect Plus uses 5-bits for LSN for a maximum value of 31
            {
                //Cap+ & Con+ Logical Slot Numbers
                int lsn1 = (channel - 1) * 2 + 1;
                int lsn2 = (channel - 1) * 2 + 2;
                sb.append("LSN:").append(lsn1).append("/").append(lsn2).append(" or ");
            }

            //Tier III channel IDs
            int chanId1 = channel * 2 + 1;
            int chanId2 = channel * 2 + 2;
            sb.append("Tier3 Chan:").append(chanId1).append("/").append(chanId2);
        }
        else
        {
            sb.append("(empty)");
        }

        setDescription(sb.toString());
    }

    /**
     * Description of the logical slot numbers for the channel number.
     */
    @JsonIgnore
    public String getDescription()
    {
        return mDescription;
    }

    /**
     * Sets the description of the timeslots for the channel number.
     * @param description of the logical timeslots for the channel number.
     */
    public void setDescription(String description)
    {
        mDescription = description;
    }

    /**
     * Sets the logical channel number (LCN) as a 1-index start
     * @param lcn where LCN 1 is logical slot numbers (LSN) 1 and 2, LCN2=LSN3/4, etc.
     */
    public void setNumber(int lcn)
    {
        mNumber = lcn;
        updateDescription();
    }

    /**
     * Downlink frequency
     * @return value in hertz
     */
    @JacksonXmlProperty(isAttribute = true, localName = "downlink")
    public long getDownlinkFrequency()
    {
        return mDownlinkFrequency;
    }

    /**
     * Sets the downlink frequency value
     * @param downlinkFrequency in hertz
     */
    public void setDownlinkFrequency(long downlinkFrequency)
    {
        mDownlinkFrequency = downlinkFrequency;
    }

    /**
     * Uplink frequency
     * @return value in hertz
     */
    @JacksonXmlProperty(isAttribute = true, localName = "uplink")
    public long getUplinkFrequency()
    {
        return mUplinkFrequency;
    }

    /**
     * Sets the uplink frequency value
     * @param uplinkFrequency in hertz
     */
    public void setUplinkFrequency(long uplinkFrequency)
    {
        mUplinkFrequency = uplinkFrequency;
    }

    @Override
    public String toString()
    {
        return "LCN:" + getNumber() + " DOWNLINK:" + getDownlinkFrequency();
    }

}
