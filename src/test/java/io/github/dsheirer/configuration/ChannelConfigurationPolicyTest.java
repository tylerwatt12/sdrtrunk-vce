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

package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.mpt1327.DecodeConfigMPT1327;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.SourceType;
import io.github.dsheirer.source.config.SourceConfigFactory;
import io.github.dsheirer.source.config.SourceConfigMixer;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ChannelConfigurationPolicyTest
{
    @Test
    void classifiesActiveAndRetiredChannels()
    {
        Channel active = channel(new DecodeConfigDMR());
        Channel retired = channel(new DecodeConfigMPT1327());
        Channel retiredSource = channel(new DecodeConfigDMR());
        retiredSource.setSourceConfiguration(new SourceConfigMixer());

        assertTrue(ChannelConfigurationPolicy.isActive(active));
        assertFalse(ChannelConfigurationPolicy.isRetired(active));
        assertFalse(ChannelConfigurationPolicy.isActive(retired));
        assertTrue(ChannelConfigurationPolicy.isRetired(retired));
        assertThrows(IllegalArgumentException.class,
            () -> ChannelConfigurationPolicy.requireChannelKind(retired));
        assertFalse(ChannelConfigurationPolicy.isActive(retiredSource));
        assertTrue(ChannelConfigurationPolicy.isRetired(retiredSource));
        assertThrows(IllegalArgumentException.class,
            () -> ChannelConfigurationPolicy.requireChannelKind(retiredSource));
    }

    @Test
    void classifiesPersistedTypesBeforeJsonBinding()
    {
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("DMR", "TUNER"));
        assertTrue(ChannelConfigurationPolicy.isRetiredPersisted("MPT1327", "TUNER"));
        assertTrue(ChannelConfigurationPolicy.isRetiredPersisted("DMR", "MIXER"));
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("mpt1327", "TUNER"));
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("MPT1327 ", "TUNER"));
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("DMR", "mixer"));
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("DMR", "REMOVED_SOURCE"));
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("FUTURE_DECODER", "TUNER"));
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("AM", "TUNER"));
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("LTR", "TUNER"));
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("LTR_NET", "TUNER"));
        assertFalse(ChannelConfigurationPolicy.isRetiredPersisted("PASSPORT", "TUNER"));
    }

    @Test
    void retiredCompatibilityValuesAreNotSelectable()
    {
        assertTrue(DecoderType.MPT1327.isRetiredCompatibility());
        assertFalse(DecoderType.PRIMARY_DECODERS.contains(DecoderType.MPT1327));
        assertFalse(DecoderType.BITSTREAM_DECODERS.contains(DecoderType.MPT1327));
        assertTrue(Protocol.MPT1327.isRetiredCompatibility());
        assertFalse(Protocol.TALKGROUP_PROTOCOLS.contains(Protocol.MPT1327));
        assertTrue(SourceType.TUNER.isActive());
        assertFalse(SourceType.NONE.isActive());
        assertFalse(SourceType.NONE.isRetiredCompatibility());
        assertFalse(Arrays.asList(SourceType.getTypes()).contains(SourceType.NONE));
        assertTrue(SourceType.MIXER.isRetiredCompatibility());
        assertFalse(Arrays.asList(SourceType.getTypes()).contains(SourceType.MIXER));
        assertThrows(IllegalArgumentException.class,
            () -> SourceConfigFactory.getSourceConfiguration(SourceType.MIXER));
        assertThrows(IllegalArgumentException.class,
            () -> SourceConfigFactory.copy(new SourceConfigMixer()));
    }

    private static Channel channel(io.github.dsheirer.module.decode.config.DecodeConfiguration decoder)
    {
        Channel channel = new Channel("Test");
        channel.setDecodeConfiguration(decoder);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(460_000_000L);
        channel.setSourceConfiguration(source);
        return channel;
    }
}
