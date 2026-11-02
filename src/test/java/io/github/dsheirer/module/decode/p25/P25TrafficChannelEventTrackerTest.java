/*
 * *****************************************************************************
 * Copyright (C) 2026
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.identifier.IdentityResolutionEvidence;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.audio.call.CallEncryptionState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25TrafficChannelEventTrackerTest
{
    private static final long START = 1_000L;
    private static final String OWNER = "223e4567-e89b-42d3-a456-426614174000";

    @Test
    void firstUnchangedBroadcastOffersOneFullSnapshotForEitherPhaseWithoutFormattingLiveObjects()
    {
        for(boolean phaseTwo: List.of(false, true))
        {
            var owner = new Channel("P25");
            owner.setConfigurationId(OWNER);
            owner.setDecodeConfiguration(phaseTwo ? new DecodeConfigP25Phase2() : new DecodeConfigP25Phase1());
            var event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START,
                VoiceServiceOptions.createUnencrypted()).identifiers(identifiers(1201, null))
                .channelDescriptor(new StandardChannel(851_012_500L)
                {
                    @Override public String toString() { throw new AssertionError("Producer must not format RF facts"); }
                }).timeslot(phaseTwo ? 2 : 1).build();
            var tracker = new P25TrafficChannelEventTracker(event, "p25:bee00:123");
            var initial = new P25CallStartEvent(owner, event, tracker.getRadioSystemKey(), tracker.getActivityCallToken());
            tracker.setCallStart(initial);

            var first = tracker.callUpdate();
            assertNotNull(first, "The first broadcast gives a dropped start one independent full handoff");
            assertEquals(initial, first.call());
            assertEquals(tracker.getActivityCallToken(), first.call().callToken());
            assertEquals(START, first.call().startedAtEpochMilliseconds());
            assertEquals(START, first.observedAtEpochMilliseconds());
            assertFalse(first.complete());
            assertNull(first.durationMilliseconds());
            tracker.updateDurationControl(START + 50);
            assertNull(tracker.callUpdate(), "Unchanged later broadcasts do not create observer traffic");
        }
    }

    @Test
    void lateDescriptorAndCanonicalIdentityReachTheSameImmutableCallWithoutMovingItsStart()
    {
        for(boolean phaseTwo: List.of(false, true))
        {
            long frequency = 851_012_500L;
            var owner = new Channel("P25");
            owner.setConfigurationId(OWNER);
            owner.setDecodeConfiguration(phaseTwo ? new DecodeConfigP25Phase2() : new DecodeConfigP25Phase1());
            var event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
                .identifiers(identifiers(1201, null)).channelDescriptor(new StandardChannel(frequency))
                .timeslot(phaseTwo ? 2 : 1).build();
            var tracker = new P25TrafficChannelEventTracker(event, "p25:bee00:123");
            tracker.setCallStart(new P25CallStartEvent(owner, event, tracker.getRadioSystemKey(), tracker.getActivityCallToken()));
            var first = tracker.callUpdate();
            var channel = APCO25Channel.create(0, phaseTwo ? 1 : 0);
            channel.setFrequencyBand(new P25FrequencyBand(0, frequency, -45_000_000L, 12_500L, 12_500,
                phaseTwo ? 2 : 1));
            event.setChannelDescriptor(channel);
            tracker.updateDurationControl(START + 100);
            var descriptor = tracker.callUpdate();
            assertNotNull(descriptor, "A newly learned LCN is material without an identifier change");
            assertEquals(0, descriptor.call().channelBand());
            assertEquals(0, descriptor.call().channelNumber());
            assertEquals(frequency, descriptor.call().frequencyHertz());
            assertEquals(phaseTwo, descriptor.call().tdma());
            assertEquals(START + 100, descriptor.observedAtEpochMilliseconds());
            assertNull(first.call().channelBand(), "The already published start remains immutable");

            var source = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x123, 700_001);
            var target = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1201, 0xBEE00, 0x123, 2201);
            tracker.addIdentifierIfMissing(source);
            tracker.addIdentifierIfMissing(target);
            tracker.updateDurationTraffic(START + 200);
            var learned = tracker.callUpdate();
            assertEquals(OWNER, learned.call().configurationId());
            assertEquals("p25:bee00:123", learned.call().radioSystemKey());
            assertEquals(START, learned.call().startedAtEpochMilliseconds());
            assertEquals(first.call().callToken(), learned.call().callToken());
            assertEquals(START + 200, learned.observedAtEpochMilliseconds());
            assertSame(source, learned.call().identifierCollection().getFromIdentifier());
            assertSame(target, learned.call().identifierCollection().getToIdentifier());
            assertNull(descriptor.call().identifierCollection().getFromIdentifier());
            assertNull(tracker.callUpdate());

            var incompatible = APCO25Channel.create(1, phaseTwo ? 3 : 1);
            incompatible.setFrequencyBand(new P25FrequencyBand(1, frequency + 100_000L, -45_000_000L,
                12_500L, 12_500, phaseTwo ? 2 : 1));
            event.setChannelDescriptor(incompatible);
            assertNull(tracker.callUpdate(), "An incompatible descriptor cannot move an accepted call");
        }
    }

    @Test
    void genericCallSubtypeCanGainAcceptedDestinationAndEncryptionFacts()
    {
        var event = P25ChannelGrantEvent.builder(DecodeEventType.CALL, START, null)
            .identifiers(new MutableIdentifierCollection()).build();
        var tracker = publishedTracker(event);
        tracker.addIdentifierIfMissing(APCO25RadioIdentifier.createTo(502));
        event.setServiceOptions(VoiceServiceOptions.createEncrypted());
        var privateCall = tracker.callUpdate();
        assertEquals(DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED, privateCall.call().eventType());
        assertEquals(START, privateCall.call().startedAtEpochMilliseconds());
        assertNull(tracker.callUpdate());
    }

    @Test
    void encryptionOnlyChangesPublishOnTheSameExactCallAndConflictStaysUnknown()
    {
        var event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers(1201, null)).build();
        var tracker = publishedTracker(event);
        assertNull(tracker.callUpdate());
        event.setServiceOptions(VoiceServiceOptions.createEncrypted());
        var encrypted = tracker.callUpdate();
        assertEquals(CallEncryptionState.ENCRYPTED, encrypted.call().encryptionState());
        assertEquals(tracker.getActivityCallToken(), encrypted.call().callToken());
        event.setServiceOptions(null);
        assertNull(tracker.callUpdate(), "Absent information cannot erase accepted encryption");
        event.setServiceOptions(VoiceServiceOptions.createUnencrypted());
        assertEquals(CallEncryptionState.UNKNOWN, tracker.callUpdate().call().encryptionState());
        event.setServiceOptions(VoiceServiceOptions.createEncrypted());
        assertNull(tracker.callUpdate(), "Conflicting evidence stays unknown for this physical call");
    }

    @Test
    void repeatedKeyConfirmationPublishesEvenWhenIdentifiersDoNotChange()
    {
        for(boolean expire: List.of(false, true))
        {
            var key = EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 5));
            var collection = identifiers(1201, null);
            collection.update(key);
            var event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED, START,
                VoiceServiceOptions.createEncrypted()).identifiers(collection).build();
            var tracker = publishedTracker(event);
            P25EncryptionConfirmationTracker.observe(event, key, START);
            assertNull(tracker.callUpdate());
            P25EncryptionConfirmationTracker.observe(event, key, START + 100);
            var confirmed = tracker.callUpdate();
            assertTrue(confirmed.call().encryptionConfirmed());
            assertEquals(CallEncryptionState.ENCRYPTED, confirmed.call().encryptionState());
            assertNull(tracker.callUpdate());
            if(expire)
            {
                var later = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED,
                    START + 61_000, VoiceServiceOptions.createEncrypted()).identifiers(collection).build();
                P25EncryptionConfirmationTracker.observe(later, key, START + 61_000);
                P25EncryptionConfirmationTracker.complete(later, START + 61_000);
            }
            else P25EncryptionConfirmationTracker.complete(event, START + 100);
            assertNull(tracker.callUpdate(), "Retiring temporary evidence cannot erase an already confirmed detail");
        }
    }

    @Test
    void conflictingRawKeyEvidenceRevokesPreviouslyConfirmedDetails()
    {
        var key = EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 5));
        var collection = identifiers(1201, null);
        collection.update(key);
        var event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED, START,
            VoiceServiceOptions.createEncrypted()).identifiers(collection).build();
        var tracker = publishedTracker(event);
        P25EncryptionConfirmationTracker.observe(event, key, START);
        P25EncryptionConfirmationTracker.observe(event, key, START + 100);
        assertTrue(tracker.callUpdate().call().encryptionConfirmed());
        P25EncryptionConfirmationTracker.observe(event,
            EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 7)), START + 200);
        var rejected = tracker.callUpdate();
        assertFalse(rejected.call().encryptionConfirmed());
        assertEquals(CallEncryptionState.ENCRYPTED, rejected.call().encryptionState());
        assertNull(tracker.callUpdate());
        P25EncryptionConfirmationTracker.complete(event, START + 200);
    }

    @Test
    void finalObservedDurationSurvivesLateCallerEnrichmentWithoutDependingOnAudio()
    {
        for(boolean encrypted: List.of(false, true))
        {
            var event = P25ChannelGrantEvent.builder(encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED :
                DecodeEventType.CALL_GROUP, START, encrypted ? VoiceServiceOptions.createEncrypted() :
                VoiceServiceOptions.createUnencrypted()).identifiers(identifiers(1201, null)).build();
            var tracked = publishedTracker(event);
            assertNull(tracked.callUpdate(), "Creating a tracker does not invent a final interval");
            tracked.updateDurationTraffic(START + 100);
            tracked.addIdentifierIfMissing(APCO25RadioIdentifier.createFrom(501));
            var enriched = tracked.callUpdate();
            assertFalse(enriched.complete());
            assertNull(enriched.durationMilliseconds());
            assertEquals(START, enriched.call().startedAtEpochMilliseconds());

            assertTrue(tracked.completeTraffic(START + 900));
            var completed = tracked.callUpdate();
            assertTrue(completed.complete());
            assertEquals(900L, completed.durationMilliseconds());
            assertEquals(enriched.call().callToken(), completed.call().callToken());
            assertEquals(START, completed.call().startedAtEpochMilliseconds());
            assertNull(tracked.callUpdate(), "Unchanged completion remains coalesced");
            event.setDuration(5_000);
            assertEquals(900L, completed.durationMilliseconds(), "Published timing is immutable");
        }
    }

    @Test
    void grantOnlyCompletionAndRetiredOwnershipDoNotInventObservedCallTiming()
    {
        var grantOnly = publishedTracker(P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers(1201, null)).build());
        grantOnly.updateDurationControl(START + 500);
        grantOnly.completeTraffic(START + 750);
        assertNull(grantOnly.callUpdate().durationMilliseconds());

        var retired = publishedTracker(P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers(1201, null)).build());
        retired.updateDurationTraffic(START + 100);
        retired.completeAttribution();
        retired.addIdentifierIfMissing(APCO25RadioIdentifier.createFrom(501));
        var update = retired.callUpdate();
        assertFalse(update.complete());
        assertNull(update.durationMilliseconds(), "Map retirement supplies no new RF end timestamp");
    }

    @Test
    void missingStartOrDefaultZeroSpanStaysUnknown()
    {
        for(long start: List.of(0L, START))
        {
            var tracked = publishedTracker(P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, start, null)
                .identifiers(identifiers(1201, null)).build());
            tracked.updateDurationTraffic(start);
            tracked.completeTraffic(start == 0 ? 500 : start);
            assertNull(tracked.callUpdate().durationMilliseconds());
        }
        assertNull(new P25CallUpdateEvent(null, true).durationMilliseconds(),
            "Compatibility producers without observed timing preserve unknown");

        var staleEnd = publishedTracker(P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers(1201, null)).build());
        staleEnd.updateDurationTraffic(START + 100);
        staleEnd.completeTraffic(START - 10);
        assertNull(staleEnd.callUpdate().durationMilliseconds(),
            "An ignored end before the call start must not reuse an in-progress duration");
    }

    @Test
    void resetCompletionCannotExportItsWallClockAsAnObservedCallLength()
    {
        var tracked = publishedTracker(P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers(1201, null)).build());
        tracked.updateDurationTraffic(START + 100);
        assertTrue(tracked.completeTraffic(1_700_000_000_000L, false));
        var completed = tracked.callUpdate();
        assertTrue(completed.complete());
        assertNull(completed.durationMilliseconds());
        assertFalse(tracked.completeTraffic(START + 500), "A later notification cannot rewrite final provenance");
        tracked.addIdentifierIfMissing(APCO25RadioIdentifier.createFrom(501));
        assertNull(tracked.callUpdate().durationMilliseconds(), "Late identity enrichment cannot invent an RF end");
    }

    @Test
    void equalHomeGroupLocalChangesAndRadioEvidenceChangesReachTheTracker()
    {
        var first = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1201, 0xABCDE, 0x123, 2201);
        var next = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1202, 0xABCDE, 0x123, 2201);
        var mappedRadio = APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(501, 0xABCDE, 0x123, 700_001,
            io.github.dsheirer.identifier.Role.FROM,
            io.github.dsheirer.identifier.IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT);
        var tracked = tracker(identifiers(first, mappedRadio));
        tracked.addIdentifierIfMissing(next);
        assertSame(next, tracked.getEvent().getIdentifierCollection().getToIdentifier());
        var direct = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xABCDE, 0x123, 700_001);
        tracked.addIdentifierIfMissing(direct);
        assertSame(direct, tracked.getEvent().getIdentifierCollection().getFromIdentifier());
    }

    @Test
    void canonicalOnlyObservationsRefreshRawSourceAndTargetWithoutLosingDirectAddresses()
    {
        var directGroup = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1201, 0xABCDE, 0x123, 2201);
        var directRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xABCDE, 0x123, 700_001);
        var tracked = tracker(identifiers(directGroup, directRadio));
        var observedGroup = APCO25FullyQualifiedTalkgroupIdentifier.createTo(2201, 0xABCDE, 0x123, 2201);
        var observedRadio = APCO25FullyQualifiedRadioIdentifier.createFrom(700_001, 0xABCDE, 0x123, 700_001);
        tracked.addIdentifierIfMissing(observedGroup);
        tracked.addIdentifierIfMissing(observedRadio);
        var retainedGroup = (APCO25FullyQualifiedTalkgroupIdentifier)tracked.getEvent().getIdentifierCollection().getToIdentifier();
        var retainedRadio = (APCO25FullyQualifiedRadioIdentifier)tracked.getEvent().getIdentifierCollection().getFromIdentifier();
        assertEquals(1201, retainedGroup.getLocalAddress());
        assertEquals(501, retainedRadio.getWorkingAddress());
        assertEquals(IdentityResolutionEvidence.DIRECT, retainedGroup.getResolutionEvidence());
        assertEquals(IdentityResolutionEvidence.DIRECT, retainedRadio.getResolutionEvidence());
        assertSame(observedGroup, retainedGroup.getRecordingIdentifier());
        assertSame(observedRadio, retainedRadio.getRecordingIdentifier());
        assertTrue(tracked.isSameCallCheckingToAndFrom(identifiers(1201, APCO25RadioIdentifier.createFrom(501)), START + 75));
        tracked.addIdentifierIfMissing(APCO25FullyQualifiedTalkgroupIdentifier.createTo(2201, 0xABCDE, 0x123, 2201));
        tracked.addIdentifierIfMissing(APCO25FullyQualifiedRadioIdentifier.createFrom(700_001, 0xABCDE, 0x123, 700_001));
        assertSame(retainedGroup, tracked.getEvent().getIdentifierCollection().getToIdentifier());
        assertSame(retainedRadio, tracked.getEvent().getIdentifierCollection().getFromIdentifier());
    }

    @Test
    void homeOnlyForeignGroupCannotBorrowACoincidentallyEqualLocalValue()
    {
        var homeOnly = APCO25FullyQualifiedTalkgroupIdentifier.createTo(1201, 0xABCDE, 0x123, 1201);
        var tracked = tracker(identifiers(homeOnly, APCO25RadioIdentifier.createFrom(501)));
        assertFalse(tracked.isSameCallCheckingToOnly(identifiers(1201, APCO25RadioIdentifier.createFrom(501)), START + 75L));
    }

    @Test
    void localAndQualifiedAddressesDescribeTheSameTrackedCallInBothDirections()
    {
        RadioIdentifier plainRadio = APCO25RadioIdentifier.createFrom(1234567);
        RadioIdentifier qualifiedRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1234567, 0xABCDE, 0x123, 7654321);
        Identifier<?> plainGroup = APCO25Talkgroup.create(1201);
        Identifier<?> qualifiedGroup = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(
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
        RadioIdentifier qualifiedRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1234567, 0xABCDE, 0x123, 7654321);
        Identifier<?> qualifiedGroup = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(
            1201, 0xABCDE, 0x123, 2201);
        P25TrafficChannelEventTracker tracker = tracker(identifiers(1201,
            APCO25RadioIdentifier.createFrom(1234567)));
        tracker.addIdentifierIfMissing(qualifiedRadio);
        tracker.addIdentifierIfMissing(qualifiedGroup);
        tracker.addIdentifierIfMissing(APCO25RadioIdentifier.createFrom(1234567));
        tracker.addIdentifierIfMissing(APCO25Talkgroup.create(1201));

        assertSame(qualifiedRadio, tracker.getEvent().getIdentifierCollection().getFromIdentifier());
        assertSame(qualifiedGroup, tracker.getEvent().getIdentifierCollection().getToIdentifier());
        assertTrue(tracker.isDifferentTalker(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1234567, 0xABCDE, 0x123, 7654322)));
        assertFalse(tracker.isSameCallCheckingToOnly(identifiers(
            APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1201, 0xABCDE, 0x123, 2202), qualifiedRadio),
            START + 75L));
    }

    @Test
    void matchingLocalAddressesCannotHideConflictingKnownHomeIdentities()
    {
        RadioIdentifier radio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1234567, 0xABCDE, 0x123, 7654321);
        Identifier<?> group = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(
            1201, 0xABCDE, 0x123, 2201);
        P25TrafficChannelEventTracker tracker = tracker(identifiers(group, radio));

        for(RadioIdentifier conflict: List.of(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(1234567, 0xABCDF, 0x123, 7654321),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(1234567, 0xABCDE, 0x124, 7654321),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(1234567, 0xABCDE, 0x123, 7654322)))
        {
            assertTrue(tracker.isDifferentTalker(conflict));
            assertFalse(tracker.isSameCallCheckingToAndFrom(identifiers(group, conflict), START + 75L));
        }

        for(Identifier<?> conflict: List.of(
            APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1201, 0xABCDF, 0x123, 2201),
            APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1201, 0xABCDE, 0x124, 2201),
            APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1201, 0xABCDE, 0x123, 2202)))
        {
            MutableIdentifierCollection next = identifiers(conflict, radio);
            assertFalse(tracker.isSameCallCheckingToOnly(next, START + 75L));
            assertFalse(tracker.isSameControlContinuationCheckingToOnly(next, START + 75L));
            assertFalse(tracker.isSameCallCheckingToAndFrom(next, START + 75L));
        }
    }

    @Test
    void identityOnlyRadioCannotBorrowASameNumberWorkingAddress()
    {
        RadioIdentifier permanent = APCO25FullyQualifiedRadioIdentifier.createFrom(1234567,
            0xABCDE, 0x123, 1234567);
        P25TrafficChannelEventTracker tracked = tracker(1201, permanent);
        assertTrue(tracked.isDifferentTalker(APCO25RadioIdentifier.createFrom(1234567)));
        assertFalse(tracked.isSameCallCheckingToAndFrom(identifiers(1201,
            APCO25RadioIdentifier.createFrom(1234567)), START + 75L));
    }

    @Test
    void nativeHomeIdentityEnrichmentKeepsOneCallAndSurvivesAbbreviatedUpdates()
    {
        RadioIdentifier local = APCO25RadioIdentifier.createFrom(10_900_077);
        RadioIdentifier permanent = APCO25FullyQualifiedRadioIdentifier.createFrom(10_900_077,
            0xBEE00, 0x123, 10_900_077);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers(1201, local)).build();
        P25TrafficChannelEventTracker tracked = new P25TrafficChannelEventTracker(event, "p25:bee00:123");

        assertTrue(tracked.isSameCallCheckingToAndFrom(identifiers(1201, permanent), START + 75));
        assertFalse(tracked.isDifferentTalker(permanent));
        tracked.addIdentifierIfMissing(permanent);
        tracked.addIdentifierIfMissing(local);
        assertSame(permanent, event.getIdentifierCollection().getFromIdentifier());
        assertTrue(tracked.isSameCallCheckingToAndFrom(identifiers(1201, local), START + 100));
        assertEquals(START, event.getTimeStart());
        assertTrue(tracked.isDifferentTalker(APCO25FullyQualifiedRadioIdentifier.createFrom(
            10_900_077, 0xBEE00, 0x124, 10_900_077)));
    }

    @Test
    void eventScopeCannotBeReplacedByALaterTrackerOrInferredAfterAnUnknownStart()
    {
        P25ChannelGrantEvent known = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers(1201, APCO25RadioIdentifier.createFrom(501))).build();
        new P25TrafficChannelEventTracker(known, "p25:bee00:123");
        var rebound = new P25TrafficChannelEventTracker(known, "p25:bee00:124");
        assertEquals("p25:bee00:123", known.getRadioSystemKey());
        assertEquals("p25:bee00:123", rebound.getRadioSystemKey());

        P25ChannelGrantEvent unknown = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, START, null)
            .identifiers(identifiers(1201, APCO25RadioIdentifier.createFrom(501))).build();
        new P25TrafficChannelEventTracker(unknown);
        var late = new P25TrafficChannelEventTracker(unknown, "p25:bee00:123");
        assertEquals(null, unknown.getRadioSystemKey());
        assertEquals(null, late.getRadioSystemKey());
    }

    @Test
    void localAddressMismatchAndUnknownZeroRemainSeparate()
    {
        P25TrafficChannelEventTracker tracker = tracker(1201, APCO25RadioIdentifier.createFrom(1234567));
        assertTrue(tracker.isDifferentTalker(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1234568, 0xABCDE, 0x123, 1234567)));
        assertFalse(tracker.isSameCallCheckingToOnly(identifiers(
            APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1202, 0xABCDE, 0x123, 1201), null), START + 75L));
        P25TrafficChannelEventTracker zero = tracker(identifiers(APCO25Talkgroup.create(0),
            APCO25RadioIdentifier.createFrom(0)));
        assertFalse(zero.isSameCallCheckingToAndFrom(identifiers(
            APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(0, 0xABCDE, 0x123, 1201),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(0, 0xABCDE, 0x123, 1234567)), START + 75L));
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

    private static P25TrafficChannelEventTracker publishedTracker(P25ChannelGrantEvent event)
    {
        var tracker = new P25TrafficChannelEventTracker(event);
        tracker.setCallStart(new P25CallStartEvent(null, event, null, tracker.getActivityCallToken()));
        assertNotNull(tracker.callUpdate(), "Publish the first unchanged full snapshot");
        return tracker;
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
