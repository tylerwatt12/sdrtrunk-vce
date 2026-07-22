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
    void publishesCompleteSnapshotsWhileSwingTableIsHidden()
    {
        ChannelActivityTableModel model = new ChannelActivityTableModel("Conventional", null, false);
        AtomicReference<ChannelActivitySnapshot> latest = new AtomicReference<>();
        model.addSnapshotListener(latest::set);
        Channel channel = new Channel("County Fire");
        ChannelActivityRow row = model.getOrCreate("row-1", channel, ChannelActivityRow.Role.CONVENTIONAL,
            155_250_000L, null);
        row.setState(State.CALL);
        row.setDecoder("NBFM");
        row.setCallsign("WPFF205");
        model.refresh(row);

        assertEquals("conventional", latest.get().tableId());
        assertEquals(1, latest.get().rows().size());
        assertEquals("CALL", latest.get().rows().getFirst().status());
        assertEquals("County Fire", latest.get().rows().getFirst().channelName());
        assertEquals(155_250_000L, latest.get().rows().getFirst().frequencyHz());
        assertEquals("WPFF205", latest.get().rows().getFirst().callsign());
        assertTrue(latest.get().rows().getFirst().selectionId().startsWith("exact-"));
        assertEquals(ChannelActivitySelectionScope.EXACT_FREQUENCY,
            latest.get().rows().getFirst().selectionScope());
        assertEquals(channel.getChannelID(), latest.get().rows().getFirst().rowChannelId());
        assertNull(latest.get().rows().getFirst().ownerChannelId());
        assertEquals("WPFF205", model.getValueAt(0, ChannelActivityTableModel.COLUMN_CALLSIGN));
        assertEquals("Channel", model.getColumnName(ChannelActivityTableModel.COLUMN_LCN));
        assertEquals("County Fire", model.getValueAt(0, ChannelActivityTableModel.COLUMN_LCN));
    }

    @Test
    void preservesLcnColumnForTrunkedTables()
    {
        Channel owner = new Channel("County System");
        ChannelActivityTableModel model = new ChannelActivityTableModel("County System", owner, true);
        ChannelActivityRow row = model.getOrCreate("row-1", owner, ChannelActivityRow.Role.CURRENT_CONTROL,
            851_012_500L, null);
        row.setLcn("0-101");

        assertEquals("LCN", model.getColumnName(ChannelActivityTableModel.COLUMN_LCN));
        assertEquals("0-101", model.getValueAt(0, ChannelActivityTableModel.COLUMN_LCN));
        assertNull(ChannelActivitySnapshot.from(model).rows().getFirst().channelName());
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
    void selectsCurrentControlWhenLogicalSiteRowNeedsReplacement()
    {
        Channel owner = new Channel("County System");
        ChannelActivityTableModel model = new ChannelActivityTableModel("County System", owner, true);
        ChannelActivityRow configured = model.getOrCreate("configured", owner,
            ChannelActivityRow.Role.CONFIGURED_CONTROL, 851_012_500L, null);
        ChannelActivityRow current = model.getOrCreate("current", owner, ChannelActivityRow.Role.CURRENT_CONTROL,
            852_012_500L, null);

        assertSame(current, ChannelActivitySelectionController.findPreferredSiteControl(model));
        assertTrue(ChannelActivitySelectionController.isSiteControl(configured));
    }
}
