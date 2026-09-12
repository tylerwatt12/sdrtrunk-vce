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
        String configurationEditor = Files.readString(
            Path.of("src/main/java/io/github/dsheirer/gui/configuration/ConfigurationEditor.java"));

        assertTrue(javascript.contains("ADMIN_CHANNELS: 'admin-channels'"));
        assertTrue(javascript.contains("requestJson('/api/v1/admin/channels'"));
        assertTrue(javascript.contains("profile.sections.forEach"));
        assertTrue(javascript.contains("field.visible_when"));
        assertTrue(javascript.contains("action('Start', 'START')"));
        assertTrue(javascript.contains("action('Stop', 'STOP')"));
        assertTrue(javascript.contains("action('Clone', 'CLONE')"));
        assertTrue(javascript.contains("action('Delete', 'DELETE'"));
        assertTrue(javascript.contains("direction: 'EARLIER'"));
        assertTrue(javascript.contains("direction: 'LATER'"));
        assertTrue(Files.readString(Path.of("src/main/resources/channel-protocols.json"))
            .contains("\"value\":\"CQPSK\",\"label\":\"CQPSK\""));
        assertFalse(configurationEditor.contains("getChannelsTab()"));
        assertFalse(configurationEditor.contains("new ChannelEditor("));
    }
}
