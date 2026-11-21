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

import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import java.util.Optional;

/**
 * DMRA known-key voice privacy algorithms.
 */
public enum DmrPrivacyAlgorithm
{
    DMRA_RC4(VoiceEncryptionAlgorithm.DMR_DMRA_RC4),
    DMRA_AES_128(VoiceEncryptionAlgorithm.DMR_DMRA_AES_128),
    DMRA_AES_256(VoiceEncryptionAlgorithm.DMR_DMRA_AES_256);

    private final VoiceEncryptionAlgorithm mAlgorithm;

    DmrPrivacyAlgorithm(VoiceEncryptionAlgorithm algorithm)
    {
        if(!algorithm.hasExpectedKeyLength())
        {
            throw new IllegalArgumentException("DMR privacy decryptor requires a fixed-length algorithm");
        }

        mAlgorithm = algorithm;
    }

    public int getAlgorithmId()
    {
        return mAlgorithm.getValue();
    }

    public String getLabel()
    {
        return mAlgorithm.toString();
    }

    public int getKeyBytes()
    {
        return mAlgorithm.getExpectedKeyBytes();
    }

    public static Optional<DmrPrivacyAlgorithm> fromAlgorithmId(int algorithmId)
    {
        for(DmrPrivacyAlgorithm algorithm: values())
        {
            if(algorithm.getAlgorithmId() == algorithmId)
            {
                return Optional.of(algorithm);
            }
        }

        return Optional.empty();
    }
}
