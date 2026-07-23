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

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Explicit command-line migration for an existing portable SQLite database.
 *
 * <p>This tool is never invoked by application startup. The application must be stopped before it runs. It creates a
 * standalone safety backup, migrates a staged copy, validates integrity and foreign keys, and only then atomically
 * replaces the original database. The safety backup is retained after success.</p>
 */
public final class TrunkedSiteV2DatabaseMigration
{
    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_INPUT = 3;
    public static final int EXIT_MIGRATION_FAILED = 5;

    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String USAGE =
        "Usage: TrunkedSiteV2DatabaseMigration <portable-sdrtrunk-sqlite-path>";

    private TrunkedSiteV2DatabaseMigration()
    {
    }

    public static void main(String[] args)
    {
        int exitCode = run(args, System.out, System.err);

        if(exitCode != EXIT_SUCCESS)
        {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintStream output, PrintStream error)
    {
        Objects.requireNonNull(output, "output cannot be null");
        Objects.requireNonNull(error, "error cannot be null");

        if(args != null && args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0])))
        {
            output.println(USAGE);
            return EXIT_SUCCESS;
        }

        if(args == null || args.length != 1)
        {
            error.println("ERROR: A portable SDRTrunk SQLite database path is required.");
            error.println(USAGE);
            return EXIT_USAGE;
        }

        final Path database;

        try
        {
            database = Path.of(args[0]).toAbsolutePath().normalize();
        }
        catch(InvalidPathException | NullPointerException e)
        {
            error.println("ERROR: The SQLite database path is invalid.");
            return EXIT_INPUT;
        }

        try
        {
            MigrationResult result = migrate(database, output);
            output.println("Safety backup retained at: " + result.safetyBackup());
            return EXIT_SUCCESS;
        }
        catch(IOException e)
        {
            error.println("ERROR: " + message(e));
            return EXIT_INPUT;
        }
        catch(SQLException | RuntimeException e)
        {
            error.println("ERROR: Database migration failed: " + message(e));
            return EXIT_MIGRATION_FAILED;
        }
    }

    static MigrationResult migrate(Path database, PrintStream output) throws IOException, SQLException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("SQLite database not found: " + database);
        }

        Path databaseDirectory = database.getParent();

        if(databaseDirectory == null)
        {
            throw new IOException("SQLite database has no parent directory: " + database);
        }

        if(!SdrTrunkDatabasePath.DATABASE_FILENAME.equals(database.getFileName().toString()) ||
            databaseDirectory.getFileName() == null ||
            !SdrTrunkDatabasePath.DATABASE_DIRECTORY.equals(databaseDirectory.getFileName().toString()) ||
            databaseDirectory.getParent() == null)
        {
            throw new IOException("Expected a portable database path ending in database/" +
                SdrTrunkDatabasePath.DATABASE_FILENAME + ".");
        }

        Path portableDataRoot = databaseDirectory.getParent();

        try(PortableDataRootLock ignored = PortableDataRootLock.acquire(portableDataRoot))
        {
            return migrateLocked(database, databaseDirectory, output);
        }
    }

    private static MigrationResult migrateLocked(Path database, Path databaseDirectory, PrintStream output)
        throws IOException, SQLException
    {
        output.println("Checking that the portable database is not in use.");
        prepareForReplacement(database);

        Path backupDirectory = databaseDirectory.resolve("backups");
        Files.createDirectories(backupDirectory);
        String identity = BACKUP_TIME.format(LocalDateTime.now()) + "-" +
            UUID.randomUUID().toString().substring(0, 8);
        Path backup = backupDirectory.resolve("sdrtrunk-before-trunked-site-v2-" + identity + ".sqlite");
        Path staged = databaseDirectory.resolve("." + database.getFileName() + ".trunked-site-v2-" +
            UUID.randomUUID());

        try
        {
            output.println("Creating standalone safety backup.");
            SqliteDatabaseSnapshot.create(database, backup);
            Files.copy(backup, staged, StandardCopyOption.COPY_ATTRIBUTES);

            output.println("Migrating staged database.");
            TrunkedSiteV2Upgrade.upgradeStaged(staged, output);
            validateDatabase(staged);
            requireNoSidecars(staged,
                "The staged migration still has SQLite sidecar files and cannot be installed safely.");

            output.println("Installing validated database.");
            prepareForReplacement(database);
            moveAtomicallyReplacing(staged, database);

            try
            {
                validateDatabase(database);
            }
            catch(SQLException validationFailure)
            {
                try
                {
                    restoreBackup(backup, database);
                }
                catch(IOException restoreFailure)
                {
                    validationFailure.addSuppressed(restoreFailure);
                }

                throw validationFailure;
            }

            output.println("RESULT: Portable database migration to trunked-site schema v2 is complete.");
            return new MigrationResult(database, backup);
        }
        catch(TrunkedSiteV2Upgrade.UnsupportedSchemaVersionException e)
        {
            throw new SQLException(e.getMessage(), e);
        }
        finally
        {
            Files.deleteIfExists(staged);
        }
    }

    private static void prepareForReplacement(Path database) throws IOException, SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);

            try(ResultSet resultSet = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(!resultSet.next() || resultSet.getInt(1) != 0)
                {
                    throw new IOException("The SQLite database is in use. Close sdrtrunk-vce and try again.");
                }
            }
        }

        requireNoSidecars(database, "The SQLite database is still active. Close sdrtrunk-vce and try again.");
    }

    private static void validateDatabase(Path database) throws SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
            SdrTrunkDatabaseSchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            TrunkedSiteSchema.validate(connection);
            requireIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
            requireForeignKeysValid(connection);
        }
    }

    private static void requireIntegrity(Connection connection, String pragma, String label) throws SQLException
    {
        StringJoiner failures = new StringJoiner("; ");
        boolean foundResult = false;

        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(pragma))
        {
            while(resultSet.next())
            {
                foundResult = true;
                String result = resultSet.getString(1);

                if(!"ok".equalsIgnoreCase(result))
                {
                    failures.add(result);
                }
            }
        }

        if(!foundResult || failures.length() > 0)
        {
            throw new SQLException(label + " failed" + (failures.length() > 0 ? ": " + failures : "."));
        }
    }

    private static void requireForeignKeysValid(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check"))
        {
            if(resultSet.next())
            {
                throw new SQLException("Foreign-key check failed for table [" + resultSet.getString("table") +
                    "], row [" + resultSet.getString("rowid") + "], parent [" + resultSet.getString("parent") +
                    "].");
            }
        }
    }

    private static void requireNoSidecars(Path database, String failureMessage) throws IOException
    {
        if(Files.exists(Path.of(database + "-wal")) || Files.exists(Path.of(database + "-shm")) ||
            Files.exists(Path.of(database + "-journal")))
        {
            throw new IOException(failureMessage);
        }
    }

    private static void restoreBackup(Path backup, Path database) throws IOException
    {
        Path restore = database.resolveSibling("." + database.getFileName() + ".restore-" + UUID.randomUUID());

        try
        {
            Files.copy(backup, restore);
            requireNoSidecars(database,
                "The upgraded database is active, so its safety backup cannot be restored automatically.");
            moveAtomicallyReplacing(restore, database);
        }
        finally
        {
            Files.deleteIfExists(restore);
        }
    }

    private static void moveAtomicallyReplacing(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            throw new IOException("This drive does not support the atomic database replacement required for a " +
                "safe migration.", e);
        }
    }

    private static String message(Exception exception)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    record MigrationResult(Path database, Path safetyBackup)
    {
    }
}
