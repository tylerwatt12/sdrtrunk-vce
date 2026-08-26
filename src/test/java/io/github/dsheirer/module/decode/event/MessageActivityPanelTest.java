/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionScope;
import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.filter.AllPassFilter;
import io.github.dsheirer.filter.Filter;
import io.github.dsheirer.filter.FilterElement;
import io.github.dsheirer.filter.FilterSet;
import io.github.dsheirer.filter.IFilter;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.MessageHistory;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class MessageActivityPanelTest
{
    @Test
    void siteRotationAndViewCollapsePreserveMessagesAndFilters() throws Exception
    {
        Channel site = channel("Test Site");
        ProcessingChain processingChain = new ProcessingChain(site, new AliasModel());
        IMessage first = new TestMessage(1L, 0);
        IMessage second = new TestMessage(2L, 0);
        processingChain.getMessageHistory().receive(first);
        MessageActivityPanel[] panelReference = new MessageActivityPanel[1];

        SwingUtilities.invokeAndWait(() -> panelReference[0] = new MessageActivityPanel(new UserPreferences()));
        MessageActivityPanel panel = panelReference[0];

        try
        {
            SelectedFrequencyContext initial = siteSelection(site, processingChain, 851_012_500L);
            SelectedFrequencyContext rotated = siteSelection(site, processingChain, 852_012_500L);
            SwingUtilities.invokeAndWait(() -> {
                panel.receive(initial);
                panel.addNotify();
            });
            drainSwing();
            assertEquals(1, table(panel).getRowCount());
            FilterSet<IMessage> filterSet = filterSet(panel);

            processingChain.getMessageHistory().receive(second);
            SwingUtilities.invokeAndWait(panel::drainLiveMessages);
            drainSwing();
            assertEquals(2, table(panel).getRowCount());

            SwingUtilities.invokeAndWait(() -> panel.receive(rotated));
            drainSwing();
            assertEquals(2, table(panel).getRowCount());
            assertSame(filterSet, filterSet(panel));

            SwingUtilities.invokeAndWait(panel::removeNotify);
            assertEquals(2, table(panel).getRowCount());
            assertSame(filterSet, filterSet(panel));

            IMessage whileCollapsed = new TestMessage(3L, 0);
            processingChain.getMessageHistory().receive(whileCollapsed);
            drainSwing();
            assertEquals(2, table(panel).getRowCount());

            SwingUtilities.invokeAndWait(() -> {
                panel.receive(rotated);
                panel.addNotify();
            });
            drainSwing();
            assertEquals(3, table(panel).getRowCount());
            assertSame(filterSet, filterSet(panel));
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void listenerRegisteredBeforeReattachSnapshotDoesNotMissConcurrentMessage() throws Exception
    {
        Channel site = channel("Reattach Race Site");
        ProcessingChain processingChain = new ProcessingChain(site, new AliasModel());
        IMessage initial = new TestMessage(1L, 0);
        IMessage duringReattach = new TestMessage(2L, 0);
        ReattachRaceMessageHistory history = new ReattachRaceMessageHistory(initial, duringReattach);
        setMessageHistory(processingChain, history);
        MessageActivityPanel[] panelReference = new MessageActivityPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] = new MessageActivityPanel(new UserPreferences()));
        MessageActivityPanel panel = panelReference[0];
        SelectedFrequencyContext selection = siteSelection(site, processingChain, 851_012_500L);

        try
        {
            SwingUtilities.invokeAndWait(() -> {
                panel.receive(selection);
                panel.addNotify();
            });
            drainSwing();
            assertEquals(1, table(panel).getRowCount());

            SwingUtilities.invokeAndWait(panel::removeNotify);
            history.arm();
            SwingUtilities.invokeAndWait(panel::addNotify);
            SwingUtilities.invokeAndWait(panel::drainLiveMessages);
            drainSwing();

            assertEquals(2, table(panel).getRowCount());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void queuedMessageFromDetachedHistoryCannotEnterNewSelection() throws Exception
    {
        Channel oldSite = channel("Old Site");
        ProcessingChain oldProcessingChain = new ProcessingChain(oldSite, new AliasModel());
        Channel newSite = channel("New Site");
        ProcessingChain newProcessingChain = new ProcessingChain(newSite, new AliasModel());
        IMessage oldSnapshotMessage = new TestMessage(0L, 0);
        IMessage staleMessage = new TestMessage(1L, 0);
        IMessage selectedMessage = new TestMessage(2L, 0);
        oldProcessingChain.getMessageHistory().receive(oldSnapshotMessage);
        newProcessingChain.getMessageHistory().receive(selectedMessage);
        MessageActivityPanel[] panelReference = new MessageActivityPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] = new MessageActivityPanel(new UserPreferences()));
        MessageActivityPanel panel = panelReference[0];

        try
        {
            SwingUtilities.invokeAndWait(() -> {
                panel.receive(siteSelection(oldSite, oldProcessingChain, 851_012_500L));
                panel.addNotify();
            });
            drainSwing();

            SwingUtilities.invokeAndWait(() -> {
                //Queues delivery from the old attachment, then changes selection before the EDT can process it.
                oldProcessingChain.getMessageHistory().receive(staleMessage);
                panel.receive(siteSelection(newSite, newProcessingChain, 852_012_500L));
            });
            SwingUtilities.invokeAndWait(panel::drainLiveMessages);
            drainSwing();

            MessageActivityModel model = (MessageActivityModel)table(panel).getModel();
            assertEquals(1, model.getRowCount());
            assertSame(selectedMessage, model.getItem(0).getMessage());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void exactTrafficChildReleaseAndReplacementRetainRowsAndFilters() throws Exception
    {
        Channel owner = channel("Trunked Site");
        Channel firstTraffic = channel("Traffic A");
        Channel replacementTraffic = channel("Traffic B");
        ProcessingChain firstChain = new ProcessingChain(firstTraffic, new AliasModel());
        ProcessingChain replacementChain = new ProcessingChain(replacementTraffic, new AliasModel());
        IMessage first = new TestMessage(1L, 1);
        IMessage replacement = new TestMessage(2L, 1);
        firstChain.getMessageHistory().receive(first);
        replacementChain.getMessageHistory().receive(replacement);
        MessageActivityPanel[] panelReference = new MessageActivityPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] = new MessageActivityPanel(new UserPreferences()));
        MessageActivityPanel panel = panelReference[0];
        long frequency = 852_012_500L;
        SelectedFrequencyContext allocated = exactTrafficSelection(owner, firstTraffic, firstChain, frequency, 1);
        SelectedFrequencyContext released = exactTrafficSelection(owner, owner, null, frequency, 1);
        SelectedFrequencyContext rebound = exactTrafficSelection(owner, replacementTraffic, replacementChain,
            frequency, 1);

        try
        {
            SwingUtilities.invokeAndWait(() -> {
                panel.receive(allocated);
                panel.addNotify();
            });
            drainSwing();
            MessageActivityModel model = model(panel);
            assertEquals(1, model.getRowCount());
            FilterSet<IMessage> retainedFilters = filterSet(panel);
            FilterElement<?> excluded = firstFilterElement(retainedFilters);
            SwingUtilities.invokeAndWait(() -> excluded.setEnabled(false));

            SwingUtilities.invokeAndWait(() -> panel.receive(released));
            assertEquals(1, model.getRowCount());
            assertSame(retainedFilters, filterSet(panel));
            assertFalse(firstFilterElement(filterSet(panel)).isEnabled());

            SwingUtilities.invokeAndWait(() -> panel.receive(rebound));
            drainSwing();
            assertEquals(2, model.getRowCount());
            assertSame(retainedFilters, filterSet(panel));
            assertFalse(firstFilterElement(filterSet(panel)).isEnabled());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void messageFilterChoicesSurviveDifferentSelectionAndReturn() throws Exception
    {
        Channel firstSite = channel("First Filter Site");
        Channel secondSite = channel("Second Filter Site");
        ProcessingChain firstChain = new ProcessingChain(firstSite, new AliasModel());
        ProcessingChain secondChain = new ProcessingChain(secondSite, new AliasModel());
        MessageActivityPanel[] panelReference = new MessageActivityPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] = new MessageActivityPanel(new UserPreferences()));
        MessageActivityPanel panel = panelReference[0];
        SelectedFrequencyContext first = siteSelection(firstSite, firstChain, 851_012_500L);
        SelectedFrequencyContext second = siteSelection(secondSite, secondChain, 852_012_500L);

        try
        {
            SwingUtilities.invokeAndWait(() -> {
                panel.receive(first);
                panel.addNotify();
            });
            FilterSet<IMessage> firstFilters = filterSet(panel);
            SwingUtilities.invokeAndWait(() -> firstFilterElement(firstFilters).setEnabled(false));

            SwingUtilities.invokeAndWait(() -> panel.receive(second));
            FilterSet<IMessage> secondFilters = filterSet(panel);
            assertFalse(firstFilters == secondFilters);
            assertFalse(firstFilterElement(secondFilters).isEnabled());

            SwingUtilities.invokeAndWait(() -> panel.receive(first));
            assertFalse(firstFilterElement(filterSet(panel)).isEnabled());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void compatibleFilterStateRestoresWhileNewChoicesRemainEnabled()
    {
        FilterElementStateCache cache = new FilterElementStateCache();
        FilterSet<IMessage> first = new FilterSet<>("Message Filters");
        AllPassFilter<IMessage> firstFilter = new AllPassFilter<>("Shared Filter");
        first.addFilter(firstFilter);
        firstFilter.getFilterElements().getFirst().setEnabled(false);
        cache.capture(first);

        FilterSet<IMessage> replacement = new FilterSet<>("Message Filters");
        AllPassFilter<IMessage> replacementFilter = new AllPassFilter<>("Shared Filter");
        replacementFilter.add(new FilterElement<>("New Choice"));
        replacement.addFilter(replacementFilter);
        cache.restore(replacement);

        assertFalse(filterElement(replacementFilter, "Other/Unlisted").isEnabled());
        assertTrue(filterElement(replacementFilter, "New Choice").isEnabled());

        cache.capture(replacement);
        FilterSet<IMessage> returned = new FilterSet<>("Message Filters");
        AllPassFilter<IMessage> returnedFilter = new AllPassFilter<>("Shared Filter");
        returnedFilter.add(new FilterElement<>("Previously Unseen Choice"));
        returned.addFilter(returnedFilter);
        cache.restore(returned);

        assertFalse(filterElement(returnedFilter, "Other/Unlisted").isEnabled());
        assertTrue(filterElement(returnedFilter, "Previously Unseen Choice").isEnabled());
    }

    @Test
    void clearRejectsQueuedMessagesButAcceptsPostClearMessages() throws Exception
    {
        Channel site = channel("Clear Ordering Site");
        ProcessingChain processingChain = new ProcessingChain(site, new AliasModel());
        MessageActivityPanel[] panelReference = new MessageActivityPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] = new MessageActivityPanel(new UserPreferences()));
        MessageActivityPanel panel = panelReference[0];

        try
        {
            SwingUtilities.invokeAndWait(() -> {
                panel.receive(siteSelection(site, processingChain, 851_012_500L));
                panel.addNotify();
            });
            IMessage beforeClear = new TestMessage(10L, 0);
            SwingUtilities.invokeAndWait(() -> {
                processingChain.getMessageHistory().receive(beforeClear);
                button(panel, "Clear").doClick();
            });
            SwingUtilities.invokeAndWait(panel::drainLiveMessages);
            assertEquals(0, model(panel).getRowCount());

            IMessage afterClear = new TestMessage(11L, 0);
            processingChain.getMessageHistory().receive(afterClear);
            SwingUtilities.invokeAndWait(panel::drainLiveMessages);
            assertEquals(1, model(panel).getRowCount());
            assertSame(afterClear, model(panel).getItem(0).getMessage());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    @Test
    void manualClearDoesNotResurrectMessagesAndResumeAddsOnlyNewMessages() throws Exception
    {
        Channel site = channel("Manual Clear Site");
        ProcessingChain processingChain = new ProcessingChain(site, new AliasModel());
        processingChain.getMessageHistory().receive(new TestMessage(1L, 0));
        processingChain.getMessageHistory().receive(new TestMessage(2L, 0));
        MessageActivityPanel[] panelReference = new MessageActivityPanel[1];
        SwingUtilities.invokeAndWait(() -> panelReference[0] = new MessageActivityPanel(new UserPreferences()));
        MessageActivityPanel panel = panelReference[0];
        SelectedFrequencyContext selection = siteSelection(site, processingChain, 851_012_500L);

        try
        {
            SwingUtilities.invokeAndWait(() -> {
                panel.receive(selection);
                panel.addNotify();
            });
            drainSwing();
            JTable table = table(panel);
            assertEquals(2, table.getRowCount());

            SwingUtilities.invokeAndWait(() -> button(panel, "Clear").doClick());
            assertEquals(0, table.getRowCount());

            SwingUtilities.invokeAndWait(panel::removeNotify);
            SwingUtilities.invokeAndWait(panel::addNotify);
            drainSwing();
            assertEquals(0, table.getRowCount());

            SwingUtilities.invokeAndWait(panel::removeNotify);
            IMessage whileCollapsed = new TestMessage(3L, 0);
            processingChain.getMessageHistory().receive(whileCollapsed);
            SwingUtilities.invokeAndWait(panel::addNotify);
            drainSwing();

            MessageActivityModel model = (MessageActivityModel)table.getModel();
            assertEquals(1, model.getRowCount());
            assertSame(whileCollapsed, model.getItem(0).getMessage());
        }
        finally
        {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    private static SelectedFrequencyContext siteSelection(Channel site, ProcessingChain processingChain,
                                                          long frequency)
    {
        return new SelectedFrequencyContext(frequency, null, "NBFM", site, site, processingChain,
            ChannelActivitySelectionScope.SITE, false);
    }

    private static SelectedFrequencyContext exactTrafficSelection(Channel owner, Channel rowChannel,
                                                                  ProcessingChain processingChain, long frequency,
                                                                  int timeslot)
    {
        return new SelectedFrequencyContext(frequency, timeslot, "P25", owner, rowChannel, processingChain,
            ChannelActivitySelectionScope.EXACT, false);
    }

    private static Channel channel(String name)
    {
        Channel channel = new Channel(name);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        return channel;
    }

    private static JTable table(MessageActivityPanel panel)
    {
        for(java.awt.Component component: panel.getComponents())
        {
            if(component instanceof javax.swing.JScrollPane scrollPane &&
                scrollPane.getViewport().getView() instanceof JTable table)
            {
                return table;
            }
        }

        throw new AssertionError("Message table not found");
    }

    private static MessageActivityModel model(MessageActivityPanel panel)
    {
        return (MessageActivityModel)table(panel).getModel();
    }

    private static FilterElement<?> firstFilterElement(FilterSet<?> filterSet)
    {
        for(IFilter<?> filter: filterSet.getFilters())
        {
            if(filter instanceof FilterSet<?> childSet)
            {
                FilterElement<?> child = firstFilterElementOrNull(childSet);

                if(child != null)
                {
                    return child;
                }
            }
            else if(filter instanceof Filter<?,?> leaf && !leaf.getFilterElements().isEmpty())
            {
                return leaf.getFilterElements().getFirst();
            }
        }

        throw new AssertionError("Filter element not found");
    }

    private static FilterElement<?> firstFilterElementOrNull(FilterSet<?> filterSet)
    {
        for(IFilter<?> filter: filterSet.getFilters())
        {
            if(filter instanceof FilterSet<?> childSet)
            {
                FilterElement<?> child = firstFilterElementOrNull(childSet);

                if(child != null)
                {
                    return child;
                }
            }
            else if(filter instanceof Filter<?,?> leaf && !leaf.getFilterElements().isEmpty())
            {
                return leaf.getFilterElements().getFirst();
            }
        }

        return null;
    }

    private static FilterElement<?> filterElement(Filter<?,?> filter, String name)
    {
        return filter.getFilterElements().stream()
            .filter(element -> name.equals(element.getName()))
            .findFirst()
            .orElseThrow();
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

    private static void setMessageHistory(ProcessingChain processingChain, MessageHistory history)
        throws ReflectiveOperationException
    {
        Field field = ProcessingChain.class.getDeclaredField("mMessageHistory");
        field.setAccessible(true);
        field.set(processingChain, history);
    }

    @SuppressWarnings("unchecked")
    private static FilterSet<IMessage> filterSet(MessageActivityPanel panel) throws ReflectiveOperationException
    {
        Field field = MessageActivityPanel.class.getDeclaredField("mMessageFilterSet");
        field.setAccessible(true);
        return (FilterSet<IMessage>)field.get(panel);
    }

    private static void drainSwing() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
    }

    private record TestMessage(long timestamp, int timeslot) implements IMessage
    {
        @Override
        public long getTimestamp()
        {
            return timestamp;
        }

        @Override
        public boolean isValid()
        {
            return true;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.UNKNOWN;
        }

        @Override
        public int getTimeslot()
        {
            return timeslot;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }
    }

    private static class ReattachRaceMessageHistory extends MessageHistory
    {
        private final IMessage mDuringReattach;
        private boolean mArmed;

        private ReattachRaceMessageHistory(IMessage initial, IMessage duringReattach)
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
        public List<IMessage> getItems()
        {
            List<IMessage> snapshot = super.getItems();

            if(mArmed)
            {
                mArmed = false;
                receive(mDuringReattach);
            }

            return snapshot;
        }
    }

}
