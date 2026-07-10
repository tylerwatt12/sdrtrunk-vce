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

package io.github.dsheirer.stats;

/**
 * HTTP-safe API error. Messages must not contain SQL, credentials, or private configuration values.
 */
class StatsApiException extends RuntimeException
{
    private final int mStatus;

    StatsApiException(int status, String message)
    {
        super(message);
        mStatus = status;
    }

    int status()
    {
        return mStatus;
    }
}
