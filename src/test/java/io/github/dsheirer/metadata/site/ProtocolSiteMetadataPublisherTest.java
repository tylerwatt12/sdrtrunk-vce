/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.metadata.site;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProtocolSiteMetadataPublisherTest
{
    @Test
    void publishesOnChangeAndAfterInterval()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        AtomicReference<TestSnapshot> snapshot = new AtomicReference<>(new TestSnapshot("ONE", true));
        List<ProtocolSiteMetadataEvent> events = new ArrayList<>();
        ProtocolSiteMetadataPublisher publisher = new ProtocolSiteMetadataPublisher(channel, snapshot::get,
            () -> true, events::add, 5_000);

        publisher.publish(1_000);
        publisher.publish(2_000);
        publisher.publish(5_999);
        assertEquals(1, events.size());

        publisher.publish(6_000);
        assertEquals(2, events.size());

        snapshot.set(new TestSnapshot("TWO", true));
        publisher.publish(6_001);
        assertEquals(3, events.size());
        assertEquals("TWO", events.get(2).snapshot().decoder());
    }

    @Test
    void ignoresUselessTrafficAndDisconnectedSnapshots()
    {
        List<ProtocolSiteMetadataEvent> events = new ArrayList<>();
        AtomicReference<TestSnapshot> snapshot = new AtomicReference<>(new TestSnapshot("EMPTY", false));
        Channel standard = new Channel("control", Channel.ChannelType.STANDARD);
        ProtocolSiteMetadataPublisher publisher = new ProtocolSiteMetadataPublisher(standard, snapshot::get,
            () -> true, events::add, 5_000);

        publisher.publish(1_000);
        assertEquals(0, events.size());

        snapshot.set(new TestSnapshot("DMR", true));
        ProtocolSiteMetadataPublisher disconnected = new ProtocolSiteMetadataPublisher(standard, snapshot::get,
            () -> false, events::add, 5_000);
        disconnected.publish(1_000);

        Channel traffic = new Channel("traffic", Channel.ChannelType.TRAFFIC);
        ProtocolSiteMetadataPublisher trafficPublisher = new ProtocolSiteMetadataPublisher(traffic, snapshot::get,
            () -> true, events::add, 5_000);
        trafficPublisher.publish(1_000);
        assertEquals(0, events.size());
    }

    @Test
    void resetAllowsImmediateRepublish()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        List<ProtocolSiteMetadataEvent> events = new ArrayList<>();
        ProtocolSiteMetadataPublisher publisher = new ProtocolSiteMetadataPublisher(channel,
            () -> new TestSnapshot("DMR", true), () -> true, events::add, 5_000);

        publisher.publish(1_000);
        publisher.reset();
        publisher.publish(1_001);

        assertEquals(2, events.size());
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
