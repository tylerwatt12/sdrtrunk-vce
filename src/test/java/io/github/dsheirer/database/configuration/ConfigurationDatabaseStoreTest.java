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
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog.Bandwidth;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void roundTripsChannelAndBroadcastConfiguration() throws Exception
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
        channel.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x123, 1, 2));

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(853_762_500L);
        sourceConfig.setPreferredTuner("Airspy");
        channel.setSourceConfiguration(sourceConfig);
        DecodeConfigP25Phase1 decodeConfig = new DecodeConfigP25Phase1();
        decodeConfig.setModulation(Modulation.CQPSK);
        decodeConfig.setLearnedControlFrequencies(List.of(852_012_500L));
        channel.setDecodeConfiguration(decodeConfig);

        RadioResolveConfiguration stream = new RadioResolveConfiguration();
        stream.setName("RadioResolve");
        stream.setHost("https://example.invalid/upload");
        stream.setApiKey("test-api-key");
        stream.setNodeName("TEST-NODE");
        stream.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        stream.setEnabled(true);

        TestConfiguration state = new TestConfiguration();
        state.setChannels(List.of(channel));
        state.setBroadcastConfigurations(List.of(stream));

        replace(database, state);

        ChannelAndBroadcastConfiguration loaded = store.load();
        assertEquals(1, loaded.channels().size());
        assertEquals(1, loaded.broadcastConfigurations().size());

        Channel loadedChannel = loaded.channels().get(0);
        assertEquals("County", loadedChannel.getSystem());
        assertEquals("Simulcast", loadedChannel.getSite());
        assertEquals("Control", loadedChannel.getName());
        assertEquals(configurationId, loadedChannel.getConfigurationId());
        assertEquals("County Aliases", loadedChannel.getAliasListName());
        assertEquals("11111111-2222-3333-4444-555555555555", loadedChannel.getRadresGuid());
        assertEquals(new P25SiteIdentity(0xBEE00, 0x123, 1, 2), loadedChannel.getP25SiteIdentity());
        assertTrue(loadedChannel.getAutoStart());
        assertEquals(2, loadedChannel.getAutoStartOrder());
        assertInstanceOf(SourceConfigTuner.class, loadedChannel.getSourceConfiguration());
        assertInstanceOf(DecodeConfigP25Phase1.class, loadedChannel.getDecodeConfiguration());
        DecodeConfigP25Phase1 loadedDecodeConfig = (DecodeConfigP25Phase1)loadedChannel.getDecodeConfiguration();
        assertEquals(Modulation.CQPSK, loadedDecodeConfig.getModulation());
        assertEquals(List.of(852_012_500L), loadedDecodeConfig.getLearnedControlFrequencies());
        SourceConfigTuner loadedSource = (SourceConfigTuner)loadedChannel.getSourceConfiguration();
        assertEquals(853_762_500L, loadedSource.getFrequency());
        assertEquals("Airspy", loadedSource.getPreferredTuner());

        BroadcastConfiguration loadedStream = loaded.broadcastConfigurations().get(0);
        assertInstanceOf(RadioResolveConfiguration.class, loadedStream);
        RadioResolveConfiguration loadedRadioResolve = (RadioResolveConfiguration)loadedStream;
        assertEquals("RadioResolve", loadedRadioResolve.getName());
        assertEquals("https://example.invalid/upload", loadedRadioResolve.getHost());
        assertEquals("test-api-key", loadedRadioResolve.getApiKey());
        assertEquals("TEST-NODE", loadedRadioResolve.getNodeName());
        assertEquals(RadioResolveConfiguration.Mode.CALLS_ONLY, loadedRadioResolve.getMode());
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
                SELECT server_type, enabled, host, port, json_extract(config_json, '$.mode') AS mode,
                       json_type(config_json, '$.callUploadEnabled') AS call_upload_enabled,
                       json_type(config_json, '$.siteMetadataEnabled') AS site_metadata_enabled
                FROM configuration_broadcast_stream
                """))
            {
                assertTrue(resultSet.next());
                assertEquals("RADIORESOLVE", resultSet.getString("server_type"));
                assertEquals(1, resultSet.getInt("enabled"));
                assertEquals("https://example.invalid/upload", resultSet.getString("host"));
                assertEquals(80, resultSet.getInt("port"));
                assertEquals("CALLS_ONLY", resultSet.getString("mode"));
                assertNull(resultSet.getString("call_upload_enabled"));
                assertNull(resultSet.getString("site_metadata_enabled"));
            }
        }
    }

    @Test
    void roundTripsRememberedControlFrequencyAsTheRestartPreference() throws Exception
    {
        Path database = mTemporaryFolder.resolve("remembered-control.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        long firstFrequency = 851_012_500L;
        long rememberedFrequency = 852_012_500L;

        Channel channel = new Channel("Control");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(firstFrequency, rememberedFrequency));
        source.setPreferredFrequency(rememberedFrequency);
        channel.setSourceConfiguration(source);

        TestConfiguration state = new TestConfiguration();
        state.setChannels(List.of(channel));
        replace(database, state);

        Channel restoredChannel = store.load().channels().getFirst();
        SourceConfigTunerMultipleFrequency restored = assertInstanceOf(SourceConfigTunerMultipleFrequency.class,
            restoredChannel.getSourceConfiguration());
        assertEquals(List.of(firstFrequency, rememberedFrequency), restored.getFrequencies());
        assertEquals(rememberedFrequency, restored.getPreferredFrequency());

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT primary_frequency_hz FROM configuration_channel"))
        {
            assertTrue(resultSet.next());
            assertEquals(rememberedFrequency, resultSet.getLong("primary_frequency_hz"));
        }
    }

    @Test
    void replacementDropsOpaqueRetiredRowsAndLeavesLegacyChannelMapsUntouched() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retired-configuration.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        String channelMapJson = "{\"name\":\"Retired Map\",\"ranges\":[{\"first\":1,\"last\":9}]}";

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement channelStatement = connection.prepareStatement("""
                INSERT INTO configuration_channel (
                    id, configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_name,
                    radres_guid, auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                    frequency_count, recording_enabled, event_logging_enabled, config_json
                ) VALUES (77, '11111111-1111-4111-8111-111111111111', 'TRUNKED', 9, 'Legacy System',
                    'Legacy Site', 'Retired MPT', 'Legacy Aliases',
                    '22222222-2222-4222-8222-222222222222', 1, 4, 'MPT1327', 'TUNER', 451000000, 2, 1, 1,
                    '{"type":"retired-channel","payload":"must be dropped without decoding"}')
                """);
            PreparedStatement soundCardStatement = connection.prepareStatement("""
                INSERT INTO configuration_channel (
                    id, configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_name,
                    radres_guid, auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                    frequency_count, recording_enabled, event_logging_enabled, config_json
                ) VALUES (78, '33333333-3333-4333-8333-333333333333', 'CONVENTIONAL', 10, 'Legacy System',
                    'Audio Input', 'Retired Sound Card', 'Legacy Aliases', NULL, 1, 5, 'DMR', 'MIXER', NULL, 0, 0, 1,
                    '{"type":"retired-sound-card","payload":"must be dropped without decoding"}')
                """);
            PreparedStatement mapStatement = connection.prepareStatement("""
                INSERT INTO configuration_channel_map (id, sort_order, name, config_json)
                VALUES (88, 3, 'Retired Map', ?)
                """))
        {
            channelStatement.executeUpdate();
            soundCardStatement.executeUpdate();
            mapStatement.setString(1, channelMapJson);
            mapStatement.executeUpdate();
        }

        Channel active = new Channel("Supported DMR");
        active.setSystem("Active System");
        active.setDecodeConfiguration(new io.github.dsheirer.module.decode.dmr.DecodeConfigDMR());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(460_000_000L);
        active.setSourceConfiguration(source);
        TestConfiguration replacement = new TestConfiguration();
        replacement.setChannels(List.of(active));
        replace(database, replacement);

        ChannelAndBroadcastConfiguration loaded = store.load();
        assertEquals(1, loaded.channels().size());
        assertEquals("Supported DMR", loaded.channels().get(0).getName());

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement retiredQuery = connection.prepareStatement("""
                SELECT COUNT(*) FROM configuration_channel WHERE id IN (77, 78)
                """);
            PreparedStatement mapQuery = connection.prepareStatement("""
                SELECT sort_order, name, config_json FROM configuration_channel_map WHERE id = 88
                """))
        {
            try(ResultSet resultSet = retiredQuery.executeQuery())
            {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt(1));
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

        TestConfiguration state = new TestConfiguration();
        state.setChannels(List.of(channel));
        replace(database, state);

        Channel loaded = store.load().channels().get(0);
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

        TestConfiguration state = new TestConfiguration();
        state.setChannels(List.of(channel));

        replace(database, state);

        Channel loaded = store.load().channels().get(0);
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
    void currentFormatLoadRefusesEveryTamperedChannelProjectionScalar() throws Exception
    {
        List<ProjectionTamper> tampers = List.of(
            new ProjectionTamper("auto_start", "auto_start=2"),
            new ProjectionTamper("auto_start", "auto_start=0.5"),
            new ProjectionTamper("auto_start_order", "auto_start_order=1.5"),
            new ProjectionTamper("auto_start_order", "auto_start_order=2147483648"),
            new ProjectionTamper("decoder_type", "decoder_type='NBFM'"),
            new ProjectionTamper("source_type", "source_type='RECORDING'"),
            new ProjectionTamper("primary_frequency_hz", "primary_frequency_hz=121900001"),
            new ProjectionTamper("frequency_count", "frequency_count=2"),
            new ProjectionTamper("recording_enabled", "recording_enabled=1"),
            new ProjectionTamper("event_logging_enabled", "event_logging_enabled=1"));

        for(int index = 0; index < tampers.size(); index++)
        {
            ProjectionTamper tamper = tampers.get(index);
            Path database = mTemporaryFolder.resolve("tampered-projection-" + index + ".sqlite");
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
            Channel channel = new Channel("Airport Ground");
            channel.setDecodeConfiguration(new DecodeConfigAM());
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(121_900_000L);
            channel.setSourceConfiguration(source);
            TestConfiguration state = new TestConfiguration();
            state.setChannels(List.of(channel));
            replace(database, state);

            try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement())
            {
                statement.execute("PRAGMA ignore_check_constraints=ON");
                assertEquals(1, statement.executeUpdate("UPDATE configuration_channel SET " + tamper.assignment()));
                statement.execute("PRAGMA ignore_check_constraints=OFF");
            }

            IOException exception = assertThrows(IOException.class, store::load, tamper.column());
            assertTrue(exception.getMessage().contains(tamper.column()), exception::getMessage);
        }
    }

    @Test
    void blankConventionalUploadGuidIsAssignedOnTheNextSaveWithoutChangingItsIdentity() throws Exception
    {
        Path database = mTemporaryFolder.resolve("blank-conventional-guid.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        Channel channel = new Channel("Airport Ground");
        channel.setDecodeConfiguration(new DecodeConfigAM());
        String configurationId = channel.getConfigurationId();
        TestConfiguration initial = new TestConfiguration();
        initial.setChannels(List.of(channel));
        replace(database, initial);

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET radres_guid = NULL, config_json = json_remove(config_json, '$.radresGuid')
                """);
        }

        Channel loadedBlank = store.load().channels().getFirst();
        assertEquals(configurationId, loadedBlank.getConfigurationId());
        assertFalse(loadedBlank.hasRadresGuid(), "loading must preserve an explicitly blank correlation value");

        TestConfiguration replacement = new TestConfiguration();
        replacement.setChannels(List.of(loadedBlank));
        replace(database, replacement);

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT configuration_id, radres_guid,
                       json_extract(config_json, '$.radresGuid') AS json_radres_guid
                FROM configuration_channel
                """))
        {
            assertTrue(resultSet.next());
            assertEquals(configurationId, resultSet.getString("configuration_id"));
            String assignedGuid = resultSet.getString("radres_guid");
            assertEquals(assignedGuid, UUID.fromString(assignedGuid).toString());
            assertEquals(assignedGuid, resultSet.getString("json_radres_guid"));
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

        assertThrows(IOException.class, store::load);

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

    private static void replace(Path database, TestConfiguration state) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            connection.setAutoCommit(false);
            new ConfigurationDatabaseStore(database).replace(connection,
                new ChannelAndBroadcastConfiguration(state.mChannels, state.mBroadcastConfigurations));
            connection.commit();
        }
    }

    private static final class TestConfiguration
    {
        private List<Channel> mChannels = List.of();
        private List<BroadcastConfiguration> mBroadcastConfigurations = List.of();

        private void setChannels(List<Channel> channels)
        {
            mChannels = channels;
        }

        private void setBroadcastConfigurations(List<BroadcastConfiguration> configurations)
        {
            mBroadcastConfigurations = configurations;
        }
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

    private record ProjectionTamper(String column, String assignment)
    {
    }
}
