/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationReportTest
{
    @TempDir Path directory;

    @Test
    void completedConfigurationOnlyImportPersistsReviewSnapshotAndAdjacentStepTimings() throws Exception
    {
        Path sourceRoot = directory.resolve("previous/data");
        Path source = Format61TestDatabase.create(sourceRoot.resolve("database/sdrtrunk.sqlite"));
        byte[] original = Files.readAllBytes(source);
        var selection = new PreviousBuildLocator.Selection(sourceRoot, PreviousBuildLocator.InputScope.PORTABLE_PROFILE);
        var approval = ApplicationMigrationService.readMigrationApproval(selection, directory)
            .withContent(MigrationContent.CONFIGURATION_ONLY);
        Path targetRoot = directory.resolve("new/data");

        var result = ApplicationMigrationServiceTestSupport.createInProcess()
            .importPrevious(selection, targetRoot, approval, null);

        String report = savedReport(targetRoot);
        assertTrue(result.helperOutput().contains("Migration report saved: logs/migration-reports/"));
        assertTrue(report.contains("Source review: capture and verify external snapshot"));
        assertTrue(report.contains("Recheck external source unchanged"));
        assertTrue(report.contains("Contents: Configuration only"));
        assertTrue(report.contains("Source physical integrity check"));
        assertTrue(report.contains("Count and discard saved receiver Activity, statistics and alert history"));
        assertTrue(report.contains("TIMING: Database format 61 -> 62:"));
        assertTrue(report.contains("TIMING: Database format 62 -> 63:"));
        assertTrue(report.contains("Commit database update"));
        assertTrue(report.contains("Verifying the copied database matches source review"));
        assertArrayEquals(original, Files.readAllBytes(source));
        ConfigurationHistoryDatabase.validatePairIdentity(targetRoot.resolve("database/sdrtrunk.sqlite"));
    }

    @Test
    void selectingSourceAsDestinationRefusesWithoutSavingIntoSource() throws Exception
    {
        Path root = directory.resolve("source/data");
        Path source = Format61TestDatabase.create(root.resolve("database/sdrtrunk.sqlite"));
        byte[] original = Files.readAllBytes(source);
        var selection = new PreviousBuildLocator.Selection(root, PreviousBuildLocator.InputScope.PORTABLE_PROFILE);

        assertThrows(java.io.IOException.class, () -> ApplicationMigrationServiceTestSupport.createInProcess()
            .importPrevious(selection, root, null));

        assertArrayEquals(original, Files.readAllBytes(source));
        assertFalse(Files.exists(root.resolve("logs")));
    }

    @Test
    void reportWriteFailureLeavesCompletedOwnedUpdateUsableAndDoesNotSuggestRetry() throws Exception
    {
        Path root = directory.resolve("data");
        Path database = Format61TestDatabase.create(root.resolve("database/sdrtrunk.sqlite"));
        Files.writeString(root.resolve("logs"), "preserve this file");

        var result = new ApplicationMigrationService().migrateCurrent(root, false,
            MigrationContent.CONFIGURATION_ONLY, null);

        assertTrue(result.helperOutput().contains("WARNING: The migration report could not be saved"));
        assertTrue(result.helperOutput().contains("completed migration does not need to be repeated"));
        ConfigurationHistoryDatabase.validatePairIdentity(database);
        assertEquals("preserve this file", Files.readString(root.resolve("logs")));
    }

    @Test
    void completedOwnedUpdatePersistsEngineAndOperationTimingsWithoutIntegritySweeps() throws Exception
    {
        Path root = directory.resolve("data");
        Format61TestDatabase.create(root.resolve("database/sdrtrunk.sqlite"));

        new ApplicationMigrationService().migrateCurrent(root, false, MigrationContent.CONFIGURATION_ONLY, null);

        String report = savedReport(root);
        assertTrue(report.contains("Outcome: Completed"));
        assertTrue(report.contains("recovery backup skipped"));
        assertTrue(report.contains("Total operation (excluding earlier source review)"));
        assertTrue(report.contains("TIMING: Database format 61 -> 62:"));
        assertFalse(report.contains("Source physical integrity check"));
        assertFalse(report.contains(directory.toString()));
    }

    private String savedReport(Path root) throws Exception
    {
        try(var files = Files.list(root.resolve(MigrationReportSession.REPORT_DIRECTORY)))
        {
            return Files.readString(files.filter(file -> file.getFileName().toString().endsWith(".txt"))
                .findFirst().orElseThrow());
        }
    }
}
