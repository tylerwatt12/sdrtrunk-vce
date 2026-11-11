/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.reference;

import io.github.dsheirer.bits.BinaryMessage;

/** Motorola BSI character encoding shared by the control and voice formats. */
public final class MotorolaBsi
{
    private MotorolaBsi() {}

    /** Eight MSB-first six-bit characters in octets 2–7; zero is padding. */
    public static String decodeCallsign(BinaryMessage message)
    {
        StringBuilder callsign = new StringBuilder(8);
        for(int bit = 16; bit < 64; bit += 6)
        {
            int value = message.getInt(bit, bit + 5);
            if(value != 0)
            {
                callsign.append((char)(value + 43));
            }
        }
        return callsign.toString();
    }
}
