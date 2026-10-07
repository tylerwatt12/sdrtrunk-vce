/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConventionalLiveSourcesTest
{
    private static final long FREQUENCY = 451_012_500L;

    @Test
    void usesAuthoritativeClassificationAndDeduplicatesDmrTimeslots() throws Exception
    {
        AliasModel aliases = new AliasModel();
        try(ChannelActivityModel model = new ChannelActivityModel(aliases, new NowPlayingPreference(type -> {})))
        {
            var sources = new ConventionalLiveSources(model);
            Channel dmr = channel("DMR Repeater", new DecodeConfigDMR());
            Channel nxdn = channel("NXDN Repeater", new DecodeConfigNXDN());
            Channel p25 = channel("P25 Repeater", new DecodeConfigP25Conventional());
            Channel am = channel("Tower", new DecodeConfigAM());
            DecodeConfigDMR trunkedDmr = new DecodeConfigDMR();
            trunkedDmr.setChannelMode(DMRChannelMode.TRUNKED);
            Channel dmrControl = channel("DMR Control", trunkedDmr);
            Channel p25Control = channel("P25 Control", new DecodeConfigP25Phase1());
            Channel traffic = channel("Traffic", new DecodeConfigNXDN(), Channel.ChannelType.TRAFFIC);
            model.channelStarted(dmr, List.of(new ChannelMetadata(aliases, 1), new ChannelMetadata(aliases, 2)));
            for(Channel channel: List.of(nxdn, p25, am, dmrControl, p25Control, traffic))
            {
                model.channelStarted(channel, List.of(new ChannelMetadata(aliases, 1)));
            }
            idle(model);

            assertEquals(4, sources.get().size());
            assertSame(sources.get(), sources.get(), "An unchanged immutable activity snapshot reuses membership");
            for(Channel channel: List.of(dmr, nxdn, p25, am))
            {
                assertTrue(sources.matches(channel.getConfigurationId(), FREQUENCY));
            }
            for(Channel channel: List.of(dmrControl, p25Control, traffic))
            {
                assertFalse(sources.matches(channel.getConfigurationId(), FREQUENCY));
            }
            assertFalse(sources.matches(dmr.getConfigurationId(), FREQUENCY + 12_500));
            assertEquals("DMR Repeater", sources.get().stream()
                .filter(source -> source.configurationId().equals(dmr.getConfigurationId()))
                .findFirst().orElseThrow().channelName());

            DecodeEventViewService.EventView neighbor = new DecodeEventViewService.EventView("event",
                dmr.getConfigurationId(), 1, 0, "CALL_GROUP", "Group Call", "VOICE", "", "", "", "",
                "Neighbor", FREQUENCY + 12_500, 1, "", "DMR", List.of(), List.of(), 1,
                "DMR Repeater", FREQUENCY);
            assertTrue(DecodeEventViewService.Scope.conventional().matches(neighbor, sources),
                "A decoded neighbor frequency does not change the source channel's membership");
            assertFalse(DecodeEventViewService.Scope.conventional().matches(neighbor),
                "Aggregate membership must be supplied explicitly; it never matches all events by default");
        }
    }

    @Test
    void followsStopRestartAndNxdnPromotionWithoutRetainingOldMembership() throws Exception
    {
        AliasModel aliases = new AliasModel();
        try(ChannelActivityModel model = new ChannelActivityModel(aliases, new NowPlayingPreference(type -> {})))
        {
            var sources = new ConventionalLiveSources(model);
            Channel nxdn = channel("NXDN", new DecodeConfigNXDN());
            ChannelMetadata metadata = new ChannelMetadata(aliases, 1);
            assertTrue(sources.get().isEmpty());
            model.channelStarted(nxdn, List.of(metadata));
            idle(model);
            assertTrue(sources.matches(nxdn.getConfigurationId(), FREQUENCY));
            model.channelStopped(nxdn);
            idle(model);
            assertTrue(sources.get().isEmpty());
            model.channelStarted(nxdn, List.of(metadata), new Object());
            idle(model);
            assertTrue(sources.matches(nxdn.getConfigurationId(), FREQUENCY));

            var trunked = new NXDNNetworkConfigurationSnapshot("NXDN", "TYPE-D", 5,
                new NXDNNetworkConfigurationSnapshot.Location("REGIONAL", 8, 9, null), null, null, null, null,
                List.of(), List.of(), null, List.of(), List.of(), null, null, List.of());
            model.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(nxdn, trunked, 1_000));
            idle(model);
            assertTrue(sources.get().isEmpty(), "Membership follows the activity model's learned trunked classification");
        }
    }

    private static Channel channel(String name, DecodeConfiguration decoder)
    {
        return channel(name, decoder, Channel.ChannelType.STANDARD);
    }

    private static Channel channel(String name, DecodeConfiguration decoder, Channel.ChannelType type)
    {
        Channel channel = new Channel(name, type);
        channel.setConfigurationId(UUID.randomUUID().toString());
        channel.setDecodeConfiguration(decoder);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(FREQUENCY);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static void idle(ChannelActivityModel model)
    {
        assertTrue(model.awaitIdle(5, TimeUnit.SECONDS), "Activity worker did not become idle");
    }
}
