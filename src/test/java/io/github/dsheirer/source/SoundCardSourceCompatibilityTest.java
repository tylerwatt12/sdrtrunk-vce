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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.AudioFormats;
import io.github.dsheirer.source.config.SourceConfigFactory;
import io.github.dsheirer.source.config.SourceConfigMixer;
import io.github.dsheirer.source.mixer.MixerChannel;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SoundCardSourceCompatibilityTest
{
    @Test
    void soundCardConfigurationIsSelectableAndCopyable()
    {
        SourceConfigMixer original = new SourceConfigMixer();
        original.setMixer("Line Input");
        original.setChannel(MixerChannel.RIGHT);

        assertTrue(SourceType.MIXER.isActive());
        assertTrue(Arrays.asList(SourceType.getTypes()).contains(SourceType.MIXER));
        SourceConfigMixer copy = assertInstanceOf(SourceConfigMixer.class, SourceConfigFactory.copy(original));
        assertEquals("Line Input", copy.getMixer());
        assertEquals(MixerChannel.RIGHT, copy.getChannel());
    }

    @Test
    void soundCardCaptureImplementationsAndFormatsArePackaged() throws Exception
    {
        for(String restoredClass: new String[]{
            "io.github.dsheirer.source.mixer.MixerManager",
            "io.github.dsheirer.source.mixer.MixerReader",
            "io.github.dsheirer.source.mixer.RealMixerSource",
            "io.github.dsheirer.source.mixer.MixerChannelConfiguration",
            "io.github.dsheirer.sample.adapter.RealChannelShortAdapter"})
        {
            Class.forName(restoredClass);
        }

        assertNotNull(AudioFormats.MONO_SOURCE_DATALINE_INFO);
        assertNotNull(AudioFormats.STEREO_SOURCE_DATALINE_INFO);
        assertNotNull(AudioFormats.PCM_SIGNED_8000_HZ_16BITS_STEREO);
    }
}
