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

package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable audio behavior for a talkgroup or patch group that has no matching alias in an alias list.
 *
 * <p>This policy deliberately contains no alias identity or matcher. The received talkgroup remains the call's
 * identity while this policy supplies only recording and streaming behavior. Scan-list membership is normalized
 * separately around the durable Alias List ID.</p>
 */
public final class UnmatchedTalkgroupPolicy
{
    public static final UnmatchedTalkgroupPolicy DEFAULT = new UnmatchedTalkgroupPolicy(false, List.of());

    private final boolean mRecordEnabled;
    private final List<BroadcastChannel> mStreamDestinations;

    public UnmatchedTalkgroupPolicy(boolean recordEnabled, Collection<BroadcastChannel> streamDestinations)
    {
        Set<String> destinations = new LinkedHashSet<>();
        List<BroadcastChannel> routes = new ArrayList<>();

        if(streamDestinations != null)
        {
            for(BroadcastChannel destination: streamDestinations)
            {
                if(destination == null || !destination.isValid() && !destination.hasDisplayName())
                {
                    throw new IllegalArgumentException("Unmatched talkgroup stream destinations must be valid");
                }

                String key = destination.getConfigurationId() != null ? destination.getConfigurationId() :
                    "legacy-name:" + destination.getChannelName().strip();
                if(!destinations.add(key))
                {
                    throw new IllegalArgumentException("Duplicate unmatched talkgroup stream destination [" +
                        destination + "]");
                }
                routes.add(new BroadcastChannel(destination.getConfigurationId(), destination.getChannelName()));
            }
        }

        mRecordEnabled = recordEnabled;
        mStreamDestinations = List.copyOf(routes);
    }

    public boolean isRecordEnabled()
    {
        return mRecordEnabled;
    }

    public List<BroadcastChannel> getStreamDestinations()
    {
        return mStreamDestinations;
    }

    @Override
    public boolean equals(Object object)
    {
        if(this == object)
        {
            return true;
        }
        if(!(object instanceof UnmatchedTalkgroupPolicy other))
        {
            return false;
        }
        return mRecordEnabled == other.mRecordEnabled && mStreamDestinations.equals(other.mStreamDestinations);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mRecordEnabled, mStreamDestinations);
    }
}
