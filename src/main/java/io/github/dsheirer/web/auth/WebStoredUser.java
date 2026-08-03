/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.util.Objects;

/**
 * Persisted ordinary-user role and password verifier.
 */
record WebStoredUser(AccessTier tier, WebAdminCredential credential)
{
    WebStoredUser
    {
        Objects.requireNonNull(tier, "Web user tier cannot be null");
        Objects.requireNonNull(credential, "Web user credential cannot be null");

        if(!tier.isAccountTier())
        {
            throw new IllegalArgumentException("Persisted web users must have USER or ADMIN access");
        }

        if(WebAccessService.PRIMARY_ADMIN_USERNAME.equals(credential.username()))
        {
            throw new IllegalArgumentException("The primary administrator cannot be stored as an ordinary user");
        }
    }

    WebStoredUser withTier(AccessTier replacementTier, long replacementCredentialVersion)
    {
        return new WebStoredUser(replacementTier, credential.withCredentialVersion(replacementCredentialVersion));
    }

    WebStoredUser withCredential(WebAdminCredential replacementCredential)
    {
        return new WebStoredUser(tier, replacementCredential);
    }
}
