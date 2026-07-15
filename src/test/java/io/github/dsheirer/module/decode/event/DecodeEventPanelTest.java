/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityRow;
import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class DecodeEventPanelTest
{
    private static final long CONTROL_FREQUENCY = 851_012_500L;
    private static final long TRAFFIC_FREQUENCY = 852_012_500L;

    @Test
    void controlRowsSelectSiteEventHistory()
    {
        assertTrue(DecodeEventPanel.isSiteEventSelection(selection(ChannelActivityRow.Role.CONFIGURED_CONTROL)));
        assertTrue(DecodeEventPanel.isSiteEventSelection(selection(ChannelActivityRow.Role.CURRENT_CONTROL)));
        assertTrue(DecodeEventPanel.isSiteEventSelection(selection(ChannelActivityRow.Role.ALTERNATE_CONTROL)));
        assertFalse(DecodeEventPanel.isSiteEventSelection(selection(ChannelActivityRow.Role.TRAFFIC)));
        assertFalse(DecodeEventPanel.isSiteEventSelection(selection(ChannelActivityRow.Role.CONVENTIONAL)));
        assertFalse(DecodeEventPanel.isSiteEventSelection(SelectedFrequencyContext.clear()));
    }

    @Test
    void siteEventHistoryIncludesTrafficFrequencyGrants()
    {
        IDecodeEvent grant = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1L)
            .channel(new TestChannelDescriptor(TRAFFIC_FREQUENCY))
            .build();

        assertFalse(DecodeEventPanel.matchesSelectedFrequency(grant, CONTROL_FREQUENCY, false));
        assertTrue(DecodeEventPanel.matchesSelectedFrequency(grant, CONTROL_FREQUENCY, true));
        assertTrue(DecodeEventPanel.matchesSelectedFrequency(grant, TRAFFIC_FREQUENCY, false));
    }

    @Test
    void controlRetuneWithinSameSiteDoesNotChangeLogicalEventSelection()
    {
        Channel site = new Channel("Test Site");

        assertFalse(DecodeEventPanel.logicalSelectionChanged(CONTROL_FREQUENCY, null, true, site,
            TRAFFIC_FREQUENCY, null, true, site));
        assertTrue(DecodeEventPanel.logicalSelectionChanged(CONTROL_FREQUENCY, null, true, site,
            TRAFFIC_FREQUENCY, null, true, new Channel("Other Site")));
    }

    private static SelectedFrequencyContext selection(ChannelActivityRow.Role role)
    {
        boolean siteEventSelection = role == ChannelActivityRow.Role.CONFIGURED_CONTROL ||
            role == ChannelActivityRow.Role.CURRENT_CONTROL || role == ChannelActivityRow.Role.ALTERNATE_CONTROL;
        return new SelectedFrequencyContext(CONTROL_FREQUENCY, null, role, "P25", "site", null, null, null,
            null, siteEventSelection, false);
    }

    private record TestChannelDescriptor(long getDownlinkFrequency) implements IChannelDescriptor
    {
        @Override
        public long getUplinkFrequency()
        {
            return 0;
        }

        @Override
        public int[] getFrequencyBandIdentifiers()
        {
            return new int[0];
        }

        @Override
        public void setFrequencyBand(IFrequencyBand bandIdentifier)
        {
        }

        @Override
        public boolean isTDMAChannel()
        {
            return false;
        }

        @Override
        public int getTimeslotCount()
        {
            return 1;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.APCO25;
        }
    }
}
