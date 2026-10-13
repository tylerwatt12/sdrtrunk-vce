/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.IdentityResolutionEvidence;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25IdentityResolverGroupTest
{
    private static final P25SiteIdentity SITE = new P25SiteIdentity(0xBEE00, 0x348, 1, 1);
    private static final int LOCAL = 1_234;
    private static final int HOME = 5_678;

    @Test
    void conflictingCapturedAndBroadcastSystemsStopLearningAndRetireTheOldSource()
    {
        var resolver = new P25IdentityResolver();
        var channel = new io.github.dsheirer.controller.channel.Channel("Serving context", io.github.dsheirer.controller.channel.Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2());
        channel.setP25SiteIdentity(SITE);
        var manager = new P25TrafficChannelManager(channel,
            io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry.empty(), resolver);
        assertTrue(manager.processP25GroupPresence(P25AffiliationEvent.Outcome.ACCEPTED, pair(LOCAL, HOME), 1_000));
        assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class, resolve(resolver, SITE, LOCAL, 1_001));
        manager.processNetworkConfigurationIdentity(new P25SiteIdentity(SITE.wacn(), SITE.system() + 1, 1, 1), null, 1_100);
        assertFalse(manager.processP25GroupPresence(P25AffiliationEvent.Outcome.ACCEPTED, pair(LOCAL, HOME + 1), 1_200));
        var identifiers = new MutableIdentifierCollection(List.of(APCO25Talkgroup.create(LOCAL)));
        manager.enrichMutableIdentifiers(identifiers, 1_201);
        assertInstanceOf(APCO25Talkgroup.class, identifiers.getToIdentifier());
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 1_201));
        manager.processNetworkConfigurationIdentity(new P25SiteIdentity(SITE.wacn(), SITE.system(), 9, 9), null, 2_000);
        assertTrue(manager.processP25GroupPresence(P25AffiliationEvent.Outcome.ACCEPTED, pair(LOCAL, HOME), 2_001));
        assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class, resolve(resolver, SITE, LOCAL, 2_002));
    }

    @Test
    void acceptedIndependentPairResolvesBothRolesAcrossSitesInTheSameReceivingSystem()
    {
        var resolver = new P25IdentityResolver();
        assertTrue(resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_000, null));
        for(Role role: List.of(Role.FROM, Role.TO))
        {
            var raw = new APCO25Talkgroup(LOCAL, role);
            var identifiers = new MutableIdentifierCollection(List.of(raw));
            resolver.enrich(new P25SiteIdentity(SITE.wacn(), SITE.system(), 9, 9), identifiers, 2_000);
            var group = assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class, identifiers.getIdentifiers().getFirst());
            assertEquals(role, group.getRole());
            assertEquals(LOCAL, group.getLocalAddress());
            assertEquals(HOME, group.getTalkgroup());
            assertEquals(0xABCDE, group.getWacn());
            assertEquals(IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT, group.getResolutionEvidence());
            assertSame(raw, group.getRecordingIdentifier());
        }
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, new P25SiteIdentity(SITE.wacn(), 0x349, 1, 1), LOCAL, 2_000));
        assertEquals(1, resolver.snapshot(2_000).groupAssignments().size());
        assertEquals(0, resolver.snapshot(2_000).assignments().size());
    }

    @Test
    void rejectedHomeOnlyZeroReservedAndDerivedObservationsNeverLearnMappings()
    {
        var resolver = new P25IdentityResolver();
        assertFalse(resolver.observeGroup(SITE, pair(LOCAL, HOME), false, 1_000, null));
        for(var group: List.of(
            APCO25FullyQualifiedTalkgroupIdentifier.createFrom(LOCAL, 0xABCDE, 0x456, HOME),
            pair(0, HOME), pair(0xFFFF, HOME), pair(LOCAL, 0), pair(LOCAL, 0xFFFF),
            APCO25FullyQualifiedTalkgroupIdentifier.createWithLocalAddress(LOCAL, 0xABCDE, 0x456, HOME,
                Role.FROM, IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT)))
            assertFalse(resolver.observeGroup(SITE, group, true, 1_000, null));
        assertFalse(resolver.observeGroup(null, pair(LOCAL, HOME), true, 1_000, null));
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 2_000));
    }

    @Test
    void equalHomeAndLocalNumbersRequireSeparateFieldEvidence()
    {
        var resolver = new P25IdentityResolver();
        assertFalse(resolver.observeGroup(SITE,
            APCO25FullyQualifiedTalkgroupIdentifier.createFrom(HOME, 0xABCDE, 0x456, HOME), true, 1_000, null));
        assertTrue(resolver.observeGroup(SITE, pair(HOME, HOME), true, 2_000, null));
        var result = assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class, resolve(resolver, SITE, HOME, 2_100));
        assertEquals(HOME, result.getLocalAddress());
        assertTrue(result.hasExplicitLocalAddress());
    }

    @Test
    void groupLifetimeDoesNotUseTheRadioLeaseAndAcceptedEvidenceRefreshesIt()
    {
        var resolver = new P25IdentityResolver();
        resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_000, null);
        assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class, resolve(resolver, SITE, LOCAL, 300_999));
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 301_001));
        assertTrue(resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 302_000, null));
        assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class, resolve(resolver, SITE, LOCAL, 302_001));
        assertEquals(602_000, resolver.snapshot(302_001).groupAssignments().getFirst().expiresAt());
    }

    @Test
    void reassignmentsRetireBothOldDirectionsAndRejectDelayedEvidence()
    {
        var resolver = new P25IdentityResolver();
        resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_000, null);
        resolver.observeGroup(SITE, pair(LOCAL + 1, HOME), true, 2_000, null);
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 2_001));
        assertFalse(resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_500, null));
        resolver.observeGroup(SITE, pair(LOCAL + 1, HOME + 1), true, 3_000, null);
        assertFalse(resolver.observeGroup(SITE, pair(LOCAL + 2, HOME), true, 2_500, null));
        assertEquals(HOME + 1, assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class,
            resolve(resolver, SITE, LOCAL + 1, 3_001)).getTalkgroup());
    }

    @Test
    void equalTimestampConflictsRemainUnresolvedUntilNewerAcceptedEvidence()
    {
        var resolver = new P25IdentityResolver();
        resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_000, null);
        assertFalse(resolver.observeGroup(SITE, pair(LOCAL, HOME + 1), true, 1_000, null));
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 1_001));
        assertFalse(resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_000, null));
        assertTrue(resolver.snapshot(1_001).groupAssignments().isEmpty());
        assertTrue(resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 2_000, null));
        assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class, resolve(resolver, SITE, LOCAL, 2_001));
    }

    @Test
    void continuityGapsResetOnlyTheReceivingSystemAndDelayedEvidenceCannotReviveIt()
    {
        var resolver = new P25IdentityResolver();
        var other = new P25SiteIdentity(SITE.wacn(), SITE.system() + 1, 1, 1);
        resolver.observeSource(SITE, "one", 1_000);
        resolver.observeSource(other, "other", 1_000);
        assertTrue(resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_100, "one"));
        assertTrue(resolver.observeGroup(other, pair(LOCAL, HOME + 1), true, 1_100, "other"));
        resolver.observationGap(SITE, "one", 2_000);
        resolver.observeSource(other, "other", 2_000);
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 2_001));
        assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class, resolve(resolver, other, LOCAL, 2_001));
        resolver.observeSource(SITE, "one", 3_000);
        assertFalse(resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_500, "one"));
        assertTrue(resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 3_100, "one"));
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 64_001));
        assertTrue(resolver.snapshot(64_001).groupAssignments().isEmpty());
    }

    @Test
    void losingOneControlSiteRetainsMappingsWhileAnotherObservedContinuously()
    {
        var resolver = new P25IdentityResolver();
        resolver.observeSource(SITE, "one", 1_000);
        resolver.observeSource(SITE, "two", 1_000);
        resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_100, "one");
        resolver.observeSource(SITE, "two", 61_000);
        resolver.observationGap(SITE, "one", 62_000);
        assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class, resolve(resolver, SITE, LOCAL, 90_000));
    }

    @Test
    void groupAndRadioNamespacesCannotCollideAndPatchResolutionDoesNotMutateTheCache()
    {
        var resolver = new P25IdentityResolver();
        resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_000, null);
        resolver.register(SITE, new P25RadioPresence(new P25SubscriberIdentity(0x12345, 0x222, 700_001), LOCAL), 1_000, null);
        var patch = new PatchGroup(APCO25Talkgroup.create(LOCAL + 1), 7);
        var member = APCO25Talkgroup.create(LOCAL);
        patch.addPatchedTalkgroup(member);
        patch.addPatchedRadio(APCO25RadioIdentifier.createFrom(LOCAL));
        var identifiers = new MutableIdentifierCollection(List.of(APCO25PatchGroup.create(patch)));
        resolver.enrich(SITE, identifiers, 2_000);
        var result = assertInstanceOf(APCO25PatchGroup.class, identifiers.getToIdentifier()).getValue();
        assertEquals(7, result.getVersion());
        assertEquals(HOME, assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class,
            result.getPatchedTalkgroupIdentifiers().getFirst()).getTalkgroup());
        assertEquals(700_001, assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            result.getPatchedRadioIdentifiers().getFirst()).getRadio());
        assertSame(member, patch.getPatchedTalkgroupIdentifiers().getFirst());
        assertEquals(LOCAL + 1, result.getPatchGroup().getValue());
        io.github.dsheirer.identifier.IdentifierCollection immutable = new io.github.dsheirer.identifier.IdentifierCollection(
            List.of(APCO25PatchGroup.create(patch)));
        var projected = resolver.enrich(SITE, immutable, 2_001);
        assertNotSame(immutable, projected, "An unchanged primary group cannot hide newly qualified members");
        assertInstanceOf(FullyQualifiedTalkgroupIdentifier.class,
            ((APCO25PatchGroup)projected.getToIdentifier()).getValue().getPatchedTalkgroupIdentifiers().getFirst());
    }

    @Test
    void saturationAndResetInvalidateGroupAssignmentsWithoutWaiting()
    {
        var resolver = new P25IdentityResolver(2, 2);
        resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_000, null);
        resolver.observeGroup(SITE, pair(LOCAL + 1, HOME + 1), true, 1_000, null);
        assertFalse(resolver.observeGroup(SITE, pair(LOCAL + 2, HOME + 2), true, 1_000, null));
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 1_001));
        assertTrue(resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 2_000, null));
        resolver.reset();
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 2_001));
    }

    @Test
    void enrichmentCannotPublishAcrossAnInvalidatedGeneration()
    {
        P25IdentityResolver[] holder = new P25IdentityResolver[1];
        holder[0] = new P25IdentityResolver(16, 8, () -> {}, () -> holder[0].reset());
        var resolver = holder[0];
        resolver.observeGroup(SITE, pair(LOCAL, HOME), true, 1_000, null);
        assertInstanceOf(APCO25Talkgroup.class, resolve(resolver, SITE, LOCAL, 2_000));
    }

    private static APCO25FullyQualifiedTalkgroupIdentifier pair(int local, int home)
    {
        return APCO25FullyQualifiedTalkgroupIdentifier.createFromWithLocalAddress(local, 0xABCDE, 0x456, home);
    }

    private static Identifier<?> resolve(P25IdentityResolver resolver, P25SiteIdentity site, int local, long at)
    {
        var identifiers = new MutableIdentifierCollection(List.of(APCO25Talkgroup.create(local)));
        resolver.enrich(site, identifiers, at);
        return identifiers.getToIdentifier();
    }
}
