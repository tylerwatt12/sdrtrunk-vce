/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.record.managed.ManagedRecordingSchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingCatalogReportTest
{
    @TempDir Path temporary;

    @Test
    void completedCatalogReportSurvivesSetupAndRecordsEveryAdjacentStepAndBackup() throws Exception
    {
        Path root = temporary.resolve("data");
        Path catalog = createCatalog(root, 1);

        var result = ManagedRecordingCatalogMigrator.migrate(catalog, true);

        assertTrue(result.migrated());
        assertTrue(result.report().contains("Contents: Recording catalog entries and metadata"));
        assertTrue(result.report().contains("Backup: " + result.backup()));
        assertTrue(result.report().contains("Migration report saved: logs/migration-reports/"));
        Path report = savedReports(root).getFirst();
        String saved = Files.readString(report);
        assertTrue(saved.startsWith("VCE migration report\n"));
        assertTrue(saved.contains("Migration plan: catalog format 1 -> " + ManagedRecordingSchema.CURRENT_FORMAT_VERSION));
        for(int version = 1; version < ManagedRecordingSchema.CURRENT_FORMAT_VERSION; version++)
            assertTiming(saved, "Managed Recordings format " + version + " -> " + (version + 1), "completed");
        for(String phase: List.of("create recovery backup", "validate completed catalog", "commit catalog update",
            "close and finalize catalog"))
            assertTiming(saved, "Managed Recordings: " + phase, "completed");
        assertFalse(saved.contains(root.toString()));
        assertEquals(ManagedRecordingSchema.CURRENT_FORMAT_VERSION, version(catalog));

        var repeated = ManagedRecordingCatalogMigrator.migrate(catalog, true);
        assertFalse(repeated.migrated());
        assertEquals("", repeated.report());
        assertEquals(List.of(report), savedReports(root));
    }

    @Test
    void absentCatalogDoesNotCreateReportDirectories() throws Exception
    {
        Path root = temporary.resolve("missing-data");
        var result = ManagedRecordingCatalogMigrator.migrate(root.resolve("database/managed-recordings.sqlite"), false);
        assertFalse(result.migrated());
        assertEquals("", result.report());
        assertFalse(Files.exists(root));
    }

    @Test
    void failedExistingProfileReportKeepsTimingsWithoutExceptionValuesAndCatalogRollsBack() throws Exception
    {
        Path root = temporary.resolve("failed-data");
        Path catalog = createCatalog(root, 1);
        // The report store needs only proof that this is an existing profile, and never reads the application file.
        Files.writeString(root.resolve("database/sdrtrunk.sqlite"), "existing application database");

        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false,
            connection -> { throw new SQLException("private reporter value must stay out of saved diagnostics"); },
            source -> DriverManager.getConnection("jdbc:sqlite:" + source)));

        assertEquals(1, version(catalog));
        String saved = Files.readString(savedReports(root).getFirst());
        assertTrue(saved.contains("Outcome: Failed (SQLException)"));
        assertTiming(saved, "Managed Recordings: validate completed catalog", "failed");
        assertTiming(saved, "Managed Recordings: roll back failed update", "completed");
        assertFalse(saved.contains("private reporter value"));
        assertFalse(saved.contains(root.toString()));
    }

    @Test
    void reportWriteFailureDoesNotTurnCommittedCatalogIntoRetryableFailure() throws Exception
    {
        Path root = temporary.resolve("blocked-data");
        Path catalog = createCatalog(root, 1);
        Files.writeString(root.resolve("logs"), "preserve existing blocker");

        var result = ManagedRecordingCatalogMigrator.migrate(catalog, false);

        assertTrue(result.migrated());
        assertTrue(result.report().contains("WARNING: The migration report could not be saved"));
        assertEquals(ManagedRecordingSchema.CURRENT_FORMAT_VERSION, version(catalog));
        assertEquals("preserve existing blocker", Files.readString(root.resolve("logs")));
    }

    @Test
    void connectionCleanupFailureStillSavesCommittedReportWithFailedCleanupTiming() throws Exception
    {
        Path root = temporary.resolve("cleanup-data");
        Path catalog = createCatalog(root, 1);

        var result = ManagedRecordingCatalogMigrator.migrate(catalog, false, ignored -> {}, source ->
            cleanupFailingConnection(DriverManager.getConnection("jdbc:sqlite:" + source)));

        assertTrue(result.migrated());
        assertEquals(ManagedRecordingSchema.CURRENT_FORMAT_VERSION, version(catalog));
        String saved = Files.readString(savedReports(root).getFirst());
        assertTiming(saved, "Managed Recordings: commit catalog update", "completed");
        assertTiming(saved, "Managed Recordings: close and finalize catalog", "failed");
        assertTrue(saved.contains("WARNING: Catalog update completed, but connection cleanup failed"));
    }

    private static Path createCatalog(Path root, int version) throws Exception
    {
        Path database = root.resolve("database/managed-recordings.sqlite");
        Files.createDirectories(database.getParent());
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            for(String ddl: ManagedRecordingSchema.ddlForFormat(version).values()) statement.execute(ddl);
            statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1," + version + ",0,0)");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=" + version);
        }
        return database;
    }

    private static int version(Path database) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version"))
        {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static List<Path> savedReports(Path root) throws Exception
    {
        try(var files = Files.list(root.resolve(MigrationReportSession.REPORT_DIRECTORY)))
        {
            return files.filter(file -> file.getFileName().toString().endsWith(".txt")).sorted().toList();
        }
    }

    private static void assertTiming(String report, String phase, String outcome)
    {
        assertTrue(report.lines().anyMatch(line -> line.matches("TIMING: " + java.util.regex.Pattern.quote(phase) +
            " — [0-9]+ ms \\(" + outcome + "\\)")), phase + " timing missing");
    }

    private static Connection cleanupFailingConnection(Connection delegate)
    {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                try
                {
                    Object result = method.invoke(delegate, args);
                    if(method.getName().equals("close")) throw new SQLException("Injected close failure");
                    return result;
                }
                catch(InvocationTargetException failure) { throw failure.getCause(); }
            });
    }
}
