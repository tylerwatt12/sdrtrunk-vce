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
 * Deterministic sample-count based selector for the P25 Phase 1 C4FM and LSM decoders. The selector contains no
 * timers, locks, queues or allocation in its sample/message methods.
 */
class P25P1AutoSelector
{
    private static final double TRIAL_SECONDS = 0.75;
    private static final double SIGNAL_LOSS_SECONDS = 3.0;
    private static final double MINIMUM_HOLD_SECONDS = 10.0;
    private static final int EARLY_LOCK_VALID_MESSAGES = 2;

    private Phase mPhase;
    private Modulation mPreferred;
    private Modulation mActive;
    private Modulation mPreviousLocked;
    private long mTrialSamples;
    private long mSignalLossSamples;
    private long mMinimumHoldSamples;
    private long mPhaseSamples;
    private long mLockedSamples;
    private long mSamplesSinceValidMessage;
    private int mPreferredValidMessages;
    private int mActiveValidMessages;

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
        mSignalLossSamples = Math.max(1, Math.round(sampleRate * SIGNAL_LOSS_SECONDS));
        mMinimumHoldSamples = Math.max(1, Math.round(sampleRate * MINIMUM_HOLD_SECONDS));
    }

    void reset(Modulation preferred)
    {
        mPreferred = fixed(preferred);
        mActive = mPreferred;
        mPreviousLocked = null;
        mPhase = Phase.ACQUIRE_PREFERRED;
        mPhaseSamples = 0;
        mLockedSamples = 0;
        mSamplesSinceValidMessage = 0;
        mPreferredValidMessages = 0;
        mActiveValidMessages = 0;
    }

    Modulation getActive()
    {
        return mActive;
    }

    boolean isLocked()
    {
        return mPhase == Phase.LOCKED;
    }

    /**
     * Records a decoded message and indicates if it may be forwarded to the processing chain.
     */
    boolean receiveMessage(Modulation modulation, boolean valid)
    {
        if(modulation != mActive)
        {
            return false;
        }

        if(valid)
        {
            mSamplesSinceValidMessage = 0;

            if(mPhase != Phase.LOCKED)
            {
                mActiveValidMessages++;

                if(mActiveValidMessages >= EARLY_LOCK_VALID_MESSAGES)
                {
                    lock(mActive);
                }
            }
        }

        return mPhase == Phase.LOCKED;
    }

    /**
     * Advances the selector after one buffer was processed.
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
            mLockedSamples += sampleCount;
            mSamplesSinceValidMessage += sampleCount;

            if(mLockedSamples >= mMinimumHoldSamples && mSamplesSinceValidMessage >= mSignalLossSamples)
            {
                mPreviousLocked = mActive;
                return beginTrial(alternate(mActive), Phase.VERIFY_ALTERNATE);
            }

            return null;
        }

        if(mPhaseSamples < mTrialSamples)
        {
            return null;
        }

        return switch(mPhase)
        {
            case ACQUIRE_PREFERRED -> {
                mPreferredValidMessages = mActiveValidMessages;
                yield beginTrial(alternate(mPreferred), Phase.ACQUIRE_ALTERNATE);
            }
            case ACQUIRE_ALTERNATE -> finishAcquisition();
            case VERIFY_ALTERNATE -> finishVerification();
            case LOCKED -> null;
        };
    }

    private Modulation finishAcquisition()
    {
        if(mPreferredValidMessages == 0 && mActiveValidMessages == 0)
        {
            mPreferredValidMessages = 0;
            return beginTrial(mPreferred, Phase.ACQUIRE_PREFERRED);
        }

        Modulation winner = mActiveValidMessages > mPreferredValidMessages ? mActive : mPreferred;
        Modulation previous = mActive;
        lock(winner);
        return winner != previous ? winner : null;
    }

    private Modulation finishVerification()
    {
        Modulation winner = mActiveValidMessages > 0 ? mActive : mPreviousLocked;
        Modulation previous = mActive;
        lock(winner);
        return winner != previous ? winner : null;
    }

    private Modulation beginTrial(Modulation modulation, Phase phase)
    {
        Modulation previous = mActive;
        mActive = fixed(modulation);
        mPhase = phase;
        mPhaseSamples = 0;
        mActiveValidMessages = 0;
        return mActive != previous ? mActive : null;
    }

    private void lock(Modulation modulation)
    {
        mActive = fixed(modulation);
        mPhase = Phase.LOCKED;
        mPhaseSamples = 0;
        mLockedSamples = 0;
        mSamplesSinceValidMessage = 0;
        mActiveValidMessages = 0;
        mPreviousLocked = null;
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
        ACQUIRE_PREFERRED,
        ACQUIRE_ALTERNATE,
        LOCKED,
        VERIFY_ALTERNATE
    }
}
