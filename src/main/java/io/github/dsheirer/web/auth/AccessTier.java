/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.util.Objects;

/**
 * Monotonic web-access tiers.  Anonymous requests use {@link #PUBLIC}; persisted user accounts may use only
 * {@link #USER} or {@link #ADMIN}.
 */
public enum AccessTier
{
    PUBLIC(0),
    USER(1),
    ADMIN(2);

    private final int mRank;

    AccessTier(int rank)
    {
        mRank = rank;
    }

    /**
     * Indicates whether this tier satisfies the required tier.
     */
    public boolean allows(AccessTier requiredTier)
    {
        return mRank >= Objects.requireNonNull(requiredTier, "Required access tier cannot be null").mRank;
    }

    /**
     * User accounts cannot be assigned the anonymous/public tier.
     */
    public boolean isAccountTier()
    {
        return this == USER || this == ADMIN;
    }
}
