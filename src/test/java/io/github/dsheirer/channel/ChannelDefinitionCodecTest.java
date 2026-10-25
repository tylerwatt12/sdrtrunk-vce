/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.source.SourceType;
import io.github.dsheirer.source.config.SourceConfigRemote;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelDefinitionCodecTest
{
    private final ChannelProtocolRegistry mRegistry = new ChannelProtocolRegistry();
    private final ChannelDefinitionCodec mCodec = new ChannelDefinitionCodec(mRegistry);

    @Test
    void everyManifestProfileBuildsAnActiveRuntimeChannelFromItsDefaults()
    {
        long id = 1;
        for(DecoderType decoderType: DecoderType.PRIMARY_DECODERS)
        {
            ChannelProtocolRegistry.Profile profile = mRegistry.require(decoderType);
            AliasListDefinition list = aliasList(id++, profile.aliasFamily());
            boolean multiple = profile.sourceMode() == ChannelProtocolRegistry.SourceMode.MULTIPLE;
            Channel channel = mCodec.toChannel(definition(profile.id(), list.getId(), profile.defaultSettings(),
                List.of(851_012_500L), multiple), list, null);

            assertEquals(decoderType, channel.getDecodeConfiguration().getDecoderType());
            assertEquals(profile.id(), mCodec.fromChannel(channel).protocolId());
        }
    }

    @Test
    void p25PhaseOneRoundTripsThroughUiNeutralDefinition()
    {
        AliasListDefinition list = aliasList(19, AliasListFamily.P25);
        Map<String,Object> settings = new LinkedHashMap<>(mRegistry.require("p25-phase1").defaultSettings());
        settings.put("ignore_encrypted_calls", true);
        ChannelDefinition definition = definition("p25-phase1", list.getId(), settings,
            List.of(851_012_500L, 852_112_500L), true);

        Channel channel = mCodec.toChannel(definition, list, null);
        ChannelDefinition read = mCodec.fromChannel(channel);

        assertEquals("p25-phase1", read.protocolId());
        assertEquals(List.of(851_012_500L, 852_112_500L), read.source().frequenciesHz());
        assertEquals(Boolean.TRUE, read.settings().get("learn_announced_control_channels"));
        assertEquals(Boolean.TRUE, read.settings().get("ignore_encrypted_calls"));
        assertEquals(list.getId(), read.aliasListId());
    }

    @Test
    void remoteP25SourceRoundTripsStableRoutingIdentity()
    {
        AliasListDefinition list = aliasList(20, AliasListFamily.P25);
        String senderId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        String feedId = "11111111-2222-4333-8444-555555555555";
        ChannelDefinition submitted = new ChannelDefinition(null, "p25-phase1", "County", "Remote", "Control",
            null, list.getId(), new ChannelDefinition.Source(List.of(851_012_500L), null, null, null, null, null,
                SourceType.REMOTE.name(), senderId.toUpperCase(), feedId),
            mRegistry.require("p25-phase1").defaultSettings(), List.of(), List.of(), List.of(), List.of(),
            ChannelDefinition.Observed.EMPTY);

        Channel channel = mCodec.toChannel(submitted, list, null);
        SourceConfigRemote source = assertInstanceOf(SourceConfigRemote.class, channel.getSourceConfiguration());
        assertEquals(senderId, source.getSenderId());
        assertEquals(feedId, source.getFeedId());
        assertEquals(List.of(851_012_500L), channel.getFrequencyList());
        assertTrue(channel.getTunerChannels().isEmpty());

        ChannelDefinition restored = mCodec.fromChannel(channel);
        assertEquals(SourceType.REMOTE.name(), restored.source().sourceType());
        assertEquals(senderId, restored.source().senderId());
        assertEquals(feedId, restored.source().feedId());
        assertEquals(List.of(851_012_500L), restored.source().frequenciesHz());
    }

    @Test
    void remoteSourceIsLimitedToP25TrunkedProfilesAndHasNoTunerOptions()
    {
        String senderId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        String feedId = "11111111-2222-4333-8444-555555555555";
        AliasListDefinition analog = aliasList(21, AliasListFamily.NBFM);
        ChannelDefinition.Source remote = new ChannelDefinition.Source(List.of(155_000_000L), null, null, null,
            null, null, "REMOTE", senderId, feedId);
        ChannelDefinition analogDefinition = new ChannelDefinition(null, "nbfm", null, null, "Remote", null,
            analog.getId(), remote, mRegistry.require("nbfm").defaultSettings(), List.of(), List.of(), List.of(),
            List.of(), ChannelDefinition.Observed.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(analogDefinition, analog, null));

        AliasListDefinition p25 = aliasList(22, AliasListFamily.P25);
        ChannelDefinition.Source remoteWithTuner = new ChannelDefinition.Source(List.of(851_012_500L), null, null,
            null, "Airspy", null, "REMOTE", senderId, feedId);
        ChannelDefinition p25Definition = new ChannelDefinition(null, "p25-phase2", null, null, "Remote", null,
            p25.getId(), remoteWithTuner, mRegistry.require("p25-phase2").defaultSettings(), List.of(), List.of(),
            List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(p25Definition, p25, null));
    }

    @Test
    void cloneGetsNewRuntimeIdentityAndNoAutoStartPosition()
    {
        AliasListDefinition list = aliasList(7, AliasListFamily.NBFM);
        Channel source = mCodec.toChannel(definition("nbfm", list.getId(),
            mRegistry.require("nbfm").defaultSettings(), List.of(154_325_000L), false), list, null);
        source.setAutoStart(true);
        source.setAutoStartOrder(4);
        String sourceId = source.getConfigurationId();

        Channel clone = mCodec.cloneChannel(source, list);

        assertNotEquals(sourceId, clone.getConfigurationId());
        assertFalse(clone.isAutoStart());
        assertNull(clone.getAutoStartOrder());
    }

    @Test
    void aliasFamiliesAndProtocolChangesAreEnforced()
    {
        AliasListDefinition p25 = aliasList(1, AliasListFamily.P25);
        AliasListDefinition analog = aliasList(2, AliasListFamily.NBFM);
        ChannelDefinition p25Definition = definition("p25-conventional", p25.getId(),
            mRegistry.require("p25-conventional").defaultSettings(), List.of(155_000_000L), false);
        Channel existing = mCodec.toChannel(p25Definition, p25, null);

        assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(p25Definition, analog, null));
        ChannelDefinition nbfm = definition("nbfm", analog.getId(), mRegistry.require("nbfm").defaultSettings(),
            List.of(155_000_000L), false);
        assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(nbfm, analog, existing));
    }

    @Test
    void profileSpecificChoicesAndDependentDataRoundTripWithoutJavaEditors()
    {
        AliasListDefinition analog = aliasList(1, AliasListFamily.NBFM);
        Map<String,Object> nbfm = new LinkedHashMap<>(mRegistry.require("nbfm").defaultSettings());
        nbfm.put("bandwidth", "BW_25_0");
        nbfm.put("deemphasis", "US_750US");
        nbfm.put("ctcss_frequency_hz", 100.0);
        nbfm.put("low_pass_enabled", true);
        nbfm.put("low_pass_cutoff_hz", 3000L);
        ChannelDefinition analogRead = mCodec.fromChannel(mCodec.toChannel(
            definition("nbfm", analog.getId(), nbfm, List.of(154_325_000L), false), analog, null));
        assertEquals("BW_25_0", analogRead.settings().get("bandwidth"));
        assertEquals("US_750US", analogRead.settings().get("deemphasis"));
        assertEquals(100.0, analogRead.settings().get("ctcss_frequency_hz"));
        assertEquals(true, analogRead.settings().get("low_pass_enabled"));
        assertEquals(3000, analogRead.settings().get("low_pass_cutoff_hz"));

        AliasListDefinition dmrAliases = aliasList(2, AliasListFamily.DMR);
        Map<String,Object> dmr = new LinkedHashMap<>(mRegistry.require("dmr").defaultSettings());
        dmr.put("channel_mode", "TRUNKED");
        dmr.put("traffic_channel_pool_size", 12L);
        dmr.put("ignore_encrypted_calls", true);
        dmr.put("ignore_crc_checksums", true);
        ChannelDefinition dmrDefinition = new ChannelDefinition(null, "dmr", "County", "Central", "DMR", null,
            dmrAliases.getId(), new ChannelDefinition.Source(List.of(451_012_500L), null, null, 451_012_500L,
            null, null), dmr, List.of(new ChannelDefinition.FrequencyMapEntry(7, 451_012_500L, 456_012_500L)),
            List.of("TRAFFIC_CALL_EVENT"), List.of("TRAFFIC_MBE_CALL_SEQUENCE"), List.of(),
            ChannelDefinition.Observed.EMPTY);
        ChannelDefinition dmrRead = mCodec.fromChannel(mCodec.toChannel(dmrDefinition, dmrAliases, null));
        assertEquals("TRUNKED", dmrRead.settings().get("channel_mode"));
        assertEquals(true, dmrRead.settings().get("ignore_encrypted_calls"));
        assertEquals(dmrDefinition.frequencyMap(), dmrRead.frequencyMap());
        assertEquals(dmrDefinition.eventLogs(), dmrRead.eventLogs());
        assertEquals(dmrDefinition.recorders(), dmrRead.recorders());

        AliasListDefinition nxdnAliases = aliasList(3, AliasListFamily.NXDN);
        Map<String,Object> nxdn = new LinkedHashMap<>(mRegistry.require("nxdn").defaultSettings());
        nxdn.put("transmission_mode", "TYPE_D");
        nxdn.put("talker_alias_encoding", "BIG5");
        nxdn.put("ignore_encrypted_calls", true);
        ChannelDefinition nxdnDefinition = new ChannelDefinition(null, "nxdn", "County", "Central", "NXDN", null,
            nxdnAliases.getId(), new ChannelDefinition.Source(List.of(155_012_500L), null, null, null, null, null),
            nxdn, List.of(new ChannelDefinition.FrequencyMapEntry(2048, 155_012_500L, 0)), List.of(), List.of(),
            List.of(), ChannelDefinition.Observed.EMPTY);
        ChannelDefinition nxdnRead = mCodec.fromChannel(mCodec.toChannel(nxdnDefinition, nxdnAliases, null));
        assertEquals("TYPE_D", nxdnRead.settings().get("transmission_mode"));
        assertEquals("BIG5", nxdnRead.settings().get("talker_alias_encoding"));
        assertEquals(true, nxdnRead.settings().get("ignore_encrypted_calls"));
        assertEquals(nxdnDefinition.frequencyMap(), nxdnRead.frequencyMap());

        AliasListDefinition p25Aliases = aliasList(4, AliasListFamily.P25);
        Map<String,Object> p25 = new LinkedHashMap<>(mRegistry.require("p25-phase2").defaultSettings());
        p25.put("ignore_encrypted_calls", true);
        p25.put("auto_detect_scramble_parameters", false);
        p25.put("scramble_wacn", 0xBEE00L);
        p25.put("scramble_system", 0x49FL);
        p25.put("scramble_nac", 0x293L);
        ChannelDefinition p25Read = mCodec.fromChannel(mCodec.toChannel(
            definition("p25-phase2", p25Aliases.getId(), p25, List.of(851_012_500L), true), p25Aliases, null));
        assertEquals(0xBEE00, p25Read.settings().get("scramble_wacn"));
        assertEquals(0x49F, p25Read.settings().get("scramble_system"));
        assertEquals(0x293, p25Read.settings().get("scramble_nac"));
        assertEquals(true, p25Read.settings().get("ignore_encrypted_calls"));
        assertTrue(p25Read.source().frequenciesHz().contains(851_012_500L));
    }

    @Test
    void analogCtcssDefaultsOffAndRejectsNonstandardChoices()
    {
        AliasListDefinition aliases = aliasList(1, AliasListFamily.NBFM);
        for(String protocol: List.of("am", "nbfm"))
        {
            Map<String,Object> settings = new LinkedHashMap<>(mRegistry.require(protocol).defaultSettings());
            assertEquals(0L, settings.get("ctcss_frequency_hz"));
            settings.remove("ctcss_frequency_hz");
            Channel channel = mCodec.toChannel(definition(protocol, aliases.getId(), settings,
                List.of(154_325_000L), false), aliases, null);
            assertEquals(0.0, mCodec.fromChannel(channel).settings().get("ctcss_frequency_hz"));
            settings.put("ctcss_frequency_hz", 99.0);
            assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(definition(protocol, aliases.getId(),
                settings, List.of(154_325_000L), false), aliases, null));
            settings.put("ctcss_frequency_hz", 123.0);
            Channel selected = mCodec.toChannel(definition(protocol, aliases.getId(), settings,
                List.of(154_325_000L), false), aliases, null);
            assertEquals(123.0, mCodec.fromChannel(selected).settings().get("ctcss_frequency_hz"));
        }
    }

    @Test
    void conventionalDigitalWritesDiscardHiddenSettingsMalformedMapsAndTrafficOutputs()
    {
        for(String protocol: List.of("dmr", "nxdn"))
        {
            ChannelProtocolRegistry.Profile profile = mRegistry.require(protocol);
            AliasListDefinition aliases = aliasList(10, profile.aliasFamily());
            Map<String,Object> settings = new LinkedHashMap<>(profile.defaultSettings());
            settings.put("channel_mode", "CONVENTIONAL");
            settings.put("traffic_channel_pool_size", "stale");
            settings.put("ignore_data_calls", null);
            settings.put("ignore_encrypted_calls", "stale");
            if(protocol.equals("dmr")) settings.put("use_compressed_talkgroups", true);
            ChannelDefinition submitted = digitalDefinition(protocol, aliases.getId(), settings,
                List.of(new ChannelDefinition.FrequencyMapEntry(-1, -20, -30)));

            Channel channel = mCodec.toChannel(submitted, aliases, null);
            ChannelDefinition read = mCodec.fromChannel(channel);
            assertTrue(read.frequencyMap().isEmpty(), protocol);
            assertEquals(List.of("CALL_EVENT"), read.eventLogs(), protocol);
            assertEquals(List.of("BASEBAND"), read.recorders(), protocol);
            assertEquals(false, read.settings().get("ignore_encrypted_calls"), protocol);
            if(channel.getDecodeConfiguration() instanceof DecodeConfigDMR dmr)
            {
                assertTrue(dmr.getTimeslotMap().isEmpty());
                assertFalse(dmr.isUseCompressedTalkgroups());
            }
            else assertTrue(((DecodeConfigNXDN)channel.getDecodeConfiguration()).getChannelMap().isEmpty());

            settings.put("channel_mode", "TRUNKED");
            assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(
                digitalDefinition(protocol, aliases.getId(), settings, submitted.frequencyMap()), aliases, null));
            settings.put("traffic_channel_pool_size", 20);
            settings.put("ignore_data_calls", false);
            settings.put("ignore_encrypted_calls", false);
            assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(
                digitalDefinition(protocol, aliases.getId(), settings, submitted.frequencyMap()), aliases, null));
        }
    }

    @Test
    void classificationChangesRequireNewChannelsAndConventionalProjectionCannotReviveTrunkedOptions()
    {
        for(String protocol: List.of("dmr", "nxdn"))
        {
            ChannelProtocolRegistry.Profile profile = mRegistry.require(protocol);
            AliasListDefinition aliases = aliasList(11, profile.aliasFamily());
            Map<String,Object> settings = new LinkedHashMap<>(profile.defaultSettings());
            settings.put("channel_mode", "TRUNKED");
            settings.put("traffic_channel_pool_size", 7);
            settings.put("ignore_encrypted_calls", true);
            if(protocol.equals("dmr")) settings.put("use_compressed_talkgroups", true);
            Channel trunked = mCodec.toChannel(digitalDefinition(protocol, aliases.getId(), settings,
                List.of(new ChannelDefinition.FrequencyMapEntry(7, 451_012_500L, 0))), aliases, null);
            assertEquals(2, mCodec.fromChannel(trunked).eventLogs().size());
            settings.put("channel_mode", "CONVENTIONAL");
            ChannelDefinition conventionalDefinition = digitalDefinition(protocol, aliases.getId(), settings,
                List.of(new ChannelDefinition.FrequencyMapEntry(7, 451_012_500L, 0)));
            assertThrows(IllegalArgumentException.class,
                () -> mCodec.toChannel(conventionalDefinition, aliases, trunked));
            assertEquals("TRUNKED", mCodec.fromChannel(trunked).settings().get("channel_mode"));
            Channel conventional = mCodec.toChannel(conventionalDefinition, aliases, null);
            assertNotEquals(trunked.getConfigurationId(), conventional.getConfigurationId());

            //Simulate older persisted conventional state containing dormant trunked settings and invalid maps.
            if(conventional.getDecodeConfiguration() instanceof DecodeConfigDMR dmr)
            {
                dmr.setUseCompressedTalkgroups(true);
                dmr.setIgnoreEncryptedCalls(true);
                TimeslotFrequency invalid = new TimeslotFrequency();
                invalid.setNumber(-1);
                dmr.setTimeslotMap(List.of(invalid));
            }
            else
            {
                DecodeConfigNXDN nxdn = (DecodeConfigNXDN)conventional.getDecodeConfiguration();
                nxdn.setIgnoreEncryptedCalls(true);
                nxdn.setChannelMap(List.of(new ChannelFrequency(-1, -20, -30)));
            }
            conventional.getEventLogConfiguration().addLogger(EventLogType.TRAFFIC_CALL_EVENT);
            conventional.getRecordConfiguration().addRecorder(RecorderType.TRAFFIC_BASEBAND);
            ChannelDefinition projected = mCodec.fromChannel(conventional);
            assertTrue(projected.frequencyMap().isEmpty(), protocol);
            assertEquals(List.of("CALL_EVENT"), projected.eventLogs(), protocol);
            assertEquals(List.of("BASEBAND"), projected.recorders(), protocol);
            assertEquals(false, projected.settings().get("ignore_encrypted_calls"), protocol);
            if(protocol.equals("dmr")) assertEquals(false, projected.settings().get("use_compressed_talkgroups"));

            Map<String,Object> restoredSettings = new LinkedHashMap<>(projected.settings());
            restoredSettings.put("channel_mode", "TRUNKED");
            ChannelDefinition restored = new ChannelDefinition(null, protocol,
                projected.system(), projected.site(), projected.name(), projected.radioResolveId(), aliases.getId(),
                projected.source(), restoredSettings, projected.frequencyMap(), projected.eventLogs(),
                projected.recorders(), projected.auxiliaryDecoders(), projected.observed());
            assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(restored, aliases, conventional));
            assertEquals("CONVENTIONAL", mCodec.fromChannel(conventional).settings().get("channel_mode"));
            ChannelDefinition roundTrip = mCodec.fromChannel(mCodec.toChannel(restored, aliases, null));
            assertTrue(roundTrip.frequencyMap().isEmpty(), protocol);
            assertEquals(false, roundTrip.settings().get("ignore_encrypted_calls"), protocol);
            assertEquals(List.of("CALL_EVENT"), roundTrip.eventLogs(), protocol);
        }
    }

    @Test
    void nxdnAddressModeChangesRequireANewChannelWithoutMutatingTheSavedDefinition()
    {
        AliasListDefinition aliases = aliasList(12, AliasListFamily.NXDN);
        Map<String,Object> settings = new LinkedHashMap<>(mRegistry.require("nxdn").defaultSettings());
        settings.put("channel_mode", "TRUNKED");
        settings.put("transmission_mode", "M4800");
        Channel saved = mCodec.toChannel(digitalDefinition("nxdn", aliases.getId(), settings, List.of()), aliases, null);
        settings.put("transmission_mode", "TYPE_D");
        ChannelDefinition changed = digitalDefinition("nxdn", aliases.getId(), settings, List.of());
        assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(changed, aliases, saved));
        assertEquals("M4800", mCodec.fromChannel(saved).settings().get("transmission_mode"));
        Channel fresh = mCodec.toChannel(changed, aliases, null);
        assertNotEquals(saved.getConfigurationId(), fresh.getConfigurationId());
        assertEquals("TYPE_D", mCodec.fromChannel(fresh).settings().get("transmission_mode"));
    }

    @Test
    void disabledAnalogTuningSurvivesProjectionAndAnEditorOmittingHiddenFields()
    {
        for(String protocol: List.of("am", "nbfm"))
        {
            AliasListDefinition aliases = aliasList(12, AliasListFamily.NBFM);
            Map<String,Object> settings = new LinkedHashMap<>(mRegistry.require(protocol).defaultSettings());
            settings.put("low_pass_cutoff_hz", 2800);
            settings.put("squelch_tail_ms", 80);
            settings.put("squelch_head_ms", 25);
            Channel existing = mCodec.toChannel(definition(protocol, aliases.getId(), settings,
                List.of(154_325_000L), false), aliases, null);
            for(String hidden: List.of("low_pass_cutoff_hz", "squelch_tail_ms", "squelch_head_ms"))
                settings.remove(hidden);
            Channel saved = mCodec.toChannel(definition(protocol, aliases.getId(), settings,
                List.of(154_325_000L), false), aliases, existing);
            ChannelDefinition read = mCodec.fromChannel(saved);
            assertEquals(2800, read.settings().get("low_pass_cutoff_hz"), protocol);
            assertEquals(80, read.settings().get("squelch_tail_ms"), protocol);
            assertEquals(25, read.settings().get("squelch_head_ms"), protocol);
        }
    }

    @Test
    void legacyOutOfRangeActiveValuesRemainReadableButMustBeCorrectedBeforeSaving()
    {
        for(String protocol: List.of("dmr", "nxdn"))
        {
            ChannelProtocolRegistry.Profile profile = mRegistry.require(protocol);
            AliasListDefinition aliases = aliasList(13, profile.aliasFamily());
            Map<String,Object> settings = new LinkedHashMap<>(profile.defaultSettings());
            settings.put("channel_mode", "TRUNKED");
            Channel channel = mCodec.toChannel(digitalDefinition(protocol, aliases.getId(), settings, List.of()),
                aliases, null);
            if(channel.getDecodeConfiguration() instanceof DecodeConfigDMR dmr) dmr.setTrafficChannelPoolSize(99);
            else ((DecodeConfigNXDN)channel.getDecodeConfiguration()).setTrafficChannelPoolSize(99);
            ChannelDefinition projected = mCodec.fromChannel(channel);
            assertEquals(99, projected.settings().get("traffic_channel_pool_size"), protocol);
            assertThrows(IllegalArgumentException.class, () -> mCodec.toChannel(projected, aliases, channel));
        }
    }

    private static ChannelDefinition digitalDefinition(String protocol, long aliasListId,
        Map<String,Object> settings, List<ChannelDefinition.FrequencyMapEntry> map)
    {
        return new ChannelDefinition(null, protocol, "County", "Central", "Dispatch", null, aliasListId,
            new ChannelDefinition.Source(List.of(451_012_500L), null, null, null, null, null), settings, map,
            List.of("CALL_EVENT", "TRAFFIC_CALL_EVENT"), List.of("BASEBAND", "TRAFFIC_BASEBAND"), List.of(),
            ChannelDefinition.Observed.EMPTY);
    }

    private static ChannelDefinition definition(String protocol, long aliasListId, Map<String,Object> settings,
                                                List<Long> frequencies, boolean multiple)
    {
        return new ChannelDefinition(null, protocol, "County", "Central", "Dispatch", null, aliasListId,
            new ChannelDefinition.Source(frequencies, null, null, multiple ? frequencies.getFirst() : null,
                null, null), settings, List.of(), List.of(), List.of(), List.of(),
            ChannelDefinition.Observed.EMPTY);
    }

    private static AliasListDefinition aliasList(long id, AliasListFamily family)
    {
        AliasListDefinition definition = new AliasListDefinition("List", family);
        definition.setId(id);
        return definition;
    }
}
