/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ChannelActivityModelTest
{
    @Test
    void exposesExistingTablesWhenRendererAttachesAfterChannelStart() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(), new NowPlayingPreference(type -> {}));
        Channel channel = new Channel("Test Site", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(856_137_500L);
        channel.setSourceConfiguration(source);

        SwingUtilities.invokeAndWait(() -> {
            model.setEnabled(true);
            model.channelStarted(channel, List.of());
        });

        List<ChannelActivityTableModel> tables = model.getTables();
        assertEquals(2, tables.size());
        assertSame(model.getConventionalTable(), tables.getFirst());
        assertSame(channel, tables.get(1).getOwnerChannel());
    }

    @Test
    void appliesAndClearsControlChannelQualityInJavaActivityTable() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(), new NowPlayingPreference(type -> {}));
        Channel channel = new Channel("Test Site", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        channel.setRadresGuid("123e4567-e89b-12d3-a456-426614174000");
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(856_137_500L);
        channel.setSourceConfiguration(source);

        SwingUtilities.invokeAndWait(() -> {
            model.setEnabled(true);
            model.channelStarted(channel, List.of());
            model.receiveControlChannelQuality(new ControlChannelQualitySnapshot(channel, channel.getRadresGuid(),
                856_137_500L, 1_000L, true, -20.5, -21.0, -25.0, -18.0, 97.5,
                100, 1, 3, 0, 0, 999L));
        });

        ChannelActivityRow row = model.getTables().get(1).getRows().getFirst();
        assertEquals(-20.5, row.getSignalDbfs());
        assertEquals(97.5, row.getDecodeHealthPercent());
        assertEquals(1_000L, row.getQualityObservedAt());

        SwingUtilities.invokeAndWait(() -> model.receiveControlChannelQuality(new ControlChannelQualitySnapshot(
            channel, channel.getRadresGuid(), 856_137_500L, 2_000L, false, -20.5, -21.0, -25.0, -18.0,
            97.5, 100, 1, 3, 0, 0, 999L)));
        assertNull(row.getSignalDbfs());
        assertNull(row.getDecodeHealthPercent());
        assertEquals(0, row.getQualityObservedAt());
    }

    @Test
    void combinesControlVoiceAndDataTagsForTheSameFrequency()
    {
        Channel parent = new Channel("Test Site", ChannelType.STANDARD);
        ChannelActivityTableModel table = new ChannelActivityTableModel("Test Site", parent, true);
        SiteActivitySession session = new SiteActivitySession(parent, table);
        long frequency = 856_137_500L;

        ChannelActivityRow control = session.currentControl(frequency, "0-821").current();
        ChannelActivityRow traffic = session.announcedData(frequency, "0-821");
        session.addTag(frequency, ChannelTag.VOICE);
        session.addTag(frequency, ChannelTag.DATA);
        traffic.setState(io.github.dsheirer.channel.state.State.ENCRYPTED);

        assertSame(control, traffic);
        assertEquals(1, table.getRows().size());

        for(ChannelActivityRow row: List.of(control, traffic))
        {
            assertTrue(row.hasTag(ChannelTag.CURRENT_CONTROL));
            assertTrue(row.hasTag(ChannelTag.DATA_ANNOUNCED));
            assertTrue(row.hasTag(ChannelTag.VOICE));
            assertTrue(row.hasTag(ChannelTag.DATA));
        }

        assertEquals(io.github.dsheirer.channel.state.State.ENCRYPTED, traffic.getState());
    }

    @Test
    void reusesAlternateControlRowForFdmaVoiceTraffic()
    {
        Channel parent = new Channel("Test Site", ChannelType.STANDARD);
        ChannelActivityTableModel table = new ChannelActivityTableModel("Test Site", parent, true);
        SiteActivitySession session = new SiteActivitySession(parent, table);
        APCO25Channel channel = APCO25Channel.create(0, 459);
        channel.setFrequencyBand(new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1));

        ChannelActivityRow alternate = session.alternateControl(channel);
        ChannelActivityRow traffic = session.traffic(parent, channel);
        session.addTag(channel.getDownlinkFrequency(), ChannelTag.VOICE);
        traffic.setState(io.github.dsheirer.channel.state.State.CALL);
        traffic.setTrafficGrantExpiresAt(System.currentTimeMillis() + 5_000L);

        ChannelActivityRow refreshedAlternate = session.alternateControl(channel);

        assertSame(alternate, traffic);
        assertSame(traffic, refreshedAlternate);
        assertEquals(853_875_000L, traffic.getFrequency());
        assertEquals("ACC + VC", traffic.getTagsDisplay());
        assertEquals(io.github.dsheirer.channel.state.State.CALL, traffic.getState());
        assertEquals(1, table.getRows().size());
    }

    @Test
    void retainsSeparateRowsForTdmATimeslots()
    {
        Channel parent = new Channel("Test Site", ChannelType.STANDARD);
        ChannelActivityTableModel table = new ChannelActivityTableModel("Test Site", parent, true);
        SiteActivitySession session = new SiteActivitySession(parent, table);
        P25FrequencyBand band = new P25FrequencyBand(1, 851_012_500L, -45_000_000L, 12_500L, 12_500, 2);
        APCO25Channel timeslotOne = APCO25Channel.create(1, 2);
        APCO25Channel timeslotTwo = APCO25Channel.create(1, 3);
        timeslotOne.setFrequencyBand(band);
        timeslotTwo.setFrequencyBand(band);

        ChannelActivityRow control = session.alternateControl(timeslotOne.getDownlinkFrequency(), "1-2");
        ChannelActivityRow trafficOne = session.traffic(parent, timeslotOne);
        ChannelActivityRow trafficTwo = session.traffic(parent, timeslotTwo);

        assertNotSame(control, trafficOne);
        assertNotSame(trafficOne, trafficTwo);
        assertEquals(trafficOne.getFrequency(), trafficTwo.getFrequency());
        assertEquals(3, table.getRows().size());
    }

    @Test
    void retainsFdmaTrafficRowWhenControlAnnouncementIsWithdrawn()
    {
        Channel parent = new Channel("Test Site", ChannelType.STANDARD);
        ChannelActivityTableModel table = new ChannelActivityTableModel("Test Site", parent, true);
        SiteActivitySession session = new SiteActivitySession(parent, table);
        long frequency = 853_875_000L;

        ChannelActivityRow alternate = session.alternateControl(frequency, "0-459");
        ChannelActivityRow traffic = session.announcedData(frequency, "0-459");
        session.addTag(frequency, ChannelTag.VOICE);

        assertTrue(session.reconcilePromotedControls(Set.of(), 852_400_000L).isEmpty());
        assertSame(alternate, traffic);
        assertSame(traffic, session.traffic(frequency, null));
        assertEquals(1, table.getRows().size());
    }
}
