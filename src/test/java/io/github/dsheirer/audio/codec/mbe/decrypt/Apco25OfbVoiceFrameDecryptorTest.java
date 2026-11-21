/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Apco25OfbVoiceFrameDecryptorTest
{
    @Test
    void decryptsKnownDesOfbFrames() throws Exception
    {
        VoiceFrameDecryptor decryptor = new Apco25OfbVoiceFrameDecryptor(context(0x81, 0x4101,
            "AB8E0CCFB65CCCC500"), key(0x81, 0x4101, "465BA1A7F83D10C2"));

        byte[] first = decryptor.decrypt(hex("0E5C0D835E2A0BDD031F250613D3AFC39C40"));
        byte[] second = decryptor.decrypt(hex("ACBF28DAF9580497AD3B1776797B0DFDD62C"));
        byte[] third = decryptor.decrypt(hex("8BA2C9F2B795460DB1900AF7308E2E5BAA30"));

        assertEquals("CA0C29EC85CF16F09F8012EB30CC44A9D316", hex(first));
        assertEquals("A8131773BCB700032CDF21", hex(ImbeAdapter.decode18ToPacked11(first)));
        assertEquals("18700F0F39F400DE4CA162", hex(ImbeAdapter.decode18ToPacked11(second)));
        assertEquals("181C5797A2F700AF7F5BF3", hex(ImbeAdapter.decode18ToPacked11(third)));
    }

    @Test
    void decryptsKnownAes256Frames() throws Exception
    {
        VoiceFrameDecryptor decryptor = new Apco25OfbVoiceFrameDecryptor(context(0x84, 0x4099,
            "E6E63447BF74FBFD00"), key(0x84, 0x4099,
            "D59DA7112B0D0AF44544C055F381F2D3900896D1C4456078E8ABCD33B1CBADE0"));

        byte[] first = decryptor.decrypt(hex("6ECBD27A0898E0E52E1822EDD8785722553A"));
        byte[] second = decryptor.decrypt(hex("CD352BCC9C184D646B03ED08C08B09CC828C"));
        byte[] third = decryptor.decrypt(hex("83B3BDFAFBB4933DAF69B7F4EF5B8061D25F"));

        assertEquals("F21FE69AF7C4251E7CD0CED19626A5794396", hex(first));
        assertEquals("A87827B224B3000386AD41", hex(ImbeAdapter.decode18ToPacked11(first)));
        assertEquals("18389E1E2BC60192E7CF32", hex(ImbeAdapter.decode18ToPacked11(second)));
        assertEquals("18700F2F0DE2001E2AF153", hex(ImbeAdapter.decode18ToPacked11(third)));
    }

    private static VoiceEncryptionContext context(int algorithmId, int keyId, String mi)
    {
        return new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, algorithmId, keyId, mi, 0, null);
    }

    private static VoiceEncryptionKey key(int algorithmId, int keyId, String keyHex)
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setProtocol(VoiceEncryptionProtocol.APCO25);
        key.setAlgorithmId(algorithmId);
        key.setKeyId(keyId);
        key.setKeyHex(keyHex);
        return key;
    }

    private static byte[] hex(String value)
    {
        byte[] bytes = new byte[value.length() / 2];

        for(int x = 0; x < bytes.length; x++)
        {
            bytes[x] = (byte)Integer.parseInt(value.substring(x * 2, (x * 2) + 2), 16);
        }

        return bytes;
    }

    private static String hex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder();

        for(byte value: bytes)
        {
            sb.append(String.format("%02X", value & 0xFF));
        }

        return sb.toString();
    }
}
