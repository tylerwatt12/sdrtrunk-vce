/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import com.google.common.base.Joiner;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.filter.FilterSet;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.event.filter.DecodeEventFilterSet;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import io.github.dsheirer.sample.Listener;
import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.miginfocom.swing.MigLayout;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.RowFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.event.TableModelEvent;

public class DecodeEventPanel extends JPanel implements Listener<SelectedFrequencyContext>
{
    private static final long serialVersionUID = 1L;
    private static final String TABLE_PREFERENCE_KEY = "decode.event.panel";
    private static final int[] DEFAULT_COLUMN_WIDTHS = {146, 79, 111, 99, 82, 82, 94, 62, 98, 240};
    private static final int[] MINIMUM_COLUMN_WIDTHS = {146, 79, 111, 99, 82, 82, 94, 62, 98, 100};
    private static final int MAX_OBSERVED_EVENT_IDENTITIES = 4096;
    private static final int LIVE_EVENT_HANDOFF_CAPACITY = 256;
    private static final int LIVE_EVENT_DRAIN_INTERVAL_MILLISECONDS = 25;

    private transient JTable mTable;
    private transient JTableColumnWidthMonitor mTableColumnWidthMonitor;
    private transient DecodeEventModel mEventModel = new DecodeEventModel();
    private transient DecodeEventHistory mCurrentEventHistory;
    private transient Listener<IDecodeEvent> mCurrentEventHistoryListener;
    private transient long mEventHistoryAttachmentGeneration;
    private transient BoundedSwingHistoryHandoff<DecodeEventHistory,IDecodeEvent> mLiveEventHandoff =
        new BoundedSwingHistoryHandoff<>(LIVE_EVENT_HANDOFF_CAPACITY, this::processLiveEvent);
    private transient Timer mLiveEventDrainTimer;
    private transient Set<IDecodeEvent> mObservedEvents = Collections.newSetFromMap(new IdentityHashMap<>());
    private transient ArrayDeque<IDecodeEvent> mObservedEventOrder = new ArrayDeque<>();
    private transient JScrollPane mEmptyScroller;
    private transient IconModel mIconModel;
    private transient AliasModel mAliasModel;
    private transient UserPreferences mUserPreferences;
    private transient TimestampCellRenderer mTimestampCellRenderer;
    private transient FilterSet<IDecodeEvent> mFilterSet = new DecodeEventFilterSet();
    private transient TableRowSorter<TableModel> mTableRowSorter;
    private transient HistoryManagementPanel<IDecodeEvent> mHistoryManagementPanel;
    private transient SelectedFrequencyContext mSelectedContext;
    private transient boolean mActive = true;


    /**
     * View for call event table
     * @param iconModel to display alias icons in table rows
     */
    public DecodeEventPanel(IconModel iconModel, UserPreferences userPreferences, AliasModel aliasModel)
    {
        MyEventBus.getGlobalEventBus().register(this);

        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]", "[][grow,fill]"));
        mIconModel = iconModel;
        mAliasModel = aliasModel;
        mUserPreferences = userPreferences;
        mTimestampCellRenderer = new TimestampCellRenderer();
        mTable = new JTable(mEventModel);
        mTableRowSorter = new TableRowSorter<>(mEventModel);
        mTableRowSorter.setRowFilter(new EventRowFilter());
        mTable.setRowSorter(mTableRowSorter);
        mTableColumnWidthMonitor = new JTableColumnWidthMonitor(mUserPreferences, mTable, TABLE_PREFERENCE_KEY,
            MINIMUM_COLUMN_WIDTHS, DEFAULT_COLUMN_WIDTHS, JTable.AUTO_RESIZE_LAST_COLUMN);
        updateCellRenderers();
        mHistoryManagementPanel = new HistoryManagementPanel<>(mEventModel, "Event Filter Editor",
            this::restoreTablePresentation, this::clearEventHistory);
        mHistoryManagementPanel.updateFilterSet(mFilterSet);
        add(mHistoryManagementPanel, "span,growx");
        mEmptyScroller = new JScrollPane(mTable);
        add(mEmptyScroller);

        //Register filter change listener to refresh the table any time the event filters are changed.
        mFilterSet.register(() -> mEventModel.fireTableDataChanged());
        mEventModel.addTableModelListener(event -> {
            if(event.getFirstRow() == TableModelEvent.HEADER_ROW)
            {
                EventQueue.invokeLater(this::restoreTablePresentation);
            }
        });
        mLiveEventDrainTimer = new Timer(LIVE_EVENT_DRAIN_INTERVAL_MILLISECONDS,
            event -> drainLiveEvents());
        mLiveEventDrainTimer.setCoalesce(true);
        mLiveEventDrainTimer.start();
    }

    public void dispose()
    {
        suspend();

        if(mTableColumnWidthMonitor != null)
        {
            mTableColumnWidthMonitor.dispose();
        }

        mEventModel.dispose();

        MyEventBus.getGlobalEventBus().unregister(this);
    }

    /**
     * Receives preference update notifications via the event bus
     * @param preferenceType that was updated
     */
    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.DECODE_EVENT || preferenceType == PreferenceType.TALKGROUP_FORMAT)
        {
            EventQueue.invokeLater(() -> mTimestampCellRenderer.updatePreferences());
        }
    }

    private void updateCellRenderers()
    {
        mTable.getColumnModel().getColumn(DecodeEventModel.COLUMN_TIME).setCellRenderer(mTimestampCellRenderer);
        mTable.getColumnModel().getColumn(DecodeEventModel.COLUMN_DURATION).setCellRenderer(new DurationCellRenderer());
        mTable.getColumnModel().getColumn(DecodeEventModel.COLUMN_FROM_ID).setCellRenderer(new IdentifierCellRenderer(Role.FROM));
        mTable.getColumnModel().getColumn(DecodeEventModel.COLUMN_FROM_ALIAS).setCellRenderer(new AliasedIdentifierCellRenderer(Role.FROM));
        mTable.getColumnModel().getColumn(DecodeEventModel.COLUMN_TO_ID).setCellRenderer(new IdentifierCellRenderer(Role.TO));
        mTable.getColumnModel().getColumn(DecodeEventModel.COLUMN_TO_ALIAS).setCellRenderer(new AliasedIdentifierCellRenderer(Role.TO));
        mTable.getColumnModel().getColumn(DecodeEventModel.COLUMN_CHANNEL).setCellRenderer(new ChannelDescriptorCellRenderer());
        mTable.getColumnModel().getColumn(DecodeEventModel.COLUMN_FREQUENCY).setCellRenderer(new FrequencyCellRenderer());
    }

    /**
     * Restores the table presentation that JTable can discard when its model structure is refreshed.
     */
    void restoreTablePresentation()
    {
        if(mTable.getColumnModel().getColumnCount() != mEventModel.getColumnCount())
        {
            return;
        }

        if(mTable.getRowSorter() != mTableRowSorter)
        {
            mTable.setRowSorter(mTableRowSorter);
        }

        mTableRowSorter.setRowFilter(new EventRowFilter());
        updateCellRenderers();
    }

    @Override
    public void receive(final SelectedFrequencyContext context)
    {
        EventQueue.invokeLater(() -> {
            if(!mActive)
            {
                return;
            }

            boolean clearRequested = context == null || context.clearRequested();
            boolean selectionChanged = clearRequested || mSelectedContext == null ||
                !mSelectedContext.hasSameLogicalSelection(context);
            mSelectedContext = clearRequested ? null : context;
            ProcessingChain processingChain = clearRequested ? null : context.processingChain();

            if(clearRequested)
            {
                detachEventHistory();
                clearObservedEvents();
                mEventModel.clearAndSet(Collections.emptyList());
                mHistoryManagementPanel.setEnabled(false);
            }
            else if(processingChain != null)
            {
                DecodeEventHistory eventHistory = processingChain.getDecodeEventHistory();
                boolean historyChanged = mCurrentEventHistory != eventHistory;

                if(!selectionChanged && !historyChanged)
                {
                    mHistoryManagementPanel.setEnabled(true);
                    return;
                }

                if(historyChanged)
                {
                    detachEventHistory();
                    attachEventHistory(eventHistory);
                }

                List<IDecodeEvent> historyItems = mCurrentEventHistory.getItems();

                if(selectionChanged)
                {
                    clearObservedEvents();
                    historyItems.forEach(this::markObservedEvent);
                    mEventModel.clearAndSet(historyItems.stream()
                        .filter(this::matchesSelectedFrequency)
                        .toList());
                }
                else
                {
                    //The source history is smaller than the configurable UI history.  Merge only source objects that
                    //arrived while detached so retained UI rows are not discarded and a manual Clear is respected.
                    List<IDecodeEvent> missedEvents = historyItems.stream()
                        .filter(event -> !mObservedEvents.contains(event))
                        .filter(this::matchesSelectedFrequency)
                        .toList();
                    historyItems.forEach(this::markObservedEvent);
                    missedEvents.forEach(mEventModel::add);
                }

                mHistoryManagementPanel.setEnabled(true);
            }
            else
            {
                //The old chain cannot produce more events.  Keep the displayed site history during a temporary
                //receiver/control-channel gap and bind to the replacement owner chain when it starts.
                detachEventHistory();

                if(selectionChanged)
                {
                    clearObservedEvents();
                    mEventModel.clearAndSet(Collections.emptyList());
                }

                mHistoryManagementPanel.setEnabled(context.isSiteSelection());
            }
        });
    }

    /**
     * Detaches the live history listener while retaining the bounded model, filter choices, and logical selection.
     */
    public void suspend()
    {
        mActive = false;
        detachEventHistory();
        mLiveEventDrainTimer.stop();
        mLiveEventHandoff.clear();
    }

    /**
     * Reattaches this view to the current selection after it becomes visible again.
     */
    public void resume(SelectedFrequencyContext context)
    {
        mActive = true;
        mLiveEventDrainTimer.start();
        receive(context);
    }

    private void detachEventHistory()
    {
        mEventHistoryAttachmentGeneration++;

        if(mCurrentEventHistory != null)
        {
            mCurrentEventHistory.removeListener(mCurrentEventHistoryListener);
        }

        mCurrentEventHistory = null;
        mCurrentEventHistoryListener = null;
    }

    private void attachEventHistory(DecodeEventHistory eventHistory)
    {
        mCurrentEventHistory = eventHistory;
        DecodeEventHistory attachedHistory = mCurrentEventHistory;
        long attachmentGeneration = ++mEventHistoryAttachmentGeneration;
        mCurrentEventHistoryListener = event ->
            mLiveEventHandoff.offer(attachedHistory, event, attachmentGeneration);
        mCurrentEventHistory.addListener(mCurrentEventHistoryListener);
    }

    /**
     * Establishes a source watermark at the user's Clear action.  The listener generation cut rejects in-flight old
     * callbacks, while the listener-before-snapshot ordering preserves genuinely new events.
     */
    private void clearEventHistory()
    {
        DecodeEventHistory eventHistory = mCurrentEventHistory;
        detachEventHistory();
        mLiveEventHandoff.clear();

        if(mActive && eventHistory != null)
        {
            attachEventHistory(eventHistory);
            eventHistory.getItems().forEach(this::markObservedEvent);
        }

        mEventModel.clear();
    }

    /**
     * Drains the bounded producer handoff.  Swing Timer and deterministic tests invoke this only on the EDT.
     */
    void drainLiveEvents()
    {
        if(!EventQueue.isDispatchThread())
        {
            throw new IllegalStateException("Live event handoff must drain on the Swing event thread");
        }

        mLiveEventHandoff.drain();
    }

    private void clearObservedEvents()
    {
        mObservedEvents.clear();
        mObservedEventOrder.clear();
    }

    private void processLiveEvent(DecodeEventHistory history, IDecodeEvent event, long attachmentGeneration)
    {
        if(mActive && mCurrentEventHistory == history &&
            mEventHistoryAttachmentGeneration == attachmentGeneration)
        {
            markObservedEvent(event);

            if(matchesSelectedFrequency(event))
            {
                mEventModel.add(event);
            }
        }
    }

    /**
     * Remembers source-event identity independently of visible model contents.  This makes a user-requested Clear a
     * durable watermark while keeping the state bounded above the largest configurable UI history plus source tail.
     */
    private boolean markObservedEvent(IDecodeEvent event)
    {
        if(event == null)
        {
            return false;
        }

        boolean newlyObserved = mObservedEvents.add(event);

        if(!newlyObserved)
        {
            mObservedEventOrder.removeIf(observed -> observed == event);
        }

        mObservedEventOrder.addLast(event);

        while(mObservedEventOrder.size() > MAX_OBSERVED_EVENT_IDENTITIES)
        {
            mObservedEvents.remove(mObservedEventOrder.removeFirst());
        }

        return newlyObserved;
    }

    private boolean matchesSelectedFrequency(IDecodeEvent event)
    {
        return matchesSelectedFrequency(event, mSelectedContext != null ? mSelectedContext.frequency() : 0,
            mSelectedContext != null ? mSelectedContext.timeslot() : null,
            mSelectedContext != null && mSelectedContext.isSiteSelection());
    }

    /**
     * Control rows represent the trunked site in the Events view.  P25 channel-grant events are produced by the
     * control processing chain but carry the granted traffic frequency, so exact-frequency filtering would otherwise
     * hide every call when the control row is selected.
     */
    static boolean isSiteEventSelection(SelectedFrequencyContext context)
    {
        return context != null && context.isSiteSelection();
    }

    static boolean matchesSelectedFrequency(IDecodeEvent event, long selectedFrequency, Integer selectedTimeslot,
                                            boolean siteEventSelection)
    {
        if(siteEventSelection || selectedFrequency <= 0)
        {
            return true;
        }

        IChannelDescriptor channelDescriptor = event != null ? event.getChannelDescriptor() : null;
        boolean frequencyMatches = channelDescriptor != null &&
            channelDescriptor.getDownlinkFrequency() == selectedFrequency;
        return frequencyMatches && (selectedTimeslot == null ||
            (event.hasTimeslot() && event.getTimeslot() == selectedTimeslot));
    }

    /**
     * Custom cell renderer for displaying identifiers from an identifier collection
     */
    public class IdentifierCellRenderer extends DefaultTableCellRenderer
    {
        protected Role mRole;

        /**
         * Constructs an instance of the cell renderer.
         *
         * @param role of the identifier
         */
        public IdentifierCellRenderer(Role role)
        {
            mRole = role;
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof IdentifierCollection identifierCollection)
            {
                List<Identifier> identifiers = identifierCollection.getIdentifiers(mRole);
                label.setText(format(identifiers));
            }
            else
            {
                label.setText(null);
            }

            return label;
        }

        /**
         * Formats a list of identifiers as a comma separated list of values
         * @param identifiers to format
         * @return formatted list or null
         */
        protected String format(List<Identifier> identifiers)
        {
            if(identifiers == null || identifiers.isEmpty())
            {
                return null;
            }

            StringBuilder sb = new StringBuilder();

            for(Identifier identifier: identifiers)
            {
                if(!sb.isEmpty())
                {
                    sb.append(",");
                }

                if(identifier.getForm() == Form.TALKGROUP || identifier.getForm() == Form.RADIO || identifier.getForm() == Form.PATCH_GROUP)
                {
                    sb.append(mUserPreferences.getTalkgroupFormatPreference().format(identifier));
                }
                else
                {
                    sb.append(identifier);
                }

            }

            return sb.toString();
        }
    }

    /**
     * Cell renderer for identifier aliases
     */
    public class AliasedIdentifierCellRenderer extends DefaultTableCellRenderer
    {
        private Role mRole;

        /**
         * Constructs an instance of the cell renderer.
         *
         * @param role of the identifier
         */
        public AliasedIdentifierCellRenderer(Role role)
        {
            mRole = role;
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            Color color = mTable.getForeground();
            ImageIcon icon = null;
            String text = null;

            if(value instanceof IdentifierCollection identifierCollection)
            {
                List<Identifier> identifiers = identifierCollection.getIdentifiers(mRole);

                if(identifiers != null && !identifiers.isEmpty())
                {
                    AliasList aliasList = mAliasModel.getAliasList(identifierCollection);

                    if(aliasList != null)
                    {
                        StringBuilder sb = new StringBuilder();

                        for(Identifier identifier: identifiers)
                        {
                            List<Alias> aliases = aliasList.getAliases(identifier);

                            if(!aliases.isEmpty())
                            {
                                if(!sb.isEmpty())
                                {
                                    sb.append(",");
                                }
                                sb.append(Joiner.on(", ").skipNulls().join(aliases));
                                Alias firstAlias = aliases.get(0);

                                if(firstAlias.getColor() != 0)
                                {
                                    color = firstAlias.getDisplayColor();
                                }

                                icon = mIconModel.getIcon(firstAlias.getIconName(), IconModel.DEFAULT_ICON_SIZE);
                            }
                        }

                        text = sb.toString();
                    }
                }
            }

            label.setText(text);
            label.setForeground(color);
            label.setIcon(icon);

            return label;
        }
    }

    public class TimestampCellRenderer extends DefaultTableCellRenderer
    {
        private SimpleDateFormat mTimestampFormatter;

        public TimestampCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
            updatePreferences();
        }

        public void updatePreferences()
        {
            mTimestampFormatter = mUserPreferences.getDecodeEventPreference().getTimestampFormat().getFormatter();
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof Long)
            {
                label.setText(mTimestampFormatter.format(new Date((long)value)));
            }
            else
            {
                label.setText(null);
            }

            return label;
        }
    }

    public class DurationCellRenderer extends DefaultTableCellRenderer
    {
        private DecimalFormat mDecimalFormat = new DecimalFormat("0.0");

        public DurationCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String formatted = null;

            if(value instanceof Long)
            {
                long duration = (long)value;

                if(duration > 0)
                {
                    formatted = mDecimalFormat.format(duration / 1e3d);
                }
            }

            label.setText(formatted);

            return label;
        }
    }

    /**
     * Frequency value cell renderer
     */
    public class FrequencyCellRenderer extends DefaultTableCellRenderer
    {
        private DecimalFormat mFrequencyFormatter = new DecimalFormat("0.00000");

        public FrequencyCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String formatted = null;

            if(value instanceof IChannelDescriptor channelDescriptor)
            {
                long frequency = channelDescriptor.getDownlinkFrequency();

                if(frequency > 0)
                {
                    formatted = mFrequencyFormatter.format(frequency / 1e6d);
                }
            }

            label.setText(formatted);

            return label;
        }
    }

    /**
     * Channel descriptor value cell renderer
     */
    public class ChannelDescriptorCellRenderer extends DefaultTableCellRenderer
    {
        public ChannelDescriptorCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }
    }

    /**
     * Row filter for decode events
     */
    public class EventRowFilter extends RowFilter<TableModel, Integer>
    {
        @Override
        public boolean include(Entry<? extends TableModel, ? extends Integer> entry)
        {
            if(entry.getModel() instanceof DecodeEventModel model)
            {
                Integer identifier = entry.getIdentifier();

                if(identifier == null)
                {
                    return false;
                }

                IDecodeEvent event = model.getItem(identifier);

                if(event != null)
                {
                    return matchesSelectedFrequency(event) && mFilterSet.canProcess(event) && mFilterSet.passes(event);
                }
            }

            return false;
        }
    }
}
