/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityRow;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionScope;
import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
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

    @Test
    void historySizeChangeRestoresEventTableRenderers() throws Exception
    {
        DecodeEventPanel[] panelReference = new DecodeEventPanel[1];

        SwingUtilities.invokeAndWait(() -> {
            DecodeEventPanel panel = new DecodeEventPanel(null, new UserPreferences(), null);
            panelReference[0] = panel;
            JTable table = component(panel, JTable.class);
            table.getColumnModel().getColumn(DecodeEventModel.COLUMN_DURATION).setCellRenderer(null);
            table.getColumnModel().getColumn(DecodeEventModel.COLUMN_FROM_ID).setCellRenderer(null);
            table.getColumnModel().getColumn(DecodeEventModel.COLUMN_FROM_ALIAS).setCellRenderer(null);
            table.getColumnModel().getColumn(DecodeEventModel.COLUMN_TO_ID).setCellRenderer(null);
            table.getColumnModel().getColumn(DecodeEventModel.COLUMN_TO_ALIAS).setCellRenderer(null);
            table.getColumnModel().getColumn(DecodeEventModel.COLUMN_FREQUENCY).setCellRenderer(null);

            JSlider slider = component(panel, JSlider.class);
            slider.setValue(slider.getValue() + 1);

            assertInstanceOf(DecodeEventPanel.DurationCellRenderer.class,
                table.getColumnModel().getColumn(DecodeEventModel.COLUMN_DURATION).getCellRenderer());
            assertInstanceOf(DecodeEventPanel.IdentifierCellRenderer.class,
                table.getColumnModel().getColumn(DecodeEventModel.COLUMN_FROM_ID).getCellRenderer());
            assertInstanceOf(DecodeEventPanel.AliasedIdentifierCellRenderer.class,
                table.getColumnModel().getColumn(DecodeEventModel.COLUMN_FROM_ALIAS).getCellRenderer());
            assertInstanceOf(DecodeEventPanel.IdentifierCellRenderer.class,
                table.getColumnModel().getColumn(DecodeEventModel.COLUMN_TO_ID).getCellRenderer());
            assertInstanceOf(DecodeEventPanel.AliasedIdentifierCellRenderer.class,
                table.getColumnModel().getColumn(DecodeEventModel.COLUMN_TO_ALIAS).getCellRenderer());
            assertInstanceOf(DecodeEventPanel.FrequencyCellRenderer.class,
                table.getColumnModel().getColumn(DecodeEventModel.COLUMN_FREQUENCY).getCellRenderer());
        });

        SwingUtilities.invokeAndWait(panelReference[0]::dispose);
    }

    private static <T extends Component> T component(Container root, Class<T> type)
    {
        for(Component component: root.getComponents())
        {
            if(type.isInstance(component))
            {
                return type.cast(component);
            }

            if(component instanceof Container container)
            {
                T match = componentOrNull(container, type);

                if(match != null)
                {
                    return match;
                }
            }
        }

        throw new AssertionError("Component not found: " + type.getSimpleName());
    }

    private static <T extends Component> T componentOrNull(Container root, Class<T> type)
    {
        for(Component component: root.getComponents())
        {
            if(type.isInstance(component))
            {
                return type.cast(component);
            }

            if(component instanceof Container container)
            {
                T match = componentOrNull(container, type);

                if(match != null)
                {
                    return match;
                }
            }
        }

        return null;
    }

    private static SelectedFrequencyContext selection(ChannelActivityRow.Role role)
    {
        boolean siteEventSelection = role == ChannelActivityRow.Role.CONFIGURED_CONTROL ||
            role == ChannelActivityRow.Role.CURRENT_CONTROL || role == ChannelActivityRow.Role.ALTERNATE_CONTROL;
        ChannelActivitySelectionScope scope = siteEventSelection ? ChannelActivitySelectionScope.SITE :
            ChannelActivitySelectionScope.EXACT_FREQUENCY;
        return new SelectedFrequencyContext(CONTROL_FREQUENCY, null, "P25", null, null, null, null, scope, false);
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
