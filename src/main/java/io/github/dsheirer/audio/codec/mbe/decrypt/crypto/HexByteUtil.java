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
import java.util.Locale;

/**
 * Common hex and byte-array helpers for voice decryption implementations.
 */
public class HexByteUtil
{
    private HexByteUtil()
    {
    }

    public static byte[] parse(String hex, String label) throws VoiceFrameDecryptionException
    {
        String normalized = normalize(hex);

        if(normalized.isBlank() || normalized.length() % 2 != 0 || !normalized.matches("[0-9A-F]+"))
        {
            throw new VoiceFrameDecryptionException("Invalid " + label + " hex value");
        }

        byte[] bytes = new byte[normalized.length() / 2];

        for(int x = 0; x < bytes.length; x++)
        {
            bytes[x] = (byte)Integer.parseInt(normalized.substring(x * 2, (x * 2) + 2), 16);
        }

        return bytes;
    }

    public static byte[] parseLeftPadded(String hex, int byteCount, String label)
        throws VoiceFrameDecryptionException
    {
        byte[] parsed = parse(hex, label);

        if(parsed.length > byteCount)
        {
            throw new VoiceFrameDecryptionException(label + " must be no more than " + byteCount + " bytes");
        }

        byte[] padded = new byte[byteCount];
        System.arraycopy(parsed, 0, padded, byteCount - parsed.length, parsed.length);
        return padded;
    }

    public static byte[] parseExact(String hex, int byteCount, String label)
        throws VoiceFrameDecryptionException
    {
        byte[] parsed = parse(hex, label);

        if(parsed.length != byteCount)
        {
            throw new VoiceFrameDecryptionException(label + " must be exactly " + byteCount + " bytes");
        }

        return parsed;
    }

    public static byte[] parseFirstBytes(String hex, int byteCount, String label)
        throws VoiceFrameDecryptionException
    {
        byte[] parsed = parse(hex, label);

        if(parsed.length < byteCount)
        {
            throw new VoiceFrameDecryptionException(label + " must contain at least " + byteCount + " bytes");
        }

        byte[] bytes = new byte[byteCount];
        System.arraycopy(parsed, 0, bytes, 0, byteCount);
        return bytes;
    }

    public static String normalize(String hex) throws VoiceFrameDecryptionException
    {
        if(hex == null)
        {
            throw new VoiceFrameDecryptionException("Missing hex value");
        }

        return hex.replaceAll("[\\s:_-]", "").toUpperCase(Locale.ROOT);
    }
}
