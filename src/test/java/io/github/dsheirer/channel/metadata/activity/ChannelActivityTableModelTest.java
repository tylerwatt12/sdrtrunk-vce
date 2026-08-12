/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChannelActivityTableModelTest
{
    @Test
    void publishesCompleteSnapshotsWhileSwingTableIsHidden() throws Exception
    {
        AtomicReference<ChannelActivitySnapshot> latest = new AtomicReference<>();
        ChannelActivityTableState state = new ChannelActivityTableState("Conventional", null, false, latest::set);
        ChannelActivityTableModel model = new ChannelActivityTableModel(state);
        Channel channel = new Channel("County Fire");
        ChannelActivityRow row = state.getOrCreate("row-1", channel, ChannelActivityRow.Role.CONVENTIONAL,
            155_250_000L, null);
        row.setState(State.CALL);
        row.setDecoder("NBFM");
        row.setCallsign("WPFF205");
        state.refresh(row);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertEquals("conventional", latest.get().tableId());
        assertEquals(1, latest.get().rows().size());
        assertEquals("CALL", latest.get().rows().getFirst().status());
        assertEquals("County Fire", latest.get().rows().getFirst().channelName());
        assertEquals(155_250_000L, latest.get().rows().getFirst().frequencyHz());
        assertEquals("WPFF205", latest.get().rows().getFirst().callsign());
        assertEquals("WPFF205", model.getValueAt(0, ChannelActivityTableModel.COLUMN_CALLSIGN));
        assertEquals("Channel", model.getColumnName(ChannelActivityTableModel.COLUMN_LCN));
        assertEquals("County Fire", model.getValueAt(0, ChannelActivityTableModel.COLUMN_LCN));
    }

    @Test
    void preservesLcnColumnForTrunkedTables() throws Exception
    {
        Channel owner = new Channel("County System");
        ChannelActivityTableState state = new ChannelActivityTableState("County System", owner, true, null);
        ChannelActivityTableModel model = new ChannelActivityTableModel(state);
        ChannelActivityRow row = state.getOrCreate("row-1", owner, ChannelActivityRow.Role.CURRENT_CONTROL,
            851_012_500L, null);
        row.setLcn("0-101");
        state.refresh(row);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertEquals("LCN", model.getColumnName(ChannelActivityTableModel.COLUMN_LCN));
        assertEquals("0-101", model.getValueAt(0, ChannelActivityTableModel.COLUMN_LCN));
        assertNull(ChannelActivitySnapshot.from(state).rows().getFirst().channelName());
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
        AtomicReference<ChannelActivitySnapshot> latest = new AtomicReference<>();
        ChannelActivityTableState state = new ChannelActivityTableState("Test", null, false, latest::set);
        ChannelActivityRow row = state.getOrCreate("row-1", null, ChannelActivityRow.Role.TRAFFIC,
            851_012_500L, null);
        row.setSourceAliases(List.of(new Alias("Engine 1")));
        row.setTalkerAlias(P25TalkerAliasIdentifier.create("Portable 12"));
        state.refresh(row);

        ChannelActivitySnapshot.Row snapshot = latest.get().rows().getFirst();
        assertEquals("Engine 1", snapshot.sourceAlias());
        assertEquals("Portable 12", snapshot.talkerAlias());
        assertEquals("Engine 1 · TA: Portable 12", snapshot.sourceAliasDisplay());
    }

    @Test
    void abbreviatesTagsAndTreatsEncryptedAudioAsVoice()
    {
        ChannelActivityRow row = new ChannelActivityRow("row-1", null, ChannelActivityRow.Role.CONVENTIONAL,
            155_250_000L, null);
        row.addTag(ChannelTag.fromService(State.ENCRYPTED));

        assertEquals("CONV + VC", row.getTagsDisplay());
        assertEquals("Conventional channel + Observed voice traffic", row.getTagsDescription());

        row.addTag(ChannelTag.DATA_ANNOUNCED);
        assertEquals("CONV + VC + DAT-A", row.getTagsDisplay());
        row.addTag(ChannelTag.DATA);
        assertEquals("CONV + VC + DAT", row.getTagsDisplay());
    }

    @Test
    void treatsSharedTrafficAndControlRowAsSiteSelection()
    {
        ChannelActivityRow row = new ChannelActivityRow("row-1", null, ChannelActivityRow.Role.TRAFFIC,
            851_012_500L, null);
        row.addTag(ChannelTag.CURRENT_CONTROL);

        assertTrue(ChannelActivitySelectionController.isSiteControl(row));
    }

    @Test
    void selectsCurrentControlWhenLogicalSiteRowNeedsReplacement() throws Exception
    {
        Channel owner = new Channel("County System");
        ChannelActivityTableState state = new ChannelActivityTableState("County System", owner, true, null);
        ChannelActivityRow configured = state.getOrCreate("configured", owner,
            ChannelActivityRow.Role.CONFIGURED_CONTROL, 851_012_500L, null);
        ChannelActivityRow current = state.getOrCreate("current", owner, ChannelActivityRow.Role.CURRENT_CONTROL,
            852_012_500L, null);
        state.refreshAllRows();
        ChannelActivityTableModel model = new ChannelActivityTableModel(state);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertEquals(current.getKey(), ChannelActivitySelectionController.findPreferredSiteControl(model).getKey());
        assertTrue(ChannelActivitySelectionController.isSiteControl(configured));
    }
}
