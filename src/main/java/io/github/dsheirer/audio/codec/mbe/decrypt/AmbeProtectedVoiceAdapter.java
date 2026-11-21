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

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.Golay23;
import io.github.dsheirer.edac.Golay24;

/**
 * Shared AMBE protected voice-bit adapter used by protocols that encrypt the 49 AMBE vocoder bits inside a
 * transmitted 72-bit coded frame.
 */
public class AmbeProtectedVoiceAdapter
{
    public static final int FRAME_BYTES = 9;
    public static final int FRAME_BITS = FRAME_BYTES * 8;
    public static final int VOICE_BITS = 49;
    public static final int PACKED_VOICE_BYTES = 7;

    /**
     * Golay(23,12) checksums. These match the JMBE/SDRTrunk Golay decoder lookup table.
     */
    private static final int[] GOLAY_CHECKSUMS = {0x63A, 0x31D, 0x7B4, 0x3DA, 0x1ED, 0x6CC, 0x366, 0x1B3,
        0x6E3, 0x54B, 0x49F, 0x475};

    private AmbeProtectedVoiceAdapter()
    {
    }

    public record Interleave(String label, int[] c0, int[] c1, int[] c2, int[] c3)
    {
    }

    /**
     * Decodes a transmitted 9-byte AMBE frame into 49 protected AMBE voice bits.
     */
    public static boolean[] decode9ToVoice49(byte[] frame, Interleave interleave)
        throws VoiceFrameDecryptionException
    {
        if(frame == null || frame.length != FRAME_BYTES)
        {
            throw new VoiceFrameDecryptionException(interleave.label() + " requires a 9-byte AMBE frame");
        }

        boolean[] frameBits = toBits(frame);
        boolean[] c0 = extract(frameBits, interleave.c0());
        boolean[] c1 = extract(frameBits, interleave.c1());
        boolean[] c2 = extract(frameBits, interleave.c2());
        boolean[] c3 = extract(frameBits, interleave.c3());

        c0 = correctGolay24(c0);
        boolean[] modulation = getModulationVector(toInt(c0, 0, 12));
        xor(c1, modulation);
        c1 = correctGolay23(c1);

        boolean[] voice = new boolean[VOICE_BITS];
        int offset = 0;
        offset = copy(c0, 0, voice, offset, 12);
        offset = copy(c1, 0, voice, offset, 12);
        offset = copy(c2, 0, voice, offset, c2.length);
        copy(c3, 0, voice, offset, c3.length);
        return voice;
    }

    /**
     * Encodes 49 protected AMBE voice bits into a transmitted 9-byte coded AMBE frame.
     */
    public static byte[] encodeVoice49To9(boolean[] voice, Interleave interleave)
        throws VoiceFrameDecryptionException
    {
        if(voice == null || voice.length != VOICE_BITS)
        {
            throw new VoiceFrameDecryptionException(interleave.label() + " requires 49 voice bits");
        }

        boolean[] c0 = new boolean[24];
        boolean[] c1 = new boolean[23];
        boolean[] c2 = new boolean[11];
        boolean[] c3 = new boolean[14];

        int offset = 0;
        System.arraycopy(voice, offset, c0, 0, 12);
        offset += 12;
        System.arraycopy(voice, offset, c1, 0, 12);
        offset += 12;
        System.arraycopy(voice, offset, c2, 0, c2.length);
        offset += c2.length;
        copy(voice, offset, c3, 0, c3.length);

        encodeGolay24(c0);
        encodeGolay23(c1);
        xor(c1, getModulationVector(toInt(c0, 0, 12)));

        boolean[] frameBits = new boolean[FRAME_BITS];
        insert(frameBits, interleave.c0(), c0);
        insert(frameBits, interleave.c1(), c1);
        insert(frameBits, interleave.c2(), c2);
        insert(frameBits, interleave.c3(), c3);
        return toBytes(frameBits);
    }

    public static boolean[] packVoiceBytesToBits(byte[] packed) throws VoiceFrameDecryptionException
    {
        if(packed == null || packed.length != PACKED_VOICE_BYTES)
        {
            throw new VoiceFrameDecryptionException("AMBE protected voice requires 7 packed voice bytes");
        }

        boolean[] bits = new boolean[VOICE_BITS];

        for(int x = 0; x < VOICE_BITS; x++)
        {
            bits[x] = ((packed[x / 8] >> (7 - (x % 8))) & 0x01) == 1;
        }

        return bits;
    }

    public static byte[] packVoiceBits(boolean[] bits) throws VoiceFrameDecryptionException
    {
        if(bits == null || bits.length != VOICE_BITS)
        {
            throw new VoiceFrameDecryptionException("AMBE protected voice requires 49 voice bits");
        }

        byte[] packed = new byte[PACKED_VOICE_BYTES];

        for(int x = 0; x < VOICE_BITS; x++)
        {
            if(bits[x])
            {
                packed[x / 8] |= (byte)(0x80 >> (x % 8));
            }
        }

        return packed;
    }

    private static boolean[] correctGolay24(boolean[] bits)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(bits.length);
        load(message, bits);
        Golay24.checkAndCorrect(message, 0);
        return read(message, bits.length);
    }

    private static boolean[] correctGolay23(boolean[] bits)
    {
        BinaryMessage message = new BinaryMessage(bits.length);
        load(message, bits);
        Golay23.checkAndCorrect(message, 0);
        return read(message, bits.length);
    }

    private static void encodeGolay24(boolean[] bits)
    {
        int checksum = calculateGolayChecksum(bits);
        load(bits, 12, 11, checksum);
        bits[23] = (cardinality(bits, 0, 23) % 2) != 0;
    }

    private static void encodeGolay23(boolean[] bits)
    {
        load(bits, 12, 11, calculateGolayChecksum(bits));
    }

    private static int calculateGolayChecksum(boolean[] bits)
    {
        int checksum = 0;

        for(int x = 0; x < 12; x++)
        {
            if(bits[x])
            {
                checksum ^= GOLAY_CHECKSUMS[x];
            }
        }

        return checksum;
    }

    private static boolean[] getModulationVector(int u0)
    {
        boolean[] vector = new boolean[23];
        int seed = 16 * u0;

        for(int x = 0; x < vector.length; x++)
        {
            seed = ((173 * seed) + 13849) % 65536;
            vector[x] = seed >= 32768;
        }

        return vector;
    }

    private static boolean[] toBits(byte[] bytes)
    {
        boolean[] bits = new boolean[bytes.length * 8];

        for(int x = 0; x < bits.length; x++)
        {
            bits[x] = ((bytes[x / 8] >> (7 - (x % 8))) & 0x01) == 1;
        }

        return bits;
    }

    private static byte[] toBytes(boolean[] bits)
    {
        byte[] bytes = new byte[(bits.length + 7) / 8];

        for(int x = 0; x < bits.length; x++)
        {
            if(bits[x])
            {
                bytes[x / 8] |= (byte)(0x80 >> (x % 8));
            }
        }

        return bytes;
    }

    private static boolean[] extract(boolean[] source, int[] vector)
    {
        boolean[] extracted = new boolean[vector.length];

        for(int x = 0; x < vector.length; x++)
        {
            extracted[x] = source[vector[x]];
        }

        return extracted;
    }

    private static void insert(boolean[] target, int[] vector, boolean[] source)
    {
        for(int x = 0; x < vector.length; x++)
        {
            target[vector[x]] = source[x];
        }
    }

    private static int copy(boolean[] source, int sourceOffset, boolean[] target, int targetOffset, int length)
    {
        System.arraycopy(source, sourceOffset, target, targetOffset, length);
        return targetOffset + length;
    }

    private static void xor(boolean[] target, boolean[] mask)
    {
        for(int x = 0; x < Math.min(target.length, mask.length); x++)
        {
            target[x] ^= mask[x];
        }
    }

    private static int toInt(boolean[] bits, int offset, int length)
    {
        int value = 0;

        for(int x = 0; x < length; x++)
        {
            value <<= 1;

            if(bits[offset + x])
            {
                value++;
            }
        }

        return value;
    }

    private static void load(boolean[] bits, int offset, int width, int value)
    {
        for(int x = 0; x < width; x++)
        {
            bits[offset + x] = ((value >> (width - x - 1)) & 0x01) == 1;
        }
    }

    private static int cardinality(boolean[] bits, int offset, int length)
    {
        int count = 0;

        for(int x = offset; x < offset + length; x++)
        {
            if(bits[x])
            {
                count++;
            }
        }

        return count;
    }

    private static void load(BinaryMessage message, boolean[] bits)
    {
        for(int x = 0; x < bits.length; x++)
        {
            message.set(x, bits[x]);
        }
    }

    private static boolean[] read(BinaryMessage message, int length)
    {
        boolean[] bits = new boolean[length];

        for(int x = 0; x < length; x++)
        {
            bits[x] = message.get(x);
        }

        return bits;
    }
}
