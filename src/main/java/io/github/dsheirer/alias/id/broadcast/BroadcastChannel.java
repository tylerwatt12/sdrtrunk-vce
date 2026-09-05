/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2020 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */
package io.github.dsheirer.alias.id.broadcast;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import java.util.UUID;

public class BroadcastChannel extends AliasID implements Comparable<BroadcastChannel>
{
    private String mConfigurationId;
    private String mChannelName;

    public BroadcastChannel()
    {
        //Deserialization constructor
    }

    @Override
    public int compareTo(BroadcastChannel other)
    {
        if(mConfigurationId != null && other.getConfigurationId() != null)
        {
            return mConfigurationId.compareTo(other.getConfigurationId());
        }
        else if(mConfigurationId != null)
        {
            return -1;
        }
        else if(other.getConfigurationId() != null)
        {
            return 1;
        }

        String name = mChannelName != null ? mChannelName : "";
        String otherName = other.getChannelName() != null ? other.getChannelName() : "";
        return name.compareTo(otherName);
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }
        if(!(o instanceof BroadcastChannel))
        {
            return false;
        }

        BroadcastChannel that = (BroadcastChannel)o;

        if(getConfigurationId() != null || that.getConfigurationId() != null)
        {
            return getConfigurationId() != null && getConfigurationId().equals(that.getConfigurationId());
        }

        return getChannelName() != null ? getChannelName().equals(that.getChannelName()) : that.getChannelName() == null;
    }

    @Override
    public int hashCode()
    {
        return getConfigurationId() != null ? getConfigurationId().hashCode() :
            getChannelName() != null ? getChannelName().hashCode() : 0;
    }

    /**
     * Creates an unresolved legacy XML route. Current configuration must resolve this display name to a provider
     * identity before it can be persisted or used for delivery.
     */
    public BroadcastChannel(String channelName)
    {
        setChannelName(channelName);
    }

    /** Creates a current provider route with stable identity and presentation text. */
    public BroadcastChannel(String configurationId, String channelName)
    {
        setConfigurationId(configurationId);
        setChannelName(channelName);
    }

    @JacksonXmlProperty(isAttribute = true, localName = "configuration_id")
    public String getConfigurationId()
    {
        return mConfigurationId;
    }

    public void setConfigurationId(String configurationId)
    {
        if(configurationId == null || configurationId.isBlank())
        {
            mConfigurationId = null;
            return;
        }

        try
        {
            String normalized = configurationId.strip();
            String canonical = UUID.fromString(normalized).toString();
            mConfigurationId = canonical.equals(normalized) ? canonical : normalized;
        }
        catch(IllegalArgumentException exception)
        {
            mConfigurationId = configurationId.strip();
        }
    }

    /**
     * Name of the broadcastAudio channel configuration
     */
    @JacksonXmlProperty(isAttribute = true, localName = "channel")
    public String getChannelName()
    {
        return mChannelName;
    }

    /**
     * Sets the name of the broadcastAudio channel configuration
     */
    public void setChannelName(String channel)
    {
        mChannelName = channel;
        updateValueProperty();
    }

    @Override
    public AliasIDType getType()
    {
        return AliasIDType.BROADCAST_CHANNEL;
    }

    @Override
    public boolean isValid()
    {
        if(mConfigurationId == null)
        {
            return false;
        }

        try
        {
            return UUID.fromString(mConfigurationId).toString().equals(mConfigurationId);
        }
        catch(IllegalArgumentException exception)
        {
            return false;
        }
    }

    /** True for a named legacy route that still needs import-time resolution. */
    public boolean hasDisplayName()
    {
        return mChannelName != null && !mChannelName.isBlank();
    }

    @Override
    public boolean matches(AliasID id)
    {
        return false;
    }

    @Override
    public String toString()
    {
        return hasDisplayName() ? mChannelName : isValid() ? mConfigurationId : "(invalid)";
    }
}
