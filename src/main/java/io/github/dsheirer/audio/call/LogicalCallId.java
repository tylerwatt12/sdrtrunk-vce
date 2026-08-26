/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.call;

/**
 * Process-local identity for one resolved logical call.  A logical call can contain receiver legs from several
 * channels and sites.  This identity exists to keep completion and output notifications idempotent; it is not a
 * database key and must not be persisted.
 */
public record LogicalCallId(long coordinatorId, long sequence)
{
    public LogicalCallId
    {
        if(sequence <= 0)
        {
            throw new IllegalArgumentException("Logical call sequence must be positive");
        }
    }

    @Override
    public String toString()
    {
        return coordinatorId + ":" + sequence;
    }
}
