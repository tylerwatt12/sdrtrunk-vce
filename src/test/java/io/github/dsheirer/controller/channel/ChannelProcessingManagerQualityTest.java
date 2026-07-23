/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerQualityTest
{
    @Test
    void enablesQualityOnlyForSupportedStandardParentChannels()
    {
        assertTrue(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigP25Phase1())));
        assertTrue(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigP25Phase2())));
        assertTrue(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigDMR())));
        assertTrue(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigNXDN())));

        assertFalse(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.TRAFFIC, new DecodeConfigDMR())));
        assertFalse(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigNBFM())));
        assertFalse(ChannelProcessingManager.supportsControlChannelQuality(null));
    }

    private static Channel channel(ChannelType type, DecodeConfiguration configuration)
    {
        Channel channel = new Channel("Test", type);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }
}
