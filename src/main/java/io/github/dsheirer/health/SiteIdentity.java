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

import io.github.dsheirer.metadata.site.SiteMetadataSnapshot;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;

/**
 * A DMR or NXDN site's identity, as node health reports give it (`id`), from what its control channel's network
 * configuration monitor has worked out the site says of itself: DMR network, site and color code; NXDN system, site
 * and RAN.  Services compare nodes that report the same site by it.
 */
public final class SiteIdentity
{
    private SiteIdentity()
    {
    }

    /**
     * Gives a channel's totals the identity in a site metadata snapshot.  Fields the snapshot doesn't have are left as
     * they were (a site says them at different times).
     */
    public static void give(ChannelHealth health, SiteMetadataSnapshot snapshot)
    {
        if(health == null)
        {
            return;
        }

        if(snapshot instanceof DMRNetworkConfigurationSnapshot dmr)
        {
            put(health, "network", dmr.network());
            put(health, "site", dmr.site());
            put(health, "colorCode", dmr.colorCodeTimeslot1() != null ? dmr.colorCodeTimeslot1() :
                dmr.colorCodeTimeslot2());
        }
        else if(snapshot instanceof NXDNNetworkConfigurationSnapshot nxdn)
        {
            NXDNNetworkConfigurationSnapshot.Location location = nxdn.currentLocation();

            if(location != null)
            {
                put(health, "system", location.system());
                put(health, "site", location.site());
            }

            //A Type-D site says its site number on its own.
            if(location == null || location.site() == null)
            {
                put(health, "site", nxdn.typeDSite());
            }

            put(health, "ran", nxdn.ran());
        }
    }

    private static void put(ChannelHealth health, String name, Integer value)
    {
        if(value != null)
        {
            health.identity(name, value);
        }
    }
}
