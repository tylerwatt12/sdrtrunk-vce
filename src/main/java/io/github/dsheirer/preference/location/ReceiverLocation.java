/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.location;

/**
 * Administrator-configured receiver coordinates used by location-aware directory integrations.
 */
public record ReceiverLocation(double latitude, double longitude)
{
    public ReceiverLocation
    {
        if(!Double.isFinite(latitude) || latitude < -90.0d || latitude > 90.0d)
        {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }

        if(!Double.isFinite(longitude) || longitude < -180.0d || longitude > 180.0d)
        {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }

        //Avoid persisting a surprising negative zero while preserving all meaningful coordinate precision.
        latitude = latitude == 0.0d ? 0.0d : latitude;
        longitude = longitude == 0.0d ? 0.0d : longitude;
    }
}
