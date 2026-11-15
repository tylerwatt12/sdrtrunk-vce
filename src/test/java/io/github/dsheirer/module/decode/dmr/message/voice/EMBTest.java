/* Copyright (C) 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.dmr.message.voice;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.dmr.message.type.LCSS;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EMBTest
{
    //ETSI TS 102 361-1 V2.7.1 (2026-05), B.3.2 table B.12, ordered by input bit weight.
    private static final int[] GENERATOR_ROWS = {0x0273, 0x04E5, 0x09C9, 0x11E2, 0x21B7, 0x411E, 0x804F};

    @Test
    void acceptsEveryCleanCodewordAndCorrectsEverySingleBitError()
    {
        for(int data = 0; data < 128; data++)
        {
            int word = encode(data);
            assertPayload(new EMB(message(word)), data, 0);

            for(int bit = 0; bit < 16; bit++)
            {
                assertPayload(new EMB(message(word ^ (1 << bit))), data, 1);
            }
        }
    }

    @Test
    void rejectsTwoBitErrorsAcrossAllColorCodes()
    {
        for(int data = 0; data < 128; data++)
        {
            EMB embedded = new EMB(message(encode(data) ^ 0x0003));
            assertFalse(embedded.isValid(), "payload " + data);
        }
    }

    private static void assertPayload(EMB embedded, int data, int corrected)
    {
        assertTrue(embedded.isValid(), "payload " + data);
        assertEquals(data >>> 3, embedded.getColorCode());
        assertEquals((data & 4) != 0, embedded.isEncrypted());
        assertEquals(LCSS.fromValue(data & 3), embedded.getLCSS());
        assertEquals(corrected, embedded.getMessage().getCorrectedBitCount());
    }

    private static CorrectedBinaryMessage message(int word)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(16);
        message.load(0, 16, word);
        return message;
    }

    private static int encode(int data)
    {
        int word = 0;

        for(int bit = 0; bit < GENERATOR_ROWS.length; bit++)
        {
            if((data & (1 << bit)) != 0)
            {
                word ^= GENERATOR_ROWS[bit];
            }
        }

        return word;
    }
}
