/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.record.managed.ManagedRecordingSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingCatalogMigratorTest
{
    @TempDir
    Path temporary;

    @Test
    void preservesPopulatedFormatOneAndRetainsBackup() throws Exception
    {
        Path catalog = temporary.resolve("managed-recordings.sqlite");
        createFormatOne(catalog);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());

        ManagedRecordingCatalogMigrator.MigrationResult result =
            ManagedRecordingCatalogMigrator.migrate(catalog);
        assertTrue(result.migrated());
        assertNotNull(result.backup());
        assertTrue(Files.isRegularFile(result.backup()));
        assertEquals(ManagedRecordingCatalogMigrator.State.CURRENT,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspect(result.backup()).state());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + result.backup());
            Statement statement = connection.createStatement())
        {
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_call"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_call_site"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_patch_member"));
            assertEquals(1, scalar(statement, "SELECT id FROM recording_call"));
            assertEquals(1, scalar(statement, "SELECT site_id FROM recording_call_site"));
            assertEquals(42, scalar(statement, "SELECT local_id FROM recording_patch_member"));
            assertEquals(1, scalar(statement, "PRAGMA user_version"));
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalog);
            Statement statement = connection.createStatement())
        {
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_call"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_call_site"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_patch_member"));
            assertEquals(1, scalar(statement, "SELECT id FROM recording_call"));
            assertEquals(1, scalar(statement, "SELECT call_count FROM catalog_metadata"));
            assertEquals(17, scalar(statement, "SELECT total_bytes FROM catalog_metadata"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM recording_transcript"));
            assertEquals(2, scalar(statement, "PRAGMA user_version"));
            assertEquals(2, scalar(statement, "SELECT format_version FROM catalog_metadata"));
            assertEquals(1, scalar(statement, "SELECT seq FROM sqlite_sequence " +
                "WHERE name='recording_call'"));
        }

        ManagedRecordingCatalogMigrator.MigrationResult repeated =
            ManagedRecordingCatalogMigrator.migrate(catalog);
        assertFalse(repeated.migrated());
        assertEquals(null, repeated.backup());

        Path retry = temporary.resolve("retry.sqlite");
        Files.copy(result.backup(), retry);
        assertTrue(ManagedRecordingCatalogMigrator.migrate(retry).migrated());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + retry);
            Statement statement = connection.createStatement())
        {
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_call"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_call_site"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_patch_member"));
            assertEquals(1, scalar(statement, "SELECT id FROM recording_call"));
            assertEquals(2, scalar(statement, "PRAGMA user_version"));
        }
    }

    @Test
    void preservesCommittedWalDuringOfflineMigration() throws Exception
    {
        Path active = temporary.resolve("active.sqlite");
        Path offline = temporary.resolve("managed-recordings.sqlite");
        createFormatOne(active);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("UPDATE recording_call SET relative_path='from-wal.mp3' WHERE id=1");
            Files.copy(active, offline);
            Files.copy(Path.of(active + "-wal"), Path.of(offline + "-wal"));
            Files.copy(Path.of(active + "-shm"), Path.of(offline + "-shm"));
        }
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspect(offline).state());
        ManagedRecordingCatalogMigrator.MigrationResult result =
            ManagedRecordingCatalogMigrator.migrate(offline);
        assertTrue(result.migrated());
        assertFalse(Files.exists(Path.of(offline + "-wal")));
        assertFalse(Files.exists(Path.of(offline + "-shm")));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + offline);
            Statement statement = connection.createStatement())
        {
            try(ResultSet rows = statement.executeQuery("SELECT relative_path FROM recording_call WHERE id=1"))
            {
                assertTrue(rows.next());
                assertEquals("from-wal.mp3", rows.getString(1));
            }
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + result.backup());
            Statement statement = connection.createStatement())
        {
            try(ResultSet rows = statement.executeQuery("SELECT relative_path FROM recording_call WHERE id=1"))
            {
                assertTrue(rows.next());
                assertEquals("from-wal.mp3", rows.getString(1));
            }
        }
    }

    @Test
    void currentCatalogWithCommittedWalUsesBoundedInspection() throws Exception
    {
        Path active = temporary.resolve("current-active.sqlite");
        Path offline = temporary.resolve("managed-recordings.sqlite");
        createFormatTwo(active);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("INSERT INTO recording_system VALUES(1,'committed-in-wal')");
            Files.copy(active, offline);
            Files.copy(Path.of(active + "-wal"), Path.of(offline + "-wal"));
            Files.copy(Path.of(active + "-shm"), Path.of(offline + "-shm"));
        }

        assertEquals(ManagedRecordingCatalogMigrator.State.CURRENT,
            ManagedRecordingCatalogMigrator.inspect(offline).state());
        try(var paths = Files.list(temporary))
        {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                .startsWith(".managed-recordings-migration-")));
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + offline);
            Statement statement = connection.createStatement())
        {
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_system " +
                "WHERE system_key='committed-in-wal'"));
        }
    }

    @Test
    void refusesMixedSignatureWithoutChangingSource() throws Exception
    {
        Path catalog = temporary.resolve("managed-recordings.sqlite");
        createFormatOne(catalog);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalog);
            Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE unexpected(id INTEGER PRIMARY KEY) STRICT");
        }
        String original = SqliteDatabaseSnapshot.sha256(catalog);
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.inspect(catalog));
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog));
        assertEquals(original, SqliteDatabaseSnapshot.sha256(catalog));
    }

    @Test
    void refusesNewerFormatWithoutChangingSource() throws Exception
    {
        Path catalog = temporary.resolve("managed-recordings.sqlite");
        createFormatOne(catalog);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalog);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA user_version=3");
        }
        String original = SqliteDatabaseSnapshot.sha256(catalog);
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.inspect(catalog));
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog));
        assertEquals(original, SqliteDatabaseSnapshot.sha256(catalog));
    }

    @Test
    void childRefusesLiveCatalogPath() throws Exception
    {
        Path catalog = temporary.resolve("managed-recordings.sqlite");
        createFormatOne(catalog);
        String original = SqliteDatabaseSnapshot.sha256(catalog);
        assertThrows(java.io.IOException.class, () -> ManagedRecordingCatalogMigrator.runChild(catalog));
        assertEquals(original, SqliteDatabaseSnapshot.sha256(catalog));
    }

    @Test
    void absentCatalogDoesNotCreateAFile() throws Exception
    {
        Path catalog = temporary.resolve("managed-recordings.sqlite");
        assertEquals(ManagedRecordingCatalogMigrator.State.ABSENT,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());
        assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog).migrated());
        assertFalse(Files.exists(catalog));
    }

    private static void createFormatOne(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            for(String ddl: ManagedRecordingSchema.ddlForFormat(1).values())
            {
                statement.execute(ddl);
            }
            statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1,1,1,17)");
            statement.executeUpdate("INSERT INTO recording_system VALUES(1,'P25:123:456')");
            statement.executeUpdate("INSERT INTO recording_channel VALUES(1,'11111111-1111-1111-1111-111111111111')");
            statement.executeUpdate("INSERT INTO recording_site VALUES(1,1,2,3,4)");
            statement.executeUpdate("INSERT INTO recording_system_site VALUES(1,1)");
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms," +
                "relative_path,size_bytes,system_id,channel_id,protocol,call_type,voice_type) " +
                "VALUES(1,1000,2000,1000,'one.mp3',17,1,1,1,1,1)");
            statement.executeUpdate("INSERT INTO recording_call_site VALUES(1,1,1000)");
            statement.executeUpdate("INSERT INTO recording_patch_member VALUES(1,1,42,0,0,0,1000)");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=1");
        }
    }

    private static void createFormatTwo(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            for(String ddl: ManagedRecordingSchema.ddlForFormat(2).values())
            {
                statement.execute(ddl);
            }
            statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1,2,0,0)");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=2");
        }
    }

    private static long scalar(Statement statement, String query) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(query))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
