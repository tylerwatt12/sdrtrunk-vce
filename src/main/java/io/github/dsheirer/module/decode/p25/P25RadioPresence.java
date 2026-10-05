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
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;

/**
 * Structured P25 subscriber evidence carried by registration and affiliation events.
 *
 * <p>The working ID is nullable because many fully-qualified messages carry only a permanent subscriber identity.
 * Its presence is determined by the message field layout at the decode call site, never by comparing the working
 * value to the subscriber ID.  This preserves the valid case where both values happen to be equal.</p>
 */
public record P25RadioPresence(P25SubscriberIdentity subscriber, Integer workingId)
{
    public P25RadioPresence
    {
        if(workingId != null && (workingId < 1 ||
            workingId > RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID))
        {
            throw new IllegalArgumentException("Invalid P25 working unit ID");
        }

        if(subscriber == null && workingId == null)
        {
            throw new IllegalArgumentException("P25 radio presence requires a subscriber or working unit ID");
        }
    }

    /**
     * Builds structured evidence from an explicit message-field decision made by the caller.
     *
     * @param identifier complete or ordinary radio identifier carried for display and canonical identity
     * @param explicitWorkingId working-address field carried by the message, or null when the message has no such
     * field
     * @return usable structured evidence, or null when neither component is valid
     */
    public static P25RadioPresence from(Identifier<?> identifier, Integer explicitWorkingId)
    {
        P25SubscriberIdentity subscriber = P25SubscriberIdentity.from(identifier);
        Integer carriedWorkingId = explicitWorkingId != null ? explicitWorkingId :
            identifier instanceof FullyQualifiedRadioIdentifier fullyQualified ?
                fullyQualified.getWorkingAddress() : null;
        Integer workingId = carriedWorkingId != null && carriedWorkingId >= 1 &&
            carriedWorkingId <= RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID ? carriedWorkingId : null;
        return subscriber != null || workingId != null ? new P25RadioPresence(subscriber, workingId) : null;
    }
}
