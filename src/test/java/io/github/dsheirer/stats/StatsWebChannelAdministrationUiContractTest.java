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

class StatsWebChannelAdministrationUiContractTest
{
    @Test
    void channelManagementIsWebFirstAndProtocolDriven() throws Exception
    {
        String javascript = Files.readString(Path.of("stats-web/assets/app.js"));
        String stylesheet = StatsWebStylesheetTestSupport.readAll();
        String configurationEditor = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/gui/configuration/ConfigurationEditor.java"));

        assertTrue(javascript.contains("ADMIN_CHANNELS: 'admin-channels'"));
        assertTrue(javascript.contains("const catalogPath = editable ? '/api/v1/admin/channels' : " +
            "'/api/v1/channel-catalog'"));
        assertTrue(javascript.contains("profile.sections.forEach"));
        assertTrue(javascript.contains("field.visible_when"));
        assertTrue(javascript.contains("action('Start', 'icon-play', 'START')"));
        assertTrue(javascript.contains("action('Stop', 'icon-stop', 'STOP')"));
        assertTrue(javascript.contains("action('Clone', 'icon-copy', 'CLONE')"));
        assertTrue(javascript.contains("action('Delete', 'icon-trash', 'DELETE'"));
        assertTrue(javascript.contains("direction: 'EARLIER'"));
        assertTrue(javascript.contains("direction: 'LATER'"));
        assertTrue(javascript.contains("uiSelectFrame(protocolSelect, 'channel-protocol-select')"));
        assertTrue(javascript.contains("control instanceof HTMLSelectElement ? uiSelectFrame(control) : control"));
        assertTrue(javascript.contains("function channelEditorSectionPlan(sections)"));
        assertTrue(javascript.contains("function channelEditorSectionNavigation(panels, plan)"));
        assertTrue(javascript.contains("alias-editor-panel channel-editor-panel ui-form-section"));
        assertTrue(javascript.contains("channel-editor-section-disclosure ui-section-disclosure"));
        assertTrue(javascript.contains("channel-editor-section-layout ui-editor-layout"));
        assertTrue(javascript.contains("node('label', 'channel-map-field')"));
        assertTrue(javascript.contains("panel.setAttribute('aria-labelledby', labelId)"));
        assertTrue(javascript.contains("form.addEventListener('invalid', (event) =>"));
        assertFalse(javascript.contains("candidate.setAttribute('aria-current', 'location')"));
        assertFalse(javascript.contains("function channelEditorTabs("));
        assertFalse(javascript.contains("channel-modal-tabs"));
        assertTrue(stylesheet.contains(".ui-select-frame > svg"));
        assertTrue(stylesheet.contains(".channel-protocol-picker"));
        assertTrue(stylesheet.contains(".channel-restart-notice svg"));
        assertTrue(stylesheet.contains(".channel-map-mobile-label"));
        assertTrue(stylesheet.contains(".channel-catalog-table td.empty"));
        assertTrue(stylesheet.contains("max-height: min(70dvh, 720px)"));
        assertTrue(stylesheet.contains(".data-workspace"));
        assertTrue(stylesheet.contains(".editor-workspace"));
        assertTrue(Files.readString(Path.of("src/main/resources/channel-protocols.json"))
            .contains("\"value\":\"CQPSK\",\"label\":\"CQPSK\""));
        assertFalse(configurationEditor.contains("getChannelsTab()"));
        assertFalse(configurationEditor.contains("new ChannelEditor("));
    }
}
