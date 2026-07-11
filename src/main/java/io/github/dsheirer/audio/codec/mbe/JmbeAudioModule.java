/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.audio.codec.mbe;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.audio.squelch.ISquelchStateListener;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import java.nio.file.Path;
import jmbe.iface.IAudioCodec;

/**
 * Base audio module for protocols that use the JMBE audio codec.
 */
public abstract class JmbeAudioModule extends AbstractAudioModule implements Listener<IMessage>, IMessageListener,
    ISquelchStateListener
{
    private static final JmbeLibraryLoader LIBRARY_LOADER = JmbeLibraryLoader.getInstance();
    private volatile IAudioCodec mAudioCodec;
    private final UserPreferences mUserPreferences;

    protected JmbeAudioModule(UserPreferences userPreferences, AliasList aliasList, int timeslot)
    {
        super(aliasList, timeslot, DEFAULT_SEGMENT_AUDIO_SAMPLE_LENGTH);
        mUserPreferences = userPreferences;
        MyEventBus.getGlobalEventBus().register(this);
        loadConverter();
    }

    @Override
    protected void closeAudioSegment()
    {
        super.closeAudioSegment();

        //Reset the audio codec to clear any leftover frame data from the previous call.
        IAudioCodec audioCodec = mAudioCodec;

        if(audioCodec != null)
        {
            audioCodec.reset();
        }
    }

    @Override
    public void dispose()
    {
        super.dispose();
        MyEventBus.getGlobalEventBus().unregister(this);
    }

    public IAudioCodec getAudioCodec()
    {
        return mAudioCodec;
    }

    /**
     * Indicates that the JMBE audio library has been loaded and a suitable audio codec is usable (ie non-null)
     */
    protected boolean hasAudioCodec()
    {
        return getAudioCodec() != null;
    }

    @Override
    public Listener<IMessage> getMessageListener()
    {
        return this;
    }

    /**
     * Receives notifications that the JMBE library preference has been updated via the Guava event bus
     *
     * @param preferenceType that was updated
     */
    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.JMBE_LIBRARY)
        {
            loadConverter();
        }
    }

    /**
     * Name of the CODEC to use from the JMBE library
     */
    protected abstract String getCodecName();

    /**
     * Loads JMBE audio converter library class and then instantiates new converter instances from the loaded class.
     */
    protected void loadConverter()
    {
        Path path = mUserPreferences.getJmbeLibraryPreference().getPathJmbeLibrary();
        mAudioCodec = LIBRARY_LOADER.getAudioCodec(path, getCodecName());
    }
}
