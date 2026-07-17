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

import com.google.common.base.Joiner;
import com.google.common.eventbus.Subscribe;
import com.jidesoft.swing.JideTabbedPane;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.SwingUtils;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.event.TableModelListener;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import net.miginfocom.swing.MigLayout;

/**
 * Tabbed Now Playing activity view with one conventional table and one table per learned trunked site.
 */
public class ChannelActivityPanel extends JPanel
{
    private static final String TABLE_COLUMN_WIDTH_PREFERENCE_KEY = "now.playing.activity.table.v4";
    private static final int[] TABLE_COLUMN_DEFAULT_WIDTHS = {150, 180, 130, 96, 92, 82, 72, 210, 88, 210, 88, 74};
    private static final int[] TABLE_COLUMN_MINIMUM_WIDTHS = {90, 90, 90, 80, 75, 70, 62, 80, 67, 80, 67, 54};
    private static final double DECODE_HEALTHY_MINIMUM_PERCENT = 90.0;
    private static final double DECODE_DEGRADED_MINIMUM_PERCENT = 75.0;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final ChannelActivityModel mActivityModel;
    private final IconModel mIconModel;
    private final UserPreferences mUserPreferences;
    private final NowPlayingPreference mNowPlayingPreference;
    private final Broadcaster<SelectedFrequencyContext> mSelectedFrequencyBroadcaster = new Broadcaster<>();
    private final Listener<ChannelActivityTableModel> mTableAddListener =
        tableModel -> SwingUtils.run(() -> addTable(tableModel));
    private final Listener<ChannelActivityTableModel> mTableChangeListener =
        tableModel -> SwingUtils.run(() -> updateTable(tableModel));
    private final Map<State,Color> mBackgroundColors = new EnumMap<>(State.class);
    private final Map<State,Color> mForegroundColors = new EnumMap<>(State.class);
    private final Map<ChannelActivityTableModel,Component> mTabComponents = new HashMap<>();
    private final Map<ChannelActivityTableModel,ChannelActivityTable> mTables = new HashMap<>();
    private final Map<JTable,JTableColumnWidthMonitor> mColumnWidthMonitors = new HashMap<>();
    private final Map<JTable,TableColumnModelListener> mColumnWidthSyncListeners = new HashMap<>();
    private final Map<ChannelActivityTableModel,TableModelListener> mSelectionRenderListeners = new HashMap<>();
    private final Set<ChannelActivityTableModel> mPendingSelectionRenders = new HashSet<>();
    private final ChannelActivitySelectionController mSelectionController =
        new ChannelActivitySelectionController();
    private SelectedFrequencyContext mLastBroadcastSelectedFrequencyContext;
    private boolean mApplyingColumnWidths;
    private boolean mActive;
    private boolean mRegisteredForPreferences;
    private JideTabbedPane mTabbedPane;

    public ChannelActivityPanel(ConfigurationManager configurationManager, IconModel iconModel, UserPreferences userPreferences)
    {
        mChannelProcessingManager = configurationManager.getChannelProcessingManager();
        mActivityModel = mChannelProcessingManager.getChannelActivityModel();
        mIconModel = iconModel;
        mUserPreferences = userPreferences;
        mNowPlayingPreference = userPreferences.getNowPlayingPreference();
        setColors();
        init();
        setActive(true);
    }

    public void dispose()
    {
        setActive(false);
    }

    /**
     * Attaches or detaches this renderer from the shared activity model. The model may remain active for another
     * renderer, such as the web console, while this panel is hidden.
     */
    public void setActive(boolean active)
    {
        if(mActive == active)
        {
            return;
        }

        mActive = active;

        if(active)
        {
            if(!mRegisteredForPreferences)
            {
                MyEventBus.getGlobalEventBus().register(this);
                mRegisteredForPreferences = true;
            }

            mActivityModel.addTableAddListener(mTableAddListener);
            mActivityModel.addTableChangeListener(mTableChangeListener);

            for(ChannelActivityTableModel tableModel: mActivityModel.getTables())
            {
                addTable(tableModel);
            }
        }
        else
        {
            if(mRegisteredForPreferences)
            {
                MyEventBus.getGlobalEventBus().unregister(this);
                mRegisteredForPreferences = false;
            }

            mActivityModel.removeTableAddListener(mTableAddListener);
            mActivityModel.removeTableChangeListener(mTableChangeListener);
            clearTables();
        }
    }

    private void clearTables()
    {
        clearSelectedFrequencyContext();

        for(ChannelActivityTableModel tableModel: mTabComponents.keySet())
        {
            tableModel.setActivityViewVisible(false);
        }

        disposeTableWiring();
        mTabComponents.clear();
        mTables.clear();
        mPendingSelectionRenders.clear();

        if(mTabbedPane != null)
        {
            mTabbedPane.removeAll();
        }
    }

    private void disposeTableWiring()
    {
        for(Map.Entry<ChannelActivityTableModel,TableModelListener> entry: mSelectionRenderListeners.entrySet())
        {
            entry.getKey().removeTableModelListener(entry.getValue());
        }

        for(ChannelActivityTable table: mTables.values())
        {
            table.setUserSelectionListener(null);
        }

        for(JTableColumnWidthMonitor monitor: mColumnWidthMonitors.values())
        {
            monitor.dispose();
        }

        for(Map.Entry<JTable,TableColumnModelListener> entry: mColumnWidthSyncListeners.entrySet())
        {
            entry.getKey().getColumnModel().removeColumnModelListener(entry.getValue());
        }

        mColumnWidthMonitors.clear();
        mColumnWidthSyncListeners.clear();
        mSelectionRenderListeners.clear();
    }

    public void addSelectedFrequencyListener(Listener<SelectedFrequencyContext> listener)
    {
        mSelectedFrequencyBroadcaster.addListener(listener);
    }

    public void removeSelectedFrequencyListener(Listener<SelectedFrequencyContext> listener)
    {
        mSelectedFrequencyBroadcaster.removeListener(listener);
    }

    public void clearSelectedFrequencyContext()
    {
        mSelectionController.clear();
        clearSelectionPresentation();
    }

    private void clearSelectionPresentation()
    {
        for(JTable table: mTables.values())
        {
            table.clearSelection();
        }

        broadcastSelectedFrequencyContext(SelectedFrequencyContext.clear(), true);
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]", "[grow,fill]"));
        add(getTabbedPane(), "grow");
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.NOW_PLAYING)
        {
            refreshTables();
        }
    }

    private JideTabbedPane getTabbedPane()
    {
        if(mTabbedPane == null)
        {
            mTabbedPane = new JideTabbedPane();
            mTabbedPane.setFont(this.getFont());
            mTabbedPane.setForeground(Color.BLACK);
            mTabbedPane.addChangeListener(event -> updateTableVisibility());
            mTabbedPane.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent event)
                {
                    handleTabIndicatorClick(event);
                }
            });
        }

        return mTabbedPane;
    }

    private void addTable(ChannelActivityTableModel tableModel)
    {
        JTable table = createTable(tableModel);
        JScrollPane scrollPane = new JScrollPane(table, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        getTabbedPane().addTab(tableModel.getTitle(), scrollPane);
        mTabComponents.put(tableModel, scrollPane);

        if(tableModel.isCloseable())
        {
            int index = getTabbedPane().indexOfComponent(scrollPane);
            getTabbedPane().setIconAt(index, new TabStatusIcon(tableModel));
            getTabbedPane().setTabClosableAt(index, false);
            getTabbedPane().setToolTipTextAt(index, getTabToolTip(tableModel));
        }
        else
        {
            int index = getTabbedPane().indexOfComponent(scrollPane);
            getTabbedPane().setToolTipTextAt(index, tableModel.getTitle());
        }

        updateTableVisibility();
    }

    private void updateTable(ChannelActivityTableModel tableModel)
    {
        Component component = mTabComponents.get(tableModel);
        int index = component != null ? getTabbedPane().indexOfComponent(component) : -1;

        if(index >= 0)
        {
            String title = tableModel.getTitle();
            getTabbedPane().setTitleAt(index, title);
            getTabbedPane().setToolTipTextAt(index, tableModel.isCloseable() ? getTabToolTip(tableModel) : title);

            if(tableModel.isCloseable() && !(getTabbedPane().getIconAt(index) instanceof TabStatusIcon))
            {
                getTabbedPane().setIconAt(index, new TabStatusIcon(tableModel));
            }

            getTabbedPane().revalidate();
            getTabbedPane().repaint();
        }
    }

    private String getTabToolTip(ChannelActivityTableModel tableModel)
    {
        if(tableModel.isControlActive())
        {
            return "Control channel active. " + tableModel.getTitle();
        }

        return "Control channel stale or stopped. Click the status dot to close this site tab. " +
            tableModel.getTitle();
    }

    private void handleTabIndicatorClick(MouseEvent event)
    {
        if(event.getButton() != MouseEvent.BUTTON1)
        {
            return;
        }

        int index = getTabbedPane().indexAtLocation(event.getX(), event.getY());
        ChannelActivityTableModel tableModel = getTableModel(index);

        if(tableModel != null && tableModel.isCloseable() && !tableModel.isControlActive())
        {
            Rectangle tabBounds = getTabbedPane().getUI().getTabBounds(getTabbedPane(), index);

            if(tabBounds != null && event.getX() <= tabBounds.x + TabStatusIcon.WIDTH + 8)
            {
                closeTab(tableModel);
            }
        }
    }

    private ChannelActivityTableModel getTableModel(int tabIndex)
    {
        if(tabIndex >= 0)
        {
            Component tabComponent = getTabbedPane().getComponentAt(tabIndex);

            for(Map.Entry<ChannelActivityTableModel,Component> entry: mTabComponents.entrySet())
            {
                if(entry.getValue() == tabComponent)
                {
                    return entry.getKey();
                }
            }
        }

        return null;
    }

    private void closeTab(ChannelActivityTableModel tableModel)
    {
        Component component = mTabComponents.remove(tableModel);
        int index = component != null ? getTabbedPane().indexOfComponent(component) : -1;

        if(index >= 0)
        {
            getTabbedPane().remove(index);
        }

        tableModel.setActivityViewVisible(false);

        ChannelActivityTable table = mTables.remove(tableModel);
        boolean selectedTableClosed = mSelectionController.isSelected(tableModel);

        if(table != null)
        {
            mPendingSelectionRenders.remove(tableModel);
            table.setUserSelectionListener(null);
            TableModelListener selectionListener = mSelectionRenderListeners.remove(tableModel);

            if(selectionListener != null)
            {
                tableModel.removeTableModelListener(selectionListener);
            }

            JTableColumnWidthMonitor monitor = mColumnWidthMonitors.remove(table);

            if(monitor != null)
            {
                monitor.dispose();
            }

            TableColumnModelListener listener = mColumnWidthSyncListeners.remove(table);

            if(listener != null)
            {
                table.getColumnModel().removeColumnModelListener(listener);
            }
        }

        if(selectedTableClosed)
        {
            mSelectionController.clear();
            broadcastSelectedFrequencyContext(SelectedFrequencyContext.clear(), true);
        }

        mActivityModel.close(tableModel);
        updateTableVisibility();
    }

    private void updateTableVisibility()
    {
        Component selectedComponent = getTabbedPane().getSelectedComponent();
        ChannelActivityTableModel selectedTableModel = null;

        for(Map.Entry<ChannelActivityTableModel,Component> entry: mTabComponents.entrySet())
        {
            boolean selected = entry.getValue() == selectedComponent;
            entry.getKey().setActivityViewVisible(selected);

            if(selected)
            {
                selectedTableModel = entry.getKey();
            }
        }

        if(mSelectionController.clearIfSelectionIsOutside(selectedTableModel))
        {
            clearSelectionPresentation();
        }
    }

    private void refreshTables()
    {
        for(ChannelActivityTableModel tableModel: mTabComponents.keySet())
        {
            tableModel.refreshAllRows();
        }
    }

    private JTable createTable(ChannelActivityTableModel tableModel)
    {
        ChannelActivityTable table = new ChannelActivityTable(tableModel);
        mTables.put(tableModel, table);
        table.setAutoCreateRowSorter(false);
        table.setSelectionBackground(table.getBackground());
        table.setSelectionForeground(table.getForeground());
        TableModelListener selectionListener = event -> scheduleSelectionRender(tableModel);
        tableModel.addTableModelListener(selectionListener);
        mSelectionRenderListeners.put(tableModel, selectionListener);
        table.setUserSelectionListener(this::processUserSelection);
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_STATUS)
            .setCellRenderer(new StateCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_TAGS)
            .setCellRenderer(new TagCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_LCN)
            .setCellRenderer(new LcnCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_FREQUENCY)
            .setCellRenderer(new FrequencyCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_CALLSIGN)
            .setCellRenderer(new CenteredCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_SIGNAL)
            .setCellRenderer(new QualityCellRenderer(false));
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_DECODE_HEALTH)
            .setCellRenderer(new QualityCellRenderer(true));
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_SOURCE_ALIAS)
            .setCellRenderer(new AliasCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_TARGET_ALIAS)
            .setCellRenderer(new AliasCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_SOURCE)
            .setCellRenderer(new CenteredCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_TARGET)
            .setCellRenderer(new CenteredCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_DECODER)
            .setCellRenderer(new CenteredCellRenderer());
        configureColumnWidths(table);
        mColumnWidthMonitors.put(table, new JTableColumnWidthMonitor(mUserPreferences, table,
            TABLE_COLUMN_WIDTH_PREFERENCE_KEY, TABLE_COLUMN_MINIMUM_WIDTHS));
        addColumnWidthSync(table);
        return table;
    }

    private void addColumnWidthSync(JTable table)
    {
        TableColumnModelListener listener = new TableColumnModelListener()
        {
            @Override
            public void columnMarginChanged(ChangeEvent event)
            {
                if(!mApplyingColumnWidths)
                {
                    applyColumnWidthsToOtherTables(table);
                }
            }

            @Override
            public void columnAdded(TableColumnModelEvent event)
            {
            }

            @Override
            public void columnRemoved(TableColumnModelEvent event)
            {
            }

            @Override
            public void columnMoved(TableColumnModelEvent event)
            {
            }

            @Override
            public void columnSelectionChanged(ListSelectionEvent event)
            {
            }
        };

        table.getColumnModel().addColumnModelListener(listener);
        mColumnWidthSyncListeners.put(table, listener);
        SwingUtilities.invokeLater(() -> applyExistingColumnWidths(table));
    }

    private void applyExistingColumnWidths(JTable target)
    {
        if(mTables.containsValue(target))
        {
            for(JTable source: mTables.values())
            {
                if(source != target)
                {
                    applyColumnWidths(source, target);
                    break;
                }
            }
        }
    }

    private void applyColumnWidthsToOtherTables(JTable source)
    {
        for(JTable target: mTables.values())
        {
            if(target != source)
            {
                applyColumnWidths(source, target);
            }
        }
    }

    private void applyColumnWidths(JTable source, JTable target)
    {
        TableColumnModel sourceColumns = source.getColumnModel();
        TableColumnModel targetColumns = target.getColumnModel();
        int columnCount = Math.min(sourceColumns.getColumnCount(), targetColumns.getColumnCount());
        mApplyingColumnWidths = true;

        try
        {
            for(int column = 0; column < columnCount; column++)
            {
                TableColumn sourceColumn = sourceColumns.getColumn(column);
                TableColumn targetColumn = targetColumns.getColumn(column);
                int width = getValidColumnWidth(column, targetColumn, sourceColumn.getWidth());
                targetColumn.setPreferredWidth(width);
                targetColumn.setWidth(width);
            }
        }
        finally
        {
            mApplyingColumnWidths = false;
        }
    }

    private void configureColumnWidths(JTable table)
    {
        TableColumnModel columns = table.getColumnModel();

        for(int column = 0; column < columns.getColumnCount(); column++)
        {
            TableColumn tableColumn = columns.getColumn(column);
            tableColumn.setMinWidth(getConfiguredColumnMinimumWidth(column, tableColumn.getMinWidth()));
            tableColumn.setPreferredWidth(getConfiguredColumnDefaultWidth(column, tableColumn.getPreferredWidth()));
        }
    }

    private int getValidColumnWidth(int column, TableColumn tableColumn, int width)
    {
        int minimum = getConfiguredColumnMinimumWidth(column, tableColumn.getMinWidth());
        int maximum = tableColumn.getMaxWidth();
        int validWidth = Math.max(minimum, width);

        if(maximum > 0 && maximum < Integer.MAX_VALUE)
        {
            validWidth = Math.min(maximum, validWidth);
        }

        return validWidth;
    }

    private int getConfiguredColumnMinimumWidth(int column, int fallback)
    {
        return column < TABLE_COLUMN_MINIMUM_WIDTHS.length ? TABLE_COLUMN_MINIMUM_WIDTHS[column] : fallback;
    }

    private int getConfiguredColumnDefaultWidth(int column, int fallback)
    {
        return column < TABLE_COLUMN_DEFAULT_WIDTHS.length ? TABLE_COLUMN_DEFAULT_WIDTHS[column] : fallback;
    }

    private void processUserSelection(ChannelActivityTable table)
    {
        if(!(table.getModel() instanceof ChannelActivityTableModel model))
        {
            return;
        }

        int selectedViewRow = table.getSelectedRow();
        ChannelActivityRow row = selectedViewRow >= 0 ?
            model.getRow(table.convertRowIndexToModel(selectedViewRow)) : null;

        if(row == null)
        {
            clearSelectedFrequencyContext();
            return;
        }

        ChannelActivitySelectionController.Selection selection = mSelectionController.select(model, row);
        clearOtherSelectionHighlights(model);
        broadcastSelection(selection, row, true);
    }

    /**
     * Schedules a passive redraw after JTable has applied a model mutation.  This redraw reads the latest logical
     * selection, so a user click that occurs before it runs always wins.
     */
    private void scheduleSelectionRender(ChannelActivityTableModel model)
    {
        if(mSelectionController.isSelected(model) && mPendingSelectionRenders.add(model))
        {
            SwingUtilities.invokeLater(() -> {
                mPendingSelectionRenders.remove(model);
                renderSelection(model);
            });
        }
    }

    private void renderSelection(ChannelActivityTableModel model)
    {
        ChannelActivityTable table = mTables.get(model);

        if(table == null)
        {
            return;
        }

        if(!mSelectionController.isSelected(model))
        {
            table.clearSelection();
            return;
        }

        ChannelActivityRow row = mSelectionController.resolveSelectedRow();
        ChannelActivitySelectionController.Selection selection = mSelectionController.getSelection();

        if(selection == null)
        {
            table.clearSelection();
            broadcastSelectedFrequencyContext(SelectedFrequencyContext.clear(), false);
            return;
        }

        if(row == null)
        {
            //There is temporarily no row to highlight, but the site remains the user's logical selection.
            table.clearSelection();
        }
        else
        {
            int modelRow = model.getRowIndex(row.getKey());
            int viewRow = modelRow >= 0 ? table.convertRowIndexToView(modelRow) : -1;

            if(viewRow >= 0 && table.getSelectedRow() != viewRow)
            {
                table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
            }
        }

        broadcastSelection(selection, row, false);
    }

    private void clearOtherSelectionHighlights(ChannelActivityTableModel selectedModel)
    {
        for(Map.Entry<ChannelActivityTableModel,ChannelActivityTable> entry: mTables.entrySet())
        {
            if(entry.getKey() != selectedModel)
            {
                entry.getValue().clearSelection();
            }
        }
    }

    private void broadcastSelection(ChannelActivitySelectionController.Selection selection, ChannelActivityRow row,
                                    boolean force)
    {
        broadcastSelectedFrequencyContext(getSelectedFrequencyContext(selection, row), force);
    }

    private SelectedFrequencyContext getSelectedFrequencyContext(
        ChannelActivitySelectionController.Selection selection, ChannelActivityRow row)
    {
        if(selection == null)
        {
            return SelectedFrequencyContext.clear();
        }

        long frequency = row != null ? row.getFrequency() : selection.frequency();
        Integer timeslot = row != null ? row.getTimeslot() : selection.timeslot();
        String decoderHint = row != null ? row.getDecoder() : selection.decoderHint();
        Channel rowChannel = row != null ? row.getChannel() : selection.rowChannel();
        ProcessingChain processingChain = frequency > 0 ?
            mChannelProcessingManager.getProcessingChainByFrequency(frequency, timeslot) : null;
        ProcessingChain eventProcessingChain = selection.isSite() && selection.ownerChannel() != null ?
            mChannelProcessingManager.getProcessingChain(selection.ownerChannel()) : processingChain;
        return new SelectedFrequencyContext(frequency, timeslot, decoderHint, selection.ownerChannel(), rowChannel,
            processingChain, eventProcessingChain, selection.scope(), false);
    }

    private void broadcastSelectedFrequencyContext(SelectedFrequencyContext context, boolean force)
    {
        if(force || !context.equals(mLastBroadcastSelectedFrequencyContext))
        {
            mLastBroadcastSelectedFrequencyContext = context;
            mSelectedFrequencyBroadcaster.broadcast(context);
        }
    }

    private void setColors()
    {
        mBackgroundColors.put(State.ACTIVE, Color.CYAN);
        mForegroundColors.put(State.ACTIVE, Color.BLUE);
        mBackgroundColors.put(State.CALL, Color.BLUE);
        mForegroundColors.put(State.CALL, Color.YELLOW);
        mBackgroundColors.put(State.CONTROL, Color.ORANGE);
        mForegroundColors.put(State.CONTROL, Color.BLUE);
        mBackgroundColors.put(State.DATA, Color.GREEN);
        mForegroundColors.put(State.DATA, Color.BLUE);
        mBackgroundColors.put(State.ENCRYPTED, Color.MAGENTA);
        mForegroundColors.put(State.ENCRYPTED, Color.WHITE);
        mBackgroundColors.put(State.FADE, Color.LIGHT_GRAY);
        mForegroundColors.put(State.FADE, Color.DARK_GRAY);
        mBackgroundColors.put(State.IDLE, Color.WHITE);
        mForegroundColors.put(State.IDLE, Color.DARK_GRAY);
        mBackgroundColors.put(State.RESET, Color.PINK);
        mForegroundColors.put(State.RESET, Color.YELLOW);
        mBackgroundColors.put(State.TEARDOWN, Color.DARK_GRAY);
        mForegroundColors.put(State.TEARDOWN, Color.WHITE);
    }

    public static class TabStatusIcon implements Icon
    {
        public static final int WIDTH = 18;
        private static final int HEIGHT = 14;
        private final ChannelActivityTableModel mTableModel;

        public TabStatusIcon(ChannelActivityTableModel tableModel)
        {
            mTableModel = tableModel;
        }

        @Override
        public int getIconWidth()
        {
            return WIDTH;
        }

        @Override
        public int getIconHeight()
        {
            return HEIGHT;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y)
        {
            Graphics2D g2 = (Graphics2D)graphics.create();

            try
            {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = 11;
                int circleX = x + 3;
                int circleY = y + ((HEIGHT - size) / 2);
                boolean controlActive = mTableModel.isControlActive();
                g2.setColor(controlActive ? new Color(0, 145, 40) : Color.BLACK);
                g2.fillOval(circleX, circleY, size, size);

                if(!controlActive)
                {
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(1.6f));
                    g2.drawLine(circleX + 3, circleY + 3, circleX + size - 4, circleY + size - 4);
                    g2.drawLine(circleX + size - 4, circleY + 3, circleX + 3, circleY + size - 4);
                }
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    public class StateCellRenderer extends DefaultTableCellRenderer
    {
        public StateCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof State state)
            {
                label.setText(state.toString());

                if(state == State.ENCRYPTED && advancedP25EncryptionStatus())
                {
                    ChannelActivityRow activityRow = getActivityRow(table, row);

                    if(activityRow != null && activityRow.getEncryptionDetails() != null)
                    {
                        label.setText(activityRow.getEncryptionDetails());
                    }
                }

                label.setBackground(mBackgroundColors.getOrDefault(state, table.getBackground()));
                label.setForeground(mForegroundColors.getOrDefault(state, table.getForeground()));
            }

            applySelectionBorder(table, label, isSelected, column);
            return label;
        }
    }

    private boolean advancedP25EncryptionStatus()
    {
        return mNowPlayingPreference != null && mNowPlayingPreference.isAdvancedP25EncryptionStatus();
    }

    private ChannelActivityRow getActivityRow(JTable table, int row)
    {
        if(table.getModel() instanceof ChannelActivityTableModel model)
        {
            return model.getRow(table.convertRowIndexToModel(row));
        }

        return null;
    }

    private void applyControlChannelForeground(JTable table, JLabel label, int row)
    {
        ChannelActivityRow activityRow = getActivityRow(table, row);

        if(activityRow != null)
        {
            if(activityRow.hasTag(ChannelTag.CURRENT_CONTROL))
            {
                label.setForeground(Color.RED);
                return;
            }
            else if(activityRow.hasTag(ChannelTag.ALTERNATE_CONTROL))
            {
                label.setForeground(new Color(180, 130, 0));
                return;
            }
        }

        label.setForeground(table.getForeground());
    }

    private void applySelectionBorder(JTable table, JLabel label, boolean isSelected, int column)
    {
        if(isSelected)
        {
            int lastColumn = table.getColumnCount() - 1;
            int left = column == 0 ? 1 : 0;
            int right = column == lastColumn ? 1 : 0;
            Border outline = BorderFactory.createMatteBorder(1, left, 1, right, Color.BLACK);
            label.setBorder(outline);
        }
        else
        {
            label.setBorder(null);
        }
    }

    public class LcnCellRenderer extends DefaultTableCellRenderer
    {
        public LcnCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setText(value != null ? value.toString() : null);

            ChannelActivityRow activityRow = getActivityRow(table, row);
            label.setToolTipText(activityRow != null && activityRow.getRole() == ChannelActivityRow.Role.CONVENTIONAL ?
                activityRow.getChannelName() : null);

            applyControlChannelForeground(table, label, row);
            applySelectionBorder(table, label, isSelected, column);

            return label;
        }
    }

    public class FrequencyCellRenderer extends DefaultTableCellRenderer
    {
        private final DecimalFormat mFormatter = new DecimalFormat("#.00000");

        public FrequencyCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof Long frequency && frequency > 0)
            {
                label.setText(mFormatter.format(frequency / 1e6d));
            }
            else
            {
                label.setText(null);
            }

            applyControlChannelForeground(table, label, row);
            applySelectionBorder(table, label, isSelected, column);

            return label;
        }
    }

    public class AliasCellRenderer extends DefaultTableCellRenderer
    {
        public AliasCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof List<?> aliases && aliases.stream().allMatch(Alias.class::isInstance))
            {
                if(!aliases.isEmpty())
                {
                    label.setText(Joiner.on(", ").skipNulls().join(aliases));
                    Alias firstAlias = Alias.class.cast(aliases.getFirst());
                    label.setIcon(mIconModel.getIcon(firstAlias.getIconName(), IconModel.DEFAULT_ICON_SIZE));

                    label.setForeground(firstAlias.getDisplayColor());
                }
                else
                {
                    label.setText(null);
                    label.setIcon(null);
                    label.setForeground(table.getForeground());
                }
            }
            else
            {
                label.setText(null);
                label.setIcon(null);
                label.setForeground(table.getForeground());
            }

            int modelColumn = table.convertColumnIndexToModel(column);

            if(modelColumn == ChannelActivityTableModel.COLUMN_SOURCE_ALIAS &&
                table.getModel() instanceof ChannelActivityTableModel activityTableModel)
            {
                ChannelActivityRow activityRow = activityTableModel.getRow(table.convertRowIndexToModel(row));
                label.setText(activityRow != null ? activityRow.getSourceAliasDisplay() : null);
            }

            applySelectionBorder(table, label, isSelected, column);
            return label;
        }
    }

    public class QualityCellRenderer extends DefaultTableCellRenderer
    {
        private final DecimalFormat mFormatter = new DecimalFormat("0.0");
        private final boolean mHealth;

        public QualityCellRenderer(boolean health)
        {
            mHealth = health;
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof Double measurement && Double.isFinite(measurement))
            {
                label.setText(mFormatter.format(measurement) + (mHealth ? "%" : " dBFS"));

                if(mHealth)
                {
                    label.setForeground(measurement >= DECODE_HEALTHY_MINIMUM_PERCENT ? new Color(0, 128, 0) :
                        measurement >= DECODE_DEGRADED_MINIMUM_PERCENT ? new Color(180, 130, 0) : Color.RED);
                }
                else
                {
                    applyControlChannelForeground(table, label, row);
                }
            }
            else
            {
                label.setText(null);
                label.setForeground(table.getForeground());
            }

            applySelectionBorder(table, label, isSelected, column);
            return label;
        }
    }

    public class CenteredCellRenderer extends DefaultTableCellRenderer
    {
        public CenteredCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setForeground(table.getForeground());
            label.setBackground(table.getBackground());
            applySelectionBorder(table, label, isSelected, column);
            return label;
        }
    }

    public class TagCellRenderer extends CenteredCellRenderer
    {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row,
                column);
            ChannelActivityRow activityRow = getActivityRow(table, row);
            label.setToolTipText(activityRow != null ? activityRow.getTagsDescription() : null);
            return label;
        }
    }
}
