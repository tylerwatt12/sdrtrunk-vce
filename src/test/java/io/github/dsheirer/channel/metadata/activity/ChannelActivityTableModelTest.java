/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import java.util.List;
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

    @Test
    void formatsConfiguredAndTalkerAliasesWithoutLosingEitherValue()
    {
        ChannelActivityRow row = new ChannelActivityRow("row-1", null, ChannelActivityRow.Role.TRAFFIC,
            851_012_500L, null);

        assertNull(row.getSourceAliasDisplay());

        row.setTalkerAlias(P25TalkerAliasIdentifier.create("Portable 12"));
        assertEquals("TA: Portable 12", row.getSourceAliasDisplay());

        row.setSourceAliases(List.of(new Alias("Engine 1")));
        assertEquals("Engine 1 · TA: Portable 12", row.getSourceAliasDisplay());

        row.setTalkerAlias(P25TalkerAliasIdentifier.create("  engine 1  "));
        assertEquals("Engine 1", row.getSourceAliasDisplay());

        row.clearCallDetails();
        assertNull(row.getTalkerAlias());
        assertNull(row.getSourceAliasDisplay());
    }

    @Test
    void publishesRawAndDisplayTalkerAliasValues()
    {
        ChannelActivityTableModel model = new ChannelActivityTableModel("Test", null, false);
        AtomicReference<ChannelActivitySnapshot> latest = new AtomicReference<>();
        model.addSnapshotListener(latest::set);
        ChannelActivityRow row = model.getOrCreate("row-1", null, ChannelActivityRow.Role.TRAFFIC,
            851_012_500L, null);
        row.setSourceAliases(List.of(new Alias("Engine 1")));
        row.setTalkerAlias(P25TalkerAliasIdentifier.create("Portable 12"));
        model.refresh(row);

        ChannelActivitySnapshot.Row snapshot = latest.get().rows().getFirst();
        assertEquals("Engine 1", snapshot.sourceAlias());
        assertEquals("Portable 12", snapshot.talkerAlias());
        assertEquals("Engine 1 · TA: Portable 12", snapshot.sourceAliasDisplay());
    }
}
