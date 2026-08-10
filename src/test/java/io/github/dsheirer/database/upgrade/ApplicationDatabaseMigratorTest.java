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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationDatabaseMigratorTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void validatesExactCurrentStagedDatabaseWithoutChangingSchema() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("already current and valid"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = open(database))
        {
            assertEquals(Integer.toString(SdrTrunkDatabaseSchema.ALIAS_SCHEMA_VERSION),
                metadata(connection, "alias_schema_version"));
            assertEquals(Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION),
                metadata(connection, "p25_activity_schema_version"));
            assertEquals("2", metadata(connection, "trunked_site_schema_version"));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void migratesExactPublishedAlpha9DirectlyToCurrentSchema() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9MigrationCases(database);
        Path sourceRoot = mTemporaryFolder.resolve("alpha9-source").toAbsolutePath();
        Path targetRoot = mTemporaryFolder.resolve("alpha10-target").toAbsolutePath();

        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms)
            VALUES ('portable_java_preferences_v1', ?, 1)
            """))
        {
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(java.util.Map.of("directories",
                java.util.Map.of("directory.recording", sourceRoot.resolve("recordings").toString()))));
            statement.executeUpdate();
        }

        CommandResult result = run(database, sourceRoot, targetRoot);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("Alpha 8/Alpha 9 layout migration"));
        assertTrue(result.output().contains("converted 4 unmatched-talkgroup"));
        assertTrue(result.output().contains("removed 1 retired fully-qualified talkgroup"));
        assertTrue(result.output().contains("1 retired fully-qualified radio"));
        assertTrue(result.output().contains("preserved 3 current P25 affiliation"));
        assertTrue(result.output().contains("without inventing site presence"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertEquals("5", metadata(connection, "alias_schema_version"));
            assertEquals(Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION),
                metadata(connection, "p25_activity_schema_version"));
            assertEquals("-1:1", scalar(connection, """
                SELECT unmatched_talkgroup_priority || ':' || unmatched_talkgroup_record_enabled
                FROM alias_list WHERE id=1
                """));
            assertEquals("100:0", scalar(connection, """
                SELECT unmatched_talkgroup_priority || ':' || unmatched_talkgroup_record_enabled
                FROM alias_list WHERE id=2
                """));
            assertEquals("4:1|6:0|9:1", scalar(connection, """
                SELECT group_concat(policy, '|')
                FROM (
                    SELECT unmatched_talkgroup_priority || ':' ||
                           unmatched_talkgroup_record_enabled AS policy
                    FROM alias_list
                    WHERE id IN (5, 6, 7)
                    ORDER BY id
                )
                """));
            assertEquals("Safe Stream", scalar(connection, """
                SELECT channel_name FROM alias_list_unmatched_talkgroup_stream WHERE alias_list_id=1
                """));
            assertEquals("5:Phase 2 Stream|6:DMR Stream|7:NXDN Stream", scalar(connection, """
                SELECT group_concat(route, '|')
                FROM (
                    SELECT alias_list_id || ':' || channel_name AS route
                    FROM alias_list_unmatched_talkgroup_stream
                    WHERE alias_list_id IN (5, 6, 7)
                    ORDER BY alias_list_id
                )
                """));
            assertEquals("8", scalar(connection, "SELECT COUNT(*) FROM alias"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM alias
                WHERE matcher_type IN (
                    'P25_FULLY_QUALIFIED_TALKGROUP', 'P25_FULLY_QUALIFIED_RADIO_ID'
                )
                """));
            assertEquals("Keep this appearance", scalar(connection,
                "SELECT description FROM alias WHERE id=104"));
            assertEquals("Keep Group|7|Keep Icon", scalar(connection, """
                SELECT (SELECT group_name FROM alias WHERE id=111) || '|' ||
                       (SELECT color FROM alias WHERE id=112) || '|' ||
                       (SELECT icon_name FROM alias WHERE id=113)
                """));
            assertEquals("3", scalar(connection, """
                SELECT COUNT(*) FROM alias WHERE id IN (105, 106, 107)
                """));
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*) FROM alias_broadcast_channel
                WHERE id=203 AND alias_id=103 AND channel_name='Retained Stream'
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM alias_broadcast_channel
                WHERE id IN (201, 202)
                """));
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM trunked_identity_scope_context"));
            assertEquals("5", scalar(connection, "SELECT COUNT(*) FROM trunked_identity_summary"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope WHERE protocol_code IN (3, 4)
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary WHERE identity_id=999
                """));
            assertEquals("2", scalar(connection, """
                SELECT COUNT(*) FROM receiver_context WHERE protocol_code IN (3, 4)
                """));
            assertEquals("0", scalar(connection,
                "SELECT COUNT(*) FROM p25_zero_local_fq_talkgroup_summary"));
            assertEquals("3", scalar(connection,
                "SELECT COUNT(*) FROM trunked_radio_talkgroup_summary"));
            assertEquals("3", scalar(connection, "SELECT COUNT(*) FROM trunked_radio_affiliation"));
            assertEquals("1800001:43:8000|1800002:44:8500|1800003:43:9000", scalar(connection, """
                SELECT group_concat(affiliation, '|')
                FROM (
                    SELECT radio_id || ':' || talkgroup_id || ':' || confirmed_at_ms AS affiliation
                    FROM trunked_radio_affiliation
                    ORDER BY radio_id
                )
                """));
            assertEquals("1:43:1|1:44:1|2:1800001:0|2:1800002:0|2:1800003:0", scalar(connection, """
                SELECT group_concat(identity, '|')
                FROM (
                    SELECT identity_kind_code || ':' || identity_id || ':' || p25_identity_state_code AS identity
                    FROM trunked_identity_summary
                    ORDER BY identity_kind_code, identity_id
                )
                """));
            assertEquals("8000:9000:2", scalar(connection, """
                SELECT first_seen_ms || ':' || last_seen_ms || ':' || join_count
                FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=43
                """));
            assertEquals("8000:8000:1", scalar(connection, """
                SELECT first_seen_ms || ':' || last_seen_ms || ':' || join_count
                FROM trunked_identity_summary
                WHERE identity_kind_code=2 AND identity_id=1800001
                """));
            assertEquals("8000:8000:1", scalar(connection, """
                SELECT first_seen_ms || ':' || last_seen_ms || ':' || join_count
                FROM trunked_radio_talkgroup_summary
                WHERE radio_id=1800001 AND talkgroup_id=43 AND target_kind_code=1
                """));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM trunked_radio_site_presence"));
            assertEquals("0", scalar(connection,
                "SELECT COUNT(*) FROM trunked_radio_presence_lifecycle"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM sqlite_schema
                WHERE name IN ('p25_radio_affiliation', 'idx_p25_radio_affiliation_talkgroup')
                """));
            assertFalse("1234".equals(metadata(connection,
                P25ActivityLogSchema.TRUNKED_IDENTITY_METRICS_STARTED_AT_KEY)));
            assertEquals("Preserved Channel", scalar(connection,
                "SELECT name FROM configuration_channel WHERE id=77"));
            assertEquals("preserved-context", scalar(connection,
                "SELECT context_key FROM receiver_context WHERE id=50"));
            assertEquals("92.5", scalar(connection, """
                SELECT decode_health_pct FROM p25_control_channel_quality WHERE guid='preserved-quality'
                """));
            assertEquals("{\"preserved\":true}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='migration-sentinel'
                """));
            JsonNode preferences = OBJECT_MAPPER.readTree(scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(targetRoot.resolve("recordings").toString(),
                preferences.path("directories").path("directory.recording").asText());
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));

            long insertedId;
            try(ResultSet resultSet = statement.executeQuery("""
                INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
                VALUES (1, 'Sequence Check', 'TALKGROUP', 'APCO25', 99)
                RETURNING id
                """))
            {
                assertTrue(resultSet.next());
                insertedId = resultSet.getLong(1);
            }
            assertTrue(insertedId > 900, "Retired high-water alias IDs must not be reused");

            long insertedScopeId;
            try(ResultSet resultSet = statement.executeQuery("""
                INSERT INTO trunked_identity_scope(
                    scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    first_seen_ms, last_seen_ms
                ) VALUES ('post-migration:dmr', 3, 2, 0, 10000, 10000)
                RETURNING scope_id
                """))
            {
                assertTrue(resultSet.next());
                insertedScopeId = resultSet.getLong(1);
            }
            assertTrue(insertedScopeId > 901, "Reset identity-scope IDs must not be reused");
        }

        Path exactCurrent = mTemporaryFolder.resolve("exact-current.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(exactCurrent);
        try(Connection migrated = open(database); Connection current = open(exactCurrent))
        {
            assertEquals(SqliteSchemaValidator.fingerprint(current), SqliteSchemaValidator.fingerprint(migrated),
                "The direct Alpha 9 transition must produce the exact current schema, not a compatibility layout");
        }
    }

    @Test
    void refusesAlpha9VersionStampsOnTheWrongSchema() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE unexpected_alpha9_object(id INTEGER PRIMARY KEY)");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("not the exact shared v0.6.2 Alpha 8/Alpha 9 schema layout"));
        try(Connection connection = open(database))
        {
            assertEquals("4", metadata(connection, "alias_schema_version"));
            assertEquals("24", metadata(connection, "p25_activity_schema_version"));
        }
    }

    @Test
    void refusesAlpha9AffiliationWithoutAProtocolNeutralScope() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (70, 781824, 840, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (70, 1800001, 43, 2000)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("without a protocol-neutral P25 scope"));
        assertEquals("4", metadata(database, "alias_schema_version"));
        assertEquals("24", metadata(database, "p25_activity_schema_version"));
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM p25_radio_affiliation"));
    }

    @Test
    void refusesAlpha9AffiliationWithANonstandardP25IdentityDomain() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9P25Scope(database, 70);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE trunked_identity_scope SET identity_domain_code=1 WHERE scope_id=70");
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (70, 1800001, 43, 2000)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("without a protocol-neutral P25 scope"));
        assertEquals("24", metadata(database, "p25_activity_schema_version"));
        assertEquals("1", scalar(database, "SELECT identity_domain_code FROM trunked_identity_scope"));
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM p25_radio_affiliation"));
    }

    @Test
    void refusesAlpha9AffiliationWithAReservedP25Identity() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9P25Scope(database, 70);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (70, 16777212, 43, 2000)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("reserved identities"));
        assertEquals("24", metadata(database, "p25_activity_schema_version"));
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM p25_radio_affiliation"));
    }

    @Test
    void refusesAlpha9AffiliationWithoutAValidConfirmationTime() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9P25Scope(database, 70);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (70, 1800001, 43, 0)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("invalid confirmation times"));
        assertEquals("24", metadata(database, "p25_activity_schema_version"));
        assertEquals("0", scalar(database,
            "SELECT updated_at_ms FROM p25_radio_affiliation WHERE system_key=70 AND radio_id=1800001"));
    }

    @Test
    void refusesAlpha9AffiliationWithNonIntegerStorageClasses() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9P25Scope(database, 70);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (70, 1800001.5, 43.5, 'not-a-time')
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("non-integer values"));
        assertEquals("4", metadata(database, "alias_schema_version"));
        assertEquals("24", metadata(database, "p25_activity_schema_version"));
        assertEquals("real:real:text", scalar(database, """
            SELECT typeof(radio_id) || ':' || typeof(talkgroup_id) || ':' || typeof(updated_at_ms)
            FROM p25_radio_affiliation WHERE system_key=70
            """));
    }

    @Test
    void usesIndexBackedBoundedIdentityAdmissionChecks() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9P25Scope(database, 70);
        boolean usesAffiliationIndex = false;

        try(Connection connection = open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "EXPLAIN QUERY PLAN " + Alpha9DatabaseMigration.IDENTITY_ADMISSION_CAP_QUERY))
        {
            while(resultSet.next())
            {
                String detail = resultSet.getString(4);
                assertFalse(detail.contains("TEMP B-TREE"), detail);
                usesAffiliationIndex |= detail.contains("idx_p25_radio_affiliation_talkgroup");
            }
        }

        assertTrue(usesAffiliationIndex, "Identity admission must scan each bounded system-key index slice");
    }

    @Test
    void refusesAlpha9AffiliationsAboveTheBoundedCurrentStateCap() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9P25Scope(database, 70);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE radio_ids(value) AS (
                    VALUES (1)
                    UNION ALL
                    SELECT value + 1 FROM radio_ids WHERE value < 100001
                )
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                SELECT 70, value, 43, 2000 FROM radio_ids
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("current-affiliation admission cap"));
        assertEquals("24", metadata(database, "p25_activity_schema_version"));
        assertEquals("100001", scalar(database, "SELECT COUNT(*) FROM p25_radio_affiliation"));
    }

    @Test
    void refusesAlpha9AffiliationsWhoseEndpointsExceedTheIdentityCap() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9P25Scope(database, 70);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE radio_ids(value) AS (
                    VALUES (1)
                    UNION ALL
                    SELECT value + 1 FROM radio_ids WHERE value < 100000
                )
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                SELECT 70, value, 43, 2000 FROM radio_ids
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("identity admission cap"));
        assertEquals("24", metadata(database, "p25_activity_schema_version"));
        assertEquals("100000", scalar(database, "SELECT COUNT(*) FROM p25_radio_affiliation"));
    }

    @Test
    void migratesAtTheExactIdentityAdmissionCap() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9P25Scope(database, 70);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE radio_ids(value) AS (
                    VALUES (1)
                    UNION ALL
                    SELECT value + 1 FROM radio_ids WHERE value < 99999
                )
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                SELECT 70, value, 43, 2000 FROM radio_ids
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertEquals("100000", scalar(database, "SELECT COUNT(*) FROM trunked_identity_summary"));
        assertEquals("99999", scalar(database, "SELECT COUNT(*) FROM trunked_radio_affiliation"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM trunked_radio_site_presence"));
        assertEquals("ok", scalar(database, "PRAGMA quick_check"));
    }

    @Test
    void migratesMinimumAndMaximumP25IdentitiesAcrossIndependentScopes() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9P25Scope(database, 70, 840);
        insertAlpha9P25Scope(database, 71, 841);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (70, 1, 1, 2000),
                       (71, 16777211, 65534, 3000)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertEquals("70:1:1:2000|71:16777211:65534:3000", scalar(database, """
            SELECT group_concat(value, '|')
            FROM (
                SELECT scope.p25_system_key || ':' || affiliation.radio_id || ':' ||
                       affiliation.talkgroup_id || ':' || affiliation.confirmed_at_ms AS value
                FROM trunked_radio_affiliation AS affiliation
                JOIN trunked_identity_scope AS scope ON scope.scope_id=affiliation.scope_id
                ORDER BY scope.p25_system_key
            )
            """));
        assertEquals("2", scalar(database, "SELECT COUNT(*) FROM trunked_identity_scope"));
        assertEquals("4", scalar(database, "SELECT COUNT(*) FROM trunked_identity_summary"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM trunked_radio_site_presence"));
    }

    @Test
    void rebasesPortableDirectoriesWithoutChangingSchema() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path source = mTemporaryFolder.resolve("source-data").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("target-data").toAbsolutePath();

        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms)
            VALUES ('portable_java_preferences_v1', ?, 1)
            """))
        {
            Path external = mTemporaryFolder.resolve("external-library.jar").toAbsolutePath();
            Path similarlyNamedSibling = mTemporaryFolder.resolve("source-data-other/streams").toAbsolutePath();
            Path unrecognizedInsideSource = source.resolve("private/leave-alone.txt");
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(java.util.Map.of("directories",
                java.util.Map.of("directory.recording", source.resolve("recordings").toString(),
                    "directory.application.logs", source.resolve("logs").toString(),
                    "path.jmbe.library.primary", source.resolve("jmbe/jmbe.jar").toString(),
                    "path.voice.decryption.module.primary", source.resolve("modules/voice.jar").toString(),
                    "path.jmbe.library.external", external.toString(),
                    "path.voice.decryption.module.relative", "modules/relative.jar",
                    "directory.streaming", similarlyNamedSibling.toString(),
                    "unrecognized.absolute.path", unrecognizedInsideSource.toString()))));
            statement.executeUpdate();
        }

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("Portable directory preferences updated: 4"));

        try(Connection connection = open(database))
        {
            JsonNode settings = OBJECT_MAPPER.readTree(scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(target.resolve("recordings").toString(),
                settings.path("directories").path("directory.recording").asText());
            assertEquals(target.resolve("logs").toString(),
                settings.path("directories").path("directory.application.logs").asText());
            assertEquals(target.resolve("jmbe/jmbe.jar").toString(),
                settings.path("directories").path("path.jmbe.library.primary").asText());
            assertEquals(target.resolve("modules/voice.jar").toString(),
                settings.path("directories").path("path.voice.decryption.module.primary").asText());
            assertEquals(mTemporaryFolder.resolve("external-library.jar").toAbsolutePath().toString(),
                settings.path("directories").path("path.jmbe.library.external").asText());
            assertEquals("modules/relative.jar",
                settings.path("directories").path("path.voice.decryption.module.relative").asText());
            assertEquals(mTemporaryFolder.resolve("source-data-other/streams").toAbsolutePath().toString(),
                settings.path("directories").path("directory.streaming").asText());
            assertEquals(source.resolve("private/leave-alone.txt").toString(),
                settings.path("directories").path("unrecognized.absolute.path").asText());
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void refusesUnreleasedPredecessorSchemaWithoutRepairingIt() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        removeDmrActivityVersion(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("complete current tuple"));

        try(Connection connection = open(database))
        {
            assertEquals(null, metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void refusesExactAlpha7VersionTupleWithoutChangingIt() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE database_metadata SET value='3' WHERE key='alias_schema_version'");
            statement.executeUpdate("UPDATE database_metadata SET value='21' WHERE key='p25_activity_schema_version'");
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                DmrActivitySchema.SCHEMA_VERSION_KEY + "'");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("complete current tuple"));
        try(Connection connection = open(database))
        {
            assertEquals("3", metadata(connection, "alias_schema_version"));
            assertEquals("21", metadata(connection, "p25_activity_schema_version"));
            assertEquals(null, metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void refusesOlderPublishedSchemaOutsideReleasePreparation() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, "p25_activity_schema_version", "20");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("complete current tuple"));

        try(Connection connection = open(database))
        {
            assertEquals("20", metadata(connection, "p25_activity_schema_version"));
        }
    }

    @Test
    void refusesIntermediateP25Version25WithoutChangingIt() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, "p25_activity_schema_version", "25");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("exact shared v0.6.2 Alpha 8/Alpha 9 tuple"));
        assertEquals("25", metadata(database, "p25_activity_schema_version"));
        assertEquals("1", scalar(database, """
            SELECT COUNT(*) FROM sqlite_schema
            WHERE type='table' AND name='p25_radio_affiliation'
            """));
    }

    @Test
    void malformedRelocationSettingsRollBackWithoutSchemaChanges() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path source = mTemporaryFolder.resolve("source-data").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("target-data").toAbsolutePath();

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '{invalid', 1)
                """);
        }

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertFalse(result.error().isBlank());

        try(Connection connection = open(database))
        {
            assertEquals("{invalid", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void alpha9LateRelocationFailureRollsBackTheEntireReleaseMigration() throws Exception
    {
        Path database = Alpha9TestDatabase.create(newStagedDatabase());
        insertAlpha9MigrationCases(database);
        Path source = mTemporaryFolder.resolve("alpha9-rollback-source").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("alpha9-rollback-target").toAbsolutePath();

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '{invalid', 1)
                """);
        }

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertFalse(result.error().isBlank());

        try(Connection connection = open(database))
        {
            Alpha9DatabaseMigration.validateSource(connection);
            assertEquals("4", metadata(connection, "alias_schema_version"));
            assertEquals("24", metadata(connection, "p25_activity_schema_version"));
            assertEquals("14", scalar(connection, "SELECT COUNT(*) FROM alias"));
            assertEquals("2", scalar(connection, """
                SELECT COUNT(*) FROM alias
                WHERE matcher_type IN (
                    'P25_FULLY_QUALIFIED_TALKGROUP', 'P25_FULLY_QUALIFIED_RADIO_ID'
                )
                """));
            assertEquals("15", scalar(connection, """
                SELECT (SELECT COUNT(*) FROM trunked_identity_scope) +
                       (SELECT COUNT(*) FROM trunked_identity_scope_context) +
                       (SELECT COUNT(*) FROM trunked_identity_summary) +
                       (SELECT COUNT(*) FROM trunked_radio_talkgroup_summary)
                """));
            assertEquals("3", scalar(connection, "SELECT COUNT(*) FROM p25_radio_affiliation"));
            assertEquals("{invalid", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void refusesAValidLiveDatabasePathWithoutOpeningItForMigration() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("live-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        byte[] before = Files.readAllBytes(database);

        CommandResult result = runArguments(database.toString());

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("accepts only an application-created staged database"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(database)));
    }

    @Test
    void reportsUsageAndMissingInputWithStableExitCodes()
    {
        CommandResult help = runArguments("--help");
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, help.exitCode());
        assertTrue(help.output().contains("Usage:"));

        CommandResult missing = runArguments();
        assertEquals(ApplicationDatabaseMigrator.EXIT_USAGE, missing.exitCode());
        assertTrue(missing.error().contains("staged database path"));
    }

    private Path newStagedDatabase() throws Exception
    {
        Path database = mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
        Files.createDirectories(database.getParent());
        return database;
    }

    private CommandResult run(Path database)
    {
        return runArguments(database.toString());
    }

    private CommandResult run(Path database, Path source, Path target)
    {
        return runArguments(database.toString(), source.toString(), target.toString());
    }

    private CommandResult runArguments(String... arguments)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode = ApplicationDatabaseMigrator.run(arguments, new PrintStream(output), new PrintStream(error));
        return new CommandResult(exitCode, output.toString(StandardCharsets.UTF_8),
            error.toString(StandardCharsets.UTF_8));
    }

    private static void removeDmrActivityVersion(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                DmrActivitySchema.SCHEMA_VERSION_KEY + "'");
        }
    }

    private static void insertAlpha9MigrationCases(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family) VALUES
                    (1, 'Safe', 'P25'),
                    (2, 'Styled', 'P25'),
                    (3, 'Ambiguous', 'P25'),
                    (4, 'Stream As', 'P25'),
                    (5, 'Phase 2', 'P25'),
                    (6, 'DMR', 'DMR'),
                    (7, 'NXDN', 'NXDN'),
                    (8, 'Group Styled', 'P25'),
                    (9, 'Color Styled', 'P25'),
                    (10, 'Icon Styled', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(
                    id, alias_list_id, name, description, color, record_enabled, priority,
                    matcher_type, protocol, value, min_value, max_value, wacn, p25_system_id
                ) VALUES
                    (100, 1, 'Safe Catchall', NULL, 0, 1, -1,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (800, 1, 'Retired FQ Talkgroup', NULL, 0, 0, 10,
                        'P25_FULLY_QUALIFIED_TALKGROUP', 'APCO25', 42, NULL, NULL, 781824, 840),
                    (900, 1, 'Retired FQ Radio', NULL, 0, 0, 10,
                        'P25_FULLY_QUALIFIED_RADIO_ID', 'APCO25', 1800001, NULL, NULL, 781824, 840),
                    (103, 1, 'Retained Talkgroup', NULL, 0, 1, 5,
                        'TALKGROUP', 'APCO25', 43, NULL, NULL, NULL, NULL),
                    (104, 2, 'Styled Catchall', 'Keep this appearance', 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (105, 3, 'Ambiguous A', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (106, 3, 'Ambiguous B', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25_PHASE2', NULL, 1, 65535, NULL, NULL),
                    (107, 4, 'Stream As Catchall', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (108, 5, 'Phase 2 Catchall', NULL, 0, 1, 4,
                        'TALKGROUP_RANGE', 'APCO25_PHASE2', NULL, 1, 65535, NULL, NULL),
                    (109, 6, 'DMR Catchall', NULL, 0, 0, 6,
                        'TALKGROUP_RANGE', 'DMR', NULL, 1, 16777215, NULL, NULL),
                    (110, 7, 'NXDN Catchall', NULL, 0, 1, 9,
                        'TALKGROUP_RANGE', 'NXDN', NULL, 1, 65535, NULL, NULL),
                    (111, 8, 'Group Catchall', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (112, 9, 'Color Catchall', NULL, 7, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (113, 10, 'Icon Catchall', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL)
                """);
            statement.executeUpdate("UPDATE alias SET stream_as_talkgroup=999 WHERE id=107");
            statement.executeUpdate("UPDATE alias SET group_name='Keep Group' WHERE id=111");
            statement.executeUpdate("UPDATE alias SET icon_name='Keep Icon' WHERE id=113");
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(id, alias_id, channel_name) VALUES
                    (200, 100, 'Safe Stream'),
                    (201, 800, 'Retired Talkgroup Stream'),
                    (202, 900, 'Retired Radio Stream'),
                    (203, 103, 'Retained Stream'),
                    (204, 108, 'Phase 2 Stream'),
                    (205, 109, 'DMR Stream'),
                    (206, 110, 'NXDN Stream')
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    id, sort_order, system_name, site_name, name, alias_list_name,
                    decoder_type, source_type, primary_frequency_hz, frequency_count, config_json
                ) VALUES (
                    77, 1, 'Preserved System', 'Preserved Site', 'Preserved Channel', 'Safe',
                    'P25_PHASE1', 'TUNER', 851012500, 1, '{"preserved":true}'
                )
                """);
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('migration-sentinel', '{"preserved":true}', 1000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality(
                    guid, frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs,
                    decode_health_pct, valid_frames, invalid_frames, corrected_bits,
                    sync_loss_bits, dropped_bits, last_valid_decode_ms
                ) VALUES (
                    'preserved-quality', 851012500, 1000, 2000, -72.5,
                    92.5, 100, 3, 4, 5, 6, 1900
                )
                """);
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (50, 781824, 840, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                    decoder, first_seen_ms, last_seen_ms, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz
                ) VALUES (
                    50, 'preserved-context', 'preserved-guid', 1, 1, 'Preserved Channel', 'Safe',
                    'P25-1', 1000, 9000, 50, 801, 1, 2, 851012500, 851012500
                )
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope(
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (50, 'p25:BEE00:348', 1, 1, 0, 50, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context(context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES (50, 50, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                    first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES
                    (900, 'preserved-dmr', 'preserved-dmr-guid', 1, 3, 'Preserved DMR', 'DMR',
                        1000, 9000, 451000000),
                    (901, 'preserved-nxdn', 'preserved-nxdn-guid', 1, 4, 'Preserved NXDN', 'NXDN',
                        1000, 9000, 452000000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope(
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES
                    (900, 'context:dmr:preserved', 3, 2, 0, NULL, 1000, 9000),
                    (901, 'context:nxdn:preserved', 4, 2, 1, NULL, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context(context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES (900, 900, 1000, 9000), (901, 901, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary(
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, call_count
                ) VALUES
                    (50, 1, 43, 1000, 9000, 7),
                    (50, 1, 999, 1000, 9000, 2),
                    (900, 1, 321, 1000, 9000, 4),
                    (900, 2, 765432, 1000, 9000, 4),
                    (901, 1, 432, 1000, 9000, 3),
                    (901, 2, 1234, 1000, 9000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_talkgroup_summary(
                    scope_id, radio_id, talkgroup_id, target_kind_code,
                    first_seen_ms, last_seen_ms, call_count
                ) VALUES
                    (50, 1800001, 43, 1, 1000, 9000, 4),
                    (900, 765432, 321, 1, 1000, 9000, 4),
                    (901, 1234, 432, 1, 1000, 9000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (50, 1800001, 43, 8000),
                       (50, 1800002, 44, 8500),
                       (50, 1800003, 43, 9000)
                """);
            statement.executeUpdate("""
                UPDATE database_metadata SET value='1234'
                WHERE key='trunked_identity_metrics_started_at_ms'
                """);
        }
    }

    private static void insertAlpha9P25Scope(Path database, int systemKey) throws Exception
    {
        insertAlpha9P25Scope(database, systemKey, 840);
    }

    private static void insertAlpha9P25Scope(Path database, int systemKey, int systemId) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (%1$d, 781824, %2$d, 1000, 2000)
                """.formatted(systemKey, systemId));
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope(
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (%1$d, 'p25:test:%2$d', 1, 1, 0, %1$d, 1000, 2000)
                """.formatted(systemKey, systemId));
        }
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static String metadata(Connection connection, String key) throws Exception
    {
        try(var statement = connection.prepareStatement("SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String metadata(Path database, String key) throws Exception
    {
        try(Connection connection = open(database))
        {
            return metadata(connection, key);
        }
    }

    private static String scalar(Path database, String sql) throws Exception
    {
        try(Connection connection = open(database))
        {
            return scalar(connection, sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private record CommandResult(int exitCode, String output, String error)
    {
    }
}
