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

package io.github.dsheirer.preference.encryption;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VoiceEncryptionAlgorithmTest
{
    @Test
    void resolvesKnownP25AlgorithmMetadata()
    {
        assertEquals(VoiceEncryptionAlgorithm.APCO25_AES_256,
            VoiceEncryptionAlgorithm.fromValue(VoiceEncryptionProtocol.APCO25, 0x84));
        assertEquals(32, VoiceEncryptionAlgorithm.APCO25_AES_256.getExpectedKeyBytes());
    }

    @Test
    void resolvesKnownDmrAlgorithmMetadata()
    {
        assertEquals(VoiceEncryptionAlgorithm.DMR_DMRA_AES_256,
            VoiceEncryptionAlgorithm.fromValue(VoiceEncryptionProtocol.DMR, 0x25));
    }

    @Test
    void unknownAlgorithmsUseHexLabel()
    {
        assertEquals("0xFE", VoiceEncryptionAlgorithm.getLabel(VoiceEncryptionProtocol.APCO25, 0xFE));
    }
}
