/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Small global admission limit applied before expensive password verification.
 */
final class AccountLoginAdmissionLimiter
{
    private final Configuration mConfiguration;
    private final Clock mClock;
    private final ReentrantLock mLock = new ReentrantLock();
    private final ArrayDeque<Long> mAdmittedAtEpochMillis;

    AccountLoginAdmissionLimiter(Configuration configuration, Clock clock)
    {
        mConfiguration = Objects.requireNonNull(configuration,
            "Account login admission configuration cannot be null");
        mClock = Objects.requireNonNull(clock, "Clock cannot be null");
        mAdmittedAtEpochMillis = new ArrayDeque<>(mConfiguration.maximumAttempts());
    }

    Decision tryAcquire()
    {
        mLock.lock();

        try
        {
            long now = Math.max(0, mClock.millis());
            removeExpired(now);

            if(mAdmittedAtEpochMillis.size() >= mConfiguration.maximumAttempts())
            {
                long retryAt = saturatingAdd(mAdmittedAtEpochMillis.getFirst(), mConfiguration.window().toMillis());
                return new Decision(false, Math.max(1, retryAt - now));
            }

            mAdmittedAtEpochMillis.addLast(now);
            return Decision.ALLOWED;
        }
        finally
        {
            mLock.unlock();
        }
    }

    void clear()
    {
        mLock.lock();

        try
        {
            mAdmittedAtEpochMillis.clear();
        }
        finally
        {
            mLock.unlock();
        }
    }

    int size()
    {
        mLock.lock();

        try
        {
            removeExpired(Math.max(0, mClock.millis()));
            return mAdmittedAtEpochMillis.size();
        }
        finally
        {
            mLock.unlock();
        }
    }

    private void removeExpired(long now)
    {
        long windowMillis = mConfiguration.window().toMillis();

        while(!mAdmittedAtEpochMillis.isEmpty() &&
            now >= saturatingAdd(mAdmittedAtEpochMillis.getFirst(), windowMillis))
        {
            mAdmittedAtEpochMillis.removeFirst();
        }
    }

    private static long saturatingAdd(long value, long increment)
    {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    record Decision(boolean allowed, long retryAfterMillis)
    {
        private static final Decision ALLOWED = new Decision(true, 0);

        Decision
        {
            if(retryAfterMillis < 0 || allowed && retryAfterMillis != 0)
            {
                throw new IllegalArgumentException("Invalid account login admission decision");
            }
        }
    }

    record Configuration(int maximumAttempts, Duration window)
    {
        Configuration
        {
            if(maximumAttempts < 1 || maximumAttempts > 32)
            {
                throw new IllegalArgumentException("Maximum account login attempts must be between 1 and 32");
            }

            Objects.requireNonNull(window, "Account login admission window cannot be null");

            if(window.isZero() || window.isNegative() || window.toMillis() <= 0)
            {
                throw new IllegalArgumentException("Account login admission window must be positive");
            }
        }

        static Configuration defaults()
        {
            return new Configuration(3, Duration.ofSeconds(10));
        }
    }
}
