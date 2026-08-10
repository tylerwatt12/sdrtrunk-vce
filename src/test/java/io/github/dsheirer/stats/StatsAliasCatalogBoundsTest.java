/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StatsAliasCatalogBoundsTest
{
    @Test
    void enrichmentAdmissionRejectsExcessConcurrencyAndAlwaysReleasesPermits() throws Exception
    {
        StatsAliasCatalog.EnrichmentAdmission admission = new StatsAliasCatalog.EnrichmentAdmission(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try(var executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            Future<String> first = executor.submit(() -> admission.execute(() -> awaitRelease(entered, release)));
            Future<String> second = executor.submit(() -> admission.execute(() -> awaitRelease(entered, release)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            StatsApiException busy = assertThrows(StatsApiException.class,
                () -> admission.execute(() -> "unexpected"));
            assertEquals(429, busy.status());
            assertEquals("alias_enrichment_busy", busy.code());

            release.countDown();
            assertEquals("complete", first.get(5, TimeUnit.SECONDS));
            assertEquals("complete", second.get(5, TimeUnit.SECONDS));
            assertEquals("reused", admission.execute(() -> "reused"));
        }
    }

    private static String awaitRelease(CountDownLatch entered, CountDownLatch release)
    {
        entered.countDown();

        try
        {
            if(!release.await(5, TimeUnit.SECONDS))
            {
                throw new AssertionError("Timed out waiting to release enrichment permit");
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while holding enrichment permit", e);
        }

        return "complete";
    }
}
