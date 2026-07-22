/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryFeatureAccessPolicyTest
{
    @Test
    void featureIdentifiersAreUniqueAndRoundTrip()
    {
        Set<String> identifiers = new HashSet<>();

        for(WebFeature feature: WebFeature.values())
        {
            assertTrue(identifiers.add(feature.getId()));
            assertEquals(feature, WebFeature.fromId(feature.getId()));
        }

        assertThrows(IllegalArgumentException.class, () -> WebFeature.fromId("unknown"));
    }

    @Test
    void currentAndNewProfileDefaultsAreExplicit()
    {
        InMemoryFeatureAccessPolicy current = InMemoryFeatureAccessPolicy.currentProfileDefaults();
        assertEquals(FeatureAccessMode.PUBLIC, current.getMode(WebFeature.STATUS_STATISTICS));
        assertEquals(FeatureAccessMode.PUBLIC, current.getMode(WebFeature.CALL_AUDIO));
        assertEquals(FeatureAccessMode.ADMIN_ONLY, current.getMode(WebFeature.WIDEBAND_SIGNAL));
        assertEquals(FeatureAccessMode.ADMIN_ONLY, current.getMode(WebFeature.SELECTED_CHANNEL_SIGNAL));
        assertEquals(FeatureAccessMode.ADMIN_ONLY, current.getMode(WebFeature.EVENTS));
        assertEquals(FeatureAccessMode.ADMIN_ONLY, current.getMode(WebFeature.MESSAGES));

        InMemoryFeatureAccessPolicy newProfile = InMemoryFeatureAccessPolicy.newProfileDefaults();

        for(WebFeature feature: WebFeature.values())
        {
            assertEquals(FeatureAccessMode.ADMIN_ONLY, newProfile.getMode(feature));
        }
    }

    @Test
    void allSubjectsAndTransportsUseTheSameGatewayRules()
    {
        for(FeatureAccessMode configuredMode: FeatureAccessMode.values())
        {
            EnumMap<WebFeature,FeatureAccessMode> modes = new EnumMap<>(WebFeature.class);

            for(WebFeature feature: WebFeature.values())
            {
                modes.put(feature, isPermanentAdminFeature(feature) ? FeatureAccessMode.ADMIN_ONLY :
                    configuredMode);
            }

            InMemoryFeatureAccessPolicy policy = InMemoryFeatureAccessPolicy.create(modes);

            for(WebFeature feature: WebFeature.values())
            {
                for(WebTransport transport: WebTransport.values())
                {
                    FeatureAccessDecision anonymous = policy.authorize(feature, AuthorizationSubject.ANONYMOUS,
                        transport);
                    FeatureAccessDecision admin = policy.authorize(feature, AuthorizationSubject.AUTHENTICATED_ADMIN,
                        transport);
                    boolean publicFeature = configuredMode == FeatureAccessMode.PUBLIC &&
                        !isPermanentAdminFeature(feature);

                    assertEquals(publicFeature, anonymous.isAllowed());
                    assertEquals(publicFeature ? FeatureAccessDecision.Outcome.ALLOWED :
                        FeatureAccessDecision.Outcome.AUTHENTICATION_REQUIRED, anonymous.outcome());
                    assertTrue(admin.isAllowed());
                    assertEquals(FeatureAccessDecision.Outcome.ALLOWED, admin.outcome());
                    assertEquals(transport, anonymous.request().transport());
                    assertEquals(policy.getRevision(), anonymous.policyRevision());
                }
            }
        }
    }

    @Test
    void signalDiagnosticsCannotBeMadePublic()
    {
        InMemoryFeatureAccessPolicy policy = InMemoryFeatureAccessPolicy.currentProfileDefaults();
        assertThrows(IllegalArgumentException.class,
            () -> policy.setMode(WebFeature.WIDEBAND_SIGNAL, FeatureAccessMode.PUBLIC));
        assertThrows(IllegalArgumentException.class,
            () -> policy.setMode(WebFeature.SELECTED_CHANNEL_SIGNAL, FeatureAccessMode.PUBLIC));
        assertEquals(FeatureAccessMode.ADMIN_ONLY, policy.getMode(WebFeature.WIDEBAND_SIGNAL));
        assertEquals(FeatureAccessMode.ADMIN_ONLY, policy.getMode(WebFeature.SELECTED_CHANNEL_SIGNAL));
        assertEquals(0, policy.getRevision());

        EnumMap<WebFeature,FeatureAccessMode> invalid = new EnumMap<>(WebFeature.class);

        for(WebFeature feature: WebFeature.values())
        {
            invalid.put(feature, FeatureAccessMode.ADMIN_ONLY);
        }

        invalid.put(WebFeature.WIDEBAND_SIGNAL, FeatureAccessMode.PUBLIC);
        assertThrows(IllegalArgumentException.class, () -> InMemoryFeatureAccessPolicy.create(invalid));

        invalid.put(WebFeature.WIDEBAND_SIGNAL, FeatureAccessMode.ADMIN_ONLY);
        invalid.put(WebFeature.SELECTED_CHANNEL_SIGNAL, FeatureAccessMode.PUBLIC);
        assertThrows(IllegalArgumentException.class, () -> InMemoryFeatureAccessPolicy.create(invalid));
    }

    @Test
    void publicToAdminOnlyChangeAdvancesRevisionAndSignalsRevocation()
    {
        InMemoryFeatureAccessPolicy policy = InMemoryFeatureAccessPolicy.currentProfileDefaults();
        List<FeaturePolicyChange> changes = new ArrayList<>();

        try(InMemoryFeatureAccessPolicy.Registration registration = policy.addListener(changes::add))
        {
            FeaturePolicyChange revoked = policy.setMode(WebFeature.STATUS_STATISTICS,
                FeatureAccessMode.ADMIN_ONLY).orElseThrow();
            assertEquals(0, revoked.previousRevision());
            assertEquals(1, revoked.revision());
            assertTrue(revoked.revokesAnonymousAccess());
            assertFalse(policy.authorize(WebFeature.STATUS_STATISTICS, AuthorizationSubject.ANONYMOUS,
                WebTransport.WEBSOCKET).isAllowed());
            assertTrue(policy.authorize(WebFeature.STATUS_STATISTICS, AuthorizationSubject.AUTHENTICATED_ADMIN,
                WebTransport.WEBSOCKET).isAllowed());

            assertTrue(policy.setMode(WebFeature.STATUS_STATISTICS, FeatureAccessMode.ADMIN_ONLY).isEmpty());
            assertEquals(1, policy.getRevision());
            assertEquals(1, changes.size());

            FeaturePolicyChange restored = policy.setMode(WebFeature.STATUS_STATISTICS,
                FeatureAccessMode.PUBLIC).orElseThrow();
            assertEquals(2, restored.revision());
            assertFalse(restored.revokesAnonymousAccess());
            assertEquals(List.of(revoked, restored), changes);
        }

        assertEquals(0, policy.getListenerCount());
        policy.setMode(WebFeature.WIDEBAND_SIGNAL, FeatureAccessMode.ADMIN_ONLY);
        assertEquals(2, changes.size());
    }

    @Test
    void policyMapAndListenerRegistrationsAreBounded()
    {
        EnumMap<WebFeature,FeatureAccessMode> incomplete = new EnumMap<>(WebFeature.class);
        incomplete.put(WebFeature.STATUS_STATISTICS, FeatureAccessMode.PUBLIC);
        assertThrows(IllegalArgumentException.class, () -> InMemoryFeatureAccessPolicy.create(incomplete));

        EnumMap<WebFeature,FeatureAccessMode> complete = new EnumMap<>(WebFeature.class);

        for(WebFeature feature: WebFeature.values())
        {
            complete.put(feature, isPermanentAdminFeature(feature) ? FeatureAccessMode.ADMIN_ONLY :
                FeatureAccessMode.PUBLIC);
        }

        InMemoryFeatureAccessPolicy policy = InMemoryFeatureAccessPolicy.create(complete, 1);
        InMemoryFeatureAccessPolicy.Registration registration = policy.addListener(change -> {});
        assertThrows(IllegalStateException.class, () -> policy.addListener(change -> {}));
        registration.close();
        assertEquals(WebFeature.values().length, policy.snapshot().modes().size());
        assertThrows(UnsupportedOperationException.class,
            () -> policy.snapshot().modes().put(WebFeature.EVENTS, FeatureAccessMode.ADMIN_ONLY));
    }

    @Test
    void concurrentReadsAndWritesPublishOrderedCompleteSnapshots() throws Exception
    {
        InMemoryFeatureAccessPolicy policy = InMemoryFeatureAccessPolicy.currentProfileDefaults();
        List<FeaturePolicyChange> changes = new ArrayList<>();
        int writerCount = 6;
        int iterations = 300;
        ExecutorService executor = Executors.newFixedThreadPool(writerCount + 2);
        CountDownLatch start = new CountDownLatch(1);

        try(InMemoryFeatureAccessPolicy.Registration registration = policy.addListener(changes::add))
        {
            List<Future<?>> futures = new ArrayList<>();

            for(int writer = 0; writer < writerCount; writer++)
            {
                int writerIndex = writer;
                futures.add(executor.submit(() -> {
                    start.await();

                    for(int iteration = 0; iteration < iterations; iteration++)
                    {
                        WebFeature feature = WebFeature.values()[
                            (writerIndex + iteration) % WebFeature.values().length];
                        FeatureAccessMode mode = isPermanentAdminFeature(feature) ?
                            FeatureAccessMode.ADMIN_ONLY : ((writerIndex + iteration) % 2 == 0 ?
                            FeatureAccessMode.PUBLIC : FeatureAccessMode.ADMIN_ONLY);
                        policy.setMode(feature, mode);
                    }

                    return null;
                }));
            }

            for(int reader = 0; reader < 2; reader++)
            {
                futures.add(executor.submit(() -> {
                    start.await();

                    for(int iteration = 0; iteration < writerCount * iterations; iteration++)
                    {
                        for(WebFeature feature: WebFeature.values())
                        {
                            FeatureAccessDecision decision = policy.authorize(feature, AuthorizationSubject.ANONYMOUS,
                                WebTransport.WEBSOCKET);
                            assertEquals(decision.configuredMode() == FeatureAccessMode.PUBLIC, decision.isAllowed());
                        }

                        assertEquals(WebFeature.values().length, policy.snapshot().modes().size());
                    }

                    return null;
                }));
            }

            start.countDown();

            for(Future<?> future: futures)
            {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        finally
        {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(policy.getRevision(), changes.size());

        for(int index = 0; index < changes.size(); index++)
        {
            FeaturePolicyChange change = changes.get(index);
            assertEquals(index, change.previousRevision());
            assertEquals(index + 1, change.revision());
        }
    }

    private static boolean isPermanentAdminFeature(WebFeature feature)
    {
        return feature == WebFeature.WIDEBAND_SIGNAL || feature == WebFeature.SELECTED_CHANNEL_SIGNAL;
    }
}
