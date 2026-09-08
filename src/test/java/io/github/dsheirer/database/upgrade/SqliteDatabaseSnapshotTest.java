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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseSnapshotTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void onlineSnapshotCapturesCommittedRowsThatHaveNotBeenCheckpointedFromWal() throws Exception
    {
        Path source = mTemporaryFolder.resolve("source.sqlite");
        Path snapshot = mTemporaryFolder.resolve("snapshots").resolve("snapshot.sqlite");

        try(Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = writer.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("CREATE TABLE event(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO event(name) VALUES ('committed-in-wal')");

            Path wal = Path.of(source + "-wal");
            assertTrue(Files.isRegularFile(wal));
            assertTrue(Files.size(wal) > 0);

            SqliteDatabaseSnapshot.create(source, snapshot);
            assertTrue(Files.size(wal) > 0);
        }

        assertEquals("committed-in-wal", scalar(snapshot, "SELECT name FROM event WHERE id=1"));
        assertEquals("ok", scalar(snapshot, "PRAGMA quick_check"));
    }

    @Test
    void refusesToOverwriteAnExistingSnapshot() throws Exception
    {
        Path source = mTemporaryFolder.resolve("source.sqlite");
        Path destination = mTemporaryFolder.resolve("existing.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
        }

        Files.writeString(destination, "do-not-replace");
        IOException exception = assertThrows(IOException.class,
            () -> SqliteDatabaseSnapshot.createExternal(source, destination));

        assertTrue(exception.getMessage().contains("Refusing to overwrite"));
        assertEquals("do-not-replace", Files.readString(destination));
    }

    @Test
    void refusesToReplaceADanglingDestinationSymbolicLink() throws Exception
    {
        Path source = mTemporaryFolder.resolve("dangling-source.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
        }

        Path destination = mTemporaryFolder.resolve("dangling-destination.sqlite");
        try
        {
            Files.createSymbolicLink(destination, Path.of("missing-target.sqlite"));
        }
        catch(UnsupportedOperationException | IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        IOException exception = assertThrows(IOException.class,
            () -> SqliteDatabaseSnapshot.createExternal(source, destination));
        assertTrue(exception.getMessage().contains("Refusing to overwrite"));
        assertTrue(Files.isSymbolicLink(destination));
    }

    @Test
    void snapshotsReadOnlyDatabaseFromReadOnlyFolderWithoutChangingSource() throws Exception
    {
        Path sourceFolder = mTemporaryFolder.resolve("read-only-source");
        Files.createDirectory(sourceFolder);
        Path source = sourceFolder.resolve("source.sqlite");
        Path destination = mTemporaryFolder.resolve("read-only-source-snapshot.sqlite");

        Assumptions.assumeTrue(Files.getFileStore(sourceFolder)
            .supportsFileAttributeView(PosixFileAttributeView.class), "POSIX permissions are unavailable");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.executeUpdate("CREATE TABLE sample(value TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO sample(value) VALUES ('preserved')");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        Files.deleteIfExists(Path.of(source + "-wal"));
        Files.deleteIfExists(Path.of(source + "-shm"));

        byte[] originalBytes = Files.readAllBytes(source);
        FileTime originalModifiedTime = Files.getLastModifiedTime(source);

        try
        {
            Files.setPosixFilePermissions(source, EnumSet.of(PosixFilePermission.OWNER_READ));
            Files.setPosixFilePermissions(sourceFolder, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE));

            SqliteDatabaseSnapshot.createExternal(source, destination);

            assertEquals("preserved", scalar(destination, "SELECT value FROM sample"));
            assertArrayEquals(originalBytes, Files.readAllBytes(source));
            assertEquals(originalModifiedTime, Files.getLastModifiedTime(source));
            assertFalse(Files.exists(Path.of(source + "-journal")));
            assertFalse(Files.exists(Path.of(source + "-wal")));
            assertFalse(Files.exists(Path.of(source + "-shm")));
        }
        finally
        {
            Files.setPosixFilePermissions(sourceFolder, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            Files.setPosixFilePermissions(source, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void externalSnapshotIncludesWalWithoutChangingSourceOrSidecars() throws Exception
    {
        Path source = mTemporaryFolder.resolve("external-wal.sqlite");
        Path destination = mTemporaryFolder.resolve("external-wal-snapshot.sqlite");
        try(Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = writer.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
            statement.executeUpdate("INSERT INTO sample(value) VALUES ('from-wal')");

            Path wal = Path.of(source + "-wal");
            Path sharedMemory = Path.of(source + "-shm");
            byte[] databaseBytes = Files.readAllBytes(source);
            byte[] walBytes = Files.readAllBytes(wal);
            byte[] sharedMemoryBytes = Files.readAllBytes(sharedMemory);
            FileTime databaseModified = Files.getLastModifiedTime(source);
            FileTime walModified = Files.getLastModifiedTime(wal);
            FileTime sharedMemoryModified = Files.getLastModifiedTime(sharedMemory);

            SqliteDatabaseSnapshot.createExternal(source, destination);

            assertEquals("from-wal", scalar(destination, "SELECT value FROM sample"));
            assertArrayEquals(databaseBytes, Files.readAllBytes(source));
            assertArrayEquals(walBytes, Files.readAllBytes(wal));
            assertArrayEquals(sharedMemoryBytes, Files.readAllBytes(sharedMemory));
            assertEquals(databaseModified, Files.getLastModifiedTime(source));
            assertEquals(walModified, Files.getLastModifiedTime(wal));
            assertEquals(sharedMemoryModified, Files.getLastModifiedTime(sharedMemory));
        }
    }

    @Test
    void externalSnapshotRecoversPrivateRollbackJournalWithoutChangingSource() throws Exception
    {
        Path source = mTemporaryFolder.resolve("active-journal.sqlite");
        Path destination = mTemporaryFolder.resolve("journal-destination.sqlite");
        try(Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = writer.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
            writer.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO sample(value) VALUES ('uncommitted')");
            Path journal = Path.of(source + "-journal");
            assertTrue(Files.size(journal) > 0);
            byte[] databaseBytes = Files.readAllBytes(source);
            byte[] journalBytes = Files.readAllBytes(journal);
            FileTime databaseModified = Files.getLastModifiedTime(source);
            FileTime journalModified = Files.getLastModifiedTime(journal);

            SqliteDatabaseSnapshot.createExternal(source, destination);

            assertEquals("0", scalar(destination, "SELECT COUNT(*) FROM sample"));
            assertArrayEquals(databaseBytes, Files.readAllBytes(source));
            assertArrayEquals(journalBytes, Files.readAllBytes(journal));
            assertEquals(databaseModified, Files.getLastModifiedTime(source));
            assertEquals(journalModified, Files.getLastModifiedTime(journal));
            writer.rollback();
        }
    }

    @Test
    void failedSnapshotRemovesTheDatabaseAndEverySidecar() throws Exception
    {
        Path source = mTemporaryFolder.resolve("invalid.sqlite");
        Path destination = mTemporaryFolder.resolve("failed-snapshot.sqlite");
        Files.writeString(source, "not a SQLite database");

        assertThrows(SQLException.class, () -> SqliteDatabaseSnapshot.createExternal(source, destination));

        for(String suffix: java.util.List.of("", "-journal", "-wal", "-shm"))
        {
            assertFalse(Files.exists(suffix.isEmpty() ? destination : Path.of(destination + suffix)));
        }
    }

    @Test
    void refusesSymbolicLinkDatabaseAndSidecars() throws Exception
    {
        Path source = mTemporaryFolder.resolve("source.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
        }

        Path sourceLink = mTemporaryFolder.resolve("source-link.sqlite");
        try
        {
            Files.createSymbolicLink(sourceLink, source.getFileName());
        }
        catch(UnsupportedOperationException | IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        Path linkedDestination = mTemporaryFolder.resolve("linked-snapshot.sqlite");
        IOException sourceFailure = assertThrows(IOException.class,
            () -> SqliteDatabaseSnapshot.create(sourceLink, linkedDestination));
        assertTrue(sourceFailure.getMessage().contains("symbolic-link SQLite database"));
        assertFalse(Files.exists(linkedDestination));

        Path external = mTemporaryFolder.resolve("external-sidecar-content");
        Files.writeString(external, "must remain unchanged");

        for(String suffix: java.util.List.of("-journal", "-wal", "-shm"))
        {
            Path sidecar = Path.of(source + suffix);
            Files.createSymbolicLink(sidecar, external.getFileName());
            Path destination = mTemporaryFolder.resolve("sidecar" + suffix + ".sqlite");
            IOException sidecarFailure = assertThrows(IOException.class,
                () -> SqliteDatabaseSnapshot.create(source, destination));
            assertTrue(sidecarFailure.getMessage().contains("symbolic-link SQLite sidecar"));
            assertFalse(Files.exists(destination));
            assertEquals("must remain unchanged", Files.readString(external));
            Files.delete(sidecar);
        }
    }

    private static String scalar(Path database, String sql) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
