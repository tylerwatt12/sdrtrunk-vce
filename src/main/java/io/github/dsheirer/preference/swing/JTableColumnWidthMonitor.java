/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2019 Dennis Sheirer
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
 * *****************************************************************************
 */

package io.github.dsheirer.preference.swing;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.ThreadPool;

import java.awt.EventQueue;
import javax.swing.JTable;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors a JTable column model and persists column width changes to the user preferences.  Restores
 * previous column width preferred sizes on application restart.
 */
public class JTableColumnWidthMonitor
{
    private static final String COLUMN_WIDTH_KEY_TOKEN = ".column.";
    private static final List<JTableColumnWidthMonitor> MONITORS = new ArrayList<>();

    private UserPreferences mUserPreferences;
    private JTable mTable;
    private String mKey;
    private int[] mMinimumColumnWidths;
    private int[] mDefaultColumnWidths;
    private ColumnResizeListener mColumnResizeListener = new ColumnResizeListener();
    private AtomicBoolean mSaveInProgress = new AtomicBoolean();

    /**
     * Constructs a column width monitor.
     *
     * @param userPreferences to store column widths
     * @param table to monitor for column width changes
     * @param key that uniquely identifies the table to monitor
     */
    public JTableColumnWidthMonitor(UserPreferences userPreferences, JTable table, String key)
    {
        this(userPreferences, table, key, null);
    }

    /**
     * Constructs a column width monitor.
     *
     * @param userPreferences to store column widths
     * @param table to monitor for column width changes
     * @param key that uniquely identifies the table to monitor
     * @param minimumColumnWidths optional per-column minimums for restored widths
     */
    public JTableColumnWidthMonitor(UserPreferences userPreferences, JTable table, String key, int[] minimumColumnWidths)
    {
        this(userPreferences, table, key, minimumColumnWidths, null, JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    }

    /**
     * Constructs a column width monitor.
     *
     * @param userPreferences to store column widths
     * @param table to monitor for column width changes
     * @param key that uniquely identifies the table to monitor
     * @param minimumColumnWidths optional per-column minimums for restored widths
     * @param defaultColumnWidths optional per-column default widths used when no user preference exists
     * @param autoResizeMode JTable auto resize mode to apply
     */
    public JTableColumnWidthMonitor(UserPreferences userPreferences, JTable table, String key, int[] minimumColumnWidths,
                                    int[] defaultColumnWidths, int autoResizeMode)
    {
        mUserPreferences = userPreferences;
        mTable = table;
        mKey = key;
        mMinimumColumnWidths = minimumColumnWidths != null ? minimumColumnWidths.clone() : null;
        mDefaultColumnWidths = defaultColumnWidths != null ? defaultColumnWidths.clone() : null;

        mTable.setAutoResizeMode(autoResizeMode);

        // Wait until the UI is realized to set preferred column widths
        EventQueue.invokeLater(this::restoreColumnWidths);

        // Keep listening for drag-resizes so you can re-save new widths
        mTable.getColumnModel().addColumnModelListener(mColumnResizeListener);

        synchronized(MONITORS)
        {
            MONITORS.add(this);
        }
    }

    /**
     * Prepares this monitor for disposal by unregistering as a listener to the table column model.
     */
    public void dispose()
    {
        if(mTable != null && mColumnResizeListener != null)
        {
            mTable.getColumnModel().removeColumnModelListener(mColumnResizeListener);
        }

        mTable = null;
        mUserPreferences = null;
        mMinimumColumnWidths = null;
        mDefaultColumnWidths = null;

        synchronized(MONITORS)
        {
            MONITORS.remove(this);
        }
    }

    /**
     * Sets the preferred column widths on the table from persisted settings
     */
    private void restoreColumnWidths()
    {
        TableColumnModel model = mTable.getColumnModel();

        for(int x = 0; x < model.getColumnCount(); x++)
        {
            int width = mUserPreferences.getSwingPreference().getInt(getColumnKey(x), Integer.MAX_VALUE);

            if(width == Integer.MAX_VALUE && mDefaultColumnWidths != null && x < mDefaultColumnWidths.length)
            {
                width = mDefaultColumnWidths[x];
            }

            if(width != Integer.MAX_VALUE)
            {
                TableColumn column = model.getColumn(x);
                int validWidth = getValidWidth(column, x, width);
                column.setPreferredWidth(validWidth);
                column.setWidth(validWidth);
            }
        }
    }

    private int getValidWidth(TableColumn column, int columnIndex, int width)
    {
        int minimum = column.getMinWidth();

        if(mMinimumColumnWidths != null && columnIndex < mMinimumColumnWidths.length)
        {
            minimum = Math.max(minimum, mMinimumColumnWidths[columnIndex]);
        }

        int validWidth = Math.max(minimum, width);
        int maximum = column.getMaxWidth();

        if(maximum > 0 && maximum < Integer.MAX_VALUE)
        {
            validWidth = Math.min(maximum, validWidth);
        }

        return validWidth;
    }

    /**
     * Constructs a preference key for the column number
     */
    private String getColumnKey(int column)
    {
        return mKey + ".column." + column;
    }

    private void resetColumnWidthsToDefaults()
    {
        if(mTable == null || mDefaultColumnWidths == null)
        {
            return;
        }

        TableColumnModel model = mTable.getColumnModel();

        for(int x = 0; x < model.getColumnCount() && x < mDefaultColumnWidths.length; x++)
        {
            TableColumn column = model.getColumn(x);
            int validWidth = getValidWidth(column, x, mDefaultColumnWidths[x]);
            column.setPreferredWidth(validWidth);
            column.setWidth(validWidth);
        }
    }

    /**
     * Clears saved column widths and restores defaults for active monitored tables that define defaults.
     * @param userPreferences preferences containing saved table widths
     * @return count of removed stored width values
     */
    public static int resetSavedColumnWidths(UserPreferences userPreferences)
    {
        int removed = userPreferences.getSwingPreference().removeKeysContaining(COLUMN_WIDTH_KEY_TOKEN);

        synchronized(MONITORS)
        {
            for(JTableColumnWidthMonitor monitor: MONITORS)
            {
                EventQueue.invokeLater(monitor::resetColumnWidthsToDefaults);
            }
        }

        userPreferences.getSwingPreference().flush();
        return removed;
    }

    /**
     * Table column model listener.
     */
    class ColumnResizeListener implements TableColumnModelListener
    {
        @Override
        public void columnMarginChanged(ChangeEvent e)
        {
            if(mSaveInProgress.compareAndSet(false, true))
            {
                ThreadPool.SCHEDULED.schedule(new ColumnWidthSaveTask(), 2, TimeUnit.SECONDS);
            }
        }

        @Override
        public void columnAdded(TableColumnModelEvent e)
        {
            /* no action required */
        }
        @Override
        public void columnRemoved(TableColumnModelEvent e)
        {
            /* no action required */
        }
        @Override
        public void columnMoved(TableColumnModelEvent e)
        {
            /* no action required */
        }
        @Override
        public void columnSelectionChanged(ListSelectionEvent e)
        {
            /* no action required */
        }
    }

    public class ColumnWidthSaveTask implements Runnable
    {

        @Override
        public void run()
        {
            TableColumnModel model = mTable.getColumnModel();

            for(int x = 0; x < model.getColumnCount(); x++)
            {
                mUserPreferences.getSwingPreference().setInt(getColumnKey(x), model.getColumn(x).getWidth());
            }

            mSaveInProgress.set(false);
        }
    }
}
