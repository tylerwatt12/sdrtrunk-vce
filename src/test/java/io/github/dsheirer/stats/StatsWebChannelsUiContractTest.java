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

/** Protects the one channel directory and channel-owned detail resources. */
class StatsWebChannelsUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void usesOneModernCatalogForReadOnlyAndChannelAdministrators() throws Exception
    {
        String source = source();
        String setup = function(source, "async function renderChannelSetup()");
        String directory = function(source, "async function renderRadioSystems()");
        String catalog = function(source, "async function renderModernChannelCatalog(renderContext, editable)");
        String configurationRequest = function(source,
            "async function requestChannelConfigurationJson(path, options = {})");
        String columns = function(source,
            "function channelAdminColumns(selected, state, statusHost, editable, selectionChanged, " +
                "renderSelectionHeader)");

        assertTrue(setup.contains("renderModernChannelCatalog(renderContext, true)"));
        assertTrue(directory.contains("renderModernChannelCatalog(renderContext, false)"));
        assertTrue(catalog.contains("editable ? '/api/v1/admin/channels' : '/api/v1/channel-catalog'"));
        assertTrue(catalog.contains("requestChannelConfigurationJson(catalogPath"));
        assertTrue(catalog.contains("requestChannelConfigurationJson('/api/v1/admin/channels/protocols'"));
        assertTrue(catalog.contains("requestChannelConfigurationJson('/api/v1/admin/channels/options'"));
        assertTrue(configurationRequest.contains("error?.code !== 'configuration_loading'"));
        assertTrue(configurationRequest.contains("CHANNEL_CONFIGURATION_RETRY_DELAYS_MILLISECONDS[attempt]"));
        assertTrue(configurationRequest.contains("waitForRequestRetry(delay, signal)"));
        assertTrue(catalog.contains("exportCsvLink('channels')"));
        assertTrue(catalog.contains("channelSummaryCards(catalog, editable)"));
        assertTrue(catalog.contains("tableController.reconcileRows"));
        assertTrue(catalog.contains("layoutMenuHost: toolbar"));
        assertTrue(catalog.contains("editable ? 'channel-catalog-admin-v1' : " +
            "'channel-catalog-readonly-v1'"));
        assertTrue(columns.contains("row.processing_state === 'RUNNING'"));
        assertTrue(columns.contains("if (editable) columns.push"));
        assertTrue(columns.contains("label: 'Startup order'"));
        assertTrue(columns.contains("row.alias_list_name"));
        assertTrue(columns.contains("row.editable !== false"));
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
        String editor = function(source, "function channelEditorControl(field, profile, options, channel)");
        String dependencies = function(source, "function channelEditorDependencies(form)");
        String modal = function(source,
            "async function openChannelEditorModal(mode = 'create', configurationId = null, prefetched = null)");
        String css = StatsWebStylesheetTestSupport.readAll();

        for(String helper: new String[]{"uiActionButton", "uiSelect", "uiToggle", "uiPill", "uiSegmentedControl"})
        {
            assertTrue(source.contains("function " + helper + "("), () -> "Missing reusable " + helper);
        }
        assertTrue(editor.contains("uiToggle(Boolean(value ?? field.default), field.label)"));
        assertTrue(editor.contains("channelListEditor(field, value)"));
        assertTrue(editor.contains("channelMapEditor(field, value)"));
        assertTrue(dependencies.contains("frequency_select"));
        assertTrue(dependencies.contains("channelEditorVisibility(form)"));
        assertTrue(modal.contains("channelRestoreProtocolDefaults(form, profile)"));
        assertTrue(modal.contains("Save & restart"));
        assertFalse(modal.contains("action: 'STOP'"));
        assertTrue(css.contains(".ui-toggle input:checked + .ui-toggle-track"));
        assertTrue(css.contains(".channel-catalog-table tbody tr.selected"));
        assertTrue(css.contains("grid-template-columns: minmax(0, 1fr)"));
        assertTrue(css.contains(".channel-catalog-table-host {\n  width: 100%;\n  min-width: 0;\n" +
            "  max-width: 100%;\n  overflow: hidden;"));
        assertTrue(css.contains(".channel-catalog-table-wrap {\n  width: 100%;\n  min-width: 0;\n" +
            "  max-width: 100%;\n  max-height:"));
        assertTrue(css.contains("overflow: auto;"));
        assertTrue(css.contains("@media (max-width: 720px)"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] .channel-admin-catalog .link-button"));
        assertTrue(css.contains(":not(.ui-button):not(.ui-segmented-option)"));
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
