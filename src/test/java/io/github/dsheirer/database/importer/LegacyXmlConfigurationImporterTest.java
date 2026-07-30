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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListFamily;
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
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
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

        LegacyXmlConfigurationImporter.importPlaylist(xml, database);

        AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
        var definitions = aliasStore.loadAliasListDefinitions();
        List<Alias> aliases = aliasStore.loadAliases(definitions);
        assertEquals(1, definitions.size());
        assertEquals("County", definitions.get(0).getName());
        assertEquals(AliasListFamily.P25, definitions.get(0).getFamily());
        assertEquals(2, aliases.size());
        assertTrue(aliases.stream().allMatch(alias -> alias.getId() > 0 && alias.getAliasListId() > 0));
        assertTrue(aliases.stream().allMatch(alias -> "Dispatch".equals(alias.getName())));
        assertTrue(aliases.stream().allMatch(alias -> "County".equals(alias.getAliasListName())));
        assertTrue(aliases.stream().anyMatch(alias -> alias.getMatchIdentifier() instanceof Talkgroup));
        assertTrue(aliases.stream().anyMatch(alias -> alias.getMatchIdentifier() instanceof TalkgroupRange));
        assertTrue(aliases.stream().allMatch(alias -> alias.hasBroadcastChannel("RadioResolve")));
        assertTrue(aliases.stream().allMatch(alias -> alias.getPlaybackPriority() == 50));

        ConfigurationState state = new ConfigurationDatabaseStore(database).loadConfigurationState();
        assertEquals(2, state.getChannels().size());
        assertEquals(1, state.getBroadcastConfigurations().size());

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
        assertEquals(DMRChannelMode.TRUNKED, dmr.getChannelMode());
        assertTrue(dmr.getIgnoreCRCChecksums());
        assertTrue(dmr.isUseCompressedTalkgroups());
    }

    @Test
    void silentlyDropsAllLegacyAliasActions() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("retired-script.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <alias name="Dispatch" list="County">
                <id type="talkgroup" protocol="APCO25" value="1234"/>
                <action type="beepAction" interval="ONCE" period="0"/>
                <action type="clipAction" interval="DELAYED_RESET" period="5" path="/tmp/retired.wav"/>
                <action type="scriptAction" interval="ONCE" period="0" script="/tmp/retired-script"/>
              </alias>
              <channel system="County" site="Site" name="Control">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="851000000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigP25Phase1" modulation="CQPSK"
                    ignore_data_calls="false"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
            </playlist>
            """);

        Path database = mTemporaryFolder.resolve("retired-actions.sqlite");
        LegacyXmlConfigurationImporter.importPlaylist(xml, database);
        AliasDatabaseStore store = new AliasDatabaseStore(database);
        List<Alias> aliases = store.loadAliases(store.loadAliasListDefinitions());
        assertEquals(1, aliases.size());
        assertInstanceOf(Talkgroup.class, aliases.getFirst().getMatchIdentifier());
    }

    @Test
    void silentlyDropsRetiredDecoderChannelsAndIdentifiers() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("retired-decoders.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <alias name="AM Alias" list="AM System">
                <id type="talkgroup" protocol="AM" value="1"/>
              </alias>
              <alias name="LTR Alias" list="LTR System">
                <id type="talkgroup" protocol="LTR" value="257"/>
              </alias>
              <alias name="LTR-Net Alias" list="LTR-Net System">
                <id type="uniqueID" uid="1234"/>
              </alias>
              <alias name="Passport Alias" list="Passport System">
                <id type="min" min="12345"/>
              </alias>
              <alias name="Current Alias" list="Current">
                <id type="talkgroup" protocol="DMR" value="100"/>
              </alias>
              <channel system="AM System" site="Site" name="AM">
                <alias_list_name>AM System</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="118500000"/>
                <decode_configuration type="decodeConfigAM" talkgroup="1"/>
              </channel>
              <channel system="LTR System" site="Site" name="LTR">
                <alias_list_name>LTR System</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="451000000"/>
                <decode_configuration type="decodeConfigLTRStandard"/>
              </channel>
              <channel system="LTR-Net System" site="Site" name="LTR-Net">
                <alias_list_name>LTR-Net System</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="452000000"/>
                <decode_configuration type="decodeConfigLTRNet"/>
              </channel>
              <channel system="Passport System" site="Site" name="Passport">
                <alias_list_name>Passport System</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="453000000"/>
                <decode_configuration type="decodeConfigPassport"/>
              </channel>
              <channel system="Current" site="Site" name="DMR">
                <alias_list_name>Current</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="460000000"/>
                <decode_configuration type="decodeConfigDMR" ignore_data_calls="false"/>
              </channel>
            </playlist>
            """);

        ConfigurationState state = LegacyXmlConfigurationImporter.readConfigurationState(xml);

        assertEquals(1, state.getChannels().size());
        assertEquals(DecoderType.DMR, state.getChannels().getFirst().getDecodeConfiguration().getDecoderType());
        assertEquals(1, state.getAliases().size());
        assertEquals("Current Alias", state.getAliases().getFirst().getName());
        Talkgroup talkgroup = assertInstanceOf(Talkgroup.class,
            state.getAliases().getFirst().getMatchIdentifier());
        assertEquals(Protocol.DMR, talkgroup.getProtocol());
    }

    @Test
    void reportsMalformedMatchersAndInfersUnassignedListFamily() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("strict-alias-import.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <alias name="Missing" list="County"/>
              <alias name="Invalid" list="County">
                <id type="talkgroup" protocol="APCO25" value="-1"/>
              </alias>
              <alias name="Retired" list="County">
                <id type="mpt1327ID" ident="123"/>
              </alias>
              <alias name="Orphan" list="Old List">
                <id type="talkgroup" protocol="APCO25" value="100"/>
              </alias>
              <alias name="Wrong Family" list="County">
                <id type="talkgroup" protocol="DMR" value="200"/>
              </alias>
              <channel system="County" site="Site" name="Control">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="851000000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigP25Phase1" modulation="CQPSK"
                    ignore_data_calls="false"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
            </playlist>
            """);

        Path database = mTemporaryFolder.resolve("strict.sqlite");
        LegacyXmlConfigurationImporter.importPlaylist(xml, database);
        AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
        var definitions = aliasStore.loadAliasListDefinitions();
        assertEquals(2, definitions.size());
        var aliases = aliasStore.loadAliases(definitions);
        assertEquals(1, aliases.size());
        assertEquals("Orphan", aliases.getFirst().getName());
        assertEquals(AliasListFamily.P25,
            definitions.stream().filter(definition -> definition.getName().equals("Old List"))
                .findFirst().orElseThrow().getFamily());
    }

    @Test
    void skipsRetiredMptChannelsAndChannelMapsFromMixedLegacyXml() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("mixed-mpt.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <channel_map name="Retired Map">
                <range first="0" last="4095" base="451000000" size="12500"/>
              </channel_map>
              <channel system="Legacy" site="MPT Site" name="Retired MPT" enabled="true" order="1">
                <alias_list_name>Legacy</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="451000000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigMPT1327" channel_map_name="Retired Map"
                    sync="FRENCH" traffic_channel_pool_size="8" call_timeout="30"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
              <channel system="Current" site="DMR Site" name="Supported DMR" enabled="true" order="2">
                <alias_list_name>Current</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="460000000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigDMR" ignore_data_calls="false"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
            </playlist>
            """);

        ConfigurationState state = LegacyXmlConfigurationImporter.readConfigurationState(xml);

        assertEquals(1, state.getChannels().size());
        assertEquals("Supported DMR", state.getChannels().get(0).getName());
        assertEquals(DecoderType.DMR, state.getChannels().get(0).getDecodeConfiguration().getDecoderType());
        assertEquals(DMRChannelMode.CONVENTIONAL,
            ((DecodeConfigDMR)state.getChannels().get(0).getDecodeConfiguration()).getChannelMode());
    }

    @Test
    void skipsRetiredSoundCardChannelsWithoutChangingTheLegacyXml() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("mixed-sound-card.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <channel system="Legacy" site="Audio Input" name="Retired Sound Card" enabled="true" order="1">
                <alias_list_name>Legacy</alias_list_name>
                <source_configuration type="sourceConfigMixer" source_type="MIXER"
                    mixer="Legacy Line Input" channel="RIGHT"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigDMR" ignore_data_calls="false"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
              <channel system="Current" site="DMR Site" name="Supported DMR" enabled="true" order="2">
                <alias_list_name>Current</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="460000000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigDMR" ignore_data_calls="false"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
            </playlist>
            """);
        byte[] original = Files.readAllBytes(xml);

        ConfigurationState state = LegacyXmlConfigurationImporter.readConfigurationState(xml);

        assertEquals(1, state.getChannels().size());
        assertEquals("Supported DMR", state.getChannels().get(0).getName());
        assertEquals(DecoderType.DMR, state.getChannels().get(0).getDecodeConfiguration().getDecoderType());
        assertArrayEquals(original, Files.readAllBytes(xml),
            "reading a legacy playlist must never rewrite or delete its retired sound-card channel");
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
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
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
