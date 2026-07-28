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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

class AliasListDefinitionResolverTest
{
    @Test
    void firstChannelClaimsLegacyListAndConflictingChannelIsDetached()
    {
        ConfigurationState state = new ConfigurationState();
        Channel p25 = channel("Metro P25", "Shared", new DecodeConfigP25Phase1());
        Channel dmr = channel("Metro DMR", "Shared", new DecodeConfigDMR());
        state.setChannels(List.of(p25, dmr));

        Alias p25Source = alias("Dispatch", "Shared", new Talkgroup(Protocol.APCO25, 101));
        Alias dmrSource = alias("Dispatch", "Shared", new Radio(Protocol.DMR, 202));
        state.setAliases(List.of(p25Source, dmrSource));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliasListDefinitions().size());
        assertEquals(1, state.getAliases().size());
        assertEquals("Shared", p25.getAliasListName());
        assertNull(dmr.getAliasListName());

        AliasListDefinition p25List = definition(state, p25.getAliasListName());
        assertEquals(AliasListFamily.P25, p25List.getFamily());
        assertTrue(AliasMatchRegistry.allowed(p25List).stream()
            .noneMatch(descriptor -> descriptor.matches(new Talkgroup(Protocol.DMR, 1))));

        Alias p25Alias = state.getAliases().getFirst();
        assertEquals(p25List.getName(), p25Alias.getAliasListName());
        assertEquals(101, ((Talkgroup)p25Alias.getMatchIdentifier()).getValue());
    }

    @Test
    void firstSystemOwnsLegacyListWithoutCreatingCopies()
    {
        ConfigurationState state = new ConfigurationState();
        Channel north = channel("North", "Regional", new DecodeConfigP25Phase1());
        Channel south = channel("South", "Regional", new DecodeConfigP25Phase1());
        state.setChannels(List.of(north, south));

        Alias alias = new Alias("Fire");
        alias.setId(42);
        alias.setAliasListName("Regional");
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 303));
        alias.setRecordable(true);
        alias.addBroadcastChannel("Calls");
        state.setAliases(List.of(alias));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliasListDefinitions().size());
        assertEquals(1, state.getAliases().size());
        assertEquals(42, state.getAliases().get(0).getId());
        assertTrue(state.getAliases().getFirst().isRecordable());
        assertTrue(state.getAliases().getFirst().hasBroadcastChannel("Calls"));
        assertEquals("Regional", north.getAliasListName());
        assertNull(south.getAliasListName());
    }

    @Test
    void ignoresAuxiliaryDecodersWhenResolvingListOwnership()
    {
        ConfigurationState state = new ConfigurationState();
        Channel first = channel("Metro", "Shared", new DecodeConfigP25Phase1());
        Channel second = channel("Metro", "Shared", new DecodeConfigP25Phase1());
        first.getAuxDecodeConfiguration().addAuxDecoder(DecoderType.DCS);
        second.getAuxDecodeConfiguration().addAuxDecoder(DecoderType.MDC1200);
        state.setChannels(List.of(first, second));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliasListDefinitions().size());
        assertEquals(first.getAliasListName(), second.getAliasListName());
    }

    @Test
    void omitsUnownedMatcherWithoutCreatingReviewState()
    {
        ConfigurationState state = new ConfigurationState();
        Alias alias = alias("Orphan", "Old List", new Talkgroup(Protocol.NXDN, 1));
        state.setAliases(List.of(alias));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertTrue(state.getAliasListDefinitions().isEmpty());
        assertTrue(state.getAliases().isEmpty());
    }

    @Test
    void silentlyDropsMatchersThatDoNotBelongToTheClaimedSystemFamily()
    {
        ConfigurationState state = new ConfigurationState();
        Channel p25 = channel("Metro P25", "Metro Aliases", new DecodeConfigP25Phase1());
        state.setChannels(List.of(p25));
        state.setAliases(List.of(
            alias("Dispatch", "Metro Aliases", new Talkgroup(Protocol.APCO25, 101)),
            alias("Legacy MDC", "Metro Aliases", new Talkgroup(Protocol.MDC1200, 202))));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliases().size());
        assertEquals("Dispatch", state.getAliases().getFirst().getName());
        assertEquals(101, ((Talkgroup)state.getAliases().getFirst().getMatchIdentifier()).getValue());
    }

    private static Alias alias(String name, String aliasList, AliasID matcher)
    {
        Alias alias = new Alias(name);
        alias.setAliasListName(aliasList);
        alias.setMatchIdentifier(matcher);
        return alias;
    }

    private static Channel channel(String system, String aliasList, Object decodeConfiguration)
    {
        Channel channel = new Channel(system + " Site");
        channel.setSystem(system);
        channel.setAliasListName(aliasList);

        if(decodeConfiguration instanceof DecodeConfigP25Phase1 p25)
        {
            channel.setDecodeConfiguration(p25);
        }
        else if(decodeConfiguration instanceof DecodeConfigDMR dmr)
        {
            channel.setDecodeConfiguration(dmr);
        }

        return channel;
    }

    private static AliasListDefinition definition(ConfigurationState state, String name)
    {
        return state.getAliasListDefinitions().stream()
            .filter(definition -> name.equals(definition.getName())).findFirst().orElseThrow();
    }
}
