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

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.EncryptionKeySchema;
import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.sample.Listener;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Ordinary SQLite configuration with a process-local snapshot for receiver callbacks. */
public class EncryptionKeyPreference extends Preference
{
    private final ApplicationSettingsStore mSettings;
    private volatile List<VoiceEncryptionKey> mKeys;

    public EncryptionKeyPreference(Listener<PreferenceType> updateListener, DirectoryPreference directoryPreference)
    {
        this(updateListener, new ApplicationSettingsStore(SdrTrunkDatabasePath.getDatabasePath(
            directoryPreference.getDirectoryApplicationRoot())));
    }

    public EncryptionKeyPreference(Listener<PreferenceType> updateListener, ApplicationSettingsStore settings)
    {
        super(updateListener);
        mSettings = settings;
        try
        {
            mKeys = sorted(EncryptionKeySchema.decode(settings.loadJson(EncryptionKeySchema.KEY,
                EncryptionKeySchema.MAXIMUM_BYTES).orElseThrow(() -> new SQLException("Voice encryption keys are missing"))));
        }
        catch(IOException | SQLException failure)
        {
            throw new IllegalStateException("Unable to load voice encryption keys; restore valid settings before starting");
        }
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.ENCRYPTION_KEYS;
    }

    /** No lock, filesystem access or database work occurs on receiver reads. */
    public List<VoiceEncryptionKey> getKeys()
    {
        return mKeys.stream().map(VoiceEncryptionKey::copy).toList();
    }

    public boolean hasKeys()
    {
        return !mKeys.isEmpty();
    }

    public Optional<VoiceEncryptionKey> resolve(VoiceEncryptionContext context)
    {
        for(VoiceEncryptionKey key: mKeys)
        {
            if(key.matches(context)) return Optional.of(key.copy());
        }
        return Optional.empty();
    }

    public synchronized void addKey(VoiceEncryptionKey key)
    {
        List<VoiceEncryptionKey> keys = new ArrayList<>(mKeys);
        keys.add(key.copy());
        replace(keys);
    }

    public synchronized void updateKey(VoiceEncryptionKey key)
    {
        List<VoiceEncryptionKey> keys = new ArrayList<>(mKeys);
        for(int index = 0; index < keys.size(); index++)
        {
            if(keys.get(index).getId().equals(key.getId()))
            {
                keys.set(index, key.copy());
                replace(keys);
                return;
            }
        }
        keys.add(key.copy());
        replace(keys);
    }

    public synchronized void removeKey(VoiceEncryptionKey key)
    {
        List<VoiceEncryptionKey> keys = new ArrayList<>(mKeys);
        if(keys.removeIf(existing -> existing.getId().equals(key.getId()))) replace(keys);
    }

    private void replace(Collection<VoiceEncryptionKey> keys)
    {
        List<VoiceEncryptionKey> next = sorted(keys);
        try
        {
            mSettings.save(EncryptionKeySchema.KEY, EncryptionKeySchema.encode(next));
        }
        catch(IOException | SQLException failure)
        {
            throw new IllegalStateException("Unable to save voice encryption keys; check database access and try again");
        }
        mKeys = next;
        notifyPreferenceUpdated();
    }

    private static List<VoiceEncryptionKey> sorted(Collection<VoiceEncryptionKey> keys)
    {
        return keys.stream().map(VoiceEncryptionKey::copy)
            .sorted(Comparator.comparing(VoiceEncryptionKey::toString, String.CASE_INSENSITIVE_ORDER)).toList();
    }
}
