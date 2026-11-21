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
import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;

/**
 * Access to the voice decryption providers bundled with the application.
 */
public class VoiceDecryptionModulePreference extends Preference
{
    private final VoiceDecryptionModuleManager mModuleManager = new VoiceDecryptionModuleManager();

    public VoiceDecryptionModulePreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
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
}
