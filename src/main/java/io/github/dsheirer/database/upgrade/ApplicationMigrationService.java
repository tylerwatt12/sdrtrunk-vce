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
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.configuration.ConfigurationSnapshotValidator;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultSchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.sqlite.SQLiteConfig;

/**
 * Stages, validates, and promotes portable data accepted by the current sdrtrunk-vce build.
 *
 * <p>The Application Migrator always runs in a child process and only receives a staged database. Normal startup
 * services remain validation-only for an existing SQLite schema. Every supported Alpha 8-or-newer source is resolved
 * by the whole-file format catalog and advanced through the same adjacent migration chain.</p>
 */
public final class ApplicationMigrationService
{
    private static final long FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> COPIED_DIRECTORIES = List.of("jmbe", "modules");

    private final Snapshotter mSnapshotter;
    private final MigrationRunner mMigrationRunner;
    private final StagePromoter mStagePromoter;

    public ApplicationMigrationService()
    {
        this(SqliteDatabaseSnapshot::create, ApplicationMigratorLauncher::run,
            ApplicationMigrationService::moveAtomically);
    }

    ApplicationMigrationService(Snapshotter snapshotter, MigrationRunner migrationRunner)
    {
        this(snapshotter, migrationRunner, ApplicationMigrationService::moveAtomically);
    }

    ApplicationMigrationService(Snapshotter snapshotter, MigrationRunner migrationRunner,
                                StagePromoter stagePromoter)
    {
        mSnapshotter = Objects.requireNonNull(snapshotter);
        mMigrationRunner = Objects.requireNonNull(migrationRunner);
        mStagePromoter = Objects.requireNonNull(stagePromoter);
    }

    /**
     * Imports a previous portable profile into a data root that does not yet have a database.
     */
    public MigrationResult importPrevious(Path sourceDataRoot, Path targetDataRoot, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        return importPrevious(new PreviousBuildLocator.Selection(sourceDataRoot.toAbsolutePath().normalize(),
            PreviousBuildLocator.InputScope.PORTABLE_PROFILE), targetDataRoot, progress);
    }

    /**
     * Imports either a complete previous portable profile or only a directly selected SQLite database into a data
     * root that does not yet have a database. Database-only input never copies neighboring profile artifacts or
     * rebases portable paths.
     */
    public MigrationResult importPrevious(PreviousBuildLocator.Selection source, Path targetDataRoot,
                                          ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        return importPrevious(source, targetDataRoot, null, progress);
    }

    /**
     * Imports a previous source only if it still matches the plan already presented to the operator.
     */
    public MigrationResult importPrevious(PreviousBuildLocator.Selection source, Path targetDataRoot,
                                          DatabaseMigrationChain.PreflightReport approvedPlan,
                                          ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        ProgressListener listener = progress == null ? ignored -> { } : progress;
        Objects.requireNonNull(source, "Previous-build input cannot be null");
        Path sourcePath = source.path().toAbsolutePath().normalize();
        PreviousBuildLocator.InputScope inputScope = source.scope();
        Path sourceRoot = inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE ? sourcePath : null;
        Path sourceDatabase = inputScope == PreviousBuildLocator.InputScope.DATABASE_FILE ? sourcePath :
            SdrTrunkDatabasePath.getDatabasePath(sourcePath);
        Path targetRoot = targetDataRoot.toAbsolutePath().normalize();
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);

        if(inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE)
        {
            requireSeparatePhysicalRoots(sourceRoot, targetRoot);
        }
        else
        {
            requireSeparatePhysicalDatabases(sourceDatabase, targetDatabase);
        }

        if(Files.exists(targetDatabase))
        {
            throw new IOException("The current portable data folder already has a database: " + targetDatabase);
        }
        SqliteDatabaseSnapshot.requireSourceUsable(sourceDatabase);

        listener.update("Checking previous data");
        DatabaseMigrationChain.PreflightReport sourcePlan = readMigrationPlan(sourceDatabase);
        if(approvedPlan != null)
        {
            requireMatchingPlan(approvedPlan, sourcePlan, "source database selected after confirmation");
        }
        listener.update("Migration plan: " + describePlan(sourcePlan));
        listener.update("Migration scope: " + describeScope(inputScope));
        requireEmptyOrMissing(targetRoot);
        FileAccessAttributeSnapshot targetRootAttributes =
            Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS) ? FileAccessAttributeSnapshot.capture(targetRoot) :
                null;
        Path targetParent = targetRoot.getParent();

        if(targetParent == null)
        {
            throw new IOException("The current portable data folder has no parent: " + targetRoot);
        }

        Files.createDirectories(targetParent);
        ensureFreeSpace(targetParent, requiredImportSpace(sourceDatabase, sourceRoot));
        Path stageRoot = targetParent.resolve("." + targetRoot.getFileName() + ".migration-" + UUID.randomUUID());
        boolean promoted = false;

        try
        {
            Files.createDirectory(stageRoot);
            listener.update(inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE ? "Copying setup" :
                "Copying selected database");
            Path stagedDatabase = SdrTrunkDatabasePath.getDatabasePath(stageRoot);
            mSnapshotter.create(sourceDatabase, stagedDatabase);
            requireMatchingPlan(sourcePlan, readMigrationPlan(stagedDatabase), "staged import snapshot");

            if(inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE)
            {
                copyOptionalProfileData(sourceRoot, stageRoot);
                listener.update("Creating safety backup");
                copyVaultSnapshot(sourceRoot, stageRoot);
            }

            listener.update("Updating database");
            String helperOutput = inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE ?
                mMigrationRunner.run(stagedDatabase, sourceRoot, targetRoot) :
                mMigrationRunner.run(stagedDatabase, null, null);

            listener.update("Checking updated data");
            validateGlobalDatabase(stagedDatabase);

            if(inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE)
            {
                validateVaultIfPresent(stageRoot);
            }

            listener.update("Finishing");
            requireNoSidecars(stagedDatabase,
                "The staged database still has SQLite sidecar files and cannot be installed safely.");
            if(targetRootAttributes != null)
            {
                targetRootAttributes.applyTo(stageRoot);
            }
            boolean removedOriginalTarget = false;
            try
            {
                removeEmptyTreeIfPresent(targetRoot);
                removedOriginalTarget = targetRootAttributes != null;
                mStagePromoter.promote(stageRoot, targetRoot);
            }
            catch(IOException | RuntimeException promotionFailure)
            {
                if(removedOriginalTarget && !Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS))
                {
                    try
                    {
                        Files.createDirectory(targetRoot);
                        targetRootAttributes.applyTo(targetRoot);
                    }
                    catch(IOException restoreFailure)
                    {
                        promotionFailure.addSuppressed(restoreFailure);
                    }
                }
                throw promotionFailure;
            }
            promoted = true;
            return new MigrationResult(inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE, null,
                sourcePlan, helperOutput, inputScope);
        }
        finally
        {
            if(!promoted)
            {
                deleteTreeIfExists(stageRoot);
            }
        }
    }

    /**
     * Migrates a supported earlier database already in the current portable data root and retains a safety backup.
     */
    public MigrationResult migrateCurrent(Path dataRoot, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        return migrateCurrent(dataRoot, null, progress);
    }

    /** Migrates in place only if the source still matches the plan already presented to the operator. */
    public MigrationResult migrateCurrent(Path dataRoot, DatabaseMigrationChain.PreflightReport approvedPlan,
                                          ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        ProgressListener listener = progress == null ? ignored -> { } : progress;
        Path normalizedRoot = dataRoot.toAbsolutePath().normalize();
        Path database = SdrTrunkDatabasePath.getDatabasePath(normalizedRoot);
        SqliteDatabaseSnapshot.requireSourceUsable(database);
        FileAccessAttributeSnapshot liveDatabaseAttributes = FileAccessAttributeSnapshot.capture(database);

        listener.update("Checking previous data");
        DatabaseMigrationChain.PreflightReport sourcePlan = readMigrationPlan(database);
        if(approvedPlan != null)
        {
            requireMatchingPlan(approvedPlan, sourcePlan, "source database selected after confirmation");
        }
        listener.update("Migration plan: " + describePlan(sourcePlan));
        listener.update("Migration scope: existing portable-profile database; external artifacts remain in place " +
            "and stored paths are not remapped.");

        Path databaseDirectory = database.getParent();
        //Safety backup + staged database + worst-case rollback journal/WAL for the staged transformation.
        int databaseCopies = 3;
        long requiredDatabaseSpace = safeMultiply(sqliteFootprint(database), databaseCopies);
        ensureFreeSpace(databaseDirectory, safeAdd(requiredDatabaseSpace, FREE_SPACE_MARGIN_BYTES));
        Path backupDirectory = databaseDirectory.resolve("backups");
        Files.createDirectories(backupDirectory);
        String identity = BACKUP_TIME.format(LocalDateTime.now()) + "-" +
            UUID.randomUUID().toString().substring(0, 8);
        Path backup = backupDirectory.resolve("sdrtrunk-before-application-migration-" + identity + ".sqlite");
        Path stagedBackup = backupDirectory.resolve("." + backup.getFileName() + ".incomplete-" + UUID.randomUUID());
        Path staged = databaseDirectory.resolve("." + SdrTrunkDatabasePath.DATABASE_FILENAME + ".migration-" +
            UUID.randomUUID());

        Throwable primaryFailure = null;
        try
        {
            listener.update("Creating safety backup");
            mSnapshotter.create(database, stagedBackup);
            liveDatabaseAttributes.applyTo(stagedBackup);
            DatabaseMigrationChain.PreflightReport backupPlan = readMigrationPlan(stagedBackup);
            requireMatchingPlan(sourcePlan, backupPlan, "safety backup");
            finalizeStandaloneSnapshot(stagedBackup);
            requireNoSidecars(stagedBackup,
                "The safety backup still has SQLite sidecar files and cannot be published safely.");
            liveDatabaseAttributes.applyTo(stagedBackup);
            moveAtomically(stagedBackup, backup);
            Files.copy(backup, staged, StandardCopyOption.COPY_ATTRIBUTES);

            listener.update("Updating database");
            String helperOutput = mMigrationRunner.run(staged, normalizedRoot, normalizedRoot);

            listener.update("Checking updated data");
            validateGlobalDatabase(staged);
            requireNoSidecars(staged,
                "The staged database still has SQLite sidecar files and cannot replace the live database safely.");
            liveDatabaseAttributes.applyTo(staged);

            listener.update("Finishing");
            prepareLiveDatabaseForReplacement(database);
            moveAtomicallyReplacing(staged, database);

            try
            {
                validateGlobalDatabase(database);
                liveDatabaseAttributes.applyTo(database);
            }
            catch(IOException | SQLException | RuntimeException validationFailure)
            {
                try
                {
                    restoreBackup(backup, database, liveDatabaseAttributes);
                }
                catch(IOException restoreFailure)
                {
                    validationFailure.addSuppressed(restoreFailure);
                }

                throw validationFailure;
            }

            return new MigrationResult(false, backup, sourcePlan, helperOutput,
                PreviousBuildLocator.InputScope.DATABASE_FILE);
        }
        catch(IOException | SQLException | InterruptedException | RuntimeException e)
        {
            primaryFailure = e;
            throw e;
        }
        catch(Error e)
        {
            primaryFailure = e;
            throw e;
        }
        finally
        {
            IOException cleanupFailure = null;
            try
            {
                deleteDatabaseAndSidecarsIfExists(staged);
            }
            catch(IOException e)
            {
                cleanupFailure = e;
            }
            try
            {
                deleteDatabaseAndSidecarsIfExists(stagedBackup);
            }
            catch(IOException e)
            {
                if(cleanupFailure == null)
                {
                    cleanupFailure = e;
                }
                else
                {
                    cleanupFailure.addSuppressed(e);
                }
            }
            if(cleanupFailure != null)
            {
                if(primaryFailure != null)
                {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
                else
                {
                    throw cleanupFailure;
                }
            }
        }
    }

    /** Inspects a source read-only and returns its exact ordered migration plan. */
    public static DatabaseMigrationChain.PreflightReport readMigrationPlan(Path database)
        throws IOException, SQLException
    {
        Path normalized = database.toAbsolutePath().normalize();

        if(!Files.isRegularFile(normalized))
        {
            throw new IOException("SDRTrunk SQLite database does not exist: " + normalized);
        }

        try(Connection connection = openReadOnly(normalized))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspect(connection);
            DatabaseMigrationChain.PreflightReport report =
                DatabaseMigrationChain.validateSource(connection, source);
            requireIntegrity(connection);
            requireForeignKeysValid(connection);
            return report;
        }
    }

    /** User-facing, value-free description of the ordered migration and its declared effects. */
    public static String describePlan(DatabaseMigrationChain.PreflightReport plan)
    {
        if(plan.steps().isEmpty())
        {
            return "No database changes are required.";
        }

        StringBuilder description = new StringBuilder("format ")
            .append(plan.source().version()).append(" [").append(plan.source().id()).append("] to format ")
            .append(plan.target().version()).append(" [").append(plan.target().id()).append("]");

        for(DatabaseMigrationChain.StepPreflight step: plan.steps())
        {
            description.append("; ").append(step.description());

            for(DatabaseMigrationEffect effect: step.effects())
            {
                description.append("; ").append(effect.kind().name().toLowerCase()).append(' ')
                    .append(effect.subject());
                if(effect.affectedRows() >= 0)
                {
                    description.append(" (").append(effect.affectedRows()).append(" row(s))");
                }
            }
        }

        return description.toString();
    }

    private static String describeScope(PreviousBuildLocator.InputScope inputScope)
    {
        return inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE ?
            "portable-profile import; supported neighboring artifacts are copied and stored paths may be remapped." :
            "SQLite database only; neighboring vault, JMBE, module, and other profile files are not copied, and " +
                "stored paths are not remapped.";
    }

    private static void validateGlobalDatabase(Path database) throws IOException, SQLException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Staged SDRTrunk SQLite database does not exist: " + database);
        }

        try(Connection connection = openReadOnly(database))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            SdrTrunkDatabaseSchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            DmrActivitySchema.validate(connection);
            TrunkedSiteSchema.validate(connection);
            requireIntegrity(connection);
            requireForeignKeysValid(connection);
        }

        ConfigurationSnapshotValidator.validateForStartup(new ConfigurationRepository(database).load());

        //The read-only checks above establish that it is safe to apply the main runtime's WAL configuration.
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    private static void requireMatchingPlan(DatabaseMigrationChain.PreflightReport expected,
                                            DatabaseMigrationChain.PreflightReport actual, String snapshot)
        throws IOException
    {
        if(!expected.equals(actual))
        {
            throw new IOException("The " + snapshot + " does not match the migration plan approved for the " +
                "source database. The source may have changed; close it and try again.");
        }
    }

    private static void validateVaultIfPresent(Path dataRoot) throws SQLException
    {
        Path vault = EncryptionKeyVaultPath.getVaultPath(dataRoot);

        if(Files.isRegularFile(vault))
        {
            try(Connection connection = openReadOnly(vault))
            {
                EncryptionKeyVaultSchema.validate(connection);
                requireIntegrity(connection);
                requireForeignKeysValid(connection);
            }
        }
    }

    private void copyVaultSnapshot(Path sourceRoot, Path stageRoot) throws IOException, SQLException
    {
        Path sourceVault = EncryptionKeyVaultPath.getVaultPath(sourceRoot);

        if(Files.isRegularFile(sourceVault))
        {
            mSnapshotter.create(sourceVault, EncryptionKeyVaultPath.getVaultPath(stageRoot));
        }
    }

    private static void copyOptionalProfileData(Path sourceRoot, Path stageRoot) throws IOException
    {
        for(String directory : COPIED_DIRECTORIES)
        {
            Path source = sourceRoot.resolve(directory);

            if(Files.isDirectory(source))
            {
                copyDirectory(source, stageRoot.resolve(directory));
            }
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException
    {
        Files.walkFileTree(source, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException
            {
                if(Files.isSymbolicLink(directory))
                {
                    throw new IOException("Refusing to follow a symbolic link while copying previous data: " +
                        directory);
                }

                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                if(Files.isSymbolicLink(file))
                {
                    throw new IOException("Refusing to copy a symbolic link from previous data: " + file);
                }

                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void prepareLiveDatabaseForReplacement(Path database) throws IOException, SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);

            try(ResultSet resultSet = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(!resultSet.next() || resultSet.getInt(1) != 0)
                {
                    throw new IOException("The current database is in use. Close every other sdrtrunk-vce window " +
                        "and try again.");
                }
            }
        }

        Path wal = Path.of(database + "-wal");
        Path sharedMemory = Path.of(database + "-shm");
        Path rollbackJournal = Path.of(database + "-journal");

        if(Files.exists(wal) || Files.exists(sharedMemory) || Files.exists(rollbackJournal))
        {
            throw new IOException("The current database is still active. Close every other sdrtrunk-vce window " +
                "and try again.");
        }
    }

    private static void finalizeStandaloneSnapshot(Path database) throws IOException, SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
            try(ResultSet resultSet = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(!resultSet.next() || resultSet.getInt(1) != 0)
                {
                    throw new IOException("The safety backup could not be checkpointed.");
                }
            }
            try(ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode=DELETE"))
            {
                if(!resultSet.next() || !"delete".equalsIgnoreCase(resultSet.getString(1)))
                {
                    throw new IOException("The safety backup could not be made standalone.");
                }
            }
        }
    }

    private static void restoreBackup(Path backup, Path database, FileAccessAttributeSnapshot accessAttributes)
        throws IOException
    {
        Path restore = database.resolveSibling("." + database.getFileName() + ".restore-" + UUID.randomUUID());

        try
        {
            Files.copy(backup, restore);
            accessAttributes.applyTo(restore);
            requireNoSidecars(database, "The migrated database is still active, so its safety backup cannot be " +
                "restored automatically.");
            moveAtomicallyReplacing(restore, database);
        }
        finally
        {
            Files.deleteIfExists(restore);
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

    private static Connection openReadOnly(Path database) throws SQLException
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
        config.enforceForeignKeys(true);
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize(),
            config.toProperties());
    }

    private static void requireIntegrity(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check"))
        {
            boolean result = false;

            while(resultSet.next())
            {
                result = true;

                if(!"ok".equalsIgnoreCase(resultSet.getString(1)))
                {
                    throw new SQLException("SQLite integrity check failed: " + resultSet.getString(1));
                }
            }

            if(!result)
            {
                throw new SQLException("SQLite integrity check returned no result.");
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
                throw new SQLException("SQLite foreign-key check failed for table " + resultSet.getString(1) + ".");
            }
        }
    }

    private static void requireEmptyOrMissing(Path directory) throws IOException
    {
        if(!Files.exists(directory))
        {
            return;
        }

        if(Files.isSymbolicLink(directory))
        {
            throw new IOException("The portable data location must not be a symbolic link: " + directory);
        }

        if(!Files.isDirectory(directory))
        {
            throw new IOException("The portable data location is not a folder: " + directory);
        }

        try(var paths = Files.list(directory))
        {
            if(paths.findAny().isPresent())
            {
                throw new IOException("The current portable data folder already contains data. Move or rename it " +
                    "before importing previous data: " + directory);
            }
        }
    }

    private static void removeEmptyTreeIfPresent(Path directory) throws IOException
    {
        if(Files.exists(directory))
        {
            requireEmptyOrMissing(directory);
            //A nonrecursive delete fails atomically if another first-run process populated this folder after the
            //emptiness check. Recursive deletion is reserved for this process's private UUID staging folder.
            Files.deleteIfExists(directory);
        }
    }

    static void deleteDatabaseAndSidecarsIfExists(Path database) throws IOException
    {
        IOException failure = null;
        for(String suffix: List.of("-journal", "-wal", "-shm", ""))
        {
            Path path = suffix.isEmpty() ? database : Path.of(database + suffix);
            try
            {
                Files.deleteIfExists(path);
            }
            catch(IOException e)
            {
                if(failure == null)
                {
                    failure = e;
                }
                else
                {
                    failure.addSuppressed(e);
                }
            }
        }
        if(failure != null)
        {
            throw failure;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            throw new IOException("This drive does not support the atomic folder move required for a safe import.",
                e);
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

    private static long requiredImportSpace(Path sourceDatabase, Path sourceRoot) throws IOException
    {
        //The source remains untouched while the staged snapshot may need a rollback journal/WAL of comparable size.
        long required = safeMultiply(sqliteFootprint(sourceDatabase), 2);

        if(sourceRoot == null)
        {
            return safeAdd(required, FREE_SPACE_MARGIN_BYTES);
        }

        Path vault = EncryptionKeyVaultPath.getVaultPath(sourceRoot);

        if(Files.isRegularFile(vault))
        {
            required = safeAdd(required, sqliteFootprint(vault));
        }

        for(String directory : COPIED_DIRECTORIES)
        {
            Path source = sourceRoot.resolve(directory);

            if(Files.isDirectory(source))
            {
                try(var paths = Files.walk(source))
                {
                    var files = paths.filter(Files::isRegularFile).iterator();

                    while(files.hasNext())
                    {
                        required = safeAdd(required, Files.size(files.next()));
                    }
                }
            }
        }

        return safeAdd(required, FREE_SPACE_MARGIN_BYTES);
    }

    private static void requireSeparatePhysicalRoots(Path sourceRoot, Path targetRoot) throws IOException
    {
        Path physicalSource = sourceRoot.toRealPath();
        Path targetParent = targetRoot.getParent();
        if(targetParent == null || targetRoot.getFileName() == null)
        {
            throw new IOException("The current portable data folder has no parent: " + targetRoot);
        }
        //Resolve the install parent, but never follow an existing target symlink that promotion would replace.
        Path physicalTarget = effectivePhysicalPath(targetParent).resolve(targetRoot.getFileName()).normalize();
        if(physicalSource.startsWith(physicalTarget) || physicalTarget.startsWith(physicalSource))
        {
            throw new IOException("The previous and current portable data folders overlap.");
        }
    }

    private static void requireSeparatePhysicalDatabases(Path sourceDatabase, Path targetDatabase) throws IOException
    {
        Path physicalSource = sourceDatabase.toRealPath();
        Path targetParent = targetDatabase.getParent();

        if(targetParent == null || targetDatabase.getFileName() == null)
        {
            throw new IOException("The target database has no parent: " + targetDatabase);
        }

        Path physicalTarget = effectivePhysicalPath(targetParent).resolve(targetDatabase.getFileName()).normalize();

        if(physicalSource.equals(physicalTarget))
        {
            throw new IOException("The previous and current SQLite database paths are the same.");
        }
    }

    private static Path effectivePhysicalPath(Path path) throws IOException
    {
        Path existing = path;
        Deque<Path> missing = new ArrayDeque<>();
        while(existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS))
        {
            missing.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        if(existing == null)
        {
            throw new IOException("The current portable data folder has no existing filesystem ancestor: " + path);
        }
        Path effective = existing.toRealPath();
        for(Path element: missing)
        {
            effective = effective.resolve(element);
        }
        return effective.normalize();
    }

    private static long sqliteFootprint(Path database) throws IOException
    {
        long size = Files.size(database);

        for(String suffix : List.of("-wal", "-journal"))
        {
            Path sidecar = Path.of(database + suffix);

            if(Files.isRegularFile(sidecar))
            {
                size = safeAdd(size, Files.size(sidecar));
            }
        }

        return size;
    }

    private static void ensureFreeSpace(Path targetDirectory, long required) throws IOException
    {
        FileStore store = Files.getFileStore(targetDirectory);
        long usable = store.getUsableSpace();

        if(usable < required)
        {
            throw new IOException("Not enough free space for a safe migration. Required approximately " +
                humanSize(required) + "; available " + humanSize(usable) + ".");
        }
    }

    private static long safeAdd(long left, long right)
    {
        if(left > Long.MAX_VALUE - right)
        {
            return Long.MAX_VALUE;
        }

        return left + right;
    }

    private static long safeMultiply(long value, int multiplier)
    {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static String humanSize(long bytes)
    {
        long mebibytes = Math.max(1, bytes / (1024L * 1024L));
        return mebibytes + " MiB";
    }

    private static void deleteTreeIfExists(Path root) throws IOException
    {
        if(!Files.exists(root))
        {
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
            {
                if(failure != null)
                {
                    throw failure;
                }

                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    public interface ProgressListener
    {
        void update(String step);
    }

    @FunctionalInterface
    interface Snapshotter
    {
        void create(Path source, Path destination) throws IOException, SQLException;
    }

    @FunctionalInterface
    interface MigrationRunner
    {
        String run(Path stagedDatabase, Path sourceDataRoot, Path targetDataRoot)
            throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface StagePromoter
    {
        void promote(Path stagedDataRoot, Path targetDataRoot) throws IOException;
    }

    public record MigrationResult(boolean importedPreviousProfile, Path safetyBackup,
                                  DatabaseMigrationChain.PreflightReport sourcePlan,
                                  String helperOutput, PreviousBuildLocator.InputScope inputScope)
    {
        public DatabaseFormatCatalog.DetectedFormat sourceFormat()
        {
            return sourcePlan.source();
        }
    }
}
