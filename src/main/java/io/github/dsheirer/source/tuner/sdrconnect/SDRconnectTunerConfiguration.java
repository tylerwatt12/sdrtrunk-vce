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

package io.github.dsheirer.source.tuner.sdrconnect;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;

/**
 * SDRconnect tuner configuration for connecting to SDRplay devices via SDRconnect WebSocket API
 */
public class SDRconnectTunerConfiguration extends TunerConfiguration
{
    private String mHost = SDRconnectTunerController.DEFAULT_HOST;
    private int mPort = SDRconnectTunerController.DEFAULT_PORT;
    private String mDeviceName = SDRconnectTunerController.DEFAULT_DEVICE_NAME;
    private int mSampleRate = SDRconnectTunerController.DEFAULT_SAMPLE_RATE;
    private String mAntenna = "";

    /**
     * Jackson constructor
     */
    public SDRconnectTunerConfiguration()
    {
        super(SDRconnectTunerController.MINIMUM_FREQUENCY, SDRconnectTunerController.MAXIMUM_FREQUENCY);
    }

    /**
     * Constructs an instance
     * @param uniqueId to use for this configuration
     */
    public SDRconnectTunerConfiguration(String uniqueId)
    {
        this();
        setUniqueID(uniqueId);
    }

    /**
     * Constructs an instance with host and port
     * @param uniqueId to use for this configuration
     * @param host SDRconnect host address
     * @param port SDRconnect WebSocket port
     */
    public SDRconnectTunerConfiguration(String uniqueId, String host, int port, String deviceName)
    {
        this(uniqueId);
        mHost = host;
        mPort = port;
        mDeviceName = deviceName;
    }

    /**
     * Constructs an instance with host and port using the default device name.
     * @param uniqueId to use for this configuration
     * @param host SDRconnect host address
     * @param port SDRconnect WebSocket port
     */
    public SDRconnectTunerConfiguration(String uniqueId, String host, int port)
    {
        this(uniqueId, host, port, SDRconnectTunerController.DEFAULT_DEVICE_NAME);
    }

    @JsonIgnore
    @Override
    public TunerType getTunerType()
    {
        return TunerType.SDRCONNECT;
    }

    /**
     * SDRconnect host address
     */
    @JacksonXmlProperty(isAttribute = true, localName = "host")
    public String getHost()
    {
        return mHost;
    }

    /**
     * Sets the SDRconnect host address
     */
    public void setHost(String host)
    {
        mHost = host;
    }

    /**
     * SDRconnect WebSocket port
     */
    @JacksonXmlProperty(isAttribute = true, localName = "port")
    public int getPort()
    {
        return mPort;
    }

    /**
     * Sets the SDRconnect WebSocket port
     */
    public void setPort(int port)
    {
        mPort = port;
    }

    /**
     * SDRconnect device name
     */
    @JacksonXmlProperty(isAttribute = true, localName = "device_name")
    public String getDeviceName()
    {
        return mDeviceName;
    }

    /**
     * Sets the SDRconnect device name
     */
    public void setDeviceName(String deviceName)
    {
        mDeviceName = deviceName;
    }

    /**
     * Configured sample rate in Hz
     */
    @JacksonXmlProperty(isAttribute = true, localName = "sample_rate")
    public int getSampleRate()
    {
        return mSampleRate;
    }

    public void setSampleRate(int sampleRate)
    {
        mSampleRate = sampleRate;
    }

    /**
     * Configured antenna selection
     */
    @JacksonXmlProperty(isAttribute = true, localName = "antenna")
    public String getAntenna()
    {
        return mAntenna;
    }

    public void setAntenna(String antenna)
    {
        mAntenna = antenna != null ? antenna : "";
    }

    /**
     * Creates a new SDRconnect configuration with default settings
     */
    public static SDRconnectTunerConfiguration create()
    {
        return create(SDRconnectTunerController.DEFAULT_HOST, SDRconnectTunerController.DEFAULT_PORT,
            SDRconnectTunerController.DEFAULT_DEVICE_NAME);
    }

    /**
     * Creates a new SDRconnect configuration with specified host, port, and device name
     */
    public static SDRconnectTunerConfiguration create(String host, int port, String deviceName)
    {
        return new SDRconnectTunerConfiguration(getUniqueId(host, port), host, port, deviceName);
    }

    /**
     * Creates a new SDRconnect configuration with specified host and port using the default device name.
     */
    public static SDRconnectTunerConfiguration create(String host, int port)
    {
        return create(host, port, SDRconnectTunerController.DEFAULT_DEVICE_NAME);
    }

    /**
     * Creates a stable unique identifier for an SDRconnect tuner configuration.
     */
    public static String getUniqueId(String host, int port)
    {
        return "SDRconnect-" + host + ":" + port;
    }
}
