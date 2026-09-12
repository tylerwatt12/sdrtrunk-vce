/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
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
        Map<String,Object> settings = mRegistry.require("p25-phase1").defaultSettings();
        ChannelDefinition definition = definition("p25-phase1", list.getId(), settings,
            List.of(851_012_500L, 852_112_500L), true);

        Channel channel = mCodec.toChannel(definition, list, null);
        ChannelDefinition read = mCodec.fromChannel(channel);

        assertEquals("p25-phase1", read.protocolId());
        assertEquals(List.of(851_012_500L, 852_112_500L), read.source().frequenciesHz());
        assertEquals(Boolean.FALSE, read.settings().get("learn_announced_control_channels"));
        assertEquals(list.getId(), read.aliasListId());
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
        nbfm.put("low_pass_enabled", true);
        nbfm.put("low_pass_cutoff_hz", 3000L);
        ChannelDefinition analogRead = mCodec.fromChannel(mCodec.toChannel(
            definition("nbfm", analog.getId(), nbfm, List.of(154_325_000L), false), analog, null));
        assertEquals("BW_25_0", analogRead.settings().get("bandwidth"));
        assertEquals("US_750US", analogRead.settings().get("deemphasis"));
        assertEquals(true, analogRead.settings().get("low_pass_enabled"));
        assertEquals(3000, analogRead.settings().get("low_pass_cutoff_hz"));

        AliasListDefinition dmrAliases = aliasList(2, AliasListFamily.DMR);
        Map<String,Object> dmr = new LinkedHashMap<>(mRegistry.require("dmr").defaultSettings());
        dmr.put("channel_mode", "TRUNKED");
        dmr.put("traffic_channel_pool_size", 12L);
        dmr.put("ignore_crc_checksums", true);
        ChannelDefinition dmrDefinition = new ChannelDefinition(null, "dmr", "County", "Central", "DMR", null,
            dmrAliases.getId(), new ChannelDefinition.Source(List.of(451_012_500L), null, null, 451_012_500L,
            null, null), dmr, List.of(new ChannelDefinition.FrequencyMapEntry(7, 451_012_500L, 456_012_500L)),
            List.of("TRAFFIC_CALL_EVENT"), List.of("TRAFFIC_MBE_CALL_SEQUENCE"), List.of(),
            ChannelDefinition.Observed.EMPTY);
        ChannelDefinition dmrRead = mCodec.fromChannel(mCodec.toChannel(dmrDefinition, dmrAliases, null));
        assertEquals("TRUNKED", dmrRead.settings().get("channel_mode"));
        assertEquals(dmrDefinition.frequencyMap(), dmrRead.frequencyMap());
        assertEquals(dmrDefinition.eventLogs(), dmrRead.eventLogs());
        assertEquals(dmrDefinition.recorders(), dmrRead.recorders());

        AliasListDefinition nxdnAliases = aliasList(3, AliasListFamily.NXDN);
        Map<String,Object> nxdn = new LinkedHashMap<>(mRegistry.require("nxdn").defaultSettings());
        nxdn.put("transmission_mode", "TYPE_D");
        nxdn.put("talker_alias_encoding", "BIG5");
        ChannelDefinition nxdnDefinition = new ChannelDefinition(null, "nxdn", "County", "Central", "NXDN", null,
            nxdnAliases.getId(), new ChannelDefinition.Source(List.of(155_012_500L), null, null, null, null, null),
            nxdn, List.of(new ChannelDefinition.FrequencyMapEntry(2048, 155_012_500L, 0)), List.of(), List.of(),
            List.of(), ChannelDefinition.Observed.EMPTY);
        ChannelDefinition nxdnRead = mCodec.fromChannel(mCodec.toChannel(nxdnDefinition, nxdnAliases, null));
        assertEquals("TYPE_D", nxdnRead.settings().get("transmission_mode"));
        assertEquals("BIG5", nxdnRead.settings().get("talker_alias_encoding"));
        assertEquals(nxdnDefinition.frequencyMap(), nxdnRead.frequencyMap());

        AliasListDefinition p25Aliases = aliasList(4, AliasListFamily.P25);
        Map<String,Object> p25 = new LinkedHashMap<>(mRegistry.require("p25-phase2").defaultSettings());
        p25.put("auto_detect_scramble_parameters", false);
        p25.put("scramble_wacn", 0xBEE00L);
        p25.put("scramble_system", 0x49FL);
        p25.put("scramble_nac", 0x293L);
        ChannelDefinition p25Read = mCodec.fromChannel(mCodec.toChannel(
            definition("p25-phase2", p25Aliases.getId(), p25, List.of(851_012_500L), true), p25Aliases, null));
        assertEquals(0xBEE00, p25Read.settings().get("scramble_wacn"));
        assertEquals(0x49F, p25Read.settings().get("scramble_system"));
        assertEquals(0x293, p25Read.settings().get("scramble_nac"));
        assertTrue(p25Read.source().frequenciesHz().contains(851_012_500L));
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
