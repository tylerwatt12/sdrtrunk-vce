/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import io.github.dsheirer.source.tuner.test.TestTuner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunerManagerRecordingRemovalTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void removesManagedRecordingButNeverItsWaveFile() throws Exception
    {
        Path wave = mTemporaryDirectory.resolve("source.wav");
        Files.writeString(wave, "source remains operator-owned");
        TrackingConfigurations configurations = new TrackingConfigurations();
        TunerManager manager = new TunerManager(null, configurations);
        manager.addRecordingTuner(wave, 851_012_500L);
        DiscoveredTuner recording = manager.getDiscoveredTunerRegistry().find(wave.toAbsolutePath().toString());

        assertEquals(TunerManager.RecordingTunerRemovalResult.REMOVED, manager.removeRecordingTuner(recording));
        assertFalse(manager.getDiscoveredTunerRegistry().contains(recording));
        assertTrue(Files.exists(wave));
        assertEquals("source remains operator-owned", Files.readString(wave));
        assertSame(recording, configurations.mRemoved);
        assertEquals(TunerManager.RecordingTunerRemovalResult.NOT_FOUND, manager.removeRecordingTuner(recording));
    }

    @Test
    void refusesPhysicalTunerAndActiveRecording() throws Exception
    {
        TrackingConfigurations configurations = new TrackingConfigurations();
        TunerManager manager = new TunerManager(null, configurations);
        DiscoveredTuner physical = new DiscoveredTuner()
        {
            @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
            @Override public String getId() { return "physical"; }
            @Override public void start() { }
        };
        manager.getDiscoveredTunerRegistry().add(physical);
        assertEquals(TunerManager.RecordingTunerRemovalResult.NOT_RECORDING,
            manager.removeRecordingTuner(physical));
        assertTrue(manager.getDiscoveredTunerRegistry().contains(physical));

        RecordingTunerConfiguration configuration = RecordingTunerConfiguration.createWithUniqueId();
        configuration.setPath(mTemporaryDirectory.resolve("active.wav").toString());
        ActiveRecording recording = new ActiveRecording(configuration);
        manager.getDiscoveredTunerRegistry().add(recording);
        assertEquals(TunerManager.RecordingTunerRemovalResult.IN_USE, manager.removeRecordingTuner(recording));
        assertTrue(manager.getDiscoveredTunerRegistry().contains(recording));
        assertNull(configurations.mRemoved);
        manager.getDiscoveredTunerRegistry().release();
    }

    private static final class TrackingConfigurations extends TunerConfigurationManager
    {
        private DiscoveredRecordingTuner mRemoved;

        @Override
        public void addTunerConfiguration(TunerConfiguration configuration)
        {
        }

        @Override
        public void removeRecordingTunerConfiguration(DiscoveredRecordingTuner recording)
        {
            mRemoved = recording;
        }
    }

    private static final class ActiveRecording extends DiscoveredRecordingTuner
    {
        private final Tuner mActiveTuner;

        private ActiveRecording(RecordingTunerConfiguration configuration)
        {
            super(configuration);
            mActiveTuner = new TestTuner(this)
            {
                private final ChannelSourceManager mChannels = new ChannelSourceManager()
                {
                    @Override public SortedSet<TunerChannel> getTunerChannels() { return new TreeSet<>(); }
                    @Override public String getStateDescription() { return "active"; }
                    @Override public int getTunerChannelCount() { return 1; }
                    @Override public void stopAllChannels() { }
                    @Override public TunerChannelSource getSource(TunerChannel channel,
                        ChannelSpecification specification, String name) { return null; }
                    @Override public void setErrorMessage(String errorMessage) { }
                    @Override public void process(SourceEvent event) { }
                };

                @Override
                public ChannelSourceManager getChannelSourceManager()
                {
                    return mChannels;
                }
            };
        }

        @Override public boolean hasTuner() { return mActiveTuner != null; }
        @Override public Tuner getTuner() { return mActiveTuner; }
    }
}
