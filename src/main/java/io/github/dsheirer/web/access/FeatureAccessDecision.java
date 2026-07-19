/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

import java.util.Objects;

/**
 * Immutable gateway result.  HTTP, SSE, media, and WebSocket adapters consume the same outcome and may map an
 * authentication-required result to their transport-specific response or disconnect behavior.
 */
public record FeatureAccessDecision(FeatureAccessRequest request, FeatureAccessMode configuredMode, long policyRevision,
                                    Outcome outcome)
{
    public enum Outcome
    {
        ALLOWED,
        AUTHENTICATION_REQUIRED
    }

    public FeatureAccessDecision
    {
        Objects.requireNonNull(request, "Access request cannot be null");
        Objects.requireNonNull(configuredMode, "Configured mode cannot be null");
        Objects.requireNonNull(outcome, "Access outcome cannot be null");

        if(policyRevision < 0)
        {
            throw new IllegalArgumentException("Policy revision cannot be negative");
        }
    }

    public boolean isAllowed()
    {
        return outcome == Outcome.ALLOWED;
    }
}
