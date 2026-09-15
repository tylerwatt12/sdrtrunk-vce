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
import java.util.function.Supplier;

/**
 * Protocol-neutral deferred snapshot request.  The decoder callback captures only receiver/tuning facts and a
 * supplier reference; snapshot construction and usefulness checks run on the bounded observer worker.
 */
public record ProtocolSiteMetadataSnapshotRequest(Channel channel, SiteReceiverContext receiverContext,
                                                  Supplier<? extends SiteMetadataSnapshot> snapshotSupplier,
                                                  long observedAtEpochMilliseconds)
{
    /** Resolves only while the captured receiver generation is still current. Runs on the observer worker. */
    public ProtocolSiteMetadataEvent resolve()
    {
        if(receiverContext == null || snapshotSupplier == null ||
            !receiverContext.matchesCurrentChannel(channel))
        {
            return null;
        }

        SiteMetadataSnapshot snapshot = snapshotSupplier.get();

        if(snapshot == null || !snapshot.isUseful())
        {
            return null;
        }

        return new ProtocolSiteMetadataEvent(channel, receiverContext, snapshot, observedAtEpochMilliseconds);
    }
}
