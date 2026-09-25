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
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;

/**
 * Shared semantic gate for authoritative P25 radio-presence observations.
 *
 * <p>Decoder strings and generic registration/call events are deliberately excluded.  Only the structured
 * {@link P25AffiliationEvent} outcome is accepted, which keeps persistence and live web consumers from developing
 * different interpretations of a request, denial, registration, or affiliation response.</p>
 */
public final class P25AffiliationSemantics
{
    public enum Kind
    {
        AFFILIATION_OBSERVED,
        PRESENCE_OBSERVED,
        PRESENCE_CLEARED,
        IGNORED
    }

    public enum Evidence
    {
        AFFILIATION,
        REGISTRATION,
        CLEAR
    }

    public record Observation(Kind kind, Evidence evidence)
    {
        private static final Observation IGNORED = new Observation(Kind.IGNORED, Evidence.AFFILIATION);

        public boolean isObserved()
        {
            return kind != Kind.IGNORED;
        }
    }

    private P25AffiliationSemantics()
    {
    }

    /**
     * Allocation-free producer-side screen for outcomes that can possibly become an authoritative observation.
     * Identifier validation remains on the observer worker in {@link #evaluate(P25AffiliationEvent)}.
     */
    public static boolean mayProduceObservation(P25AffiliationEvent event)
    {
        if(event == null)
        {
            return false;
        }

        P25AffiliationEvent.Outcome outcome = event.getOutcome();
        return outcome == P25AffiliationEvent.Outcome.ACCEPTED ||
            outcome == P25AffiliationEvent.Outcome.CONFIRMED ||
            (outcome == P25AffiliationEvent.Outcome.CLEARED &&
                event.getEventType() == DecodeEventType.DEREGISTER);
    }

    /**
     * Classifies one structured decoder event without inspecting display text or generic identifier roles.
     */
    public static Observation evaluate(P25AffiliationEvent event)
    {
        if(event == null || !validRadio(event.getRadioIdentifier(), event.getRadioId()))
        {
            return Observation.IGNORED;
        }

        Evidence evidence = event.getEventType() == DecodeEventType.REGISTER ?
            Evidence.REGISTRATION : Evidence.AFFILIATION;

        if(event.getOutcome() == P25AffiliationEvent.Outcome.CLEARED)
        {
            return event.getEventType() == DecodeEventType.DEREGISTER ?
                new Observation(Kind.PRESENCE_CLEARED, Evidence.CLEAR) : Observation.IGNORED;
        }

        if(event.getOutcome() != P25AffiliationEvent.Outcome.ACCEPTED &&
            event.getOutcome() != P25AffiliationEvent.Outcome.CONFIRMED)
        {
            return Observation.IGNORED;
        }

        if(event.getTalkgroupId() != null)
        {
            // ANSI/TIA-102.AABC-B-2005 section 6.2.23 defines the accepted location-registration group and target
            // fields as current addresses. Keep that group-bearing evidence distinct from group-less presence.
            return validTalkgroup(event.getTalkgroupIdentifier(), event.getTalkgroupId()) ?
                new Observation(Kind.AFFILIATION_OBSERVED, evidence) : Observation.IGNORED;
        }

        return evidence == Evidence.REGISTRATION ? new Observation(Kind.PRESENCE_OBSERVED, evidence) :
            Observation.IGNORED;
    }

    private static boolean validRadio(Identifier<?> identifier, Integer observedId)
    {
        if(observedId == null)
        {
            return false;
        }

        if(identifier instanceof FullyQualifiedRadioIdentifier fullyQualified)
        {
            return fullyQualified.getRadio() > 0 && observedId >= 0;
        }

        return observedId > 0;
    }

    private static boolean validTalkgroup(Identifier<?> identifier, Integer observedId)
    {
        if(observedId == null)
        {
            return false;
        }

        if(identifier instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
        {
            return fullyQualified.getTalkgroup() > 0 && observedId >= 0;
        }

        return observedId > 0;
    }
}
