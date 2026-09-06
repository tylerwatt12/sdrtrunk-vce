/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.gui.configuration.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.rrapi.type.County;
import io.github.dsheirer.rrapi.type.Site;
import io.github.dsheirer.rrapi.type.System;
import io.github.dsheirer.rrapi.type.SystemInformation;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemEditorTest
{
    @Test
    void sortsSystemsByNewestRadioReferenceUpdateFirst()
    {
        System oldest = system(1, "Old System", "2026-01-01T12:00:00Z");
        System newest = system(2, "New System", "2026-07-31T12:00:00Z");
        System middle = system(3, "Middle System", "2026-04-01T12:00:00Z");

        assertEquals(List.of(newest, middle, oldest),
            SystemEditor.sortedSystems(List.of(oldest, newest, middle)));
    }

    @Test
    void sortsEqualAndMissingDatesDeterministically()
    {
        System zulu = system(1, "Zulu", "2026-07-31T12:00:00Z");
        System alpha = system(2, "alpha", "2026-07-31T12:00:00Z");
        System unknown = system(3, "Unknown Date", null);

        assertEquals(List.of(alpha, zulu, unknown),
            SystemEditor.sortedSystems(List.of(unknown, zulu, alpha)));
    }

    @Test
    void joinsSiteCountyNamesFromAlreadyLoadedSystemInformation()
    {
        County first = county(101, "Alpha County");
        County second = county(202, "Bravo County");
        SystemInformation systemInformation = new SystemInformation();
        systemInformation.setCounties(List.of(first, second));

        Map<Integer,String> countyNames = SystemEditor.countyNames(systemInformation);
        List<EnrichedSite> sites = SystemEditor.enrich(List.of(site(1, 202), site(2, 101)), countyNames);

        assertEquals(Map.of(101, "Alpha County", 202, "Bravo County"), countyNames);
        assertEquals("Bravo County", sites.get(0).getCountyName());
        assertEquals("Alpha County", sites.get(1).getCountyName());
    }

    @Test
    void leavesUnknownAndSentinelSiteCountiesBlank()
    {
        SystemInformation systemInformation = new SystemInformation();
        systemInformation.setCounties(List.of(county(101, "Known County")));

        List<EnrichedSite> sites = SystemEditor.enrich(List.of(site(1, 0), site(2, 99999)),
            SystemEditor.countyNames(systemInformation));

        assertNull(sites.get(0).getCountyName());
        assertNull(sites.get(1).getCountyName());
    }

    @Test
    void requestsFallbackOnlyForDistinctCountyIdsMissingFromSystemInformation()
    {
        List<Site> sites = List.of(site(1, 101), site(2, 202), site(3, 202), site(4, 0), site(5, 99999));

        assertEquals(List.of(202), SystemEditor.unresolvedCountyIds(sites, Map.of(101, "Known County")));
    }

    private static County county(int id, String name)
    {
        County county = new County();
        county.setCountyId(id);
        county.setName(name);
        return county;
    }

    private static Site site(int id, int countyId)
    {
        Site site = new Site();
        site.setSiteId(id);
        site.setCountyId(countyId);
        return site;
    }

    private static System system(int id, String name, String lastUpdated)
    {
        System system = new System();
        system.setSystemId(id);
        system.setName(name);
        system.setLastUpdated(lastUpdated != null ? Date.from(Instant.parse(lastUpdated)) : null);
        return system;
    }
}
