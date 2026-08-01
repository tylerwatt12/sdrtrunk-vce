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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.mpt1327.DecodeConfigMPT1327;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.source.config.SourceConfigMixer;
import io.github.dsheirer.source.config.SourceConfigNone;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationDatabaseMigratorTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void validatesCurrentStagedDatabaseWithoutChangingSchema() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("already current and valid"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = open(database))
        {
            assertEquals("4", metadata(connection, "alias_schema_version"));
            assertEquals(Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION),
                metadata(connection, "p25_activity_schema_version"));
            assertEquals("2", metadata(connection, "trunked_site_schema_version"));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
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
        removeDmrActivitySchema(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("complete Alpha 7 source tuple"));

        try(Connection connection = open(database))
        {
            assertFalse(tableExists(connection, DmrActivitySchema.TALKGROUP_TABLE));
            assertFalse(tableExists(connection, DmrActivitySchema.RADIO_TABLE));
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
        assertTrue(result.error().contains("complete Alpha 7 source tuple"));

        try(Connection connection = open(database))
        {
            assertEquals("20", metadata(connection, "p25_activity_schema_version"));
        }
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
    void migratesExactPublishedAlpha7AliasesAndResetsActivityHistory() throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        insertAlpha7AliasUpgradeCases(database);
        insertAlpha7ActivityHistory(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("Alpha 7 migration"));
        assertTrue(result.output().contains("activity, statistics, site observations, identities, affiliations, " +
            "and quality history reset; new activity starts empty"));
        assertTrue(result.output().contains("legacy matcher detail fields removed=1"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = open(database))
        {
            assertEquals("4", metadata(connection, "alias_schema_version"));
            assertEquals(Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION),
                metadata(connection, "p25_activity_schema_version"));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
            assertEquals("7", scalar(connection, "SELECT count(*) FROM alias"));
            assertEquals("2", scalar(connection, "SELECT count(*) FROM alias_list"));
            assertEquals(Integer.toString((1 << 12) + 1), scalar(connection, """
                SELECT value FROM alias WHERE name='Fleet' AND matcher_type='TALKGROUP'
                    AND protocol='FLEETSYNC'
                """));
            assertEquals(Integer.toString(0xABCD), scalar(connection, """
                SELECT value FROM alias WHERE name='MDC' AND matcher_type='TALKGROUP'
                    AND protocol='MDC1200'
                """));
            assertEquals("ABC123", scalar(connection, """
                SELECT text_value FROM alias WHERE name='LoJack' AND matcher_type='ESN'
                """));
            assertEquals(Integer.toString(0x123456), scalar(connection, """
                SELECT value FROM alias WHERE name='Legacy P25' AND matcher_type='RADIO_ID'
                """));
            assertEquals("70000", scalar(connection, """
                SELECT value FROM alias WHERE name='Large P25' AND matcher_type='RADIO_ID'
                """));
            assertEquals("65000:65535", scalar(connection, """
                SELECT min_value || ':' || max_value FROM alias
                WHERE name='Crossing P25' AND matcher_type='TALKGROUP_RANGE'
                """));
            assertEquals("65536:66000", scalar(connection, """
                SELECT min_value || ':' || max_value FROM alias
                WHERE name='Crossing P25' AND matcher_type='RADIO_ID_RANGE'
                """));
            assertCurrentActivityTablesEmpty(connection);
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void rejectsAnUnpublishedAlpha7SchemaWithCurrentVersionStamps() throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE INDEX unpublished_alpha7_configuration_index
                ON configuration_channel(name)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("not an exact published Alpha 7 layout"));
        try(Connection connection = open(database))
        {
            assertEquals("21", metadata(connection, "p25_activity_schema_version"));
            assertEquals("1", scalar(connection, """
                SELECT count(*) FROM sqlite_master
                WHERE type='index' AND name='unpublished_alpha7_configuration_index'
                """));
        }
    }

    @Test
    void migratesAlpha7DatabaseSequentiallyUpgradedFromAlpha6() throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("ALTER TABLE alias DROP COLUMN description");
            statement.executeUpdate("ALTER TABLE alias ADD COLUMN description TEXT");
            statement.executeUpdate("ALTER TABLE p25_talkgroup_summary DROP COLUMN recorded_count");
            statement.executeUpdate("ALTER TABLE p25_talkgroup_summary DROP COLUMN streamed_count");
            statement.executeUpdate("ALTER TABLE p25_talkgroup_summary " +
                "ADD COLUMN recorded_count INTEGER NOT NULL DEFAULT 0");
            statement.executeUpdate("ALTER TABLE p25_talkgroup_summary " +
                "ADD COLUMN streamed_count INTEGER NOT NULL DEFAULT 0");
            statement.executeUpdate("ALTER TABLE p25_site_channel DROP COLUMN callsign");
            statement.executeUpdate("ALTER TABLE p25_site_channel ADD COLUMN callsign TEXT");

            for(String column: List.of("lra", "mfid", "broadcast_clock_ms", "micro_slots", "data_service",
                "data_access", "wuid_lease_minutes", "registration_service", "tdma", "voice_service"))
            {
                statement.executeUpdate("ALTER TABLE p25_site_snapshot DROP COLUMN " + column);
            }
            for(String definition: List.of("lra INTEGER", "mfid INTEGER", "broadcast_clock_ms INTEGER",
                "micro_slots INTEGER", "data_service INTEGER", "data_access TEXT", "wuid_lease_minutes INTEGER",
                "registration_service INTEGER", "tdma INTEGER", "voice_service INTEGER"))
            {
                statement.executeUpdate("ALTER TABLE p25_site_snapshot ADD COLUMN " + definition);
            }

            statement.executeUpdate("DROP INDEX idx_p25_site_channel_summary_guid_frequency");
            statement.executeUpdate("""
                CREATE INDEX idx_p25_site_channel_summary_guid_frequency
                ON p25_site_channel_summary(guid, downlink_hz, last_seen_ms DESC)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot(
                    guid, first_seen_ms, last_seen_ms, lra, primary_frequency_hz, current_control_hz
                ) VALUES ('sequential-site', 1, 2, 3, 851000000, 852000000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel(guid, channel_key, callsign, confirmed_at_ms)
                VALUES ('sequential-site', '0-1', 'TEST123', 2)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        try(Connection connection = open(database))
        {
            assertEquals("4", metadata(connection, "alias_schema_version"));
            assertEquals(Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION),
                metadata(connection, "p25_activity_schema_version"));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
            assertCurrentActivityTablesEmpty(connection);
            assertEquals("CREATE INDEX idx_p25_site_channel_summary_guid_frequency " +
                "ON p25_site_channel_summary(guid, downlink_hz)", scalar(connection, """
                    SELECT replace(sql, char(10), ' ') FROM sqlite_master
                    WHERE type='index' AND name='idx_p25_site_channel_summary_guid_frequency'
                    """).replaceAll("\\s+", " ").trim());
        }
    }

    @Test
    void migratesAlpha7ChannelModesIdentitiesCompatibilityRowsAndStreams() throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        ConfigurationFixture fixture = insertAlpha7ConfigurationCases(database);
        insertRetiredAmActivity(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("retired channels removed=1"));
        assertTrue(result.output().contains("retired streams removed=1"));
        assertTrue(result.output().contains("new activity starts empty"));
        try(Connection connection = open(database))
        {
            assertCurrentActivityTablesEmpty(connection);
            assertEquals("TRUNKED", channelMode(connection, 1));
            assertEquals("CONVENTIONAL", channelMode(connection, 2));
            assertEquals("TRUNKED", channelMode(connection, 3));
            assertEquals("5", scalar(connection, "SELECT count(*) FROM configuration_channel"));
            assertEquals("0", scalar(connection,
                "SELECT count(*) FROM configuration_channel WHERE decoder_type='AM'"));
            assertEquals("5", scalar(connection, """
                SELECT count(DISTINCT json_extract(config_json, '$.configurationId'))
                FROM configuration_channel
                """));
            assertEquals(fixture.mptRow(), configurationRow(connection, 4));
            assertEquals(fixture.mixerRow(), configurationRow(connection, 5));
            assertEquals(fixture.channelMapJson(), scalar(connection,
                "SELECT config_json FROM configuration_channel_map WHERE id=1"));
            assertEquals("3", scalar(connection, "SELECT count(*) FROM configuration_broadcast_stream"));
            assertEquals("3", scalar(connection, """
                SELECT count(DISTINCT json_extract(config_json, '$.configurationId'))
                FROM configuration_broadcast_stream
                """));
            assertEquals("0", scalar(connection, """
                SELECT count(*) FROM configuration_broadcast_stream WHERE server_type='SHOUTCAST_V2'
                """));
            assertEquals("3", scalar(connection, """
                SELECT count(*) FROM configuration_broadcast_stream WHERE server_type='RADIORESOLVE'
                """));

            Set<String> channelIdentities = new HashSet<>();
            for(int id = 1; id <= 5; id++)
            {
                String identity = configurationJson(connection, "configuration_channel", id)
                    .path("configurationId").asText();
                UUID.fromString(identity);
                assertTrue(channelIdentities.add(identity));
            }
        }
    }

    @Test
    void preservesAnExactAlpha7ChannelThatHasAValidDecoderButNoSource() throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        ObjectNode channel = channelJson("Incomplete P25", new DecodeConfigP25Phase1(), null);
        channel.remove("configurationId");
        channel.putNull("sourceConfiguration");
        try(Connection connection = open(database); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(
                id, sort_order, name, auto_start, decoder_type, source_type, config_json
            ) VALUES (1, 1, 'Incomplete P25', 1, 'P25_PHASE1', NULL, ?)
            """))
        {
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(channel));
            statement.executeUpdate();
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        try(Connection connection = open(database))
        {
            assertEquals("P25_PHASE1", scalar(connection,
                "SELECT decoder_type FROM configuration_channel WHERE id=1"));
            assertEquals("NONE", scalar(connection,
                "SELECT source_type FROM configuration_channel WHERE id=1"));
            JsonNode migratedJson = configurationJson(connection, "configuration_channel", 1);
            assertEquals("sourceConfigNone", migratedJson.path("sourceConfiguration").path("type").asText());
            UUID.fromString(migratedJson.path("configurationId").asText());
            Channel migrated = OBJECT_MAPPER.treeToValue(migratedJson, Channel.class);
            assertFalse(ChannelConfigurationPolicy.isActive(migrated));
        }
    }

    @Test
    void preservesAnExactAlpha7ChannelAlreadyStoredWithAnInactiveNoneSource() throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        ObjectNode channel = channelJson("Inactive P25", new DecodeConfigP25Phase1(), new SourceConfigNone());
        channel.remove("configurationId");
        try(Connection connection = open(database); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(
                id, sort_order, name, auto_start, decoder_type, source_type, config_json
            ) VALUES (1, 1, 'Inactive P25', 1, 'P25_PHASE1', 'NONE', ?)
            """))
        {
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(channel));
            statement.executeUpdate();
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        try(Connection connection = open(database))
        {
            assertEquals("NONE", scalar(connection,
                "SELECT source_type FROM configuration_channel WHERE id=1"));
            Channel migrated = OBJECT_MAPPER.treeToValue(
                configurationJson(connection, "configuration_channel", 1), Channel.class);
            assertFalse(ChannelConfigurationPolicy.isActive(migrated));
        }
    }

    @Test
    void rejectsIncompleteAlpha7ChannelsWithUnknownOrMismatchedTypeFacts() throws Exception
    {
        assertRejectedIncompleteChannel("UNKNOWN", null, new DecodeConfigP25Phase1(), null,
            "unsupported decoder type [UNKNOWN]");
        assertRejectedIncompleteChannel("DMR", null, new DecodeConfigP25Phase1(), null,
            "cached decoder/source types do not match");
        assertRejectedIncompleteChannel("P25_PHASE1", null, new DecodeConfigP25Phase1(),
            tuner(851_000_000L), "cached decoder/source types do not match");
        assertRejectedIncompleteChannel("P25_PHASE1", "TUNER", new DecodeConfigP25Phase1(), null,
            "cached decoder/source types do not match");
    }

    @Test
    void reportsEveryPlannedAliasDiscardAndDuplicateCollapse() throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id, sort_order, name, alias_list_name, non_recordable) VALUES
                    (1, 1, 'Retained', 'P25', 1),
                    (2, 2, 'Matcherless', 'P25', 0),
                    (3, 3, 'Retired matcher', 'Retired', 0),
                    (4, 4, 'Invalid matcher', 'Invalid', 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_talkgroup(alias_id, sort_order, protocol, value, fully_qualified, ranged)
                VALUES (1, 1, 'APCO25', 101, 0, 0), (1, 2, 'APCO25', 101, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_text_identifier(alias_id, sort_order, identifier_type, text_value)
                VALUES (3, 1, 'MIN', 'retired'), (4, 1, 'DCS', 'not-a-dcs-code')
                """);
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id, sort_order, channel_name)
                VALUES (1, 1, 'Calls'), (1, 2, 'Calls'), (2, 1, 'Orphaned by retired alias')
                """);
            statement.executeUpdate("""
                INSERT INTO alias_action(alias_id, sort_order, type, script)
                VALUES (1, 1, 'SCRIPT', '/private/retired-script')
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        for(String expected: List.of(
            "alias actions removed=1",
            "non-recordable alias flags removed=1",
            "duplicate matcher rows collapsed=1",
            "duplicate broadcast-route rows collapsed=1",
            "broadcast routes skipped=1",
            "source aliases skipped=3",
            "matcherless aliases skipped=1",
            "invalid matcher rows skipped=1",
            "retired matcher rows skipped=1"))
        {
            assertTrue(result.output().contains(expected), result.output());
        }
        assertFalse(result.output().contains("/private/retired-script"));
        try(Connection connection = open(database))
        {
            assertEquals("1", scalar(connection, "SELECT count(*) FROM alias"));
            assertEquals("1", scalar(connection, "SELECT count(*) FROM alias_broadcast_channel"));
        }
    }

    @Test
    void rejectsRetiredChannelCachesThatDisagreeWithAuthoritativeFacts() throws Exception
    {
        Path channelDatabase = Alpha7TestDatabase.create(newStagedDatabase());
        insertAlpha7ConfigurationCases(channelDatabase);
        try(Connection connection = open(channelDatabase); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE configuration_channel SET decoder_type='AM' WHERE id=1");
        }

        CommandResult channelResult = run(channelDatabase);
        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, channelResult.exitCode());
        assertTrue(channelResult.error().contains("cached decoder/source types do not match"));
    }

    @Test
    void rejectsCachedAndJsonBroadcastTypeMismatchBeforeMigration() throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        ObjectNode supported = radioResolveJson("Mismatch");
        try(Connection connection = open(database); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_broadcast_stream(
                id, sort_order, name, server_type, enabled, config_json
            ) VALUES (1, 1, 'Mismatch', 'SHOUTCAST_V2', 0, ?)
            """))
        {
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(supported));
            statement.executeUpdate();
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("cached server type does not match"));
        try(Connection connection = open(database))
        {
            assertEquals("3", metadata(connection, "alias_schema_version"));
            assertEquals("1", scalar(connection, "SELECT count(*) FROM configuration_broadcast_stream"));
        }
    }

    @Test
    void splitsMixedAndUnassignedAlpha7AliasListsWithoutLosingSupportedData() throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        insertMixedFamilyAliasCases(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        try(Connection connection = open(database))
        {
            assertEquals("8", scalar(connection, "SELECT count(*) FROM alias_list"));
            assertEquals("8", scalar(connection, "SELECT count(*) FROM alias"));
            assertEquals("2", scalar(connection, "SELECT count(*) FROM alias_broadcast_channel"));
            assertEquals("Shared [P25] 2", scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=1"));
            assertEquals("Shared [DMR]", scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=2"));
            assertEquals("Shared [P25]", scalar(connection,
                "SELECT alias_list_name FROM configuration_channel WHERE id=3"));
            assertEquals("Shared [P25] 2", scalar(connection, """
                SELECT json_extract(config_json, '$.aliasListName')
                FROM configuration_channel WHERE id=1
                """));
            assertEquals("Shared [DMR]", scalar(connection, """
                SELECT json_extract(config_json, '$.aliasListName')
                FROM configuration_channel WHERE id=2
                """));
            assertEquals("1", scalar(connection, """
                SELECT count(*) FROM alias a JOIN alias_list l ON l.id=a.alias_list_id
                WHERE a.name='Shared P25 Alias' AND l.name='Shared [P25] 2' AND l.family='P25'
                """));
            assertEquals("1", scalar(connection, """
                SELECT count(*) FROM alias a JOIN alias_list l ON l.id=a.alias_list_id
                WHERE a.name='Shared DMR Alias' AND l.name='Shared [DMR]' AND l.family='DMR'
                """));
            assertEquals("2", scalar(connection, """
                SELECT count(*) FROM alias a JOIN alias_list l ON l.id=a.alias_list_id
                WHERE a.name IN ('Unassigned P25', 'Unassigned DMR')
                  AND l.name IN ('Unassigned Mix [P25]', 'Unassigned Mix [DMR]')
                """));
            assertEquals("1", scalar(connection, """
                SELECT count(*) FROM alias a JOIN alias_list l ON l.id=a.alias_list_id
                WHERE a.name='Blank P25' AND l.name='Imported Unassigned [P25]'
                """));
            assertEquals("1", scalar(connection, """
                SELECT count(*) FROM alias a JOIN alias_list l ON l.id=a.alias_list_id
                WHERE a.name='Collision P25' AND l.name='Shared [P25]' AND l.family='P25'
                """));
            assertEquals("1", scalar(connection, """
                SELECT count(*) FROM alias a JOIN alias_list l ON l.id=a.alias_list_id
                WHERE a.name='Generic DCS' AND l.name='Generic Mix [NBFM]' AND l.family='NBFM'
                  AND a.matcher_type='DCS'
                """));
            assertEquals("1", scalar(connection, """
                SELECT count(*) FROM alias a JOIN alias_list l ON l.id=a.alias_list_id
                WHERE a.name='Generic Unit Status' AND l.name='Generic Mix [P25]' AND l.family='P25'
                  AND a.matcher_type='UNIT_STATUS'
                """));
            assertFalse(result.output().contains("planned removals/collapses"));
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

    private static void removeDmrActivitySchema(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
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

    private static void insertAlpha7AliasUpgradeCases(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id, sort_order, name, alias_list_name) VALUES
                    (1, 1, 'Fleet', 'Analog'),
                    (2, 2, 'MDC', 'Analog'),
                    (3, 3, 'LoJack', 'Analog'),
                    (4, 4, 'Legacy P25', 'P25'),
                    (5, 5, 'Large P25', 'P25'),
                    (6, 6, 'Crossing P25', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias_text_identifier(
                    alias_id, sort_order, identifier_type, text_value, text_value_2
                ) VALUES
                    (1, 1, 'FLEETSYNC', '001-0001', NULL),
                    (2, 1, 'MDC1200', 'ABCD', NULL),
                    (3, 1, 'LOJACK', 'ABC123', 'F1_STOLEN_VEHICLE'),
                    (4, 1, 'LEGACY_TALKGROUP', '123456', NULL)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_talkgroup(
                    alias_id, sort_order, protocol, value, min_value, max_value,
                    fully_qualified, ranged
                ) VALUES
                    (5, 1, 'APCO25', 70000, NULL, NULL, 0, 0),
                    (6, 1, 'APCO25', NULL, 65000, 66000, 0, 1)
                """);
        }
    }

    private static void insertAlpha7ActivityHistory(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (1, 781824, 840, 100, 200)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                    first_seen_ms, last_seen_ms, system_key, nac, rfss, site
                ) VALUES (1, 'site-1', 'guid-1', 1, 1, 'Control', 'P25_PHASE1',
                          100, 200, 1, 659, 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_talkgroup_summary(
                    system_key, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                    call_count, grant_count, encrypted_count, recorded_count, streamed_count,
                    last_source_radio_id, last_encryption_algorithm_id, last_encryption_key_id
                ) VALUES
                    (1, 123, 1, 100, 200, 4, 5, 2, 3, 1, 456, 128, 7),
                    (1, 0, 1, 100, 200, 9, 9, 9, 9, 9, 456, 128, 7),
                    (1, 65535, 1, 100, 200, 8, 8, 8, 8, 8, 456, 128, 7)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_summary(
                    system_key, radio_id, first_seen_ms, last_seen_ms, call_count, encrypted_count,
                    last_talkgroup_id, last_talker_alias, last_talker_alias_seen_ms,
                    last_encryption_algorithm_id, last_encryption_key_id
                ) VALUES
                    (1, 456, 100, 200, 4, 2, 123, 'Unit 456', 190, 128, 7),
                    (1, 0, 100, 200, 9, 9, 123, 'Reserved zero', 190, 128, 7),
                    (1, 16777215, 100, 200, 8, 8, 123, 'Reserved special', 190, 128, 7)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_talkgroup_summary(
                    system_key, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                    call_count, grant_count, encrypted_count
                ) VALUES
                    (1, 456, 123, 1, 100, 200, 4, 6, 2),
                    (1, 0, 123, 1, 100, 200, 9, 9, 9),
                    (1, 456, 0, 1, 100, 200, 8, 8, 8),
                    (1, 16777215, 65535, 1, 100, 200, 7, 7, 7)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES
                    (1, 456, 123, 200),
                    (1, 0, 123, 200),
                    (1, 457, 0, 200),
                    (1, 16777215, 123, 200)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_talkgroup_bucket(
                    context_id, talkgroup_id, bucket_start_ms, call_count, encrypted_count,
                    recorded_count, streamed_count
                ) VALUES
                    (1, 123, 0, 4, 2, 3, 1),
                    (1, 0, 0, 9, 9, 9, 9),
                    (1, 65535, 0, 8, 8, 8, 8)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot(guid, first_seen_ms, last_seen_ms, observation_count)
                VALUES ('p25-site-history', 100, 200, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality(
                    guid, frequency_hz, bucket_start_ms, observed_at_ms, decode_health_pct, valid_frames
                ) VALUES ('p25-site-history', 851000000, 0, 200, 95.0, 10)
                """);
            statement.executeUpdate("""
                INSERT INTO logger_status(key, value, updated_at_ms)
                VALUES ('history', 'present', 200)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot(
                    guid, snapshot_hash, protocol_code, configured_system, first_seen_ms, last_seen_ms
                ) VALUES ('dmr-site-history', 'snapshot', 3, 'DMR System', 100, 200)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_channel_summary(
                    guid, channel_number, inbound_channel_number, timeslot, frequency_hz,
                    first_seen_ms, last_seen_ms
                ) VALUES ('dmr-site-history', 1, -1, 1, 451000000, 100, 200)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_neighbor_summary(
                    guid, variant_code, identity_domain_code, network_id, system_id, site_id,
                    channel_number, frequency_hz, first_seen_ms, last_seen_ms
                ) VALUES ('dmr-site-history', 0, 0, -1, 1, 2, 3, 452000000, 100, 200)
                """);
        }
    }

    private static void insertRetiredAmActivity(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, kind_code, protocol_code, channel_name, decoder,
                    first_seen_ms, last_seen_ms
                ) VALUES (101, 'retired-am', 10, 11, 'Retired AM', 'AM', 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event(context_id, observed_at_ms, action_code)
                VALUES (101, 2, 4)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary(
                    context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count
                ) VALUES (101, 455000000, -1, 1, 2, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO conventional_activity_bucket(
                    context_id, frequency_hz, timeslot, bucket_start_ms, call_count
                ) VALUES (101, 455000000, -1, 0, 1)
                """);
        }
    }

    private static ConfigurationFixture insertAlpha7ConfigurationCases(Path database) throws Exception
    {
        String sharedIdentity = "11111111-2222-3333-4444-555555555555";
        DecodeConfigDMR mappedDmr = new DecodeConfigDMR();
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(1);
        mapping.setDownlinkFrequency(451_000_000L);
        mappedDmr.addTimeslotFrequency(mapping);
        ObjectNode mappedDmrJson = channelJson("Mapped DMR", mappedDmr, tuner(451_000_000L));
        ((ObjectNode)mappedDmrJson.path("decodeConfiguration")).remove("channelMode");
        mappedDmrJson.put("configurationId", sharedIdentity);

        ObjectNode conventionalDmrJson = channelJson("Conventional DMR", new DecodeConfigDMR(),
            tuner(452_000_000L));
        ((ObjectNode)conventionalDmrJson.path("decodeConfiguration")).remove("channelMode");
        conventionalDmrJson.put("configurationId", sharedIdentity);

        ObjectNode nxdnJson = channelJson("NXDN", new DecodeConfigNXDN(), tuner(453_000_000L));
        ((ObjectNode)nxdnJson.path("decodeConfiguration")).remove("channelMode");
        nxdnJson.remove("configurationId");

        ObjectNode mptJson = channelJson("Retired MPT", new DecodeConfigMPT1327(), tuner(454_000_000L));
        String mptText = OBJECT_MAPPER.writeValueAsString(mptJson);
        SourceConfigMixer mixer = new SourceConfigMixer();
        mixer.setMixer("Legacy sound card");
        ObjectNode mixerJson = channelJson("Retired Mixer", new DecodeConfigNBFM(), mixer);
        String mixerText = OBJECT_MAPPER.writeValueAsString(mixerJson);
        String mapText = "{\"legacy\":\"DMR channel map stays byte-for-byte\"}";

        try(Connection connection = open(database);
            PreparedStatement channel = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    id, sort_order, system_name, site_name, name, decoder_type, source_type,
                    primary_frequency_hz, config_json
                ) VALUES (?, ?, 'Test', 'Site', ?, ?, ?, ?, ?)
                """);
            PreparedStatement stream = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream(
                    id, sort_order, name, server_type, enabled, host, port, delay_ms,
                    maximum_recording_age_ms, config_json
                ) VALUES (?, ?, ?, ?, 0, 'https://example.invalid', 443, 0, 600000, ?)
                """))
        {
            insertChannel(channel, 1, "Mapped DMR", "DMR", "TUNER", 451_000_000L,
                OBJECT_MAPPER.writeValueAsString(mappedDmrJson));
            insertChannel(channel, 2, "Conventional DMR", "DMR", "TUNER", 452_000_000L,
                OBJECT_MAPPER.writeValueAsString(conventionalDmrJson));
            insertChannel(channel, 3, "NXDN", "NXDN", "TUNER", 453_000_000L,
                OBJECT_MAPPER.writeValueAsString(nxdnJson));
            insertChannel(channel, 4, "Retired MPT", "MPT1327", "TUNER", 454_000_000L, mptText);
            insertChannel(channel, 5, "Retired Mixer", "NBFM", "MIXER", null, mixerText);
            insertChannel(channel, 6, "Retired AM", "AM", "TUNER", 455_000_000L,
                "{\"decodeConfiguration\":{\"type\":\"decodeConfigAM\"}," +
                    "\"sourceConfiguration\":{\"type\":\"sourceConfigTuner\"}}");

            ObjectNode firstStream = radioResolveJson("First");
            firstStream.put("configurationId", sharedIdentity);
            insertStream(stream, 1, "First", "RADIORESOLVE", OBJECT_MAPPER.writeValueAsString(firstStream));
            ObjectNode secondStream = radioResolveJson("Second");
            secondStream.put("configurationId", sharedIdentity);
            insertStream(stream, 2, "Second", "RADIORESOLVE", OBJECT_MAPPER.writeValueAsString(secondStream));
            ObjectNode thirdStream = radioResolveJson("Third");
            thirdStream.remove("configurationId");
            insertStream(stream, 3, "Third", "RADIORESOLVE", OBJECT_MAPPER.writeValueAsString(thirdStream));
            insertStream(stream, 4, "Retired Shoutcast", "SHOUTCAST_V2",
                "{\"type\":\"shoutcastV2Configuration\"}");
        }

        try(Connection connection = open(database); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel_map(id, sort_order, name, config_json)
            VALUES (1, 1, 'Legacy Map', ?)
            """))
        {
            statement.setString(1, mapText);
            statement.executeUpdate();
        }
        try(Connection connection = open(database))
        {
            return new ConfigurationFixture(configurationRow(connection, 4), configurationRow(connection, 5),
                mapText);
        }
    }

    private void assertRejectedIncompleteChannel(String cachedDecoder, String cachedSource,
                                                 DecodeConfiguration decoder, SourceConfiguration source,
                                                 String expectedError) throws Exception
    {
        Path database = Alpha7TestDatabase.create(newStagedDatabase());
        ObjectNode channel = channelJson("Invalid incomplete channel", decoder, source);
        if(source == null)
        {
            channel.putNull("sourceConfiguration");
        }
        try(Connection connection = open(database); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(
                id, sort_order, name, decoder_type, source_type, config_json
            ) VALUES (1, 1, 'Invalid incomplete channel', ?, ?, ?)
            """))
        {
            statement.setString(1, cachedDecoder);
            statement.setString(2, cachedSource);
            statement.setString(3, OBJECT_MAPPER.writeValueAsString(channel));
            statement.executeUpdate();
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains(expectedError), result.error());
        try(Connection connection = open(database))
        {
            assertEquals("3", metadata(connection, "alias_schema_version"));
            assertEquals(cachedDecoder, scalar(connection,
                "SELECT decoder_type FROM configuration_channel WHERE id=1"));
        }
    }

    private static void insertMixedFamilyAliasCases(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id, sort_order, name, alias_list_name) VALUES
                    (1, 1, 'Shared P25 Alias', 'Shared'),
                    (2, 2, 'Shared DMR Alias', 'Shared'),
                    (3, 3, 'Unassigned P25', 'Unassigned Mix'),
                    (4, 4, 'Unassigned DMR', 'Unassigned Mix'),
                    (5, 5, 'Blank P25', NULL),
                    (6, 6, 'Collision P25', 'Shared [P25]'),
                    (7, 7, 'Generic DCS', 'Generic Mix'),
                    (8, 8, 'Generic Unit Status', 'Generic Mix')
                """);
            statement.executeUpdate("""
                INSERT INTO alias_talkgroup(
                    alias_id, sort_order, protocol, value, fully_qualified, ranged
                ) VALUES
                    (1, 1, 'APCO25', 101, 0, 0),
                    (2, 1, 'DMR', 202, 0, 0),
                    (3, 1, 'APCO25', 303, 0, 0),
                    (4, 1, 'DMR', 404, 0, 0),
                    (5, 1, 'APCO25', 505, 0, 0),
                    (6, 1, 'APCO25', 606, 0, 0)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id, sort_order, channel_name)
                VALUES (1, 1, 'P25 Stream'), (2, 1, 'DMR Stream')
                """);
            statement.executeUpdate("""
                INSERT INTO alias_text_identifier(
                    alias_id, sort_order, identifier_type, text_value
                ) VALUES (7, 1, 'DCS', 'N023')
                """);
            statement.executeUpdate("""
                INSERT INTO alias_status(alias_id, sort_order, status_kind, status)
                VALUES (8, 1, 'UNIT', 7)
                """);
        }

        insertAlpha7FamilyChannel(database, 1, "P25 Shared", "Shared", new DecodeConfigP25Phase1(),
            "P25_PHASE1", 851_000_000L);
        insertAlpha7FamilyChannel(database, 2, "DMR Shared", "Shared", new DecodeConfigDMR(),
            "DMR", 452_000_000L);
        insertAlpha7FamilyChannel(database, 3, "P25 Collision", "Shared [P25]",
            new DecodeConfigP25Phase1(), "P25_PHASE1", 852_000_000L);
    }

    private static void insertAlpha7FamilyChannel(Path database, int id, String name, String aliasList,
                                                   DecodeConfiguration decoder, String decoderType,
                                                   long frequency) throws Exception
    {
        Channel channel = new Channel(name);
        channel.setAliasListName(aliasList);
        channel.setDecodeConfiguration(decoder);
        channel.setSourceConfiguration(tuner(frequency));
        try(Connection connection = open(database); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(
                id, sort_order, name, alias_list_name, decoder_type, source_type,
                primary_frequency_hz, config_json
            ) VALUES (?, ?, ?, ?, ?, 'TUNER', ?, ?)
            """))
        {
            statement.setInt(1, id);
            statement.setInt(2, id);
            statement.setString(3, name);
            statement.setString(4, aliasList);
            statement.setString(5, decoderType);
            statement.setLong(6, frequency);
            statement.setString(7, OBJECT_MAPPER.writeValueAsString(channel));
            statement.executeUpdate();
        }
    }

    private static ObjectNode channelJson(String name, DecodeConfiguration decoder, SourceConfiguration source)
        throws Exception
    {
        Channel channel = new Channel(name);
        channel.setSystem("Test");
        channel.setSite("Site");
        channel.setDecodeConfiguration(decoder);
        channel.setSourceConfiguration(source);
        return (ObjectNode)OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(channel));
    }

    private static SourceConfigTuner tuner(long frequency)
    {
        SourceConfigTuner tuner = new SourceConfigTuner();
        tuner.setFrequency(frequency);
        return tuner;
    }

    private static ObjectNode radioResolveJson(String name) throws Exception
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setName(name);
        configuration.setHost("https://example.invalid/upload");
        configuration.setApiKey("test-only-key");
        return (ObjectNode)OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(configuration));
    }

    private static void insertChannel(PreparedStatement statement, int id, String name, String decoder,
                                      String source, Long frequency, String json) throws Exception
    {
        statement.setInt(1, id);
        statement.setInt(2, id);
        statement.setString(3, name);
        statement.setString(4, decoder);
        statement.setString(5, source);
        if(frequency != null)
        {
            statement.setLong(6, frequency);
        }
        else
        {
            statement.setNull(6, java.sql.Types.INTEGER);
        }
        statement.setString(7, json);
        statement.executeUpdate();
    }

    private static void insertStream(PreparedStatement statement, int id, String name, String serverType,
                                     String json) throws Exception
    {
        statement.setInt(1, id);
        statement.setInt(2, id);
        statement.setString(3, name);
        statement.setString(4, serverType);
        statement.setString(5, json);
        statement.executeUpdate();
    }

    private static String channelMode(Connection connection, int id) throws Exception
    {
        return configurationJson(connection, "configuration_channel", id)
            .path("decodeConfiguration").path("channelMode").asText();
    }

    private static JsonNode configurationJson(Connection connection, String table, int id) throws Exception
    {
        return OBJECT_MAPPER.readTree(scalar(connection,
            "SELECT config_json FROM " + table + " WHERE id=" + id));
    }

    private static List<Object> configurationRow(Connection connection, int id) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, sort_order, system_name, site_name, name, alias_list_name, radres_guid,
                   auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                   frequency_count, recording_enabled, event_logging_enabled, config_json
            FROM configuration_channel WHERE id=?
            """))
        {
            statement.setInt(1, id);
            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                List<Object> values = new ArrayList<>();
                for(int column = 1; column <= 16; column++)
                {
                    values.add(resultSet.getObject(column));
                }
                return values;
            }
        }
    }

    private static void assertCurrentActivityTablesEmpty(Connection connection) throws Exception
    {
        List<String> tables = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
            SELECT name
            FROM sqlite_master
            WHERE type='table' AND (
                name='p25_system' OR name='receiver_context' OR name='activity_event_talkgroup_member'
                OR name='call_identity_bucket' OR name='logger_status'
                OR name LIKE 'p25_%' OR name LIKE 'conventional_activity_%'
                OR name LIKE 'trunked_identity_%' OR name LIKE 'dmr_conventional_%'
                OR name LIKE 'trunked_site_%'
            )
            ORDER BY name
            """))
        {
            while(resultSet.next())
            {
                tables.add(resultSet.getString(1));
            }
        }

        assertTrue(tables.size() >= 35, "Current activity schema is incomplete: " + tables);
        for(String table: tables)
        {
            assertEquals("0", scalar(connection, "SELECT count(*) FROM " + table),
                "Alpha 7 activity was retained in " + table);
        }
        for(String retired: List.of("p25_talkgroup_summary", "p25_radio_summary",
            "p25_radio_talkgroup_summary"))
        {
            assertFalse(tableExists(connection, retired));
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

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
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

    private record CommandResult(int exitCode, String output, String error)
    {
    }

    private record ConfigurationFixture(List<Object> mptRow, List<Object> mixerRow, String channelMapJson)
    {
    }
}
