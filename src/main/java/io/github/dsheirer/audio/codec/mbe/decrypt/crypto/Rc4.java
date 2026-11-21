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

/**
 * RC4 KSA/PRGA helper for voice privacy algorithms.
 */
public class Rc4
{
    private Rc4()
    {
    }

    public static byte[] keystream(byte[] key, int byteCount)
    {
        return keystream(key, 0, byteCount);
    }

    public static byte[] keystream(byte[] key, int dropBytes, int byteCount)
    {
        int[] state = new int[256];
        byte[] keystream = new byte[byteCount];

        for(int x = 0; x < state.length; x++)
        {
            state[x] = x;
        }

        int j = 0;

        for(int i = 0; i < state.length; i++)
        {
            j = (j + state[i] + (key[i % key.length] & 0xFF)) & 0xFF;
            swap(state, i, j);
        }

        int i = 0;
        j = 0;

        for(int x = 0; x < dropBytes + byteCount; x++)
        {
            i = (i + 1) & 0xFF;
            j = (j + state[i]) & 0xFF;
            swap(state, i, j);
            int value = state[(state[i] + state[j]) & 0xFF];

            if(x >= dropBytes)
            {
                keystream[x - dropBytes] = (byte)value;
            }
        }

        return keystream;
    }

    public static byte[] decrypt(byte[] key, int dropBytes, byte[] encrypted)
    {
        byte[] keystream = keystream(key, dropBytes, encrypted.length);
        byte[] decrypted = new byte[encrypted.length];

        for(int x = 0; x < encrypted.length; x++)
        {
            decrypted[x] = (byte)((encrypted[x] & 0xFF) ^ (keystream[x] & 0xFF));
        }

        return decrypted;
    }

    private static void swap(int[] values, int x, int y)
    {
        int value = values[x];
        values[x] = values[y];
        values[y] = value;
    }
}
