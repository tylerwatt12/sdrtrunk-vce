/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebEntityNavigationCatalogTest
{
    private static final String CONFIGURATION_ID = "728d2d66-de4e-476b-a696-919f32dd4d12";
    private static final String SITE_ID = "4b75217f-2555-4c38-aafc-5d17bc0faf71";

    @Test
    void retainsTheLastCompleteGenerationWhenARefreshFails()
    {
        WebEntityNavigationCatalog.Snapshot expected = p25Snapshot();
        AtomicInteger calls = new AtomicInteger();
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() -> {
            if(calls.incrementAndGet() == 1)
            {
                return expected;
            }

            throw new IllegalStateException("database unavailable");
        });

        catalog.refreshNow();
        assertSame(expected, catalog.snapshot());
        catalog.refreshNow();
        assertSame(expected, catalog.snapshot());
        assertEquals(1, catalog.successfulRefreshes());
        assertEquals(1, catalog.failedRefreshes());
    }

    @Test
    void refreshRunsOnOneLowPriorityWorkerAndStopsWithTheCatalog() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Thread> initialThread = new AtomicReference<>();
        AtomicReference<Thread> loaderThread = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() -> {
            if(calls.incrementAndGet() == 1)
            {
                initialThread.set(Thread.currentThread());
                return p25Snapshot();
            }

            loaderThread.set(Thread.currentThread());
            entered.countDown();
            try
            {
                release.await(2, TimeUnit.SECONDS);
                return p25Snapshot();
            }
            finally
            {
                completed.countDown();
            }
        }, 1L);

        try
        {
            catalog.start();
            catalog.start();
            assertSame(Thread.currentThread(), initialThread.get(),
                "startup must load the required first catalog before live services can accept calls");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(2, calls.get(), "a blocked periodic refresh must not queue or overlap another refresh");
            assertTrue(loaderThread.get().isDaemon());
            assertTrue(loaderThread.get().getPriority() < Thread.NORM_PRIORITY);
            catalog.stop();
            release.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(p25Snapshot().channel(CONFIGURATION_ID, null).entityRef(),
                catalog.snapshot().channel(CONFIGURATION_ID, null).entityRef());
            assertEquals(1, catalog.successfulRefreshes(),
                "a refresh completing after stop must not publish a new generation");
        }
        finally
        {
            release.countDown();
            catalog.stop();
        }
    }

    @Test
    void refusesToStartWithoutACompleteInitialCatalog()
    {
        WebEntityNavigationCatalog catalog = new WebEntityNavigationCatalog(() -> {
            throw new IllegalStateException("database unavailable");
        });

        assertThrows(IllegalStateException.class, catalog::start);
        assertEquals(0, catalog.successfulRefreshes());
        assertEquals(1, catalog.failedRefreshes());
    }

    @Test
    void resolvesOnlyProtocolValidIdentitiesUnderAnExactLearnedScope()
    {
        WebEntityNavigationCatalog.Channel channel = p25Snapshot().channel(CONFIGURATION_ID, null);
        assertEquals(Map.of("kind", "radio", "scope", "p25:BEE00:49F:alias-list:1", "id", 1201),
            channel.identity(new ChannelActivitySnapshot.MatcherReference("radio", "p25", "phase_1", 1201))
                .toMap());
        assertEquals(Map.of("kind", "patch_group", "scope", "p25:BEE00:49F:alias-list:1", "id", 4400),
            channel.identity(new ChannelActivitySnapshot.MatcherReference("patch_group", "p25", "phase_1", 4400))
                .toMap());
        assertNull(channel.identity(
            new ChannelActivitySnapshot.MatcherReference("talkgroup", "dmr", null, 4400)));
        assertNull(channel.identity(
            new ChannelActivitySnapshot.MatcherReference("talkgroup", "p25", null, 0xFFFF)));
    }

    @Test
    void refusesDuplicateCanonicalChannelIdentities()
    {
        WebEntityNavigationCatalog.Channel channel = p25Snapshot().channel(CONFIGURATION_ID, null);
        assertThrows(IllegalArgumentException.class,
            () -> WebEntityNavigationCatalog.Snapshot.of(List.of(channel, channel)));
    }

    @Test
    void neverUsesConventionalCorrelationMetadataOrFallbackIdentityForNavigation()
    {
        String conventionalId = "828d2d66-de4e-476b-a696-919f32dd4d12";
        String correlationGuid = "a1b2c3d4-e5f6-4789-8abc-def012345678";
        WebEntityNavigationCatalog.Channel conventional = new WebEntityNavigationCatalog.Channel(
            conventionalId, correlationGuid, WebEntityRef.conventional(conventionalId), null, 3, 0);
        WebEntityNavigationCatalog.Snapshot snapshot = WebEntityNavigationCatalog.Snapshot.of(
            List.of(p25Snapshot().channel(CONFIGURATION_ID, null), conventional));

        assertSame(conventional, snapshot.channel(conventionalId, null));
        assertNull(snapshot.channel(null, correlationGuid),
            "conventional radres_guid is upload correlation metadata, not a site identity");
        assertNull(snapshot.channel("00000000-0000-0000-0000-000000000999", SITE_ID),
            "a supplied but unresolved configuration identity must not fall back to GUID");
    }

    private static WebEntityNavigationCatalog.Snapshot p25Snapshot()
    {
        return WebEntityNavigationCatalog.Snapshot.of(List.of(new WebEntityNavigationCatalog.Channel(
            CONFIGURATION_ID, SITE_ID, WebEntityRef.site(SITE_ID),
            WebEntityRef.system("p25:BEE00:49F:alias-list:1"), 1, 0)));
    }
}
