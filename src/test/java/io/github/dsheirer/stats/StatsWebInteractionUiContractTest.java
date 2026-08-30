/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Protects shared web controls and labels that are intentionally implemented once for every supported scope.
 */
class StatsWebInteractionUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");
    private static final Path WEB_CALL_PLAYER = Path.of("stats-web", "assets", "web-call-player.js");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");
    private static final Path WEB_SERVER = Path.of("src", "main", "java", "io", "github", "dsheirer", "stats",
        "StatsWebServerService.java");

    @Test
    void normalizesCheckedOutWebAssetLineEndings()
    {
        assertEquals("first\nsecond\nthird", normalizeLineEndings("first\r\nsecond\rthird"));
    }

    @Test
    void presentsAmAsAConventionalWebProtocol() throws Exception
    {
        String javascript = source();
        assertTrue(javascript.contains("am: 'AM'"));
        assertTrue(javascript.contains("AM: ['AM', 'AM']"));
        assertTrue(javascript.contains("{ key: 'AM', label: 'AM'"));
        assertTrue(javascript.contains("['am', 'p25', 'dmr', 'nxdn', 'nbfm'"));
    }

    @Test
    void pausesEveryNewestActivityRefreshWithoutQueueingLiveEvents() throws Exception
    {
        String activity = function(source(), "async function renderActivity(scopeParameters, title = 'Activity')");
        assertTrue(activity.contains("if (!route.get('before_id'))"));
        assertTrue(activity.contains("'Pause refresh'"));
        assertTrue(activity.contains("'Resume refresh'"));
        assertTrue(activity.contains("refreshGeneration += 1"));
        assertTrue(activity.contains("nextRefreshAt = paused ? nextRefreshAt : Date.now()"));
        assertFalse(activity.contains("const pending = new Map()"));
        assertFalse(activity.contains("liveConnection('activity'"));
    }

    @Test
    void pollsNewestActivityAndHighlightsStableNewRows() throws Exception
    {
        String source = source();
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");
        String css = readText(APP_CSS);
        int topicsStart = source.indexOf("const LIVE_MULTIPLEX_TOPICS");
        String topics = source.substring(topicsStart, source.indexOf("});", topicsStart));

        assertTrue(source.contains("const ACTIVITY_REFRESH_INTERVAL_MILLISECONDS = 10_000"));
        assertTrue(activity.contains("pageInterval(refreshTick, 1_000)"));
        assertTrue(activity.contains("document.hidden"));
        assertTrue(activity.contains("refreshInFlight"));
        assertTrue(activity.contains("renderIsCurrent(renderContext)"));
        assertTrue(activity.contains("const currentIds = new Set"));
        assertTrue(activity.contains("const newIds = new Set"));
        assertTrue(activity.contains("row.classList.add('activity-row-new')"));
        assertTrue(activity.contains("row.classList.remove('activity-row-new')"));
        assertTrue(activity.contains("pageTimeout(() => highlighted.forEach"));
        assertTrue(activity.contains("8_000"));
        assertTrue(activity.contains("activityTable.tableController.replaceRows(rows)"));
        assertTrue(activity.contains("updatePager(refreshed)"));
        assertTrue(activity.contains("page?.next_before_id"));
        assertTrue(activity.contains("current?.tagName === 'A'"));
        assertTrue(activity.contains("current.setAttribute('href', target)"));
        assertFalse(activity.contains("controls.replaceChildren"));
        assertTrue(activity.contains("paused || document.hidden || !renderIsCurrent(renderContext)"));
        assertTrue(activity.contains("!paused && !document.hidden ?"));
        assertTrue(activity.contains("countdown.setAttribute('role', 'timer')"));
        assertTrue(activity.contains("countdown.setAttribute('aria-live', 'off')"));
        assertTrue(activity.contains("announcement.setAttribute('role', 'status')"));
        assertTrue(activity.contains("Activity refresh recovered."));
        assertTrue(activity.contains("else announcement.textContent = ''"));
        assertTrue(source.contains("const LIVE_MULTIPLEX_VERSION = 2"));
        assertTrue(topics.contains("1: 'channel_activity'"));
        assertTrue(topics.contains("2: 'decode_events'"));
        assertTrue(topics.contains("5: 'tuner_diagnostics'"));
        assertFalse(topics.contains("'calls'"));
        assertTrue(css.contains("@keyframes activity-row-highlight"));
        assertTrue(css.contains(".activity-row-new > td"));
        assertTrue(css.contains("animation: activity-row-highlight 8s ease-out"));
        assertTrue(css.contains("@media (prefers-reduced-motion: reduce)"));
    }

    @Test
    void resynchronizesChannelActivityAfterRevisionGapsAndRemovals() throws Exception
    {
        String source = source();
        String synchronize = function(source, "function synchronizeLiveChannelActivitySource()");
        String snapshot = function(source, "function applyLiveChannelActivitySnapshot(snapshot)");

        assertTrue(snapshot.contains("liveChannelActivityTables.clear()"));
        assertTrue(snapshot.contains("liveChannelActivityNeedsResync = false"));
        assertTrue(synchronize.contains("revision !== liveChannelActivityRevision + 1"));
        assertTrue(synchronize.contains("liveChannelActivityNeedsResync = true"));
        assertTrue(synchronize.contains("if (update.operation === 'remove')"));
        assertTrue(synchronize.contains("source.addEventListener('activity_resync'"));
        assertTrue(synchronize.contains("applyLiveChannelActivitySnapshot(resync?.snapshot || resync)"));
    }

    @Test
    void avoidsAdvertisingSeparatelyRestrictedLiveFeatures() throws Exception
    {
        String source = source();
        String loggingNotice = function(source, "function databaseLoggingNotice(view)");
        String signalHealth = function(source, "async function signalHealthSection()");
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");

        assertFalse(loggingNotice.contains("Live Systems"));
        assertFalse(loggingNotice.contains("audio playback"));
        assertTrue(signalHealth.contains("capabilityAllowed(ACCESS_CAPABILITIES.LIVE)"));
        assertTrue(signalHealth.contains("anchor('Open Live signal levels', href('live'))"));
        assertFalse(activity.contains("Live Systems remain available"));
    }

    @Test
    void limitsDetailedHistoryNoticesToViewsThatRequireDetailedRows() throws Exception
    {
        String source = source();
        String globalNotice = function(source, "function databaseLoggingNotice(view)");
        String status = function(source, "async function loadStatus(refreshCurrentView = false)");
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");

        assertFalse(globalNotice.contains("Detailed history"));
        assertFalse(globalNotice.contains("Activity pages"));
        assertFalse(status.contains("historyLabel"));
        assertFalse(status.contains("History off"));
        assertTrue(activity.contains("Detailed history logging is not running."));
    }

    @Test
    void rendersSystemStatusWithoutCoercingLabelsOrMissingValuesToNumbers() throws Exception
    {
        String javascript = source();
        String statusBytes = function(javascript, "function adminStatusBytes(value)");
        String databaseDisplay = function(javascript, "function adminDatabaseDisplay(database)");
        String system = function(javascript, "function adminSystemStatusSection()");
        String refresh = function(javascript, "function refreshAdminSystemStatus()");
        String loadStatus = function(javascript, "async function loadStatus(refreshCurrentView = false)");

        assertTrue(statusBytes.contains("typeof value === 'number' ? value : Number.NaN"));
        assertTrue(databaseDisplay.contains("typeof database?.database_exists !== 'boolean'"));
        assertTrue(databaseDisplay.contains("if (!database.database_exists) return 'Missing'"));
        assertTrue(databaseDisplay.contains("return size === '—' ? 'Present' : size"));
        assertTrue(system.contains("const database = serviceStatus?.database"));
        assertTrue(system.contains("adminDatabaseDisplay(database)"));
        assertTrue(system.contains("['Summary logging', logging.summaryActive, summaryState]"));
        assertTrue(system.contains("['Detailed history', logging.historyActive, historyState]"));
        assertTrue(system.contains("['Activity database', database?.database_bytes, databaseDisplay]"));
        assertTrue(system.contains("logging.summaryActive ? 'Running'"));
        assertTrue(system.contains("logging.historyActive ? 'Running'"));
        assertTrue(system.contains("loggingState !== 'Unknown' && loggingState !== 'Running'"));
        assertTrue(system.contains("loggingState === 'Failed' ? 'Off · Failed'"));
        assertTrue(system.contains("logging.historyConfigured ? 'Configured · Inactive'"));
        assertTrue(system.contains("logging.historyRetained ? 'Off · Data retained'"));
        assertFalse(system.contains("admin-listener-status-values"));
        assertFalse(system.contains("Logging service state"));
        assertFalse(system.contains("Database file"));
        assertFalse(system.contains("logging.historyConfigured ? `Configured · ${inactiveState}`"));
        assertFalse(system.contains("Number(database.database_bytes || 0)"));
        assertFalse(system.contains("['Summary collection', summaryState]"));
        assertTrue(refresh.contains("current.replaceWith(adminSystemStatusSection())"));
        assertTrue(loadStatus.contains("currentView === 'admin' && route.get('tab') === 'system'"));
        assertTrue(loadStatus.contains("refreshAdminSystemStatus();"));
    }

    @Test
    void keepsSignalAndTunerSpectrumVisibleWithoutDesktopViewGates() throws Exception
    {
        String source = source();
        String live = function(source, "async function renderLive()");
        String channel = function(source, "function liveChannelPane()");
        String tuner = function(source, "function tunerSpectrumPanel()");

        assertTrue(live.contains("liveEventsPanel"));
        assertTrue(channel.contains("diagnostic('Signal', 'Selected channel signal spectrum')"));
        assertTrue(channel.contains("diagnostic('Symbols', 'Selected channel demodulated symbols')"));
        assertTrue(channel.contains("DIAGNOSTIC_FRAME_TYPES.CHANNEL_SYMBOLS"));
        assertTrue(tuner.contains("plot('FFT', 'Tuner frequency spectrum'"));
        assertTrue(tuner.contains("plot('Waterfall', 'Tuner spectrum history'"));
        assertFalse(live.contains("java-ui"));
        assertFalse(channel.contains("java-ui"));
        assertFalse(tuner.contains("java-ui"));
    }

    @Test
    void bindsAccessControlsDirectlyToTheVersionOneSchemas() throws Exception
    {
        String source = source();
        String session = function(source, "function normalizedAccessSession(value)");
        String allowed = function(source, "function capabilityAllowed(capability)");
        String policies = function(source, "function adminAccessPolicies(response)");

        assertTrue(session.contains("typeof allowed === 'boolean'"));
        assertFalse(session.contains("normalizeCapabilityMap"));
        assertTrue(session.contains("accessTierFromWire(value?.tier)"));
        assertTrue(allowed.contains("accessSession.capabilities?.[capability] === true"));
        assertFalse(allowed.contains("required_tier"));
        assertTrue(policies.contains("Array.isArray(response?.capabilities)"));
        assertTrue(policies.contains("entry?.required_tier"));
        assertTrue(source.contains("function wholeSiteAccessControl(policy, statusHost)"));
        assertTrue(source.contains("ACCESS_CAPABILITIES.SITE_ACCESS"));
        assertTrue(source.contains("policies.find((policy) => policy.id === ACCESS_CAPABILITIES.SITE_ACCESS)"));
        assertFalse(policies.contains("response?.policies"));
        assertFalse(policies.contains("entry?.capability"));
        assertFalse(source.contains("function normalizeCapabilityMap"));
        assertFalse(source.contains("function normalizeAccessTier"));
        assertFalse(source.contains("tier: requested.toLowerCase()"));
    }

    @Test
    void keepsCrossPageLinksInsideTheirAccessBoundaries() throws Exception
    {
        String source = source();
        String aliasList = function(source, "function aliasListLink(name, id)");
        assertTrue(aliasList.contains("aliasAdminAllowed()"));
        assertFalse(aliasList.contains("Alias List #"));
        assertTrue(function(source, "function scopeAliasListName(row)")
            .contains("row?.alias_list_name"));
        String systemLink = function(source, "function systemLink(reference, label)");
        assertTrue(systemLink.contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(systemLink.contains("entityRefHref(reference)"));
        assertFalse(systemLink.contains("row?.entity_ref"));
        assertTrue(source.contains("systemLink(talkgroup.system_entity_ref"));
        assertTrue(source.contains("systemLink(radio.system_entity_ref"));
        assertTrue(source.contains("systemLink(site.system_entity_ref"));
        assertTrue(function(source, "function siteLink(row, label = siteValue(row))")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source, "function siteNameSummary(row, linked = true)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source, "function neighborSiteLink(row)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source,
            "function talkgroupLink(row, id = row.talkgroup_id, label, reference = row?.entity_ref)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source,
            "function radioLink(row, id = row.radio_id, label, reference = row?.entity_ref)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source, "function callSourceLink(row)")
            .contains("entityReferenceAllowed(row.entity_ref)"));
        assertTrue(function(source, "function dashboardIdentityLink(row, label = dashboardIdentityId(row))")
            .contains("entityReferenceAllowed(row.entity_ref)"));
    }

    @Test
    void keepsSharedMetricsAndFittingTablesInsideTheirContainers() throws Exception
    {
        String source = source();
        String css = readText(APP_CSS);
        assertTrue(css.contains("grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));"));
        assertTrue(css.contains(".metric {\n  min-width: 0;"));
        assertTrue(css.contains("font-variant-numeric: tabular-nums;\n  overflow-wrap: anywhere;"));
        assertTrue(css.contains(".resizable-table th:last-child .column-resizer {\n  right: 0;"));
        assertFalse(css.contains("[data-table-type=\"alias-editor-scope-breakdown\"] th:last-child .column-resizer"));
        assertTrue(css.contains(".table-wrap {"));
        assertTrue(css.contains("overflow-x: auto;"));
        assertTrue(function(source, "function setTableColumnWidths(element, columnElements, widths)")
            .contains("element.style.minWidth = `${Math.round(total)}px`"));
    }

    @Test
    void reloadsStaleWebClientsWithoutRestoringLegacyTalkgroupReads() throws Exception
    {
        String source = source();
        String reload = function(source, "async function reloadForWebClientRevision()");
        String status = function(source, "async function loadStatus(refreshCurrentView = false)");
        String talkgroup = function(source, "async function renderTalkgroup()");
        String index = readText(INDEX_HTML);

        assertTrue(index.contains("<meta name=\"sdrtrunk-web-revision\" content=\"107\">"));
        assertTrue(source.contains("meta[name=\"sdrtrunk-web-revision\"]"));
        assertTrue(reload.contains("const response = await fetch('/', {"));
        assertTrue(reload.contains("method: 'HEAD', cache: 'no-store', credentials: 'same-origin'"));
        assertTrue(reload.contains("signal: controller.signal"));
        assertTrue(reload.contains("response.headers.get('X-Sdrtrunk-Web-Revision')"));
        assertTrue(reload.contains("window.location.reload()"));
        assertTrue(status.contains("if (await reloadForWebClientRevision()) return;"));
        assertTrue(status.indexOf("await reloadForWebClientRevision()") <
            status.indexOf("capabilityAllowed(ACCESS_CAPABILITIES.DASHBOARD)"));
        assertTrue(talkgroup.contains("api(groupIdentityApiPath(systemScope.scope, kind, id))"));
        assertFalse(source.contains("/api/talkgroup"));
    }

    @Test
    void keepsSharedTabsScrollableWithoutVisibleScrollbars() throws Exception
    {
        String css = readText(APP_CSS);

        assertTrue(css.contains(".tabs {"));
        assertTrue(css.contains("overflow-x: auto;\n  overflow-y: hidden;\n  scrollbar-width: none;"));
        assertTrue(css.contains(".tabs::-webkit-scrollbar {\n  display: none;"));
    }

    @Test
    void showsConfiguredSystemHeadingsAndLinksEveryTrunkedParent() throws Exception
    {
        String source = source();
        String systems = function(source, "async function renderSystems()");
        String presenter = function(source, "function systemsDirectoryContent(data)");
        assertTrue(presenter.contains("row.configured_system || `${protocolFamily(row)} System`"));
        assertTrue(presenter.contains("heading.append(systemLink(row.entity_ref, label))"));
        assertTrue(presenter.contains("siteNameSummary(row)"));
        assertTrue(systems.contains("systemsDirectory.load(apiPage"));
        assertTrue(presenter.contains("tableRows: rows"));
        assertTrue(presenter.contains("`directory-${row.directory_type}-row`"));
        assertTrue(presenter.contains("truncatedParentCount"));
        assertTrue(presenter.contains("previewLimit"));
        assertFalse(systems.contains("systemApiPath(system.scope_token, 'sites')"));
        assertFalse(source.contains("SYSTEM_DIRECTORY_SITE_CONCURRENCY"));
        assertFalse(presenter.contains("directory-secondary"));
        assertFalse(presenter.contains("row.site_names && row.site_names"));
        assertFalse(presenter.contains("isP25(row) ? 'P25 System'"));
        assertFalse(readText(APP_CSS).contains(".directory-secondary"));
    }

    @Test
    void separatesCallOutcomesFromNonCallSignalingInRoomyViews() throws Exception
    {
        String source = source();
        assertTrue(source.contains("key: 'alias_description'"));
        assertTrue(source.contains("key: 'talkgroup_alias_description'"));
        assertTrue(source.contains("fullLabel: 'Signaling observations'"));
        assertTrue(source.contains("render: talkgroupSignaling, className: 'numeric'"));
        assertTrue(source.contains("sort: 'signaling_observation_count'"));
        assertTrue(function(source, "function talkgroupSignaling(row)")
            .contains("return total > 0 ? number(total) : '—'"));
        assertTrue(function(source, "function talkgroupSignalingSortValue(row)")
            .contains("row.signaling_observation_count"));
        assertTrue(function(source, "function signalingCounts(row)")
            .contains(".sort((left, right) => right[1] - left[1])"));
        assertTrue(function(source, "function talkgroupActivityChart(response, seriesConfigurations, ariaLabel)")
            .contains("const largest = configurations.reduce"));
        assertTrue(source.contains("section('Logical Call Activity'"));
        assertTrue(source.contains("section('Retained Signaling Observations'"));
        assertTrue(source.contains("section('Call Activity'"));
        assertTrue(source.contains("section('Collected Signaling Observations'"));
        assertTrue(source.contains("section('Retained Signaling Totals'"));
        assertTrue(source.contains("TALKGROUP_CALL_ACTIVITY_SERIES"));
        assertTrue(source.contains("TALKGROUP_SIGNALING_SERIES"));
        assertTrue(source.contains("entity-info-column entity-info-standalone"));
        assertTrue(readText(APP_CSS).contains(".entity-info-standalone > .section"));
        assertFalse(source.contains("function talkgroupEvidence"));
        assertFalse(source.contains("row.evidence_total"));
        assertFalse(source.contains("'Open full Action Counts'"));
        assertFalse(source.contains("node('details', 'evidence')"));
        assertFalse(source.contains("fullLabel: 'Affiliations'"));
        assertTrue(source.contains("talkgroup.alias_description"));
        assertFalse(source.contains("section('Action Counts'"));
        assertTrue(function(source, "function conventionalTalkgroupColumns()")
            .contains("key: 'alias_description'"));
        assertFalse(source.contains("Talkgroup Name"));
        assertFalse(source.contains("TG Name"));
    }

    @Test
    void displaysStationIdentificationChannelsAsCwid() throws Exception
    {
        String source = source();
        assertTrue(source.contains("CWID: { abbreviation: 'CWID'"));
        assertTrue(function(source, "function channelTags(row)").contains("channelTagBadge('CWID')"));
    }

    @Test
    void usesServerSuppliedProtocolAwareEncryptionNames() throws Exception
    {
        String source = source();
        String activityValue = function(source, "function encryptionActivityValue(row)");
        assertTrue(activityValue.contains("row.encryption_display || 'ENC'"));
        assertTrue(activityValue.contains("row.encryption_full_display"));
        assertTrue(source.contains("talkgroup.last_encryption_algorithm_name"));
        assertTrue(source.contains("radio.last_encryption_algorithm_name"));
        assertFalse(source.contains("P25_ENCRYPTION_ALGORITHM_NAMES"));
    }

    @Test
    void preservesPatchKindAcrossTabsRelationshipsAndActivity() throws Exception
    {
        String source = source();
        String tabs = function(source, "function entityTabs(view, system, id, active, radio, kind = null)");
        String talkgroup = function(source, "async function renderTalkgroup()");
        String radio = function(source, "async function renderRadio()");
        String links = function(source,
            "function talkgroupLink(row, id = row.talkgroup_id, label, reference = row?.entity_ref)");
        assertTrue(tabs.contains("kind: kind === 'patch_group' ? 'patch_group' : null"));
        assertTrue(talkgroup.contains("entityTabs('talkgroup', talkgroup, id, tab, false, kind)"));
        assertTrue(talkgroup.contains(
            "pageParameters({ talkgroup_id: id, kind: kind === 'patch_group' ? 'patch_group' : null,"));
        assertTrue(talkgroup.contains("renderActivity({ ...systemScope, talkgroup_id: id, kind }"));
        assertTrue(talkgroup.contains("talkgroupActivityHistorySection({ ...systemScope, talkgroup_id: id, kind })"));
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");
        assertFalse(activity.contains("scopeParameters.kind === 'patch'"));
        assertTrue(activity.contains("const refreshed = await api('/api/v1/activity'"));
        assertTrue(activity.contains("...scopeParameters"));
        assertTrue(links.contains("entityRefHref(reference)"));
        assertTrue(radio.contains("radio.last_talkgroup_entity_ref"));
        assertTrue(source.contains("render: (row) => groupIdentityLabel(row)"));
        assertTrue(source.contains("talkgroupLink(row, row.patch_group)"));
        assertFalse(source.contains("target_kind_code"));
        assertFalse(source.contains("identity_kind_code"));
        assertFalse(source.contains("last_talkgroup_kind_code"));
        String siteTalkgroups = function(source, "async function siteTopTalkgroupsSection(site)");
        assertTrue(siteTalkgroups.contains("id: 'talkgroup-kind'"));
        assertTrue(siteTalkgroups.contains("groupIdentityLabel(row)"));
    }

    @Test
    void pagesSiteChannelsAndNeighborsWithoutLegacySiteRoutes() throws Exception
    {
        String source = source();
        String channels = function(source, "async function renderSiteChannels(site, renderContext)");
        String neighbors = function(source, "async function renderSiteNeighbors(site, renderContext)");
        String site = function(source, "async function renderSite()");
        String render = function(source, "async function render()");
        String system = function(source, "async function renderSystem()");
        assertTrue(channels.contains("createAsyncSection('Channels'"));
        assertTrue(channels.contains("apiPage(siteApiPath(site.guid, 'channels'), pageParameters())"));
        assertTrue(channels.contains("pagedTableContent(page"));
        assertTrue(channels.contains("tableOptions: { sortable: false, serverSort: false }"));
        assertTrue(neighbors.contains("createAsyncSection('Neighbors'"));
        assertTrue(neighbors.contains("apiPage(siteApiPath(site.guid, 'neighbors'), pageParameters())"));
        assertTrue(neighbors.contains("pagedTableContent(page"));
        assertTrue(neighbors.contains("tableOptions: { sortable: false, serverSort: false }"));
        assertTrue(site.contains("renderSiteChannels(site, renderContext)"));
        assertTrue(site.contains("renderSiteNeighbors(site, renderContext)"));
        assertTrue(system.contains("const tabItems = systemTabItems(system)"));
        assertTrue(system.contains("tabItems.some((item) => item.id === requestedTab)"));
        assertTrue(system.contains("window.history.replaceState({}, '', currentHref())"));
        assertFalse(source.contains("async function renderSites()"));
        assertFalse(render.contains("route.get('view') === 'sites'"));
        assertFalse(render.contains("sites: renderSites"));
        assertFalse(system.contains("requestedTab === 'sites'"));
    }

    @Test
    void showsSystemIdentityTotalsWithNavigationAboveAndBelowEachTable() throws Exception
    {
        String source = source();
        String pager = function(source, "function pager(page, position = 'bottom', itemLabel = 'Rows')");
        String content = function(source,
            "function pagedTableContent(page, columns, tableType, options = {})");
        String pagedSection = function(source,
            "function pagedSection(title, page, columns, searchPlaceholder, tableType, action = null, options = {})");
        String system = function(source, "async function renderSystem()");
        String css = readText(APP_CSS);

        assertTrue(pager.contains("const totalCount = page.total_count"));
        assertTrue(pager.contains("of ${number(totalCount)}"));
        assertTrue(pager.contains("aria-label"));
        assertTrue(content.contains("if (options.topPager) result.append(pager(page, 'top', itemLabel))"));
        assertTrue(content.contains("result.append(pager(page, 'bottom', itemLabel))"));
        assertTrue(pagedSection.contains("section(title, pagedTableContent(page, columns, tableType, options), action)"));
        assertEquals(3, system.split("topPager: true", -1).length - 1);
        assertTrue(css.contains(".pager-top {\n  border-top: 0;\n  border-bottom: 1px solid var(--line);"));
    }

    @Test
    void exportsCompleteFilteredManagerTablesWithoutPaginationParameters() throws Exception
    {
        String source = source();
        String href = function(source, "function exportCsvHref(dataset, context = {})");
        String helper = function(source, "function exportCsvLink(dataset, context = {})");
        assertTrue(href.contains("`/api/v1/exports/${encodeURIComponent(String(dataset))}.csv`"));
        assertTrue(href.contains("['q', 'sort', 'direction']"));
        assertTrue(href.contains("return `${path}${parameters.size ? `?${parameters}` : ''}`"));
        assertFalse(href.contains("parameters.set('dataset'"));
        assertTrue(helper.contains("anchor('Export CSV', exportCsvHref(dataset, context)"));
        assertTrue(helper.contains("link.setAttribute('download', '')"));
        assertTrue(helper.contains("link.setAttribute('aria-label'"));
        assertFalse(href.contains("'limit'"));
        assertFalse(href.contains("'offset'"));
        assertFalse(href.contains("'before_id'"));

        String system = function(source, "async function renderSystem()");
        assertTrue(system.contains("exportCsvLink('system-talkgroups', systemScope)"));
        assertTrue(system.contains("exportCsvLink('system-radios', { ...systemScope, ...filters })"));
        assertEquals(2, system.split("exportCsvLink\\(", -1).length - 1,
            "Talker Alias Summary must not expose CSV export");

        assertTrue(function(source, "async function renderSiteChannels(site, renderContext)")
            .contains("exportCsvLink('site-channels', { guid: site.guid })"));
        assertTrue(function(source, "async function renderSiteNeighbors(site, renderContext)")
            .contains("exportCsvLink('site-neighbors', { guid: site.guid })"));
        assertTrue(function(source, "async function renderConventional()")
            .contains("exportCsvLink('conventional-channels')"));
        assertTrue(function(source, "async function renderConventionalTalkgroups(configurationId)")
            .contains("exportCsvLink('conventional-talkgroups', { configuration_id: configurationId })"));
        assertTrue(function(source, "async function renderConventionalRadios(configurationId)")
            .contains("exportCsvLink('conventional-radios', { configuration_id: configurationId })"));

        assertFalse(function(source, "async function renderDashboard()").contains("exportCsvLink("));
        assertFalse(function(source, "async function renderLive()").contains("exportCsvLink("));
        assertFalse(function(source, "async function renderActivity(scopeParameters, title = 'Activity')")
            .contains("exportCsvLink("));
        assertTrue(readText(APP_CSS).contains(".export-csv-action"));
    }

    @Test
    void labelsProtocolDefinedSentinelsAsSystemOrSpecialActivityWithoutLinkingThem() throws Exception
    {
        String source = source();
        String css = readText(APP_CSS);
        String labels = function(source, "function specialIdentifierLabel(row, value, kind)");
        String renderer = function(source, "function activityIdentifier(row, value, kind, reference)");
        String sourceAlias = function(source, "function activitySourceAlias(row)");
        assertTrue(labels.contains("0x0000: 'No Talkgroup'"));
        assertTrue(labels.contains("0xFFFF: 'Everyone'"));
        assertTrue(labels.contains("0x000000: 'No Unit'"));
        assertTrue(labels.contains("0xFFFFFC: 'FNE'"));
        assertTrue(labels.contains("0xFFFFFD: 'System Default'"));
        assertTrue(labels.contains("0xFFFFFE: 'Registration Default'"));
        assertTrue(labels.contains("0xFFFFFF: 'All Units'"));
        assertTrue(labels.contains("0xFFFEC0: 'PSTN Gateway'"));
        assertTrue(labels.contains("0xFFFECA: 'Trunking System Controller'"));
        assertTrue(labels.contains("0xFFFFFD: 'All Radios at Site'"));
        assertTrue(labels.contains("0xFFF0: 'Reserved Group'"));
        assertTrue(labels.contains("0xFFF0: 'Trunking Controller'"));
        assertTrue(labels.contains("row.address_domain !== 'nxdn_type_d'"));
        assertFalse(labels.contains("identity_domain_code"));
        assertTrue(renderer.contains("node('span', 'special-identifier', specialLabel)"));
        assertTrue(renderer.contains("talkgroupLink(row, value, identifier, reference)"));
        assertTrue(renderer.contains("radioLink(row, value, identifier, reference)"));
        assertFalse(renderer.contains("badge('System/special'"));
        assertTrue(css.contains(".special-identifier"));
        assertTrue(css.contains("text-overflow: ellipsis"));
        assertFalse(css.contains(".special-signaling"));
        assertTrue(renderer.indexOf("if (specialLabel)") < renderer.indexOf("talkgroupLink(row, value"));
        assertTrue(sourceAlias.contains("specialIdentifierLabel(row, row.source_radio_id, 'radio')"));
        assertTrue(sourceAlias.indexOf("specialIdentifierLabel") < sourceAlias.indexOf("radioLink("));
        assertTrue(source.contains("render: activitySourceAlias"));
    }

    @Test
    void keepsIdentifiersUngroupedWhileCountsRemainLocalized() throws Exception
    {
        String source = source();
        String identifier = function(source, "function identifierNumber(value)");
        assertTrue(identifier.contains("String(Math.trunc(numeric))"));
        assertFalse(identifier.contains("number(value)"));
        assertTrue(function(source, "function dashboardIdentityId(row)")
            .contains("identityNumber(row, row.identity_id)"));
        assertTrue(function(source, "function identityNumber(row, value)")
            .contains("String((numeric >> 11) & 0x1F).padStart(2, '0')"));
        assertTrue(function(source, "function identityNumber(row, value)")
            .contains("String(numeric & 0x7FF).padStart(4, '0')"));
        assertTrue(source.contains("render: (row) => identifierNumber(row.radio_id)"));
        assertTrue(source.contains("render: (row) => number(row.logical_call_count)"));
    }

    @Test
    void linksCallsignsAndKnownNeighborSitesSafely() throws Exception
    {
        String source = source();
        String callsign = function(source, "function callsignLink(value)");
        String neighbor = function(source, "function neighborSiteLink(row)");
        String trunkedNeighbors = function(source, "function trunkedSiteNeighborColumns(site)");
        assertTrue(callsign.contains("externalAnchor(callsign"));
        assertTrue(callsign.contains("encodeURIComponent(callsign)"));
        assertTrue(source.contains("['Callsign', callsignLink(site.callsign)]"));
        assertTrue(source.contains("render: (row) => callsignLink(row.callsign)"));
        assertTrue(neighbor.contains("entityRefHref(row?.entity_ref)"));
        assertFalse(neighbor.contains("neighbor_guid"));
        assertFalse(neighbor.contains("href('site'"));
        assertTrue(neighbor.contains("neighborSiteDisplayParts(row)"));
        assertTrue(source.contains("fullLabel: 'Monitored Name and Site'"));
        assertTrue(source.contains("row?.neighbor_configured_site"));
        assertTrue(source.contains("row?.neighbor_configured_name"));
        assertTrue(trunkedNeighbors.contains("render: neighborSiteLink"));
    }

    @Test
    void wrapsAdjacentBadgesWithTwoDimensionalSpacing() throws Exception
    {
        String source = source();
        String css = readText(APP_CSS);
        assertTrue(function(source, "function neighborStatus(value)").contains("badgeGroup("));
        assertTrue(css.contains(".badge-group"));
        assertTrue(css.contains("flex-wrap: wrap"));
        assertTrue(css.contains("gap: 4px"));
        assertFalse(css.contains(".badge + .badge"));
    }

    @Test
    void appliesAndPersistsAnAccessibleAccountOwnedTheme() throws Exception
    {
        String source = source();
        String html = readText(INDEX_HTML);
        String css = readText(APP_CSS);
        assertFalse(html.contains("localStorage"));
        assertTrue(html.contains("id=\"theme-toggle\""));
        assertTrue(html.contains("/assets/app.css?v=89"));
        assertTrue(function(source, "function storedTheme()")
            .contains("activeUserPreferences().appearance.theme"));
        assertTrue(function(source, "function setTheme(theme)")
            .contains("preferences.appearance.theme = selected"));
        assertFalse(source.contains("THEME_STORAGE_KEY"));
        assertTrue(function(source, "function updateThemeButton(toggle, theme)")
            .contains("dark ? '#icon-sun' : '#icon-moon'"));
        assertTrue(source.contains("toggle.setAttribute('aria-pressed'"));
        assertTrue(css.contains(":root[data-theme=\"dark\"]"));
        assertTrue(css.contains("color-scheme: light"));
        assertTrue(css.contains("--chart-call:"));
        assertTrue(css.contains(
            ":not(.auth-action):not(.auth-session-button):not(.table-sort-control):not(.systems-live-tab)"));
        assertFalse(css.contains("filter: invert("));
    }

    @Test
    void controlsAndPersistsAccountOwnedBrowserPlaybackVolumeIndependentlyOfTransportState() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(WEB_CALL_PLAYER);
        String application = source();
        String css = readText(APP_CSS);
        String changeVolume = function(source, "  changeVolume(write = false)");
        String applyPreferences = function(source, "  applyPreferences(preferences)");
        String writePreferences = function(source, "  writePreferences()");
        String bindControls = function(source, "  bindControls()");
        String ensureAudioContext = function(source, "  ensureAudioContext()");
        String startCurrent = function(source, "  startCurrent()");

        assertTrue(html.contains("id=\"playback-volume\" type=\"range\""));
        assertTrue(html.contains("aria-label=\"Browser playback volume\""));
        assertTrue(html.contains("class=\"playback-volume-label\" aria-hidden=\"true\">VOL</span>"));
        assertFalse(html.contains("id=\"playback-volume-value\""));
        assertTrue(application.contains("import { WebCallPlayer } from './web-call-player.js';"));
        assertFalse(html.contains("/assets/web-call-player.js"));
        assertFalse(source.contains("VOLUME_KEY"));
        assertFalse(source.contains("localStorage"));
        assertTrue(source.contains("this.volume = 1"));
        assertTrue(applyPreferences.contains("const volume = Number(preferences.volume)"));
        assertTrue(applyPreferences.contains("this.gainNode.gain.value = volume"));
        assertTrue(changeVolume.contains("this.gainNode.gain.value = this.volume"));
        assertTrue(bindControls.contains("this.changeVolume(false)"));
        assertTrue(bindControls.contains("this.writePreferences()"));
        assertTrue(writePreferences.contains("this.preferenceWriter({"));
        assertTrue(writePreferences.contains("volume: this.volume"));
        assertFalse(changeVolume.contains("this.paused"));
        assertTrue(function(source, "  synchronizeSubscription()").contains("else this.setStatus('Ready')"));
        assertTrue(ensureAudioContext.contains("this.audioContext.createAnalyser()"));
        assertTrue(ensureAudioContext.contains("this.analyserNode.connect(this.gainNode)"));
        assertTrue(ensureAudioContext.contains("this.audioContext.createGain()"));
        assertTrue(ensureAudioContext.contains("this.gainNode.gain.value = this.volume"));
        assertTrue(startCurrent.contains("source.connect(this.analyserNode)"));
        String waveform = function(source, "  readAudioWaveform(levels)");
        assertTrue(waveform.contains("this.analyserNode.getByteTimeDomainData(this.waveformSamples)"));
        assertTrue(waveform.contains("Math.abs(this.waveformSamples[sample] - 128) / 128"));
        assertTrue(css.contains(".playback-volume input:focus-visible"));
        assertTrue(css.contains(".playback-volume input::-webkit-slider-runnable-track"));
        assertTrue(css.contains("height: 20px"));
        assertTrue(css.contains(".playback-volume {\n  position: relative;\n  width: 92px;\n  height: 32px;"));
        assertTrue(css.contains("border: 1px solid #30383b;"));
    }

    @Test
    void startsCallPlaybackAtTheLiveEdgeAndRetainsActivityAcrossPollingFailures() throws Exception
    {
        String player = readText(WEB_CALL_PLAYER);
        String ensureConnected = function(player, "  ensureConnected()");
        String requestFeed = function(player, "  async requestFeed(signal)");
        String pollFeed = function(player, "  async pollFeed(generation)");
        String enqueue = function(player, "  enqueue(call)");
        String activity = function(source(), "async function renderActivity(scopeParameters, title = 'Activity')");

        assertTrue(ensureConnected.contains("this.feedCursor = null"));
        assertTrue(requestFeed.contains("this.feedRequestUrl()"));
        assertTrue(requestFeed.contains("typeof value?.reset !== 'boolean'"));
        assertTrue(pollFeed.contains("if (response.reset) this.recordSkippedCallNotice()"));
        assertTrue(pollFeed.contains("this.setStatus('Reconnecting')"));
        assertTrue(enqueue.contains("this.seenCallIds.has(normalized._callId)"));
        assertTrue(enqueue.contains("this.rememberCallId(normalized._callId)"));
        assertFalse(player.contains("consumeSnapshot("));
        assertFalse(player.contains("live_gap"));
        assertTrue(activity.contains("api('/api/v1/activity'"));
        assertTrue(activity.contains("tableController.replaceRows"));
        assertTrue(activity.contains("refreshFailed = true"));
        assertTrue(activity.contains("The current entries were retained; retrying automatically."));
        assertFalse(activity.contains("activity_reset"));
    }

    @Test
    void usesNormalPlaybackTransportAndKeepsBufferingCallsOutOfCallActions() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(WEB_CALL_PLAYER);
        String css = readText(APP_CSS);
        String enqueue = function(source, "  enqueue(call)");
        String togglePlayback = function(source, "  async togglePlayback()");
        String replayLast = function(source, "  async replayLastCall()");
        String toggleHold = function(source, "  toggleHold()");
        String avoidCurrent = function(source, "  avoidCurrent()");
        String render = function(source, "  render()");
        String avoidList = function(source(), "function openPlaybackAvoidList(player = webCallPlayer)");

        int play = html.indexOf("id=\"playback-play\"");
        int skip = html.indexOf("id=\"playback-skip\"");
        int replay = html.indexOf("id=\"playback-replay\"");
        int hold = html.indexOf("id=\"playback-hold\"");
        int avoid = html.indexOf("id=\"playback-avoid\"");
        assertTrue(play >= 0 && play < skip && skip < replay && replay < hold && hold < avoid);
        assertTrue(html.contains("id=\"icon-replay\""));
        assertTrue(html.contains("id=\"icon-stop\""));
        assertTrue(html.contains("id=\"playback-replay\" class=\"playback-command playback-icon-command\" " +
            "aria-label=\"Replay last call\""));
        assertTrue(html.contains("<use href=\"#icon-replay\"></use>"));
        assertTrue(html.contains("id=\"playback-control-menu\" class=\"playback-control-menu\" open"));
        assertTrue(css.contains("#desktop-playback-slot .playback-control-menu[open] > " +
            ".playback-control-menu-panel"));
        assertTrue(css.contains("#desktop-playback-slot .playback-control-menu-panel .playback-volume"));
        assertFalse(css.contains(".playback-controls .playback-command:not(#playback-play)"));
        assertFalse(html.contains("id=\"playback-capacity\""));
        assertFalse(source.contains("Matching calls are delivered once"));
        assertTrue(css.contains(".playback-panel-note:empty"));
        assertTrue(css.contains("linear-gradient(180deg, #2c3235 0%, #202528 52%, #171b1d 100%)"));
        assertFalse(html.contains("id=\"playback-mute\""));
        assertFalse(html.contains(">Unmute<"));
        assertTrue(source.contains("this.paused = true"));
        assertTrue(enqueue.contains("if (!this.paused && !this.current) this.playNext();"));
        assertTrue(enqueue.contains("else this.render();"));
        assertTrue(togglePlayback.contains("this.stopFeed()"));
        assertTrue(togglePlayback.contains("this.clearQueuedCalls()"));
        assertTrue(togglePlayback.contains("this.stopCurrent()"));
        assertTrue(togglePlayback.contains("if (!this.ensureConnected())"));
        assertTrue(togglePlayback.contains("this.setStatus('Unavailable')"));
        assertFalse(togglePlayback.contains("defaultSelected"));
        assertFalse(source.contains("persistSelectedScanLists"));
        assertTrue(replayLast.contains("this.current = this.lastHeard"));
        assertTrue(replayLast.contains("this.currentBuffer = this.lastHeardBuffer"));
        assertFalse(replayLast.contains("fetch("));
        assertTrue(source.contains("this.lastHeardBuffer = completedBuffer"));
        assertTrue(toggleHold.contains("this.current && this.currentBuffer"));
        assertTrue(avoidCurrent.contains("if (!this.current || !this.currentBuffer || this.replayingLast) return;"));
        assertTrue(avoidCurrent.contains("label: this.targetLabel(this.current)"));
        assertFalse(avoidCurrent.contains("details:"));
        assertFalse(avoidCurrent.contains("addedAtMs:"));
        assertFalse(avoidList.contains("avoid.details"));
        assertFalse(avoidList.contains("avoid.addedAtMs"));
        assertTrue(render.contains("this.replayingLast || (!this.holdTarget && !currentReady)"));
        assertTrue(render.contains("this.ui.avoid.disabled = !currentReady || this.replayingLast"));
        assertTrue(render.contains("this.ui.replay.disabled = !lastCallReady"));
    }

    @Test
    void movesAndSoftlyFadesTheCurrentCallProgressGlow() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(WEB_CALL_PLAYER);
        String css = readText(APP_CSS);
        String startCurrent = function(source, "  startCurrent()");
        String progress = function(source, "  renderProgress()");

        assertTrue(html.contains("id=\"playback-progress\" class=\"playback-progress\" aria-hidden=\"true\""));
        assertTrue(html.contains("id=\"playback-progress-glow\" class=\"playback-progress-glow\""));
        assertTrue(startCurrent.contains("source.start(0)"));
        assertFalse(source.contains("playbackOffset"));
        assertTrue(startCurrent.contains("this.startProgress()"));
        assertTrue(progress.contains("position / duration"));
        assertTrue(progress.contains("duration - position <= fadeWindow"));
        assertTrue(progress.contains("--playback-progress"));
        assertTrue(css.contains("left: calc(var(--playback-progress) * 100%);"));
        assertTrue(css.contains("background: linear-gradient(90deg"));
        assertTrue(css.contains("border-radius: 0;"));
        assertTrue(css.contains("transition: opacity 600ms ease;"));
        assertTrue(css.contains(".playback-progress.ending"));
        assertTrue(css.contains("transition-duration: 800ms;"));
        assertTrue(css.contains(".playback-progress.active"));
        assertTrue(css.contains("height: 6px;"));
        assertTrue(css.contains("left: calc(var(--playback-progress) * 100%);"));
    }

    @Test
    void pollsBoundedScanListsDeduplicatesCallsAndSchedulesOneBrowserQueue() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(WEB_CALL_PLAYER);
        String enqueue = function(source, "  enqueue(call)");
        String feedUrl = function(source, "  feedRequestUrl()");
        String synchronize = function(source, "  synchronizeSubscription()");
        String normalize = function(source, "  normalizeCall(value)");
        String schedule = function(source, "  nextQueueIndex(queue, lastKey, consecutive)");
        String subscribeState = function(source, "  subscribeState(observer)");

        assertTrue(html.contains("id=\"playback-scan-list-options\""));
        assertFalse(html.contains("id=\"playback-missed\""));
        assertTrue(feedUrl.contains("query.append('scan_list_id', id)"));
        assertTrue(feedUrl.contains("query.set('cursor', this.feedCursor)"));
        assertTrue(function(source, "  activeSelectedScanListIds()")
            .contains("this.scanListById.get(id)?.enabled"));
        assertTrue(synchronize.contains("this.stopFeed()"));
        assertFalse(source.contains("maximum_selected_scan_lists"));
        assertFalse(source.contains("waiting_calls_per_listener"));
        assertFalse(function(source, "  setScanListSelected(id, selected)").contains("togglePlayback()"));
        assertTrue(normalize.contains("typeof value.call_id === 'string'"));
        assertTrue(normalize.contains("Array.isArray(value.scan_list_ids)"));
        assertTrue(normalize.contains("scanListIds.map(String)"));
        assertFalse(normalize.contains("value.scan_list_ids.map(Number)"));
        assertFalse(normalize.contains("logical_call_id"));
        assertFalse(normalize.contains("matched_scan_list_ids"));
        assertFalse(normalize.contains("selected_scan_list_ids"));
        assertFalse(normalize.contains("start_timestamp_ms"));
        assertTrue(enqueue.contains("this.seenCallIds.has(normalized._callId)"));
        assertTrue(enqueue.indexOf("callMatchesSelection(normalized)") <
            enqueue.indexOf("rememberCallId(normalized._callId)"));
        assertTrue(source.contains("MAXIMUM_SEEN_CALL_IDS = 2048"));
        assertTrue(source.contains("MAXIMUM_QUEUED_CALLS = 100"));
        assertTrue(source.contains("MAXIMUM_AVOIDS = 256"));
        assertTrue(schedule.contains("consecutive < this.conversationBurstLimit"));
        assertTrue(schedule.contains("!this.conversationGrouping"));
        assertTrue(source.contains("first._startedAtMs - second._startedAtMs"));
        assertTrue(normalize.contains("typeof value.conversation_key !== 'string'"));
        assertTrue(normalize.contains("value.conversation_key.trim()"));
        assertFalse(source.contains("conversationKey(call)"));
        assertFalse(source.contains("recentCalls"));
        assertFalse(source.contains("recentReplay"));
        assertFalse(source.contains("conversationLanes"));
        assertTrue(subscribeState.contains("return () => this.stateObservers.delete(observer)"));
    }

    @Test
    void providesOneResponsiveScannerShellWithoutAdministratorAudioControls() throws Exception
    {
        String html = Files.readString(INDEX_HTML);
        String source = source();
        String css = Files.readString(APP_CSS);
        String scanner = function(source, "function renderScanner()");
        String scannerCall = function(source, "function renderScannerCall(host, state, site)");
        String networkSite = function(source, "function scannerNetworkSiteIdentity(call)");
        String callQuality = function(source, "function scannerCallQuality(call)");
        String voiceMeter = function(source, "function scannerVoiceMeter(call)");
        String configuration = function(source, "async function renderConfiguration()");
        String scanLists = function(source, "async function renderAdminScanLists()");

        assertTrue(html.contains("id=\"navigation-toggle\""));
        assertTrue(html.contains("id=\"navigation-backdrop\""));
        assertTrue(html.contains("data-nav-group=\"listen\""));
        assertTrue(html.contains("data-view=\"scanner\""));
        assertTrue(html.contains("id=\"playback-bar\""));
        assertTrue(html.contains("id=\"playback-avoid-list\""));
        assertTrue(html.contains("id=\"playback-clear-queue\""));
        assertTrue(html.contains("id=\"icon-play\""));
        assertTrue(html.contains("id=\"icon-stop\""));
        assertTrue(html.contains("id=\"icon-skip\""));
        assertTrue(html.contains("id=\"icon-clear-queue\""));
        assertTrue(html.contains("id=\"playback-hold\"") && html.contains(">H</button>"));
        assertTrue(html.contains("id=\"playback-avoid\"") && html.contains(">A</button>"));
        assertTrue(html.contains("class=\"playback-command-group\""));
        assertTrue(html.contains("class=\"playback-scan-list-label\">Scan Lists:</span>"));
        assertFalse(html.contains("Choose scan lists"));
        assertFalse(html.contains("class=\"playback-field\">Now</span>"));
        assertFalse(html.contains("id=\"playback-clear\""));
        assertTrue(scanner.contains("scanner-player-host"));
        assertTrue(scanner.contains("scanner-chassis"));
        assertTrue(scanner.contains("Simple"));
        assertTrue(scanner.contains("Normal"));
        assertTrue(scanner.contains("Advanced"));
        assertTrue(scanner.contains("Engineer"));
        assertTrue(scanner.contains("Avoid List"));
        assertFalse(scanner.contains("Recent Calls"));
        assertTrue(scanner.contains("Replay Last Call"));
        assertTrue(scanner.contains("Clear Queue"));
        assertTrue(scanner.contains("View coverage tree"));
        assertTrue(source.contains("scannerParticipant('Target'"));
        assertTrue(source.contains("scannerParticipant('Source'"));
        assertTrue(scannerCall.contains("scannerField('Network / Site'"));
        assertTrue(source.contains("scannerField('Frequency'"));
        assertTrue(source.contains("scannerField('Modulation'"));
        assertTrue(source.contains("function scannerVoiceMeter(call)"));
        assertTrue(source.contains("function scannerParticipant("));
        assertTrue(source.contains("scannerField('Matched Scan Lists'"));
        assertTrue(networkSite.contains("`${wacn}-${system}`"));
        assertTrue(networkSite.contains("`${rfss}-${site}`"));
        assertTrue(networkSite.contains(".join(' · ')"));
        assertTrue(networkSite.contains("scannerHex(call?.wacn, 5)"));
        assertTrue(networkSite.contains("scannerHex(call?.system_id, 3)"));
        assertTrue(networkSite.contains("scannerHex(call?.rfss_id, 2)"));
        assertTrue(networkSite.contains("scannerHex(call?.site_id, 2)"));
        assertTrue(scannerCall.contains("engineer.append(scannerCallQuality(call))"));
        for(String field: List.of("Decoded", "Repeated", "Concealed", "Missing", "FEC Errors", "FEC Protected"))
        {
            assertTrue(callQuality.contains("['" + field + "'"), () -> "Missing grouped quality field " + field);
        }
        for(String removed: List.of("Configuration ID", "Channel Identity", "System Identity", "Site Identity",
            "Site GUID", "Completed"))
        {
            assertFalse(scannerCall.contains("['" + removed + "'"), () -> "Scanner still displays " + removed);
        }
        assertFalse(scannerCall.contains("scannerField('Identifier'"));
        assertFalse(scanner.contains("Squelch"));
        assertFalse(scanner.contains("Tune"));
        assertFalse(scanner.contains("RF Signal"));
        assertTrue(css.contains(".scanner-player-host > .playback-bar {"));
        assertTrue(css.contains(".scanner-chassis {"));
        assertTrue(css.contains(".scanner-field-grid {"));
        assertTrue(css.contains("height: clamp(380px, 46vh, 460px);"));
        assertTrue(css.contains("scrollbar-gutter: stable;"));
        assertTrue(css.contains(".scanner-idle {\n  height: 100%;"));
        assertTrue(css.contains(".scanner-call-quality-values {"));
        assertFalse(css.contains(".scanner-quality-meter {"));
        assertTrue(css.contains(".scanner-quality-bars {"));
        assertFalse(voiceMeter.contains("Voice Quality"));
        assertFalse(voiceMeter.contains("Measured from decoded voice frames"));
        assertFalse(voiceMeter.contains("node('strong'"));
        assertTrue(scannerCall.contains("for (let index = 0; index < 24; index++)"));
        assertTrue(scannerCall.contains("node('div', 'scanner-call-instruments')"));
        assertTrue(scannerCall.contains("intro.append(copy, instruments)"));
        assertTrue(scannerCall.contains("host.dataset.renderKey === renderKey"));
        assertTrue(scanner.contains("player.readAudioWaveform(waveformLevels)"));
        assertTrue(scanner.contains("window.cancelAnimationFrame(waveformFrame)"));
        assertFalse(css.contains("@keyframes scanner-audio-wave"));
        assertTrue(css.contains("linear-gradient(145deg, #e8e2bd 0%, #d4d1b1 50%, #eee8c5 100%)"));
        assertTrue(css.contains("grid-template-columns: minmax(0, 1fr) minmax(140px, 320px);"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] #content .scanner-display {"));
        assertFalse(css.contains(".scanner-quality-track {"));
        assertTrue(css.contains(".scanner-participant-grid {"));
        assertTrue(css.contains("font-size: 14px;"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] #content .scanner-key {"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] #content .scanner-scan-button.active {"));
        assertTrue(css.contains("body[data-view=\"scanner\"] .page-header {"));
        assertTrue(css.contains("flex: 1 0 100%;"));
        assertTrue(source.contains("restorePlaybackBarBeforeRender();"));
        assertTrue(function(source, "function placePlaybackBar()")
            .contains("(scannerHost || slot).append(bar)"));
        assertTrue(function(source, "function initializePlaybackHeader()")
            .contains("!panel.contains(event.target)"));
        assertTrue(css.contains("@media (max-width: 1180px)"));
        assertTrue(css.contains("body.navigation-open .primary-nav"));

        assertTrue(html.contains("view=configuration&amp;tab=scan-lists"));
        assertTrue(configuration.contains("id: 'scan-lists', label: 'Scan Lists'"));
        assertTrue(configuration.contains("await renderAdminScanLists()"));
        assertTrue(scanLists.contains("requestJson('/api/v1/admin/scan-lists'"));
        assertTrue(scanLists.contains("'No scan lists are configured'"));
        assertTrue(scanLists.contains("unmatched_alias_list_count"));
        assertTrue(scanLists.contains("'Alias List Defaults'"));
        assertTrue(scanLists.contains("Alias List\\'s Alias List Defaults"));
        String editScanList = function(source, "function openScanListAdminModal(scanList, revision)");
        String deleteScanList = function(source, "function openDeleteScanListAdminModal(scanList, revision)");
        assertTrue(deleteScanList.contains("unmatched_alias_list_count"));
        assertTrue(deleteScanList.contains("Alias List Defaults"));
        assertTrue(source.contains("/api/v1/admin/scan-lists/${scanList.id}"));
        assertTrue(editScanList.contains("await refreshPlaybackScanLists(true)"));
        assertTrue(deleteScanList.contains("await refreshPlaybackScanLists(true)"));
        assertFalse(editScanList.contains("location.reload"));
        assertFalse(deleteScanList.contains("location.reload"));
        assertFalse(source.contains("/api/v1/admin/web-audio"));
        assertFalse(source.contains("renderAdminWebAudio"));
        assertFalse(source.contains("label: 'Listener Status'"));
        assertFalse(html.contains("playback-max-queued"));
    }

    @Test
    void batchesLiveOnlyEventAndMessageCaptureBeforeFilteringAndRendering() throws Exception
    {
        String source = source();
        String css = readText(APP_CSS);
        String catalog = function(source, "function liveDetailFilterCatalog(value)");
        String model = function(source, "function liveDetailFilterModel(options = {})");
        String filters = function(source, "function liveDetailFilterController(options)");
        String modal = function(source, "function openReadOnlyModal(title, body, options = {})");
        String messages = function(source, "function liveMessagesPane()");
        String events = function(source, "function liveEventsPanel(onCollapse)");
        String addMessage = function(source, "  const addMessage = (message) =>");
        String addEvent = function(source, "  const addEvent = (event) =>");

        assertTrue(source.contains("LIVE_DETAIL_REFRESH_INTERVAL_MILLISECONDS = 125"));
        assertTrue(messages.contains("window.setTimeout"));
        assertTrue(events.contains("window.setTimeout"));
        assertFalse(messages.contains("window.requestAnimationFrame"));
        assertFalse(events.contains("window.requestAnimationFrame"));
        assertFalse(source.contains("liveDetailSynchronizeObservedSelect"));
        assertFalse(messages.contains("observedProtocols"));
        assertFalse(events.contains("observedEventTypes"));
        assertFalse(addMessage.contains("replaceChildren"));
        assertFalse(addEvent.contains("replaceChildren"));
        assertTrue(messages.contains(".filter((message) => message && matches(message))"));
        assertTrue(events.contains(".filter((event) => event && eventMatches(event))"));
        assertTrue(messages.contains(".slice(0, liveDetailMatchingRowLimit())"));
        assertTrue(events.contains(".slice(0, liveDetailMatchingRowLimit())"));
        assertTrue(catalog.contains("Array.isArray(value.groups)"));
        assertTrue(catalog.contains("candidate.children"));
        assertTrue(catalog.contains("value.timeslots"));
        assertTrue(filters.contains("openReadOnlyModal("));
        assertTrue(filters.contains("className: 'live-filter-modal'"));
        assertTrue(filters.contains("returnFocusSelector:"));
        assertTrue(filters.contains("trigger.setAttribute('aria-haspopup', 'dialog')"));
        assertTrue(filters.contains("tree.setAttribute('role', 'tree')"));
        assertTrue(filters.contains("input.indeterminate"));
        assertTrue(filters.contains("`${selected}/${leafKeys.length}`"));
        assertTrue(model.contains("catalog?.signature === next.signature"));
        assertTrue(model.contains("if (!next) return 'ignored'"));
        assertTrue(model.contains("excludedLeafKeys"));
        assertFalse(model.contains("resetForSelection"));
        assertTrue(messages.contains("message.filter_key"));
        assertTrue(messages.contains("message.filter_label"));
        assertTrue(events.contains("filters.matchesLeaf(event.event_type)"));
        assertTrue(messages.contains("addEventListener('live_gap'"));
        assertTrue(events.contains("addEventListener('live_gap'"));
        assertTrue(messages.contains("addEventListener('source_change'"));
        assertTrue(messages.contains("filters.setCatalog(change?.filter_catalog)"));
        assertTrue(messages.contains(
            "liveMessageSourceMatchesSelection(selection, expectedSubscriptionId, change)"));
        assertTrue(messages.contains("expectedSubscriptionId = randomLiveClientId()"));
        assertTrue(messages.contains("parameters.subscription_id = expectedSubscriptionId"));
        assertTrue(source.contains(
            "function liveMessageSourceMatchesSelection(selection, subscriptionId, source)"));
        assertTrue(messages.contains("liveDetailSelectionDelta(selection, nextSelection)"));
        assertFalse(messages.contains("filters.resetForSelection"));
        assertFalse(events.contains("addEventListener('filter_catalog'"));
        assertTrue(events.contains("addEventListener('source_change'"));
        assertTrue(events.contains("liveEventScopeMatchesSelection(selection, expectedSubscriptionId, source)"));
        assertTrue(events.contains("const subscriptionId = randomLiveClientId()"));
        assertTrue(events.contains("parameters.subscription_id = subscriptionId"));
        assertTrue(events.contains("epoch !== streamEpoch || !transportReady"));
        assertTrue(events.contains("epoch === streamEpoch && transportReady"));
        assertTrue(messages.contains("stream.onopen = () =>"));
        assertTrue(events.contains("stream.onopen = () =>"));
        assertTrue(messages.contains("additional messages may have been missed"));
        assertTrue(events.contains("additional events may have been missed"));
        assertFalse(messages.contains("parameters.timeslot"));
        assertFalse(messages.contains("addEventListener('snapshot'"));
        assertFalse(events.contains("addEventListener('snapshot'"));
        assertTrue(messages.contains("stream.update(parameters)"));
        assertFalse(events.contains("stream.update("));
        assertTrue(css.contains(".read-only-modal.live-filter-modal"));
        assertTrue(css.contains(".live-filter-tree"));
        assertTrue(css.contains(".live-filter-settings"));
        assertTrue(css.contains(".live-filter-tree {\n    max-height: none;"));
        assertTrue(modal.contains("dialog.setAttribute('aria-modal', 'true')"));
        assertTrue(modal.contains("if (event.key === 'Escape')"));
        assertTrue(modal.contains("if (event.target === backdrop) dismiss()"));
        assertTrue(modal.contains("returnFocusSelector"));
    }

    @Test
    void separatesLiveTabSignalStrengthFromDecodeQuality() throws Exception
    {
        String source = source();
        String level = function(source, "function signalBarLevel(value)");
        String live = function(source, "function liveSystemsSection(onSelectionChange)");
        assertTrue(level.contains("signal >= -65"));
        assertTrue(level.contains("signal >= -75"));
        assertTrue(level.contains("signal >= -85"));
        assertTrue(live.contains("const level = signalBarLevel(signalStrength)"));
        assertTrue(live.contains("const state = decodeQuality === null ? 'unavailable'"));
        assertTrue(live.contains("dBFS signal strength"));
        assertTrue(live.contains("% decode quality"));
        assertFalse(live.contains("Math.ceil(decodeQuality / 25)"));
    }

    @Test
    void liveRowsLinkToAliasAndReceiverEditorsAndDismissOnlyStoppedChannels() throws Exception
    {
        String source = source();
        String css = readText(APP_CSS);
        String existingAlias = function(source, "function liveExistingAliasHref(reference)");
        String draftAlias = function(source, "function liveAliasDraftHref(row, kind)");
        String routedPrefill = function(source, "function routedAliasPrefill(selectedList, options)");
        String conventional = function(source, "function liveConventionalChannelValue(row)");
        String systems = function(source, "function liveSystemsSection(onSelectionChange)");
        String upsert = function(systems, "const upsertTable = (value) =>");
        String rowRenderer = function(source,
            "function renderTableRow(data, columns, rowKey, rowClass, onRowClick)");

        assertTrue(existingAlias.contains("list: Number(reference.alias_list_id)"));
        assertTrue(existingAlias.contains("alias: Number(reference.alias_id)"));
        assertTrue(draftAlias.contains("createAlias: 1"));
        assertTrue(draftAlias.contains("createListName: aliasListName"));
        assertTrue(draftAlias.contains("createType: type"));
        assertTrue(draftAlias.contains("createProtocol: protocol"));
        assertTrue(draftAlias.contains("createValue: value"));
        assertTrue(routedPrefill.contains("aliasMatcherDescriptor(options, type, protocol, variant)"));
        assertTrue(routedPrefill.contains("selectedList.unmatched_talkgroup_policy"));
        assertTrue(conventional.contains("entityRefHref(row?.entity_ref)"));
        assertFalse(conventional.contains("context_key"));
        assertTrue(upsert.contains("entityTarget(value.entity_ref)"));
        assertTrue(upsert.contains("dismissedStoppedTables.has(value.table_id)"));
        assertTrue(upsert.contains("value.channel_running !== true"));
        assertTrue(upsert.contains("current.channel_running !== false"));
        assertTrue(systems.contains("type: 'live-systems'"));
        assertTrue(systems.contains("onRowClick: (row) =>"));
        assertTrue(rowRenderer.contains("event.target.closest('a, button, input, select, textarea, label')"));
        assertTrue(upsert.contains("quality.classList.toggle('quality-link'"));
        assertTrue(upsert.contains("select.classList.toggle('quality-link'"));
        assertTrue(css.contains(".systems-tab-close"));
        assertTrue(css.contains(".systems-live-tab.stopped .systems-tab-quality"));
        assertTrue(css.contains(".systems-tab-select:hover .systems-tab-quality.quality-link span"));
        assertTrue(css.contains(".systems-tab-select.quality-link:hover"));
    }

    @Test
    void splitsLiveDetailsAndScopesBoundedDecoderEventsToTheCurrentSelection() throws Exception
    {
        String source = source();
        String css = readText(APP_CSS);
        String selection = function(source, "function liveDetailSelection(tableValue, row, bindingRow = row)");
        String rowSelection = function(source, "function liveDetailRowSelection(tableValue, row)");
        String events = function(source, "function liveEventsPanel(onCollapse)");
        String messages = function(source, "function liveMessagesPane()");
        String channel = function(source, "function liveChannelPane()");
        String systems = function(source, "function liveSystemsSection(onSelectionChange)");
        String showTable = function(systems, "const showTable = (tableId) =>");
        String updateVisibleRows = function(systems, "const updateVisibleRows = (value) =>");
        String live = function(source, "async function renderLive()");
        String html = readText(INDEX_HTML);

        assertTrue(live.contains("node('div', 'live-split')"));
        assertTrue(live.contains("liveSystemsSection(eventsPanel.select)"));
        assertTrue(systems.contains("node('div', 'section-title-actions live-systems-title-actions')"));
        assertTrue(systems.contains("layoutMenuHost: titleActions"));
        assertTrue(systems.contains("iconGlyph('icon-live-presentation')"));
        assertTrue(systems.contains("openLivePresentationSettings('#live-presentation-settings')"));
        assertTrue(systems.contains("section('Live Systems', host, titleActions)"));
        assertFalse(events.contains("layoutMenuHost"));
        assertFalse(messages.contains("layoutMenuHost"));
        assertTrue(html.contains("id=\"icon-columns\""));
        assertTrue(html.contains("id=\"icon-live-presentation\""));
        assertTrue(events.contains("['events', 'messages', 'channel']"));
        assertTrue(events.contains("liveMessagesPane()"));
        assertTrue(events.contains("liveChannelPane()"));
        assertTrue(events.contains("liveConnection('decode_events', parameters)"));
        assertTrue(events.contains("configuration_id: selection.configurationId"));
        assertTrue(events.contains("selection.kind === LIVE_DETAIL_SELECTION_KINDS.EXACT"));
        assertTrue(events.contains("parameters.frequency_hz = selection.bindingFrequencyHz"));
        assertTrue(events.contains("parameters.timeslot = selection.bindingTimeslot"));
        assertFalse(events.contains("stream.addEventListener('snapshot'"));
        assertTrue(events.contains("stream.addEventListener('decode_event'"));
        assertTrue(events.contains("liveEventMatchesSelection(selection, value)"));
        assertTrue(source.contains("function liveEventMatchesSelection(selection, event)"));
        assertTrue(source.contains(
            "function liveEventScopeMatchesSelection(selection, subscriptionId, source)"));
        assertTrue(source.contains(
            "function liveChannelStateMatchesSelection(selection, subscriptionId, source)"));
        assertTrue(source.contains("event?.frequency_hz"));
        assertTrue(source.contains("event?.timeslot"));
        assertTrue(events.contains("liveDetailSelectionDelta(selection, nextSelection)"));
        assertFalse(events.contains("filters.resetForSelection"));
        assertTrue(events.contains("if (!events.has(event.event_id)) order.unshift(event.event_id)"));
        assertTrue(events.contains("while (order.length > liveDetailCaptureLimit())"));
        assertTrue(events.contains("stream.addEventListener('live_gap'"));
        assertTrue(events.contains("stream.addEventListener('source_change'"));
        assertTrue(events.contains("filters.setCatalog(source?.filter_catalog)"));
        assertFalse(events.contains("liveDetailSelect('Protocol'"));
        assertFalse(messages.contains("liveDetailSelect('Protocol'"));
        for(String column: new String[]{"time", "duration", "event", "from", "to", "channel", "details"})
        {
            assertTrue(events.contains("id: '" + column + "'"), () -> "Missing live event column " + column);
        }
        assertTrue(events.contains("type: 'live-events'"));
        assertTrue(events.contains("rowClass: (event) => liveEventCategoryClass(event.category)"));
        assertTrue(events.contains("eventsTable.tableController.replaceRows"));
        assertTrue(events.contains("node('strong', 'live-event-duration-value', text)"));
        assertTrue(messages.contains("liveConnection('decode_messages', parameters)"));
        assertTrue(messages.contains("stream.addEventListener('decode_message'"));
        assertTrue(messages.contains("active && !collapsed && !document.hidden && selection?.configurationId"));
        assertTrue(messages.contains("type: 'live-messages'"));
        assertTrue(messages.contains("messagesTable.tableController.replaceRows"));
        assertTrue(channel.contains("binaryFrameConnection('channel_diagnostics', parameters"));
        assertTrue(channel.contains("expectedSubscriptionId = randomLiveClientId()"));
        assertTrue(channel.contains("parameters.subscription_id = expectedSubscriptionId"));
        assertTrue(channel.contains(
            "liveChannelStateMatchesSelection(selection, expectedSubscriptionId, nextState)"));
        assertEquals(channel.indexOf("binaryFrameConnection('channel_diagnostics', parameters"),
            channel.lastIndexOf("binaryFrameConnection('channel_diagnostics', parameters"));
        assertTrue(channel.contains("frame.type === DIAGNOSTIC_FRAME_TYPES.CHANNEL_SIGNAL"));
        assertTrue(channel.contains("DIAGNOSTIC_FRAME_TYPES.CHANNEL_SYMBOLS"));
        assertFalse(channel.contains("client_id"));
        assertTrue(channel.contains("diagnosticGrid.append(signalDiagnostic.card, symbolDiagnostic.card)"));
        assertTrue(channel.contains("let signalSequence = 0"));
        assertTrue(channel.contains("['Center'"));
        assertTrue(channel.contains("['Peak'"));
        assertTrue(channel.contains("['Decoder', state?.decoder_profile || state?.protocol || '—']"));
        assertFalse(channel.contains("['Span'"));
        assertFalse(channel.contains("['Bins'"));
        assertFalse(channel.contains("['Selected TS'"));
        assertFalse(channel.contains("['Visible'"));
        assertFalse(channel.contains("['Range'"));
        assertFalse(channel.contains("['Age'"));
        assertFalse(channel.contains("ageText"));
        assertTrue(channel.contains("symbolDiagnostic"));
        assertTrue(channel.contains("symbolValues"));
        assertTrue(channel.contains("['FFT', 'Waterfall']"));
        assertTrue(channel.contains("signalViewToggle.setAttribute('aria-label', 'Signal graph view')"));
        assertTrue(channel.contains("button.setAttribute('aria-pressed'"));
        assertTrue(channel.contains("const waterfallBuffer = document.createElement('canvas')"));
        assertTrue(channel.contains("const addWaterfallFrame = (values) =>"));
        assertTrue(channel.contains("nextWaterfallRow = (nextWaterfallRow - 1 + waterfallBuffer.height)"));
        assertTrue(channel.contains("addWaterfallFrame(signalValues)"));
        assertTrue(channel.contains("Connection interrupted. Reconnecting…"));
        assertTrue(channel.contains("stream.update(parameters)"));
        assertTrue(channel.contains("liveDetailSelectionDelta(selection, nextSelection)"));
        assertTrue(channel.contains("if (awaitingState) return"));
        assertTrue(channel.contains(
            "liveChannelStateMatchesSelection(selection, expectedSubscriptionId, nextState)"));
        assertTrue(source.contains("Number(source?.frequency_hz || 0) === " +
            "Number(selection?.bindingFrequencyHz || 0)"));
        assertFalse(channel.contains("window.setInterval"));
        assertFalse(channel.contains("window.clearInterval(ageTimer)"));
        assertTrue(channel.contains("active && !collapsed && !paused && !document.hidden"));
        assertFalse(channel.contains("channel-mode-tabs"));
        assertFalse(channel.contains("view: mode"));
        assertTrue(selection.contains("row?.configuration_id || tableValue?.configuration_id"));
        assertTrue(source.contains("LIVE_DETAIL_CONTROL_ROLES = new Set(['CONFIGURED_CONTROL', " +
            "'CURRENT_CONTROL', 'ALTERNATE_CONTROL'])"));
        assertTrue(selection.contains("LIVE_DETAIL_SELECTION_KINDS.CONTROL"));
        assertTrue(selection.contains("bindingFrequencyHz"));
        assertTrue(selection.contains("TS ${bindingTimeslot}"));
        assertTrue(selection.contains("logicalKey:"));
        assertTrue(selection.contains("transportKey:"));
        assertTrue(selection.contains("rowKey: resolvedRow?.key || null"));
        assertTrue(rowSelection.contains("tableValue?.control_active === true"));
        assertTrue(rowSelection.contains("liveCurrentControlRow(tableValue)"));
        assertTrue(systems.contains(
            "const currentControl = displayed.control_active ? liveCurrentControlRow(displayed) : null"));
        assertTrue(systems.contains("liveDetailRowSelection(value, row)"));
        assertTrue(systems.contains("onSelectionChange(selection)"));
        assertFalse(systems.contains("selectedRowKey"));
        assertTrue(source.contains("function liveCurrentControlRow(tableValue)"));
        assertTrue(systems.contains("type: 'live-systems'"));
        assertTrue(systems.contains("onRowClick: (row) =>"));
        assertTrue(systems.contains("liveTable.tableController.replaceRows"));
        assertTrue(showTable.contains("displayed.control_active ? liveCurrentControlRow(displayed) : null"));
        assertTrue(showTable.contains("selectRow(displayed, currentControl)"));
        assertTrue(updateVisibleRows.contains("liveCurrentControlRow(displayed)"));
        assertTrue(updateVisibleRows.contains("selection?.kind === LIVE_DETAIL_SELECTION_KINDS.CONTROL"));
        assertTrue(updateVisibleRows.contains("liveDetailSelection(displayed, controlIntent, null)"));
        assertTrue(css.contains("grid-template-rows: minmax(0, 1fr) minmax(0, 1fr)"));
        assertTrue(css.contains(".live-split.details-collapsed"));
        assertTrue(css.contains(".live-details.collapsed .live-details-body"));
        assertTrue(css.contains(".live-events-table tbody tr:hover"));
        assertTrue(css.contains(".live-event-category-voice"));
        assertTrue(css.contains(".live-event-category-encrypted-voice"));
        assertTrue(css.contains(".live-event-category-data"));
        assertTrue(css.contains(".live-event-category-command"));
        assertTrue(css.contains(".live-event-category-registration"));
        assertTrue(css.contains(".live-event-category-other"));
        assertTrue(css.contains(".live-event-duration-value"));
        assertTrue(css.contains("border: 1px solid var(--live-event-accent);"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] .live-event-category-voice"));
        assertTrue(css.contains(".channel-diagnostic-canvas"));
        assertTrue(css.contains(".channel-diagnostic-view-toggle"));
        assertTrue(css.contains(".channel-diagnostic-view-button[aria-pressed=\"true\"]"));
        assertTrue(css.contains(":not(.live-details-tab):not(.channel-diagnostic-view-button)"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] .channel-diagnostic-view-button[aria-pressed=\"true\"]"));
        assertTrue(css.contains(".channel-diagnostic-view-button:hover {\n  color: var(--ink);"));
        assertTrue(css.contains(".channel-diagnostic-grid {"));
        assertTrue(css.contains("grid-template-columns: minmax(0, 1fr)"));
        assertTrue(css.contains(".channel-diagnostic-grid"));
    }

    @Test
    void opensDemandDrivenTunerSpectrumAndWaterfallOnDedicatedPage() throws Exception
    {
        String source = source();
        String binary = function(source, "function binaryFrameConnection(topic, parameters = {}, callbacks = {})");
        String diagnostic = function(source, "function decodeDiagnosticFrame(encoded)");
        String activity = function(source, "function synchronizeLiveChannelActivitySource()");
        String subscribeActivity = function(source, "function subscribeLiveChannelActivity(callbacks = {})");
        String frequencyMapping = function(source, "function tunerFrequencyAtBin(domain, coordinate)");
        String inverseFrequencyMapping = function(source, "function tunerBinAtFrequency(domain, frequencyHz)");
        String snapper = function(source, "function tunerSnapFrequency(frequencyHz)");
        String tuner = function(source, "function tunerSpectrumPanel()");
        String parameters = function(tuner, "function diagnosticParameters()");
        String refinement = function(tuner, "function queueViewportUpdate(immediate = false)");
        String pointerMove = function(tuner, "function onPlotPointerMove(event)");
        String acceptState = function(tuner, "function acceptTunerState(frame)");
        String acceptFrame = function(tuner, "function acceptTunerFrame(frame)");
        String visibleValues = function(tuner, "function visibleSpectrumValues(useSmoothing = true)");
        String visibleValuesFor = function(tuner, "function visibleValuesFor(values, metadata)");
        String live = function(source, "async function renderLive()");
        String systems = function(source, "function liveSystemsSection(onSelectionChange)");
        String css = readText(APP_CSS);

        String tunerPage = function(source, "async function renderTunerSpectrum()");
        String html = readText(INDEX_HTML);

        assertFalse(live.contains("'Tuner Spectrum'"));
        assertTrue(html.contains("data-view=\"tuner-spectrum\""));
        assertTrue(source.contains("TUNER_SPECTRUM: 'tuner-spectrum'"));
        assertTrue(readText(Path.of("stats-web", "assets", "core", "routes.js"))
            .contains("id: 'tuner-spectrum', label: 'Tuner Spectrum', title: 'Tuner Spectrum', " +
                "parent: 'tuner-spectrum', access: 'admin-tuner'"));
        assertTrue(tunerPage.contains("pageConnections.add(spectrum)"));
        assertTrue(tunerPage.contains("pageHeader('Tuner Spectrum'"));
        assertFalse(tuner.contains("openReadOnlyModal('Tuner Spectrum'"));
        assertTrue(tuner.contains("api('/api/v1/diagnostics/tuners')"));
        assertTrue(tuner.contains("binaryFrameConnection('tuner_diagnostics'"));
        assertTrue(tuner.contains("frame.type !== DIAGNOSTIC_FRAME_TYPES.TUNER_FFT"));
        assertTrue(tuner.contains("const shouldRun = () => !disposed && !paused && !document.hidden"));
        assertTrue(tuner.contains("const waterfallBuffer = document.createElement('canvas')"));
        assertTrue(tuner.contains("const firstBin ="));
        assertTrue(tuner.contains("for (let bin = firstBin; bin < lastBin; bin += 1)"));
        assertTrue(tuner.contains("nextWaterfallRow = (nextWaterfallRow - 1 + waterfallBuffer.height)"));
        assertTrue(tuner.contains("close: () =>"));
        assertTrue(tuner.contains("closeStreams()"));
        assertTrue(parameters.contains("zoomAmount() > 1.0001"));
        assertTrue(parameters.contains("parameters.viewport_start_hz"));
        assertTrue(parameters.contains("parameters.viewport_end_hz"));
        assertTrue(source.contains("const TUNER_SPECTRUM_VIEWPORT_DEBOUNCE_MS = 160"));
        assertTrue(source.contains("const TUNER_SPECTRUM_MAXIMUM_ZOOM = 64"));
        assertFalse(source.contains("TUNER_SPECTRUM_MAXIMUM_ANALYTICAL_ZOOM"));
        assertTrue(tuner.contains("'Spectrum performance'"));
        assertTrue(tuner.contains("'Efficient · 2,048 bins / 5 FPS'"));
        assertTrue(tuner.contains("'Balanced · 8,192 bins / 10 FPS'"));
        assertTrue(tuner.contains("'High detail · 16,384 bins / 20 FPS'"));
        assertTrue(tuner.contains("'Maximum detail · 32,768 bins / 20 FPS'"));
        assertTrue(tuner.contains("All profiles use 8-bit spectrum data."));
        assertTrue(parameters.contains("profile: spectrumProfile"));
        assertTrue(acceptState.contains("Object.hasOwn(TUNER_SPECTRUM_PROFILES, acceptedProfile)"));
        assertFalse(source.contains("_STORAGE_KEY"));
        assertFalse(source.contains("localStorage"));
        assertTrue(source.contains("TUNER_SPECTRUM_PROFILE_PREFERENCE = 'profile'"));
        assertTrue(tuner.contains("storeTunerChoice(TUNER_SPECTRUM_PROFILE_PREFERENCE, spectrumProfile)"));
        assertTrue(tuner.contains("profileSelect.addEventListener('change', applySelectedProfile)"));
        assertFalse(tuner.contains("Temporary spectrum experiment"));
        assertFalse(tuner.contains("Reset measurement"));
        assertFalse(parameters.contains("experiment_"));
        assertFalse(tuner.contains("resetExperimentMeasurement"));
        assertTrue(css.contains(".tuner-spectrum-profile {"));
        assertTrue(tuner.contains("'Zoom in'"));
        assertTrue(tuner.contains("'Zoom out'"));
        assertTrue(refinement.contains("stream.update(diagnosticParameters())"));
        assertTrue(refinement.contains("awaitingViewportState = true"));
        assertTrue(refinement.contains("window.setTimeout"));
        assertTrue(refinement.contains("immediate ? 0 : TUNER_SPECTRUM_VIEWPORT_DEBOUNCE_MS"));
        assertFalse(refinement.contains("closeStreams();"));
        assertFalse(refinement.contains("await closed;"));
        assertTrue(tuner.contains("let streamRelease = Promise.resolve()"));
        assertTrue(tuner.contains("Promise.all([streamRelease, releaseConnection(active)])"));
        assertFalse(refinement.contains("closePendingStream();"));
        assertFalse(tuner.contains("pendingStream"));
        assertFalse(tuner.contains("refiningStream"));
        assertFalse(tuner.contains("previous && previous !== candidate"));
        assertTrue(tuner.contains("addEventListener('wheel', onPlotWheel"));
        assertTrue(tuner.contains("addEventListener('pointermove', onPlotPointerMove"));
        assertTrue(tuner.contains("if (!stream && !drag) openDiagnosticStream();"));
        assertTrue(pointerMove.contains("if (!drag.moved)"));
        assertFalse(pointerMove.contains("closeStreams();"));
        assertTrue(acceptFrame.contains("drag?.moved"));
        assertTrue(tuner.contains("if (moved) queueViewportUpdate();"));
        assertTrue(refinement.contains("restoreWaterfallHistory();\n        drawWaterfall();"));
        assertTrue(tuner.contains("event.key === 'ArrowLeft'"));
        assertTrue(tuner.contains("event.key === 'r' || event.key === 'R'"));
        assertTrue(tuner.contains("connectActiveChannels()"));
        assertTrue(tuner.contains("if (!liveActivityAllowed || !shouldRun() || activeChannelSource) return"));
        assertTrue(tuner.contains("flagLegend.hidden = !liveActivityAllowed"));
        assertTrue(tuner.contains("'Channel markers require Live access.'"));
        assertTrue(tuner.contains("subscribeLiveChannelActivity({"));
        assertTrue(systems.contains("subscribeLiveChannelActivity({"));
        assertTrue(activity.contains("liveConnection('channel_activity', {}, false)"));
        assertTrue(activity.contains("liveChannelActivityTables"));
        assertTrue(subscribeActivity.contains("invokeLiveSubscriber(subscriber, 'snapshot'"));
        assertTrue(tuner.contains("const tableChannelName = String(table?.channel_name || '').trim()"));
        assertTrue(tuner.contains("const tableSystemName = String(table?.system_name || '').trim()"));
        assertTrue(tuner.contains("const tableSiteName = String(table?.site_name || '').trim()"));
        assertTrue(tuner.contains("add('System', activityValues(rows, (row) => row.tableSystemName))"));
        assertTrue(tuner.contains("add('Site', activityValues(rows, (row) => row.tableSiteName))"));
        assertTrue(tuner.contains("add('Channel', activityValues(rows, (row) => row.channel_name || row.tableChannelName))"));
        assertFalse(tuner.contains("`${row.tableChannelName} · Control`"));
        assertTrue(tuner.contains("snapInput.type = 'checkbox'"));
        assertTrue(tuner.contains("'tuner-spectrum-floor'"));
        assertTrue(tuner.contains("'tuner-spectrum-ceiling'"));
        assertTrue(tuner.contains("'Lower display limit'"));
        assertTrue(tuner.contains("'Upper display limit'"));
        assertTrue(tuner.contains("function updateDisplayRange(changedHandle = '', persist = false)"));
        assertTrue(tuner.contains("preferences.tuner.floor_db = dbFloor"));
        assertTrue(tuner.contains("preferences.tuner.ceiling_db = dbCeiling"));
        assertTrue(tuner.contains("Math.min(dbCeiling, raw)"));
        assertTrue(tuner.contains("(dbCeiling - value) / (dbCeiling - dbFloor)"));
        assertTrue(tuner.contains("(value - dbFloor) / (dbCeiling - dbFloor)"));
        assertTrue(tuner.contains("TUNER_WATERFALL_SPEED_PREFERENCE, waterfallSpeed"));
        assertTrue(tuner.contains("TUNER_SPECTRUM_SNAP_PREFERENCE, true"));
        assertTrue(tuner.contains("storeTunerBoolean(TUNER_SPECTRUM_SNAP_PREFERENCE, snapInput.checked)"));
        assertTrue(tuner.contains("'Snap frequency'"));
        assertTrue(tuner.contains("smoothInput.type = 'checkbox'"));
        assertTrue(tuner.contains("TUNER_SPECTRUM_SMOOTH_PREFERENCE, true"));
        assertTrue(tuner.contains("storeTunerBoolean(TUNER_SPECTRUM_SMOOTH_PREFERENCE, smoothInput.checked)"));
        assertTrue(tuner.contains("'Smooth FFT'"));
        assertTrue(tuner.contains("waterfallChannelsInput.type = 'checkbox'"));
        assertTrue(tuner.contains("TUNER_WATERFALL_CHANNELS_PREFERENCE, false"));
        assertTrue(tuner.contains("'Highlight channels on waterfall'"));
        assertTrue(tuner.contains("storeTunerChoice('session-target', targetSelect.value)"));
        assertTrue(tuner.contains("tunerStoredChoice('session-target', targets[0].id"));
        assertTrue(tuner.contains("toolbarActions.append(options)"));
        assertTrue(tuner.contains("optionsPanel.append(rangeControl, rangeHelp, speedControl, toggleControls, profilePanel)"));
        assertTrue(tuner.contains("optionsSummary.setAttribute('aria-expanded', 'false')"));
        assertTrue(tuner.contains("options.addEventListener('toggle'"));
        assertTrue(tuner.contains("displayControls.append(refiningBadge, flagLegend)"));
        assertFalse(tuner.contains("displayControls.append(options"));
        assertTrue(tuner.contains("const snap = snapInput.checked ? tunerSnapFrequency(pointerHz) : null"));
        assertTrue(tuner.contains("const displayHz = snap?.frequencyHz ?? pointerHz"));
        assertTrue(tuner.contains("cursorFrequency.textContent = `${(displayHz / 1_000_000).toFixed(6)} MHz`"));
        assertTrue(tuner.contains("cursorSnap.hidden = true"));
        assertTrue(tuner.contains("cursorSnap.textContent = ''"));
        assertTrue(tuner.contains("snapInput.addEventListener('change'"));
        assertTrue(tuner.contains("smoothInput.addEventListener('change'"));
        assertTrue(tuner.contains("function displayedSpectrumValues()"));
        assertTrue(tuner.contains("function updateSpectrumSmoothing(values, frame, domain)"));
        assertTrue(tuner.contains("previous + TUNER_SPECTRUM_SMOOTHING_ALPHA * (current - previous)"));
        assertTrue(tuner.contains("domain.rawBinWidthHz"));
        assertTrue(tuner.contains("domain.sourceBinCount, domain.transmittedBinCount"));
        assertTrue(tuner.contains("const spectrumValues = visibleSpectrumValues()"));
        assertTrue(tuner.contains("const value = displayedSpectrumValues()[index]"));
        assertTrue(tuner.contains("clearSpectrumSmoothing()"));
        assertTrue(source.contains("const TUNER_SPECTRUM_SMOOTHING_ALPHA = 0.25"));
        assertFalse(tuner.contains("cursorFrequency.textContent = `Pointer ${"));
        assertFalse(tuner.contains("`Snapped ${(guideHz / 1_000_000).toFixed(6)} MHz"));
        assertTrue(tuner.contains("waterfallObservedAtRows[nextWaterfallRow] = observedAtEpochMs"));
        assertTrue(tuner.contains("function activeCarrierDescription(carrier)"));
        assertTrue(tuner.contains("function activeCarrierFields(carrier, fftPower = null)"));
        assertTrue(tuner.contains("function activityAliasLabel(row, prefix)"));
        assertTrue(tuner.contains("`${prefix}_alias_description`"));
        assertTrue(tuner.contains("function renderActiveCarrierFields(carrier, power)"));
        assertTrue(tuner.contains("targetIdentifierLabel(form)"));
        assertTrue(tuner.contains("row.tableIdentifiers || []"));
        assertTrue(tuner.contains("row.source_form"));
        assertTrue(tuner.contains("row.target_form"));
        assertTrue(tuner.contains("row.talker_alias"));
        assertTrue(tuner.contains("row.signal_dbfs"));
        assertTrue(tuner.contains("row.decode_health_pct"));
        assertTrue(tuner.contains("row.vc_quality_pct"));
        assertTrue(tuner.contains("carrier.rows.push(decorated)"));
        assertTrue(tuner.contains("const spectrumActiveFlags = node('div', 'tuner-spectrum-active-flags')"));
        assertTrue(tuner.contains("const waterfallActiveFlags = node('div', 'tuner-spectrum-active-flags')"));
        assertTrue(tuner.contains("waterfall.host.insertBefore(waterfallActiveFlags, waterfall.guide)"));
        assertTrue(tuner.contains("`tuner-spectrum-active-flag status-${carrier.status.toLowerCase()}`"));
        assertTrue(tuner.contains("flag.style.left ="));
        assertTrue(tuner.contains("waterfallPlotWidth * TUNER_CHANNEL_VISUAL_BANDWIDTH_HZ / visibleSpanHz"));
        assertTrue(tuner.contains("if (waterfallLayer) flag.style.width"));
        assertTrue(tuner.contains("waterfallChannelsInput.checked ? createFlags(true) : []"));
        assertTrue(tuner.contains("waterfallActiveFlags.hidden = event.currentTarget !== waterfall.canvas"));
        assertTrue(tuner.contains("if (event.currentTarget === waterfall.canvas) waterfallActiveFlags.hidden = true"));
        assertTrue(tuner.contains("node(waterfallLayer ? 'span' : 'button'"));
        assertTrue(tuner.contains("if (!waterfallLayer) flag.type = 'button'"));
        assertTrue(tuner.contains("flag.addEventListener('pointerenter'"));
        assertTrue(tuner.contains("flag.addEventListener('focus'"));
        assertTrue(tuner.contains("showActiveFlag(carrier, flag)"));
        assertTrue(tuner.contains("TUNER_ACTIVITY_LABELS[carrier.status]"));
        assertTrue(tuner.contains("function tunerActivityStatus(row)"));
        assertTrue(tuner.contains("if (tags.has('CURRENT_CONTROL')) return 'CONTROL'"));
        assertTrue(tuner.contains("tags.has('ALTERNATE_CONTROL') || status === 'IDLE'"));
        assertTrue(tuner.contains("function updateSpectrumActivityTable(table)"));
        assertTrue(tuner.contains(".filter(tunerActivityStatus)"));
        assertTrue(tuner.contains("else activeChannelTables.delete(id)"));
        assertTrue(tuner.contains("row.channel_name || row.tableChannelName"));
        assertTrue(tuner.contains("decoderLabel(row.decoder)"));
        assertFalse(source.contains("IDLE: 'Known / idle channel'"));
        assertTrue(tuner.contains("const signature = JSON.stringify([viewport.startHz, viewport.endHz"));
        assertTrue(tuner.contains("if (signature === activeFlagSignature) return;"));
        assertTrue(tuner.contains("waterfallObservedAtRows = new Float64Array(waterfallBuffer.height)"));
        assertTrue(source.contains("const TUNER_WATERFALL_HISTORY_ROWS = 256"));
        assertTrue(tuner.contains("const waterfallHistoryRows = []"));
        assertTrue(tuner.contains("waterfallHistoryRows.push(cached)"));
        assertTrue(tuner.contains("Math.max(domain.startHz, viewport.startHz)"));
        assertTrue(tuner.contains("Math.min(domain.endHz, viewport.endHz)"));
        assertTrue(tuner.contains("pixelStartHz"));
        assertTrue(tuner.contains("pixelEndHz"));
        assertTrue(tuner.contains("restoreWaterfallHistory()"));
        assertFalse(tuner.contains("' (digital)'"));
        assertTrue(tuner.contains("const frameDomain = tunerFrameDomain(frameMetadata, fftValues.length)"));
        assertTrue(tuner.contains("const resolution = frameDomain.sentBinWidthHz"));
        assertTrue(tuner.contains("['Analysis span'"));
        assertTrue(tuner.contains("['Displayed resolution'"));
        assertTrue(source.contains("const TUNER_FREQUENCY_RASTERS = Object.freeze(["));
        assertTrue(source.contains("id: 'vhf-land-mobile', minHz: 150_000_000, maxHz: 173_997_500"));
        assertTrue(source.contains("originHz: 150_000_000, stepHz: 2_500"));
        assertTrue(source.contains("id: 'uhf-land-mobile-421', minHz: 421_000_000, maxHz: 429_993_750"));
        assertTrue(source.contains("originHz: 421_000_000, stepHz: 6_250"));
        assertTrue(source.contains("id: 'uhf-land-mobile-450', minHz: 450_000_000, maxHz: 511_993_750"));
        assertTrue(source.contains("originHz: 450_000_000, stepHz: 6_250"));
        assertTrue(source.contains("originHz: 769_006_250, stepHz: 6_250"));
        assertTrue(source.contains("originHz: 851_006_250, stepHz: 6_250"));
        assertTrue(source.contains("originHz: 935_012_500, stepHz: 12_500"));
        assertTrue(snapper.contains("Math.round((frequencyHz - raster.originHz) / raster.stepHz)"));
        assertTrue(snapper.contains("source: 'raster'"));
        assertFalse(source.contains("activeToleranceHz"));
        assertFalse(source.contains("activeOverlayRows"));
        assertFalse(source.contains("function tunerSignalCandidates"));
        assertFalse(source.contains("function tunerSignalHasCurrentSupport"));
        assertFalse(source.contains("waterfallSignalCandidates"));
        assertTrue(frequencyMapping.contains("Math.floor(index * sourceBinCount / transmittedBinCount)"));
        assertTrue(inverseFrequencyMapping.contains("tunerFrequencyAtBin(domain, middle)"));
        assertTrue(tuner.contains("tunerBinAtFrequency(tunerFrameDomain(frameMetadata, fftValues.length), displayHz)"));
        assertTrue(tuner.contains("tunerBinAtFrequency(tunerFrameDomain(frameMetadata, fftValues.length), carrier.frequencyHz)"));
        assertTrue(tuner.contains("sourceBinCount"));
        assertTrue(tuner.contains("resetZoom.disabled = !shouldRun()"));
        assertTrue(tuner.contains("if (!shouldRun() || !fullViewport || zoomAmount() <= 1.0001) return"));
        assertTrue(acceptState.contains("center_frequency_hz"));
        assertTrue(acceptState.contains("sample_rate_hz"));
        assertTrue(acceptState.contains("stateViewport(tunerState, 'visible')"));
        assertTrue(acceptState.contains("stateMatchesRequest(tunerState)"));
        assertFalse(acceptState.contains("frameMetadata ="));
        assertTrue(acceptFrame.contains("const nextAnalysis = { startHz: domain.startHz, endHz: domain.endHz }"));
        assertTrue(acceptFrame.contains("analysisViewport && !sameViewport"));
        assertTrue(acceptFrame.contains("domain.startHz > viewport.startHz"));
        assertTrue(acceptFrame.contains("domain.endHz < viewport.endHz"));
        assertTrue(acceptFrame.contains("if (generationChanged || analysisChanged)"));
        assertFalse(acceptFrame.contains("resetWaterfallBuffer(1, 1)"));
        assertTrue(acceptFrame.contains("restoreWaterfallHistory()"));
        assertTrue(acceptFrame.contains("if (analysisChanged) renderActiveChannels()"));
        assertTrue(acceptFrame.contains("frameMetadata = frame"));
        assertFalse(acceptFrame.contains("fullViewport = nextFull"));
        assertTrue(visibleValues.contains("return visibleValuesFor(values, frameMetadata)"));
        assertTrue(visibleValuesFor.contains("Math.max(domain.startHz, viewport.startHz)"));
        assertTrue(visibleValuesFor.contains("Math.min(domain.endHz, viewport.endHz)"));
        assertTrue(visibleValuesFor.contains("return values.subarray(first, end)"));
        assertTrue(tuner.contains("queueViewportUpdate(requestMode === 'immediate')"));
        assertTrue(tuner.contains("generation === frame.generation && sequence !== null"));
        assertTrue(source.contains("TUNER_SPECTRUM_FLOOR_PREFERENCE = 'floor_db'"));
        assertTrue(source.contains("TUNER_WATERFALL_SPEED_PREFERENCE = 'waterfall_speed'"));
        assertTrue(source.contains("TUNER_WATERFALL_CHANNELS_PREFERENCE = 'highlight_waterfall_channels'"));
        assertFalse(source.contains("sdrtrunk.wideband."));
        assertTrue(source.contains("const TUNER_CHANNEL_VISUAL_BANDWIDTH_HZ = 25_000"));
        assertTrue(diagnostic.contains("headerBytes >= 68 ? header.getInt32(64, true) : 0"));
        assertTrue(diagnostic.contains("headerBytes >= 72 ? header.getInt32(68, true) : valueCount"));
        assertFalse(css.contains(".tuner-spectrum-modal"));
        assertTrue(css.contains("body[data-view=\"tuner-spectrum\"] .content > .tuner-spectrum-layout"));
        assertTrue(css.contains(".tuner-spectrum-plot"));
        assertTrue(css.contains(".tuner-spectrum-active-flag {\n  width: 12px;\n  height: 12px;\n  min-height: 12px;"));
        assertTrue(css.contains(".tuner-spectrum-flag-legend"));
        assertTrue(css.contains(".tuner-spectrum-options-panel"));
        assertTrue(css.contains(".tuner-spectrum-options:not([open]) > .tuner-spectrum-options-panel"));
        assertTrue(css.contains(".tuner-spectrum-toggle-control"));
        assertTrue(css.contains(".tuner-spectrum-option-toggles"));
        assertTrue(css.contains(".tuner-spectrum-active-flag.status-encrypted"));
        assertTrue(css.contains(".tuner-spectrum-active-flag.status-call"));
        assertTrue(css.contains(".tuner-spectrum-active-flag.status-data"));
        assertTrue(css.contains(".tuner-spectrum-active-flag.status-control"));
        assertTrue(css.contains(".tuner-spectrum-active-flag.status-active"));
        assertTrue(css.contains(".tuner-spectrum-active-flag.status-idle"));
        assertFalse(css.contains(".tuner-spectrum-active-label"));
        assertTrue(css.contains(".tuner-spectrum-cursor-popup"));
        assertTrue(css.contains(".tuner-spectrum-cursor-field-label"));
        assertTrue(css.contains(".tuner-spectrum-cursor-field-value"));
        assertTrue(css.contains(".tuner-spectrum-display-controls"));
        assertTrue(css.contains(".tuner-spectrum-dual-range"));
        assertTrue(css.contains("--range-lower"));
        assertTrue(css.contains("--range-upper"));
        assertTrue(binary.contains("liveMultiplexer.subscribe(topic, parameters"));
        assertTrue(binary.contains("return close();"));
        assertTrue(css.contains(".tuner-spectrum-waterfall .tuner-spectrum-active-flag {"));
        assertTrue(css.contains("height: 100%;"));
        assertTrue(css.contains("pointer-events: none;"));
    }

    @Test
    void pausesEventsMessagesAndChannelFromOneSharedControl() throws Exception
    {
        String source = source();
        String events = function(source, "function liveEventsPanel(onCollapse)");
        String messages = function(source, "function liveMessagesPane()");
        String channel = function(source, "function liveChannelPane()");

        assertTrue(events.contains("live-details-pause', 'Pause'"));
        assertTrue(events.contains("'Pause Events, Messages, and Channel'"));
        assertFalse(events.contains("const connection = badge('Waiting'"));
        assertTrue(events.contains("eventsActive && !collapsed && selection?.configurationId"));
        assertTrue(events.contains("eventsActive = nextEventsActive"));
        assertFalse(events.contains("document.addEventListener('visibilitychange'"));
        assertTrue(events.contains("pause.textContent = paused ? 'Resume' : 'Pause'"));
        assertTrue(events.contains("messagesController.setPaused(paused)"));
        assertTrue(events.contains("channelController.setPaused(paused)"));
        assertTrue(events.contains("if (!paused) scheduleRender()"));
        assertTrue(messages.contains("active && !collapsed && !document.hidden && selection?.configurationId"));
        assertTrue(messages.contains("document.addEventListener('visibilitychange', onVisibilityChange)"));
        assertTrue(messages.contains("document.removeEventListener('visibilitychange', onVisibilityChange)"));
        assertTrue(messages.contains("if (document.hidden && stream)"));
        assertTrue(messages.contains("possibleGap = true"));
        assertTrue(messages.contains("setPaused(value) { paused = value; if (!paused) scheduleRender(); }"));
        assertFalse(messages.contains("badge('Waiting'"));
        assertFalse(messages.contains("setStatus("));
        assertTrue(channel.contains("active && !collapsed && !paused && !document.hidden"));
        assertTrue(channel.contains("setPaused(value) { paused = value; sync(); }"));
    }

    @Test
    void usesOneMultiplexedLiveConnectionAndRetiresPerFeatureTransports() throws Exception
    {
        String source = source();
        String player = readText(WEB_CALL_PLAYER);
        assertTrue(source.contains("class LiveMultiplexer"));
        assertTrue(source.contains("/api/v1/live/multiplex?client_id="));
        assertTrue(source.contains("/api/v1/live/multiplex/control"));
        assertEquals(source.indexOf("/api/v1/live/multiplex?client_id="),
            source.lastIndexOf("/api/v1/live/multiplex?client_id="));
        assertFalse(source.contains("EventSource"));
        assertFalse(player.contains("EventSource"));
        assertFalse(source.contains("/api/v1/live/channel-activity"));
        assertFalse(source.contains("/api/v1/live/decode-events"));
        assertFalse(source.contains("/api/v1/live/decode-messages"));
        assertFalse(source.contains("/api/v1/live/channel-diagnostics"));
        assertFalse(source.contains("/api/v1/live/tuner-diagnostics"));
        assertTrue(source.contains("stream.update(diagnosticParameters())"));
    }

    @Test
    void deliversFinalEmptyMultiplexControlBeforeAbortingTheConnection() throws Exception
    {
        String source = source();
        String server = readText(WEB_SERVER);
        String multiplexer = source.substring(source.indexOf("class LiveMultiplexer"),
            source.indexOf("const liveMultiplexer = new LiveMultiplexer()"));

        assertTrue(multiplexer.contains("subscribers.delete(subscriber)"));
        assertTrue(multiplexer.contains("this.parameters.delete(topic)"));
        assertTrue(multiplexer.contains("const desiredRevision = this.queueControl(true)"));
        assertTrue(multiplexer.contains("return this.closeIfIdle(desiredRevision)"));
        assertTrue(multiplexer.contains("const subscriptions = {}"));
        assertTrue(multiplexer.contains("if (targets.size) subscriptions[topic]"));
        assertTrue(multiplexer.contains("return this.waitForControlRevision(revision).then"));

        int controlPost = multiplexer.indexOf("await requestJson('/api/v1/live/multiplex/control'");
        int controlApplied = multiplexer.indexOf("this.controlAppliedRevision = Math.max", controlPost);
        int waitedClose = multiplexer.indexOf("return this.waitForControlRevision(revision).then");
        int idleStop = multiplexer.indexOf("this.stop();", waitedClose);
        int abort = multiplexer.indexOf("this.controller?.abort()", idleStop);
        assertTrue(controlPost >= 0 && controlPost < controlApplied);
        assertTrue(waitedClose >= 0 && waitedClose < idleStop);
        assertTrue(idleStop < abort);
        assertTrue(multiplexer.contains("this.settleControlWaiters(Number.MAX_SAFE_INTEGER, false)"));
        assertTrue(server.contains("if(requested.isEmpty())"));
        assertTrue(server.indexOf("client.requestClose();", server.indexOf("if(requested.isEmpty())")) <
            server.indexOf("ApiHttpResponse.sendData(exchange, 200", server.indexOf("if(requested.isEmpty())")));
    }

    @Test
    void idlePlaybackAndHiddenLiveSystemsDoNotOwnLogicalSubscriptions() throws Exception
    {
        String source = source();
        String playback = function(source, "function synchronizePlaybackAccess(accessChanged = false)");
        String channelActivity = function(source, "function synchronizeLiveChannelActivitySource()");
        String live = function(source, "function liveConnection(topic, parameters = {}, pageScoped = true)");
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");
        String player = readText(WEB_CALL_PLAYER);
        String connect = function(player, "  connect(url, feedFetch = null)");
        String toggle = function(player, "  async togglePlayback()");

        assertTrue(playback.contains("webCallPlayer.connect('/api/v1/calls/feed'"));
        assertTrue(playback.contains("requestJson(path, { ...options, csrf: false, page: false"));
        assertFalse(playback.contains("liveConnection('calls'"));
        assertTrue(connect.contains("this.feedFetch = typeof feedFetch === 'function'"));
        assertTrue(connect.contains("return this.connectionHandle()"));
        assertTrue(toggle.contains("this.ensureConnected()"));
        assertTrue(toggle.contains("this.stopFeed()"));
        assertTrue(channelActivity.contains("document.hidden || !liveChannelActivitySubscribers.size"));
        assertTrue(channelActivity.contains("source.close()"));
        assertTrue(live.contains("pageScoped && document.hidden"));
        assertTrue(live.contains("subscription?.close()"));
        assertTrue(live.contains("document.addEventListener('visibilitychange', synchronizeVisibility)"));
        assertTrue(live.contains("document.removeEventListener('visibilitychange', synchronizeVisibility)"));
        assertFalse(activity.contains("liveConnection('activity'"));
        assertTrue(activity.contains("pageInterval(refreshTick, 1_000)"));
        assertTrue(source.contains("document.addEventListener('visibilitychange', " +
            "synchronizeLiveChannelActivitySource)"));
    }

    @Test
    void preservesConfirmedAuthenticationAcrossTransientRefreshFailures() throws Exception
    {
        String refresh = function(source(), "async function refreshAccessSession(refreshCurrentView = false)");

        assertTrue(refresh.contains("if (error?.status === 401 || error?.status === 403)"));
        assertTrue(refresh.contains("accessSession = anonymousAccessSession();"));
        assertTrue(refresh.contains("} else if (!accessSessionAvailable) {"));
        assertFalse(refresh.contains("catch (error) {\n    accessSession = anonymousAccessSession();"));
    }

    @Test
    void boundsLiveHandshakeBodyReadsAndSilentConnectionsWithoutSharingCallbackFailures() throws Exception
    {
        String source = source();
        String request = function(source, "async function requestJson(path, options = {})");
        String invoke = function(source,
            "function invokeLiveSubscriber(target, callback, ...parameters)");
        String listener = function(source, "function invokeLiveListener(callback, ...parameters)");
        String channelActivity = function(source, "function synchronizeLiveChannelActivitySource()");
        String multiplexer = source.substring(source.indexOf("class LiveMultiplexer"),
            source.indexOf("const liveMultiplexer = new LiveMultiplexer()"));

        assertTrue(request.indexOf("result = await response.json()") <
            request.indexOf("window.clearTimeout(timeout)"));
        assertTrue(source.contains("const LIVE_MULTIPLEX_READY_TIMEOUT_MS = 10_000"));
        assertTrue(source.contains("const LIVE_MULTIPLEX_LIVENESS_TIMEOUT_MS = 25_000"));
        assertTrue(multiplexer.contains("const watchdog = window.setInterval"));
        assertTrue(multiplexer.contains("watchdogTimedOut = true"));
        assertTrue(multiplexer.contains("controller.abort()"));
        assertTrue(invoke.contains("try {"));
        assertTrue(invoke.contains("catch (error)"));
        assertTrue(listener.contains("try {"));
        assertTrue(listener.contains("catch (error)"));
        assertTrue(multiplexer.contains("invokeLiveSubscriber(target, 'onEvent'"));
        assertTrue(multiplexer.contains("invokeLiveSubscriber(target, 'onError'"));
        assertTrue(multiplexer.contains("this.failedTopics.add(topic)"));
        assertTrue(multiplexer.contains("this.failedTopics.delete(topic)"));
        assertTrue(source.contains("invokeLiveListener(callback, { type: event"));
        assertTrue(channelActivity.contains("invokeLiveSubscriber(target, 'activityTable', update)"));
        assertTrue(source.contains("invokeLiveSubscriber(target, 'snapshot', current)"));
        assertTrue(multiplexer.contains("error?.status === 401 || error?.status === 403"));
        assertTrue(multiplexer.contains("!this.authorizationRecoveryUsed"));
        assertTrue(multiplexer.contains("await refreshAccessSession(false)"));
        assertTrue(multiplexer.contains("this.authorizationBlocked = true"));
        assertTrue(multiplexer.contains("confirmedAccessRefresh()"));
        assertTrue(multiplexer.contains("if (this.hasSubscribers()) this.restart()"));
        assertTrue(source.contains("notifyConfirmedAccessRefresh = () => liveMultiplexer.confirmedAccessRefresh()"));
        String refresh = function(source, "async function refreshAccessSession(refreshCurrentView = false)");
        assertTrue(refresh.contains("{ csrf: false, page: false }"));
        assertTrue(refresh.contains("notifyConfirmedAccessRefresh()"));
    }

    private static String source() throws Exception
    {
        assertTrue(Files.isRegularFile(APP_JAVASCRIPT), () -> "Missing " + APP_JAVASCRIPT.toAbsolutePath());
        return readText(APP_JAVASCRIPT);
    }

    private static String readText(Path path) throws Exception
    {
        return normalizeLineEndings(Files.readString(path));
    }

    private static String normalizeLineEndings(String text)
    {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String function(String source, String signature)
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
