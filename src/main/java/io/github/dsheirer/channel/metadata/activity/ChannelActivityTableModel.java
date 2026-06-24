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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final List<ChannelActivityRow> mRows = new ArrayList<>();
    private final Map<String,ChannelActivityRow> mRowsByKey = new HashMap<>();

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
    }

    public Channel getOwnerChannel()
    {
        return mOwnerChannel;
    }

    public boolean isCloseable()
    {
        return mCloseable;
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
        }
        else
        {
            row.setChannel(channel);
            row.setRole(role);
            row.setFrequency(frequency);
            row.setTimeslot(timeslot);
        }

        sortAndRefresh();
        return row;
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
        if(row >= 0 && row < mRows.size())
        {
            return mRows.get(row);
        }

        return null;
    }

    public void refresh(ChannelActivityRow row)
    {
        int index = mRows.indexOf(row);

        if(index >= 0)
        {
            fireTableRowsUpdated(index, index);
        }
    }

    public void sortAndRefresh()
    {
        mRows.sort(ROW_SORT);
        fireTableDataChanged();
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
            case COLUMN_LCN -> Integer.class;
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
