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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Out-of-process upgrade helper for a staged copy of an Alpha 5 global database.
 *
 * <p>This helper deliberately supports one migration only: P25 activity schema v19 to v20. The caller owns staging,
 * backup, and final installation of the database. Running this class against the user's live database is not a
 * supported workflow.</p>
 */
public final class P25ActivityV19ToV20Upgrade
{
    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_INPUT = 3;
    public static final int EXIT_UNSUPPORTED_VERSION = 4;
    public static final int EXIT_MIGRATION_FAILED = 5;

    private static final String VERSION_KEY = "p25_activity_schema_version";
    private static final String SOURCE_VERSION = "19";
    private static final String TARGET_VERSION = "20";
    private static final String PORTABLE_PREFERENCES_KEY = "portable_java_preferences_v1";
    private static final Set<String> PORTABLE_DIRECTORY_KEYS = Set.of(
        "directory.application.logs",
        "directory.event.logs",
        "directory.jmbe",
        "directory.recording",
        "directory.screen.capture",
        "directory.streaming",
        "directory.last.recording.browse"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String USAGE = "Usage: P25ActivityV19ToV20Upgrade <staged-database-path> " +
        "[<source-data-root> <target-data-root>]";

    private P25ActivityV19ToV20Upgrade()
    {
    }

    /**
     * Command-line entry point. A zero exit code means that the staged database is valid at v20, either because this
     * invocation migrated it or because it was already current.
     */
    public static void main(String[] args)
    {
        int exitCode = run(args, System.out, System.err);

        if(exitCode != EXIT_SUCCESS)
        {
            System.exit(exitCode);
        }
    }

    /**
     * Runs the command and returns its process exit code. This method is public so launchers and tests can share the
     * same command contract without intercepting {@link System#exit(int)}.
     */
    public static int run(String[] args, PrintStream output, PrintStream error)
    {
        Objects.requireNonNull(output, "output cannot be null");
        Objects.requireNonNull(error, "error cannot be null");

        if(args != null && args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0])))
        {
            output.println(USAGE);
            return EXIT_SUCCESS;
        }

        if(args == null || (args.length != 1 && args.length != 3))
        {
            error.println("ERROR: A staged database path and, optionally, source and target data roots are required.");
            error.println(USAGE);
            return EXIT_USAGE;
        }

        final Path database;
        final DataRootRelocation relocation;

        try
        {
            database = Path.of(args[0]).toAbsolutePath().normalize();
            relocation = args.length == 3 ? new DataRootRelocation(
                Path.of(args[1]).toAbsolutePath().normalize(), Path.of(args[2]).toAbsolutePath().normalize()) : null;
        }
        catch(InvalidPathException | NullPointerException e)
        {
            error.println("ERROR: The staged database path is invalid.");
            return EXIT_INPUT;
        }

        try
        {
            upgrade(database, relocation, output);
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
            error.println("ERROR: Database upgrade failed: " + message(e));
            return EXIT_MIGRATION_FAILED;
        }
    }

    private static void upgrade(Path database, DataRootRelocation relocation, PrintStream output)
        throws IOException, SQLException, UnsupportedSchemaVersionException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Staged database not found: " + database);
        }

        output.println("Checking staged database: " + database);

        try(Connection connection = open(database))
        {
            String version = schemaVersion(connection);
            output.println("Detected P25 activity schema v" + (version == null ? "unknown" : version) + ".");

            if(TARGET_VERSION.equals(version))
            {
                validateCurrentDatabase(connection);
                requireIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
                requireForeignKeysValid(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                int rebased = rebaseInTransaction(connection, relocation);
                finalizeStagedDatabase(connection);
                output.println(rebased == 0 ?
                    "RESULT: Database is already valid at P25 activity schema v20; the staged copy was prepared " +
                        "without schema changes." :
                    "RESULT: Database is already valid at P25 activity schema v20; " + rebased +
                        " portable directory preference(s) were updated for the new data folder.");
                return;
            }

            if(!SOURCE_VERSION.equals(version))
            {
                throw new UnsupportedSchemaVersionException("Expected P25 activity schema v19 or v20, found [" +
                    version + "]. Refusing upgrade.");
            }

            SdrTrunkDatabaseSchema.validate(connection);
            requireIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
            requireForeignKeysValid(connection);
            output.println("Pre-upgrade checks passed. Upgrading staged database from v19 to v20.");

            int rebased = migrateInTransaction(connection, relocation);
            validateCurrentDatabase(connection);
            requireForeignKeysValid(connection);
            requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
            finalizeStagedDatabase(connection);
            output.println("Portable directory preferences updated: " + rebased + ".");
        }

        output.println("RESULT: P25 activity schema upgrade complete: v19 -> v20 foreign-system bands.");
    }

    private static int migrateInTransaction(Connection connection, DataRootRelocation relocation)
        throws IOException, SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            boolean transactionOpen = false;

            try
            {
                statement.execute("BEGIN IMMEDIATE");
                transactionOpen = true;
                P25ActivityLogSchema.createForeignSystemBandTables(statement);
                SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, TARGET_VERSION);
                int rebased = rebasePortableDirectoryPreferences(connection, relocation);
                validateCurrentDatabase(connection);
                requireForeignKeysValid(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                statement.execute("COMMIT");
                transactionOpen = false;
                return rebased;
            }
            catch(IOException | SQLException | RuntimeException e)
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

    private static int rebaseInTransaction(Connection connection, DataRootRelocation relocation)
        throws IOException, SQLException
    {
        if(relocation == null || relocation.source().equals(relocation.target()))
        {
            return 0;
        }

        try(Statement statement = connection.createStatement())
        {
            boolean transactionOpen = false;

            try
            {
                statement.execute("BEGIN IMMEDIATE");
                transactionOpen = true;
                int rebased = rebasePortableDirectoryPreferences(connection, relocation);
                validateCurrentDatabase(connection);
                requireForeignKeysValid(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                statement.execute("COMMIT");
                transactionOpen = false;
                return rebased;
            }
            catch(IOException | SQLException | RuntimeException e)
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

    private static int rebasePortableDirectoryPreferences(Connection connection, DataRootRelocation relocation)
        throws IOException, SQLException
    {
        if(relocation == null || relocation.source().equals(relocation.target()))
        {
            return 0;
        }

        String json;

        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT settings_json FROM application_settings WHERE key=?"))
        {
            statement.setString(1, PORTABLE_PREFERENCES_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return 0;
                }

                json = resultSet.getString(1);
            }
        }

        Map<String,Map<String,String>> values = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});

        if(values == null)
        {
            return 0;
        }

        int rebased = 0;

        for(Map<String,String> node : values.values())
        {
            for(String key : PORTABLE_DIRECTORY_KEYS)
            {
                String value = node.get(key);

                if(value == null || value.isBlank())
                {
                    continue;
                }

                try
                {
                    Path stored = Path.of(value);

                    if(stored.isAbsolute())
                    {
                        Path normalized = stored.normalize();

                        if(normalized.startsWith(relocation.source()))
                        {
                            Path updated = relocation.target().resolve(relocation.source().relativize(normalized))
                                .normalize();
                            node.put(key, updated.toString());
                            rebased++;
                        }
                    }
                }
                catch(InvalidPathException e)
                {
                    // Leave an unrelated or platform-specific preference untouched.
                }
            }
        }

        if(rebased > 0)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE application_settings SET settings_json=?, updated_at_ms=? WHERE key=?
                """))
            {
                statement.setString(1, OBJECT_MAPPER.writeValueAsString(values));
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, PORTABLE_PREFERENCES_KEY);
                statement.executeUpdate();
            }
        }

        return rebased;
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

    private static void validateCurrentDatabase(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseSchema.validate(connection);
        P25ActivityLogSchema.validate(connection);
    }

    private static String schemaVersion(Connection connection) throws SQLException
    {
        try(var statement = connection.prepareStatement("SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, VERSION_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
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

    private static void finalizeStagedDatabase(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
        {
            if(resultSet.next() && resultSet.getInt(1) != 0)
            {
                throw new SQLException("Unable to checkpoint the upgraded staged database.");
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

    private static final class UnsupportedSchemaVersionException extends Exception
    {
        private UnsupportedSchemaVersionException(String message)
        {
            super(message);
        }
    }

    private record DataRootRelocation(Path source, Path target)
    {
    }
}
