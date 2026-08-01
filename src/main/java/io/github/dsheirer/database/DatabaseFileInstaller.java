/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Creates a SQLite database in a temporary sibling file and atomically installs it after a successful checkpoint.
 */
public final class DatabaseFileInstaller
{
    private DatabaseFileInstaller()
    {
    }

    public static void install(Path databasePath, DatabaseBuilder builder) throws IOException, SQLException
    {
        Path target = databasePath.toAbsolutePath().normalize();

        if(Files.exists(target))
        {
            throw new IOException("Refusing to overwrite existing SQLite database: " + target);
        }

        Path parent = target.getParent();
        if(parent == null)
        {
            throw new IOException("SQLite database has no parent folder: " + target);
        }
        Files.createDirectories(parent);
        Path lockPath = target.resolveSibling("." + target.getFileName() + ".install.lock");

        try(FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock ignored = tryLock(lockChannel, target))
        {
            if(Files.exists(target))
            {
                throw new IOException("Refusing to overwrite existing SQLite database: " + target);
            }

            Path temporary = target.resolveSibling(target.getFileName() + ".creating-" + UUID.randomUUID());
            try
            {
                builder.build(temporary);
                checkpoint(temporary);
                move(temporary, target);
                deleteSidecars(temporary);
            }
            catch(IOException | SQLException | RuntimeException | Error e)
            {
                try
                {
                    delete(temporary);
                }
                catch(IOException cleanupFailure)
                {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
        }
    }

    private static FileLock tryLock(FileChannel channel, Path target) throws IOException
    {
        try
        {
            FileLock lock = channel.tryLock();
            if(lock != null)
            {
                return lock;
            }
        }
        catch(OverlappingFileLockException e)
        {
            throw new IOException("SQLite database setup is already in progress: " + target, e);
        }
        throw new IOException("SQLite database setup is already in progress: " + target);
    }

    private static void checkpoint(Path databasePath) throws SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private static void move(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            Files.move(source, target);
        }
    }

    private static void delete(Path database) throws IOException
    {
        Files.deleteIfExists(database);
        deleteSidecars(database);
    }

    private static void deleteSidecars(Path database) throws IOException
    {
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-journal"));
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
    }

    @FunctionalInterface
    public interface DatabaseBuilder
    {
        void build(Path databasePath) throws IOException, SQLException;
    }
}
