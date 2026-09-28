/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format27To28DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preservesSavedConfigurationAndDefaultsAllExistingChannelsToLocal() throws Exception
    {
        Path database = Format27TestDatabase.create(mTemporaryFolder.resolve("format-27.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            String configurationBefore = configurationDigest(statement);
            String settingsBefore = settingsDigest(statement);
            String fingerprintBefore = SqliteSchemaValidator.fingerprint(connection);
            long channelCount = number(statement, "SELECT COUNT(*) FROM configuration_channel");
            long settingsCount = number(statement, "SELECT COUNT(*) FROM application_settings");

            List<DatabaseMigrationEffect> validated = new Format27To28DatabaseMigration().validateSource(connection);
            assertEffect(validated, DatabaseMigrationEffect.Kind.PRESERVE, "saved channel configurations",
                channelCount);
            assertEffect(validated, DatabaseMigrationEffect.Kind.PRESERVE, "application settings", settingsCount);
            assertEffect(validated, DatabaseMigrationEffect.Kind.DEFAULT, "remote P25 source assignments", 0);

            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals(1, preflight.steps().size());
            assertEquals("format-27-to-28", preflight.steps().getFirst().id());

            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
            assertEquals(1, report.steps().size());
            assertEquals("format-27-to-28", report.steps().getFirst().id());
            assertEffect(report.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DEFAULT,
                "remote P25 source assignments", 0);
            assertEquals(configurationBefore, configurationDigest(statement));
            assertEquals(settingsBefore, settingsDigest(statement));
            assertEquals(fingerprintBefore, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(0, number(statement, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE json_extract(config_json, '$.sourceConfiguration.type')='sourceConfigRemote'
                """));
            assertEquals("28", metadata(statement));
            assertEquals(28, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
        }
    }

    @Test
    void rollbackRestoresFormat27AndAllowsTheSameSourceToBeRetried() throws Exception
    {
        Path database = Format27TestDatabase.create(mTemporaryFolder.resolve("retry.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            String before = configurationDigest(statement);
            connection.setAutoCommit(false);
            try
            {
                assertEquals(28, DatabaseMigrationChain.migrate(connection).target().version());
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(27, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("27", metadata(statement));
            assertEquals(before, configurationDigest(statement));
            assertEquals(28, DatabaseMigrationChain.migrate(connection).target().version());
            assertEquals(28, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects, DatabaseMigrationEffect.Kind kind,
                                     String subject, long rows)
    {
        assertTrue(effects.stream().anyMatch(effect -> effect.kind() == kind && effect.subject().equals(subject) &&
            effect.affectedRows() == rows), effects::toString);
    }

    private static String configurationDigest(Statement statement) throws Exception
    {
        return text(statement, """
            SELECT coalesce(group_concat(configuration_id || ':' || config_json, char(10)), '')
            FROM (SELECT configuration_id, config_json FROM configuration_channel ORDER BY id)
            """);
    }

    private static String settingsDigest(Statement statement) throws Exception
    {
        return text(statement, """
            SELECT coalesce(group_concat(key || ':' || settings_json || ':' || updated_at_ms, char(10)), '')
            FROM (SELECT key, settings_json, updated_at_ms FROM application_settings ORDER BY key)
            """);
    }

    private static long number(Statement statement, String sql) throws Exception
    {
        try(ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static String text(Statement statement, String sql) throws Exception
    {
        try(ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static String metadata(Statement statement) throws Exception
    {
        return text(statement, "SELECT value FROM database_metadata WHERE key='database_format_version'");
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
