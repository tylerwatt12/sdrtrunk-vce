/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BugReportConfigurationExporterTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void exportsAllConfigurationWhileRemovingSecrets() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream
                (sort_order, name, server_type, enabled, host, port, config_json)
                VALUES (0, 'Feed', 'RADIORESOLVE', 1, 'radio.example', 443, ?)
                """))
            {
                statement.setString(1,
                    "{\"password\":\"stream-password\",\"apiKey\":\"stream-key\",\"serialNumber\":\"SER123\"}");
                statement.executeUpdate();
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms) VALUES (?, ?, 1)
                """))
            {
                statement.setString(1, "portable_java_preferences_v1");
                statement.setString(2,
                    "{\"user/test\":{\"vault.saved.password\":\"vault-password\",\"normal\":\"kept\"}}");
                statement.executeUpdate();
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        BugReportConfigurationExporter exporter =
            new BugReportConfigurationExporter(mapper, new BugReportRedactor());
        Map<String,List<Map<String,Object>>> snapshot = exporter.export(database);
        JsonNode stream = (JsonNode)snapshot.get("configuration_broadcast_stream").getFirst().get("config_json");
        JsonNode preferences = (JsonNode)snapshot.get("application_settings").getFirst().get("settings_json");

        assertEquals(BugReportRedactor.REDACTED, stream.get("password").textValue());
        assertEquals(BugReportRedactor.REDACTED, stream.get("apiKey").textValue());
        assertEquals("SER123", stream.get("serialNumber").textValue());
        assertEquals(BugReportRedactor.REDACTED,
            preferences.at("/user~1test/vault.saved.password").textValue());
        assertEquals("kept", preferences.at("/user~1test/normal").textValue());
        assertTrue(snapshot.containsKey("alias_list"));
        assertTrue(snapshot.containsKey("alias"));
        assertFalse(snapshot.containsKey("alias_talkgroup"));
        assertFalse(snapshot.containsKey("alias_radio"));
    }
}
