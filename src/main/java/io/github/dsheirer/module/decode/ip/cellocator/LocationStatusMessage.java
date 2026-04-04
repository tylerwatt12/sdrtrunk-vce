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

package io.github.dsheirer.module.decode.ip.cellocator;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import org.apache.commons.math3.util.FastMath;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Unit (Outbound) Status and Location
 */
public class LocationStatusMessage extends MCGPPacket
{
    private static final int[] SOURCE_UNIT_ID = new int[]{24, 25, 26, 27, 28, 29, 30, 31, 16, 17, 18, 19, 20, 21, 22,
        23, 8, 9, 10, 11, 12, 13, 14, 15, 0, 1, 2, 3, 4, 5, 6, 7};
    private static final int[] COMMUNICATION_CONTROL = new int[]{32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44,
        45, 46, 47};
    private static final int[] MESSAGE_NUMERATOR = new int[]{48, 49, 50, 51, 52, 53, 54, 55};
    private static final int[] GPS_SATELLITES_USED = new int[]{304, 305, 306, 307, 308, 309, 310, 311};

    //Original signed 32-bit big endian field rearranged in little endian byte format
    private static final int[] LONGITUDE = new int[]{336, 337, 338, 339, 340, 341, 342, 343, 328, 329, 330, 331, 332,
            333, 334, 335, 320, 321, 322, 323, 324, 325, 326, 327, 312, 313, 314, 315, 316, 317, 318, 319};

    //Original signed 32-bit big endian field rearranged in little endian byte format
    private static final int[] LATITUDE = new int[]{368, 369, 370, 371, 372, 373, 374, 375, 360, 361, 362, 363, 364,
            365, 366, 367, 352, 353, 354, 355, 356, 357, 358, 359, 344, 345, 346, 347, 348, 349, 350, 351};

    //Original signed 32-bit big endian field rearranged in little endian byte format
    private static final int[] ALTITUDE = new int[]{400, 401, 402, 403, 404, 405, 406, 407, 392, 393, 394, 395, 396,
            397, 398, 399, 384, 385, 386, 387, 388, 389, 390, 391, 376, 377, 378, 379, 380, 381, 382, 383};

    //Original signed 32-bit big endian field rearranged in little endian byte format
    private static final int[] GROUND_SPEED = new int[]{432, 433, 434, 435, 436, 437, 438, 439, 424, 425, 426, 427, 428,
            429, 430, 431, 416, 417, 418, 419, 420, 421, 422, 423, 408, 409, 410, 411, 412, 413, 414, 415};

    //Original signed 32-bit big endian field rearranged in little endian byte format
    private static final int[] HEADING_TRUE = new int[]{448, 449, 450, 451, 452, 453, 454, 455, 440, 441, 442, 443,
            444, 445, 446, 447};

    private static final int[] UTC_TIME_SECOND = new int[]{456, 457, 458, 459, 460, 461, 462, 463};
    private static final int[] UTC_TIME_MINUTE = new int[]{464, 465, 466, 467, 468, 469, 470, 471};
    private static final int[] UTC_TIME_HOUR = new int[]{472, 473, 474, 475, 476, 477, 478, 479};
    private static final int[] UTC_TIME_DAY = new int[]{480, 481, 482, 483, 484, 485, 486, 487};
    private static final int[] UTC_TIME_MONTH = new int[]{488, 489, 490, 491, 492, 493, 494, 495};
    private static final int[] UTC_TIME_YEAR = new int[]{496, 497, 498, 499, 500, 501, 502, 503, 504, 505, 506, 507,
        508, 509, 510, 511};

    private CellocatorRadioIdentifier mSourceRadioId;
    private CommunicationControl mCommunicationControl;
    private Long mUTCTimestamp;

    /**
     * Constructs a parser for a header contained within a binary message starting at the offset.
     *
     * @param header for this message
     * @param message containing the packet
     * @param offset to the packet within the message
     */
    public LocationStatusMessage(MCGPHeader header, CorrectedBinaryMessage message, int offset)
    {
        super(header, message, offset);
    }

    /**
     * Source radio or unit ID
     */
    public CellocatorRadioIdentifier getRadioId()
    {
        if(mSourceRadioId == null)
        {
            mSourceRadioId = CellocatorRadioIdentifier.createFrom(getMessage().getInt(SOURCE_UNIT_ID, getOffset()));
        }

        return mSourceRadioId;
    }

    /**
     * Communication control bitmap field parser.
     */
    public CommunicationControl getCommunicationControl()
    {
        if(mCommunicationControl == null)
        {
            mCommunicationControl = new CommunicationControl(getMessage().getInt(COMMUNICATION_CONTROL, getOffset()));
        }

        return mCommunicationControl;
    }

    /**
     * Message number.  This is a one-up sequence maintained by the source unit that is reset to zero after
     * each power cycle and used to track the message through receipt.
     */
    public int getMessageNumerator()
    {
        return getMessage().getInt(MESSAGE_NUMERATOR, getOffset());
    }

    /**
     * Number of satellites used by the GPS for calculating the position/fix.
     */
    public int getSatelliteCount()
    {
        return getMessage().getInt(GPS_SATELLITES_USED, getOffset());
    }

    /**
     * Latitude in degrees decimal format for WGS-84 datum
     */
    public double getLatitude()
    {
        int value = getMessage().getInt(LATITUDE, getOffset());
        double radians = value / 1E8d;
        return FastMath.toDegrees(radians);
    }

    /**
     * Longitude in degrees decimal format for WGS-84 datum
     */
    public double getLongitude()
    {
        int value = getMessage().getInt(LONGITUDE, getOffset());
        double radians = value / 1E8d;
        return FastMath.toDegrees(radians);
    }

    /**
     * Altitude in meters
     */
    public double getAltitude()
    {
        int centimeters = getMessage().getInt(ALTITUDE, getOffset());
        return centimeters / 1E2d;
    }

    /**
     * Speed over ground in kph
     */
    public double getSpeed()
    {
        int centimetersPerSecond = getMessage().getInt(GROUND_SPEED, getOffset());
        double kilometersPerSecond = centimetersPerSecond / 1E5d;
        return kilometersPerSecond * 3600;
    }

    /**
     * Heading relative to true North, 0-360 degrees.
     */
    public double getHeading()
    {
        int headingThousandthsRadian = getMessage().getInt(HEADING_TRUE, getOffset());
        double radians = headingThousandthsRadian / 1E3d;
        return FastMath.toDegrees(radians);
    }

    /**
     * UTC timestamp for the location and status
     * @return timestamp in milliseconds since epoch
     */
    public long getUTCTimestamp()
    {
        if(mUTCTimestamp == null)
        {
            int year = getMessage().getInt(UTC_TIME_YEAR, getOffset());
            int month = getMessage().getInt(UTC_TIME_MONTH, getOffset());
            int day = getMessage().getInt(UTC_TIME_DAY, getOffset());
            int hour = getMessage().getInt(UTC_TIME_HOUR, getOffset());
            int minute = getMessage().getInt(UTC_TIME_MINUTE, getOffset());
            int second = getMessage().getInt(UTC_TIME_SECOND, getOffset());

            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
            calendar.clear();
            calendar.set(year, month, day, hour, minute, second);
            mUTCTimestamp = calendar.getTimeInMillis();
        }

        return mUTCTimestamp;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("CELLOCATOR RADIO:").append(getRadioId());
        sb.append(" GPS LOCATION: ").append(getLatitude()).append(" ").append(getLongitude());
        sb.append(" HEADING:").append(getHeading());
        sb.append(" SPEED:").append(getSpeed()).append("kph");
        sb.append(" AT:").append(new Date(getUTCTimestamp()));
        sb.append(" MESSAGE #").append(getMessageNumerator());
        return sb.toString();
    }
}
