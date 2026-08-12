/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.controller.channel.Channel;
import java.util.List;

/**
 * Immutable desktop-facing view of one activity table.  It contains detached row copies so a Swing renderer never
 * reads state that is being changed by the activity worker.
 */
record ChannelActivityTableView(String tableId, String title, Channel ownerChannel, boolean closeable,
                                boolean controlActive, List<ChannelActivityRow> rows)
{
    ChannelActivityTableView
    {
        tableId = tableId != null ? tableId : "";
        title = title != null ? title : "";
        rows = rows != null ? List.copyOf(rows) : List.of();
    }
}
