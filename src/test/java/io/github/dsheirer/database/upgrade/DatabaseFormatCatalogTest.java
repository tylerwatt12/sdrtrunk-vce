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
    private static final int FROZEN_PRE_FORMAT_5_MAXIMUM_VERSION = 4;
    private static final Set<String> FROZEN_PRE_FORMAT_5_FINGERPRINTS = Set.of(
        "ef9197c7cee7261cdda03a395b6552754f3607f6c0053acbe21c273e4242ce3a",
        "38294d5173dbaa550b7818006f09b9d2b83fe3c2bae1ba15b6c56416d8fd69dc",
        "d1a300bf3cfc32870a36c6c4d009d5eb3ae0fea794782357ed2ea3c2948d270d",
        "d4b539e9486d81c0d21ec7816a8a1a0c07d7274dd45f659e44155beef404c3f1");
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
        assertTrue(DatabaseFormatCatalog.current().migrationPolicy().stream()
            .anyMatch(policy -> policy.contains("limit of 16 scan lists")));

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
        Path database = Format7TestDatabase.create(mTemporaryFolder.resolve("current.sqlite"));

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
    void genuineFormat5IsOutsideTheFrozenPreFormat5AcceptanceBoundary() throws Exception
    {
        Path database = Format5TestDatabase.create(mTemporaryFolder.resolve("format-5-boundary.sqlite"));
        try(Connection connection = open(database))
        {
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            int marker = Integer.parseInt(scalar(connection, """
                SELECT value FROM database_metadata WHERE key='database_format_version'
                """));

            assertEquals("cc4ab232780c6445865d86c69d4f04eb43f4f6064cf9e6770ff1405c4da32080",
                fingerprint);
            assertEquals(5, marker);
            assertTrue(marker > FROZEN_PRE_FORMAT_5_MAXIMUM_VERSION);
            assertFalse(FROZEN_PRE_FORMAT_5_FINGERPRINTS.contains(fingerprint));
        }
    }

    @Test
    void format5RequiresCanonicalSiteGuidForTrunkedChannels() throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve("format-6-channel-constraints.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, radres_guid, config_json)
                VALUES ('11111111-2222-4333-8444-555555555555', 'TRUNKED', 0, NULL, '{}')
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, radres_guid, config_json)
                VALUES ('11111111-2222-4333-8444-555555555555', 'TRUNKED', 0,
                        'AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE', '{}')
                """));
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, radres_guid, config_json)
                VALUES ('67676767-7777-4888-8999-aaaaaaaaaaaa', 'CONVENTIONAL', 100, NULL, '{}')
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, auto_start, config_json)
                VALUES ('77777777-7777-4777-8777-777777777777', 'CONVENTIONAL', 1, 2, '{}')
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, auto_start, config_json)
                VALUES ('88888888-8888-4888-8888-888888888888', 'CONVENTIONAL', 2, 0.5, '{}')
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, auto_start_order,
                                                  config_json)
                VALUES ('99999999-9999-4999-8999-999999999999', 'CONVENTIONAL', 3, 2147483648, '{}')
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, auto_start_order,
                                                  config_json)
                VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'CONVENTIONAL', 4, 1.5, '{}')
                """));
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, auto_start,
                                                  auto_start_order, config_json)
                VALUES ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'CONVENTIONAL', 5, 1, 2147483647, '{}')
                """));
        }
    }

    @Test
    void format6RejectsALegacyKeyForAConfiguredConventionalContext() throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve("format-6-legacy-context.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE receiver_context
                SET context_key='GUID:bbbbbbbb-cccc-4ddd-8eee-ffffffffffff'
                WHERE id=900
                """);

            SQLException catalog = assertThrows(SQLException.class,
                () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(catalog.getMessage().contains("uses noncanonical key"));
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
    void frozenFormat4FixtureContainsOnlyTheDeclaredSyntheticMigrationInventory() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("format-4.sqlite"));

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
            assertEquals(4, detected.version());
            assertTrue(detected.markerPresent());
            assertEquals("admin,listener,operator", scalar(connection, """
                SELECT group_concat(username, ',') FROM (
                    SELECT json_extract(settings_json, '$.primaryAdmin.username') AS username
                    FROM application_settings WHERE key='web.access.v1'
                    UNION ALL
                    SELECT json_extract(value, '$.credential.username') AS username
                    FROM application_settings, json_each(settings_json, '$.users')
                    WHERE application_settings.key='web.access.v1'
                    ORDER BY username
                )
                """));
            assertEquals("2", scalar(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals("4", scalar(connection, "SELECT COUNT(*) FROM alias_list"));
            assertEquals("4", scalar(connection, "SELECT COUNT(*) FROM application_settings"));
            assertEquals("{\"preserved\":true}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='format-4-preserve-sentinel'
                """));
            assertEquals("2", scalar(connection, """
                SELECT json_array_length(json_extract(settings_json, '$.users'))
                FROM application_settings WHERE key='web.access.v1'
                """));
            assertEquals("USER:USER", scalar(connection, """
                SELECT json_extract(settings_json, '$.policyOverrides.dashboard') || ':' ||
                       json_extract(settings_json, '$.policyOverrides.site-access')
                FROM application_settings WHERE key='web.access.v1'
                """));
            assertEquals("0", scalar(connection, """
                SELECT json_extract(settings_json, '$.show_encryption_details')
                FROM application_settings WHERE key='web.display.v1'
                """));
        }
    }

    @Test
    void unmarkedEmptyCurrentLayoutIsRefusedBecauseFormatsSixAndSevenAreSemanticallyAmbiguous() throws Exception
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
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(exception.getMessage().contains("ambiguous across formats [6, 7]"), exception::getMessage);
            assertTrue(exception.getMessage().contains("authoritative database_format_version marker is required"),
                exception::getMessage);
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
    void currentCatalogRefusesAMarkerBeyondItsRegisteredVersion() throws Exception
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

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); var resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
