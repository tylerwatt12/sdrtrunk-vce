/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.ActivityDatabasePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationApprovalReuseTest
{
    @TempDir Path directory;

    @Test
    void identicalCanonicalSnapshotReusesTheExactReviewedConfigurationOnlyApproval() throws Exception
    {
        Path source = Format62TestDatabase.create(directory.resolve("source.sqlite"));
        var expected = ApplicationMigrationService.readMigrationApproval(source, directory)
            .withContent(MigrationContent.CONFIGURATION_ONLY);
        Path staged = directory.resolve("staged.sqlite");
        SqliteDatabaseSnapshot.createExternal(source, staged);

        var verified = ApplicationMigrationService.verifyStagedApproval(expected, staged, true);

        assertSame(expected, verified, "An identical canonical snapshot retains the already-reviewed plan");
        assertSame(expected.plan(), verified.plan());
        assertEquals(MigrationContent.CONFIGURATION_ONLY, verified.content());
        assertEquals(MigrationContent.CONFIGURATION_ONLY, verified.plan().content());
    }

    @Test
    void changedSourceRowsRefuseEvenWhenTheFrozenSchemaAndDeclaredPlanStillMatch() throws Exception
    {
        Path source = Format62TestDatabase.create(directory.resolve("source.sqlite"));
        var expected = ApplicationMigrationService.readMigrationApproval(source, directory)
            .withContent(MigrationContent.CONFIGURATION_ONLY);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            var statement = connection.createStatement())
        {
            assertTrue(statement.executeUpdate("UPDATE alias SET name='Changed after review' " +
                "WHERE id=(SELECT min(id) FROM alias)") > 0);
            assertEquals(expected.plan().source(), DatabaseFormatCatalog.inspectForMigration(connection));
        }
        byte[] changedSource = Files.readAllBytes(source);
        Path staged = directory.resolve("staged.sqlite");
        SqliteDatabaseSnapshot.createExternal(source, staged);

        assertThrows(IOException.class, () ->
            ApplicationMigrationService.verifyStagedApproval(expected, staged, true));

        assertArrayEquals(changedSource, Files.readAllBytes(source));
    }

    @Test
    void damagedCanonicalSnapshotRefusesByDigestBeforeItCanBeReinspectedOrMigrated() throws Exception
    {
        Path source = Format62TestDatabase.create(directory.resolve("source.sqlite"));
        var expected = ApplicationMigrationService.readMigrationApproval(source, directory);
        byte[] unchangedSource = Files.readAllBytes(source);
        Path staged = directory.resolve("staged.sqlite");
        SqliteDatabaseSnapshot.createExternal(source, staged);
        Files.write(staged, new byte[]{1, 2, 3});

        //Schema inspection would throw SQLException for these bytes. Digest refusal must come first.
        assertThrows(IOException.class, () ->
            ApplicationMigrationService.verifyStagedApproval(expected, staged, true));

        assertArrayEquals(unchangedSource, Files.readAllBytes(source));
    }

    @Test
    void matchingConfigurationCannotApproveAChangedActivityCompanion() throws Exception
    {
        Path source = Format63TestDatabase.create(Files.createDirectory(directory.resolve("source"))
            .resolve("sdrtrunk.sqlite"));
        var expected = ApplicationMigrationService.readMigrationApproval(source, directory);
        Path staged = Files.createDirectory(directory.resolve("stage")).resolve("sdrtrunk.sqlite");
        SqliteDatabaseSnapshot.createExternal(source, staged);
        byte[] configurationBefore = Files.readAllBytes(staged);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" +
            ActivityDatabasePath.forConfiguration(staged)); var statement = connection.createStatement())
        {
            assertTrue(statement.executeUpdate("UPDATE receiver_channel SET last_seen_ms=last_seen_ms+1") > 0);
        }
        assertArrayEquals(configurationBefore, Files.readAllBytes(staged));

        assertThrows(IOException.class, () ->
            ApplicationMigrationService.verifyStagedApproval(expected, staged, true));
    }

    @Test
    void noncanonicalInjectedSnapshotsRetainIndependentInspectionAndCanonicalComparison() throws Exception
    {
        Path source = Format62TestDatabase.create(directory.resolve("source.sqlite"));
        var expected = ApplicationMigrationService.readMigrationApproval(source, directory)
            .withContent(MigrationContent.CONFIGURATION_ONLY);
        Path staged = directory.resolve("staged.sqlite");
        Files.copy(source, staged);

        var verified = ApplicationMigrationService.verifyStagedApproval(expected, staged, false);

        assertNotSame(expected, verified);
        assertEquals(expected.plan(), verified.plan());
        assertEquals(MigrationContent.CONFIGURATION_ONLY, verified.content());
    }
}
