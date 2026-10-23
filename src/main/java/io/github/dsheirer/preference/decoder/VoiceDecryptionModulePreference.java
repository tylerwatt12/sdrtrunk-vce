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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final String LEGACY_REQUEST_ID = "voice.module.request.id.1";
    private static final String LEGACY_MODULE_KEY = "voice.module.key.1";
    private final Preferences mPreferences;
    private final VoiceDecryptionModuleManager mModuleManager = new VoiceDecryptionModuleManager();
    private Path mPath;

    public VoiceDecryptionModulePreference(Listener<PreferenceType> updateListener)
    {
        this(updateListener, Preferences.userNodeForPackage(VoiceDecryptionModulePreference.class));
    }

    VoiceDecryptionModulePreference(Listener<PreferenceType> updateListener, Preferences preferences)
    {
        super(updateListener);
        mPreferences = preferences;
        mPreferences.remove(LEGACY_REQUEST_ID);
        mPreferences.remove(LEGACY_MODULE_KEY);
        String savedPath = mPreferences.get(PREFERENCE_KEY_PATH, null);

        if(savedPath != null && !savedPath.isBlank())
        {
            mPath = PortableApplicationPaths.resolvePortablePath(savedPath);
            mModuleManager.load(mPath);
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

    /**
     * Checks, installs and loads a selected module. Rejected selections keep the active module.
     */
    public boolean setPath(Path path) throws IOException
    {
        if(!mModuleManager.isCompatible(path))
        {
            notifyPreferenceUpdated();
            return false;
        }

        try
        {
            Path installed = copyModule(path);

            if(mModuleManager.load(installed))
            {
                mPath = mModuleManager.getPath();
                mPreferences.put(PREFERENCE_KEY_PATH, PortableApplicationPaths.toPortablePath(mPath));
                notifyPreferenceUpdated();
                return true;
            }
        }
        catch(IOException e)
        {
            mLog.error("Unable to copy optional module into the portable data directory", e);
            throw e;
        }

        notifyPreferenceUpdated();
        return false;
    }

    private Path copyModule(Path source) throws IOException
    {
        Path directory = PortableApplicationPaths.getDataRoot().resolve("modules");
        Path target = directory.resolve(source.getFileName());

        if(!Files.exists(target))
        {
            return PortableApplicationPaths.copyIntoDataDirectory(source, "modules");
        }

        if(Files.mismatch(source, target) == -1) return target;

        //Keep an active JAR intact until its replacement has loaded, including on Windows.
        Path replacement = Files.createTempFile(directory, "voice-module-", ".jar");
        boolean copied = false;

        try
        {
            Files.copy(source, replacement, StandardCopyOption.REPLACE_EXISTING);
            copied = true;
            return replacement;
        }
        finally
        {
            if(!copied) Files.deleteIfExists(replacement);
        }
    }

    public void resetPath()
    {
        mPreferences.remove(PREFERENCE_KEY_PATH);
        mPath = null;
        mModuleManager.unload();
        notifyPreferenceUpdated();
    }
}
