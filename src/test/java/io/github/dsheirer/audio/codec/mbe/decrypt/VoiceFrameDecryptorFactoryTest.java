/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class VoiceFrameDecryptorFactoryTest
{
    @Test
    void returnsEmptyWithoutModuleProviders()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0xAA, 1,
            "000000000000000000", 0, null);
        assertTrue(new VoiceFrameDecryptorFactory().create(context, key()).isEmpty());
    }

    @Test
    void usesSuppliedProvider()
    {
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0xAA, 1,
            "000000000000000000", 0, null);
        VoiceFrameDecryptorProvider provider = new VoiceFrameDecryptorProvider()
        {
            @Override
            public boolean supports(VoiceEncryptionContext suppliedContext)
            {
                return suppliedContext == context;
            }

            @Override
            public VoiceFrameDecryptor create(VoiceEncryptionContext suppliedContext, VoiceEncryptionKey key)
            {
                return new VoiceFrameDecryptor()
                {
                    @Override public boolean isImplemented() { return true; }
                    @Override public void reset() {}
                    @Override public byte[] decrypt(byte[] frame) { return frame; }
                    @Override public BinaryMessage decrypt(BinaryMessage frame) { return frame; }
                };
            }
        };

        assertTrue(new VoiceFrameDecryptorFactory(java.util.List.of(provider)).create(context, key()).isPresent());
    }

    private VoiceEncryptionKey key()
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setProtocol(VoiceEncryptionProtocol.APCO25);
        key.setAlgorithmId(0xAA);
        key.setKeyId(1);
        key.setKeyHex("0102030405");
        return key;
    }
}
