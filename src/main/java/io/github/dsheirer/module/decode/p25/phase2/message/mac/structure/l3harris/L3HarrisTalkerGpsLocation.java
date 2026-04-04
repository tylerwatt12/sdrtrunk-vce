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

package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.l3harris;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.dmr.identifier.P25Location;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructureVendor;
import io.github.dsheirer.module.decode.p25.reference.Vendor;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.jdesktop.swingx.mapviewer.GeoPosition;

/**
 * L3Harris Talker GPS Location.
 *
 * Bit field definitions are best-guess from observed samples.
 */
public class L3HarrisTalkerGpsLocation extends MacStructureVendor
{
    private static final DecimalFormat GPS_FORMAT = new DecimalFormat("0.000000");
    private final SimpleDateFormat mSimpleDateFormat = new SimpleDateFormat("HH:mm:ss");
    private static final int DATA_OFFSET = 24;

    private P25Location mLocation;
    private GeoPosition mGeoPosition;
    private List<Identifier> mIdentifiers;

    /**
     * Constructs the message
     *
     * @param message containing the message bits
     * @param offset into the message for this structure
     */
    public L3HarrisTalkerGpsLocation(CorrectedBinaryMessage message, int offset)
    {
        super(message, offset);
        mSimpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Textual representation of this message
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        if(getVendor() == Vendor.HARRIS)
        {
            sb.append("L3H TALKER GPS ");
        }
        else
        {
            sb.append("VENDOR:").append(getVendor()).append(" TALKER GPS ");
        }

        GeoPosition geo = getGeoPosition();
        sb.append(GPS_FORMAT.format(geo.getLatitude())).append(" ").append(GPS_FORMAT.format(geo.getLongitude()));
        sb.append(" HEADING:").append(getHeading());
        sb.append(" TIME:").append(mSimpleDateFormat.format(getTimestampMs()));
        sb.append(" UTC MSG:").append(getMessage().toHexString());
        return sb.toString();
    }

    /**
     * Heading
     * @return heading, 0-359 degrees.
     */
    public int getHeading()
    {
        return L3HarrisGPS.parseHeading(getMessage(), getOffset() + DATA_OFFSET);
    }

    /**
     * GPS Position time in milliseconds.
     * @return time in ms UTC
     */
    public long getTimestampMs()
    {
        return L3HarrisGPS.parseTimestamp(getMessage(), getOffset() + DATA_OFFSET);
    }

    /**
     * GPS Location
     * @return location in decimal degrees
     */
    public P25Location getLocation()
    {
        if(mLocation == null)
        {
            GeoPosition geoPosition = getGeoPosition();
            mLocation = P25Location.createFrom(geoPosition.getLatitude(), geoPosition.getLongitude());
        }

        return mLocation;
    }

    /**
     * Geo position
     * @return position
     */
    public GeoPosition getGeoPosition()
    {
        if(mGeoPosition == null)
        {
            mGeoPosition = new GeoPosition(L3HarrisGPS.parseLatitude(getMessage(), getOffset() + DATA_OFFSET),
                                           L3HarrisGPS.parseLongitude(getMessage(), getOffset() + DATA_OFFSET));
        }

        return mGeoPosition;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getLocation());
        }

        return mIdentifiers;
    }
}
