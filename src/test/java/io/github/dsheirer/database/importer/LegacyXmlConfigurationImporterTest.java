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

package io.github.dsheirer.database.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyXmlConfigurationImporterTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void migratesCurrentXmlShapeToSqlite() throws Exception
    {
        Path xml = writePlaylistXml();
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");

        LegacyXmlConfigurationImporter.ImportResult result =
            LegacyXmlConfigurationImporter.importPlaylist(xml, database);

        assertEquals(1, result.aliasCount());
        assertEquals(1, result.streamCount());
        assertEquals(1, result.channelMapCount());
        assertEquals(2, result.channelCount());
        assertEquals(1, result.p25ConventionalConversions());

        List<Alias> aliases = new AliasDatabaseStore(database).loadAliases();
        assertEquals(1, aliases.size());
        Alias alias = aliases.get(0);
        assertEquals("Dispatch", alias.getName());
        assertEquals("County", alias.getAliasListName());
        assertEquals(4, alias.getAliasIdentifiers().size());
        assertTrue(alias.getAliasIdentifiers().stream().anyMatch(Talkgroup.class::isInstance));
        assertTrue(alias.getAliasIdentifiers().stream().anyMatch(BroadcastChannel.class::isInstance));
        assertTrue(alias.getAliasIdentifiers().stream().anyMatch(Priority.class::isInstance));
        assertTrue(alias.getAliasIdentifiers().stream().anyMatch(TalkgroupRange.class::isInstance));

        ConfigurationState state = new ConfigurationDatabaseStore(database).loadConfigurationState();
        assertEquals(2, state.getChannels().size());
        assertEquals(1, state.getBroadcastConfigurations().size());
        assertEquals(1, state.getChannelMaps().size());

        assertInstanceOf(RadioResolveConfiguration.class, state.getBroadcastConfigurations().get(0));
        RadioResolveConfiguration stream = (RadioResolveConfiguration)state.getBroadcastConfigurations().get(0);
        assertEquals("test-api-key", stream.getApiKey());
        assertEquals("TEST-NODE", stream.getNodeName());

        assertInstanceOf(DecodeConfigP25Conventional.class, state.getChannels().get(0).getDecodeConfiguration());
        assertEquals(DecoderType.P25_CONVENTIONAL, state.getChannels().get(0).getDecodeConfiguration().getDecoderType());
        assertTrue(state.getChannels().get(0).getAutoStart());
        assertEquals(4, state.getChannels().get(0).getAutoStartOrder());
        assertEquals("11111111-2222-3333-4444-555555555555", state.getChannels().get(0).getRadresGuid());
        assertInstanceOf(SourceConfigTuner.class, state.getChannels().get(0).getSourceConfiguration());

        assertInstanceOf(DecodeConfigP25Phase1.class, state.getChannels().get(1).getDecodeConfiguration());
        SourceConfigTunerMultipleFrequency trunkedSource = assertInstanceOf(SourceConfigTunerMultipleFrequency.class,
            state.getChannels().get(1).getSourceConfiguration());
        assertEquals(List.of(856137500L, 856162500L), trunkedSource.getFrequencies());
        DecodeConfigP25 p25 = (DecodeConfigP25)state.getChannels().get(1).getDecodeConfiguration();
        assertTrue(p25.getLearnAnnouncedControlChannels());
    }

    @Test
    void refusesToOverwriteExistingDatabase() throws Exception
    {
        Path xml = writePlaylistXml();
        Path database = mTemporaryFolder.resolve("existing.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        assertThrows(IOException.class, () -> LegacyXmlConfigurationImporter.importPlaylist(xml, database));
    }

    private Path writePlaylistXml() throws IOException
    {
        Path xml = mTemporaryFolder.resolve("default.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <alias name="Dispatch" list="County" group="Dispatch" color="255">
                <id type="talkgroup" protocol="APCO25" value="1234"/>
                <id type="broadcastChannel" channel="RadioResolve"/>
                <id type="priority" priority="50"/>
                <id type="talkgroupRange" protocol="APCO25" min="2000" max="2005"/>
              </alias>
              <stream type="RADIORESOLVE" broadcast_format="MP3" name="RadioResolve" host="http://198.51.100.10:8080"
                  api_key="test-api-key" node_name="TEST-NODE" node_timezone="America/New_York" enabled="true"
                  maximum_recording_age="600000" mode="CALLS_AND_METADATA" concurrent_uploads="4"/>
              <channel_map name="County Map">
                <range first="0" last="4095" base="851000000" size="12500"/>
              </channel_map>
              <channel system="County" site="Conventional" name="Fixed P25" radres_guid="11111111-2222-3333-4444-555555555555"
                  enabled="true" order="4">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="155250000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigP25Phase1" modulation="CQPSK" ignore_data_calls="false"
                    learn_control_channels="false" traffic_channel_pool_size="20"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
              <channel system="County" site="Simulcast" name="Trunked P25" radres_guid="22222222-3333-4444-5555-666666666666">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTunerMultipleFrequency" source_type="TUNER_MULTIPLE_FREQUENCIES">
                  <frequency>856137500</frequency>
                  <frequency>856162500</frequency>
                </source_configuration>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigP25Phase1" modulation="CQPSK" ignore_data_calls="false"
                    learn_control_channels="true" traffic_channel_pool_size="20"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
            </playlist>
            """);
        return xml;
    }
}
