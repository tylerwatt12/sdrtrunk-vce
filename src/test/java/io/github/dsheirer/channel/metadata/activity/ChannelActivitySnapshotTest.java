/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.controller.channel.Channel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelActivitySnapshotTest
{
    @Test
    void normalizesNullTopLevelValues()
    {
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot(null, null, null, null, null, null, null,
            false, false, null);

        assertEquals("", snapshot.tableId());
        assertEquals("", snapshot.title());
        assertEquals("", snapshot.systemName());
        assertEquals("", snapshot.siteName());
        assertEquals("", snapshot.channelName());
        assertEquals("", snapshot.configurationId());
        assertEquals(List.of(), snapshot.rows());
    }

    @Test
    void normalizesUnnamedOwnerChannel()
    {
        Channel owner = new Channel();
        ChannelActivityTableState table = new ChannelActivityTableState(null, owner, true, null);
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
            new ChannelActivityTableState("Decoded title", owner, true, null));

        assertEquals("County System", snapshot.systemName());
        assertEquals("Downtown Simulcast", snapshot.siteName());
        assertEquals("Primary Control", snapshot.channelName());
    }
}
