/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

/**
 * Renderer-neutral lifecycle event for an activity table.
 */
public record ChannelActivityTableEvent(Operation operation, ChannelActivityTableState table)
{
    public enum Operation
    {
        ADD,
        UPDATE,
        REMOVE
    }
}
