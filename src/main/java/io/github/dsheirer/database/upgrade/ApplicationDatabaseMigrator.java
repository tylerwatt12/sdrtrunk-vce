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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.configuration.ConfigurationSnapshotValidator;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

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
    private static final String NOW_PLAYING_PREFERENCES_NODE =
        "user/io/github/dsheirer/preference/nowplaying";
    private static final int MAXIMUM_PORTABLE_PREFERENCES_BYTES = 4_194_304;
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
    private static final Set<String> RECEIVER_PREFERENCE_KEYS = Set.of(
        "site.settings.revision",
        "receiver.settings.revision",
        "retain.idle.call.details",
        "clear.voice.decode.quality.on.call.end",
        "traffic.grant.age.out.milliseconds"
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
        //The helper output is shown and copyable in migration-completion dialogs. Keep private staging paths out of it.
        output.println("Checking staged database.");

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat source = requireSupportedFormat(connection);
            output.println("Detected database format " + source.version() + " [" + source.id() + "]: " +
                source.description() + (source.markerPresent() ? "." : " (legacy layout without global marker)."));
            DatabaseMigrationChain.PreflightReport preflight = requireSupportedPreflight(connection, source);
            printPreflight(output, preflight, relocation);

            boolean relocationRequired = relocation != null && !relocation.source().equals(relocation.target());
            if(!preflight.requiresMigration() && !relocationRequired)
            {
                validateCurrentDatabase(connection);
                requireForeignKeysValid(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                finalizeStagedDatabase(connection);
                validateConfiguration(database);
                output.println("Portable directory preferences updated: 0.");
                output.println("Portable preference components repaired or reset: 0.");
                output.println("Database is already at current format " + DatabaseFormatCatalog.CURRENT_VERSION +
                    ".");
                output.println("RESULT: Application database is already current and valid; no changes made.");
                return;
            }

            output.println("Pre-migration checks passed. Updating the staged database.");
            MigrationSummary migration = migrateInTransaction(connection, source, relocation, database);
            validateCurrentDatabase(connection);
            requireForeignKeysValid(connection);
            requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
            finalizeStagedDatabase(connection);
            validateConfiguration(database);

            printCompletion(output, migration);
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

    private static DatabaseFormatCatalog.DetectedFormat requireSupportedFormat(Connection connection)
        throws SQLException, UnsupportedDatabaseFormatException
    {
        try
        {
            return DatabaseFormatCatalog.inspectForMigration(connection);
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
        DatabaseMigrationChain.PreflightReport report =
            DatabaseMigrationChain.planForApplicationMigration(connection, source);
        requireSourceIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
        return report;
    }

    private static MigrationSummary migrateInTransaction(Connection connection,
            DatabaseFormatCatalog.DetectedFormat expectedSource, DataRootRelocation relocation, Path database)
        throws IOException, SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            boolean transactionOpen = false;
            boolean foreignKeysRelaxed = expectedSource.version() < DatabaseFormatCatalog.CURRENT_VERSION;
            boolean checkConstraintsRelaxed = foreignKeysRelaxed;

            if(foreignKeysRelaxed)
            {
                //Historical databases can contain orphaned relationships and row-local CHECK damage that later
                //steps rebuild, default, or drop. Keep those constraints from aborting an intermediate bulk copy;
                //the exact current target is checked in full before COMMIT. Foreign keys must change before BEGIN.
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.execute("PRAGMA ignore_check_constraints=ON");
            }

            try
            {
                statement.execute("BEGIN IMMEDIATE");
                transactionOpen = true;

                PortablePreferenceResult portablePreferences = new PortablePreferenceResult(0, 0);
                CurrentDatabaseAdministrativeRepair.Inspection administrativeRepair =
                    CurrentDatabaseAdministrativeRepair.Inspection.none();
                CurrentDatabaseBestEffortRepair.Inspection currentRepair =
                    CurrentDatabaseBestEffortRepair.Inspection.none();
                CurrentDatabaseDerivedStateRepair.Inspection derivedStateRepair =
                    CurrentDatabaseDerivedStateRepair.Inspection.none();
                SqliteIdentityRepair.Inspection identityRepair = SqliteIdentityRepair.Inspection.none();
                if(expectedSource.version() < DatabaseFormatCatalog.CURRENT_VERSION)
                {
                    //Historical steps can seed factory configuration. Normalize exhausted allocator state and
                    //isolate JSON-unsafe configuration identities before any such insert occurs.
                    identityRepair = SqliteIdentityRepair.repair(connection);
                }
                if(expectedSource.version() == DatabaseFormatCatalog.CURRENT_VERSION)
                {
                    //A current-format source has the final schema already. Repair its bounded components before the
                    //chain performs strict current-format validation or adopts a missing global marker.
                    portablePreferences = updatePortableDirectoryPreferences(connection, relocation);
                    administrativeRepair = CurrentDatabaseAdministrativeRepair.repair(connection);
                    derivedStateRepair = CurrentDatabaseDerivedStateRepair.repair(connection);
                    currentRepair = CurrentDatabaseBestEffortRepair.repair(connection);
                    identityRepair = SqliteIdentityRepair.repair(connection);
                }

                DatabaseMigrationChain.MigrationReport chainReport = DatabaseMigrationChain.migrate(connection);
                if(checkConstraintsRelaxed)
                {
                    statement.execute("PRAGMA ignore_check_constraints=OFF");
                    checkConstraintsRelaxed = false;
                }
                if(chainReport.source().version() != expectedSource.version() ||
                    !chainReport.source().id().equals(expectedSource.id()) ||
                    chainReport.source().markerPresent() != expectedSource.markerPresent())
                {
                    throw new SQLException("Staged SQLite database changed after preflight; expected format [" +
                        expectedSource.id() + ", marker=" + expectedSource.markerPresent() + "] but migration saw [" +
                        chainReport.source().id() + ", marker=" + chainReport.source().markerPresent() + "]");
                }
                if(expectedSource.version() < DatabaseFormatCatalog.CURRENT_VERSION)
                {
                    portablePreferences = updatePortableDirectoryPreferences(connection, relocation);
                }

                validateCurrentDatabase(connection);
                requireForeignKeysValid(connection);
                requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                validateConfiguration(connection, database);
                statement.execute("COMMIT");
                transactionOpen = false;
                MigrationSummary summary = new MigrationSummary(portablePreferences.rebasedDirectories(),
                    portablePreferences.resetEntries(), administrativeRepair, currentRepair, derivedStateRepair,
                    identityRepair, chainReport);
                if(foreignKeysRelaxed)
                {
                    statement.execute("PRAGMA foreign_keys=ON");
                }
                return summary;
            }
            catch(IOException | SQLException | RuntimeException | Error e)
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

                if(checkConstraintsRelaxed)
                {
                    try
                    {
                        statement.execute("PRAGMA ignore_check_constraints=OFF");
                    }
                    catch(SQLException checkConstraintRestoreFailure)
                    {
                        e.addSuppressed(checkConstraintRestoreFailure);
                    }
                }

                if(foreignKeysRelaxed)
                {
                    try
                    {
                        statement.execute("PRAGMA foreign_keys=ON");
                    }
                    catch(SQLException foreignKeyRestoreFailure)
                    {
                        e.addSuppressed(foreignKeyRestoreFailure);
                    }
                }

                throw e;
            }
        }
    }

    private static PortablePreferenceResult updatePortableDirectoryPreferences(Connection connection,
                                                                                DataRootRelocation relocation)
        throws IOException, SQLException
    {
        String json;

        boolean sourceRowStorageValid;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(settings_json)='text'
                              AND length(CAST(settings_json AS BLOB)) <= 4194304
                        THEN settings_json END AS settings_json,
                   updated_at_ms, typeof(settings_json), typeof(updated_at_ms),
                   length(CAST(settings_json AS BLOB)) AS settings_json_bytes
            FROM application_settings WHERE key=?
            """))
        {
            statement.setString(1, PORTABLE_PREFERENCES_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return new PortablePreferenceResult(0, 0);
                }

                json = resultSet.getString(1);
                sourceRowStorageValid = "text".equals(resultSet.getString(3)) &&
                    "integer".equals(resultSet.getString(4)) && resultSet.getLong(2) > 0 &&
                    resultSet.getLong("settings_json_bytes") <= MAXIMUM_PORTABLE_PREFERENCES_BYTES;
            }
        }

        Map<String,Map<String,String>> values = new LinkedHashMap<>();
        int reset = 0;
        boolean sourceDocumentValid = sourceRowStorageValid;
        try
        {
            Format5WebStateValidator.validateCurrentPortablePreferences(json);
        }
        catch(SQLException exception)
        {
            sourceDocumentValid = false;
        }
        try
        {
            if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PORTABLE_PREFERENCES_BYTES)
            {
                throw new IOException("unusable portable preferences");
            }
            JsonNode parsed = OBJECT_MAPPER.readTree(json);
            if(!(parsed instanceof ObjectNode root))
            {
                throw new IOException("portable preferences are not an object");
            }
            var nodes = root.fields();
            while(nodes.hasNext())
            {
                Map.Entry<String,JsonNode> node = nodes.next();
                if(!(node.getValue() instanceof ObjectNode storedValues))
                {
                    reset++;
                    continue;
                }
                Map<String,String> retained = new LinkedHashMap<>();
                var entries = storedValues.fields();
                while(entries.hasNext())
                {
                    Map.Entry<String,JsonNode> entry = entries.next();
                    if(Format6To7DatabaseMigration.RETIRED_WEB_AUDIO_KEYS.contains(entry.getKey()))
                    {
                        reset++;
                    }
                    else if(entry.getValue().isTextual())
                    {
                        retained.put(entry.getKey(), entry.getValue().textValue());
                    }
                    else
                    {
                        reset++;
                    }
                }
                values.put(node.getKey(), retained);
            }
        }
        catch(IOException | RuntimeException exception)
        {
            values.clear();
            reset = 1;
        }

        String targetJson;
        try
        {
            reset = Math.addExact(reset, sanitizeReceiverPreferences(values));
            targetJson = OBJECT_MAPPER.writeValueAsString(values);
            try
            {
                Format5WebStateValidator.validateCurrentPortablePreferences(targetJson);
            }
            catch(SQLException invalidReceiverSettings)
            {
                Map<String,String> nowPlaying = values.get(NOW_PLAYING_PREFERENCES_NODE);
                if(nowPlaying != null)
                {
                    for(String key: RECEIVER_PREFERENCE_KEYS)
                    {
                        if(nowPlaying.remove(key) != null)
                        {
                            reset++;
                        }
                    }
                }
                targetJson = OBJECT_MAPPER.writeValueAsString(values);
                try
                {
                    Format5WebStateValidator.validateCurrentPortablePreferences(targetJson);
                }
                catch(SQLException stillInvalid)
                {
                    values.clear();
                    reset++;
                    targetJson = "{}";
                    Format5WebStateValidator.validateCurrentPortablePreferences(targetJson);
                }
            }
        }
        catch(IOException exception)
        {
            throw new SQLException("Portable preferences could not be serialized safely.", exception);
        }
        if(!sourceDocumentValid && reset == 0)
        {
            //For example, strict duplicate detection can require a canonical rewrite even when every surviving
            //node and value is independently usable.
            reset = 1;
        }

        int rebased = 0;
        boolean relocate = relocation != null && !relocation.source().equals(relocation.target());
        if(relocate)
        {
            for(Map<String,String> node : values.values())
            {
                var entries = node.entrySet().iterator();
                while(entries.hasNext())
                {
                    Map.Entry<String,String> entry = entries.next();
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
                                Path updated = relocation.target().resolve(
                                    relocation.source().relativize(normalized)).normalize();
                                entry.setValue(updated.toString());
                                String candidateJson;
                                try
                                {
                                    candidateJson = OBJECT_MAPPER.writeValueAsString(values);
                                }
                                catch(IOException exception)
                                {
                                    throw new SQLException("Portable preferences could not be serialized safely.",
                                        exception);
                                }
                                try
                                {
                                    Format5WebStateValidator.validateCurrentPortablePreferences(candidateJson);
                                    targetJson = candidateJson;
                                    rebased++;
                                }
                                catch(SQLException invalidRebase)
                                {
                                    //A much longer target root can push an otherwise valid document past its
                                    //storage bound. Drop only that stale path so every other preference survives.
                                    entries.remove();
                                    reset++;
                                    try
                                    {
                                        targetJson = OBJECT_MAPPER.writeValueAsString(values);
                                        Format5WebStateValidator.validateCurrentPortablePreferences(targetJson);
                                    }
                                    catch(IOException exception)
                                    {
                                        throw new SQLException(
                                            "Portable preferences could not be serialized safely.", exception);
                                    }
                                }
                            }
                        }
                    }
                    catch(InvalidPathException e)
                    {
                        // Leave an unrelated or platform-specific preference untouched.
                    }
                }
            }
        }

        if(rebased > 0 || reset > 0)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE application_settings SET settings_json=?, updated_at_ms=? WHERE key=?
            """))
            {
                statement.setString(1, targetJson);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, PORTABLE_PREFERENCES_KEY);
                statement.executeUpdate();
            }
        }

        return new PortablePreferenceResult(rebased, reset);
    }

    /** Preserves independent receiver preferences and defaults only malformed keys in that small component. */
    private static int sanitizeReceiverPreferences(Map<String,Map<String,String>> values)
    {
        Map<String,String> nowPlaying = values.get(NOW_PLAYING_PREFERENCES_NODE);
        if(nowPlaying == null)
        {
            return 0;
        }

        int reset = 0;
        if(nowPlaying.remove("site.settings.revision") != null)
        {
            reset++;
        }
        String revision = nowPlaying.get("receiver.settings.revision");
        if(revision != null && !validCanonicalLong(revision, 1, Long.MAX_VALUE - 2))
        {
            nowPlaying.remove("receiver.settings.revision");
            revision = null;
            reset++;
        }
        //These shared presentation settings moved into each web user's preference document and are no longer
        //receiver-wide state. Remove them even when their old values are well-formed.
        for(String key: List.of("retain.idle.call.details", "clear.voice.decode.quality.on.call.end"))
        {
            if(nowPlaying.remove(key) != null)
            {
                reset++;
            }
        }
        String ageOut = nowPlaying.get("traffic.grant.age.out.milliseconds");
        if(ageOut != null && !validCanonicalLong(ageOut, 100, 15_000))
        {
            nowPlaying.remove("traffic.grant.age.out.milliseconds");
            reset++;
        }
        boolean hasReceiverValue = nowPlaying.containsKey("traffic.grant.age.out.milliseconds");
        if(hasReceiverValue && revision == null)
        {
            nowPlaying.put("receiver.settings.revision", "1");
            reset++;
        }
        return reset;
    }

    private static boolean validCanonicalLong(String text, long minimum, long maximum)
    {
        try
        {
            long value = Long.parseLong(text);
            return value >= minimum && value <= maximum && Long.toString(value).equals(text);
        }
        catch(NumberFormatException ignored)
        {
            return false;
        }
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
    }

    private static void printCompletion(PrintStream output, MigrationSummary migration)
    {
        DatabaseMigrationChain.MigrationReport report = migration.chainReport();
        boolean portableRepairRan = report.source().version() < DatabaseFormatCatalog.CURRENT_VERSION ||
            migration.resetPortablePreferenceEntries() > 0 || migration.rebasedDirectories() > 0;
        long recoveryChanges = recoveryChangeCount(migration, portableRepairRan);
        if(recoveryChanges > 0)
        {
            //The itemized effects can overlap and some count JSON preference components instead of table rows.
            //Do not present their sum as a row count.
            output.println("OUTCOME: Migration completed with itemized repairs, resets, or skipped items. " +
                "Usable independent data was preserved.");
        }
        else
        {
            output.println("OUTCOME: Migration completed without row-level repairs, resets, or skipped items.");
        }
        if(report.source().version() == DatabaseFormatCatalog.CURRENT_VERSION)
        {
            printPortableCompletion(output, migration, portableRepairRan);
            printAdministrativeCompletion(output, migration.administrativeRepair());
            printDerivedStateCompletion(output, migration.derivedStateRepair());
            printCurrentConfigurationCompletion(output, migration.currentRepair());
            printIdentityCompletion(output, migration.identityRepair(), report.source().version());
            printChainCompletion(output, report);
        }
        else
        {
            printIdentityCompletion(output, migration.identityRepair(), report.source().version());
            printChainCompletion(output, report);
            printPortableCompletion(output, migration, portableRepairRan);
        }
        boolean sameFormatWork = report.steps().isEmpty() && (migration.currentRepair().requiresRepair() ||
            migration.administrativeRepair().requiresRepair() || migration.derivedStateRepair().requiresRepair() ||
            migration.identityRepair().requiresRepair() ||
            migration.rebasedDirectories() > 0 ||
            migration.resetPortablePreferenceEntries() > 0);
        int supplementalSteps = (migration.currentRepair().requiresRepair() ? 1 : 0) +
            (migration.administrativeRepair().requiresRepair() ? 1 : 0) +
            (migration.derivedStateRepair().requiresRepair() ? 1 : 0) +
            (migration.identityRepair().requiresRepair() ? 1 : 0) + (portableRepairRan ? 1 : 0);
        if(sameFormatWork)
        {
            output.println("Database format was already current at " + report.target().version() +
                "; the itemized same-format updates above were applied.");
        }
        else if(supplementalSteps > 0)
        {
            int completedSteps = report.steps().size() + supplementalSteps;
            output.println("Migrated database format " + report.source().version() + " [" + report.source().id() +
                "] to " + report.target().version() + " [" + report.target().id() + "] through " + completedSteps +
                " step(s). See the itemized completion counts above.");
        }
        else
        {
            output.println(report.releaseSummary());
        }
    }

    private static void printChainCompletion(PrintStream output, DatabaseMigrationChain.MigrationReport report)
    {
        for(DatabaseMigrationChain.StepReport step: report.steps())
        {
            output.println("COMPLETED STEP: " + step.sourceVersion() + " -> " + step.targetVersion() + " [" +
                step.id() + "] " + step.description());
            for(DatabaseMigrationEffect effect: step.effects())
            {
                printNonzeroEffect(output, effect);
            }
        }
    }

    private static void printPortableCompletion(PrintStream output, MigrationSummary migration,
                                                boolean portableRepairRan)
    {
        if(portableRepairRan)
        {
            output.println("COMPLETED STEP: 15 -> 15 [repair-portable-preferences] " +
                "Validate and independently repair portable preference components");
            output.println("  TRANSFORM portable directory preferences: " + migration.rebasedDirectories() +
                " preference component(s) - Updated only stored paths that pointed inside the copied portable profile");
            if(migration.resetPortablePreferenceEntries() > 0)
            {
                output.println("  RESET unusable portable preference components: " +
                    migration.resetPortablePreferenceEntries() + " preference component(s) - Preserved usable " +
                    "entries and removed or reset only malformed, obsolete, or invalid components");
            }
        }
    }

    private static void printAdministrativeCompletion(PrintStream output,
            CurrentDatabaseAdministrativeRepair.Inspection repair)
    {
        if(repair.requiresRepair())
        {
            output.println("COMPLETED STEP: 15 -> 15 [" + CurrentDatabaseAdministrativeRepair.STEP_ID +
                "] Repair recoverable current-format administrative components independently");
            for(DatabaseMigrationEffect effect: CurrentDatabaseAdministrativeRepair.effects(repair))
            {
                printNonzeroEffect(output, effect);
            }
        }
    }

    private static void printCurrentConfigurationCompletion(PrintStream output,
            CurrentDatabaseBestEffortRepair.Inspection repair)
    {
        if(repair.requiresRepair())
        {
            output.println("COMPLETED STEP: 15 -> 15 [" + CurrentDatabaseBestEffortRepair.STEP_ID +
                "] Repair unusable current-format configuration items independently");
            for(DatabaseMigrationEffect effect: CurrentDatabaseBestEffortRepair.effects(repair))
            {
                printNonzeroEffect(output, effect);
            }
        }
    }

    private static void printDerivedStateCompletion(PrintStream output,
            CurrentDatabaseDerivedStateRepair.Inspection repair)
    {
        if(repair.requiresRepair())
        {
            output.println("COMPLETED STEP: 15 -> 15 [" + CurrentDatabaseDerivedStateRepair.STEP_ID +
                "] Reset damaged reproducible receiver activity and statistics state");
            for(DatabaseMigrationEffect effect: CurrentDatabaseDerivedStateRepair.effects(repair))
            {
                printNonzeroEffect(output, effect);
            }
        }
    }

    private static void printIdentityCompletion(PrintStream output, SqliteIdentityRepair.Inspection repair,
                                                int version)
    {
        if(repair.requiresRepair())
        {
            output.println("COMPLETED STEP: " + version + " -> " + version + " [" +
                SqliteIdentityRepair.STEP_ID + "] Normalize recoverable SQLite configuration identities and " +
                "allocator state");
            for(DatabaseMigrationEffect effect: SqliteIdentityRepair.effects(repair))
            {
                printNonzeroEffect(output, effect);
            }
        }
    }

    private static String formatEffect(DatabaseMigrationEffect effect)
    {
        String count = effect.affectedRows() >= 0 ? effect.affectedRows() + " row(s)" :
            "count determined during migration";
        return effect.kind() + " " + effect.subject() + ": " + count + " - " + effect.detail();
    }

    private static void printNonzeroEffect(PrintStream output, DatabaseMigrationEffect effect)
    {
        if(effect.affectedRows() != 0)
        {
            output.println("  " + formatEffect(effect));
        }
    }

    private static long recoveryChangeCount(MigrationSummary migration, boolean portableRepairRan)
    {
        long count = portableRepairRan ? migration.resetPortablePreferenceEntries() : 0;
        count += recoveryEffectCount(CurrentDatabaseAdministrativeRepair.effects(migration.administrativeRepair()));
        count += recoveryEffectCount(CurrentDatabaseDerivedStateRepair.effects(migration.derivedStateRepair()));
        count += recoveryEffectCount(CurrentDatabaseBestEffortRepair.effects(migration.currentRepair()));
        count += migration.currentRepair().repairedBroadcastRoutes();
        count += migration.currentRepair().remappedScanListMemberships();
        count += recoveryEffectCount(SqliteIdentityRepair.effects(migration.identityRepair()));
        for(DatabaseMigrationChain.StepReport step: migration.chainReport().steps())
        {
            count += recoveryEffectCount(step.effects());
        }
        return count;
    }

    private static long recoveryEffectCount(List<DatabaseMigrationEffect> effects)
    {
        return effects.stream()
            .filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DEFAULT ||
                effect.kind() == DatabaseMigrationEffect.Kind.RESET ||
                effect.kind() == DatabaseMigrationEffect.Kind.DROP)
            .filter(effect -> effect.affectedRows() > 0)
            .mapToLong(DatabaseMigrationEffect::affectedRows)
            .sum();
    }

    static void validateCurrentDatabase(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseSchema.validate(connection);
        ReceiverActivitySchema.validate(connection);
        DmrActivitySchema.validate(connection);
        TrunkedSiteSchema.validate(connection);
        DatabaseFormatCatalog.requireCurrent(connection);
    }

    private static void validateConfiguration(Path database) throws IOException, SQLException
    {
        ConfigurationSnapshotValidator.validateForStartup(new ConfigurationRepository(database).load());
    }

    private static void validateConfiguration(Connection connection, Path database) throws IOException, SQLException
    {
        ConfigurationSnapshotValidator.validateForStartup(new ConfigurationRepository(database).load(connection));
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

    /**
     * Checks physical SQLite structure without allowing a recoverable row CHECK violation to block staged repair.
     * Every final migrated target uses the normal strict integrity check.
     */
    private static void requireSourceIntegrity(Connection connection, String pragma, String label) throws SQLException
    {
        SQLException failure = null;
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            try
            {
                requireIntegrity(connection, pragma, label);
            }
            catch(SQLException exception)
            {
                failure = exception;
                throw exception;
            }
            finally
            {
                try
                {
                    statement.execute("PRAGMA ignore_check_constraints=OFF");
                }
                catch(SQLException restoreFailure)
                {
                    if(failure != null)
                    {
                        failure.addSuppressed(restoreFailure);
                    }
                    else
                    {
                        throw restoreFailure;
                    }
                }
            }
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

    private record PortablePreferenceResult(int rebasedDirectories, int resetEntries)
    {
    }

    private record MigrationSummary(int rebasedDirectories, int resetPortablePreferenceEntries,
                                    CurrentDatabaseAdministrativeRepair.Inspection administrativeRepair,
                                    CurrentDatabaseBestEffortRepair.Inspection currentRepair,
                                    CurrentDatabaseDerivedStateRepair.Inspection derivedStateRepair,
                                    SqliteIdentityRepair.Inspection identityRepair,
                                    DatabaseMigrationChain.MigrationReport chainReport)
    {
    }

}
