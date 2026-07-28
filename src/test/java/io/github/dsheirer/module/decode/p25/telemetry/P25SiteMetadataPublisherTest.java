/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataPublicationRateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class P25SiteMetadataPublisherTest
{
    @Test
    void allP25ChangesUseTheSameHardInterval()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        AtomicLong clock = new AtomicLong(1_000);
        AtomicReference<P25NetworkConfigurationSnapshot> snapshot =
            new AtomicReference<>(snapshot(1_000L, true));
        List<SiteMetadataEvent> events = new ArrayList<>();
        P25SiteMetadataPublisher publisher = new P25SiteMetadataPublisher(channel, snapshot::get, () -> true,
            events::add, limiter(clock));

        publisher.publish(1_000L);
        snapshot.set(snapshot(2_000L, false));
        clock.set(2_000);
        publisher.publish(2_000L);
        assertEquals(1, events.size());

        clock.set(6_000);
        publisher.publish(6_000L);
        assertEquals(2, events.size());
        assertFalse(events.get(1).snapshot().siteStatus().voiceService());
    }

    @Test
    void phaseTwoTimeslotsCanShareOnePublicationWindow()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        AtomicLong clock = new AtomicLong(1_000);
        SiteMetadataPublicationRateLimiter limiter = limiter(clock);
        List<SiteMetadataEvent> events = new ArrayList<>();
        P25SiteMetadataPublisher first = new P25SiteMetadataPublisher(channel,
            () -> snapshot(1_000L, true), () -> true, events::add, limiter);
        P25SiteMetadataPublisher second = new P25SiteMetadataPublisher(channel,
            () -> snapshot(1_001L, false), () -> true, events::add, limiter);

        first.publish(1_000L);
        second.publish(1_001L);
        assertEquals(1, events.size());

        clock.set(6_000);
        second.publish(6_000L);
        assertEquals(2, events.size());
        assertFalse(events.get(1).snapshot().siteStatus().voiceService());
    }

    private static SiteMetadataPublicationRateLimiter limiter(AtomicLong clockMilliseconds)
    {
        return new SiteMetadataPublicationRateLimiter(5_000,
            () -> TimeUnit.MILLISECONDS.toNanos(clockMilliseconds.get()));
    }

    private static P25NetworkConfigurationSnapshot snapshot(long broadcastClock, boolean voiceService)
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_2",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x348, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x348, 2, 1, null, true),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            new P25NetworkConfigurationSnapshot.SiteStatus(broadcastClock, Math.toIntExact(broadcastClock / 10),
                true, "REQUEST", 30, true, 0, voiceService),
            List.of());
    }
}
