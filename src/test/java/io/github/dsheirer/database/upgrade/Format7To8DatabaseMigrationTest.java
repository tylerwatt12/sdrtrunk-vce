/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format7To8DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void upgradesEveryUserWithAllHealthAlertsEnabled() throws Exception
    {
        Path database = Format7TestDatabase.create(mTemporaryFolder.resolve("format-7.sqlite"));

        try(Connection connection = open(database))
        {
            String securityBefore = securityDigest(connection);
            String existingPreferencesBefore = existingPreferenceDigest(connection);
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals(3, preflight.steps().size());
            assertEquals("format-7-to-8", preflight.steps().getFirst().id());
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DEFAULT,
                "per-user receiver-health alert settings", 3);

            connection.setAutoCommit(false);
            DatabaseMigrationChain.MigrationReport report;
            try
            {
                report = DatabaseMigrationChain.migrate(connection);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(7, report.source().version());
            assertEquals(10, report.target().version());
            assertEquals("format-7-to-8", report.steps().getFirst().id());
            assertEquals("3", scalar(connection, """
                SELECT COUNT(*) FROM web_user
                WHERE json_extract(preferences_json, '$.version')=4
                  AND json_type(preferences_json, '$.health_alerts.disabled_codes')='array'
                  AND json_array_length(json_extract(preferences_json,
                      '$.health_alerts.disabled_codes'))=0
                  AND preferences_revision=4
                """));
            assertEquals(existingPreferencesBefore, existingPreferenceDigest(connection));
            assertEquals(securityBefore, securityDigest(connection));
            assertEquals("10", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals(10, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void callerRollbackRestoresExactFormatSevenDocuments() throws Exception
    {
        Path database = Format7TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));

        try(Connection connection = open(database))
        {
            String usersBefore = preferenceDigest(connection);
            connection.setAutoCommit(false);
            try
            {
                assertEquals(10, DatabaseMigrationChain.migrate(connection).target().version());
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(usersBefore, preferenceDigest(connection));
            assertEquals(7, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test
    void rejectsUnknownVersionTwoFieldsAndExhaustedRevisionsWithoutChangingSource() throws Exception
    {
        Path unknown = Format7TestDatabase.create(mTemporaryFolder.resolve("unknown.sqlite"));
        try(Connection connection = open(unknown); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE web_user
                SET preferences_json=json_set(preferences_json, '$.unknown', 1)
                WHERE id=1
                """);
            String malformed = preferenceDigest(connection);
            SQLException rejection = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.migrate(connection));
            assertTrue(rejection.getMessage().contains("invalid typed preference document"), rejection.getMessage());
            assertEquals(malformed, preferenceDigest(connection));
            assertEquals("7", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        }

        Path exhausted = Format7TestDatabase.create(mTemporaryFolder.resolve("exhausted.sqlite"));
        try(Connection connection = open(exhausted); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE web_user SET preferences_revision=9223372036854775807 WHERE id=1");
            SQLException rejection = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.migrate(connection));
            assertTrue(rejection.getMessage().contains("preference revision must be positive and incrementable"),
                rejection.getMessage());
            assertEquals("7", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        }
    }

    @Test
    void currentFormatRejectsMalformedDisabledAlertCodes() throws Exception
    {
        Path database = Format8TestDatabase.create(mTemporaryFolder.resolve("invalid-alert-code.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE web_user
                SET preferences_json=json_set(preferences_json,
                    '$.health_alerts.disabled_codes', json('["Receiver IQ Drop"]'))
                WHERE id=1
                """);
            SQLException rejection = assertThrows(SQLException.class,
                () -> DatabaseFormatCatalog.requireCurrent(connection));
            assertTrue(rejection.getMessage().contains("invalid typed preference document"), rejection.getMessage());
        }
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects, DatabaseMigrationEffect.Kind kind,
                                     String subject, long rows)
    {
        DatabaseMigrationEffect effect = effects.stream().filter(candidate -> candidate.kind() == kind &&
            candidate.subject().equals(subject)).findFirst().orElseThrow();
        assertEquals(rows, effect.affectedRows());
    }

    private static String existingPreferenceDigest(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT group_concat(row_value, '|') FROM (
                SELECT id || ':' ||
                       json_extract(preferences_json, '$.appearance.theme') || ':' ||
                       json_extract(preferences_json, '$.page_titles.prepend_playing_call') || ':' ||
                       json_extract(preferences_json, '$.playback.volume') || ':' ||
                       json_extract(preferences_json, '$.playback.selected_scan_list_ids') || ':' ||
                       json_extract(preferences_json, '$.playback.conversation_grouping') || ':' ||
                       json_extract(preferences_json, '$.playback.conversation_burst_limit') || ':' ||
                       json_extract(preferences_json, '$.scanner.detail_mode') || ':' ||
                       json_extract(preferences_json, '$.presentation.show_encryption_details') || ':' ||
                       json_extract(preferences_json, '$.presentation.show_control_decode_quality') || ':' ||
                       json_extract(preferences_json, '$.presentation.show_voice_decode_quality') || ':' ||
                       json_extract(preferences_json, '$.presentation.decode_quality_display_mode') || ':' ||
                       json_extract(preferences_json, '$.presentation.live_detail_row_limit') || ':' ||
                       json_extract(preferences_json, '$.tuner.floor_db') || ':' ||
                       json_extract(preferences_json, '$.tuner.ceiling_db') || ':' ||
                       json_extract(preferences_json, '$.tuner.waterfall_speed') || ':' ||
                       json_extract(preferences_json, '$.tuner.snap_frequency') || ':' ||
                       json_extract(preferences_json, '$.tuner.smooth_fft') || ':' ||
                       json_extract(preferences_json, '$.tuner.highlight_waterfall_channels') || ':' ||
                       json_extract(preferences_json, '$.tuner.profile') || ':' ||
                       json_extract(preferences_json, '$.tables') AS row_value
                FROM web_user ORDER BY id)
            """);
    }

    private static String securityDigest(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT group_concat(row_value, '|') FROM (
                SELECT id || ':' || username || ':' || tier || ':' || primary_admin || ':' ||
                       credential_version || ':' || password_algorithm || ':' || password_iterations || ':' ||
                       password_derived_key_bits || ':' || hex(password_salt) || ':' || hex(password_hash) || ':' ||
                       password_changed_at_ms || ':' || auth_revision || ':' || created_at_ms AS row_value
                FROM web_user ORDER BY id)
            """);
    }

    private static String preferenceDigest(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT group_concat(row_value, '|') FROM (
                SELECT id || ':' || preferences_revision || ':' || updated_at_ms || ':' || preferences_json AS row_value
                FROM web_user ORDER BY id)
            """);
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static String metadata(Connection connection, String key) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
