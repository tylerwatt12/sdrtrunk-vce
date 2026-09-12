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

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import java.util.List;
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
        assertEquals(Boolean.TRUE, read.settings().get("learn_announced_control_channels"));
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
