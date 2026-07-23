/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import org.junit.jupiter.api.Test;

class P25ActivityLogServiceQualityTest
{
    @Test
    void acceptsOnlyP25QualityForP25Persistence()
    {
        assertTrue(P25ActivityLogService.isP25ControlChannelQuality(
            quality(new DecodeConfigP25Phase1())));
        assertTrue(P25ActivityLogService.isP25ControlChannelQuality(
            quality(new DecodeConfigP25Phase2())));
        assertFalse(P25ActivityLogService.isP25ControlChannelQuality(
            quality(new DecodeConfigDMR())));
        assertFalse(P25ActivityLogService.isP25ControlChannelQuality(
            quality(new DecodeConfigNXDN())));
        assertFalse(P25ActivityLogService.isP25ControlChannelQuality(null));
    }

    private static ControlChannelQualitySnapshot quality(DecodeConfiguration configuration)
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(configuration);
        return new ControlChannelQualitySnapshot(channel, "123e4567-e89b-12d3-a456-426614174000",
            851_012_500L, 1_000L, true, -20.0, -21.0, -25.0, -18.0, 95.0,
            100, 2, 1, 0, 0, 999L);
    }
}
