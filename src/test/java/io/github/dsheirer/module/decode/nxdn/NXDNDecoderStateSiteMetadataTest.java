/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataPublicationRateLimiter;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.RepeaterFree;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.RepeaterIdle;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.SiteID;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class NXDNDecoderStateSiteMetadataTest
{
    @Test
    void forwardsTypeDSiteAndRepeaterMessagesToMetadataMonitor()
    {
        Channel channel = new Channel("type-d", Channel.ChannelType.STANDARD);
        AtomicLong clock = new AtomicLong();
        NXDNDecoderState decoderState = new NXDNDecoderState(channel, null,
            new SiteMetadataPublicationRateLimiter(5_000, clock::get));
        EventBus eventBus = new EventBus();
        EventCollector collector = new EventCollector();
        eventBus.register(collector);
        decoderState.setInterModuleEventBus(eventBus);

        CorrectedBinaryMessage siteBits = new CorrectedBinaryMessage(32);
        siteBits.load(8, 5, 7);
        decoderState.receive(new SiteID(siteBits, 1_000,
            NXDNMessageType.TYPE_D_SCCH_OUT_INFO_4_SITE_ID, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE));

        assertNotNull(collector.event);
        NXDNNetworkConfigurationSnapshot siteSnapshot =
            (NXDNNetworkConfigurationSnapshot)collector.event.snapshot();
        assertEquals(7, siteSnapshot.typeDSite());

        CorrectedBinaryMessage repeaterBits = new CorrectedBinaryMessage(32);
        repeaterBits.load(3, 5, 9);
        repeaterBits.load(8, 5, 14);
        clock.set(TimeUnit.SECONDS.toNanos(5));
        decoderState.receive(new RepeaterIdle(repeaterBits, 1_001,
            NXDNMessageType.TYPE_D_SCCH_OUT_INFO_4_REPEATER_IDLE, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE));

        NXDNNetworkConfigurationSnapshot repeaterSnapshot =
            (NXDNNetworkConfigurationSnapshot)collector.event.snapshot();
        assertEquals(9, repeaterSnapshot.currentRepeater());
        assertEquals(java.util.List.of(14), repeaterSnapshot.observedRepeaters());

        clock.set(TimeUnit.SECONDS.toNanos(10));
        decoderState.receive(new RepeaterFree(repeaterBits, 1_002,
            NXDNMessageType.TYPE_D_SCCH_OUT_INFO_4_REPEATER_FREE, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE));

        NXDNNetworkConfigurationSnapshot freeSnapshot =
            (NXDNNetworkConfigurationSnapshot)collector.event.snapshot();
        assertNull(freeSnapshot.currentRepeater());
        assertEquals("FREE", freeSnapshot.repeaterStatus());
        assertEquals(java.util.List.of(9, 14), freeSnapshot.observedRepeaters());
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
