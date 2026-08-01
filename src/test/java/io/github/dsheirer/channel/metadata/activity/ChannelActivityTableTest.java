/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ChannelActivityTableTest
{
    @Test
    void distinguishesUserInputFromProgrammaticSelection() throws Exception
    {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical AWT environment");
        AtomicBoolean mouseObservedAsUser = new AtomicBoolean();
        AtomicInteger userSelections = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            ChannelActivityTableModel model = new ChannelActivityTableModel("Test", null, false);
            model.getOrCreate("one", null, ChannelActivityRow.Role.CONVENTIONAL, 151_000_000L, null);
            model.getOrCreate("two", null, ChannelActivityRow.Role.CONVENTIONAL, 152_000_000L, null);
            ChannelActivityTable table = new ChannelActivityTable(model);
            assertEquals(ListSelectionModel.SINGLE_SELECTION, table.getSelectionModel().getSelectionMode());
            table.setSize(600, 200);
            table.setUserSelectionListener(selectedTable -> {
                userSelections.incrementAndGet();
                mouseObservedAsUser.set(selectedTable.getSelectedRow() == 1);
            });

            table.setRowSelectionInterval(0, 0);
            assertEquals(0, userSelections.get());
            int y = table.getRowHeight() + table.getRowHeight() / 2;
            table.processMouseEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                20, y, 1, false, MouseEvent.BUTTON1));

            model.getOrCreate("inserted", null, ChannelActivityRow.Role.CONVENTIONAL, 150_000_000L, null);
            assertEquals(1, userSelections.get());
            table.setRowSelectionInterval(0, 0);
            table.processKeyEvent(new KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED));
        });

        assertEquals(2, userSelections.get());
        assertTrue(mouseObservedAsUser.get());
    }
}
