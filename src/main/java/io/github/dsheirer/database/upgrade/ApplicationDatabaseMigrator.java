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
import java.nio.file.LinkOption;
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
 * migrator accepts only an exact format-catalog signature, runs the complete adjacent global-format chain, optionally
 * rebases portable paths for a full-profile import, and validates the staged database before the application can
 * promote it. It must never be aimed directly at a live database.</p>
 */
public final class ApplicationDatabaseMigrator
{
    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_INPUT = 3;
    public static final int EXIT_UNSUPPORTED_VERSION = 4;
    public static final int EXIT_MIGRATION_FAILED = 5;

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
        catch(UnsupportedDatabaseFormatException e)
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
        throws IOException, SQLException, UnsupportedDatabaseFormatException
    {
        if(Files.isSymbolicLink(database))
        {
            throw new IOException("The staged database must not be a symbolic link: " + database);
        }

        if(!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("Staged database not found: " + database);
        }

        requireSingleFilesystemLinkWhenSupported(database);

        requireApplicationStage(database);
        output.println("Checking staged database: " + database);

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat source = requireSupportedFormat(connection);
            output.println("Detected database format " + source.version() + " [" + source.id() + "]: " +
                source.description() + (source.markerPresent() ? "." : " (legacy layout without global marker)."));
            DatabaseMigrationChain.PreflightReport preflight = requireSupportedPreflight(connection, source);
            printPreflight(output, preflight, relocation);

            boolean relocationRequired = relocation != null && !relocation.source().equals(relocation.target());

            if(!source.requiresMigration() && !relocationRequired)
            {
                validateCurrentDatabase(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                finalizeStagedDatabase(connection);
                output.println("RESULT: Application database is already current and valid; no schema changes made.");
                return;
            }

            output.println("Pre-migration checks passed. Updating the staged database.");
            MigrationSummary migration = migrateInTransaction(connection, source, relocation);
            validateCurrentDatabase(connection);
            requireForeignKeysValid(connection);
            requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
            finalizeStagedDatabase(connection);
            printCompletion(output, migration.chainReport());
            output.println("Portable directory preferences updated: " + migration.rebasedDirectories() + ".");
            output.println("RESULT: Application database migration and validation complete.");
        }
    }

    private static void requireApplicationStage(Path database) throws IOException
    {
        Path physicalDatabase = database.toRealPath();
        Path fileName = physicalDatabase.getFileName();

        if(fileName != null && STAGED_DATABASE_FILE.matcher(fileName.toString()).matches())
        {
            return;
        }

        Path databaseDirectory = physicalDatabase.getParent();
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
            "The selected path resolves outside an application stage: " + database);
    }

    private static void requireSingleFilesystemLinkWhenSupported(Path database) throws IOException
    {
        try
        {
            Object value = Files.getAttribute(database, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if(value instanceof Number links && links.longValue() > 1)
            {
                throw new IOException("The staged database has multiple filesystem links and may alias a live " +
                    "database: " + database);
            }
        }
        catch(UnsupportedOperationException | IllegalArgumentException ignored)
        {
            //The Unix link-count view is unavailable on this platform. The application normally creates the stage
            //itself; the physical-path and symbolic-link gates still prevent the portable accidental aliases.
        }
    }

    static DatabaseMigrationChain.PreflightReport validateAcceptedSource(Path database,
                                                                          DatabaseFormatCatalog.DetectedFormat expected)
        throws IOException, SQLException
    {
        try(Connection connection = openReadOnly(database))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            DatabaseMigrationChain.PreflightReport report = DatabaseMigrationChain.validateSource(connection,
                expected);
            requireForeignKeysValid(connection);
            requireIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
            return report;
        }
    }

    private static DatabaseFormatCatalog.DetectedFormat requireSupportedFormat(Connection connection)
        throws SQLException, UnsupportedDatabaseFormatException
    {
        try
        {
            return DatabaseFormatCatalog.inspect(connection);
        }
        catch(DatabaseFormatCatalog.FormatRejectionException e)
        {
            throw new UnsupportedDatabaseFormatException(message(e), e);
        }
    }

    private static DatabaseMigrationChain.PreflightReport requireSupportedPreflight(Connection connection,
            DatabaseFormatCatalog.DetectedFormat source) throws SQLException
    {
        SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
        DatabaseMigrationChain.PreflightReport report = DatabaseMigrationChain.validateSource(connection, source);
        requireForeignKeysValid(connection);
        requireIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
        return report;
    }

    private static MigrationSummary migrateInTransaction(Connection connection,
            DatabaseFormatCatalog.DetectedFormat expectedSource, DataRootRelocation relocation)
        throws IOException, SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            boolean transactionOpen = false;

            try
            {
                statement.execute("BEGIN IMMEDIATE");
                transactionOpen = true;

                DatabaseMigrationChain.MigrationReport chainReport = DatabaseMigrationChain.migrate(connection);
                if(chainReport.source().version() != expectedSource.version() ||
                    !chainReport.source().id().equals(expectedSource.id()) ||
                    chainReport.source().markerPresent() != expectedSource.markerPresent())
                {
                    throw new SQLException("Staged SQLite database changed after preflight; expected format [" +
                        expectedSource.id() + ", marker=" + expectedSource.markerPresent() + "] but migration saw [" +
                        chainReport.source().id() + ", marker=" + chainReport.source().markerPresent() + "]");
                }
                int rebased = rebasePortableDirectoryPreferences(connection, relocation);

                validateCurrentDatabase(connection);
                requireForeignKeysValid(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                statement.execute("COMMIT");
                transactionOpen = false;
                return new MigrationSummary(rebased, chainReport);
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

    private static void printPreflight(PrintStream output, DatabaseMigrationChain.PreflightReport report,
                                       DataRootRelocation relocation)
    {
        String scope;
        if(relocation == null)
        {
            scope = "SQLite database only; external portable-profile artifacts and stored paths will not be copied " +
                "or remapped.";
        }
        else if(relocation.source().equals(relocation.target()))
        {
            scope = "existing portable-profile database; neighboring artifacts remain in place and stored paths " +
                "will not be remapped.";
        }
        else
        {
            scope = "portable-profile import; supported stored paths may be remapped within the copied profile.";
        }
        output.println("Migration scope: " + scope);
        output.println("Migration plan: format " + report.source().version() + " [" + report.source().id() +
            "] -> format " + report.target().version() + " [" + report.target().id() + "] through " +
            report.steps().size() + " step(s).");

        for(DatabaseMigrationChain.StepPreflight step: report.steps())
        {
            output.println("PLAN STEP: " + step.sourceVersion() + " -> " + step.targetVersion() + " [" +
                step.id() + "] " + step.description());
            for(DatabaseMigrationEffect effect: step.effects())
            {
                output.println("  " + formatEffect(effect));
            }
        }
    }

    private static void printCompletion(PrintStream output, DatabaseMigrationChain.MigrationReport report)
    {
        for(DatabaseMigrationChain.StepReport step: report.steps())
        {
            output.println("COMPLETED STEP: " + step.sourceVersion() + " -> " + step.targetVersion() + " [" +
                step.id() + "] " + step.description());
            for(DatabaseMigrationEffect effect: step.effects())
            {
                output.println("  " + formatEffect(effect));
            }
        }

        output.println(report.releaseSummary());
    }

    private static String formatEffect(DatabaseMigrationEffect effect)
    {
        String count = effect.affectedRows() >= 0 ? effect.affectedRows() + " row(s)" :
            "count determined during migration";
        return effect.kind() + " " + effect.subject() + ": " + count + " - " + effect.detail();
    }

    static void validateCurrentDatabase(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseSchema.validate(connection);
        P25ActivityLogSchema.validate(connection);
        DmrActivitySchema.validate(connection);
        TrunkedSiteSchema.validate(connection);
        DatabaseFormatCatalog.requireCurrent(connection);
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

    private static String message(Exception exception)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static final class UnsupportedDatabaseFormatException extends Exception
    {
        private UnsupportedDatabaseFormatException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private record DataRootRelocation(Path source, Path target)
    {
    }

    private record MigrationSummary(int rebasedDirectories, DatabaseMigrationChain.MigrationReport chainReport)
    {
    }

}
