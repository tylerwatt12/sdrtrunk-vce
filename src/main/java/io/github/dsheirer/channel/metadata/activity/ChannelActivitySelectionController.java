/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.controller.channel.Channel;

/**
 * Single source of truth for the user's Now Playing activity selection.  JTable row indexes are deliberately not
 * stored here because they are transient presentation state that changes whenever the live activity model changes.
 */
class ChannelActivitySelectionController
{
    record Selection(ChannelActivityTableModel tableModel, ChannelActivitySelectionDescriptor descriptor,
                     Channel ownerChannel, Channel rowChannel)
    {
        boolean isSite()
        {
            return descriptor.isSite();
        }

        String rowKey()
        {
            return descriptor.rowKey();
        }

        String selectionId()
        {
            return descriptor.selectionId();
        }

        ChannelActivitySelectionScope scope()
        {
            return descriptor.scope();
        }

        long frequency()
        {
            return descriptor.frequencyHz();
        }

        Integer timeslot()
        {
            return descriptor.timeslot();
        }

        String decoderHint()
        {
            return descriptor.decoderHint();
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
            ChannelActivitySelectionScope.EXACT_FREQUENCY;
        mSelection = selection(tableModel, row, scope);
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
        ChannelActivityRow row = model.get(mSelection.rowKey());

        if(mSelection.isSite() && !isSiteControl(row))
        {
            row = findPreferredSiteControl(model);
        }

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
        ChannelActivitySelectionDescriptor descriptor = ChannelActivitySelectionFactory.from(tableModel, row);

        if(descriptor.scope() != scope)
        {
            throw new IllegalStateException("Activity selection scope changed unexpectedly");
        }

        return new Selection(tableModel, descriptor, tableModel.getOwnerChannel(), row.getChannel());
    }

    static boolean isSiteControl(ChannelActivityRow row)
    {
        return ChannelActivitySelectionFactory.isSiteControl(row);
    }

    static ChannelActivityRow findPreferredSiteControl(ChannelActivityTableModel model)
    {
        ChannelActivityRow configured = null;
        ChannelActivityRow alternate = null;

        if(model != null)
        {
            for(ChannelActivityRow row: model.getRows())
            {
                if(row.hasTag(ChannelTag.CURRENT_CONTROL) || row.getRole() == ChannelActivityRow.Role.CURRENT_CONTROL)
                {
                    return row;
                }

                if(configured == null && (row.hasTag(ChannelTag.CONFIGURED) ||
                    row.getRole() == ChannelActivityRow.Role.CONFIGURED_CONTROL))
                {
                    configured = row;
                }

                if(alternate == null && (row.hasTag(ChannelTag.ALTERNATE_CONTROL) ||
                    row.getRole() == ChannelActivityRow.Role.ALTERNATE_CONTROL))
                {
                    alternate = row;
                }
            }
        }

        return configured != null ? configured : alternate;
    }
}
