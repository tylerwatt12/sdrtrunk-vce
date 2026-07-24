/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner.configuration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite payload containing tuner configurations and disabled tuner identities.
 */
@JsonDeserialize(using = TunerSettingsDeserializer.class)
public class TunerSettings
{
    private List<DisabledTuner> mDisabledTuners = new ArrayList<>();
    private List<TunerConfiguration> mTunerConfigurations = new ArrayList<>();
    private int mIgnoredEntryCount;

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

    /**
     * Number of unsupported or malformed entries ignored while loading this payload.
     */
    @JsonIgnore
    public int getIgnoredEntryCount()
    {
        return mIgnoredEntryCount;
    }

    void setIgnoredEntryCount(int ignoredEntryCount)
    {
        mIgnoredEntryCount = ignoredEntryCount;
    }
}
