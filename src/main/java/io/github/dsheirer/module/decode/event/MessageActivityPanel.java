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

import io.github.dsheirer.channel.metadata.activity.SelectedFrequencyContext;
import io.github.dsheirer.filter.FilterSet;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.MessageHistory;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import io.github.dsheirer.sample.Listener;
import net.miginfocom.swing.MigLayout;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

/**
 * Panel to display decoded messages/activity.
 */
public class MessageActivityPanel extends JPanel implements Listener<SelectedFrequencyContext>
{
    private static final long serialVersionUID = 1L;
    private static final String TABLE_PREFERENCE_KEY = "message.activity.panel";
    private transient MessageActivityModel mMessageModel = new MessageActivityModel();
    private transient ProcessingChain mCurrentProcessingChain;
    private transient MessageHistory mCurrentMessageHistory;
    private transient JTable mTable = new JTable(mMessageModel);
    private transient TableRowSorter<TableModel> mTableRowSorter;
    private transient JTableColumnWidthMonitor mTableColumnWidthMonitor;
    private transient UserPreferences mUserPreferences;
    private transient FilterSet<IMessage> mMessageFilterSet;
    private transient HistoryManagementPanel<IMessage> mHistoryManagementPanel;
    private transient long mSelectedFrequency;
    private transient Integer mSelectedTimeslot;

    /**
     * Constructs an instance
     * @param userPreferences
     */
    public MessageActivityPanel(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        mTableRowSorter = new TableRowSorter<>(mMessageModel);
        mTableRowSorter.setRowFilter(new MessageRowFilter());
        mTable.setRowSorter(mTableRowSorter);
        mTableColumnWidthMonitor = new JTableColumnWidthMonitor(mUserPreferences, mTable, TABLE_PREFERENCE_KEY);
        setLayout(new MigLayout("insets 0 0 0 0", "[][grow,fill]", "[]0[grow,fill]"));
        mHistoryManagementPanel = new HistoryManagementPanel<>(mMessageModel, "Message Filter Editor");
        add(mHistoryManagementPanel, "span,growx");
        add(new JScrollPane(mTable), "span,grow");
    }

    /**
     * Updates the message activity model with message history from the specified processing chain
     */
    public void receive(ProcessingChain processingChain)
    {
        updateProcessingChain(processingChain, true, true);
    }

    /**
     * Updates the message activity model for the selected exact-frequency context.
     */
    @Override
    public void receive(SelectedFrequencyContext selection)
    {
        if(selection == null || selection.clearRequested())
        {
            clearSelection();
            return;
        }

        boolean selectionChanged = selectionChanged(selection.frequency(), selection.timeslot());
        mSelectedFrequency = selection.frequency();
        mSelectedTimeslot = selection.timeslot();
        updateProcessingChain(selection.processingChain(), selectionChanged, true);
    }

    private void clearSelection()
    {
        detachMessageHistory();
        unregisterFilterSet();
        mSelectedFrequency = 0;
        mSelectedTimeslot = null;
        mMessageModel.clear();

        HistoryManagementPanel<IMessage> historyManagementPanel = mHistoryManagementPanel;

        if(historyManagementPanel != null)
        {
            historyManagementPanel.setEnabled(false);
        }
    }

    private boolean selectionChanged(long frequency, Integer timeslot)
    {
        if(mSelectedFrequency != frequency)
        {
            return true;
        }

        if(mSelectedTimeslot == null)
        {
            return timeslot != null;
        }

        return !mSelectedTimeslot.equals(timeslot);
    }

    private void updateProcessingChain(ProcessingChain processingChain, boolean selectionChanged, boolean preloadHistory)
    {
        HistoryManagementPanel<IMessage> historyManagementPanel = mHistoryManagementPanel;

        if(processingChain == null)
        {
            detachMessageHistory();

            if(selectionChanged)
            {
                unregisterFilterSet();
                mMessageModel.clear();

                if(historyManagementPanel != null)
                {
                    historyManagementPanel.setEnabled(false);
                }
            }

            return;
        }

        if(processingChain == mCurrentProcessingChain && !selectionChanged)
        {
            return;
        }

        detachMessageHistory();
        unregisterFilterSet();

        if(selectionChanged)
        {
            mMessageModel.clear();
        }

        mCurrentProcessingChain = processingChain;
        mCurrentMessageHistory = processingChain.getMessageHistory();
        mMessageFilterSet = DecoderFactory.getMessageFilters(processingChain.getModules());
        //Register filter change listener to refresh the table any time the event filters are changed.
        mMessageFilterSet.register(() -> mMessageModel.fireTableDataChanged());

        if(historyManagementPanel != null)
        {
            historyManagementPanel.updateFilterSet(mMessageFilterSet);
            historyManagementPanel.setEnabled(true);
        }

        if(preloadHistory)
        {
            mMessageModel.addMessages(mCurrentMessageHistory.getItems());
        }

        mCurrentMessageHistory.addListener(mMessageModel);
    }

    private void detachMessageHistory()
    {
        if(mCurrentMessageHistory != null)
        {
            mCurrentMessageHistory.removeListener(mMessageModel);
        }

        mCurrentMessageHistory = null;
        mCurrentProcessingChain = null;
    }

    private void unregisterFilterSet()
    {
        //Unregister from changes made to the filter set
        if(mMessageFilterSet != null)
        {
            mMessageFilterSet.register(null);
        }

        mMessageFilterSet = null;
    }

    public void dispose()
    {
        clearSelection();

        if(mTableColumnWidthMonitor != null)
        {
            mTableColumnWidthMonitor.dispose();
            mTableColumnWidthMonitor = null;
        }
    }

    @Override
    public void removeNotify()
    {
        dispose();

        super.removeNotify();
    }

    /**
     * Row visibility filter for messages
     */
    public class MessageRowFilter extends RowFilter<TableModel, Integer>
    {
        @Override
        public boolean include(Entry<? extends TableModel, ? extends Integer> entry)
        {
            if(entry.getModel() instanceof MessageActivityModel model)
            {
                Integer identifier = entry.getIdentifier();

                if(identifier == null)
                {
                    return false;
                }

                MessageItem item = model.getItem(identifier);

                if(item != null && item.getMessage() != null)
                {
                    IMessage message = item.getMessage();
                    return mMessageFilterSet == null ||
                        (mMessageFilterSet.canProcess(message) && mMessageFilterSet.passes(message));
                }
            }

            return false;
        }
    }
}
