/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Protects channel administration and channel-owned detail resources. */
class StatsWebChannelsUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void usesOneModernCatalogForChannelAdministrators() throws Exception
    {
        String source = source();
        String setup = function(source, "async function renderChannelSetup()");
        String catalog = function(source, "async function renderModernChannelCatalog(renderContext)");
        String configurationRequest = function(source,
            "async function requestChannelConfigurationJson(path, options = {})");
        String columns = function(source, "function channelAdminColumns(");

        assertTrue(setup.contains("renderModernChannelCatalog(renderContext)"));
        assertFalse(source.contains("function renderRadioSystems()"));
        assertTrue(catalog.contains("requestChannelConfigurationJson('/api/v1/admin/channels'"));
        assertTrue(catalog.contains("requestChannelConfigurationJson('/api/v1/admin/channels/protocols'"));
        assertTrue(catalog.contains("requestChannelConfigurationJson('/api/v1/admin/channels/options'"));
        assertTrue(configurationRequest.contains("error?.code !== 'configuration_loading'"));
        assertTrue(configurationRequest.contains("CHANNEL_CONFIGURATION_RETRY_DELAYS_MILLISECONDS[attempt]"));
        assertTrue(configurationRequest.contains("waitForRequestRetry(delay, signal)"));
        assertTrue(catalog.contains("exportCsvLink('channels')"));
        assertTrue(catalog.contains("channelSummaryCards(catalog, true)"));
        assertTrue(catalog.contains("tableController.reconcileRows"));
        assertTrue(catalog.contains("layoutMenuHost: toolbar"));
        assertTrue(catalog.contains("type: 'channel-catalog-admin-v2'"));
        assertTrue(columns.contains("row.processing_state === 'RUNNING'"));
        assertFalse(columns.contains("if (editable)"));
        assertTrue(columns.contains("fullLabel: 'Startup order'"));
        assertTrue(columns.contains("row.alias_list_name"));
        assertTrue(columns.contains("row.editable !== false"));
        assertTrue(columns.contains("channelInlineNavigation(row"));
        assertFalse(columns.contains("id: 'protocol'"));
        assertFalse(columns.contains("id: 'live'"));
        assertFalse(columns.contains("id: 'channel'"));
        assertTrue(columns.contains("id: 'select'"));
        assertTrue(columns.contains("essential: true, fixed: true, renderHeader: renderSelectionHeader"));
        assertTrue(catalog.contains("const renderSelectionHeader = () =>"));
        assertTrue(catalog.contains("updateSelection, renderSelectionHeader"));
        assertTrue(catalog.contains("action('Enable auto-start', 'icon-plus', 'ENABLE_AUTO_START')"));
        assertTrue(catalog.contains("action('Disable auto-start', 'icon-clear-queue', 'DISABLE_AUTO_START')"));
        assertTrue(catalog.contains("selectedRows.every((row) => row.auto_start_order != null)"));
        assertTrue(catalog.contains("selectedRows.every((row) => row.auto_start_order == null)"));
        assertFalse(catalog.contains("querySelector('thead th')"));
        assertFalse(source.contains("/api/v1/conventional-channels"));
        assertFalse(source.contains("/api/v1/conventional-contexts"));
    }

    @Test
    void groupsChannelsByProtocolWithoutTheOldTopologySelector() throws Exception
    {
        String source = source();
        String catalog = function(source, "async function renderModernChannelCatalog(renderContext)");
        String protocolGroup = function(source, "function channelProtocolGroup(row)");
        String protocolOrder = function(source, "function channelProtocolOrder(left, right)");
        String table = function(source, "function table(rows, columns, emptyText = 'No rows', options = {})");
        String groupRow = function(source,
            "function renderTableRowGroup(group, count, columnCount, noun = 'row', collapsed = false,");

        assertTrue(catalog.contains("pageHeader('Channels'"));
        assertTrue(catalog.contains("label: 'All statuses'"));
        assertTrue(catalog.contains("statusFilter.setAttribute('aria-label', 'Filter channels by status')"));
        assertTrue(catalog.contains("rowGroup: channelProtocolGroup, rowGroupNoun: 'channel'"));
        assertTrue(catalog.contains("{ value: 'grouped', label: 'Grouped' }"));
        assertTrue(catalog.contains("{ value: 'auto-start', label: 'Startup order' }"));
        assertTrue(catalog.contains("viewToggle.setAttribute('aria-label', 'Channel table view')"));
        assertTrue(catalog.contains("activeCatalogView === 'grouped' ?"));
        assertTrue(catalog.contains("activeCatalogView === 'auto-start' ? channelAutoStartOrder : " +
            "channelProtocolOrder"));
        assertTrue(catalog.contains("wrapper: channelTable || undefined"));
        assertTrue(catalog.contains("revealRowGroups: () => Boolean(search.value.trim())"));
        assertTrue(catalog.contains("Select all matching channels"));
        assertTrue(catalog.contains("rows.sort(activeCatalogView === 'auto-start' ? channelAutoStartOrder : " +
            "channelProtocolOrder)"));
        assertFalse(catalog.contains("value: 'trunked'"));
        assertFalse(catalog.contains("value: 'conventional'"));
        assertFalse(catalog.contains("uiSegmentedControl(filterEntries"));
        assertTrue(protocolGroup.contains("row.protocol_label"));
        assertTrue(protocolGroup.contains("row.protocol_id"));
        assertTrue(protocolGroup.contains("unsupported.${unsupportedLabel}"));
        assertTrue(protocolOrder.contains("leftGroup.label.localeCompare"));
        assertTrue(protocolOrder.contains("leftGroup.key.localeCompare(rightGroup.key)"));
        assertTrue(table.contains("typeof options.rowGroup === 'function'"));
        assertTrue(table.contains("renderTableRowGroup(group"));
        assertTrue(groupRow.contains("heading.scope = 'rowgroup'"));
        assertTrue(groupRow.contains("disclosure.setAttribute('aria-expanded'"));
        assertTrue(groupRow.contains("table-row-group-disclosure"));
        assertTrue(groupRow.contains("count === 1 ? noun : `${noun}s`"));
    }

    @Test
    void usesTheSharedHeaderOffsetForStickyChannelActions() throws Exception
    {
        String source = source();
        String catalog = function(source, "async function renderModernChannelCatalog(renderContext)");
        String stickyOffset = function(source, "function installStickyHeaderOffset()");
        String css = StatsWebStylesheetTestSupport.readAll();

        assertTrue(stickyOffset.contains("document.querySelector('.app-header')"));
        assertTrue(stickyOffset.contains("document.documentElement.style.setProperty('--app-header-offset'"));
        assertTrue(stickyOffset.contains("new ResizeObserver(update).observe(header)"));
        assertTrue(stickyOffset.contains("window.addEventListener('resize', update)"));
        assertTrue(catalog.contains("loading.element.classList.add('channel-catalog-section')"));
        assertFalse(catalog.contains("positionSelectionBar"));
        assertTrue(css.contains(".ui-selection-bar {\n  position: sticky;\n" +
            "  top: calc(var(--app-header-offset, 0px) + var(--space-2));"));
        assertTrue(css.contains(".channel-catalog-section {\n  overflow: clip;"));
    }

    @Test
    void usesConfigurationIdForEveryChannelResource() throws Exception
    {
        String source = source();
        String render = function(source, "async function renderChannel()");
        String groups = function(source, "async function renderChannelGroupIdentities(configurationId)");
        String radios = function(source, "async function renderChannelRadios(configurationId)");

        assertTrue(render.contains("route.get('configuration_id')"));
        assertTrue(render.contains("api(channelApiPath(configurationId))"));
        assertTrue(groups.contains("channelApiPath(configurationId, 'group-identities')"));
        assertTrue(groups.contains("exportCsvLink('channel-group-identities'"));
        assertTrue(radios.contains("channelApiPath(configurationId, 'radios')"));
        assertTrue(radios.contains("exportCsvLink('channel-radios'"));
        assertFalse(source.contains("site.guid"));
        assertFalse(source.contains("context_key"));
    }

    @Test
    void exposesReusableControlsAndProtocolDrivenEditorBehavior() throws Exception
    {
        String source = source();
        String editor = function(source,
            "function channelEditorControl(field, profile, options, channel, protocolCatalog)");
        String dependencies = function(source, "function channelEditorDependencies(form)");
        String modal = function(source,
            "async function openChannelEditorModal(mode = 'create', configurationId = null, prefetched = null)");
        String css = StatsWebStylesheetTestSupport.readAll();

        for(String helper: new String[]{"uiActionButton", "uiSelect", "uiToggle", "uiToggleField", "uiPill",
            "uiSegmentedControl"})
        {
            assertTrue(source.contains("function " + helper + "("), () -> "Missing reusable " + helper);
        }
        assertTrue(editor.contains("uiToggle(Boolean(value ?? field.default), field.label)"));
        assertTrue(editor.contains("dataControl.dataset.channelPreservedValue"));
        assertTrue(editor.contains("setUiToggle(dataControl, false)"));
        assertTrue(editor.contains("dataControl.disabled = true"));
        assertTrue(editor.contains("channelListEditor(field, value)"));
        assertTrue(editor.contains("channelMapEditor(field, value)"));
        assertTrue(dependencies.contains("frequency_select"));
        assertTrue(dependencies.contains("channelEditorVisibility(form)"));
        assertTrue(modal.contains("requestJson('/api/v1/admin/channels/protocols'"));
        assertFalse(modal.contains("prefetched?.protocols"));
        assertTrue(modal.contains("channelRestoreProtocolDefaults(form, editorProfile)"));
        assertTrue(modal.contains("Save & restart"));
        assertFalse(modal.contains("action: 'STOP'"));
        assertTrue(css.contains(".ui-toggle input:checked + .ui-toggle-track"));
        assertTrue(css.contains(".channel-catalog-table tbody tr.selected"));
        assertTrue(css.contains("grid-template-columns: minmax(0, 1fr)"));
        assertTrue(css.contains(".channel-catalog-table-host {\n  width: 100%;\n  min-width: 0;\n" +
            "  max-width: 100%;\n  overflow: visible;"));
        assertTrue(css.contains(".channel-catalog-table-wrap {\n  width: 100%;\n  min-width: 0;\n" +
            "  max-width: 100%;\n  overflow-x: auto;\n  overflow-y: visible;"));
        assertFalse(css.contains("max-height: min(70dvh, 720px);"));
        assertTrue(css.contains("@media (max-width: 720px)"));
        assertTrue(css.contains(".channel-name-actions .link-button"));
    }

    @Test
    void hidesSyntheticAnalogGroupTablesButKeepsDigitalGroupsAndDmrSlots() throws Exception
    {
        String source = source();
        String tabs = function(source, "function channelTabItems(channel)");
        String groupColumns = function(source, "function channelGroupIdentityColumns()");

        assertTrue(tabs.contains("['AM', 'NBFM']"));
        assertTrue(tabs.contains("if (!analog && channelCapability(channel, 'group_identities'))"));
        assertTrue(groupColumns.contains("groupIdentityLabel(row)"));
        assertTrue(groupColumns.contains("render: (row) => groupIdentityLink(row)"));
        assertTrue(groupColumns.contains("render: (row) => groupIdentityAliasLink(row)"));
        assertTrue(groupColumns.contains("key: 'timeslot'"));
        assertTrue(groupColumns.contains("label: 'Slot'"));
    }

    @Test
    void linksChannelIdentityTablesToTheirDrillDownPages() throws Exception
    {
        String source = source();
        String groupColumns = function(source, "function channelGroupIdentityColumns()");
        String radioColumns = function(source, "function channelRadioColumns()");

        assertTrue(groupColumns.contains("radioLink(row, row.last_source_radio_id)"));
        assertTrue(groupColumns.contains("radioLink(row, row.last_source_radio_id, row.last_source_alias_name)"));
        assertTrue(radioColumns.contains("render: (row) => radioLink(row)"));
        assertTrue(radioColumns.contains("radioLink(row, undefined, aliasLabel(row))"));
        assertTrue(radioColumns.contains("groupIdentityLink(row, row.last_talkgroup_id)"));
        assertTrue(radioColumns.contains("groupIdentityLink(row, row.last_talkgroup_id, row.last_talkgroup_alias_name)"));
        assertTrue(radioColumns.contains("radioLink(row, row.last_peer_radio_id)"));
        assertTrue(radioColumns.contains("radioLink(row, row.last_peer_radio_id, row.last_peer_alias_name)"));
    }

    private static String source() throws Exception
    {
        assertTrue(Files.isRegularFile(APP_JAVASCRIPT), () -> "Missing " + APP_JAVASCRIPT.toAbsolutePath());
        return Files.readString(APP_JAVASCRIPT).replace("\r\n", "\n").replace('\r', '\n');
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
