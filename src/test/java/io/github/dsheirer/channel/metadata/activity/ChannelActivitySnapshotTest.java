/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelActivitySnapshotTest
{
    @Test
    void normalizesNullTopLevelValues()
    {
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot(null, null, null, null, null, null,
            false, true, null, null);

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
        assertEquals("transient-channel:" + owner.getChannelID(), table.getTableId());
        assertEquals("transient-channel:" + owner.getChannelID(), snapshot.tableId());
        assertEquals("", snapshot.configurationId());
    }

    @Test
    void carriesConfiguredSystemSiteAndChannelContext()
    {
        String configurationId = "3ba9d443-cbe2-436e-9d78-ccff9f66943f";
        Channel owner = new Channel();
        owner.setConfigurationId(configurationId);
        owner.setSystem("County System");
        owner.setSite("Downtown Simulcast");
        owner.setName("Primary Control");
        ChannelActivityTableState table = new ChannelActivityTableState("Decoded title", owner, null);
        ChannelActivitySnapshot snapshot = ChannelActivitySnapshot.from(table);

        assertEquals("County System", snapshot.systemName());
        assertEquals("Downtown Simulcast", snapshot.siteName());
        assertEquals("Primary Control", snapshot.channelName());
        assertEquals("channel:" + configurationId, table.getTableId());
        assertEquals("channel:" + configurationId, snapshot.tableId());
    }

    @Test
    void carriesDetachedCanonicalNavigationForLiveIdentifiers()
    {
        String configurationId = "3ba9d443-cbe2-436e-9d78-ccff9f66943f";
        Channel channel = new Channel("Dispatch", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(configurationId);
        channel.setRadioResolveId("86a927a5-fc21-4ee3-8bb3-6e8b943cc68f");
        channel.setAliasListName("County Sheriff");
        channel.setAliasListId(41L);
        Alias radio = new Alias("Car 12");
        radio.setId(301L);
        radio.setAliasListId(41L);
        Alias talkgroup = new Alias("Dispatch");
        talkgroup.setId(302L);
        talkgroup.setAliasListId(41L);
        ChannelActivityTableState table = new ChannelActivityTableState("Conventional", null, null);
        ChannelActivityRow row = table.getOrCreate(configurationId + ":155730000:0", channel,
            ChannelActivityRow.Role.CONVENTIONAL, 155_730_000L, null);
        row.setSource(APCO25RadioIdentifier.createFrom(1201));
        row.setSourceAliases(List.of(radio));
        row.setTarget(APCO25Talkgroup.create(4400));
        row.setTargetAliases(List.of(talkgroup));
        table.refresh(row);

        ChannelActivitySnapshot.Row snapshotRow = table.getLatestSnapshot().rows().getFirst();
        ChannelActivitySnapshot.Navigation navigation = snapshotRow.navigation();
        assertEquals("CONVENTIONAL", snapshotRow.role());
        assertEquals(configurationId, navigation.channelConfigurationId());
        assertEquals(41L, navigation.aliasListId());
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

    @Test
    void carriesCanonicalFullyQualifiedP25PatchGroupNavigation()
    {
        String configurationId = "3ba9d443-cbe2-436e-9d78-ccff9f66943f";
        Channel channel = new Channel("Dispatch", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(configurationId);
        ChannelActivityTableState table = new ChannelActivityTableState("Site", channel, null);
        ChannelActivityRow row = table.getOrCreate("traffic", channel,
            ChannelActivityRow.Role.TRAFFIC, 851_262_500L, null);
        row.setTarget(APCO25PatchGroup.create(new PatchGroup(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(4400, 0xBEE00, 0x49F, 4400))));
        table.refresh(row);

        ChannelActivitySnapshot.MatcherReference target =
            table.getLatestSnapshot().rows().getFirst().navigation().targetMatcher();
        assertEquals(new ChannelActivitySnapshot.MatcherReference("patch_group", "p25", "phase_1", 4400,
            "v1-p-bee00-49f-4400"), target);
    }

    @Test
    void assignsNewOrderWhenAControlChannelIsLostAndRegained()
    {
        ChannelActivityTableState table = new ChannelActivityTableState("Site", new Channel("Site"), null);
        ChannelActivityRow control = table.getOrCreate("control", null,
            ChannelActivityRow.Role.CURRENT_CONTROL, 851_012_500L, null);
        ChannelActivityRow traffic = table.getOrCreate("traffic", null,
            ChannelActivityRow.Role.TRAFFIC, 851_262_500L, null);

        control.setState(State.CONTROL);
        long firstControlOrder = control.getActivationOrder();
        traffic.setState(State.CALL);
        long trafficOrder = traffic.getActivationOrder();
        traffic.setState(State.ENCRYPTED);

        assertTrue(firstControlOrder > 0);
        assertTrue(trafficOrder > firstControlOrder);
        assertEquals(trafficOrder, traffic.getActivationOrder(),
            "An active status change must keep the row in its original position");

        control.setState(State.IDLE);
        assertEquals(0, control.getActivationOrder());
        control.setState(State.CONTROL);
        assertTrue(control.getActivationOrder() > trafficOrder,
            "A regained control channel must follow channels that remained active");

        table.refresh(List.of(control, traffic));
        ChannelActivitySnapshot snapshot = table.getLatestSnapshot();
        assertEquals(control.getActivationOrder(), snapshot.rows().stream()
            .filter(row -> "control".equals(row.key())).findFirst().orElseThrow().activationOrder());
        assertEquals(trafficOrder, snapshot.rows().stream()
            .filter(row -> "traffic".equals(row.key())).findFirst().orElseThrow().activationOrder());
    }
}
