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

package io.github.dsheirer.module.decode.p25.bandplan;

import io.github.dsheirer.module.decode.p25.P25FrequencyBandValidator;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import java.util.Objects;

/**
 * One manually configured P25 bandplan row. Frequencies are stored in Hertz.
 */
public record P25BandplanOverrideBand(int identifier, P25BandplanChannelType type, long baseFrequency,
                                      int bandwidth, long channelSpacing, long transmitOffset)
{
    public P25BandplanOverrideBand
    {
        Objects.requireNonNull(type, "P25 bandplan channel type is required");
        P25FrequencyBand band = new P25FrequencyBand(identifier, baseFrequency, transmitOffset, channelSpacing,
            bandwidth, type.getTimeslotCount());
        P25FrequencyBandValidator.RejectReason rejectReason = P25FrequencyBandValidator.validate(band);

        if(rejectReason != null)
        {
            throw new IllegalArgumentException("Invalid P25 bandplan row: " +
                rejectReason.getDescription());
        }

        if(bandwidth <= 0)
        {
            throw new IllegalArgumentException("P25 bandplan bandwidth must be greater than zero");
        }
    }

    public P25FrequencyBand toFrequencyBand()
    {
        return new P25FrequencyBand(identifier, baseFrequency, transmitOffset, channelSpacing, bandwidth,
            type.getTimeslotCount());
    }
}
