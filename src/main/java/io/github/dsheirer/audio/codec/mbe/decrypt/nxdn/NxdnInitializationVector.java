/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt.nxdn;

import java.util.Arrays;

/**
 * NXDN conventional/Type-C and Type-D initialization-vector generators.
 */
public class NxdnInitializationVector
{
    private static final int MASK_23 = 0x7FFFFF;

    private NxdnInitializationVector()
    {
    }

    /**
     * Expands a conventional/Type-C 64-bit IV into the 128-bit AES IV using
     * C(x)=x^64+x^62+x^46+x^38+x^27+x^15+1.
     */
    public static byte[] expandStandardAes(long initializationVector)
    {
        byte[] expanded = new byte[16];
        writeLong(initializationVector, expanded, 0);
        long lfsr = initializationVector;

        for(int index = 64; index < 128; index++)
        {
            long bit = ((lfsr >>> 63) ^ (lfsr >>> 61) ^ (lfsr >>> 45) ^ (lfsr >>> 37) ^
                (lfsr >>> 26) ^ (lfsr >>> 14)) & 1L;
            lfsr = (lfsr << 1) | bit;
            setBit(expanded, index, bit != 0);
        }

        return expanded;
    }

    /**
     * Generates a Type-D 64-bit DES IV from a 23-bit transmitted seed.
     */
    public static Generated generateTypeDDes(int seed)
    {
        GeneratedBits generated = generateTypeDBits(seed, 64);
        return new Generated(toBytes(generated.bits()), generated.nextSeed());
    }

    /**
     * Generates a Type-D 128-bit AES IV.  The following session seed is the LFSR state after 105 shifts.
     */
    public static Generated generateTypeDAes(int seed)
    {
        GeneratedBits generated = generateTypeDBits(seed, 128);
        int nextSeed = advanceTypeD(seed, 105);
        return new Generated(toBytes(generated.bits()), nextSeed);
    }

    private static GeneratedBits generateTypeDBits(int seed, int count)
    {
        int lfsr = seed & MASK_23;
        boolean[] bits = new boolean[count];

        for(int index = 0; index < count; index++)
        {
            bits[index] = ((lfsr >>> 22) & 1) != 0;
            int feedback = ((lfsr >>> 22) ^ (lfsr >>> 4)) & 1;
            lfsr = ((lfsr << 1) & MASK_23) | feedback;
        }

        return new GeneratedBits(bits, lfsr);
    }

    private static int advanceTypeD(int seed, int count)
    {
        int lfsr = seed & MASK_23;

        for(int index = 0; index < count; index++)
        {
            int feedback = ((lfsr >>> 22) ^ (lfsr >>> 4)) & 1;
            lfsr = ((lfsr << 1) & MASK_23) | feedback;
        }

        return lfsr;
    }

    private static byte[] toBytes(boolean[] bits)
    {
        byte[] bytes = new byte[(bits.length + 7) / 8];

        for(int index = 0; index < bits.length; index++)
        {
            setBit(bytes, index, bits[index]);
        }

        return bytes;
    }

    private static void setBit(byte[] bytes, int index, boolean value)
    {
        if(value)
        {
            bytes[index / 8] |= (byte)(0x80 >>> (index % 8));
        }
    }

    private static void writeLong(long value, byte[] bytes, int offset)
    {
        for(int index = 0; index < 8; index++)
        {
            bytes[offset + index] = (byte)(value >>> (56 - (index * 8)));
        }
    }

    public record Generated(byte[] initializationVector, int nextSeed)
    {
        public Generated
        {
            initializationVector = Arrays.copyOf(initializationVector, initializationVector.length);
        }

        @Override
        public byte[] initializationVector()
        {
            return Arrays.copyOf(initializationVector, initializationVector.length);
        }
    }

    private record GeneratedBits(boolean[] bits, int nextSeed)
    {
    }
}
