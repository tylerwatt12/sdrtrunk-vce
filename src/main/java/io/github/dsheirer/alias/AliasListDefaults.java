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

package io.github.dsheirer.alias;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Recording, browser scan-list delivery, and external streaming defaults owned by one Alias List.
 *
 * <p>The same values supply unmatched destination-talkgroup behavior and initialize newly-created talkgroup and
 * talkgroup-range Aliases.  They are deliberately not applied to source-radio or other matcher types.</p>
 */
public record AliasListDefaults(UnmatchedTalkgroupPolicy unmatchedTalkgroupPolicy, Set<Long> scanListIds)
{
    public AliasListDefaults
    {
        unmatchedTalkgroupPolicy = Objects.requireNonNull(unmatchedTalkgroupPolicy,
            "Unmatched talkgroup policy cannot be null");
        scanListIds = scanListIds != null ? Set.copyOf(new LinkedHashSet<>(scanListIds)) : Set.of();
    }

    public AliasListDefaults(UnmatchedTalkgroupPolicy unmatchedTalkgroupPolicy, Collection<Long> scanListIds)
    {
        this(unmatchedTalkgroupPolicy,
            scanListIds != null ? new LinkedHashSet<>(scanListIds) : Set.of());
    }

    public boolean isRecordEnabled()
    {
        return unmatchedTalkgroupPolicy.isRecordEnabled();
    }

    public Set<String> streamDestinationNames()
    {
        return Set.copyOf(unmatchedTalkgroupPolicy.getStreamDestinationNames());
    }
}
