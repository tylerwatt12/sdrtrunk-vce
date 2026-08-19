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

package io.github.dsheirer.stats;

import java.util.concurrent.TimeUnit;

/**
 * Worker-owned budget for expendable diagnostic processing. Receiver queue snapshots are approximate, lock-free
 * control-plane observations; this class never runs on, or calls back into, a receiver thread.
 */
final class DiagnosticResourceGovernor
{
    private static final long RECOVERY_HOLD_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long RECEIVER_PAUSE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int ELEVATED_QUEUE_PERCENT = 25;
    private static final int CRITICAL_QUEUE_PERCENT = 50;
    private static final int HEAVY_FRAME_PERCENT = 75;
    private Mode mMode = Mode.FULL;
    private long mPreviousDroppedBuffers = -1;
    private long mNextFrameNanos;
    private long mProtectUntilNanos;
    private long mStableSinceNanos = -1;
    private long mSkippedFrames;
    private long mThrottleEvents;
    private int mHeavyFrameStreak;
    private int mPendingWorkSeverity;
    private volatile Status mStatus = new Status(Mode.FULL, false, 0, 0, "", 0, 0);

    /** Records one completed diagnostic frame. Call only from the diagnostic worker. */
    void recordFrameDuration(long durationNanos, int requestedFramesPerSecond)
    {
        validateFramesPerSecond(requestedFramesPerSecond);
        int budgetFramesPerSecond = effectiveFramesPerSecond(requestedFramesPerSecond, mMode);
        long budgetNanos = TimeUnit.SECONDS.toNanos(1) / budgetFramesPerSecond;

        if(durationNanos >= budgetNanos)
        {
            mHeavyFrameStreak++;
            mPendingWorkSeverity = 2;
        }
        else if(durationNanos >= budgetNanos * HEAVY_FRAME_PERCENT / 100)
        {
            mHeavyFrameStreak++;

            if(mHeavyFrameStreak >= 2)
            {
                mPendingWorkSeverity = Math.max(mPendingWorkSeverity, 1);
            }
        }
        else
        {
            mHeavyFrameStreak = 0;
        }
    }

    /**
     * Samples receiver pressure and decides whether this scheduler pass may calculate a frame. A zero queue capacity
     * means queue pressure is unsupported; discard deltas and diagnostic work duration still protect the receiver.
     */
    Decision evaluate(long nowNanos, int requestedFramesPerSecond, long receiverQueuedMilliseconds,
                      long receiverQueueCapacityMilliseconds, long receiverDroppedBuffers)
    {
        validateFramesPerSecond(requestedFramesPerSecond);
        int severity = 0;
        String reason = "";
        boolean newReceiverDrop = mPreviousDroppedBuffers >= 0 &&
            receiverDroppedBuffers > mPreviousDroppedBuffers;
        mPreviousDroppedBuffers = Math.max(0, receiverDroppedBuffers);

        if(newReceiverDrop)
        {
            severity = 2;
            reason = "Receiver IQ was discarded; Spectrum is paused to protect decoding.";
        }
        else if(atLeastPercent(receiverQueuedMilliseconds, receiverQueueCapacityMilliseconds,
            CRITICAL_QUEUE_PERCENT))
        {
            severity = 2;
            reason = "Receiver IQ queue pressure paused Spectrum processing.";
        }
        else if(mPendingWorkSeverity >= 2)
        {
            severity = 2;
            reason = "Spectrum processing exceeded its frame budget.";
        }
        else if(atLeastPercent(receiverQueuedMilliseconds, receiverQueueCapacityMilliseconds,
            ELEVATED_QUEUE_PERCENT))
        {
            severity = 1;
            reason = "Spectrum frame rate was reduced for receiver IQ queue pressure.";
        }
        else if(mPendingWorkSeverity == 1)
        {
            severity = 1;
            reason = "Spectrum frame rate was reduced for diagnostic processing load.";
        }

        mPendingWorkSeverity = 0;
        boolean escalated = severity > mMode.severity();

        if(escalated)
        {
            mMode = Mode.forSeverity(severity);
            mThrottleEvents++;
            mStableSinceNanos = -1;
        }
        else if(severity > 0)
        {
            mStableSinceNanos = -1;
        }
        else if(mMode != Mode.FULL)
        {
            if(mStableSinceNanos < 0)
            {
                mStableSinceNanos = nowNanos;
            }
            else if(nowNanos - mStableSinceNanos >= RECOVERY_HOLD_NANOS)
            {
                mMode = mMode == Mode.PROTECTING ? Mode.REDUCED : Mode.FULL;
                mStableSinceNanos = mMode == Mode.FULL ? -1 : nowNanos;
                mNextFrameNanos = 0;
            }

            if(mMode != Mode.FULL)
            {
                reason = "Spectrum is recovering after receiver or diagnostic pressure.";
            }
        }

        int effectiveFramesPerSecond = effectiveFramesPerSecond(requestedFramesPerSecond, mMode);

        if(severity >= 2)
        {
            mProtectUntilNanos = Math.max(mProtectUntilNanos, nowNanos + RECEIVER_PAUSE_NANOS);
        }

        if(escalated)
        {
            mNextFrameNanos = Math.max(mNextFrameNanos,
                nowNanos + TimeUnit.SECONDS.toNanos(1) / effectiveFramesPerSecond);
        }

        boolean paused = mMode == Mode.PROTECTING && nowNanos < mProtectUntilNanos;
        boolean render = !paused && (mNextFrameNanos == 0 || nowNanos >= mNextFrameNanos);

        if(render)
        {
            mNextFrameNanos = nowNanos + TimeUnit.SECONDS.toNanos(1) / effectiveFramesPerSecond;
        }
        else
        {
            mSkippedFrames++;
        }

        boolean throttled = mMode != Mode.FULL;
        Status status = new Status(mMode, throttled, requestedFramesPerSecond, effectiveFramesPerSecond,
            throttled ? reason : "", mSkippedFrames, mThrottleEvents);
        mStatus = status;
        return new Decision(render, status);
    }

    Status status()
    {
        return mStatus;
    }

    private static int effectiveFramesPerSecond(int requested, Mode mode)
    {
        return switch(mode)
        {
            case FULL -> requested;
            case REDUCED -> Math.max(1, (requested + 1) / 2);
            case PROTECTING -> Math.min(2, requested);
        };
    }

    private static boolean atLeastPercent(long value, long total, int percent)
    {
        return value > 0 && total > 0 && (double)value / total >= percent / 100.0;
    }

    private static void validateFramesPerSecond(int framesPerSecond)
    {
        if(framesPerSecond < 1 || framesPerSecond > 60)
        {
            throw new IllegalArgumentException("Diagnostic frame rate must be between 1 and 60");
        }
    }

    enum Mode
    {
        FULL(0),
        REDUCED(1),
        PROTECTING(2);

        private final int mSeverity;

        Mode(int severity)
        {
            mSeverity = severity;
        }

        int severity()
        {
            return mSeverity;
        }

        private static Mode forSeverity(int severity)
        {
            return severity >= 2 ? PROTECTING : severity == 1 ? REDUCED : FULL;
        }
    }

    record Decision(boolean renderFrame, Status status)
    {
    }

    record Status(Mode mode, boolean throttled, int requestedFramesPerSecond, int effectiveFramesPerSecond,
                  String reason, long skippedFrames, long throttleEvents)
    {
    }
}
