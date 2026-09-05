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
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.util.List;
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
            new ReceiverActivityService.TrunkedSiteEvidence(
                SiteReceiverContext.capture(trunked, Protocol.DMR));

        assertTrue(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(trunked), evidence));
        assertFalse(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(sameGuidConventional), evidence));

        trunked.setDecodeConfiguration(dmr(DMRChannelMode.TRUNKED));
        assertTrue(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(trunked), evidence));

        DecodeConfigNXDN nxdnConfiguration = nxdn(NXDNChannelMode.TRUNKED);
        Channel nxdnTrunked = channel(nxdnConfiguration);
        ReceiverActivityService.TrunkedSiteEvidence nxdnEvidence =
            new ReceiverActivityService.TrunkedSiteEvidence(
                SiteReceiverContext.capture(nxdnTrunked, Protocol.NXDN));
        assertTrue(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(nxdnTrunked), nxdnEvidence));

        ControlChannelQualitySnapshot beforeEdit = quality(nxdnTrunked);
        nxdnTrunked.setDecodeConfiguration(nxdn(NXDNChannelMode.CONVENTIONAL));
        assertTrue(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(beforeEdit, nxdnEvidence),
            "an already-published quality observation keeps its producer-time receiver mode");
        assertFalse(ReceiverActivityService.hasCurrentTrunkedSiteEvidence(
            quality(nxdnTrunked), nxdnEvidence));
    }

    @Test
    void qualitySurvivesLearnedAndPreferredFrequencyUpdatesButSiteMatchingRemainsStrict()
    {
        Channel channel = channel(new DecodeConfigP25Phase1());
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(851_012_500L, 852_012_500L));
        source.setPreferredFrequency(851_012_500L);
        channel.setSourceConfiguration(source);
        ControlChannelQualitySnapshot quality = quality(channel);
        SiteReceiverContext siteContext = SiteReceiverContext.capture(channel, Protocol.APCO25,
            851_012_500L);

        source.addFrequency(853_012_500L);
        source.setPreferredFrequency(852_012_500L);

        assertTrue(quality.matchesCurrentChannel(),
            "quality belongs to the same running decoder after normal control-frequency learning");
        assertFalse(siteContext.matchesCurrentChannel(channel),
            "site assignment retains the strict producer-time source ownership check");
    }

    @Test
    void qualityRejectsReusedOrReconfiguredReceiverFacts()
    {
        Channel original = channel(dmr(DMRChannelMode.TRUNKED));
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(451_012_500L);
        original.setSourceConfiguration(source);
        ControlChannelQualitySnapshot beforeEdit = quality(original);

        source.setFrequency(452_012_500L);
        assertFalse(beforeEdit.matchesCurrentChannel(), "a primary source-frequency edit starts a new generation");

        source.setFrequency(451_012_500L);
        original.setDecodeConfiguration(dmr(DMRChannelMode.CONVENTIONAL));
        assertFalse(beforeEdit.matchesCurrentChannel(), "a trunked-to-conventional mode edit is stale");

        original.setDecodeConfiguration(nxdn(NXDNChannelMode.TRUNKED));
        assertFalse(beforeEdit.matchesCurrentChannel(), "a decoder protocol replacement is stale");

        original.setDecodeConfiguration(dmr(DMRChannelMode.TRUNKED));
        SourceConfigTunerMultipleFrequency replacementSource = new SourceConfigTunerMultipleFrequency();
        replacementSource.setFrequencies(List.of(451_012_500L));
        original.setSourceConfiguration(replacementSource);
        assertFalse(beforeEdit.matchesCurrentChannel(), "a source-kind replacement is stale");

        original.setSourceConfiguration(source);
        original.setConfigurationId("00000000-0000-0000-0000-000000000222");
        assertFalse(beforeEdit.matchesCurrentChannel(), "a durable configuration replacement is stale");

        Channel replacement = channel(dmr(DMRChannelMode.TRUNKED));
        replacement.setSourceConfiguration(source);
        ControlChannelQualitySnapshot reusedToken = new ControlChannelQualitySnapshot(replacement,
            beforeEdit.receiverContext(), beforeEdit.frequencyHz(), beforeEdit.observedAtMs(), beforeEdit.active(),
            beforeEdit.signalDbfs(), beforeEdit.averageSignalDbfs(), beforeEdit.minimumSignalDbfs(),
            beforeEdit.maximumSignalDbfs(), beforeEdit.decodeHealthPercent(), beforeEdit.validFrames(),
            beforeEdit.invalidFrames(), beforeEdit.correctedBits(), beforeEdit.syncLossBits(),
            beforeEdit.droppedBits(), beforeEdit.lastValidDecodeMs());
        assertFalse(reusedToken.matchesCurrentChannel(), "a different runtime channel token is stale");
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
        channel.setConfigurationId("123e4567-e89b-12d3-a456-426614174000");
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
