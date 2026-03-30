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
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SDRconnect Tuner - connects to SDRplay devices via SDRconnect WebSocket API
 */
public class SDRconnectTuner extends Tuner
{
    /**
     * Constructs an instance
     * @param controller for the SDRconnect connection
     * @param tunerErrorListener to receive error notifications
     * @param channelizerType for the channelizer
     */
    public SDRconnectTuner(SDRconnectTunerController controller, ITunerErrorListener tunerErrorListener,
                           ChannelizerType channelizerType)
    {
        super(controller, tunerErrorListener, channelizerType);
    }

    @Override
    public String getPreferredName()
    {
        return "SDRconnect " + getController().getHost() + ":" + getController().getPort() +
            " [" + getController().getDeviceName() + "]";
    }

    /**
     * SDRconnect tuner controller
     */
    public SDRconnectTunerController getController()
    {
        return (SDRconnectTunerController)getTunerController();
    }

    @Override
    public String getUniqueID()
    {
        return getController().getUniqueId();
    }

    @Override
    public TunerClass getTunerClass()
    {
        return TunerClass.SDRCONNECT;
    }

    @Override
    public double getSampleSize()
    {
        // 16-bit samples
        return 16.0;
    }

    @Override
    public int getMaximumUSBBitsPerSecond()
    {
        // Network connection, not USB - return a high value
        // Maximum supported SDRconnect sample rate is 10 MHz:
        // 10 MHz * 4 bytes per complex sample * 8 bits = 320 Mbps
        return 320000000;
    }
}
