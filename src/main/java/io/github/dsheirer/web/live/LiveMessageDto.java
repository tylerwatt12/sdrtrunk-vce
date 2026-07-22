/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.live;

import java.util.List;

/**
 * Immutable, bounded browser representation of one decoder message.
 */
public record LiveMessageDto(String id, long generation, long sequence, long timestampMs, boolean valid,
                             String protocol, Integer timeslot, String category, String text,
                             List<LiveIdentifierDto> identifiers)
{
    public LiveMessageDto
    {
        identifiers = identifiers != null ? List.copyOf(identifiers) : List.of();
    }
}
