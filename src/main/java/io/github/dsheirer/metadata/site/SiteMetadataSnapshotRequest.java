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
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.util.function.Supplier;

/**
 * Small producer-time request that defers the potentially expensive P25 snapshot copy to the bounded site-metadata
 * observer worker.  Receiver identity and tuning facts are frozen before the request leaves the decoder callback.
 */
public record SiteMetadataSnapshotRequest(Channel channel, SiteReceiverContext receiverContext,
                                          Supplier<P25NetworkConfigurationSnapshot> snapshotSupplier,
                                          long observedAtEpochMilliseconds)
{
    /** Resolves only while the captured receiver generation is still current. Runs on the observer worker. */
    public SiteMetadataEvent resolve()
    {
        if(receiverContext == null || snapshotSupplier == null ||
            !receiverContext.matchesCurrentChannel(channel))
        {
            return null;
        }

        P25NetworkConfigurationSnapshot snapshot = snapshotSupplier.get();

        if(snapshot == null || !snapshot.isUseful())
        {
            return null;
        }

        return new SiteMetadataEvent(channel, receiverContext, snapshot, observedAtEpochMilliseconds);
    }
}
