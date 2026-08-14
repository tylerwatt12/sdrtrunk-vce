/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.AudioFormats;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigMixer;
import io.github.dsheirer.source.mixer.MixerChannel;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RetiredSoundCardSourceTest
{
    @Test
    void compatibilityDataTypesRemainWithoutBecomingSelectable()
    {
        SourceConfigMixer compatibility = new SourceConfigMixer();
        compatibility.setMixer("Legacy Line Input");
        compatibility.setChannel(MixerChannel.RIGHT);

        assertTrue(SourceType.MIXER.isRetiredCompatibility());
        assertTrue(compatibility.getSourceType().isRetiredCompatibility());
        assertFalse(Arrays.asList(SourceType.getTypes()).contains(SourceType.MIXER));
    }

    @Test
    void soundCardCaptureImplementationsAreNotPackaged()
    {
        for(String removedClass : new String[]{
            "io.github.dsheirer.source.mixer.MixerManager",
            "io.github.dsheirer.source.mixer.MixerReader",
            "io.github.dsheirer.source.mixer.RealMixerSource",
            "io.github.dsheirer.source.mixer.MixerChannelConfiguration",
            "io.github.dsheirer.sample.adapter.RealChannelShortAdapter"})
        {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(removedClass), removedClass);
        }
    }

    @Test
    void receiverCallPlaybackAndStereoOutputAreNotPackaged()
    {
        for(String playbackClass : new String[]{
            "io.github.dsheirer.audio.IAudioController",
            "io.github.dsheirer.audio.playback.AudioPlaybackManager",
            "io.github.dsheirer.audio.playback.AudioPanel",
            "io.github.dsheirer.audio.playback.AudioOutput",
            "io.github.dsheirer.audio.playback.ManagedPlayableAudioCall",
            "io.github.dsheirer.preference.playback.PlaybackPreference"})
        {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(playbackClass), playbackClass);
        }

        assertThrows(NoSuchFieldException.class,
            () -> AudioFormats.class.getField("PCM_SIGNED_8000_HZ_16BITS_STEREO"));
    }

    @Test
    void tunerManagerRejectsCompatibilitySourceBeforeHardwareAllocation()
    {
        TunerManager tunerManager = new TunerManager(new UserPreferences());
        SourceConfigMixer compatibility = new SourceConfigMixer();

        assertThrows(SourceException.class, () -> tunerManager.getSource(compatibility,
            new ChannelSpecification(8_000, 12_500, 5_000, 6_500), "retired sound card"));
    }

    @Test
    void legacySpeakerDataLineConstantsRemainRetired()
    {
        assertThrows(NoSuchFieldException.class, () -> AudioFormats.class.getField("MONO_SOURCE_DATALINE_INFO"));
        assertThrows(NoSuchFieldException.class, () -> AudioFormats.class.getField("STEREO_SOURCE_DATALINE_INFO"));
    }
}
