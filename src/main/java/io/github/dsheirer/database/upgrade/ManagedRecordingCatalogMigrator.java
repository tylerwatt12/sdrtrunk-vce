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

    public record MigrationResult(boolean migrated, Path backup, String report)
    {
        public MigrationResult(boolean migrated, Path backup)
        {
            this(migrated, backup, "");
        }
    }

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

    /** The caller owns its manual installation backup and recovery. Catalog users must already be stopped. */
    public static MigrationResult migrate(Path database) throws IOException, SQLException
    {
        return migrate(database, false);
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
        Path parent = source.getParent();
        Path dataRoot = parent != null && parent.getFileName() != null &&
            "database".equals(parent.getFileName().toString()) ? parent.getParent() : parent;
        if(dataRoot == null) throw new IOException("The managed recordings catalog has no containing folder.");
        MigrationReportSession report = new MigrationReportSession(dataRoot, source,
            "Update Managed Recordings catalog; " +
                (createBackup ? "recovery backup selected" : "manual installation backup"),
            "Recording catalog entries and metadata", "");
        StringBuilder details = new StringBuilder();
        ApplicationMigrationService.ProgressListener reportProgress = report.progress(null);
        MigrationTimings timings = new MigrationTimings(line -> {
            details.append(line).append('\n');
            reportProgress.update(line);
        });
        try
        {
            reportProgress.update("Updating Managed Recordings catalog");
            MigrationResult result = migrateInternal(source, createBackup, beforeCommit, connectionFactory,
                timings, details);
            if(!result.migrated()) return result;
            String completion = "Managed Recordings catalog update completed. Recording entries and audio are kept.\n" +
                (result.backup() == null ? "Recovery: restore your manual installation backup if needed. " +
                    "No automatic upgrade backup was created." : "Recovery backup retained: " + result.backup()) +
                "\n" + details;
            return new MigrationResult(true, result.backup(), report.completedText(completion));
        }
        catch(IOException | SQLException | RuntimeException | Error failure)
        {
            report.failed(failure);
            throw failure;
        }
    }

    private static MigrationResult migrateInternal(Path source, boolean createBackup, BeforeCommit beforeCommit,
                                   ConnectionFactory connectionFactory, MigrationTimings timings,
                                   StringBuilder details) throws IOException, SQLException
    {
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
            int version;
            try(var phase = timings.phase("Managed Recordings: open and inspect source catalog"))
            {
                connection = connectionFactory.open(source);
                version = requireSupportedVersion(connection);
                phase.complete();
            }
            if(version == CURRENT_FORMAT)
            {
                completed = true;
                return new MigrationResult(false, null);
            }
            details.append("Migration plan: catalog format ").append(version).append(" -> ")
                .append(CURRENT_FORMAT).append('\n');
            try(var phase = timings.phase("Managed Recordings: check migration workspace"))
            {
                requireSpace(source, createBackup);
                phase.complete();
            }
            try(Statement statement = connection.createStatement())
            {
                try(var phase = timings.phase("Managed Recordings: reserve writer and verify source format"))
                {
                    statement.execute("BEGIN IMMEDIATE");
                    transactionStarted = true;
                    version = requireSupportedVersion(connection);
                    phase.complete();
                }
                if(version == CURRENT_FORMAT)
                {
                    statement.execute("ROLLBACK");
                    transactionStarted = false;
                    completed = true;
                    return new MigrationResult(false, null);
                }
                if(createBackup)
                {
                    try(var phase = timings.phase("Managed Recordings: create recovery backup"))
                    {
                        Path backupDirectory = source.getParent().resolve("backups");
                        Files.createDirectories(backupDirectory);
                        Path candidate = backupDirectory.resolve("managed-recordings-before-upgrade-" +
                            UUID.randomUUID() + ".sqlite");
                        SqliteDatabaseSnapshot.create(source, candidate);
                        backup = candidate;
                        phase.complete();
                    }
                }
                applyAdjacentSteps(connection, statement, version, timings);
                try(var phase = timings.phase("Managed Recordings: validate completed catalog"))
                {
                    beforeCommit.validate(connection);
                    ManagedRecordingSchema.validate(connection, CURRENT_FORMAT);
                    phase.complete();
                }
                try(var phase = timings.phase("Managed Recordings: commit catalog update"))
                {
                    statement.execute("COMMIT");
                    transactionStarted = false;
                    completed = true;
                    migrated = true;
                    phase.complete();
                }
            }
            return new MigrationResult(true, backup);
        }
        catch(IOException | SQLException | RuntimeException | Error failure)
        {
            // Statement cleanup can fail after COMMIT returned. The catalog is already upgraded at that point.
            if(completed)
            {
                LOG.warn("Managed recordings catalog update completed, but statement cleanup failed");
                details.append("WARNING: Catalog update completed, but statement cleanup failed. Do not repeat the update.\n");
                return new MigrationResult(migrated, backup);
            }
            primaryFailure = failure;
            if(transactionStarted && connection != null)
            {
                try(Statement rollback = connection.createStatement())
                {
                    try(var phase = timings.phase("Managed Recordings: roll back failed update"))
                    {
                        rollback.execute("ROLLBACK");
                        phase.complete();
                    }
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
                try(var phase = timings.phase("Managed Recordings: close and finalize catalog"))
                {
                    connection.close();
                    phase.complete();
                }
                catch(SQLException | RuntimeException cleanupFailure)
                {
                    if(primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                    else if(completed)
                    {
                        LOG.warn("Managed recordings catalog update completed, but connection cleanup failed");
                        details.append("WARNING: Catalog update completed, but connection cleanup failed. Do not repeat the update.\n");
                    }
                    else throw cleanupFailure;
                }
            }
        }
    }

    private static void applyAdjacentSteps(Connection connection, Statement statement, int version,
                                           MigrationTimings timings) throws SQLException
    {
        while(version < CURRENT_FORMAT)
        {
            try(var phase = timings.phase("Managed Recordings format " + version + " -> " + (version + 1)))
            {
                switch(version)
                {
                    case 1 -> statement.execute(ManagedRecordingSchema.ddlForFormat(2).get("recording_transcript"));
                    case 2 ->
                    {
                        appendTranscriptionStatusColumn(connection);
                        statement.executeUpdate("UPDATE recording_call SET transcription_status='complete' " +
                            "WHERE id IN (SELECT call_id FROM recording_transcript)");
                        statement.execute(ManagedRecordingSchema.ddlForFormat(3).get("idx_recording_call_transcription_pending"));
                    }
                    case 3 ->
                    {
                        statement.execute(ManagedRecordingSchema.ddlForFormat(4).get("recording_tone_sequence"));
                        statement.execute(ManagedRecordingSchema.ddlForFormat(4).get("recording_tone"));
                    }
                    case 4 -> widenToneMethodConstraint(connection, 4);
                    case 5 ->
                    {
                        // Only new lookup indexes are added; all recording values and relationships remain intact.
                        for(var definition: ManagedRecordingSchema.ddlForFormat(6).entrySet())
                        {
                            if(!ManagedRecordingSchema.ddlForFormat(5).containsKey(definition.getKey()))
                            {
                                statement.execute(definition.getValue());
                            }
                        }
                    }
                    case 6 ->
                    {
                        // Only future captured facts establish auxiliary identities; never infer them from old IDs.
                        statement.execute(ManagedRecordingSchema.ddlForFormat(7).get("recording_identifier"));
                        statement.execute(ManagedRecordingSchema.ddlForFormat(7).get("idx_recording_identifier_lookup"));
                    }
                    case 7 ->
                    {
                        //The plain transcript table remains authoritative. Its disk search index is backfilled once.
                        var definitions = ManagedRecordingSchema.ddlForFormat(8);
                        statement.execute(definitions.get("recording_transcript_fts"));
                        for(String trigger: List.of("insert", "delete", "update"))
                            statement.execute(definitions.get("recording_transcript_fts_" + trigger));
                        statement.execute("INSERT INTO recording_transcript_fts(recording_transcript_fts) VALUES('rebuild')");
                    }
                    case 8 -> widenToneMethodConstraint(connection, 8);
                    case 9 ->
                    {
                        // Prospective analog nominal frequency identities expand; preserve all stored facts verbatim.
                        // The shared adjacent runner stamps format 10 so older method-4 readers refuse this catalog.
                    }
                    case 10 ->
                    {
                        // Preserve all existing catalog facts; exact source references begin with newly saved calls.
                        statement.execute(ManagedRecordingSchema.ddlForFormat(11).get("recording_source"));
                        statement.execute(ManagedRecordingSchema.ddlForFormat(11).get("idx_recording_source_uuid"));
                    }
                    case 11 ->
                    {
                        // Preserve every recording and relationship. No historical quality is inferred or backfilled.
                        appendVoiceQualityColumns(connection);
                    }
                    default -> throw new SQLException("No adjacent managed recordings migration from format " + version);
                }
                stamp(statement, ++version);
                ManagedRecordingSchema.validate(connection, version);
                phase.complete();
            }
        }
    }

    /** The old physical records read the valid literal pending default; later backfill/index work is unchanged. */
    static void appendTranscriptionStatusColumn(Connection connection) throws SQLException
    {
        appendRecordingCallColumns(connection, 2);
    }

    /** The six nullable additions need no old values; STRICT native ADD would scan every call six times. */
    private static void appendVoiceQualityColumns(Connection connection) throws SQLException
    {
        appendRecordingCallColumns(connection, 11);
    }

    private static void appendRecordingCallColumns(Connection connection, int sourceFormat) throws SQLException
    {
        List<String> additions = switch(sourceFormat)
        {
            case 2 ->
            {
                // Freeze this safety proof: the new CHECK depends only on its valid non-null TEXT default,
                // never on any retained field. Native ADD on the empty proof table establishes its layout.
                if(!ManagedRecordingSchema.TRANSCRIPTION_STATUS_COLUMN.equals("transcription_status TEXT NOT NULL " +
                    "DEFAULT 'pending' CHECK(transcription_status IN('pending','complete','failed'))"))
                    throw new SQLException("Managed recordings status addition no longer has the admitted pending default");
                yield List.of(ManagedRecordingSchema.TRANSCRIPTION_STATUS_COLUMN);
            }
            case 11 -> ManagedRecordingSchema.VOICE_QUALITY_COLUMNS;
            default -> throw new SQLException("No metadata-only recording append from catalog format " + sourceFormat);
        };
        ManagedRecordingSchema.validate(connection, sourceFormat);
        String previous = ManagedRecordingSchema.ddlForFormat(sourceFormat).get("recording_call");
        String current = ManagedRecordingSchema.ddlForFormat(sourceFormat + 1).get("recording_call");
        List<String> columns;
        // Prove the exact target and physical append using SQLite itself, with no retained rows to visit.
        try(Connection validation = DriverManager.getConnection("jdbc:sqlite::memory:");
            var statement = validation.createStatement())
        {
            statement.execute(previous);
            for(String column: additions) statement.execute("ALTER TABLE recording_call ADD COLUMN " + column);
            try(var row = statement.executeQuery("SELECT sql FROM sqlite_schema WHERE name='recording_call'"))
            {
                if(!row.next() || !current.equals(row.getString(1)))
                    throw new SQLException("Managed recordings column additions do not match the exact native target");
            }
            columns = tableColumns(validation, "recording_call");
        }
        replaceTableDefinition(connection, "recording_call", previous, current, columns);
    }

    /** Only these two frozen steps widen tone CHECKs; no stored value or physical field changes. */
    static void widenToneMethodConstraint(Connection connection, int sourceFormat) throws SQLException
    {
        if(sourceFormat != 4 && sourceFormat != 8)
            throw new SQLException("No metadata-only tone constraint update from catalog format " + sourceFormat);
        ManagedRecordingSchema.validate(connection, sourceFormat);
        String table = "recording_tone_sequence";
        var source = ManagedRecordingSchema.ddlForFormat(sourceFormat);
        var target = ManagedRecordingSchema.ddlForFormat(sourceFormat + 1);
        String previous = source.get(table);
        String current = target.get(table);
        String widened = sourceFormat == 4 ? previous
            .replace("method_code IN(1,2)", "method_code IN(1,2,3)")
            .replace("CHECK(source_code=method_code)",
                "CHECK((source_code=1 AND method_code IN(1,3)) OR (source_code=2 AND method_code=2))") : previous
            .replace("method_code IN(1,2,3)", "method_code IN(1,2,3,4)")
            .replace("method_code IN(1,3)", "method_code IN(1,3,4)");
        var expected = new java.util.LinkedHashMap<>(source);
        expected.put(table, widened);
        if(!expected.equals(target))
            throw new SQLException("Managed recordings tone update changes more than the admitted CHECK widening");
        List<String> columns;
        // SQLite parses both frozen definitions on empty tables. Exact CHECK widening admits every old row;
        // identical physical fields, defaults and STRICT declaration keep all parent and child b-trees intact.
        try(Connection validation = DriverManager.getConnection("jdbc:sqlite::memory:");
            var statement = validation.createStatement())
        {
            statement.execute(previous);
            columns = tableColumns(validation, table);
            statement.execute("DROP TABLE " + table);
            statement.execute(current);
            if(!columns.equals(tableColumns(validation, table)))
                throw new SQLException("Managed recordings tone update changes the physical column layout");
        }
        replaceTableDefinition(connection, table, previous, current, columns);
    }

    /** Caller owns BEGIN IMMEDIATE and exact-format validation; only proven compatible definitions are admitted. */
    private static void replaceTableDefinition(Connection connection, String table, String previous, String current,
                                               List<String> columns) throws SQLException
    {
        if(!List.of("recording_call", "recording_tone_sequence").contains(table))
            throw new SQLException("Unsupported managed recordings metadata-only table update");
        int version = pragmaInt(connection, "schema_version");
        if(version < 0 || version == Integer.MAX_VALUE || pragmaInt(connection, "writable_schema") != 0)
            throw new SQLException("Managed recordings update cannot advance the SQLite schema version");
        // Missing status reads its valid pending default, quality reads NULL; tone changes widen known CHECK domains.
        // No retained row needs inspection or rewriting in either admitted operation.
        try(var statement = connection.createStatement())
        {
            try
            {
                statement.execute("PRAGMA writable_schema=ON");
                try(var update = connection.prepareStatement("UPDATE sqlite_schema SET sql=? " +
                    "WHERE type='table' AND name=? AND sql=?"))
                {
                    update.setString(1, current);
                    update.setString(2, table);
                    update.setString(3, previous);
                    if(update.executeUpdate() != 1)
                        throw new SQLException("Managed recordings source definition changed during metadata update");
                }
                statement.execute("PRAGMA schema_version=" + (version + 1));
            }
            finally
            {
                try { statement.execute("PRAGMA writable_schema=OFF"); }
                finally { statement.execute("PRAGMA writable_schema=RESET"); }
            }
        }
        if(!columns.equals(tableColumns(connection, table)))
            throw new SQLException("Managed recordings update changed the native column layout");
    }

    private static List<String> tableColumns(Connection connection, String table) throws SQLException
    {
        var columns = new java.util.ArrayList<String>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "PRAGMA table_xinfo(" + table + ")"))
        {
            while(rows.next()) columns.add(rows.getInt("cid") + "|" + rows.getString("name") + "|" +
                rows.getString("type") + "|" + rows.getInt("notnull") + "|" + rows.getString("dflt_value") +
                "|" + rows.getInt("pk") + "|" + rows.getInt("hidden"));
        }
        return columns;
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
