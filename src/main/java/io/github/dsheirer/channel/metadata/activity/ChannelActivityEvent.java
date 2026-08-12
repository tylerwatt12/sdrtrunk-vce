/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

/**
 * Adds, updates, or removes one shared Systems activity table.
 */
public record ChannelActivityEvent(Operation operation, ChannelActivitySnapshot snapshot, long revision)
{
    public ChannelActivityEvent(Operation operation, ChannelActivitySnapshot snapshot)
    {
        this(operation, snapshot, 0);
    }

    public enum Operation
    {
        UPSERT,
        REMOVE
    }
}
