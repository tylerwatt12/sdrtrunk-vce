/* Copyright (C) 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1.message.hdu;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HDUMessageTest
{
    @Test
    void correctsErrorsInEveryShortenedGolayWord()
    {
        //The all-zero Golay and RS codewords form a valid header. Exercise every word independently.
        for(int word = 0; word < 36; word++)
        {
            CorrectedBinaryMessage bits = new CorrectedBinaryMessage(648);
            bits.set(word * 18 + 7);
            HDUMessage message = new HDUMessage(bits, 0x123, 1_000L);
            assertTrue(message.getHeaderData().isValid(), "word " + word);
            assertEquals(0, bits.cardinality(), "word " + word);
            assertEquals(1, bits.getCorrectedBitCount(), "word " + word);
        }
    }
}
