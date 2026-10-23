/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One read shared by visitors, with a bounded wait when no usable data is cached. */
final class StatsWebSnapshot<T> implements Supplier<T>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsWebSnapshot.class);
    private static final long FAILURE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long STALE_GRACE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long REFRESH_WAIT_SECONDS = 2;
    private final Supplier<T> mRead;
    private final LongSupplier mClock;
    private final long mLifetimeNanos;
    private final ReentrantLock mRefresh = new ReentrantLock();
    private volatile Snapshot<T> mSnapshot;
    private volatile Long mFailedAtNanos;

    StatsWebSnapshot(Duration lifetime, Supplier<T> read)
    {
        this(lifetime, read, System::nanoTime);
    }

    StatsWebSnapshot(Duration lifetime, Supplier<T> read, LongSupplier clock)
    {
        mLifetimeNanos = lifetime.toNanos();
        if(mLifetimeNanos <= 0) throw new IllegalArgumentException("Snapshot lifetime must be positive");
        mRead = Objects.requireNonNull(read);
        mClock = Objects.requireNonNull(clock);
    }

    @Override
    public T get()
    {
        Snapshot<T> current = mSnapshot;
        long now = mClock.getAsLong();
        if(fresh(current, now)) return current.value();
        if(coolingDown(now)) return staleOrUnavailable(current, now);
        if(!mRefresh.tryLock())
        {
            current = mSnapshot;
            now = mClock.getAsLong();
            if(usable(current, now)) return current.value();
            try
            {
                if(!mRefresh.tryLock(REFRESH_WAIT_SECONDS, TimeUnit.SECONDS))
                    throw unavailable();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw unavailable();
            }
        }
        try
        {
            current = mSnapshot;
            now = mClock.getAsLong();
            if(fresh(current, now)) return current.value();
            if(coolingDown(now)) return staleOrUnavailable(current, now);
            try
            {
                current = new Snapshot<>(Objects.requireNonNull(mRead.get()), mClock.getAsLong());
                mSnapshot = current;
                mFailedAtNanos = null;
                return current.value();
            }
            catch(RuntimeException exception)
            {
                now = mClock.getAsLong();
                mFailedAtNanos = now;
                LOGGER.warn("Unable to refresh shared web data; retries are temporarily limited", exception);
                return staleOrUnavailable(current, now);
            }
        }
        finally
        {
            mRefresh.unlock();
        }
    }

    private boolean fresh(Snapshot<T> snapshot, long now)
    {
        return snapshot != null && now - snapshot.createdAt() < mLifetimeNanos;
    }

    private boolean coolingDown(long now)
    {
        Long failedAt = mFailedAtNanos;
        return failedAt != null && now - failedAt < FAILURE_RETRY_NANOS;
    }

    private T staleOrUnavailable(Snapshot<T> snapshot, long now)
    {
        if(usable(snapshot, now)) return snapshot.value();
        throw unavailable();
    }

    private boolean usable(Snapshot<T> snapshot, long now)
    {
        return snapshot != null && now - snapshot.createdAt() - mLifetimeNanos <= STALE_GRACE_NANOS;
    }

    private static StatsApiException unavailable()
    {
        return new StatsApiException(503, "snapshot_unavailable", "Page data is temporarily unavailable. Try again shortly.");
    }

    private record Snapshot<T>(T value, long createdAt) {}
}
