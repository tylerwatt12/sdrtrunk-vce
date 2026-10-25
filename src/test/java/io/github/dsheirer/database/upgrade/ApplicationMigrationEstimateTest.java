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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationEstimateTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void reportedThreeGiBExampleHasABroadRangeContainingEightMinutes()
    {
        String warning = ApplicationMigrationEstimate.fullMigrationWarning(3_135_460L * 1024);
        assertEquals("Rough estimate: 4–20 minutes. It may take longer.", warning);
    }

    @Test
    void largerDatabasesWarnAboutHoursAndDaysWithoutClaimingADeadline()
    {
        String warning = ApplicationMigrationEstimate.fullMigrationWarning(10L * 3_135_460L * 1024);
        assertTrue(warning.contains("40 minutes–4 hours"));
        assertTrue(ApplicationMigrationEstimate.fullMigrationWarning(Long.MAX_VALUE).contains("days"));
        assertFalse(warning.contains("will finish"));
    }

    @Test
    void smallOrUnknownSizesAvoidAFalselyPreciseCountdown()
    {
        assertTrue(ApplicationMigrationEstimate.fullMigrationWarning(128L * 1024 * 1024)
            .contains("Allow a few minutes"));
        assertTrue(ApplicationMigrationEstimate.fullMigrationWarning(0).contains("Time estimate unavailable"));
        assertTrue(ApplicationMigrationEstimate.fullMigrationWarning(-1).contains("Time estimate unavailable"));
    }

    @Test
    void countsOnlyTheApplicationFileAndItsJournalsWithoutOpeningSqlite() throws Exception
    {
        Path database = Files.write(mTemporaryFolder.resolve("sdrtrunk.sqlite"), new byte[100]);
        Files.write(mTemporaryFolder.resolve("sdrtrunk.sqlite-wal"), new byte[40]);
        Files.write(mTemporaryFolder.resolve("sdrtrunk.sqlite-journal"), new byte[20]);
        Files.write(mTemporaryFolder.resolve("sdrtrunk.sqlite-shm"), new byte[10]);
        Files.write(mTemporaryFolder.resolve("managed-recordings.sqlite"), new byte[200]);
        assertEquals(160, ApplicationMigrationEstimate.databaseFootprint(database).orElseThrow());
    }

    @Test
    void missingSidecarsAreNormalAndMissingDatabaseDoesNotBlockReview() throws Exception
    {
        Path database = Files.write(mTemporaryFolder.resolve("source.sqlite"), new byte[64]);
        assertEquals(64, ApplicationMigrationEstimate.databaseFootprint(database).orElseThrow());
        Files.delete(database);
        assertTrue(ApplicationMigrationEstimate.fullMigrationWarning(database).contains("Time estimate unavailable"));
        assertTrue(ApplicationMigrationEstimate.databaseFootprint(mTemporaryFolder).isEmpty());
        assertTrue(ApplicationMigrationEstimate.databaseFootprint(null).isEmpty());
    }
}
