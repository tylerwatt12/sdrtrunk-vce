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
package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

class AliasOneToOneTest
{
    @Test
    void copyGetsNewIdentityAndDeepCopiesMatcherAndBehavior()
    {
        Alias original = new Alias("Dispatch");
        original.setId(41);
        original.setAliasListId(12);
        original.setAliasListName("Metro");
        original.setDescription("Primary dispatch");
        original.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        original.setRecordable(true);
        original.setListen(false);
        original.addBroadcastChannel("Stream A");
        original.setStreamTalkgroupAlias(new StreamAsTalkgroup(900));

        Alias copy = AliasFactory.copyOf(original);

        assertEquals(Alias.UNASSIGNED_ID, copy.getId());
        assertEquals(12, copy.getAliasListId());
        assertEquals("Primary dispatch", copy.getDescription());
        assertInstanceOf(Talkgroup.class, copy.getMatchIdentifier());
        assertNotSame(original.getMatchIdentifier(), copy.getMatchIdentifier());
        assertEquals(100, ((Talkgroup)copy.getMatchIdentifier()).getValue());
        assertTrue(copy.isRecordable());
        assertFalse(copy.isListen());
        assertTrue(copy.hasBroadcastChannel("Stream A"));
        assertEquals(900, copy.getStreamTalkgroupAlias().getValue());
    }

    @Test
    void activeModelRejectsMissingMatcher()
    {
        AliasListDefinition definition = new AliasListDefinition("Metro", AliasListFamily.P25);
        Alias alias = new Alias("Missing");
        alias.setAliasListDefinition(definition);
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));

        IllegalArgumentException exception =
            assertThrows(IllegalArgumentException.class, () -> model.addAlias(alias));

        assertTrue(exception.getMessage().contains("not supported"));
        assertTrue(model.getAliases().isEmpty());
        assertEquals(1, model.aliasListDefinitions().size());
    }

    @Test
    void selectorReplacementUpdatesPublishedLookup()
    {
        Alias alias = new Alias("Dispatch");
        Talkgroup original = new Talkgroup(Protocol.APCO25, 100);
        alias.setMatchIdentifier(original);
        AliasList aliasList = new AliasList(
            new AliasListDefinition("Metro", AliasListFamily.P25));
        aliasList.addAlias(alias);

        assertSame(alias, aliasList.getAliases(APCO25Talkgroup.create(100)).getFirst());

        Radio replacement = new Radio(Protocol.APCO25, 200);
        alias.setMatchIdentifier(replacement);
        assertTrue(aliasList.getAliases(APCO25Talkgroup.create(100)).isEmpty());
        assertSame(alias, aliasList.getAliases(APCO25RadioIdentifier.createFrom(200)).getFirst());

        original.setValue(101);
        assertTrue(aliasList.getAliases(APCO25Talkgroup.create(101)).isEmpty(),
            "The old matcher listener must be detached");
    }

    @Test
    void runtimeAliasExposesOnlyOneMatcher()
    {
        Alias alias = new Alias("Dispatch");
        Talkgroup matcher = new Talkgroup(Protocol.APCO25, 100);
        alias.setMatchIdentifier(matcher);

        assertSame(matcher, alias.getMatchIdentifier());
        alias.setMatchIdentifier(new Radio(Protocol.APCO25, 200));
        assertInstanceOf(Radio.class, alias.getMatchIdentifier());
    }

    @Test
    void definitionBackedListsRejectCrossFamilyMatchers()
    {
        AliasListDefinition p25 = new AliasListDefinition("P25", AliasListFamily.P25);
        Alias p25Alias = new Alias("Dispatch");
        p25Alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        AliasList p25List = new AliasList(p25);
        p25List.addAlias(p25Alias);
        assertSame(p25Alias, p25List.getAliases(APCO25Talkgroup.create(100)).getFirst());

        Alias dmrAlias = new Alias("Wrong Family");
        dmrAlias.setMatchIdentifier(new Talkgroup(Protocol.DMR, 100));
        assertThrows(IllegalArgumentException.class, () -> p25List.addAlias(dmrAlias));
    }

    @Test
    void runtimeChannelLookupAcceptsSameFamilyListAcrossSystems()
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        Alias alias = new Alias("Dispatch");
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));
        model.addAlias(alias);

        Channel correct = channel("System A", "County");
        Channel wrongSystem = channel("System B", "County");

        assertSame(alias, model.getAliasListForChannel(correct)
            .getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertSame(alias, model.getAliasListForChannel(wrongSystem)
            .getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertTrue(model.isAliasListCompatible(wrongSystem));
    }

    private static Channel channel(String system, String aliasList)
    {
        Channel channel = new Channel("Control");
        channel.setSystem(system);
        channel.setAliasListName(aliasList);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        return channel;
    }
}
