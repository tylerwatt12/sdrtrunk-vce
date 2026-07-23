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

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StatsLiveServiceTest
{
    @Test
    void createsSerializableProtocolNeutralLiveSite()
        throws Exception
    {
        Channel channel = new Channel("Control");
        channel.setSystem("Metro");
        channel.setSite("Downtown");
        channel.setAliasListName("County");
        channel.setRadresGuid("00000000-0000-0000-0000-000000000456");
        DMRNetworkConfigurationSnapshot snapshot = new DMRNetworkConfigurationSnapshot(
            "DMR", "TIER_III", 10, 20, "Tier III Trunking", "SMALL", null, "Control",
            1, 2, List.of(new DMRNetworkConfigurationSnapshot.Channel(
                "DMRChannel", 42, 1, 451_000_000L, 456_000_000L)), List.of());
        Map<String,Object> site = StatsLiveService.protocolSite(
            new ProtocolSiteMetadataEvent(channel, snapshot, 1_000L),
            Map.of("signal_dbfs", -25.5, "decode_health_pct", 98.0));

        assertEquals("DMR", site.get("protocol"));
        assertEquals(3, site.get("protocol_code"));
        assertEquals("TIER_III", site.get("variant"));
        assertEquals(-25.5, site.get("signal_dbfs"));
        String json = new ObjectMapper().writeValueAsString(site);
        assertTrue(json.contains("\"network\":10"));
        assertTrue(json.contains("\"logicalChannelNumber\":42"));
    }

    @Test
    void clearsAndExpiresQualityAndEvictsStaleSites() throws Exception
    {
        AtomicLong clock = new AtomicLong(1_000L);
        StatsLiveService service = new StatsLiveService(null, null, clock::get);
        Channel channel = channel();
        ProtocolSiteMetadataEvent metadata = new ProtocolSiteMetadataEvent(channel, dmrSnapshot(), 1_000L);
        service.process(metadata);
        service.process(activity(channel.getRadresGuid(), true));
        assertEquals(-25.5, liveSite(service).get("signal_dbfs"));

        service.process(activity(channel.getRadresGuid(), false));
        assertFalse(liveSite(service).containsKey("signal_dbfs"));

        service.process(activity(channel.getRadresGuid(), true));
        clock.set(40_000L);
        service.process(activity(channel.getRadresGuid(), true));
        clock.set(46_001L);
        service.process(new ProtocolSiteMetadataEvent(channel, dmrSnapshot(), clock.get()));
        service.sweepExpired();
        assertFalse(liveSite(service).containsKey("signal_dbfs"));

        try(StatsLiveEventHub.Subscription subscription = service.subscribeSites())
        {
            clock.set(76_002L);
            service.sweepExpired();
            assertTrue(sites(service).isEmpty());
            StatsLiveEventHub.LiveEvent removed = subscription.poll(1, TimeUnit.SECONDS);
            assertNotNull(removed);
            assertEquals("site_removed", removed.name());
            assertEquals(channel.getRadresGuid(), ((Map<?,?>)removed.data()).get("guid"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void snapshotDoesNotExpireSitesOutsideTheSerializedEventPipeline() throws Exception
    {
        AtomicLong clock = new AtomicLong(1_000L);
        StatsLiveService service = new StatsLiveService(null, null, clock::get);
        Channel channel = channel();
        service.process(new ProtocolSiteMetadataEvent(channel, dmrSnapshot(), clock.get()));

        try(StatsLiveEventHub.Subscription subscription = service.subscribeSites())
        {
            clock.set(StatsLiveService.SITE_METADATA_LIVE_MILLISECONDS + 1_001L);
            assertEquals(1, sites(service).size());
            assertNull(subscription.poll(50, TimeUnit.MILLISECONDS));

            service.process(new ProtocolSiteMetadataEvent(channel, dmrSnapshot(), clock.get()));
            assertEquals("site_metadata", subscription.poll(1, TimeUnit.SECONDS).name());
            service.sweepExpired();
            assertEquals(1, sites(service).size());
            assertNull(subscription.poll(50, TimeUnit.MILLISECONDS));
        }
        finally
        {
            service.close();
        }
    }

    private static Channel channel()
    {
        Channel channel = new Channel("Control");
        channel.setSystem("Metro");
        channel.setSite("Downtown");
        channel.setAliasListName("County");
        channel.setRadresGuid("00000000-0000-0000-0000-000000000456");
        return channel;
    }

    private static DMRNetworkConfigurationSnapshot dmrSnapshot()
    {
        return new DMRNetworkConfigurationSnapshot(
            "DMR", "TIER_III", 10, 20, "Tier III Trunking", "SMALL", null, "Control",
            1, 2, List.of(new DMRNetworkConfigurationSnapshot.Channel(
            "DMRChannel", 42, 1, 451_000_000L, 456_000_000L)), List.of());
    }

    private static ChannelActivityEvent activity(String guid, boolean withQuality)
    {
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("control", null, "CONTROL",
            List.of("CONTROL"), "42", 451_000_000L, null, withQuality ? -25.5 : null,
            withQuality ? 98.0 : null, withQuality ? 1_000L : 0L, null, null, null, null, null, null, null,
            "DMR", null);
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("channel-1", "DMR", "Control", guid,
            false, true, List.of(row));
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> sites(StatsLiveService service)
    {
        return (List<Map<String,Object>>)service.siteSnapshot().get("sites");
    }

    private static Map<String,Object> liveSite(StatsLiveService service)
    {
        return sites(service).getFirst();
    }
}
