/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

/**
 * Single source of truth for the user's Now Playing activity selection.  JTable row indexes are deliberately not
 * stored here because they are transient presentation state that changes whenever the live activity model changes.
 */
class ChannelActivitySelectionController
{
    record Selection(ChannelActivityTableModel tableModel, String rowKey, ChannelActivitySelectionScope scope)
    {
        boolean isSite()
        {
            return scope == ChannelActivitySelectionScope.SITE;
        }
    }

    private Selection mSelection;

    Selection select(ChannelActivityTableModel tableModel, ChannelActivityRow row)
    {
        if(tableModel == null || row == null)
        {
            return clear();
        }

        ChannelActivitySelectionScope scope = isSiteControl(row) ? ChannelActivitySelectionScope.SITE :
            ChannelActivitySelectionScope.EXACT;
        mSelection = selection(tableModel, row, scope);

        if(mSelection.isSite())
        {
            resolveSelectedRow();
        }

        return mSelection;
    }

    Selection clear()
    {
        mSelection = null;
        return null;
    }

    Selection getSelection()
    {
        return mSelection;
    }

    boolean isSelected(ChannelActivityTableModel tableModel)
    {
        return mSelection != null && mSelection.tableModel() == tableModel;
    }

    /**
     * Clears a selection that belongs to a table other than the currently visible Systems table.
     *
     * @param visibleTableModel currently visible Conventional or trunked-site table, or null when no table is visible
     * @return true when an existing selection was cleared
     */
    boolean clearIfSelectionIsOutside(ChannelActivityTableModel visibleTableModel)
    {
        if(mSelection != null && !isSelected(visibleTableModel))
        {
            clear();
            return true;
        }

        return false;
    }

    /**
     * Resolves the selected domain row after a model mutation.  An exact-frequency selection ends when its row is
     * removed.  A site selection remains selected through an empty-table interval and binds to a replacement control
     * row when one becomes available.
     */
    ChannelActivityRow resolveSelectedRow()
    {
        if(mSelection == null)
        {
            return null;
        }

        ChannelActivityTableModel model = mSelection.tableModel();
        ChannelActivityRow row = mSelection.isSite() ? findCurrentSiteControl(model) :
            model.get(mSelection.rowKey());

        if(row != null)
        {
            mSelection = selection(model, row, mSelection.scope());
        }
        else if(!mSelection.isSite())
        {
            clear();
        }

        return row;
    }

    private static Selection selection(ChannelActivityTableModel tableModel, ChannelActivityRow row,
                                       ChannelActivitySelectionScope scope)
    {
        return new Selection(tableModel, row.getKey(), scope);
    }

    static boolean isSiteControl(ChannelActivityRow row)
    {
        return row != null && row.isControlRow();
    }

    static ChannelActivityRow findCurrentSiteControl(ChannelActivityTableModel model)
    {
        if(model != null)
        {
            for(ChannelActivityRow row: model.getRows())
            {
                if(row.getRole() == ChannelActivityRow.Role.CURRENT_CONTROL)
                {
                    return row;
                }
            }
        }

        return null;
    }
}
