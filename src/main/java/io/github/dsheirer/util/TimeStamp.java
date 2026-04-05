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
package io.github.dsheirer.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimeStamp
{
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(SYSTEM_ZONE);
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss").withZone(SYSTEM_ZONE);
    public static final DateTimeFormatter TIME_WITH_MILLISECONDS_FORMAT =
        DateTimeFormatter.ofPattern("HHmmss.SSS").withZone(SYSTEM_ZONE);
    public static final DateTimeFormatter DATE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(SYSTEM_ZONE);
    public static final DateTimeFormatter DATE_TIME_FORMAT_FILE =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(SYSTEM_ZONE);
    public static final DateTimeFormatter DATE_TIME_MILLIS_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd HHmmss.SSS").withZone(SYSTEM_ZONE);

    private TimeStamp()
    {
    }

    /**
     * Returns the current system date formatted as yyyy-MM-dd
     */
    public static synchronized String getFormattedDate()
    {
        return getFormattedDate(System.currentTimeMillis());
    }

    /**
     * Returns the timestamp formatted as a date of yyyy-MM-dd
     */
    public static synchronized String getFormattedDate(long timestamp)
    {
        return DATE_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    /**
     * Date time formatted for use in a file.
     * @param timestamp to format
     * @return format string
     */
    public static String getFileFormattedDateTime(long timestamp)
    {
        return DATE_TIME_FORMAT_FILE.format(Instant.ofEpochMilli(timestamp));
    }

    /**
     * Current date and time formatted for use in a file.
     * @return format string
     */
    public static String getFileFormattedDateTime()
    {
        return getFileFormattedDateTime(System.currentTimeMillis());
    }

    /**
     * Creates formatted date and timestamp
     * @param timestamp to format
     * @return formatted date and time
     */
    public static String getFormattedDateTime(long timestamp)
    {
        return DATE_TIME_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    /**
     * Creates formatted date and timestamp using now as the timestamp.
     * @return formatted date and time
     */
    public static String getFormattedDateTime()
    {
        return getFormattedDateTime(System.currentTimeMillis());
    }

    /**
     * Returns the current system time formatted as HH:mm:ss
     */
    public static synchronized String getFormattedTime()
    {
        return getFormattedTime(System.currentTimeMillis());
    }

    /**
     * Returns the timestamp formatted as a time of HH:mm:ss
     */
    public static synchronized String getFormattedTime(long timestamp)
    {
        return TIME_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    /**
     * Returns the timestamp formatted as a time of HH:mm:ss
     */
    public static synchronized String getFormattedTimeWithMilliseconds(long timestamp)
    {
        return TIME_WITH_MILLISECONDS_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    /**
     * Returns current system time formatted as yyyy-MM-dd*HH:mm:ss
     * with the * representing the separator attribute
     */
    public static synchronized String getTimeStamp(String separator)
    {
        return getTimeStamp(System.currentTimeMillis(), separator);
    }

    /**
     * Returns timestamp formatted as yyyy-MM-dd*HH:mm:ss
     * with the * representing the separator attribute
     */
    public static synchronized String getTimeStamp(long timestamp, String separator)
    {
        StringBuilder sb = new StringBuilder();

        sb.append(getFormattedDate(timestamp));
        sb.append(separator);
        sb.append(getFormattedTime(timestamp));

        return sb.toString();
    }

    /**
     * Returns current system time formatted as yyyy-MM-dd*HH:mm:ss.SSS
     * with the * representing the separator attribute
     */
    public static synchronized String getLongTimeStamp(String separator)
    {
        return getLongTimeStamp(System.currentTimeMillis(), separator);
    }

    /**
     * Returns timestamp formatted as yyyy-MM-dd*HH:mm:ss.SSS
     * with the * representing the separator attribute
     */
    public static synchronized String getLongTimeStamp(long timestamp, String separator)
    {
        StringBuilder sb = new StringBuilder();

        sb.append(getFormattedDate(timestamp));
        sb.append(separator);
        sb.append(getFormattedTimeWithMilliseconds(timestamp));

        return sb.toString();
    }

}
