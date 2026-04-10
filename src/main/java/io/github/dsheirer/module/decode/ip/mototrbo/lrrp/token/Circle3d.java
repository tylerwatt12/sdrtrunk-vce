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

package io.github.dsheirer.module.decode.ip.mototrbo.lrrp.token;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import java.text.DecimalFormat;

/**
 * LRRP 3D Position with latitude, longitude, altitude and radius
 */
public class Circle3d extends Circle2d
{
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.00");
    private static final IntField ALTITUDE = IntField.length16(88);
    private static final IntField ALTITUDE_ACCURACY = IntField.length16(104);

    /**
     * Constructs an instance of a 3D position token.
     *
     * @param message containing the heading
     * @param offset to the start of the token
     */
    public Circle3d(CorrectedBinaryMessage message, int offset)
    {
        super(message, offset);
    }

    @Override
    public TokenType getTokenType()
    {
        return TokenType.CIRCLE_3D;
    }

    /**
     * Altitude
     *
     * @return altitude in meters
     */
    public float getAltitude()
    {
        return getMessage().getInt(ALTITUDE, getOffset()) * HUNDREDTHS_MULTIPLIER;
    }

    /**
     * Altitude accuracy
     * @return accuracy in meters
     */
    public float getAltitudeAccuracy()
    {
        return getMessage().getInt(ALTITUDE_ACCURACY, getOffset()) * HUNDREDTHS_MULTIPLIER;
    }


    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("CIRCLE 3D POSITION:").append(getPosition());
        sb.append(" RADIUS:").append(DECIMAL_FORMAT.format(getRadius()));
        sb.append("MTRS ALTITUDE:").append(DECIMAL_FORMAT.format(getAltitude()));
        sb.append(" ALT ACCURACY:").append(DECIMAL_FORMAT.format(getAltitudeAccuracy()));
        return sb.toString();
    }
}
