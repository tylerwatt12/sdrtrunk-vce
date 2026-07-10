/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.table.AbstractTableModel;

/**
 * Table model for one Now Playing activity tab.
 */
public class ChannelActivityTableModel extends AbstractTableModel
{
    public static final int COLUMN_STATUS = 0;
    public static final int COLUMN_LCN = 1;
    public static final int COLUMN_FREQUENCY = 2;
    public static final int COLUMN_SOURCE_ALIAS = 3;
    public static final int COLUMN_SOURCE = 4;
    public static final int COLUMN_TARGET_ALIAS = 5;
    public static final int COLUMN_TARGET = 6;
    public static final int COLUMN_DECODER = 7;

    private static final String[] COLUMNS = {
        "Status", "LCN", "Frequency", "Source Alias", "Source", "Target Alias", "Target", "Decoder"
    };
    private static final Comparator<ChannelActivityRow> ROW_SORT =
        Comparator.comparingLong(ChannelActivityRow::getFrequency)
            .thenComparing(row -> row.getTimeslot() != null ? row.getTimeslot() : 0)
            .thenComparing(ChannelActivityRow::getKey);

    private String mTitle;
    private final Channel mOwnerChannel;
    private final boolean mCloseable;
    private boolean mControlActive;
    private boolean mActivityViewVisible;
    private boolean mPendingFullRefresh;
    private final List<ChannelActivityRow> mRows = new ArrayList<>();
    private final Map<String,ChannelActivityRow> mRowsByKey = new HashMap<>();
    private final List<Listener<ChannelActivitySnapshot>> mSnapshotListeners = new CopyOnWriteArrayList<>();

    public ChannelActivityTableModel(String title, Channel ownerChannel, boolean closeable)
    {
        mTitle = title;
        mOwnerChannel = ownerChannel;
        mCloseable = closeable;
    }

    public String getTitle()
    {
        return mTitle;
    }

    public void setTitle(String title)
    {
        mTitle = title;
        notifySnapshotListeners();
    }

    public Channel getOwnerChannel()
    {
        return mOwnerChannel;
    }

    public boolean isCloseable()
    {
        return mCloseable;
    }

    /**
     * Indicates if this trunked table has recently decoded current-control messages.
     */
    public boolean isControlActive()
    {
        return mControlActive;
    }

    /**
     * Sets current-control decode activity for tab header rendering.
     */
    public boolean setControlActive(boolean controlActive)
    {
        boolean changed = mControlActive != controlActive;
        mControlActive = controlActive;

        if(changed)
        {
            notifySnapshotListeners();
        }

        return changed;
    }

    public void addSnapshotListener(Listener<ChannelActivitySnapshot> listener)
    {
        if(listener != null)
        {
            mSnapshotListeners.add(listener);
        }
    }

    public void removeSnapshotListener(Listener<ChannelActivitySnapshot> listener)
    {
        mSnapshotListeners.remove(listener);
    }

    private void notifySnapshotListeners()
    {
        if(!mSnapshotListeners.isEmpty())
        {
            ChannelActivitySnapshot snapshot = ChannelActivitySnapshot.from(this);

            for(Listener<ChannelActivitySnapshot> listener: mSnapshotListeners)
            {
                listener.receive(snapshot);
            }
        }
    }

    /**
     * Sets visibility state for this table in the Now Playing activity tab view.
     */
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

    /**
     * Indicates if this table is currently visible in the Now Playing activity tab view.
     */
    public boolean isActivityViewVisible()
    {
        return mActivityViewVisible;
    }

    public ChannelActivityRow getOrCreate(String key, Channel channel, ChannelActivityRow.Role role, long frequency,
                                          Integer timeslot)
    {
        ChannelActivityRow row = mRowsByKey.get(key);

        if(row == null)
        {
            row = new ChannelActivityRow(key, channel, role, frequency, timeslot);
            mRowsByKey.put(key, row);
            mRows.add(row);
            mRows.sort(ROW_SORT);
            int index = mRows.indexOf(row);

            if(index >= 0)
            {
                if(mActivityViewVisible)
                {
                    fireTableRowsInserted(index, index);
                }
                else
                {
                    mPendingFullRefresh = true;
                }
            }
        }
        else
        {
            row.setChannel(channel);
        }

        return row;
    }

    public ChannelActivityRow get(String key)
    {
        return mRowsByKey.get(key);
    }

    public void remove(ChannelActivityRow row)
    {
        int index = mRows.indexOf(row);

        if(index >= 0)
        {
            mRows.remove(index);
            mRowsByKey.remove(row.getKey());

            if(mActivityViewVisible)
            {
                fireTableRowsDeleted(index, index);
            }
            else
            {
                mPendingFullRefresh = true;
            }

            notifySnapshotListeners();
        }
    }

    public List<ChannelActivityRow> getRows()
    {
        return new ArrayList<>(mRows);
    }

    public void clear()
    {
        int lastRow = mRows.size() - 1;
        mRows.clear();
        mRowsByKey.clear();
        mPendingFullRefresh = false;

        if(lastRow >= 0 && mActivityViewVisible)
        {
            fireTableRowsDeleted(0, lastRow);
        }

        notifySnapshotListeners();
    }

    public ChannelActivityRow getRow(int row)
    {
        if(row >= 0 && row < mRows.size())
        {
            return mRows.get(row);
        }

        return null;
    }

    public int getRowIndex(String key)
    {
        return mRows.indexOf(get(key));
    }

    public void refresh(ChannelActivityRow row)
    {
        int index = mRows.indexOf(row);

        if(index >= 0)
        {
            if(mActivityViewVisible)
            {
                fireTableRowsUpdated(index, index);
            }
            else
            {
                mPendingFullRefresh = true;
            }

            notifySnapshotListeners();
        }
    }

    /**
     * Refreshes the specified rows, batching contiguous row index ranges.
     */
    public void refresh(Collection<ChannelActivityRow> rows)
    {
        if(rows == null || rows.isEmpty())
        {
            return;
        }

        if(!mActivityViewVisible)
        {
            mPendingFullRefresh = true;
            notifySnapshotListeners();
            return;
        }

        List<Integer> indexes = new ArrayList<>();

        for(ChannelActivityRow row: rows)
        {
            int index = mRows.indexOf(row);

            if(index >= 0)
            {
                indexes.add(index);
            }
        }

        if(indexes.isEmpty())
        {
            return;
        }

        Collections.sort(indexes);
        int start = indexes.get(0);
        int previous = start;

        for(int x = 1; x < indexes.size(); x++)
        {
            int current = indexes.get(x);

            if(current > previous + 1)
            {
                fireTableRowsUpdated(start, previous);
                start = current;
            }

            previous = current;
        }

        fireTableRowsUpdated(start, previous);
        notifySnapshotListeners();
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
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex)
    {
        return switch(columnIndex)
        {
            case COLUMN_STATUS -> State.class;
            case COLUMN_FREQUENCY -> Long.class;
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
            case COLUMN_LCN -> row.getLcn();
            case COLUMN_FREQUENCY -> row.getFrequency();
            case COLUMN_SOURCE_ALIAS -> row.getSourceAliases();
            case COLUMN_SOURCE -> row.getSource();
            case COLUMN_TARGET_ALIAS -> row.getTargetAliases();
            case COLUMN_TARGET -> row.getTarget();
            case COLUMN_DECODER -> row.getDecoder();
            default -> null;
        };
    }
}
