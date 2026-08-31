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
import io.github.dsheirer.message.DecodeMessageViewService;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        assertEquals(URI.create("http://127.0.0.1:8090/?view=aliases"), http.aliasEditorUri());
        assertEquals(URI.create("https://127.0.0.1:8443/?view=aliases"), https.aliasEditorUri());
        assertEquals(URI.create("http://127.0.0.1:8090/?view=aliases&list=12&alias=41"),
            http.aliasEditorUri(12, 41));
        assertThrows(IllegalArgumentException.class, () -> http.aliasEditorUri(0, 41));
        assertThrows(IllegalArgumentException.class, () -> http.aliasEditorUri(12, 0));
    }

    @Test
    void acceptsOnlyRegisteredFixedPaths()
    {
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/api/v1/systems/p25%3ABEE00%3A348"), "/api/v1/systems/p25:BEE00:348"));
        assertFalse(StatsWebServerService.hasExactPath(
            URI.create("/api/system?scope=p25%3ABEE00%3A348"), "/api/v1/systems/p25:BEE00:348"));
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/api/v1/live/multiplex?client_id=00000000-0000-0000-0000-000000000001"),
            StatsApiV1.LIVE_MULTIPLEX));
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/api/v1/live/multiplex/control"), StatsApiV1.LIVE_MULTIPLEX_CONTROL));
        assertFalse(StatsWebServerService.hasExactPath(
            URI.create("/retired-live-path?configuration_id=test"), StatsApiV1.LIVE_MULTIPLEX));
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/api/v1/diagnostics/tuners"), StatsApiV1.TUNER_DIAGNOSTICS));
        assertTrue(StatsWebServerService.hasExactPath(
            URI.create("/api/v1/exports/system-talkgroups.csv"),
            StatsApiV1.EXPORTS + "/system-talkgroups.csv"));
        assertFalse(StatsWebServerService.hasExactPath(
            URI.create("/api/export.csv?dataset=system-talkgroups"),
            StatsApiV1.EXPORTS + "/system-talkgroups.csv"));
    }

    @Test
    void validatesLiveDecoderEventScope()
    {
        DecodeEventViewService.Scope scope = StatsWebServerService.decodeEventScope(URI.create(
            "/multiplex/decode-events?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&frequency_hz=851012500&timeslot=2" +
                "&subscription_id=00000000-0000-0000-0000-000000000002"));

        assertEquals("00000000-0000-0000-0000-000000000001", scope.configurationId());
        assertEquals(851_012_500L, scope.frequencyHz());
        assertEquals(2, scope.timeslot());
        assertThrows(StatsApiException.class,
            () -> StatsWebServerService.decodeEventScope(URI.create("/multiplex/decode-events")));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.decodeEventScope(URI.create(
            "/multiplex/decode-events?configuration_id=00000000-0000-0000-0000-000000000001&timeslot=0")));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.decodeEventScope(URI.create(
            "/multiplex/decode-events?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&subscription_id=not-a-uuid")));
    }

    @Test
    void validatesExactChannelDiagnosticScope()
    {
        ChannelDiagnosticService.Scope scope = StatsWebServerService.channelDiagnosticScope(URI.create(
            "/multiplex/channel-diagnostics?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&frequency_hz=851012500&timeslot=2" +
                "&subscription_id=00000000-0000-0000-0000-000000000003"));

        assertEquals("00000000-0000-0000-0000-000000000001", scope.configurationId());
        assertEquals(851_012_500L, scope.frequencyHz());
        assertEquals(2, scope.timeslot());
        assertThrows(StatsApiException.class, () -> StatsWebServerService.channelDiagnosticScope(URI.create(
            "/multiplex/channel-diagnostics?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&timeslot=2")));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.channelDiagnosticScope(URI.create(
            "/multiplex/channel-diagnostics?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&frequency_hz=851012500&subscription_id=not-a-uuid")));
    }

    @Test
    void validatesExactDecodeMessageScope()
    {
        DecodeMessageViewService.Scope scope = StatsWebServerService.decodeMessageScope(URI.create(
            "/multiplex/decode-messages?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&frequency_hz=851012500" +
                "&subscription_id=00000000-0000-0000-0000-000000000004"));

        assertEquals("00000000-0000-0000-0000-000000000001", scope.configurationId());
        assertEquals(851_012_500L, scope.frequencyHz());
        assertThrows(StatsApiException.class, () -> StatsWebServerService.decodeMessageScope(URI.create(
            "/multiplex/decode-messages?configuration_id=00000000-0000-0000-0000-000000000001")));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.decodeMessageScope(URI.create(
            "/multiplex/decode-messages?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&frequency_hz=851012500&timeslot=2")));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.decodeMessageScope(URI.create(
            "/multiplex/decode-messages?configuration_id=00000000-0000-0000-0000-000000000001" +
                "&frequency_hz=851012500&subscription_id=not-a-uuid")));
    }

    @Test
    void acceptsOnlyOneStrictCallAudioIdentifierSegment()
    {
        String callId = "0123456789abcdef0123456789abcdef-abc123";
        assertEquals(callId, StatsWebServerService.callAudioId(URI.create(
            "/api/v1/calls/" + callId + "/audio")));
        assertEquals(callId, StatsWebServerService.callAudioId(URI.create(
            "/api/v1/calls/%30" + callId.substring(1) + "/audio")));
        assertEquals(null, StatsWebServerService.callAudioId(URI.create(
            "/api/v1/calls/abc123/audio")));
        assertEquals(null, StatsWebServerService.callAudioId(URI.create(
            "/api/v1/calls/abc%2Faudio")));
        assertEquals(null, StatsWebServerService.callAudioId(URI.create(
            "/api/v1/calls/abc%252Faudio")));
        assertEquals(null, StatsWebServerService.callAudioId(URI.create(
            "/api/v1/calls/abc+def/audio")));
        assertEquals(null, StatsWebServerService.callAudioId(URI.create(
            "/api/v1/calls/abc/def/audio")));
    }

    @Test
    void selectsPublishedScanListsFromRepeatedStrictParameters()
    {
        ScanListModel model = new ScanListModel();
        model.replaceConfiguration(new ScanListConfiguration(List.of(
            new ScanList(1, 0, "Default", null, true, true),
            new ScanList(2, 1, "SouthWest", null, true, false),
            new ScanList(3, 2, "Cleveland", null, true, false),
            new ScanList(4, 3, "Draft", null, false, false)), Map.of(), Map.of()));

        assertEquals(Set.of(1L), StatsWebServerService.parseCallFeedRequest(
            URI.create(StatsApiV1.CALLS_FEED), model, 2).scanListIds());
        assertEquals(Set.of(2L, 3L), StatsWebServerService.parseCallFeedRequest(
            URI.create(StatsApiV1.CALLS_FEED + "?scan_list_id=2&scan_list_id=3"), model, 2).scanListIds());
        assertEquals(Set.of(2L), StatsWebServerService.parseCallFeedRequest(
            URI.create(StatsApiV1.CALLS_FEED + "?scan_list_id=2&scan_list_id=2"), model, 1).scanListIds());

        for(String query : List.of("scan_list_id=0", "scan_list_id=-1", "scan_list_id=01",
            "scan_list_id=missing", "other=2", "scan_list_id=4", "scan_list_id=99"))
        {
            assertThrows(StatsApiException.class, () -> StatsWebServerService.parseCallFeedRequest(
                URI.create(StatsApiV1.CALLS_FEED + "?" + query), model, 2), query);
        }

        assertThrows(StatsApiException.class, () -> StatsWebServerService.parseCallFeedRequest(
            URI.create(StatsApiV1.CALLS_FEED + "?scan_list_id=1&scan_list_id=2"), model, 1));
        assertThrows(StatsApiException.class, () -> StatsWebServerService.parseCallFeedRequest(
            URI.create(StatsApiV1.CALLS_FEED), null, 2));
    }

    @Test
    void parsesTheCallFeedCursorAsAnOptionalDecimalString()
    {
        ScanListModel model = new ScanListModel();
        model.replaceConfiguration(new ScanListConfiguration(List.of(
            new ScanList(1, 0, "Default", null, true, true),
            new ScanList(2, 1, "SouthWest", null, true, false)), Map.of(), Map.of()));

        StatsWebServerService.CallFeedRequest liveEdge = StatsWebServerService.parseCallFeedRequest(
            URI.create(StatsApiV1.CALLS_FEED + "?scan_list_id=2"), model, 2);
        assertEquals(Set.of(2L), liveEdge.scanListIds());
        assertEquals(null, liveEdge.cursor());

        StatsWebServerService.CallFeedRequest continued = StatsWebServerService.parseCallFeedRequest(
            URI.create(StatsApiV1.CALLS_FEED + "?scan_list_id=2&cursor=9007199254740993"), model, 2);
        assertEquals(9_007_199_254_740_993L, continued.cursor());

        for(String query: List.of("cursor=-1", "cursor=01", "cursor=1&cursor=2", "cursor=9223372036854775808",
            "other=1"))
        {
            assertThrows(StatsApiException.class, () -> StatsWebServerService.parseCallFeedRequest(
                URI.create(StatsApiV1.CALLS_FEED + "?" + query), model, 2), query);
        }
    }

    @Test
    void parsesOnlyCanonicalScanListCoveragePaths()
    {
        assertEquals(12L, StatsWebServerService.scanListCoverageId(
            URI.create("/api/v1/scan-lists/12/coverage")));

        for(String path : List.of("/api/v1/scan-lists/0/coverage", "/api/v1/scan-lists/01/coverage",
            "/api/v1/scan-lists/+1/coverage", "/api/v1/scan-lists/1", "/api/v1/scan-lists/1/coverage/",
            "/api/v1/scan-lists/1/extra/coverage", "/api/v1/admin/scan-lists/1/coverage"))
        {
            assertEquals(null, StatsWebServerService.scanListCoverageId(URI.create(path)), path);
        }
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

}
