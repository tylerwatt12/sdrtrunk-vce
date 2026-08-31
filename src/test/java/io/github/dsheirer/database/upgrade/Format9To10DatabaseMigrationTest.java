/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format9To10DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preservesPopulatedFormatNineAndDefaultsOverridesOff() throws Exception
    {
        Path database = Format9TestDatabase.create(mTemporaryFolder.resolve("format-9.sqlite"));

        try(Connection connection = open(database))
        {
            String configurationBefore = configurationDigest(connection);
            String fingerprintBefore = SqliteSchemaValidator.fingerprint(connection);
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals(1, preflight.steps().size());
            assertEquals("format-9-to-10", preflight.steps().getFirst().id());
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.PRESERVE,
                "saved channels and application settings", 4);
            assertEffect(preflight.steps().getFirst().effects(), DatabaseMigrationEffect.Kind.DEFAULT,
                "P25 bandplan overrides", 0);

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

            assertEquals(9, report.source().version());
            assertEquals(10, report.target().version());
            assertEquals("format-9-to-10", report.steps().getFirst().id());
            assertEquals(configurationBefore, configurationDigest(connection));
            assertEquals(fingerprintBefore, SqliteSchemaValidator.fingerprint(connection));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE json_type(config_json,
                    '$.decodeConfiguration.useP25BandplanOverride') IS NOT NULL
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM application_settings WHERE key='p25.bandplan.overrides'
                """));
            assertEquals("10", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals(10, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void callerRollbackRestoresExactFormatNineMarkerAndData() throws Exception
    {
        Path database = Format9TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));

        try(Connection connection = open(database))
        {
            String configurationBefore = configurationDigest(connection);
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

            assertEquals(configurationBefore, configurationDigest(connection));
            assertEquals(9, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("9", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        }
    }

    private static void assertEffect(List<DatabaseMigrationEffect> effects, DatabaseMigrationEffect.Kind kind,
                                     String subject, long rows)
    {
        DatabaseMigrationEffect effect = effects.stream().filter(candidate -> candidate.kind() == kind &&
            candidate.subject().equals(subject)).findFirst().orElseThrow();
        assertEquals(rows, effect.affectedRows());
    }

    private static String configurationDigest(Connection connection) throws SQLException
    {
        return scalar(connection, """
            SELECT group_concat(value, '|') FROM (
                SELECT 'channel:' || id || ':' || configuration_id || ':' || config_json AS value
                FROM configuration_channel
                UNION ALL
                SELECT 'setting:' || key || ':' || settings_json AS value
                FROM application_settings
                ORDER BY value)
            """);
    }

    private static String metadata(Connection connection, String key) throws SQLException
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

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
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
}
