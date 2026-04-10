/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2018 Dennis Sheirer
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
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.tait;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.edac.CRC;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.Message;
import io.github.dsheirer.module.decode.tait.identifier.TaitIdentifier;
import io.github.dsheirer.protocol.Protocol;
import org.jdesktop.swingx.mapviewer.GeoPosition;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

public class Tait1200GPSMessage extends Message
{
    private static final IntField SIZE = IntField.length16(20);
    private static final IntField FROM_DIGIT_1 = IntField.length8(36);
    private static final IntField FROM_DIGIT_2 = IntField.length8(44);
    private static final IntField FROM_DIGIT_3 = IntField.length8(52);
    private static final IntField FROM_DIGIT_4 = IntField.length8(60);
    private static final IntField FROM_DIGIT_5 = IntField.length8(68);
    private static final IntField FROM_DIGIT_6 = IntField.length8(76);
    private static final IntField FROM_DIGIT_7 = IntField.length8(84);
    private static final IntField FROM_DIGIT_8 = IntField.length8(92);

    private static final IntField SIZE_2 = IntField.length16(188);
    private static final IntField TO_DIGIT_1 = IntField.length8(204);
    private static final IntField TO_DIGIT_2 = IntField.length8(212);
    private static final IntField TO_DIGIT_3 = IntField.length8(220);
    private static final IntField TO_DIGIT_4 = IntField.length8(228);
    private static final IntField TO_DIGIT_5 = IntField.length8(236);
    private static final IntField TO_DIGIT_6 = IntField.length8(244);
    private static final IntField TO_DIGIT_7 = IntField.length8(252);
    private static final IntField TO_DIGIT_8 = IntField.length8(260);

    private static final IntField HOUR_TENS = IntField.range(293, 295);
    private static final IntField HOUR_ONES = IntField.length4(296);
    private static final IntField MINUTES_TENS = IntField.range(301, 303);
    private static final IntField MINUTES_ONES = IntField.length4(304);
    private static final IntField SECONDS_TENS = IntField.range(309, 311);
    private static final IntField SECONDS_ONES = IntField.length4(312);
    private static final IntField LATITUDE_SIGN = IntField.length2(317);
    private static final IntField LATITUDE_DEGREES_TENS = IntField.length4(320);
    private static final IntField LATITUDE_DEGREES_ONES = IntField.length4(324);
    private static final IntField LATITUDE_MINUTES_TENS = IntField.range(329, 331);
    private static final IntField LATITUDE_MINUTES_ONES = IntField.length4(332);
    private static final IntField LATITUDE_SECONDS_HUND = IntField.length4(336);
    private static final IntField LATITUDE_SECONDS_TENS = IntField.range(340, 342);
    private static final IntField LATITUDE_SECONDS_ONES = IntField.length4(344);
    private static final IntField LONGITUDE_SIGN = IntField.length2(349);
    private static final int LONGITUDE_DEGREES_HUNDREDS = 351;
    private static final IntField LONGITUDE_DEGREES_TENS = IntField.length4(352);
    private static final IntField LONGITUDE_DEGREES_ONES = IntField.length4(356);
    private static final IntField LONGITUDE_MINUTES_TENS = IntField.range(361, 363);
    private static final IntField LONGITUDE_MINUTES_ONES = IntField.length4(364);
    private static final IntField LONGITUDE_SECONDS_HUND = IntField.length4(368);
    private static final IntField LONGITUDE_SECONDS_TENS = IntField.length4(372);
    private static final IntField LONGITUDE_SECONDS_ONES = IntField.length4(376);
    private static final IntField DATE_DAY_TENS = IntField.range(381, 383);
    private static final IntField DATE_DAY_ONES = IntField.length4(384);

    //TODO: verify against the Tait GPS payload layout; this field is currently used as both DATE_MONTH and
    // SPEED_HUNDREDS, so the semantics are unclear and should not be "cleaned up" blindly.
    private static final int[] DATE_MONTH = {388, 389, 390, 391};
    private static final int[] SPEED_HUNDREDS = {388, 389, 390, 391};

    private static final IntField SPEED_TENS = IntField.length4(392);
    private static final IntField SPEED_ONES = IntField.length4(396);
    private static final IntField SPEED_TENTHS = IntField.length4(400);

    private BinaryMessage mMessage;
    private TaitIdentifier mFromIdentifier;
    private TaitIdentifier mToIdentifier;
    private List<Identifier> mIdentifiers;
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyyMMdd HHmmss");

    public Tait1200GPSMessage(BinaryMessage message)
    {
        mMessage = message;
        mDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public TaitIdentifier getFromIdentifier()
    {
        if(mFromIdentifier == null)
        {
            StringBuilder sb = new StringBuilder();

            sb.append(getCharacter(FROM_DIGIT_1));
            sb.append(getCharacter(FROM_DIGIT_2));
            sb.append(getCharacter(FROM_DIGIT_3));
            sb.append(getCharacter(FROM_DIGIT_4));
            sb.append(getCharacter(FROM_DIGIT_5));
            sb.append(getCharacter(FROM_DIGIT_6));
            sb.append(getCharacter(FROM_DIGIT_7));
            sb.append(getCharacter(FROM_DIGIT_8));

            mFromIdentifier = TaitIdentifier.createFrom(sb.toString().trim());
        }

        return mFromIdentifier;
    }

    public TaitIdentifier getToIdentifier()
    {
        if(mToIdentifier == null)
        {
            StringBuilder sb = new StringBuilder();

            sb.append(getCharacter(TO_DIGIT_1));
            sb.append(getCharacter(TO_DIGIT_2));
            sb.append(getCharacter(TO_DIGIT_3));
            sb.append(getCharacter(TO_DIGIT_4));
            sb.append(getCharacter(TO_DIGIT_5));
            sb.append(getCharacter(TO_DIGIT_6));
            sb.append(getCharacter(TO_DIGIT_7));
            sb.append(getCharacter(TO_DIGIT_8));

            mToIdentifier = TaitIdentifier.createTo(sb.toString().trim());
        }

        return mToIdentifier;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        if(mIdentifiers == null)
        {
            mIdentifiers = new ArrayList<>();
            mIdentifiers.add(getFromIdentifier());
            mIdentifiers.add(getToIdentifier());
        }

        return mIdentifiers;
    }

    public boolean isValid()
    {
        //TODO: Override until we figure out the CRC
        return true;
    }

    public int getMessage1Size()
    {
        return mMessage.getInt(SIZE);
    }

    public int getMessage2Size()
    {
        return mMessage.getInt(SIZE_2);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();

        sb.append("GPS FROM:").append(getFromIdentifier());
        sb.append(" TO:").append(getToIdentifier());
        sb.append(" LOCATION:");

        GeoPosition location = getGPSLocation();

        sb.append(location.getLatitude());
        sb.append(" ");
        sb.append(location.getLongitude());

        sb.append(" SPEED:").append(getSpeed()).append("KPH");
        sb.append(" GPS TIME:");
        sb.append(mDateFormat.format(new Date(getGPSTime())));

        sb.append(" ");
        sb.append(mMessage.toString());

        return sb.toString();
    }

    public GeoPosition getGPSLocation()
    {
        double latitude = mMessage.getInt(LATITUDE_DEGREES_TENS) * 10.0d;
        latitude += mMessage.getInt(LATITUDE_DEGREES_ONES);
        latitude += (double)mMessage.getInt(LATITUDE_MINUTES_TENS) / 6.0d;
        latitude += (double)mMessage.getInt(LATITUDE_MINUTES_ONES) / 60.0d;
        latitude += (double)mMessage.getInt(LATITUDE_SECONDS_HUND) / 600.0d;
        latitude += (double)mMessage.getInt(LATITUDE_SECONDS_TENS) / 6000.0d;
        latitude += (double)mMessage.getInt(LATITUDE_SECONDS_ONES) / 60000.0d;

        if(mMessage.getInt(LATITUDE_SIGN) == 0)
        {
            latitude *= -1;
        }

        double longitude = mMessage.get(LONGITUDE_DEGREES_HUNDREDS) ? 100.0d : 0.0d;

        longitude += mMessage.getInt(LONGITUDE_DEGREES_TENS) * 10.0d;
        longitude += mMessage.getInt(LONGITUDE_DEGREES_ONES);
        longitude += (double)mMessage.getInt(LONGITUDE_MINUTES_TENS) / 6.0d;
        longitude += (double)mMessage.getInt(LONGITUDE_MINUTES_ONES) / 60.0d;
        longitude += (double)mMessage.getInt(LONGITUDE_SECONDS_HUND) / 600.0d;
        longitude += (double)mMessage.getInt(LONGITUDE_SECONDS_TENS) / 6000.0d;
        longitude += (double)mMessage.getInt(LONGITUDE_SECONDS_ONES) / 60000.0d;

        if(mMessage.getInt(LONGITUDE_SIGN) == 0)
        {
            longitude = -1;
        }

        return new GeoPosition(latitude, longitude);
    }

    public long getGPSTime()
    {
        Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));

        cal.clear();

        /* Use current time to get current year */
        cal.setTimeInMillis(System.currentTimeMillis());

        //TODO: this field is either the month field, or the speed hundredths field
        cal.set(Calendar.MONTH, mMessage.getInt(DATE_MONTH));

        int day = mMessage.getInt(DATE_DAY_TENS) * 10 +
            mMessage.getInt(DATE_DAY_ONES);
        cal.set(Calendar.DAY_OF_MONTH, day);

        cal.set(Calendar.HOUR_OF_DAY, (mMessage.getInt(HOUR_TENS) * 10) +
            mMessage.getInt(HOUR_ONES));
        cal.set(Calendar.MINUTE, (mMessage.getInt(MINUTES_TENS) * 10) +
            mMessage.getInt(MINUTES_ONES));
        cal.set(Calendar.SECOND, (mMessage.getInt(SECONDS_TENS) * 10) +
            mMessage.getInt(SECONDS_ONES));
        cal.set(Calendar.MILLISECOND, 0);

        return cal.getTimeInMillis();
    }

    public double getSpeed()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getDigit(SPEED_HUNDREDS));
        sb.append(getDigit(SPEED_TENS));
        sb.append(getDigit(SPEED_ONES));
        sb.append(".");
        sb.append(getDigit(SPEED_TENTHS));

        try
        {
            return Double.parseDouble(sb.toString());
        }
        catch(Exception e)
        {
            //Do nothing, we couldn't parse the value
        }

        return 0;
    }

    private String getDigit(IntField field)
    {
        int value = mMessage.getInt(field);
        return (0 <= value && value <= 9) ? String.valueOf(value) : "?";
    }

    private String getDigit(int[] field)
    {
        int value = mMessage.getInt(field);
        return (0 <= value && value <= 9) ? String.valueOf(value) : "?";
    }

    public char getCharacter(IntField bits)
    {
        int value = mMessage.getInt(bits);
        return (char)value;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.TAIT1200;
    }
}
