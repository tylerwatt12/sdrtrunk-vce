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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.mpt1327.DecodeConfigMPT1327;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerRetiredDecoderTest
{
    @Test
    void rejectsRetiredDecoderBeforeRequestingATunerSource() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        CountingTunerManager tunerManager = new CountingTunerManager(preferences);
        ChannelProcessingManager manager = new ChannelProcessingManager(null, tunerManager, new AliasModel(),
            preferences);
        Channel retired = channel("Retired", new DecodeConfigMPT1327());

        assertThrows(ChannelException.class, () -> manager.start(retired));
        assertEquals(0, tunerManager.getSourceRequests());
        assertFalse(ChannelProcessingManager.isRunnable(retired));
        assertThrows(IllegalArgumentException.class,
            () -> DecoderFactory.getPrimaryModules(retired, null, null, null, null, 0, null));
    }

    @Test
    void autoStartListExcludesRetiredCompatibilityChannels()
    {
        Channel active = channel("Active", new DecodeConfigDMR());
        active.setAutoStart(true);
        active.setAutoStartOrder(2);
        Channel retired = channel("Retired", new DecodeConfigMPT1327());
        retired.setAutoStart(true);
        retired.setAutoStartOrder(1);

        ChannelModel model = new ChannelModel(new AliasModel());
        model.addChannels(List.of(retired, active));

        assertTrue(ChannelProcessingManager.isRunnable(active));
        assertEquals(List.of(active), model.getAutoStartChannels());
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

    private static class CountingTunerManager extends TunerManager
    {
        private int mSourceRequests;

        private CountingTunerManager(UserPreferences preferences)
        {
            super(preferences);
        }

        @Override
        public Source getSource(SourceConfiguration configuration, ChannelSpecification channelSpecification,
                                String threadName) throws SourceException
        {
            mSourceRequests++;
            return null;
        }

        private int getSourceRequests()
        {
            return mSourceRequests;
        }
    }
}
