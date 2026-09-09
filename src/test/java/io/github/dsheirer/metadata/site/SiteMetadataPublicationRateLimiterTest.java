/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.metadata.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SiteMetadataPublicationRateLimiterTest
{
    @Test
    void enforcesHardMonotonicInterval()
    {
        AtomicLong clock = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(1_000));
        SiteMetadataPublicationRateLimiter limiter = new SiteMetadataPublicationRateLimiter(5_000, clock::get);

        assertTrue(limiter.tryAcquire());
        clock.set(TimeUnit.MILLISECONDS.toNanos(5_999));
        assertFalse(limiter.tryAcquire());
        clock.set(TimeUnit.MILLISECONDS.toNanos(6_000));
        assertTrue(limiter.tryAcquire());
        clock.set(TimeUnit.MILLISECONDS.toNanos(6_001));
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void aRewoundClockCannotReopenTheWindow()
    {
        AtomicLong clock = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(10_000));
        SiteMetadataPublicationRateLimiter limiter = new SiteMetadataPublicationRateLimiter(5_000, clock::get);

        assertTrue(limiter.tryAcquire());
        clock.set(TimeUnit.MILLISECONDS.toNanos(9_999));
        assertFalse(limiter.tryAcquire());
        clock.set(TimeUnit.MILLISECONDS.toNanos(14_999));
        assertFalse(limiter.tryAcquire());
        clock.set(TimeUnit.MILLISECONDS.toNanos(15_000));
        assertTrue(limiter.tryAcquire());
    }

    @Test
    void concurrentPublishersUseOneNonblockingAcquisitionPerWindow()
    {
        AtomicLong clock = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(1_000));
        SiteMetadataPublicationRateLimiter limiter = new SiteMetadataPublicationRateLimiter(5_000, clock::get);
        ExecutorService publishers = Executors.newFixedThreadPool(16);

        try
        {
            assertEquals(1, concurrentAcquisitions(limiter, publishers));
            clock.set(TimeUnit.MILLISECONDS.toNanos(6_000));
            assertEquals(1, concurrentAcquisitions(limiter, publishers));
        }
        finally
        {
            publishers.shutdownNow();
        }
    }

    private static int concurrentAcquisitions(SiteMetadataPublicationRateLimiter limiter,
                                              ExecutorService publishers)
    {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        for(int index = 0; index < 64; index++)
        {
            attempts.add(publishers.submit(() -> {
                start.await();
                return limiter.tryAcquire();
            }));
        }

        return assertTimeout(Duration.ofSeconds(2), () -> {
            start.countDown();
            int accepted = 0;

            for(Future<Boolean> attempt : attempts)
            {
                if(attempt.get(1, TimeUnit.SECONDS))
                {
                    accepted++;
                }
            }

            return accepted;
        });
    }
}
