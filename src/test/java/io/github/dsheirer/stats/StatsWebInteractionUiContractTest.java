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
        assertTrue(function(source, "function talkgroupLink(row, id = row.talkgroup_id, label, explicitKindCode)")
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
        String css = Files.readString(APP_CSS);
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
    void showsConfiguredSystemHeadingsAndLinksEveryTrunkedParent() throws Exception
    {
        String systems = function(source(), "async function renderSystems()");
        assertTrue(systems.contains("row.configured_system || `${protocolFamily(row)} System`"));
        assertTrue(systems.contains("heading.append(systemLink(row, label))"));
        assertTrue(systems.contains("siteNameSummary(row)"));
        assertFalse(systems.contains("directory-secondary"));
        assertFalse(systems.contains("row.site_names && row.site_names"));
        assertFalse(systems.contains("isP25(row) ? 'P25 System'"));
        assertFalse(Files.readString(APP_CSS).contains(".directory-secondary"));
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
        assertTrue(Files.readString(APP_CSS).contains(".entity-info-standalone > .section"));
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
        String links = function(source, "function talkgroupLink(row, id = row.talkgroup_id, label, explicitKindCode)");
        assertTrue(tabs.contains("kind: kind === 'patch' ? 'patch' : null"));
        assertTrue(talkgroup.contains("entityTabs('talkgroup', talkgroup, id, tab, false, kind)"));
        assertTrue(talkgroup.contains("pageParameters({ ...systemScope, talkgroup_id: id, kind })"));
        assertTrue(talkgroup.contains("renderActivity({ ...systemScope, talkgroup_id: id, kind }"));
        assertTrue(talkgroup.contains("talkgroupActivityHistorySection({ ...systemScope, talkgroup_id: id, kind })"));
        assertTrue(links.contains("explicitKindCode ?? row.identity_kind_code ?? row.target_kind_code"));
        assertTrue(radio.contains("radio.last_talkgroup_kind_code"));
        assertTrue(source.contains("Number(row.target_kind_code) === 3 ? 'Patch' : 'TG'"));
        assertTrue(source.contains("talkgroupLink(site, row.patch_group, undefined, 3)"));
        String siteTalkgroups = function(source, "async function siteTopTalkgroupsSection(site)");
        assertTrue(siteTalkgroups.contains("id: 'talkgroup-kind'"));
        assertTrue(siteTalkgroups.contains("=== 3 ? 'Patch' : 'TG'"));
    }

    @Test
    void pagesSiteChannelsAndNeighborsWithoutLegacySiteRoutes() throws Exception
    {
        String source = source();
        String channels = function(source, "async function renderSiteChannels(site)");
        String neighbors = function(source, "async function renderSiteNeighbors(site)");
        String render = function(source, "async function render()");
        String system = function(source, "async function renderSystem()");
        assertTrue(channels.contains("pageParameters({ guid: site.guid })"));
        assertTrue(channels.contains("block.append(pager(data))"));
        assertTrue(channels.contains("sortable: false"));
        assertTrue(neighbors.contains("pageParameters({ guid: site.guid })"));
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
    void exportsCompleteFilteredManagerTablesWithoutPaginationParameters() throws Exception
    {
        String source = source();
        String href = function(source, "function exportCsvHref(dataset, context = {})");
        String helper = function(source, "function exportCsvLink(dataset, context = {})");
        assertTrue(href.contains("parameters.set('dataset', dataset)"));
        assertTrue(href.contains("['q', 'sort', 'direction']"));
        assertTrue(href.contains("return `/api/export.csv?${parameters}`"));
        assertTrue(helper.contains("anchor('Export CSV', exportCsvHref(dataset, context)"));
        assertTrue(helper.contains("link.setAttribute('download', '')"));
        assertTrue(helper.contains("link.setAttribute('aria-label'"));
        assertFalse(href.contains("'limit'"));
        assertFalse(href.contains("'offset'"));
        assertFalse(href.contains("'before_id'"));

        String system = function(source, "async function renderSystem()");
        assertTrue(system.contains("exportCsvLink('system-talkgroups', systemScope)"));
        assertTrue(system.contains("exportCsvLink('system-radios', systemScope)"));
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
        assertTrue(Files.readString(APP_CSS).contains(".export-csv-action"));
    }

    @Test
    void labelsProtocolDefinedSentinelsAsSystemOrSpecialActivityWithoutLinkingThem() throws Exception
    {
        String source = source();
        String css = Files.readString(APP_CSS);
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
        assertTrue(labels.contains("Number(row.identity_domain_code) !== 2"));
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
        String css = Files.readString(APP_CSS);
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
        String html = Files.readString(INDEX_HTML);
        String css = Files.readString(APP_CSS);
        assertTrue(html.indexOf("localStorage.getItem('sdrtrunk_theme')") <
            html.indexOf("rel=\"stylesheet\""));
        assertTrue(html.contains("id=\"theme-toggle\""));
        assertTrue(html.contains("/assets/app.css?v=34"));
        assertTrue(html.contains("/assets/app.js?v=47"));
        assertTrue(source.contains("window.localStorage.setItem(THEME_STORAGE_KEY"));
        assertTrue(source.contains("toggle.setAttribute('aria-pressed'"));
        assertTrue(css.contains(":root[data-theme=\"dark\"]"));
        assertTrue(css.contains("--chart-call:"));
        assertTrue(css.contains(":not(.auth-action):not(.table-sort-control):not(.systems-live-tab)"));
        assertFalse(css.contains("filter: invert("));
    }

    @Test
    void controlsAndPersistsBrowserPlaybackVolumeIndependentlyOfMute() throws Exception
    {
        String html = Files.readString(INDEX_HTML);
        String source = Files.readString(WEB_CALL_PLAYER);
        String css = Files.readString(APP_CSS);
        String changeVolume = function(source, "  changeVolume()");
        String readVolume = function(source, "  readVolume()");
        String ensureAudioContext = function(source, "  ensureAudioContext()");
        String startCurrent = function(source, "  startCurrent()");

        assertTrue(html.contains("id=\"playback-volume\" type=\"range\""));
        assertTrue(html.contains("aria-label=\"Browser playback volume\""));
        assertTrue(html.contains("id=\"playback-volume-value\""));
        assertTrue(html.contains("/assets/web-call-player.js?v=5"));
        assertTrue(source.contains("VOLUME_KEY = 'sdrtrunk-vce.web-player.volume'"));
        assertTrue(source.contains("this.volume = this.readVolume()"));
        assertTrue(changeVolume.contains("this.gainNode.gain.value = this.volume"));
        assertTrue(changeVolume.contains("localStorage.setItem(WebCallPlayer.VOLUME_KEY"));
        assertFalse(changeVolume.contains("this.muted"));
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
    void keepsBufferingCallsOutOfQueueAndPlaybackActions() throws Exception
    {
        String source = Files.readString(WEB_CALL_PLAYER);
        String enqueue = function(source, "  enqueue(call)");
        String toggleHold = function(source, "  toggleHold()");
        String avoidCurrent = function(source, "  avoidCurrent()");
        String render = function(source, "  render()");

        assertTrue(enqueue.contains("if (!this.muted && !this.current) this.playNext();"));
        assertTrue(enqueue.contains("else this.render();"));
        assertTrue(toggleHold.contains("this.source && this.current"));
        assertTrue(avoidCurrent.contains("if (!this.source || !this.current) return;"));
        assertTrue(render.contains("this.ui.hold.disabled = !this.holdTarget && !activelyPlaying"));
        assertTrue(render.contains("this.ui.avoid.disabled = !activelyPlaying"));
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
        String css = Files.readString(APP_CSS);
        String selection = function(source, "function liveEventSelection(tableValue, row)");
        String events = function(source, "function liveEventsPanel(onCollapse)");
        String messages = function(source, "function liveMessagesPane()");
        String channel = function(source, "function liveChannelPane()");
        String systems = function(source, "function liveSystemsSection(onSelectionChange)");
        String live = function(source, "async function renderLive()");

        assertTrue(live.contains("node('div', 'live-split')"));
        assertTrue(live.contains("liveSystemsSection(eventsPanel.select)"));
        assertTrue(events.contains("['events', 'messages', 'channel']"));
        assertTrue(events.contains("liveMessagesPane()"));
        assertTrue(events.contains("liveChannelPane()"));
        assertTrue(events.contains("liveConnection('/live/events', parameters)"));
        assertTrue(events.contains("configuration_id: selection.configurationId"));
        assertTrue(events.contains("parameters.frequency_hz = selection.frequencyHz"));
        assertTrue(events.contains("parameters.timeslot = selection.timeslot"));
        assertTrue(events.contains("stream.addEventListener('snapshot'"));
        assertTrue(events.contains("stream.addEventListener('decode_event'"));
        assertTrue(events.contains("if (!events.has(event.eventId)) order.unshift(event.eventId)"));
        assertTrue(events.contains("while (order.length > 200)"));
        assertTrue(events.contains("['ENCRYPTED_VOICE', 'Encrypted voice']"));
        assertTrue(events.contains("['REGISTRATION', 'Registrations']"));
        assertTrue(messages.contains("liveConnection('/live/messages', parameters)"));
        assertTrue(messages.contains("stream.addEventListener('decode_message'"));
        assertTrue(messages.contains("active && !collapsed && !paused && !document.hidden"));
        assertTrue(channel.contains("binaryFrameConnection('/live/channel-diagnostics', parameters"));
        assertEquals(channel.indexOf("binaryFrameConnection('/live/channel-diagnostics', parameters"),
            channel.lastIndexOf("binaryFrameConnection('/live/channel-diagnostics', parameters"));
        assertTrue(channel.contains("frame.type === DIAGNOSTIC_FRAME_TYPES.CHANNEL_SIGNAL"));
        assertTrue(channel.contains("frame.type !== DIAGNOSTIC_FRAME_TYPES.CHANNEL_SYMBOLS"));
        assertFalse(channel.contains("client_id"));
        assertTrue(channel.contains("diagnosticGrid.append(signalDiagnostic.card, symbolDiagnostic.card)"));
        assertTrue(channel.contains("let signalSequence = 0"));
        assertTrue(channel.contains("let symbolSequence = 0"));
        assertTrue(channel.contains("['Center'"));
        assertTrue(channel.contains("['Peak'"));
        assertTrue(channel.contains("['Protocol'"));
        assertFalse(channel.contains("['Span'"));
        assertFalse(channel.contains("['Bins'"));
        assertFalse(channel.contains("['Selected TS'"));
        assertFalse(channel.contains("['Visible'"));
        assertFalse(channel.contains("['Range'"));
        assertFalse(channel.contains("['Age'"));
        assertFalse(channel.contains("ageText"));
        assertTrue(channel.contains("maximumVisibleSymbols"));
        assertTrue(channel.contains("const denominator = Math.max(1, symbolValues.length - 1)"));
        assertTrue(channel.contains("symbolsNeedClear = true"));
        assertTrue(channel.contains("if (symbolCount + incoming.length > capacity)"));
        assertTrue(channel.contains("symbolValues = new Float32Array(maximumSymbols)"));
        assertFalse(channel.contains("symbolValues.splice"));
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
        assertTrue(systems.contains("onSelectionChange(liveEventSelection(value, currentRow))"));
        assertTrue(systems.contains("if (selectedRowKey !== null && !incoming.has(selectedRowKey)) clearSelection()"));
        assertTrue(css.contains("grid-template-rows: minmax(0, 1fr) minmax(0, 1fr)"));
        assertTrue(css.contains(".live-split.details-collapsed"));
        assertTrue(css.contains(".live-details.collapsed .live-details-body"));
        assertTrue(css.contains(".live-events-table tbody tr:hover"));
        assertTrue(css.contains(".channel-diagnostic-canvas"));
        assertTrue(css.contains("grid-template-columns: repeat(2, minmax(0, 1fr))"));
        assertTrue(css.contains(".channel-diagnostic-grid"));
    }

    @Test
    void opensDemandDrivenTunerSpectrumAndWaterfallInSharedModal() throws Exception
    {
        String source = source();
        String binary = function(source, "function binaryFrameConnection(path, parameters = {}, callbacks = {})");
        String tuner = function(source, "function showTunerSpectrumModal(returnFocusSelector = '#open-tuner-spectrum')");
        String live = function(source, "async function renderLive()");
        String css = Files.readString(APP_CSS);

        assertTrue(live.contains("'Tuner Spectrum'"));
        assertTrue(live.contains("spectrum.addEventListener('click', () => showTunerSpectrumModal())"));
        assertTrue(tuner.contains("openReadOnlyModal('Tuner Spectrum'"));
        assertTrue(tuner.contains("api('/api/tuner-diagnostics/targets')"));
        assertTrue(tuner.contains("binaryFrameConnection('/live/tuner-diagnostics'"));
        assertTrue(tuner.contains("frame.type !== DIAGNOSTIC_FRAME_TYPES.TUNER_FFT"));
        assertTrue(tuner.contains("const shouldRun = () => !disposed && !paused && !document.hidden"));
        assertTrue(tuner.contains("const waterfallBuffer = document.createElement('canvas')"));
        assertTrue(tuner.contains("const firstBin ="));
        assertTrue(tuner.contains("for (let bin = firstBin; bin < lastBin; bin += 1)"));
        assertTrue(tuner.contains("nextWaterfallRow = (nextWaterfallRow - 1 + ringHeight) % ringHeight"));
        assertTrue(tuner.contains("cleanup: () =>"));
        assertTrue(tuner.contains("closeStream()"));
        assertTrue(css.contains(".tuner-spectrum-modal"));
        assertTrue(css.contains(".tuner-spectrum-plot"));
        assertTrue(binary.contains("await reader.cancel().catch(() => {})"));
        assertTrue(binary.contains("attemptController.abort()"));
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
        assertTrue(events.contains("const shouldRun = () => !paused && selection?.configurationId"));
        assertTrue(events.contains("pause.textContent = paused ? 'Resume' : 'Pause'"));
        assertTrue(events.contains("messagesController.setPaused(paused)"));
        assertTrue(events.contains("channelController.setPaused(paused)"));
        assertTrue(messages.contains("active && !collapsed && !paused && !document.hidden"));
        assertTrue(messages.contains("setPaused(value) { paused = value; sync(); }"));
        assertTrue(channel.contains("active && !collapsed && !paused && !document.hidden"));
        assertTrue(channel.contains("setPaused(value) { paused = value; sync(); }"));
    }

    private static String source() throws Exception
    {
        assertTrue(Files.isRegularFile(APP_JAVASCRIPT), () -> "Missing " + APP_JAVASCRIPT.toAbsolutePath());
        return Files.readString(APP_JAVASCRIPT);
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
