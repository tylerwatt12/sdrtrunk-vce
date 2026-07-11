/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner.configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite payload containing tuner configurations and disabled tuner identities.
 */
public class TunerSettings
{
    private List<DisabledTuner> mDisabledTuners = new ArrayList<>();
    private List<TunerConfiguration> mTunerConfigurations = new ArrayList<>();

    public List<DisabledTuner> getDisabledTuners()
    {
        return mDisabledTuners;
    }

    public void setDisabledTuners(List<DisabledTuner> disabledTuners)
    {
        mDisabledTuners = disabledTuners != null ? disabledTuners : new ArrayList<>();
    }

    public List<TunerConfiguration> getTunerConfigurations()
    {
        return mTunerConfigurations;
    }

    public void setTunerConfigurations(List<TunerConfiguration> tunerConfigurations)
    {
        mTunerConfigurations = tunerConfigurations != null ? tunerConfigurations : new ArrayList<>();
    }
}
