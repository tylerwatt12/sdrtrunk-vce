/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.channel.rotation;

import io.github.dsheirer.module.ModuleEventBusMessage;

/**
 * Requests that a multi-frequency source select a specific configured frequency.
 *
 * <p>The request is posted from decoder callbacks.  Subscribers must only perform a fixed, nonblocking handoff and
 * defer tuner work to a lifecycle worker.</p>
 */
public record ChannelRotationFrequencySelectionRequest(long frequency) implements ModuleEventBusMessage
{
    public ChannelRotationFrequencySelectionRequest
    {
        if(frequency <= 0)
        {
            throw new IllegalArgumentException("Frequency must be greater than zero");
        }
    }
}
