/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/** Fixed in-memory history limit for one call-matching diagnostic session. */
public record LogicalCallDiagnosticConfiguration(int recentDuplicateCapacity)
{
    public static final int DEFAULT_RECENT_DUPLICATE_CAPACITY = 256;

    public LogicalCallDiagnosticConfiguration
    {
        if(recentDuplicateCapacity < 2 || recentDuplicateCapacity > DEFAULT_RECENT_DUPLICATE_CAPACITY ||
            Integer.bitCount(recentDuplicateCapacity) != 1)
        {
            throw new IllegalArgumentException("recentDuplicateCapacity must be a power of two between 2 and " +
                DEFAULT_RECENT_DUPLICATE_CAPACITY);
        }
    }
}
