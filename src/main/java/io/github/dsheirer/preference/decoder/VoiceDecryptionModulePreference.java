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
    private final Preferences mPreferences = Preferences.userNodeForPackage(VoiceDecryptionModulePreference.class);
    private final VoiceDecryptionModuleManager mModuleManager = new VoiceDecryptionModuleManager();
    private Path mPath;

    public VoiceDecryptionModulePreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
        String savedPath = mPreferences.get(PREFERENCE_KEY_PATH, null);

        if(savedPath != null && !savedPath.isBlank())
        {
            Path candidate = PortableApplicationPaths.resolvePortablePath(savedPath);

            if(mModuleManager.load(candidate))
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

    public boolean setPath(Path path)
    {
        try
        {
            Path installed = PortableApplicationPaths.copyIntoDataDirectory(path, "modules");

            if(mModuleManager.load(installed))
            {
                mPath = mModuleManager.getPath();
                mPreferences.put(PREFERENCE_KEY_PATH, PortableApplicationPaths.toPortablePath(mPath));
                notifyPreferenceUpdated();
                return true;
            }
        }
        catch(java.io.IOException e)
        {
            mLog.error("Unable to copy voice decryption module into the portable data directory", e);
        }

        notifyPreferenceUpdated();
        return false;
    }

    public void resetPath()
    {
        mPreferences.remove(PREFERENCE_KEY_PATH);
        mPath = null;
        mModuleManager.unload();
        notifyPreferenceUpdated();
    }
}
