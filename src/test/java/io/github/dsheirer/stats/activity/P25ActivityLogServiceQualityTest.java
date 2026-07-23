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
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import org.junit.jupiter.api.Test;

class P25ActivityLogServiceQualityTest
{
    @Test
    void acceptsQualityForEverySupportedTrunkedProtocol()
    {
        assertTrue(P25ActivityLogService.isTrunkedControlChannelQuality(
            quality(new DecodeConfigP25Phase1())));
        assertTrue(P25ActivityLogService.isTrunkedControlChannelQuality(
            quality(new DecodeConfigP25Phase2())));
        assertTrue(P25ActivityLogService.isTrunkedControlChannelQuality(
            quality(new DecodeConfigDMR())));
        assertTrue(P25ActivityLogService.isTrunkedControlChannelQuality(
            quality(new DecodeConfigNXDN())));
        assertFalse(P25ActivityLogService.isTrunkedControlChannelQuality(null));

        assertTrue(P25ActivityLogService.shouldPersistControlChannelQuality(
            quality(new DecodeConfigP25Phase1()), false));
        assertTrue(P25ActivityLogService.shouldPersistControlChannelQuality(
            quality(new DecodeConfigP25Phase2()), false));
        assertFalse(P25ActivityLogService.shouldPersistControlChannelQuality(
            quality(new DecodeConfigDMR()), false));
        assertFalse(P25ActivityLogService.shouldPersistControlChannelQuality(
            quality(new DecodeConfigNXDN()), false));
        assertTrue(P25ActivityLogService.shouldPersistControlChannelQuality(
            quality(new DecodeConfigDMR()), true));
        assertTrue(P25ActivityLogService.shouldPersistControlChannelQuality(
            quality(new DecodeConfigNXDN()), true));
        assertFalse(P25ActivityLogService.shouldPersistControlChannelQuality(null, true));
    }

    @Test
    void requiresEvidenceFromTheSameChannelAndDecoderConfiguration()
    {
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        Channel trunked = channel(configuration);
        Channel sameGuidConventional = channel(new DecodeConfigDMR());
        P25ActivityLogService.TrunkedSiteEvidence evidence =
            new P25ActivityLogService.TrunkedSiteEvidence(trunked, configuration, DecoderType.DMR);

        assertTrue(P25ActivityLogService.hasCurrentTrunkedSiteEvidence(
            quality(trunked), evidence));
        assertFalse(P25ActivityLogService.hasCurrentTrunkedSiteEvidence(
            quality(sameGuidConventional), evidence));

        trunked.setDecodeConfiguration(new DecodeConfigDMR());
        assertFalse(P25ActivityLogService.hasCurrentTrunkedSiteEvidence(
            quality(trunked), evidence));
    }

    private static ControlChannelQualitySnapshot quality(DecodeConfiguration configuration)
    {
        return quality(channel(configuration));
    }

    private static Channel channel(DecodeConfiguration configuration)
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }

    private static ControlChannelQualitySnapshot quality(Channel channel)
    {
        return new ControlChannelQualitySnapshot(channel, "123e4567-e89b-12d3-a456-426614174000",
            851_012_500L, 1_000L, true, -20.0, -21.0, -25.0, -18.0, 95.0,
            100, 2, 1, 0, 0, 999L);
    }
}
