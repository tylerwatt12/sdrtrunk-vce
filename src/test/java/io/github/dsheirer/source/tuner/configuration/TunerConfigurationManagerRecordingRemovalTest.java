/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.configuration;

import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredRecordingTuner;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunerConfigurationManagerRecordingRemovalTest
{
    @Test
    void removesRecordingConfigurationAndDisabledMarkerInOneSavedSnapshot()
    {
        RecordingTunerConfiguration target = RecordingTunerConfiguration.createWithUniqueId();
        target.setPath("/test/target.wav");
        RecordingTunerConfiguration other = RecordingTunerConfiguration.createWithUniqueId();
        other.setPath("/test/other.wav");
        TunerSettings initial = new TunerSettings();
        initial.setTunerConfigurations(List.of(target, other));
        initial.setDisabledTuners(List.of(
            new DisabledTuner(TunerClass.RECORDING_TUNER, target.getPath()),
            new DisabledTuner(TunerClass.RECORDING_TUNER, other.getPath())));
        TrackingStore store = new TrackingStore(initial);
        TunerConfigurationManager manager = new TunerConfigurationManager(store, Runnable::run);
        DiscoveredRecordingTuner discovered = new DiscoveredRecordingTuner(target);

        manager.removeRecordingTunerConfiguration(discovered);

        assertEquals(1, store.mSaveCount);
        assertEquals(List.of(other), store.mSaved.getTunerConfigurations());
        assertFalse(store.mSaved.getDisabledTuners().stream().anyMatch(disabled -> disabled.matches(discovered)));
        assertTrue(store.mSaved.getDisabledTuners().stream().anyMatch(disabled -> other.getPath().equals(disabled.id())));
    }

    private static final class TrackingStore extends ApplicationSettingsStore
    {
        private final TunerSettings mInitial;
        private TunerSettings mSaved;
        private int mSaveCount;

        private TrackingStore(TunerSettings initial)
        {
            super(Path.of("recording-removal-test.sqlite"));
            mInitial = initial;
        }

        @Override
        public <T> Optional<T> load(String key, Class<T> type)
        {
            return Optional.of(type.cast(mInitial));
        }

        @Override
        public void saveLater(String key, Object value)
        {
            mSaved = (TunerSettings)value;
            mSaveCount++;
        }
    }
}
