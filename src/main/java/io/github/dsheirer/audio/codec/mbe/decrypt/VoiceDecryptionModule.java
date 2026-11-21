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
import java.util.Collection;

/**
 * Algorithm and provider catalog for bundled voice decryption.
 */
public interface VoiceDecryptionModule
{
    int API_VERSION = 3;

    int getApiVersion();

    String getName();

    String getVersion();

    Collection<VoiceEncryptionAlgorithm> getSupportedAlgorithms();

    Collection<VoiceFrameDecryptorProvider> getProviders();
}
