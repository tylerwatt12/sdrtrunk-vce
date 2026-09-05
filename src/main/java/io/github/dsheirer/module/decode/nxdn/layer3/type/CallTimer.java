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

package io.github.dsheirer.module.decode.nxdn.layer3.type;

/**
 * Call Timer values enumeration
 */
public enum CallTimer
{
    UNSPECIFIED("UNSPECIFIED", 0),
    CT1("15 SECONDS", 15),
    CT2("30 SECONDS", 30),
    CT3("45 SECONDS", 45),
    CT4("60 SECONDS", 60),
    CT5("75 SECONDS", 75),
    CT6("90 SECONDS", 90),
    CT7("105 SECONDS", 105),
    CT8("120 SECONDS", 120),
    CT9("135 SECONDS", 135),
    CT10("150 SECONDS", 150),
    CT11("165 SECONDS", 165),
    CT12("180 SECONDS", 180),
    CT13("210 SECONDS", 210),
    CT14("240 SECONDS", 240),
    CT15("270 SECONDS", 270),
    CT16("300 SECONDS", 300),
    CT17("330 SECONDS", 330),
    CT18("360 SECONDS", 360),
    CT19("390 SECONDS", 390),
    CT20("420 SECONDS", 420),
    CT21("450 SECONDS", 450),
    CT22("480 SECONDS", 480),
    CT23("510 SECONDS", 510),
    CT24("540 SECONDS", 540),
    CT25("570 SECONDS", 570),
    CT26("600 SECONDS", 600);

    private final String mLabel;
    private final int mSeconds;

    /**
     * Constructs an instance
     * @param label to display
     */
    CallTimer(String label, int seconds)
    {
        mLabel = label;
        mSeconds = seconds;
    }

    public static CallTimer fromValue(int value)
    {
        return switch(value)
        {
            case 1 -> CT1;
            case 2 -> CT2;
            case 3 -> CT3;
            case 4 -> CT4;
            case 5 -> CT5;
            case 6 -> CT6;
            case 7 -> CT7;
            case 8 -> CT8;
            case 9 -> CT9;
            case 10 -> CT10;
            case 11 -> CT11;
            case 12 -> CT12;
            case 13 -> CT13;
            case 14 -> CT14;
            case 15 -> CT15;
            case 16 -> CT16;
            case 17 -> CT17;
            case 18 -> CT18;
            case 19 -> CT19;
            case 20 -> CT20;
            case 21 -> CT21;
            case 22 -> CT22;
            case 23 -> CT23;
            case 24 -> CT24;
            case 25 -> CT25;
            case 26 -> CT26;
            default -> UNSPECIFIED;
        };
    }

    /** Numeric timer value used by structured telemetry and storage without parsing the display label. */
    public int getSeconds()
    {
        return mSeconds;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
