/*
 * Copyright (C) 2026 Dennis Sheirer
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 or later.
 */
package io.github.dsheirer.module.decode.p25;

/** Full immutable same-tracker snapshot, including observed final timing when the traffic lifecycle established it. */
public record P25CallUpdateEvent(P25CallStartEvent call, boolean complete, Long durationMilliseconds)
{
    /** Older producers have no captured final timing. */
    public P25CallUpdateEvent(P25CallStartEvent call, boolean complete)
    {
        this(call, complete, null);
    }
}
