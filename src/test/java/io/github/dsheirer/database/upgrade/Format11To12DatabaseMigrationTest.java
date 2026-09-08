/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format11To12DatabaseMigrationTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir
    Path mTemporaryFolder;

    @Test
    void addsOnlyTheDisabledMarkerChoiceAndPreservesEveryExistingUserAndReceiverSetting() throws Exception
    {
        Path database = Format11TestDatabase.create(mTemporaryFolder.resolve("populated.sqlite"));
        try(Connection connection = open(database))
        {
            assertEquals(11, DatabaseFormatCatalog.inspect(connection).version());
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.requireCurrent(connection));
            Map<Long, UserPreferences> before = preferences(connection);
            assertEquals(3, before.size());
            String preserved = preservedDigest(connection);
            var plan = DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 11, plan.steps().size());
            var effect = plan.steps().getFirst().effects().getFirst();
            assertEquals(DatabaseMigrationEffect.Kind.DEFAULT, effect.kind());
            assertEquals("per-user idle FFT channel markers", effect.subject());
            assertEquals(DatabaseMigrationEffect.UNKNOWN_COUNT, effect.affectedRows());
            assertEquals(before, preferences(connection), "Preflight must not write");

            List<DatabaseMigrationEffect> completed = migrateToFormat12(connection);
            assertEquals(3, completed.getFirst().affectedRows());

            Map<Long, UserPreferences> after = preferences(connection);
            assertEquals(before.keySet(), after.keySet());
            for(var entry: before.entrySet())
            {
                UserPreferences actual = after.get(entry.getKey());
                ObjectNode expected = (ObjectNode)MAPPER.readTree(entry.getValue().json());
                expected.put("version", 5);
                ((ObjectNode)expected.get("tuner")).put("show_idle_channels", false);
                assertEquals(expected, MAPPER.readTree(actual.json()));
                assertEquals(entry.getValue().revision() + 1, actual.revision());
                assertTrue(actual.updatedAt() >= entry.getValue().updatedAt());
                Format12WebUserPreferencesCodec.validate(actual.json());
            }
            assertEquals(preserved, preservedDigest(connection));
            assertEquals(12, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void rollbackRestoresExactPriorPreferencesAndAllowsRetry() throws Exception
    {
        Path database = Format11TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));
        try(Connection connection = open(database))
        {
            Map<Long, UserPreferences> before = preferences(connection);
            String preserved = preservedDigest(connection);
            connection.setAutoCommit(false);
            try
            {
                new Format11To12DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 12);
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }
            assertEquals(11, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, preferences(connection));
            assertEquals(preserved, preservedDigest(connection));
            migrateToFormat12(connection);
            assertEquals(12, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test
    void defaultsOnlyIncompleteMalformedNewerAndExhaustedUserPreferences() throws Exception
    {
        List<String> changes = List.of(
            "preferences_json=json_remove(preferences_json, '$.tuner.smooth_fft')",
            "preferences_json=json_set(preferences_json, '$.version', 5)",
            "preferences_json=json_set(preferences_json, '$.tuner.show_idle_channels', json('false'))",
            "preferences_revision=9223372036854775807");
        for(int index = 0; index < changes.size(); index++)
        {
            Path database = Format11TestDatabase.create(mTemporaryFolder.resolve("invalid-" + index + ".sqlite"));
            try(Connection connection = open(database); var statement = connection.createStatement())
            {
                statement.executeUpdate("UPDATE web_user SET " + changes.get(index) + " WHERE id=3");
                String preserved = preservedDigest(connection);
                long sourceRevision = Long.parseLong(scalar(connection,
                    "SELECT preferences_revision FROM web_user WHERE id=3"));
                long expectedRevision = sourceRevision > 0 && sourceRevision < Long.MAX_VALUE ?
                    sourceRevision + 1 : 1;
                List<DatabaseMigrationEffect> effects = migrateToFormat12(connection);
                DatabaseMigrationEffect reset = effects.stream()
                    .filter(effect -> effect.subject().equals("unusable per-user browser preferences"))
                    .findFirst().orElseThrow();
                assertEquals(1, reset.affectedRows());
                assertEquals(preserved, preservedDigest(connection));
                assertEquals("12", scalar(connection,
                    "SELECT value FROM database_metadata WHERE key='database_format_version'"));
                assertEquals("5:" + expectedRevision, scalar(connection, """
                    SELECT json_extract(preferences_json, '$.version') || ':' || preferences_revision
                    FROM web_user WHERE id=3
                    """));
            }
        }
    }

    @Test
    void defaultsOnlyAnOversizedUserPreferenceDocument() throws Exception
    {
        Path database = Format11TestDatabase.create(mTemporaryFolder.resolve("oversized.sqlite"));
        try(Connection connection = open(database); var statement = connection.createStatement())
        {
            String siblingTheme = scalar(connection,
                "SELECT json_extract(preferences_json, '$.appearance.theme') FROM web_user WHERE id=2");
            statement.execute("PRAGMA ignore_check_constraints=ON");
            try
            {
                statement.executeUpdate("""
                    UPDATE web_user
                    SET preferences_json='{"padding":"' || hex(zeroblob(65537)) || '"}'
                    WHERE id=3
                    """);
            }
            finally
            {
                statement.execute("PRAGMA ignore_check_constraints=OFF");
            }

            List<DatabaseMigrationEffect> effects = migrateToFormat12(connection);
            DatabaseMigrationEffect reset = effects.stream()
                .filter(effect -> effect.subject().equals("unusable per-user browser preferences"))
                .findFirst().orElseThrow();
            assertEquals(1, reset.affectedRows());
            assertEquals(siblingTheme, scalar(connection,
                "SELECT json_extract(preferences_json, '$.appearance.theme') FROM web_user WHERE id=2"));
            assertEquals("5", scalar(connection,
                "SELECT json_extract(preferences_json, '$.version') FROM web_user WHERE id=3"));
            assertEquals(12, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test
    void frozenCodecAcceptsBothSwitchValuesAndRejectsMalformedDocuments() throws Exception
    {
        Path database = Format11TestDatabase.create(mTemporaryFolder.resolve("codec.sqlite"));
        String old;
        try(Connection connection = open(database))
        {
            old = preferences(connection).values().iterator().next().json();
        }
        String migrated = Format12WebUserPreferencesCodec.migrateFromFormat11(old);
        for(boolean enabled: new boolean[]{false, true})
        {
            String json = migrated.replace("\"show_idle_channels\":false", "\"show_idle_channels\":" + enabled);
            Format12WebUserPreferencesCodec.validate(json);
            assertThrows(IOException.class, () -> Format9WebUserPreferencesCodec.validate(json));
        }
        for(String invalid: List.of(old, migrated + " {}", migrated.replace("\"version\":5", "\"version\":6"),
            migrated.replace("\"show_idle_channels\":false", "\"show_idle_channels\":null"),
            migrated.replace("\"show_idle_channels\":false", "\"show_idle_channels\":\"false\""),
            migrated.replace(",\"show_idle_channels\":false", ""),
            migrated.replace("\"show_idle_channels\":false", "\"show_idle_channels\":false,\"show_idle_channels\":true"),
            migrated.replace("\"show_idle_channels\":false", "\"show_idle_channels\":false,\"unknown\":true")))
        {
            assertThrows(IOException.class, () -> Format12WebUserPreferencesCodec.validate(invalid));
        }
    }

    private static List<DatabaseMigrationEffect> migrateToFormat12(Connection connection) throws Exception
    {
        connection.setAutoCommit(false);
        try
        {
            Format11To12DatabaseMigration migration = new Format11To12DatabaseMigration();
            List<DatabaseMigrationEffect> effects = migration.validateSource(connection);
            migration.migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 12);
            connection.commit();
            return effects;
        }
        catch(Exception e)
        {
            connection.rollback();
            throw e;
        }
        finally
        {
            connection.setAutoCommit(true);
        }
    }

    private static Map<Long, UserPreferences> preferences(Connection connection) throws Exception
    {
        Map<Long, UserPreferences> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT id, preferences_json, preferences_revision, updated_at_ms FROM web_user ORDER BY id"))
        {
            while(rows.next())
            {
                result.put(rows.getLong(1), new UserPreferences(rows.getString(2), rows.getLong(3), rows.getLong(4)));
            }
        }
        return result;
    }

    /** Compare all unchanged persisted data without putting password verifiers in assertion output. */
    private static String preservedDigest(Connection connection) throws Exception
    {
        List<String> queries = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("""
            SELECT name FROM sqlite_schema WHERE type='table'
            AND name NOT IN ('web_user', 'database_metadata') ORDER BY name
            """))
        {
            while(rows.next()) queries.add("SELECT * FROM \"" + rows.getString(1).replace("\"", "\"\"") + "\"" +
                ("application_settings".equals(rows.getString(1)) ?
                    " WHERE key NOT IN ('setup_wizard','spectrum_snap_country')" : ""));
        }
        queries.add("""
            SELECT id, username, tier, primary_admin, credential_version, password_algorithm, password_iterations,
                password_derived_key_bits, password_salt, password_hash, password_changed_at_ms, auth_revision,
                created_at_ms FROM web_user ORDER BY id
            """);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for(String query: queries)
        {
            digest.update(query.getBytes(StandardCharsets.UTF_8));
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(query))
            {
                while(rows.next())
                {
                    for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                    {
                        byte[] bytes = rows.getBytes(column);
                        digest.update((bytes == null ? "null" : Base64.getEncoder().encodeToString(bytes))
                            .getBytes(StandardCharsets.UTF_8));
                        digest.update((byte)'|');
                    }
                    digest.update((byte)'\n');
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private record UserPreferences(String json, long revision, long updatedAt) {}
}
