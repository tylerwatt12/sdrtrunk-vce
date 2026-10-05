/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import org.junit.jupiter.api.Test;

class P25AffiliationSemanticsTest
{
    @Test
    void producerPrefilterAdmitsOnlyOutcomesThatCanBecomeAuthoritativeObservations()
    {
        assertTrue(P25AffiliationSemantics.mayProduceObservation(event(DecodeEventType.RESPONSE,
            P25AffiliationEvent.Outcome.ACCEPTED, true)));
        assertTrue(P25AffiliationSemantics.mayProduceObservation(event(DecodeEventType.RESPONSE,
            P25AffiliationEvent.Outcome.CONFIRMED, true)));
        assertTrue(P25AffiliationSemantics.mayProduceObservation(event(DecodeEventType.DEREGISTER,
            P25AffiliationEvent.Outcome.CLEARED, false)));
        assertFalse(P25AffiliationSemantics.mayProduceObservation(event(DecodeEventType.REQUEST,
            P25AffiliationEvent.Outcome.REQUESTED, true)));
        assertFalse(P25AffiliationSemantics.mayProduceObservation(event(DecodeEventType.RESPONSE,
            P25AffiliationEvent.Outcome.REJECTED, true)));
        assertFalse(P25AffiliationSemantics.mayProduceObservation(event(DecodeEventType.RESPONSE,
            P25AffiliationEvent.Outcome.UNRESOLVED, true)));
        assertFalse(P25AffiliationSemantics.mayProduceObservation(event(DecodeEventType.REQUEST,
            P25AffiliationEvent.Outcome.CLEARED, false)));
        assertFalse(P25AffiliationSemantics.mayProduceObservation(null));
    }

    @Test
    void acceptsOnlySuccessfulStructuredObservations()
    {
        assertEquals(P25AffiliationSemantics.Kind.AFFILIATION_OBSERVED,
            semantics(DecodeEventType.RESPONSE, P25AffiliationEvent.Outcome.ACCEPTED, true).kind());
        assertEquals(P25AffiliationSemantics.Kind.AFFILIATION_OBSERVED,
            semantics(DecodeEventType.RESPONSE, P25AffiliationEvent.Outcome.CONFIRMED, true).kind());
        assertEquals(P25AffiliationSemantics.Kind.IGNORED,
            semantics(DecodeEventType.REQUEST, P25AffiliationEvent.Outcome.REQUESTED, true).kind());
        assertEquals(P25AffiliationSemantics.Kind.IGNORED,
            semantics(DecodeEventType.RESPONSE, P25AffiliationEvent.Outcome.REJECTED, true).kind());
        assertEquals(P25AffiliationSemantics.Kind.IGNORED,
            semantics(DecodeEventType.RESPONSE, P25AffiliationEvent.Outcome.UNRESOLVED, true).kind());
    }

    @Test
    void distinguishesRegistrationPresenceAffiliationAndExplicitClear()
    {
        P25AffiliationSemantics.Observation registration =
            semantics(DecodeEventType.REGISTER, P25AffiliationEvent.Outcome.ACCEPTED, false);
        assertEquals(P25AffiliationSemantics.Kind.PRESENCE_OBSERVED, registration.kind());
        assertEquals(P25AffiliationSemantics.Evidence.REGISTRATION, registration.evidence());

        P25AffiliationSemantics.Observation registrationWithGroup =
            semantics(DecodeEventType.REGISTER, P25AffiliationEvent.Outcome.ACCEPTED, true);
        assertEquals(P25AffiliationSemantics.Kind.AFFILIATION_OBSERVED, registrationWithGroup.kind());
        assertEquals(P25AffiliationSemantics.Evidence.REGISTRATION, registrationWithGroup.evidence());

        assertEquals(P25AffiliationSemantics.Kind.IGNORED,
            semantics(DecodeEventType.RESPONSE, P25AffiliationEvent.Outcome.ACCEPTED, false).kind());
        P25AffiliationSemantics.Observation clear =
            semantics(DecodeEventType.DEREGISTER, P25AffiliationEvent.Outcome.CLEARED, false);
        assertEquals(P25AffiliationSemantics.Kind.PRESENCE_CLEARED, clear.kind());
        assertEquals(P25AffiliationSemantics.Evidence.CLEAR, clear.evidence());
        assertEquals(P25AffiliationSemantics.Kind.IGNORED,
            semantics(DecodeEventType.REQUEST, P25AffiliationEvent.Outcome.CLEARED, false).kind());
    }

    @Test
    void rejectsReservedOrdinaryIdentifiers()
    {
        P25AffiliationEvent invalidRadio = new P25AffiliationEvent(DecodeEventType.RESPONSE, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED, null, APCO25RadioIdentifier.createFrom(0),
            APCO25Talkgroup.create(101));
        P25AffiliationEvent invalidGroup = new P25AffiliationEvent(DecodeEventType.RESPONSE, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED,
            P25RadioPresence.from(APCO25RadioIdentifier.createFrom(1_201), 1_201), APCO25RadioIdentifier.createFrom(1_201),
            APCO25Talkgroup.create(0));

        assertEquals(P25AffiliationSemantics.Kind.IGNORED,
            P25AffiliationSemantics.evaluate(invalidRadio).kind());
        assertEquals(P25AffiliationSemantics.Kind.IGNORED,
            P25AffiliationSemantics.evaluate(invalidGroup).kind());
    }

    @Test
    void acceptsZeroLocalAliasesOnlyWhenTheFullyQualifiedHomeIdentityIsUsable()
    {
        P25AffiliationEvent event = new P25AffiliationEvent(DecodeEventType.RESPONSE, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED,
            P25RadioPresence.from(APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xABCDE, 0x123, 1_201), null),
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xABCDE, 0x123, 1_201),
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(0, 0xABCDE, 0x123, 101));

        assertEquals(P25AffiliationSemantics.Kind.AFFILIATION_OBSERVED,
            P25AffiliationSemantics.evaluate(event).kind());
    }

    private static P25AffiliationSemantics.Observation semantics(DecodeEventType eventType,
                                                                 P25AffiliationEvent.Outcome outcome,
                                                                 boolean withGroup)
    {
        return P25AffiliationSemantics.evaluate(event(eventType, outcome, withGroup));
    }

    private static P25AffiliationEvent event(DecodeEventType eventType, P25AffiliationEvent.Outcome outcome,
                                             boolean withGroup)
    {
        return new P25AffiliationEvent(eventType, 1_000L, outcome,
            P25RadioPresence.from(APCO25RadioIdentifier.createFrom(1_201), 1_201), APCO25RadioIdentifier.createFrom(1_201),
            withGroup ? APCO25Talkgroup.create(101) : null);
    }
}
