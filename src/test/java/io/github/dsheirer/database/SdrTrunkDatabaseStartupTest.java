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

package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.stats.activity.DmrActivitySchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

class SdrTrunkDatabaseStartupTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsAndValidatesCurrentDmrSchema() throws Exception
    {
        Path database = mTemporaryFolder.resolve("new-global.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        assertEquals("wal", journalMode(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT value FROM database_metadata WHERE key = 'dmr_activity_schema_version'
                """))
        {
            assertTrue(resultSet.next());
            assertEquals(Integer.toString(DmrActivitySchema.SCHEMA_VERSION), resultSet.getString(1));
            DmrActivitySchema.validate(connection);
        }
    }

    @Test
    void createsAndValidatesUnmatchedTalkgroupScanListMembershipSchema() throws Exception
    {
        Path database = mTemporaryFolder.resolve("unmatched-scan-lists.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery("""
                SELECT sql FROM sqlite_master
                WHERE type = 'table'
                  AND name = 'alias_list_unmatched_talkgroup_scan_list_membership'
                """))
            {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getString("sql").contains("PRIMARY KEY(alias_list_id, scan_list_id)"));
                assertTrue(resultSet.getString("sql").contains("WITHOUT ROWID"));
            }

            try(ResultSet resultSet = statement.executeQuery("""
                SELECT sql FROM sqlite_master
                WHERE type = 'index'
                  AND name = 'idx_alias_list_unmatched_talkgroup_scan_list_by_list'
                """))
            {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getString("sql").contains("scan_list_id, alias_list_id"));
            }
        }

        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test
    void defaultAliasListSeedReusesMatchingFamilyAndRoutesEveryFamily() throws Exception
    {
        Path database = mTemporaryFolder.resolve("default-alias-lists.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family)
                VALUES (25, 'Default P25', 'P25')
                """);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);

            assertEquals("25:P25|26:DMR|27:NXDN|28:NBFM", scalar(statement, """
                SELECT group_concat(value, '|')
                FROM (
                    SELECT id || ':' || family AS value
                    FROM alias_list
                    WHERE name IN ('Default P25', 'Default DMR', 'Default NXDN', 'Default NBFM')
                    ORDER BY id
                )
                """));
            assertEquals("Default DMR|Default NBFM|Default NXDN|Default P25", scalar(statement, """
                SELECT group_concat(name, '|')
                FROM (
                    SELECT alias_list.name
                    FROM alias_list
                    JOIN alias_list_unmatched_talkgroup_scan_list_membership AS membership
                      ON membership.alias_list_id = alias_list.id
                    JOIN scan_list ON scan_list.id = membership.scan_list_id
                    WHERE scan_list.is_default = 1
                    ORDER BY alias_list.name
                )
                """));
        }
    }

    @Test
    void defaultAliasListSeedRejectsWrongFamilyNameCollisionBeforeChangingRows() throws Exception
    {
        Path database = mTemporaryFolder.resolve("wrong-family-default.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);
            statement.executeUpdate("""
                INSERT INTO alias_list(name, family)
                VALUES ('Default P25', 'DMR')
                """);

            java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
                () -> SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection));
            assertTrue(failure.getMessage().contains("Default P25"));
            assertTrue(failure.getMessage().contains("expected [P25]"));
            assertEquals("1", scalar(statement, "SELECT COUNT(*) FROM alias_list"));
            assertEquals("0", scalar(statement,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership"));
        }
    }

    @Test
    void rejectsIncompleteExistingSchemaWithoutRepairingIt() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);
        }

        assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'p25_system'
                """))
        {
            assertFalse(resultSet.next());
        }
    }

    @Test
    void rejectsMissingDmrSchemaWithoutRepairingIt() throws Exception
    {
        Path database = mTemporaryFolder.resolve("missing-dmr.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_RETENTION_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.RADIO_RETENTION_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_CONTEXT_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.RADIO_CONTEXT_INDEX);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.TALKGROUP_TABLE);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.RADIO_TABLE);
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                DmrActivitySchema.SCHEMA_VERSION_KEY + "'");
        }

        assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT 1 FROM sqlite_master WHERE type = 'table'
                    AND name = 'dmr_conventional_talkgroup_summary'
                """))
        {
            assertFalse(resultSet.next());
        }
    }

    @Test
    void rejectsChangedAliasTableDefinitionWithoutRepairingIt() throws Exception
    {
        Path database = mTemporaryFolder.resolve("changed-alias-definition.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA writable_schema=ON");
            statement.executeUpdate("""
                UPDATE sqlite_master
                SET sql = replace(sql, 'alias_list_id INTEGER', 'alias_list_id TEXT')
                WHERE type = 'table' AND name = 'alias'
                """);
            statement.execute("PRAGMA writable_schema=OFF");
        }

        assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'alias'
                """))
        {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getString(1).contains("alias_list_id TEXT"));
        }
    }

    @Test
    void rejectsCurrentTupleWithWebfirstRecordingTable() throws Exception
    {
        Path database = mTemporaryFolder.resolve("webfirst-table.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE recorded_call(id INTEGER PRIMARY KEY)");
        }

        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertTrue(failure.getMessage().contains("webfirst managed-recording"));
    }

    @Test
    void rejectsWebfirstFootprintWithoutChangingDatabaseOrJournalMode() throws Exception
    {
        Path database = mTemporaryFolder.resolve("webfirst-read-only-rejection.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.executeUpdate("CREATE TABLE recorded_call(id INTEGER PRIMARY KEY)");
        }
        assertEquals("delete", journalMode(database));
        byte[] before = Files.readAllBytes(database);
        FileTime modifiedBefore = Files.getLastModifiedTime(database);
        List<String> siblingsBefore;
        try(var files = Files.list(database.getParent()))
        {
            siblingsBefore = files.map(path -> path.getFileName().toString()).sorted().toList();
        }

        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        assertTrue(failure.getMessage().contains("webfirst managed-recording"));
        assertEquals("delete", journalMode(database));
        assertArrayEquals(before, Files.readAllBytes(database));
        assertEquals(modifiedBefore, Files.getLastModifiedTime(database));
        try(var files = Files.list(database.getParent()))
        {
            assertEquals(siblingsBefore,
                files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void rejectsCurrentTupleWithWebfirstRecordingMetadata() throws Exception
    {
        Path database = mTemporaryFolder.resolve("webfirst-metadata.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO database_metadata(key, value, updated_at_ms)
                VALUES ('recorded_call_catalog_schema_version', '3', 1)
                """);
        }

        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertTrue(failure.getMessage().contains("recorded_call_catalog_schema_version"));
    }

    @Test
    void similarlyNamedNonWebfirstMetadataIsAccepted() throws Exception
    {
        Path database = mTemporaryFolder.resolve("non-webfirst-names.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO database_metadata(key, value, updated_at_ms)
                VALUES ('recordedXcall_catalog_schema_version', 'not-webfirst', 1)
                """);
        }

        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        assertEquals("wal", journalMode(database));
    }

    @Test
    void rejectsExtraTriggerWithoutChangingJournalMode() throws Exception
    {
        Path database = mTemporaryFolder.resolve("extra-trigger.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.executeUpdate("""
                CREATE TRIGGER unexpected_alias_trigger
                AFTER INSERT ON alias
                BEGIN
                    SELECT 1;
                END
                """);
        }

        assertEquals("delete", journalMode(database));
        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertTrue(failure.getMessage().contains("Unrecognized SQLite database schema fingerprint"));
        assertEquals("delete", journalMode(database));
    }

    @Test
    void rejectsSameNamedIndexWithWrongDefinition() throws Exception
    {
        Path database = mTemporaryFolder.resolve("wrong-index-definition.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_configuration_channel_sort");
            statement.executeUpdate("""
                CREATE INDEX idx_configuration_channel_sort
                ON configuration_channel(id)
                """);
        }

        java.sql.SQLException failure = assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertTrue(failure.getMessage().contains("Unrecognized SQLite database schema fingerprint"));
    }

    private static String journalMode(Path database) throws Exception
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database,
            config.toProperties()); Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode"))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static String scalar(Statement statement, String sql) throws Exception
    {
        try(ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
