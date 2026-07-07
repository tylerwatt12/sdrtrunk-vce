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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VoiceEncryptionAlgorithmTest
{
    @Test
    void listsKnownP25AlgorithmsButSupportsOnlyImplementedDecryptors()
    {
        assertTrue(VoiceEncryptionAlgorithm.getAlgorithms(VoiceEncryptionProtocol.APCO25)
            .contains(VoiceEncryptionAlgorithm.APCO25_AES_256));
        assertTrue(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.APCO25, 0x81));
        assertTrue(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.APCO25, 0x84));
        assertTrue(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.APCO25, 0xAA));
        assertFalse(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.APCO25, 0x85));
        assertEquals(VoiceEncryptionAlgorithm.APCO25_DES_OFB,
            VoiceEncryptionAlgorithm.getFirstSupported(VoiceEncryptionProtocol.APCO25));
        assertFalse(VoiceEncryptionAlgorithm.getAlgorithms(VoiceEncryptionProtocol.APCO25)
            .contains(VoiceEncryptionAlgorithm.APCO25_AES_128));
    }

    @Test
    void listsKnownDmrAlgorithmsButSupportsOnlyImplementedDecryptors()
    {
        assertFalse(VoiceEncryptionAlgorithm.getAlgorithms(VoiceEncryptionProtocol.DMR)
            .contains(VoiceEncryptionAlgorithm.DMR_HYTERA_BASIC_PRIVACY));
        assertTrue(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.DMR, 0x21));
        assertTrue(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.DMR, 0x24));
        assertTrue(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.DMR, 0x25));
        assertFalse(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.DMR, 0x01));
    }

    @Test
    void unknownAlgorithmsAreNotSupported()
    {
        assertFalse(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.APCO25, 0xFE));
        assertFalse(VoiceEncryptionAlgorithm.isSupported(VoiceEncryptionProtocol.DMR, 0xFE));
        assertEquals("0xFE", VoiceEncryptionAlgorithm.getLabel(VoiceEncryptionProtocol.APCO25, 0xFE));
    }
}
