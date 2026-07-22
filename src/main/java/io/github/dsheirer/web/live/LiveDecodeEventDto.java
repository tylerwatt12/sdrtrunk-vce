/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.live;

import java.util.List;

/**
 * Immutable, bounded browser representation of one decoder event.
 */
public record LiveDecodeEventDto(String id, long generation, long timeStartMs, long durationMs,
                                 String eventType, String eventLabel, String category, String protocol,
                                 List<LiveIdentifierDto> from, List<LiveIdentifierDto> to,
                                 String channel, long frequencyHz, Integer timeslot, String details)
{
    public LiveDecodeEventDto
    {
        from = from != null ? List.copyOf(from) : List.of();
        to = to != null ? List.copyOf(to) : List.of();
    }
}
