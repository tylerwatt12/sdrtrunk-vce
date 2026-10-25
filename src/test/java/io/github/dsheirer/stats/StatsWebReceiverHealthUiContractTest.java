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
 * Protects the receiver-health navigation, refresh, and troubleshooting presentation contract.
 */
class StatsWebReceiverHealthUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void showsComputerFactsWithSharedDisclosureAndCopyFeedback() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String overview = block(source, "function receiverHealthComputerOverview(snapshot)");
        String facts = block(source, "function receiverHealthComputerFacts(computer)");
        String page = block(source, "function renderReceiverHealthPage(host, snapshot, stale, lastError)");
        String diagnostics = block(source, "function receiverHealthDiagnostics(snapshot)");
        String computerPage = block(source, "function renderReceiverComputerPage(host, snapshot, stale)");
        String controller = block(source, "class ReceiverHealthController");
        assertTrue(page.contains("receiverHealthDiagnostics(snapshot)"));
        assertFalse(diagnostics.contains("receiverHealthComputerOverview(snapshot)"));
        assertTrue(controller.contains("this.pageKind === 'computer' ? renderReceiverComputerPage : renderReceiverHealthPage"));
        assertTrue(computerPage.contains("receiverHealthComputerOverview(snapshot)"));
        assertTrue(overview.contains("keyValues(entries)"));
        assertTrue(overview.contains("ui-facts-embedded"));
        assertTrue(overview.contains("receiverHealthSection('computer', 'Computer & version'"));
        assertTrue(overview.contains("uiActionButton('Copy details', 'icon-copy'"));
        assertTrue(overview.contains("const healthController = currentReceiverHealthPageController()"));
        assertTrue(overview.contains("healthController.authorized()"));
        assertTrue(overview.contains("navigator.clipboard.writeText"));
        assertTrue(overview.contains("ui-feedback-${state}"));
        assertTrue(facts.contains("['Installed RAM', memory('installed_memory_bytes')]"));
        assertTrue(facts.contains("['VCE memory limit', memory('jvm_memory_limit_bytes')]"));
        assertTrue(facts.contains("Logical processors available to VCE"));
        assertFalse(facts.contains("hostname"));
    }

    @Test
    void exposesReceiverHealthOnlyToAuthorizedAdministrators() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String html = readText(INDEX_HTML);
        String viewAllowed = block(source, "function viewAllowed(view)");
        String routeAllowed = block(source, "function routeDefinitionAllowed(definition)");
        String adminGroups = block(source, "function adminSettingsGroups()");
        String controller = block(source, "class ReceiverHealthController");
        String desktopEnabled = block(controller, "desktopEnabled()");

        assertTrue(source.contains("RECEIVER_HEALTH: 'receiver-health'"));
        assertTrue(viewAllowed.contains("applicationRoutes?.[view]?.allowed()"));
        assertTrue(routeAllowed.contains("accessSession.tier === 'ADMIN'"));
        assertTrue(routeAllowed.contains("ACCESS_CAPABILITIES.RECEIVER_HEALTH"));
        assertTrue(html.contains("id=\"receiver-health-indicator\""));
        assertTrue(html.contains("href=\"/?view=admin&amp;tab=health\" hidden"));
        assertTrue(adminGroups.contains("id: 'health', label: 'Receiver status', icon: 'health', capability: " +
            "ACCESS_CAPABILITIES.RECEIVER_HEALTH"));
        assertFalse(adminGroups.contains("id: 'activity', label: 'Activity history'"));
        assertTrue(desktopEnabled.contains("return this.authorized()"));
    }

    @Test
    void pollsOneBoundedEndpointAndPreservesTheLastSnapshotWhenRefreshFails() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String controller = block(source, "class ReceiverHealthController");
        String refresh = block(controller, "async refresh()");
        String refreshButton = block(source, "function receiverHealthRefreshButton()");
        String access = block(source, "function updateAccessControls()");
        int topicsStart = source.indexOf("const LIVE_MULTIPLEX_TOPICS");
        String topics = source.substring(topicsStart, source.indexOf("});", topicsStart));

        assertTrue(refresh.contains("if (!this.desktopEnabled() || document.hidden || this.requestController) return;"));
        assertTrue(refresh.contains("api('/api/v1/receiver-health'"));
        assertTrue(refresh.contains("page: false, signal: controller.signal, timeoutMs: 10_000"));
        assertTrue(refresh.contains("this.snapshot = normalizeReceiverHealthSnapshot(response)"));
        assertTrue(refresh.contains("RECEIVER_HEALTH_STALE_MILLISECONDS"));
        assertTrue(refresh.contains("Receiver status has not updated recently."));
        assertTrue(refresh.contains("Receiver status is unavailable right now. Select Refresh to try again."));
        assertFalse(refresh.contains("error?.message"));
        assertTrue(refresh.contains("this.stale = true"));
        assertFalse(refresh.contains("this.snapshot = null"));
        assertFalse(refresh.contains("liveConnection("));
        assertFalse(topics.contains("receiver_health"));
        assertTrue(access.contains("receiverHealthController.synchronizeAccess()"));
        assertTrue(source.contains(".then(() => loadStatus(false))"));
        assertTrue(source.contains("void receiverHealthController.refresh();"));
        assertTrue(source.contains("Promise.all([loadStatus(true), receiverHealthController.refresh()])"));
        assertTrue(refreshButton.contains("'Refresh'"));
        assertTrue(refreshButton.contains("Promise.all([healthController.guideMode ? healthController.updatePage() : healthController.refresh()"));
        assertTrue(refreshButton.contains("healthController === receiverHealthController ? loadStatus(true) : Promise.resolve()"));
        assertFalse(source.contains("refreshLiveDisplaySettings"));
        assertTrue(source.contains("receiverHealthController.refresh()"));
    }

    @Test
    void rendersActionableActiveResolvedAndMeasurementDetailsWithoutDismissal() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String page = block(source, "function renderReceiverHealthPage(host, snapshot, stale, lastError)");
        String overview = block(source, "function receiverHealthOverview(snapshot, stale, lastError)");
        String diagnostics = block(source, "function receiverHealthDiagnostics(snapshot)");
        String controller = block(source, "class ReceiverHealthController");
        String indicator = block(controller, "\n  updateIndicator()");
        String incident = block(source, "function receiverHealthIncident(incident, resolved = false");
        String resolvedList = block(source, "function receiverHealthIncidentList(incidents, resolved = false)");
        String resolvedSort = block(source, "function receiverHealthSortedResolvedIncidents(incidents, sort)");
        String resolvedSection = block(source, "function receiverHealthResolvedSection(incidents)");
        String resourceScale = block(source, "function receiverHealthResourceScale(row)");
        String resourceBar = block(source, "function receiverHealthResourceBar(row)");
        String resourceOverview = block(source, "function receiverHealthHostResourceOverview(snapshot)");
        String measurement = block(source, "function receiverHealthMeasurementRow(row)");

        assertTrue(overview.contains("'Issues needing attention'"));
        assertTrue(overview.contains("receiverHealthIncidentList(snapshot.active)"));
        assertTrue(indicator.contains("Current issues"));
        assertFalse(overview.contains("'Service-impact alerts'"));
        assertFalse(overview.contains("summary.diagnostic_count"));
        assertTrue(page.contains("receiverHealthResolvedSection(snapshot.resolved)"));
        assertTrue(diagnostics.contains("receiverHealthSavedActivitySection()"));
        assertTrue(overview.contains("receiverHealthViewButton('Saved activity', 'diagnostics', 'saved-activity')"));
        assertTrue(diagnostics.contains("'Detailed measurements'"));
        assertTrue(page.contains("if (!snapshot)"));
        assertTrue(page.contains("'Loading receiver status…'"));
        assertTrue(page.contains("feedback.setAttribute('role', 'status')"));
        assertTrue(page.contains("Live status is delayed. Showing the last update received."));
        assertTrue(overview.contains("'Receiver status out of date'"));
        assertTrue(indicator.contains("'Status out of date'"));
        assertFalse(overview.contains("'Stale'"));
        assertTrue(overview.indexOf("overview.append(status)") <
            overview.indexOf("receiverHealthHostResourceOverview(snapshot)"));
        assertTrue(incident.contains("ui-section-disclosure"));
        assertFalse(incident.contains("incident.occurrence_id"));
        assertFalse(incident.contains("['Issue code'"));
        assertTrue(incident.contains("incident.severity"));
        assertTrue(incident.contains("incident.title"));
        assertTrue(incident.contains("incident.scope"));
        assertTrue(incident.contains("incident.opened_at_ms"));
        assertTrue(incident.contains("incident.last_seen_ms"));
        assertTrue(incident.contains("incident.resolved_at_ms"));
        assertFalse(incident.contains("incident.count"));
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
        assertTrue(resolvedSection.contains("'Most recently resolved'"));
        assertTrue(resolvedSection.contains("'Issue type (A–Z)'"));
        assertTrue(resolvedSection.contains("'Sort issue history'"));
        assertFalse(overview.toLowerCase().contains("dismiss"));
        assertFalse(resolvedSection.toLowerCase().contains("dismiss"));
        assertTrue(resourceScale.contains("RECEIVER_HEALTH_GC_BAR_MAXIMUM_MILLISECONDS"));
        assertTrue(resourceScale.contains("unit === '%' ? 100"));
        assertTrue(resourceBar.contains("node('progress'"));
        assertTrue(resourceBar.contains("progress.max = scale.maximum"));
        assertTrue(resourceBar.contains("progress.value = scale.value"));
        assertTrue(resourceBar.contains("progress.setAttribute('aria-label', label)"));
        assertTrue(resourceBar.contains("progress.setAttribute('aria-valuetext'"));
        assertTrue(resourceOverview.contains("measurement.id"));
        assertTrue(resourceOverview.contains("filter((row) => labels.has(row.label))"));
        assertTrue(resourceOverview.contains("rows.map((row) => receiverHealthResourceBar("));
        assertTrue(resourceOverview.contains("'Computer resources'"));
        assertTrue(resourceOverview.contains("receiverHealthViewButton('View details', 'diagnostics')"));
        assertTrue(measurement.contains("row.scope"));
        assertTrue(measurement.contains("row.label"));
        assertTrue(measurement.contains("row.value"));
        assertTrue(measurement.contains("row.unit"));
        assertTrue(measurement.contains("row.severity"));
        assertTrue(measurement.contains("row.detail"));
    }

    @Test
    void composesAccessibleHealthViewsAndKeepsResolvedIssuesCollapsedWithFiveGroupPages() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String controller = block(source, "class ReceiverHealthController");
        String page = block(source, "function renderReceiverHealthPage(host, snapshot, stale, lastError)");
        String diagnostics = block(source, "function receiverHealthDiagnostics(snapshot)");
        String section = block(source,
            "function receiverHealthSection(key, title, child, action = null, summary = '', status = null)");
        String measurement = block(source, "function receiverHealthMeasurementGroup(group, index)");
        String resolvedPage = block(source,
            "function receiverHealthResolvedPage(incidents, sort, requestedPage)");
        String resolvedPager = block(source, "function receiverHealthResolvedPager(page, onPage)");
        String sharedPager = readText(Path.of("stats-web", "assets", "core", "browsing-workflows.js"));
        String resolvedSection = block(source, "function receiverHealthResolvedSection(incidents)");
        String prune = block(source, "function receiverHealthPruneExpandedResolvedIncidents(incidents)");
        String focusedControl = block(source, "function receiverHealthFocusedControl(host)");
        String restoreFocus = block(source, "function receiverHealthRestoreFocus(host, key)");

        assertTrue(source.contains("const RECEIVER_HEALTH_RESOLVED_PAGE_SIZE = 5;"));
        assertTrue(controller.contains("this.healthView = 'overview'"));
        assertTrue(controller.contains("this.expandedResolvedIncidents = new Set()"));
        assertFalse(controller.contains("this.openHealthSections = new Set(['resolved'])"));
        assertTrue(controller.contains("if (this.pageHost !== host)"));
        assertTrue(controller.contains("this.resolvedPage = 0"));
        assertTrue(section.contains("ui-section-collapsible receiver-health-section"));
        assertTrue(section.contains("node('button', 'ui-disclosure-toggle receiver-health-section-toggle')"));
        assertTrue(section.contains("copy.append(node('span', '', title))"));
        assertTrue(section.contains("toggle.setAttribute('aria-controls', body.id)"));
        assertTrue(section.contains("toggle.setAttribute('aria-expanded', String(expanded))"));
        assertTrue(section.contains("body.hidden = !expanded"));
        assertTrue(section.contains("openHealthSections.has(key)"));
        assertTrue(section.contains("openHealthSections.add(key)"));
        assertTrue(section.contains("openHealthSections.delete(key)"));
        assertTrue(page.contains("[['overview', 'Overview'], ['history', 'Issue history'], " +
            "['diagnostics', 'Diagnostics']]"));
        assertTrue(page.contains("tabs.setAttribute('role', 'tablist')"));
        assertTrue(page.contains("tab.setAttribute('aria-selected', String(active))"));
        assertTrue(page.contains("tab.setAttribute('aria-controls', `receiver-health-panel-${key}`)"));
        assertTrue(page.contains("tab.tabIndex = active ? 0 : -1"));
        assertTrue(page.contains("panel.setAttribute('role', 'tabpanel')"));
        assertTrue(page.contains("panel.setAttribute('aria-labelledby', tab.id)"));
        assertTrue(page.contains("panel.hidden = !active"));
        assertTrue(page.contains("if (active)"));
        assertTrue(page.contains("livePickerNavigationIndex(event.key"));
        assertTrue(page.contains("key === 'overview' ? receiverHealthOverview(snapshot, stale, lastError)"));
        assertTrue(page.contains("key === 'history' ? receiverHealthResolvedSection(snapshot.resolved) : " +
            "receiverHealthDiagnostics(snapshot)"));
        assertTrue(diagnostics.contains("diagnostics.setAttribute('aria-label', 'Receiver diagnostics')"));
        assertTrue(diagnostics.contains("snapshot.measurements.map(receiverHealthMeasurementGroup)"));
        assertTrue(measurement.contains("`measurement:${receiverHealthText(group.id, `${title}:${index}`)}`"));
        assertTrue(measurement.contains("receiverHealthSection(key, title, body, null"));
        assertTrue(resolvedPage.indexOf("receiverHealthSortedResolvedIncidents") <
            resolvedPage.indexOf("sorted.slice"));
        assertTrue(resolvedPage.contains("RECEIVER_HEALTH_RESOLVED_PAGE_SIZE"));
        assertTrue(resolvedPage.contains("Math.max(0, pageCount - 1)"));
        assertTrue(resolvedPager.contains("ariaLabel: 'Issue history'"));
        assertTrue(resolvedPager.contains("label: 'Issue groups'"));
        assertTrue(resolvedPager.contains("browsingWorkflows.createBrowsingPager({ node"));
        assertTrue(sharedPager.contains("[['previous', 'Previous'], ['next', 'Next']]"));
        assertTrue(resolvedPager.contains("previous: { enabled: page.page > 0"));
        assertTrue(resolvedPager.contains("next: { enabled: page.has_more"));
        assertTrue(resolvedSection.contains("receiverHealthResolvedGroups(incidents)"));
        assertTrue(resolvedSection.contains("receiverHealthResolvedPage(groups,"));
        assertTrue(resolvedSection.contains("section('Issue history'"));
        assertTrue(resolvedSection.contains("result.dataset.receiverHealthSection = 'resolved'"));
        assertTrue(resolvedSection.contains("const healthController = currentReceiverHealthPageController()"));
        assertTrue(resolvedSection.contains("healthController.resolvedPage = page.page"));
        assertTrue(resolvedSection.contains("healthController.resolvedPage = 0"));
        assertTrue(prune.contains("incidents.flatMap((incident) => [incident, ...(incident.occurrences || [])])"));
        assertTrue(prune.contains(".map(receiverHealthResolvedIncidentKey)"));
        assertTrue(prune.contains("expandedResolvedIncidents.delete(key)"));
        assertTrue(focusedControl.contains("host.contains(active)"));
        assertTrue(focusedControl.contains("active.dataset.receiverHealthFocus"));
        assertTrue(restoreFocus.contains("'[data-receiver-health-focus]'"));
        assertTrue(restoreFocus.contains("element.dataset.receiverHealthFocus === key"));
        assertTrue(restoreFocus.contains("focus({ preventScroll: true })"));
        assertTrue(page.contains("receiverHealthFocusedControl(host)"));
        assertTrue(page.contains("receiverHealthRestoreFocus(host, focusedControl)"));
        assertTrue(section.contains("toggle.dataset.receiverHealthFocus = `section:${key}`"));
        assertTrue(resolvedPager.contains("navigation.dataset.receiverHealthFocus = 'resolved-pager'"));
    }

    @Test
    void providesLightAndDarkPresentationContracts() throws Exception
    {
        String css = StatsWebStylesheetTestSupport.readAll();

        assertTrue(css.contains(".receiver-health-indicator.receiver-health-healthy"));
        assertTrue(css.contains(".receiver-health-indicator.receiver-health-warning"));
        assertTrue(css.contains(".receiver-health-indicator.receiver-health-critical"));
        assertTrue(css.contains(".receiver-health-indicator.receiver-health-stale"));
        assertFalse(css.contains(":root[data-theme=\"dark\"] .receiver-health-overview-state"));
        assertFalse(css.contains(":root[data-theme=\"dark\"] .receiver-health-measurement-row"));
        assertTrue(css.contains(".ui-pill-danger"));
        assertTrue(css.contains(".ui-section-disclosure[open] > .ui-section-summary"));
        assertTrue(css.contains(".receiver-health-resolved-select"));
        assertTrue(css.contains(".ui-section-collapsible.collapsed > .ui-section-title"));
        assertTrue(css.contains(".receiver-health-section-body[hidden]"));
        assertTrue(css.contains(".ui-disclosure-toggle[aria-expanded=\"true\"]::before"));
        assertTrue(css.contains(".ui-disclosure-toggle:focus-visible"));
        assertTrue(css.contains(".ui-browse-pager"));
        assertTrue(css.contains(".receiver-health-resource-bars"));
        assertTrue(css.contains(".ui-metric-progress::-webkit-progress-value"));
        assertTrue(css.contains(".ui-metric-progress::-moz-progress-bar"));
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
