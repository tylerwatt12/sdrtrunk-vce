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

/** Protects the unified public Radio Directory and the separate administrator workspaces. */
class StatsWebRadioInformationArchitectureUiContractTest
{
    private static final Path APP = Path.of("stats-web", "assets", "app.js");
    private static final Path ROUTES = Path.of("stats-web", "assets", "core", "routes.js");
    private static final Path INDEX = Path.of("stats-web", "index.html");

    @Test
    void separatesRadioBrowsingFromChannelManagement() throws Exception
    {
        String app = readText(APP);
        String routes = readText(ROUTES);
        String index = readText(INDEX);

        assertTrue(routes.contains("id: 'dashboard', label: 'Main'"));
        assertFalse(routes.contains("id: 'radio-systems', label: 'Radio Directory'"));
        assertFalse(routes.contains("id: 'identities', label: 'Identities'"));
        assertTrue(routes.contains("id: 'channel-setup', label: 'Channels'"));
        assertTrue(routes.contains("access: 'admin-channels'"));
        assertTrue(index.contains("data-view=\"dashboard\""));
        assertFalse(index.contains("data-view=\"radio-systems\""));
        assertFalse(index.contains("data-nav-group=\"radio\""));
        assertFalse(index.contains("data-view=\"identities\""));
        assertTrue(index.contains("data-view=\"channel-setup\""));
        assertTrue(index.contains("<summary>Manage</summary>"));
        assertFalse(index.contains("data-view=\"channels\""));
        assertFalse(routes.contains("id: 'channels'"));
        assertFalse(routes.contains("id: 'configuration'"));
        assertFalse(routes.contains("id: 'hardware'"));
        assertFalse(app.contains("if (view === 'channels' || view === 'radio-systems')"));
        assertFalse(app.contains("if (view === 'identities')"));
        assertTrue(app.contains("renderNestedRadioDirectory(renderContext)"));
        assertFalse(app.contains("editable ? 'Channels' : 'Radio Directory'"));
        assertFalse(app.contains("if (editable) columns.push"));
    }

    @Test
    void exposesAliasCoverageInsideTheDirectoryAndNestedReceiverSettings() throws Exception
    {
        String app = readText(APP);
        String css = StatsWebStylesheetTestSupport.readAll();

        assertTrue(app.contains("apiPage('/api/v1/identities/lists'"));
        assertTrue(app.contains("/overview`"));
        assertTrue(app.contains("/unassigned`"));
        assertTrue(app.contains("function renderAliasCoverageDirectory(renderContext, embedded = false)"));
        assertFalse(app.contains("See aliases that activity-only views cannot show"));
        assertFalse(app.contains("Where this Alias List is used"));
        assertTrue(app.contains("node('h2', '', 'Configuration scope')"));
        assertTrue(app.contains("node('h2', '', 'Alias inventory')"));
        assertTrue(app.contains("const navigateCoverage = (target, options = {}) => routeFoundation.navigate"));
        assertTrue(app.contains("directory.host.replaceChildren(present(model))"));
        assertTrue(app.contains("scrollToTable: Boolean(link.closest('.pager'))"));
        assertTrue(app.contains("value: 'zero_calls'"));
        assertTrue(app.contains("value: 'never_heard'"));
        assertTrue(app.contains("value: 'talkgroup', label: 'Talkgroups'"));
        assertTrue(app.contains("value: 'radio', label: 'Radios'"));
        assertTrue(app.contains("entityTarget(row.entity_ref, { channel: row.identity_type === 'radio' ? 'radios' : 'groups' })"));
        assertFalse(app.contains("identitySummaryValue(label, row.identity_key || ''"));
        assertTrue(app.contains("function adminSettingsTree(groups, active)"));
        assertTrue(app.contains("{ label: 'Receiving & output', items: ["));
        assertTrue(app.contains("id: 'protocol-p25'"));
        assertFalse(app.contains("id: 'protocol-dmr'"));
        assertFalse(app.contains("id: 'protocol-nxdn'"));
        assertFalse(app.contains("id: 'protocol-am'"));
        assertFalse(app.contains("id: 'protocol-nbfm'"));
        assertFalse(app.contains("Traffic-row idle delay"));
        assertTrue(app.contains("Mark a traffic row idle after (milliseconds)"));
        assertFalse(app.contains("This does not keep ' +\n        'the call, tuner, or traffic channel active."));
        assertTrue(css.contains(".admin-settings-shell {"));
        assertFalse(css.contains(".admin-settings-nested-branch"));
        assertFalse(css.contains(".alias-coverage-guidance"));
        assertTrue(css.contains(".alias-coverage-scope-list"));
        assertTrue(css.contains(".alias-coverage-table-header"));
        assertTrue(css.contains(".ui-time-pair"));
    }

    private static String readText(Path path) throws Exception
    {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }
}
