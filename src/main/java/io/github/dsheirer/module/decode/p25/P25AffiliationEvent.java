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
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.reference.Response;

/**
 * Structured P25 radio affiliation event.  Radio and talkgroup values are carried separately from identifier roles
 * because outbound affiliation responses address the radio as the message target.
 */
public class P25AffiliationEvent extends P25DecodeEvent
{
    public enum Outcome
    {
        REQUESTED,
        ACCEPTED,
        CONFIRMED,
        REJECTED,
        CLEARED,
        UNRESOLVED;

        public static Outcome from(Response response)
        {
            if(response == Response.ACCEPTED)
            {
                return ACCEPTED;
            }

            if(response == Response.FAILED || response == Response.DENIED || response == Response.REFUSED)
            {
                return REJECTED;
            }

            return UNRESOLVED;
        }
    }

    private final Outcome mOutcome;
    private final Identifier<?> mRadio;
    private final Identifier<?> mTalkgroup;
    private final Integer mRadioId;
    private final Integer mTalkgroupId;

    public P25AffiliationEvent(DecodeEventType eventType, long timestamp, Outcome outcome, Identifier<?> radio,
                               Identifier<?> talkgroup)
    {
        super(eventType, timestamp);
        mOutcome = outcome;
        mRadio = radio;
        mTalkgroup = talkgroup;
        mRadioId = integerValue(radio);
        mTalkgroupId = integerValue(talkgroup);
    }

    public Outcome getOutcome()
    {
        return mOutcome;
    }

    public Integer getRadioId()
    {
        return mRadioId;
    }

    /** Original over-the-air radio identity, including a fully-qualified home tuple when supplied. */
    public Identifier<?> getRadioIdentifier()
    {
        return mRadio;
    }

    public Integer getTalkgroupId()
    {
        return mTalkgroupId;
    }

    /** Original over-the-air group identity, including a fully-qualified home tuple when supplied. */
    public Identifier<?> getTalkgroupIdentifier()
    {
        return mTalkgroup;
    }

    private static Integer integerValue(Identifier<?> identifier)
    {
        return identifier != null && identifier.getValue() instanceof Number number ? number.intValue() : null;
    }
}
