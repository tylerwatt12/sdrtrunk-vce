/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

/**
 * Establishes a fixed NAC authority from three consecutive matching observations within a short time window.
 */
public class P25NACAuthority
{
    public static final int NO_NAC = -1;
    static final int REQUIRED_OBSERVATIONS = 3;
    static final long MAX_OBSERVATION_GAP_MILLISECONDS = 1_000L;
    private int mCandidateNAC = NO_NAC;
    private int mCandidateCount;
    private int mNAC = NO_NAC;
    private long mCandidateFirstTimestamp = Long.MIN_VALUE;
    private long mLastTimestamp = Long.MIN_VALUE;
    private int mLastDiscriminator = Integer.MIN_VALUE;

    /**
     * Observes one independently protected physical unit. The discriminator distinguishes simultaneous units that
     * share a timestamp, such as the two P25 Phase 2 timeslots.
     */
    public synchronized Result observe(int nac, long timestamp, int discriminator)
    {
        if(mNAC != NO_NAC)
        {
            return nac == mNAC ? Result.MATCH : Result.REJECTED;
        }

        if(timestamp == mLastTimestamp && discriminator == mLastDiscriminator)
        {
            return Result.DUPLICATE;
        }

        boolean expired = mLastTimestamp != Long.MIN_VALUE &&
            (timestamp < mLastTimestamp || timestamp - mCandidateFirstTimestamp > MAX_OBSERVATION_GAP_MILLISECONDS);

        if(expired || nac != mCandidateNAC)
        {
            mCandidateNAC = nac;
            mCandidateCount = 1;
            mCandidateFirstTimestamp = timestamp;
        }
        else
        {
            mCandidateCount++;
        }

        mLastTimestamp = timestamp;
        mLastDiscriminator = discriminator;

        if(mCandidateCount >= REQUIRED_OBSERVATIONS)
        {
            mNAC = mCandidateNAC;
            return Result.ESTABLISHED;
        }

        return Result.PENDING;
    }

    /**
     * Establishes authority from an upstream unit that has already passed this same confirmation policy.
     */
    public synchronized boolean establishFromValidatedUpstream(int nac)
    {
        if(mNAC == NO_NAC)
        {
            mCandidateNAC = nac;
            mCandidateCount = REQUIRED_OBSERVATIONS;
            mNAC = nac;
        }

        return mNAC == nac;
    }

    public synchronized int getNAC()
    {
        return mNAC;
    }

    public synchronized void reset()
    {
        mCandidateNAC = NO_NAC;
        mCandidateCount = 0;
        mNAC = NO_NAC;
        mCandidateFirstTimestamp = Long.MIN_VALUE;
        mLastTimestamp = Long.MIN_VALUE;
        mLastDiscriminator = Integer.MIN_VALUE;
    }

    public enum Result
    {
        PENDING,
        DUPLICATE,
        ESTABLISHED,
        MATCH,
        REJECTED
    }
}
