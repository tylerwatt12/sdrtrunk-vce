/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.edac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import org.junit.jupiter.api.Test;

class Golay24Test
{
    private static final int GENERATOR_POLYNOMIAL = 0xC75;
    private static final int[] TEST_OFFSETS = {0, 24};
    private static final int[] REPRESENTATIVE_DATA = {0x000, 0x001, 0x135, 0xA5B, 0xFFF};
    private static final int MESSAGE_SIZE = 72;

    @Test
    void acceptsEveryCleanCodewordAtZeroAndNonzeroOffsets()
    {
        for(int data = 0; data <= 0xFFF; data++)
        {
            int codeword = encode(data);

            for(int offset: TEST_OFFSETS)
            {
                CorrectedBinaryMessage message = createMessage(codeword, offset);
                BinaryMessage original = message.copy();
                String testContext = context(data, offset, 0);

                assertEquals(0, Golay24.checkAndCorrect(message, offset), testContext);
                assertEquals(original, message, testContext);
                assertEquals(0, message.getCorrectedBitCount(), testContext);
            }
        }
    }

    @Test
    void correctsEveryOneTwoAndThreeBitErrorPatternAtZeroAndNonzeroOffsets()
    {
        for(int data: REPRESENTATIVE_DATA)
        {
            int codeword = encode(data);

            for(int offset: TEST_OFFSETS)
            {
                for(int first = 0; first < 24; first++)
                {
                    assertCorrected(codeword, offset, bit(first));

                    for(int second = first + 1; second < 24; second++)
                    {
                        assertCorrected(codeword, offset, bit(first) | bit(second));

                        for(int third = second + 1; third < 24; third++)
                        {
                            assertCorrected(codeword, offset, bit(first) | bit(second) | bit(third));
                        }
                    }
                }
            }
        }
    }

    @Test
    void rejectsEveryFourBitErrorPatternWithoutMutatingTheMessage()
    {
        int codeword = encode(0xA5B);

        for(int offset: TEST_OFFSETS)
        {
            for(int first = 0; first < 24; first++)
            {
                for(int second = first + 1; second < 24; second++)
                {
                    for(int third = second + 1; third < 24; third++)
                    {
                        for(int fourth = third + 1; fourth < 24; fourth++)
                        {
                            int errorPattern = bit(first) | bit(second) | bit(third) | bit(fourth);
                            CorrectedBinaryMessage message = createMessage(codeword ^ errorPattern, offset);
                            BinaryMessage original = message.copy();
                            message.setCorrectedBitCount(7);

                            assertEquals(2, Golay24.checkAndCorrect(message, offset),
                                () -> context(0xA5B, offset, errorPattern));
                            assertEquals(original, message, () -> context(0xA5B, offset, errorPattern));
                            assertEquals(7, message.getCorrectedBitCount(),
                                () -> context(0xA5B, offset, errorPattern));
                        }
                    }
                }
            }
        }
    }

    @Test
    void correctsTwelveIndependentTdulcCodewordsAndAccumulatesCorrectedBits()
    {
        CorrectedBinaryMessage clean = new CorrectedBinaryMessage(288);
        CorrectedBinaryMessage received = new CorrectedBinaryMessage(288);
        int expectedCorrectedBitCount = 0;

        for(int codewordIndex = 0; codewordIndex < 12; codewordIndex++)
        {
            int offset = codewordIndex * 24;
            int codeword = encode((codewordIndex * 0x165) & 0xFFF);
            clean.load(offset, 24, codeword);
            received.load(offset, 24, codeword);

            int errorCount = codewordIndex % 4;

            for(int error = 0; error < errorCount; error++)
            {
                received.flip(offset + ((codewordIndex * 5 + error * 7) % 24));
            }

            expectedCorrectedBitCount += errorCount;
        }

        for(int codewordIndex = 0; codewordIndex < 12; codewordIndex++)
        {
            int expectedStatus = codewordIndex % 4 == 0 ? 0 : 1;
            assertEquals(expectedStatus, Golay24.checkAndCorrect(received, codewordIndex * 24));
        }

        assertEquals(clean, received);
        assertEquals(expectedCorrectedBitCount, received.getCorrectedBitCount());
    }

    private static void assertCorrected(int codeword, int offset, int errorPattern)
    {
        CorrectedBinaryMessage message = createMessage(codeword ^ errorPattern, offset);
        BinaryMessage received = message.copy();
        message.setCorrectedBitCount(7);

        assertEquals(1, Golay24.checkAndCorrect(message, offset),
            () -> context(codeword >>> 12, offset, errorPattern));
        assertEquals(codeword, message.getInt(offset, offset + 23),
            () -> context(codeword >>> 12, offset, errorPattern));
        assertEquals(7 + Integer.bitCount(errorPattern), message.getCorrectedBitCount(),
            () -> context(codeword >>> 12, offset, errorPattern));
        assertBitsOutsideCodewordUnchanged(received, message, offset, errorPattern);
    }

    private static CorrectedBinaryMessage createMessage(int codeword, int offset)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(MESSAGE_SIZE);

        //Non-codeword sentinel bits expose any correction accidentally applied outside the requested 24-bit range.
        for(int index = 0; index < MESSAGE_SIZE; index += 5)
        {
            message.set(index);
        }

        message.load(offset, 24, codeword);
        return message;
    }

    private static void assertBitsOutsideCodewordUnchanged(BinaryMessage before, BinaryMessage after, int offset,
                                                            int errorPattern)
    {
        for(int index = 0; index < MESSAGE_SIZE; index++)
        {
            if(index < offset || index >= offset + 24)
            {
                int checkedIndex = index;
                assertEquals(before.get(index), after.get(index),
                    () -> "Bit outside codeword changed at index " + checkedIndex + ", offset=" + offset +
                        ", errors=" + String.format("%06X", errorPattern));
            }
        }
    }

    private static int encode(int data)
    {
        int dataAndChecksum = (data & 0xFFF) << 11;
        int remainder = dataAndChecksum;

        for(int bit = 22; bit >= 11; bit--)
        {
            if((remainder & (1 << bit)) != 0)
            {
                remainder ^= GENERATOR_POLYNOMIAL << (bit - 11);
            }
        }

        dataAndChecksum |= remainder;
        int parity = Integer.bitCount(dataAndChecksum) & 1;
        return (dataAndChecksum << 1) | parity;
    }

    private static int bit(int codewordIndex)
    {
        return 1 << (23 - codewordIndex);
    }

    private static String context(int data, int offset, int errorPattern)
    {
        return "data=" + String.format("%03X", data) + ", offset=" + offset +
            ", errors=" + String.format("%06X", errorPattern);
    }
}
