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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.sqlite.SQLiteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit pre-receiver upgrade of the application-owned Managed Recordings catalog. The optional source-format
 * backup is created once, then the adjacent transformations and final exact-format validation share one in-place
 * transaction. Normal catalog startup validates only; it never applies schema changes.
 */
public final class ManagedRecordingCatalogMigrator
{
    private static final Logger LOG = LoggerFactory.getLogger(ManagedRecordingCatalogMigrator.class);
    private static final int LEGACY_FORMAT = 1;
    private static final int CURRENT_FORMAT = ManagedRecordingSchema.CURRENT_FORMAT_VERSION;
    private static final long FREE_SPACE_MARGIN = 16L * 1024L * 1024L;

    private ManagedRecordingCatalogMigrator() {}

    public enum State { ABSENT, CURRENT, UPGRADE_REQUIRED }

    public record Inspection(State state)
    {
        public boolean needsMigration() { return state == State.UPGRADE_REQUIRED; }
    }

    public record MigrationResult(boolean migrated, Path backup) {}

    /** Bounded exact-format admission; retained history is not scanned or copied during startup. */
    public static Inspection inspectForStartup(Path database) throws IOException, SQLException
    {
        return inspect(database);
    }

    /**
     * Classifies the inactive application-owned catalog. SQLite may recover its hot rollback journal, but no DDL
     * or catalog rows are changed by this inspection. A committed WAL is always read through SQLite.
     */
    public static Inspection inspect(Path database) throws IOException, SQLException
    {
        Path source = normalized(database);
        if(!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
        {
            requireNoOrphanSidecars(source);
            return new Inspection(State.ABSENT);
        }
        requireOwnedSourceUsable(source);
        try(Connection connection = Files.exists(Path.of(source + "-journal"), LinkOption.NOFOLLOW_LINKS) ?
            openWritable(source) : openReadOnly(source))
        {
            return new Inspection(state(requireSupportedVersion(connection)));
        }
    }

    /** Defaults to retaining one SQLite-aware source-format backup. Catalog users must already be stopped. */
    public static MigrationResult migrate(Path database) throws IOException, SQLException
    {
        return migrate(database, true);
    }

    /**
     * Runs one transaction under the caller's portable-data lock. A failed pre-commit update rolls back; after a
     * successful commit there is no automatic rollback, and skipping the backup leaves no retained earlier copy.
     */
    public static MigrationResult migrate(Path database, boolean createBackup) throws IOException, SQLException
    {
        return migrate(database, createBackup, ignored -> {}, ManagedRecordingCatalogMigrator::openWritable);
    }

    /** Fault boundaries exercise rollback and committed-cleanup behavior without a subprocess or staging path. */
    static MigrationResult migrate(Path database, boolean createBackup, BeforeCommit beforeCommit,
                                   ConnectionFactory connectionFactory) throws IOException, SQLException
    {
        Path source = normalized(database);
        if(!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
        {
            requireNoOrphanSidecars(source);
            return new MigrationResult(false, null);
        }
        requireOwnedSourceUsable(source);
        Connection connection = null;
        Path backup = null;
        boolean transactionStarted = false;
        boolean completed = false;
        boolean migrated = false;
        Throwable primaryFailure = null;
        try
        {
            // Writable admission lets SQLite recover a hot journal before the read-only backup connection opens.
            connection = connectionFactory.open(source);
            int version = requireSupportedVersion(connection);
            if(version == CURRENT_FORMAT)
            {
                completed = true;
                return new MigrationResult(false, null);
            }
            requireSpace(source, createBackup);
            try(Statement statement = connection.createStatement())
            {
                statement.execute("BEGIN IMMEDIATE");
                transactionStarted = true;
                version = requireSupportedVersion(connection);
                if(version == CURRENT_FORMAT)
                {
                    statement.execute("ROLLBACK");
                    transactionStarted = false;
                    completed = true;
                    return new MigrationResult(false, null);
                }
                if(createBackup)
                {
                    Path backupDirectory = source.getParent().resolve("backups");
                    Files.createDirectories(backupDirectory);
                    Path candidate = backupDirectory.resolve("managed-recordings-before-upgrade-" +
                        UUID.randomUUID() + ".sqlite");
                    SqliteDatabaseSnapshot.create(source, candidate);
                    backup = candidate;
                }
                applyAdjacentSteps(connection, statement, version);
                beforeCommit.validate(connection);
                ManagedRecordingSchema.validate(connection, CURRENT_FORMAT);
                statement.execute("COMMIT");
                transactionStarted = false;
                completed = true;
                migrated = true;
            }
            return new MigrationResult(true, backup);
        }
        catch(IOException | SQLException | RuntimeException | Error failure)
        {
            // Statement cleanup can fail after COMMIT returned. The catalog is already upgraded at that point.
            if(completed)
            {
                LOG.warn("Managed recordings catalog update completed, but statement cleanup failed");
                return new MigrationResult(migrated, backup);
            }
            primaryFailure = failure;
            if(transactionStarted && connection != null)
            {
                try(Statement rollback = connection.createStatement())
                {
                    rollback.execute("ROLLBACK");
                }
                catch(SQLException rollbackFailure)
                {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if(backup != null)
            {
                String retained = "Managed recordings catalog update failed; source-format backup retained at " + backup + ". ";
                if(failure instanceof SQLException sqlFailure)
                    throw new SQLException(retained + sqlFailure.getMessage(), sqlFailure.getSQLState(),
                        sqlFailure.getErrorCode(), sqlFailure);
                if(failure instanceof IOException ioFailure)
                    throw new IOException(retained + ioFailure.getMessage(), ioFailure);
                failure.addSuppressed(new IOException(retained));
            }
            throw failure;
        }
        finally
        {
            if(connection != null)
            {
                try { connection.close(); }
                catch(SQLException | RuntimeException cleanupFailure)
                {
                    if(primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                    else if(completed) LOG.warn("Managed recordings catalog update completed, but connection cleanup failed");
                    else throw cleanupFailure;
                }
            }
        }
    }

    private static void applyAdjacentSteps(Connection connection, Statement statement, int version) throws SQLException
    {
        while(version < CURRENT_FORMAT)
        {
            switch(version)
            {
                case 1 -> statement.execute(ManagedRecordingSchema.ddlForFormat(2).get("recording_transcript"));
                case 2 ->
                {
                    statement.execute("ALTER TABLE recording_call ADD COLUMN " + ManagedRecordingSchema.TRANSCRIPTION_STATUS_COLUMN);
                    statement.executeUpdate("UPDATE recording_call SET transcription_status='complete' " +
                        "WHERE id IN (SELECT call_id FROM recording_transcript)");
                    statement.execute(ManagedRecordingSchema.ddlForFormat(3).get("idx_recording_call_transcription_pending"));
                }
                default -> throw new SQLException("No adjacent managed recordings migration from format " + version);
            }
            stamp(statement, ++version);
            ManagedRecordingSchema.validate(connection, version);
        }
    }

    private static int requireSupportedVersion(Connection connection) throws SQLException
    {
        int version = pragmaInt(connection, "user_version");
        if(version < LEGACY_FORMAT || version > CURRENT_FORMAT)
            throw new SQLException("Unsupported managed recordings catalog version " + version + ".");
        ManagedRecordingSchema.validate(connection, version);
        return version;
    }

    private static State state(int version)
    {
        return version == CURRENT_FORMAT ? State.CURRENT : State.UPGRADE_REQUIRED;
    }

    private static void stamp(Statement statement, int version) throws SQLException
    {
        statement.executeUpdate("UPDATE catalog_metadata SET format_version=" + version + " WHERE id=1");
        statement.execute("PRAGMA user_version=" + version);
    }

    private static int pragmaInt(Connection connection, String name) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA " + name))
        {
            if(!rows.next()) throw new SQLException("Missing SQLite " + name + " pragma.");
            return rows.getInt(1);
        }
    }

    private static Connection openWritable(Path source) throws SQLException
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(10_000);
        config.enforceForeignKeys(true);
        return DriverManager.getConnection("jdbc:sqlite:" + source.toUri().toASCIIString() + "?mode=rw", config.toProperties());
    }

    private static Connection openReadOnly(Path source) throws SQLException
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(10_000);
        return DriverManager.getConnection("jdbc:sqlite:" + source, config.toProperties());
    }

    private static void requireSpace(Path source, boolean createBackup) throws IOException
    {
        long footprint = Files.size(source);
        for(String suffix: List.of("-wal", "-journal"))
        {
            Path sidecar = Path.of(source + suffix);
            if(Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) footprint = Math.addExact(footprint, Files.size(sidecar));
        }
        // One optional backup plus a worst-case rollback journal or WAL for the one in-place transaction.
        long needed = Math.addExact(Math.multiplyExact(footprint, createBackup ? 2 : 1), FREE_SPACE_MARGIN);
        if(Files.getFileStore(source).getUsableSpace() < needed)
            throw new IOException("Insufficient free space for the managed recordings catalog update.");
    }

    private static void requireNoOrphanSidecars(Path source) throws IOException
    {
        for(String suffix: List.of("-wal", "-shm", "-journal"))
            if(Files.exists(Path.of(source + suffix), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("A managed recordings SQLite sidecar exists without its catalog file.");
    }

    private static void requireOwnedSourceUsable(Path source) throws IOException
    {
        SqliteDatabaseSnapshot.requireSourceUsable(source);
        ApplicationDatabaseMigrator.requireSingleFilesystemLinkWhenSupported(source);
    }

    private static Path normalized(Path database)
    {
        return Objects.requireNonNull(database, "Managed recordings catalog path is required").toAbsolutePath().normalize();
    }

    @FunctionalInterface interface BeforeCommit { void validate(Connection connection) throws SQLException; }
    @FunctionalInterface interface ConnectionFactory { Connection open(Path source) throws SQLException; }
}
