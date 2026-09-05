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
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.DigitalStationIDInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.SiteInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.RepeaterFree;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.RepeaterIdle;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.SiteID;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class NXDNDecoderStateSiteMetadataTest
{
    @Test
    void nativeIdentityIsGenerationBoundAndIncompleteEvidenceFailsClosed()
    {
        Channel channel = new Channel("Type-C", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN configuration = new DecodeConfigNXDN();
        configuration.setChannelMode(NXDNChannelMode.TRUNKED);
        channel.setDecodeConfiguration(configuration);
        NXDNTrafficChannelManager oldManager = new NXDNTrafficChannelManager(channel);
        NXDNDecoderState.updateNativeRadioSystemKey(oldManager,
            snapshot("TYPE_C", new NXDNNetworkConfigurationSnapshot.Location("LOCAL", 302, 1, null)));
        assertEquals("nxdn-c:local:302", oldManager.getNativeRadioSystemKey());

        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(channel);
        assertNull(manager.getNativeRadioSystemKey(),
            "a new processing-chain manager must not inherit the old chain's learned identity");
        NXDNDecoderState.updateNativeRadioSystemKey(oldManager,
            snapshot("TYPE_C", new NXDNNetworkConfigurationSnapshot.Location("LOCAL", 304, 1, null)));
        assertNull(manager.getNativeRadioSystemKey(),
            "a late callback changes only the unreachable old manager");

        NXDNDecoderState.updateNativeRadioSystemKey(manager,
            snapshot("TYPE_C", new NXDNNetworkConfigurationSnapshot.Location("LOCAL", 303, 1, null)));
        assertEquals("nxdn-c:local:303", manager.getNativeRadioSystemKey());

        NXDNDecoderState.updateNativeRadioSystemKey(manager,
            snapshot("TYPE_C", new NXDNNetworkConfigurationSnapshot.Location("LOCAL", null, 1, null)));
        assertNull(manager.getNativeRadioSystemKey(),
            "an incomplete supported-native tuple cannot retain an older generation's key");

        NXDNDecoderState.updateNativeRadioSystemKey(manager,
            snapshot("TYPE_C", new NXDNNetworkConfigurationSnapshot.Location("LOCAL", 305, 1, null)));
        manager.reset();
        assertNull(manager.getNativeRadioSystemKey(), "reset must clear the chain-local learned identity");

        NXDNDecoderState.updateNativeRadioSystemKey(manager,
            snapshot("TYPE_C", new NXDNNetworkConfigurationSnapshot.Location("LOCAL", 306, 1, null)));
        manager.stop();
        assertNull(manager.getNativeRadioSystemKey(), "stop must clear the chain-local learned identity");

        NXDNDecoderState.updateNativeRadioSystemKey(manager, snapshot("TYPE_D", null));
        assertNull(manager.getNativeRadioSystemKey());

        Channel typeDChannel = new Channel("Type-D", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN typeDConfiguration = new DecodeConfigNXDN();
        typeDConfiguration.setChannelMode(NXDNChannelMode.TRUNKED);
        typeDConfiguration.setTransmissionMode(TransmissionMode.TYPE_D);
        typeDChannel.setDecodeConfiguration(typeDConfiguration);
        NXDNTrafficChannelManager typeDManager = new NXDNTrafficChannelManager(typeDChannel);
        NXDNDecoderState.updateNativeRadioSystemKey(typeDManager,
            snapshot("TYPE_C", new NXDNNetworkConfigurationSnapshot.Location("LOCAL", 303, 1, null)));
        assertNull(typeDManager.getNativeRadioSystemKey(),
            "a Type-D saved channel must not accept Type-C native identity evidence");
    }

    @Test
    void changedRanOrdinaryMessageClearsThePreviousNativeIdentity()
    {
        Channel channel = new Channel("Type-C", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN configuration = new DecodeConfigNXDN();
        configuration.setChannelMode(NXDNChannelMode.TRUNKED);
        channel.setDecodeConfiguration(configuration);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(channel);
        NXDNDecoderState decoderState = new NXDNDecoderState(channel, manager,
            new SiteMetadataPublicationRateLimiter(0));
        decoderState.setInterModuleEventBus(new EventBus());
        CorrectedBinaryMessage siteBits = new CorrectedBinaryMessage(176);
        siteBits.load(10, 10, 341);
        siteBits.load(20, 12, 837);

        decoderState.receive(new SiteInformation(siteBits, 1_000,
            NXDNMessageType.CONTROL_OUT_24_BC_SITE_INFORMATION, 12,
            LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));
        assertEquals("nxdn-c:global:341", manager.getNativeRadioSystemKey());

        decoderState.receive(new DigitalStationIDInformation(new CorrectedBinaryMessage(176), 2_000,
            NXDNMessageType.CONTROL_OUT_23_BC_DIGITAL_STATION_ID_INFORMATION, 13,
            LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL));

        assertNull(manager.getNativeRadioSystemKey(),
            "a new RAN without a complete location must fail closed before the next call starts");
    }

    @Test
    void conventionalModeDoesNotPromoteRuntimeMessagesToSiteMetadata()
    {
        Channel channel = new Channel("conventional", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN configuration = new DecodeConfigNXDN();
        configuration.setChannelMode(NXDNChannelMode.CONVENTIONAL);
        channel.setDecodeConfiguration(configuration);
        NXDNDecoderState decoderState = new NXDNDecoderState(channel, null,
            new SiteMetadataPublicationRateLimiter(0));
        EventBus eventBus = new EventBus();
        EventCollector collector = new EventCollector();
        eventBus.register(collector);
        decoderState.setInterModuleEventBus(eventBus);
        CorrectedBinaryMessage siteBits = new CorrectedBinaryMessage(32);
        siteBits.load(8, 5, 7);

        decoderState.receive(new SiteID(siteBits, 1_000,
            NXDNMessageType.TYPE_D_SCCH_OUT_INFO_4_SITE_ID, 0,
            LICH.RTCH_2_OUTBOUND_SUPER_VOICE_VOICE));

        assertNull(collector.event);
    }

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

    private static NXDNNetworkConfigurationSnapshot snapshot(String variant,
                                                               NXDNNetworkConfigurationSnapshot.Location location)
    {
        return new NXDNNetworkConfigurationSnapshot("NXDN", variant, 5, location,
            "TYPE_D".equals(variant) ? 7 : null, null, null, null, List.of(), List.of(), null,
            List.of(), List.of(), null, null, List.of());
    }
}
