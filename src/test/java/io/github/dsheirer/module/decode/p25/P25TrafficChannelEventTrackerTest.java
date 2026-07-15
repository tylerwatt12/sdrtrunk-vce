/*
 * *****************************************************************************
 * Copyright (C) 2026
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import org.junit.jupiter.api.Test;

class P25TrafficChannelEventTrackerTest
{
    private static final long START = 1_000L;

    @Test
    void sourceLessControlUpdatesContinueIncompleteCallBeyondNormalStaleWindow()
    {
        RadioIdentifier source = APCO25RadioIdentifier.createFrom(1234567);
        P25TrafficChannelEventTracker tracker = tracker(1201, source);
        MutableIdentifierCollection update = identifiers(1201, null);
        long updateTimestamp = START + 3_000L;

        assertFalse(tracker.isSameCallCheckingToOnly(update, updateTimestamp));
        assertTrue(tracker.isSameControlContinuationCheckingToOnly(update, updateTimestamp));
        assertTrue(tracker.updateDurationControl(updateTimestamp));
        assertEquals(source, tracker.getEvent().getIdentifierCollection().getFromIdentifier());
    }

    @Test
    void eachControlUpdateRefreshesContinuationWindow()
    {
        P25TrafficChannelEventTracker tracker = tracker(1201, APCO25RadioIdentifier.createFrom(1234567));
        MutableIdentifierCollection update = identifiers(1201, null);
        long firstUpdate = START + P25TrafficChannelEventTracker.CONTROL_CONTINUATION_THRESHOLD_MS - 1;

        assertTrue(tracker.isSameControlContinuationCheckingToOnly(update, firstUpdate));
        tracker.updateDurationControl(firstUpdate);
        assertTrue(tracker.isSameControlContinuationCheckingToOnly(update,
            firstUpdate + P25TrafficChannelEventTracker.CONTROL_CONTINUATION_THRESHOLD_MS - 1));
    }

    @Test
    void completedCallCannotConsumeNextControlUpdate()
    {
        P25TrafficChannelEventTracker tracker = tracker(1201, APCO25RadioIdentifier.createFrom(1234567));
        MutableIdentifierCollection update = identifiers(1201, null);

        assertTrue(tracker.completeTraffic(2_000L));
        assertFalse(tracker.isSameControlContinuationCheckingToOnly(update, 2_100L));
    }

    @Test
    void continuationExpiresAfterBoundedSilence()
    {
        P25TrafficChannelEventTracker tracker = tracker(1201, APCO25RadioIdentifier.createFrom(1234567));
        MutableIdentifierCollection update = identifiers(1201, null);

        assertFalse(tracker.isSameControlContinuationCheckingToOnly(update,
            START + P25TrafficChannelEventTracker.CONTROL_CONTINUATION_THRESHOLD_MS + 1));
    }

    private static P25TrafficChannelEventTracker tracker(int talkgroup, RadioIdentifier source)
    {
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers(talkgroup, source))
            .build();
        return new P25TrafficChannelEventTracker(event);
    }

    private static MutableIdentifierCollection identifiers(int talkgroup, RadioIdentifier source)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(talkgroup));

        if(source != null)
        {
            identifiers.update(source);
        }

        return identifiers;
    }
}
