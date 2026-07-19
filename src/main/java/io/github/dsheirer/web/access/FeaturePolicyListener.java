/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

/**
 * Listener for committed feature policy changes.  Implementations should perform only bounded, non-blocking work such
 * as marking affected live subscriptions for closure.
 */
@FunctionalInterface
public interface FeaturePolicyListener
{
    void policyChanged(FeaturePolicyChange change);
}
