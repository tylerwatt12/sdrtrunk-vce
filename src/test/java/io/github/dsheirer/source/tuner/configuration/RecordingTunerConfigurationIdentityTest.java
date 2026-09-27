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
package io.github.dsheirer.source.tuner.configuration;

import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingTunerConfigurationIdentityTest
{
    @Test
    void rapidAddsRetainEveryRecordingInConfigurationPersistence()
    {
        InMemorySettingsStore store = new InMemorySettingsStore();
        TunerConfigurationManager manager = new TunerConfigurationManager(store, Runnable::run);
        int count = 100;

        for(int index = 0; index < count; index++)
        {
            RecordingTunerConfiguration recording = RecordingTunerConfiguration.createWithUniqueId();
            recording.setPath("recording-" + index + ".wav");
            recording.setFrequency(851_012_500L);
            manager.addTunerConfiguration(recording);
        }

        List<TunerConfiguration> configurations = manager.getTunerConfigurations(TunerType.RECORDING);
        Set<String> ids = new HashSet<>();
        configurations.forEach(configuration -> ids.add(configuration.getUniqueID()));
        assertEquals(count, configurations.size(), "The manager must not discard a rapid add as a duplicate");
        assertEquals(count, ids.size());
        assertEquals(count, store.mLastSaved.getTunerConfigurations().size(),
            "The persisted snapshot must contain every recording");
        assertTrue(ids.stream().allMatch(id -> id.startsWith("Recording ")));
    }

    private static final class InMemorySettingsStore extends ApplicationSettingsStore
    {
        private TunerSettings mLastSaved;

        private InMemorySettingsStore()
        {
            super(Path.of("recording-tuner-identity-test.sqlite"));
        }

        @Override
        public <T> Optional<T> load(String key, Class<T> type)
        {
            return Optional.empty();
        }

        @Override
        public void saveLater(String key, Object value)
        {
            mLastSaved = (TunerSettings)value;
        }
    }
}
