/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt.dmr;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorProvider;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;

/**
 * DMR known-key voice privacy provider.
 */
public class DmrPrivacyFrameDecryptorProvider implements VoiceFrameDecryptorProvider
{
    @Override
    public boolean supports(VoiceEncryptionContext context)
    {
        return context != null && context.getProtocol() == VoiceEncryptionProtocol.DMR &&
            DmrPrivacyAlgorithm.fromAlgorithmId(context.getAlgorithmId()).isPresent();
    }

    @Override
    public VoiceFrameDecryptor create(VoiceEncryptionContext context, VoiceEncryptionKey key)
        throws VoiceFrameDecryptionException
    {
        DmrPrivacyAlgorithm algorithm = DmrPrivacyAlgorithm.fromAlgorithmId(context.getAlgorithmId())
            .orElseThrow(() -> new VoiceFrameDecryptionException("Unsupported DMR privacy algorithm"));
        return new DmrPrivacyFrameDecryptor(context, key, algorithm);
    }
}
