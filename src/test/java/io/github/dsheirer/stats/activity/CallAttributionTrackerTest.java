/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Form;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallAttributionTrackerTest
{
    @Test
    void emitsEachLateFactOnceAndKeepsTheOriginalCallStart()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(activity(1_000L, null, null, false, true));

        CallAttributionTracker.AttributionResult identified =
            tracker.enrich(activity(1_100L, 91, 101, true, false));

        assertTrue(identified.tracked());
        assertEquals(1_000L, identified.attribution().callStartEpochMilliseconds());
        assertEquals(91, identified.attribution().destinationId());
        assertEquals(101, identified.attribution().sourceRadioId());
        assertTrue(identified.attribution().destinationBecameKnown());
        assertTrue(identified.attribution().sourceBecameKnown());
        assertTrue(identified.attribution().encryptionBecameKnown());
        assertFalse(identified.attribution().encryptedBeforeObservation());

        CallAttributionTracker.AttributionResult repeated =
            tracker.enrich(activity(1_200L, 91, 101, true, false));
        assertTrue(repeated.tracked());
        assertNull(repeated.attribution());

        CallAttributionTracker.AttributionResult laterTalker =
            tracker.enrich(activity(1_300L, 91, 102, true, false));
        assertTrue(laterTalker.tracked());
        assertNull(laterTalker.attribution());

        CallAttributionTracker.AttributionResult targetChange =
            tracker.enrich(activity(1_400L, 92, 102, true, false));
        assertTrue(targetChange.tracked());
        assertNull(targetChange.attribution());

        CallAttributionTracker.AttributionResult afterTargetChange =
            tracker.enrich(activity(1_500L, 92, 102, true, false));
        assertFalse(afterTargetChange.tracked());
        assertNull(afterTargetChange.attribution());
    }

    @Test
    void reportsEncryptionWasAlreadyKnownWhenDestinationArrivesLater()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(activity(2_000L, null, 101, true, true));

        CallAttributionTracker.AttributionResult identified =
            tracker.enrich(activity(2_100L, 91, 101, true, false));

        assertTrue(identified.tracked());
        assertTrue(identified.attribution().destinationBecameKnown());
        assertFalse(identified.attribution().sourceBecameKnown());
        assertFalse(identified.attribution().encryptionBecameKnown());
        assertTrue(identified.attribution().encryptedBeforeObservation());
    }

    @Test
    void attributesLateEncryptionDetailsWithoutCountingEncryptionAgain()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(activity(2_500L, 91, 101, true, true));

        CallAttributionTracker.AttributionResult detailed =
            tracker.enrich(activity(2_600L, 91, 101, true, false, 0x84, 101));

        assertTrue(detailed.tracked());
        assertFalse(detailed.attribution().destinationBecameKnown());
        assertFalse(detailed.attribution().sourceBecameKnown());
        assertFalse(detailed.attribution().encryptionBecameKnown());
        assertTrue(detailed.attribution().encryptedBeforeObservation());
        assertEquals(0x84, detailed.attribution().encryptionAlgorithmId());
        assertEquals(101, detailed.attribution().encryptionKeyId());

        CallAttributionTracker.AttributionResult repeated =
            tracker.enrich(activity(2_700L, 91, 101, true, false, 0x84, 101));
        assertTrue(repeated.tracked());
        assertNull(repeated.attribution());

        CallAttributionTracker.AttributionResult incomplete =
            tracker.enrich(activity(2_800L, 91, 101, true, false));
        assertTrue(incomplete.tracked());
        assertNull(incomplete.attribution());
    }

    @Test
    void keepsNxdnTypeDAddressDomainDuringLateAttribution()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(activity(3_000L, null, null, false, true,
            P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D));

        CallAttributionTracker.AttributionResult identified =
            tracker.enrich(activity(3_100L, 0x2223, 0x1134, false, false,
                P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D));

        assertTrue(identified.tracked());
        assertEquals(P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D,
            identified.attribution().identityDomain());
    }

    @Test
    void ignoresReservedP25AddressesUntilARealIdentityArrives()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(activity(4_000L, null, null, false, true));

        CallAttributionTracker.AttributionResult reserved =
            tracker.enrich(activity(4_100L, 0xFFFF, 0xFFFFFC, false, false));

        assertTrue(reserved.tracked());
        assertNull(reserved.attribution());

        CallAttributionTracker.AttributionResult identified =
            tracker.enrich(activity(4_200L, 91, 101, false, false));

        assertTrue(identified.tracked());
        assertEquals(91, identified.attribution().destinationId());
        assertEquals(101, identified.attribution().sourceRadioId());
        assertTrue(identified.attribution().destinationBecameKnown());
        assertTrue(identified.attribution().sourceBecameKnown());
    }

    @Test
    void carriesP25TargetIdentityLearnedAfterCallStart()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(activity(5_000L, 91, 101, false, true,
            P25ActivityLogRecords.IdentityDomain.STANDARD, null, null,
            P25ActivityLogRecords.P25TargetIdentity.ORDINARY));

        CallAttributionTracker.AttributionResult identified = tracker.enrich(
            activity(5_100L, 91, 101, false, false,
                P25ActivityLogRecords.IdentityDomain.STANDARD, null, null,
                P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 1_200)));

        assertTrue(identified.tracked());
        assertNotNull(identified.attribution());
        assertFalse(identified.attribution().destinationBecameKnown());
        assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED,
            identified.attribution().p25TargetIdentity().state());
        assertEquals(0xABCDE, identified.attribution().p25TargetIdentity().homeWacn());
        assertEquals(0x321, identified.attribution().p25TargetIdentity().homeSystemId());
        assertEquals(1_200, identified.attribution().p25TargetIdentity().homeTalkgroupId());

        CallAttributionTracker.AttributionResult lessSpecific = tracker.enrich(
            activity(5_200L, 91, 101, false, false,
                P25ActivityLogRecords.IdentityDomain.STANDARD, null, null,
                P25ActivityLogRecords.P25TargetIdentity.ORDINARY));
        assertTrue(lessSpecific.tracked());
        assertNull(lessSpecific.attribution());

        CallAttributionTracker.AttributionResult conflicting = tracker.enrich(
            activity(5_300L, 91, 101, false, false,
                P25ActivityLogRecords.IdentityDomain.STANDARD, null, null,
                P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x322, 1_201)));
        assertTrue(conflicting.tracked());
        assertNotNull(conflicting.attribution());
        assertEquals(P25ActivityLogRecords.P25IdentityState.AMBIGUOUS,
            conflicting.attribution().p25TargetIdentity().state());
    }

    @Test
    void treatsZeroLocalP25TalkgroupAsKnownOnlyWithOneStableHomeTuple()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(activity(5_500L, null, 101, false, true));
        P25ActivityLogRecords.P25TargetIdentity firstIdentity =
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 1_200);

        CallAttributionTracker.AttributionResult identified = tracker.enrich(
            activity(5_600L, 0, 101, false, false,
                P25ActivityLogRecords.IdentityDomain.STANDARD, null, null, firstIdentity));

        assertTrue(identified.tracked());
        assertNotNull(identified.attribution());
        assertTrue(identified.attribution().destinationBecameKnown());
        assertEquals(0, identified.attribution().destinationId());
        assertEquals(firstIdentity, identified.attribution().p25TargetIdentity());

        CallAttributionTracker.AttributionResult repeated = tracker.enrich(
            activity(5_700L, 0, 101, false, false,
                P25ActivityLogRecords.IdentityDomain.STANDARD, null, null, firstIdentity));
        assertTrue(repeated.tracked());
        assertNull(repeated.attribution());

        CallAttributionTracker.AttributionResult conflicting = tracker.enrich(
            activity(5_800L, 0, 101, false, false,
                P25ActivityLogRecords.IdentityDomain.STANDARD, null, null,
                P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x322, 1_201)));
        assertTrue(conflicting.tracked());
        assertNull(conflicting.attribution(), "A different home tuple is a different physical call");
        assertFalse(tracker.enrich(activity(5_900L, 0, 101, false, false,
            P25ActivityLogRecords.IdentityDomain.STANDARD, null, null, firstIdentity)).tracked());
    }

    @Test
    void mergesPatchMemberEvidenceByLocalIdentityWithoutDowngradingIt()
    {
        CallAttributionTracker tracker = new CallAttributionTracker();
        tracker.register(patchActivity(6_000L, List.of(501, 502), List.of(
            new P25ActivityLogRecords.P25PatchMemberIdentity(501,
                P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 1_201)),
            new P25ActivityLogRecords.P25PatchMemberIdentity(502,
                P25ActivityLogRecords.P25TargetIdentity.ORDINARY)), true));

        assertNull(tracker.enrich(patchActivity(6_100L, List.of(), List.of(), false)).attribution());
        CallAttributionTracker.AttributionResult refined = tracker.enrich(patchActivity(6_200L,
            List.of(501, 502), List.of(new P25ActivityLogRecords.P25PatchMemberIdentity(502,
                P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 1_202))), false));

        assertNotNull(refined.attribution());
        assertEquals(List.of(501, 502), refined.attribution().patchMemberTalkgroupIds());
        assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED,
            refined.attribution().p25PatchMemberIdentities().get(0).targetIdentity().state());
        assertEquals(1_201,
            refined.attribution().p25PatchMemberIdentities().get(0).targetIdentity().homeTalkgroupId());
        assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED,
            refined.attribution().p25PatchMemberIdentities().get(1).targetIdentity().state());
        assertEquals(1_202,
            refined.attribution().p25PatchMemberIdentities().get(1).targetIdentity().homeTalkgroupId());

        CallAttributionTracker.AttributionResult removed = tracker.enrich(patchActivity(6_300L,
            List.of(502), List.of(new P25ActivityLogRecords.P25PatchMemberIdentity(502,
                P25ActivityLogRecords.P25TargetIdentity.ORDINARY)), false));
        assertNotNull(removed.attribution());
        assertEquals(List.of(502), removed.attribution().patchMemberTalkgroupIds());
        assertEquals(1, removed.attribution().p25PatchMemberIdentities().size());
        assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED,
            removed.attribution().p25PatchMemberIdentities().get(0).targetIdentity().state());
        assertEquals(1_202,
            removed.attribution().p25PatchMemberIdentities().get(0).targetIdentity().homeTalkgroupId());
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp, Integer target, Integer source,
                                                                 boolean encrypted, boolean countedCall)
    {
        return activity(timestamp, target, source, encrypted, countedCall,
            P25ActivityLogRecords.IdentityDomain.STANDARD);
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp, Integer target, Integer source,
                                                                 boolean encrypted, boolean countedCall,
                                                                 Integer encryptionAlgorithmId,
                                                                 Integer encryptionKeyId)
    {
        return activity(timestamp, target, source, encrypted, countedCall,
            P25ActivityLogRecords.IdentityDomain.STANDARD, encryptionAlgorithmId, encryptionKeyId);
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp, Integer target, Integer source,
                                                                 boolean encrypted, boolean countedCall,
                                                                 P25ActivityLogRecords.IdentityDomain identityDomain)
    {
        return activity(timestamp, target, source, encrypted, countedCall, identityDomain, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp, Integer target, Integer source,
                                                                 boolean encrypted, boolean countedCall,
                                                                 P25ActivityLogRecords.IdentityDomain identityDomain,
                                                                 Integer encryptionAlgorithmId,
                                                                 Integer encryptionKeyId)
    {
        return activity(timestamp, target, source, encrypted, countedCall, identityDomain,
            encryptionAlgorithmId, encryptionKeyId, P25ActivityLogRecords.P25TargetIdentity.UNKNOWN);
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp, Integer target, Integer source,
                                                                 boolean encrypted, boolean countedCall,
                                                                 P25ActivityLogRecords.IdentityDomain identityDomain,
                                                                 Integer encryptionAlgorithmId,
                                                                 Integer encryptionKeyId,
                                                                 P25ActivityLogRecords.P25TargetIdentity p25TargetIdentity)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:test-site", "test-site",
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25",
            countedCall ? P25ActivityLogRecords.Action.CALL : P25ActivityLogRecords.Action.ACTIVE,
            encrypted ? "CALL_GROUP_ENCRYPTED" : "CALL_GROUP",
            source != null ? source.toString() : null, target != null ? target.toString() : null,
            target != null ? Form.TALKGROUP.name() : null, List.of(), 851_012_500L, "1-100", 1, encrypted,
            encryptionAlgorithmId, encryptionKeyId, 0xbee00, 0x123, 0x293, 1, 2, "P25 Site", "P25_PHASE2", null,
            countedCall, null, null, identityDomain, p25TargetIdentity);
    }

    private static P25ActivityLogRecords.ActivityEvent patchActivity(
        long timestamp, List<Integer> patchMembers,
        List<P25ActivityLogRecords.P25PatchMemberIdentity> p25PatchMemberIdentities, boolean countedCall)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:test-site", "test-site",
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25",
            countedCall ? P25ActivityLogRecords.Action.CALL : P25ActivityLogRecords.Action.CONTINUE,
            "CALL_PATCH_GROUP", "101", "91", Form.PATCH_GROUP.name(), patchMembers, 851_012_500L,
            "1-100", 1, false, null, null, 0xbee00, 0x123, 0x293, 1, 2, "P25 Site", "P25_PHASE2", null,
            countedCall, null, null, P25ActivityLogRecords.IdentityDomain.STANDARD,
            P25ActivityLogRecords.P25TargetIdentity.ORDINARY, p25PatchMemberIdentities);
    }
}
