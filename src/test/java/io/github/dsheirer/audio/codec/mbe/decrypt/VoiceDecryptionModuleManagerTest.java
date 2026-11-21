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

import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.util.EnumSet;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoiceDecryptionModuleManagerTest
{
    @Test
    void makesEveryBundledProtocolAndAlgorithmAvailableWithoutSelection()
    {
        try(VoiceDecryptionModuleManager manager = new VoiceDecryptionModuleManager())
        {
            assertTrue(manager.isLoaded());
            assertTrue(manager.statusSnapshot().loaded());
            assertEquals(3, manager.getProviders().size());
            assertEquals(EnumSet.of(VoiceEncryptionAlgorithm.APCO25_DES_OFB,
                VoiceEncryptionAlgorithm.APCO25_AES_256, VoiceEncryptionAlgorithm.APCO25_ADP),
                EnumSet.copyOf(manager.getSupportedAlgorithms(VoiceEncryptionProtocol.APCO25)));
            assertEquals(EnumSet.of(VoiceEncryptionAlgorithm.DMR_DMRA_RC4,
                VoiceEncryptionAlgorithm.DMR_DMRA_AES_128, VoiceEncryptionAlgorithm.DMR_DMRA_AES_256),
                EnumSet.copyOf(manager.getSupportedAlgorithms(VoiceEncryptionProtocol.DMR)));
            assertEquals(EnumSet.of(VoiceEncryptionAlgorithm.NXDN_SCRAMBLER,
                VoiceEncryptionAlgorithm.NXDN_DES_OFB, VoiceEncryptionAlgorithm.NXDN_AES_256_OFB),
                EnumSet.copyOf(manager.getSupportedAlgorithms(VoiceEncryptionProtocol.NXDN)));
            assertFalse(manager.supports(VoiceEncryptionAlgorithm.APCO25_FIREFLY));
            assertFalse(manager.supports(null));
            assertThrows(UnsupportedOperationException.class, () -> manager.getProviders().clear());
        }
    }

    @Test
    void packagesTheCompleteProviderCatalogAsAnApplicationResource()
    {
        var modules = ServiceLoader.load(VoiceDecryptionModule.class).stream()
            .map(ServiceLoader.Provider::get).toList();
        assertEquals(1, modules.size());
        assertEquals(3, modules.getFirst().getProviders().size());
        assertEquals(9, modules.getFirst().getSupportedAlgorithms().size());
    }
}
