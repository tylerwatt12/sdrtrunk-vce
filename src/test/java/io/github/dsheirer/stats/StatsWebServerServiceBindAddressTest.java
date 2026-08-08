/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.web.auth.WebCapability;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatsWebServerServiceBindAddressTest
{
    @Test
    void selectsLoopbackOrWildcardBinding()
    {
        InetSocketAddress localOnly = StatsWebServerService.createBindAddress(8090, false);
        InetSocketAddress anyIp = StatsWebServerService.createBindAddress(8090, true);

        assertTrue(localOnly.getAddress().isLoopbackAddress());
        assertEquals("127.0.0.1", localOnly.getAddress().getHostAddress());
        assertTrue(anyIp.getAddress().isAnyLocalAddress());
        assertEquals("0.0.0.0", anyIp.getAddress().getHostAddress());
        assertEquals(8090, localOnly.getPort());
        assertEquals(8090, anyIp.getPort());
    }

    @Test
    void navigationStateUsesConfiguredTransportScheme()
    {
        StatsWebNavigationState http = new StatsWebNavigationState(true, 8090, false, true, true);
        StatsWebNavigationState https = new StatsWebNavigationState(true, 8443, true, true, true);

        assertEquals(URI.create("http://127.0.0.1:8090/"), http.baseUri());
        assertEquals(URI.create("https://127.0.0.1:8443/"), https.baseUri());
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
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/live/events?configuration_id=test"), "/live/events"));
        assertFalse(StatsWebServerService.hasExactPath(URI.create("/live/events-old"), "/live/events"));
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/live/messages?configuration_id=test"), "/live/messages"));
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/live/channel-diagnostics?configuration_id=test"), "/live/channel-diagnostics"));
        assertFalse(StatsWebServerService.hasExactPath(
            URI.create("/live/channel-diagnostics-old"), "/live/channel-diagnostics"));
        assertFalse(StatsWebServerService.hasExactPath(URI.create("/live/sites/legacy"), "/live/sites"));
        assertFalse(StatsWebServerService.hasExactPath(URI.create("/live/web-calls/legacy"), "/live/web-calls"));
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/api/export.csv?dataset=system-talkgroups"), "/api/export.csv"));
        assertFalse(StatsWebServerService.hasExactPath(
            URI.create("/api/export.csv/legacy"), "/api/export.csv"));
    }

    @Test
    void decodesQualityScopeBeforeChoosingItsCapability()
    {
        assertEquals(WebCapability.DASHBOARD_VIEW,
            StatsWebServerService.qualityCapability(URI.create("/api/quality?range=1h")));
        assertEquals(WebCapability.SYSTEMS_VIEW,
            StatsWebServerService.qualityCapability(URI.create("/api/quality?guid=site-1")));
        assertEquals(WebCapability.SYSTEMS_VIEW,
            StatsWebServerService.qualityCapability(URI.create("/api/quality?%67uid=site-1")));
    }

    @Test
    void validatesLiveDecoderEventScope()
    {
        DecodeEventViewService.Scope scope = StatsWebServerService.decodeEventScope(URI.create(
            "/live/events?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&frequency_hz=851012500&timeslot=2"));

        assertEquals("00000000-0000-0000-0000-000000000001", scope.configurationId());
        assertEquals(851_012_500L, scope.frequencyHz());
        assertEquals(2, scope.timeslot());
        assertThrows(StatsApiException.class,
            () -> StatsWebServerService.decodeEventScope(URI.create("/live/events")));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.decodeEventScope(URI.create(
            "/live/events?configuration_id=00000000-0000-0000-0000-000000000001&timeslot=0")));
    }

    @Test
    void validatesExactChannelDiagnosticScope()
    {
        ChannelDiagnosticService.Scope scope = StatsWebServerService.channelDiagnosticScope(URI.create(
            "/live/channel-diagnostics?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&frequency_hz=851012500&timeslot=2&client_id=00000000-0000-0000-0000-000000000002"));

        assertEquals("00000000-0000-0000-0000-000000000001", scope.configurationId());
        assertEquals(851_012_500L, scope.frequencyHz());
        assertEquals(2, scope.timeslot());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000002"),
            StatsWebServerService.channelDiagnosticClientId(URI.create(
                "/live/channel-diagnostics?client_id=00000000-0000-0000-0000-000000000002")));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.channelDiagnosticScope(URI.create(
            "/live/channel-diagnostics?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&timeslot=2")));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.channelDiagnosticClientId(URI.create(
            "/live/channel-diagnostics")));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.channelDiagnosticClientId(URI.create(
            "/live/channel-diagnostics?client_id=invalid")));
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
