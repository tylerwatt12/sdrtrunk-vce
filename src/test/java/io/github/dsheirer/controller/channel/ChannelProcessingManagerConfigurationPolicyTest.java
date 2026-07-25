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

package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.mpt1327.DecodeConfigMPT1327;
import io.github.dsheirer.source.config.SourceConfigMixer;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerConfigurationPolicyTest
{
    @Test
    void restoredMptDecoderIsRunnable()
    {
        assertTrue(ChannelProcessingManager.isRunnable(channel("MPT", new DecodeConfigMPT1327())));
    }

    @Test
    void autoStartListIncludesRestoredMptChannels()
    {
        Channel active = channel("Active", new DecodeConfigDMR());
        active.setAutoStart(true);
        active.setAutoStartOrder(2);
        Channel mpt = channel("MPT", new DecodeConfigMPT1327());
        mpt.setAutoStart(true);
        mpt.setAutoStartOrder(1);

        ChannelModel model = new ChannelModel(new AliasModel());
        model.addChannels(List.of(mpt, active));

        assertTrue(ChannelProcessingManager.isRunnable(active));
        assertEquals(List.of(mpt, active), model.getAutoStartChannels());
    }

    @Test
    void restoredSoundCardSourceIsRunnable()
    {
        Channel soundCard = channel("Sound card", new DecodeConfigDMR());
        soundCard.setSourceConfiguration(new SourceConfigMixer());

        assertTrue(ChannelProcessingManager.isRunnable(soundCard));
    }

    @Test
    void rejectsMissingConfiguration()
    {
        assertFalse(ChannelProcessingManager.isRunnable(null));
    }

    private static Channel channel(String name,
                                   io.github.dsheirer.module.decode.config.DecodeConfiguration decoder)
    {
        Channel channel = new Channel(name);
        channel.setDecodeConfiguration(decoder);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(460_000_000L);
        channel.setSourceConfiguration(source);
        return channel;
    }

}
