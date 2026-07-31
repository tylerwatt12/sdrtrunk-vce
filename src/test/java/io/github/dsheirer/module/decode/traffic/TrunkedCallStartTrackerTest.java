/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.DMRTier3Channel;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class TrunkedCallStartTrackerTest
{
    @Test
    void countsTargetChangesButNotGrantOrTalkerUpdates()
    {
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(5_000);
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigDMR());
        DMRTier3Channel channel = channel(451_012_500L, 1);

        TrunkedCallStartEvent first = tracker.observe(parent, Protocol.DMR, channel, 1,
            identifiers(101, 91), DecodeEventType.CALL_GROUP, 1_000L);
        TrunkedCallStartEvent talkerUpdate = tracker.observe(parent, Protocol.DMR, channel, 1,
            identifiers(102, 91), DecodeEventType.CALL_GROUP, 1_100L);
        TrunkedCallStartEvent targetChange = tracker.observe(parent, Protocol.DMR, channel, 1,
            identifiers(102, 92), DecodeEventType.CALL_GROUP, 1_200L);
        TrunkedCallStartEvent repeatedGrant = tracker.observe(parent, Protocol.DMR, channel, 1,
            identifiers(102, 92), DecodeEventType.CALL_GROUP, 1_300L);

        assertNotNull(first);
        assertEquals(Protocol.DMR, first.event().getProtocol());
        assertEquals(451_012_500L, first.event().getChannelDescriptor().getDownlinkFrequency());
        assertEquals(1, first.event().getTimeslot());
        assertEquals(101, first.event().getIdentifierCollection().getFromIdentifier().getValue());
        assertNull(talkerUpdate);
        assertNotNull(targetChange);
        assertNull(repeatedGrant);
    }

    @Test
    void treatsLateTargetIdentificationAsCallEnrichment()
    {
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(5_000);
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigDMR());
        DMRTier3Channel channel = channel(451_012_500L, 1);
        MutableIdentifierCollection unidentified = new MutableIdentifierCollection();

        TrunkedCallStartTracker.ObservationResult initial = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 1, unidentified, DecodeEventType.CALL_GROUP, 1_000L);
        TrunkedCallStartTracker.ObservationResult target = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 1, identifiers(0, 91), DecodeEventType.CALL_GROUP, 1_100L);
        TrunkedCallStartTracker.ObservationResult sourceAndEncryption =
            tracker.observeWithAttribution(parent, Protocol.DMR, channel, 1, identifiers(101, 91),
                DecodeEventType.CALL_GROUP_ENCRYPTED, 1_200L);
        TrunkedCallStartTracker.ObservationResult repeated =
            tracker.observeWithAttribution(parent, Protocol.DMR, channel, 1, identifiers(101, 91),
                DecodeEventType.CALL_GROUP_ENCRYPTED, 1_250L);
        TrunkedCallStartTracker.ObservationResult delayed =
            tracker.observeWithAttribution(parent, Protocol.DMR, channel, 1, identifiers(102, 91),
                DecodeEventType.CALL_GROUP_ENCRYPTED, 1_150L);
        TrunkedCallStartTracker.ObservationResult changed =
            tracker.observeWithAttribution(parent, Protocol.DMR, channel, 1, identifiers(101, 92),
                DecodeEventType.CALL_GROUP_ENCRYPTED, 1_300L);

        assertNotNull(initial.callStart());
        assertNull(initial.attribution());
        assertNull(target.callStart());
        assertNotNull(target.attribution());
        assertEquals(1_000L, target.attribution().callStartEpochMilliseconds());
        assertTrue(target.attribution().destinationBecameKnown());
        assertFalse(target.attribution().sourceBecameKnown());
        assertFalse(target.attribution().encryptionBecameKnown());
        assertNull(sourceAndEncryption.callStart());
        assertNotNull(sourceAndEncryption.attribution());
        assertFalse(sourceAndEncryption.attribution().destinationBecameKnown());
        assertTrue(sourceAndEncryption.attribution().sourceBecameKnown());
        assertTrue(sourceAndEncryption.attribution().encryptionBecameKnown());
        assertEquals(91, sourceAndEncryption.attribution().identifiers().getToIdentifier().getValue());
        assertEquals(101, sourceAndEncryption.attribution().identifiers().getFromIdentifier().getValue());
        assertNull(repeated.callStart());
        assertNull(repeated.attribution());
        assertNull(delayed.callStart());
        assertNull(delayed.attribution());
        assertNotNull(changed.callStart());
        assertNull(changed.attribution());
    }

    @Test
    void retainsPriorEncryptionWhenUnknownDestinationBecomesKnown()
    {
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(5_000);
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigDMR());
        DMRTier3Channel channel = channel(451_012_500L, 1);
        MutableIdentifierCollection sourceOnly = new MutableIdentifierCollection();
        sourceOnly.update(DMRRadio.createFrom(101));

        TrunkedCallStartTracker.ObservationResult initial = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 1, sourceOnly, DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L);
        TrunkedCallStartTracker.ObservationResult target = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 1, identifiers(101, 91), DecodeEventType.CALL_GROUP, 1_100L);

        assertNotNull(initial.callStart());
        assertNotNull(target.attribution());
        assertTrue(target.attribution().destinationBecameKnown());
        assertTrue(target.attribution().encryptedBeforeObservation());
        assertFalse(target.attribution().encryptionBecameKnown());
    }

    @Test
    void enrichmentOnlyCannotStartOrReviveACall()
    {
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(5_000);
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigDMR());
        DMRTier3Channel channel = channel(451_012_500L, 1);
        MutableIdentifierCollection targetOnly = identifiers(0, 91);

        TrunkedCallStartTracker.ObservationResult untracked = tracker.enrichActiveCall(parent, Protocol.DMR,
            channel, 1, identifiers(101, 91), DecodeEventType.CALL_GROUP, 900L);
        TrunkedCallStartTracker.ObservationResult started = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 1, targetOnly, DecodeEventType.CALL_GROUP, 1_000L);
        TrunkedCallStartTracker.ObservationResult enriched = tracker.enrichActiveCall(parent, Protocol.DMR,
            channel, 1, identifiers(101, 91), DecodeEventType.CALL_GROUP_ENCRYPTED, 1_100L);
        tracker.end(channel, 1, 1_200L);
        TrunkedCallStartTracker.ObservationResult ended = tracker.enrichActiveCall(parent, Protocol.DMR,
            channel, 1, identifiers(101, 91), DecodeEventType.CALL_GROUP_ENCRYPTED, 1_300L);
        TrunkedCallStartEvent next = tracker.observe(parent, Protocol.DMR, channel, 1,
            identifiers(102, 91), DecodeEventType.CALL_GROUP, 1_301L);

        assertNull(untracked.callStart());
        assertNull(untracked.attribution());
        assertNotNull(started.callStart());
        assertNotNull(enriched.attribution());
        assertTrue(enriched.attribution().sourceBecameKnown());
        assertTrue(enriched.attribution().encryptionBecameKnown());
        assertNull(ended.callStart());
        assertNull(ended.attribution());
        assertNotNull(next);
    }

    @Test
    void reservedDmrIdentitiesCanBeReplacedWithoutStartingAnotherCall()
    {
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(5_000);
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigDMR());
        DMRTier3Channel channel = channel(451_012_500L, 1);

        TrunkedCallStartTracker.ObservationResult reserved = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 1, identifiers(0xFFFECA, 0xFFFEC6), DecodeEventType.CALL_GROUP, 1_000L);
        TrunkedCallStartTracker.ObservationResult valid = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 1, identifiers(101, 91), DecodeEventType.CALL_GROUP, 1_100L);
        TrunkedCallStartTracker.ObservationResult repeated = tracker.observeWithAttribution(parent, Protocol.DMR,
            channel, 1, identifiers(101, 91), DecodeEventType.CALL_GROUP, 1_200L);

        assertNotNull(reserved.callStart());
        assertNull(valid.callStart());
        assertNotNull(valid.attribution());
        assertTrue(valid.attribution().destinationBecameKnown());
        assertTrue(valid.attribution().sourceBecameKnown());
        assertEquals(91, valid.attribution().identifiers().getToIdentifier().getValue());
        assertEquals(101, valid.attribution().identifiers().getFromIdentifier().getValue());
        assertNull(repeated.callStart());
        assertNull(repeated.attribution());
    }

    @Test
    void startsAgainAfterExplicitEndOrContinuationGapAndIgnoresData()
    {
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(3_000);
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigDMR());
        DMRTier3Channel channel = channel(451_012_500L, 2);
        MutableIdentifierCollection identifiers = identifiers(101, 91);

        assertNotNull(tracker.observe(parent, Protocol.DMR, channel, 2, identifiers,
            DecodeEventType.CALL_GROUP, 1_000L));
        assertNull(tracker.observe(parent, Protocol.DMR, channel, 2, identifiers,
            DecodeEventType.DATA_CALL, 1_100L));
        assertNotNull(tracker.observe(parent, Protocol.DMR, channel, 2, identifiers,
            DecodeEventType.CALL_GROUP, 4_100L));
        tracker.end(channel, 2, 4_150L);
        assertNotNull(tracker.observe(parent, Protocol.DMR, channel, 2, identifiers,
            DecodeEventType.CALL_GROUP, 4_200L));
    }

    @Test
    void rejectsDelayedObservationsAndEndsButAllowsTheNextCall()
    {
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(3_000);
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigDMR());
        DMRTier3Channel channel = channel(451_012_500L, 1);
        MutableIdentifierCollection identifiers = identifiers(101, 91);

        assertNotNull(tracker.observe(parent, Protocol.DMR, channel, 1, identifiers,
            DecodeEventType.CALL_GROUP, 1_000L));
        tracker.end(channel, 1, 2_000L);
        assertNull(tracker.observe(parent, Protocol.DMR, channel, 1, identifiers,
            DecodeEventType.CALL_GROUP, 1_900L));
        assertNotNull(tracker.observe(parent, Protocol.DMR, channel, 1, identifiers,
            DecodeEventType.CALL_GROUP, 2_001L));
        tracker.end(channel, 1, 1_950L);
        assertNull(tracker.observe(parent, Protocol.DMR, channel, 1, identifiers,
            DecodeEventType.CALL_GROUP, 2_200L));
    }

    @Test
    void keepsLogicalDmrResourceStableWhenItsFrequencyResolves()
    {
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(3_000);
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigDMR());
        DMRTier3Channel channel = new DMRTier3Channel(12, 1);
        MutableIdentifierCollection identifiers = identifiers(101, 91);

        assertNotNull(tracker.observe(parent, Protocol.DMR, channel, 1, identifiers,
            DecodeEventType.CALL_GROUP, 1_000L));
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(451_012_500L);
        channel.setTimeslotFrequency(mapping);
        assertNull(tracker.observe(parent, Protocol.DMR, channel, 1, identifiers,
            DecodeEventType.CALL_GROUP, 1_100L));
    }

    @Test
    void trafficProgressKeepsAnActiveCallFromRestarting()
    {
        TrunkedCallStartTracker tracker = new TrunkedCallStartTracker(3_000);
        Channel parent = new Channel("DMR Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigDMR());
        DMRTier3Channel channel = channel(451_012_500L, 1);
        MutableIdentifierCollection identifiers = identifiers(101, 91);

        assertNotNull(tracker.observe(parent, Protocol.DMR, channel, 1, identifiers,
            DecodeEventType.CALL_GROUP, 1_000L));
        tracker.touch(channel, 1, 4_500L);
        assertNull(tracker.observe(parent, Protocol.DMR, channel, 1, identifiers,
            DecodeEventType.CALL_GROUP, 5_000L));
    }

    private static MutableIdentifierCollection identifiers(int radio, int talkgroup)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        if(radio > 0)
        {
            identifiers.update(DMRRadio.createFrom(radio));
        }
        identifiers.update(DMRTalkgroup.create(talkgroup));
        return identifiers;
    }

    private static DMRTier3Channel channel(long frequency, int timeslot)
    {
        DMRTier3Channel channel = new DMRTier3Channel(12, timeslot);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(frequency);
        channel.setTimeslotFrequency(mapping);
        return channel;
    }
}
