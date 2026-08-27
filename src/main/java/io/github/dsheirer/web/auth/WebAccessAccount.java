/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.util.Objects;

/**
 * Public, verifier-free account metadata used for user management and authenticated principals.
 */
public record WebAccessAccount(long id, String username, AccessTier tier, long passwordChangedAtEpochMillis,
                               long authRevision, boolean primaryAdmin)
{
    public WebAccessAccount
    {
        username = WebPasswordVerifier.normalizeUsername(username);
        Objects.requireNonNull(tier, "Web account tier cannot be null");

        if(id <= 0 || !tier.isAccountTier() || passwordChangedAtEpochMillis <= 0 || authRevision < 1)
        {
            throw new IllegalArgumentException("Invalid web account metadata");
        }

        if(primaryAdmin != WebAccessService.PRIMARY_ADMIN_USERNAME.equals(username) || primaryAdmin && tier != AccessTier.ADMIN)
        {
            throw new IllegalArgumentException("Invalid primary web administrator metadata");
        }
    }
}
