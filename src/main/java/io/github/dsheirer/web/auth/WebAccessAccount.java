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
public record WebAccessAccount(String username, AccessTier tier, long passwordChangedAtEpochMillis,
                               long credentialVersion, boolean primaryAdmin)
{
    public WebAccessAccount
    {
        username = WebAdminCredential.normalizeUsername(username);
        Objects.requireNonNull(tier, "Web account tier cannot be null");

        if(!tier.isAccountTier() || passwordChangedAtEpochMillis <= 0 || credentialVersion < 1)
        {
            throw new IllegalArgumentException("Invalid web account metadata");
        }

        if(primaryAdmin != WebAccessService.PRIMARY_ADMIN_USERNAME.equals(username) || primaryAdmin && tier != AccessTier.ADMIN)
        {
            throw new IllegalArgumentException("Invalid primary web administrator metadata");
        }
    }
}
