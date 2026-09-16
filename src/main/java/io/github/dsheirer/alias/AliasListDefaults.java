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
 * Independent unknown-call and newly-created Alias behaviors owned by one Alias List.
 */
public record AliasListDefaults(UnmatchedTalkgroupPolicy unknownAliasBehavior, Set<Long> unknownScanListIds,
                                NewAliasBehavior newAliasBehavior, Set<Long> newAliasScanListIds)
{
    public AliasListDefaults
    {
        unknownAliasBehavior = Objects.requireNonNull(unknownAliasBehavior,
            "Unmatched talkgroup policy cannot be null");
        unknownScanListIds = immutable(unknownScanListIds);
        newAliasBehavior = Objects.requireNonNull(newAliasBehavior, "New Alias behavior cannot be null");
        newAliasScanListIds = immutable(newAliasScanListIds);
    }

    public AliasListDefaults(UnmatchedTalkgroupPolicy unknownAliasBehavior, Collection<Long> unknownScanListIds,
                             NewAliasBehavior newAliasBehavior, Collection<Long> newAliasScanListIds)
    {
        this(unknownAliasBehavior, mutable(unknownScanListIds), newAliasBehavior, mutable(newAliasScanListIds));
    }

    /** Compatibility constructor for callers that still supply one shared behavior. */
    public AliasListDefaults(UnmatchedTalkgroupPolicy behavior, Collection<Long> scanListIds)
    {
        this(behavior, scanListIds, NewAliasBehavior.copyOf(behavior), scanListIds);
    }

    /** Compatibility name for the former shared scan-list defaults. */
    public Set<Long> scanListIds()
    {
        return unknownScanListIds;
    }

    private static Set<Long> immutable(Collection<Long> values)
    {
        return values != null ? Set.copyOf(new LinkedHashSet<>(values)) : Set.of();
    }

    private static Set<Long> mutable(Collection<Long> values)
    {
        return values != null ? new LinkedHashSet<>(values) : Set.of();
    }
}
