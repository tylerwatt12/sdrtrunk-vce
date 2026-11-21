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
 * Byte-oriented DES-OFB keystream generator.
 */
public class DesOfb
{
    private static final int BLOCK_BYTES = 8;

    private DesOfb()
    {
    }

    public static byte[] keystream(byte[] key, byte[] iv, int blockCount) throws VoiceFrameDecryptionException
    {
        if(key == null || key.length != BLOCK_BYTES || iv == null || iv.length != BLOCK_BYTES)
        {
            throw new VoiceFrameDecryptionException("DES-OFB requires an 8-byte key and 8-byte IV");
        }

        try
        {
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
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
            throw new VoiceFrameDecryptionException("Unable to create DES-OFB keystream", e);
        }
    }
}
