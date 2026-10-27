/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationTimingsTest
{
    @TempDir Path directory;

    @Test
    void configurationOnlyOwnedUpdateReportsEachStepDiscardAndCommitButRunsNoIntegritySweep() throws Exception
    {
        Path database = Format61TestDatabase.create(directory.resolve("owned.sqlite"));
        List<String> progress = new ArrayList<>();
        var result = ApplicationDatabaseMigrator.migrateInPlace(database, progress::add, () -> { },
            java.util.Map.of(), MigrationContent.CONFIGURATION_ONLY);
        String output = result.helperOutput();
        assertCompleted(output, "Inspect source schema and bounded configuration");
        assertCompleted(output, "Acquire database writer lock");
        assertCompleted(output, "Create SQLite recovery backup");
        assertCompleted(output, "Count and discard saved receiver Activity, statistics and alert history");
        assertCompleted(output, "Count discarded receiver history rows");
        assertCompleted(output, "Clear discarded receiver history tables");
        assertTrue(output.contains("TIMING: Database format 61 -> 62:"), output);
        assertTrue(output.contains("TIMING: Database format 62 -> 63:"), output);
        assertCompleted(output, "Commit database update");
        assertCompleted(output, "Finalize database pair activation and startup validation");
        assertFalse(output.contains("physical integrity check"), output);
        assertTrue(progress.containsAll(output.lines().filter(line -> line.startsWith("TIMING:")).toList()));
    }

    @Test
    void failedBackupReportsFailedPhaseAndRollbackBeforePropagatingOriginalError() throws Exception
    {
        Path database = Format61TestDatabase.create(directory.resolve("failed.sqlite"));
        byte[] source = Files.readAllBytes(database);
        List<String> progress = new ArrayList<>();
        IOException failure = assertThrows(IOException.class, () ->
            ApplicationDatabaseMigrator.migrateInPlace(database, progress::add,
                () -> { throw new IOException("backup failed"); }));
        assertEquals("backup failed", failure.getMessage());
        assertTrue(progress.stream().anyMatch(line -> line.matches(
            "TIMING: Create SQLite recovery backup — [0-9]+ ms \\(failed\\)")), progress.toString());
        assertTrue(progress.stream().anyMatch(line -> line.startsWith("TIMING: Roll back failed database update")));
        assertFalse(progress.stream().anyMatch(line -> line.startsWith("TIMING: Commit database update")));
        assertArrayEquals(source, Files.readAllBytes(database));
    }

    @Test
    void cancelledAdjacentStepReportsItsFailedDurationAndLeavesSourceUnchanged() throws Exception
    {
        Path database = Format61TestDatabase.create(directory.resolve("cancelled.sqlite"));
        byte[] source = Files.readAllBytes(database);
        List<String> progress = new ArrayList<>();
        assertThrows(CancellationException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, line -> {
            progress.add(line);
            if(line.startsWith("Step 1 of")) throw new CancellationException();
        }));
        assertTrue(progress.stream().anyMatch(line -> line.startsWith("TIMING: Database format 61 -> 62:") &&
            line.endsWith("(failed)")), progress.toString());
        assertArrayEquals(source, Files.readAllBytes(database));
    }

    @Test
    void externalConfigurationOnlyMigrationSeparatelyTimesSourceIntegrityAndFinalChecks() throws Exception
    {
        Path database = Format61TestDatabase.create(directory.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try(PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8))
        {
            assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, ApplicationDatabaseMigrator.run(
                new String[]{database.toString(), "--configuration-only"}, output, output),
                bytes.toString(StandardCharsets.UTF_8));
        }
        String report = bytes.toString(StandardCharsets.UTF_8);
        assertCompleted(report, "Source physical integrity check");
        assertCompleted(report, "Count and discard saved receiver Activity, statistics and alert history");
        assertCompleted(report, "Check updated foreign-key relationships");
        assertCompleted(report, "Check updated SQLite integrity");
        assertFalse(report.contains(directory.toString()), report);
    }

    @Test
    void failedTimingObserverDoesNotChangeCommittedOutcome() throws Exception
    {
        Path database = Format61TestDatabase.create(directory.resolve("observer.sqlite"));
        var result = ApplicationDatabaseMigrator.migrateInPlace(database, line -> {
            if(line.startsWith("TIMING:")) throw new IllegalStateException("timing observer failed");
        });
        assertTrue(result.helperOutput().contains("RESULT: In-place application database update complete."));
        assertCompleted(result.helperOutput(), "Commit database update");
    }

    private static void assertCompleted(String report, String label)
    {
        assertTrue(report.lines().anyMatch(line -> line.matches("TIMING: " + java.util.regex.Pattern.quote(label) +
            " — [0-9]+ ms \\(completed\\)")), report);
    }
}
