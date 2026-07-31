/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatsWebServerServiceBindAddressTest
{
    @Test
    void selectsLoopbackOrWildcardBinding()
    {
        InetSocketAddress localOnly = StatsWebServerService.createBindAddress(8090, false);
        InetSocketAddress anyIp = StatsWebServerService.createBindAddress(8090, true);

        assertTrue(localOnly.getAddress().isLoopbackAddress());
        assertTrue(anyIp.getAddress().isAnyLocalAddress());
        assertEquals(8090, localOnly.getPort());
        assertEquals(8090, anyIp.getPort());
    }

    @Test
    void acceptsOnlyRegisteredFixedPaths()
    {
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/api/system?scope=p25%3ABEE00%3A348"), "/api/system"));
        assertFalse(StatsWebServerService.hasExactPath(URI.create("/api/systems"), "/api/system"));
        assertFalse(StatsWebServerService.hasExactPath(URI.create("/api/system/legacy"), "/api/system"));
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/live/activity?scope=dmr%3Aguid%3Atest"), "/live/activity"));
        assertFalse(StatsWebServerService.hasExactPath(URI.create("/live/systems-old"), "/live/systems"));
        assertFalse(StatsWebServerService.hasExactPath(URI.create("/live/sites/legacy"), "/live/sites"));
        assertFalse(StatsWebServerService.hasExactPath(URI.create("/live/web-calls/legacy"), "/live/web-calls"));
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/api/export.csv?dataset=system-talkgroups"), "/api/export.csv"));
        assertFalse(StatsWebServerService.hasExactPath(
            URI.create("/api/export.csv/legacy"), "/api/export.csv"));
    }

    @Test
    void appliesDownloadAndSecurityHeadersToCsvResponses()
    {
        Headers headers = new Headers();
        StatsWebServerService.applyCsvHeaders(headers, "sdrtrunk-system-radios-test.csv");
        assertEquals("text/csv; charset=utf-8", headers.getFirst("Content-Type"));
        assertEquals("attachment; filename=\"sdrtrunk-system-radios-test.csv\"",
            headers.getFirst("Content-Disposition"));
        assertEquals("no-store", headers.getFirst("Cache-Control"));
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
    }

    @Test
    void streamsPatchEventsToMemberTalkgroupsOnly()
    {
        Map<String,Object> patchEvent = Map.of(
            "scope_token", "p25:BEE00:348",
            "target_id", 60000L,
            "target_kind_code", 3L,
            "member_talkgroup_ids", List.of(56133L, 56134L));

        assertTrue(StatsWebServerService.matchesActivity(patchEvent, StatsRequest.from(
            URI.create("/live/activity?scope=p25:BEE00:348&talkgroup_id=56133"))));
        assertFalse(StatsWebServerService.matchesActivity(patchEvent, StatsRequest.from(
            URI.create("/live/activity?scope=p25:BEE00:348&talkgroup_id=56135"))));
        assertTrue(StatsWebServerService.matchesActivity(patchEvent, StatsRequest.from(
            URI.create("/live/activity?scope=p25:BEE00:348&talkgroup_id=60000&kind=patch"))));
        assertFalse(StatsWebServerService.matchesActivity(patchEvent, StatsRequest.from(
            URI.create("/live/activity?scope=p25:BEE00:348&talkgroup_id=56133&kind=patch"))));
    }
}
