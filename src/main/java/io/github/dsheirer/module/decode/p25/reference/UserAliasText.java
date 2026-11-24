/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.reference;

import java.nio.charset.StandardCharsets;

/** Bounded ASCII User Alias text; preserves spaces within a complete name. */
public final class UserAliasText
{
    private UserAliasText() {}

    /** Null padding and older senders' trailing spaces are removed only after the complete text is joined. */
    public static String decode(byte[] payload)
    {
        if(payload == null || payload.length == 0 || payload.length > 14) return null;
        int end = payload.length;
        boolean padding = false;
        for(int index = 0; index < payload.length; index++)
        {
            int value = Byte.toUnsignedInt(payload[index]);
            if(value == 0)
            {
                if(!padding) end = index;
                padding = true;
            }
            else if(padding && value != 0x20 || value < 0x20 || value > 0x7E)
            {
                return null;
            }
        }
        while(end > 0 && payload[end - 1] == 0x20) end--;
        return end > 0 ? new String(payload, 0, end, StandardCharsets.US_ASCII) : null;
    }
}
