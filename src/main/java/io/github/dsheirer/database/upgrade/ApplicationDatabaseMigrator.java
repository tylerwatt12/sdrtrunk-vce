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
import java.io.ByteArrayOutputStream;
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
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The single application-owned database migration entry point.
 *
 * <p>Owned-profile upgrades run one in-place transaction with an optional SQLite backup. External imports run the
 * same adjacent chain on a private staged copy in a child process. Both paths admit exact catalog signatures.</p>
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
        "directory.recording.managed",
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
        "[<source-data-root> <target-data-root> [--complete-profile-copy|--owned-complete-profile-copy]] " +
        "[--configuration-only]";

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

        MigrationContent content = MigrationContent.FULL;
        if(args != null)
        {
            List<String> arguments = new ArrayList<>(java.util.Arrays.asList(args));
            if(arguments.remove("--configuration-only")) content = MigrationContent.CONFIGURATION_ONLY;
            args = arguments.toArray(String[]::new);
        }

        if(args == null || (args.length != 1 && args.length != 3 &&
            !(args.length == 4 && ("--complete-profile-copy".equals(args[3]) ||
                "--owned-complete-profile-copy".equals(args[3])))))
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
            relocation = args.length >= 3 ? new DataRootRelocation(
                Path.of(args[1]).toAbsolutePath().normalize(), Path.of(args[2]).toAbsolutePath().normalize(),
                args.length == 4) : null;
        }
        catch(InvalidPathException | NullPointerException e)
        {
            error.println("ERROR: The staged database path or data root is invalid.");
            return EXIT_INPUT;
        }

        try
        {
            if(args.length == 4 && "--owned-complete-profile-copy".equals(args[3]))
            {
                migrateOwnedCompleteProfileCopy(database, relocation, output, content);
            }
            else
            {
                migrate(database, relocation, output, content);
            }
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

    /**
     * Updates an inactive application database under the caller's portable-profile lock. The optional backup runs
     * after the write lock is acquired and before any conversion. Full history/integrity scans belong to explicit
     * maintenance; bounded configuration repair and exact final admission complete before commit.
     */
    static InPlaceMigrationResult migrateInPlace(Path database, Consumer<String> progress)
        throws IOException, SQLException
    {
        return migrateInPlace(database, progress, null);
    }

    @FunctionalInterface
    interface BackupAction
    {
        void run() throws IOException, SQLException;
    }

    static InPlaceMigrationResult migrateInPlace(Path database, Consumer<String> progress, BackupAction backup)
        throws IOException, SQLException
    {
        return migrateInPlace(database, progress, backup, Map.of());
    }

    static InPlaceMigrationResult migrateInPlace(Path database, Consumer<String> progress, BackupAction backup,
                                                Map<String,String> recoveryDefaults)
        throws IOException, SQLException
    {
        return migrateInPlace(database, progress, backup, recoveryDefaults, MigrationContent.FULL);
    }

    static InPlaceMigrationResult migrateInPlace(Path database, Consumer<String> progress, BackupAction backup,
                                                Map<String,String> recoveryDefaults, MigrationContent content)
        throws IOException, SQLException
    {
        Objects.requireNonNull(content, "Migration contents cannot be null");
        Path normalized = database.toAbsolutePath().normalize();
        recoverSplitTransition(normalized);
        Consumer<String> listener = progress == null ? ignored -> { } : progress;
        if(Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("Application database not found or is a symbolic link: " + normalized);
        }
        requireSingleFilesystemLinkWhenSupported(normalized);

        listener.accept("Opening the inactive database");
        Connection connection = open(normalized);
        Throwable primaryFailure = null;
        try(ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8))
        {
            MigrationTimings timings = new MigrationTimings(message -> {
                output.println(message);
                reportCleanupWarning(listener, message);
            });
            DatabaseFormatCatalog.DetectedFormat source;
            DatabaseMigrationChain.PreflightReport plan;
            LegacyReceiverSettingsMigration.Snapshot legacy;
            try(var phase = timings.phase("Inspect source schema and bounded configuration"))
            {
                SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
                source = requireSupportedFormat(connection);
                if(content == MigrationContent.CONFIGURATION_ONLY && source.version() >= DatabaseFormatCatalog.CURRENT_VERSION)
                    throw new IllegalArgumentException("Configuration-only in-place conversion requires an older database. " +
                        "Use the VCE upgrade settings-extraction workflow for a current profile.");
                if(content == MigrationContent.CONFIGURATION_ONLY && source.version() >= 63)
                    throw new IllegalArgumentException("Configuration-only in-place conversion of a paired profile " +
                        "requires the VCE upgrade settings-extraction workflow.");
                if(source.version() >= 63)
                    io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePairIdentity(normalized);
                plan = DatabaseMigrationChain.planInPlace(connection, source).withContent(content);
                Set<String> legacyKeys = source.version() <= 52 ? LegacyReceiverSettingsMigration.missingKeys(connection) :
                    recoveryDefaults.keySet();
                legacy = LegacyReceiverSettingsMigration.capture(normalized.getParent().getParent(), legacyKeys);
                phase.complete();
            }
            catch(UnsupportedDatabaseFormatException exception)
            {
                throw new SQLException(exception.getMessage(), exception);
            }
            if(!plan.requiresMigration() && recoveryDefaults.isEmpty())
            {
                try(var phase = timings.phase("Validate already-current configuration"))
                {
                    DatabaseFormatCatalog.requireCurrent(connection);
                    validateConfiguration(connection, normalized);
                    phase.complete();
                }
                output.println("RESULT: Application database is already current and valid; no changes made.");
                return new InPlaceMigrationResult(plan, bytes.toString(StandardCharsets.UTF_8));
            }
            printPreflight(output, plan, null);
            if(!recoveryDefaults.isEmpty())
                output.println("Explicitly selected legacy receiver settings recovery: " + recoveryDefaults.size() + " setting(s).");
            MigrationSummary migration = migrateInTransaction(connection, source, null, normalized, true, listener, backup,
                legacy, recoveryDefaults, content, timings);
            try(var phase = timings.phase("Finalize database pair activation and startup validation"))
            {
                connection.close();
                recoverSplitTransition(normalized);
                SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(normalized);
                phase.complete();
            }
            printCompletion(output, migration);
            output.println("RESULT: In-place application database update complete.");
            return new InPlaceMigrationResult(plan, bytes.toString(StandardCharsets.UTF_8));
        }
        catch(IOException | SQLException | RuntimeException | Error failure)
        {
            primaryFailure = failure;
            throw failure;
        }
        finally
        {
            try
            {
                connection.close();
            }
            catch(SQLException | RuntimeException closeFailure)
            {
                if(primaryFailure != null)
                {
                    primaryFailure.addSuppressed(closeFailure);
                }
                else
                {
                    //All validation and COMMIT already succeeded. Do not misreport a committed live update as a
                    //retryable failure solely because JDBC cleanup reported an error.
                    reportCleanupWarning(listener, "Database update committed; connection cleanup reported a warning");
                }
            }
        }
    }

    /** In-process transfer entry point: the same live OS lock must cover the entire database activation. */
    static String migrateTransferred(Path database, Path source, Path target,
                                     io.github.dsheirer.portable.PortableDataRootLock lock) throws IOException, SQLException
    {
        if(lock == null || !lock.getDataRoot().equals(target))
            throw new IOException("The transferred VCE profile must remain locked during its update.");
        lock.rebindMovedRoot(target);
        PortableProfileTransfer.requireActivation(source, target);
        try(ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8))
        {
            migrateOwnedCompleteProfileCopy(database, new DataRootRelocation(source, target, true), output,
                MigrationContent.FULL, true);
            return bytes.toString(StandardCharsets.UTF_8);
        }
        catch(UnsupportedDatabaseFormatException unsupported)
        {
            throw new SQLException(unsupported.getMessage(), unsupported);
        }
    }

    /**
     * Relocates an inactive, complete copy of an owned profile under the caller's source and target profile locks.
     * The caller retains the complete original installation. External imports retain the strict staged path below.
     */
    private static void migrateOwnedCompleteProfileCopy(Path database, DataRootRelocation relocation,
                                                        PrintStream output, MigrationContent content)
        throws IOException, SQLException, UnsupportedDatabaseFormatException
    {
        migrateOwnedCompleteProfileCopy(database, relocation, output, content, false);
    }

    private static void migrateOwnedCompleteProfileCopy(Path database, DataRootRelocation relocation,
                                                        PrintStream output, MigrationContent content, boolean transferred)
        throws IOException, SQLException, UnsupportedDatabaseFormatException
    {
        if(Files.isSymbolicLink(database))
        {
            throw new IOException("The owned profile stage must be an ordinary database file.");
        }
        if(!transferred) requireApplicationStage(database, true);
        // A committed split briefly has no main file: the original is already Activity, and its extracted
        // configuration is awaiting promotion. Validate the owned directory and all existing artifacts before
        // allowing the receipt-bound recovery to finish that rename, then require the completed ordinary file.
        requireOwnedCompleteProfileStage(database, relocation, transferred);
        requireOwnedTransitionArtifacts(database);
        recoverSplitTransition(database);
        if(!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("The owned profile stage must be an ordinary database file.");
        }
        requireOwnedCompleteProfileStage(database, relocation, transferred);
        requireSingleFilesystemLinkWhenSupported(database);
        output.println("Checking the owned complete-profile copy's schema and configuration.");
        output.println("Whole-file physical-integrity, derived-history, and foreign-key sweeps are not run " +
            "for this owned copy; use explicit maintenance for those checks.");
        MigrationTimings timings = new MigrationTimings(output::println);

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat source;
            DatabaseMigrationChain.PreflightReport plan;
            try(var phase = timings.phase("Inspect owned-copy source schema and configuration"))
            {
                SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
                source = requireSupportedFormat(connection);
                if(source.version() >= 63)
                    io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePairIdentity(database);
                plan = DatabaseMigrationChain.planInPlace(connection, source).withContent(content);
                phase.complete();
            }
            printPreflight(output, plan, relocation);
            Set<String> legacyKeys = source.version() <= 52 ? LegacyReceiverSettingsMigration.missingKeys(connection) :
                Set.of();
            LegacyReceiverSettingsMigration.Snapshot legacy = LegacyReceiverSettingsMigration.capture(
                database.getParent().getParent(), legacyKeys);
            MigrationSummary migration = migrateInTransaction(connection, source, relocation, database, true,
                output::println, null, legacy, Map.of(), content, timings);
            //The transaction has checked the exact final schema, required settings and configuration before COMMIT.
            //Finalize this private stage for atomic promotion without scanning retained history again.
            try(var phase = timings.phase("Finalize owned-copy journals and database pair activation"))
            {
                finalizeStagedDatabase(connection);
                connection.close();
                recoverSplitTransition(database);
                phase.complete();
            }
            printCompletion(output, migration);
            output.println("RESULT: Owned complete-profile relocation and bounded validation complete.");
        }
    }

    private static void requireOwnedCompleteProfileStage(Path database, DataRootRelocation relocation, boolean transferred)
        throws IOException
    {
        if(relocation == null || !relocation.completeProfileCopy())
        {
            throw new IOException("Owned complete-profile relocation requires both data roots.");
        }
        Path source = relocation.source(), target = relocation.target();
        if(Files.isSymbolicLink(source) || (!transferred && !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) ||
            Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("Owned complete-profile relocation requires ordinary source and target directories.");
        }
        Path physicalSource = transferred ? source.toAbsolutePath().normalize() : source.toRealPath(), physicalTarget = target.toRealPath();
        Path targetDatabaseDirectory = target.resolve("database");
        if(physicalSource.startsWith(physicalTarget) || physicalTarget.startsWith(physicalSource))
        {
            throw new IOException("Owned complete-profile relocation requires distinct, non-nested data roots.");
        }
        if(Files.isSymbolicLink(targetDatabaseDirectory) ||
            !Files.isDirectory(targetDatabaseDirectory, LinkOption.NOFOLLOW_LINKS) ||
            !database.getParent().equals(targetDatabaseDirectory) ||
            !database.getParent().toRealPath().equals(physicalTarget.resolve("database")))
        {
            throw new IOException("The owned profile stage must be inside the target data root's database directory.");
        }
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(source);
        if(Files.exists(database, LinkOption.NOFOLLOW_LINKS) &&
            Files.exists(sourceDatabase, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(database, sourceDatabase))
        {
            throw new IOException("The owned profile stage must be a separate file from the source database.");
        }
    }

    private static void requireOwnedTransitionArtifacts(Path database) throws IOException
    {
        for(Path artifact: List.of(database, io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database),
            io.github.dsheirer.database.ActivityDatabasePath.configurationStage(database),
            io.github.dsheirer.database.ActivityDatabasePath.splitReceipt(database)))
        {
            if(Files.exists(artifact, LinkOption.NOFOLLOW_LINKS))
            {
                if(Files.isSymbolicLink(artifact) || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS))
                {
                    throw new IOException("The owned profile transition artifacts must be ordinary files.");
                }
                requireSingleFilesystemLinkWhenSupported(artifact);
            }
        }
    }

    private static void migrate(Path database, DataRootRelocation relocation, PrintStream output, MigrationContent content)
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
        recoverSplitTransition(database);
        //The helper output is shown and copyable in migration-completion dialogs. Keep private staging paths out of it.
        output.println("Checking staged database.");
        MigrationTimings timings = new MigrationTimings(output::println);

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat source = requireSupportedFormat(connection);
            if(source.version() >= 63)
            {
                // Only explicit private stages attach writable history. Owned upgrades never inspect retained rows.
                io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePairIdentity(database);
                requireSingleFilesystemLinkWhenSupported(
                    io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database));
                try(var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
                {
                    attach.setString(1, io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database).toString());
                    attach.execute();
                }
            }
            output.println("Detected database format " + source.version() + " [" + source.id() + "]: " +
                source.description() + (source.markerPresent() ? "." : " (legacy layout without global marker)."));
            DatabaseMigrationChain.PreflightReport preflight = requireSupportedPreflight(connection, source, timings).withContent(content);
            printPreflight(output, preflight, relocation);

            boolean relocationRequired = relocation != null && !relocation.source().equals(relocation.target());
            LegacyReceiverSettingsMigration.Snapshot legacy = relocation == null ?
                new LegacyReceiverSettingsMigration.Snapshot(Map.of()) :
                LegacyReceiverSettingsMigration.staged(database.getParent().getParent());
            if(!preflight.requiresMigration() && !relocationRequired)
            {
                try(var phase = timings.phase("Validate and finalize already-current staged database"))
                {
                    validateCurrentDatabase(connection);
                    requireForeignKeysValid(connection);
                    requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                    finalizeStagedDatabase(connection);
                    validateConfiguration(database);
                    phase.complete();
                }
                output.println("Portable directory preferences updated: 0.");
                output.println("Portable preference components repaired or reset: 0.");
                output.println("Database is already at current format " + DatabaseFormatCatalog.CURRENT_VERSION +
                    ".");
                output.println("RESULT: Application database is already current and valid; no changes made.");
                return;
            }

            output.println("Pre-migration checks passed. Updating the staged database.");
            if(hasAttachedActivity(connection)) finalizeStagedDatabase(connection);
            MigrationSummary migration = migrateInTransaction(connection, source, relocation, database, false,
                output::println, null, legacy, Map.of(), content, timings);
            //The transaction already completed the full schema, row, foreign-key and quick checks before COMMIT.
            //Finalizing the private stage changes only journal state; do not rescan the same retained history here.
            try(var phase = timings.phase("Finalize staged journals, pair activation and configuration validation"))
            {
                finalizeStagedDatabase(connection);
                DatabaseFormatCatalog.requireCurrent(connection);
                connection.close();
                recoverSplitTransition(database);
                validateConfiguration(database);
                phase.complete();
            }

            printCompletion(output, migration);
            output.println("RESULT: Application database migration and validation complete.");
        }
    }

    private static void requireApplicationStage(Path database) throws IOException
    {
        requireApplicationStage(database, false);
    }

    private static void requireApplicationStage(Path database, boolean allowMissingMainFile) throws IOException
    {
        Path physicalDatabase = allowMissingMainFile && !Files.exists(database, LinkOption.NOFOLLOW_LINKS) ?
            database.getParent().toRealPath().resolve(database.getFileName()) : database.toRealPath();
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

    static void requireSingleFilesystemLinkWhenSupported(Path database) throws IOException
    {
        try
        {
            Object value = Files.getAttribute(database, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if(value instanceof Number links && links.longValue() > 1)
            {
                throw new IOException("The database has multiple filesystem links and may alias a live " +
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
            DatabaseFormatCatalog.DetectedFormat source, MigrationTimings timings) throws SQLException
    {
        DatabaseMigrationChain.PreflightReport report;
        try(var phase = timings.phase("Inspect staged source and plan component repairs"))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            report = DatabaseMigrationChain.planForApplicationMigration(connection, source);
            phase.complete();
        }
        try(var phase = timings.phase("Source physical integrity check"))
        {
            requireSourceIntegrity(connection, "PRAGMA integrity_check", "Integrity check");
            phase.complete();
        }
        if(hasAttachedActivity(connection))
        {
            try(var phase = timings.phase("Source activity physical integrity check"))
            {
                requireSourceIntegrity(connection, "PRAGMA activity.integrity_check", "Activity integrity check");
                phase.complete();
            }
        }
        return report;
    }

    /** Completes only an Application-Migrator-owned interrupted format-63 transition under the profile lock. */
    public static void recoverSplitTransition(Path database) throws IOException, SQLException
    {
        Format62To63DatabaseMigration.recover(database.toAbsolutePath().normalize());
    }

    private static MigrationSummary migrateInTransaction(Connection connection,
            DatabaseFormatCatalog.DetectedFormat expectedSource, DataRootRelocation relocation, Path database,
            boolean inPlace, Consumer<String> progress, BackupAction backup,
            LegacyReceiverSettingsMigration.Snapshot legacy, Map<String,String> recoveryDefaults, MigrationContent content,
            MigrationTimings timings)
        throws IOException, SQLException
    {
        MigrationSummary committed = null;
        try(Statement statement = connection.createStatement())
        {
            boolean transactionOpen = false;
            // Every historical route now replaces the Alias List parent table at format 43 -> 44.
            // Disable enforcement before BEGIN even for owned profiles that previously needed only index updates.
            boolean foreignKeysRelaxed = expectedSource.version() < DatabaseFormatCatalog.CURRENT_VERSION;
            boolean checkConstraintsRelaxed = expectedSource.version() < DatabaseFormatCatalog.CURRENT_VERSION;

            if(foreignKeysRelaxed)
            {
                //Historical databases can contain orphaned relationships and row-local CHECK damage that later
                //steps rebuild, default, or drop. Keep those constraints from aborting an intermediate bulk copy;
                //final admission runs before COMMIT. Foreign keys must change before BEGIN.
                statement.execute("PRAGMA foreign_keys=OFF");
            }
            if(checkConstraintsRelaxed)
            {
                statement.execute("PRAGMA ignore_check_constraints=ON");
            }

            try(ApplicationMigrationTransaction transaction = beginTimedTransaction(connection, statement, timings))
            {
                transactionOpen = true;
                DatabaseFormatCatalog.DetectedFormat lockedSource;
                try(var phase = timings.phase("Recheck source format under writer lock"))
                {
                    lockedSource = DatabaseFormatCatalog.inspectForMigration(connection);
                    if(lockedSource.version() != expectedSource.version() ||
                        !lockedSource.id().equals(expectedSource.id()) ||
                        lockedSource.markerPresent() != expectedSource.markerPresent())
                    {
                        throw new SQLException("SQLite database format changed before the update transaction");
                    }
                    phase.complete();
                }
                if(backup != null)
                {
                    try(var phase = timings.phase("Create SQLite recovery backup"))
                    {
                        progress.accept("Creating the database backup");
                        backup.run();
                        phase.complete();
                    }
                }
                Long discardedHistoryRows = null;
                if(content == MigrationContent.CONFIGURATION_ONLY)
                {
                    try(var phase = timings.phase("Count and discard saved receiver Activity, statistics and alert history"))
                    {
                        progress.accept("Discarding saved receiver Activity, statistics and alert history");
                        discardedHistoryRows = MigrationActivityReset.discard(connection, timings);
                        phase.complete();
                    }
                }
                int recoveredLegacySettings;
                int[] importedLegacySettings = {0};

                PortablePreferenceResult portablePreferences = new PortablePreferenceResult(0, 0);
                CurrentDatabaseAdministrativeRepair.Inspection administrativeRepair =
                    CurrentDatabaseAdministrativeRepair.Inspection.none();
                CurrentDatabaseBestEffortRepair.Inspection currentRepair =
                    CurrentDatabaseBestEffortRepair.Inspection.none();
                CurrentDatabaseDerivedStateRepair.Inspection derivedStateRepair =
                    CurrentDatabaseDerivedStateRepair.Inspection.none();
                SqliteIdentityRepair.Inspection identityRepair = SqliteIdentityRepair.Inspection.none();
                try(var phase = timings.phase("Prepare legacy settings, configuration and identity repairs"))
                {
                    recoveredLegacySettings = legacy.recoverSelected(connection, recoveryDefaults);
                    if(inPlace)
                    {
                        //Factory inserts need usable sequence state before their adjacent step. This checks only
                        //configuration allocator rows and indexed maxima; it never walks retained receiver history.
                        identityRepair = SqliteIdentityRepair.repairAllocators(connection);
                    }
                    else if(expectedSource.version() < DatabaseFormatCatalog.CURRENT_VERSION)
                    {
                        //Historical steps can seed factory configuration. Normalize exhausted allocator state and
                        //isolate JSON-unsafe configuration identities before any such insert occurs.
                        identityRepair = SqliteIdentityRepair.repair(connection);
                    }
                    if(!inPlace && expectedSource.version() == DatabaseFormatCatalog.CURRENT_VERSION)
                    {
                        //A current-format source has the final schema already. Repair its bounded components before the
                        //chain performs strict current-format validation or adopts a missing global marker.
                        portablePreferences = updatePortableDirectoryPreferences(connection, relocation);
                        administrativeRepair = CurrentDatabaseAdministrativeRepair.repair(connection);
                        derivedStateRepair = CurrentDatabaseDerivedStateRepair.repair(connection);
                        currentRepair = CurrentDatabaseBestEffortRepair.repair(connection);
                        identityRepair = SqliteIdentityRepair.repair(connection);
                    }
                    phase.complete();
                }

                PortablePreferenceResult[] splitPreferences = {null};
                CurrentDatabaseAdministrativeRepair.Inspection[] splitAdministrative = {null};
                CurrentDatabaseBestEffortRepair.Inspection[] splitConfiguration = {null};
                int[] splitTunerPaths = {-1};
                int totalSteps = Math.max(1, DatabaseFormatCatalog.CURRENT_VERSION - expectedSource.version());
                int[] stepNumber = {0};
                DatabaseMigrationChain.MigrationReport chainReport = DatabaseMigrationChain.migrate(connection,
                    lockedSource, !inPlace, step -> progress.accept(
                        "Step " + (++stepNumber[0]) + " of " + totalSteps + " — " + step.description()),
                    step -> {
                        if(step.sourceVersion() == 52)
                            importedLegacySettings[0] = legacy.importMissing(connection);
                        if(step.sourceVersion() == 62)
                        {
                            try
                            {
                                splitPreferences[0] = updatePortableDirectoryPreferences(connection, relocation);
                                splitAdministrative[0] = CurrentDatabaseAdministrativeRepair.repair(connection, false);
                                splitConfiguration[0] = CurrentDatabaseBestEffortRepair.repair(connection);
                                splitTunerPaths[0] = rebaseRecordingTunerPaths(connection, relocation);
                            }
                            catch(IOException failure) { throw new SQLException("Unable to prepare configuration extraction",failure); }
                        }
                    }, DatabaseFormatCatalog.CURRENT_VERSION, timings);
                int rebasedRecordingTunerPaths;
                try(var phase = timings.phase("Finish history reset, component repairs and path relocation"))
                {
                    if(discardedHistoryRows != null)
                    {
                        DatabaseMigrationChain.StepReport reset = MigrationActivityReset.finish(connection,
                            expectedSource.version(), discardedHistoryRows);
                        List<DatabaseMigrationChain.StepReport> reports = new ArrayList<>();
                        reports.add(reset); reports.addAll(chainReport.steps());
                        chainReport = new DatabaseMigrationChain.MigrationReport(chainReport.source(), chainReport.target(),
                            List.copyOf(reports));
                    }
                    if(splitPreferences[0] != null)
                    {
                        portablePreferences = splitPreferences[0];
                        administrativeRepair = splitAdministrative[0];
                        currentRepair = splitConfiguration[0];
                    }
                    else if(inPlace)
                    {
                        //All historical layouts have now reached current configuration semantics. Repair this bounded
                        //component once; omit full derived-history, physical-integrity and foreign-key sweeps.
                        portablePreferences = updatePortableDirectoryPreferences(connection, relocation);
                        administrativeRepair = CurrentDatabaseAdministrativeRepair.repair(connection, false);
                        currentRepair = CurrentDatabaseBestEffortRepair.repair(connection);
                    }
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
                    if(splitPreferences[0] == null && !inPlace && expectedSource.version() < DatabaseFormatCatalog.CURRENT_VERSION)
                    {
                        portablePreferences = updatePortableDirectoryPreferences(connection, relocation);
                    }
                    rebasedRecordingTunerPaths = splitTunerPaths[0] >= 0 ? splitTunerPaths[0] : rebaseRecordingTunerPaths(connection, relocation);
                    phase.complete();
                }

                progress.accept("Validating the updated schema and configuration");
                try(var phase = timings.phase("Validate updated schema and retained rows"))
                {
                    if(inPlace)
                    {
                        //The exact catalog fingerprint covers every schema object. Required bounded settings and the
                        //configuration snapshot remain strict. Owned-profile upgrades omit full scans of retained
                        //radio identities, affiliations and presence; explicit maintenance checks that derived history.
                        DatabaseFormatCatalog.requireCurrent(connection);
                    }
                    else validateCurrentDatabase(connection);
                    phase.complete();
                }
                if(!inPlace)
                {
                    try(var phase = timings.phase("Check updated foreign-key relationships"))
                    {
                        requireForeignKeysValid(connection);
                        phase.complete();
                    }
                    try(var phase = timings.phase("Check updated SQLite integrity"))
                    {
                        requireIntegrity(connection, "PRAGMA quick_check", "Quick check");
                        phase.complete();
                    }
                }
                try(var phase = timings.phase("Validate saved configuration"))
                {
                    validateConfiguration(connection, database);
                    phase.complete();
                }
                MigrationSummary summary = new MigrationSummary(
                    (expectedSource.version() < DatabaseFormatCatalog.CURRENT_VERSION ||
                        portablePreferences.rebasedDirectories() > 0 || portablePreferences.resetEntries() > 0),
                    portablePreferences.rebasedDirectories(),
                    portablePreferences.resetEntries(), rebasedRecordingTunerPaths,
                    administrativeRepair, currentRepair, derivedStateRepair,
                    identityRepair, chainReport, importedLegacySettings[0], recoveredLegacySettings, content);
                try(var phase = timings.phase("Commit database update"))
                {
                    progress.accept("Committing the database update");
                    statement.execute("COMMIT");
                    transactionOpen = false;
                    committed = summary;
                    phase.complete();
                }
                try { progress.accept("Database update committed"); }
                catch(RuntimeException | Error ignored)
                {
                    reportCleanupWarning(progress, "Database update committed; completion observer reported a warning");
                }
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
                        try(var phase = timings.phase("Roll back failed database update"))
                        {
                            statement.execute("ROLLBACK");
                            phase.complete();
                        }
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
        catch(IOException | SQLException | RuntimeException | Error failure)
        {
            if(committed != null)
            {
                reportCleanupWarning(progress, "Database update committed; completion cleanup reported a warning");
                return committed;
            }
            throw failure;
        }
    }

    private static ApplicationMigrationTransaction beginTimedTransaction(Connection connection, Statement statement,
        MigrationTimings timings) throws SQLException
    {
        try(var phase = timings.phase("Acquire database writer lock"))
        {
            ApplicationMigrationTransaction transaction = ApplicationMigrationTransaction.begin(connection, statement);
            phase.complete();
            return transaction;
        }
    }

    private static void reportCleanupWarning(Consumer<String> progress, String message)
    {
        try { progress.accept(message); }
        catch(RuntimeException | Error ignored) { /* Completion observers cannot undo a committed transaction. */ }
    }

    private static PortablePreferenceResult updatePortableDirectoryPreferences(Connection connection,
                                                                                DataRootRelocation relocation)
        throws IOException, SQLException
    {
        return updatePortableDirectoryPreferences(connection, relocation, true);
    }

    /** Read-only count used by semantic adjacent migrations before the strict current-format stamp. */
    static int inspectCurrentPortablePreferenceRepairs(Connection connection) throws SQLException
    {
        return currentPortablePreferenceRepairs(connection, false);
    }

    /** Repairs only unusable current-layout portable preference components without relocating stored paths. */
    static int repairCurrentPortablePreferences(Connection connection) throws SQLException
    {
        return currentPortablePreferenceRepairs(connection, true);
    }

    private static int currentPortablePreferenceRepairs(Connection connection, boolean apply) throws SQLException
    {
        try
        {
            return updatePortableDirectoryPreferences(connection, null, apply).resetEntries();
        }
        catch(IOException exception)
        {
            throw new SQLException("Portable preferences could not be repaired safely.", exception);
        }
    }

    private static PortablePreferenceResult updatePortableDirectoryPreferences(Connection connection,
                                                                                DataRootRelocation relocation,
                                                                                boolean apply)
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
            Path physicalSource = physicalSourceRoot(relocation.source());
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
                    //Ordinary setup imports copy optional modules and vault, but do not copy recording audio/catalogs.
                    if("directory.recording.managed".equals(key) && !relocation.completeProfileCopy())
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
                            Path updated = relocatedContainedPath(stored, relocation, physicalSource);
                            if(updated != null)
                            {
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

        if(apply && (rebased > 0 || reset > 0))
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

    /** Relocates only application-owned recording tuner files; preserve independent tuner fields and external paths. */
    private static int rebaseRecordingTunerPaths(Connection connection, DataRootRelocation relocation)
        throws IOException, SQLException
    {
        if(relocation == null || !relocation.completeProfileCopy() || relocation.source().equals(relocation.target()))
        {
            return 0;
        }
        JsonNode settings;
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT settings_json FROM application_settings WHERE key='tuner.settings'");
            ResultSet rows = statement.executeQuery())
        {
            if(!rows.next())
            {
                return 0;
            }
            settings = OBJECT_MAPPER.readTree(rows.getString(1));
        }
        if(settings == null || !settings.path("tunerConfigurations").isArray())
        {
            return 0;
        }
        int rebased = 0;
        Path physicalSource = physicalSourceRoot(relocation.source());
        for(JsonNode configuration: settings.path("tunerConfigurations"))
        {
            if(!(configuration instanceof ObjectNode tuner) ||
                !"recordingTunerConfiguration".equals(tuner.path("type").asText()) ||
                !tuner.path("path").isTextual())
            {
                continue;
            }
            try
            {
                Path stored = Path.of(tuner.path("path").textValue());
                Path updated = relocatedContainedPath(stored, relocation, physicalSource);
                if(updated != null)
                {
                    tuner.put("path", updated.toString());
                    rebased++;
                }
            }
            catch(InvalidPathException ignored)
            {
                //Preserve a platform-specific or unusable path without changing other tuner configuration.
            }
        }
        if(rebased > 0)
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "UPDATE application_settings SET settings_json=?, updated_at_ms=? WHERE key='tuner.settings'"))
            {
                statement.setString(1, OBJECT_MAPPER.writeValueAsString(settings));
                statement.setLong(2, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
        return rebased;
    }

    /** The data folder may already have moved; its installation parent remains available to resolve aliases. */
    private static Path physicalSourceRoot(Path source) throws IOException
    {
        return source.getParent() != null ? source.getParent().toRealPath().resolve(source.getFileName()) : source;
    }

    /** Match either the selected installation spelling or its physical spelling without probing external paths. */
    private static Path relocatedContainedPath(Path stored, DataRootRelocation relocation, Path physicalSource)
    {
        if(!stored.isAbsolute()) return null;
        Path normalized = stored.normalize();
        Path matchingSource = normalized.startsWith(relocation.source()) ? relocation.source() :
            normalized.startsWith(physicalSource) ? physicalSource : null;
        return matchingSource != null ? relocation.target().resolve(matchingSource.relativize(normalized)).normalize() : null;
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
        else if(relocation.completeProfileCopy())
        {
            scope = "complete portable profile already copied by the caller; supported stored paths, including " +
                "Managed Recordings and recording tuner files, may be remapped within that copy.";
        }
        else
        {
            scope = "portable-profile import; supported stored paths may be remapped within the copied profile.";
        }
        output.println("Migration scope: " + scope);
        output.println("Migration contents: " + report.content().displayName() + ".");
        output.println("Migration plan: format " + report.source().version() + " [" + report.source().id() +
            "] -> format " + report.target().version() + " [" + report.target().id() + "] through " +
            report.steps().size() + " step(s).");
    }

    private static void printCompletion(PrintStream output, MigrationSummary migration)
    {
        output.println("Migration contents: " + migration.content().displayName() + ".");
        output.println("Legacy receiver settings imported from profile JSON: " + migration.importedLegacySettings() + ".");
        output.println("Explicitly selected legacy receiver settings recovered: " + migration.recoveredLegacySettings() + ".");
        output.println("Original legacy settings files were left unchanged.");
        DatabaseMigrationChain.MigrationReport report = migration.chainReport();
        boolean portableRepairRan = migration.portableRepairRan();
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
            printAdministrativeCompletion(output, migration.administrativeRepair());
            printCurrentConfigurationCompletion(output, migration.currentRepair());
        }
        if(migration.rebasedRecordingTunerPaths() > 0)
        {
            output.println("COMPLETED STEP: " + report.target().version() + " -> " + report.target().version() +
                " [rebase-recording-tuner-paths] Relocate recording tuner files inside the copied portable profile");
            output.println("  TRANSFORM recording tuner paths: " + migration.rebasedRecordingTunerPaths() +
                " path(s) - Preserved external files, tuner identities, and other saved tuner settings");
        }
        boolean sameFormatWork = report.steps().isEmpty() && (migration.currentRepair().requiresRepair() ||
            migration.administrativeRepair().requiresRepair() || migration.derivedStateRepair().requiresRepair() ||
            migration.identityRepair().requiresRepair() ||
            migration.rebasedDirectories() > 0 || migration.rebasedRecordingTunerPaths() > 0 ||
            migration.resetPortablePreferenceEntries() > 0 || migration.recoveredLegacySettings() > 0);
        int supplementalSteps = (migration.currentRepair().requiresRepair() ? 1 : 0) +
            (migration.administrativeRepair().requiresRepair() ? 1 : 0) +
            (migration.derivedStateRepair().requiresRepair() ? 1 : 0) +
            (migration.identityRepair().requiresRepair() ? 1 : 0) + (portableRepairRan ? 1 : 0) +
            (migration.rebasedRecordingTunerPaths() > 0 ? 1 : 0);
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
            output.println("COMPLETED STEP: " + DatabaseFormatCatalog.CURRENT_VERSION + " -> " +
                DatabaseFormatCatalog.CURRENT_VERSION + " [repair-portable-preferences] " +
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
            output.println("COMPLETED STEP: " + DatabaseFormatCatalog.CURRENT_VERSION + " -> " +
                DatabaseFormatCatalog.CURRENT_VERSION + " [" + CurrentDatabaseAdministrativeRepair.STEP_ID +
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
            output.println("COMPLETED STEP: " + DatabaseFormatCatalog.CURRENT_VERSION + " -> " +
                DatabaseFormatCatalog.CURRENT_VERSION + " [" + CurrentDatabaseBestEffortRepair.STEP_ID +
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
            output.println("COMPLETED STEP: " + DatabaseFormatCatalog.CURRENT_VERSION + " -> " +
                DatabaseFormatCatalog.CURRENT_VERSION + " [" + CurrentDatabaseDerivedStateRepair.STEP_ID +
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
        String format = io.github.dsheirer.database.ConfigurationHistorySchema.metadata(connection,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY);
        if("63".equals(format) || "64".equals(format) || "65".equals(format))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
            if(io.github.dsheirer.database.ConfigurationHistorySchema.HISTORY_ROLE.equals(
                io.github.dsheirer.database.ConfigurationHistorySchema.metadata(connection,
                    io.github.dsheirer.database.ConfigurationHistorySchema.ROLE_KEY)))
                ReceiverActivitySchema.validateRetainedHistoryData(connection);
            else
            {
                if(hasAttachedActivity(connection))
                {
                    validateAttachedHistoryContent(connection);
                    ReceiverActivitySchema.validateRetainedHistoryData(connection, "activity");
                    requireForeignKeysValid(connection, "activity");
                    requireIntegrity(connection, "PRAGMA activity.quick_check", "Activity quick check");
                    return;
                }
                Path history=io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(
                    Format62To63DatabaseMigration.databasePath(connection));
                try(Connection activity=SqliteDatabaseSnapshot.openImmutable(history))
                {
                    io.github.dsheirer.database.ConfigurationHistoryDatabase.validateRole(activity,"activity");
                    ReceiverActivitySchema.validateRetainedHistoryData(activity);
                    requireForeignKeysValid(activity);
                    requireIntegrity(activity,"PRAGMA quick_check","Activity quick check");
                }
            }
            return;
        }
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
        if(io.github.dsheirer.database.ConfigurationHistorySchema.HISTORY_ROLE.equals(
            io.github.dsheirer.database.ConfigurationHistorySchema.metadata(connection,
                io.github.dsheirer.database.ConfigurationHistorySchema.ROLE_KEY)))
        {
            Path extracted=io.github.dsheirer.database.ActivityDatabasePath.configurationStage(database);
            try(Connection configuration=io.github.dsheirer.database.ConfigurationHistoryDatabase.openReadOnly(extracted))
            {
                ConfigurationSnapshotValidator.validateForStartup(new ConfigurationRepository(extracted).load(configuration));
            }
            return;
        }
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
        requireForeignKeysValid(connection, "main");
    }

    private static void requireForeignKeysValid(Connection connection, String schema) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA " + schema + ".foreign_key_check"))
        {
            if(resultSet.next())
            {
                throw new SQLException("Foreign-key check failed for table [" + resultSet.getString("table") +
                    "], row [" + resultSet.getString("rowid") + "], parent [" + resultSet.getString("parent") +
                    "].");
            }
        }
    }

    private static void validateAttachedHistoryContent(Connection connection) throws SQLException
    {
        // The private companion's exact schema was admitted before this row-only current-format repair.
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validateAdmittedRole(connection,
            io.github.dsheirer.database.ConfigurationHistorySchema.HISTORY_ROLE,
            io.github.dsheirer.database.ConfigurationHistoryDatabase.HISTORY_FINGERPRINT, "activity");
        String pairKey = io.github.dsheirer.database.ConfigurationHistorySchema.PAIR_ID_KEY;
        if(!Objects.equals(io.github.dsheirer.database.ConfigurationHistorySchema.metadata(connection, pairKey),
            io.github.dsheirer.database.ConfigurationHistorySchema.metadata(connection, "activity", pairKey)))
            throw new SQLException("The private activity database pair identity changed during migration");
    }

    private static boolean hasAttachedActivity(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA database_list"))
        {
            while(rows.next()) if("activity".equals(rows.getString("name"))) return true;
            return false;
        }
    }

    private static void finalizeStagedDatabase(Connection connection) throws SQLException
    {
        finalizeStagedDatabase(connection, "main");
        if(hasAttachedActivity(connection)) finalizeStagedDatabase(connection, "activity");
    }

    private static void finalizeStagedDatabase(Connection connection, String schema) throws SQLException
    {
        if(!List.of("main", "activity").contains(schema)) throw new SQLException("Unknown staged database schema");
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA " + schema + ".wal_checkpoint(TRUNCATE)"))
        {
            if(resultSet.next() && resultSet.getInt(1) != 0)
            {
                throw new SQLException("Unable to checkpoint the migrated staged database.");
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA " + schema + ".journal_mode=DELETE"))
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

    private record DataRootRelocation(Path source, Path target, boolean completeProfileCopy)
    {
    }

    private record PortablePreferenceResult(int rebasedDirectories, int resetEntries)
    {
    }

    private record MigrationSummary(boolean portableRepairRan, int rebasedDirectories,
                                    int resetPortablePreferenceEntries, int rebasedRecordingTunerPaths,
                                    CurrentDatabaseAdministrativeRepair.Inspection administrativeRepair,
                                    CurrentDatabaseBestEffortRepair.Inspection currentRepair,
                                    CurrentDatabaseDerivedStateRepair.Inspection derivedStateRepair,
                                    SqliteIdentityRepair.Inspection identityRepair,
                                    DatabaseMigrationChain.MigrationReport chainReport, int importedLegacySettings,
                                    int recoveredLegacySettings, MigrationContent content)
    {
    }

    record InPlaceMigrationResult(DatabaseMigrationChain.PreflightReport sourcePlan, String helperOutput)
    {
    }

}
