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

package io.github.dsheirer.audio.broadcast.rdioscanner;

import java.util.Objects;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;

/**
 * Streaming configuration for Rdio Scanner API.
 *
 * Note: this API is not a streaming audio service, rather a completed call push service.  However, it fits nicely
 * with the structure of the audio streaming subsystem in sdrtrunk
 */
public class RdioScannerConfiguration extends BroadcastConfiguration
{
    private int mSystemID = 0;
    private String mApiKey = null;

    /**
     * Constructor for faster jackson
     */
    public RdioScannerConfiguration()
    {
        this(BroadcastFormat.MP3);
    }

    /**
     * Public constructor.
     * @param format to use for audio recording (MP3)
     */
    public RdioScannerConfiguration(BroadcastFormat format)
    {
        super(format);

        if(getHost() == null || getHost().isEmpty())
        {
            setHost("http://localhost");
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
     * System ID as provided by RdioScanner.com
     */
    public int getSystemID()
    {
        return mSystemID;
    }

    /**
     * Sets the system ID provided by RdioScanner.com
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
        return BroadcastServerType.RDIOSCANNER_CALL;
    }

    @Override
    public BroadcastConfiguration copyOf()
    {
        RdioScannerConfiguration copy = new RdioScannerConfiguration();
        copy.setSystemID(getSystemID());
        return copy;
    }
    @Override
    public boolean isValid()
    {
        return getSystemID() > 0 && getApiKey() != null && getHost() != null;
    }
}
