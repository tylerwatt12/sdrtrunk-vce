/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.web.settings.WebUserPreferences;
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

class Format61To62DatabaseMigrationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test void preservesPreferencesAccountBookkeepingAndAllOtherRowsAcrossRollbackAndRetry() throws Exception
    {
        Path database = Format61TestDatabase.create(directory.resolve("prior.sqlite"));
        byte[] source = Files.readAllBytes(database);
        assertThrows(java.io.IOException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(source, Files.readAllBytes(database));
        try(var connection = open(database); var statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE web_user SET preferences_json=json_set(preferences_json," +
                "'$.appearance.theme','dark','$.presentation.live_row_density','dense'," +
                "'$.presentation.source_name_display','both','$.playback.volume',0.35) WHERE primary_admin=1");
            Map<Long,Preference> preferences = preferences(connection);
            Map<String,List<String>> retained = retainedRows(connection);
            assertTrue(preferences.size() >= 2);
            assertTrue(retained.get("web_user_tour_dismissal").size() >= 3);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            connection.setAutoCommit(false);
            var effects = migrateAdjacent(connection);
            assertEquals(preferences.size(), effects.getFirst().affectedRows());
            assertRetained(retained, retainedRows(connection));
            assertConverted(preferences, preferences(connection));
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(62, DatabaseFormatCatalog.inspect(connection).version());
            connection.rollback();
            assertEquals(61, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(preferences, preferences(connection));
            assertRetained(retained, retainedRows(connection));
            migrateAdjacent(connection);
            connection.commit();
            assertConverted(preferences, preferences(connection));
            assertRetained(retained, retainedRows(connection));
            try(var rows = statement.executeQuery("PRAGMA integrity_check")) { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
            try(var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        }
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        ConfigurationHistoryDatabase.validatePair(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        byte[] current = Files.readAllBytes(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
        assertArrayEquals(current, Files.readAllBytes(database));
    }

    @Test void defaultsOnlyAnUnusablePreferenceOrRevisionAndKeepsUsableAccountAndHistory() throws Exception
    {
        for(String damage: List.of("preferences_json='{}'", "preferences_revision=9223372036854775805",
            "preferences_revision=9223372036854775806"))
        {
            Path database = Format61TestDatabase.create(directory.resolve("damaged-" + Math.abs(damage.hashCode()) + ".sqlite"));
            try(var connection = open(database); var statement = connection.createStatement())
            {
                var before = preferences(connection);
                var retained = retainedRows(connection);
                statement.executeUpdate("UPDATE web_user SET " + damage + " WHERE primary_admin=1");
                var effects = migrateAdjacent(connection);
                assertTrue(effects.stream().anyMatch(effect ->
                    effect.kind() == DatabaseMigrationEffect.Kind.DEFAULT && effect.affectedRows() == 1));
                for(var entry: preferences(connection).entrySet())
                {
                    Format62WebUserPreferencesCodec.validate(entry.getValue().json());
                    assertTrue(entry.getValue().revision() > 0);
                    if(entry.getKey() != primaryId(connection))
                    {
                        assertEquals(Format62WebUserPreferencesCodec.migrateFromFormat61(before.get(entry.getKey()).json()).json(),
                            entry.getValue().json());
                    }
                }
                // Valid identity, password metadata, account timestamps and tour rows survive independently.
                assertRetained(retained, retainedRows(connection));
                assertEquals(62, DatabaseFormatCatalog.inspect(connection).version());
            }
        }
    }

    @Test void trimsOnlyTheMinimumNecessaryCachedLayoutsAndReportsTheCount() throws Exception
    {
        Path database = Format61TestDatabase.create(directory.resolve("full.sqlite"));
        String source = fullVersionElevenDocument();
        Format43WebUserPreferencesCodec.validate(source);
        var converted = Format62WebUserPreferencesCodec.migrateFromFormat61(source);
        assertEquals(1, converted.resetLayouts());
        ObjectNode before = (ObjectNode)JSON.readTree(source);
        ObjectNode after = (ObjectNode)JSON.readTree(converted.json());
        before.remove("tables"); after.remove("tables");
        before.put("version", 12); before.putObject("guides").put("automatic_page_guides", true);
        assertEquals(before, after);
        try(var connection = open(database); var update = connection.prepareStatement(
            "UPDATE web_user SET preferences_json=? WHERE primary_admin=1"))
        {
            update.setString(1, source); assertEquals(1, update.executeUpdate());
            var effects = migrateAdjacent(connection);
            var effect = effects.stream().filter(value ->
                value.kind() == DatabaseMigrationEffect.Kind.RESET && value.subject().contains("storage bound")).findFirst().orElseThrow();
            assertEquals(1, effect.affectedRows());
            assertEquals(converted.json(), preferences(connection).get(primaryId(connection)).json());
            assertEquals(62, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test void frozenVersionsRemainStrictAndTheNewStepRefusesWrongSources() throws Exception
    {
        String legacy = Format43WebUserPreferencesCodec.defaults();
        String current = Format62WebUserPreferencesCodec.defaults();
        ObjectNode prior = (ObjectNode)JSON.readTree(current);
        prior.put("version", 11); prior.remove("guides");
        assertEquals(JSON.readTree(legacy), prior);
        assertTrue(JSON.readTree(current).path("guides").path("automatic_page_guides").booleanValue());
        assertThrows(IOException.class, () -> Format62WebUserPreferencesCodec.validate(legacy));
        assertThrows(IOException.class, () -> Format43WebUserPreferencesCodec.validate(current));
        for(String value: List.of("null", "0", "1", "\"true\"", "[]", "{}"))
            assertThrows(IOException.class, () -> Format62WebUserPreferencesCodec.validate(
                current.replace("\"automatic_page_guides\":true", "\"automatic_page_guides\":" + value)));
        Path earlier = Format60TestDatabase.create(directory.resolve("earlier.sqlite"));
        byte[] unchanged = Files.readAllBytes(earlier);
        try(var connection = open(earlier)) { assertThrows(SQLException.class, () -> new Format61To62DatabaseMigration().migrate(connection)); }
        assertArrayEquals(unchanged, Files.readAllBytes(earlier));
    }

    private static List<DatabaseMigrationEffect> migrateAdjacent(Connection connection) throws Exception
    {
        var effects = new Format61To62DatabaseMigration().migrateAndReport(connection);
        DatabaseFormatCatalog.stamp(connection, 62);
        return effects;
    }

    private static void assertConverted(Map<Long,Preference> before, Map<Long,Preference> after) throws Exception
    {
        assertEquals(before.keySet(), after.keySet());
        for(var entry: before.entrySet())
        {
            var prior = entry.getValue(); var current = after.get(entry.getKey());
            ObjectNode expected = (ObjectNode)JSON.readTree(prior.json());
            expected.put("version", 12); expected.putObject("guides").put("automatic_page_guides", true);
            assertEquals(expected, JSON.readTree(current.json()));
            assertEquals(prior.revision() + 1, current.revision());
            assertEquals(prior.updatedAt(), current.updatedAt());
            assertTrue(JSON.readTree(current.json()).path("guides").path("automatic_page_guides").booleanValue());
        }
    }

    private static void assertRetained(Map<String,List<String>> before, Map<String,List<String>> after)
    {
        assertEquals(before.keySet(), after.keySet());
        before.forEach((table, rows) -> assertEquals(rows, after.get(table), table));
    }

    private static Map<Long,Preference> preferences(Connection connection) throws Exception
    {
        Map<Long,Preference> values = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT id,preferences_json,preferences_revision,updated_at_ms FROM web_user ORDER BY id"))
        { while(rows.next()) values.put(rows.getLong(1), new Preference(rows.getString(2),rows.getLong(3),rows.getLong(4))); }
        return values;
    }

    /** Exact row proof excludes only the deliberately converted document, its revision, and global format marker. */
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
                        for(int i=1; i<=rows.getMetaData().getColumnCount(); i++)
                        {
                            String column = rows.getMetaData().getColumnName(i);
                            if(table.equals("web_user") && List.of("preferences_json","preferences_revision").contains(column)) continue;
                            Object value = rows.getObject(i);
                            fields.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                        }
                        values.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(fields.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                    }
                }
                values.sort(String::compareTo); result.put(table,values);
            }
        }
        return result;
    }

    private static String fullVersionElevenDocument() throws Exception
    {
        ObjectNode document = (ObjectNode)JSON.readTree(Format43WebUserPreferencesCodec.defaults());
        ObjectNode tables = (ObjectNode)document.get("tables"); ArrayNode schema = null, order = null;
        int column = 128, table = 0;
        while(document.toString().length() <= WebUserPreferences.MAXIMUM_JSON_BYTES)
        {
            if(column == 128)
            {
                ObjectNode layout = tables.putObject("table" + table++);
                schema = layout.putArray("schema"); order = layout.putArray("column_order");
                layout.putObject("column_widths"); layout.putArray("hidden_columns"); layout.putArray("collapsed_groups"); column=0;
            }
            String id = "c" + "x".repeat(58) + String.format(java.util.Locale.ROOT,"%05d",column++);
            schema.add(id); order.add(id);
        }
        int length=64;
        while(document.toString().length() > WebUserPreferences.MAXIMUM_JSON_BYTES)
        {
            String shorter = schema.get(schema.size()-1).textValue().substring(0,--length);
            schema.set(schema.size()-1, JSON.getNodeFactory().textNode(shorter));
            order.set(order.size()-1, JSON.getNodeFactory().textNode(shorter));
        }
        return document.toString();
    }

    private static long primaryId(Connection connection) throws Exception
    { try(var statement = connection.createStatement(); var row = statement.executeQuery("SELECT id FROM web_user WHERE primary_admin=1")) { assertTrue(row.next()); return row.getLong(1); } }
    private static Connection open(Path database) throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + database); }
    private record Preference(String json,long revision,long updatedAt) {}
}
