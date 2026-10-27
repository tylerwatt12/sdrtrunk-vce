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
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
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
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConfig;

/**
 * Updates an inactive current profile or stages and promotes explicitly selected external data.
 *
 * <p>Current-profile updates use one optional recovery snapshot and one transaction in the bundled Application
 * Migrator. External imports retain their private staged child process and source approval. Both advance through
 * the same adjacent migration chain; normal database services remain validation-only.</p>
 */
public final class ApplicationMigrationService
{
    private static final long FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String JMBE_DIRECTORY = "jmbe";
    private static final String MODULES_DIRECTORY = "modules";
    private static final String PROFILE_REPORT_HEADER = "Profile files:";
    private static final int SQLITE_PROGRESS_VM_CALLS = 1_000;
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    private final Snapshotter mExternalSnapshotter;
    private final Snapshotter mLiveSnapshotter;
    private final MigrationRunner mMigrationRunner;
    private final StagePromoter mStagePromoter;
    private final DatabaseValidator mPromotedDatabaseValidator;
    private final boolean mExternalSnapshotsAreCanonical;
    private final boolean mLiveSnapshotsAreCanonical;

    public ApplicationMigrationService()
    {
        this(externalSnapshotter(), SqliteDatabaseSnapshot::create,
            ApplicationMigratorLauncher::run,
            ApplicationMigrationService::moveAtomically, ApplicationMigrationService::validatePromotedDatabase,
            true, true);
    }

    ApplicationMigrationService(MigrationRunner migrationRunner)
    {
        this(externalSnapshotter(), SqliteDatabaseSnapshot::create, migrationRunner,
            ApplicationMigrationService::moveAtomically, ApplicationMigrationService::validatePromotedDatabase,
            true, true);
    }

    ApplicationMigrationService(Snapshotter snapshotter, MigrationRunner migrationRunner)
    {
        this(snapshotter, snapshotter, migrationRunner, ApplicationMigrationService::moveAtomically,
            ApplicationMigrationService::validatePromotedDatabase, false, false);
    }

    ApplicationMigrationService(Snapshotter snapshotter, MigrationRunner migrationRunner,
                                StagePromoter stagePromoter)
    {
        this(snapshotter, snapshotter, migrationRunner, stagePromoter,
            ApplicationMigrationService::validatePromotedDatabase, false, false);
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
        Objects.requireNonNull(source, "Previous-build input cannot be null");
        MigrationReportSession report = new MigrationReportSession(targetDataRoot, source.database(),
            "Import previous profile", approvedPlan == null ? MigrationContent.FULL : approvedPlan.content(),
            approvedPlan == null ? "" : approvedPlan.mReviewTimings);
        try
        {
            return report.completed(importPreviousInternal(source, targetDataRoot, approvedPlan, report.progress(progress)));
        }
        catch(IOException | SQLException | InterruptedException | RuntimeException | Error failure)
        {
            report.failed(failure, false);
            throw failure;
        }
    }

    private MigrationResult importPreviousInternal(PreviousBuildLocator.Selection source, Path targetDataRoot,
                                          ApprovedMigrationPlan approvedPlan, ProgressListener progress)
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
            readMigrationApproval(source, targetParent, listener);
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
            mExternalSnapshotter.create(sourceDatabase, stagedDatabase, listener);
            FileAccessAttributeSnapshot.restrictSensitiveFile(stagedDatabase);
            listener.update("Verifying the copied database matches source review");
            ApprovedMigrationPlan stagedPlan = verifyStagedApproval(expectedPlan, stagedDatabase,
                mExternalSnapshotsAreCanonical);
            DatabaseMigrationChain.PreflightReport sourcePlan = stagedPlan.plan();

            LegacyReceiverSettingsMigration.Snapshot legacySnapshot = null;
            if(inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE)
            {
                LegacyReceiverSettingsMigration.Snapshot actual = LegacyReceiverSettingsMigration.captureMissing(
                    sourceRoot, stagedDatabase, sourcePlan.source().version());
                legacySnapshot = expectedPlan.mLegacyReceiverSettings;
                if(legacySnapshot == null)
                {
                    if(!actual.values().isEmpty())
                        throw new IOException("Review the complete source folder again before importing its legacy receiver settings");
                    legacySnapshot = actual;
                }
                legacySnapshot.requireUnchanged(actual);
                LegacyReceiverSettingsMigration.stage(legacySnapshot, stageRoot);
            }

            listener.update("Updating database");
            String helperOutput = inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE ?
                mMigrationRunner.run(stagedDatabase, sourceRoot, targetRoot, expectedPlan.content()) :
                mMigrationRunner.run(stagedDatabase, null, null, expectedPlan.content());
            LegacyReceiverSettingsMigration.removeStage(stageRoot);

            listener.update("Checking updated data");
            InitialAdminSetup.initializeNewProfile(stagedDatabase);
            //A copied profile belongs to a new destination. Never inherit the source installation's completed wizard.
            new io.github.dsheirer.gui.setup.SetupProgress(false, true).save(stagedDatabase);
            validateGlobalDatabase(stagedDatabase);
            requireNoSidecars(stagedDatabase,
                "The staged database still has SQLite sidecar files and cannot be installed safely.");

            if(inputScope == PreviousBuildLocator.InputScope.PORTABLE_PROFILE)
            {
                listener.update("Copying profile files");
                List<String> statuses = copyProfileData(sourceRoot, stageRoot, listener);
                for(String status: statuses)
                {
                    listener.update(status.substring(2));
                }
                helperOutput = appendProfileReport(helperOutput, statuses);
            }

            listener.update("Finishing");
            if(legacySnapshot != null)
                legacySnapshot.requireUnchanged(LegacyReceiverSettingsMigration.capture(sourceRoot,
                    legacySnapshot.fingerprints().keySet()));
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
        MigrationReportSession report = new MigrationReportSession(targetDataRoot, sourceDatabase,
            "Replace application database", approvedPlan == null ? MigrationContent.FULL : approvedPlan.content(),
            approvedPlan == null ? "" : approvedPlan.mReviewTimings);
        try
        {
            return report.completed(replaceCurrentDatabaseInternal(sourceDatabase, targetDataRoot, approvedPlan,
                report.progress(progress)));
        }
        catch(IOException | SQLException | InterruptedException | RuntimeException | Error failure)
        {
            report.failed(failure, false);
            throw failure;
        }
    }

    private MigrationResult replaceCurrentDatabaseInternal(Path sourceDatabase, Path targetDataRoot,
                                                  ApprovedMigrationPlan approvedPlan, ProgressListener progress)
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
            readMigrationApproval(source, target.getParent(), listener);
        listener.update("Migration plan: " + describePlan(expectedPlan.plan()));
        listener.update("Migration scope: configuration and saved activity are imported together. Other source " +
            "files are not copied, and the current portable files outside the databases remain in place.");
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

        MigrationResult completedResult = null;
        Throwable primaryFailure = null;
        try
        {
            listener.update("Copying selected SQLite database");
            mExternalSnapshotter.create(source, staged, listener);
            FileAccessAttributeSnapshot.restrictSensitiveFile(staged);
            listener.update("Verifying the copied database matches source review");
            ApprovedMigrationPlan stagedPlan = verifyStagedApproval(expectedPlan, staged, mExternalSnapshotsAreCanonical);
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
            moveDatabasePair(stagedBackup, backup, false);

            listener.update("Updating selected database copy");
            String helperOutput = mMigrationRunner.run(staged, null, null, expectedPlan.content());
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
            promoteAndValidateDatabaseOrRestore(staged, target, backup, targetAttributes);

            completedResult = new MigrationResult(false, backup, sourcePlan, helperOutput,
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
                else if(completedResult != null)
                {
                    completedResult = withCleanupWarning(completedResult);
                }
                else
                {
                    throw cleanupFailure;
                }
            }
        }
        return Objects.requireNonNull(completedResult, "Database replacement did not complete");
    }

    /** Updates the inactive current profile, retaining one recovery backup by default. */
    public MigrationResult migrateCurrent(Path dataRoot, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        return migrateCurrent(dataRoot, true, progress);
    }

    /**
     * Uses the same adjacent migration transaction with or without a recovery backup. The caller holds the portable
     * data-root lock and has not started receivers or database services. A requested snapshot is taken under the
     * transaction's writer reservation, before any changes, and includes committed WAL data.
     */
    public MigrationResult migrateCurrent(Path dataRoot, boolean createBackup, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        return migrateCurrent(dataRoot, createBackup, Map.of(), MigrationContent.FULL, progress);
    }

    public MigrationResult migrateCurrent(Path dataRoot, boolean createBackup, MigrationContent content,
                                           ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        return migrateCurrent(dataRoot, createBackup, Map.of(), content, progress);
    }

    /** Returns only labels' stable keys; callers must explicitly offer recovery, never infer user intent from defaults. */
    public static Set<String> availableRecoveryKeys(Path dataRoot) throws IOException, SQLException
    {
        return LegacyReceiverSettingsMigration.availableRecoveryKeys(dataRoot.toAbsolutePath().normalize());
    }

    /** Explicit recovery is limited to selected legacy settings whose reviewed SQLite defaults remain unchanged. */
    public MigrationResult migrateCurrent(Path dataRoot, boolean createBackup,
        Map<String,String> expectedLegacyDefaults, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        return migrateCurrent(dataRoot, createBackup, expectedLegacyDefaults, MigrationContent.FULL, progress);
    }

    public MigrationResult migrateCurrent(Path dataRoot, boolean createBackup,
        Map<String,String> expectedLegacyDefaults, MigrationContent content, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        MigrationReportSession report = new MigrationReportSession(dataRoot,
            SdrTrunkDatabasePath.getDatabasePath(dataRoot.toAbsolutePath().normalize()),
            "Update current profile; recovery backup " + (createBackup ? "selected" : "skipped"), content, "");
        try
        {
            return report.completed(migrateCurrentInternal(dataRoot, createBackup, expectedLegacyDefaults,
                content, report.progress(progress)));
        }
        catch(IOException | SQLException | InterruptedException | RuntimeException | Error failure)
        {
            report.failed(failure);
            throw failure;
        }
    }

    private MigrationResult migrateCurrentInternal(Path dataRoot, boolean createBackup,
        Map<String,String> expectedLegacyDefaults, MigrationContent content, ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        Objects.requireNonNull(content, "Migration contents cannot be null");
        Map<String,String> recovery = Map.copyOf(Objects.requireNonNull(expectedLegacyDefaults));
        ProgressListener listener = progress == null ? ignored -> { } : progress;
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot.toAbsolutePath().normalize());
        boolean completingSplit=Files.exists(ActivityDatabasePath.splitReceipt(database));
        ApplicationDatabaseMigrator.recoverSplitTransition(database);
        SqliteDatabaseSnapshot.requireSourceUsable(database);
        FileAccessAttributeSnapshot attributes = FileAccessAttributeSnapshot.capture(database);
        //The live transaction needs rollback space; only the selected recovery copy adds another footprint.
        DatabaseMigrationChain.PreflightReport ownedPlan=readStartupPlan(database);
        if(completingSplit && content==MigrationContent.CONFIGURATION_ONLY && recovery.isEmpty() &&
            ownedPlan.source().version()==DatabaseFormatCatalog.CURRENT_VERSION && !ownedPlan.requiresMigration())
        {
            SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
            var recoveredPlan=new DatabaseMigrationChain.PreflightReport(ownedPlan.source(),ownedPlan.target(),
                List.of(),MigrationContent.CONFIGURATION_ONLY);
            return new MigrationResult(false,null,recoveredPlan,
                "RESULT: Interrupted configuration-only update activation complete. History was not reset again.",
                PreviousBuildLocator.InputScope.DATABASE_FILE);
        }
        if(ownedPlan.requiresMigration() || !recovery.isEmpty())
        {
            long workspace = requiredOwnedMigrationWorkspace(database, content, ownedPlan);
            ensureFreeSpace(database.getParent(), safeAdd(safeAdd(workspace,
                createBackup ? sqliteFootprint(database) : 0), 5L * 1024 * 1024 * 1024));
        }
        String identity = BACKUP_TIME.format(LocalDateTime.now()) + "-" +
            UUID.randomUUID().toString().substring(0, 8);
        Path backupDirectory = database.getParent().resolve("backups");
        Path backup = backupDirectory.resolve("sdrtrunk-before-application-migration-" + identity + ".sqlite");
        Path incomplete = database.resolveSibling("." + backup.getFileName() + ".incomplete-" + UUID.randomUUID());
        AtomicBoolean backedUp = new AtomicBoolean();
        MigrationResult completedResult = null;
        Throwable primaryFailure = null;
        try
        {
            ApplicationDatabaseMigrator.InPlaceMigrationResult result =
                ApplicationDatabaseMigrator.migrateInPlace(database, listener::update, createBackup ? () ->
                {
                    listener.update("Creating recovery backup");
                    Files.createDirectories(backupDirectory);
                    mLiveSnapshotter.create(database, incomplete);
                    attributes.applyTo(incomplete);
                    requireNoSidecars(incomplete, "The recovery backup must be a standalone SQLite snapshot.");
                    moveDatabasePair(incomplete, backup, false);
                    backedUp.set(true);
                } : null, recovery, content);
            String backupReport = backedUp.get() ? "Recovery backup retained: " + backup :
                createBackup ? "No recovery backup was needed; the database was already current." :
                    "WARNING: Recovery backup skipped by operator choice.";
            completedResult = new MigrationResult(false, backedUp.get() ? backup : null, result.sourcePlan(),
                result.helperOutput() + System.lineSeparator() + backupReport,
                PreviousBuildLocator.InputScope.DATABASE_FILE);
        }
        catch(IOException | SQLException | RuntimeException | Error failure)
        {
            primaryFailure = failure;
            if(backedUp.get()) failure.addSuppressed(new IOException("Recovery backup retained: " + backup));
            throw failure;
        }
        finally
        {
            try
            {
                deleteDatabaseAndSidecarsIfExists(incomplete);
            }
            catch(IOException cleanupFailure)
            {
                if(primaryFailure != null)
                {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
                else if(completedResult != null)
                {
                    completedResult = withCleanupWarning(completedResult);
                }
                else
                {
                    throw cleanupFailure;
                }
            }
        }
        return Objects.requireNonNull(completedResult, "Database migration did not complete");
    }

    /**
     * Additional workspace for migrating an application-owned database, after exact source admission. This excludes
     * any profile copy, selected backup and free-space reserve, which the caller must budget separately. Format 62's
     * full migration keeps detailed history pages in place and budgets only the compact structures it changes.
     * The exact full-content 63-to-64 plan changes configuration only and leaves its paired activity file untouched.
     */
    public static long requiredOwnedMigrationWorkspace(Path database, MigrationContent content)
        throws IOException, SQLException
    {
        Objects.requireNonNull(content, "Migration contents cannot be null");
        Path normalized = Objects.requireNonNull(database, "Database cannot be null").toAbsolutePath().normalize();
        return requiredOwnedMigrationWorkspace(normalized, content, readStartupPlan(normalized));
    }

    private static long requiredOwnedMigrationWorkspace(Path database, MigrationContent content,
        DatabaseMigrationChain.PreflightReport plan) throws IOException, SQLException
    {
        if(content == MigrationContent.FULL)
        {
            if(plan.source().version() == 62) return splitWorkspace(database);
            if(plan.source().version() == 63 && plan.target().version() == 64)
                return sqliteFileFootprint(database);
        }
        return sqliteFootprint(database);
    }

    /** Format 62 keeps detailed event pages in their original file; only compact owners and indexes change. */
    private static long splitWorkspace(Path database) throws IOException,SQLException
    {
        try(Connection connection=openLiveReadOnly(database);Statement query=connection.createStatement();
            ResultSet rows=query.executeQuery("""
                SELECT coalesce(sum(pgsize),0) FROM dbstat WHERE name IN (
                    SELECT name FROM sqlite_master WHERE tbl_name IN (
                        'receiver_channel','radio_system','alias','alias_activity_summary',
                        'alias_list_analog_activity_summary','trunked_radio_affiliation',
                        'trunked_radio_channel_presence_clear','p25_wuid_assignment_observation_summary',
                        'alias_list','alias_p25_subscriber_identity','scan_list','alias_scan_list_membership',
                        'alias_list_unmatched_talkgroup_scan_list_membership','alias_list_new_alias_scan_list_membership',
                        'alias_broadcast_channel','alias_list_unmatched_talkgroup_stream','alias_list_new_alias_stream',
                        'configuration_channel','configuration_broadcast_stream','application_settings','application_icons',
                        'web_user','web_user_tour_dismissal','web_access_policy','p25_subscriber_identity',
                        'topic_alert_topic','topic_alert_rule','topic_alert_device','topic_alert_membership',
                        'topic_alert_enrollment','topic_alert_enrollment_topic','topic_alert_message','topic_alert_delivery'))
                """))
        {
            return safeMultiply(rows.next() ? rows.getLong(1) : 0,4);
        }
    }

    private static MigrationResult withCleanupWarning(MigrationResult result)
    {
        String warning = "WARNING: Temporary migration files could not be removed after installation. " +
            "The update is complete; do not repeat it.";
        return new MigrationResult(result.importedPreviousProfile(), result.safetyBackup(), result.sourcePlan(),
            Objects.toString(result.helperOutput(), "") + System.lineSeparator() + warning, result.inputScope());
    }

    /**
     * Inspects the inactive application-owned profile under its portable-data lock. SQLite honors committed WAL
     * content and may recover an interrupted rollback journal; this does not run a migration or a full-history scan.
     * Explicit external-source review continues to use its private immutable snapshot instead.
     */
    public static DatabaseMigrationChain.PreflightReport readStartupPlan(Path database)
        throws IOException, SQLException
    {
        Path normalized = database.toAbsolutePath().normalize();
        SqliteDatabaseSnapshot.requireSourceUsable(normalized);
        ApplicationDatabaseMigrator.requireSingleFilesystemLinkWhenSupported(normalized);
        boolean recoveryRequired = Files.isRegularFile(Path.of(normalized + "-journal"), LinkOption.NOFOLLOW_LINKS);
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(!recoveryRequired);
        config.setBusyTimeout(SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
        config.enforceForeignKeys(true);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized, config.toProperties()))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
            if(source.version()>=63) ConfigurationHistoryDatabase.validatePairIdentity(normalized);
            if(source.version() == DatabaseFormatCatalog.CURRENT_VERSION)
            {
                try
                {
                    return DatabaseMigrationChain.plan(DatabaseFormatCatalog.requireCurrent(connection, source));
                }
                catch(SQLException rejectedCurrent)
                {
                    //Inspect bounded repairable configuration only when ordinary current-format admission fails.
                }
            }
            return DatabaseMigrationChain.planInPlace(connection, source);
        }
    }

    /**
     * Fully inspects the application-owned database without making a full-size copy or hashing its content. Use
     * this for migration/import review; ordinary startup uses {@link #readStartupPlan(Path)} first. A caller that
     * asks the operator to approve a migration must use {@link #readMigrationApproval(Path)} instead.
     */
    public static DatabaseMigrationChain.PreflightReport readMigrationPlan(Path database)
        throws IOException, SQLException
    {
        Path normalized = database.toAbsolutePath().normalize();
        SqliteDatabaseSnapshot.requireSourceUsable(normalized);
        Path activity = io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(normalized);
        boolean committedWalPresent = false;
        for(Path file: List.of(normalized, activity))
        {
            if(!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            Path rollbackJournal = Path.of(file + "-journal");
            if(Files.isRegularFile(rollbackJournal, LinkOption.NOFOLLOW_LINKS))
            {
                // A hot journal in either profile role requires recovery only on a private copy.
                return readMigrationApproval(normalized).plan();
            }
            Path wal = Path.of(file + "-wal");
            committedWalPresent |= Files.isRegularFile(wal, LinkOption.NOFOLLOW_LINKS) && Files.size(wal) > 0;
        }
        try(Connection connection = committedWalPresent ? openLiveReadOnly(normalized) : openReadOnly(normalized))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
            requireConfigurationSource(connection,source);
            if(source.version()>=63) io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePairIdentity(normalized);
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

    /** Binds profile-owned legacy configuration files only when the operator selected the complete folder. */
    public static ApprovedMigrationPlan readMigrationApproval(PreviousBuildLocator.Selection source, Path scratchDirectory)
        throws IOException, SQLException
    {
        return readMigrationApproval(source, scratchDirectory, null);
    }

    private static ApprovedMigrationPlan readMigrationApproval(PreviousBuildLocator.Selection source, Path scratchDirectory,
                                                               ProgressListener progress)
        throws IOException, SQLException
    {
        Objects.requireNonNull(source);
        ApprovedMigrationPlan database = readMigrationApproval(source.database(), scratchDirectory, progress);
        if(!source.portableProfile()) return database;
        LegacyReceiverSettingsMigration.Snapshot legacy = LegacyReceiverSettingsMigration.captureMissing(
            source.path(), source.database(), database.plan().source().version());
        return new ApprovedMigrationPlan(database.plan(), database.sourceContentSha256(), legacy, database.mReviewTimings);
    }

    /** Creates approval scratch data beside the caller's selected destination rather than in the JVM temp volume. */
    public static ApprovedMigrationPlan readMigrationApproval(Path database, Path scratchDirectory)
        throws IOException, SQLException
    {
        return readMigrationApproval(database, scratchDirectory, null);
    }

    /** Creates approval scratch data while reporting honest whole-file phases to the setup UI. */
    public static ApprovedMigrationPlan readMigrationApproval(Path database, Path scratchDirectory,
                                                               ProgressListener progress)
        throws IOException, SQLException
    {
        return readMigrationApproval(database, scratchDirectory, progress, NEVER_CANCELLED);
    }

    /** Creates approval scratch data with cooperative cancellation and complete cleanup before cancellation returns. */
    public static ApprovedMigrationPlan readMigrationApproval(Path database, Path scratchDirectory,
                                                               ProgressListener progress,
                                                               BooleanSupplier cancellation)
        throws IOException, SQLException
    {
        Objects.requireNonNull(cancellation, "Migration approval cancellation cannot be null");
        requireNotCancelled(cancellation);
        ProgressListener listener = progress == null ? ignored -> { } : progress;
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
        ApprovedMigrationPlan approval = null;
        StringBuilder reviewTimings = new StringBuilder();
        java.util.function.Consumer<String> timingOutput = line -> {
            reviewTimings.append(line).append('\n');
            listener.update(line);
        };
        MigrationTimings timings = new MigrationTimings(timingOutput);
        Throwable primaryFailure = null;
        try
        {
            FileAccessAttributeSnapshot.restrictPrivateDirectory(previewDirectory);
            listener.update("Creating a temporary safety copy");
            requireNotCancelled(cancellation);
            try(var phase = timings.phase("Source review: capture and verify external snapshot"))
            {
                SqliteDatabaseSnapshot.createExternal(normalized, previewDatabase, cancellation, timingOutput);
                phase.complete();
            }
            listener.update("Checking the copied database");
            requireNotCancelled(cancellation);
            DatabaseMigrationChain.PreflightReport plan;
            try(var phase = timings.phase("Source review: schema, configuration and physical quick check"))
            {
                plan = readPrivateMigrationPlan(previewDatabase, cancellation);
                phase.complete();
            }
            listener.update("Fingerprinting the copied database");
            requireNotCancelled(cancellation);
            try(var phase = timings.phase("Source review: snapshot content fingerprint"))
            {
                approval = new ApprovedMigrationPlan(plan, SqliteDatabaseSnapshot.sha256(previewDatabase, cancellation));
                phase.complete();
            }
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
                listener.update("Cleaning temporary review files");
                try(var phase = timings.phase("Source review: remove temporary snapshot"))
                {
                    deleteTreeIfExists(previewDirectory);
                    phase.complete();
                }
            }
            catch(IOException cleanupFailure)
            {
                if(primaryFailure instanceof CancellationException)
                {
                    cleanupFailure.addSuppressed(primaryFailure);
                    throw cleanupFailure;
                }
                else if(primaryFailure != null)
                {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
                else
                {
                    throw cleanupFailure;
                }
            }
        }
        requireNotCancelled(cancellation);
        Objects.requireNonNull(approval, "Migration approval was not produced");
        return new ApprovedMigrationPlan(approval.plan(), approval.sourceContentSha256(),
            approval.mLegacyReceiverSettings, reviewTimings.toString());
    }

    /** Reads an application-owned standalone snapshot without making another full-size copy. */
    private static DatabaseMigrationChain.PreflightReport readPrivateMigrationPlan(Path database)
        throws IOException, SQLException
    {
        return readPrivateMigrationPlan(database, null);
    }

    /** Reads a private snapshot with SQLite VM cancellation for integrity and format-inspection scans. */
    private static DatabaseMigrationChain.PreflightReport readPrivateMigrationPlan(Path database,
                                                                                    BooleanSupplier cancellation)
        throws IOException, SQLException
    {
        requireNotCancelled(cancellation);
        Path normalized = database.toAbsolutePath().normalize();
        SqliteDatabaseSnapshot.requireSourceUsable(normalized);
        try(Connection connection = openReadOnly(normalized))
        {
            boolean handlerInstalled = false;
            AtomicBoolean cancellationInterruptedSql = new AtomicBoolean();
            Throwable primaryFailure = null;
            try
            {
                if(cancellation != null)
                {
                    ProgressHandler.setHandler(connection, SQLITE_PROGRESS_VM_CALLS, new ProgressHandler()
                    {
                        @Override
                        protected int progress()
                        {
                            if(cancellation.getAsBoolean())
                            {
                                cancellationInterruptedSql.set(true);
                                return 1;
                            }
                            return 0;
                        }
                    });
                    handlerInstalled = true;
                }

                requireNotCancelled(cancellation);
                SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
                requireNotCancelled(cancellation);
                DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
                requireConfigurationSource(connection,source);
                if(source.version()>=63) io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePairIdentity(normalized);
                requireNotCancelled(cancellation);
                requireSourceQuickCheck(connection);
                requireNotCancelled(cancellation);
                DatabaseMigrationChain.PreflightReport plan =
                    DatabaseMigrationChain.planForApplicationMigration(connection, source);
                requireNotCancelled(cancellation);
                return plan;
            }
            catch(SQLException failure)
            {
                if(cancellationInterruptedSql.get())
                {
                    CancellationException cancelled = cancellationException();
                    cancelled.initCause(failure);
                    primaryFailure = cancelled;
                    throw cancelled;
                }
                primaryFailure = failure;
                throw failure;
            }
            catch(RuntimeException | Error failure)
            {
                primaryFailure = failure;
                throw failure;
            }
            finally
            {
                if(handlerInstalled)
                {
                    try
                    {
                        ProgressHandler.clearHandler(connection);
                    }
                    catch(SQLException cleanupFailure)
                    {
                        if(primaryFailure instanceof CancellationException)
                        {
                            cleanupFailure.addSuppressed(primaryFailure);
                            throw cleanupFailure;
                        }
                        else if(primaryFailure != null)
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
    }

    private static void requireConfigurationSource(Connection connection,DatabaseFormatCatalog.DetectedFormat source)
        throws IOException,SQLException
    {
        if(source.version()>=63 && !io.github.dsheirer.database.ConfigurationHistoryDatabase.isPairedConfiguration(connection))
            throw new IOException("Select the configuration database. The activity database is its required companion and cannot be imported on its own.");
    }

    private static void requireNotCancelled(BooleanSupplier cancellation)
    {
        if(cancellation != null && cancellation.getAsBoolean())
        {
            throw cancellationException();
        }
    }

    private static CancellationException cancellationException()
    {
        return new CancellationException("Database safety review was skipped.");
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

        StringBuilder description = new StringBuilder("Contents: ").append(plan.content().displayName())
            .append("\n\nformat ")
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
            "configuration and saved activity only; neighboring vault, JMBE, module, and other profile files are " +
                "not copied, and stored paths are not remapped.";
    }

    private static void validateGlobalDatabase(Path database) throws IOException, SQLException
    {
        if(!Files.isRegularFile(database))
        {
            throw new IOException("Staged VCE SQLite database does not exist: " + database);
        }

        if(io.github.dsheirer.database.ConfigurationHistoryDatabase.isPairedConfiguration(database))
        {
            io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(database);
            for(Path file: List.of(database,io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database)))
            {
                try(Connection connection=openReadOnly(file))
                {
                    requireIntegrity(connection);requireForeignKeysValid(connection);
                    if(file.equals(io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(database)))
                        ReceiverActivitySchema.validateRetainedHistoryData(connection);
                }
            }
            ConfigurationSnapshotValidator.validateForStartup(new ConfigurationRepository(database).load());
            SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
            return;
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

        //The full staged checks above have already validated derived rows and physical integrity. Bounded startup
        //admission applies the runtime WAL settings without scanning that same retained history again.
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
    }

    /** Atomic promotion changes only the file location; the staged contents already passed full validation. */
    private static void validatePromotedDatabase(Path database) throws IOException, SQLException
    {
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
        ConfigurationSnapshotValidator.validateForStartup(new ConfigurationRepository(database).load());
    }

    /** A failed replacement restores its exact supported source; it need not already be the new current format. */
    private static void validateSupportedRestoredDatabase(Path database) throws IOException,SQLException
    {
        if(io.github.dsheirer.database.ConfigurationHistoryDatabase.isPairedConfiguration(database))
        {
            validateGlobalDatabase(database);
            return;
        }
        try(Connection connection=openReadOnly(database))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
            DatabaseFormatCatalog.DetectedFormat restored=DatabaseFormatCatalog.inspect(connection);
            requireIntegrity(connection);
            requireForeignKeysValid(connection);
            // These monolithic formats already have the complete channel/Alias configuration contract understood
            // by the current loader. Earlier registered formats retain their frozen catalog configuration checks
            // and require their supported adjacent update before this build can receive.
            if(restored.version()>=45)
                ConfigurationSnapshotValidator.validateForStartup(new ConfigurationRepository(database).load(connection));
        }
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

    /** A byte-identical canonical snapshot already received review admission; the child still validates it fully. */
    static ApprovedMigrationPlan verifyStagedApproval(ApprovedMigrationPlan expected, Path staged, boolean canonical)
        throws IOException, SQLException
    {
        if(canonical)
        {
            // Canonical backup header counters are normalized by sha256, exactly as during approval. This covers
            // both matching profile files. No schema/configuration/history inspection runs before equality.
            String digest = SqliteDatabaseSnapshot.sha256(staged);
            ApprovedMigrationPlan actual = new ApprovedMigrationPlan(expected.plan(), digest);
            requireApprovedPlan(expected, actual, "source database selected after confirmation");
            return expected;
        }
        ApprovedMigrationPlan actual = readPrivateApprovedMigrationPlan(staged, false).withContent(expected.content());
        requireApprovedPlan(expected, actual, "source database selected after confirmation");
        return actual;
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

    private List<String> copyProfileData(Path sourceRoot, Path stageRoot, ProgressListener listener) throws IOException
    {
        MigrationTimings timings = new MigrationTimings(listener::update);
        List<String> statuses = new ArrayList<>(3);
        try(var phase = timings.phase("Copy optional JMBE library files"))
        {
            statuses.add(copyOptionalDirectory(sourceRoot.resolve(JMBE_DIRECTORY), stageRoot.resolve(JMBE_DIRECTORY),
                "JMBE library files", "Select the JMBE library again after setup."));
            phase.complete();
        }
        try(var phase = timings.phase("Copy optional decoder module files"))
        {
            statuses.add(copyOptionalDirectory(sourceRoot.resolve(MODULES_DIRECTORY),
                stageRoot.resolve(MODULES_DIRECTORY), "optional decoder module files",
                "Select or reinstall the optional decoder modules again after setup."));
            phase.complete();
        }
        try(var phase = timings.phase("Copy and validate optional encryption-key vault"))
        {
            statuses.add(copyOptionalVault(sourceRoot, stageRoot));
            phase.complete();
        }
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

    private static String appendProfileReport(String helperOutput, List<String> statuses)
    {
        String report = PROFILE_REPORT_HEADER + '\n' + String.join("\n", statuses);
        return helperOutput == null || helperOutput.isBlank() ? report :
            helperOutput.stripTrailing() + "\n\n" + report;
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
        Path activity = ActivityDatabasePath.forConfiguration(database);
        if(Files.exists(activity)) prepareSingleLiveDatabaseForReplacement(activity);
        prepareSingleLiveDatabaseForReplacement(database);
    }

    private static void prepareSingleLiveDatabaseForReplacement(Path database) throws IOException, SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);

            try(ResultSet resultSet = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(!resultSet.next() || resultSet.getInt(1) != 0)
                {
                    throw new IOException("The current database is in use. Close every other VCE window " +
                        "and try again.");
                }
            }
        }

        Path wal = Path.of(database + "-wal");
        Path sharedMemory = Path.of(database + "-shm");
        Path rollbackJournal = Path.of(database + "-journal");

        if(Files.exists(wal) || Files.exists(sharedMemory) || Files.exists(rollbackJournal))
        {
            throw new IOException("The current database is still active. Close every other VCE window " +
                "and try again.");
        }
    }

    private static void finalizeStandaloneSnapshot(Path database) throws IOException, SQLException
    {
        Path activity = ActivityDatabasePath.forConfiguration(database);
        if(Files.exists(activity)) finalizeSingleStandaloneSnapshot(activity);
        finalizeSingleStandaloneSnapshot(database);
    }

    private static void finalizeSingleStandaloneSnapshot(Path database) throws IOException, SQLException
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
            ApplicationMigrationService::validateSupportedRestoredDatabase);
    }

    private void validatePromotedDatabaseOrRestore(Path database, Path backup,
                                                   FileAccessAttributeSnapshot accessAttributes,
                                                   DatabaseValidator restoredDatabaseValidator)
        throws IOException, SQLException
    {
        promoteAndValidateDatabaseOrRestore(null, database, backup, accessAttributes, restoredDatabaseValidator);
    }

    private void promoteAndValidateDatabaseOrRestore(Path staged, Path database, Path backup,
                                                    FileAccessAttributeSnapshot accessAttributes)
        throws IOException, SQLException
    {
        promoteAndValidateDatabaseOrRestore(staged, database, backup, accessAttributes,
            ApplicationMigrationService::validateSupportedRestoredDatabase);
    }

    private void promoteAndValidateDatabaseOrRestore(Path staged, Path database, Path backup,
                                                    FileAccessAttributeSnapshot accessAttributes,
                                                    DatabaseValidator restoredDatabaseValidator)
        throws IOException, SQLException
    {
        try
        {
            if(staged != null) moveDatabasePair(staged, database, true);
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
            SqliteDatabaseSnapshot.create(backup, restore);
            accessAttributes.applyTo(restore);
            requireNoSidecars(database, "The migrated database is still active, so its safety backup cannot be " +
                "restored automatically.");
            moveDatabasePair(restore, database, true);
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
                deleteDatabaseAndSidecarsIfExists(restore);
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

    /** Both files are quiescent and validated before promotion; the caller restores the retained pair on failure. */
    private static void moveDatabasePair(Path source, Path destination, boolean replace) throws IOException
    {
        Path sourceActivity = ActivityDatabasePath.forConfiguration(source);
        Path destinationActivity = ActivityDatabasePath.forConfiguration(destination);
        boolean companion = Files.exists(sourceActivity);
        if(companion)
        {
            if(replace) moveAtomicallyReplacing(sourceActivity, destinationActivity);
            else moveAtomically(sourceActivity, destinationActivity);
        }
        else if(replace) Files.deleteIfExists(destinationActivity);
        try
        {
            if(replace) moveAtomicallyReplacing(source, destination);
            else moveAtomically(source, destination);
        }
        catch(IOException failure)
        {
            if(companion && !replace)
            {
                try { moveAtomically(destinationActivity, sourceActivity); }
                catch(IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    private static void requireNoSidecars(Path database, String failureMessage) throws IOException
    {
        requireSingleNoSidecars(database, failureMessage);
        requireSingleNoSidecars(ActivityDatabasePath.forConfiguration(database), failureMessage);
    }

    private static void requireSingleNoSidecars(Path database, String failureMessage) throws IOException
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
        for(Path member: List.of(database, ActivityDatabasePath.forConfiguration(database)))
        for(String suffix: List.of("-journal", "-wal", "-shm", ""))
        {
            Path path = suffix.isEmpty() ? member : Path.of(member + suffix);
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
        long size = 0;
        for(Path member: List.of(database, ActivityDatabasePath.forConfiguration(database)))
        {
            size = safeAdd(size, sqliteFileFootprint(member));
        }
        return size;
    }

    private static long sqliteFileFootprint(Path database) throws IOException
    {
        if(!Files.isRegularFile(database)) return 0;
        long size = Files.size(database);
        for(String suffix : List.of("-wal", "-journal"))
        {
            Path sidecar = Path.of(database + suffix);
            if(Files.isRegularFile(sidecar)) size = safeAdd(size, Files.size(sidecar));
        }
        return size;
    }

    private static void ensureFreeSpace(Path targetDirectory, long required) throws IOException
    {
        FileStore store = Files.getFileStore(targetDirectory);
        long usable = store.getUsableSpace();

        if(usable < required)
        {
            throw new IOException("Not enough free space for the database update. Required approximately " +
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

        default void create(Path source, Path destination, ProgressListener diagnostics) throws IOException, SQLException
        {
            create(source, destination);
        }
    }

    private static Snapshotter externalSnapshotter()
    {
        return new Snapshotter()
        {
            @Override
            public void create(Path source, Path destination) throws IOException, SQLException
            {
                SqliteDatabaseSnapshot.createExternal(source, destination);
            }

            @Override
            public void create(Path source, Path destination, ProgressListener diagnostics) throws IOException, SQLException
            {
                SqliteDatabaseSnapshot.createExternal(source, destination, NEVER_CANCELLED, diagnostics::update);
            }
        };
    }

    @FunctionalInterface
    interface MigrationRunner
    {
        String run(Path stagedDatabase, Path sourceDataRoot, Path targetDataRoot, MigrationContent content)
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

    /** Signals that neither the promoted database nor automatic restoration could be confirmed safe. */
    public static final class LiveDatabaseRecoveryException extends IOException
    {
        private final Path mDatabase;
        private final Path mSafetyBackup;

        private LiveDatabaseRecoveryException(Path database, Path safetyBackup, Throwable validationFailure,
                                              Throwable restoreFailure)
        {
            super("The updated database could not be validated, and the previous database could not be restored " +
                "automatically. Do not retry the migration or start VCE with this database. Restore the " +
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
        private final LegacyReceiverSettingsMigration.Snapshot mLegacyReceiverSettings;
        private final String mReviewTimings;

        private ApprovedMigrationPlan(DatabaseMigrationChain.PreflightReport plan, String sourceContentSha256)
        {
            this(plan, sourceContentSha256, null);
        }

        private ApprovedMigrationPlan(DatabaseMigrationChain.PreflightReport plan, String sourceContentSha256,
                                     LegacyReceiverSettingsMigration.Snapshot legacyReceiverSettings)
        {
            this(plan, sourceContentSha256, legacyReceiverSettings, "");
        }

        private ApprovedMigrationPlan(DatabaseMigrationChain.PreflightReport plan, String sourceContentSha256,
                                     LegacyReceiverSettingsMigration.Snapshot legacyReceiverSettings, String reviewTimings)
        {
            mPlan = Objects.requireNonNull(plan);
            mSourceContentSha256 = Objects.requireNonNull(sourceContentSha256);
            mLegacyReceiverSettings = legacyReceiverSettings;
            mReviewTimings = reviewTimings;
        }

        public DatabaseMigrationChain.PreflightReport plan()
        {
            return mPlan;
        }

        public MigrationContent content()
        {
            return mPlan.content();
        }

        /** Rebinds only the declared contents; the reviewed source hash and legacy-settings snapshot stay intact. */
        public ApprovedMigrationPlan withContent(MigrationContent content)
        {
            return new ApprovedMigrationPlan(mPlan.withContent(content), mSourceContentSha256, mLegacyReceiverSettings,
                mReviewTimings);
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
                    helperOutput.contains("WARNING: Temporary migration files could not be removed") ||
                    helperOutput.contains("WARNING - skipped because it could not be imported safely"));
        }
    }
}
