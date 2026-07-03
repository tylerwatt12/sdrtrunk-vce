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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.map.ChannelMap;
import io.github.dsheirer.controller.channel.map.ChannelRange;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.source.config.SourceConfigTuner;
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
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        assertFalse(store.isInitialized());

        Channel channel = new Channel("Control");
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

        ChannelMap channelMap = new ChannelMap("County Map");
        channelMap.addRange(new ChannelRange(1, 10, 851_000_000, 12_500));

        RadioResolveConfiguration stream = new RadioResolveConfiguration();
        stream.setName("RadioResolve");
        stream.setHost("http://198.51.100.10:8080");
        stream.setApiKey("test-api-key");
        stream.setNodeName("TEST-NODE");
        stream.setEnabled(true);

        ConfigurationState state = new ConfigurationState();
        state.setChannels(List.of(channel));
        state.setChannelMaps(List.of(channelMap));
        state.setBroadcastConfigurations(List.of(stream));

        store.replaceConfigurationState(state);
        assertTrue(store.isInitialized());

        ConfigurationState loaded = store.loadConfigurationState();
        assertEquals(1, loaded.getChannels().size());
        assertEquals(1, loaded.getChannelMaps().size());
        assertEquals(1, loaded.getBroadcastConfigurations().size());

        Channel loadedChannel = loaded.getChannels().get(0);
        assertEquals("County", loadedChannel.getSystem());
        assertEquals("Simulcast", loadedChannel.getSite());
        assertEquals("Control", loadedChannel.getName());
        assertEquals("County Aliases", loadedChannel.getAliasListName());
        assertEquals("11111111-2222-3333-4444-555555555555", loadedChannel.getRadresGuid());
        assertTrue(loadedChannel.getAutoStart());
        assertEquals(2, loadedChannel.getAutoStartOrder());
        assertInstanceOf(SourceConfigTuner.class, loadedChannel.getSourceConfiguration());
        assertInstanceOf(DecodeConfigP25Phase1.class, loadedChannel.getDecodeConfiguration());
        SourceConfigTuner loadedSource = (SourceConfigTuner)loadedChannel.getSourceConfiguration();
        assertEquals(853_762_500L, loadedSource.getFrequency());
        assertEquals("Airspy", loadedSource.getPreferredTuner());

        ChannelMap loadedMap = loaded.getChannelMaps().get(0);
        assertEquals("County Map", loadedMap.getName());
        assertEquals(1, loadedMap.getRanges().size());
        assertEquals(851_000_000, loadedMap.getRanges().get(0).getBaseFrequency());

        BroadcastConfiguration loadedStream = loaded.getBroadcastConfigurations().get(0);
        assertInstanceOf(RadioResolveConfiguration.class, loadedStream);
        RadioResolveConfiguration loadedRadioResolve = (RadioResolveConfiguration)loadedStream;
        assertEquals("RadioResolve", loadedRadioResolve.getName());
        assertEquals("http://198.51.100.10:8080", loadedRadioResolve.getHost());
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
                assertEquals("http://198.51.100.10:8080", resultSet.getString("host"));
                assertEquals(80, resultSet.getInt("port"));
            }
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
}
