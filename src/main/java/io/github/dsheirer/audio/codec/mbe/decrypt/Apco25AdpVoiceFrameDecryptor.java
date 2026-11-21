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

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;

/**
 * APCO25/Motorola ADP decryptor for P25 Phase 1 IMBE voice frames.
 */
public class Apco25AdpVoiceFrameDecryptor extends P25Phase1ImbeVoiceFrameDecryptor
{
    public static final int ALGORITHM_ID = P25VoiceKeystreamFactory.ADP_ALGORITHM_ID;

    public Apco25AdpVoiceFrameDecryptor(VoiceEncryptionContext context, VoiceEncryptionKey key)
        throws VoiceFrameDecryptionException
    {
        this(P25VoiceKeystreamFactory.create(context, key, P25VoiceKeystreamFactory.Phase.PHASE1));
    }

    private Apco25AdpVoiceFrameDecryptor(P25VoiceKeystreamFactory.P25Keystream keystream)
        throws VoiceFrameDecryptionException
    {
        super(keystream.keystream(), keystream.voicePayloadStartOffset());
    }
}
