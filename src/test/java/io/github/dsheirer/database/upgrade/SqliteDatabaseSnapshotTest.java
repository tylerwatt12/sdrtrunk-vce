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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseSnapshotTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void capturesCommittedRowsThatHaveNotBeenCheckpointedFromWal() throws Exception
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

            assertTrue(Files.isRegularFile(wal), "Creating a snapshot must not remove the source WAL");
            assertTrue(Files.size(wal) > 0, "The writer keeps committed pages in the source WAL");
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT name FROM event WHERE id=1"))
        {
            assertTrue(resultSet.next());
            assertEquals("committed-in-wal", resultSet.getString(1));
        }

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
            () -> SqliteDatabaseSnapshot.create(source, destination));

        assertTrue(exception.getMessage().contains("Refusing to overwrite"));
        assertEquals("do-not-replace", Files.readString(destination));
    }

    @Test
    void failedSnapshotRemovesTheDatabaseAndEverySidecar() throws Exception
    {
        Path source = mTemporaryFolder.resolve("invalid.sqlite");
        Path destination = mTemporaryFolder.resolve("failed-snapshot.sqlite");
        Files.writeString(source, "not a SQLite database");

        assertThrows(SQLException.class, () -> SqliteDatabaseSnapshot.create(source, destination));

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
