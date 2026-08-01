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
import io.github.dsheirer.controller.channel.Channel;
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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import net.miginfocom.swing.MigLayout;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.JTable;
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

    private transient JTable mTable;
    private transient JTableColumnWidthMonitor mTableColumnWidthMonitor;
    private transient DecodeEventModel mEventModel = new DecodeEventModel();
    private transient DecodeEventHistory mCurrentEventHistory;
    private transient JScrollPane mEmptyScroller;
    private transient IconModel mIconModel;
    private transient AliasModel mAliasModel;
    private transient UserPreferences mUserPreferences;
    private transient TimestampCellRenderer mTimestampCellRenderer;
    private transient FilterSet<IDecodeEvent> mFilterSet = new DecodeEventFilterSet();
    private transient TableRowSorter<TableModel> mTableRowSorter;
    private transient HistoryManagementPanel<IDecodeEvent> mHistoryManagementPanel;
    private transient long mSelectedFrequency;
    private transient Integer mSelectedTimeslot;
    private transient Channel mSelectedSiteOwner;
    private transient boolean mSiteEventSelection;


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
            this::restoreTablePresentation);
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
    }

    public void dispose()
    {
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
            ProcessingChain processingChain = context != null ? context.eventProcessingChain() : null;
            boolean clearRequested = context == null || context.clearRequested();
            long selectedFrequency = clearRequested ? 0 : context.frequency();
            Integer selectedTimeslot = clearRequested ? null : context.timeslot();
            boolean siteEventSelection = isSiteEventSelection(context);
            Channel siteOwner = siteEventSelection ? context.ownerChannel() : null;
            boolean selectionChanged = selectionChanged(selectedFrequency, selectedTimeslot, siteEventSelection,
                siteOwner);

            mSelectedFrequency = selectedFrequency;
            mSelectedTimeslot = selectedTimeslot;
            mSelectedSiteOwner = siteOwner;
            mSiteEventSelection = siteEventSelection;

            if(clearRequested)
            {
                detachEventHistory();
                mEventModel.clearAndSet(Collections.emptyList());
                mHistoryManagementPanel.setEnabled(false);
            }
            else if(processingChain != null)
            {
                DecodeEventHistory eventHistory = processingChain.getDecodeEventHistory();

                if(mCurrentEventHistory != eventHistory)
                {
                    detachEventHistory();
                    mCurrentEventHistory = eventHistory;
                    mCurrentEventHistory.addListener(mEventModel);
                }

                List<IDecodeEvent> selectedEvents = mCurrentEventHistory.getItems().stream()
                    .filter(this::matchesSelectedFrequency)
                    .toList();

                if(selectionChanged)
                {
                    mEventModel.clearAndSet(selectedEvents);
                }
                else
                {
                    selectedEvents.forEach(mEventModel::add);
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
                    mEventModel.clearAndSet(Collections.emptyList());
                }

                mHistoryManagementPanel.setEnabled(siteEventSelection);
            }
        });
    }

    private void detachEventHistory()
    {
        if(mCurrentEventHistory != null)
        {
            mCurrentEventHistory.removeListener(mEventModel);
        }

        mCurrentEventHistory = null;
    }

    private boolean selectionChanged(long frequency, Integer timeslot, boolean siteEventSelection, Channel siteOwner)
    {
        return logicalSelectionChanged(mSelectedFrequency, mSelectedTimeslot, mSiteEventSelection,
            mSelectedSiteOwner, frequency, timeslot, siteEventSelection, siteOwner);
    }

    static boolean logicalSelectionChanged(long previousFrequency, Integer previousTimeslot,
                                           boolean previousSiteSelection, Channel previousSiteOwner,
                                           long frequency, Integer timeslot, boolean siteEventSelection,
                                           Channel siteOwner)
    {
        if(previousSiteSelection || siteEventSelection)
        {
            return previousSiteSelection != siteEventSelection || previousSiteOwner != siteOwner;
        }

        if(previousFrequency != frequency)
        {
            return true;
        }

        if(previousTimeslot == null)
        {
            return timeslot != null;
        }

        return !previousTimeslot.equals(timeslot);
    }

    private boolean matchesSelectedFrequency(IDecodeEvent event)
    {
        return matchesSelectedFrequency(event, mSelectedFrequency, mSiteEventSelection);
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

    static boolean matchesSelectedFrequency(IDecodeEvent event, long selectedFrequency, boolean siteEventSelection)
    {
        if(siteEventSelection || selectedFrequency <= 0)
        {
            return true;
        }

        IChannelDescriptor channelDescriptor = event != null ? event.getChannelDescriptor() : null;
        return channelDescriptor != null && channelDescriptor.getDownlinkFrequency() == selectedFrequency;
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
