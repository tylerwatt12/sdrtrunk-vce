/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.metadata.site;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
