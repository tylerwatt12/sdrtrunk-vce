/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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
package io.github.dsheirer.audio.broadcast;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallSiteConfiguration;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.audio.broadcast.openmhz.OpenMHzConfiguration;
import io.github.dsheirer.audio.broadcast.icecast.IcecastConfiguration;
import io.github.dsheirer.audio.broadcast.shoutcast.v1.ShoutcastV1Configuration;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BroadcastifyCallConfiguration.class, name="broadcastifyCallConfiguration"),
    @JsonSubTypes.Type(value = BroadcastifyCallSiteConfiguration.class, name="broadcastifyCallSiteConfiguration"),
    @JsonSubTypes.Type(value = RadioResolveConfiguration.class, name="RadioResolveConfiguration"),
    @JsonSubTypes.Type(value = RdioScannerConfiguration.class, name="RdioScannerConfiguration"),
    @JsonSubTypes.Type(value = OpenMHzConfiguration.class, name="OpenMHzConfiguration"),
    @JsonSubTypes.Type(value = IcecastConfiguration.class, name="icecastConfiguration"),
    @JsonSubTypes.Type(value = ShoutcastV1Configuration.class, name="shoutcastV1Configuration"),
})
public abstract class BroadcastConfiguration
{
    private final java.util.List<Runnable> mChangeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addChangeListener(Runnable listener) { mChangeListeners.add(listener); }
    public void removeChangeListener(Runnable listener) { mChangeListeners.remove(listener); }
    protected final void notifyChanged() { mChangeListeners.forEach(Runnable::run); }

    // Static unique channel identifier tracking
    private static int UNIQUE_ID = 0;

    private BroadcastFormat mBroadcastFormat = BroadcastFormat.MP3;
    protected String mName = null;
    protected String mHost = null;
    protected int mPort = 80;
    protected String mPassword = null;
    protected long mDelay = 0;
    protected long mMaximumRecordingAge = 10 * 60 * 1000; //10 minutes default
    protected boolean mEnabled = false;
    private int mId = ++UNIQUE_ID;
    private String mConfigurationId = UUID.randomUUID().toString();
    private boolean mConfigurationIdPersistenceRequired = true;

    protected BroadcastConfiguration()
    {
    }

    /**
     * Unique identifier for this configuration.
     * Note: unique ids are generated at runtime and have no persistent context across sessions.
     */
    @JsonIgnore
    public int getId()
    {
        return mId;
    }

    /**
     * Stable internal identity for this saved provider.  Unlike {@link #getId()}, this value survives restarts and
     * provider renames.
     */
    public String getConfigurationId()
    {
        if(mConfigurationId == null)
        {
            regenerateConfigurationId();
        }

        return mConfigurationId;
    }

    /**
     * Restores a persisted provider identity.  Missing or malformed legacy values are replaced before providers start.
     */
    public void setConfigurationId(String configurationId)
    {
        if(configurationId != null && !configurationId.isBlank())
        {
            try
            {
                mConfigurationId = UUID.fromString(configurationId.trim()).toString();
                mConfigurationIdPersistenceRequired = false;
                return;
            }
            catch(IllegalArgumentException _)
            {
                //Replace malformed values below.
            }
        }

        regenerateConfigurationId();
    }

    /**
     * Assigns a distinct identity to a new or cloned provider.
     */
    public void regenerateConfigurationId()
    {
        mConfigurationId = UUID.randomUUID().toString();
        mConfigurationIdPersistenceRequired = true;
    }

    @JsonIgnore
    public boolean isConfigurationIdPersistenceRequired()
    {
        return mConfigurationIdPersistenceRequired;
    }

    /**
     * Creates a copy/clone of the configuration
     */
    public abstract BroadcastConfiguration copyOf();

    /**
     * Broadcast audio streaming configuration.  Describes the streaming server and configuration details.
     */
    protected BroadcastConfiguration(BroadcastFormat format)
    {
        mBroadcastFormat = format;
    }

    /**
     * Broadcast server type
     */
    public abstract BroadcastServerType getBroadcastServerType();

    @SuppressWarnings("java:S1172")
    private void setBroadcastServerType(BroadcastServerType type)
    {
        //Do nothing
    }

    /**
     * Name identifying this broadcastAudio configuration.
     */
    public String getName()
    {
        return mName;
    }

    /**
     * Sets the name name that identifies/describes this configuration
     *
     * @param name
     */
    public void setName(String name)
    {
        if(!Objects.equals(mName, name))
        {
            mName = name;
            notifyChanged();
        }
    }

    /**
     * Indicates if this configuration has a name value
     */
    public boolean hasName()
    {
        return true;
    }

    /**
     * BROADCAST server host name.
     */
    public String getHost()
    {
        return mHost;
    }

    /**
     * Host name or IP address of the streaming server
     *
     * @param host
     */
    public void setHost(String host)
    {
        if(!Objects.equals(mHost, host))
        {
            mHost = host;
            notifyChanged();
        }
    }

    /**
     * Indicates if this configuration has a host value
     */
    public boolean hasHost()
    {
        return true;
    }

    /**
     * BROADCAST server port number;
     */
    public int getPort()
    {
        return mPort;
    }

    /**
     * Port number of the streaming server to where the broadcastAudio will be directed.
     *
     * @param port
     */
    public void setPort(int port)
    {
        if(!Objects.equals(mPort, port))
        {
            mPort = port;
            notifyChanged();
        }
    }

    /**
     * Indicates if this configuration contains a port value
     */
    public boolean hasPort()
    {
        return mPort > 0;
    }

    @JsonIgnore
    public SocketAddress getAddress()
    {
        return new InetSocketAddress(getHost(), getPort());
    }

    /**
     * Password to authenticate with the streaming server.
     */
    public String getPassword()
    {
        return mPassword;
    }

    /**
     * Sets the password used to authenticate the broadcastAudio broadcastAudio user to the streaming server
     *
     * @param password
     */
    public void setPassword(String password)
    {
        if(!Objects.equals(mPassword, password))
        {
            mPassword = password;
            notifyChanged();
        }
    }

    /**
     * Indicates if this configuration has a password value
     */
    public boolean hasPassword()
    {
        return true;
    }

    /**
     * Audio broadcastAudio content type (e.g. mp3)
     */
    public BroadcastFormat getBroadcastFormat()
    {
        return mBroadcastFormat;
    }

    public void setBroadcastFormat(BroadcastFormat format)
    {
        mBroadcastFormat = format;
    }

    /**
     * Audio broadcastAudio delay from recording start until the audio file is broadcastAudio to the server.
     */
    public long getDelay()
    {
        return mDelay;
    }

    /**
     * User specified delay in milli-seconds from audio call start until when the audio is broadcastAudio to the server.
     * @param delay in milliseconds
     */
    public void setDelay(long delay)
    {
        if(!Objects.equals(mDelay, delay))
        {
            mDelay = delay;
            notifyChanged();
        }
    }

    /**
     * Gets maximum recording age which determines the maximum amount of elapsed time that a recording can set in the
     * queue awaiting streaming before it is purged from the queue.  This value is in addition to the delay setting.
     * @return age in milliseconds
     */
    public long getMaximumRecordingAge()
    {
        return mMaximumRecordingAge;
    }

    /**
     * Sets maximum recording age which determines the maximum amount of elapsed time that a recording can set in the
     * queue awaiting streaming before it is purged from the queue.  This value is in addition to the delay setting.
     * @param age in milliseconds
     */
    public void setMaximumRecordingAge(long age)
    {
        if(!Objects.equals(mMaximumRecordingAge, age))
        {
            mMaximumRecordingAge = age;
            notifyChanged();
        }
    }

    /**
     * Indicates if this broadcaster is enable, meaning that it will automatically connect on startup.
     */
    public boolean isEnabled()
    {
        return mEnabled;
    }

    public void setEnabled(boolean enabled)
    {
        if(!Objects.equals(mEnabled, enabled))
        {
            mEnabled = enabled;
            notifyChanged();
        }
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }

        if (!(o instanceof BroadcastConfiguration))
        {
            return false;
        }

        BroadcastConfiguration that = (BroadcastConfiguration) o;

        if (getBroadcastFormat() != that.getBroadcastFormat())
        {
            return false;
        }

        if (getName() != null ? !getName().equals(that.getName()) : that.getName() != null)
        {
            return false;
        }

        return getBroadcastServerType() == that.getBroadcastServerType();
    }

    @Override
    public int hashCode()
    {
        int result = getBroadcastFormat().hashCode();
        result = 31 * result + (getName() != null ? getName().hashCode() : 0);
        result = 31 * result + getBroadcastServerType().hashCode();
        return result;
    }

    /**
     * Indicates if this configuration is valid.  A minimal check will ensure that it contains at least a hostname and
     * port number.
     */
    @JsonIgnore
    public boolean isValid()
    {
        return false;
    }

}
