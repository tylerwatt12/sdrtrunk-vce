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

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.voice.decrypt.module.SdrTrunkVoiceDecryptionModule;
import java.util.EnumSet;
import java.util.List;

/**
 * Immutable registry of the voice decryption providers included in every application package.
 * All lookups are in-memory and safe for receiver callbacks.
 */
public class VoiceDecryptionModuleManager implements AutoCloseable
{
    private final VoiceDecryptionModule mModule = new SdrTrunkVoiceDecryptionModule();
    private final List<VoiceFrameDecryptorProvider> mProviders = List.copyOf(mModule.getProviders());
    private final EnumSet<VoiceEncryptionAlgorithm> mSupportedAlgorithms =
        EnumSet.copyOf(mModule.getSupportedAlgorithms());
    private final Status mStatus = new Status(true, "Voice decryption included");

    public boolean isLoaded() { return true; }

    public String getStatus() { return mStatus.message(); }

    public Status statusSnapshot() { return mStatus; }

    public record Status(boolean loaded, String message) {}

    public String getModuleName() { return mModule.getName(); }

    public String getModuleVersion() { return mModule.getVersion(); }

    public List<VoiceFrameDecryptorProvider> getProviders() { return mProviders; }

    public List<VoiceEncryptionAlgorithm> getSupportedAlgorithms(VoiceEncryptionProtocol protocol)
    {
        return mSupportedAlgorithms.stream().filter(algorithm -> algorithm.getProtocol() == protocol).toList();
    }

    public boolean supports(VoiceEncryptionAlgorithm algorithm)
    {
        return algorithm != null && mSupportedAlgorithms.contains(algorithm);
    }

    /** The bundled registry owns no external files, class loaders, or other closeable resources. */
    @Override public void close() {}
}
