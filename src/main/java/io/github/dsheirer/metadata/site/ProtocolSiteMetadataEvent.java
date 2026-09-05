/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.metadata.site;

import io.github.dsheirer.controller.channel.Channel;

/**
 * Protocol-neutral site metadata event for live consumers.
 */
public record ProtocolSiteMetadataEvent(Channel channel, SiteReceiverContext receiverContext,
                                        SiteMetadataSnapshot snapshot,
                                        long observedAtEpochMilliseconds)
{
    public ProtocolSiteMetadataEvent(Channel channel, SiteMetadataSnapshot snapshot,
                                     long observedAtEpochMilliseconds)
    {
        this(channel, snapshot, observedAtEpochMilliseconds, 0);
    }

    public ProtocolSiteMetadataEvent(Channel channel, SiteMetadataSnapshot snapshot,
                                     long observedAtEpochMilliseconds, long sourceFrequency)
    {
        this(channel, SiteReceiverContext.capture(channel,
            snapshot != null ? snapshot.protocol() : null, sourceFrequency), snapshot,
            observedAtEpochMilliseconds);
    }

    public boolean isUseful()
    {
        return receiverContext != null && receiverContext.isStandardChannel() &&
            !receiverContext.isTrafficChannel() && snapshot != null && snapshot.isUseful();
    }

    /** True only while the optional live channel still represents the captured receiver. */
    public boolean matchesCurrentChannel()
    {
        return receiverContext != null && receiverContext.matchesCurrentChannel(channel);
    }
}
