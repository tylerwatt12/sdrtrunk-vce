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
package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SdrTrunkDatabaseBootstrapTest
{
    @Test
    void exposesOnlyTheReleaseOwnedMigrationSummaryLine()
    {
        String summary = "Migrated database format 1 [alpha8-shared] to 2 [scan-lists-p25-v26] through " +
            "1 step(s): transformed/defaulted 4, reset 5, and dropped 6 counted row(s).";
        String helperOutput = "Checking staged database: /private/source\n" +
            "credential-like-noise=do-not-display\n" + summary + "\n" +
            "RESULT: Application database migration and validation complete.\n";

        assertEquals(summary,
            SdrTrunkDatabaseBootstrap.migrationSummary(helperOutput).orElseThrow());
        assertTrue(SdrTrunkDatabaseBootstrap.migrationSummary("unrelated output").isEmpty());
        assertTrue(SdrTrunkDatabaseBootstrap.migrationSummary(null).isEmpty());
    }

    @Test
    void rejectsMalformedOrUnboundedSummaryLines()
    {
        assertTrue(SdrTrunkDatabaseBootstrap.migrationSummary(
            "Migrated database format 1 [unexpected] @ bad").isEmpty());
        assertTrue(SdrTrunkDatabaseBootstrap.migrationSummary(
            "Migrated database format " + "x".repeat(8_192)).isEmpty());
    }
}
