/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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

        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".creating");
        delete(temporary);

        try
        {
            builder.build(temporary);
            checkpoint(temporary);
            move(temporary, target);
            deleteSidecars(temporary);
        }
        catch(IOException | SQLException | RuntimeException e)
        {
            delete(temporary);
            throw e;
        }
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
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
    }

    @FunctionalInterface
    public interface DatabaseBuilder
    {
        void build(Path databasePath) throws IOException, SQLException;
    }
}
