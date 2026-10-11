/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class P25WuidAssignmentRegistryTest
{
    private static final P25SiteIdentity SERVING_SITE = new P25SiteIdentity(0xBEE00, 0x348, 1, 1);
    private static final int WORKING_ID = 501;
    private static final int HOME_WACN = 0xABCDE;
    private static final int HOME_SYSTEM = 0x456;
    private static final int SUBSCRIBER = 2_115_288;

    @Test
    void registrationReturnsACompletePositiveObservation()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();

        P25WuidAssignmentRegistry.AssignmentObservation observation =
            registry.register(SERVING_SITE, assignmentPresence(), 1_000L, 300);

        assertNotNull(observation);
        assertEquals(SERVING_SITE.wacn(), observation.servingWacn());
        assertEquals(SERVING_SITE.system(), observation.servingSystem());
        assertEquals(WORKING_ID, observation.workingId());
        assertEquals(subscriber(), observation.subscriber());
        assertEquals(1_000L, observation.observedAt());
        assertEquals(1_000L + 300L * 60_000L, observation.expiresAt());
        assertEquals(P25WuidAssignmentRegistry.Evidence.REGISTRATION, observation.evidence());
    }

    @Test
    void explicitNoExpiryLeaseDoesNotBecomeTheFiniteDefault()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        P25WuidAssignmentRegistry.AssignmentObservation observation = registry.register(SERVING_SITE,
            assignmentPresence(), 1_000L, P25WuidAssignmentRegistry.NO_EXPIRY_LEASE_MINUTES);

        assertNotNull(observation);
        assertEquals(Long.MAX_VALUE, observation.expiresAt());
        assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, SERVING_SITE, WORKING_ID, Long.MAX_VALUE - 1));
    }

    @Test
    void promotesOnlyTheMatchingServingSystemUntilExpiry()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);

        Identifier<?> promoted = enrich(registry, new P25SiteIdentity(0xBEE00, 0x348, 9, 9),
            WORKING_ID, 2_000L);
        APCO25FullyQualifiedRadioIdentifier fullyQualified =
            assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class, promoted);
        assertEquals(WORKING_ID, fullyQualified.getValue());
        assertEquals(HOME_WACN, fullyQualified.getWacn());
        assertEquals(HOME_SYSTEM, fullyQualified.getSystem());
        assertEquals(SUBSCRIBER, fullyQualified.getRadio());

        assertInstanceOf(APCO25RadioIdentifier.class,
            enrich(registry, new P25SiteIdentity(0xBEE00, 0x349, 1, 1), WORKING_ID, 2_000L));
        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, WORKING_ID,
            1_000L + P25WuidAssignmentRegistry.DEFAULT_LEASE_MILLISECONDS + 1L));
    }

    @Test
    void affiliationCanOnlyRefreshAnExistingExactMapping()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        P25SubscriberIdentity otherSubscriber = new P25SubscriberIdentity(0x12345, 0x222, 700_001);

        assertNull(registry.refresh(SERVING_SITE, assignmentPresence(), 1_000L, 300),
            "affiliation evidence cannot establish a mapping");
        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, WORKING_ID, 1_100L));

        registry.register(SERVING_SITE, assignmentPresence(), 1_200L, 300);
        assertNull(registry.refresh(SERVING_SITE, new P25RadioPresence(otherSubscriber, WORKING_ID),
            1_300L, 300), "an asserted canonical identity must match the current assignment");

        P25WuidAssignmentRegistry.AssignmentObservation observation = registry.refresh(SERVING_SITE,
            new P25RadioPresence(null, WORKING_ID), 1_400L, 300);
        assertNotNull(observation);
        assertEquals(subscriber(), observation.subscriber());
        assertEquals(WORKING_ID, observation.workingId());
        assertEquals(P25WuidAssignmentRegistry.Evidence.AFFILIATION, observation.evidence());
        assertEquals(1_400L + 300L * 60_000L, observation.expiresAt());
    }

    @Test
    void canonicalClearAndTimestampOrderingPreventDelayedRevival()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        registry.clear(SERVING_SITE, new P25RadioPresence(subscriber(), null), 2_000L);

        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, WORKING_ID, 2_100L));

        assertNull(registry.register(SERVING_SITE, assignmentPresence(), 1_500L, null));
        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, WORKING_ID, 2_100L));

        assertNotNull(registry.register(SERVING_SITE, assignmentPresence(), 2_200L, null));
        assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, SERVING_SITE, WORKING_ID, 2_300L));
    }

    @Test
    void equalValuedWorkingAndSubscriberIdsRemainExplicit()
    {
        int equalId = 1_234_567;
        P25SubscriberIdentity equalSubscriber = new P25SubscriberIdentity(HOME_WACN, HOME_SYSTEM, equalId);
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        registry.register(SERVING_SITE, new P25RadioPresence(equalSubscriber, equalId), 1_000L, null);

        APCO25FullyQualifiedRadioIdentifier enriched = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, enrich(registry, SERVING_SITE, equalId, 1_100L));
        assertTrue(enriched.hasExplicitWorkingAddress());
        assertEquals(equalId, enriched.getWorkingAddress());
        assertEquals(equalId, enriched.getRadio());
    }

    @Test
    void identityWithoutAnExplicitWorkingIdDoesNotEstablishAnAssignment()
    {
        APCO25FullyQualifiedRadioIdentifier identityOnly = APCO25FullyQualifiedRadioIdentifier.createFrom(
            SUBSCRIBER, HOME_WACN, HOME_SYSTEM, SUBSCRIBER);
        P25RadioPresence presence = P25RadioPresence.from(identityOnly, null);
        assertNull(presence.workingId());

        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        assertNull(registry.register(SERVING_SITE, presence, 1_000L, null));
        assertInstanceOf(APCO25RadioIdentifier.class,
            enrich(registry, SERVING_SITE, SUBSCRIBER, 1_100L));
    }

    @Test
    void subscriberAndWorkingAddressReassignmentInvalidateBothOldDirections()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        registry.register(SERVING_SITE, new P25RadioPresence(subscriber(), 502), 2_000L, null);

        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, WORKING_ID, 2_100L));
        assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class, enrich(registry, SERVING_SITE, 502, 2_100L));

        P25SubscriberIdentity replacement = new P25SubscriberIdentity(0x12345, 0x222, 700_001);
        registry.register(SERVING_SITE, new P25RadioPresence(replacement, 502), 3_000L, null);
        APCO25FullyQualifiedRadioIdentifier reassigned = assertInstanceOf(
            APCO25FullyQualifiedRadioIdentifier.class, enrich(registry, SERVING_SITE, 502, 3_100L));
        assertEquals(replacement.homeWacn(), reassigned.getWacn());
        assertEquals(replacement.subscriberId(), reassigned.getRadio());
    }

    @Test
    void trafficManagerReturnsOnlyPositiveAcceptedObservations()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        Channel firstSite = new Channel("First Site");
        firstSite.setP25SiteIdentity(SERVING_SITE);
        Channel secondSite = new Channel("Second Site");
        secondSite.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x348, 2, 7));
        P25TrafficChannelManager first = new P25TrafficChannelManager(firstSite,
            P25BandplanOverrideRegistry.empty(), registry);
        P25TrafficChannelManager second = new P25TrafficChannelManager(secondSite,
            P25BandplanOverrideRegistry.empty(), registry);

        assertNull(first.processP25RadioPresence(P25AffiliationEvent.Outcome.REJECTED, DecodeEventType.REGISTER,
            assignmentPresence(), 1_000L));
        assertNull(first.processP25RadioPresence(P25AffiliationEvent.Outcome.ACCEPTED, DecodeEventType.AFFILIATE,
            assignmentPresence(), 1_100L), "accepted affiliation cannot create the first assignment");

        P25WuidAssignmentRegistry.AssignmentObservation registration = first.processP25RadioPresence(
            P25AffiliationEvent.Outcome.ACCEPTED, DecodeEventType.REGISTER, assignmentPresence(), 1_200L);
        assertEquals(P25WuidAssignmentRegistry.Evidence.REGISTRATION, registration.evidence());
        MutableIdentifierCollection accepted = ordinary(WORKING_ID);
        second.enrichMutableIdentifiers(accepted, 1_300L);
        assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class, accepted.getFromIdentifier());

        P25WuidAssignmentRegistry.AssignmentObservation affiliation = first.processP25RadioPresence(
            P25AffiliationEvent.Outcome.CONFIRMED, DecodeEventType.AFFILIATE,
            new P25RadioPresence(null, WORKING_ID), 1_400L);
        assertEquals(P25WuidAssignmentRegistry.Evidence.AFFILIATION, affiliation.evidence());
        assertEquals(subscriber(), affiliation.subscriber());

        assertNull(first.processP25RadioPresence(P25AffiliationEvent.Outcome.CLEARED, DecodeEventType.DEREGISTER,
            new P25RadioPresence(null, WORKING_ID), 1_500L));
        MutableIdentifierCollection cleared = ordinary(WORKING_ID);
        second.enrichMutableIdentifiers(cleared, 1_600L);
        assertInstanceOf(APCO25RadioIdentifier.class, cleared.getFromIdentifier());
    }

    @Test
    void eventCarriesThePositiveObservationSeparatelyFromRadioPresence()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        P25WuidAssignmentRegistry.AssignmentObservation observation =
            registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        Identifier<?> radio = APCO25RadioIdentifier.createTo(WORKING_ID);

        P25AffiliationEvent event = new P25AffiliationEvent(DecodeEventType.REGISTER, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED, assignmentPresence(), observation, radio, null);
        assertSame(observation, event.getAssignmentObservation());
        assertEquals(assignmentPresence(), event.getRadioPresence());

        P25AffiliationEvent compatibility = new P25AffiliationEvent(DecodeEventType.REGISTER, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED, assignmentPresence(), radio, null);
        assertNull(compatibility.getAssignmentObservation());
    }

    @Test
    void advertisedLeaseFollowsServingSystemRatherThanSite()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry();
        Channel channel = new Channel("Runtime identity");
        P25TrafficChannelManager manager = new P25TrafficChannelManager(channel,
            P25BandplanOverrideRegistry.empty(), registry);
        P25SiteIdentity firstSite = new P25SiteIdentity(0xBEE00, 0x348, 1, 1);
        P25SiteIdentity secondSite = new P25SiteIdentity(0xBEE00, 0x348, 2, 2);
        P25SiteIdentity otherSystem = new P25SiteIdentity(0xBEE00, 0x349, 1, 1);

        manager.processNetworkConfigurationIdentity(firstSite, 300);
        manager.processNetworkConfigurationIdentity(secondSite, null);
        manager.processP25RadioPresence(P25AffiliationEvent.Outcome.ACCEPTED, DecodeEventType.REGISTER,
            assignmentPresence(), 1_000L);
        assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, secondSite, WORKING_ID, 1_000L + 299L * 60_000L));

        manager.processNetworkConfigurationIdentity(otherSystem, null);
        manager.processP25RadioPresence(P25AffiliationEvent.Outcome.ACCEPTED, DecodeEventType.REGISTER,
            new P25RadioPresence(subscriber(), 502), 2_000L);
        assertInstanceOf(APCO25RadioIdentifier.class,
            enrich(registry, otherSystem, 502, 2_000L + 271L * 60_000L));
    }

    @Test
    void saturationInvalidatesTheWholeCacheGeneration()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(2, 2);
        assertEquals(2, registry.slotCapacity());

        assertNotNull(registry.register(SERVING_SITE, presence(1, 1_001), 1L, null));
        assertNotNull(registry.register(SERVING_SITE, presence(2, 1_002), 2L, null));
        assertNull(registry.register(SERVING_SITE, presence(3, 1_003), 3L, null));

        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, 1, 100L));
        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, 2, 100L));
        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, 3, 100L));
        assertTrue(registry.saturatedMutationCount() > 0);

        assertNotNull(registry.register(SERVING_SITE, presence(3, 1_003), 4L, null));
        assertEquals(1_003, assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, SERVING_SITE, 3, 100L)).getRadio());
    }

    @Test
    void contendedRegistrationReturnsImmediatelyAndInvalidatesTheWholeCache() throws Exception
    {
        BlockingMutation blocking = new BlockingMutation();
        P25WuidAssignmentRegistry registry = blocking.registry();
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        registry.register(SERVING_SITE, presence(777, 700_001), 1_000L, null);

        blocking.arm();
        CompletableFuture<P25WuidAssignmentRegistry.AssignmentObservation> owner =
            CompletableFuture.supplyAsync(() -> registry.refresh(SERVING_SITE,
                new P25RadioPresence(null, WORKING_ID), 1_050L, null));
        blocking.awaitEntry();

        P25SubscriberIdentity replacement = new P25SubscriberIdentity(HOME_WACN, HOME_SYSTEM, SUBSCRIBER + 1);
        try
        {
            P25WuidAssignmentRegistry.AssignmentObservation dropped = assertTimeoutPreemptively(
                Duration.ofMillis(250), () -> registry.register(SERVING_SITE,
                    new P25RadioPresence(replacement, WORKING_ID), 1_100L, null));
            assertNull(dropped);
            assertEquals(1, registry.droppedMutationCount());
            assertInstanceOf(APCO25RadioIdentifier.class,
                enrich(registry, SERVING_SITE, WORKING_ID, 1_150L));
            assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, 777, 1_150L));
        }
        finally
        {
            blocking.release();
        }

        assertNull(owner.get(2, TimeUnit.SECONDS));
        assertNotNull(registry.register(SERVING_SITE,
            new P25RadioPresence(replacement, WORKING_ID), 1_200L, null));
        assertEquals(replacement.subscriberId(), assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, SERVING_SITE, WORKING_ID, 1_250L)).getRadio());
    }

    @Test
    void contendedClearReturnsImmediatelyAndInvalidatesTheWholeCache() throws Exception
    {
        BlockingMutation blocking = new BlockingMutation();
        P25WuidAssignmentRegistry registry = blocking.registry();
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        registry.register(SERVING_SITE, presence(777, 700_001), 1_000L, null);

        blocking.arm();
        CompletableFuture<P25WuidAssignmentRegistry.AssignmentObservation> owner =
            CompletableFuture.supplyAsync(() -> registry.refresh(SERVING_SITE,
                new P25RadioPresence(null, WORKING_ID), 1_050L, null));
        blocking.awaitEntry();

        try
        {
            assertTimeoutPreemptively(Duration.ofMillis(250), () -> registry.clear(SERVING_SITE,
                new P25RadioPresence(null, WORKING_ID), 1_100L, null));
            assertEquals(1, registry.droppedMutationCount());
            assertInstanceOf(APCO25RadioIdentifier.class,
                enrich(registry, SERVING_SITE, WORKING_ID, 1_150L));
            assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, 777, 1_150L));
        }
        finally
        {
            blocking.release();
        }

        assertNull(owner.get(2, TimeUnit.SECONDS));
    }

    @Test
    void contendedAffiliationDropsOnlyTheRefreshAndKeepsTheCache() throws Exception
    {
        BlockingMutation blocking = new BlockingMutation();
        P25WuidAssignmentRegistry registry = blocking.registry();
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);

        blocking.arm();
        CompletableFuture<P25WuidAssignmentRegistry.AssignmentObservation> owner =
            CompletableFuture.supplyAsync(() -> registry.register(SERVING_SITE,
                presence(777, 700_001), 1_050L, null));
        blocking.awaitEntry();

        P25WuidAssignmentRegistry.AssignmentObservation dropped = assertTimeoutPreemptively(
            Duration.ofMillis(250), () -> registry.refresh(SERVING_SITE,
                new P25RadioPresence(null, WORKING_ID), 1_100L, null));
        assertNull(dropped);
        assertEquals(1, registry.droppedMutationCount());

        blocking.release();
        assertNotNull(owner.get(2, TimeUnit.SECONDS));
        assertEquals(SUBSCRIBER, assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, SERVING_SITE, WORKING_ID, 1_150L)).getRadio());
        assertEquals(700_001, assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, SERVING_SITE, 777, 1_150L)).getRadio());
    }

    @Test
    void enrichmentCapturedBeforeResetCannotPublishTheRetiredAssignment() throws Exception
    {
        CountDownLatch lookupComplete = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(16, 4, () -> {}, () -> {
            lookupComplete.countDown();
            await(releaseLookup);
        });
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        CompletableFuture<Identifier<?>> enrichment = CompletableFuture.supplyAsync(() ->
            enrich(registry, SERVING_SITE, WORKING_ID, 1_100L));

        assertTrue(lookupComplete.await(2, TimeUnit.SECONDS));
        registry.reset();
        releaseLookup.countDown();

        assertInstanceOf(APCO25RadioIdentifier.class, enrichment.get(2, TimeUnit.SECONDS));
        assertInstanceOf(APCO25RadioIdentifier.class,
            enrich(new P25WuidAssignmentRegistry(), SERVING_SITE, WORKING_ID, 1_100L));
    }

    @Test
    void enrichmentCannotCrossACompletedReassignment() throws Exception
    {
        CountDownLatch lookupComplete = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(16, 4, () -> {}, () -> {
            if(pause.compareAndSet(true, false))
            {
                lookupComplete.countDown();
                await(releaseLookup);
            }
        });
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        CompletableFuture<Identifier<?>> enrichment = CompletableFuture.supplyAsync(() ->
            enrich(registry, SERVING_SITE, WORKING_ID, 1_100L));

        assertTrue(lookupComplete.await(2, TimeUnit.SECONDS));
        P25SubscriberIdentity replacement = new P25SubscriberIdentity(0x12345, 0x222, 700_001);
        assertNotNull(registry.register(SERVING_SITE,
            new P25RadioPresence(replacement, WORKING_ID), 1_200L, null));
        releaseLookup.countDown();

        assertInstanceOf(APCO25RadioIdentifier.class, enrichment.get(2, TimeUnit.SECONDS));
        assertEquals(replacement.subscriberId(), assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, SERVING_SITE, WORKING_ID, 1_300L)).getRadio());
    }

    @Test
    void resetBetweenCaptureAndLockDropsTheOldRegistration() throws Exception
    {
        CountDownLatch beforeLock = new CountDownLatch(1);
        CountDownLatch releaseLockAttempt = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(16, 4, () -> {}, () -> {}, () -> {
            if(pause.compareAndSet(true, false))
            {
                beforeLock.countDown();
                await(releaseLockAttempt);
            }
        });
        CompletableFuture<P25WuidAssignmentRegistry.AssignmentObservation> registration =
            CompletableFuture.supplyAsync(() -> registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null));

        assertTrue(beforeLock.await(2, TimeUnit.SECONDS));
        registry.reset();
        releaseLockAttempt.countDown();

        assertNull(registration.get(2, TimeUnit.SECONDS));
        assertInstanceOf(APCO25RadioIdentifier.class,
            enrich(registry, SERVING_SITE, WORKING_ID, 1_100L));
    }

    @Test
    void observerSnapshotCarriesUsableEvidenceAndIsImmutable()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(128, 8);
        registry.observeSource(SERVING_SITE, "source-a", 1_000L);
        registry.register(SERVING_SITE, assignmentPresence(), 1_100L, 300, "source-a");
        P25WuidAssignmentRegistry.Snapshot snapshot = registry.snapshot(1_200L);
        assertFalse(snapshot.stale());
        assertEquals(1, snapshot.usableCount());
        P25WuidAssignmentRegistry.Entry entry = snapshot.assignments().getFirst();
        assertEquals(SERVING_SITE, entry.site());
        assertEquals("source-a", entry.configurationId());
        assertEquals(P25WuidAssignmentRegistry.LeaseSource.ADVERTISED, entry.leaseSource());
        assertEquals(P25WuidAssignmentRegistry.Evidence.REGISTRATION, entry.evidence());
        assertTrue(snapshot.systems().getFirst().observing());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.assignments().clear());
        var promoted = assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, SERVING_SITE, WORKING_ID, 1_300L));
        assertEquals(io.github.dsheirer.identifier.radio.ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT,
            promoted.getResolutionEvidence());
    }

    @Test
    void lastSourceGapInvalidatesOnlyItsSystemAndDelayedEvidenceCannotReviveIt()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(128, 8);
        P25SiteIdentity secondSite = new P25SiteIdentity(SERVING_SITE.wacn(), SERVING_SITE.system(), 2, 2);
        P25SiteIdentity otherSystem = new P25SiteIdentity(SERVING_SITE.wacn(), SERVING_SITE.system() + 1, 1, 1);
        registry.observeSource(SERVING_SITE, "source-a", 1_000L);
        registry.observeSource(secondSite, "source-b", 1_000L);
        registry.observeSource(otherSystem, "source-c", 1_000L);
        registry.register(SERVING_SITE, assignmentPresence(), 1_100L, null, "source-a");
        registry.register(otherSystem, assignmentPresence(), 1_100L, null, "source-c");
        registry.observationGap(SERVING_SITE, "source-a", 2_000L);
        assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, secondSite, WORKING_ID, 2_001L));
        registry.observationGap(secondSite, "source-b", 2_100L);
        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, WORKING_ID, 2_101L));
        assertInstanceOf(APCO25FullyQualifiedRadioIdentifier.class,
            enrich(registry, otherSystem, WORKING_ID, 2_101L));
        registry.observeSource(SERVING_SITE, "source-a", 3_000L);
        assertNull(registry.register(SERVING_SITE, assignmentPresence(), 2_000L, null, "source-a"));
        assertNotNull(registry.register(SERVING_SITE, assignmentPresence(), 3_100L, null, "source-a"));
    }

    @Test
    void observationTimeoutProtectsDecodeWithoutAnyWebSnapshot()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(128, 8);
        registry.observeSource(SERVING_SITE, "source-a", 1_000L);
        registry.register(SERVING_SITE, assignmentPresence(), 1_100L, 0, "source-a");
        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, WORKING_ID,
            1_001L + P25WuidAssignmentRegistry.OBSERVATION_GAP_MILLISECONDS));
        registry.observeSource(SERVING_SITE, "source-a", 70_000L);
        assertInstanceOf(APCO25RadioIdentifier.class, enrich(registry, SERVING_SITE, WORKING_ID, 70_001L));
        assertNotNull(registry.register(SERVING_SITE, assignmentPresence(), 70_002L, 0, "source-a"));
    }

    @Test
    void observerReportsExpiryOnceAndUsesFallbackLeaseLabel()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(128, 8);
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        assertEquals(P25WuidAssignmentRegistry.LeaseSource.FALLBACK,
            registry.snapshot(1_100L).assignments().getFirst().leaseSource());
        long afterExpiry = 1_001L + P25WuidAssignmentRegistry.DEFAULT_LEASE_MILLISECONDS;
        var expired = registry.snapshot(afterExpiry);
        assertEquals(0, expired.usableCount());
        assertEquals(1, expired.recentChanges().stream()
            .filter(change -> change.change() == P25WuidAssignmentRegistry.ChangeReason.EXPIRED).count());
        assertEquals(1, registry.snapshot(afterExpiry + 1).recentChanges().stream()
            .filter(change -> change.change() == P25WuidAssignmentRegistry.ChangeReason.EXPIRED).count());
    }

    @Test
    void observerDoesNotReportAnOldLeaseAsExpiredAfterRenewal()
    {
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(128, 8);
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        registry.snapshot(1_100L);
        long originalExpiry = 1_000L + P25WuidAssignmentRegistry.DEFAULT_LEASE_MILLISECONDS;
        assertNotNull(registry.refresh(SERVING_SITE, assignmentPresence(), originalExpiry - 1L, null));

        var renewed = registry.snapshot(originalExpiry + 1L);
        assertEquals(1, renewed.usableCount());
        assertEquals(1_000L, renewed.assignments().getFirst().establishedAt());
        assertEquals(0, renewed.recentChanges().stream()
            .filter(change -> change.change() == P25WuidAssignmentRegistry.ChangeReason.EXPIRED).count());
        registry.register(SERVING_SITE, assignmentPresence(), originalExpiry + 2L, null);
        assertEquals(1_000L, registry.snapshot(originalExpiry + 3L).assignments().getFirst().establishedAt());
    }

    @Test
    void observationGapDuringEnrichmentCannotPublishARetiredMapping() throws Exception
    {
        CountDownLatch enrichmentEntered = new CountDownLatch(1);
        CountDownLatch releaseEnrichment = new CountDownLatch(1);
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(128, 8, () -> {}, () -> {
            enrichmentEntered.countDown();
            await(releaseEnrichment);
        });
        registry.observeSource(SERVING_SITE, "source-a", 1_000L);
        registry.register(SERVING_SITE, assignmentPresence(), 1_100L, null, "source-a");
        CompletableFuture<Identifier<?>> enrichment = CompletableFuture.supplyAsync(() ->
            enrich(registry, SERVING_SITE, WORKING_ID, 1_200L));
        try
        {
            assertTrue(enrichmentEntered.await(1, TimeUnit.SECONDS));
            registry.observationGap(SERVING_SITE, "source-a", 1_300L);
        }
        finally
        {
            releaseEnrichment.countDown();
        }
        assertInstanceOf(APCO25RadioIdentifier.class, enrichment.get(1, TimeUnit.SECONDS));
    }

    @Test
    void samplingDuringMutationRetainsLastGoodViewWithoutTakingTheMutationLock() throws Exception
    {
        AtomicBoolean block = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        P25WuidAssignmentRegistry registry = new P25WuidAssignmentRegistry(128, 8, () -> {
            if(block.get())
            {
                entered.countDown();
                await(release);
            }
        });
        registry.register(SERVING_SITE, assignmentPresence(), 1_000L, null);
        var lastGood = registry.snapshot(1_100L);
        block.set(true);
        CompletableFuture<Void> mutating = CompletableFuture.runAsync(() ->
            registry.register(SERVING_SITE, presence(502, SUBSCRIBER + 1), 1_200L, null));
        try
        {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var stale = assertTimeoutPreemptively(Duration.ofMillis(250), () -> registry.snapshot(1_300L));
            assertTrue(stale.stale());
            assertEquals(lastGood.asOf(), stale.asOf());
            assertEquals(lastGood.assignments(), stale.assignments());
            assertEquals(0, registry.droppedMutationCount());
        }
        finally
        {
            release.countDown();
            mutating.get(1, TimeUnit.SECONDS);
        }
    }

    private static P25RadioPresence assignmentPresence()
    {
        return new P25RadioPresence(subscriber(), WORKING_ID);
    }

    private static P25RadioPresence presence(int workingId, int subscriberId)
    {
        return new P25RadioPresence(new P25SubscriberIdentity(HOME_WACN, HOME_SYSTEM, subscriberId), workingId);
    }

    private static P25SubscriberIdentity subscriber()
    {
        return new P25SubscriberIdentity(HOME_WACN, HOME_SYSTEM, SUBSCRIBER);
    }

    private static MutableIdentifierCollection ordinary(int workingId)
    {
        return new MutableIdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(workingId)));
    }

    private static Identifier<?> enrich(P25WuidAssignmentRegistry registry, P25SiteIdentity site, int workingId,
                                        long timestamp)
    {
        MutableIdentifierCollection identifiers = ordinary(workingId);
        registry.enrich(site, identifiers, timestamp);
        return identifiers.getFromIdentifier();
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await(5, TimeUnit.SECONDS);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static class BlockingMutation
    {
        private final CountDownLatch mEntered = new CountDownLatch(1);
        private final CountDownLatch mRelease = new CountDownLatch(1);
        private final AtomicBoolean mBlock = new AtomicBoolean();
        private final P25WuidAssignmentRegistry mRegistry = new P25WuidAssignmentRegistry(16, 4, () -> {
            if(mBlock.compareAndSet(true, false))
            {
                mEntered.countDown();
                await(mRelease);
            }
        });

        private P25WuidAssignmentRegistry registry()
        {
            return mRegistry;
        }

        private void arm()
        {
            mBlock.set(true);
        }

        private void awaitEntry() throws InterruptedException
        {
            assertTrue(mEntered.await(2, TimeUnit.SECONDS));
        }

        private void release()
        {
            mRelease.countDown();
        }
    }
}
