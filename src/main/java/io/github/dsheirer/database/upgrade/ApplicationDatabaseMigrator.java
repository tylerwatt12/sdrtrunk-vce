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
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.sqlite.SQLiteConfig;

/**
 * The single application-owned database migration entry point.
 *
 * <p>The application creates a safety backup, makes a staged copy, and launches this class in a child process. This
 * migrator accepts only a completely recognizable schema, rebases portable paths when importing an already-current
 * profile, and validates the staged database before the application can promote it. Release-to-release schema
 * transitions are added here only during preparation of a numbered public release. It must never be aimed directly
 * at a live database.</p>
 */
public final class ApplicationDatabaseMigrator
{
    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_INPUT = 3;
    public static final int EXIT_UNSUPPORTED_VERSION = 4;
    public static final int EXIT_MIGRATION_FAILED = 5;

    private static final String ALIAS_VERSION_KEY = "alias_schema_version";
    private static final String P25_VERSION_KEY = "p25_activity_schema_version";
    private static final String ALIAS_TARGET_VERSION =
        Integer.toString(SdrTrunkDatabaseSchema.ALIAS_SCHEMA_VERSION);
    private static final String P25_TARGET_VERSION = Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION);
    private static final String DMR_TARGET_VERSION = Integer.toString(DmrActivitySchema.SCHEMA_VERSION);
    private static final String TRUNKED_SITE_TARGET_VERSION = Integer.toString(TrunkedSiteSchema.SCHEMA_VERSION);
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
    private static final Set<String> PORTABLE_PATH_KEY_PREFIXES = Set.of(
        "path.jmbe.library.",
        "path.voice.decryption.module."
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern STAGED_DATABASE_FILE = Pattern.compile(
        "^\\." + Pattern.quote(SdrTrunkDatabasePath.DATABASE_FILENAME) + "\\.migration-" + UUID_PATTERN + "$");
    private static final Pattern STAGED_DATA_ROOT =
        Pattern.compile("^\\..+\\.migration-" + UUID_PATTERN + "$");
    private static final String USAGE = "Usage: ApplicationDatabaseMigrator <staged-database-path> " +
        "[<source-data-root> <target-data-root>]";

    private ApplicationDatabaseMigrator()
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

    /**
     * Runs the child-process command and returns its stable process exit code.
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
            error.println("ERROR: The staged database path or data root is invalid.");
            return EXIT_INPUT;
        }

        try
        {
            migrate(database, relocation, output);
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
            error.println("ERROR: Database migration failed: " + message(e));
            return EXIT_MIGRATION_FAILED;
        }
    }

    private static void migrate(Path database, DataRootRelocation relocation, PrintStream output)
        throws IOException, SQLException, UnsupportedSchemaVersionException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Staged database not found: " + database);
        }

        requireApplicationStage(database);
        output.println("Checking staged database: " + database);

        try(Connection connection = open(database))
        {
            SchemaState state = SchemaState.read(connection);
            output.println("Detected " + state.description() + ".");
            SourceKind sourceKind = state.requireSupported();
            preflight(connection, sourceKind);

            boolean relocationRequired = relocation != null && !relocation.source().equals(relocation.target());

            if(sourceKind == SourceKind.CURRENT && !relocationRequired)
            {
                validateCurrentDatabase(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                finalizeStagedDatabase(connection);
                output.println("RESULT: Application database is already current and valid; no schema changes made.");
                return;
            }

            output.println("Pre-migration checks passed. Updating the staged database.");
            MigrationSummary migration = migrateInTransaction(connection, sourceKind, relocation);
            validateCurrentDatabase(connection);
            requireForeignKeysValid(connection);
            requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
            finalizeStagedDatabase(connection);

            if(sourceKind == SourceKind.ALPHA_9)
            {
                output.println(migration.releaseSummary());
            }
            output.println("Portable directory preferences updated: " + migration.rebasedDirectories() + ".");
            output.println("RESULT: Application database migration and validation complete.");
        }
    }

    private static void requireApplicationStage(Path database) throws IOException
    {
        Path fileName = database.getFileName();

        if(fileName != null && STAGED_DATABASE_FILE.matcher(fileName.toString()).matches())
        {
            return;
        }

        Path databaseDirectory = database.getParent();
        Path stageRoot = databaseDirectory == null ? null : databaseDirectory.getParent();

        if(fileName != null && SdrTrunkDatabasePath.DATABASE_FILENAME.equals(fileName.toString()) &&
            databaseDirectory != null && databaseDirectory.getFileName() != null &&
            "database".equals(databaseDirectory.getFileName().toString()) &&
            stageRoot != null && stageRoot.getFileName() != null &&
            STAGED_DATA_ROOT.matcher(stageRoot.getFileName().toString()).matches())
        {
            return;
        }

        throw new IOException("The Application Migrator accepts only an application-created staged database. " +
            "Refusing direct database path: " + database);
    }

    static void validateAcceptedSource(Path database, ApplicationMigrationService.MigrationState state)
        throws IOException, SQLException
    {
        try(Connection connection = openReadOnly(database))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            if(state.current())
            {
                validateCurrentDatabase(connection);
                requireForeignKeysValid(connection);
            }
            else if(state.alpha9())
            {
                Alpha9DatabaseMigration.validateSource(connection);
                requireForeignKeysValid(connection);
            }
            else
            {
                throw new IOException("No bundled migration accepts " + state.description() + ".");
            }

            requireIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
        }
    }

    private static void preflight(Connection connection, SourceKind sourceKind) throws SQLException
    {
        SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);

        if(sourceKind == SourceKind.CURRENT)
        {
            validateCurrentDatabase(connection);
        }
        else
        {
            Alpha9DatabaseMigration.validateSource(connection);
        }

        requireForeignKeysValid(connection);
        requireIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
    }

    private static MigrationSummary migrateInTransaction(Connection connection, SourceKind sourceKind,
                                                          DataRootRelocation relocation)
        throws IOException, SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            boolean transactionOpen = false;

            try
            {
                statement.execute("BEGIN IMMEDIATE");
                transactionOpen = true;

                String releaseSummary = sourceKind == SourceKind.ALPHA_9 ?
                    Alpha9DatabaseMigration.migrate(connection).releaseSummary() : "";
                int rebased = rebasePortableDirectoryPreferences(connection, relocation);

                validateCurrentDatabase(connection);
                requireForeignKeysValid(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                statement.execute("COMMIT");
                transactionOpen = false;
                return new MigrationSummary(rebased, releaseSummary);
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

        Map<String,Map<String,String>> values;

        try
        {
            values = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        }
        catch(IOException e)
        {
            throw new SQLException("Portable directory preferences contain invalid JSON.", e);
        }

        if(values == null)
        {
            return 0;
        }

        int rebased = 0;

        for(Map<String,String> node : values.values())
        {
            if(node == null)
            {
                continue;
            }

            for(Map.Entry<String,String> entry : node.entrySet())
            {
                String key = entry.getKey();
                if(!isPortablePathKey(key))
                {
                    continue;
                }

                String value = entry.getValue();

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
                            entry.setValue(updated.toString());
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
                try
                {
                    statement.setString(1, OBJECT_MAPPER.writeValueAsString(values));
                }
                catch(IOException e)
                {
                    throw new SQLException("Portable directory preferences could not be serialized safely.", e);
                }
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, PORTABLE_PREFERENCES_KEY);
                statement.executeUpdate();
            }
        }

        return rebased;
    }

    private static boolean isPortablePathKey(String key)
    {
        if(key == null)
        {
            return false;
        }
        if(PORTABLE_DIRECTORY_KEYS.contains(key))
        {
            return true;
        }
        return PORTABLE_PATH_KEY_PREFIXES.stream().anyMatch(key::startsWith);
    }

    static void validateCurrentDatabase(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseSchema.validate(connection);
        P25ActivityLogSchema.validate(connection);
        DmrActivitySchema.validate(connection);
        TrunkedSiteSchema.validate(connection);
        SdrTrunkDatabaseStartup.requireCurrentSchemaFingerprint(connection);
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

    private static Connection openReadOnly(Path database) throws SQLException
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize(),
            config.toProperties());
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
                throw new SQLException("Unable to checkpoint the migrated staged database.");
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

    private static String metadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement =
                connection.prepareStatement("SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String message(Exception exception)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record SchemaState(String aliasVersion, String p25Version, String trunkedSiteVersion, String dmrVersion)
    {
        private static SchemaState read(Connection connection) throws SQLException
        {
            return new SchemaState(metadata(connection, ALIAS_VERSION_KEY), metadata(connection, P25_VERSION_KEY),
                metadata(connection, TrunkedSiteSchema.SCHEMA_VERSION_KEY),
                metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }

        private SourceKind requireSupported() throws UnsupportedSchemaVersionException
        {
            if(ALIAS_TARGET_VERSION.equals(aliasVersion) && P25_TARGET_VERSION.equals(p25Version) &&
                TRUNKED_SITE_TARGET_VERSION.equals(trunkedSiteVersion) && DMR_TARGET_VERSION.equals(dmrVersion))
            {
                return SourceKind.CURRENT;
            }

            if(Integer.toString(Alpha9DatabaseMigration.ALIAS_VERSION).equals(aliasVersion) &&
                Integer.toString(Alpha9DatabaseMigration.P25_VERSION).equals(p25Version) &&
                Integer.toString(Alpha9DatabaseMigration.TRUNKED_SITE_VERSION).equals(trunkedSiteVersion) &&
                Integer.toString(Alpha9DatabaseMigration.DMR_VERSION).equals(dmrVersion))
            {
                return SourceKind.ALPHA_9;
            }

            throw new UnsupportedSchemaVersionException(
                "Expected the complete current tuple (Alias v" + ALIAS_TARGET_VERSION +
                    ", P25 activity v" + P25_TARGET_VERSION +
                    ", trunked-site v" + TRUNKED_SITE_TARGET_VERSION + ", DMR activity v" +
                    DMR_TARGET_VERSION + ") or the exact shared v0.6.2 Alpha 8/Alpha 9/Alpha 10 tuple (Alias v" +
                    Alpha9DatabaseMigration.ALIAS_VERSION + ", P25 activity v" +
                    Alpha9DatabaseMigration.P25_VERSION + ", trunked-site v" +
                    Alpha9DatabaseMigration.TRUNKED_SITE_VERSION + ", DMR activity v" +
                    Alpha9DatabaseMigration.DMR_VERSION + "). Found " + description() + ". Refusing migration.");
        }

        private String description()
        {
            return "Alias schema v" + (aliasVersion == null ? "unknown" : aliasVersion) +
                ", P25 activity schema v" + (p25Version == null ? "unknown" : p25Version) +
                ", trunked-site schema " +
                (trunkedSiteVersion == null ? "not installed" : "v" + trunkedSiteVersion) +
                ", and DMR activity schema " +
                (dmrVersion == null ? "not installed" : "v" + dmrVersion);
        }
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

    private record MigrationSummary(int rebasedDirectories, String releaseSummary)
    {
    }

    private enum SourceKind
    {
        ALPHA_9,
        CURRENT
    }

}
