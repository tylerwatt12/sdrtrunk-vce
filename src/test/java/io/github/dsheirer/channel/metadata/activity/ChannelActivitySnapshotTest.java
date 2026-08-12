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
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot(null, null, null, null, null,
            false, false, null);

        assertEquals("", snapshot.tableId());
        assertEquals("", snapshot.title());
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
}
