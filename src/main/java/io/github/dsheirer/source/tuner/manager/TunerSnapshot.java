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

package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;
import java.util.Objects;

/**
 * Immutable, read-only description of one currently discovered tuner.
 *
 * <p>This value contains no mutable tuner, controller, USB, or configuration object.  It is safe to map into an
 * administrator API response.  Snapshot construction uses only passive in-memory status getters; it never requests
 * hardware identity or performs USB control I/O.  Nullable measurements are unavailable when the receiver is
 * disabled, disconnected, or failed during its snapshot.</p>
 *
 * @param id stable opaque receiver ID suitable for routes and spectrum target selection
 * @param label current human-readable receiver label
 * @param tunerClass broad receiver family
 * @param tunerType detected receiver model/type
 * @param status current discovery status
 * @param enabled configured discovery state
 * @param available receiver is enabled and has a usable initialized tuner
 * @param hardwareIdentifier administrator-only hardware identifier, when an initialized tuner supplies one
 * @param centerFrequencyHz current center frequency, when available
 * @param sampleRateHz current sample rate, when available
 * @param activeChannelCount current allocated channel count, when available
 * @param sampleRateLocked true when active processing currently locks disruptive frequency/sample-rate changes
 * @param centerFrequencyFixed current saved fixed-center choice, when a tuner configuration is available
 * @param errorMessage bounded current error text, when present
 */
public record TunerSnapshot(String id, String label, TunerClass tunerClass, TunerType tunerType, TunerStatus status,
                            boolean enabled, boolean available, String hardwareIdentifier, Long centerFrequencyHz,
                            Long sampleRateHz, Integer activeChannelCount, Boolean sampleRateLocked,
                            Boolean centerFrequencyFixed, String errorMessage)
{
    public TunerSnapshot
    {
        Objects.requireNonNull(id, "Tuner snapshot ID cannot be null");
        Objects.requireNonNull(label, "Tuner snapshot label cannot be null");
        Objects.requireNonNull(tunerClass, "Tuner snapshot class cannot be null");
        Objects.requireNonNull(tunerType, "Tuner snapshot type cannot be null");
        Objects.requireNonNull(status, "Tuner snapshot status cannot be null");

        if(!id.matches("TNR_[A-F0-9]{28}"))
        {
            throw new IllegalArgumentException("Tuner snapshot ID is not a valid opaque receiver ID");
        }

        label = label.strip();

        if(label.isEmpty())
        {
            throw new IllegalArgumentException("Tuner snapshot label cannot be blank");
        }
    }
}
