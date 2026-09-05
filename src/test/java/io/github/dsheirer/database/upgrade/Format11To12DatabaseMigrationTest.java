/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
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
            assertEquals(3, effect.affectedRows());
            assertEquals(before, preferences(connection), "Preflight must not write");

            connection.setAutoCommit(false);
            try
            {
                var report = DatabaseMigrationChain.migrate(connection);
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, report.target().version());
                assertEquals(List.of(effect), report.steps().getFirst().effects());
                connection.commit();
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
                assertFalse(WebUserPreferencesCodec.decode(actual.json()).tuner().showIdleChannels());
            }
            assertEquals(preserved, preservedDigest(connection));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            assertEquals(after, preferences(connection), "Current format is a no-op");
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
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
                DatabaseMigrationChain.migrate(connection);
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }
            assertEquals(11, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, preferences(connection));
            assertEquals(preserved, preservedDigest(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseMigrationChain.migrate(connection).target().version());
        }
    }

    @Test
    void rejectsIncompleteMalformedNewerAndExhaustedSourcesWithoutChangingOtherUsers() throws Exception
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
                Map<Long, UserPreferences> before = preferences(connection);
                String preserved = preservedDigest(connection);
                assertThrows(SQLException.class, () -> new Format11To12DatabaseMigration().validateSource(connection));
                assertThrows(SQLException.class, () -> new Format11To12DatabaseMigration().migrate(connection));
                assertEquals(before, preferences(connection));
                assertEquals(preserved, preservedDigest(connection));
                assertEquals("11", scalar(connection,
                    "SELECT value FROM database_metadata WHERE key='database_format_version'"));
            }
        }
    }

    @Test
    void frozenCodecRejectsMalformedDocumentsAndRuntimeRoundTripsBothSwitchValues() throws Exception
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
            var runtime = WebUserPreferencesCodec.decode(json);
            assertEquals(enabled, runtime.tuner().showIdleChannels());
            assertEquals(runtime, WebUserPreferencesCodec.decode(WebUserPreferencesCodec.encode(runtime)));
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
