/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityRow;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionScope;
import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.List;
import javax.swing.JButton;
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

        assertFalse(DecodeEventPanel.matchesSelectedFrequency(grant, CONTROL_FREQUENCY, null, false));
        assertTrue(DecodeEventPanel.matchesSelectedFrequency(grant, CONTROL_FREQUENCY, null, true));
        assertTrue(DecodeEventPanel.matchesSelectedFrequency(grant, TRAFFIC_FREQUENCY, null, false));
    }

    @Test
    void exactEventSelectionRequiresMatchingTimeslot()
    {
        IDecodeEvent timeslotOne = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1L)
            .channel(new TestChannelDescriptor(TRAFFIC_FREQUENCY))
            .timeslot(1)
            .build();
        IDecodeEvent timeslotTwo = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 2L)
            .channel(new TestChannelDescriptor(TRAFFIC_FREQUENCY))
            .timeslot(2)
            .build();

        assertTrue(DecodeEventPanel.matchesSelectedFrequency(timeslotOne, TRAFFIC_FREQUENCY, 1, false));
        assertFalse(DecodeEventPanel.matchesSelectedFrequency(timeslotTwo, TRAFFIC_FREQUENCY, 1, false));
        assertTrue(DecodeEventPanel.matchesSelectedFrequency(timeslotTwo, TRAFFIC_FREQUENCY, 1, true));
    }

    @Test
    void controlRetuneWithinSameSiteDoesNotChangeLogicalEventSelection()
    {
        Channel site = new Channel("Test Site");
        SelectedFrequencyContext previous = new SelectedFrequencyContext(CONTROL_FREQUENCY, null, "P25", site,
            site, null, ChannelActivitySelectionScope.SITE, false);
        SelectedFrequencyContext rotated = new SelectedFrequencyContext(TRAFFIC_FREQUENCY, null, "P25", site,
            site, null, ChannelActivitySelectionScope.SITE, false);
        Channel otherSite = new Channel("Other Site");
        SelectedFrequencyContext changed = new SelectedFrequencyContext(TRAFFIC_FREQUENCY, null, "P25", otherSite,
            otherSite, null, ChannelActivitySelectionScope.SITE, false);

        assertTrue(previous.hasSameLogicalSelection(rotated));
        assertFalse(previous.hasSameLogicalSelection(changed));
    }

    @Test
    void exactLogicalIdentityIgnoresTrunkedChildReplacementButDistinguishesConventionalChannels()
    {
        Channel site = new Channel("Test Site");
        Channel firstTraffic = new Channel("Traffic A");
        Channel replacementTraffic = new Channel("Traffic B");
        SelectedFrequencyContext allocated = new SelectedFrequencyContext(TRAFFIC_FREQUENCY, 1, "P25", site,
            firstTraffic, null, ChannelActivitySelectionScope.EXACT, false);
        SelectedFrequencyContext released = new SelectedFrequencyContext(TRAFFIC_FREQUENCY, 1, "P25", site,
            site, null, ChannelActivitySelectionScope.EXACT, false);
        SelectedFrequencyContext replacement = new SelectedFrequencyContext(TRAFFIC_FREQUENCY, 1, "P25", site,
            replacementTraffic, null, ChannelActivitySelectionScope.EXACT, false);

        assertTrue(allocated.hasSameLogicalSelection(released));
        assertTrue(released.hasSameLogicalSelection(replacement));

        Channel firstConventional = new Channel("Conventional A");
        Channel secondConventional = new Channel("Conventional B");
        SelectedFrequencyContext first = new SelectedFrequencyContext(TRAFFIC_FREQUENCY, null, "NBFM", null,
            firstConventional, null, ChannelActivitySelectionScope.EXACT, false);
        SelectedFrequencyContext second = new SelectedFrequencyContext(TRAFFIC_FREQUENCY, null, "NBFM", null,
            secondConventional, null, ChannelActivitySelectionScope.EXACT, false);

        assertFalse(first.hasSameLogicalSelection(second));
    }

    @Test
    void siteRotationAndViewCollapsePreserveEventRows() throws Exception
    {
        Channel site = new Channel("Test Site");
        site.setDecodeConfiguration(new DecodeConfigNBFM());
        ProcessingChain processingChain = new ProcessingChain(site, new AliasModel());
        IDecodeEvent first = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1L)
            .channel(new TestChannelDescriptor(TRAFFIC_FREQUENCY))
            .build();
        IDecodeEvent whileCollapsed = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 2L)
            .channel(new TestChannelDescriptor(TRAFFIC_FREQUENCY + 12_500L))
            .build();
        processingChain.getDecodeEventHistory().receive(first);
        DecodeEventPanel[] panelReference = new DecodeEventPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] =
            new DecodeEventPanel(null, new UserPreferences(), null));
        DecodeEventPanel panel = panelReference[0];

        try
        {
            SelectedFrequencyContext initial = new SelectedFrequencyContext(CONTROL_FREQUENCY, null, "P25", site,
                site, processingChain, ChannelActivitySelectionScope.SITE, false);
            SelectedFrequencyContext rotated = new SelectedFrequencyContext(CONTROL_FREQUENCY + 12_500L, null,
                "P25", site, site, processingChain, ChannelActivitySelectionScope.SITE, false);
            panel.receive(initial);
            drainSwing();
            JTable table = component(panel, JTable.class);
            assertEquals(1, table.getRowCount());

            panel.receive(rotated);
            drainSwing();
            assertEquals(1, table.getRowCount());

            SwingUtilities.invokeAndWait(panel::suspend);
            processingChain.getDecodeEventHistory().receive(whileCollapsed);
            drainSwing();
            assertEquals(1, table.getRowCount());

            SwingUtilities.invokeAndWait(() -> panel.resume(rotated));
            drainSwing();
            assertEquals(2, table.getRowCount());

            SwingUtilities.invokeAndWait(panel::suspend);
            SwingUtilities.invokeAndWait(() -> panel.resume(rotated));
            drainSwing();
            assertEquals(2, table.getRowCount());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void eventQueuedDuringReattachSurvivesHistoryMerge() throws Exception
    {
        Channel site = new Channel("Reattach Race Site");
        site.setDecodeConfiguration(new DecodeConfigNBFM());
        ProcessingChain processingChain = new ProcessingChain(site, new AliasModel());
        IDecodeEvent initial = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1L)
            .channel(new TestChannelDescriptor(TRAFFIC_FREQUENCY))
            .build();
        IDecodeEvent duringReattach = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 2L)
            .channel(new TestChannelDescriptor(TRAFFIC_FREQUENCY + 12_500L))
            .build();
        ReattachRaceDecodeEventHistory history = new ReattachRaceDecodeEventHistory(initial, duringReattach);
        setDecodeEventHistory(processingChain, history);
        DecodeEventPanel[] panelReference = new DecodeEventPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] =
            new DecodeEventPanel(null, new UserPreferences(), null));
        DecodeEventPanel panel = panelReference[0];
        SelectedFrequencyContext selection = new SelectedFrequencyContext(CONTROL_FREQUENCY, null, "P25", site,
            site, processingChain, ChannelActivitySelectionScope.SITE, false);

        try
        {
            panel.receive(selection);
            drainSwing();
            JTable table = component(panel, JTable.class);
            assertEquals(1, table.getRowCount());

            SwingUtilities.invokeAndWait(panel::suspend);
            history.arm();
            SwingUtilities.invokeAndWait(() -> panel.resume(selection));
            SwingUtilities.invokeAndWait(panel::drainLiveEvents);
            drainSwing();

            assertEquals(2, table.getRowCount());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void reattachPreservesUiRowsBeyondBoundedSourceHistory() throws Exception
    {
        Channel site = new Channel("Long UI History Site");
        site.setDecodeConfiguration(new DecodeConfigNBFM());
        ProcessingChain processingChain = new ProcessingChain(site, new AliasModel());
        DecodeEventPanel[] panelReference = new DecodeEventPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] =
            new DecodeEventPanel(null, new UserPreferences(), null));
        DecodeEventPanel panel = panelReference[0];
        SelectedFrequencyContext selection = new SelectedFrequencyContext(CONTROL_FREQUENCY, null, "P25", site,
            site, processingChain, ChannelActivitySelectionScope.SITE, false);

        try
        {
            SwingUtilities.invokeAndWait(() -> component(panel, JSlider.class).setValue(300));
            panel.receive(selection);
            drainSwing();

            for(int event = 0; event < 250; event++)
            {
                processingChain.getDecodeEventHistory().receive(DecodeEvent.builder(DecodeEventType.CALL_GROUP,
                        event + 1L)
                    .channel(new TestChannelDescriptor(TRAFFIC_FREQUENCY + event))
                    .build());
            }

            SwingUtilities.invokeAndWait(panel::drainLiveEvents);
            drainSwing();
            JTable table = component(panel, JTable.class);
            assertEquals(250, table.getRowCount());
            assertTrue(processingChain.getDecodeEventHistory().getItems().size() < 250);

            SwingUtilities.invokeAndWait(panel::suspend);
            SwingUtilities.invokeAndWait(() -> panel.resume(selection));
            drainSwing();

            assertEquals(250, table.getRowCount());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void staleSameHistoryAttachmentCannotEnterAfterSelectionReturnsToIt() throws Exception
    {
        Channel firstSite = channel("First Site");
        Channel secondSite = channel("Second Site");
        ProcessingChain firstChain = new ProcessingChain(firstSite, new AliasModel());
        ProcessingChain secondChain = new ProcessingChain(secondSite, new AliasModel());
        IDecodeEvent selected = event(1L);
        IDecodeEvent other = event(2L);
        IDecodeEvent stale = event(3L);
        firstChain.getDecodeEventHistory().receive(selected);
        secondChain.getDecodeEventHistory().receive(other);
        DecodeEventPanel[] panelReference = new DecodeEventPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] =
            new DecodeEventPanel(null, new UserPreferences(), null));
        DecodeEventPanel panel = panelReference[0];
        SelectedFrequencyContext firstSelection = siteSelection(firstSite, firstChain, CONTROL_FREQUENCY);
        SelectedFrequencyContext secondSelection = siteSelection(secondSite, secondChain, TRAFFIC_FREQUENCY);

        try
        {
            panel.receive(firstSelection);
            drainSwing();
            Listener<IDecodeEvent> oldAttachment = currentEventHistoryListener(panel);

            panel.receive(secondSelection);
            panel.receive(firstSelection);
            drainSwing();
            oldAttachment.receive(stale);
            SwingUtilities.invokeAndWait(panel::drainLiveEvents);

            DecodeEventModel model = (DecodeEventModel)component(panel, JTable.class).getModel();
            assertEquals(1, model.getRowCount());
            assertTrue(model.getItem(0) == selected);
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void clearRejectsQueuedEventsButAcceptsPostClearEvents() throws Exception
    {
        Channel site = channel("Clear Ordering Site");
        ProcessingChain processingChain = new ProcessingChain(site, new AliasModel());
        DecodeEventPanel[] panelReference = new DecodeEventPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] =
            new DecodeEventPanel(null, new UserPreferences(), null));
        DecodeEventPanel panel = panelReference[0];

        try
        {
            panel.receive(siteSelection(site, processingChain, CONTROL_FREQUENCY));
            drainSwing();
            IDecodeEvent beforeClear = event(10L);
            SwingUtilities.invokeAndWait(() -> {
                processingChain.getDecodeEventHistory().receive(beforeClear);
                button(panel, "Clear").doClick();
            });
            SwingUtilities.invokeAndWait(panel::drainLiveEvents);
            assertEquals(0, component(panel, JTable.class).getModel().getRowCount());

            processingChain.getDecodeEventHistory().receive(beforeClear);
            SwingUtilities.invokeAndWait(panel::drainLiveEvents);
            DecodeEventModel model = (DecodeEventModel)component(panel, JTable.class).getModel();
            assertEquals(1, model.getRowCount());
            assertTrue(model.getItem(0) == beforeClear);
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void manualClearDoesNotResurrectSourceSnapshotOnResume() throws Exception
    {
        Channel site = new Channel("Manual Clear Site");
        site.setDecodeConfiguration(new DecodeConfigNBFM());
        ProcessingChain processingChain = new ProcessingChain(site, new AliasModel());
        processingChain.getDecodeEventHistory().receive(event(1L));
        processingChain.getDecodeEventHistory().receive(event(2L));
        DecodeEventPanel[] panelReference = new DecodeEventPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] =
            new DecodeEventPanel(null, new UserPreferences(), null));
        DecodeEventPanel panel = panelReference[0];
        SelectedFrequencyContext selection = new SelectedFrequencyContext(CONTROL_FREQUENCY, null, "P25", site,
            site, processingChain, ChannelActivitySelectionScope.SITE, false);

        try
        {
            panel.receive(selection);
            drainSwing();
            JTable table = component(panel, JTable.class);
            assertEquals(2, table.getRowCount());

            SwingUtilities.invokeAndWait(() -> button(panel, "Clear").doClick());
            assertEquals(0, table.getRowCount());

            SwingUtilities.invokeAndWait(panel::suspend);
            SwingUtilities.invokeAndWait(() -> panel.resume(selection));
            drainSwing();
            assertEquals(0, table.getRowCount());

            SwingUtilities.invokeAndWait(panel::suspend);
            processingChain.getDecodeEventHistory().receive(event(3L));
            SwingUtilities.invokeAndWait(() -> panel.resume(selection));
            drainSwing();
            assertEquals(1, table.getRowCount());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
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

    private static JButton button(Container root, String text)
    {
        for(Component component: root.getComponents())
        {
            if(component instanceof JButton button && text.equals(button.getText()))
            {
                return button;
            }

            if(component instanceof Container container)
            {
                JButton match = buttonOrNull(container, text);

                if(match != null)
                {
                    return match;
                }
            }
        }

        throw new AssertionError("Button not found: " + text);
    }

    private static JButton buttonOrNull(Container root, String text)
    {
        for(Component component: root.getComponents())
        {
            if(component instanceof JButton button && text.equals(button.getText()))
            {
                return button;
            }

            if(component instanceof Container container)
            {
                JButton match = buttonOrNull(container, text);

                if(match != null)
                {
                    return match;
                }
            }
        }

        return null;
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

    private static void drainSwing() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
    }

    private static void setDecodeEventHistory(ProcessingChain processingChain, DecodeEventHistory history)
        throws ReflectiveOperationException
    {
        Field field = ProcessingChain.class.getDeclaredField("mDecodeEventHistory");
        field.setAccessible(true);
        field.set(processingChain, history);
    }

    @SuppressWarnings("unchecked")
    private static Listener<IDecodeEvent> currentEventHistoryListener(DecodeEventPanel panel)
        throws ReflectiveOperationException
    {
        Field field = DecodeEventPanel.class.getDeclaredField("mCurrentEventHistoryListener");
        field.setAccessible(true);
        return (Listener<IDecodeEvent>)field.get(panel);
    }

    private static Channel channel(String name)
    {
        Channel channel = new Channel(name);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        return channel;
    }

    private static SelectedFrequencyContext siteSelection(Channel site, ProcessingChain processingChain,
                                                          long frequency)
    {
        return new SelectedFrequencyContext(frequency, null, "P25", site, site, processingChain,
            ChannelActivitySelectionScope.SITE, false);
    }

    private static SelectedFrequencyContext selection(ChannelActivityRow.Role role)
    {
        boolean siteEventSelection = role == ChannelActivityRow.Role.CONFIGURED_CONTROL ||
            role == ChannelActivityRow.Role.CURRENT_CONTROL || role == ChannelActivityRow.Role.ALTERNATE_CONTROL;
        ChannelActivitySelectionScope scope = siteEventSelection ? ChannelActivitySelectionScope.SITE :
            ChannelActivitySelectionScope.EXACT;
        return new SelectedFrequencyContext(CONTROL_FREQUENCY, null, "P25", null, null, null, scope, false);
    }

    private static IDecodeEvent event(long timestamp)
    {
        return DecodeEvent.builder(DecodeEventType.CALL_GROUP, timestamp)
            .channel(new TestChannelDescriptor(TRAFFIC_FREQUENCY + timestamp))
            .build();
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

    private static class ReattachRaceDecodeEventHistory extends DecodeEventHistory
    {
        private final IDecodeEvent mDuringReattach;
        private boolean mArmed;

        private ReattachRaceDecodeEventHistory(IDecodeEvent initial, IDecodeEvent duringReattach)
        {
            super(200);
            mDuringReattach = duringReattach;
            receive(initial);
        }

        private void arm()
        {
            mArmed = true;
        }

        @Override
        public List<IDecodeEvent> getItems()
        {
            List<IDecodeEvent> snapshot = super.getItems();

            if(mArmed)
            {
                mArmed = false;
                receive(mDuringReattach);
            }

            return snapshot;
        }
    }
}
