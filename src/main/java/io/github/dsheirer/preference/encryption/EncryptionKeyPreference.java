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

package io.github.dsheirer.preference.encryption;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultException;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vault-backed voice encryption key list.
 */
public class EncryptionKeyPreference extends Preference
{
    private static final Logger mLog = LoggerFactory.getLogger(EncryptionKeyPreference.class);
    private final EncryptionKeyVaultService mVaultService;

    public EncryptionKeyPreference(Listener<PreferenceType> updateListener, DirectoryPreference directoryPreference)
    {
        super(updateListener);
        mVaultService = new EncryptionKeyVaultService(EncryptionKeyVaultPath.getVaultPath(directoryPreference));
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.ENCRYPTION_KEYS;
    }

    public EncryptionKeyVaultService getVaultService()
    {
        return mVaultService;
    }

    public synchronized List<VoiceEncryptionKey> getKeys()
    {
        return sorted(mVaultService.getKeys());
    }

    public synchronized void setKeys(Collection<VoiceEncryptionKey> keys)
    {
        replace(keys);
    }

    public synchronized void addKey(VoiceEncryptionKey key)
    {
        List<VoiceEncryptionKey> keys = new ArrayList<>(mVaultService.getKeys());
        keys.add(key.copy());
        replace(keys);
    }

    public synchronized void updateKey(VoiceEncryptionKey key)
    {
        List<VoiceEncryptionKey> keys = new ArrayList<>(mVaultService.getKeys());

        for(int x = 0; x < keys.size(); x++)
        {
            if(keys.get(x).getId().equals(key.getId()))
            {
                keys.set(x, key.copy());
                replace(keys);
                return;
            }
        }

        keys.add(key.copy());
        replace(keys);
    }

    public synchronized void removeKey(VoiceEncryptionKey key)
    {
        List<VoiceEncryptionKey> keys = new ArrayList<>(mVaultService.getKeys());

        if(keys.removeIf(existing -> existing.getId().equals(key.getId())))
        {
            replace(keys);
        }
    }

    private void replace(Collection<VoiceEncryptionKey> keys)
    {
        try
        {
            mVaultService.replaceKeys(keys);
            notifyPreferenceUpdated();
        }
        catch(EncryptionKeyVaultException e)
        {
            mLog.warn("Unable to save voice encryption keys to vault", e);
        }
    }

    private List<VoiceEncryptionKey> sorted(Collection<VoiceEncryptionKey> keys)
    {
        return keys.stream().map(VoiceEncryptionKey::copy)
            .sorted(Comparator.comparing(VoiceEncryptionKey::toString, String.CASE_INSENSITIVE_ORDER)).toList();
    }
}
