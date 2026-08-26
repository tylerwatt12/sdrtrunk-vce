/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelActivitySnapshotTest
{
    @Test
    void normalizesNullTopLevelValues()
    {
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot(null, null, null, null, null, null, null,
            false, null, null);

        assertEquals("", snapshot.tableId());
        assertEquals("", snapshot.title());
        assertEquals("", snapshot.systemName());
        assertEquals("", snapshot.siteName());
        assertEquals("", snapshot.channelName());
        assertEquals("", snapshot.configurationId());
        assertEquals(List.of(), snapshot.identifiers());
        assertEquals(List.of(), snapshot.rows());
    }

    @Test
    void normalizesUnnamedOwnerChannel()
    {
        Channel owner = new Channel();
        ChannelActivityTableState table = new ChannelActivityTableState(null, owner, null);
        ChannelActivitySnapshot snapshot = ChannelActivitySnapshot.from(table);

        assertEquals("", snapshot.title());
        assertEquals("", snapshot.channelName());
    }

    @Test
    void carriesConfiguredSystemSiteAndChannelContext()
    {
        Channel owner = new Channel();
        owner.setSystem("County System");
        owner.setSite("Downtown Simulcast");
        owner.setName("Primary Control");
        ChannelActivitySnapshot snapshot = ChannelActivitySnapshot.from(
            new ChannelActivityTableState("Decoded title", owner, null));

        assertEquals("County System", snapshot.systemName());
        assertEquals("Downtown Simulcast", snapshot.siteName());
        assertEquals("Primary Control", snapshot.channelName());
    }

    @Test
    void carriesDetachedCanonicalNavigationForLiveIdentifiers()
    {
        Channel channel = new Channel("Dispatch", Channel.ChannelType.STANDARD);
        channel.setConfigurationId("configuration-17");
        channel.setRadresGuid("86a927a5-fc21-4ee3-8bb3-6e8b943cc68f");
        channel.setAliasListName("County Sheriff");
        Alias radio = new Alias("Car 12");
        radio.setId(301L);
        radio.setAliasListId(41L);
        Alias talkgroup = new Alias("Dispatch");
        talkgroup.setId(302L);
        talkgroup.setAliasListId(41L);
        ChannelActivityTableState table = new ChannelActivityTableState("Conventional", null, null);
        ChannelActivityRow row = table.getOrCreate("configuration-17:155730000:0", channel,
            ChannelActivityRow.Role.CONVENTIONAL, 155_730_000L, null);
        row.setSource(APCO25RadioIdentifier.createFrom(1201));
        row.setSourceAliases(List.of(radio));
        row.setTarget(APCO25Talkgroup.create(4400));
        row.setTargetAliases(List.of(talkgroup));
        table.refresh(row);

        ChannelActivitySnapshot.Row snapshotRow = table.getLatestSnapshot().rows().getFirst();
        ChannelActivitySnapshot.Navigation navigation = snapshotRow.navigation();
        assertEquals("CONVENTIONAL", snapshotRow.role());
        assertEquals("GUID:86a927a5-fc21-4ee3-8bb3-6e8b943cc68f", navigation.contextKey());
        assertEquals("County Sheriff", navigation.aliasListName());
        assertEquals("p25", navigation.protocol());
        assertEquals(new ChannelActivitySnapshot.AliasReference(301L, 41L, "Car 12"),
            navigation.sourceAliases().getFirst());
        assertEquals(new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 1201),
            navigation.sourceMatcher());
        assertEquals(new ChannelActivitySnapshot.AliasReference(302L, 41L, "Dispatch"),
            navigation.targetAliases().getFirst());
        assertEquals(new ChannelActivitySnapshot.MatcherReference("talkgroup", "p25", "phase_1", 4400),
            navigation.targetMatcher());
    }
}
