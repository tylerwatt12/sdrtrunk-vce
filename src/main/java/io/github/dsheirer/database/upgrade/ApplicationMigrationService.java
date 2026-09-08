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
import io.github.dsheirer.database.InitialAdminSetup;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.configuration.ConfigurationSnapshotValidator;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultSchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
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
import java.util.ArrayList;
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
    private static final String JMBE_DIRECTORY = "jmbe";
    private static final String MODULES_DIRECTORY = "modules";
    private static final String OPTIONAL_PROFILE_REPORT_HEADER = "Optional profile items:";

    private final Snapshotter mExternalSnapshotter;
    private final Snapshotter mLiveSnapshotter;
    private final MigrationRunner mMigrationRunner;
    private final StagePromoter mStagePromoter;
    private final DatabaseValidator mPromotedDatabaseValidator;
    private final boolean mExternalSnapshotsAreCanonical;
    private final boolean mLiveSnapshotsAreCanonical;

    public ApplicationMigrationService()
    {
        this(SqliteDatabaseSnapshot::createExternal, SqliteDatabaseSnapshot::create,
            ApplicationMigratorLauncher::run,
            ApplicationMigrationService::moveAtomically, ApplicationMigrationService::validateGlobalDatabase,
            true, true);
    }

    ApplicationMigrationService(MigrationRunner migrationRunner)
    {
        this(SqliteDatabaseSnapshot::createExternal, SqliteDatabaseSnapshot::create, migrationRunner,
            ApplicationMigrationService::moveAtomically, ApplicationMigrationService::validateGlobalDatabase,
            true, true);
    }

    ApplicationMigrationService(Snapshotter snapshotter, MigrationRunner migrationRunner)
    {
        this(snapshotter, snapshotter, migrationRunner, ApplicationMigrationService::moveAtomically,
            ApplicationMigrationService::validateGlobalDatabase, false, false);
    }

    ApplicationMigrationService(Snapshotter snapshotter, MigrationRunner migrationRunner,
                                StagePromoter stagePromoter)
    {
        this(snapshotter, snapshotter, migrationRunner, stagePromoter,
            ApplicationMigrationService::validateGlobalDatabase, false, false);
    }

    private ApplicationMigrationService(Snapshotter externalSnapshotter, Snapshotter liveSnapshotter,
                                        MigrationRunner migrationRunner, StagePromoter stagePromoter,
                                        DatabaseValidator promotedDatabaseValidator,
                                        boolean externalSnapshotsAreCanonical,
                                        boolean liveSnapshotsAreCanonical)
    {
        mExternalSnapshotter = Objects.requireNonNull(externalSnapshotter);
        mLiveSnapshotter = Objects.requireNonNull(liveSnapshotter);
        mMigrationRunner = Objects.requireNonNull(migrationRunner);
        mStagePromoter = Objects.requireNonNull(stagePromoter);
        mPromotedDatabaseValidator = Objects.requireNonNull(promotedDatabaseValidator);
        mExternalSnapshotsAreCanonical = externalSnapshotsAreCanonical;
        mLiveSnapshotsAreCanonical = liveSnapshotsAreCanonical;
    }

    ApplicationMigrationService(Snapshotter snapshotter, MigrationRunner migrationRunner,
                                DatabaseValidator promotedDatabaseValidator)
    {
        this(snapshotter, snapshotter, migrationRunner, ApplicationMigrationService::moveAtomically,
            promotedDatabaseValidator, false, false);
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
                                          ApprovedMigrationPlan approvedPlan,
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
        Path targetParent = targetRoot.getParent();
        if(targetParent == null)
        {
            throw new IOException("The current portable data folder has no parent: " + targetRoot);
        }
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
        requireEmptyOrMissing(targetRoot);
        Files.createDirectories(targetParent);
        listener.update("Checking previous data");
        ApprovedMigrationPlan expectedPlan = approvedPlan != null ? approvedPlan :
            readMigrationApproval(sourceDatabase, targetParent);
        listener.update("Migration plan: " + describePlan(expectedPlan.plan()));
        listener.update("Migration scope: " + describeScope(inputScope));
        FileAccessAttributeSnapshot targetRootAttributes =
            Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS) ? FileAccessAttributeSnapshot.capture(targetRoot) :
                null;
        ensureFreeSpace(targetParent, requiredImportSpace(sourceDatabase));
        Path stageRoot = targetParent.resolve("." + targetRoot.getFileName() + ".migration-" + UUID.randomUUID());
        boolean promoted = false;
        Throwable primaryFailure = null;

        try
        {
            Files.createDirectory(stageRoot);
            FileAccessAttributeSnapshot.restrictPrivateDirectory(stageRoot);
            listener.update("Copying selected database");
            Path stagedDatabase = SdrTrunkDatabasePath.getDatabasePath(stageRoot);
            mExternalSnapshotter.create(sourceDatabase, stagedDatabase);
            FileAccessAttributeSnapshot.restrictSensitiveFile(stagedDatabase);
            ApprovedMigrationPlan stagedPlan = readPrivateApprovedMigrationPlan(stagedDatabase,
                mExternalSnapshotsAreCanonical);
            requireApprovedPlan(expectedPlan, stagedPlan, "source database selected after confirmation");
            DatabaseMigrationChain.PreflightReport sourcePlan = stagedPlan.plan();

            listener.update("Updating database");
            String helperOutput = inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE ?
                mMigrationRunner.run(stagedDatabase, sourceRoot, targetRoot) :
                mMigrationRunner.run(stagedDatabase, null, null);

            listener.update("Checking updated data");
            InitialAdminSetup.initializeNewProfile(stagedDatabase);
            //A copied profile belongs to a new destination. Never inherit the source installation's completed wizard.
            new io.github.dsheirer.gui.setup.SetupProgress(false, true).save(stagedDatabase);
            validateGlobalDatabase(stagedDatabase);
            requireNoSidecars(stagedDatabase,
                "The staged database still has SQLite sidecar files and cannot be installed safely.");

            if(inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE)
            {
                listener.update("Copying optional profile items");
                List<String> optionalStatuses = copyOptionalProfileData(sourceRoot, stageRoot);
                for(String status: optionalStatuses)
                {
                    listener.update(status.substring(2));
                }
                helperOutput = appendOptionalProfileReport(helperOutput, optionalStatuses);
            }

            listener.update("Finishing");
            if(targetRootAttributes != null)
            {
                targetRootAttributes.applyTo(stageRoot);
            }
            FileAccessAttributeSnapshot.restrictSensitiveFile(stagedDatabase);
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
        catch(IOException | SQLException | InterruptedException | RuntimeException failure)
        {
            primaryFailure = failure;
            throw failure;
        }
        catch(Error failure)
        {
            primaryFailure = failure;
            throw failure;
        }
        finally
        {
            if(!promoted)
            {
                try
                {
                    deleteTreeIfExists(stageRoot);
                }
                catch(IOException cleanupFailure)
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
    }

    /**
     * Replaces the active database with a separately selected, supported SQLite database. The selected source is
     * copied into a private stage and never changed. The current active database is retained as a validated safety
     * backup before the staged source can be promoted.
     *
     * <p>This operation requires a stopped application runtime: all database services must be closed before the
     * final atomic replacement.</p>
     */
    public MigrationResult replaceCurrentDatabase(Path sourceDatabase, Path targetDataRoot,
                                                  ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        return replaceCurrentDatabase(sourceDatabase, targetDataRoot, null, progress);
    }

    /** Replaces the active database only if the selected source still matches the operator-approved plan. */
    public MigrationResult replaceCurrentDatabase(Path sourceDatabase, Path targetDataRoot,
                                                  ApprovedMigrationPlan approvedPlan,
                                                  ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        ProgressListener listener = progress == null ? ignored -> { } : progress;
        Objects.requireNonNull(sourceDatabase, "Selected SQLite database cannot be null");
        Objects.requireNonNull(targetDataRoot, "Current portable data root cannot be null");
        Path source = sourceDatabase.toAbsolutePath().normalize();
        Path targetRoot = targetDataRoot.toAbsolutePath().normalize();
        Path target = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        requireSeparatePhysicalDatabases(source, target);
        SqliteDatabaseSnapshot.requireSourceUsable(source);
        SqliteDatabaseSnapshot.requireSourceUsable(target);
        FileAccessAttributeSnapshot targetAttributes = FileAccessAttributeSnapshot.capture(target);

        listener.update("Checking selected SQLite database");
        ApprovedMigrationPlan expectedPlan = approvedPlan != null ? approvedPlan :
            readMigrationApproval(source, target.getParent());
        listener.update("Migration plan: " + describePlan(expectedPlan.plan()));
        listener.update("Migration scope: SQLite database only; neighboring source files are not copied and the " +
            "current portable files outside the database remain in place.");
        listener.update("Checking current database");
        DatabaseMigrationChain.PreflightReport currentPlan = readMigrationPlan(target);
        Path databaseDirectory = target.getParent();
        long sourceCopies = safeMultiply(sqliteFootprint(source), 2);
        long requiredSpace = safeAdd(sqliteFootprint(target), sourceCopies);
        ensureFreeSpace(databaseDirectory, safeAdd(requiredSpace, FREE_SPACE_MARGIN_BYTES));
        Path backupDirectory = databaseDirectory.resolve("backups");
        String identity = BACKUP_TIME.format(LocalDateTime.now()) + "-" +
            UUID.randomUUID().toString().substring(0, 8);
        Path backup = backupDirectory.resolve("sdrtrunk-before-sqlite-import-" + identity + ".sqlite");
        Path stagedBackup = databaseDirectory.resolve("." + backup.getFileName() + ".incomplete-" + UUID.randomUUID());
        Path staged = databaseDirectory.resolve("." + SdrTrunkDatabasePath.DATABASE_FILENAME + ".migration-" +
            UUID.randomUUID());

        Throwable primaryFailure = null;
        try
        {
            listener.update("Copying selected SQLite database");
            mExternalSnapshotter.create(source, staged);
            FileAccessAttributeSnapshot.restrictSensitiveFile(staged);
            ApprovedMigrationPlan stagedPlan = readPrivateApprovedMigrationPlan(staged,
                mExternalSnapshotsAreCanonical);
            requireApprovedPlan(expectedPlan, stagedPlan, "SQLite database selected after confirmation");
            DatabaseMigrationChain.PreflightReport sourcePlan = stagedPlan.plan();

            listener.update("Creating current database safety backup");
            mLiveSnapshotter.create(target, stagedBackup);
            targetAttributes.applyTo(stagedBackup);
            requireMatchingPlan(currentPlan, readPrivateMigrationPlan(stagedBackup),
                "current database safety backup");
            finalizeStandaloneSnapshot(stagedBackup);
            requireNoSidecars(stagedBackup,
                "The current database safety backup still has SQLite sidecar files and cannot be published safely.");
            targetAttributes.applyTo(stagedBackup);
            Files.createDirectories(backupDirectory);
            moveAtomically(stagedBackup, backup);

            listener.update("Updating selected database copy");
            String helperOutput = mMigrationRunner.run(staged, null, null);
            InitialAdminSetup.initializeNewProfile(staged);
            //Commit the review requirement with the replacement, not after promotion or only in restart arguments.
            io.github.dsheirer.gui.setup.SetupProgress.replacementReview().save(staged);

            listener.update("Checking updated database");
            validateGlobalDatabase(staged);
            requireNoSidecars(staged,
                "The staged SQLite database still has sidecar files and cannot replace the active database safely.");
            targetAttributes.applyTo(staged);

            listener.update("Replacing active database");
            prepareLiveDatabaseForReplacement(target);
            moveAtomicallyReplacing(staged, target);

            validatePromotedDatabaseOrRestore(target, backup, targetAttributes);

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

    /**
     * Migrates a supported earlier database already in the current portable data root and retains a safety backup.
     */
    public MigrationResult migrateCurrent(Path dataRoot, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        return migrateCurrent(dataRoot, null, progress);
    }

    /** Migrates in place only if the source still matches the plan already presented to the operator. */
    public MigrationResult migrateCurrent(Path dataRoot, ApprovedMigrationPlan approvedPlan,
                                          ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        ProgressListener listener = progress == null ? ignored -> { } : progress;
        Path normalizedRoot = dataRoot.toAbsolutePath().normalize();
        Path database = SdrTrunkDatabasePath.getDatabasePath(normalizedRoot);
        SqliteDatabaseSnapshot.requireSourceUsable(database);
        FileAccessAttributeSnapshot liveDatabaseAttributes = FileAccessAttributeSnapshot.capture(database);
        listener.update("Checking previous data");
        ApprovedMigrationPlan expectedPlan = approvedPlan != null ? approvedPlan :
            readMigrationApproval(database, database.getParent());
        listener.update("Migration plan: " + describePlan(expectedPlan.plan()));
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
        Path stagedBackup = databaseDirectory.resolve("." + backup.getFileName() + ".incomplete-" + UUID.randomUUID());
        Path staged = databaseDirectory.resolve("." + SdrTrunkDatabasePath.DATABASE_FILENAME + ".migration-" +
            UUID.randomUUID());

        Throwable primaryFailure = null;
        try
        {
            listener.update("Creating safety backup");
            mLiveSnapshotter.create(database, stagedBackup);
            liveDatabaseAttributes.applyTo(stagedBackup);
            ApprovedMigrationPlan backupPlan = readPrivateApprovedMigrationPlan(stagedBackup,
                mLiveSnapshotsAreCanonical);
            requireApprovedPlan(expectedPlan, backupPlan, "source database selected after confirmation");
            DatabaseMigrationChain.PreflightReport sourcePlan = backupPlan.plan();
            finalizeStandaloneSnapshot(stagedBackup);
            requireNoSidecars(stagedBackup,
                "The safety backup still has SQLite sidecar files and cannot be published safely.");
            DatabaseMigrationChain.PreflightReport finalizedBackupPlan = readPrivateMigrationPlan(stagedBackup);
            requireMatchingPlan(sourcePlan, finalizedBackupPlan, "finalized safety backup");
            RestoredDatabaseExpectation rollbackExpectation = new RestoredDatabaseExpectation(finalizedBackupPlan,
                SqliteDatabaseSnapshot.sha256(stagedBackup));
            liveDatabaseAttributes.applyTo(stagedBackup);
            moveAtomically(stagedBackup, backup);
            Files.copy(backup, staged, StandardCopyOption.COPY_ATTRIBUTES);

            listener.update("Updating database");
            String helperOutput = mMigrationRunner.run(staged, normalizedRoot, normalizedRoot);
            InitialAdminSetup.preserveGrandfatheredAbsence(backup, staged);

            listener.update("Checking updated data");
            validateGlobalDatabase(staged);
            requireNoSidecars(staged,
                "The staged database still has SQLite sidecar files and cannot replace the live database safely.");
            liveDatabaseAttributes.applyTo(staged);

            listener.update("Finishing");
            prepareLiveDatabaseForReplacement(database);
            moveAtomicallyReplacing(staged, database);

            validatePromotedDatabaseOrRestore(database, backup, liveDatabaseAttributes,
                restored -> validateRestoredMigrationSource(restored, rollbackExpectation));

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

    /**
     * Inspects the application-owned database without making a full-size copy or hashing all of its content. This is
     * the normal startup path; a caller that will ask the operator to approve a migration must use
     * {@link #readMigrationApproval(Path)} instead.
     */
    public static DatabaseMigrationChain.PreflightReport readMigrationPlan(Path database)
        throws IOException, SQLException
    {
        Path normalized = database.toAbsolutePath().normalize();
        SqliteDatabaseSnapshot.requireSourceUsable(normalized);
        Path rollbackJournal = Path.of(normalized + "-journal");
        if(Files.isRegularFile(rollbackJournal, LinkOption.NOFOLLOW_LINKS))
        {
            //A hot rollback journal requires recovery. Recover only a private copy during startup inspection.
            return readMigrationApproval(normalized).plan();
        }
        Path wal = Path.of(normalized + "-wal");
        boolean committedWalPresent = Files.isRegularFile(wal, LinkOption.NOFOLLOW_LINKS) && Files.size(wal) > 0;
        try(Connection connection = committedWalPresent ? openLiveReadOnly(normalized) : openReadOnly(normalized))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
            requireSourceQuickCheck(connection);
            return DatabaseMigrationChain.planForApplicationMigration(connection, source);
        }
    }

    /**
     * Creates an immutable approval from one recovered copy of the complete selected SQLite source, including a WAL
     * or rollback journal. The content digest is intentionally calculated only for an explicit migration/import
     * review, never during an ordinary current-format startup.
     */
    public static ApprovedMigrationPlan readMigrationApproval(Path database) throws IOException, SQLException
    {
        Path normalized = database.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if(parent == null)
        {
            throw new IOException("The selected SQLite database has no parent directory: " + normalized);
        }
        return readMigrationApproval(normalized, parent);
    }

    /** Creates approval scratch data beside the caller's selected destination rather than in the JVM temp volume. */
    public static ApprovedMigrationPlan readMigrationApproval(Path database, Path scratchDirectory)
        throws IOException, SQLException
    {
        Path normalized = database.toAbsolutePath().normalize();
        Path scratch = Objects.requireNonNull(scratchDirectory, "Migration scratch directory cannot be null")
            .toAbsolutePath().normalize();
        SqliteDatabaseSnapshot.requireSourceUsable(normalized);
        Files.createDirectories(scratch);
        if(!Files.isDirectory(scratch, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("Migration scratch location is not a directory: " + scratch);
        }
        long requiredSpace = safeAdd(safeMultiply(sqliteFootprint(normalized), 2), FREE_SPACE_MARGIN_BYTES);
        ensureFreeSpace(scratch, requiredSpace);
        Path previewDirectory = Files.createTempDirectory(scratch, ".sdrtrunk-migration-approval-");
        Path previewDatabase = previewDirectory.resolve(SdrTrunkDatabasePath.DATABASE_FILENAME);
        Throwable primaryFailure = null;
        try
        {
            FileAccessAttributeSnapshot.restrictPrivateDirectory(previewDirectory);
            SqliteDatabaseSnapshot.createExternal(normalized, previewDatabase);
            return readPrivateApprovedMigrationPlan(previewDatabase);
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
                deleteTreeIfExists(previewDirectory);
            }
            catch(IOException cleanupFailure)
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

    /** Reads an application-owned standalone snapshot without making another full-size copy. */
    private static DatabaseMigrationChain.PreflightReport readPrivateMigrationPlan(Path database)
        throws IOException, SQLException
    {
        Path normalized = database.toAbsolutePath().normalize();
        SqliteDatabaseSnapshot.requireSourceUsable(normalized);
        try(Connection connection = openReadOnly(normalized))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
            requireSourceQuickCheck(connection);
            return DatabaseMigrationChain.planForApplicationMigration(connection, source);
        }
    }

    private static ApprovedMigrationPlan readPrivateApprovedMigrationPlan(Path database)
        throws IOException, SQLException
    {
        return readPrivateApprovedMigrationPlan(database, true);
    }

    private static ApprovedMigrationPlan readPrivateApprovedMigrationPlan(Path database, boolean canonicalSnapshot)
        throws IOException, SQLException
    {
        DatabaseMigrationChain.PreflightReport plan = readPrivateMigrationPlan(database);
        if(canonicalSnapshot)
        {
            return new ApprovedMigrationPlan(plan, SqliteDatabaseSnapshot.sha256(database));
        }

        Path normalized = database.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if(parent == null)
        {
            throw new IOException("The private SQLite snapshot has no parent: " + normalized);
        }
        Path canonical = parent.resolve("." + normalized.getFileName() + ".approval-content-" + UUID.randomUUID());
        Throwable primaryFailure = null;
        try
        {
            //Injected snapshotters may make byte copies instead of normalized SQLite online snapshots.
            SqliteDatabaseSnapshot.create(normalized, canonical);
            return new ApprovedMigrationPlan(plan, SqliteDatabaseSnapshot.sha256(canonical));
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
                deleteDatabaseAndSidecarsIfExists(canonical);
            }
            catch(IOException cleanupFailure)
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

    /** User-facing, value-free description of the ordered migration and its declared effects. */
    public static String describePlan(DatabaseMigrationChain.PreflightReport plan)
    {
        if(plan.steps().isEmpty())
        {
            return "No schema, format, or repair changes are required; a full-profile import may still remap " +
                "eligible stored paths.";
        }

        StringBuilder description = new StringBuilder("format ")
            .append(plan.source().version()).append(" [").append(plan.source().id()).append("] to format ")
            .append(plan.target().version()).append(" [").append(plan.target().id()).append("]");

        for(DatabaseMigrationChain.StepPreflight step: plan.steps())
        {
            description.append("\n\n").append(step.description());

            for(DatabaseMigrationEffect effect: step.effects())
            {
                description.append("\n  - ").append(effect.kind().name().toLowerCase()).append(' ')
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
            "portable-profile import; usable neighboring artifacts are attempted independently and stored paths " +
                "may be remapped." :
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
            ReceiverActivitySchema.validate(connection);
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

    private static void requireApprovedPlan(ApprovedMigrationPlan expected, ApprovedMigrationPlan actual,
                                            String snapshot) throws IOException
    {
        if(expected != null && (!expected.plan().equals(actual.plan()) ||
            !expected.sourceContentSha256().equals(actual.sourceContentSha256())))
        {
            throw new IOException("The " + snapshot + " does not match the migration plan and database content " +
                "approved by the operator. Its database content may have changed or the file may have been " +
                "replaced; close the previous application, review the source again, and retry.");
        }
    }

    private static void validateVault(Path vault) throws IOException, SQLException
    {
        if(Files.isSymbolicLink(vault) || !Files.isRegularFile(vault, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("The optional encryption-key vault snapshot is not a regular file.");
        }

        try(Connection connection = openReadOnly(vault))
        {
            EncryptionKeyVaultSchema.validate(connection);
            requireIntegrity(connection);
            requireForeignKeysValid(connection);
        }
        requireNoSidecars(vault, "The optional encryption-key vault snapshot remained active.");
    }

    private List<String> copyOptionalProfileData(Path sourceRoot, Path stageRoot) throws IOException
    {
        List<String> statuses = new ArrayList<>(3);
        statuses.add(copyOptionalDirectory(sourceRoot.resolve(JMBE_DIRECTORY), stageRoot.resolve(JMBE_DIRECTORY),
            "JMBE library files", "Select the JMBE library again after setup."));
        statuses.add(copyOptionalDirectory(sourceRoot.resolve(MODULES_DIRECTORY),
            stageRoot.resolve(MODULES_DIRECTORY), "optional decoder module files",
            "Select or reinstall the optional decoder modules again after setup."));
        statuses.add(copyOptionalVault(sourceRoot, stageRoot));
        return List.copyOf(statuses);
    }

    private String copyOptionalVault(Path sourceRoot, Path stageRoot) throws IOException
    {
        Path sourceDirectory = sourceRoot.resolve(EncryptionKeyVaultPath.VAULT_DIRECTORY);
        Path destinationDirectory = stageRoot.resolve(EncryptionKeyVaultPath.VAULT_DIRECTORY);
        String label = "encryption-key vault";

        if(!Files.exists(sourceDirectory, LinkOption.NOFOLLOW_LINKS))
        {
            return optionalMissing(label);
        }

        if(Files.isSymbolicLink(sourceDirectory) ||
            !Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(sourceDirectory))
        {
            return optionalSkipped(label, "Set up encrypted credentials or keys again after setup.");
        }

        Path sourceVault = EncryptionKeyVaultPath.getVaultPath(sourceRoot);

        if(!Files.exists(sourceVault, LinkOption.NOFOLLOW_LINKS))
        {
            return optionalMissing(label);
        }

        if(Files.isSymbolicLink(sourceVault) || !Files.isRegularFile(sourceVault, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isReadable(sourceVault))
        {
            return optionalSkipped(label, "Set up encrypted credentials or keys again after setup.");
        }

        try
        {
            Path destinationVault = EncryptionKeyVaultPath.getVaultPath(stageRoot);
            mExternalSnapshotter.create(sourceVault, destinationVault);
            FileAccessAttributeSnapshot.restrictSensitiveFile(destinationVault);
            validateVault(destinationVault);
            FileAccessAttributeSnapshot.restrictSensitiveFile(destinationVault);
            return optionalCopied(label);
        }
        catch(IOException | SQLException | RuntimeException failure)
        {
            discardOptionalComponent(destinationDirectory, failure);
            return optionalSkipped(label, "Set up encrypted credentials or keys again after setup.");
        }
    }

    private static String copyOptionalDirectory(Path source, Path destination, String label, String recovery)
        throws IOException
    {
        if(!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
        {
            return optionalMissing(label);
        }

        if(Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isReadable(source))
        {
            return optionalSkipped(label, recovery);
        }

        try
        {
            copyDirectory(source, destination);
            return optionalCopied(label);
        }
        catch(IOException | RuntimeException failure)
        {
            discardOptionalComponent(destination, failure);
            return optionalSkipped(label, recovery);
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
                if(Files.isSymbolicLink(directory) || !attributes.isDirectory() || !Files.isReadable(directory))
                {
                    throw new IOException("An optional profile directory could not be copied safely.");
                }

                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                if(Files.isSymbolicLink(file) || !attributes.isRegularFile() || !Files.isReadable(file))
                {
                    throw new IOException("An optional profile file could not be copied safely.");
                }

                Files.copy(file, destination.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void discardOptionalComponent(Path destination, Throwable originalFailure) throws IOException
    {
        try
        {
            deleteTreeIfExists(destination);
        }
        catch(IOException cleanupFailure)
        {
            cleanupFailure.addSuppressed(originalFailure);
            throw new IOException("An incomplete optional profile component could not be removed safely.",
                cleanupFailure);
        }
    }

    private static String appendOptionalProfileReport(String helperOutput, List<String> statuses)
    {
        String optionalReport = OPTIONAL_PROFILE_REPORT_HEADER + '\n' + String.join("\n", statuses);
        return helperOutput == null || helperOutput.isBlank() ? optionalReport :
            helperOutput.stripTrailing() + "\n\n" + optionalReport;
    }

    private static String optionalCopied(String label)
    {
        return "- " + label + ": copied.";
    }

    private static String optionalMissing(String label)
    {
        return "- " + label + ": not present in the selected profile.";
    }

    private static String optionalSkipped(String label, String recovery)
    {
        //Only fixed labels and recovery guidance reach the completion report. Never include a path, filename, or
        //exception message from an optional source component here.
        return "- " + label + ": WARNING - skipped because it could not be imported safely. " + recovery;
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

    private void validatePromotedDatabaseOrRestore(Path database, Path backup,
                                                   FileAccessAttributeSnapshot accessAttributes)
        throws IOException, SQLException
    {
        validatePromotedDatabaseOrRestore(database, backup, accessAttributes,
            ApplicationMigrationService::validateGlobalDatabase);
    }

    private void validatePromotedDatabaseOrRestore(Path database, Path backup,
                                                   FileAccessAttributeSnapshot accessAttributes,
                                                   DatabaseValidator restoredDatabaseValidator)
        throws IOException, SQLException
    {
        try
        {
            mPromotedDatabaseValidator.validate(database);
            accessAttributes.applyTo(database);
        }
        catch(IOException | SQLException | RuntimeException | Error validationFailure)
        {
            try
            {
                restoreBackup(backup, database, accessAttributes, restoredDatabaseValidator);
            }
            catch(IOException | SQLException | RuntimeException | Error restoreFailure)
            {
                throw new LiveDatabaseRecoveryException(database, backup, validationFailure, restoreFailure);
            }
            throw validationFailure;
        }
    }

    private static void restoreBackup(Path backup, Path database, FileAccessAttributeSnapshot accessAttributes,
                                      DatabaseValidator restoredDatabaseValidator)
        throws IOException, SQLException
    {
        Path restore = database.resolveSibling("." + database.getFileName() + ".restore-" + UUID.randomUUID());
        Throwable primaryFailure = null;
        try
        {
            Files.copy(backup, restore);
            accessAttributes.applyTo(restore);
            requireNoSidecars(database, "The migrated database is still active, so its safety backup cannot be " +
                "restored automatically.");
            moveAtomicallyReplacing(restore, database);
            restoredDatabaseValidator.validate(database);
            accessAttributes.applyTo(database);
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
                Files.deleteIfExists(restore);
            }
            catch(IOException cleanupFailure)
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

    /**
     * An in-place rollback restores the exact supported source rather than a current-format database. Validate its
     * physical integrity and bind it to both the reviewed source plan and the published backup's complete content.
     */
    private static void validateRestoredMigrationSource(Path database, RestoredDatabaseExpectation expected)
        throws IOException, SQLException
    {
        String actualContentSha256 = SqliteDatabaseSnapshot.sha256(database);
        if(!expected.contentSha256().equals(actualContentSha256))
        {
            throw new IOException("The restored database content does not match the retained in-place migration " +
                "safety backup.");
        }

        DatabaseMigrationChain.PreflightReport actualPlan = readPrivateMigrationPlan(database);
        if(!expected.plan().equals(actualPlan))
        {
            throw new IOException("The restored database does not match the approved in-place migration source " +
                "plan.");
        }

        try(Connection connection = openReadOnly(database); Statement statement = connection.createStatement())
        {
            //The migration chain explicitly admits repairable row CHECK violations. Verify full physical integrity
            //without reclassifying that approved source data as an unsafe rollback.
            statement.execute("PRAGMA ignore_check_constraints=ON");
            requireIntegrity(connection);
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
        return SqliteDatabaseSnapshot.openImmutable(database);
    }

    /** Reads an application-owned live database and therefore honors committed WAL content. */
    private static Connection openLiveReadOnly(Path database) throws SQLException
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

    private static void requireQuickCheck(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA quick_check"))
        {
            boolean result = false;

            while(resultSet.next())
            {
                result = true;

                if(!"ok".equalsIgnoreCase(resultSet.getString(1)))
                {
                    throw new SQLException("SQLite quick check failed: " + resultSet.getString(1));
                }
            }

            if(!result)
            {
                throw new SQLException("SQLite quick check returned no result.");
            }
        }
    }

    /** Keeps the source scan physical while allowing staged repair of recoverable row CHECK violations. */
    private static void requireSourceQuickCheck(Connection connection) throws SQLException
    {
        SQLException failure = null;
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            try
            {
                requireQuickCheck(connection);
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

    private static long requiredImportSpace(Path sourceDatabase) throws IOException
    {
        //The source remains untouched while the staged snapshot may need a rollback journal/WAL of comparable size.
        return safeAdd(safeMultiply(sqliteFootprint(sourceDatabase), 2), FREE_SPACE_MARGIN_BYTES);
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

        if(Files.exists(targetDatabase, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(physicalSource, targetDatabase))
        {
            throw new IOException("The previous and current SQLite database paths are the same physical file.");
        }

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

    @FunctionalInterface
    interface DatabaseValidator
    {
        void validate(Path database) throws IOException, SQLException;
    }

    private record RestoredDatabaseExpectation(DatabaseMigrationChain.PreflightReport plan, String contentSha256)
    {
        private RestoredDatabaseExpectation
        {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(contentSha256);
        }
    }

    /** Signals that neither the promoted database nor automatic restoration could be confirmed safe. */
    public static final class LiveDatabaseRecoveryException extends IOException
    {
        private final Path mDatabase;
        private final Path mSafetyBackup;

        private LiveDatabaseRecoveryException(Path database, Path safetyBackup, Throwable validationFailure,
                                              Throwable restoreFailure)
        {
            super("The updated database could not be validated, and the previous database could not be restored " +
                "automatically. Do not retry the migration or start SDRTrunk with this database. Restore the " +
                "retained safety backup manually: " + safetyBackup, validationFailure);
            mDatabase = database;
            mSafetyBackup = safetyBackup;
            addSuppressed(restoreFailure);
        }

        public Path database()
        {
            return mDatabase;
        }

        public Path safetyBackup()
        {
            return mSafetyBackup;
        }
    }

    /**
     * Opaque operator approval bound to both the reviewed plan and the complete content of its recovered SQLite
     * snapshot. Instances are created only by {@link #readMigrationApproval(Path)}.
     */
    public static final class ApprovedMigrationPlan
    {
        private final DatabaseMigrationChain.PreflightReport mPlan;
        private final String mSourceContentSha256;

        private ApprovedMigrationPlan(DatabaseMigrationChain.PreflightReport plan, String sourceContentSha256)
        {
            mPlan = Objects.requireNonNull(plan);
            mSourceContentSha256 = Objects.requireNonNull(sourceContentSha256);
        }

        public DatabaseMigrationChain.PreflightReport plan()
        {
            return mPlan;
        }

        private String sourceContentSha256()
        {
            return mSourceContentSha256;
        }
    }

    public record MigrationResult(boolean importedPreviousProfile, Path safetyBackup,
                                  DatabaseMigrationChain.PreflightReport sourcePlan,
                                  String helperOutput, PreviousBuildLocator.InputScope inputScope)
    {
        public DatabaseFormatCatalog.DetectedFormat sourceFormat()
        {
            return sourcePlan.source();
        }

        public boolean completedWithRepairsOrSkippedItems()
        {
            return helperOutput != null &&
                (helperOutput.contains("OUTCOME: Migration completed with ") ||
                    helperOutput.contains("WARNING - skipped because it could not be imported safely"));
        }
    }
}
