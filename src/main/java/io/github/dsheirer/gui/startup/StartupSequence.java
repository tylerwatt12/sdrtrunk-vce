/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.startup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Small deterministic state machine for the optional post-launch startup pages.
 */
public final class StartupSequence
{
    private final List<StartupStep> mSteps;
    private int mIndex = -1;

    public StartupSequence(boolean showWhatsNew, boolean calibrateCpu, boolean setupJmbe, boolean unlockVault,
                           boolean autoStartChannels)
    {
        List<StartupStep> steps = new ArrayList<>();

        if(showWhatsNew)
        {
            steps.add(StartupStep.WHATS_NEW);
        }

        if(calibrateCpu)
        {
            steps.add(StartupStep.CPU_CALIBRATION);
        }

        if(setupJmbe)
        {
            steps.add(StartupStep.JMBE_LIBRARY);
        }

        if(unlockVault)
        {
            steps.add(StartupStep.ENCRYPTION_VAULT);
        }

        if(autoStartChannels)
        {
            steps.add(StartupStep.AUTO_START_CHANNELS);
        }

        mSteps = List.copyOf(steps);
    }

    public List<StartupStep> getSteps()
    {
        return mSteps;
    }

    public Optional<StartupStep> start()
    {
        if(mSteps.isEmpty())
        {
            return Optional.empty();
        }

        mIndex = 0;
        return current();
    }

    public Optional<StartupStep> advance()
    {
        if(mIndex < 0)
        {
            return start();
        }

        mIndex++;
        return current();
    }

    public Optional<StartupStep> current()
    {
        return mIndex >= 0 && mIndex < mSteps.size() ? Optional.of(mSteps.get(mIndex)) : Optional.empty();
    }

    public int getCurrentNumber()
    {
        return current().isPresent() ? mIndex + 1 : 0;
    }

    public int size()
    {
        return mSteps.size();
    }

    public boolean isComplete()
    {
        return !mSteps.isEmpty() && mIndex >= mSteps.size();
    }
}
