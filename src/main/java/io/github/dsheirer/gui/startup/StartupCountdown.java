/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.startup;

/**
 * Small reusable countdown state for bounded startup steps.
 */
final class StartupCountdown
{
    private final int mInitialSeconds;
    private int mSecondsRemaining;

    StartupCountdown(int seconds)
    {
        mInitialSeconds = Math.max(0, seconds);
        reset();
    }

    int getSecondsRemaining()
    {
        return mSecondsRemaining;
    }

    boolean isExpired()
    {
        return mSecondsRemaining <= 0;
    }

    boolean tick()
    {
        if(mSecondsRemaining > 0)
        {
            mSecondsRemaining--;
        }

        return isExpired();
    }

    void reset()
    {
        mSecondsRemaining = mInitialSeconds;
    }
}
