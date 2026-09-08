/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format5To6DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void resetsAllReceiverActivityWithoutTranslatingAmbiguousLegacyOwners() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("activity-reset.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name,
                    first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES
                    (901, 'GUID:BBBBBBBB-CCCC-4DDD-8EEE-FFFFFFFFFFFF',
                     'BBBBBBBB-CCCC-4DDD-8EEE-FFFFFFFFFFFF', 10, 11, 'Ambiguous Conventional',
                     100, 200, 155550000),
                    (902, 'CONFIGURATION:66666666-7777-4888-8999-aaaaaaaaaaaa',
                     NULL, 10, 11, 'Occupied Former Target', 100, 200, 155560000)
                """);

            long activityRows = LegacyActivityReset.count(connection, LegacyActivityReset.LOGICAL_CALL_TABLES);
            assertTrue(activityRows > 0);
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            DatabaseMigrationEffect reset = preflight.steps().getFirst().effects().getFirst();
            assertEquals(DatabaseMigrationEffect.Kind.RESET, reset.kind());
            assertEquals("receiver-derived activity and counters", reset.subject());
            assertEquals(DatabaseMigrationEffect.UNKNOWN_COUNT, reset.affectedRows());

            connection.setAutoCommit(false);
            try
            {
                new Format5To6DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 6);
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

            assertEquals(0, LegacyActivityReset.count(connection, LegacyActivityReset.LOGICAL_CALL_TABLES));
            assertEquals("29", metadata(connection, "p25_activity_schema_version"));
            assertEquals("6", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(6, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE configuration_id='66666666-7777-4888-8999-aaaaaaaaaaaa'
                """));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void callerRollbackRestoresExactFormat5ActivityAndMetadata() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));

        try(Connection connection = open(database))
        {
            long activityRows = LegacyActivityReset.count(connection, LegacyActivityReset.LOGICAL_CALL_TABLES);
            String contextDigest = scalar(connection, """
                SELECT group_concat(id || ':' || context_key || ':' || coalesce(guid, '') || ':' || kind_code, '|')
                FROM (SELECT * FROM receiver_context ORDER BY id)
                """);
            connection.setAutoCommit(false);
            try
            {
                new Format5To6DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 6);
                assertEquals(0, LegacyActivityReset.count(connection, LegacyActivityReset.LOGICAL_CALL_TABLES));
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(activityRows,
                LegacyActivityReset.count(connection, LegacyActivityReset.LOGICAL_CALL_TABLES));
            assertEquals(contextDigest, scalar(connection, """
                SELECT group_concat(id || ':' || context_key || ':' || coalesce(guid, '') || ':' || kind_code, '|')
                FROM (SELECT * FROM receiver_context ORDER BY id)
                """));
            assertEquals("28", metadata(connection, "p25_activity_schema_version"));
            assertEquals("5", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(5, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test
    void repairsMissingOrCorruptP25SchemaMetadata() throws Exception
    {
        String[] corruptions = {
            "DELETE FROM database_metadata WHERE key='p25_activity_schema_version'",
            "UPDATE database_metadata SET value='corrupt' WHERE key='p25_activity_schema_version'"
        };

        for(int index = 0; index < corruptions.length; index++)
        {
            Path database = Format5TestDatabase.create(
                mTemporaryFolder.resolve("recover-p25-metadata-" + index + ".sqlite"));
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                statement.executeUpdate(corruptions[index]);
                assertEquals(5, DatabaseFormatCatalog.inspectForMigration(connection).version());

                connection.setAutoCommit(false);
                try
                {
                    new Format5To6DatabaseMigration().migrate(connection);
                    DatabaseFormatCatalog.stamp(connection, 6);
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

                assertEquals("29", metadata(connection, "p25_activity_schema_version"));
                assertEquals(6, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(0, LegacyActivityReset.count(connection, LegacyActivityReset.LOGICAL_CALL_TABLES));
            }
        }
    }

    private static Connection open(Path database) throws Exception
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
