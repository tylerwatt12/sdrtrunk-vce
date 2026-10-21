/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** One typed setting binding, shared by description, validation, saved values and hardware application. */
final class TunerSettingDefinition<C extends TunerConfiguration,V>
{
    @FunctionalInterface
    interface HardwareWrite<C,V>
    {
        void apply(TunerController controller, C configuration, V value) throws Exception;
    }

    private final Class<C> mConfigurationType;
    private final Class<V> mValueType;
    private final String mId;
    private final String mLabel;
    private final Function<C,V> mRead;
    private final BiConsumer<C,V> mWrite;
    private final HardwareWrite<C,V> mApply;
    private Number mMinimum;
    private Number mMaximum;
    private Number mStep;
    private String mUnit;
    private String mScope = "tuner";
    private boolean mDynamicOptions;
    private Function<C,String> mUnavailable = configuration -> null;

    TunerSettingDefinition(Class<C> configurationType, Class<V> valueType, String id, String label,
                           Function<C,V> read, BiConsumer<C,V> write, HardwareWrite<C,V> apply)
    {
        mConfigurationType = Objects.requireNonNull(configurationType);
        mValueType = Objects.requireNonNull(valueType);
        mId = Objects.requireNonNull(id);
        mLabel = Objects.requireNonNull(label);
        mRead = Objects.requireNonNull(read);
        mWrite = Objects.requireNonNull(write);
        mApply = Objects.requireNonNull(apply);
    }

    TunerSettingDefinition<C,V> range(Number minimum, Number maximum, Number step, String unit)
    {
        mMinimum = minimum;
        mMaximum = maximum;
        mStep = step;
        mUnit = unit;
        return this;
    }

    TunerSettingDefinition<C,V> deviceScope() { mScope = "device"; return this; }
    TunerSettingDefinition<C,V> dynamicRate() { mDynamicOptions = true; mUnit = "Hz"; return this; }
    TunerSettingDefinition<C,V> unavailableWhen(Function<C,String> reason) { mUnavailable = reason; return this; }
    String id() { return mId; }
    String label() { return mLabel; }
    Class<V> valueType() { return mValueType; }
    Number minimum() { return mMinimum; }
    Number maximum() { return mMaximum; }
    Number step() { return mStep; }
    String unit() { return mUnit; }
    String scope() { return mScope; }
    boolean dynamicOptions() { return mDynamicOptions; }
    String unavailableReason(TunerConfiguration configuration) { return mUnavailable.apply(mConfigurationType.cast(configuration)); }
    Object read(TunerConfiguration configuration) { return mRead.apply(mConfigurationType.cast(configuration)); }
    void write(TunerConfiguration configuration, Object value)
    {
        mWrite.accept(mConfigurationType.cast(configuration), mValueType.cast(value));
    }
    void apply(TunerController controller, TunerConfiguration configuration) throws Exception
    {
        C typed = mConfigurationType.cast(configuration);
        mApply.apply(controller, typed, mRead.apply(typed));
    }
}
