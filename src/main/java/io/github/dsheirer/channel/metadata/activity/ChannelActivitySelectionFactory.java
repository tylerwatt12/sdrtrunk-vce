/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.controller.channel.Channel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Creates the shared stable identity used by Swing selection and browser Live selection.
 */
public final class ChannelActivitySelectionFactory
{
    private static final String CONVENTIONAL_TABLE_ID = "conventional";

    private ChannelActivitySelectionFactory()
    {
    }

    public static ChannelActivitySelectionDescriptor from(ChannelActivityTableModel table,
                                                           ChannelActivityRow row)
    {
        if(table == null || row == null)
        {
            throw new IllegalArgumentException("Activity table and row are required");
        }

        String tableId = tableId(table);
        Channel owner = table.getOwnerChannel();
        Channel rowChannel = row.getChannel();
        ChannelActivitySelectionScope scope = scope(row);
        String stableInput = scope == ChannelActivitySelectionScope.SITE ? tableId : tableId + ":" + row.getKey();
        String prefix = scope == ChannelActivitySelectionScope.SITE ? "site-" : "exact-";
        String selectionId = prefix + UUID.nameUUIDFromBytes(stableInput.getBytes(StandardCharsets.UTF_8));

        return new ChannelActivitySelectionDescriptor(selectionId, tableId, row.getKey(), table.getTitle(),
            row.getChannelName(), scope,
            owner != null ? owner.getChannelID() : null, rowChannel != null ? rowChannel.getChannelID() : null,
            row.getFrequency(), row.getTimeslot(), row.getDecoder());
    }

    public static String tableId(ChannelActivityTableModel table)
    {
        Channel owner = table != null ? table.getOwnerChannel() : null;
        return owner != null ? "channel-" + owner.getChannelID() : CONVENTIONAL_TABLE_ID;
    }

    public static ChannelActivitySelectionScope scope(ChannelActivityRow row)
    {
        return isSiteControl(row) ? ChannelActivitySelectionScope.SITE :
            ChannelActivitySelectionScope.EXACT_FREQUENCY;
    }

    public static boolean isSiteControl(ChannelActivityRow row)
    {
        return row != null && (row.isControlRow() || row.hasTag(ChannelTag.CONFIGURED) ||
            row.hasTag(ChannelTag.CURRENT_CONTROL) || row.hasTag(ChannelTag.ALTERNATE_CONTROL));
    }
}
