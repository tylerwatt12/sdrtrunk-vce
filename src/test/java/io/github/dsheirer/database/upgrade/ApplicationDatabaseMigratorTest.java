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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
    private static final String ALIAS_VERSION_KEY = "alias_schema_version";
    private static final String VERSION_KEY = "p25_activity_schema_version";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void migratesAliasV2WithCurrentOtherSchemasAndPreservesRows() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        makeAliasV2(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(sort_order, name, alias_list_name, group_name, color)
                VALUES (0, 'Dispatch', 'County', 'Fire', 0)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("Alias schema migration complete: v2 -> v3"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT name, description FROM alias"))
        {
            assertTrue(resultSet.next());
            assertEquals("Dispatch", resultSet.getString("name"));
            assertNull(resultSet.getString("description"));
            assertEquals("3", metadata(connection, ALIAS_VERSION_KEY));
            assertEquals("21", metadata(connection, VERSION_KEY));
            TrunkedSiteSchema.validate(connection);
        }
    }

    @Test
    void migratesAliasP25AndTrunkedSiteTogetherInOrder() throws Exception
    {
        Path database = createV19Database();
        makeAliasV2(database);
        removeTrunkedSiteSchema(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias(sort_order, name) VALUES (0, 'Keep Me')");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        int aliasResult = result.output().indexOf("Alias schema migration complete");
        int p25Result = result.output().indexOf("P25 activity schema migration complete");
        int trunkedResult = result.output().indexOf("Trunked-site schema migration complete");
        assertTrue(aliasResult >= 0 && aliasResult < p25Result && p25Result < trunkedResult);
        assertTrue(result.error().isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("3", metadata(connection, ALIAS_VERSION_KEY));
            assertEquals("21", metadata(connection, VERSION_KEY));
            assertEquals(1, count(connection, "alias"));
            assertTrue(columnExists(connection, "alias", "description"));
            P25ActivityLogSchema.validate(connection);
            TrunkedSiteSchema.validate(connection);
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void unsupportedTrunkedVersionPreventsEveryOtherChange() throws Exception
    {
        Path database = createV19Database();
        makeAliasV2(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, TrunkedSiteSchema.SCHEMA_VERSION_KEY, "3");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("Expected trunked-site schema to be absent or v2"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("2", metadata(connection, ALIAS_VERSION_KEY));
            assertEquals("19", metadata(connection, VERSION_KEY));
            assertFalse(columnExists(connection, "alias", "description"));
            assertFalse(tableExists(connection, "p25_foreign_system_band"));
        }
    }

    @Test
    void refusesPartialV19WithLaterVersionTablesBeforeChangingOtherSchemas() throws Exception
    {
        Path database = createV19Database();
        makeAliasV2(database);
        removeTrunkedSiteSchema(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            P25ActivityLogSchema.createForeignSystemBandTables(statement);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("unexpectedly contains later-version table"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("2", metadata(connection, ALIAS_VERSION_KEY));
            assertEquals("19", metadata(connection, VERSION_KEY));
            assertFalse(columnExists(connection, "alias", "description"));
            assertTrue(tableExists(connection, "p25_foreign_system_band"));
            assertFalse(tableExists(connection, "trunked_site_snapshot"));
        }
    }

    @Test
    void refusesPartialAliasV2ShapeWithoutChangingMetadata() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, ALIAS_VERSION_KEY, "2");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("description column already exists"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("2", metadata(connection, ALIAS_VERSION_KEY));
            assertTrue(columnExists(connection, "alias", "description"));
        }
    }

    @Test
    void migratesV19StagedCopyAndPreservesExistingData() throws Exception
    {
        Path database = createV19Database();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias(sort_order, name) VALUES (0, 'Dispatch')");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("RESULT: P25 activity schema migration complete: v19 -> v21"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("21", metadata(connection, VERSION_KEY));
            assertTrue(tableExists(connection, "p25_foreign_system_band"));
            assertTrue(tableExists(connection, "p25_foreign_system_band_summary"));
            assertTrue(indexExists(connection, "idx_p25_control_quality_retention"));
            assertEquals(1, count(connection, "alias"));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
            DmrActivitySchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertFalse(hasRows(connection, "PRAGMA foreign_key_check"));
        }
    }

    @Test
    void migratesV20StagedCopyAndPreservesQualityData() throws Exception
    {
        Path database = createV20Database();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality (
                    guid, frequency_hz, bucket_start_ms, observed_at_ms
                ) VALUES ('dmr-site', 451000000, 10000, 12000)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("v20 -> v21 indexed quality retention"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("21", metadata(connection, VERSION_KEY));
            assertTrue(indexExists(connection, "idx_p25_control_quality_retention"));
            assertEquals(1, count(connection, "p25_control_channel_quality"));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
            DmrActivitySchema.validate(connection);
            P25ActivityLogSchema.validate(connection);
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void migratesV21DmrChannelsAutomaticallyAndPreservesRetiredRows() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        removeDmrActivitySchema(database);
        String retainedJson = "{\"type\":\"retired-sound-card\",\"payload\":\"keep-byte-for-byte\"}";
        String incompleteJson = "{\"name\":\"Incomplete\",\"decodeConfiguration\":null}";

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    id, sort_order, decoder_type, source_type, config_json
                ) VALUES (?, ?, ?, ?, ?)
                """))
        {
            insertChannel(statement, 101, 0, null, "TUNER", """
                {
                  "decodeConfiguration": {
                    "type": "decodeConfigDMR",
                    "timeslotMap": [{"number": 0, "downlinkFrequency": 0}]
                  },
                  "migrationMarker": "conventional-preserved"
                }
                """);
            insertChannel(statement, 102, 1, "DMR", "TUNER_MULTIPLE_FREQUENCIES", """
                {
                  "decodeConfiguration": {
                    "type": "decodeConfigDMR",
                    "timeslotMap": [{"number": 7, "downlinkFrequency": 451000000}]
                  }
                }
                """);
            insertChannel(statement, 103, 2, "DMR", "TUNER", """
                {
                  "decodeConfiguration": {
                    "type": "decodeConfigDMR",
                    "channelMode": "CONVENTIONAL",
                    "timeslotMap": [{"number": 9, "downlinkFrequency": 452000000}]
                  }
                }
                """);
            insertChannel(statement, 104, 3, "DMR", "MIXER", retainedJson);
            insertChannel(statement, 105, 4, null, "TUNER", incompleteJson);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("DMR activity schema migration complete: absent -> v1"));
        assertTrue(result.output().contains("2 conventional, 1 trunked"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("CONVENTIONAL", channelMode(connection, 101));
            assertEquals("TRUNKED", channelMode(connection, 102));
            assertEquals("CONVENTIONAL", channelMode(connection, 103),
                "an existing explicit mode must not be replaced by map inference");
            assertEquals("conventional-preserved",
                channelJson(connection, 101).path("migrationMarker").asText());
            assertEquals("DMR", scalar(connection,
                "SELECT decoder_type FROM configuration_channel WHERE id=101"));
            assertEquals(retainedJson, scalar(connection,
                "SELECT config_json FROM configuration_channel WHERE id=104"));
            assertEquals(incompleteJson, scalar(connection,
                "SELECT config_json FROM configuration_channel WHERE id=105"));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
            DmrActivitySchema.validate(connection);
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertFalse(hasRows(connection, "PRAGMA foreign_key_check"));
        }
    }

    @Test
    void refusesPartialDmrSchemaBeforeChangingLegacyChannelJson() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    id, sort_order, decoder_type, source_type, config_json
                ) VALUES (201, 0, 'DMR', 'TUNER', ?)
                """))
        {
            statement.setString(1, """
                {"decodeConfiguration":{"type":"decodeConfigDMR","timeslotMap":[]}}
                """);
            statement.executeUpdate();
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement(
                "DELETE FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, DmrActivitySchema.SCHEMA_VERSION_KEY);
            statement.executeUpdate();
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("already contains DMR activity object"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertFalse(channelJson(connection, 201).path("decodeConfiguration").has("channelMode"));
            assertNull(metadataOrNull(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void refusesMissingModeAfterDmrSchemaIsInstalled() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    id, sort_order, decoder_type, source_type, config_json
                ) VALUES (301, 0, 'DMR', 'TUNER', ?)
                """))
        {
            statement.setString(1, """
                {"decodeConfiguration":{"type":"decodeConfigDMR","timeslotMap":[]}}
                """);
            statement.executeUpdate();
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("does not have an explicit channelMode"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertFalse(channelJson(connection, 301).path("decodeConfiguration").has("channelMode"));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void refusesCurrentDmrSchemaWithIncorrectIndexDirection() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_CONTEXT_INDEX);
            statement.executeUpdate("""
                CREATE INDEX idx_dmr_conventional_talkgroup_context
                ON dmr_conventional_talkgroup_summary(
                    context_id, last_seen_ms, frequency_hz, timeslot, talkgroup_id)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("incorrect ordered columns"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void validatesV21WithoutChangingItsMetadata() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long updatedAt = metadataUpdatedAt(database, VERSION_KEY);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("Application database is already current and valid"));
        assertTrue(result.output().contains("no schema changes"));
        assertTrue(result.error().isEmpty());
        assertEquals(updatedAt, metadataUpdatedAt(database, VERSION_KEY));
    }

    @Test
    void refusesUnsupportedVersionWithoutChangingDatabase() throws Exception
    {
        Path database = createV19Database();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "18");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("Expected P25 activity schema v19, v20, or v21, found [18]"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("18", metadata(connection, VERSION_KEY));
            assertFalse(tableExists(connection, "p25_foreign_system_band"));
            assertFalse(tableExists(connection, "p25_foreign_system_band_summary"));
        }
    }

    @Test
    void rejectsForeignKeyViolationBeforeMigration() throws Exception
    {
        Path database = createV19Database();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id, sort_order, channel_name)
                VALUES (999999, 0, 'missing-parent')
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("Foreign-key check failed"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("19", metadata(connection, VERSION_KEY));
            assertFalse(tableExists(connection, "p25_foreign_system_band"));
        }
    }

    @Test
    void rollsBackTablesAndVersionWhenCurrentSchemaValidationFails() throws Exception
    {
        Path database = createV19Database();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE p25_site_neighbor");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("SQLite schema is missing table [p25_site_neighbor]"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("19", metadata(connection, VERSION_KEY));
            assertFalse(tableExists(connection, "p25_foreign_system_band"));
            assertFalse(tableExists(connection, "p25_foreign_system_band_summary"));
        }
    }

    @Test
    void refusesIncorrectPreexistingRetentionIndexWithoutRepairingIt() throws Exception
    {
        Path database = createV20Database();
        makeAliasV2(database);
        removeTrunkedSiteSchema(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE INDEX idx_p25_control_quality_retention
                ON p25_control_channel_quality(guid)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains(
            "unexpectedly contains later-version index [idx_p25_control_quality_retention]"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "PRAGMA index_info(idx_p25_control_quality_retention)"))
        {
            assertEquals("20", metadata(connection, VERSION_KEY));
            assertEquals("2", metadata(connection, ALIAS_VERSION_KEY));
            assertFalse(columnExists(connection, "alias", "description"));
            assertFalse(tableExists(connection, "trunked_site_snapshot"));
            assertTrue(resultSet.next());
            assertEquals("guid", resultSet.getString("name"));
            assertFalse(resultSet.next());
        }
    }

    @Test
    void rollsBackEverySchemaStepWhenPortablePreferencesAreMalformed() throws Exception
    {
        Path database = createV19Database();
        makeAliasV2(database);
        removeTrunkedSiteSchema(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms) VALUES (?, ?, ?)
                """))
        {
            statement.setString(1, "portable_java_preferences_v1");
            statement.setString(2, "{not-valid-json");
            statement.setLong(3, 1L);
            statement.executeUpdate();
        }

        CommandResult result = runArguments(database.toString(),
            mTemporaryFolder.resolve("old-data").toString(), mTemporaryFolder.resolve("new-data").toString());

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("Portable directory preferences contain invalid JSON"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("2", metadata(connection, ALIAS_VERSION_KEY));
            assertEquals("19", metadata(connection, VERSION_KEY));
            assertFalse(columnExists(connection, "alias", "description"));
            assertFalse(tableExists(connection, "p25_foreign_system_band"));
            assertFalse(indexExists(connection, "idx_p25_control_quality_retention"));
            assertFalse(tableExists(connection, "trunked_site_snapshot"));
            assertFalse(tableExists(connection, DmrActivitySchema.TALKGROUP_TABLE));
            assertFalse(tableExists(connection, DmrActivitySchema.RADIO_TABLE));
            assertNull(metadataOrNull(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void reportsUsageAndMissingInputWithStableExitCodes()
    {
        CommandResult usage = runArguments();
        assertEquals(ApplicationDatabaseMigrator.EXIT_USAGE, usage.exitCode());
        assertTrue(usage.error().contains("A staged database path"));

        CommandResult missing = run(newStagedDatabase());
        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, missing.exitCode());
        assertTrue(missing.error().contains("Staged database not found"));

        CommandResult help = runArguments("--help");
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, help.exitCode());
        assertTrue(help.output().startsWith("Usage:"));
    }

    @Test
    void refusesAValidLiveDatabasePathWithoutOpeningItForMigration() throws Exception
    {
        Path database = mTemporaryFolder.resolve("data/database/sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        makeAliasV2(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("accepts only an application-created staged database"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("2", metadata(connection, ALIAS_VERSION_KEY));
            assertFalse(columnExists(connection, "alias", "description"));
        }
    }

    private Path createV19Database() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE p25_foreign_system_band");
            statement.executeUpdate("DROP TABLE p25_foreign_system_band_summary");
            statement.executeUpdate("DROP INDEX idx_p25_control_quality_retention");
            SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "19");
        }

        removeDmrActivitySchema(database);
        return database;
    }

    private Path createV20Database() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_p25_control_quality_retention");
            SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "20");
        }

        removeDmrActivitySchema(database);
        return database;
    }

    private Path newStagedDatabase()
    {
        return mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
    }

    private static void makeAliasV2(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("ALTER TABLE alias DROP COLUMN description");
            SdrTrunkDatabaseStartup.setMetadata(connection, ALIAS_VERSION_KEY, "2");
        }
    }

    private static void removeTrunkedSiteSchema(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE trunked_site_channel_summary");
            statement.executeUpdate("DROP TABLE trunked_site_neighbor_summary");
            statement.executeUpdate("DROP TABLE trunked_site_snapshot");
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                TrunkedSiteSchema.SCHEMA_VERSION_KEY + "'");
        }
    }

    private static void removeDmrActivitySchema(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_RETENTION_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.RADIO_RETENTION_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_CONTEXT_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.RADIO_CONTEXT_INDEX);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.TALKGROUP_TABLE);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.RADIO_TABLE);
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                DmrActivitySchema.SCHEMA_VERSION_KEY + "'");
        }
    }

    private static void insertChannel(java.sql.PreparedStatement statement, long id, int sortOrder,
                                      String decoderType, String sourceType, String json) throws Exception
    {
        statement.setLong(1, id);
        statement.setInt(2, sortOrder);
        statement.setString(3, decoderType);
        statement.setString(4, sourceType);
        statement.setString(5, json);
        statement.executeUpdate();
    }

    private static JsonNode channelJson(Connection connection, long id) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT config_json FROM configuration_channel WHERE id=?"))
        {
            statement.setLong(1, id);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return OBJECT_MAPPER.readTree(resultSet.getString(1));
            }
        }
    }

    private static String channelMode(Connection connection, long id) throws Exception
    {
        return channelJson(connection, id).path("decodeConfiguration").path("channelMode").asText();
    }

    private CommandResult run(Path database)
    {
        return runArguments(database.toString());
    }

    private CommandResult runArguments(String... arguments)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode;

        try(PrintStream outputStream = new PrintStream(output, true, StandardCharsets.UTF_8);
            PrintStream errorStream = new PrintStream(error, true, StandardCharsets.UTF_8))
        {
            exitCode = ApplicationDatabaseMigrator.run(arguments, outputStream, errorStream);
        }

        return new CommandResult(exitCode, output.toString(StandardCharsets.UTF_8),
            error.toString(StandardCharsets.UTF_8));
    }

    private static String metadata(Connection connection, String key) throws Exception
    {
        try(var statement = connection.prepareStatement("SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static String metadataOrNull(Connection connection, String key) throws Exception
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

    private static long metadataUpdatedAt(Path database, String key) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement(
                "SELECT updated_at_ms FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"))
        {
            statement.setString(1, table);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static boolean indexExists(Connection connection, String index) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?"))
        {
            statement.setString(1, index);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                if(column.equals(resultSet.getString("name")))
                {
                    return true;
                }
            }

            return false;
        }
    }

    private static int count(Connection connection, String table) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static boolean hasRows(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next();
        }
    }

    private record CommandResult(int exitCode, String output, String error)
    {
    }
}
