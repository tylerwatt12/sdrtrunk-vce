/*
 * *****************************************************************************
 *  Copyright (C) 2014-2020 Dennis Sheirer
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

package io.github.dsheirer.audio.broadcast.broadcastify;

import java.util.Objects;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;

/**
 * Streaming configuration for Broadcastify Calls API.
 *
 * Note: this API is not a streaming audio service, rather a completed call push service.  However, it fits nicely
 * with the structure of the audio streaming subsystem in sdrtrunk
 */
public class BroadcastifyCallConfiguration extends BroadcastConfiguration
{
    public static final String PRODUCTION_ENDPOINT = "https://api.broadcastify.com/call-upload";

    private int mSystemID = 0;
    private String mApiKey = null;
    private boolean mTestEnabled = false;
    private int mTestInterval = 15;

    /**
     * Constructor for faster jackson
     */
    public BroadcastifyCallConfiguration()
    {
        this(BroadcastFormat.MP3);
    }

    /**
     * Public constructor.
     * @param format to use for audio recording (MP3)
     */
    public BroadcastifyCallConfiguration(BroadcastFormat format)
    {
        super(format);

        if(getHost() == null)
        {
            setHost(PRODUCTION_ENDPOINT);
        }
    }

    /**
     * API Key
     */
    public String getApiKey()
    {
        return mApiKey;
    }

    /**
     * Sets the api key
     * @param apiKey
     */
    public void setApiKey(String apiKey)
    {
        if(!Objects.equals(mApiKey, apiKey))
        {
            mApiKey = apiKey;
            notifyChanged();
        }
    }

    /**
     * System ID as provided by broadcastify.com
     */
    public int getSystemID()
    {
        return mSystemID;
    }

    /**
     * Sets the system ID provided by broadcastify.com
     */
    public void setSystemID(int systemID)
    {
        if(!Objects.equals(mSystemID, systemID))
        {
            mSystemID = systemID;
            notifyChanged();
        }
    }

    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.BROADCASTIFY_CALL;
    }

    /**
     * Indicates if this instance should schedule tests after connecting
     */
    public boolean isTestEnabled()
    {
        return mTestEnabled;
    }

    public void setTestEnabled(boolean enabled)
    {
        if(!Objects.equals(mTestEnabled, enabled))
        {
            mTestEnabled = enabled;
            notifyChanged();
        }
    }

    /**
     * Indicates the interval at which tests should be performed while enabled
     */
    public int getTestInterval()
    {
        return mTestInterval;
    }

    public void setTestInterval(int interval)
    {
        if(!Objects.equals(mTestInterval, interval))
        {
            mTestInterval = interval;
            notifyChanged();
        }
    }

    @Override
    public BroadcastConfiguration copyOf()
    {
        BroadcastifyCallConfiguration copy = new BroadcastifyCallConfiguration();
        copy.setSystemID(getSystemID());
        return copy;
    }
    @Override
    public boolean isValid()
    {
        return getSystemID() > 0 && getApiKey() != null && getHost() != null;
    }
}
