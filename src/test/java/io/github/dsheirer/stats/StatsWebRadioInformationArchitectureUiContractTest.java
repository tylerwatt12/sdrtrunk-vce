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

/** Protects the public radio directories and the separate administrator workspaces. */
class StatsWebRadioInformationArchitectureUiContractTest
{
    private static final Path APP = Path.of("stats-web", "assets", "app.js");
    private static final Path ROUTES = Path.of("stats-web", "assets", "core", "routes.js");
    private static final Path INDEX = Path.of("stats-web", "index.html");

    @Test
    void separatesRadioBrowsingFromChannelManagement() throws Exception
    {
        String app = Files.readString(APP);
        String routes = Files.readString(ROUTES);
        String index = Files.readString(INDEX);

        assertTrue(routes.contains("id: 'radio-systems', label: 'Radio Directory'"));
        assertTrue(routes.contains("id: 'identities', label: 'Identities'"));
        assertTrue(routes.contains("id: 'channel-setup', label: 'Channel Setup'"));
        assertTrue(routes.contains("access: 'admin-channels'"));
        assertTrue(index.contains("data-view=\"radio-systems\""));
        assertTrue(index.contains("data-view=\"identities\""));
        assertTrue(index.contains("data-view=\"channel-setup\""));
        assertTrue(index.contains("<summary>Manage</summary>"));
        assertFalse(index.contains("data-view=\"channels\""));
        assertTrue(app.contains("if (view === 'channels')"));
        assertTrue(app.contains("route.set('view', 'radio-systems')"));
        assertTrue(app.contains("editable ? 'Channel Setup' : 'Radio Directory'"));
        assertTrue(app.contains("if (editable) columns.push"));
    }

    @Test
    void exposesReadOnlyIdentitiesAndNestedReceiverSettings() throws Exception
    {
        String app = Files.readString(APP);
        String css = StatsWebStylesheetTestSupport.readAll();

        assertTrue(app.contains("apiPage('/api/v1/identities'"));
        assertTrue(app.contains("value: 'talkgroup', label: 'Talkgroups'"));
        assertTrue(app.contains("value: 'radio', label: 'Radios'"));
        assertTrue(app.contains("function adminSettingsTree(groups, active)"));
        assertTrue(app.contains("{ label: 'Protocols', items: ["));
        assertTrue(app.contains("id: 'protocol-p25'"));
        assertTrue(app.contains("id: 'protocol-dmr'"));
        assertTrue(app.contains("id: 'protocol-nxdn'"));
        assertTrue(app.contains("id: 'protocol-am'"));
        assertTrue(app.contains("id: 'protocol-nbfm'"));
        assertTrue(app.contains("Live traffic-row idle delay"));
        assertTrue(app.contains("This does not keep ' +\n        'the call, tuner, or traffic channel active."));
        assertTrue(css.contains(".admin-settings-shell {"));
        assertTrue(css.contains(".admin-settings-nested-branch"));
        assertTrue(css.contains(".identity-directory-table-wrap {"));
    }
}
