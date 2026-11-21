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

package io.github.dsheirer.audio.codec.mbe.decrypt.nxdn;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorProvider;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;

/**
 * Provider for standardized NXDN EHR voice cipher types.
 */
public class NxdnVoiceFrameDecryptorProvider implements VoiceFrameDecryptorProvider
{
    @Override
    public boolean supports(VoiceEncryptionContext context)
    {
        if(context == null || context.getProtocol() != VoiceEncryptionProtocol.NXDN)
        {
            return false;
        }

        return context.getAlgorithmId() >= NxdnPrivacyFrameDecryptor.SCRAMBLER &&
            context.getAlgorithmId() <= NxdnPrivacyFrameDecryptor.AES_256_OFB;
    }

    @Override
    public VoiceFrameDecryptor create(VoiceEncryptionContext context, VoiceEncryptionKey key)
        throws VoiceFrameDecryptionException
    {
        return new NxdnPrivacyFrameDecryptor(context, key);
    }
}
