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
import com.jidesoft.swing.JideTabbedPane;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.SwingUtils;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.text.DecimalFormat;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import net.miginfocom.swing.MigLayout;

/**
 * Tabbed Now Playing activity view with one conventional table and one table per learned trunked site.
 */
public class ChannelActivityPanel extends JPanel
{
    private final ChannelProcessingManager mChannelProcessingManager;
    private final ChannelActivityModel mActivityModel;
    private final IconModel mIconModel;
    private final Broadcaster<ProcessingChain> mSelectedProcessingChainBroadcaster = new Broadcaster<>();
    private final Map<State,Color> mBackgroundColors = new EnumMap<>(State.class);
    private final Map<State,Color> mForegroundColors = new EnumMap<>(State.class);
    private final Map<ChannelActivityTableModel,Component> mTabComponents = new HashMap<>();
    private final Map<ChannelActivityTableModel,CloseableTabComponent> mCloseableTabComponents = new HashMap<>();
    private JideTabbedPane mTabbedPane;

    public ChannelActivityPanel(PlaylistManager playlistManager, IconModel iconModel, UserPreferences userPreferences)
    {
        mChannelProcessingManager = playlistManager.getChannelProcessingManager();
        mActivityModel = mChannelProcessingManager.getChannelActivityModel();
        mIconModel = iconModel;
        setColors();
        init();
    }

    public void addProcessingChainSelectionListener(Listener<ProcessingChain> listener)
    {
        mSelectedProcessingChainBroadcaster.addListener(listener);
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]", "[grow,fill]"));
        add(getTabbedPane(), "grow");
        addTable(mActivityModel.getConventionalTable());
        mActivityModel.addTableAddListener(tableModel -> SwingUtils.run(() -> addTable(tableModel)));
        mActivityModel.addTableChangeListener(tableModel -> SwingUtils.run(() -> updateTable(tableModel)));
    }

    private JideTabbedPane getTabbedPane()
    {
        if(mTabbedPane == null)
        {
            mTabbedPane = new JideTabbedPane();
            mTabbedPane.setFont(this.getFont());
            mTabbedPane.setForeground(Color.BLACK);
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
            CloseableTabComponent closeableTabComponent = new CloseableTabComponent(tableModel, scrollPane);
            mCloseableTabComponents.put(tableModel, closeableTabComponent);
            getTabbedPane().setTabComponentAt(index, closeableTabComponent);
        }
    }

    private void updateTable(ChannelActivityTableModel tableModel)
    {
        Component component = mTabComponents.get(tableModel);
        int index = component != null ? getTabbedPane().indexOfComponent(component) : -1;

        if(index >= 0)
        {
            CloseableTabComponent closeableTabComponent = mCloseableTabComponents.get(tableModel);

            if(closeableTabComponent != null)
            {
                closeableTabComponent.updateTitle();
            }
            else
            {
                getTabbedPane().setTitleAt(index, tableModel.getTitle());
            }
        }
    }

    private JTable createTable(ChannelActivityTableModel tableModel)
    {
        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(false);
        table.getSelectionModel().addListSelectionListener(event -> processSelection(event, table));
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_STATUS)
            .setCellRenderer(new StateCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_LCN)
            .setCellRenderer(new LcnCellRenderer());
        table.getColumnModel().getColumn(ChannelActivityTableModel.COLUMN_FREQUENCY)
            .setCellRenderer(new FrequencyCellRenderer());
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
        return table;
    }

    private void configureColumnWidths(JTable table)
    {
        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(ChannelActivityTableModel.COLUMN_STATUS).setPreferredWidth(80);
        columns.getColumn(ChannelActivityTableModel.COLUMN_STATUS).setMaxWidth(110);
        columns.getColumn(ChannelActivityTableModel.COLUMN_LCN).setPreferredWidth(52);
        columns.getColumn(ChannelActivityTableModel.COLUMN_LCN).setMaxWidth(70);
        columns.getColumn(ChannelActivityTableModel.COLUMN_FREQUENCY).setPreferredWidth(95);
        columns.getColumn(ChannelActivityTableModel.COLUMN_FREQUENCY).setMaxWidth(120);
        columns.getColumn(ChannelActivityTableModel.COLUMN_SOURCE).setPreferredWidth(90);
        columns.getColumn(ChannelActivityTableModel.COLUMN_SOURCE).setMaxWidth(125);
        columns.getColumn(ChannelActivityTableModel.COLUMN_TARGET).setPreferredWidth(90);
        columns.getColumn(ChannelActivityTableModel.COLUMN_TARGET).setMaxWidth(125);
        columns.getColumn(ChannelActivityTableModel.COLUMN_DECODER).setPreferredWidth(70);
        columns.getColumn(ChannelActivityTableModel.COLUMN_DECODER).setMaxWidth(90);
        columns.getColumn(ChannelActivityTableModel.COLUMN_SOURCE_ALIAS).setPreferredWidth(260);
        columns.getColumn(ChannelActivityTableModel.COLUMN_TARGET_ALIAS).setPreferredWidth(260);
    }

    private void processSelection(ListSelectionEvent event, JTable table)
    {
        if(event.getValueIsAdjusting())
        {
            return;
        }

        ProcessingChain processingChain = null;
        int selectedRow = table.getSelectedRow();

        if(selectedRow >= 0 && table.getModel() instanceof ChannelActivityTableModel model)
        {
            ChannelActivityRow row = model.getRow(table.convertRowIndexToModel(selectedRow));

            if(row != null && row.getChannel() != null)
            {
                processingChain = mChannelProcessingManager.getProcessingChain(row.getChannel());
            }
        }

        mSelectedProcessingChainBroadcaster.broadcast(processingChain);
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

    public class CloseableTabComponent extends JPanel
    {
        private final ChannelActivityTableModel mTableModel;
        private final JLabel mTitle;

        public CloseableTabComponent(ChannelActivityTableModel tableModel, Component tabComponent)
        {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            mTableModel = tableModel;
            setOpaque(false);
            mTitle = new JLabel(tableModel.getTitle());
            add(mTitle);
            JButton close = new JButton("X");
            close.setFocusable(false);
            close.setBorderPainted(false);
            close.setContentAreaFilled(false);
            close.addActionListener(event -> {
                int index = getTabbedPane().indexOfComponent(tabComponent);

                if(index >= 0)
                {
                    getTabbedPane().remove(index);
                    mTabComponents.remove(tableModel);
                    mCloseableTabComponents.remove(tableModel);
                    mActivityModel.close(tableModel);
                    mSelectedProcessingChainBroadcaster.broadcast(null);
                }
            });
            add(close);
        }

        public void updateTitle()
        {
            mTitle.setText(mTableModel.getTitle());
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

                if(!isSelected)
                {
                    label.setBackground(mBackgroundColors.getOrDefault(state, table.getBackground()));
                    label.setForeground(mForegroundColors.getOrDefault(state, table.getForeground()));
                }
            }

            return label;
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

            if(!isSelected && table.getModel() instanceof ChannelActivityTableModel model)
            {
                ChannelActivityRow activityRow = model.getRow(table.convertRowIndexToModel(row));

                if(activityRow != null)
                {
                    if(activityRow.getRole() == ChannelActivityRow.Role.CURRENT_CONTROL)
                    {
                        label.setForeground(Color.RED);
                    }
                    else if(activityRow.getRole() == ChannelActivityRow.Role.ALTERNATE_CONTROL)
                    {
                        label.setForeground(new Color(180, 130, 0));
                    }
                    else
                    {
                        label.setForeground(table.getForeground());
                    }
                }
            }

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

                    if(!isSelected)
                    {
                        label.setForeground(firstAlias.getDisplayColor());
                    }
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

            return label;
        }
    }

    public class CenteredCellRenderer extends DefaultTableCellRenderer
    {
        public CenteredCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }
    }
}
