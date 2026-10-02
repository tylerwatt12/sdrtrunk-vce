/*
 * *****************************************************************************
 * Copyright (C) 2026
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25TrafficChannelEventTrackerTest
{
    private static final long START = 1_000L;

    @Test
    void localAndQualifiedAddressesDescribeTheSameTrackedCallInBothDirections()
    {
        RadioIdentifier plainRadio = APCO25RadioIdentifier.createFrom(1234567);
        RadioIdentifier qualifiedRadio = APCO25FullyQualifiedRadioIdentifier.createFrom(
            1234567, 0xABCDE, 0x123, 7654321);
        Identifier<?> plainGroup = APCO25Talkgroup.create(1201);
        Identifier<?> qualifiedGroup = APCO25FullyQualifiedTalkgroupIdentifier.createTo(
            1201, 0xABCDE, 0x123, 2201);
        MutableIdentifierCollection plain = identifiers(plainGroup, plainRadio);
        MutableIdentifierCollection qualified = identifiers(qualifiedGroup, qualifiedRadio);

        assertFalse(plainRadio.equals(qualifiedRadio), "Global identifier equality must remain strict");
        assertFalse(plainGroup.equals(qualifiedGroup));
        assertTrue(tracker(plain).isSameCallCheckingToAndFrom(qualified, START + 75L));
        assertTrue(tracker(qualified).isSameCallCheckingToAndFrom(plain, START + 75L));
        assertTrue(tracker(plain).isSameCallCheckingToOnly(qualified, START + 75L));
        assertTrue(tracker(qualified).isSameCallCheckingToOnly(plain, START + 75L));
        assertTrue(tracker(plain).isSameControlContinuationCheckingToOnly(qualified, START + 3_000L));
        assertTrue(tracker(qualified).isSameControlContinuationCheckingToOnly(plain, START + 3_000L));
        assertFalse(tracker(plain).isDifferentTalker(qualifiedRadio));
        assertFalse(tracker(qualified).isDifferentTalker(plainRadio));
    }

    @Test
    void abbreviatedUpdatesCannotEraseLearnedHomeIdentities()
    {
        RadioIdentifier qualifiedRadio = APCO25FullyQualifiedRadioIdentifier.createFrom(
            1234567, 0xABCDE, 0x123, 7654321);
        Identifier<?> qualifiedGroup = APCO25FullyQualifiedTalkgroupIdentifier.createTo(
            1201, 0xABCDE, 0x123, 2201);
        P25TrafficChannelEventTracker tracker = tracker(identifiers(1201,
            APCO25RadioIdentifier.createFrom(1234567)));
        tracker.addIdentifierIfMissing(qualifiedRadio);
        tracker.addIdentifierIfMissing(qualifiedGroup);
        tracker.addIdentifierIfMissing(APCO25RadioIdentifier.createFrom(1234567));
        tracker.addIdentifierIfMissing(APCO25Talkgroup.create(1201));

        assertSame(qualifiedRadio, tracker.getEvent().getIdentifierCollection().getFromIdentifier());
        assertSame(qualifiedGroup, tracker.getEvent().getIdentifierCollection().getToIdentifier());
        assertTrue(tracker.isDifferentTalker(APCO25FullyQualifiedRadioIdentifier.createFrom(
            1234567, 0xABCDE, 0x123, 7654322)));
        assertFalse(tracker.isSameCallCheckingToOnly(identifiers(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(1201, 0xABCDE, 0x123, 2202), qualifiedRadio),
            START + 75L));
    }

    @Test
    void matchingLocalAddressesCannotHideConflictingKnownHomeIdentities()
    {
        RadioIdentifier radio = APCO25FullyQualifiedRadioIdentifier.createFrom(
            1234567, 0xABCDE, 0x123, 7654321);
        Identifier<?> group = APCO25FullyQualifiedTalkgroupIdentifier.createTo(
            1201, 0xABCDE, 0x123, 2201);
        P25TrafficChannelEventTracker tracker = tracker(identifiers(group, radio));

        for(RadioIdentifier conflict: List.of(
            APCO25FullyQualifiedRadioIdentifier.createFrom(1234567, 0xABCDF, 0x123, 7654321),
            APCO25FullyQualifiedRadioIdentifier.createFrom(1234567, 0xABCDE, 0x124, 7654321),
            APCO25FullyQualifiedRadioIdentifier.createFrom(1234567, 0xABCDE, 0x123, 7654322)))
        {
            assertTrue(tracker.isDifferentTalker(conflict));
            assertFalse(tracker.isSameCallCheckingToAndFrom(identifiers(group, conflict), START + 75L));
        }

        for(Identifier<?> conflict: List.of(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(1201, 0xABCDF, 0x123, 2201),
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(1201, 0xABCDE, 0x124, 2201),
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(1201, 0xABCDE, 0x123, 2202)))
        {
            MutableIdentifierCollection next = identifiers(conflict, radio);
            assertFalse(tracker.isSameCallCheckingToOnly(next, START + 75L));
            assertFalse(tracker.isSameControlContinuationCheckingToOnly(next, START + 75L));
            assertFalse(tracker.isSameCallCheckingToAndFrom(next, START + 75L));
        }
    }

    @Test
    void localAddressMismatchAndUnknownZeroRemainSeparate()
    {
        P25TrafficChannelEventTracker tracker = tracker(1201, APCO25RadioIdentifier.createFrom(1234567));
        assertTrue(tracker.isDifferentTalker(APCO25FullyQualifiedRadioIdentifier.createFrom(
            1234568, 0xABCDE, 0x123, 1234567)));
        assertFalse(tracker.isSameCallCheckingToOnly(identifiers(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(1202, 0xABCDE, 0x123, 1201), null), START + 75L));
        P25TrafficChannelEventTracker zero = tracker(identifiers(APCO25Talkgroup.create(0),
            APCO25RadioIdentifier.createFrom(0)));
        assertFalse(zero.isSameCallCheckingToAndFrom(identifiers(
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(0, 0xABCDE, 0x123, 1201),
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xABCDE, 0x123, 1234567)), START + 75L));
    }

    @Test
    void patchDestinationsKeepTheirExistingIdentityRules()
    {
        RadioIdentifier radio = APCO25RadioIdentifier.createFrom(1234567);
        APCO25PatchGroup patch = APCO25PatchGroup.create(1201);
        APCO25PatchGroup samePatch = APCO25PatchGroup.create(1201);
        samePatch.getValue().addPatchedTalkgroup(APCO25Talkgroup.create(1202));
        P25TrafficChannelEventTracker tracker = tracker(identifiers(patch, radio));

        assertTrue(tracker.isSameCallCheckingToAndFrom(identifiers(samePatch, radio), START + 75L));
        assertFalse(tracker.isSameCallCheckingToOnly(identifiers(APCO25PatchGroup.create(1203), radio),
            START + 75L));
        assertFalse(tracker.isSameCallCheckingToOnly(identifiers(APCO25Talkgroup.create(1201), radio),
            START + 75L));
    }

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
        return tracker(identifiers(talkgroup, source));
    }

    private static P25TrafficChannelEventTracker tracker(MutableIdentifierCollection identifiers)
    {
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers)
            .build();
        return new P25TrafficChannelEventTracker(event);
    }

    private static MutableIdentifierCollection identifiers(int talkgroup, RadioIdentifier source)
    {
        return identifiers(APCO25Talkgroup.create(talkgroup), source);
    }

    private static MutableIdentifierCollection identifiers(Identifier<?> target, Identifier<?> source)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(target);

        if(source != null)
        {
            identifiers.update(source);
        }

        return identifiers;
    }
}
