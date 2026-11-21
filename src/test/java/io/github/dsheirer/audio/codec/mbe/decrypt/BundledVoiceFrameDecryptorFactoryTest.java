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

import io.github.dsheirer.audio.codec.mbe.decrypt.dmr.DmrPrivacyFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.nxdn.NxdnPrivacyFrameDecryptor;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.voice.decrypt.module.SdrTrunkVoiceDecryptionModule;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BundledVoiceFrameDecryptorFactoryTest
{
    @Test
    void recognizesDmrArc4()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.DMR, 0x21, 3,
            "C0DCFFAF", 0, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.DMR, 0x21, 3, "CDEFAB1234");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isPresent());
        assertInstanceOf(DmrPrivacyFrameDecryptor.class, decryptor.get());
        assertTrue(decryptor.get().isImplemented());
    }

    @Test
    void recognizesApco25Adp()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0xAA, 26985,
            "A3FD5550D287CD8E00", 0, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.APCO25, 0xAA, 26985, "DEADBEEF69");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isPresent());
        assertInstanceOf(Apco25AdpVoiceFrameDecryptor.class, decryptor.get());
        assertTrue(decryptor.get().isImplemented());
    }

    @Test
    void recognizesNxdnDesOfb()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.NXDN, 2, 1,
            "ABCDEF1234567890", 0, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.NXDN, 2, 1, "ABCDEF0123456789");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isPresent());
        assertInstanceOf(NxdnPrivacyFrameDecryptor.class, decryptor.get());
        assertTrue(decryptor.get().isImplemented());
    }

    @Test
    void recognizesApco25DesOfb()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0x81, 0x4101,
            "AB8E0CCFB65CCCC500", 0, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.APCO25, 0x81, 0x4101, "465BA1A7F83D10C2");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isPresent());
        assertInstanceOf(Apco25OfbVoiceFrameDecryptor.class, decryptor.get());
        assertTrue(decryptor.get().isImplemented());
    }

    @Test
    void recognizesApco25Aes256()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0x84, 0x4099,
            "E6E63447BF74FBFD00", 0, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.APCO25, 0x84, 0x4099,
            "D59DA7112B0D0AF44544C055F381F2D3900896D1C4456078E8ABCD33B1CBADE0");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isPresent());
        assertInstanceOf(Apco25OfbVoiceFrameDecryptor.class, decryptor.get());
        assertTrue(decryptor.get().isImplemented());
    }

    @Test
    void recognizesApco25Phase2Adp()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0xAA, 26985,
            "A3FD5550D287CD8E00", 1, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.APCO25, 0xAA, 26985, "DEADBEEF69");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isPresent());
        assertInstanceOf(P25Phase2AmbeVoiceFrameDecryptor.class, decryptor.get());
        assertTrue(decryptor.get().isImplemented());
    }

    @Test
    void recognizesApco25Phase2DesOfb()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0x81, 0x4101,
            "AB8E0CCFB65CCCC500", 2, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.APCO25, 0x81, 0x4101, "465BA1A7F83D10C2");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isPresent());
        assertInstanceOf(P25Phase2AmbeVoiceFrameDecryptor.class, decryptor.get());
        assertTrue(decryptor.get().isImplemented());
    }

    @Test
    void recognizesApco25Phase2Aes256()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0x84, 0x4099,
            "E6E63447BF74FBFD00", 1, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.APCO25, 0x84, 0x4099,
            "D59DA7112B0D0AF44544C055F381F2D3900896D1C4456078E8ABCD33B1CBADE0");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isPresent());
        assertInstanceOf(P25Phase2AmbeVoiceFrameDecryptor.class, decryptor.get());
        assertTrue(decryptor.get().isImplemented());
    }

    @Test
    void rejectsDmrKnownAlgorithmWhenKeyLengthIsWrong()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.DMR, 0x24, 2,
            "13330A5E", 0, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.DMR, 0x24, 2, "ABCDEF1234567890");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isEmpty());
    }

    @Test
    void rejectsApco25Aes256WhenKeyLengthIsWrong()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0x84, 0x4099,
            "E6E63447BF74FBFD00", 0, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.APCO25, 0x84, 0x4099, "ABCDEF1234567890");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isEmpty());
    }

    @Test
    void unsupportedKnownKeyContextReturnsEmpty()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.DMR, 0x99, 2,
            "13330A5E", 0, null);
        VoiceEncryptionKey key = key(VoiceEncryptionProtocol.DMR, 0x99, 2, "ABCDEF1234567890");

        Optional<VoiceFrameDecryptor> decryptor = factory().create(context, key);

        assertTrue(decryptor.isEmpty());
    }

    private static VoiceFrameDecryptorFactory factory()
    {
        return new VoiceFrameDecryptorFactory();
    }

    private static VoiceEncryptionKey key(VoiceEncryptionProtocol protocol, int algorithmId, int keyId, String keyHex)
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setProtocol(protocol);
        key.setAlgorithmId(algorithmId);
        key.setKeyId(keyId);
        key.setKeyHex(keyHex);
        return key;
    }
}
