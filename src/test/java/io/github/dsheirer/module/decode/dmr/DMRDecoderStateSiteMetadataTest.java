/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.TalkgroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ControlChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import org.junit.jupiter.api.Test;

class DMRDecoderStateSiteMetadataTest
{
    @Test
    void publishesStructuredMetadataFromStandardParent()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        DMRDecoderState decoderState = new DMRDecoderState(channel, 1, null);
        EventBus eventBus = new EventBus();
        EventCollector collector = new EventCollector();
        eventBus.register(collector);
        decoderState.setInterModuleEventBus(eventBus);
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(32);
        bits.load(0, 4, 2);
        bits.load(6, 9, 257);
        bits.load(15, 3, 5);

        decoderState.receive(new ControlChannelSystemParameters(bits, 1_000, 1));

        assertNotNull(collector.event);
        DMRNetworkConfigurationSnapshot snapshot =
            (DMRNetworkConfigurationSnapshot)collector.event.snapshot();
        assertEquals(257, snapshot.network());
        assertEquals(5, snapshot.site());
        assertEquals("TIER_III", snapshot.variant());
    }

    @Test
    void publishesGrantFrequencyResolvedFromDecoderConfiguration()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(802);
        mapping.setDownlinkFrequency(139_518_750L);
        mapping.setUplinkFrequency(149_518_750L);
        config.addTimeslotFrequency(mapping);
        channel.setDecodeConfiguration(config);
        DMRDecoderState decoderState = new DMRDecoderState(channel, 1, null);
        EventBus eventBus = new EventBus();
        EventCollector collector = new EventCollector();
        eventBus.register(collector);
        decoderState.setInterModuleEventBus(eventBus);
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(80);
        bits.load(16, 12, 802);
        bits.set(28);
        CorrectedBinaryMessage slotBits = new CorrectedBinaryMessage(24);
        slotBits.load(8, 4, 3);
        TalkgroupVoiceChannelGrant grant = new TalkgroupVoiceChannelGrant(DMRSyncPattern.BASE_STATION_DATA,
            bits, null, new SlotType(slotBits), 1_000L, 2);

        decoderState.receive(grant);

        assertNotNull(collector.event);
        DMRNetworkConfigurationSnapshot snapshot =
            (DMRNetworkConfigurationSnapshot)collector.event.snapshot();
        assertEquals(1, snapshot.channels().size());
        assertEquals(802, snapshot.channels().getFirst().logicalChannelNumber());
        assertEquals(2, snapshot.channels().getFirst().timeslot());
        assertEquals(139_518_750L, snapshot.channels().getFirst().downlink());
        assertEquals(149_518_750L, snapshot.channels().getFirst().uplink());
        assertEquals(DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
            snapshot.channels().getFirst().role());
        assertEquals(DMRNetworkConfigurationSnapshot.FrequencySource.CONFIGURED_MAP,
            snapshot.channels().getFirst().frequencySource());
    }

    private static class EventCollector
    {
        private ProtocolSiteMetadataEvent event;

        @Subscribe
        public void receive(ProtocolSiteMetadataEvent metadataEvent)
        {
            event = metadataEvent;
        }
    }
}
