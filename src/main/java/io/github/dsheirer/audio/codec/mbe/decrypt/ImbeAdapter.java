/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.audio.codec.mbe.decrypt;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts P25 Phase 1 transmitted 18-byte IMBE C codewords to/from OP25/mbelib-style packed 11-byte IMBE
 * parameters.  ADP operates on the packed 88 voice bits, while JMBE consumes the transmitted 144-bit/FEC frame.
 */
public class ImbeAdapter
{
    public static final int TRANSMITTED_IMBE_BYTES = 18;
    public static final int PACKED_IMBE_BYTES = 11;
    private static final int IMBE_BITS = 88;
    private static final int[] DSD_IMBE_IW = new int[]{
        0, 2, 4, 1, 3, 5, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6,
        0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6,
        0, 2, 5, 1, 3, 6, 0, 2, 5, 1, 3, 6, 0, 2, 5, 1, 3, 7,
        0, 2, 5, 1, 3, 7, 0, 2, 5, 1, 4, 7, 0, 3, 5, 2, 4, 7};
    private static final int[] DSD_IMBE_IX = new int[]{
        22, 20, 10, 20, 18, 0, 20, 18, 8, 18, 16, 13, 18, 16, 6, 16, 14, 11,
        16, 14, 4, 14, 12, 9, 14, 12, 2, 12, 10, 7, 12, 10, 0, 10, 8, 5,
        10, 8, 13, 8, 6, 3, 8, 6, 11, 6, 4, 1, 6, 4, 9, 4, 2, 6,
        4, 2, 7, 2, 0, 4, 2, 0, 5, 0, 13, 2, 0, 21, 3, 21, 11, 0};
    private static final int[] DSD_IMBE_IY = new int[]{
        1, 3, 5, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6, 0, 2, 4,
        1, 3, 6, 0, 2, 4, 1, 3, 6, 0, 2, 4, 1, 3, 6, 0, 2, 5,
        1, 3, 6, 0, 2, 5, 1, 3, 6, 0, 2, 5, 1, 3, 6, 0, 2, 5,
        1, 3, 7, 0, 2, 5, 1, 4, 7, 0, 3, 5, 2, 4, 7, 1, 3, 5};
    private static final int[] DSD_IMBE_IZ = new int[]{
        21, 19, 1, 21, 19, 9, 19, 17, 14, 19, 17, 7, 17, 15, 12, 17, 15, 5,
        15, 13, 10, 15, 13, 3, 13, 11, 8, 13, 11, 1, 11, 9, 6, 11, 9, 14,
        9, 7, 4, 9, 7, 12, 7, 5, 2, 7, 5, 10, 5, 3, 0, 5, 3, 8,
        3, 1, 5, 3, 1, 6, 1, 14, 3, 1, 22, 4, 22, 12, 1, 22, 20, 2};
    private static final int[] GOLAY23_CHECKSUMS = new int[]{
        0x63A, 0x31D, 0x7B4, 0x3DA, 0x1ED, 0x6CC, 0x366, 0x1B3, 0x6E3, 0x54B, 0x49F, 0x475};
    private static final int[] HAMMING15_ENCODING = new int[]{
        0x400F, 0x200E, 0x100D, 0x080C, 0x040B, 0x020A, 0x0109, 0x0087, 0x0046, 0x0025, 0x0013};
    private static final int[] MBELIB_HAMMING_GENERATOR = new int[]{0x7F08, 0x78E4, 0x66D2, 0x55B1};
    private static final int[] GOLAY23_CODEWORDS = new int[4096];
    private static final int[] GOLAY23_ERROR_MASKS = createGolay23ErrorMasks();
    private static final int[] HAMMING15_CODEWORDS = new int[2048];

    static
    {
        for(int data = 0; data < GOLAY23_CODEWORDS.length; data++)
        {
            GOLAY23_CODEWORDS[data] = encodeGolay23(data);
        }

        for(int data = 0; data < HAMMING15_CODEWORDS.length; data++)
        {
            HAMMING15_CODEWORDS[data] = encodeHamming15(data);
        }
    }

    private ImbeAdapter()
    {
    }

    public static byte[] decode18ToPacked11(byte[] frame) throws VoiceFrameDecryptionException
    {
        if(frame == null || frame.length != TRANSMITTED_IMBE_BYTES)
        {
            throw new VoiceFrameDecryptionException("P25 Phase 1 IMBE frame must be 18 transmitted bytes");
        }

        int[][] imbe = transmittedBytesToImbeFrame(frame);
        int c0 = decodeGolay23(rowToCodeword(imbe[0], 23));
        codewordToRow(imbe[0], 23, GOLAY23_CODEWORDS[c0]);
        xorRandomizer(imbe);

        byte[] packed = new byte[PACKED_IMBE_BYTES];
        int bitOffset = 0;

        bitOffset = writePackedBits(packed, bitOffset, c0, 12);

        for(int block = 1; block < 4; block++)
        {
            bitOffset = writePackedBits(packed, bitOffset, decodeGolay23(rowToCodeword(imbe[block], 23)), 12);
        }

        for(int block = 4; block < 7; block++)
        {
            bitOffset = writePackedBits(packed, bitOffset, decodeMbelibHamming15(rowToCodeword(imbe[block], 15)), 11);
        }

        for(int bit = 6; bit >= 0; bit--)
        {
            setPackedBit(packed, bitOffset++, imbe[7][bit]);
        }

        if(bitOffset != IMBE_BITS)
        {
            throw new VoiceFrameDecryptionException("Decoded IMBE bit count mismatch");
        }

        return packed;
    }

    public static byte[] encodePacked11To18(byte[] packed) throws VoiceFrameDecryptionException
    {
        if(packed == null || packed.length != PACKED_IMBE_BYTES)
        {
            throw new VoiceFrameDecryptionException("Packed IMBE frame must be 11 bytes");
        }

        int[][] imbe = new int[8][23];
        int bitOffset = 0;

        for(int block = 0; block < 4; block++)
        {
            int data = readPackedBits(packed, bitOffset, 12);
            bitOffset += 12;
            codewordToRow(imbe[block], 23, GOLAY23_CODEWORDS[data]);
        }

        for(int block = 4; block < 7; block++)
        {
            int data = readPackedBits(packed, bitOffset, 11);
            bitOffset += 11;
            codewordToRow(imbe[block], 15, HAMMING15_CODEWORDS[data]);
        }

        for(int bit = 6; bit >= 0; bit--)
        {
            imbe[7][bit] = getPackedBit(packed, bitOffset++);
        }

        if(bitOffset != IMBE_BITS)
        {
            throw new VoiceFrameDecryptionException("Encoded IMBE bit count mismatch");
        }

        xorRandomizer(imbe);
        return imbeFrameToTransmittedBytes(imbe);
    }

    private static int[][] transmittedBytesToImbeFrame(byte[] bytes)
    {
        int[][] imbe = new int[8][23];
        int pointer = 0;

        for(byte value: bytes)
        {
            int octet = value & 0xFF;
            int high = (octet >> 4) & 0x0F;
            int low = octet & 0x0F;

            pointer = loadDibit(imbe, pointer, (high >> 2) & 0x03);
            pointer = loadDibit(imbe, pointer, high & 0x03);
            pointer = loadDibit(imbe, pointer, (low >> 2) & 0x03);
            pointer = loadDibit(imbe, pointer, low & 0x03);
        }

        return imbe;
    }

    private static int loadDibit(int[][] imbe, int pointer, int dibit)
    {
        imbe[DSD_IMBE_IW[pointer]][DSD_IMBE_IX[pointer]] = (dibit >> 1) & 0x01;
        imbe[DSD_IMBE_IY[pointer]][DSD_IMBE_IZ[pointer]] = dibit & 0x01;
        return pointer + 1;
    }

    private static byte[] imbeFrameToTransmittedBytes(int[][] imbe)
    {
        byte[] bytes = new byte[TRANSMITTED_IMBE_BYTES];
        int bitOffset = 0;

        for(int pointer = 0; pointer < DSD_IMBE_IW.length; pointer++)
        {
            bitOffset = writeTransmittedBit(bytes, bitOffset, imbe[DSD_IMBE_IW[pointer]][DSD_IMBE_IX[pointer]]);
            bitOffset = writeTransmittedBit(bytes, bitOffset, imbe[DSD_IMBE_IY[pointer]][DSD_IMBE_IZ[pointer]]);
        }

        return bytes;
    }

    private static int writeTransmittedBit(byte[] bytes, int bitOffset, int bit)
    {
        if((bit & 0x01) != 0)
        {
            bytes[bitOffset / 8] |= (byte)(1 << (7 - (bitOffset % 8)));
        }

        return bitOffset + 1;
    }

    private static int rowToCodeword(int[] row, int width)
    {
        int codeword = 0;

        for(int bit = width - 1; bit >= 0; bit--)
        {
            codeword = (codeword << 1) | (row[bit] & 0x01);
        }

        return codeword;
    }

    private static void codewordToRow(int[] row, int width, int codeword)
    {
        for(int bit = 0; bit < width; bit++)
        {
            row[bit] = (codeword >> bit) & 0x01;
        }
    }

    private static int encodeGolay23(int data)
    {
        int checksum = 0;

        for(int bit = 0; bit < 12; bit++)
        {
            if((data & (1 << (11 - bit))) != 0)
            {
                checksum ^= GOLAY23_CHECKSUMS[bit];
            }
        }

        return ((data & 0x0FFF) << 11) | (checksum & 0x07FF);
    }

    private static int decodeGolay23(int codeword)
    {
        codeword &= 0x7FFFFF;

        for(int mask: GOLAY23_ERROR_MASKS)
        {
            int corrected = codeword ^ mask;
            int data = corrected >> 11;

            if(GOLAY23_CODEWORDS[data] == corrected)
            {
                return data;
            }
        }

        int bestData = 0;
        int bestErrors = 24;

        for(int data = 0; data < GOLAY23_CODEWORDS.length; data++)
        {
            int errors = Integer.bitCount((codeword ^ GOLAY23_CODEWORDS[data]) & 0x7FFFFF);

            if(errors < bestErrors)
            {
                bestErrors = errors;
                bestData = data;

                if(errors == 0)
                {
                    break;
                }
            }
        }

        return bestData;
    }

    private static int[] createGolay23ErrorMasks()
    {
        List<Integer> masks = new ArrayList<>(2048);
        masks.add(0);

        for(int bit = 0; bit < 23; bit++)
        {
            masks.add(1 << bit);
        }

        for(int bit1 = 0; bit1 < 23; bit1++)
        {
            for(int bit2 = bit1 + 1; bit2 < 23; bit2++)
            {
                masks.add((1 << bit1) | (1 << bit2));
            }
        }

        for(int bit1 = 0; bit1 < 23; bit1++)
        {
            for(int bit2 = bit1 + 1; bit2 < 23; bit2++)
            {
                for(int bit3 = bit2 + 1; bit3 < 23; bit3++)
                {
                    masks.add((1 << bit1) | (1 << bit2) | (1 << bit3));
                }
            }
        }

        return masks.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int encodeHamming15(int data)
    {
        int codeword = 0;

        for(int bit = 0; bit < 11; bit++)
        {
            if((data & (1 << (10 - bit))) != 0)
            {
                codeword ^= HAMMING15_ENCODING[bit];
            }
        }

        return codeword & 0x7FFF;
    }

    private static int decodeMbelibHamming15(int codeword)
    {
        int syndrome = 0;

        for(int generator: MBELIB_HAMMING_GENERATOR)
        {
            syndrome = (syndrome << 1) | (Integer.bitCount(codeword & generator) & 0x01);
        }

        if(syndrome > 0)
        {
            codeword ^= 1 << (syndrome - 1);
        }

        return (codeword >> 4) & 0x07FF;
    }

    private static void xorRandomizer(int[][] imbe)
    {
        int seed = 0;

        for(int bit = 22; bit >= 11; bit--)
        {
            seed = (seed << 1) | (imbe[0][bit] & 0x01);
        }

        int pr = (seed << 4) & 0xFFFF;

        for(int block = 1; block < 4; block++)
        {
            for(int bit = 22; bit >= 0; bit--)
            {
                pr = nextRandom(imbe, pr, block, bit);
            }
        }

        for(int block = 4; block < 7; block++)
        {
            for(int bit = 14; bit >= 0; bit--)
            {
                pr = nextRandom(imbe, pr, block, bit);
            }
        }
    }

    private static int nextRandom(int[][] imbe, int pr, int block, int bit)
    {
        pr = ((173 * pr) + 13849) & 0xFFFF;

        if((pr & 0x8000) != 0)
        {
            imbe[block][bit] ^= 0x01;
        }

        return pr;
    }

    private static int readPackedBits(byte[] packed, int bitOffset, int width)
    {
        int value = 0;

        for(int bit = 0; bit < width; bit++)
        {
            value = (value << 1) | getPackedBit(packed, bitOffset + bit);
        }

        return value;
    }

    private static int writePackedBits(byte[] packed, int bitOffset, int value, int width)
    {
        for(int bit = 0; bit < width; bit++)
        {
            setPackedBit(packed, bitOffset + bit, (value >> (width - bit - 1)) & 0x01);
        }

        return bitOffset + width;
    }

    private static int getPackedBit(byte[] packed, int bitOffset)
    {
        return ((packed[bitOffset / 8] & 0xFF) >> (7 - (bitOffset % 8))) & 0x01;
    }

    private static void setPackedBit(byte[] packed, int bitOffset, int value)
    {
        if((value & 0x01) != 0)
        {
            packed[bitOffset / 8] |= (byte)(1 << (7 - (bitOffset % 8)));
        }
    }
}
