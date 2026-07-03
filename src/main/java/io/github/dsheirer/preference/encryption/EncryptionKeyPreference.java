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
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Preferences-backed voice encryption key list.
 *
 * Keys are intentionally stored in the normal user preferences tree.  This does not provide secure secret storage.
 */
public class EncryptionKeyPreference extends Preference
{
    private static final Logger mLog = LoggerFactory.getLogger(EncryptionKeyPreference.class);
    private static final String NODE_KEYS = "keys";
    private static final String KEY_LABEL = "label";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PROTOCOL = "protocol";
    private static final String KEY_ALGORITHM_ID = "algorithm_id";
    private static final String KEY_KEY_ID = "key_id";
    private static final String KEY_KEY_HEX = "key_hex";
    private static final String KEY_SCOPE = "scope";
    private final Preferences mPreferences = Preferences.userNodeForPackage(EncryptionKeyPreference.class);
    private List<VoiceEncryptionKey> mKeys;

    public EncryptionKeyPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.ENCRYPTION_KEYS;
    }

    public synchronized List<VoiceEncryptionKey> getKeys()
    {
        if(mKeys == null)
        {
            load();
        }

        return copy(mKeys);
    }

    public synchronized void setKeys(Collection<VoiceEncryptionKey> keys)
    {
        mKeys = copy(keys);
        save();
        notifyPreferenceUpdated();
    }

    public synchronized void addKey(VoiceEncryptionKey key)
    {
        if(mKeys == null)
        {
            load();
        }

        mKeys.add(key.copy());
        save();
        notifyPreferenceUpdated();
    }

    public synchronized void updateKey(VoiceEncryptionKey key)
    {
        if(mKeys == null)
        {
            load();
        }

        for(int x = 0; x < mKeys.size(); x++)
        {
            if(mKeys.get(x).getId().equals(key.getId()))
            {
                mKeys.set(x, key.copy());
                save();
                notifyPreferenceUpdated();
                return;
            }
        }

        addKey(key);
    }

    public synchronized void removeKey(VoiceEncryptionKey key)
    {
        if(mKeys == null)
        {
            load();
        }

        if(mKeys.removeIf(existing -> existing.getId().equals(key.getId())))
        {
            save();
            notifyPreferenceUpdated();
        }
    }

    private void load()
    {
        mKeys = new ArrayList<>();
        Preferences keysNode = mPreferences.node(NODE_KEYS);

        try
        {
            for(String child: keysNode.childrenNames())
            {
                VoiceEncryptionKey key = readKey(child, keysNode.node(child));

                if(key != null)
                {
                    mKeys.add(key);
                }
            }
        }
        catch(BackingStoreException bse)
        {
            mLog.warn("Unable to load voice encryption key preferences", bse);
        }

        mKeys.sort(Comparator.comparing(VoiceEncryptionKey::toString, String.CASE_INSENSITIVE_ORDER));
    }

    private VoiceEncryptionKey readKey(String id, Preferences node)
    {
        try
        {
            VoiceEncryptionKey key = new VoiceEncryptionKey(id);
            key.setLabel(node.get(KEY_LABEL, null));
            key.setEnabled(node.getBoolean(KEY_ENABLED, true));
            key.setProtocol(VoiceEncryptionProtocol.valueOf(node.get(KEY_PROTOCOL, VoiceEncryptionProtocol.APCO25.name())));
            key.setAlgorithmId(node.getInt(KEY_ALGORITHM_ID, 0));
            key.setKeyId(node.getInt(KEY_KEY_ID, 0));
            key.setKeyHex(node.get(KEY_KEY_HEX, null));
            key.setScope(node.get(KEY_SCOPE, null));
            return key;
        }
        catch(Exception e)
        {
            mLog.warn("Unable to load voice encryption key preference [" + id + "]", e);
            return null;
        }
    }

    private void save()
    {
        Preferences keysNode = mPreferences.node(NODE_KEYS);

        try
        {
            for(String child: keysNode.childrenNames())
            {
                keysNode.node(child).removeNode();
            }

            for(VoiceEncryptionKey key: mKeys)
            {
                Preferences keyNode = keysNode.node(key.getId());
                putNullable(keyNode, KEY_LABEL, key.getLabel());
                keyNode.putBoolean(KEY_ENABLED, key.isEnabled());
                keyNode.put(KEY_PROTOCOL, key.getProtocol().name());
                keyNode.putInt(KEY_ALGORITHM_ID, key.getAlgorithmId());
                keyNode.putInt(KEY_KEY_ID, key.getKeyId());
                putNullable(keyNode, KEY_KEY_HEX, key.getKeyHex());
                putNullable(keyNode, KEY_SCOPE, key.getScope());
            }

            keysNode.flush();
        }
        catch(BackingStoreException bse)
        {
            mLog.warn("Unable to save voice encryption key preferences", bse);
        }
    }

    private void putNullable(Preferences node, String key, String value)
    {
        if(value == null)
        {
            node.remove(key);
        }
        else
        {
            node.put(key, value);
        }
    }

    private List<VoiceEncryptionKey> copy(Collection<VoiceEncryptionKey> keys)
    {
        List<VoiceEncryptionKey> copy = new ArrayList<>();

        if(keys != null)
        {
            for(VoiceEncryptionKey key: keys)
            {
                copy.add(key.copy());
            }
        }

        return copy;
    }
}
