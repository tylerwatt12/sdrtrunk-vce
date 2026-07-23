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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrunkedSiteV2DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void backsUpMigratesValidatesAndAtomicallyReplacesExistingV1Database() throws Exception
    {
        Path portableData = mTemporaryFolder.resolve("data");
        Path database = portableData.resolve("database/sdrtrunk.sqlite");
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO alias(sort_order, name) VALUES (0, 'Do Not Prune')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    sort_order, name, auto_start, frequency_count, recording_enabled, event_logging_enabled,
                    config_json
                ) VALUES (0, 'Do Not Prune', 0, 1, 0, 0, '{}')
                """);
            statement.executeUpdate("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms)
                VALUES ('do-not-prune', '{}', 1)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code, configured_system,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('nxdn-v1', 'hash', 4, 2, 4, 'NXDN System', 1000, 2000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_channel_summary (
                    guid, channel_number, inbound_channel_number, timeslot, frequency_hz, role_flags,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('nxdn-v1', 120, 121, -1, 155000000, 1, 1000, 2000, 2)
                """);
            statement.executeUpdate("DROP INDEX idx_trunked_site_snapshot_last_seen");
            statement.executeUpdate("DROP INDEX idx_trunked_site_channel_last_seen");
            statement.executeUpdate("DROP INDEX idx_trunked_site_neighbor_last_seen");
            SdrTrunkDatabaseStartup.setMetadata(connection, TrunkedSiteSchema.SCHEMA_VERSION_KEY, "1");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TrunkedSiteV2DatabaseMigration.MigrationResult result;

        try(PrintStream outputStream = new PrintStream(output, true, StandardCharsets.UTF_8))
        {
            result = TrunkedSiteV2DatabaseMigration.migrate(database, outputStream);
        }

        assertEquals(database.toAbsolutePath().normalize(), result.database());
        assertTrue(Files.isRegularFile(result.safetyBackup()));
        assertTrue(result.safetyBackup().startsWith(database.getParent().resolve("backups")));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains(
            "RESULT: Portable database migration to trunked-site schema v2 is complete."));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            TrunkedSiteSchema.validate(connection);
            assertEquals("2", TrunkedSiteSchema.schemaVersion(connection));
            assertEquals(1, count(connection, "alias"));
            assertEquals(1, count(connection, "configuration_channel"));
            assertEquals(1, count(connection, "application_settings"));
            assertEquals(1, count(connection, "trunked_site_snapshot"));
            assertEquals(1, count(connection, "trunked_site_channel_summary"));
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertFalse(hasRows(connection, "PRAGMA foreign_key_check"));
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + result.safetyBackup()))
        {
            TrunkedSiteSchema.validateVersionOneForMigration(connection);
            assertEquals("1", TrunkedSiteSchema.schemaVersion(connection));
            assertEquals(1, count(connection, "alias"));
            assertEquals(1, count(connection, "configuration_channel"));
            assertEquals(1, count(connection, "application_settings"));
            assertEquals(1, count(connection, "trunked_site_snapshot"));
            assertEquals(1, count(connection, "trunked_site_channel_summary"));
            assertFalse(indexExists(connection, "idx_trunked_site_snapshot_last_seen"));
            assertFalse(indexExists(connection, "idx_trunked_site_channel_last_seen"));
            assertFalse(indexExists(connection, "idx_trunked_site_neighbor_last_seen"));
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
        }
    }

    @Test
    void refusesMigrationWhilePortableDataIsLockedByTheApplication() throws Exception
    {
        Path portableData = mTemporaryFolder.resolve("locked-data");
        Path database = portableData.resolve("database/sdrtrunk.sqlite");
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(PortableDataRootLock ignored = PortableDataRootLock.acquire(portableData))
        {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            try(PrintStream outputStream = new PrintStream(output, true, StandardCharsets.UTF_8))
            {
                IOException exception = assertThrows(IOException.class,
                    () -> TrunkedSiteV2DatabaseMigration.migrate(database, outputStream));
                assertTrue(exception.getMessage().contains("already in use"));
            }
        }
    }

    @Test
    void refusesNonPortableDatabasePath() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try(PrintStream outputStream = new PrintStream(output, true, StandardCharsets.UTF_8))
        {
            IOException exception = assertThrows(IOException.class,
                () -> TrunkedSiteV2DatabaseMigration.migrate(database, outputStream));
            assertTrue(exception.getMessage().contains("portable database path"));
        }
    }

    private static int count(Connection connection, String table) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static boolean indexExists(Connection connection, String index) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?"))
        {
            statement.setString(1, index);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static boolean hasRows(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next();
        }
    }
}
