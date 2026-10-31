/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format66To67DatabaseMigrationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test void preservesPersonalChoicesAccountRowsAndUnchangedActivityAcrossRollbackAndRetry() throws Exception
    {
        Path database = Format66TestDatabase.create(directory.resolve("prior.sqlite"));
        byte[] activity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
        byte[] original = Files.readAllBytes(database);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(original, Files.readAllBytes(database));
        try(var connection = open(database); var statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE web_user SET preferences_json=json_set(preferences_json," +
                "'$.appearance.theme','dark','$.playback.volume',0.35,'$.playback.call_display_hangtime_seconds',23,'$.playback.recording_click_mode','once','$.guides.automatic_page_guides',json('false')) WHERE primary_admin=1");
            var before = preferences(connection);
            var retained = retainedRows(connection);
            assertTrue(before.size() >= 2);
            connection.setAutoCommit(false);
            var effects = new Format66To67DatabaseMigration().migrateAndReport(connection);
            DatabaseFormatCatalog.stamp(connection, 67);
            assertEquals(before.size(), effects.getFirst().affectedRows());
            assertConverted(before, preferences(connection));
            assertEquals(retained, retainedRows(connection));
            assertEquals(ConfigurationHistoryDatabase.CONFIGURATION_FINGERPRINT, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(67, DatabaseFormatCatalog.requireCurrent(connection).version());
            connection.rollback();
            assertEquals(66, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, preferences(connection));
            new Format66To67DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 67);
            connection.commit();
            assertConverted(before, preferences(connection));
            assertEquals(retained, retainedRows(connection));
        }
        ConfigurationHistoryDatabase.validatePair(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        assertArrayEquals(activity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
        byte[] current = Files.readAllBytes(database);
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertArrayEquals(current, Files.readAllBytes(database));
    }

    @Test void migratorFailureRollsBackAndAnUnchangedSourceCanBeRetried() throws Exception
    {
        Path database = Format66TestDatabase.create(directory.resolve("retry.sqlite"));
        var before = readPreferences(database);
        byte[] activity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, phase -> {
            if(phase.equals("Validating the updated schema and configuration"))
                throw new IllegalStateException("injected precommit failure");
        }));
        try(var connection = open(database)) { assertEquals(66, DatabaseFormatCatalog.inspect(connection).version()); }
        assertEquals(before, readPreferences(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertConverted(before, readPreferences(database));
        assertArrayEquals(activity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
        ConfigurationHistoryDatabase.validatePair(database);
    }

    @Test void defaultsOnlyUnusableDocumentsAndRebasesOnlyExhaustedRevisions() throws Exception
    {
        for(String damage: List.of("preferences_json='{}'", "preferences_revision=9223372036854775806"))
        {
            Path database = Format66TestDatabase.create(directory.resolve("damaged-" + Math.abs(damage.hashCode()) + ".sqlite"));
            try(var connection = open(database); var statement = connection.createStatement())
            {
                var before = preferences(connection);
                var retained = retainedRows(connection);
                statement.executeUpdate("UPDATE web_user SET " + damage + " WHERE primary_admin=1");
                var effects = new Format66To67DatabaseMigration().migrateAndReport(connection);
                DatabaseFormatCatalog.stamp(connection, 67);
                assertTrue(effects.stream().anyMatch(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DEFAULT &&
                    effect.affectedRows() == 1));
                for(var entry: preferences(connection).entrySet())
                {
                    assertEquals("automatic", WebUserPreferencesCodec.decode(entry.getValue().json()).liveWorkspace().detailsVisibility());
                    assertTrue(entry.getValue().revision() > 0);
                }
                assertEquals(retained, retainedRows(connection));
                assertEquals(67, DatabaseFormatCatalog.requireCurrent(connection).version());
            }
        }
    }

    @Test void trimsOnlyNecessaryCachedLayoutsAndKeepsTheFrozenPriorPreferenceContract() throws Exception
    {
        ObjectNode source = fullVersionFourteenDocument();
        String json = JSON.writeValueAsString(source);
        Format66WebUserPreferencesCodec.validate(json);
        var converted = Format67WebUserPreferencesCodec.migrateFromFormat66(json);
        assertEquals(1, converted.resetLayouts());
        ObjectNode result = (ObjectNode)JSON.readTree(converted.json());
        source.remove("tables"); result.remove("tables");
        addExpectedDefaults(source);
        assertEquals(source, result);
        assertThrows(IOException.class, () -> Format66WebUserPreferencesCodec.validate(converted.json()));
        assertThrows(IOException.class, () -> Format67WebUserPreferencesCodec.validate(Format66WebUserPreferencesCodec.defaults()));
    }

    private static ObjectNode fullVersionFourteenDocument() throws Exception
    {
        ObjectNode document = (ObjectNode)JSON.readTree(Format66WebUserPreferencesCodec.defaults());
        ObjectNode tables = (ObjectNode)document.get("tables");
        ArrayNode schema = null, order = null;
        int column = 128, table = 0;
        while(document.toString().length() <= WebUserPreferences.MAXIMUM_JSON_BYTES)
        {
            if(column == 128)
            {
                ObjectNode layout = tables.putObject("table" + table++);
                schema = layout.putArray("schema"); order = layout.putArray("column_order");
                layout.putObject("column_widths"); layout.putArray("hidden_columns"); layout.putArray("collapsed_groups"); column = 0;
            }
            String id = "c" + "x".repeat(58) + String.format(java.util.Locale.ROOT, "%05d", column++);
            schema.add(id); order.add(id);
        }
        int length = 64;
        while(document.toString().length() > WebUserPreferences.MAXIMUM_JSON_BYTES)
        {
            String shorter = schema.get(schema.size()-1).textValue().substring(0, --length);
            schema.set(schema.size()-1, JSON.getNodeFactory().textNode(shorter));
            order.set(order.size()-1, JSON.getNodeFactory().textNode(shorter));
        }
        return document;
    }

    private static void addExpectedDefaults(ObjectNode document) throws Exception
    {
        ObjectNode defaults = (ObjectNode)JSON.readTree(WebUserPreferencesCodec.encode(WebUserPreferences.defaults()));
        document.put("version", 15);
        document.set("live_workspace", defaults.get("live_workspace"));
        document.set("p25_visualizer", defaults.get("p25_visualizer"));
        ((ObjectNode)document.get("tuner")).put("fft_auto_range_on_retune", true).put("waterfall_auto_range_on_retune", true);
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
    private static Map<Long,Preference> readPreferences(Path database) throws Exception
    {
        try(var connection = open(database)) { return preferences(connection); }
    }
    private static Map<Long,Preference> preferences(Connection connection) throws Exception
    {
        Map<Long,Preference> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT id,preferences_json,preferences_revision,updated_at_ms FROM web_user ORDER BY id"))
        { while(rows.next()) result.put(rows.getLong(1), new Preference(rows.getString(2), rows.getLong(3), rows.getLong(4))); }
        return result;
    }
    private static void assertConverted(Map<Long,Preference> before, Map<Long,Preference> after) throws Exception
    {
        assertEquals(before.keySet(), after.keySet());
        for(var entry: before.entrySet())
        {
            ObjectNode expected = (ObjectNode)JSON.readTree(entry.getValue().json());
            addExpectedDefaults(expected);
            Preference actual = after.get(entry.getKey());
            assertEquals(expected, JSON.readTree(actual.json()));
            assertEquals(entry.getValue().revision()+1, actual.revision());
            assertEquals(entry.getValue().updatedAt(), actual.updatedAt());
            assertEquals("automatic", WebUserPreferencesCodec.decode(actual.json()).liveWorkspace().detailsVisibility());
        }
    }
    private static Map<String,List<String>> retainedRows(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var tables = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            while(tables.next())
            {
                String table = tables.getString(1); List<String> values = new ArrayList<>();
                try(var read = connection.createStatement(); var rows = read.executeQuery("SELECT * FROM \"" + table + "\""))
                {
                    while(rows.next())
                    {
                        if(table.equals("database_metadata") && DatabaseFormatCatalog.FORMAT_VERSION_KEY.equals(rows.getString(1))) continue;
                        List<String> fields = new ArrayList<>();
                        for(int i=1;i<=rows.getMetaData().getColumnCount();i++)
                        {
                            String name=rows.getMetaData().getColumnName(i);
                            if(table.equals("web_user") && List.of("preferences_json","preferences_revision").contains(name)) continue;
                            Object value=rows.getObject(i);
                            fields.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                        }
                        values.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(fields.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                    }
                }
                values.sort(String::compareTo); result.put(table, values);
            }
        }
        return result;
    }
    private record Preference(String json, long revision, long updatedAt) {}
}
