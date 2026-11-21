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

package io.github.dsheirer.audio.codec.mbe.decrypt.crypto;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Byte-oriented AES-OFB keystream generator.
 */
public class AesOfb
{
    private static final int BLOCK_BYTES = 16;

    private AesOfb()
    {
    }

    public static byte[] keystream(byte[] key, byte[] iv, int blockCount) throws VoiceFrameDecryptionException
    {
        if(key == null || iv == null || iv.length != BLOCK_BYTES)
        {
            throw new VoiceFrameDecryptionException("AES-OFB requires a key and 16-byte IV");
        }

        if(key.length != 16 && key.length != 24 && key.length != 32)
        {
            throw new VoiceFrameDecryptionException("AES-OFB key must be 16, 24, or 32 bytes");
        }

        try
        {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] input = Arrays.copyOf(iv, iv.length);
            byte[] output = new byte[blockCount * BLOCK_BYTES];

            for(int block = 0; block < blockCount; block++)
            {
                input = cipher.doFinal(input);
                System.arraycopy(input, 0, output, block * BLOCK_BYTES, BLOCK_BYTES);
            }

            return output;
        }
        catch(Exception e)
        {
            throw new VoiceFrameDecryptionException("Unable to create AES-OFB keystream", e);
        }
    }
}
