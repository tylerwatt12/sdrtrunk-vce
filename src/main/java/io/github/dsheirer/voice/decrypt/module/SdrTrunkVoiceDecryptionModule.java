/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.voice.decrypt.module;

import io.github.dsheirer.audio.codec.mbe.decrypt.Apco25VoiceFrameDecryptorProvider;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModule;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorProvider;
import io.github.dsheirer.audio.codec.mbe.decrypt.dmr.DmrPrivacyFrameDecryptorProvider;
import io.github.dsheirer.audio.codec.mbe.decrypt.nxdn.NxdnVoiceFrameDecryptorProvider;
import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import java.util.Collection;
import java.util.List;

/**
 * Bundled SDRTrunk VCE voice decryption providers.
 */
public class SdrTrunkVoiceDecryptionModule implements VoiceDecryptionModule
{
    @Override
    public int getApiVersion()
    {
        return API_VERSION;
    }

    @Override
    public String getName()
    {
        return "SDRTrunk VCE Voice Module";
    }

    @Override
    public String getVersion()
    {
        Package modulePackage = getClass().getPackage();
        String version = modulePackage != null ? modulePackage.getImplementationVersion() : null;
        return version == null || version.isBlank() ? "development" : version;
    }

    @Override
    public Collection<VoiceEncryptionAlgorithm> getSupportedAlgorithms()
    {
        return List.of(VoiceEncryptionAlgorithm.APCO25_DES_OFB, VoiceEncryptionAlgorithm.APCO25_AES_256,
            VoiceEncryptionAlgorithm.APCO25_ADP, VoiceEncryptionAlgorithm.DMR_DMRA_RC4,
            VoiceEncryptionAlgorithm.DMR_DMRA_AES_128, VoiceEncryptionAlgorithm.DMR_DMRA_AES_256,
            VoiceEncryptionAlgorithm.NXDN_SCRAMBLER, VoiceEncryptionAlgorithm.NXDN_DES_OFB,
            VoiceEncryptionAlgorithm.NXDN_AES_256_OFB);
    }

    @Override
    public Collection<VoiceFrameDecryptorProvider> getProviders()
    {
        return List.of(new Apco25VoiceFrameDecryptorProvider(), new DmrPrivacyFrameDecryptorProvider(),
            new NxdnVoiceFrameDecryptorProvider());
    }
}
