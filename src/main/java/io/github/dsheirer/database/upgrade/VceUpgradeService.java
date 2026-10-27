/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.InitialAdminSetup;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.gui.setup.SetupProgress;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.portable.PortableFileIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Graphical, manually backed-up upgrades of stopped VCE profiles. Settings-only preparation reads configuration
 * into a small private database; it never snapshots, hashes, counts, deletes or checks the discarded history.
 * Complete portable profiles move as one directory on the same filesystem. Strict nonmutating external imports
 * retain their separate ownership contract in ApplicationMigrationService.
 */
public final class VceUpgradeService
{
    private static final ObjectMapper REPORT_JSON = new ObjectMapper();
    private static final int MAX_INTENT_BYTES = 2 * 1024 * 1024;
    public record Result(ApplicationMigrationService.MigrationResult migration, PortableDataRootLock lock) {}
    private VceUpgradeService() {}
    @FunctionalInterface interface PhaseObserver { void phase(String phase) throws IOException; }
    private static final ThreadLocal<PhaseObserver> PHASE_OBSERVER = new ThreadLocal<>();
    static AutoCloseable observePhases(PhaseObserver observer)
    {
        PHASE_OBSERVER.set(observer); return PHASE_OBSERVER::remove;
    }

    public static boolean isPending(Path target)
    {
        return Files.exists(intentPath(target), LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(PortableProfileTransfer.receiptPath(target), LinkOption.NOFOLLOW_LINKS);
    }

    /** Bounded source review after the user's backup/history choices; no snapshots or history scans. */
    public static DatabaseMigrationChain.PreflightReport inspect(Path sourceRoot) throws IOException, SQLException
    {
        Path source = sourceRoot.toAbsolutePath().normalize();
        requireProfile(source);
        try(var lock = PortableDataRootLock.acquire(source))
        {
            return ApplicationMigrationService.readStartupPlan(SdrTrunkDatabasePath.getDatabasePath(source));
        }
    }

    public static Result upgrade(Path sourceRoot, Path targetRoot, MigrationContent content, boolean backupConfirmed,
                                 PortableDataRootLock existingLock, ApplicationMigrationService.ProgressListener progress)
        throws IOException, SQLException, InterruptedException
    {
        if(!backupConfirmed) throw new IOException("Back up the existing VCE installation and confirm the backup before upgrading.");
        Path source = sourceRoot.toAbsolutePath().normalize(), target = targetRoot.toAbsolutePath().normalize();
        if(isPending(target)) throw new IOException("Finish the earlier VCE upgrade before starting another.");
        return execute(source, target, content, existingLock, progress, null);
    }

    public static Result resume(Path targetRoot, PortableDataRootLock existingLock,
                                ApplicationMigrationService.ProgressListener progress) throws IOException, SQLException, InterruptedException
    {
        Path target = targetRoot.toAbsolutePath().normalize();
        Properties intent = readIntent(target);
        return execute(Path.of(intent.getProperty("source")), target,
            MigrationContent.valueOf(intent.getProperty("content")), existingLock, progress, intent);
    }

    private static Result execute(Path source, Path target, MigrationContent content, PortableDataRootLock existingLock,
                                  ApplicationMigrationService.ProgressListener observer, Properties intent)
        throws IOException, SQLException, InterruptedException
    {
        boolean transferred = !source.equals(target), newIntent = intent == null;
        ApplicationMigrationService.ProgressListener progress = observer == null ? ignored -> {} : observer;
        PortableDataRootLock lock = existingLock;
        boolean acquired = false, returned = false, ownsIntent = false;
        PortableProfileTransfer.Transfer transfer = null;
        MigrationReportSession report = new MigrationReportSession(target,
            SdrTrunkDatabasePath.getDatabasePath(source), "Upgrade VCE installation", content, "");
        progress = report.progress(progress);
        try
        {
            if(lock == null)
            {
                Path owner = transferred && !newIntent && Files.exists(PortableProfileTransfer.receiptPath(target)) ?
                    PortableProfileTransfer.lockRoot(target) : (newIntent || "PREPARING".equals(intent.getProperty("phase")) ? source : target);
                lock = PortableDataRootLock.acquire(owner);
                acquired = true;
            }
            if(newIntent)
            {
                requireProfile(source);
                if(!lock.getDataRoot().equals(source)) throw new IOException("The VCE source profile is not locked.");
                lock.rebindMovedRoot(source);
                Path work = target.resolveSibling("." + target.getFileName() + ".migration-" + UUID.randomUUID());
                Files.createDirectory(work);
                FileAccessAttributeSnapshot.restrictPrivateDirectory(work);
                intent = new Properties();
                intent.setProperty("version", "1");
                intent.setProperty("source", source.toString());
                intent.setProperty("sourceDirectory", identity(source));
                intent.setProperty("target", target.toString());
                intent.setProperty("work", work.toString());
                intent.setProperty("content", content.name());
                intent.setProperty("phase", "PREPARING");
                createIntent(target, intent);
                ownsIntent = true;
                notifyPhase(intent);
                if(transferred) transfer = PortableProfileTransfer.prepare(source, target, lock);
            }
            else
            {
                Properties currentIntent = readIntent(target);
                for(String key: List.of("source", "target", "work", "content", "sourceDirectory"))
                    if(!java.util.Objects.equals(intent.getProperty(key), currentIntent.getProperty(key)))
                        throw new IOException("The pending VCE upgrade changed. Preserve both installations and review it again.");
                intent = currentIntent;
                Path owner = transferred && Files.exists(PortableProfileTransfer.receiptPath(target)) ?
                    PortableProfileTransfer.lockRoot(target) : ("PREPARING".equals(intent.getProperty("phase")) ? source : target);
                if(!lock.getDataRoot().equals(owner) || !identity(owner).equals(intent.getProperty("sourceDirectory")))
                    throw new IOException("The pending VCE profile is not the original locked folder.");
                lock.rebindMovedRoot(owner);
                if(transferred && Files.exists(PortableProfileTransfer.receiptPath(target)))
                {
                    transfer = PortableProfileTransfer.resume(target, lock);
                    if(!source.equals(transfer.sourceRoot()) || !target.equals(transfer.targetRoot()))
                    {
                        transfer = null;
                        throw new IOException("The VCE transfer and settings records disagree. Preserve both installations.");
                    }
                    ownsIntent = true;
                }
                else if(transferred && "PREPARING".equals(intent.getProperty("phase")))
                {
                    ownsIntent = true;
                    transfer = PortableProfileTransfer.prepare(source, target, lock);
                }
                else if(transferred && !"COMMITTED".equals(intent.getProperty("phase")))
                    throw new IOException("The VCE transfer record is missing. Preserve both installations.");
                else ownsIntent = true;
            }

            Path work = validatedWork(target, intent);
            Path prepared = SdrTrunkDatabasePath.getDatabasePath(work);
            String phase = intent.getProperty("phase");
            SavedPlan savedPlan = savedSourcePlan(intent);
            DatabaseMigrationChain.PreflightReport sourcePlan = savedPlan.plan();
            String details = intent.getProperty("helperReport", "");
            boolean interruptedFullReport = content == MigrationContent.FULL && details.isBlank() &&
                Boolean.parseBoolean(intent.getProperty("fullAttemptStarted", "false"));
            if("PREPARED".equals(phase) && content == MigrationContent.CONFIGURATION_ONLY &&
                (!transferred || PortableProfileTransfer.inspect(target).currentRoot().equals(source)) &&
                !sourceUnchanged(source, intent))
            {
                progress.update("The previous installation changed; preparing its latest settings");
                setPhase(target, intent, "PREPARING");
                phase = "PREPARING";
            }
            if("PREPARING".equals(phase))
            {
                // A prior interrupted preparation may have left private files, but never changed the source.
                // All work is in this process under the profile lock, so recovery has no orphan child writer.
                if(Files.exists(prepared)) deleteTree(work.resolve("database"));
                LegacyReceiverSettingsMigration.removeStage(work);
                progress.update("Checking the stopped VCE installation");
                requireSpace(target.getParent(), 5L * 1024 * 1024 * 1024);
                sourcePlan = ApplicationMigrationService.readStartupPlan(SdrTrunkDatabasePath.getDatabasePath(source))
                    .withContent(content);
                intent.setProperty("sourcePlan", REPORT_JSON.writeValueAsString(sourcePlan));
                intent.setProperty("sourceVersion", Integer.toString(sourcePlan.source().version()));
                intent.setProperty("sourceMarkerPresent", Boolean.toString(sourcePlan.source().markerPresent()));
                writeIntent(intentPath(target), intent);
                if(content == MigrationContent.FULL)
                {
                    long workspace = sourcePlan.source().version() == sourcePlan.target().version() ?
                        Math.multiplyExact(Files.size(SdrTrunkDatabasePath.getDatabasePath(source)), 4) :
                        ApplicationMigrationService.requiredOwnedMigrationWorkspace(SdrTrunkDatabasePath.getDatabasePath(source), content);
                    requireSpace(target.getParent(), Math.addExact(workspace, 5L * 1024 * 1024 * 1024));
                }
                if(content == MigrationContent.CONFIGURATION_ONLY)
                {
                    progress.update("Preparing settings without copying saved Activity");
                    ConfigurationOnlyDatabaseSnapshot.create(SdrTrunkDatabasePath.getDatabasePath(source), prepared,
                        progress::update);
                    var legacy = LegacyReceiverSettingsMigration.captureMissing(source, prepared, sourcePlan.source().version());
                    LegacyReceiverSettingsMigration.stage(legacy, work);
                    progress.update("Updating the prepared settings");
                    details = runPrepared(prepared, source, target, content);
                    LegacyReceiverSettingsMigration.removeStage(work);
                    if(transferred)
                    {
                        InitialAdminSetup.initializeNewProfile(prepared);
                        new SetupProgress(false, true).save(prepared);
                    }
                    finishSqlite(prepared);
                    finishSqlite(ActivityDatabasePath.forConfiguration(prepared));
                    SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(prepared);
                    finishSqlite(prepared);
                    finishSqlite(ActivityDatabasePath.forConfiguration(prepared));
                    long workspace = Math.multiplyExact(Math.addExact(Files.size(prepared),
                        Files.size(ActivityDatabasePath.forConfiguration(prepared))), 4);
                    requireSpace(target.getParent(), Math.addExact(workspace, 5L * 1024 * 1024 * 1024));
                    captureSourceState(source, intent);
                    intent.setProperty("preparedConfiguration", identity(prepared));
                    intent.setProperty("preparedActivity", identity(ActivityDatabasePath.forConfiguration(prepared)));
                    Files.writeString(work.resolve("report.txt"), details, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                intent.setProperty("phase", "PREPARED");
                saveIntent(target, intent);
                phase = "PREPARED";
            }
            boolean originalPlanKnown = sourcePlan != null;
            if(transferred && "PREPARED".equals(phase))
            {
                progress.update("Moving the complete VCE data folder, including recordings");
                transfer.move();
                lock.rebindMovedRoot(target);
            }
            if(transferred && !lock.getDataRoot().equals(target)) lock.rebindMovedRoot(target);
            if(transferred && "PREPARED".equals(phase) && content == MigrationContent.CONFIGURATION_ONLY &&
                !sourceUnchanged(target, intent))
                throw new IOException("The VCE settings changed during transfer. Close both installations and retry the upgrade.");
            Path database = SdrTrunkDatabasePath.getDatabasePath(target);
            if(!"COMMITTED".equals(phase))
            {
                if(transferred) transfer.beginActivation();
                if(content == MigrationContent.FULL)
                {
                    intent.setProperty("fullAttemptStarted", "true");
                    writeIntent(intentPath(target), intent);
                }
                if(content == MigrationContent.CONFIGURATION_ONLY)
                {
                    installSettings(target, work, intent, progress);
                    details = preparedReport(work, details);
                }
                else if(transferred)
                {
                    progress.update("Updating retained VCE settings and Activity");
                    details = ApplicationDatabaseMigrator.migrateTransferred(database, source, target, lock);
                    InitialAdminSetup.initializeNewProfile(database);
                    new SetupProgress(false, true).save(database);
                }
                else
                {
                    var result = new ApplicationMigrationService().migrateCurrent(target, false, MigrationContent.FULL, progress);
                    if(sourcePlan == null) sourcePlan = result.sourcePlan();
                    details = result.helperOutput();
                }
                if(interruptedFullReport && originalPlanKnown) details = interruptedReport(sourcePlan) + details;
                SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
                intent.setProperty("helperReport", details);
                intent.setProperty("phase", "COMMITTED");
                saveIntent(target, intent);
            }
            if(sourcePlan == null) sourcePlan = ApplicationMigrationService.readStartupPlan(database).withContent(content);
            if(content == MigrationContent.FULL)
            {
                if(originalPlanKnown && details.isBlank()) details = interruptedReport(sourcePlan);
                if(!originalPlanKnown)
                    details = "WARNING: The original source plan is unavailable. The database format reported by this retry " +
                        "describes the current profile. " + (details.isBlank() ?
                        "Exact completion counts from the earlier attempt are unavailable.\n" : "Any retained report follows.\n") + details;
            }
            if(!savedPlan.warning().isBlank()) details = savedPlan.warning() + "\n" + details;
            if(transfer != null && transfer.phase() != PortableProfileTransfer.Phase.COMMITTED) transfer.markCommitted();
            String completed = details + "\nBackup: operator confirmed a separate complete installation backup.\n" +
                (transferred ? "The complete portable data folder, including recordings and their catalog, was transferred.\n" : "") +
                (content == MigrationContent.CONFIGURATION_ONLY ?
                    "RESET saved Activity and statistics: excluded by your settings-only choice.\n" +
                    "Source history was not copied, counted or scanned.\n" : "");
            try
            {
                deleteTree(work);
                if(transfer != null) transfer.complete();
                removeIntent(target);
            }
            catch(IOException cleanup)
            {
                completed += "WARNING: The upgrade completed; some temporary files need cleanup on the next launch. Do not repeat conversion.\n";
            }
            var result = report.completed(new ApplicationMigrationService.MigrationResult(transferred, null, sourcePlan,
                completed, PreviousBuildLocator.InputScope.PORTABLE_PROFILE));
            returned = true;
            return new Result(result, lock);
        }
        catch(IOException | SQLException | InterruptedException | RuntimeException failure)
        {
            // Keep receipts and both original/prepared database bundles. Recovery resumes the exact phase and
            // never moves an activated database back beneath an older application.
            boolean ownsTarget = ownsIntent && !transferred;
            if(ownsIntent && transferred && transfer != null)
            {
                try
                {
                    if(target.equals(PortableProfileTransfer.inspect(target).currentRoot()))
                    {
                        lock.rebindMovedRoot(target);
                        ownsTarget = true;
                    }
                }
                catch(IOException unsafeReceipt) { failure.addSuppressed(unsafeReceipt); }
            }
            report.failed(failure, ownsTarget);
            if(ownsIntent && transfer != null && (transfer.phase() == PortableProfileTransfer.Phase.PREPARED ||
                transfer.phase() == PortableProfileTransfer.Phase.MOVED))
            {
                try
                {
                    transfer.restoreBeforeActivation();
                    if(intent != null) deleteTree(validatedWork(target, intent));
                    removeIntent(target);
                }
                catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            if(ownsIntent && transferred && transfer == null && !Files.exists(PortableProfileTransfer.receiptPath(target)) &&
                intent != null && "PREPARING".equals(intent.getProperty("phase")))
            {
                try { deleteTree(validatedWork(target, intent)); removeIntent(target); }
                catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            if(newIntent && !ownsIntent && intent != null)
            {
                try { deleteTree(validatedWork(target, intent)); }
                catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        finally
        {
            if(acquired && !returned && lock != null) lock.close();
        }
    }

    private static void installSettings(Path target, Path work, Properties intent,
                                        ApplicationMigrationService.ProgressListener progress) throws IOException, SQLException
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(target);
        Path prepared = SdrTrunkDatabasePath.getDatabasePath(work);
        Path original = work.resolve("original").resolve(SdrTrunkDatabasePath.DATABASE_FILENAME);
        Files.createDirectories(original.getParent());
        String phase = intent.getProperty("phase");
        if("PREPARED".equals(phase))
        {
            // Checkpointing can change the original main/WAL even when no settings change. Persist ownership
            // of that housekeeping first, so a retry cannot mistake our own work for a changed source.
            setPhase(target, intent, "SAVE_CONFIGURATION"); phase = "SAVE_CONFIGURATION";
        }
        progress.update("Installing the prepared settings with empty Activity");
        if("SAVE_CONFIGURATION".equals(phase))
        {
            if(Files.exists(database, LinkOption.NOFOLLOW_LINKS)) finishSqlite(database);
            moveBundle(database, original);
            setPhase(target, intent, "SAVE_ACTIVITY"); phase = "SAVE_ACTIVITY";
        }
        if("SAVE_ACTIVITY".equals(phase))
        {
            if(Files.exists(ActivityDatabasePath.forConfiguration(database), LinkOption.NOFOLLOW_LINKS))
                finishSqlite(ActivityDatabasePath.forConfiguration(database));
            moveBundle(ActivityDatabasePath.forConfiguration(database), ActivityDatabasePath.forConfiguration(original));
            setPhase(target, intent, "INSTALL_CONFIGURATION"); phase = "INSTALL_CONFIGURATION";
        }
        if("INSTALL_CONFIGURATION".equals(phase))
        {
            installOne(prepared, database, intent.getProperty("preparedConfiguration"));
            setPhase(target, intent, "INSTALL_ACTIVITY"); phase = "INSTALL_ACTIVITY";
        }
        if("INSTALL_ACTIVITY".equals(phase))
        {
            installOne(ActivityDatabasePath.forConfiguration(prepared), ActivityDatabasePath.forConfiguration(database),
                intent.getProperty("preparedActivity"));
            setPhase(target, intent, "VALIDATING");
        }
    }

    private static void installOne(Path from, Path to, String expectedIdentity) throws IOException
    {
        if(Files.exists(from, LinkOption.NOFOLLOW_LINKS))
        {
            if(Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException("The database destination became occupied. Preserve the pending upgrade.");
            if(!identity(from).equals(expectedIdentity)) throw new IOException("The prepared VCE database changed.");
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }
        if(!Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS) || !identity(to).equals(expectedIdentity))
            throw new IOException("The prepared database cannot be identified. Preserve the pending upgrade.");
    }

    private static void moveBundle(Path from, Path to) throws IOException
    {
        for(String suffix: List.of("", "-wal", "-shm", "-journal"))
        {
            Path source = Path.of(from + suffix), destination = Path.of(to + suffix);
            if(Files.exists(source, LinkOption.NOFOLLOW_LINKS))
            {
                if(Files.isSymbolicLink(source) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("The original database bundle changed. Preserve the pending upgrade.");
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    private static String runPrepared(Path database, Path source, Path target, MigrationContent content) throws IOException
    {
        return runMigrator(new String[]{database.toString(), source.toString(), target.toString(),
            "--complete-profile-copy", "--configuration-only"});
    }

    private static String preparedReport(Path work, String cached)
    {
        Path file = work.resolve("report.txt");
        try
        {
            if(!Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(file) <= MAX_INTENT_BYTES)
            {
                String report = Files.readString(file);
                if(!report.isBlank()) return report;
            }
        }
        catch(IOException | SecurityException unavailableReport) { /* Optional reporting cannot block pair activation. */ }
        return cached.isBlank() ?
            "WARNING: The earlier detailed settings update report is unavailable. Exact completion counts are unavailable.\n" :
            "WARNING: The temporary settings update report is unavailable. The saved completion report follows.\n" + cached;
    }

    private static String runMigrator(String[] arguments) throws IOException
    {
        try(ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8))
        {
            int status = ApplicationDatabaseMigrator.run(arguments, output, output);
            String report = bytes.toString(StandardCharsets.UTF_8);
            if(status != ApplicationDatabaseMigrator.EXIT_SUCCESS)
                throw new IOException("The VCE settings update failed. Correct the problem and finish the pending upgrade.\n" + report);
            return report;
        }
    }

    private static void finishSqlite(Path file) throws IOException, SQLException
    {
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The stopped VCE database is missing or linked. Preserve the pending upgrade.");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var query = connection.createStatement())
        {
            query.execute("PRAGMA busy_timeout=10000");
            try(var rows = query.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(rows.next() && rows.getInt(1) != 0) throw new IOException("Close the other VCE instance before upgrading.");
            }
            try(var rows = query.executeQuery("PRAGMA journal_mode=DELETE"))
            {
                if(!rows.next() || !"delete".equalsIgnoreCase(rows.getString(1)))
                    throw new IOException("The stopped VCE database could not be finalized.");
            }
        }
    }

    private static final List<String> SOURCE_FILES = List.of("database/sdrtrunk.sqlite", "database/sdrtrunk.sqlite-wal",
        "database/sdrtrunk.sqlite-journal", "remote-links/settings.json", "audio-analysis/paging-tones.json");

    private static void captureSourceState(Path source, Properties intent) throws IOException
    {
        for(int index = 0; index < SOURCE_FILES.size(); index++)
            intent.setProperty("sourceState." + index, sourceState(source.resolve(SOURCE_FILES.get(index))));
    }

    private static boolean sourceUnchanged(Path source, Properties intent) throws IOException
    {
        for(int index = 0; index < SOURCE_FILES.size(); index++)
            if(!sourceState(source.resolve(SOURCE_FILES.get(index))).equals(intent.getProperty("sourceState." + index))) return false;
        return true;
    }

    private static String sourceState(Path file) throws IOException
    {
        if(!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return "absent";
        if(Files.isSymbolicLink(file)) throw new IOException("A saved VCE input became a symbolic link.");
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return PortableFileIdentity.read(file) + ":" + attributes.size() + ":" + attributes.lastModifiedTime();
    }

    private static void requireSpace(Path directory, long required) throws IOException
    {
        long available = Files.getFileStore(directory).getUsableSpace();
        if(available < required) throw new IOException("Free more disk space before upgrading VCE. The data folder has not been moved.");
    }

    private static void requireProfile(Path root) throws IOException
    {
        for(Path path: List.of(root, root.resolve("database"), SdrTrunkDatabasePath.getDatabasePath(root),
            root.resolve("recordings"), root.resolve("recordings-managed")))
            if(Files.isSymbolicLink(path)) throw new IOException("Resolve linked VCE data folders before upgrading.");
        if(!Files.isRegularFile(SdrTrunkDatabasePath.getDatabasePath(root), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("No VCE settings were found in the selected data folder.");
    }

    private static Path intentPath(Path root)
    {
        Path target = root.toAbsolutePath().normalize();
        return target.resolveSibling("." + target.getFileName() + ".vce-upgrade").resolve("intent.properties");
    }

    /** Reporting data never authorizes a migration; the database is still inspected under its owned lock. */
    private record SavedPlan(DatabaseMigrationChain.PreflightReport plan, String warning) {}

    private static SavedPlan savedSourcePlan(Properties intent)
    {
        String document = intent.getProperty("sourcePlan");
        if(document == null && intent.getProperty("sourceVersion") == null) return new SavedPlan(null, "");
        try
        {
            var plan = REPORT_JSON.readValue(document, DatabaseMigrationChain.PreflightReport.class);
            if(plan.content() != MigrationContent.valueOf(intent.getProperty("content")) ||
                !DatabaseFormatCatalog.requireVersion(plan.source().version()).equals(plan.source().descriptor()) ||
                !DatabaseFormatCatalog.requireVersion(plan.target().version()).equals(plan.target()))
                throw new IllegalArgumentException();
            return new SavedPlan(plan, "");
        }
        catch(IOException | SQLException | RuntimeException unavailableReport)
        {
            // Optional report corruption cannot prevent receipt- and database-validated recovery.
            String warning = "WARNING: The original detailed update plan is unavailable. " +
                "Exact completed-step counts cannot be recovered from that plan.";
            try
            {
                int version = Integer.parseInt(intent.getProperty("sourceVersion"));
                String marker = intent.getProperty("sourceMarkerPresent");
                if(!"true".equals(marker) && !"false".equals(marker)) throw new IllegalArgumentException();
                var source = new DatabaseFormatCatalog.DetectedFormat(DatabaseFormatCatalog.requireVersion(version),
                    Boolean.parseBoolean(marker));
                var plan = DatabaseMigrationChain.plan(source)
                    .withContent(MigrationContent.valueOf(intent.getProperty("content")));
                return new SavedPlan(plan, warning + " The planned changes for the original version are shown instead.");
            }
            catch(SQLException | RuntimeException unavailableSource)
            {
                return new SavedPlan(null, warning + " The original source format is also unavailable; " +
                    "the database format shown by this retry describes the current profile.");
            }
        }
    }

    private static String interruptedReport(DatabaseMigrationChain.PreflightReport original)
    {
        StringBuilder report = new StringBuilder("WARNING: An earlier attempt stopped before its completion report was saved. " +
            "Its exact completed-step counts are unavailable. The original planned changes are retained below.\n");
        report.append("Original migration plan: format ").append(original.source().version()).append(" [")
            .append(original.source().id()).append("] -> format ").append(original.target().version()).append(" [")
            .append(original.target().id()).append("]\n");
        original.steps().stream().flatMap(step -> step.effects().stream())
            .filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.RESET ||
                effect.kind() == DatabaseMigrationEffect.Kind.DROP || effect.kind() == DatabaseMigrationEffect.Kind.DEFAULT)
            .map(effect -> "Planned " + effect.kind() + ": " + effect.subject() + ". " + effect.detail())
            .distinct().forEach(line -> report.append(line).append('\n'));
        return report.append("Any report below describes the present retry.\n\n").toString();
    }

    private static Properties readIntent(Path target) throws IOException
    {
        Path file = intentPath(target);
        if(Files.isSymbolicLink(file.getParent()) || Files.isSymbolicLink(file) ||
            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_INTENT_BYTES)
            throw new IOException("The pending VCE upgrade record is unavailable. Preserve both installations.");
        Properties intent = new Properties();
        try(var input = Files.newInputStream(file)) { intent.load(input); }
        try
        {
            if(!"1".equals(intent.getProperty("version")) || !target.toString().equals(intent.getProperty("target")))
                throw new IllegalArgumentException();
            Path.of(intent.getProperty("source"));
            MigrationContent.valueOf(intent.getProperty("content"));
            if(!List.of("PREPARING", "PREPARED", "SAVE_CONFIGURATION", "SAVE_ACTIVITY", "INSTALL_CONFIGURATION",
                "INSTALL_ACTIVITY", "VALIDATING", "COMMITTED").contains(intent.getProperty("phase"))) throw new IllegalArgumentException();
            validatedWork(target, intent);
            return intent;
        }
        catch(IllegalArgumentException | NullPointerException failure)
        {
            throw new IOException("The pending VCE upgrade record is invalid. Preserve both installations.", failure);
        }
    }

    private static Path validatedWork(Path target, Properties intent) throws IOException
    {
        Path work = Path.of(intent.getProperty("work")).toAbsolutePath().normalize();
        String prefix = "." + target.getFileName() + ".migration-";
        if(!work.getParent().equals(target.getParent()) || !work.getFileName().toString().startsWith(prefix) ||
            Files.isSymbolicLink(work)) throw new IOException("The pending VCE workspace is unsafe.");
        try { UUID.fromString(work.getFileName().toString().substring(prefix.length())); }
        catch(IllegalArgumentException failure) { throw new IOException("The pending VCE workspace is invalid.", failure); }
        return work;
    }

    private static void setPhase(Path target, Properties intent, String phase) throws IOException
    {
        intent.setProperty("phase", phase); saveIntent(target, intent);
    }

    /** A nonempty directory can be atomically published without replacing another upgrade's claim. */
    private static void createIntent(Path target, Properties intent) throws IOException
    {
        Path directory = intentPath(target).getParent();
        if(Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
        {
            if(Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The VCE upgrade destination is already reserved.");
            try(var files = Files.list(directory))
            {
                if(files.findAny().isPresent()) throw new IOException("Another VCE upgrade already owns this destination.");
            }
            Files.delete(directory); // Empty directory left after completed receipt cleanup only.
        }
        Path pending = Files.createTempDirectory(directory.getParent(), ".vce-upgrade-intent-");
        try
        {
            FileAccessAttributeSnapshot.restrictPrivateDirectory(pending);
            writeIntent(pending.resolve("intent.properties"), intent);
            var observer = PHASE_OBSERVER.get();
            if(observer != null) observer.phase("CLAIMING");
            Files.move(pending, directory, StandardCopyOption.ATOMIC_MOVE);
            try(FileChannel parent = FileChannel.open(directory.getParent(), StandardOpenOption.READ)) { parent.force(true); }
            catch(UnsupportedOperationException | IOException unsupportedDirectorySync) { /* Windows lacks directory fsync. */ }
        }
        finally { if(Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) deleteTree(pending); }
    }

    private static void saveIntent(Path target, Properties intent) throws IOException
    {
        writeIntent(intentPath(target), intent);
        notifyPhase(intent);
    }

    private static void notifyPhase(Properties intent) throws IOException
    {
        var observer = PHASE_OBSERVER.get();
        if(observer != null) observer.phase(intent.getProperty("phase"));
    }

    private static void writeIntent(Path path, Properties intent) throws IOException
    {
        Path temporary = path.resolveSibling(path.getFileName() + ".writing-" + UUID.randomUUID());
        if(Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent()))
            throw new IOException("The VCE upgrade record is unsafe. Preserve the pending upgrade.");
        byte[] bytes;
        try(ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            intent.store(output, "VCE upgrade activation"); bytes = output.toByteArray();
        }
        if(bytes.length > MAX_INTENT_BYTES) throw new IOException("The VCE update report exceeds its safe limit. Preserve the pending upgrade.");
        try
        {
            try(FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
            {
                FileAccessAttributeSnapshot.restrictSensitiveFile(temporary);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try(FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            catch(UnsupportedOperationException | IOException unsupportedDirectorySync) { /* Windows lacks directory fsync. */ }
        }
        finally { Files.deleteIfExists(temporary); }
    }

    private static void removeIntent(Path target) throws IOException
    {
        Path intent = intentPath(target), directory = intent.getParent();
        if(!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if(Files.isSymbolicLink(directory)) throw new IOException("The VCE upgrade record is unsafe.");
        try(var entries = Files.list(directory))
        {
            for(Path entry: entries.toList())
            {
                if(entry.equals(intent)) continue;
                String prefix = "intent.properties.writing-";
                if(!entry.getFileName().toString().startsWith(prefix) || Files.isSymbolicLink(entry))
                    throw new IOException("Unexpected files remain in the VCE upgrade record folder.");
                try { UUID.fromString(entry.getFileName().toString().substring(prefix.length())); }
                catch(IllegalArgumentException invalid) { throw new IOException("An unknown upgrade file was preserved.", invalid); }
                Files.delete(entry);
            }
        }
        Files.deleteIfExists(intent);
        Files.delete(directory);
    }

    private static String identity(Path file) throws IOException
    {
        if(Files.isSymbolicLink(file)) throw new IOException("The VCE database cannot be a symbolic link.");
        String key = PortableFileIdentity.read(file);
        if(key == null) throw new IOException("This filesystem cannot identify transferred VCE databases safely.");
        return key.toString();
    }

    private static void deleteTree(Path root) throws IOException
    {
        if(!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if(Files.isSymbolicLink(root)) throw new IOException("The VCE upgrade workspace cannot be a symbolic link.");
        try(var entries = Files.walk(root))
        {
            for(Path entry: entries.sorted(java.util.Comparator.reverseOrder()).toList())
            {
                if(Files.isSymbolicLink(entry)) throw new IOException("An unexpected link was preserved in the VCE upgrade workspace.");
                Files.delete(entry);
            }
        }
    }
}
