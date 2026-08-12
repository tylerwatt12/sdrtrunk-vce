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

package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.util.Locale;

/**
 * Complete P25 site identity used to bind automatic control-channel learning to one site.
 */
public record P25SiteIdentity(int wacn, int system, int rfss, int site)
{
    private static final int MAXIMUM_WACN = 0xFFFFF;
    private static final int MAXIMUM_SYSTEM = 0xFFF;
    private static final int MAXIMUM_RFSS = 0xFF;
    private static final int MAXIMUM_SITE = 0xFF;

    public P25SiteIdentity
    {
        requireRange("WACN", wacn, MAXIMUM_WACN);
        requireRange("System", system, MAXIMUM_SYSTEM);
        requireRange("RFSS", rfss, MAXIMUM_RFSS);
        requireRange("Site", site, MAXIMUM_SITE);
    }

    /**
     * Extracts a complete, internally consistent identity from a stabilized network snapshot.
     */
    public static P25SiteIdentity from(P25NetworkConfigurationSnapshot snapshot)
    {
        if(snapshot == null || snapshot.network() == null || snapshot.currentSite() == null)
        {
            return null;
        }

        P25NetworkConfigurationSnapshot.Network network = snapshot.network();
        P25NetworkConfigurationSnapshot.CurrentSite currentSite = snapshot.currentSite();

        if(network.wacn() == null || network.system() == null || currentSite.system() == null ||
            currentSite.rfss() == null || currentSite.site() == null ||
            !network.system().equals(currentSite.system()) ||
            network.nac() != null && currentSite.nac() != null && !network.nac().equals(currentSite.nac()))
        {
            return null;
        }

        try
        {
            return new P25SiteIdentity(network.wacn(), network.system(), currentSite.rfss(), currentSite.site());
        }
        catch(IllegalArgumentException _)
        {
            return null;
        }
    }

    /**
     * Compact operator-facing representation: WACN-System / RFSS-Site.
     */
    public String display()
    {
        return String.format(Locale.ROOT, "%05X-%03X / %02X-%02X", wacn, system, rfss, site);
    }

    private static void requireRange(String label, int value, int maximum)
    {
        if(value < 0 || value > maximum)
        {
            throw new IllegalArgumentException(label + " is outside its P25 field range: " + value);
        }
    }
}
