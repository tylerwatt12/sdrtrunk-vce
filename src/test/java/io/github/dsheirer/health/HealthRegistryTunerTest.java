/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class HealthRegistryTunerTest
{
    @Test
    void enabled_idle_tuners_accrue_seconds_only_while_they_have_channels()
    {
        FakeTuner idle = new FakeTuner("Spare", 0);
        FakeTuner active = new FakeTuner("Receiving", 2);
        HealthRegistry registry = new HealthRegistry(() -> List.of(discovered(idle), discovered(active)));
        synchronized(registry)
        {
            try
            {
                registry.retainHistory();
                registry.snapshot(1_000);
                registry.snapshot(11_000);
                assertEquals(0.0, registry.getHistory().latest().radio("Spare").seconds());
                assertEquals(10.0, registry.getHistory().latest().radio("Receiving").seconds());

                idle.mSources.mCount = 1;
                active.mSources.mCount = 0;
                registry.snapshot(21_000);
                assertEquals(10.0, registry.getHistory().latest().radio("Spare").seconds());
                assertEquals(10.0, registry.getHistory().latest().radio("Receiving").seconds());

                idle.mSources.mCount = 0;
                registry.snapshot(31_000);
                assertEquals(10.0, registry.getHistory().latest().radio("Spare").seconds());
                assertEquals(10.0, registry.getHistory().latest().radio("Receiving").seconds());
            }
            finally
            {
                registry.releaseHistory();
            }
        }
    }

    @Test
    void shutdown_after_capturing_a_tuner_or_its_sources_does_not_require_a_second_read()
    {
        FakeTuner stopping = new FakeTuner("Stopping", 1);
        stopping.mDropSourcesAfterRead = true;
        FakeDiscoveredTuner disappearing = discovered(stopping);
        disappearing.mDisappearAfterRead = true;
        FakeDiscoveredTuner disabled = new FakeDiscoveredTuner(null);
        FakeTuner healthy = new FakeTuner("Healthy", 1);
        HealthRegistry registry = new HealthRegistry(() -> List.of(disappearing, disabled, discovered(healthy)));
        synchronized(registry)
        {
            try
            {
                registry.retainHistory();
                registry.snapshot(1_000);
                HealthSnapshot first = registry.getHistory().latest();
                assertNotNull(first.radio("Stopping"), "The captured runtime remains safe to project");
                assertNotNull(first.radio("Healthy"));
                assertEquals(1, disappearing.mReads, "hasTuner() followed by getTuner() races shutdown");
                assertEquals(1, disabled.mReads);
                assertEquals(1, stopping.mSourceReads, "The channel manager can disappear after its first read");

                registry.snapshot(11_000);
                HealthSnapshot next = registry.getHistory().latest();
                assertEquals(11_000, next.timeMs(), "A missing runtime must not discard the new snapshot");
                assertNull(next.radio("Stopping"));
                assertEquals(10.0, next.radio("Healthy").seconds());
                assertEquals(2, disappearing.mReads);
            }
            finally
            {
                registry.releaseHistory();
            }
        }
    }

    @Test
    void one_tuners_projection_failure_preserves_other_radios_and_the_channels_full_snapshot()
    {
        FakeTuner before = new FakeTuner("Before failure", 1);
        FakeTuner broken = new FakeTuner("Broken", 1);
        broken.mFailName = true;
        FakeTuner after = new FakeTuner("After failure", 1);
        HealthRegistry registry = new HealthRegistry(() -> List.of(discovered(before), discovered(broken),
            discovered(after)));
        Channel channel = new Channel("Control channel");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        ChannelHealth health = registry.channel(channel);
        health.started(1_000);
        synchronized(registry)
        {
            try
            {
                registry.retainHistory();
                registry.snapshot(1_000);
                health.control("p25", true, 2_000);
                registry.snapshot(11_000);
                HealthSnapshot latest = registry.getHistory().latest();
                assertEquals(11_000, latest.timeMs());
                assertEquals(2, latest.radios().size());
                assertEquals(10.0, latest.radio("Before failure").seconds());
                assertEquals(10.0, latest.radio("After failure").seconds());
                assertEquals(1, latest.channels().size());
                assertNotNull(latest.channel(channel.getChannelID()));
                assertEquals(1, latest.channel(channel.getChannelID()).good());
                assertEquals(10_000, latest.channel(channel.getChannelID()).runningMs());
            }
            finally
            {
                registry.releaseHistory();
            }
        }
    }

    private static FakeDiscoveredTuner discovered(FakeTuner tuner)
    {
        return new FakeDiscoveredTuner(tuner);
    }

    private static final class FakeDiscoveredTuner extends DiscoveredTuner
    {
        private int mReads;
        private boolean mDisappearAfterRead;

        private FakeDiscoveredTuner(Tuner tuner) { mTuner = tuner; }
        @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
        @Override public String getId() { return "snapshot-test"; }
        @Override public void start() { }
        @Override public Tuner getTuner()
        {
            mReads++;
            Tuner captured = mTuner;
            if(mDisappearAfterRead) mTuner = null;
            return captured;
        }
    }

    private static final class FakeTuner extends Tuner
    {
        private final String mName;
        private final CountingSources mSources;
        private boolean mDropSourcesAfterRead;
        private boolean mFailName;
        private int mSourceReads;

        private FakeTuner(String name, int channels)
        {
            super(new FakeController(), null);
            mName = name;
            mSources = new CountingSources(channels);
        }

        @Override public ChannelSourceManager getChannelSourceManager()
        {
            mSourceReads++;
            return mDropSourcesAfterRead && mSourceReads > 1 ? null : mSources;
        }
        @Override public String getPreferredName()
        {
            if(mFailName) throw new IllegalStateException("Tuner was disposed during projection");
            return mName;
        }
        @Override public int getMaximumUSBBitsPerSecond() { return 0; }
        @Override public String getUniqueID() { return mName; }
        @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
        @Override public double getSampleSize() { return 16.0; }
    }

    private static final class CountingSources extends ChannelSourceManager
    {
        private int mCount;
        private CountingSources(int count) { mCount = count; }
        @Override public int getTunerChannelCount() { return mCount; }
        @Override public SortedSet<TunerChannel> getTunerChannels() { return new TreeSet<>(); }
        @Override public String getStateDescription() { return "snapshot test"; }
        @Override public void stopAllChannels() { mCount = 0; }
        @Override public TunerChannelSource getSource(TunerChannel channel, ChannelSpecification specification,
                                                     String threadName) { return null; }
        @Override public void setErrorMessage(String message) { }
        @Override public void process(SourceEvent event) { }
    }

    private static final class FakeController extends TunerController
    {
        private FakeController() { super(null); }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public TunerType getTunerType() { return TunerType.TEST; }
        @Override public int getBufferSampleCount() { return 0; }
        @Override public long getTunedFrequency() { return 460_000_000; }
        @Override public void setTunedFrequency(long frequency) { }
        @Override public double getCurrentSampleRate() { return 2_400_000; }
    }
}
