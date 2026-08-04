/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.edac.bch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BCH_63_16_23_P25Test
{
    /** TIA-102.BAAA-A section 8.5.2 generator-matrix codeword for NAC 0x293 and DUID 0x7. */
    private static final String CODEWORD =
        "0010100100110111111110001000010100010100101010111010111011001100";

    @Test
    void acceptsStandardsDerivedCodeword()
    {
        CorrectedBinaryMessage message = message();

        new BCH_63_16_23_P25().decode(message);

        assertEquals(0, message.getCorrectedBitCount());
        assertEquals(0x293, message.getInt(BCH_63_16_23_P25.NAC_FIELD));
        assertEquals(0x7, message.getInt(BCH_63_16_23_P25.DUID_FIELD));
        assertEquals(CODEWORD, message.toString());
    }

    @Test
    void correctsEverySingleBitErrorInBCHCodeword()
    {
        BCH_63_16_23_P25 decoder = new BCH_63_16_23_P25();

        for(int bit = 0; bit < 63; bit++)
        {
            CorrectedBinaryMessage message = message();
            message.flip(bit);

            decoder.decode(message);

            assertEquals(1, message.getCorrectedBitCount(), "bit " + bit);
            assertEquals(CODEWORD, message.toString(), "bit " + bit);
        }
    }

    @Test
    void correctsRepresentativePatternsThroughElevenErrors()
    {
        BCH_63_16_23_P25 decoder = new BCH_63_16_23_P25();
        Random random = new Random(0x102BAAAAL);

        for(int errorCount = 2; errorCount <= decoder.getMaxErrorCorrection(); errorCount++)
        {
            for(int sample = 0; sample < 32; sample++)
            {
                CorrectedBinaryMessage message = message();
                Set<Integer> errorBits = new LinkedHashSet<>();

                while(errorBits.size() < errorCount)
                {
                    errorBits.add(random.nextInt(63));
                }

                for(int bit: errorBits)
                {
                    message.flip(bit);
                }

                decoder.decode(message);

                String context = errorCount + " errors at " + errorBits;
                assertEquals(errorCount, message.getCorrectedBitCount(), context);
                assertEquals(CODEWORD, message.toString(), context);
            }
        }
    }

    private static CorrectedBinaryMessage message()
    {
        return CorrectedBinaryMessage.load(CODEWORD);
    }
}
