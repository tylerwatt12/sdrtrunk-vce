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
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Apco25AdpVoiceFrameDecryptorTest
{
    @Test
    void decryptsKnownDeadbeef69Frame() throws Exception
    {
        VoiceFrameDecryptor decryptor = new Apco25AdpVoiceFrameDecryptor(context(), key());

        byte[] decrypted = decryptor.decrypt(hex("751A1E8BFFF7BA782328B33789A23B9F833D"));

        assertEquals("6862E95DE2E82E83E3D981F9BE23B18EF004", hex(decrypted));
        assertEquals("040CFD7BFB7DF37B399E4C", hex(ImbeAdapter.decode18ToPacked11(decrypted)));
    }

    @Test
    void advancesPhase1VoicePosition() throws Exception
    {
        VoiceFrameDecryptor decryptor = new Apco25AdpVoiceFrameDecryptor(context(), key());

        decryptor.decrypt(hex("751A1E8BFFF7BA782328B33789A23B9F833D"));
        byte[] decrypted = decryptor.decrypt(hex("5CDA91995809CFEE48A627A8E29E399A464F"));

        assertEquals("040CFD7BFB7DF37B2D9E44", hex(ImbeAdapter.decode18ToPacked11(decrypted)));
    }

    @Test
    void leftPadsShortAdpKeys() throws Exception
    {
        VoiceEncryptionKey shortKey = key("BEEF69");
        VoiceEncryptionKey paddedKey = key("0000BEEF69");
        VoiceFrameDecryptor shortKeyDecryptor = new Apco25AdpVoiceFrameDecryptor(context(), shortKey);
        VoiceFrameDecryptor paddedKeyDecryptor = new Apco25AdpVoiceFrameDecryptor(context(), paddedKey);

        assertEquals(hex(paddedKeyDecryptor.decrypt(hex("751A1E8BFFF7BA782328B33789A23B9F833D"))),
            hex(shortKeyDecryptor.decrypt(hex("751A1E8BFFF7BA782328B33789A23B9F833D"))));
    }

    @Test
    void factorySelectsAdpForApco25Phase1AlgidAa()
    {
        Optional<VoiceFrameDecryptor> decryptor = new VoiceFrameDecryptorFactory(
            java.util.List.of(new Apco25VoiceFrameDecryptorProvider())).create(context(), key());

        assertTrue(decryptor.isPresent());
        assertTrue(decryptor.get().isImplemented());
    }

    private static VoiceEncryptionContext context()
    {
        return new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0xAA, 26985,
            "A3FD5550D287CD8E00", 0, null);
    }

    private static VoiceEncryptionKey key()
    {
        return key("DEADBEEF69");
    }

    private static VoiceEncryptionKey key(String keyHex)
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setProtocol(VoiceEncryptionProtocol.APCO25);
        key.setAlgorithmId(0xAA);
        key.setKeyId(26985);
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
