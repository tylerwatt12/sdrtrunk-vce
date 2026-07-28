/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.metadata.site;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProtocolSiteMetadataPublisherTest
{
    @Test
    void publishesLatestSnapshotAfterHardInterval()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        AtomicReference<TestSnapshot> snapshot = new AtomicReference<>(new TestSnapshot("ONE", true));
        AtomicLong clock = new AtomicLong(1_000);
        List<ProtocolSiteMetadataEvent> events = new ArrayList<>();
        ProtocolSiteMetadataPublisher publisher = new ProtocolSiteMetadataPublisher(channel, snapshot::get,
            () -> true, events::add, limiter(clock));

        publisher.publish(1_000);
        snapshot.set(new TestSnapshot("TWO", true));
        clock.set(2_000);
        publisher.publish(2_000);
        clock.set(5_999);
        publisher.publish(5_999);
        assertEquals(1, events.size());

        clock.set(6_000);
        publisher.publish(6_000);
        assertEquals(2, events.size());
        assertEquals("TWO", events.get(1).snapshot().decoder());
    }

    @Test
    void ignoresUselessTrafficAndDisconnectedSnapshots()
    {
        List<ProtocolSiteMetadataEvent> events = new ArrayList<>();
        AtomicReference<TestSnapshot> snapshot = new AtomicReference<>(new TestSnapshot("EMPTY", false));
        AtomicLong clock = new AtomicLong(1_000);
        Channel standard = new Channel("control", Channel.ChannelType.STANDARD);
        ProtocolSiteMetadataPublisher publisher = new ProtocolSiteMetadataPublisher(standard, snapshot::get,
            () -> true, events::add, limiter(clock));

        publisher.publish(1_000);
        assertEquals(0, events.size());

        snapshot.set(new TestSnapshot("DMR", true));
        ProtocolSiteMetadataPublisher disconnected = new ProtocolSiteMetadataPublisher(standard, snapshot::get,
            () -> false, events::add, limiter(clock));
        disconnected.publish(1_000);

        Channel traffic = new Channel("traffic", Channel.ChannelType.TRAFFIC);
        ProtocolSiteMetadataPublisher trafficPublisher = new ProtocolSiteMetadataPublisher(traffic, snapshot::get,
            () -> true, events::add, limiter(clock));
        trafficPublisher.publish(1_000);
        assertEquals(0, events.size());
    }

    @Test
    void twoPublishersShareOneHardLimit()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        AtomicLong clock = new AtomicLong(1_000);
        List<ProtocolSiteMetadataEvent> events = new ArrayList<>();
        SiteMetadataPublicationRateLimiter limiter = limiter(clock);
        ProtocolSiteMetadataPublisher first = new ProtocolSiteMetadataPublisher(channel,
            () -> new TestSnapshot("FIRST", true), () -> true, events::add, limiter);
        ProtocolSiteMetadataPublisher second = new ProtocolSiteMetadataPublisher(channel,
            () -> new TestSnapshot("SECOND", true), () -> true, events::add, limiter);

        first.publish(1_000);
        second.publish(1_001);
        assertEquals(1, events.size());

        clock.set(6_000);
        second.publish(6_000);
        assertEquals(2, events.size());
        assertEquals("SECOND", events.get(1).snapshot().decoder());
    }

    @Test
    void freshnessAndStructuralChangesWaitForTheSameInterval()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        AtomicLong clock = new AtomicLong(1_000);
        AtomicReference<DMRNetworkConfigurationSnapshot> snapshot =
            new AtomicReference<>(dmrSnapshot(1_000L, DMRNetworkConfigurationSnapshot.ChannelRole.OBSERVED));
        List<ProtocolSiteMetadataEvent> events = new ArrayList<>();
        ProtocolSiteMetadataPublisher publisher = new ProtocolSiteMetadataPublisher(channel, snapshot::get,
            () -> true, events::add, limiter(clock));

        publisher.publish(1_000L);
        snapshot.set(dmrSnapshot(2_000L, DMRNetworkConfigurationSnapshot.ChannelRole.OBSERVED));
        clock.set(2_000);
        publisher.publish(2_000L);
        assertEquals(1, events.size());

        snapshot.set(dmrSnapshot(3_000L, DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC));
        clock.set(3_000);
        publisher.publish(3_000L);
        assertEquals(1, events.size());

        clock.set(6_000);
        publisher.publish(6_000L);
        assertEquals(2, events.size());
        assertEquals(DMRNetworkConfigurationSnapshot.ChannelRole.TRAFFIC,
            ((DMRNetworkConfigurationSnapshot)events.get(1).snapshot()).channels().getFirst().role());
    }

    private static DMRNetworkConfigurationSnapshot dmrSnapshot(long observedAt,
                                                                DMRNetworkConfigurationSnapshot.ChannelRole role)
    {
        return new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 1, 2, null, null, null, null,
            null, null,
            List.of(new DMRNetworkConfigurationSnapshot.Channel("DMRChannel", 3, 1, null, null, role,
                DMRNetworkConfigurationSnapshot.FrequencySource.UNRESOLVED, observedAt)),
            List.of());
    }

    private static SiteMetadataPublicationRateLimiter limiter(AtomicLong clockMilliseconds)
    {
        return new SiteMetadataPublicationRateLimiter(5_000,
            () -> TimeUnit.MILLISECONDS.toNanos(clockMilliseconds.get()));
    }

    private record TestSnapshot(String decoder, boolean useful) implements SiteMetadataSnapshot
    {
        @Override
        public Protocol protocol()
        {
            return Protocol.DMR;
        }

        @Override
        public boolean isUseful()
        {
            return useful;
        }
    }
}
