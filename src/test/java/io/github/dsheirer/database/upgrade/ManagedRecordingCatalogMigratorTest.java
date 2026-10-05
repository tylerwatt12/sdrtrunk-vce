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
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
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
            assertEquals(3, scalar(statement, "PRAGMA user_version"));
            assertEquals(3, scalar(statement, "SELECT format_version FROM catalog_metadata"));
        assertEquals("pending", text(statement,
                "SELECT transcription_status FROM recording_call WHERE id=1"));
            assertEquals(1, scalar(statement, "SELECT seq FROM sqlite_sequence " +
                "WHERE name='recording_call'"));
        }

        ManagedRecordingCatalogMigrator.MigrationResult repeated =
            ManagedRecordingCatalogMigrator.migrate(catalog);
        assertFalse(repeated.migrated());
        assertEquals(null, repeated.backup());
        try(var backups = Files.list(temporary.resolve("backups")))
        {
            assertEquals(1, backups.count());
        }

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
            assertEquals(3, scalar(statement, "PRAGMA user_version"));
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
    void preservesPopulatedFormatTwoTranscriptsAndMarksThemComplete() throws Exception
    {
        Path catalog = temporary.resolve("format-two.sqlite");
        createFormatTwo(catalog);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalog);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms," +
                "relative_path,size_bytes,protocol,call_type,voice_type) " +
                "VALUES(1,1000,2000,1000,'one.mp3',17,1,1,1)");
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms," +
                "relative_path,size_bytes,protocol,call_type,voice_type) " +
                "VALUES(2,2000,2400,400,'short.mp3',11,1,1,1)");
            statement.executeUpdate("INSERT INTO recording_transcript(call_id,text,stored_at_ms) " +
                "VALUES(1,'retained speech',4321)");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=2,total_bytes=28 WHERE id=1");
        }
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());
        ManagedRecordingCatalogMigrator.MigrationResult result =
            ManagedRecordingCatalogMigrator.migrate(catalog);
        assertTrue(result.migrated());
        assertEquals(ManagedRecordingCatalogMigrator.State.CURRENT,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalog);
            Statement statement = connection.createStatement())
        {
            assertEquals(3, scalar(statement, "PRAGMA user_version"));
            assertEquals(2, scalar(statement, "SELECT call_count FROM catalog_metadata"));
            assertEquals(28, scalar(statement, "SELECT total_bytes FROM catalog_metadata"));
            assertEquals("complete", text(statement,
                "SELECT transcription_status FROM recording_call WHERE id=1"));
            assertEquals("pending", text(statement,
                "SELECT transcription_status FROM recording_call WHERE id=2"));
            assertEquals("retained speech", text(statement,
                "SELECT text FROM recording_transcript WHERE call_id=1"));
            assertEquals(4321, scalar(statement,
                "SELECT stored_at_ms FROM recording_transcript WHERE call_id=1"));
        }
        assertEquals(2, scalar(result.backup(), "PRAGMA user_version"));
        assertEquals("retained speech", text(result.backup(),
            "SELECT text FROM recording_transcript WHERE call_id=1"));
        Path retry = temporary.resolve("format-two-retry.sqlite");
        Files.copy(result.backup(), retry);
        assertTrue(ManagedRecordingCatalogMigrator.migrate(retry).migrated());
        assertEquals(3, scalar(retry, "PRAGMA user_version"));
        assertEquals("retained speech", text(retry,
            "SELECT text FROM recording_transcript WHERE call_id=1"));
    }

    @Test
    void currentCatalogWithCommittedWalUsesBoundedInspection() throws Exception
    {
        Path active = temporary.resolve("current-active.sqlite");
        Path offline = temporary.resolve("managed-recordings.sqlite");
        createFormatThree(active);
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
    void startupAdmitsExactOlderCatalogWithoutScanningRetainedCalls() throws Exception
    {
        Path catalog = temporary.resolve("old-startup.sqlite");
        createFormatOne(catalog);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalog);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE recording_call SET duration_ms=-1 WHERE id=1");
        }
        String original = SqliteDatabaseSnapshot.sha256(catalog);

        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        // Full history integrity scans are deliberately omitted from the simplified updater.
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());
        assertEquals(original, SqliteDatabaseSnapshot.sha256(catalog));
        try(var paths = Files.list(temporary))
        {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                .startsWith(".managed-recordings-migration-")));
        }
    }

    @Test
    void oldCatalogStartupHonorsCommittedWalAndRejectsMixedLayouts() throws Exception
    {
        Path active = temporary.resolve("old-active.sqlite");
        Path offline = temporary.resolve("old-offline.sqlite");
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
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(offline).state());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + offline);
            Statement statement = connection.createStatement())
        {
            assertEquals(1, scalar(statement, "SELECT count(*) FROM recording_system"));
            statement.execute("CREATE TABLE unexpected(id INTEGER PRIMARY KEY) STRICT");
        }
        String original = SqliteDatabaseSnapshot.sha256(offline);
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.inspectForStartup(offline));
        assertEquals(original, SqliteDatabaseSnapshot.sha256(offline));
    }

    @Test
    void startupRefusesOldCatalogWithInvalidMetadata() throws Exception
    {
        Path catalog = temporary.resolve("old-invalid-metadata.sqlite");
        createFormatOne(catalog);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalog);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE catalog_metadata SET format_version=2 WHERE id=1");
        }
        String original = SqliteDatabaseSnapshot.sha256(catalog);
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.inspectForStartup(catalog));
        assertEquals(original, SqliteDatabaseSnapshot.sha256(catalog));
    }

    @Test
    void startupRecoversOwnedHotJournalBeforeBoundedAdmission() throws Exception
    {
        Path catalog = temporary.resolve("journal-startup.sqlite");
        createFormatOne(catalog);
        leaveHotJournal(catalog);

        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        assertEquals(1, scalar(catalog, "SELECT count(*) FROM recording_call"));
        assertEquals("one.mp3", text(catalog, "SELECT relative_path FROM recording_call WHERE id=1"));
        assertFalse(Files.exists(Path.of(catalog + "-journal")));
    }

    @Test
    void migrationRecoversHotJournalAndBacksUpOnlyCommittedRows() throws Exception
    {
        Path catalog = temporary.resolve("journal-update.sqlite");
        createFormatOne(catalog);
        leaveHotJournal(catalog);
        var result = ManagedRecordingCatalogMigrator.migrate(catalog);
        assertTrue(result.migrated());
        assertEquals(3, scalar(catalog, "PRAGMA user_version"));
        assertEquals(1, scalar(catalog, "SELECT count(*) FROM recording_call"));
        assertEquals("one.mp3", text(catalog, "SELECT relative_path FROM recording_call WHERE id=1"));
        assertEquals(1, scalar(result.backup(), "PRAGMA user_version"));
        assertEquals(1, scalar(result.backup(), "SELECT count(*) FROM recording_call"));
        assertEquals("one.mp3", text(result.backup(), "SELECT relative_path FROM recording_call WHERE id=1"));
    }

    @Test
    void noBackupUpgradePreservesSourceFileAndDoesNotCreateBackupOrStage() throws Exception
    {
        Path catalog = temporary.resolve("direct-no-backup.sqlite");
        createFormatOne(catalog);
        Object originalFileKey = Files.readAttributes(catalog,
            java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        var result = ManagedRecordingCatalogMigrator.migrate(catalog, false);
        assertTrue(result.migrated());
        assertEquals(null, result.backup());
        assertEquals(3, scalar(catalog, "PRAGMA user_version"));
        assertEquals(1, scalar(catalog, "SELECT count(*) FROM recording_call"));
        assertEquals(originalFileKey, Files.readAttributes(catalog,
            java.nio.file.attribute.BasicFileAttributes.class).fileKey());
        assertFalse(Files.exists(temporary.resolve("backups")));
        try(var paths = Files.list(temporary))
        {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".managed-recordings-migration-")));
        }
    }

    @Test
    void preCommitFailureRollsBackAllAdjacentStepsAndAllowsRetryWithEitherBackupChoice() throws Exception
    {
        for(boolean backup: new boolean[]{false, true})
        {
            Path folder = temporary.resolve("rollback-" + backup);
            Files.createDirectory(folder);
            Path catalog = folder.resolve("managed-recordings.sqlite");
            createFormatOne(catalog);
            SQLException failure = assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(
                catalog, backup, connection -> { throw new SQLException("Injected final validation failure"); },
                source -> DriverManager.getConnection("jdbc:sqlite:" + source)));
            assertTrue(failure.getMessage().contains("Injected final validation failure"));
            assertEquals(1, scalar(catalog, "PRAGMA user_version"));
            assertEquals(0, scalar(catalog, "SELECT count(*) FROM sqlite_master WHERE name='recording_transcript'"));
            assertEquals("one.mp3", text(catalog, "SELECT relative_path FROM recording_call WHERE id=1"));
            assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
                ManagedRecordingCatalogMigrator.inspect(catalog).state());
            if(backup)
            {
                assertTrue(failure.getMessage().contains(folder.resolve("backups").toString()));
                try(var backups = Files.list(folder.resolve("backups")))
                {
                    Path retained = backups.findFirst().orElseThrow();
                    assertEquals(1, scalar(retained, "PRAGMA user_version"));
                    assertEquals(1, scalar(retained, "SELECT count(*) FROM recording_call"));
                }
            }
            else assertFalse(Files.exists(folder.resolve("backups")));
            assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, backup).migrated());
            assertEquals(3, scalar(catalog, "PRAGMA user_version"));
        }
    }

    @Test
    void connectionCleanupFailureAfterCommitDoesNotReportRetryableUpgrade() throws Exception
    {
        Path catalog = temporary.resolve("close-after-commit.sqlite");
        createFormatOne(catalog);
        var result = ManagedRecordingCatalogMigrator.migrate(catalog, false, ignored -> {}, source ->
            cleanupFailingConnection(DriverManager.getConnection("jdbc:sqlite:" + source), false));
        assertTrue(result.migrated());
        assertEquals(3, scalar(catalog, "PRAGMA user_version"));
        assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog).migrated());
    }

    @Test
    void finalSchemaValidationFailureRollsBackAndRetainsOneSourceFormatBackup() throws Exception
    {
        Path catalog = temporary.resolve("invalid-final-schema.sqlite");
        createFormatTwo(catalog);
        SQLException failure = assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(
            catalog, true, connection ->
            {
                try(Statement statement = connection.createStatement())
                {
                    statement.execute("DROP INDEX idx_recording_call_transcription_pending");
                }
            }, source -> DriverManager.getConnection("jdbc:sqlite:" + source)));
        assertTrue(failure.getMessage().contains("schema"));
        assertTrue(failure.getMessage().contains(temporary.resolve("backups").toString()));
        assertEquals(2, scalar(catalog, "PRAGMA user_version"));
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());
        try(var backups = Files.list(temporary.resolve("backups")))
        {
            Path retained = backups.findFirst().orElseThrow();
            assertEquals(2, scalar(retained, "PRAGMA user_version"));
        }
        assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
    }

    @Test
    void statementCleanupFailureAfterCommitDoesNotReportRetryableUpgrade() throws Exception
    {
        Path catalog = temporary.resolve("statement-close-after-commit.sqlite");
        createFormatOne(catalog);
        var result = ManagedRecordingCatalogMigrator.migrate(catalog, false, ignored -> {}, source ->
            cleanupFailingConnection(DriverManager.getConnection("jdbc:sqlite:" + source), true));
        assertTrue(result.migrated());
        assertEquals(3, scalar(catalog, "PRAGMA user_version"));
        assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog).migrated());
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
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false));
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
            statement.execute("PRAGMA user_version=4");
        }
        String original = SqliteDatabaseSnapshot.sha256(catalog);
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.inspect(catalog));
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog));
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false));
        assertEquals(original, SqliteDatabaseSnapshot.sha256(catalog));
    }

    @Test
    void backupFailureLeavesSourceOldAndAllowsNoBackupRetry() throws Exception
    {
        Path catalog = temporary.resolve("managed-recordings.sqlite");
        createFormatOne(catalog);
        Path blocker = temporary.resolve("backups");
        Files.writeString(blocker, "keep-existing-file");
        assertThrows(IOException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog));
        assertEquals(1, scalar(catalog, "PRAGMA user_version"));
        assertEquals("keep-existing-file", Files.readString(blocker));
        assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
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

    @Test
    void refusesHardLinkedCatalogBeforeEitherBackupChoiceCanMutateAnotherProfile() throws Exception
    {
        Path catalog = temporary.resolve("original-profile.sqlite");
        Path alias = temporary.resolve("other-profile.sqlite");
        createFormatOne(catalog);
        Assumptions.assumeTrue(Files.getFileStore(catalog).supportsFileAttributeView("unix"));
        try { Files.createLink(alias, catalog); }
        catch(UnsupportedOperationException | IOException | SecurityException failure)
        {
            Assumptions.assumeTrue(false, "Hard links are unavailable: " + failure.getMessage());
        }
        String original = SqliteDatabaseSnapshot.sha256(catalog);
        assertThrows(IOException.class, () -> ManagedRecordingCatalogMigrator.inspectForStartup(alias));
        for(boolean backup: new boolean[]{false, true})
        {
            IOException failure = assertThrows(IOException.class,
                () -> ManagedRecordingCatalogMigrator.migrate(alias, backup));
            assertTrue(failure.getMessage().contains("multiple filesystem links"));
            assertEquals(original, SqliteDatabaseSnapshot.sha256(catalog));
            assertEquals(1, scalar(catalog, "PRAGMA user_version"));
        }
        assertFalse(Files.exists(temporary.resolve("backups")));
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

    private static void createFormatThree(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            for(String ddl: ManagedRecordingSchema.ddlForFormat(3).values())
            {
                statement.execute(ddl);
            }
            statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1,3,0,0)");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=3");
        }
    }

    private static long scalar(Path database, String query) throws SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            return scalar(statement, query);
        }
    }

    private static String text(Path database, String query) throws SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            return text(statement, query);
        }
    }

    private static String text(Statement statement, String query) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(query))
        {
            assertTrue(rows.next());
            return rows.getString(1);
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

    private static Connection cleanupFailingConnection(Connection delegate, boolean statementCleanup)
    {
        java.util.concurrent.atomic.AtomicBoolean committed = new java.util.concurrent.atomic.AtomicBoolean();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) ->
            {
                try
                {
                    Object result = method.invoke(delegate, args);
                    if(!statementCleanup && method.getName().equals("close"))
                        throw new SQLException("Injected connection close failure after commit");
                    if(statementCleanup && method.getName().equals("createStatement"))
                    {
                        Statement statement = (Statement)result;
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                            (statementProxy, statementMethod, statementArgs) ->
                            {
                                try
                                {
                                    Object value = statementMethod.invoke(statement, statementArgs);
                                    if(statementMethod.getName().equals("execute") && "COMMIT".equals(statementArgs[0]))
                                        committed.set(true);
                                    if(statementMethod.getName().equals("close") && committed.get())
                                        throw new SQLException("Injected statement close failure after commit");
                                    return value;
                                }
                                catch(InvocationTargetException failure) { throw failure.getCause(); }
                            });
                    }
                    return result;
                }
                catch(InvocationTargetException failure) { throw failure.getCause(); }
            });
    }

    private static void leaveHotJournal(Path catalog) throws Exception
    {
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-preview", "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
            HotJournalWriter.class.getName(), catalog.toString()).redirectErrorStream(true).start();
        if(!child.waitFor(5, TimeUnit.SECONDS))
        {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Fixture process could not be stopped");
            throw new AssertionError("Hot-journal fixture timed out");
        }
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), output);
        assertTrue(Files.size(Path.of(catalog + "-journal")) > 512, "Fixture did not leave a recovery journal");
    }

    /** Abrupt exit after cache spill leaves an actual hot rollback journal, not a fabricated sidecar. */
    public static final class HotJournalWriter
    {
        public static void main(String[] args) throws Exception
        {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + Path.of(args[0]));
            Statement statement = connection.createStatement();
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA cache_size=1");
            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE recording_call SET relative_path='uncommitted.mp3' WHERE id=1");
            statement.executeUpdate("WITH RECURSIVE n(id) AS (VALUES(2) UNION ALL SELECT id+1 FROM n WHERE id<1000) " +
                "INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes,protocol,call_type,voice_type) " +
                "SELECT id,1000,2000,1000,'uncommitted-'||id||printf('%02000d',id),17,1,1,1 FROM n");
            Runtime.getRuntime().halt(0);
        }
    }
}
