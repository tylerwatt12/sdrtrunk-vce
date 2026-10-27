/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationReportSessionTest
{
    @TempDir Path directory;

    @Test
    void reportSurvivesSessionAndRedactsPathsSecretsAndEndpoints() throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path source = directory.resolve("previous/database/sdrtrunk.sqlite");
        var session = new MigrationReportSession(root, source, "Import previous profile",
            MigrationContent.CONFIGURATION_ONLY, "TIMING: Source review — 12 ms (completed)\n");
        var progress = session.progress(null);
        progress.update("Copy selected database");
        progress.update("Validate result");
        String result = session.completedText("Migration plan: format 61 -> format 63\n" +
            "Backup: " + root.resolve("database/backups/recovery.sqlite") + "\n" +
            "Source: " + source + "\napi_key=not-a-real-key https://example.test/private");

        List<Path> reports = reports(root);
        assertEquals(1, reports.size());
        String saved = Files.readString(reports.getFirst());
        assertTrue(result.contains("Migration report saved: logs/migration-reports/"));
        assertTrue(saved.startsWith("VCE migration report\n"));
        assertTrue(saved.contains("Contents: Configuration only"));
        assertTrue(saved.contains("Source review — 12 ms"));
        assertTrue(saved.contains("TIMING: Copy selected database —"));
        assertTrue(saved.contains("TIMING: Validate result —"));
        assertTrue(saved.contains("format 61 -> format 63"));
        assertFalse(saved.contains(root.toString()));
        assertFalse(saved.contains(source.toString()));
        assertFalse(saved.contains("not-a-real-key"));
        assertFalse(saved.contains("example.test"));
    }

    @Test
    void keepsTenOwnedReportsAndPreservesUnrelatedFiles() throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path reports = Files.createDirectories(root.resolve(MigrationReportSession.REPORT_DIRECTORY));
        Path unrelated = reports.resolve("notes.txt");
        Files.writeString(unrelated, "keep this");
        for(int i = 0; i < MigrationReportSession.RETAINED_REPORTS + 2; i++)
            session(root).completedText("OUTCOME: completed");

        assertEquals(MigrationReportSession.RETAINED_REPORTS, reports(root).size());
        assertEquals("keep this", Files.readString(unrelated));
        if(Files.getFileStore(reports).supportsFileAttributeView("posix"))
            assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(reports(root).getFirst()));
    }

    @Test
    void failedReportWriteWarnsWithoutFailingCompletedMigration() throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Files.writeString(root.resolve("logs"), "existing non-directory");

        String result = session(root).completedText("OUTCOME: Migration completed");

        assertTrue(result.contains("OUTCOME: Migration completed"));
        assertTrue(result.contains("WARNING: The migration report could not be saved"));
        assertEquals("existing non-directory", Files.readString(root.resolve("logs")));
    }

    @Test
    void refusesSymbolicLogDirectoryWithoutWritingOutsideTheProfile() throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        try { Files.createSymbolicLink(root.resolve("logs"), outside); }
        catch(java.io.IOException | UnsupportedOperationException unsupported)
        {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        String result = session(root).completedText("OUTCOME: Migration completed");

        assertTrue(result.contains("WARNING: The migration report could not be saved"));
        try(var files = Files.list(outside)) { assertEquals(0, files.count()); }
        assertTrue(Files.isSymbolicLink(root.resolve("logs")));
    }

    @Test
    void failureReportRecordsFailedPhaseWithoutSourceOwnedExceptionValues() throws Exception
    {
        Path root = Files.createDirectories(directory.resolve("data"));
        Files.createDirectories(root.resolve("database"));
        Files.write(root.resolve("database/sdrtrunk.sqlite"), new byte[]{1});
        var session = session(root);
        session.progress(null).update("Validate settings");
        session.failed(new IllegalStateException("private source-owned value"));

        String saved = Files.readString(reports(root).getFirst());
        assertTrue(saved.contains("Outcome: Failed (IllegalStateException)"));
        assertTrue(saved.matches("(?s).*TIMING: Validate settings — [0-9]+ ms \\(failed\\).*"));
        assertFalse(saved.contains("private source-owned value"));
    }

    @Test
    void failedFirstRunImportLeavesDestinationAbsentForRetry() throws Exception
    {
        Path root = directory.resolve("missing-data");
        session(root).failed(new IllegalStateException());
        assertFalse(Files.exists(root));
    }

    private MigrationReportSession session(Path root)
    {
        return new MigrationReportSession(root, root.resolve("database/sdrtrunk.sqlite"), "Update current profile",
            MigrationContent.FULL, "");
    }

    private List<Path> reports(Path root) throws Exception
    {
        try(var files = Files.list(root.resolve(MigrationReportSession.REPORT_DIRECTORY)))
        {
            return files.filter(file -> file.getFileName().toString().startsWith("migration-"))
                .filter(file -> file.getFileName().toString().endsWith(".txt")).toList();
        }
    }
}
