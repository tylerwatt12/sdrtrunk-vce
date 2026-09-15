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

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Protocol-neutral incumbent/challenger tracker for facts learned from repeated decoded messages.
 *
 * <p>Thread ownership is intentionally left to the caller. Decoder monitors normally protect a group of trackers
 * with their existing synchronized methods or decoder-thread confinement.</p>
 */
public class StableFactTracker<V,K>
{
    private final Function<V,K> mKeyFunction;
    private volatile StableObservation<V,K> mStableObservation = StableObservation.empty();
    private V mCandidateValue;
    private K mCandidateKey;
    private long mCandidateFirstSeenTimestamp;
    private long mCandidateLastSeenTimestamp;
    private int mCandidateObservationCount;

    public enum Result
    {
        NONE,
        PROMOTED,
        REJECTED
    }

    public StableFactTracker(Function<V,K> keyFunction)
    {
        mKeyFunction = Objects.requireNonNull(keyFunction);
    }

    public void reset()
    {
        mStableObservation = StableObservation.empty();
        resetCandidate();
    }

    /**
     * Clears an untrusted challenger while retaining the current trusted value.
     */
    public void resetCandidate()
    {
        mCandidateValue = null;
        mCandidateKey = null;
        mCandidateFirstSeenTimestamp = 0;
        mCandidateLastSeenTimestamp = 0;
        mCandidateObservationCount = 0;
    }

    public V getStableValue()
    {
        return mStableObservation.value();
    }

    /**
     * Atomically published stable value and last-seen timestamp. This lets an observer project an immutable snapshot
     * without acquiring the decoder-owned lock that protects candidate mutation.
     */
    public StableObservation<V,K> getStableObservation()
    {
        return mStableObservation;
    }

    public boolean hasStableValue()
    {
        return mStableObservation.value() != null;
    }

    public boolean isEmpty()
    {
        return mStableObservation.value() == null && mCandidateValue == null;
    }

    public K getCandidateKey()
    {
        return mCandidateKey;
    }

    public V getCandidateValue()
    {
        return mCandidateValue;
    }

    public int getCandidateObservationCount()
    {
        return mCandidateObservationCount;
    }

    /**
     * Promotes the current candidate when another independent evidence source confirms it.
     */
    public Result confirmCandidate(long timestamp, Predicate<V> promotionGuard)
    {
        Objects.requireNonNull(promotionGuard);

        if(mCandidateValue == null)
        {
            return Result.NONE;
        }

        return promoteIfAllowed(mCandidateValue, mCandidateKey, timestamp, promotionGuard);
    }

    /**
     * Replaces the stable value immediately when the observation has already passed its protocol-specific validity
     * and authority checks. No challenger state or time-based confirmation is used.
     */
    public Result observeAuthoritative(V value, long timestamp, Predicate<V> promotionGuard)
    {
        Objects.requireNonNull(promotionGuard);

        if(value == null)
        {
            return Result.NONE;
        }

        K key = mKeyFunction.apply(value);

        if(key == null)
        {
            return Result.NONE;
        }

        StableObservation<V,K> stable = mStableObservation;

        if(stable.value() != null && timestamp < stable.lastSeenTimestamp())
        {
            return Result.NONE;
        }

        return promoteIfAllowed(value, key, timestamp, promotionGuard);
    }

    public Result observe(V value, long timestamp, FactConfirmationPolicy policy, Predicate<V> promotionGuard)
    {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(promotionGuard);
        expireCandidate(timestamp, policy.candidateTtlMilliseconds());

        if(value == null)
        {
            return Result.NONE;
        }

        K key = mKeyFunction.apply(value);

        if(key == null)
        {
            return Result.NONE;
        }

        StableObservation<V,K> stable = mStableObservation;

        if(stable.value() != null && timestamp < stable.lastSeenTimestamp())
        {
            //A stale observation cannot update the incumbent or become a challenger that later rewinds it.
            return Result.NONE;
        }

        if(stable.value() == null && policy.trustInitialValue())
        {
            return promoteIfAllowed(value, key, timestamp, promotionGuard);
        }

        if(Objects.equals(key, stable.key()))
        {
            if(timestamp >= stable.lastSeenTimestamp())
            {
                mStableObservation = new StableObservation<>(value, key, timestamp);

                if(mCandidateValue == null || timestamp >= mCandidateLastSeenTimestamp)
                {
                    resetCandidate();
                }
            }

            return Result.NONE;
        }

        if(!Objects.equals(key, mCandidateKey))
        {
            mCandidateValue = value;
            mCandidateKey = key;
            mCandidateFirstSeenTimestamp = timestamp;
            mCandidateLastSeenTimestamp = timestamp;
            mCandidateObservationCount = 1;
            return Result.NONE;
        }

        if(timestamp <= mCandidateLastSeenTimestamp)
        {
            return Result.NONE;
        }

        mCandidateValue = value;
        mCandidateLastSeenTimestamp = timestamp;
        mCandidateObservationCount++;

        if(mCandidateObservationCount >= policy.requiredObservations() &&
            mCandidateLastSeenTimestamp - mCandidateFirstSeenTimestamp >= policy.minimumSpanMilliseconds())
        {
            return promoteIfAllowed(value, key, timestamp, promotionGuard);
        }

        return Result.NONE;
    }

    public boolean expireCandidate(long timestamp, long expirationMilliseconds)
    {
        if(mCandidateValue != null && timestamp - mCandidateLastSeenTimestamp > expirationMilliseconds)
        {
            resetCandidate();
            return true;
        }

        return false;
    }

    public boolean expireStable(long timestamp, long expirationMilliseconds)
    {
        StableObservation<V,K> stable = mStableObservation;

        if(stable.value() != null && timestamp - stable.lastSeenTimestamp() > expirationMilliseconds)
        {
            mStableObservation = StableObservation.empty();
            return true;
        }

        return false;
    }

    private Result promoteIfAllowed(V value, K key, long timestamp, Predicate<V> promotionGuard)
    {
        if(promotionGuard.test(value))
        {
            mStableObservation = new StableObservation<>(value, key, timestamp);
            resetCandidate();
            return Result.PROMOTED;
        }

        return Result.REJECTED;
    }

    /** Immutable publication unit for stable-fact readers. */
    public record StableObservation<V,K>(V value, K key, long lastSeenTimestamp)
    {
        private static <V,K> StableObservation<V,K> empty()
        {
            return new StableObservation<>(null, null, 0);
        }
    }
}
