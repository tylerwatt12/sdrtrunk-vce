/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

/**
 * Adds, updates, or removes one shared Systems activity table.
 */
public record ChannelActivityEvent(Operation operation, ChannelActivitySnapshot snapshot)
{
    public enum Operation
    {
        UPSERT,
        REMOVE
    }
}
