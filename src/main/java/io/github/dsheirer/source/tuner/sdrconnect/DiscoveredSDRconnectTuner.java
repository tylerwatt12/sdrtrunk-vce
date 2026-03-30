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

import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovered tuner for SDRconnect tuner type - connects to SDRplay devices via SDRconnect WebSocket API
 */
public class DiscoveredSDRconnectTuner extends DiscoveredTuner
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveredSDRconnectTuner.class);

    private final String mHost;
    private final int mPort;
    private final String mDeviceName;
    private final ChannelizerType mChannelizerType;

    /**
     * Constructs an instance
     * @param host SDRconnect host address
     * @param port SDRconnect WebSocket port
     * @param deviceName SDRconnect device display name
     * @param channelizerType for the tuner
     */
    public DiscoveredSDRconnectTuner(String host, int port, String deviceName, ChannelizerType channelizerType)
    {
        mHost = host;
        mPort = port;
        mDeviceName = deviceName;
        mChannelizerType = channelizerType;

        // Create default configuration
        SDRconnectTunerConfiguration config = SDRconnectTunerConfiguration.create(host, port, deviceName);
        setTunerConfiguration(config);
    }

    /**
     * Constructs an instance using the default device name.
     * @param host SDRconnect host address
     * @param port SDRconnect WebSocket port
     * @param channelizerType for the tuner
     */
    public DiscoveredSDRconnectTuner(String host, int port, ChannelizerType channelizerType)
    {
        this(host, port, SDRconnectTunerController.DEFAULT_DEVICE_NAME, channelizerType);
    }

    /**
     * Constructs an instance with default host/port
     * @param channelizerType for the tuner
     */
    public DiscoveredSDRconnectTuner(ChannelizerType channelizerType)
    {
        this(SDRconnectTunerController.DEFAULT_HOST, SDRconnectTunerController.DEFAULT_PORT,
            SDRconnectTunerController.DEFAULT_DEVICE_NAME, channelizerType);
    }

    public String getDeviceName()
    {
        return mDeviceName;
    }

    /**
     * Access the tuner configuration as an SDRconnect tuner configuration
     */
    public SDRconnectTunerConfiguration getSDRconnectTunerConfiguration()
    {
        return (SDRconnectTunerConfiguration) getTunerConfiguration();
    }

    /**
     * Host address for SDRconnect
     */
    public String getHost()
    {
        return mHost;
    }

    /**
     * Port for SDRconnect WebSocket
     */
    public int getPort()
    {
        return mPort;
    }

    @Override
    public TunerClass getTunerClass()
    {
        return TunerClass.SDRCONNECT;
    }

    @Override
    public String getId()
    {
        return "SDRconnect-" + mHost + ":" + mPort;
    }

    @Override
    public void start()
    {
        if(!hasTuner())
        {
            try
            {
                mLog.info("Starting SDRconnect tuner: {}:{} device [{}]", mHost, mPort, mDeviceName);

                SDRconnectTunerController controller = new SDRconnectTunerController(mHost, mPort, this);
                if(hasTunerConfiguration())
                {
                    // Seed the preferred device before connecting so the SDRconnect handshake can select it.
                    controller.setDeviceName(getSDRconnectTunerConfiguration().getDeviceName());
                }
                mTuner = new SDRconnectTuner(controller, this, mChannelizerType);

                mTuner.start();

                // Apply the remaining configuration after the WebSocket is connected.
                if(hasTunerConfiguration())
                {
                    mTuner.getTunerController().apply(getTunerConfiguration());
                }
                mLog.info("SDRconnect tuner started successfully");
            }
            catch(SourceException se)
            {
                mLog.error("Error starting SDRconnect tuner", se);
                setErrorMessage("Error - " + se.getMessage());
            }
        }
    }

    @Override
    public String toString()
    {
        return "SDRconnect [" + mHost + ":" + mPort + " " + mDeviceName + "]";
    }
}
