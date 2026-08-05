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

import io.github.dsheirer.alias.id.priority.Priority;
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
 * identity while this policy supplies only playback, recording, and streaming behavior.</p>
 */
public final class UnmatchedTalkgroupPolicy
{
    public static final UnmatchedTalkgroupPolicy DEFAULT =
        new UnmatchedTalkgroupPolicy(Priority.DEFAULT_PRIORITY, false, List.of());

    private final int mPlaybackPriority;
    private final boolean mRecordEnabled;
    private final List<String> mStreamDestinationNames;

    public UnmatchedTalkgroupPolicy(int playbackPriority, boolean recordEnabled,
                                    Collection<String> streamDestinationNames)
    {
        if(!isValidPlaybackPriority(playbackPriority))
        {
            throw new IllegalArgumentException("Unmatched talkgroup playback priority must be -1 or 1 through 100");
        }

        Set<String> destinations = new LinkedHashSet<>();

        if(streamDestinationNames != null)
        {
            for(String destination: streamDestinationNames)
            {
                if(destination == null || destination.isBlank())
                {
                    throw new IllegalArgumentException("Unmatched talkgroup stream destinations must be nonblank");
                }

                String normalized = destination.strip();
                if(!destinations.add(normalized))
                {
                    throw new IllegalArgumentException("Duplicate unmatched talkgroup stream destination [" +
                        normalized + "]");
                }
            }
        }

        mPlaybackPriority = playbackPriority;
        mRecordEnabled = recordEnabled;
        mStreamDestinationNames = List.copyOf(new ArrayList<>(destinations));
    }

    public int getPlaybackPriority()
    {
        return mPlaybackPriority;
    }

    public boolean isRecordEnabled()
    {
        return mRecordEnabled;
    }

    public List<String> getStreamDestinationNames()
    {
        return mStreamDestinationNames;
    }

    public static boolean isValidPlaybackPriority(int priority)
    {
        return priority == Priority.DO_NOT_MONITOR ||
            Priority.MIN_PRIORITY <= priority && priority <= Priority.MAX_PRIORITY;
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
        return mPlaybackPriority == other.mPlaybackPriority && mRecordEnabled == other.mRecordEnabled &&
            mStreamDestinationNames.equals(other.mStreamDestinationNames);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mPlaybackPriority, mRecordEnabled, mStreamDestinationNames);
    }
}
