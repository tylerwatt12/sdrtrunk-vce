/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.sample.Listener;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.table.AbstractTableModel;

/**
 * Swing-only adapter for an immutable {@link ChannelActivityTableView}.  Core activity state never calls Swing and a
 * blocked event dispatch thread retains only the latest pending view.
 */
public class ChannelActivityTableModel extends AbstractTableModel implements AutoCloseable
{
    public static final int COLUMN_STATUS = 0;
    public static final int COLUMN_TAGS = 1;
    public static final int COLUMN_LCN = 2;
    public static final int COLUMN_FREQUENCY = 3;
    public static final int COLUMN_CALLSIGN = 4;
    public static final int COLUMN_SIGNAL = 5;
    public static final int COLUMN_DECODE_HEALTH = 6;
    public static final int COLUMN_SOURCE_ALIAS = 7;
    public static final int COLUMN_SOURCE = 8;
    public static final int COLUMN_TARGET_ALIAS = 9;
    public static final int COLUMN_TARGET = 10;
    public static final int COLUMN_DECODER = 11;

    private static final String[] COLUMNS = {
        "Status", "Tags", "LCN", "Frequency", "Callsign", "Signal", "Decode", "Source Alias", "Source",
        "Target Alias", "Target", "Decoder"
    };

    private final ChannelActivityTableState mState;
    private final Listener<ChannelActivityTableView> mViewListener = this::receive;
    private final AtomicReference<ChannelActivityTableView> mPendingView = new AtomicReference<>();
    private final AtomicBoolean mUpdateScheduled = new AtomicBoolean();
    private final AtomicBoolean mDisposed = new AtomicBoolean();
    private String mTitle;
    private Channel mOwnerChannel;
    private boolean mCloseable;
    private boolean mControlActive;
    private boolean mActivityViewVisible;
    private boolean mPendingFullRefresh;
    private List<ChannelActivityRow> mRows = List.of();
    private Map<String,ChannelActivityRow> mRowsByKey = Map.of();

    public ChannelActivityTableModel(ChannelActivityTableState state)
    {
        if(state == null)
        {
            throw new IllegalArgumentException("Activity table state is required");
        }

        mState = state;
        apply(state.getLatestView(), false);
        state.addViewListener(mViewListener);
    }

    public ChannelActivityTableState getState()
    {
        return mState;
    }

    public String getTitle()
    {
        return mTitle;
    }

    public Channel getOwnerChannel()
    {
        return mOwnerChannel;
    }

    public boolean isCloseable()
    {
        return mCloseable;
    }

    public boolean isControlActive()
    {
        return mControlActive;
    }

    public void setActivityViewVisible(boolean activityViewVisible)
    {
        boolean becameVisible = !mActivityViewVisible && activityViewVisible;
        mActivityViewVisible = activityViewVisible;

        if(becameVisible && mPendingFullRefresh)
        {
            mPendingFullRefresh = false;
            fireTableDataChanged();
        }
    }

    public boolean isActivityViewVisible()
    {
        return mActivityViewVisible;
    }

    public ChannelActivityRow get(String key)
    {
        return mRowsByKey.get(key);
    }

    public List<ChannelActivityRow> getRows()
    {
        return new ArrayList<>(mRows);
    }

    public ChannelActivityRow getRow(int row)
    {
        return row >= 0 && row < mRows.size() ? mRows.get(row) : null;
    }

    public int getRowIndex(String key)
    {
        return mRows.indexOf(get(key));
    }

    public void refreshAllRows()
    {
        if(!mRows.isEmpty())
        {
            if(mActivityViewVisible)
            {
                fireTableRowsUpdated(0, mRows.size() - 1);
            }
            else
            {
                mPendingFullRefresh = true;
            }
        }
    }

    private void receive(ChannelActivityTableView view)
    {
        if(view == null || mDisposed.get())
        {
            return;
        }

        mPendingView.set(view);

        if(mUpdateScheduled.compareAndSet(false, true))
        {
            EventQueue.invokeLater(this::applyPendingView);
        }
    }

    private void applyPendingView()
    {
        ChannelActivityTableView view = mPendingView.getAndSet(null);

        if(view != null && !mDisposed.get())
        {
            apply(view, true);
        }

        mUpdateScheduled.set(false);

        if(mPendingView.get() != null && !mDisposed.get() && mUpdateScheduled.compareAndSet(false, true))
        {
            EventQueue.invokeLater(this::applyPendingView);
        }
    }

    private void apply(ChannelActivityTableView view, boolean notify)
    {
        mTitle = view.title();
        mOwnerChannel = view.ownerChannel();
        mCloseable = view.closeable();
        mControlActive = view.controlActive();
        mRows = view.rows();
        Map<String,ChannelActivityRow> rowsByKey = new HashMap<>();

        for(ChannelActivityRow row: mRows)
        {
            rowsByKey.put(row.getKey(), row);
        }

        mRowsByKey = Map.copyOf(rowsByKey);

        if(notify)
        {
            if(mActivityViewVisible)
            {
                fireTableDataChanged();
            }
            else
            {
                mPendingFullRefresh = true;
            }
        }
    }

    @Override
    public int getRowCount()
    {
        return mRows.size();
    }

    @Override
    public int getColumnCount()
    {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column)
    {
        return column == COLUMN_LCN && mOwnerChannel == null ? "Channel" : COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex)
    {
        return switch(columnIndex)
        {
            case COLUMN_STATUS -> State.class;
            case COLUMN_FREQUENCY -> Long.class;
            case COLUMN_SIGNAL -> Double.class;
            case COLUMN_DECODE_HEALTH -> ChannelActivityDecodeQuality.class;
            case COLUMN_SOURCE_ALIAS, COLUMN_TARGET_ALIAS -> Alias.class;
            case COLUMN_SOURCE, COLUMN_TARGET -> Identifier.class;
            default -> String.class;
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex)
    {
        ChannelActivityRow row = getRow(rowIndex);

        if(row == null)
        {
            return null;
        }

        return switch(columnIndex)
        {
            case COLUMN_STATUS -> row.getState();
            case COLUMN_TAGS -> row.getTagsDisplay();
            case COLUMN_LCN -> mOwnerChannel == null ? row.getChannelName() : row.getLcn();
            case COLUMN_FREQUENCY -> row.getFrequency();
            case COLUMN_CALLSIGN -> row.getCallsign();
            case COLUMN_SIGNAL -> row.getSignalDbfs();
            case COLUMN_DECODE_HEALTH -> row.getDecodeQuality();
            case COLUMN_SOURCE_ALIAS -> row.getSourceAliases();
            case COLUMN_SOURCE -> row.getSource();
            case COLUMN_TARGET_ALIAS -> row.getTargetAliases();
            case COLUMN_TARGET -> row.getTarget();
            case COLUMN_DECODER -> row.getDecoder();
            default -> null;
        };
    }

    @Override
    public void close()
    {
        if(mDisposed.compareAndSet(false, true))
        {
            mState.removeViewListener(mViewListener);
            mPendingView.set(null);
        }
    }
}
