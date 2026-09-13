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
        String stylesheet = Files.readString(Path.of("stats-web/assets/app.css"));
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
        assertTrue(stylesheet.contains(".ui-select-frame > svg"));
        assertTrue(stylesheet.contains(".channel-protocol-picker"));
        assertTrue(Files.readString(Path.of("src/main/resources/channel-protocols.json"))
            .contains("\"value\":\"CQPSK\",\"label\":\"CQPSK\""));
        assertFalse(configurationEditor.contains("getChannelsTab()"));
        assertFalse(configurationEditor.contains("new ChannelEditor("));
    }
}
