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

package io.github.dsheirer.module.decode.p25.telemetry;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared hysteresis tracker for P25 facts learned from over-the-air messages.
 */
class P25StableFactTracker<T>
{
    private final Function<T,String> mKeyFunction;
    private T mStableValue;
    private String mStableKey;
    private long mStableLastSeenTimestamp;
    private T mCandidateValue;
    private String mCandidateKey;
    private long mFirstSeenTimestamp;
    private long mLastSeenTimestamp;
    private int mObservationCount;

    enum Result
    {
        NONE,
        PROMOTED,
        REJECTED
    }

    P25StableFactTracker(Function<T,String> keyFunction)
    {
        mKeyFunction = keyFunction;
    }

    void reset()
    {
        mStableValue = null;
        mStableKey = null;
        mStableLastSeenTimestamp = 0;
        clearCandidate();
    }

    /**
     * Clears an untrusted candidate while retaining the last promoted value.
     */
    void resetCandidate()
    {
        clearCandidate();
    }

    T getStableValue()
    {
        return mStableValue;
    }

    String getStableKey()
    {
        return mStableKey;
    }

    String getCandidateKey()
    {
        return mCandidateKey;
    }

    int getCandidateObservationCount()
    {
        return mObservationCount;
    }

    long getCandidateAgeMilliseconds(long timestamp)
    {
        return mCandidateValue != null ? Math.max(0, timestamp - mFirstSeenTimestamp) : 0;
    }

    long getCandidateLastSeenAgeMilliseconds(long timestamp)
    {
        return mCandidateValue != null ? Math.max(0, timestamp - mLastSeenTimestamp) : 0;
    }

    Result observe(T value, long timestamp, int minimumObservations, long minimumAgeMilliseconds,
                   long candidateExpirationMilliseconds, boolean promoteFirstValue, Predicate<T> promotionGuard)
    {
        expireCandidate(timestamp, candidateExpirationMilliseconds);

        if(value == null)
        {
            return Result.NONE;
        }

        String key = mKeyFunction.apply(value);

        if(key == null)
        {
            return Result.NONE;
        }

        if(mStableValue == null && promoteFirstValue)
        {
            if(promotionGuard.test(value))
            {
                promote(value, key, timestamp);
                return Result.PROMOTED;
            }

            return Result.REJECTED;
        }

        if(Objects.equals(key, mStableKey))
        {
            mStableValue = value;
            mStableLastSeenTimestamp = timestamp;
            clearCandidate();
            return Result.NONE;
        }

        if(!Objects.equals(key, mCandidateKey))
        {
            mCandidateValue = value;
            mCandidateKey = key;
            mFirstSeenTimestamp = timestamp;
            mLastSeenTimestamp = timestamp;
            mObservationCount = 1;
            return Result.NONE;
        }

        if(timestamp <= mLastSeenTimestamp)
        {
            return Result.NONE;
        }

        mLastSeenTimestamp = timestamp;
        mObservationCount++;

        if(mObservationCount >= minimumObservations &&
            mLastSeenTimestamp - mFirstSeenTimestamp >= minimumAgeMilliseconds)
        {
            if(promotionGuard.test(value))
            {
                promote(value, key, timestamp);
                return Result.PROMOTED;
            }

            return Result.REJECTED;
        }

        return Result.NONE;
    }

    T expireCandidate(long timestamp, long expirationMilliseconds)
    {
        if(mCandidateValue != null && timestamp - mLastSeenTimestamp > expirationMilliseconds)
        {
            T expired = mCandidateValue;
            clearCandidate();
            return expired;
        }

        return null;
    }

    boolean expireStable(long timestamp, long expirationMilliseconds)
    {
        if(mStableValue != null && timestamp - mStableLastSeenTimestamp > expirationMilliseconds)
        {
            mStableValue = null;
            mStableKey = null;
            mStableLastSeenTimestamp = 0;
            return true;
        }

        return false;
    }

    private void promote(T value, String key, long timestamp)
    {
        mStableValue = value;
        mStableKey = key;
        mStableLastSeenTimestamp = timestamp;
        clearCandidate();
    }

    private void clearCandidate()
    {
        mCandidateValue = null;
        mCandidateKey = null;
        mFirstSeenTimestamp = 0;
        mLastSeenTimestamp = 0;
        mObservationCount = 0;
    }
}
