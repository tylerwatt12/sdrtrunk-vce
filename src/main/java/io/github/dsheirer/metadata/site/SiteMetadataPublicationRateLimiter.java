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

package io.github.dsheirer.metadata.site;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Hard monotonic rate limit for latest-value site metadata.
 */
public class SiteMetadataPublicationRateLimiter
{
    private final long mIntervalNanoseconds;
    private final LongSupplier mMonotonicClock;
    private boolean mAcquired;
    private long mLastAcquiredNanoseconds;

    public SiteMetadataPublicationRateLimiter(long intervalMilliseconds)
    {
        this(intervalMilliseconds, System::nanoTime);
    }

    /**
     * Constructor with an injectable monotonic clock for deterministic tests.
     */
    public SiteMetadataPublicationRateLimiter(long intervalMilliseconds, LongSupplier monotonicClock)
    {
        mIntervalNanoseconds = TimeUnit.MILLISECONDS.toNanos(Math.max(1, intervalMilliseconds));
        mMonotonicClock = monotonicClock != null ? monotonicClock : System::nanoTime;
    }

    /**
     * Claims the current publication window. A decoder reset does not reopen the window.
     */
    public synchronized boolean tryAcquire()
    {
        long now = mMonotonicClock.getAsLong();

        if(!mAcquired || now - mLastAcquiredNanoseconds >= mIntervalNanoseconds)
        {
            mAcquired = true;
            mLastAcquiredNanoseconds = now;
            return true;
        }

        return false;
    }
}
