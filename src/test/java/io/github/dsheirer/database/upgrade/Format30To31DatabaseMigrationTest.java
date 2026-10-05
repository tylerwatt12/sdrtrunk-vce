/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format30To31DatabaseMigrationTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesAllExistingPreferencesAndCredentialsWhileSelectingTheOriginalPalette() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("format-30.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE web_user SET preferences_json=json_set(preferences_json, '$.appearance.theme', 'dark',
                    '$.tables.channels', json('{"schema":["name","frequency"],"column_order":["frequency","name"],
                    "column_widths":{"name":240},"hidden_columns":["frequency"],"collapsed_groups":["p25-phase1"]}'))
                WHERE id=3
                """);
            Map<Long,Preference> before = preferences(statement);
            Map<Long,byte[]> credentials = credentialDigests(statement);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);

            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
            assertEquals(2, report.steps().size());
            assertEquals("format-30-to-31", report.steps().getFirst().id());
            assertEquals(before.size(), report.steps().getFirst().effects().getFirst().affectedRows());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            Map<Long,Preference> after = preferences(statement);
            assertEquals(before.keySet(), after.keySet());
            for(Map.Entry<Long,Preference> entry: before.entrySet())
            {
                Preference prior = entry.getValue();
                Preference current = after.get(entry.getKey());
                assertEquals(Format23WebUserPreferencesCodec.migrateToFormat31(prior.json()), current.json());
                assertEquals(prior.revision() + 1, current.revision());
                assertTrue(current.updatedAtMs() >= prior.updatedAtMs());
                assertTrue(WebUserPreferencesCodec.decode(current.json()).appearance().hue() == null);
                assertTrue(MessageDigest.isEqual(credentials.get(entry.getKey()),
                    credentialDigests(statement).get(entry.getKey())), "Account or credential changed");
            }
            assertEquals("ok", scalar(statement, "PRAGMA integrity_check"));
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
        }
    }

    @Test
    void defaultsOnlyAnUnusablePreferenceAndKeepsItsAccountAndValidSibling() throws Exception
    {
        for(String change: List.of(
            "preferences_json=json_remove(preferences_json, '$.playback.target_grouping')",
            "preferences_revision=9223372036854775805",
            "preferences_revision=9223372036854775806"))
        {
            Path database = Format30TestDatabase.create(mTemporaryFolder.resolve(
                "damaged-" + Math.abs(change.hashCode()) + ".sqlite"));
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                Map<Long,byte[]> credentials = credentialDigests(statement);
                String sibling = preferences(statement).get(2L).json();
                String selected = preferences(statement).get(3L).json();
                statement.executeUpdate("UPDATE web_user SET " + change + " WHERE id=3");
                DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
                assertTrue(report.steps().getFirst().effects().stream().anyMatch(effect ->
                    effect.kind() == DatabaseMigrationEffect.Kind.DEFAULT && effect.affectedRows() == 1));
                assertEquals(change.startsWith("preferences_json=") ? Format31WebUserPreferencesCodec.defaults() :
                    Format23WebUserPreferencesCodec.migrateToFormat31(selected), preferences(statement).get(3L).json());
                assertEquals(Format23WebUserPreferencesCodec.migrateToFormat31(sibling),
                    preferences(statement).get(2L).json());
                for(Map.Entry<Long,byte[]> credential: credentials.entrySet())
                {
                    assertTrue(MessageDigest.isEqual(credential.getValue(),
                        credentialDigests(statement).get(credential.getKey())), "Account or credential changed");
                }
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            }
        }
    }

    @Test
    void rollbackLeavesTheSourceUnchangedAndIndependentCopiesMigrateIdentically() throws Exception
    {
        Path source = Format30TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        Map<Long,Preference> before;
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            before = preferences(statement);
            connection.setAutoCommit(false);
            try
            {
                DatabaseMigrationChain.migrate(connection);
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }
            assertEquals(30, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, preferences(statement));
        }
        byte[] unchangedSource = Files.readAllBytes(source);
        for(String name: List.of("first.sqlite", "retry.sqlite"))
        {
            Path destination = Files.copy(source, mTemporaryFolder.resolve(name));
            try(Connection connection = open(destination); Statement statement = connection.createStatement())
            {
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseMigrationChain.migrate(connection).target().version());
                for(Map.Entry<Long,Preference> entry: before.entrySet())
                {
                    Preference current = preferences(statement).get(entry.getKey());
                    assertEquals(Format23WebUserPreferencesCodec.migrateToFormat31(entry.getValue().json()),
                        current.json());
                    assertEquals(entry.getValue().revision() + 1, current.revision());
                }
                assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
            }
        }
        assertTrue(MessageDigest.isEqual(unchangedSource, Files.readAllBytes(source)), "Source file changed");
    }

    @Test
    void frozenVersionEightValidatorAcceptsNullAndBoundedHueOnly() throws Exception
    {
        String defaults = Format31WebUserPreferencesCodec.defaults();
        Format31WebUserPreferencesCodec.validate(defaults);
        for(int hue: List.of(0, 215, 359))
        {
            Format31WebUserPreferencesCodec.validate(defaults.replace("\"hue\":null", "\"hue\":" + hue));
        }
        for(String invalid: List.of("-1", "360", "215.5", "\"215\"", "true", "[]", "2147483648"))
        {
            assertThrows(IOException.class, () -> Format31WebUserPreferencesCodec.validate(
                defaults.replace("\"hue\":null", "\"hue\":" + invalid)));
        }
        assertThrows(IOException.class, () -> Format31WebUserPreferencesCodec.validate(
            defaults.replace(",\"hue\":null", "")));
        assertThrows(IOException.class, () -> Format31WebUserPreferencesCodec.validate(
            defaults.replace("\"hue\":null", "\"hue\":null,\"hue\":215")));
        assertThrows(IOException.class, () -> Format31WebUserPreferencesCodec.validate(
            defaults.replace("\"hue\":null", "\"hue\":null,\"unknown\":true")));
    }

    @Test
    void resetsOneCachedLayoutOnlyWhenTheAddedHueExceedsTheUnchangedBound() throws Exception
    {
        Path database = Format30TestDatabase.create(mTemporaryFolder.resolve("full-preferences.sqlite"));
        String source = fullVersionSevenDocument();
        Format23WebUserPreferencesCodec.validate(source);
        assertTrue(source.length() <= WebUserPreferences.MAXIMUM_JSON_BYTES);
        assertTrue(source.length() + 11 > WebUserPreferences.MAXIMUM_JSON_BYTES);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            try(var update = connection.prepareStatement("UPDATE web_user SET preferences_json=? WHERE id=3"))
            {
                update.setString(1, source);
                assertEquals(1, update.executeUpdate());
            }
            Map<Long,Preference> before = preferences(statement);
            Map<Long,byte[]> credentials = credentialDigests(statement);
            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
            var reset = report.steps().getFirst().effects().stream().filter(effect ->
                effect.kind() == DatabaseMigrationEffect.Kind.RESET &&
                    effect.subject().contains("personal-preference storage bound")).findFirst().orElseThrow();
            assertEquals(1, reset.affectedRows());
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode expected = (ObjectNode)mapper.readTree(source);
            expected.put("version", 8);
            ((ObjectNode)expected.get("appearance")).putNull("hue");
            ObjectNode tables = (ObjectNode)expected.get("tables");
            String last = null;
            for(var names = tables.fieldNames(); names.hasNext(); ) last = names.next();
            tables.remove(last);
            assertEquals(expected, mapper.readTree(preferences(statement).get(3L).json()));
            assertTrue(preferences(statement).get(3L).json().length() <= WebUserPreferences.MAXIMUM_JSON_BYTES);
            for(Map.Entry<Long,Preference> entry: before.entrySet())
            {
                if(entry.getKey() != 3L) assertEquals(Format23WebUserPreferencesCodec.migrateToFormat31(
                    entry.getValue().json()), preferences(statement).get(entry.getKey()).json());
                assertTrue(MessageDigest.isEqual(credentials.get(entry.getKey()),
                    credentialDigests(statement).get(entry.getKey())), "Account or credential changed");
            }
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
        }
    }

    private static String fullVersionSevenDocument() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = (ObjectNode)mapper.readTree(Format23WebUserPreferencesCodec.defaults());
        ObjectNode tables = (ObjectNode)document.get("tables");
        ArrayNode schema = null;
        ArrayNode order = null;
        int column = 128;
        int table = 0;
        while(document.toString().length() <= WebUserPreferences.MAXIMUM_JSON_BYTES)
        {
            if(column == 128)
            {
                ObjectNode layout = tables.putObject("table" + table++);
                schema = layout.putArray("schema");
                order = layout.putArray("column_order");
                layout.putObject("column_widths");
                layout.putArray("hidden_columns");
                layout.putArray("collapsed_groups");
                column = 0;
            }
            String id = "c" + "x".repeat(58) + String.format(java.util.Locale.ROOT, "%05d", column++);
            schema.add(id);
            order.add(id);
        }
        int length = 64;
        while(document.toString().length() > WebUserPreferences.MAXIMUM_JSON_BYTES)
        {
            String shortened = schema.get(schema.size() - 1).textValue().substring(0, --length);
            schema.set(schema.size() - 1, mapper.getNodeFactory().textNode(shortened));
            order.set(order.size() - 1, mapper.getNodeFactory().textNode(shortened));
        }
        return document.toString();
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static Map<Long,Preference> preferences(Statement statement) throws Exception
    {
        Map<Long,Preference> result = new LinkedHashMap<>();
        try(ResultSet rows = statement.executeQuery(
            "SELECT id, preferences_json, preferences_revision, updated_at_ms FROM web_user ORDER BY id"))
        {
            while(rows.next()) result.put(rows.getLong(1), new Preference(rows.getString(2), rows.getLong(3), rows.getLong(4)));
        }
        return result;
    }

    private static Map<Long,byte[]> credentialDigests(Statement statement) throws Exception
    {
        Map<Long,byte[]> result = new LinkedHashMap<>();
        try(ResultSet rows = statement.executeQuery("""
            SELECT id, password_salt, password_hash,
                   username || ':' || tier || ':' || primary_admin || ':' || credential_version || ':' ||
                   password_algorithm || ':' || password_iterations || ':' || password_derived_key_bits || ':' ||
                   password_changed_at_ms || ':' || auth_revision || ':' || created_at_ms AS identity
            FROM web_user ORDER BY id
            """))
        {
            while(rows.next())
            {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(rows.getBytes(2));
                digest.update(rows.getBytes(3));
                digest.update(rows.getString(4).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                result.put(rows.getLong(1), digest.digest());
            }
        }
        return result;
    }

    private static String scalar(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private record Preference(String json, long revision, long updatedAtMs) {}
}
