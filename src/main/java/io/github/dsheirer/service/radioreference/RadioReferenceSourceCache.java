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
package io.github.dsheirer.service.radioreference;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * Session-owned cache of complete immutable gateway projections. The gateway supplies immutable values and an
 * aggregate payload weight; neither mutable transport models nor credentials belong in this cache. Each operation
 * and ID must always identify the same value type.
 *
 * <p>The first caller loads inline on its already admitted directory worker. Other callers wait interruptibly on
 * that load, without another executor or a lock held across upstream work. Expired values are never served, and a
 * failed refresh leaves the preceding successful value and its original expiry intact.</p>
 */
final class RadioReferenceSourceCache implements AutoCloseable
{
    private final int mMaximumEntries;
    private final long mMaximumWeight;
    private final long mLifetimeNanos;
    private final ToLongFunction<Object> mWeigher;
    private final LongSupplier mNanoTime;
    private final Map<Key,Entry> mValues = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Key,Pending> mPending = new HashMap<>();
    private long mWeight;
    private boolean mClosed;

    RadioReferenceSourceCache(int maximumEntries, long maximumWeight, Duration lifetime,
                              ToLongFunction<Object> weigher, LongSupplier nanoTime)
    {
        if(maximumEntries <= 0 || maximumWeight <= 0)
            throw new IllegalArgumentException("Cache bounds must be positive");
        mMaximumEntries = maximumEntries;
        mMaximumWeight = maximumWeight;
        mLifetimeNanos = Objects.requireNonNull(lifetime).toNanos();
        if(mLifetimeNanos <= 0) throw new IllegalArgumentException("Cache lifetime must be positive");
        mWeigher = Objects.requireNonNull(weigher);
        mNanoTime = Objects.requireNonNull(nanoTime);
    }

    <T> T get(Source source, int id, boolean refresh, Loader<T> loader)
        throws RadioReferenceGatewayException
    {
        Key key = new Key(Objects.requireNonNull(source), id);
        Objects.requireNonNull(loader);
        while(true)
        {
            Pending pending;
            boolean owner;
            synchronized(this)
            {
                requireOpen();
                requireNotInterrupted();
                Entry previous = mValues.get(key);
                if(!refresh && previous != null && mNanoTime.getAsLong() - previous.loadedAtNanos() < mLifetimeNanos)
                    return value(previous.value());
                pending = mPending.get(key);
                owner = pending == null;
                if(owner)
                {
                    if(mPending.size() >= mMaximumEntries) throw failure(RadioReferenceGatewayException.Kind.UNAVAILABLE);
                    pending = new Pending(Thread.currentThread());
                    mPending.put(key, pending);
                }
                else if(pending.owner() == Thread.currentThread())
                {
                    // A recursive load of the same source cannot wait for itself.
                    throw failure(RadioReferenceGatewayException.Kind.UNAVAILABLE);
                }
            }
            if(owner) return load(key, pending, loader);
            try
            {
                return await(pending);
            }
            catch(RadioReferenceGatewayException exception)
            {
                if(exception.kind() != RadioReferenceGatewayException.Kind.INTERRUPTED) throw exception;
                // The owner's shorter directory deadline must not cancel an otherwise active reader. Reacquire
                // inline; this caller remains subject to its own directory deadline and interruption.
                synchronized(this)
                {
                    requireOpen();
                    requireNotInterrupted();
                }
            }
        }
    }

    private <T> T load(Key key, Pending pending, Loader<T> loader) throws RadioReferenceGatewayException
    {
        try
        {
            T loaded = loader.load();
            requireNotInterrupted();
            long weight = loaded == null ? 0 : mWeigher.applyAsLong(loaded);
            if(weight < 0) throw new IllegalArgumentException("Cache weight cannot be negative");
            synchronized(this)
            {
                requireOpen();
                requireNotInterrupted();
                if(mPending.get(key) != pending || pending.result().isDone())
                    throw failure(RadioReferenceGatewayException.Kind.UNAVAILABLE);
                Entry previous = mValues.remove(key);
                if(previous != null) mWeight -= previous.weight();
                // Oversized or absent results still reach current callers, without retaining a partial catalog.
                if(loaded != null && weight <= mMaximumWeight)
                {
                    while(mValues.size() >= mMaximumEntries || mWeight > mMaximumWeight - weight)
                    {
                        var oldest = mValues.entrySet().iterator();
                        Entry removed = oldest.next().getValue();
                        oldest.remove();
                        mWeight -= removed.weight();
                    }
                    mValues.put(key, new Entry(loaded, weight, mNanoTime.getAsLong()));
                    mWeight += weight;
                }
                mPending.remove(key, pending);
                pending.result().complete(loaded);
                return loaded;
            }
        }
        catch(RadioReferenceGatewayException exception)
        {
            throw fail(key, pending, exception.kind());
        }
        catch(RuntimeException exception)
        {
            // Do not retain transport or mapper exceptions, whose messages may contain request details.
            throw fail(key, pending, RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
        catch(Error error)
        {
            fail(key, pending, RadioReferenceGatewayException.Kind.UNAVAILABLE);
            throw error;
        }
    }

    private synchronized RadioReferenceGatewayException fail(Key key, Pending pending,
                                                              RadioReferenceGatewayException.Kind kind)
    {
        RadioReferenceGatewayException exception = failure(mClosed ?
            RadioReferenceGatewayException.Kind.UNAVAILABLE : kind);
        if(mPending.remove(key, pending)) pending.result().completeExceptionally(exception);
        return exception;
    }

    private static <T> T await(Pending pending) throws RadioReferenceGatewayException
    {
        try
        {
            return value(pending.result().get());
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw failure(RadioReferenceGatewayException.Kind.INTERRUPTED);
        }
        catch(ExecutionException exception)
        {
            if(exception.getCause() instanceof RadioReferenceGatewayException failure) throw failure;
            throw failure(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
    }

    private void requireOpen() throws RadioReferenceGatewayException
    {
        if(mClosed) throw failure(RadioReferenceGatewayException.Kind.UNAVAILABLE);
    }

    private static void requireNotInterrupted() throws RadioReferenceGatewayException
    {
        if(Thread.currentThread().isInterrupted()) throw failure(RadioReferenceGatewayException.Kind.INTERRUPTED);
    }

    private static RadioReferenceGatewayException failure(RadioReferenceGatewayException.Kind kind)
    {
        return new RadioReferenceGatewayException(kind);
    }

    @SuppressWarnings("unchecked")
    private static <T> T value(Object value)
    {
        return (T)value;
    }

    @Override public void close()
    {
        synchronized(this)
        {
            if(mClosed) return;
            mClosed = true;
            // Keep ownership stable until interruption is delivered. Otherwise a pooled directory worker could
            // finish this old load, start another session's request, and receive the old session's interrupt.
            mPending.values().stream().map(Pending::owner).distinct().forEach(Thread::interrupt);
            mPending.values().forEach(pending -> pending.result().completeExceptionally(
                failure(RadioReferenceGatewayException.Kind.UNAVAILABLE)));
            mPending.clear();
            mValues.clear();
            mWeight = 0;
        }
    }

    @FunctionalInterface
    interface Loader<T>
    {
        T load() throws RadioReferenceGatewayException;
    }

    enum Source
    {
        COUNTRIES, COUNTRY, STATE, COUNTY, AGENCY, MODES, SYSTEM_TYPES, SYSTEM, SITES, SUBCATEGORY, FLAVOR, VOICE
    }

    private record Key(Source source, int id) {}
    private record Entry(Object value, long weight, long loadedAtNanos) {}
    private record Pending(Thread owner, CompletableFuture<Object> result)
    {
        private Pending(Thread owner) { this(owner, new CompletableFuture<>()); }
    }
}
