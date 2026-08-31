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

class Format8To9DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void movesSharedPresentationChoicesToEveryUserAndRemovesOnlyThoseKeys() throws Exception
    {
        Path database = Format8TestDatabase.create(mTemporaryFolder.resolve("format-8.sqlite"));

        try(Connection connection = open(database))
        {
            String securityBefore = securityDigest(connection);
            String preferencesBefore = existingPreferenceDigest(connection);
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals(2, preflight.steps().size());
            assertEquals("format-8-to-9", preflight.steps().getFirst().id());
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.TRANSFORM,
                "per-user Live presentation settings", 3);
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DROP,
                "obsolete shared Live presentation settings", 2);

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

            assertEquals(8, report.source().version());
            assertEquals(10, report.target().version());
            assertEquals("format-8-to-9", report.steps().getFirst().id());
            assertEquals("3", scalar(connection, """
                SELECT COUNT(*) FROM web_user
                WHERE json_extract(preferences_json, '$.version')=4
                  AND json_extract(preferences_json,
                      '$.presentation.show_only_active_trunked_channels')=0
                  AND json_extract(preferences_json,
                      '$.presentation.retain_last_call_on_idle_rows')=1
                  AND json_extract(preferences_json,
                      '$.presentation.clear_voice_quality_when_idle')=0
                  AND preferences_revision=4
                """));
            assertEquals(preferencesBefore, existingPreferenceDigest(connection));
            assertEquals(securityBefore, securityDigest(connection));
            assertEquals("1:1750:preserve-application:preserve-me", scalar(connection, """
                SELECT json_extract(settings_json,
                           '$."user/io/github/dsheirer/preference/nowplaying"."site.settings.revision"') || ':' ||
                       json_extract(settings_json,
                           '$."user/io/github/dsheirer/preference/nowplaying"."traffic.grant.age.out.milliseconds"') || ':' ||
                       json_extract(settings_json,
                           '$."user/io/github/dsheirer/preference/application"."unrelated.application.setting"') || ':' ||
                       json_extract(settings_json, '$."user/example".sentinel')
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM application_settings
                WHERE key='portable_java_preferences_v1'
                  AND (json_type(settings_json,
                        '$."user/io/github/dsheirer/preference/nowplaying"."retain.idle.call.details"') IS NOT NULL
                    OR json_type(settings_json,
                        '$."user/io/github/dsheirer/preference/nowplaying"."clear.voice.decode.quality.on.call.end"')
                        IS NOT NULL)
                """));
            assertEquals("10", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals(10, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void absentSharedChoicesDefaultFalseWithoutChangingUnrelatedPortablePreferences() throws Exception
    {
        Path database = Format8TestDatabase.create(mTemporaryFolder.resolve("defaults.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE application_settings
                SET settings_json=json_remove(settings_json,
                    '$."user/io/github/dsheirer/preference/nowplaying"."retain.idle.call.details"',
                    '$."user/io/github/dsheirer/preference/nowplaying"."clear.voice.decode.quality.on.call.end"')
                WHERE key='portable_java_preferences_v1'
                """);
            String portableBefore = scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """);

            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DROP,
                "obsolete shared Live presentation settings", 0);
            assertEquals(10, DatabaseMigrationChain.migrate(connection).target().version());

            assertEquals("3", scalar(connection, """
                SELECT COUNT(*) FROM web_user
                WHERE json_extract(preferences_json,
                      '$.presentation.show_only_active_trunked_channels')=0
                  AND json_extract(preferences_json,
                      '$.presentation.retain_last_call_on_idle_rows')=0
                  AND json_extract(preferences_json,
                      '$.presentation.clear_voice_quality_when_idle')=0
                """));
            assertEquals(portableBefore, scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
        }
    }

    @Test
    void callerRollbackRestoresFormatEightUsersPortablePreferencesAndMarker() throws Exception
    {
        Path database = Format8TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));
        try(Connection connection = open(database))
        {
            String usersBefore = preferenceDigest(connection);
            String portableBefore = scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """);
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
            assertEquals(portableBefore, scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(8, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test
    void refusesAnExhaustedUserRevisionWithoutChangingTheSource() throws Exception
    {
        Path database = Format8TestDatabase.create(mTemporaryFolder.resolve("exhausted.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE web_user SET preferences_revision=9223372036854775807 WHERE id=1");
            statement.execute("PRAGMA ignore_check_constraints=OFF");
            String before = preferenceDigest(connection);
            SQLException rejection = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.migrate(connection));
            assertTrue(rejection.getMessage().contains("preference revision must be positive and incrementable"),
                rejection.getMessage());
            assertEquals(before, preferenceDigest(connection));
            assertEquals("8", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        }
    }

    private static String existingPreferenceDigest(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT group_concat(row_value, '|') FROM (
                SELECT id || ':' || json_remove(json_set(preferences_json, '$.version', 3),
                    '$.presentation.show_only_active_trunked_channels',
                    '$.presentation.retain_last_call_on_idle_rows',
                    '$.presentation.clear_voice_quality_when_idle') AS row_value
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

    private static void assertEffect(List<DatabaseMigrationEffect> effects, DatabaseMigrationEffect.Kind kind,
                                     String subject, long rows)
    {
        DatabaseMigrationEffect effect = effects.stream().filter(candidate -> candidate.kind() == kind &&
            candidate.subject().equals(subject)).findFirst().orElseThrow();
        assertEquals(rows, effect.affectedRows());
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
