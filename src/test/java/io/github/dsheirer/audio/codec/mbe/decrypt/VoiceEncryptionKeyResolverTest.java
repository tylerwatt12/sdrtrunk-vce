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

import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VoiceEncryptionKeyResolverTest
{
    @Test
    void keyHexIsNormalizedAndValidated()
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setKeyHex("01 23:45-67_89");

        assertEquals("0123456789", key.getKeyHex());
        assertTrue(VoiceEncryptionKey.isValidHexKey(key.getKeyHex()));
        assertFalse(VoiceEncryptionKey.isValidHexKey("123"));
        assertFalse(VoiceEncryptionKey.isValidHexKey("12XZ"));
    }

    @Test
    void resolverMatchesEnabledProtocolAlgorithmAndKeyId()
    {
        VoiceEncryptionKey key = configuredKey();
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0xAA, 7,
            "001122334455667788", 0, null);

        VoiceEncryptionKeyResolver resolver = new VoiceEncryptionKeyResolver(List.of(key));

        assertTrue(resolver.resolve(context).isPresent());

        key.setEnabled(false);
        resolver = new VoiceEncryptionKeyResolver(List.of(key));

        assertFalse(resolver.resolve(context).isPresent());
    }

    @Test
    void resolverHonorsOptionalScope()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(1234));

        VoiceEncryptionContext matchingContext = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0xAA, 7,
            "001122334455667788", 0, identifiers);
        VoiceEncryptionContext otherContext = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0xAA, 7,
            "001122334455667788", 0, null);

        VoiceEncryptionKey key = configuredKey();
        key.setScope("1234");

        VoiceEncryptionKeyResolver resolver = new VoiceEncryptionKeyResolver(List.of(key));

        assertTrue(resolver.resolve(matchingContext).isPresent());
        assertFalse(resolver.resolve(otherContext).isPresent());
    }

    @Test
    void resolverMatchesConfiguredCustomAlgorithms()
    {
        VoiceEncryptionKey key = configuredKey();
        key.setAlgorithmId(0x85);
        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 0x85, 7,
            "001122334455667788", 0, null);

        VoiceEncryptionKeyResolver resolver = new VoiceEncryptionKeyResolver(List.of(key));

        assertTrue(resolver.resolve(context).isPresent());
    }

    private VoiceEncryptionKey configuredKey()
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setProtocol(VoiceEncryptionProtocol.APCO25);
        key.setAlgorithmId(0xAA);
        key.setKeyId(7);
        key.setKeyHex("0102030405");
        return key;
    }
}
