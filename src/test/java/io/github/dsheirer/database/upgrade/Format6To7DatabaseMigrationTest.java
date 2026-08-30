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

class Format6To7DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void upgradesEveryUserAndDropsOnlyTheFiveRetiredSettings() throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve("format-6.sqlite"));

        try(Connection connection = open(database))
        {
            String securityBefore = securityDigest(connection);
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals(3, preflight.steps().size());
            assertEquals("format-6-to-7", preflight.steps().getFirst().id());
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.TRANSFORM,
                "per-user browser preference documents", 3);
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DROP,
                "retired global browser-audio settings", 5);

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

            assertEquals(6, report.source().version());
            assertEquals(9, report.target().version());
            assertEquals(3, report.steps().size());
            assertEquals("format-6-to-7", report.steps().getFirst().id());
            assertEquals("3", scalar(connection, """
                SELECT COUNT(*) FROM web_user
                WHERE json_extract(preferences_json, '$.version')=4
                  AND json_extract(preferences_json, '$.playback.conversation_grouping')=1
                  AND json_extract(preferences_json, '$.playback.conversation_burst_limit')=4
                  AND json_array_length(json_extract(preferences_json,
                      '$.health_alerts.disabled_codes'))=0
                  AND preferences_revision=4
                """));
            assertEquals("1.0:0:1.0:0", scalar(connection, """
                SELECT min(json_extract(preferences_json, '$.playback.volume')) || ':' ||
                       min(json_array_length(json_extract(preferences_json,
                           '$.playback.selected_scan_list_ids'))) || ':' ||
                       max(json_extract(preferences_json, '$.playback.volume')) || ':' ||
                       max(json_array_length(json_extract(preferences_json,
                           '$.playback.selected_scan_list_ids')))
                FROM web_user
                """));
            assertEquals("0:0:1:detailed:125", scalar(connection, """
                SELECT json_extract(preferences_json, '$.presentation.show_encryption_details') || ':' ||
                       json_extract(preferences_json, '$.presentation.show_control_decode_quality') || ':' ||
                       json_extract(preferences_json, '$.presentation.show_voice_decode_quality') || ':' ||
                       json_extract(preferences_json, '$.presentation.decode_quality_display_mode') || ':' ||
                       json_extract(preferences_json, '$.presentation.live_detail_row_limit')
                FROM web_user WHERE username='listener'
                """));
            assertEquals("0", retiredSettingCount(connection));
            assertEquals("preserve-application", scalar(connection, """
                SELECT json_extract(settings_json,
                    '$."user/io/github/dsheirer/preference/application"."unrelated.application.setting"')
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("preserve-me", scalar(connection, """
                SELECT json_extract(settings_json, '$."user/example".sentinel')
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(securityBefore, securityDigest(connection));
            assertEquals("9", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals(9, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void callerRollbackRestoresExactFormat6DocumentsAndSettings() throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));

        try(Connection connection = open(database))
        {
            String usersBefore = preferenceDigest(connection);
            String settingsBefore = settingDigest(connection);
            connection.setAutoCommit(false);
            try
            {
                assertEquals(9, DatabaseMigrationChain.migrate(connection).target().version());
                assertEquals("0", retiredSettingCount(connection));
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(usersBefore, preferenceDigest(connection));
            assertEquals(settingsBefore, settingDigest(connection));
            assertEquals(6, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test
    void rejectsUnknownVersionOneFieldsAndExhaustedRevisionsWithoutChangingSource() throws Exception
    {
        Path unknown = Format6TestDatabase.create(mTemporaryFolder.resolve("unknown.sqlite"));
        try(Connection connection = open(unknown); Statement statement = connection.createStatement())
        {
            String before = preferenceDigest(connection);
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
            assertTrue(!before.equals(malformed));
            assertEquals("6", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        }

        Path exhausted = Format6TestDatabase.create(mTemporaryFolder.resolve("exhausted.sqlite"));
        try(Connection connection = open(exhausted); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE web_user SET preferences_revision=9223372036854775807 WHERE id=1");
            SQLException rejection = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.migrate(connection));
            assertTrue(rejection.getMessage().contains("preference revision must be positive and incrementable"),
                rejection.getMessage());
            assertEquals("6", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        }
    }

    @Test
    void refusesMoreThanSixteenSelectedScanListsWithoutTruncatingTheSource() throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve("too-many-scan-lists.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE web_user
                SET preferences_json=json_set(preferences_json, '$.playback.selected_scan_list_ids',
                    json('[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17]'))
                WHERE id=1
                """);
            String source = preferenceDigest(connection);
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            assertEquals(6, detected.version());

            SQLException preflightRejection = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.validateSource(connection, detected));
            assertScanListLimitRefusal(preflightRejection);
            assertEquals(source, preferenceDigest(connection));

            SQLException migrationRejection = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.migrate(connection));
            assertScanListLimitRefusal(migrationRejection);
            assertEquals(source, preferenceDigest(connection));
            assertEquals("6", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        }
    }

    @Test
    void currentFormatRejectsAReintroducedGlobalWebAudioSetting() throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve("retired-setting.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            connection.setAutoCommit(true);
            statement.executeUpdate("""
                UPDATE application_settings
                SET settings_json=json_set(settings_json,
                    '$."user/example"."stats.web.call.maximum.listeners"', '32')
                WHERE key='portable_java_preferences_v1'
                """);
            SQLException rejection = assertThrows(SQLException.class,
                () -> DatabaseFormatCatalog.requireCurrent(connection));
            assertTrue(rejection.getMessage().contains("retired global browser-audio setting"),
                rejection.getMessage());
        }
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects, DatabaseMigrationEffect.Kind kind,
                                     String subject, long rows)
    {
        DatabaseMigrationEffect effect = effects.stream().filter(candidate -> candidate.kind() == kind &&
            candidate.subject().equals(subject)).findFirst().orElseThrow();
        assertEquals(rows, effect.affectedRows());
    }

    private static void assertScanListLimitRefusal(SQLException rejection)
    {
        assertTrue(rejection.getMessage().contains("web user 1 selected 17 scan lists"), rejection.getMessage());
        assertTrue(rejection.getMessage().contains("format 7 supports at most 16"), rejection.getMessage());
        assertTrue(rejection.getMessage().contains("previous build before migrating"), rejection.getMessage());
    }

    private static String retiredSettingCount(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT COUNT(*)
            FROM application_settings AS settings, json_tree(settings.settings_json) AS item
            WHERE settings.key='portable_java_preferences_v1'
              AND item.key IN (
                'stats.web.call.maximum.listeners',
                'stats.web.call.maximum.selected.scan.lists',
                'stats.web.call.maximum.browser.queue.calls',
                'stats.web.call.maximum.cached.calls',
                'stats.web.call.maximum.cached.audio.mib')
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

    private static String settingDigest(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT group_concat(row_value, '|') FROM (
                SELECT key || ':' || updated_at_ms || ':' || settings_json AS row_value
                FROM application_settings ORDER BY key)
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
