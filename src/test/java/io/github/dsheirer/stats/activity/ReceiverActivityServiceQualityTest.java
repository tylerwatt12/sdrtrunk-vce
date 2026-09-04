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
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import org.junit.jupiter.api.Test;

class ReceiverActivityServiceQualityTest
{
    @Test
    void acceptsQualityForEverySupportedTrunkedProtocol()
    {
        assertTrue(ReceiverActivityService.isTrunkedControlChannelQuality(
            quality(new DecodeConfigP25Phase1())));
        assertTrue(ReceiverActivityService.isTrunkedControlChannelQuality(
            quality(new DecodeConfigP25Phase2())));
        assertTrue(ReceiverActivityService.isTrunkedControlChannelQuality(
            quality(dmr(DMRChannelMode.TRUNKED))));
        assertFalse(ReceiverActivityService.isTrunkedControlChannelQuality(
            quality(dmr(DMRChannelMode.CONVENTIONAL))));
        assertTrue(ReceiverActivityService.isTrunkedControlChannelQuality(
            quality(nxdn(NXDNChannelMode.TRUNKED))));
        assertFalse(ReceiverActivityService.isTrunkedControlChannelQuality(
            quality(nxdn(NXDNChannelMode.CONVENTIONAL))));
        assertFalse(ReceiverActivityService.isTrunkedControlChannelQuality(null));

        assertTrue(ReceiverActivityService.shouldPersistControlChannelQuality(
            quality(new DecodeConfigP25Phase1()), false));
        assertTrue(ReceiverActivityService.shouldPersistControlChannelQuality(
            quality(new DecodeConfigP25Phase2()), false));
        assertFalse(ReceiverActivityService.shouldPersistControlChannelQuality(
            quality(dmr(DMRChannelMode.CONVENTIONAL)), false));
        assertFalse(ReceiverActivityService.shouldPersistControlChannelQuality(
            quality(nxdn(NXDNChannelMode.TRUNKED)), false));
        assertTrue(ReceiverActivityService.shouldPersistControlChannelQuality(
            quality(dmr(DMRChannelMode.TRUNKED)), false));
        assertFalse(ReceiverActivityService.shouldPersistControlChannelQuality(
            quality(dmr(DMRChannelMode.CONVENTIONAL)), true));
        assertTrue(ReceiverActivityService.shouldPersistControlChannelQuality(
            quality(nxdn(NXDNChannelMode.TRUNKED)), true));
        assertFalse(ReceiverActivityService.shouldPersistControlChannelQuality(
            quality(nxdn(NXDNChannelMode.CONVENTIONAL)), true));
        assertFalse(ReceiverActivityService.shouldPersistControlChannelQuality(null, true));
    }

    @Test
    void requiresEvidenceFromTheSameChannelAndDecoderConfiguration()
    {
        DecodeConfigDMR configuration = dmr(DMRChannelMode.TRUNKED);
        Channel trunked = channel(configuration);
        Channel sameGuidConventional = channel(dmr(DMRChannelMode.CONVENTIONAL));
        ReceiverActivityService.TrunkedSiteEvidence evidence =
            new ReceiverActivityService.TrunkedSiteEvidence(trunked, configuration, DecoderType.DMR);

        assertTrue(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(trunked), evidence));
        assertFalse(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(sameGuidConventional), evidence));

        trunked.setDecodeConfiguration(dmr(DMRChannelMode.TRUNKED));
        assertFalse(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(trunked), evidence));

        DecodeConfigNXDN nxdnConfiguration = nxdn(NXDNChannelMode.TRUNKED);
        Channel nxdnTrunked = channel(nxdnConfiguration);
        ReceiverActivityService.TrunkedSiteEvidence nxdnEvidence =
            new ReceiverActivityService.TrunkedSiteEvidence(
                nxdnTrunked, nxdnConfiguration, DecoderType.NXDN);
        assertTrue(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(nxdnTrunked), nxdnEvidence));
        nxdnTrunked.setDecodeConfiguration(nxdn(NXDNChannelMode.CONVENTIONAL));
        assertFalse(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(nxdnTrunked), nxdnEvidence));
    }

    private static ControlChannelQualitySnapshot quality(DecodeConfiguration configuration)
    {
        return quality(channel(configuration));
    }

    private static DecodeConfigDMR dmr(DMRChannelMode mode)
    {
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(mode);
        return configuration;
    }

    private static DecodeConfigNXDN nxdn(NXDNChannelMode mode)
    {
        DecodeConfigNXDN configuration = new DecodeConfigNXDN();
        configuration.setChannelMode(mode);
        return configuration;
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
