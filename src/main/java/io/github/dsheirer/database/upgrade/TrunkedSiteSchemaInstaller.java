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

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Explicit out-of-process helper that installs the current trunked-site schema into a staged copy of an SDRTrunk
 * database when the subsystem is absent, or validates it when already current.
 *
 * <p>The caller owns backup, staging, and atomic replacement. This helper is intentionally not called by application
 * startup and should never be aimed at a live database.</p>
 */
public final class TrunkedSiteSchemaInstaller
{
    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_INPUT = 3;
    public static final int EXIT_UNSUPPORTED_VERSION = 4;
    public static final int EXIT_PREPARATION_FAILED = 5;

    private static final String TARGET_VERSION = Integer.toString(TrunkedSiteSchema.SCHEMA_VERSION);
    private static final List<String> TABLES = List.of(
        "trunked_site_snapshot", "trunked_site_channel_summary", "trunked_site_neighbor_summary");
    private static final String USAGE = "Usage: TrunkedSiteSchemaInstaller <staged-database-path>";

    private TrunkedSiteSchemaInstaller()
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
            error.println("ERROR: A staged database path is required.");
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
            error.println("ERROR: The staged database path is invalid.");
            return EXIT_INPUT;
        }

        try
        {
            installOrValidateStaged(database, output);
            return EXIT_SUCCESS;
        }
        catch(UnsupportedSchemaVersionException e)
        {
            error.println("ERROR: " + e.getMessage());
            return EXIT_UNSUPPORTED_VERSION;
        }
        catch(IOException e)
        {
            error.println("ERROR: " + message(e));
            return EXIT_INPUT;
        }
        catch(SQLException | RuntimeException e)
        {
            error.println("ERROR: Database preparation failed: " + message(e));
            return EXIT_PREPARATION_FAILED;
        }
    }

    static void installOrValidateStaged(Path database, PrintStream output)
        throws IOException, SQLException, UnsupportedSchemaVersionException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Staged database not found: " + database);
        }

        output.println("Checking staged database: " + database);

        try(Connection connection = open(database))
        {
            SdrTrunkDatabaseSchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            requireIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
            requireForeignKeysValid(connection);
            String version = TrunkedSiteSchema.schemaVersion(connection);
            output.println("Detected trunked-site schema v" + (version == null ? "not installed" : version) + ".");

            if(TARGET_VERSION.equals(version))
            {
                TrunkedSiteSchema.validate(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                finalizeStagedDatabase(connection);
                output.println("RESULT: Database is already valid at trunked-site schema v2; no schema changes made.");
                return;
            }

            if(version != null)
            {
                throw new UnsupportedSchemaVersionException(
                    "Expected trunked-site schema to be absent or v2; found [" + version +
                        "]. Refusing staged import.");
            }

            requireSubsystemAbsent(connection);
            output.println("Pre-install checks passed. Installing trunked-site schema v2.");
            installInTransaction(connection);
            TrunkedSiteSchema.validate(connection);
            requireForeignKeysValid(connection);
            requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
            finalizeStagedDatabase(connection);
            output.println("RESULT: Trunked-site schema installation complete: absent -> v2.");
        }
    }

    private static void installInTransaction(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            boolean transactionOpen = false;

            try
            {
                statement.execute("BEGIN IMMEDIATE");
                transactionOpen = true;

                requireSubsystemAbsent(connection);
                TrunkedSiteSchema.create(connection);
                TrunkedSiteSchema.validate(connection);
                requireForeignKeysValid(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                statement.execute("COMMIT");
                transactionOpen = false;
            }
            catch(SQLException | RuntimeException e)
            {
                if(transactionOpen)
                {
                    try
                    {
                        statement.execute("ROLLBACK");
                    }
                    catch(SQLException rollbackFailure)
                    {
                        e.addSuppressed(rollbackFailure);
                    }
                }

                throw e;
            }
        }
    }

    private static void requireSubsystemAbsent(Connection connection) throws SQLException
    {
        for(String table: TABLES)
        {
            try(var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"))
            {
                statement.setString(1, table);

                try(ResultSet resultSet = statement.executeQuery())
                {
                    if(resultSet.next())
                    {
                        throw new SQLException("Trunked-site schema metadata is absent but table [" + table +
                            "] already exists. Refusing to repair an ambiguous partial schema.");
                    }
                }
            }
        }
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }

        return connection;
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

    private static void finalizeStagedDatabase(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
        {
            if(resultSet.next() && resultSet.getInt(1) != 0)
            {
                throw new SQLException("Unable to checkpoint the prepared staged database.");
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode=DELETE"))
        {
            if(!resultSet.next() || !"delete".equalsIgnoreCase(resultSet.getString(1)))
            {
                throw new SQLException("Unable to finalize the staged database journal.");
            }
        }
    }

    private static String message(Exception exception)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    static final class UnsupportedSchemaVersionException extends Exception
    {
        private UnsupportedSchemaVersionException(String message)
        {
            super(message);
        }
    }
}
