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
import java.awt.EventQueue;
import java.util.List;
import net.miginfocom.swing.MigLayout;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.Timer;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

/**
 * Panel to display decoded messages/activity.
 */
public class MessageActivityPanel extends JPanel implements Listener<SelectedFrequencyContext>
{
    private static final long serialVersionUID = 1L;
    private static final String TABLE_PREFERENCE_KEY = "message.activity.panel";
    private static final int[] DEFAULT_COLUMN_WIDTHS = {151, 73, 76, 793};
    private static final int[] MINIMUM_COLUMN_WIDTHS = {151, 73, 76, 100};
    private static final int LIVE_MESSAGE_HANDOFF_CAPACITY = 256;
    private static final int LIVE_MESSAGE_DRAIN_INTERVAL_MILLISECONDS = 25;
    private transient MessageActivityModel mMessageModel = new MessageActivityModel();
    private transient ProcessingChain mCurrentProcessingChain;
    private transient MessageHistory mCurrentMessageHistory;
    private transient Listener<IMessage> mCurrentMessageHistoryListener;
    private transient long mMessageHistoryAttachmentGeneration;
    private transient BoundedSwingHistoryHandoff<MessageHistory,IMessage> mLiveMessageHandoff =
        new BoundedSwingHistoryHandoff<>(LIVE_MESSAGE_HANDOFF_CAPACITY, this::processLiveMessage);
    private transient Timer mLiveMessageDrainTimer;
    private transient JTable mTable = new JTable(mMessageModel);
    private transient TableRowSorter<TableModel> mTableRowSorter;
    private transient JTableColumnWidthMonitor mTableColumnWidthMonitor;
    private transient UserPreferences mUserPreferences;
    private transient FilterSet<IMessage> mMessageFilterSet;
    private transient FilterElementStateCache mFilterElementStateCache = new FilterElementStateCache();
    private transient HistoryManagementPanel<IMessage> mHistoryManagementPanel;
    private transient SelectedFrequencyContext mSelectedContext;
    private transient SelectedFrequencyContext mRetainedContext;
    private transient boolean mActive;

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
        mTableColumnWidthMonitor = new JTableColumnWidthMonitor(mUserPreferences, mTable, TABLE_PREFERENCE_KEY,
            MINIMUM_COLUMN_WIDTHS, DEFAULT_COLUMN_WIDTHS, JTable.AUTO_RESIZE_LAST_COLUMN);
        setLayout(new MigLayout("insets 0 0 0 0", "[][grow,fill]", "[]0[grow,fill]"));
        mHistoryManagementPanel = new HistoryManagementPanel<>(mMessageModel, "Message Filter Editor", null,
            this::clearMessageHistory);
        add(mHistoryManagementPanel, "span,growx");
        add(new JScrollPane(mTable), "span,grow");
        mLiveMessageDrainTimer = new Timer(LIVE_MESSAGE_DRAIN_INTERVAL_MILLISECONDS,
            event -> drainLiveMessages());
        mLiveMessageDrainTimer.setCoalesce(true);
    }

    /**
     * Updates the message activity model for the selected site or exact-channel context.
     */
    @Override
    public void receive(SelectedFrequencyContext selection)
    {
        mRetainedContext = selection;

        if(!mActive)
        {
            return;
        }

        applySelection(selection);
    }

    private void applySelection(SelectedFrequencyContext selection)
    {
        if(selection == null || selection.clearRequested())
        {
            clearSelection();
            return;
        }

        boolean selectionChanged = mSelectedContext == null ||
            !mSelectedContext.hasSameLogicalSelection(selection);
        mSelectedContext = selection;
        updateProcessingChain(selection.processingChain(), selectionChanged);
    }

    private void clearSelection()
    {
        detachMessageHistory();
        unregisterFilterSet();
        mSelectedContext = null;
        mMessageModel.resetSelectionHistory();

        HistoryManagementPanel<IMessage> historyManagementPanel = mHistoryManagementPanel;

        if(historyManagementPanel != null)
        {
            historyManagementPanel.setEnabled(false);
        }
    }

    /**
     * Detaches the live history listener while retaining the bounded model, filter choices, and logical selection.
     */
    public void suspend()
    {
        mActive = false;
        detachMessageHistory();
        mLiveMessageDrainTimer.stop();
        mLiveMessageHandoff.clear();
    }

    /**
     * Reattaches this view to the current selection after it becomes visible again.
     */
    public void resume(SelectedFrequencyContext selection)
    {
        mRetainedContext = selection;
        mActive = true;
        mLiveMessageDrainTimer.start();
        applySelection(selection);
    }

    private void updateProcessingChain(ProcessingChain processingChain, boolean selectionChanged)
    {
        HistoryManagementPanel<IMessage> historyManagementPanel = mHistoryManagementPanel;

        if(selectionChanged)
        {
            detachMessageHistory();
            unregisterFilterSet();
            mMessageModel.resetSelectionHistory();
        }
        else if(processingChain == mCurrentProcessingChain)
        {
            return;
        }

        if(processingChain == null)
        {
            detachMessageHistory();

            if(selectionChanged && historyManagementPanel != null)
            {
                historyManagementPanel.setEnabled(false);
            }

            return;
        }

        detachMessageHistory();
        mCurrentProcessingChain = processingChain;
        mCurrentMessageHistory = processingChain.getMessageHistory();

        if(mMessageFilterSet == null)
        {
            mMessageFilterSet = DecoderFactory.getMessageFilters(processingChain.getModules());
            mFilterElementStateCache.restore(mMessageFilterSet);
            //Register filter change listener to refresh the table any time the message filters are changed.
            mMessageFilterSet.register(() -> mMessageModel.fireTableDataChanged());

            if(historyManagementPanel != null)
            {
                historyManagementPanel.updateFilterSet(mMessageFilterSet);
            }
        }

        if(historyManagementPanel != null)
        {
            historyManagementPanel.setEnabled(true);
        }

        MessageHistory attachedHistory = mCurrentMessageHistory;
        attachMessageHistoryListener(attachedHistory);
        long attachmentGeneration = mMessageHistoryAttachmentGeneration;
        List<IMessage> snapshot = mCurrentMessageHistory.getItems();
        Runnable addSnapshot = () -> {
            if(mActive && mCurrentMessageHistory == attachedHistory &&
                mMessageHistoryAttachmentGeneration == attachmentGeneration)
            {
                mMessageModel.addMessages(snapshot);
            }
        };

        if(EventQueue.isDispatchThread())
        {
            addSnapshot.run();
        }
        else
        {
            EventQueue.invokeLater(addSnapshot);
        }
    }

    private void detachMessageHistory()
    {
        mMessageHistoryAttachmentGeneration++;

        if(mCurrentMessageHistory != null)
        {
            mCurrentMessageHistory.removeListener(mCurrentMessageHistoryListener);
        }

        mCurrentMessageHistory = null;
        mCurrentMessageHistoryListener = null;
        mCurrentProcessingChain = null;
    }

    private void attachMessageHistoryListener(MessageHistory messageHistory)
    {
        long attachmentGeneration = ++mMessageHistoryAttachmentGeneration;
        mCurrentMessageHistoryListener = message ->
            mLiveMessageHandoff.offer(messageHistory, message, attachmentGeneration);
        messageHistory.addListener(mCurrentMessageHistoryListener);
    }

    /**
     * Establishes a source watermark at the user's Clear action so queued or snapshotted pre-Clear messages cannot
     * reappear, while the replacement listener continues to accept messages arriving after the snapshot.
     */
    private void clearMessageHistory()
    {
        ProcessingChain processingChain = mCurrentProcessingChain;
        MessageHistory messageHistory = mCurrentMessageHistory;
        detachMessageHistory();
        mLiveMessageHandoff.clear();

        if(mActive && messageHistory != null)
        {
            mCurrentProcessingChain = processingChain;
            mCurrentMessageHistory = messageHistory;
            attachMessageHistoryListener(messageHistory);
            mMessageModel.markObservedMessages(messageHistory.getItems());
        }

        mMessageModel.clear();
    }

    /**
     * Drains the bounded producer handoff.  Swing Timer and deterministic tests invoke this only on the EDT.
     */
    void drainLiveMessages()
    {
        if(!EventQueue.isDispatchThread())
        {
            throw new IllegalStateException("Live message handoff must drain on the Swing event thread");
        }

        mLiveMessageHandoff.drain();
    }

    private void processLiveMessage(MessageHistory history, IMessage message, long attachmentGeneration)
    {
        if(mActive && mCurrentMessageHistory == history &&
            mMessageHistoryAttachmentGeneration == attachmentGeneration)
        {
            mMessageModel.addMessage(message);
        }
    }

    private void unregisterFilterSet()
    {
        //Unregister from changes made to the filter set
        if(mMessageFilterSet != null)
        {
            mFilterElementStateCache.capture(mMessageFilterSet);
            mMessageFilterSet.register(null);
        }

        mMessageFilterSet = null;
    }

    public void dispose()
    {
        suspend();
        mRetainedContext = null;
        clearSelection();

        if(mTableColumnWidthMonitor != null)
        {
            mTableColumnWidthMonitor.dispose();
            mTableColumnWidthMonitor = null;
        }
    }

    @Override
    public void addNotify()
    {
        super.addNotify();
        resume(mRetainedContext);
    }

    @Override
    public void removeNotify()
    {
        suspend();
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
