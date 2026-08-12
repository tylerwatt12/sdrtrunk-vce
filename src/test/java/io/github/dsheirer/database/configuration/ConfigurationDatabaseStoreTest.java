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

package io.github.dsheirer.database.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog.Bandwidth;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void roundTripsConfigurationState() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);

        Channel channel = new Channel("Control");
        String configurationId = channel.getConfigurationId();
        channel.setSystem("County");
        channel.setSite("Simulcast");
        channel.setAliasListName("County Aliases");
        channel.setAutoStart(true);
        channel.setAutoStartOrder(2);
        channel.setRadresGuid("11111111-2222-3333-4444-555555555555");

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(853_762_500L);
        sourceConfig.setPreferredTuner("Airspy");
        channel.setSourceConfiguration(sourceConfig);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());

        RadioResolveConfiguration stream = new RadioResolveConfiguration();
        stream.setName("RadioResolve");
        stream.setHost("https://example.invalid/upload");
        stream.setApiKey("test-api-key");
        stream.setNodeName("TEST-NODE");
        stream.setEnabled(true);

        ConfigurationState state = new ConfigurationState();
        state.setChannels(List.of(channel));
        state.setBroadcastConfigurations(List.of(stream));

        replace(database, state);

        ConfigurationState loaded = store.loadConfigurationState();
        assertEquals(1, loaded.getChannels().size());
        assertEquals(1, loaded.getBroadcastConfigurations().size());

        Channel loadedChannel = loaded.getChannels().get(0);
        assertEquals("County", loadedChannel.getSystem());
        assertEquals("Simulcast", loadedChannel.getSite());
        assertEquals("Control", loadedChannel.getName());
        assertEquals(configurationId, loadedChannel.getConfigurationId());
        assertEquals("County Aliases", loadedChannel.getAliasListName());
        assertEquals("11111111-2222-3333-4444-555555555555", loadedChannel.getRadresGuid());
        assertTrue(loadedChannel.getAutoStart());
        assertEquals(2, loadedChannel.getAutoStartOrder());
        assertInstanceOf(SourceConfigTuner.class, loadedChannel.getSourceConfiguration());
        assertInstanceOf(DecodeConfigP25Phase1.class, loadedChannel.getDecodeConfiguration());
        SourceConfigTuner loadedSource = (SourceConfigTuner)loadedChannel.getSourceConfiguration();
        assertEquals(853_762_500L, loadedSource.getFrequency());
        assertEquals("Airspy", loadedSource.getPreferredTuner());

        BroadcastConfiguration loadedStream = loaded.getBroadcastConfigurations().get(0);
        assertInstanceOf(RadioResolveConfiguration.class, loadedStream);
        RadioResolveConfiguration loadedRadioResolve = (RadioResolveConfiguration)loadedStream;
        assertEquals("RadioResolve", loadedRadioResolve.getName());
        assertEquals("https://example.invalid/upload", loadedRadioResolve.getHost());
        assertEquals("test-api-key", loadedRadioResolve.getApiKey());
        assertEquals("TEST-NODE", loadedRadioResolve.getNodeName());
        assertTrue(loadedRadioResolve.isEnabled());

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            assertFalse(tableExists(connection, "playlist_channel"));
            assertFalse(tableExists(connection, "playlist_channel_map"));
            assertFalse(tableExists(connection, "playlist_broadcast_stream"));

            try(ResultSet resultSet = statement.executeQuery("""
                SELECT decoder_type, source_type, primary_frequency_hz, frequency_count,
                       recording_enabled, event_logging_enabled
                FROM configuration_channel
                """))
            {
                assertTrue(resultSet.next());
                assertEquals("P25_PHASE1", resultSet.getString("decoder_type"));
                assertEquals("TUNER", resultSet.getString("source_type"));
                assertEquals(853_762_500L, resultSet.getLong("primary_frequency_hz"));
                assertEquals(1, resultSet.getInt("frequency_count"));
                assertEquals(0, resultSet.getInt("recording_enabled"));
                assertEquals(0, resultSet.getInt("event_logging_enabled"));
            }

            try(ResultSet resultSet = statement.executeQuery("""
                SELECT server_type, enabled, host, port
                FROM configuration_broadcast_stream
                """))
            {
                assertTrue(resultSet.next());
                assertEquals("RADIORESOLVE", resultSet.getString("server_type"));
                assertEquals(1, resultSet.getInt("enabled"));
                assertEquals("https://example.invalid/upload", resultSet.getString("host"));
                assertEquals(80, resultSet.getInt("port"));
            }
        }
    }

    @Test
    void preservesRetiredDecoderAndSoundCardRowsWithoutLoadingThem() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retired-configuration.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        String retainedJson = "{\"type\":\"retired-channel\",\"payload\":\"must remain byte-for-byte\"}";
        String soundCardJson = "{\"type\":\"retired-sound-card\",\"payload\":\"must also remain byte-for-byte\"}";
        String channelMapJson = "{\"name\":\"Retired Map\",\"ranges\":[{\"first\":1,\"last\":9}]}";

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement channelStatement = connection.prepareStatement("""
                INSERT INTO configuration_channel (
                    id, sort_order, system_name, site_name, name, alias_list_name, radres_guid, auto_start,
                    auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                    recording_enabled, event_logging_enabled, config_json
                ) VALUES (77, 9, 'Legacy System', 'Legacy Site', 'Retired MPT', 'Legacy Aliases', 'legacy-guid',
                    1, 4, 'MPT1327', 'TUNER', 451000000, 2, 1, 1, ?)
                """);
            PreparedStatement soundCardStatement = connection.prepareStatement("""
                INSERT INTO configuration_channel (
                    id, sort_order, system_name, site_name, name, alias_list_name, radres_guid, auto_start,
                    auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                    recording_enabled, event_logging_enabled, config_json
                ) VALUES (78, 10, 'Legacy System', 'Audio Input', 'Retired Sound Card', 'Legacy Aliases',
                    'legacy-sound-guid', 1, 5, 'DMR', 'MIXER', NULL, 0, 0, 1, ?)
                """);
            PreparedStatement mapStatement = connection.prepareStatement("""
                INSERT INTO configuration_channel_map (id, sort_order, name, config_json)
                VALUES (88, 3, 'Retired Map', ?)
                """))
        {
            channelStatement.setString(1, retainedJson);
            channelStatement.executeUpdate();
            soundCardStatement.setString(1, soundCardJson);
            soundCardStatement.executeUpdate();
            mapStatement.setString(1, channelMapJson);
            mapStatement.executeUpdate();
        }

        assertTrue(store.loadConfigurationState().getChannels().isEmpty(),
            "retired rows must be classified before attempting to bind their JSON");

        Channel active = new Channel("Supported DMR");
        active.setSystem("Active System");
        active.setDecodeConfiguration(new io.github.dsheirer.module.decode.dmr.DecodeConfigDMR());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(460_000_000L);
        active.setSourceConfiguration(source);
        ConfigurationState replacement = new ConfigurationState();
        replacement.setChannels(List.of(active));
        replace(database, replacement);

        ConfigurationState loaded = store.loadConfigurationState();
        assertEquals(1, loaded.getChannels().size());
        assertEquals("Supported DMR", loaded.getChannels().get(0).getName());

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement channelQuery = connection.prepareStatement("""
                SELECT sort_order, system_name, site_name, name, alias_list_name, radres_guid, auto_start,
                       auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                       recording_enabled, event_logging_enabled, config_json
                FROM configuration_channel WHERE id = 77
                """);
            PreparedStatement soundCardQuery = connection.prepareStatement("""
                SELECT sort_order, system_name, site_name, name, alias_list_name, radres_guid, auto_start,
                       auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                       recording_enabled, event_logging_enabled, config_json
                FROM configuration_channel WHERE id = 78
                """);
            PreparedStatement mapQuery = connection.prepareStatement("""
                SELECT sort_order, name, config_json FROM configuration_channel_map WHERE id = 88
                """))
        {
            try(ResultSet resultSet = channelQuery.executeQuery())
            {
                assertTrue(resultSet.next());
                assertEquals(9, resultSet.getInt("sort_order"));
                assertEquals("Legacy System", resultSet.getString("system_name"));
                assertEquals("Legacy Site", resultSet.getString("site_name"));
                assertEquals("Retired MPT", resultSet.getString("name"));
                assertEquals("Legacy Aliases", resultSet.getString("alias_list_name"));
                assertEquals("legacy-guid", resultSet.getString("radres_guid"));
                assertEquals(1, resultSet.getInt("auto_start"));
                assertEquals(4, resultSet.getInt("auto_start_order"));
                assertEquals("MPT1327", resultSet.getString("decoder_type"));
                assertEquals("TUNER", resultSet.getString("source_type"));
                assertEquals(451_000_000L, resultSet.getLong("primary_frequency_hz"));
                assertEquals(2, resultSet.getInt("frequency_count"));
                assertEquals(1, resultSet.getInt("recording_enabled"));
                assertEquals(1, resultSet.getInt("event_logging_enabled"));
                assertEquals(retainedJson, resultSet.getString("config_json"));
            }

            try(ResultSet resultSet = soundCardQuery.executeQuery())
            {
                assertTrue(resultSet.next());
                assertEquals(10, resultSet.getInt("sort_order"));
                assertEquals("Legacy System", resultSet.getString("system_name"));
                assertEquals("Audio Input", resultSet.getString("site_name"));
                assertEquals("Retired Sound Card", resultSet.getString("name"));
                assertEquals("Legacy Aliases", resultSet.getString("alias_list_name"));
                assertEquals("legacy-sound-guid", resultSet.getString("radres_guid"));
                assertEquals(1, resultSet.getInt("auto_start"));
                assertEquals(5, resultSet.getInt("auto_start_order"));
                assertEquals("DMR", resultSet.getString("decoder_type"));
                assertEquals("MIXER", resultSet.getString("source_type"));
                assertNull(resultSet.getObject("primary_frequency_hz"));
                assertEquals(0, resultSet.getInt("frequency_count"));
                assertEquals(0, resultSet.getInt("recording_enabled"));
                assertEquals(1, resultSet.getInt("event_logging_enabled"));
                assertEquals(soundCardJson, resultSet.getString("config_json"));
            }

            try(ResultSet resultSet = mapQuery.executeQuery())
            {
                assertTrue(resultSet.next());
                assertEquals(3, resultSet.getInt("sort_order"));
                assertEquals("Retired Map", resultSet.getString("name"));
                assertEquals(channelMapJson, resultSet.getString("config_json"));
            }
        }
    }

    @Test
    void roundTripsAMConventionalChannel() throws Exception
    {
        Path database = mTemporaryFolder.resolve("am-conventional.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        Channel channel = new Channel("Airport Ground");
        channel.setSystem("County Airport");
        channel.setSite("Tower");
        DecodeConfigAM decodeConfiguration = new DecodeConfigAM();
        decodeConfiguration.setBandwidth(Bandwidth.BW_8_33);
        decodeConfiguration.setTalkgroup(27);
        decodeConfiguration.setOutputGain(1.5f);
        channel.setDecodeConfiguration(decodeConfiguration);

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(121_900_000L);
        channel.setSourceConfiguration(sourceConfig);

        ConfigurationState state = new ConfigurationState();
        state.setChannels(List.of(channel));
        replace(database, state);

        Channel loaded = store.loadConfigurationState().getChannels().get(0);
        DecodeConfigAM loadedDecodeConfiguration =
            assertInstanceOf(DecodeConfigAM.class, loaded.getDecodeConfiguration());
        assertEquals(DecoderType.AM, loadedDecodeConfiguration.getDecoderType());
        assertEquals(Bandwidth.BW_8_33, loadedDecodeConfiguration.getBandwidth());
        assertEquals(27, loadedDecodeConfiguration.getTalkgroup());
        assertEquals(1.5f, loadedDecodeConfiguration.getOutputGain());

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT decoder_type, primary_frequency_hz FROM configuration_channel"))
        {
            assertTrue(resultSet.next());
            assertEquals("AM", resultSet.getString("decoder_type"));
            assertEquals(121_900_000L, resultSet.getLong("primary_frequency_hz"));
        }
    }

    @Test
    void roundTripsP25ConventionalChannel() throws Exception
    {
        Path database = mTemporaryFolder.resolve("p25-conventional.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        Channel channel = new Channel("P25 Conventional");
        channel.setRadresGuid("22222222-3333-4444-5555-666666666666");
        DecodeConfigP25Conventional decodeConfiguration = new DecodeConfigP25Conventional();
        decodeConfiguration.setModulation(Modulation.CQPSK);
        channel.setDecodeConfiguration(decodeConfiguration);

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(155_250_000L);
        channel.setSourceConfiguration(sourceConfig);

        ConfigurationState state = new ConfigurationState();
        state.setChannels(List.of(channel));

        replace(database, state);

        Channel loaded = store.loadConfigurationState().getChannels().get(0);
        DecodeConfigP25Conventional loadedDecodeConfiguration =
            assertInstanceOf(DecodeConfigP25Conventional.class, loaded.getDecodeConfiguration());
        assertEquals(DecoderType.P25_CONVENTIONAL, loaded.getDecodeConfiguration().getDecoderType());
        assertEquals(Modulation.CQPSK, loadedDecodeConfiguration.getModulation());

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT decoder_type FROM configuration_channel"))
        {
            assertTrue(resultSet.next());
            assertEquals("P25_CONVENTIONAL", resultSet.getString("decoder_type"));
        }
    }

    @Test
    void unknownCurrentSchemaStreamFailsLoadWithoutDeletingItsRawRow() throws Exception
    {
        Path database = mTemporaryFolder.resolve("unknown-stream.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        String rawJson = "{\"type\":\"retiredUnknownStream\",\"payload\":\"preserve exactly\"}";

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream (
                    sort_order, name, server_type, enabled, host, port, delay_ms,
                    maximum_recording_age_ms, config_json
                ) VALUES (0, 'Unknown Stream', 'UNKNOWN', 0, NULL, NULL, NULL, NULL, ?)
                """))
        {
            statement.setString(1, rawJson);
            statement.executeUpdate();
        }

        assertThrows(IOException.class, store::loadConfigurationState);

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT config_json FROM configuration_broadcast_stream
                """))
        {
            assertTrue(resultSet.next());
            assertEquals(rawJson, resultSet.getString(1));
        }
    }

    private static void replace(Path database, ConfigurationState state) throws Exception
    {
        new ConfigurationSnapshotDatabaseStore(database).replace(state);
    }

    private static boolean tableExists(Connection connection, String table) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
            """))
        {
            statement.setString(1, table);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }
}
