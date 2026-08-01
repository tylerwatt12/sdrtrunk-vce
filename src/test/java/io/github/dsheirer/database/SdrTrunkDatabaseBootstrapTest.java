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
        String summary = "Alpha 7 migration: aliases=10, alias lists=2, DMR conventional channels=1, " +
            "DMR trunked channels=2, NXDN trunked channels=3; activity, statistics, site observations, " +
            "identities, affiliations, and quality history reset; new activity starts empty; " +
            "planned removals/collapses: alias actions removed=7.";
        String helperOutput = "Checking staged database: /private/source\n" +
            "credential-like-noise=do-not-display\n" + summary + "\n" +
            "RESULT: Application database migration and validation complete.\n";

        assertEquals(summary,
            SdrTrunkDatabaseBootstrap.alpha7MigrationSummary(helperOutput).orElseThrow());
        assertTrue(SdrTrunkDatabaseBootstrap.alpha7MigrationSummary("unrelated output").isEmpty());
        assertTrue(SdrTrunkDatabaseBootstrap.alpha7MigrationSummary(null).isEmpty());
    }

    @Test
    void rejectsMalformedOrUnboundedSummaryLines()
    {
        assertTrue(SdrTrunkDatabaseBootstrap.alpha7MigrationSummary(
            "Alpha 7 migration: aliases=1 [unexpected]").isEmpty());
        assertTrue(SdrTrunkDatabaseBootstrap.alpha7MigrationSummary(
            "Alpha 7 migration: " + "x".repeat(8_192)).isEmpty());
    }
}
