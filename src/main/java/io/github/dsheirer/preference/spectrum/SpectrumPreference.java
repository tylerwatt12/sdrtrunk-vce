/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.spectrum;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.spectrum.DFTSize;
import java.util.prefs.Preferences;

/**
 * Spectrum and waterfall display preferences.
 */
public class SpectrumPreference extends Preference
{
    public static final String KEY_DFT_SIZE = "spectral.display.dft.size";
    public static final String KEY_FRAME_RATE = "spectral.display.frame.rate";
    private final Preferences mPreferences = Preferences.userNodeForPackage(SpectrumPreference.class);

    public SpectrumPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.SPECTRUM;
    }

    public DFTSize getDftSize()
    {
        try
        {
            return DFTSize.valueOf(mPreferences.get(KEY_DFT_SIZE, DFTSize.FFT04096.name()));
        }
        catch(IllegalArgumentException e)
        {
            return DFTSize.FFT04096;
        }
    }

    public void setDftSize(DFTSize size)
    {
        mPreferences.put(KEY_DFT_SIZE, size.name());
        notifyPreferenceUpdated();
    }

    public int getFrameRate()
    {
        return Math.clamp(mPreferences.getInt(KEY_FRAME_RATE, 20), 1, 1000);
    }

    public void setFrameRate(int frameRate)
    {
        mPreferences.putInt(KEY_FRAME_RATE, Math.clamp(frameRate, 1, 1000));
        notifyPreferenceUpdated();
    }
}
