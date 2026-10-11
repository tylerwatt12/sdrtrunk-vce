/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.reference;

/** Temporary WUID validity shared by the Phase 1 and Phase 2 service broadcasts. */
public final class P25WuidLifetime
{
    public static final int MINIMUM_MINUTES = 240;
    public static final int MAXIMUM_MINUTES = 7_860;
    /** Normalized value for a received 0xFF, distinct from an unreceived/null lifetime. */
    public static final int NO_EXPIRY_MINUTES = 0;

    private P25WuidLifetime() {}

    /** TIA-102.AABC-E (April 2019), 6.2.19: 0..254 are finite; 0xFF disables the validity timer. */
    public static int decodeMinutes(int receivedValue)
    {
        if(receivedValue < 0 || receivedValue > 0xFF)
        {
            throw new IllegalArgumentException("WUID validity requires an unsigned octet");
        }
        return receivedValue == 0xFF ? NO_EXPIRY_MINUTES : MINIMUM_MINUTES + receivedValue * 30;
    }

    public static boolean isValidMinutes(Integer minutes)
    {
        return minutes != null && (minutes == NO_EXPIRY_MINUTES ||
            minutes >= MINIMUM_MINUTES && minutes <= MAXIMUM_MINUTES && minutes % 30 == 0);
    }

    /** Invalid or missing values remain unknown; they must never acquire advertised-value provenance. */
    public static Integer advertisedMinutes(Integer minutes)
    {
        return isValidMinutes(minutes) ? minutes : null;
    }

    public static String describe(int receivedValue)
    {
        int minutes = decodeMinutes(receivedValue);
        return minutes == NO_EXPIRY_MINUTES ? "ROAMING WUIDS VALID FOR: NO EXPIRY" :
            "ROAMING WUIDS VALID FOR:" + minutes / 60 + (minutes % 60 == 30 ? ":30" : ":00") + " HOURS";
    }
}
