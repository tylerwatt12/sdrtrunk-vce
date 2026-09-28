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

/** Protects the compact, per-user Live and table-presentation controls. */
class StatsWebPresentationUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path TABLE_DEFAULTS = Path.of("stats-web", "assets", "core", "table-defaults.js");

    @Test
    void keepsOnlyReceiverTimingInReceiverSettingsAndMovesRowPresentationToLive() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String receiver = function(source, "async function renderAdminReceiverBehaviorSettings()");
        String live = function(source, "function openLivePresentationSettings(returnFocusSelector = null)");

        assertFalse(receiver.contains("Traffic-row idle delay"));
        assertTrue(receiver.contains("formField('Mark a traffic row idle after (milliseconds)'"));
        assertFalse(receiver.contains("receiver-wide presentation timing"));
        assertTrue(receiver.contains("traffic_grant_age_out_milliseconds"));
        assertFalse(receiver.contains("retain_idle_call_details"));
        assertFalse(receiver.contains("clear_voice_decode_quality_on_call_end"));
        for(String setting: new String[]{"show_only_active_trunked_channels", "retain_last_call_on_idle_rows",
            "clear_voice_quality_when_idle"})
        {
            assertTrue(live.contains(setting), () -> "Missing Live preference " + setting);
        }
        assertTrue(live.contains("These choices affect only this signed-in user."));
        assertTrue(live.contains("Conventional channels are always shown."));
    }

    @Test
    void usesOneAccessibleColumnsIconAndPlacesHighTrafficControlsInSectionHeaders() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");
        String discover = function(source,
            "function renderObservedGroupIdentities(main, page, selectedList, renderContext, updateSummary)");
        String scanList = function(source,
            "async function renderScanListMembers(main, scanListCatalog, scanList, renderContext)");
        String aliases = function(source, "async function renderAliases()");
        String channelGroups = function(source, "async function channelTopGroupsSection(channel)");
        String channelFrequencies = function(source,
            "async function renderTrunkedChannelFrequencies(channel, renderContext, host)");
        String channelNeighbors = function(source, "async function renderChannelNeighbors(channel, renderContext)");
        String channels = function(source, "async function renderModernChannelCatalog(renderContext, editable)");

        assertTrue(source.contains("const trigger = iconButton('icon-columns', 'Choose table columns'"));
        assertTrue(source.contains("function setIconButton(button, iconId, label)"));
        assertTrue(source.contains("button.setAttribute('aria-label', label)"));
        assertTrue(source.contains("button.title = label"));
        assertFalse(source.contains("inline ? '' : 'Columns'"));
        assertTrue(activity.contains("layoutMenuHost: titleActions"));
        assertTrue(activity.contains("section(title, activityTable, titleActions)"));
        assertTrue(activity.contains("titleActions.prepend(refreshControls)"));
        assertTrue(discover.contains("layoutMenuHost: actions"));
        assertTrue(discover.contains("section('Observed Groups', host, actions)"));
        assertTrue(scanList.contains("layoutMenuHost: actions"));
        assertTrue(aliases.contains("layoutMenuHost: actions"));
        assertTrue(source.contains("const actions = sectionActionHost(action);"));
        assertTrue(source.contains("layoutMenuHost: actions }"));
        assertTrue(channelGroups.contains("const titleActions = sectionActionHost(rangeControl.controls)"));
        assertTrue(channelGroups.contains("controller: tableController, layoutMenuHost: titleActions"));
        assertTrue(channelGroups.indexOf("cleanupTableLayoutMenu(tableController)") <
            channelGroups.indexOf("host.replaceChildren(node('div', 'loading'"));
        assertTrue(channelFrequencies.contains("layoutMenuHost: directory.titleActions"));
        assertTrue(channelNeighbors.contains("layoutMenuHost: directory.titleActions"));
        assertTrue(channels.contains("tableClass: 'channel-catalog-table'"));
        assertTrue(channels.contains("controller: tableController"));
        assertTrue(source.contains("function tableSection(title, rows, columns"));
        assertTrue(source.contains("{ ...options, layoutMenuHost: actions }"));
    }

    @Test
    void placesCompactLiveTableActionsInOneSharedDetailsHeader() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String liveMessages = function(source, "function liveMessagesPane()");
        String liveEvents = function(source, "function liveEventsPanel(onCollapse)");

        assertTrue(liveMessages.contains("layoutMenuHost: columnsHost"));
        assertTrue(liveMessages.contains("actions.append(columnsHost, filters.element)"));
        assertTrue(liveEvents.contains("layoutMenuHost: eventColumnsHost"));
        assertTrue(liveEvents.contains("eventActions.append(eventColumnsHost, filters.element)"));
        assertTrue(liveEvents.contains("iconButton('icon-pause', 'Pause live details'"));
        assertTrue(liveEvents.contains("ui-icon-button-compact live-details-pause"));
        assertTrue(liveEvents.contains(
            "paneActionsHost.append(eventActions, messagesController.actions, channelController.actions)"));
        assertFalse(liveEvents.contains("live-events-toolbar"));
        assertFalse(liveEvents.contains("live-event-selection"));
    }

    @Test
    void usesTheEstablishedReceiverAndLiveDetailColumnRatiosAsDefaults() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String defaults = readText(TABLE_DEFAULTS);

        assertTrue(source.contains("import * as tableDefaults from './core/table-defaults.js?v=9'"));
        assertTrue(defaults.contains("'dashboard-receivers': { widths: {"));
        assertTrue(defaults.contains("name: 442"));
        assertTrue(defaults.contains("'live-events': { widths: {"));
        assertTrue(defaults.contains("details: 864"));
        assertTrue(defaults.contains("'live-messages': { widths: {"));
        assertTrue(defaults.contains("message: 1200"));
        assertTrue(source.contains("const defaultLayout = tableDefaults.layout(tableType, declaredColumns)"));
        assertTrue(source.contains("const storedLayout = options.layout || " +
            "activeUserPreferences().tables[tableType] || defaultLayout"));
    }

    @Test
    void keepsDiscoverColumnsAtomicAndDateInputsInsideTheirFilters() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String css = StatsWebStylesheetTestSupport.readAll();
        String discover = function(source,
            "function renderObservedGroupIdentities(main, page, selectedList, renderContext, updateSummary)");
        String identity = function(source, "function observedGroupIdentityValue(row)");
        String filters = function(source, "function aliasEditorFilterToolbar(aliasPage, options = null)");
        String detail = function(source, "function observedGroupIdentityDetail(row, selectedList)");

        for(String label: new String[]{"'Identity'", "'System / Channel'", "'Calls'", "'Signaling'",
            "'Last Seen'"})
        {
            assertTrue(discover.contains(label), () -> "Missing Discover column " + label);
        }
        assertFalse(discover.contains("'Alias Match'"));
        assertFalse(source.contains("function observedGroupIdentityCounts("));
        assertTrue(discover.contains("aliasMetricValue(row, 'logical_call_count')"));
        assertTrue(discover.contains("aliasMetricValue(row, 'signaling_observation_count')"));
        assertTrue(identity.contains("Covered by range"));
        assertTrue(detail.contains("recorded_logical_call_count"));
        assertTrue(detail.contains("stream_submitted_logical_call_count"));
        assertTrue(detail.contains("encrypted_logical_call_count"));
        assertTrue(filters.contains("'alias-filter alias-date-filter ui-field'"));
        assertTrue(filters.contains("filterGroup('Find aliases', 'alias-filter-group-identity'"));
        assertTrue(filters.contains("filterGroup('Call handling', 'alias-filter-group-behavior'"));
        assertTrue(filters.contains("filterGroup('Observed activity', 'alias-filter-group-observed'"));
        assertTrue(css.contains(".alias-editor-filter-toolbar .alias-date-filter"));
        assertTrue(css.contains("width: 100%;\n  min-width: 0;\n  box-sizing: border-box;"));
    }

    @Test
    void usesReusableMetricCardsAndIndependentObservedDetailColumns() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String css = StatsWebStylesheetTestSupport.readAll();
        String detail = function(source, "function observedGroupIdentityDetail(row, selectedList)");

        assertTrue(css.contains("grid-template-columns: repeat(auto-fill, minmax(min(100%, 170px), 1fr))"));
        assertTrue(css.contains("background: var(--surface-2);\n  border: 1px solid var(--line);\n" +
            "  border-radius: var(--radius-control);"));
        assertTrue(css.contains(".ui-metric-copy {\n  min-width: 0;"));
        assertTrue(css.contains(".ui-metric-copy > strong {"));
        assertTrue(detail.contains("node('div', 'observed-group-identity-detail-column')"));
        assertTrue(detail.contains("wrapper.append(identityColumn, activityColumn)"));
        assertTrue(css.contains(".observed-group-identity-detail-column {"));
        assertTrue(css.contains("flex-direction: column;"));
    }

    private static String function(String source, String signature)
    {
        int start = source.indexOf(signature);
        if(start < 0)
        {
            throw new IllegalArgumentException("Missing " + signature);
        }
        int brace = source.indexOf('{', start);
        int depth = 0;
        boolean single = false;
        boolean dual = false;
        boolean template = false;
        boolean escaped = false;
        for(int index = brace; index < source.length(); index++)
        {
            char current = source.charAt(index);
            if(escaped)
            {
                escaped = false;
                continue;
            }
            if((single || dual || template) && current == '\\')
            {
                escaped = true;
                continue;
            }
            if(!dual && !template && current == '\'') single = !single;
            else if(!single && !template && current == '"') dual = !dual;
            else if(!single && !dual && current == '`') template = !template;
            else if(!single && !dual && !template)
            {
                if(current == '{') depth++;
                else if(current == '}' && --depth == 0) return source.substring(start, index + 1);
            }
        }
        throw new IllegalArgumentException("Unclosed " + signature);
    }

    private static String readText(Path path) throws Exception
    {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }
}
