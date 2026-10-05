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
package io.github.dsheirer.identifier.radio;

import java.util.Locale;

/**
 * One display formatter for a P25 fully-qualified subscriber identity.
 *
 * <p>Storage and APIs retain the three numeric components.  Presentation uses fixed-width uppercase hexadecimal for
 * WACN and System ID and decimal for the subscriber ID.  This formatter accepts every decoded 24-bit value so
 * logging and diagnostics remain safe for reserved and infrastructure addresses. Admission to the canonical
 * subscriber directory applies the narrower assignable-subscriber rules separately.</p>
 */
public final class P25SubscriberIdentityFormatter
{
    private P25SubscriberIdentityFormatter()
    {
    }

    public static String format(int homeWacn, int homeSystemId, int subscriberId)
    {
        if(homeWacn < 0 || homeWacn > 0xFFFFF || homeSystemId < 0 || homeSystemId > 0xFFF ||
            subscriberId < 0 || subscriberId > 0xFFFFFF)
        {
            throw new IllegalArgumentException("Invalid P25 fully-qualified subscriber identity");
        }

        return String.format(Locale.ROOT, "%05X.%03X.%d", homeWacn, homeSystemId, subscriberId);
    }
}
