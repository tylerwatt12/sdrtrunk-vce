/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Protects the desktop-only receiver-health navigation, refresh, and troubleshooting presentation contract.
 */
class StatsWebReceiverHealthUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void exposesReceiverHealthOnlyToAuthorizedDesktopAdministrators() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String html = readText(INDEX_HTML);
        String viewAllowed = block(source, "function viewAllowed(view)");
        String renderAdmin = block(source, "async function renderAdmin()");
        String desktopEnabled = block(source, "desktopEnabled()");
        String mobileShell = html.substring(html.indexOf("id=\"mobile-listener-shell\""));

        assertTrue(source.contains("RECEIVER_HEALTH: 'receiver-health'"));
        assertTrue(viewAllowed.contains("accessSession.tier === 'ADMIN'"));
        assertTrue(viewAllowed.contains("ACCESS_CAPABILITIES.RECEIVER_HEALTH"));
        assertTrue(html.contains("id=\"receiver-health-indicator\""));
        assertTrue(html.contains("href=\"/?view=admin&amp;tab=health\" hidden"));
        assertTrue(renderAdmin.contains("id: 'health', label: 'Health', capability: " +
            "ACCESS_CAPABILITIES.RECEIVER_HEALTH"));
        assertTrue(desktopEnabled.contains("!mobileListenerModeActive()"));
        assertFalse(mobileShell.contains("receiver-health"));
    }

    @Test
    void pollsOneBoundedEndpointAndPreservesTheLastSnapshotWhenRefreshFails() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String refresh = block(source, "async refresh()");
        String mode = block(source, "function applyListenerShellMode()");
        int topicsStart = source.indexOf("const LIVE_MULTIPLEX_TOPICS");
        String topics = source.substring(topicsStart, source.indexOf("});", topicsStart));

        assertTrue(refresh.contains("if (!this.desktopEnabled() || document.hidden || this.requestController) return;"));
        assertTrue(refresh.contains("api('/api/v1/receiver-health'"));
        assertTrue(refresh.contains("page: false, signal: controller.signal, timeoutMs: 10_000"));
        assertTrue(refresh.contains("this.snapshot = normalizeReceiverHealthSnapshot(response)"));
        assertTrue(refresh.contains("RECEIVER_HEALTH_STALE_MILLISECONDS"));
        assertTrue(refresh.contains("The receiver health sampler has not produced a recent snapshot."));
        assertTrue(refresh.contains("this.stale = true"));
        assertFalse(refresh.contains("this.snapshot = null"));
        assertFalse(refresh.contains("liveConnection("));
        assertFalse(topics.contains("receiver_health"));
        assertTrue(mode.contains("receiverHealthController.synchronizeMode()"));
        assertTrue(source.contains("Promise.all([loadStatus(false), receiverHealthController.refresh()])"));
        assertTrue(source.contains("Promise.all([loadStatus(true), receiverHealthController.refresh()])"));
    }

    @Test
    void rendersActionableActiveResolvedAndMeasurementDetailsWithoutDismissal() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String page = block(source, "function renderReceiverHealthPage(host, snapshot, stale, lastError)");
        String incident = block(source, "function receiverHealthIncident(incident, resolved = false");
        String resolvedList = block(source, "function receiverHealthIncidentList(incidents, resolved = false)");
        String resolvedSort = block(source, "function receiverHealthSortedResolvedIncidents(incidents, sort)");
        String resolvedSection = block(source, "function receiverHealthResolvedSection(incidents)");
        String measurement = block(source, "function receiverHealthMeasurementRow(row)");

        assertTrue(page.contains("'Active alerts and diagnostics'"));
        assertTrue(page.contains("'Service-impact alerts'"));
        assertTrue(page.contains("summary.diagnostic_count"));
        assertTrue(page.contains("receiverHealthResolvedSection(snapshot.resolved)"));
        assertTrue(page.contains("'Measurements'"));
        assertTrue(page.contains("Showing the last receiver health snapshot."));
        assertTrue(incident.contains("incident.occurrence_id"));
        assertTrue(incident.contains("incident.code"));
        assertTrue(incident.contains("incident.severity"));
        assertTrue(incident.contains("incident.title"));
        assertTrue(incident.contains("incident.scope"));
        assertTrue(incident.contains("incident.opened_at_ms"));
        assertTrue(incident.contains("incident.last_seen_ms"));
        assertTrue(incident.contains("incident.resolved_at_ms"));
        assertTrue(incident.contains("incident.count"));
        assertTrue(incident.contains("receiver-health-incident-resolved-summary"));
        assertTrue(incident.contains("incident.observed"));
        assertTrue(incident.contains("incident.likely_cause"));
        assertTrue(incident.contains("incident.impact"));
        assertTrue(incident.contains("incident.check_next"));
        assertTrue(incident.contains("resolved ? 'details' : 'article'"));
        assertTrue(incident.contains("card.open = expanded"));
        assertTrue(incident.contains("card.addEventListener('toggle'"));
        assertFalse(incident.contains("node('button'"));
        assertTrue(resolvedList.contains("expandedResolvedIncidents.has(key)"));
        assertTrue(resolvedList.contains("expandedResolvedIncidents.add(key)"));
        assertTrue(resolvedList.contains("expandedResolvedIncidents.delete(key)"));
        assertTrue(resolvedSort.contains("sort === 'type'"));
        assertTrue(resolvedSort.contains("left.title || left.code"));
        assertTrue(resolvedSort.contains("resolved_at_ms"));
        assertTrue(resolvedSection.contains("'Newest resolved'"));
        assertTrue(resolvedSection.contains("'Alert type (A–Z)'"));
        assertTrue(resolvedSection.contains("'Sort resolved alerts'"));
        assertFalse(page.toLowerCase().contains("dismiss"));
        assertTrue(measurement.contains("row.scope"));
        assertTrue(measurement.contains("row.label"));
        assertTrue(measurement.contains("row.value"));
        assertTrue(measurement.contains("row.unit"));
        assertTrue(measurement.contains("row.severity"));
        assertTrue(measurement.contains("row.detail"));
    }

    @Test
    void providesLightDarkAndCacheRevisionContracts() throws Exception
    {
        String css = readText(APP_CSS);
        String html = readText(INDEX_HTML);

        assertTrue(css.contains(".receiver-health-indicator.receiver-health-healthy"));
        assertTrue(css.contains(".receiver-health-indicator.receiver-health-warning"));
        assertTrue(css.contains(".receiver-health-indicator.receiver-health-critical"));
        assertTrue(css.contains(".receiver-health-indicator.receiver-health-stale"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] .receiver-health-overview-state.receiver-health-healthy"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] .receiver-health-measurement-row.receiver-health-critical"));
        assertTrue(css.contains("details.receiver-health-incident:not([open])"));
        assertTrue(css.contains(".receiver-health-resolved-sort select"));
        assertTrue(html.contains("<meta name=\"sdrtrunk-web-revision\" content=\"82\">"));
        assertTrue(html.contains("/assets/app.css?v=65"));
        assertTrue(html.contains("/assets/app.js?v=100"));
    }

    private static String readText(Path path) throws Exception
    {
        assertTrue(Files.isRegularFile(path), () -> "Missing " + path.toAbsolutePath());
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String block(String source, String signature)
    {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, () -> "Missing " + signature);
        int openingBrace = source.indexOf('{', start + signature.length());
        int depth = 0;

        for(int index = openingBrace; index < source.length(); index++)
        {
            char character = source.charAt(index);

            if(character == '{')
            {
                depth++;
            }
            else if(character == '}' && --depth == 0)
            {
                return source.substring(start, index + 1);
            }
        }

        throw new AssertionError("Unterminated " + signature);
    }
}
