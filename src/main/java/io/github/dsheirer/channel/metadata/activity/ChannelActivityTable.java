/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * JTable that identifies selection changes made while processing direct mouse or keyboard input.  Model-driven and
 * programmatic highlight changes use the normal JTable path but are never mistaken for a new user selection.
 */
class ChannelActivityTable extends JTable
{
    private Consumer<ChannelActivityTable> mUserSelectionListener;

    ChannelActivityTable(ChannelActivityTableModel model)
    {
        super(model);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    }

    void setUserSelectionListener(Consumer<ChannelActivityTable> listener)
    {
        mUserSelectionListener = listener;
    }

    @Override
    protected void processMouseEvent(MouseEvent event)
    {
        super.processMouseEvent(event);

        if(event.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(event))
        {
            int viewRow = rowAtPoint(event.getPoint());

            if(viewRow >= 0)
            {
                setRowSelectionInterval(viewRow, viewRow);
            }
            else
            {
                clearSelection();
            }

            notifyUserSelection();
        }
    }

    @Override
    protected void processKeyEvent(KeyEvent event)
    {
        int previousSelectedRow = getSelectedRow();
        super.processKeyEvent(event);

        if(event.getID() == KeyEvent.KEY_PRESSED && getSelectedRow() != previousSelectedRow)
        {
            notifyUserSelection();
        }
    }

    private void notifyUserSelection()
    {
        if(mUserSelectionListener != null)
        {
            mUserSelectionListener.accept(this);
        }
    }
}
