/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

/**
 * Deterministic sample-count selector for the P25 Phase 1 C4FM and LSM decoders. Initial acquisition evaluates both
 * decoders for the same fixed duration and then retains the selected modulation for this decoder's lifetime. This
 * class has no timers, locks, queues or allocation in its callback methods.
 */
class P25P1AutoSelector
{
    private static final double TRIAL_SECONDS = 0.5;
    private static final int ASSUMED_FRAME_BITS = 196;
    private static final int SCORE_SMOOTHING_FRAMES = 4;

    private Phase mPhase;
    private Modulation mPreferred;
    private Modulation mActive;
    private long mTrialSamples;
    private long mPhaseSamples;
    private int mFirstValidFrames;
    private int mFirstFailures;
    private int mActiveValidFrames;
    private int mActiveFailures;

    P25P1AutoSelector(double sampleRate, Modulation preferred)
    {
        setSampleRate(sampleRate);
        reset(preferred);
    }

    void setSampleRate(double sampleRate)
    {
        if(sampleRate <= 0)
        {
            throw new IllegalArgumentException("Sample rate must be positive");
        }

        mTrialSamples = Math.max(1, Math.round(sampleRate * TRIAL_SECONDS));
    }

    void reset(Modulation preferred)
    {
        if(isLocked())
        {
            return;
        }

        mPreferred = fixed(preferred);
        mActive = mPreferred;
        mPhase = Phase.ACQUIRE_FIRST;
        mFirstValidFrames = 0;
        mFirstFailures = 0;
        clearTrial();
    }

    Modulation getActive()
    {
        return mActive;
    }

    boolean isLocked()
    {
        return mPhase == Phase.LOCKED;
    }

    boolean receiveFrame(Modulation modulation, boolean valid)
    {
        if(modulation != mActive)
        {
            return false;
        }

        if(mPhase != Phase.LOCKED)
        {
            if(valid)
            {
                mActiveValidFrames++;
            }
            else
            {
                mActiveFailures++;
            }
        }

        return mPhase == Phase.LOCKED;
    }

    boolean receiveSyncLoss(Modulation modulation, int bitsProcessed)
    {
        if(modulation != mActive)
        {
            return false;
        }

        if(mPhase != Phase.LOCKED && bitsProcessed > 0)
        {
            long missedFrames = ((long)bitsProcessed + ASSUMED_FRAME_BITS - 1) / ASSUMED_FRAME_BITS;
            mActiveFailures += (int)Math.min(Integer.MAX_VALUE - mActiveFailures, missedFrames);
        }

        return mPhase == Phase.LOCKED;
    }

    /**
     * Advances the selector after one sample buffer was processed.
     *
     * @return a different decoder to activate, or null when the current decoder remains active
     */
    Modulation receiveSamples(int sampleCount)
    {
        if(sampleCount <= 0)
        {
            return null;
        }

        mPhaseSamples += sampleCount;

        if(mPhase == Phase.LOCKED)
        {
            return null;
        }

        if(mPhaseSamples < mTrialSamples)
        {
            return null;
        }

        return switch(mPhase)
        {
            case ACQUIRE_FIRST -> beginSecondTrial();
            case ACQUIRE_SECOND -> finishAcquisition();
            case LOCKED -> null;
        };
    }

    private Modulation beginSecondTrial()
    {
        mFirstValidFrames = mActiveValidFrames;
        mFirstFailures = mActiveFailures;
        return beginTrial(alternate(mActive), Phase.ACQUIRE_SECOND);
    }

    private Modulation finishAcquisition()
    {
        if(mFirstValidFrames == 0 && mActiveValidFrames == 0)
        {
            return beginTrial(mPreferred, Phase.ACQUIRE_FIRST);
        }

        Modulation winner = score(mActiveValidFrames, mActiveFailures) >
            score(mFirstValidFrames, mFirstFailures) ? mActive : alternate(mActive);
        return lock(winner);
    }

    private static int score(int validFrames, int failures)
    {
        return validFrames * 1_000 / Math.max(1, validFrames + failures + SCORE_SMOOTHING_FRAMES);
    }

    private Modulation beginTrial(Modulation modulation, Phase phase)
    {
        Modulation previous = mActive;
        mActive = fixed(modulation);
        mPhase = phase;
        clearTrial();
        return mActive != previous ? mActive : null;
    }

    private Modulation lock(Modulation modulation)
    {
        Modulation previous = mActive;
        mActive = fixed(modulation);
        mPhase = Phase.LOCKED;
        clearTrial();
        return mActive != previous ? mActive : null;
    }

    private void clearTrial()
    {
        mPhaseSamples = 0;
        mActiveValidFrames = 0;
        mActiveFailures = 0;
    }

    private static Modulation alternate(Modulation modulation)
    {
        return fixed(modulation) == Modulation.CQPSK ? Modulation.C4FM : Modulation.CQPSK;
    }

    private static Modulation fixed(Modulation modulation)
    {
        return modulation == Modulation.CQPSK ? Modulation.CQPSK : Modulation.C4FM;
    }

    private enum Phase
    {
        ACQUIRE_FIRST,
        ACQUIRE_SECOND,
        LOCKED
    }
}
