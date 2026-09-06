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

class Format2To3DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void resetsReceiverActivityWhilePreservingConfigurationAndCreatingTheExactTarget() throws Exception
    {
        Path database = Format2TestDatabase.create(mTemporaryFolder.resolve("format2.sqlite"));

        try(Connection connection = open(database))
        {
            long activityRows = LegacyActivityReset.count(connection,
                LegacyActivityReset.PRE_LOGICAL_CALL_TABLES);
            assertTrue(activityRows > 0);
            Format2To3DatabaseMigration migration = new Format2To3DatabaseMigration();
            DatabaseMigrationEffect reset = migration.validateSource(connection).stream()
                .filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.RESET)
                .findFirst().orElseThrow();
            assertEquals("receiver-derived activity and counters", reset.subject());
            assertEquals(activityRows, reset.affectedRows());

            connection.setAutoCommit(false);
            try
            {
                migration.migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 3);
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

            assertEquals(0, LegacyActivityReset.count(connection,
                LegacyActivityReset.PRE_LOGICAL_CALL_TABLES));
            assertEquals("{\"preserved\":true}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='format-3-preserve-sentinel'
                """));
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*) FROM pragma_table_info('p25_site_channel_summary') WHERE name='callsign'
                """));
            assertEquals("27", metadata(connection, "p25_activity_schema_version"));
            assertEquals("3", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(3, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
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
