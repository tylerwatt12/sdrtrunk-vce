/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

import java.util.Objects;

/**
 * One committed access-policy change.
 */
public record FeaturePolicyChange(WebFeature feature, FeatureAccessMode previousMode, FeatureAccessMode currentMode,
                                  long previousRevision, long revision)
{
    public FeaturePolicyChange
    {
        Objects.requireNonNull(feature, "Feature cannot be null");
        Objects.requireNonNull(previousMode, "Previous mode cannot be null");
        Objects.requireNonNull(currentMode, "Current mode cannot be null");

        if(previousRevision < 0 || revision != previousRevision + 1)
        {
            throw new IllegalArgumentException("Policy change revisions must be consecutive and non-negative");
        }

        if(previousMode == currentMode)
        {
            throw new IllegalArgumentException("Policy change must alter the feature mode");
        }
    }

    /**
     * Indicates that live transports must revoke existing anonymous subscriptions for this feature.
     */
    public boolean revokesAnonymousAccess()
    {
        return previousMode == FeatureAccessMode.PUBLIC && currentMode == FeatureAccessMode.ADMIN_ONLY;
    }
}
