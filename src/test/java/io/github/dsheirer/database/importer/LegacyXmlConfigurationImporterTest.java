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
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.protocol.Protocol;
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

    @Test
    void importsLegacyDmrTierThreeFrequencyMappings() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("dmr-tier-three.xml");
        StringBuilder mappings = new StringBuilder();

        for(int lcn = 1; lcn <= 78; lcn++)
        {
            mappings.append("<timeslot lsn=\"").append(lcn).append("\" downlink=\"")
                .append(451_000_000L + lcn * 12_500L).append("\" uplink=\"0\"/>\n");
        }

        Files.writeString(xml, """
            <playlist version="4">
              <channel system="Busy DMR" site="Site 1" name="Control">
                <alias_list_name>Busy DMR</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="452000000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigDMR" ignore_data_calls="false" ignore_crc="true"
                    use_compressed_talkgroups="true" traffic_channel_pool_size="30">
            """ + mappings + """
                </decode_configuration>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
            </playlist>
            """);

        Path database = mTemporaryFolder.resolve("dmr.sqlite");
        LegacyXmlConfigurationImporter.importPlaylist(xml, database);
        ConfigurationState state = new ConfigurationDatabaseStore(database).loadConfigurationState();
        DecodeConfigDMR dmr = assertInstanceOf(DecodeConfigDMR.class,
            state.getChannels().get(0).getDecodeConfiguration());

        assertEquals(78, dmr.getTimeslotMap().size());
        assertEquals(1, dmr.getTimeslotMap().get(0).getNumber());
        assertEquals(451_012_500L, dmr.getTimeslotMap().get(0).getDownlinkFrequency());
        assertEquals(78, dmr.getTimeslotMap().get(77).getNumber());
        assertEquals(451_975_000L, dmr.getTimeslotMap().get(77).getDownlinkFrequency());
        assertEquals(30, dmr.getTrafficChannelPoolSize());
        assertTrue(dmr.getIgnoreCRCChecksums());
        assertTrue(dmr.isUseCompressedTalkgroups());
    }

    @Test
    void classifiesLegacyP25ChannelsUsingTrunkedIndicators()
    {
        ConfigurationState state = new ConfigurationState();
        state.setAliases(List.of(
            talkgroupAlias("Trunked", 1001),
            talkgroupAlias("Trunked", 1002),
            talkgroupAlias("Trunked", 1003),
            talkgroupAlias("Two Talkgroups", 2001),
            talkgroupAlias("Two Talkgroups", 2002),
            talkgroupAlias("Other List", 2003)));

        Channel conventional = p25Channel("Conventional", "No Aliases", 154_755_000L, Modulation.C4FM);
        Channel lsm = p25Channel("LSM", "No Aliases", 155_250_000L, Modulation.CQPSK);
        Channel sevenHundredMhz = p25Channel("700 MHz", "No Aliases", 769_012_500L, Modulation.C4FM);
        Channel eightHundredMhz = p25Channel("800 MHz", "No Aliases", 851_012_500L, Modulation.C4FM);
        Channel nineHundredMhz = p25Channel("900 MHz", "No Aliases", 935_012_500L, Modulation.C4FM);
        Channel threeTalkgroups = p25Channel("Three Talkgroups", "Trunked", 155_500_000L, Modulation.C4FM);
        Channel twoTalkgroups = p25Channel("Two Talkgroups", "Two Talkgroups", 155_750_000L, Modulation.C4FM);
        Channel multipleFrequencies = new Channel("Multiple Frequencies");
        multipleFrequencies.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTunerMultipleFrequency multipleFrequencySource = new SourceConfigTunerMultipleFrequency();
        multipleFrequencySource.setFrequencies(List.of(155_000_000L, 156_000_000L));
        multipleFrequencies.setSourceConfiguration(multipleFrequencySource);

        state.setChannels(List.of(conventional, lsm, sevenHundredMhz, eightHundredMhz, nineHundredMhz,
            threeTalkgroups, twoTalkgroups, multipleFrequencies));

        assertEquals(2, LegacyXmlConfigurationImporter.convertLikelyConventionalP25Channels(state));
        assertInstanceOf(DecodeConfigP25Conventional.class, conventional.getDecodeConfiguration());
        assertInstanceOf(DecodeConfigP25Phase1.class, lsm.getDecodeConfiguration());
        assertInstanceOf(DecodeConfigP25Phase1.class, sevenHundredMhz.getDecodeConfiguration());
        assertInstanceOf(DecodeConfigP25Phase1.class, eightHundredMhz.getDecodeConfiguration());
        assertInstanceOf(DecodeConfigP25Phase1.class, nineHundredMhz.getDecodeConfiguration());
        assertInstanceOf(DecodeConfigP25Phase1.class, threeTalkgroups.getDecodeConfiguration());
        assertInstanceOf(DecodeConfigP25Conventional.class, twoTalkgroups.getDecodeConfiguration());
        assertInstanceOf(DecodeConfigP25Phase1.class, multipleFrequencies.getDecodeConfiguration());
    }

    private static Alias talkgroupAlias(String aliasListName, int talkgroup)
    {
        Alias alias = new Alias("Talkgroup " + talkgroup);
        alias.setAliasListName(aliasListName);
        alias.addAliasID(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }

    private static Channel p25Channel(String name, String aliasListName, long frequency, Modulation modulation)
    {
        Channel channel = new Channel(name);
        channel.setAliasListName(aliasListName);
        DecodeConfigP25Phase1 decodeConfiguration = new DecodeConfigP25Phase1();
        decodeConfiguration.setModulation(modulation);
        channel.setDecodeConfiguration(decodeConfiguration);
        SourceConfigTuner sourceConfiguration = new SourceConfigTuner();
        sourceConfiguration.setFrequency(frequency);
        channel.setSourceConfiguration(sourceConfiguration);
        return channel;
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
              <stream type="RADIORESOLVE" broadcast_format="MP3" name="RadioResolve" host="https://example.invalid/upload"
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
                <decode_configuration type="decodeConfigP25Phase1" modulation="C4FM" ignore_data_calls="false"
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
