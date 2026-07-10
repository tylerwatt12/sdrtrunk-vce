/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.channel.state.State;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChannelActivityTableModelTest
{
    @Test
    void publishesCompleteSnapshotsWhileSwingTableIsHidden()
    {
        ChannelActivityTableModel model = new ChannelActivityTableModel("Conventional", null, false);
        AtomicReference<ChannelActivitySnapshot> latest = new AtomicReference<>();
        model.addSnapshotListener(latest::set);
        ChannelActivityRow row = model.getOrCreate("row-1", null, ChannelActivityRow.Role.CONVENTIONAL,
            155_250_000L, null);
        row.setState(State.CALL);
        row.setDecoder("NBFM");
        model.refresh(row);

        assertEquals("conventional", latest.get().tableId());
        assertEquals(1, latest.get().rows().size());
        assertEquals("CALL", latest.get().rows().getFirst().status());
        assertEquals(155_250_000L, latest.get().rows().getFirst().frequencyHz());
    }
}
