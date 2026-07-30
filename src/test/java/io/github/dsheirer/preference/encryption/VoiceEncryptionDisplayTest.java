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

import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceEncryptionDisplayTest
{
    @Test
    void resolvesCollidingAlgorithmIdsWithinEachProtocol()
    {
        assertEquals("BAT-E", VoiceEncryptionDisplay.compactAlgorithm(Protocol.APCO25, 0x01));
        assertEquals("HYT-BP", VoiceEncryptionDisplay.compactAlgorithm(Protocol.DMR, 0x01));
        assertEquals("SCRAM", VoiceEncryptionDisplay.compactAlgorithm(Protocol.NXDN, 0x01));

        assertEquals("FIREF", VoiceEncryptionDisplay.compactAlgorithm(Protocol.APCO25, 0x02));
        assertEquals("HYT-EP", VoiceEncryptionDisplay.compactAlgorithm(Protocol.DMR, 0x02));
        assertEquals("DESOFB", VoiceEncryptionDisplay.compactAlgorithm(Protocol.NXDN, 0x02));

        assertEquals("MAYFL", VoiceEncryptionDisplay.compactAlgorithm(Protocol.APCO25, 0x03));
        assertEquals("ALG:03", VoiceEncryptionDisplay.compactAlgorithm(Protocol.DMR, 0x03));
        assertEquals("AES256", VoiceEncryptionDisplay.compactAlgorithm(Protocol.NXDN, 0x03));
    }

    @Test
    void providesCompactAndFullNamesFromOneCatalog()
    {
        assertEquals("AES256", VoiceEncryptionAlgorithm.APCO25_AES_256.getCompactLabel());
        assertEquals("AES-256", VoiceEncryptionAlgorithm.APCO25_AES_256.getFullLabel());
        assertEquals("Hytera Basic Privacy",
            VoiceEncryptionDisplay.fullAlgorithm(Protocol.DMR, 0x01));
        assertEquals("AES-256-OFB",
            VoiceEncryptionDisplay.fullAlgorithm(Protocol.NXDN, 0x03));

        for(VoiceEncryptionAlgorithm algorithm: VoiceEncryptionAlgorithm.values())
        {
            assertTrue(algorithm.getCompactLabel().length() <= 6, algorithm::toString);
        }
    }

    @Test
    void mapsDecoderAndPersistedProtocolNames()
    {
        assertEquals(VoiceEncryptionProtocol.APCO25,
            VoiceEncryptionProtocol.fromProtocol(Protocol.APCO25_PHASE2));
        assertEquals(VoiceEncryptionProtocol.APCO25,
            VoiceEncryptionProtocol.fromProtocolName("P25"));
        assertEquals(VoiceEncryptionProtocol.APCO25,
            VoiceEncryptionProtocol.fromProtocolName("APCO-25 Phase 2"));
        assertEquals(VoiceEncryptionProtocol.DMR,
            VoiceEncryptionProtocol.fromProtocolName(" dmr "));
        assertEquals(VoiceEncryptionProtocol.NXDN,
            VoiceEncryptionProtocol.fromProtocolName("NXDN"));
        assertNull(VoiceEncryptionProtocol.fromProtocolName("NBFM"));
    }

    @Test
    void formatsRawAndIdentifierDetails()
    {
        assertEquals("AES256 K:65", VoiceEncryptionDisplay.format(Protocol.APCO25_PHASE2, 0x84, 0x65));
        assertEquals("AES-256 K:65", VoiceEncryptionDisplay.formatFull(Protocol.APCO25, 0x84, 0x65));

        EncryptionKeyIdentifier p25 = EncryptionKeyIdentifier.create(Protocol.APCO25,
            APCO25EncryptionKey.create(0xAA, 0x17));
        assertEquals("ADP K:17", VoiceEncryptionDisplay.format(p25));

        EncryptionKeyIdentifier nxdn = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0x03, 0x07));
        assertEquals("AES256 K:7", VoiceEncryptionDisplay.format(nxdn));

        EncryptionKeyIdentifier dmr = EncryptionKeyIdentifier.create(Protocol.DMR, encryptedKey(0x01, 0x2A));
        assertEquals("HYT-BP K:2A", VoiceEncryptionDisplay.format(dmr));
    }

    @Test
    void usesStableFallbacks()
    {
        assertEquals("ENC", VoiceEncryptionDisplay.format(Protocol.DMR, null, null));
        assertEquals("ALG:FE", VoiceEncryptionDisplay.compactAlgorithm(Protocol.DMR, 0xFE));
        assertEquals("ALG:FE K:7", VoiceEncryptionDisplay.format(Protocol.DMR, 0xFE, 0x07));
        assertEquals("ALG:01", VoiceEncryptionDisplay.compactAlgorithm(Protocol.UNKNOWN, 0x01));

        EncryptionKeyIdentifier clear = EncryptionKeyIdentifier.create(Protocol.APCO25,
            APCO25EncryptionKey.create(0x80, 0));
        assertNull(VoiceEncryptionDisplay.format(clear));
        assertNull(VoiceEncryptionDisplay.format((EncryptionKeyIdentifier)null));
    }

    private static EncryptionKey encryptedKey(int algorithm, int key)
    {
        return new EncryptionKey(algorithm, key)
        {
            @Override
            public boolean isEncrypted()
            {
                return true;
            }
        };
    }
}
