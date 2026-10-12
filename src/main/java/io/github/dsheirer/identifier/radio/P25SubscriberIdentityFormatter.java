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

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
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

    /** Human-readable identity. Canonical storage continues to use {@link #format(int, int, int)}. */
    public static String display(int homeWacn, int homeSystemId, int subscriberId, Integer workingId,
                                  String servingSystemKey)
    {
        String label = RadioSystemKey.isForeignP25(servingSystemKey, homeWacn, homeSystemId) ?
            format(homeWacn, homeSystemId, subscriberId) : Integer.toString(subscriberId);
        return workingId != null && workingId > 0 && workingId <= 0xFFFFFC && workingId != subscriberId ?
            label + " (Working ID " + workingId + ")" : label;
    }

    public static String display(Identifier<?> identifier, String servingSystemKey)
    {
        if(identifier instanceof FullyQualifiedRadioIdentifier radio)
        {
            return display(radio.getWacn(), radio.getSystem(), radio.getRadio(), radio.getWorkingAddress(),
                servingSystemKey);
        }
        if(identifier instanceof FullyQualifiedTalkgroupIdentifier group)
        {
            return RadioSystemKey.isForeignP25(servingSystemKey, group.getWacn(), group.getSystem()) ?
                group.toString() : Integer.toString(group.getValue() != 0 ? group.getValue() : group.getTalkgroup());
        }
        if(identifier instanceof PatchGroupIdentifier patch)
        {
            var group = patch.getValue();
            String label = "P:" + display(group.getPatchGroup(), servingSystemKey);
            if(group.hasPatchedTalkgroups()) label += " " + group.getPatchedTalkgroupIdentifiers().stream()
                .map(member -> display(member, servingSystemKey)).toList();
            if(group.hasPatchedRadios()) label += " " + group.getPatchedRadioIdentifiers().stream()
                .map(member -> display(member, servingSystemKey)).toList();
            return label;
        }
        return identifier != null ? identifier.toString() : "";
    }
}
