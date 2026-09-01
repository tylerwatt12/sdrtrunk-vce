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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void readsTheExactFormat1PlanAndDeclaredEffects() throws Exception
    {
        Path database = Format1TestDatabase.create(mTemporaryFolder.resolve("format-1.sqlite"));

        DatabaseMigrationChain.PreflightReport plan =
            ApplicationMigrationService.readMigrationPlan(database);

        assertEquals(1, plan.source().version());
        assertEquals(2, plan.target().version());
        assertEquals(1, plan.steps().size());
        assertEquals("format-1-to-2", plan.steps().getFirst().id());
        assertTrue(ApplicationMigrationService.describePlan(plan).contains("P25 affiliation history"));
    }

    @Test
    void markerlessFormat2PlansOnlyMarkerAdoption() throws Exception
    {
        Path database = Format2TestDatabase.create(mTemporaryFolder.resolve("format-2.sqlite"));

        DatabaseMigrationChain.PreflightReport plan =
            ApplicationMigrationService.readMigrationPlan(database);

        assertEquals(2, plan.source().version());
        assertFalse(plan.source().markerPresent());
        assertEquals(1, plan.steps().size());
        assertEquals("adopt-global-format-marker", plan.steps().getFirst().id());
    }

    @Test
    void refusesOverlappingPortableProfileRootsBeforeCreatingStages() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("source");
        Path sourceDatabase = Format1TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(sourceRoot));
        byte[] before = sha256(sourceDatabase);
        Path targetRoot = sourceRoot.resolve("nested-target");

        IOException failure = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().importPrevious(sourceRoot, targetRoot, null));

        assertTrue(failure.getMessage().contains("overlap"));
        assertArrayEquals(before, sha256(sourceDatabase));
        assertFalse(Files.exists(targetRoot));
    }

    private static byte[] sha256(Path path) throws Exception
    {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
