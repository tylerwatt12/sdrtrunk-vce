/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.channel.rotation;

import io.github.dsheirer.module.ModuleEventBusMessage;

/**
 * Synchronously pauses channel rotation so a lifecycle operation can make a stable-source decision.
 *
 * <p>The processing-chain event bus dispatches this request synchronously.  The rotation monitor fences any callback
 * already issuing a rotation request, and a multi-frequency source fences tuner replacement and cancels any scheduled
 * source-acquisition retry before acknowledging the source state.</p>
 */
public class ChannelRotationMonitorPauseRequest implements ModuleEventBusMessage
{
    private volatile boolean mMonitorPaused;
    private volatile boolean mSourcePaused;
    private volatile boolean mSourceAvailable;
    private volatile long mSourceFrequency;

    public void acknowledgeMonitorPaused()
    {
        mMonitorPaused = true;
    }

    public void acknowledgeSourcePaused(boolean sourceAvailable, long sourceFrequency)
    {
        mSourceFrequency = sourceFrequency;
        mSourceAvailable = sourceAvailable;
        mSourcePaused = true;
    }

    /**
     * Indicates that a rotation monitor, when present, acknowledged the pause.
     */
    public boolean isMonitorPaused()
    {
        return mMonitorPaused;
    }

    /**
     * Indicates that the multi-frequency source is fenced with a live underlying source at the expected frequency.
     */
    public boolean isSourceStableAt(long expectedFrequency)
    {
        return mSourcePaused && mSourceAvailable && mSourceFrequency == expectedFrequency;
    }

    /**
     * Frequency reported by the fenced multi-frequency source.
     */
    public long getSourceFrequency()
    {
        return mSourceFrequency;
    }
}
