/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataPublisher;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataSnapshotRequest;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.module.decode.dmr.DMRNetworkConfigurationMonitor;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ControlChannelSystemParameters;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class SiteIdentityTest
{
    /**
     * A Tier III control channel's system parameters: network 257, site 5.
     */
    private static ControlChannelSystemParameters tier3()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(32);
        bits.load(0, 4, 2);
        bits.load(6, 9, 257);
        bits.load(15, 3, 5);
        return new ControlChannelSystemParameters(bits, 1_000, 1);
    }

    @Test
    void a_dmr_site_says_its_network_and_site_when_the_observer_worker_resolves_its_snapshot()
    {
        DMRNetworkConfigurationMonitor monitor = new DMRNetworkConfigurationMonitor();
        monitor.process(tier3());

        Channel channel = new Channel("Tier III control");
        List<ProtocolSiteMetadataSnapshotRequest> requests = new ArrayList<>();
        new ProtocolSiteMetadataPublisher(channel, monitor::getSnapshot, () -> true, requests::add).publish(1_000);
        assertEquals(Map.of(), HealthRegistry.getInstance().channel(channel).totals(1_000).identity(),
            "nothing on the decoder's thread: the snapshot is made on the observer worker");

        requests.getFirst().resolve();
        Map<String,Integer> id = HealthRegistry.getInstance().channel(channel).totals(1_000).identity();
        assertEquals(Map.of("network", 257, "site", 5), id);
    }

    @Test
    void a_traffic_channel_gives_no_identity()
    {
        DMRNetworkConfigurationMonitor monitor = new DMRNetworkConfigurationMonitor();
        monitor.process(tier3());
        Channel traffic = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
        ProtocolSiteMetadataSnapshotRequest request = new ProtocolSiteMetadataSnapshotRequest(traffic,
            SiteReceiverContext.capture(traffic, null, 0), monitor::getSnapshot, 1_000);
        assertNotNull(request.resolve(), "its snapshot is published as usual");
        assertEquals(Map.of(), HealthRegistry.getInstance().channel(traffic).totals(1_000).identity(),
            "its site's control channel gives the identity");
    }

    @Test
    void an_nxdn_site_says_its_system_site_and_ran()
    {
        ChannelHealth health = new ChannelHealth(ChannelHealthTest.info(301, "nxdn"));
        SiteIdentity.give(health, nxdn(17, new NXDNNetworkConfigurationSnapshot.Location("GLOBAL", 410, 3, null),
            null));
        assertEquals(Map.of("system", 410, "site", 3, "ran", 17), health.totals(0).identity());
    }

    @Test
    void a_type_d_nxdn_site_says_its_site_on_its_own()
    {
        ChannelHealth health = new ChannelHealth(ChannelHealthTest.info(302, "nxdn"));
        SiteIdentity.give(health, nxdn(5, null, 12));
        assertEquals(Map.of("site", 12, "ran", 5), health.totals(0).identity());
    }

    private static NXDNNetworkConfigurationSnapshot nxdn(Integer ran, NXDNNetworkConfigurationSnapshot.Location location,
                                                         Integer typeDSite)
    {
        return new NXDNNetworkConfigurationSnapshot("NXDN", "TYPE_C", ran, location, typeDSite, null, null, null,
            List.of(), List.of(), null, List.of(), List.of(), null, null, List.of());
    }
}
