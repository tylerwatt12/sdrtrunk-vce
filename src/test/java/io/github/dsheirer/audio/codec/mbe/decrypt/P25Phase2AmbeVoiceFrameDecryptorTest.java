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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class P25Phase2AmbeVoiceFrameDecryptorTest
{
    @Test
    void adapterRoundTripsPackedVoiceBits() throws Exception
    {
        byte[] packed = hex("F8A19F8CE00080");

        byte[] coded = P25Phase2AmbeAdapter.encodePacked7To9(packed);

        assertEquals("CFB98206E6E2480EA5", hex(coded));
        assertArrayEquals(packed, P25Phase2AmbeAdapter.decode9ToPacked7(coded));
    }

    @Test
    void roundTripsAdpPhase2Frames() throws Exception
    {
        assertRoundTrip(context(0xAA, 26985, "A3FD5550D287CD8E00", 1),
            key(0xAA, 26985, "DEADBEEF69"));
    }

    @Test
    void roundTripsDesOfbPhase2Frames() throws Exception
    {
        assertRoundTrip(context(0x81, 0x4101, "AB8E0CCFB65CCCC500", 2),
            key(0x81, 0x4101, "465BA1A7F83D10C2"));
    }

    @Test
    void roundTripsAes256Phase2Frames() throws Exception
    {
        assertRoundTrip(context(0x84, 0x4099, "E6E63447BF74FBFD00", 1),
            key(0x84, 0x4099, "D59DA7112B0D0AF44544C055F381F2D3900896D1C4456078E8ABCD33B1CBADE0"));
    }

    @Test
    void requiresFreshSyncAfterOnePhase2Superframe() throws Exception
    {
        VoiceFrameDecryptor decryptor = new P25Phase2AmbeVoiceFrameDecryptor(
            context(0xAA, 26985, "A3FD5550D287CD8E00", 1), key(0xAA, 26985, "DEADBEEF69"));
        byte[] frame = P25Phase2AmbeAdapter.encodePacked7To9(patternedPackedFrame(0));

        for(int x = 0; x < 18; x++)
        {
            decryptor.decrypt(frame);
        }

        assertThrows(VoiceFrameDecryptionException.class, () -> decryptor.decrypt(frame));
    }

    private static void assertRoundTrip(VoiceEncryptionContext context, VoiceEncryptionKey key) throws Exception
    {
        VoiceFrameDecryptor encryptor = new P25Phase2AmbeVoiceFrameDecryptor(context, key);
        VoiceFrameDecryptor decryptor = new P25Phase2AmbeVoiceFrameDecryptor(context, key);

        for(int x = 0; x < 18; x++)
        {
            byte[] clear = P25Phase2AmbeAdapter.encodePacked7To9(patternedPackedFrame(x));
            byte[] encrypted = encryptor.decrypt(clear);
            byte[] decrypted = decryptor.decrypt(encrypted);

            assertEquals(hex(clear), hex(decrypted), "Phase 2 frame index " + x);
        }
    }

    private static byte[] patternedPackedFrame(int index)
    {
        byte[] packed = new byte[7];

        for(int x = 0; x < packed.length; x++)
        {
            packed[x] = (byte)((0xA5 + (index * 17) + (x * 31)) & 0xFF);
        }

        packed[6] &= (byte)0x80;
        return packed;
    }

    private static VoiceEncryptionContext context(int algorithmId, int keyId, String mi, int timeslot)
    {
        return new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, algorithmId, keyId, mi, timeslot, null);
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
