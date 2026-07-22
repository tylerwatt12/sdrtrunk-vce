/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, in-memory per-source login failure throttle.  It stores no durable access history.
 */
final class LoginThrottle
{
    private final Configuration mConfiguration;
    private final Clock mClock;
    private final ReentrantLock mLock = new ReentrantLock();
    private final Map<String,Entry> mEntries = new HashMap<>();

    LoginThrottle(Configuration configuration, Clock clock)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Login throttle configuration cannot be null");
        mClock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    Decision check(String sourceKey)
    {
        requireKey(sourceKey);
        mLock.lock();

        try
        {
            long now = now();
            removeExpired(now);
            Entry entry = mEntries.get(sourceKey);

            if(entry != null && entry.blockedUntilEpochMillis > now)
            {
                return new Decision(false, entry.blockedUntilEpochMillis - now);
            }

            if(entry == null && mEntries.size() >= mConfiguration.maximumTrackedSources())
            {
                return new Decision(false, mConfiguration.failureWindow().toMillis());
            }

            return Decision.ALLOWED;
        }
        finally
        {
            mLock.unlock();
        }
    }

    void recordFailure(String sourceKey)
    {
        requireKey(sourceKey);
        mLock.lock();

        try
        {
            long now = now();
            removeExpired(now);
            Entry entry = mEntries.get(sourceKey);

            if(entry == null)
            {
                if(mEntries.size() >= mConfiguration.maximumTrackedSources())
                {
                    return;
                }

                entry = new Entry(now);
                mEntries.put(sourceKey, entry);
            }
            else if(now - entry.windowStartedAtEpochMillis >= mConfiguration.failureWindow().toMillis())
            {
                entry.windowStartedAtEpochMillis = now;
                entry.failureCount = 0;
                entry.blockedUntilEpochMillis = 0;
            }

            entry.failureCount++;

            if(entry.failureCount >= mConfiguration.maximumFailures())
            {
                entry.blockedUntilEpochMillis = saturatingAdd(now, mConfiguration.lockout().toMillis());
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    void recordSuccess(String sourceKey)
    {
        if(sourceKey == null)
        {
            return;
        }

        mLock.lock();

        try
        {
            mEntries.remove(sourceKey);
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
            mEntries.clear();
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
            removeExpired(now());
            return mEntries.size();
        }
        finally
        {
            mLock.unlock();
        }
    }

    private void removeExpired(long now)
    {
        Iterator<Entry> iterator = mEntries.values().iterator();

        while(iterator.hasNext())
        {
            Entry entry = iterator.next();
            long keepUntil = Math.max(saturatingAdd(entry.windowStartedAtEpochMillis,
                mConfiguration.failureWindow().toMillis()), entry.blockedUntilEpochMillis);

            if(now >= keepUntil)
            {
                iterator.remove();
            }
        }
    }

    private long now()
    {
        return Math.max(0, mClock.millis());
    }

    private static void requireKey(String sourceKey)
    {
        if(sourceKey == null || sourceKey.isBlank() || sourceKey.length() > 128)
        {
            throw new IllegalArgumentException("Login throttle source key is invalid");
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
            if(retryAfterMillis < 0)
            {
                throw new IllegalArgumentException("Login retry delay cannot be negative");
            }
        }
    }

    record Configuration(int maximumTrackedSources, int maximumFailures, Duration failureWindow, Duration lockout)
    {
        Configuration
        {
            if(maximumTrackedSources < 1 || maximumTrackedSources > 4096)
            {
                throw new IllegalArgumentException("Maximum tracked login sources is outside safe bounds");
            }

            if(maximumFailures < 1 || maximumFailures > 100)
            {
                throw new IllegalArgumentException("Maximum login failures is outside safe bounds");
            }

            requirePositive(failureWindow, "Login failure window");
            requirePositive(lockout, "Login lockout");
        }

        static Configuration defaults()
        {
            return new Configuration(256, 5, Duration.ofMinutes(5), Duration.ofMinutes(5));
        }

        private static void requirePositive(Duration duration, String label)
        {
            Objects.requireNonNull(duration, label + " cannot be null");

            if(duration.isZero() || duration.isNegative() || duration.toMillis() <= 0)
            {
                throw new IllegalArgumentException(label + " must be positive");
            }
        }
    }

    private static final class Entry
    {
        private long windowStartedAtEpochMillis;
        private int failureCount;
        private long blockedUntilEpochMillis;

        private Entry(long windowStartedAtEpochMillis)
        {
            this.windowStartedAtEpochMillis = windowStartedAtEpochMillis;
        }
    }
}
