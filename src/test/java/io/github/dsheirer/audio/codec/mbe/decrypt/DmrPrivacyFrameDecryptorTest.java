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

import io.github.dsheirer.audio.codec.mbe.decrypt.dmr.DmrAmbeAdapter;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DmrPrivacyFrameDecryptorTest
{
    @Test
    void adapterRoundTripsVoiceBitsThroughCodedAmbeFrame() throws Exception
    {
        boolean[] voice = patternedVoiceBits();
        byte[] coded = DmrAmbeAdapter.encodeVoice49To9(voice);

        assertArrayEquals(voice, DmrAmbeAdapter.decode9ToVoice49(coded));
    }

    @Test
    void arc4DecryptorRoundTripsFrame() throws Exception
    {
        assertRoundTrip(0x21, 3, "CDEFAB1234", "C0DCFFAF");
    }

    @Test
    void aes128DecryptorRoundTripsFrame() throws Exception
    {
        assertRoundTrip(0x24, 2, "0000000000000000BCDEFA1234567890", "13330A5E");
    }

    @Test
    void aes256DecryptorRoundTripsFrame() throws Exception
    {
        assertRoundTrip(0x25, 1, "000000000000000000000000000000000000000000000000ABCDEF1234567890",
            "45442076");
    }

    private static void assertRoundTrip(int algorithm, int keyId, String keyHex, String mi) throws Exception
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.DMR, algorithm, keyId, mi,
            1, null);
        VoiceEncryptionKey key = key(algorithm, keyId, keyHex);

        VoiceFrameDecryptorFactory factory = new VoiceFrameDecryptorFactory(
            java.util.List.of(new io.github.dsheirer.audio.codec.mbe.decrypt.dmr.DmrPrivacyFrameDecryptorProvider()));
        VoiceFrameDecryptor encryptor = factory.create(context, key).orElseThrow();
        VoiceFrameDecryptor decryptor = factory.create(context, key).orElseThrow();

        assertTrue(encryptor.isImplemented());
        byte[] clear = DmrAmbeAdapter.encodeVoice49To9(patternedVoiceBits());
        byte[] encrypted = encryptor.decrypt(clear);

        assertArrayEquals(clear, decryptor.decrypt(encrypted));
    }

    private static boolean[] patternedVoiceBits()
    {
        boolean[] bits = new boolean[49];

        for(int x = 0; x < bits.length; x++)
        {
            bits[x] = (x % 5) == 0 || (x % 7) == 0;
        }

        return bits;
    }

    private static VoiceEncryptionKey key(int algorithm, int keyId, String keyHex)
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setProtocol(VoiceEncryptionProtocol.DMR);
        key.setAlgorithmId(algorithm);
        key.setKeyId(keyId);
        key.setKeyHex(keyHex);
        return key;
    }
}
