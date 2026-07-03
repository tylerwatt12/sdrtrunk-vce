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

import io.github.dsheirer.preference.encryption.EncryptionKeyPreference;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Resolves encrypted voice signaling to a configured key.
 */
public class VoiceEncryptionKeyResolver
{
    private final EncryptionKeyPreference mPreference;
    private final Collection<VoiceEncryptionKey> mStaticKeys;

    public VoiceEncryptionKeyResolver(EncryptionKeyPreference preference)
    {
        mPreference = preference;
        mStaticKeys = null;
    }

    public VoiceEncryptionKeyResolver(Collection<VoiceEncryptionKey> keys)
    {
        mPreference = null;
        mStaticKeys = keys;
    }

    public Optional<VoiceEncryptionKey> resolve(VoiceEncryptionContext context)
    {
        Collection<VoiceEncryptionKey> keys = mPreference != null ? mPreference.getKeys() : mStaticKeys;

        if(keys != null)
        {
            for(VoiceEncryptionKey key: keys)
            {
                if(key.matches(context))
                {
                    return Optional.of(key);
                }
            }
        }

        return Optional.empty();
    }

    public boolean hasKeys()
    {
        List<VoiceEncryptionKey> keys = mPreference != null ? mPreference.getKeys() :
            mStaticKeys == null ? List.of() : List.copyOf(mStaticKeys);
        return !keys.isEmpty();
    }
}
