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

package io.github.dsheirer.voice.decrypt.module;

import io.github.dsheirer.audio.codec.mbe.decrypt.Apco25VoiceFrameDecryptorProvider;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorFactory;
import io.github.dsheirer.audio.codec.mbe.decrypt.dmr.DmrPrivacyFrameDecryptorProvider;
import io.github.dsheirer.audio.codec.mbe.decrypt.nxdn.NxdnVoiceFrameDecryptorProvider;
import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdrTrunkVoiceDecryptionModuleTest
{
    @Test
    void exposesAllProvidersAndAlgorithmsWithoutActivation()
    {
        SdrTrunkVoiceDecryptionModule module = new SdrTrunkVoiceDecryptionModule();

        assertEquals(3, module.getApiVersion());
        assertEquals(List.of(Apco25VoiceFrameDecryptorProvider.class, DmrPrivacyFrameDecryptorProvider.class,
            NxdnVoiceFrameDecryptorProvider.class), module.getProviders().stream().map(Object::getClass).toList());
        assertEquals(List.of(VoiceEncryptionAlgorithm.APCO25_DES_OFB, VoiceEncryptionAlgorithm.APCO25_AES_256,
            VoiceEncryptionAlgorithm.APCO25_ADP, VoiceEncryptionAlgorithm.DMR_DMRA_RC4,
            VoiceEncryptionAlgorithm.DMR_DMRA_AES_128, VoiceEncryptionAlgorithm.DMR_DMRA_AES_256,
            VoiceEncryptionAlgorithm.NXDN_SCRAMBLER, VoiceEncryptionAlgorithm.NXDN_DES_OFB,
            VoiceEncryptionAlgorithm.NXDN_AES_256_OFB), List.copyOf(module.getSupportedAlgorithms()));
    }
    @Test
    void defaultFactoryCreatesEveryAdvertisedAlgorithmIncludingBothP25Phases()
    {
        VoiceFrameDecryptorFactory factory = new VoiceFrameDecryptorFactory();
        for(VoiceEncryptionAlgorithm algorithm: new SdrTrunkVoiceDecryptionModule().getSupportedAlgorithms())
        {
            VoiceEncryptionKey key = new VoiceEncryptionKey();
            key.setProtocol(algorithm.getProtocol());
            key.setAlgorithmId(algorithm.getValue());
            key.setKeyId(1);
            key.setKeyHex("01".repeat(algorithm.getExpectedKeyBytes()));
            String indicator = switch(algorithm.getProtocol())
            {
                case APCO25 -> "010203040506070800";
                case DMR -> "01020304";
                case NXDN -> "0102030405060708";
            };
            int[] timeslots = algorithm.getProtocol() == VoiceEncryptionProtocol.APCO25 ?
                new int[]{0, 1, 2} : new int[]{0};
            for(int timeslot: timeslots)
            {
                VoiceEncryptionContext context = new VoiceEncryptionContext(algorithm.getProtocol(),
                    algorithm.getValue(), 1, indicator, timeslot, null);
                var decryptor = factory.create(context, key);
                assertTrue(decryptor.isPresent(), () -> algorithm + " timeslot " + timeslot);
                assertTrue(decryptor.orElseThrow().isImplemented());
            }
        }
    }

}
