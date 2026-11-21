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

package io.github.dsheirer.audio.codec.mbe.decrypt.dmr;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.AesOfb;
import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.Rc4;
import java.util.Arrays;

/**
 * DMR privacy keystream helpers shared by future frame decryptors and tests.
 */
public class DmrPrivacyKeystream
{
    public static final int ARC4_DROP_BYTES = 256;
    public static final int SUPERFRAME_AMBE_FRAMES = 18;
    public static final int VOICE_BITS_PER_FRAME = 49;
    public static final int KEYSTREAM_BITS_PER_FRAME = 56;
    public static final int AES_BLOCKS_PER_SUPERFRAME = 10;
    public static final int AES_DISCARD_BYTES = 16;

    private DmrPrivacyKeystream()
    {
    }

    public static byte[] createArc4Key(byte[] userKey5, int mi) throws VoiceFrameDecryptionException
    {
        if(userKey5 == null || userKey5.length != DmrPrivacyAlgorithm.DMRA_RC4.getKeyBytes())
        {
            throw new VoiceFrameDecryptionException("DMRA RC4/EP requires a 5-byte key");
        }

        byte[] key = new byte[9];
        System.arraycopy(userKey5, 0, key, 0, userKey5.length);
        key[5] = (byte)((mi >> 24) & 0xFF);
        key[6] = (byte)((mi >> 16) & 0xFF);
        key[7] = (byte)((mi >> 8) & 0xFF);
        key[8] = (byte)(mi & 0xFF);
        return key;
    }

    public static byte[] createArc4SuperframeKeystream(byte[] userKey5, int mi)
        throws VoiceFrameDecryptionException
    {
        return Rc4.keystream(createArc4Key(userKey5, mi), ARC4_DROP_BYTES,
            SUPERFRAME_AMBE_FRAMES * 7);
    }

    public static int nextArc4Mi(int mi)
    {
        long lfsr = mi & 0xFFFFFFFFL;

        for(int x = 0; x < 32; x++)
        {
            long bit = ((lfsr >> 31) ^ (lfsr >> 3) ^ (lfsr >> 1)) & 1;
            lfsr = ((lfsr << 1) | bit) & 0xFFFFFFFFL;
        }

        return (int)lfsr;
    }

    public static AesIv expandAesIv(int mi)
    {
        long lfsr = mi & 0xFFFFFFFFL;
        byte[] iv = new byte[16];
        iv[0] = (byte)((lfsr >> 24) & 0xFF);
        iv[1] = (byte)((lfsr >> 16) & 0xFF);
        iv[2] = (byte)((lfsr >> 8) & 0xFF);
        iv[3] = (byte)(lfsr & 0xFF);

        for(int bitIndex = 32; bitIndex < 128; bitIndex++)
        {
            long bit = ((lfsr >> 31) ^ (lfsr >> 21) ^ (lfsr >> 1) ^ lfsr) & 1;
            lfsr = ((lfsr << 1) | bit) & 0xFFFFFFFFL;
            iv[bitIndex / 8] = (byte)(((iv[bitIndex / 8] & 0xFF) << 1) | bit);
        }

        int nextMi = ((iv[4] & 0xFF) << 24) | ((iv[5] & 0xFF) << 16) |
            ((iv[6] & 0xFF) << 8) | (iv[7] & 0xFF);
        return new AesIv(iv, nextMi);
    }

    public static byte[] createAesSuperframeKeystream(byte[] key, int mi) throws VoiceFrameDecryptionException
    {
        byte[] bytes = AesOfb.keystream(key, expandAesIv(mi).iv(), AES_BLOCKS_PER_SUPERFRAME);
        byte[] voiceBytes = new byte[bytes.length - AES_DISCARD_BYTES];
        System.arraycopy(bytes, AES_DISCARD_BYTES, voiceBytes, 0, voiceBytes.length);
        return voiceBytes;
    }

    public record AesIv(byte[] iv, int nextMi)
    {
        public AesIv
        {
            iv = Arrays.copyOf(iv, iv.length);
        }

        @Override
        public byte[] iv()
        {
            return Arrays.copyOf(iv, iv.length);
        }
    }
}
