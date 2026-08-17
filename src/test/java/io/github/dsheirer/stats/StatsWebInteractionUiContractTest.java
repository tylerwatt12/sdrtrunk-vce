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
    void pausesEveryNewestActivityPageWithABoundedQueue() throws Exception
    {
        String activity = function(source(), "async function renderActivity(scopeParameters, title = 'Activity')");
        assertTrue(activity.contains("'Pause updates'"));
        assertTrue(activity.contains("`Resume${pending.size"));
        assertTrue(activity.contains("if (pending.size > 200)"));
        assertTrue(activity.contains("const pending = new Map()"));
        assertTrue(activity.contains("pending.delete(pending.keys().next().value)"));
        assertTrue(activity.contains("rows.forEach(addActivityRow)"));
    }

    @Test
    void refreshesExistingLiveActivityAndKeepsOnlyTheLatestPausedVersion() throws Exception
    {
        String source = source();
        String table = function(source, "function table(rows, columns, emptyText = 'No rows', options = {})");
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");
        assertTrue(table.contains("upsertRow(data, settings = {})"));
        assertTrue(table.contains("dataRows[existingIndex] = data"));
        assertTrue(table.contains("renderBody()"));
        assertTrue(activity.contains("activityTable.tableController.upsertRow(row"));
        assertTrue(activity.contains("row.id !== null && row.id !== undefined ? String(row.id) : Symbol()"));
        assertTrue(activity.contains("if (pending.has(key)) pending.delete(key)"));
        assertTrue(activity.contains("pending.set(key, row)"));
        assertTrue(activity.contains("[...pending.values()].sort"));
        assertTrue(activity.contains("pending.clear()"));
        assertFalse(activity.contains("pendingIds"));
        assertFalse(activity.contains("if (row.id !== null && row.id !== undefined && body.querySelector"));
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
        assertTrue(function(source, "function aliasListLink(name, id)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.ALIASES)"));
        assertTrue(function(source, "function systemLink(row, label = systemValue(row))")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source, "function siteLink(row, label = siteValue(row))")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source, "function siteNameSummary(row, linked = true)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source, "function neighborSiteLink(row)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source, "function talkgroupLink(row, id = row.talkgroup_id, label, explicitKind)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source, "function radioLink(row, id = row.radio_id, label)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertTrue(function(source, "function callSourceLink(row)")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.CONVENTIONAL)"));
        assertTrue(function(source, "function dashboardIdentityLink(row, label = dashboardIdentityId(row))")
            .contains("capabilityAllowed(ACCESS_CAPABILITIES.CONVENTIONAL)"));
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

        assertTrue(index.contains("<meta name=\"sdrtrunk-web-revision\" content=\"77\">"));
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
        assertTrue(source.contains("const SYSTEM_DIRECTORY_SITE_LIMIT = 100;"));
        assertTrue(source.contains("const SYSTEM_DIRECTORY_SITE_CONCURRENCY = 4;"));
        assertTrue(systems.contains("row.configured_system || `${protocolFamily(row)} System`"));
        assertTrue(systems.contains("heading.append(systemLink(row, label))"));
        assertTrue(systems.contains("siteNameSummary(row)"));
        assertTrue(systems.contains("systemApiPath(system.scope_token, 'sites')"));
        assertTrue(systems.contains("SYSTEM_DIRECTORY_SITE_LIMIT"));
        assertTrue(systems.contains("SYSTEM_DIRECTORY_SITE_CONCURRENCY"));
        assertTrue(systems.contains("directory_type: 'system'"));
        assertTrue(systems.contains("directory_type: 'site'"));
        assertTrue(systems.contains("`directory-${row.directory_type}-row`"));
        assertTrue(systems.contains("sitePage?.has_more"));
        assertFalse(systems.contains("directory-secondary"));
        assertFalse(systems.contains("row.site_names && row.site_names"));
        assertFalse(systems.contains("isP25(row) ? 'P25 System'"));
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
        assertTrue(source.contains("sort: 'signaling'"));
        assertTrue(function(source, "function talkgroupSignaling(row)")
            .contains("return total > 0 ? number(total) : '—'"));
        assertTrue(function(source, "function talkgroupSignalingSortValue(row)")
            .contains("row.signaling_count"));
        assertTrue(function(source, "function signalingCounts(row)")
            .contains(".sort((left, right) => right[1] - left[1])"));
        assertTrue(function(source, "function talkgroupActivityChart(response, seriesConfigurations, ariaLabel)")
            .contains("const largest = configurations.reduce"));
        assertTrue(source.contains("section('Retained Call Activity'"));
        assertTrue(source.contains("section('Retained Signaling Observations'"));
        assertTrue(source.contains("section('Collected Call Activity'"));
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
        String links = function(source, "function talkgroupLink(row, id = row.talkgroup_id, label, explicitKind)");
        assertTrue(tabs.contains("kind: kind === 'patch_group' ? 'patch_group' : null"));
        assertTrue(talkgroup.contains("entityTabs('talkgroup', talkgroup, id, tab, false, kind)"));
        assertTrue(talkgroup.contains(
            "pageParameters({ talkgroup_id: id, kind: kind === 'patch_group' ? 'patch_group' : null,"));
        assertTrue(talkgroup.contains("renderActivity({ ...systemScope, talkgroup_id: id, kind }"));
        assertTrue(talkgroup.contains("talkgroupActivityHistorySection({ ...systemScope, talkgroup_id: id, kind })"));
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");
        assertFalse(activity.contains("scopeParameters.kind === 'patch'"));
        assertTrue(activity.contains("liveConnection('activity', scopeParameters)"));
        assertTrue(links.contains("rowGroupIdentityKind(row, explicitKind) === 'patch_group'"));
        assertTrue(radio.contains("radio.last_talkgroup_kind"));
        assertTrue(source.contains("render: (row) => groupIdentityLabel(row)"));
        assertTrue(source.contains("talkgroupLink(site, row.patch_group, undefined, 'patch_group')"));
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
        String channels = function(source, "async function renderSiteChannels(site)");
        String neighbors = function(source, "async function renderSiteNeighbors(site)");
        String render = function(source, "async function render()");
        String system = function(source, "async function renderSystem()");
        assertTrue(channels.contains("api(siteApiPath(site.guid, 'channels'), pageParameters())"));
        assertTrue(channels.contains("block.append(pager(data))"));
        assertTrue(channels.contains("sortable: false"));
        assertTrue(neighbors.contains("api(siteApiPath(site.guid, 'neighbors'), pageParameters())"));
        assertTrue(neighbors.contains("block.append(pager(data))"));
        assertTrue(neighbors.contains("sortable: false"));
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
        String pager = function(source, "function pager(page, position = 'bottom')");
        String pagedSection = function(source,
            "function pagedSection(title, page, columns, searchPlaceholder, tableType, action = null, options = {})");
        String system = function(source, "async function renderSystem()");
        String css = readText(APP_CSS);

        assertTrue(pager.contains("Number(page.totalCount)"));
        assertTrue(pager.contains("of ${number(totalCount)}"));
        assertTrue(pager.contains("aria-label"));
        assertTrue(pagedSection.contains("if (options.topPager) block.append(pager(page, 'top'))"));
        assertTrue(pagedSection.contains("block.append(pager(page))"));
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

        assertTrue(function(source, "async function renderSiteChannels(site)")
            .contains("exportCsvLink('site-channels', { guid: site.guid })"));
        assertTrue(function(source, "async function renderSiteNeighbors(site)")
            .contains("exportCsvLink('site-neighbors', { guid: site.guid })"));
        assertTrue(function(source, "async function renderConventional()")
            .contains("exportCsvLink('conventional-channels')"));
        assertTrue(function(source, "async function renderConventionalTalkgroups(contextKey)")
            .contains("exportCsvLink('conventional-talkgroups', { context: contextKey })"));
        assertTrue(function(source, "async function renderConventionalRadios(contextKey)")
            .contains("exportCsvLink('conventional-radios', { context: contextKey })"));

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
        String renderer = function(source, "function activityIdentifier(row, value, kind)");
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
        assertTrue(source.contains("render: (row) => number(row.call_count)"));
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
        assertTrue(neighbor.contains("row.neighbor_guid"));
        assertTrue(neighbor.contains("href('site', { guid: row.neighbor_guid"));
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
    void appliesAndPersistsAnAccessibleThemeBeforePaint() throws Exception
    {
        String source = source();
        String html = readText(INDEX_HTML);
        String css = readText(APP_CSS);
        assertTrue(html.indexOf("const themeKey = mobile ? 'sdrtrunk_mobile_theme' : 'sdrtrunk_theme'") <
            html.indexOf("rel=\"stylesheet\""));
        assertTrue(html.contains("id=\"theme-toggle\""));
        assertTrue(html.contains("id=\"mobile-theme-toggle\""));
        assertTrue(html.contains("/assets/app.css?v=62"));
        assertTrue(html.contains("/assets/app.js?v=93"));
        assertTrue(source.contains("MOBILE_THEME_STORAGE_KEY = 'sdrtrunk_mobile_theme'"));
        assertTrue(source.contains("mode === 'mobile' ? MOBILE_THEME_STORAGE_KEY : THEME_STORAGE_KEY"));
        assertTrue(source.contains("toggle.setAttribute('aria-pressed'"));
        assertTrue(function(source, "function applyListenerShellMode()")
            .contains("applyTheme(mobile ? 'mobile' : 'desktop')"));
        assertTrue(css.contains(":root[data-theme=\"dark\"]"));
        assertTrue(css.contains(".mobile-listener-shell[data-theme=\"dark\"]"));
        assertTrue(css.contains("color-scheme: light"));
        assertTrue(css.contains("--chart-call:"));
        assertTrue(css.contains(":not(.auth-action):not(.table-sort-control):not(.systems-live-tab)"));
        assertFalse(css.contains("filter: invert("));
    }

    @Test
    void controlsAndPersistsBrowserPlaybackVolumeIndependentlyOfTransportState() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(WEB_CALL_PLAYER);
        String css = readText(APP_CSS);
        String changeVolume = function(source, "  changeVolume()");
        String readVolume = function(source, "  readVolume()");
        String ensureAudioContext = function(source, "  ensureAudioContext()");
        String startCurrent = function(source, "  startCurrent()");

        assertTrue(html.contains("id=\"playback-volume\" type=\"range\""));
        assertTrue(html.contains("aria-label=\"Browser playback volume\""));
        assertTrue(html.contains("id=\"playback-volume-value\""));
        assertTrue(html.contains("/assets/web-call-player.js?v=10"));
        assertTrue(source.contains("VOLUME_KEY = 'sdrtrunk-vce.web-player.volume'"));
        assertTrue(source.contains("this.volume = this.readVolume()"));
        assertTrue(changeVolume.contains("this.gainNode.gain.value = this.volume"));
        assertTrue(changeVolume.contains("localStorage.setItem(WebCallPlayer.VOLUME_KEY"));
        assertFalse(changeVolume.contains("this.paused"));
        assertTrue(readVolume.contains("stored === null || stored.trim() === ''"));
        assertTrue(readVolume.contains("return 1"));
        assertTrue(readVolume.contains("saved >= 0 && saved <= 1"));
        assertTrue(ensureAudioContext.contains("this.audioContext.createGain()"));
        assertTrue(ensureAudioContext.contains("this.gainNode.gain.value = this.volume"));
        assertTrue(startCurrent.contains("source.connect(this.gainNode)"));
        assertTrue(css.contains(".playback-volume input:focus-visible"));
        assertTrue(css.contains("accent-color: #36a99e"));
    }

    @Test
    void consumesCallSnapshotsIdempotentlyAndRefetchesActivityAfterATopicGap() throws Exception
    {
        String player = readText(WEB_CALL_PLAYER);
        String ensureConnected = function(player, "  ensureConnected()");
        String enqueue = function(player, "  enqueue(call)");
        String snapshot = function(player, "  consumeSnapshot(snapshot)");
        String activity = function(source(), "async function renderActivity(scopeParameters, title = 'Activity')");

        assertTrue(ensureConnected.contains("addEventListener('snapshot'"));
        assertTrue(enqueue.contains("this.seenCallIds.has(normalized._logicalCallId)"));
        assertTrue(enqueue.contains("this.rememberCallId(normalized._logicalCallId)"));
        assertTrue(snapshot.contains("snapshot?.calls"));
        assertTrue(activity.contains("addEventListener('activity_reset'"));
        assertTrue(activity.contains("api('/api/v1/activity'"));
        assertTrue(activity.contains("tableController.replaceRows"));
        assertTrue(activity.contains("const resetPending = new Map()"));
    }

    @Test
    void usesNormalPlaybackTransportAndKeepsBufferingCallsOutOfCallActions() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(WEB_CALL_PLAYER);
        String enqueue = function(source, "  enqueue(call)");
        String togglePlayback = function(source, "  async togglePlayback()");
        String replayCurrent = function(source, "  async replayCurrent()");
        String toggleHold = function(source, "  toggleHold()");
        String avoidCurrent = function(source, "  avoidCurrent()");
        String render = function(source, "  render()");

        int play = html.indexOf("id=\"playback-play\"");
        int skip = html.indexOf("id=\"playback-skip\"");
        int replay = html.indexOf("id=\"playback-replay\"");
        int hold = html.indexOf("id=\"playback-hold\"");
        int avoid = html.indexOf("id=\"playback-avoid\"");
        assertTrue(play >= 0 && play < skip && skip < replay && replay < hold && hold < avoid);
        assertFalse(html.contains("id=\"playback-mute\""));
        assertFalse(html.contains(">Unmute<"));
        assertTrue(source.contains("this.paused = true"));
        assertTrue(enqueue.contains("if (!this.paused && !this.current) this.playNext();"));
        assertTrue(enqueue.contains("else this.render();"));
        assertTrue(togglePlayback.contains("this.playbackOffset = this.getPlaybackPosition()"));
        assertTrue(togglePlayback.contains("if (this.currentBuffer) this.startCurrent();"));
        assertTrue(replayCurrent.contains("this.playbackOffset = 0"));
        assertTrue(replayCurrent.contains("this.startCurrent()"));
        assertTrue(toggleHold.contains("this.current && this.currentBuffer"));
        assertTrue(avoidCurrent.contains("if (!this.current || !this.currentBuffer) return;"));
        assertTrue(render.contains("this.ui.hold.disabled = !this.holdTarget && !currentReady"));
        assertTrue(render.contains("this.ui.avoid.disabled = !currentReady"));
        assertTrue(render.contains("this.ui.replay.disabled = !currentReady"));
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
        assertTrue(startCurrent.contains("source.start(0, offset)"));
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
        assertTrue(css.contains(".mobile-listener-shell .playback-progress.active"));
        assertTrue(css.contains("height: 12px;"));
        assertTrue(css.contains("width: calc(var(--playback-progress) * 100%);"));
    }

    @Test
    void subscribesToBoundedScanListsDeduplicatesCallsAndSchedulesConversationLanes() throws Exception
    {
        String html = readText(INDEX_HTML);
        String source = readText(WEB_CALL_PLAYER);
        String enqueue = function(source, "  enqueue(call)");
        String parameters = function(source, "  subscriptionParameters()");
        String synchronize = function(source, "  synchronizeSubscription()");
        String normalize = function(source, "  normalizeCall(value)");
        String schedule = function(source, "  chooseNextLane(lanes, lastKey, consecutive)");

        assertTrue(html.contains("id=\"playback-scan-list-options\""));
        assertTrue(html.contains("id=\"playback-missed\""));
        assertTrue(parameters.contains("scan_list_id: [...this.selectedScanListIds]"));
        assertTrue(synchronize.contains("this.events.update(this.subscriptionParameters())"));
        assertTrue(source.contains("maximum_selected_scan_lists"));
        assertTrue(source.contains("maximum_browser_queue_calls"));
        assertTrue(function(source, "  setScanListSelected(id, selected)")
            .contains("if (selected && this.paused) void this.togglePlayback()"));
        assertTrue(normalize.contains("value.logical_call_id ?? value.call_id"));
        assertTrue(normalize.contains("value.matched_scan_list_ids ?? value.scan_list_ids"));
        assertTrue(enqueue.contains("this.seenCallIds.has(normalized._logicalCallId)"));
        assertTrue(enqueue.indexOf("callMatchesSelection(normalized)") <
            enqueue.indexOf("rememberCallId(normalized._logicalCallId)"));
        assertTrue(source.contains("MAXIMUM_SEEN_CALL_IDS = 2048"));
        assertTrue(source.contains("MAXIMUM_CONSECUTIVE_CONVERSATION_CALLS = 4"));
        assertTrue(schedule.contains("consecutive < WebCallPlayer.MAXIMUM_CONSECUTIVE_CONVERSATION_CALLS"));
        assertTrue(source.contains("first._startedAtMs - second._startedAtMs"));
        assertTrue(source.contains("events.addEventListener('missed'"));
        assertTrue(source.contains("this.missedCountExact"));
    }

    @Test
    void providesDedicatedMobileListenerAndBoundedAdministratorAudioControls() throws Exception
    {
        String html = Files.readString(INDEX_HTML);
        String source = source();
        String css = Files.readString(APP_CSS);
        String shell = function(source, "function applyListenerShellMode()");
        String admin = function(source, "async function renderAdmin()");
        String scanLists = function(source, "async function renderAdminScanLists()");
        String audio = function(source, "async function renderAdminWebAudio()");

        assertTrue(html.contains("id=\"mobile-listener-shell\""));
        assertFalse(html.contains("id=\"mobile-listener-desktop\""));
        assertFalse(html.contains("id=\"mobile-listener-open\""));
        assertTrue(html.contains("id=\"mobile-theme-toggle\""));
        assertTrue(html.contains("data-listener-view=\"scan-lists\""));
        assertTrue(html.contains("data-listener-view=\"queue\""));
        assertTrue(shell.contains("mobileSlot.append(bar)"));
        assertTrue(shell.contains("app.hidden = true"));
        assertTrue(shell.contains("const mobile = compactListenerMedia.matches"));
        assertFalse(source.contains("LISTENER_MODE_STORAGE_KEY"));
        assertFalse(source.contains("setListenerModePreference"));
        assertTrue(function(source, "function initializePlaybackHeader()")
            .contains("!subscriptions.contains(event.target)"));
        assertTrue(function(source, "function initializePlaybackHeader()").contains("!mobileTrigger"));
        assertTrue(source.contains("COMPACT_LISTENER_MEDIA"));
        assertTrue(css.contains("height: 100dvh"));
        assertTrue(css.contains("env(safe-area-inset-bottom"));

        assertTrue(html.contains("view=admin&amp;tab=scan-lists"));
        assertTrue(admin.contains("label: 'Scan Lists'"));
        assertTrue(admin.contains("label: 'Listener Status'"));
        assertTrue(scanLists.contains("requestJson('/api/v1/admin/scan-lists'"));
        assertTrue(scanLists.contains("'No scan lists are configured'"));
        assertTrue(scanLists.contains("unmatched_alias_list_count"));
        assertTrue(scanLists.contains("'Unknown routes'"));
        assertTrue(scanLists.contains("Alias List\\'s Global Settings"));
        String editScanList = function(source, "function openScanListAdminModal(scanList, revision)");
        String deleteScanList = function(source, "function openDeleteScanListAdminModal(scanList, revision)");
        assertTrue(deleteScanList.contains("unmatched_alias_list_count"));
        assertTrue(deleteScanList.contains("global unmatched-"));
        assertTrue(source.contains("/api/v1/admin/scan-lists/${scanList.id}"));
        assertTrue(editScanList.contains("await refreshPlaybackScanLists(true)"));
        assertTrue(deleteScanList.contains("await refreshPlaybackScanLists(true)"));
        assertFalse(editScanList.contains("location.reload"));
        assertFalse(deleteScanList.contains("location.reload"));
        assertTrue(audio.contains("requestJson('/api/v1/admin/web-audio'"));
        assertTrue(audio.contains("'Refresh Status'"));
        for(String field: new String[]{"maximum_listeners", "maximum_selected_scan_lists",
            "maximum_browser_queue_calls", "maximum_cached_calls", "maximum_cached_audio_mib"})
        {
            assertTrue(audio.contains(field), () -> "Missing web-audio setting " + field);
        }
        for(String counter: new String[]{"dropped_sse_events", "rejected_listeners", "audio_fetch_misses",
            "rejected_audio_responses", "active_audio_responses", "age_evictions", "capacity_evictions",
            "encoder_queue_depth", "event_queue_capacity"})
        {
            assertTrue(audio.contains(counter), () -> "Missing listener status counter " + counter);
        }
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
    void splitsLiveDetailsAndScopesBoundedDecoderEventsToTheCurrentSelection() throws Exception
    {
        String source = source();
        String css = readText(APP_CSS);
        String selection = function(source, "function liveEventSelection(tableValue, row)");
        String events = function(source, "function liveEventsPanel(onCollapse)");
        String messages = function(source, "function liveMessagesPane()");
        String channel = function(source, "function liveChannelPane()");
        String systems = function(source, "function liveSystemsSection(onSelectionChange)");
        String createRow = function(systems, "const createRow = (row) =>");
        String showTable = function(systems, "const showTable = (tableId) =>");
        String updateVisibleRows = function(systems, "const updateVisibleRows = (value) =>");
        String live = function(source, "async function renderLive()");

        assertTrue(live.contains("node('div', 'live-split')"));
        assertTrue(live.contains("liveSystemsSection(eventsPanel.select)"));
        assertTrue(events.contains("['events', 'messages', 'channel']"));
        assertTrue(events.contains("liveMessagesPane()"));
        assertTrue(events.contains("liveChannelPane()"));
        assertTrue(events.contains("liveConnection('decode_events', parameters)"));
        assertTrue(events.contains("configuration_id: selection.configurationId"));
        assertTrue(events.contains("parameters.frequency_hz = selection.frequencyHz"));
        assertTrue(events.contains("parameters.timeslot = selection.timeslot"));
        assertTrue(events.contains("stream.addEventListener('snapshot'"));
        assertTrue(events.contains("stream.addEventListener('decode_event'"));
        assertTrue(events.contains("if (!events.has(event.event_id)) order.unshift(event.event_id)"));
        assertTrue(events.contains("while (order.length > 200)"));
        assertTrue(events.contains("['ENCRYPTED_VOICE', 'Encrypted voice']"));
        assertTrue(events.contains("['REGISTRATION', 'Registrations']"));
        assertTrue(events.contains("['Time', 'Duration', 'Event', 'From', 'To', 'Channel', 'Details']"));
        assertTrue(events.contains("node('tr', liveEventCategoryClass(event.category))"));
        assertTrue(events.contains("row.dataset.eventCategory = event.category || 'OTHER'"));
        assertTrue(events.contains("message.colSpan = 7"));
        assertTrue(events.contains("node('td', 'live-event-duration')"));
        assertTrue(events.contains("node('strong', 'live-event-duration-value', durationText)"));
        assertTrue(messages.contains("liveConnection('decode_messages', parameters)"));
        assertTrue(messages.contains("stream.addEventListener('decode_message'"));
        assertTrue(messages.contains("active && !collapsed && !paused && !document.hidden"));
        assertTrue(channel.contains("binaryFrameConnection('channel_diagnostics', parameters"));
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
        assertFalse(channel.contains("window.setInterval"));
        assertFalse(channel.contains("window.clearInterval(ageTimer)"));
        assertTrue(channel.contains("active && !collapsed && !paused && !document.hidden"));
        assertFalse(channel.contains("channel-mode-tabs"));
        assertFalse(channel.contains("view: mode"));
        assertTrue(selection.contains("row?.configuration_id || tableValue?.configuration_id"));
        assertTrue(selection.contains("['CONFIGURED', 'CURRENT_CONTROL', 'ALTERNATE_CONTROL']"));
        assertTrue(selection.contains("diagnosticFrequencyHz"));
        assertTrue(selection.contains("TS ${diagnosticTimeslot}"));
        assertTrue(systems.contains("const currentRow = (value?.rows || []).find"));
        assertTrue(systems.contains("onSelectionChange(liveEventSelection(value, row))"));
        assertTrue(systems.contains("if (selectedRowKey !== null && !incoming.has(selectedRowKey)) clearSelection()"));
        assertTrue(systems.contains("const currentControlRow = (value) =>"));
        assertTrue(createRow.contains("selectRow(value, currentRow)"));
        assertTrue(showTable.contains("value.control_active ? currentControlRow(value) : null"));
        assertTrue(showTable.contains("selectRow(value, currentControl)"));
        assertFalse(updateVisibleRows.contains("currentControlRow(value)"));
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
        assertTrue(source.contains("'tuner-spectrum': ACCESS_CAPABILITIES.TUNER_SPECTRUM"));
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
        assertTrue(source.contains("TUNER_SPECTRUM_PROFILE_STORAGE_KEY"));
        assertTrue(tuner.contains("storeTunerChoice(TUNER_SPECTRUM_PROFILE_STORAGE_KEY, spectrumProfile)"));
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
        assertTrue(tuner.contains("function updateDisplayRange(changedHandle = '')"));
        assertTrue(tuner.contains("TUNER_SPECTRUM_FLOOR_STORAGE_KEY, dbFloor"));
        assertTrue(tuner.contains("TUNER_SPECTRUM_CEILING_STORAGE_KEY, dbCeiling"));
        assertTrue(tuner.contains("Math.min(dbCeiling, raw)"));
        assertTrue(tuner.contains("(dbCeiling - value) / (dbCeiling - dbFloor)"));
        assertTrue(tuner.contains("(value - dbFloor) / (dbCeiling - dbFloor)"));
        assertTrue(tuner.contains("TUNER_WATERFALL_SPEED_STORAGE_KEY, waterfallSpeed"));
        assertTrue(tuner.contains("TUNER_SPECTRUM_SNAP_STORAGE_KEY, true"));
        assertTrue(tuner.contains("storeTunerBoolean(TUNER_SPECTRUM_SNAP_STORAGE_KEY, snapInput.checked)"));
        assertTrue(tuner.contains("'Snap frequency'"));
        assertTrue(tuner.contains("smoothInput.type = 'checkbox'"));
        assertTrue(tuner.contains("TUNER_SPECTRUM_SMOOTH_STORAGE_KEY, true"));
        assertTrue(tuner.contains("storeTunerBoolean(TUNER_SPECTRUM_SMOOTH_STORAGE_KEY, smoothInput.checked)"));
        assertTrue(tuner.contains("'Smooth FFT'"));
        assertTrue(tuner.contains("waterfallChannelsInput.type = 'checkbox'"));
        assertTrue(tuner.contains("TUNER_WATERFALL_CHANNELS_STORAGE_KEY, false"));
        assertTrue(tuner.contains("'Highlight channels on waterfall'"));
        assertTrue(tuner.contains("storeTunerChoice(TUNER_SPECTRUM_TARGET_STORAGE_KEY, targetSelect.value)"));
        assertTrue(tuner.contains("tunerStoredChoice(TUNER_SPECTRUM_TARGET_STORAGE_KEY, targets[0].id"));
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
        assertTrue(source.contains("sdrtrunk.wideband.lowerDisplayLimitDb"));
        assertTrue(source.contains("sdrtrunk.wideband.waterfallScrollSpeed"));
        assertTrue(source.contains("sdrtrunk.wideband.highlightWaterfallChannels"));
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
        assertTrue(events.contains("eventsActive && !collapsed && !paused && !document.hidden"));
        assertTrue(events.contains("eventsActive = id === 'events'"));
        assertTrue(events.contains("document.addEventListener('visibilitychange', onVisibilityChange)"));
        assertTrue(events.contains("document.removeEventListener('visibilitychange', onVisibilityChange)"));
        assertTrue(events.contains("pause.textContent = paused ? 'Resume' : 'Pause'"));
        assertTrue(events.contains("messagesController.setPaused(paused)"));
        assertTrue(events.contains("channelController.setPaused(paused)"));
        assertTrue(messages.contains("active && !collapsed && !paused && !document.hidden"));
        assertTrue(messages.contains("setPaused(value) { paused = value; sync(); }"));
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
        String connect = function(player, "  connect(url, connectionFactory)");
        String toggle = function(player, "  async togglePlayback()");

        assertTrue(playback.contains("(topic, parameters) => liveConnection(topic, parameters, false)"));
        assertFalse(connect.contains("this.connectionFactory(this.connectionTopic)"));
        assertTrue(connect.contains("return this.connectionHandle()"));
        assertTrue(toggle.contains("this.ensureConnected()"));
        assertTrue(channelActivity.contains("document.hidden || !liveChannelActivitySubscribers.size"));
        assertTrue(channelActivity.contains("source.close()"));
        assertTrue(live.contains("pageScoped && document.hidden"));
        assertTrue(live.contains("subscription?.close()"));
        assertTrue(live.contains("document.addEventListener('visibilitychange', synchronizeVisibility)"));
        assertTrue(live.contains("document.removeEventListener('visibilitychange', synchronizeVisibility)"));
        assertTrue(activity.contains("liveConnection('activity', scopeParameters)"));
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
