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
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ControlChannelSystemParameters;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
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
