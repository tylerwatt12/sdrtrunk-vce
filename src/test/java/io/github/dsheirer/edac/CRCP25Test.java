/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.edac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import org.junit.jupiter.api.Test;

class CRCP25Test
{
    private static final String CRC_16_DATA = "123456789ABCDEF00123";
    private static final int CRC_16 = 0xA2E5;
    private static final String CRC_9_DATA = "00112233445566778899AABBCCDDEEFF";
    private static final int CRC_9_SERIAL = 0x35;
    private static final int CRC_9 = 0x0B7;
    private static final String CRC_32_DATA = "0123456789ABCDEF";
    private static final long CRC_32 = 0x9F159AA0L;

    @Test
    void acceptsSpecifiedInvertedCRC16AndRejectsItsComplement()
    {
        BinaryMessage data = BinaryMessage.loadHex(CRC_16_DATA);
        assertEquals(CRC_16, independentCRC(data, 16, 0x11021L, 0xFFFFL));

        CorrectedBinaryMessage valid = message(CRC_16_DATA, CRC_16, 16);
        assertEquals(0, CRCP25.correctCCITT80(valid, 0, 80));

        CorrectedBinaryMessage complemented = message(CRC_16_DATA, CRC_16 ^ 0xFFFF, 16);
        assertEquals(2, CRCP25.correctCCITT80(complemented, 0, 80));
    }

    @Test
    void correctsEverySingleCRC16ProtectedBitAtItsActualPosition()
    {
        String expected = CRC_16_DATA + "A2E5";

        for(int bit = 0; bit < 96; bit++)
        {
            CorrectedBinaryMessage error = message(CRC_16_DATA, CRC_16, 16);
            error.flip(bit);
            assertEquals(1, CRCP25.correctCCITT80(error, 0, 80), "Protected bit " + bit);
            assertEquals(expected, error.toHexString(), "Protected bit " + bit);
        }
    }

    @Test
    void acceptsSpecifiedInvertedCRC9AndRejectsItsComplement()
    {
        BinaryMessage protectedBits = new BinaryMessage(135);
        protectedBits.load(0, 7, CRC_9_SERIAL);
        protectedBits.load(7, BinaryMessage.loadHex(CRC_9_DATA));
        assertEquals(CRC_9, independentCRC(protectedBits, 9, 0x259L, 0x1FFL));

        BinaryMessage valid = confirmedBlock(CRC_9);
        assertEquals(CRC.PASSED, CRCP25.checkCRC9(valid, 0));

        BinaryMessage complemented = confirmedBlock(CRC_9 ^ 0x1FF);
        assertEquals(CRC.FAILED_CRC, CRCP25.checkCRC9(complemented, 0));

        valid.flip(16);
        assertEquals(CRC.FAILED_CRC, CRCP25.checkCRC9(valid, 0));
    }

    @Test
    void checksPacketCRC32AcrossOnlyThePrecedingPayload()
    {
        BinaryMessage data = BinaryMessage.loadHex(CRC_32_DATA);
        assertEquals(CRC_32, independentCRC(data, 32, 0x104C11DB7L, 0xFFFFFFFFL));

        BinaryMessage valid = BinaryMessage.loadHex(CRC_32_DATA + "9F159AA0");
        assertEquals(CRC.PASSED, CRCP25.checkCRC32(valid, 0, 64));

        BinaryMessage complemented = BinaryMessage.loadHex(CRC_32_DATA + "60EA655F");
        assertEquals(CRC.FAILED_CRC, CRCP25.checkCRC32(complemented, 0, 64));

        valid.flip(17);
        assertEquals(CRC.FAILED_CRC, CRCP25.checkCRC32(valid, 0, 64));
    }

    private static CorrectedBinaryMessage message(String data, long crc, int crcLength)
    {
        BinaryMessage binaryMessage = BinaryMessage.loadHex(data);
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(binaryMessage.size() + crcLength);
        message.load(0, binaryMessage);
        message.load(binaryMessage.size(), crcLength, crc);
        return message;
    }

    private static BinaryMessage confirmedBlock(int crc)
    {
        BinaryMessage message = new BinaryMessage(144);
        message.load(0, 7, CRC_9_SERIAL);
        message.load(7, 9, crc);
        message.load(16, BinaryMessage.loadHex(CRC_9_DATA));
        return message;
    }

    /**
     * Independent polynomial long division used to generate the test CRCs.  The production implementation uses a
     * shift-register calculation.
     */
    private static long independentCRC(BinaryMessage message, int crcLength, long polynomial, long inversion)
    {
        boolean[] dividend = new boolean[message.size() + crcLength];

        for(int bit = 0; bit < message.size(); bit++)
        {
            dividend[bit] = message.get(bit);
        }

        for(int bit = 0; bit < message.size(); bit++)
        {
            if(dividend[bit])
            {
                for(int polynomialBit = 0; polynomialBit <= crcLength; polynomialBit++)
                {
                    int shift = crcLength - polynomialBit;

                    if(((polynomial >> shift) & 1L) == 1L)
                    {
                        dividend[bit + polynomialBit] = !dividend[bit + polynomialBit];
                    }
                }
            }
        }

        long remainder = 0;

        for(int bit = message.size(); bit < dividend.length; bit++)
        {
            remainder <<= 1;

            if(dividend[bit])
            {
                remainder |= 1;
            }
        }

        return remainder ^ inversion;
    }
}
