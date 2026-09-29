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
package io.github.dsheirer.remote;

import java.util.Map;

/**
 * Read-only, immutable remote-origin projection for consumers such as the web Live view.
 *
 * <p>Implementations publish a prebuilt snapshot. Consumers must never call a listener, client, decoder, database,
 * or mutable channel object while resolving one configured channel.</p>
 */
@FunctionalInterface
public interface RemoteOriginLookup
{
    RemoteOriginLookup EMPTY = () -> OriginSnapshot.EMPTY;

    OriginSnapshot originSnapshot();

    /** One atomic view of all saved remote channel mappings and their current dependency state. */
    record OriginSnapshot(long revision, Map<String,RemoteOrigin> byChannelConfigurationId)
    {
        public static final OriginSnapshot EMPTY = new OriginSnapshot(0L, Map.of());

        public OriginSnapshot
        {
            revision = Math.max(0L, revision);
            byChannelConfigurationId = byChannelConfigurationId != null ?
                Map.copyOf(byChannelConfigurationId) : Map.of();
        }

        public RemoteOrigin find(String channelConfigurationId)
        {
            return channelConfigurationId != null ? byChannelConfigurationId.get(channelConfigurationId) : null;
        }
    }

    /** Safe operator-facing origin facts. Network endpoints and credentials are deliberately absent. */
    record RemoteOrigin(String senderId, String senderName, String feedId, String feedName,
                        RemoteLinkAdministrationService.FeedState state,
                        RemoteLinkAdministrationService.DependencyState dependencyState)
    {
    }
}
