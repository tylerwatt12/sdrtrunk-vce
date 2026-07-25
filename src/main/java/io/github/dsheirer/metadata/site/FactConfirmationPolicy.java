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

package io.github.dsheirer.metadata.site;

/**
 * Confirmation requirements for promoting an observed value into trusted state.
 *
 * @param requiredObservations number of matching, increasing-timestamp observations required.
 * @param minimumSpanMilliseconds minimum time from first to final observation.
 * @param candidateTtlMilliseconds maximum idle time before an untrusted candidate is discarded.
 * @param trustInitialValue allows the first value to be promoted immediately when no trusted value exists.
 */
public record FactConfirmationPolicy(int requiredObservations, long minimumSpanMilliseconds,
                                     long candidateTtlMilliseconds, boolean trustInitialValue)
{
    public FactConfirmationPolicy
    {
        if(requiredObservations < 1)
        {
            throw new IllegalArgumentException("Required observations must be at least one");
        }

        if(minimumSpanMilliseconds < 0 || candidateTtlMilliseconds < 0)
        {
            throw new IllegalArgumentException("Confirmation durations cannot be negative");
        }
    }
}
