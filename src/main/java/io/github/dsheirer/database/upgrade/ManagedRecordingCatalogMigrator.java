/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.record.managed.ManagedRecordingSchema;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.sqlite.SQLiteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit, pre-receiver upgrade of the separate Managed Recordings catalog. Normal catalog startup only validates
 * its format. The Application Migrator child changes the schema of a private staged copy; this class retains a
 * recoverable format-1 backup under database/backups and promotes the checked format-2 copy only after the child
 * has exited successfully.
 */
public final class ManagedRecordingCatalogMigrator
{
    private static final Logger LOG = LoggerFactory.getLogger(ManagedRecordingCatalogMigrator.class);
    private static final int LEGACY_FORMAT = 1;
    private static final int CURRENT_FORMAT = ManagedRecordingSchema.CURRENT_FORMAT_VERSION;
    private static final long FREE_SPACE_MARGIN = 16L * 1024L * 1024L;
    private static final Pattern STAGED_NAME = Pattern.compile(
        "^\\.managed-recordings\\.sqlite\\.migration-[0-9a-fA-F-]{36}$");
    private static final Map<String,String> ROW_ORDER = Map.of(
        "catalog_metadata", "id",
        "recording_system", "id",
        "recording_channel", "id",
        "recording_site", "id",
        "recording_system_site", "system_id,site_id",
        "recording_call", "id",
        "recording_call_site", "call_id,site_id",
        "recording_patch_member", "call_id,kind,local_id,home_wacn,home_system,home_id",
        "sqlite_sequence", "name");

    private ManagedRecordingCatalogMigrator() {}

    public enum State { ABSENT, CURRENT, UPGRADE_REQUIRED }

    public record Inspection(State state)
    {
        public boolean needsMigration()
        {
            return state == State.UPGRADE_REQUIRED;
        }
    }

    public record MigrationResult(boolean migrated, Path backup) {}

    /**
     * Classifies an existing catalog without changing its schema. Current-format startup uses a bounded read-only
     * schema/metadata check; an older format receives a full source-immutable snapshot and integrity check.
     */
    public static Inspection inspect(Path database) throws IOException, SQLException
    {
        Path source = normalized(database);
        if(!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
        {
            requireNoOrphanSidecars(source);
            return new Inspection(State.ABSENT);
        }
        SqliteDatabaseSnapshot.requireSourceUsable(source);
        if(!Files.exists(Path.of(source + "-journal"), LinkOption.NOFOLLOW_LINKS))
        {
            // Ordinary launches of a current catalog need only bounded exact-format admission. A format-1 file
            // receives the full source-immutable snapshot and integrity scan before approval is offered. A read-only
            // live connection sees committed WAL pages when the previous launch left a WAL sidecar.
            if(readBoundedState(source) == State.CURRENT)
            {
                return new Inspection(State.CURRENT);
            }
        }
        Path scratch = privateScratch(source);
        Throwable primaryFailure = null;
        try
        {
            Path snapshot = scratch.resolve("inspection.sqlite");
            SqliteDatabaseSnapshot.createExternal(source, snapshot);
            return inspectStandalone(snapshot);
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
                deletePrivateScratch(scratch);
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
     * Upgrades only after the caller has approved it and stopped all catalog users under the portable-data lock.
     * A no-op for an absent or already-current catalog. The returned backup remains in database/backups on success.
     */
    public static MigrationResult migrate(Path database) throws IOException, SQLException, InterruptedException
    {
        Path source = normalized(database);
        if(!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
        {
            requireNoOrphanSidecars(source);
            return new MigrationResult(false, null);
        }
        SqliteDatabaseSnapshot.requireSourceUsable(source);
        if(!Files.exists(Path.of(source + "-journal"), LinkOption.NOFOLLOW_LINKS) &&
            readBoundedState(source) == State.CURRENT)
        {
            return new MigrationResult(false, null);
        }
        requireSpace(source);
        SqliteDatabaseSnapshot.ExternalSourceState sourceState =
            SqliteDatabaseSnapshot.captureExternalSourceState(source);
        FileAccessAttributeSnapshot attributes = FileAccessAttributeSnapshot.capture(source);
        Path scratch = privateScratch(source);
        Path backup = source.getParent().resolve("backups").resolve(
            "managed-recordings-before-transcript-" + UUID.randomUUID() + ".sqlite");
        boolean backupCreated = false;
        boolean promoted = false;
        Throwable primaryFailure = null;
        try
        {
            Path snapshot = scratch.resolve("original.sqlite");
            SqliteDatabaseSnapshot.createExternal(source, snapshot);
            Inspection inspection = inspectStandalone(snapshot);
            SqliteDatabaseSnapshot.requireExternalSourceUnchanged(source, sourceState);
            if(!inspection.needsMigration())
            {
                return new MigrationResult(false, null);
            }

            Files.createDirectories(backup.getParent());
            Files.copy(snapshot, backup);
            backupCreated = true;
            FileAccessAttributeSnapshot.restrictSensitiveFile(backup);
            if(inspectStandalone(backup).state() != State.UPGRADE_REQUIRED ||
                !sameLogicalRows(snapshot, backup))
            {
                throw new IOException("The retained format-1 catalog backup did not validate.");
            }
            Path staged = scratch.resolve(".managed-recordings.sqlite.migration-" + UUID.randomUUID());
            Files.copy(snapshot, staged);
            FileAccessAttributeSnapshot.restrictSensitiveFile(staged);
            launchChild(staged);

            // A SQLite child may leave its private connection in WAL mode. Canonicalize it into one file before
            // validating and promoting it, so no staged WAL can be lost or mixed with source sidecars.
            Path promotable = scratch.resolve("promotable.sqlite");
            SqliteDatabaseSnapshot.create(staged, promotable);
            if(inspectStandalone(promotable).state() != State.CURRENT)
            {
                throw new SQLException("The staged managed recordings catalog did not reach format 2.");
            }
            attributes.applyTo(promotable);
            SqliteDatabaseSnapshot.requireExternalSourceUnchanged(source, sourceState);

            // Checkpointing changes physical SQLite files but leaves the original valid as format 1 if a crash
            // occurs before the atomic main-file replacement. The already retained backup includes committed WAL.
            checkpointOriginal(source);
            Path checkpointed = scratch.resolve("checkpointed.sqlite");
            SqliteDatabaseSnapshot.createExternal(source, checkpointed);
            if(inspectStandalone(checkpointed).state() != State.UPGRADE_REQUIRED ||
                !sameLogicalRows(snapshot, checkpointed))
            {
                throw new IOException("The managed recordings catalog changed during WAL checkpoint; " +
                    "its format-1 backup was retained and the upgrade was not installed.");
            }
            requireNoRecoverySidecars(source);
            try
            {
                Files.move(promotable, source, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            catch(AtomicMoveNotSupportedException exception)
            {
                throw new IOException("Atomic replacement is unavailable for the managed recordings catalog; " +
                    "the format-1 backup was retained.", exception);
            }
            promoted = true;
            return new MigrationResult(true, backup);
        }
        catch(IOException | SQLException | InterruptedException | RuntimeException | Error exception)
        {
            primaryFailure = exception;
            if(backupCreated)
            {
                exception.addSuppressed(new IOException("Recoverable format-1 catalog backup retained " +
                    "under the database backups directory."));
            }
            throw exception;
        }
        finally
        {
            try
            {
                deletePrivateScratch(scratch);
            }
            catch(IOException cleanupFailure)
            {
                if(primaryFailure != null)
                {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
                else if(promoted)
                {
                    LOG.warn("Managed recordings catalog was upgraded, but private scratch cleanup failed");
                }
                else
                {
                    throw cleanupFailure;
                }
            }
        }
    }

    /** ApplicationDatabaseMigrator CLI dispatch calls this only for a private, pattern-matched stage. */
    public static void runChild(Path stagedDatabase) throws IOException, SQLException
    {
        Path staged = normalized(stagedDatabase);
        Path parent = staged.getParent();
        if(parent == null || parent.getFileName() == null ||
            !parent.getFileName().toString().startsWith(".managed-recordings-migration-") ||
            !STAGED_NAME.matcher(staged.getFileName().toString()).matches() ||
            Files.isSymbolicLink(staged) || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("The managed recordings migrator accepts only a private staged catalog.");
        }
        if(inspectStandalone(staged).state() != State.UPGRADE_REQUIRED)
        {
            throw new SQLException("The staged managed recordings catalog is not exact format 1.");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + staged);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=10000");
            connection.setAutoCommit(false);
            try
            {
                statement.execute(ManagedRecordingSchema.ddlForFormat(CURRENT_FORMAT).get(
                    "recording_transcript"));
                statement.executeUpdate("UPDATE catalog_metadata SET format_version=" + CURRENT_FORMAT +
                    " WHERE id=1");
                statement.execute("PRAGMA user_version=" + CURRENT_FORMAT);
                requireExact(connection, CURRENT_FORMAT);
                requireIntegrity(connection);
                connection.commit();
            }
            catch(SQLException exception)
            {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Inspection inspectStandalone(Path standalone) throws SQLException
    {
        try(Connection connection = SqliteDatabaseSnapshot.openImmutable(standalone))
        {
            int version = pragmaInt(connection, "user_version");
            if(version != LEGACY_FORMAT && version != CURRENT_FORMAT)
            {
                throw new SQLException("Unsupported managed recordings catalog version " + version + ".");
            }
            requireExact(connection, version);
            requireIntegrity(connection);
            return new Inspection(version == LEGACY_FORMAT ? State.UPGRADE_REQUIRED : State.CURRENT);
        }
    }

    private static State readBoundedState(Path source) throws SQLException
    {
        try(Connection connection = hasWalSidecars(source) ? openLiveReadOnly(source) :
            SqliteDatabaseSnapshot.openImmutable(source))
        {
            int version = pragmaInt(connection, "user_version");
            if(version == CURRENT_FORMAT)
            {
                requireExact(connection, CURRENT_FORMAT);
                return State.CURRENT;
            }
            if(version == LEGACY_FORMAT)
            {
                return State.UPGRADE_REQUIRED;
            }
            throw new SQLException("Unsupported managed recordings catalog version " + version + ".");
        }
    }

    private static void requireExact(Connection connection, int version) throws SQLException
    {
        if(pragmaInt(connection, "application_id") != ManagedRecordingSchema.APPLICATION_ID ||
            pragmaInt(connection, "user_version") != version)
        {
            throw new SQLException("Unrecognized managed recordings catalog identity or version.");
        }
        Map<String,String> actual = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT name,sql FROM sqlite_master " +
                "WHERE type IN ('table','index','view','trigger') AND name NOT GLOB 'sqlite_*' " +
                "ORDER BY name"))
        {
            while(rows.next())
            {
                actual.put(rows.getString(1), rows.getString(2));
            }
        }
        if(!actual.equals(ManagedRecordingSchema.ddlForFormat(version)))
        {
            throw new SQLException("Managed recordings catalog schema is unknown or partially migrated.");
        }
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT format_version,call_count,total_bytes " +
                "FROM catalog_metadata WHERE id=1"))
        {
            if(!rows.next() || rows.getInt(1) != version || rows.getLong(2) < 0 ||
                rows.getLong(3) < 0 || rows.next())
            {
                throw new SQLException("Managed recordings catalog metadata is invalid.");
            }
        }
    }

    private static void requireIntegrity(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA integrity_check"))
        {
            if(!rows.next() || !"ok".equals(rows.getString(1)) || rows.next())
            {
                throw new SQLException("Managed recordings catalog failed SQLite integrity_check.");
            }
        }
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check"))
        {
            if(rows.next())
            {
                throw new SQLException("Managed recordings catalog contains broken foreign keys.");
            }
        }
    }

    private static int pragmaInt(Connection connection, String name) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA " + name))
        {
            if(!rows.next())
            {
                throw new SQLException("Missing SQLite " + name + " pragma.");
            }
            return rows.getInt(1);
        }
    }

    private static boolean sameLogicalRows(Path before, Path after) throws SQLException
    {
        try(Connection first = SqliteDatabaseSnapshot.openImmutable(before);
            Connection second = SqliteDatabaseSnapshot.openImmutable(after))
        {
            for(Map.Entry<String,String> entry: ROW_ORDER.entrySet())
            {
                String query = "SELECT * FROM " + entry.getKey() + " ORDER BY " + entry.getValue();
                try(Statement firstStatement = first.createStatement();
                    Statement secondStatement = second.createStatement();
                    ResultSet firstRows = firstStatement.executeQuery(query);
                    ResultSet secondRows = secondStatement.executeQuery(query))
                {
                    int columns = firstRows.getMetaData().getColumnCount();
                    while(true)
                    {
                        boolean firstHasRow = firstRows.next();
                        boolean secondHasRow = secondRows.next();
                        if(firstHasRow != secondHasRow)
                        {
                            return false;
                        }
                        if(!firstHasRow)
                        {
                            break;
                        }
                        for(int column = 1; column <= columns; column++)
                        {
                            if(!Objects.deepEquals(firstRows.getObject(column), secondRows.getObject(column)))
                            {
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        }
    }

    private static void checkpointOriginal(Path source) throws SQLException
    {
        // SQLite itself retires its WAL and shared-memory files when the last connection closes after switching
        // to DELETE mode. The migration must never delete live sidecars: another writer could have committed to a
        // WAL between a size check and deletion.
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            source.toUri().toASCIIString() + "?mode=rw");
            Statement statement = connection.createStatement())
        {
            try(ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(!result.next() || result.getInt(1) != 0)
                {
                    throw new SQLException("The managed recordings WAL is busy; close catalog users and retry.");
                }
            }
            try(ResultSet result = statement.executeQuery("PRAGMA journal_mode=DELETE"))
            {
                if(!result.next() || !"delete".equalsIgnoreCase(result.getString(1)))
                {
                    throw new SQLException("The managed recordings WAL could not be retired; " +
                        "close catalog users and retry.");
                }
            }
        }
    }

    private static Connection openLiveReadOnly(Path source) throws SQLException
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(10_000);
        config.enforceForeignKeys(true);
        return DriverManager.getConnection("jdbc:sqlite:" + source, config.toProperties());
    }

    private static void requireNoRecoverySidecars(Path source) throws IOException
    {
        for(String suffix: List.of("-wal", "-shm", "-journal"))
        {
            if(Files.exists(Path.of(source + suffix), LinkOption.NOFOLLOW_LINKS))
            {
                throw new IOException("Managed recordings SQLite recovery sidecars remain after checkpoint; " +
                    "close catalog users and retry.");
            }
        }
    }

    private static void launchChild(Path staged) throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>(ApplicationMigratorLauncher.command(staged));
        command.add(command.size() - 1, "--managed-recording-catalog");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return ApplicationMigratorLauncher.readBounded(child.getInputStream());
            }
            catch(IOException exception)
            {
                throw new CompletionException(exception);
            }
        });
        try
        {
            if(!child.waitFor(30, TimeUnit.MINUTES))
            {
                child.destroyForcibly();
                child.waitFor();
                throw new IOException("Managed recordings Application Migrator timed out.");
            }
        }
        catch(InterruptedException exception)
        {
            child.destroyForcibly();
            Thread.currentThread().interrupt();
            throw exception;
        }
        if(child.exitValue() != 0)
        {
            String details;
            try
            {
                details = new String(output.join(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            catch(CompletionException exception)
            {
                details = "Diagnostic output could not be read.";
            }
            throw new IOException("Managed recordings Application Migrator failed (exit " +
                child.exitValue() + "): " + details);
        }
        try
        {
            output.join();
        }
        catch(CompletionException exception)
        {
            if(exception.getCause() instanceof IOException ioException)
            {
                throw ioException;
            }
            throw exception;
        }
    }

    private static Path privateScratch(Path source) throws IOException
    {
        Path directory = Files.createTempDirectory(source.getParent(), ".managed-recordings-migration-");
        try
        {
            FileAccessAttributeSnapshot.restrictPrivateDirectory(directory);
        }
        catch(IOException exception)
        {
            try
            {
                Files.deleteIfExists(directory);
            }
            catch(IOException cleanupFailure)
            {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        return directory;
    }

    private static void deletePrivateScratch(Path scratch) throws IOException
    {
        if(Files.exists(scratch))
        {
            try(var paths = Files.walk(scratch))
            {
                for(Path path: paths.sorted(java.util.Comparator.reverseOrder()).toList())
                {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void requireSpace(Path source) throws IOException
    {
        long footprint = Files.size(source);
        for(String suffix: List.of("-wal", "-journal", "-shm"))
        {
            Path sidecar = Path.of(source + suffix);
            if(Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS))
            {
                footprint = Math.addExact(footprint, Files.size(sidecar));
            }
        }
        // Original snapshot, retained backup, child stage and its possible WAL, promotable copy, and the
        // post-checkpoint comparison snapshot can coexist at the peak.
        long needed = Math.addExact(Math.multiplyExact(footprint, 6), FREE_SPACE_MARGIN);
        if(Files.getFileStore(source).getUsableSpace() < needed)
        {
            throw new IOException("Insufficient free space for a managed recordings catalog backup and stage.");
        }
    }

    private static void requireNoOrphanSidecars(Path source) throws IOException
    {
        for(String suffix: List.of("-wal", "-journal", "-shm"))
        {
            if(Files.exists(Path.of(source + suffix), LinkOption.NOFOLLOW_LINKS))
            {
                throw new IOException("A managed recordings SQLite sidecar exists without its catalog file.");
            }
        }
    }

    private static boolean hasWalSidecars(Path source)
    {
        for(String suffix: List.of("-wal", "-shm"))
        {
            if(Files.exists(Path.of(source + suffix), LinkOption.NOFOLLOW_LINKS))
            {
                return true;
            }
        }
        return false;
    }

    private static Path normalized(Path database)
    {
        return Objects.requireNonNull(database, "Managed recordings catalog path is required")
            .toAbsolutePath().normalize();
    }
}
