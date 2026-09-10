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

package io.github.dsheirer.preference.decoder;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModuleManager;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Path and runtime manager for the optional voice decryption module.
 */
public class VoiceDecryptionModulePreference extends Preference
{
    private static final Logger mLog = LoggerFactory.getLogger(VoiceDecryptionModulePreference.class);
    private static final String PREFERENCE_KEY_PATH = "path.voice.decryption.module.1";
    private static final String PREFERENCE_KEY_REQUEST_ID = "voice.module.request.id.1";
    private static final String PREFERENCE_KEY_MODULE_KEY = "voice.module.key.1";
    private final Preferences mPreferences = Preferences.userNodeForPackage(VoiceDecryptionModulePreference.class);
    private final VoiceDecryptionModuleManager mModuleManager = new VoiceDecryptionModuleManager();
    private final String mRequestId;
    private Path mPath;
    private Path mPendingPath;

    public VoiceDecryptionModulePreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
        mRequestId = getOrCreateRequestId();
        String savedPath = mPreferences.get(PREFERENCE_KEY_PATH, null);
        String savedKey = mPreferences.get(PREFERENCE_KEY_MODULE_KEY, null);

        if(savedPath != null && !savedPath.isBlank() && savedKey != null && !savedKey.isBlank())
        {
            Path candidate = PortableApplicationPaths.resolvePortablePath(savedPath);

            if(mModuleManager.load(candidate, mRequestId, savedKey))
            {
                mPath = mModuleManager.getPath();
            }
        }
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.VOICE_DECRYPTION_MODULE;
    }

    public VoiceDecryptionModuleManager getModuleManager()
    {
        return mModuleManager;
    }

    public Path getPath()
    {
        return mPath;
    }

    public String getRequestId()
    {
        return mRequestId;
    }

    /**
     * Copies and checks a selected module without changing the active module.
     */
    public boolean preparePath(Path path)
    {
        mPendingPath = null;

        try
        {
            Path installed = PortableApplicationPaths.copyIntoDataDirectory(path, "modules");

            if(mModuleManager.isCompatible(installed))
            {
                mPendingPath = installed;
                return true;
            }
        }
        catch(java.io.IOException e)
        {
            mLog.error("Unable to copy optional module into the portable data directory", e);
        }

        notifyPreferenceUpdated();
        return false;
    }

    /**
     * Applies a key to the most recently checked module.
     */
    public boolean setKey(String key)
    {
        if(mPendingPath != null && key != null && !key.isBlank() &&
            mModuleManager.load(mPendingPath, mRequestId, key.strip()))
        {
            mPath = mModuleManager.getPath();
            mPreferences.put(PREFERENCE_KEY_PATH, PortableApplicationPaths.toPortablePath(mPath));
            mPreferences.put(PREFERENCE_KEY_MODULE_KEY, key.strip());
            mPendingPath = null;
            notifyPreferenceUpdated();
            return true;
        }

        notifyPreferenceUpdated();
        return false;
    }

    public void resetPath()
    {
        mPreferences.remove(PREFERENCE_KEY_PATH);
        mPreferences.remove(PREFERENCE_KEY_MODULE_KEY);
        mPath = null;
        mPendingPath = null;
        mModuleManager.unload();
        notifyPreferenceUpdated();
    }

    private String getOrCreateRequestId()
    {
        String stored = mPreferences.get(PREFERENCE_KEY_REQUEST_ID, null);

        if(stored != null)
        {
            try
            {
                String canonical = UUID.fromString(stored.strip()).toString();

                if(canonical.equals(stored.strip()))
                {
                    return canonical;
                }
            }
            catch(IllegalArgumentException ignored)
            {
                //Replace a malformed stored value.
            }
        }

        String created = UUID.randomUUID().toString();
        mPreferences.put(PREFERENCE_KEY_REQUEST_ID, created);
        return created;
    }
}
