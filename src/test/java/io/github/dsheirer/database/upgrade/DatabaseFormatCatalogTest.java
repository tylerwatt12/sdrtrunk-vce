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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseFormatCatalogTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void descriptorsHaveUniqueContinuousVersionsIdsAndFixtures() throws Exception
    {
        Set<String> ids = new HashSet<>();
        Set<String> fixtures = new HashSet<>();
        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.formats().size());

        for(int index = 0; index < DatabaseFormatCatalog.formats().size(); index++)
        {
            DatabaseFormatCatalog.FormatDescriptor descriptor = DatabaseFormatCatalog.formats().get(index);
            assertEquals(index + 1, descriptor.version());
            assertTrue(ids.add(descriptor.id()), "Duplicate format id: " + descriptor.id());
            assertTrue(fixtures.add(descriptor.fixtureResource()),
                "Duplicate format fixture: " + descriptor.fixtureResource());
            assertTrue(java.nio.file.Files.isRegularFile(Path.of(descriptor.fixtureResource())),
                "Missing format fixture factory: " + descriptor.fixtureResource());
            assertFalse(descriptor.sourceReferences().isEmpty());
            assertFalse(descriptor.migrationPolicy().isEmpty());
        }

        assertEquals(DatabaseFormatCatalog.requireVersion(DatabaseFormatCatalog.CURRENT_VERSION),
            DatabaseFormatCatalog.current());

        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 1, DatabaseMigrationChain.steps().size());
        for(int index = 0; index < DatabaseMigrationChain.steps().size(); index++)
        {
            DatabaseMigrationChain.StepDescriptor step = DatabaseMigrationChain.steps().get(index);
            assertEquals(index + 1, step.sourceVersion());
            assertEquals(step.sourceVersion() + 1, step.targetVersion());
            assertFalse(step.id().isBlank());
            assertFalse(step.declaredEffects().isEmpty());
        }
    }

    @Test
    void exactFormat1LegacyFixtureIsRecognizedWithoutMarker() throws Exception
    {
        Path database = Format1TestDatabase.create(mTemporaryFolder.resolve("format-1.sqlite"));

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            assertEquals(1, detected.version());
            assertEquals("alpha8-shared", detected.id());
            assertFalse(detected.markerPresent());
            assertTrue(detected.requiresMigration());
            assertEquals(DatabaseFormatCatalog.requireVersion(1).fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test
    void exactFormat2PublishedNightlyFixtureIsRecognizedWithoutMarker() throws Exception
    {
        Path database = Format2TestDatabase.create(mTemporaryFolder.resolve("format-2.sqlite"));

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            assertEquals(2, detected.version());
            assertEquals("scan-lists-p25-v26", detected.id());
            assertFalse(detected.markerPresent());
            assertTrue(detected.requiresMigration());
            assertEquals(DatabaseFormatCatalog.requireVersion(2).fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test
    void freshDatabaseHasExactCurrentFingerprintAndMarker() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("current.sqlite"));

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.requireCurrent(connection);
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, detected.version());
            assertEquals(DatabaseFormatCatalog.current().id(), detected.id());
            assertTrue(detected.markerPresent());
            assertFalse(detected.requiresMigration());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test
    void exactPopulatedFormat3FixtureIsRecognizedAndRequiresMigration() throws Exception
    {
        Path database = Format3TestDatabase.create(mTemporaryFolder.resolve("format-3.sqlite"));

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            assertEquals(3, detected.version());
            assertEquals("p25-site-projection-v27", detected.id());
            assertTrue(detected.markerPresent());
            assertTrue(detected.requiresMigration());
            assertEquals(DatabaseFormatCatalog.requireVersion(3).fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test
    void exactUnmarkedCurrentLayoutGetsAMarkerAdoptionPlan() throws Exception
    {
        Path database = mTemporaryFolder.resolve("unmarked-current.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database);
            var statement = connection.prepareStatement("DELETE FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            assertEquals(1, statement.executeUpdate());
        }

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, detected.version());
            assertFalse(detected.markerPresent());
            assertTrue(detected.requiresMigration());
            DatabaseMigrationChain.PreflightReport plan = DatabaseMigrationChain.validateSource(connection,
                detected);
            assertEquals(1, plan.steps().size());
            assertEquals("adopt-global-format-marker", plan.steps().getFirst().id());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, plan.steps().getFirst().sourceVersion());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, plan.steps().getFirst().targetVersion());
        }
    }

    @Test
    void refusesMetadataMismatchEvenWhenTheSchemaFingerprintMatches() throws Exception
    {
        Path database = Format1TestDatabase.create(mTemporaryFolder.resolve("metadata-mismatch.sqlite"));
        updateMetadata(database, "alias_schema_version", "5");

        try(Connection connection = open(database))
        {
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(exception.getMessage().contains("metadata [alias_schema_version]"));
            assertTrue(exception.getMessage().contains("mixed or partially migrated"));
        }
    }

    @Test
    void refusesMarkerThatDoesNotMatchTheExactSchema() throws Exception
    {
        Path database = mTemporaryFolder.resolve("marker-mismatch.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        updateMetadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY, "1");

        try(Connection connection = open(database))
        {
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(exception.getMessage().contains("does not match schema fingerprint"));
            assertTrue(exception.getMessage().contains("mixed or partially migrated"));
        }
    }

    @Test
    void olderBuildRefusesAValidNewerGlobalMarker() throws Exception
    {
        Path database = mTemporaryFolder.resolve("newer-marker.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        updateMetadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY,
            Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION + 1));

        try(Connection connection = open(database))
        {
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(exception.getMessage().contains("is newer than this build supports"));
        }
    }

    @Test
    void refusesUnknownSchemaFingerprint() throws Exception
    {
        Path database = mTemporaryFolder.resolve("unknown.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE unknown_format_object(id INTEGER PRIMARY KEY)");
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(exception.getMessage().contains("Unrecognized SQLite database schema fingerprint"));
        }
    }

    @Test
    void refusesMissingRequiredMetricsTimestamp() throws Exception
    {
        Path database = Format1TestDatabase.create(mTemporaryFolder.resolve("missing-metrics.sqlite"));
        updateMetadata(database, "trunked_identity_metrics_started_at_ms", "0");

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.FormatRejectionException exception = assertThrows(
                DatabaseFormatCatalog.FormatRejectionException.class,
                () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(exception.getMessage().contains("must be a positive timestamp"));
        }
    }

    @Test
    void refusesFormat2WithoutExactlyOneDefaultScanList() throws Exception
    {
        Path database = Format2TestDatabase.create(mTemporaryFolder.resolve("missing-default.sqlite"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE scan_list SET is_default=0 WHERE is_default=1");
            DatabaseFormatCatalog.FormatRejectionException exception = assertThrows(
                DatabaseFormatCatalog.FormatRejectionException.class,
                () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(exception.getMessage().contains("exactly one Default scan list"));
        }
    }

    private static void updateMetadata(Path database, String key, String value) throws Exception
    {
        try(Connection connection = open(database);
            var statement = connection.prepareStatement(
                "UPDATE database_metadata SET value=? WHERE key=?"))
        {
            statement.setString(1, value);
            statement.setString(2, key);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
