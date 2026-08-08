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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.icecast.IcecastHTTPConfiguration;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.audio.broadcast.shoutcast.v1.ShoutcastV1Configuration;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
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
        assertTrue(aliases.stream().allMatch(alias -> alias.getMatchIdentifier().toString()
            .equals(alias.getMatchIdentifier().valueProperty().get())));
        assertTrue(aliases.stream().allMatch(alias -> alias.getBroadcastChannels().stream()
            .allMatch(channel -> channel.toString().equals(channel.valueProperty().get()))));
        assertTrue(aliases.stream().allMatch(alias -> alias.getPlaybackPriority() == 50));
        assertTrue(aliases.stream().allMatch(alias -> alias.getStreamTalkgroupAlias() != null &&
            alias.getStreamTalkgroupAlias().getValue() == 42 &&
            alias.getStreamTalkgroupAlias().toString().equals(
                alias.getStreamTalkgroupAlias().valueProperty().get())));

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
    void dropsRetiredFullyQualifiedTalkgroupAliases() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("retired-fq-talkgroup.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <alias name="ISSI Dispatch" list="County">
                <id type="p25FullyQualifiedTalkgroup" protocol="APCO25" value="700"
                    wacn="781824" system="840"/>
                <id type="broadcastChannel" channel="Primary"/>
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
        Path database = mTemporaryFolder.resolve("retired-fq-talkgroup.sqlite");

        LegacyXmlConfigurationImporter.importPlaylist(xml, database);

        AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
        assertTrue(aliasStore.loadAliases(aliasStore.loadAliasListDefinitions()).isEmpty());
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
    void importPreservesExistingDataRootPosixAccess() throws Exception
    {
        Path xml = writePlaylistXml();
        Path dataRoot = Files.createDirectory(mTemporaryFolder.resolve("xml-import-data"));
        PosixFileAttributeView view = Files.getFileAttributeView(dataRoot, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "POSIX file attributes are unavailable");
        view.setPermissions(PosixFilePermissions.fromString("rwx------"));
        PosixFileAttributes before = view.readAttributes();
        Path database = dataRoot.resolve("database/sdrtrunk.sqlite");

        LegacyXmlConfigurationImporter.importPlaylist(xml, database);

        PosixFileAttributes after = Files.readAttributes(dataRoot, PosixFileAttributes.class);
        assertEquals(before.permissions(), after.permissions());
        assertEquals(before.owner(), after.owner());
        assertEquals(before.group(), after.group());
    }

    @Test
    void upgradesStockVersionOneAndTwoP25AndNbfmMatchers() throws Exception
    {
        for(int version: List.of(1, 2))
        {
            Path xml = mTemporaryFolder.resolve("playlist-v" + version + ".xml");
            Files.writeString(xml, legacyMatcherPlaylist(version));

            ConfigurationState state = LegacyXmlConfigurationImporter.readConfigurationState(xml);
            assertEquals(4, state.getAliases().size());

            Talkgroup p25Talkgroup = assertInstanceOf(Talkgroup.class,
                matcher(state, "P25 Talkgroup"));
            assertEquals(Protocol.APCO25, p25Talkgroup.getProtocol());
            assertEquals(0x1234, p25Talkgroup.getValue());

            Radio p25Radio = assertInstanceOf(Radio.class, matcher(state, "P25 Radio"));
            assertEquals(Protocol.APCO25, p25Radio.getProtocol());
            assertEquals(0x123456, p25Radio.getValue());

            Talkgroup fleetsync = assertInstanceOf(Talkgroup.class, matcher(state, "FleetSync"));
            assertEquals(Protocol.FLEETSYNC, fleetsync.getProtocol());
            assertEquals((1 << 12) + 1, fleetsync.getValue());

            Talkgroup mdc1200 = assertInstanceOf(Talkgroup.class, matcher(state, "MDC-1200"));
            assertEquals(Protocol.MDC1200, mdc1200.getProtocol());
            assertEquals(0xABCD, mdc1200.getValue());
        }
    }

    @Test
    void treatsMissingVersionAsOneAndRemovesVersionOneOnlySettings() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("playlist-without-version.xml");
        Files.writeString(xml, """
            <playlist>
              <alias name="Dispatch" list="County">
                <id type="talkgroupID" talkgroup="1234"/>
                <id type="siteID" site="Old Site"/>
                <id type="nonRecordable"/>
              </alias>
              <channel system="County" site="Site" name="Control">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="851000000"/>
                <decode_configuration type="decodeConfigP25Phase1" modulation="CQPSK"
                    ignore_data_calls="false"/>
                <event_log_configuration>
                  <logger>BINARY_MESSAGE</logger>
                  <logger>DECODED_MESSAGE</logger>
                </event_log_configuration>
                <record_configuration>
                  <recorder>AUDIO</recorder>
                  <recorder>BASEBAND</recorder>
                </record_configuration>
              </channel>
            </playlist>
            """);

        ConfigurationState state = LegacyXmlConfigurationImporter.readConfigurationState(xml);
        assertEquals(1, state.getAliases().size());
        Alias alias = state.getAliases().getFirst();
        assertInstanceOf(Talkgroup.class, alias.getMatchIdentifier());
        assertFalse(alias.isRecordable());

        Channel channel = state.getChannels().getFirst();
        assertEquals(List.of(RecorderType.BASEBAND), channel.getRecordConfiguration().getRecorders());
        assertEquals(List.of(EventLogType.DECODED_MESSAGE), channel.getEventLogConfiguration().getLoggers());
    }

    @Test
    void upgradesStockVersionThreeP25RadioIdsAndSplitsCrossingRanges() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("playlist-v3.xml");
        Files.writeString(xml, """
            <playlist version="3">
              <alias name="Large Single" list="County">
                <id type="talkgroup" protocol="APCO25" value="70000"/>
              </alias>
              <alias name="Crossing Range" list="County">
                <id type="talkgroupRange" protocol="APCO25" min="65000" max="66000"/>
              </alias>
              <alias name="Radio Range" list="County">
                <id type="talkgroupRange" protocol="APCO25" min="70000" max="71000"/>
              </alias>
              <channel system="County" site="Site" name="Control">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="851000000"/>
                <decode_configuration type="decodeConfigP25Phase1" modulation="CQPSK"
                    ignore_data_calls="false"/>
              </channel>
            </playlist>
            """);

        ConfigurationState state = LegacyXmlConfigurationImporter.readConfigurationState(xml);
        assertEquals(4, state.getAliases().size());

        Radio single = assertInstanceOf(Radio.class, matcher(state, "Large Single"));
        assertEquals(70000, single.getValue());

        List<AliasID> crossing = state.getAliases().stream()
            .filter(alias -> "Crossing Range".equals(alias.getName()))
            .map(Alias::getMatchIdentifier)
            .toList();
        assertEquals(2, crossing.size());
        TalkgroupRange talkgroups = assertInstanceOf(TalkgroupRange.class, crossing.get(0));
        assertEquals(65000, talkgroups.getMinTalkgroup());
        assertEquals(65535, talkgroups.getMaxTalkgroup());
        RadioRange radios = assertInstanceOf(RadioRange.class, crossing.get(1));
        assertEquals(65536, radios.getMinRadio());
        assertEquals(66000, radios.getMaxRadio());

        RadioRange radioRange = assertInstanceOf(RadioRange.class, matcher(state, "Radio Range"));
        assertEquals(70000, radioRange.getMinRadio());
        assertEquals(71000, radioRange.getMaxRadio());
    }

    @Test
    void readsVersionFourWithoutApplyingOlderMatcherRules() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("playlist-v4.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <alias name="Dispatch" list="County">
                <id type="talkgroup" protocol="APCO25" value="1234"/>
              </alias>
            </playlist>
            """);

        ConfigurationState state = LegacyXmlConfigurationImporter.readConfigurationState(xml);
        Talkgroup talkgroup = assertInstanceOf(Talkgroup.class, matcher(state, "Dispatch"));
        assertEquals(1234, talkgroup.getValue());
    }

    @Test
    void rejectsFuturePlaylistVersionsBeforeBindingTheirContent() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("playlist-v5.xml");
        Files.writeString(xml, """
            <playlist version="5">
              <stream type="futureBroadcastConfiguration"/>
            </playlist>
            """);

        IOException exception = assertThrows(IOException.class,
            () -> LegacyXmlConfigurationImporter.readConfigurationState(xml));
        assertTrue(exception.getMessage().contains("version 5 is newer"));
        assertTrue(exception.getMessage().contains("versions 1 through 4"));
    }

    @Test
    void rejectsUnsupportedAndMalformedPlaylistVersions() throws Exception
    {
        for(String version: List.of("0", "-1"))
        {
            Path xml = mTemporaryFolder.resolve("unsupported-" + version.replace('-', 'n') + ".xml");
            Files.writeString(xml, "<playlist version=\"" + version + "\"/>");
            IOException exception = assertThrows(IOException.class,
                () -> LegacyXmlConfigurationImporter.readConfigurationState(xml));
            assertTrue(exception.getMessage().contains("Unsupported SDRTrunk playlist version"));
        }

        Path nonnumeric = mTemporaryFolder.resolve("nonnumeric-version.xml");
        Files.writeString(nonnumeric, "<playlist version=\"old\"/>");
        IOException nonnumericException = assertThrows(IOException.class,
            () -> LegacyXmlConfigurationImporter.readConfigurationState(nonnumeric));
        assertTrue(nonnumericException.getMessage().contains("version must be a whole number"));

        Path blank = mTemporaryFolder.resolve("blank-version.xml");
        Files.writeString(blank, "<playlist version=\"  \"/>");
        IOException blankException = assertThrows(IOException.class,
            () -> LegacyXmlConfigurationImporter.readConfigurationState(blank));
        assertTrue(blankException.getMessage().contains("version must be a whole number"));

        Path wrongRoot = mTemporaryFolder.resolve("wrong-root.xml");
        Files.writeString(wrongRoot, "<configuration version=\"4\"/>");
        IOException wrongRootException = assertThrows(IOException.class,
            () -> LegacyXmlConfigurationImporter.readConfigurationState(wrongRoot));
        assertTrue(wrongRootException.getMessage().contains("not an SDRTrunk playlist"));

        Path malformed = mTemporaryFolder.resolve("malformed.xml");
        Files.writeString(malformed, "<playlist version=\"4\"><alias>");
        assertThrows(IOException.class, () -> LegacyXmlConfigurationImporter.readConfigurationState(malformed));

        Path namespacedRoot = mTemporaryFolder.resolve("namespaced-root.xml");
        Files.writeString(namespacedRoot,
            "<foreign:playlist xmlns:foreign=\"https://example.invalid/not-sdrtrunk\" version=\"4\"/>");
        assertThrows(IOException.class, () -> LegacyXmlConfigurationImporter.readConfigurationState(namespacedRoot));

        Path secondRoot = mTemporaryFolder.resolve("second-root.xml");
        Files.writeString(secondRoot, "<playlist version=\"4\"/><other/>");
        assertThrows(IOException.class, () -> LegacyXmlConfigurationImporter.readConfigurationState(secondRoot));

        Path trailingJunk = mTemporaryFolder.resolve("trailing-junk.xml");
        Files.writeString(trailingJunk, "<playlist version=\"4\"/>not-xml");
        assertThrows(IOException.class, () -> LegacyXmlConfigurationImporter.readConfigurationState(trailingJunk));
    }

    @Test
    void rejectsDoctypeAndExternalEntitiesInTheBindingPass() throws Exception
    {
        Path external = mTemporaryFolder.resolve("external-entity.txt");
        Files.writeString(external, "this content must not be read");
        Path xml = mTemporaryFolder.resolve("external-entity.xml");
        Files.writeString(xml, """
            <!DOCTYPE playlist [
              <!ENTITY external SYSTEM "%s">
            ]>
            <playlist version="4">
              <ignored>&external;</ignored>
            </playlist>
            """.formatted(external.toUri()));

        assertThrows(IOException.class, () -> LegacyXmlConfigurationImporter.readConfigurationState(xml));
    }

    @Test
    void omitsRetiredShoutcastVersionTwoWithoutAbortingImport() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("shoutcast-v2.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <stream type="shoutcastV2Configuration" name="Retired Stream" host="localhost" port="8000"
                  password="secret" stream_id="1" user_id="source" bitrate="16" enabled="true"/>
              <alias name="Dispatch" list="County">
                <id type="talkgroup" protocol="APCO25" value="1234"/>
              </alias>
            </playlist>
            """);

        Path database = mTemporaryFolder.resolve("shoutcast-v2.sqlite");
        LegacyXmlConfigurationImporter.importPlaylist(xml, database);

        ConfigurationState state = new ConfigurationDatabaseStore(database).loadConfigurationState();
        assertTrue(state.getBroadcastConfigurations().isEmpty());
        AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
        assertEquals(1, aliasStore.loadAliases(aliasStore.loadAliasListDefinitions()).size());
    }

    @Test
    void preservesExactStockVersionFourXmlNamesThroughSqlite() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("stock-v4-fields.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <alias name="Radio Range" list="County">
                <id type="radioRange" protocol="APCO25" min="70000" max="71000"/>
                <id type="broadcastChannel" channel="Icecast"/>
              </alias>
              <alias name="Tone Sequence" list="County">
                <id type="tones">
                  <toneSequence>
                    <tone value="DTMF_1" duration="4"/>
                    <tone value="DTMF_2" duration="6"/>
                  </toneSequence>
                </id>
              </alias>
              <alias name="DCS" list="Analog">
                <id type="dcs" code="N023"/>
              </alias>
              <stream type="icecastHTTPConfiguration" name="Icecast" host="localhost" port="8000"
                  password="test-password" user_name="source" mount_point="/test" bitrate="48"
                  channels="1" sample_rate="8000" enabled="false">
                <format>MP3</format>
              </stream>
              <stream type="shoutcastV1Configuration" name="Shoutcast" host="localhost" port="8001"
                  password="test-password" bitrate="64" channels="1" enabled="false">
                <format>MP3</format>
              </stream>
              <channel system="County" site="Site" name="P25" enabled="true" order="2">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="851000000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigP25Phase1" modulation="CQPSK"
                    ignore_data_calls="false"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
              <channel system="Analog" site="Site" name="NBFM" enabled="true" order="3">
                <alias_list_name>Analog</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="154875000"/>
                <aux_decode_configuration>
                  <aux_decoder>MDC1200</aux_decoder>
                  <aux_decoder>DCS</aux_decoder>
                  <aux_decoder>MPT1327</aux_decoder>
                  <aux_decoder>FUTURE_AUX_DECODER</aux_decoder>
                </aux_decode_configuration>
                <decode_configuration type="decodeConfigNBFM" bandwidth="BW_25_0" talkgroup="1"
                    audioFilter="false" squelchNoiseOpenThreshold="0.15"
                    squelchNoiseCloseThreshold="0.25" squelchHysteresisOpenThreshold="3"
                    squelchHysteresisCloseThreshold="5" deemphasis="US_750US"
                    squelchTailRemovalEnabled="true" squelchTailRemovalMs="75"
                    squelchHeadRemovalMs="25" lowPassEnabled="true" lowPassCutoff="3200"
                    voiceEnhanceAmount="45.0" bassBoostDb="6.0" outputGain="1.5"/>
                <event_log_configuration>
                  <logger>DECODED_MESSAGE</logger>
                  <logger>FUTURE_EVENT_LOGGER</logger>
                </event_log_configuration>
                <record_configuration>
                  <recorder>BASEBAND</recorder>
                  <recorder>FUTURE_RECORDER</recorder>
                </record_configuration>
              </channel>
            </playlist>
            """);
        byte[] original = Files.readAllBytes(xml);
        Path database = mTemporaryFolder.resolve("stock-v4-fields.sqlite");

        LegacyXmlConfigurationImporter.importPlaylist(xml, database);

        assertArrayEquals(original, Files.readAllBytes(xml));
        AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
        List<Alias> aliases = aliasStore.loadAliases(aliasStore.loadAliasListDefinitions());
        Alias radioAlias = aliases.stream().filter(alias -> "Radio Range".equals(alias.getName()))
            .findFirst().orElseThrow();
        RadioRange radioRange = assertInstanceOf(RadioRange.class, radioAlias.getMatchIdentifier());
        assertEquals(70000, radioRange.getMinRadio());
        assertEquals(71000, radioRange.getMaxRadio());
        assertTrue(radioAlias.hasBroadcastChannel("Icecast"));
        assertEquals("Icecast", radioAlias.getBroadcastChannels().iterator().next().valueProperty().get());

        Dcs dcs = assertInstanceOf(Dcs.class, aliases.stream()
            .filter(alias -> "DCS".equals(alias.getName())).findFirst().orElseThrow().getMatchIdentifier());
        assertEquals(DCSCode.N023, dcs.getDCSCode());

        TonesID tones = assertInstanceOf(TonesID.class, aliases.stream()
            .filter(alias -> "Tone Sequence".equals(alias.getName())).findFirst().orElseThrow().getMatchIdentifier());
        assertEquals(2, tones.getToneSequence().getTones().size());
        assertEquals(AmbeTone.DTMF_1, tones.getToneSequence().getTones().get(0).getAmbeTone());
        assertEquals(4, tones.getToneSequence().getTones().get(0).getDuration());
        assertEquals(AmbeTone.DTMF_2, tones.getToneSequence().getTones().get(1).getAmbeTone());
        assertEquals(6, tones.getToneSequence().getTones().get(1).getDuration());
        assertTrue(tones.getToneSequence().getTones().stream()
            .allMatch(tone -> tone.toString().equals(tone.valueProperty().get())));
        Tone firstTone = tones.getToneSequence().getTones().getFirst();
        firstTone.incrementDuration();
        assertEquals(firstTone.toString(), firstTone.valueProperty().get());
        assertEquals(tones.toString(), tones.valueProperty().get());

        ConfigurationState state = new ConfigurationDatabaseStore(database).loadConfigurationState();
        Channel nbfmChannel = state.getChannels().stream().filter(channel -> "NBFM".equals(channel.getName()))
            .findFirst().orElseThrow();
        assertEquals(List.of(DecoderType.MDC1200, DecoderType.DCS),
            nbfmChannel.getAuxDecodeConfiguration().getAuxDecoders());
        assertEquals(List.of(RecorderType.BASEBAND), nbfmChannel.getRecordConfiguration().getRecorders());
        assertEquals(List.of(EventLogType.DECODED_MESSAGE), nbfmChannel.getEventLogConfiguration().getLoggers());

        DecodeConfigNBFM nbfm = assertInstanceOf(DecodeConfigNBFM.class, nbfmChannel.getDecodeConfiguration());
        assertEquals(DecodeConfigAnalog.Bandwidth.BW_25_0, nbfm.getBandwidth());
        assertFalse(nbfm.isAudioFilter());
        assertEquals(0.15f, nbfm.getSquelchNoiseOpenThreshold());
        assertEquals(0.25f, nbfm.getSquelchNoiseCloseThreshold());
        assertEquals(3, nbfm.getSquelchHysteresisOpenThreshold());
        assertEquals(5, nbfm.getSquelchHysteresisCloseThreshold());
        assertEquals(DecodeConfigNBFM.DeemphasisMode.US_750US, nbfm.getDeemphasis());
        assertTrue(nbfm.isSquelchTailRemovalEnabled());
        assertEquals(75, nbfm.getSquelchTailRemovalMs());
        assertEquals(25, nbfm.getSquelchHeadRemovalMs());
        assertTrue(nbfm.isLowPassEnabled());
        assertEquals(3200, nbfm.getLowPassCutoff());
        assertEquals(45.0f, nbfm.getVoiceEnhanceAmount());
        assertEquals(6.0f, nbfm.getBassBoostDb());
        assertEquals(1.5f, nbfm.getOutputGain());

        IcecastHTTPConfiguration icecast = assertInstanceOf(IcecastHTTPConfiguration.class,
            state.getBroadcastConfigurations().stream()
                .filter(configuration -> configuration instanceof IcecastHTTPConfiguration)
                .findFirst().orElseThrow());
        assertEquals(BroadcastFormat.MP3, icecast.getBroadcastFormat());
        assertEquals(48, icecast.getBitRate());
        ShoutcastV1Configuration shoutcast = assertInstanceOf(ShoutcastV1Configuration.class,
            state.getBroadcastConfigurations().stream()
                .filter(configuration -> configuration instanceof ShoutcastV1Configuration)
                .findFirst().orElseThrow());
        assertEquals(BroadcastFormat.MP3, shoutcast.getBroadcastFormat());
        assertEquals(64, shoutcast.getBitRate());
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

    private static AliasID matcher(ConfigurationState state, String aliasName)
    {
        return state.getAliases().stream()
            .filter(alias -> aliasName.equals(alias.getName()))
            .findFirst()
            .orElseThrow()
            .getMatchIdentifier();
    }

    private static String legacyMatcherPlaylist(int version)
    {
        return """
            <playlist version="%d">
              <alias name="P25 Talkgroup" list="County">
                <id type="talkgroupID" talkgroup="1234"/>
              </alias>
              <alias name="P25 Radio" list="County">
                <id type="talkgroupID" talkgroup="123456"/>
              </alias>
              <alias name="FleetSync" list="Analog">
                <id type="fleetsyncID" ident="001-0001"/>
              </alias>
              <alias name="MDC-1200" list="Analog">
                <id type="mdc1200ID" ident="ABCD"/>
              </alias>
              <channel system="County" site="Site" name="Control">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="851000000"/>
                <decode_configuration type="decodeConfigP25Phase1" modulation="CQPSK"
                    ignore_data_calls="false"/>
              </channel>
              <channel system="Analog" site="Site" name="Repeater">
                <alias_list_name>Analog</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="154875000"/>
                <decode_configuration type="decodeConfigNBFM" talkgroup="1"/>
              </channel>
            </playlist>
            """.formatted(version);
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
    void omitsMalformedMatchersAndPreservesSupportedMixedListFamilies() throws Exception
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
        assertEquals(3, definitions.size());
        var aliases = aliasStore.loadAliases(definitions);
        assertEquals(2, aliases.size());
        assertEquals(List.of("Orphan", "Wrong Family"),
            aliases.stream().map(Alias::getName).sorted().toList());
        assertTrue(aliases.stream().noneMatch(alias ->
            List.of("Missing", "Invalid", "Retired").contains(alias.getName())));
        assertEquals(AliasListFamily.P25,
            definitions.stream().filter(definition -> definition.getName().equals("Old List"))
                .findFirst().orElseThrow().getFamily());
        assertEquals(AliasListFamily.P25,
            definitions.stream().filter(definition -> definition.getName().equals("County [P25]"))
                .findFirst().orElseThrow().getFamily());
        assertEquals(AliasListFamily.DMR,
            definitions.stream().filter(definition -> definition.getName().equals("County [DMR]"))
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
                <stream_talkgroup_alias type="streamAsTalkgroup" protocol="UNKNOWN" value="42"/>
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
