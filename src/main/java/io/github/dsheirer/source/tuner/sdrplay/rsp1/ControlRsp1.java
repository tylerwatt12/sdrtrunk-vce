/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.source.tuner.sdrplay.rsp1;

import io.github.dsheirer.source.tuner.sdrplay.ControlRsp;
import io.github.dsheirer.source.tuner.sdrplay.api.device.Rsp1Device;

/**
 * Control wrapper for an RSP1 Device
 */
public class ControlRsp1 extends ControlRsp<Rsp1Device> implements IControlRsp1
{
    /**
     * Constructs an instance
     * @param device for the device
     */
    public ControlRsp1(Rsp1Device device)
    {
        super(device);
    }

    /**
     * Maximum LNA index value from the API section 5 Gain Reduction Table for the RSP1.
     * @return maximum (valid) LNA index value.
     */
    @Override
    public int getMaximumLNASetting()
    {
        return 3;
    }
}
