/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.frequency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChannelFrequencyErrorManagerTest
{
    @Test
    void distinguishesNoMeasurementFromZeroAndRetainsTheLastMeasurement() throws Exception
    {
        try(Rig rig = new Rig())
        {
            assertNull(rig.source.getFrequencyCorrectionSnapshot());
            rig.source.start();
            assertNull(rig.source.getFrequencyCorrectionSnapshot());
            List<SourceEvent> broadcasts = new ArrayList<>();
            rig.source.setSourceEventListener(broadcasts::add);
            rig.parent.tick();
            var initial = rig.source.getFrequencyCorrectionSnapshot();
            assertNotNull(initial);
            assertNull(initial.requestedAdjustmentHz());
            assertNull(initial.measurementTimestamp());
            assertEquals(0, initial.channelCorrectionHz());

            rig.request(0);
            rig.parent.tick();
            var zero = rig.source.getFrequencyCorrectionSnapshot();
            assertEquals(0L, zero.requestedAdjustmentHz());
            assertNotNull(zero.measurementTimestamp());
            rig.parent.tick();
            var unchanged = rig.source.getFrequencyCorrectionSnapshot();
            assertEquals(zero.requestedAdjustmentHz(), unchanged.requestedAdjustmentHz());
            assertEquals(zero.measurementTimestamp(), unchanged.measurementTimestamp());
            assertTrue(unchanged.timestamp() >= zero.timestamp());
            assertTrue(broadcasts.isEmpty(), "reporting must not emit SourceEvents or call consumers");
        }
    }

    @Test
    void preservesSignedRequestsAndBoundedChannelCorrectionWhileAutoPpmIsOff() throws Exception
    {
        try(Rig rig = new Rig())
        {
            rig.parent.setEnabled(false);
            rig.controller.setFrequencyCorrection(-0.25);
            rig.source.start();
            rig.request(90);
            rig.request(90);
            rig.request(90);
            rig.parent.tick();
            var positive = rig.source.getFrequencyCorrectionSnapshot();
            assertEquals(90L, positive.requestedAdjustmentHz());
            assertEquals(10, positive.channelCorrectionHz());
            assertEquals(10, rig.source.appliedCorrection);
            assertEquals(-0.25, positive.tunerCorrectionPPM());
            assertEquals(-213, positive.tunerCorrectionHz());
            assertFalse(positive.autoPPM());
            assertEquals(852_000_000, positive.tunerCenterFrequencyHz());

            rig.request(-90);
            rig.request(-90);
            rig.request(-90);
            rig.parent.tick();
            var negative = rig.source.getFrequencyCorrectionSnapshot();
            assertEquals(-90L, negative.requestedAdjustmentHz());
            assertEquals(0, negative.channelCorrectionHz());
            assertEquals(0, rig.source.appliedCorrection);
        }
    }

    @Test
    void retuningInvalidatesOldMeasurementsAndDerivesHzFromTheNewCenter() throws Exception
    {
        try(Rig rig = new Rig())
        {
            rig.controller.setFrequencyCorrection(-0.25);
            rig.source.start();
            rig.request(0);
            rig.parent.tick();
            assertNotNull(rig.source.getFrequencyCorrectionSnapshot().requestedAdjustmentHz());
            rig.controller.setFrequency(100_000_000);
            assertNull(rig.source.getFrequencyCorrectionSnapshot());
            rig.parent.tick();
            var retuned = rig.source.getFrequencyCorrectionSnapshot();
            assertEquals(100_000_000, retuned.tunerCenterFrequencyHz());
            assertEquals(-25, retuned.tunerCorrectionHz());
            assertNull(retuned.requestedAdjustmentHz());
            assertNull(retuned.measurementTimestamp());

            rig.controller.setFrequency(852_000_000);
            assertNull(rig.source.getFrequencyCorrectionSnapshot(),
                "returning to the old frequency must not revive an old tuning generation");
            rig.parent.tick();
            assertNull(rig.source.getFrequencyCorrectionSnapshot().requestedAdjustmentHz());
        }
    }

    @Test
    void stoppingClearsSnapshotsAndIgnoresFurtherRequests() throws Exception
    {
        try(Rig rig = new Rig())
        {
            rig.source.start();
            ChannelFrequencyErrorManager manager = rig.parent.child;
            rig.request(90);
            rig.parent.tick();
            assertNotNull(rig.source.getFrequencyCorrectionSnapshot());
            rig.source.stop();
            assertNull(rig.source.getFrequencyCorrectionSnapshot());
            rig.request(-90);
            manager.processChannel();
            assertNull(rig.source.getFrequencyCorrectionSnapshot());
            assertEquals(0, rig.source.appliedCorrection);
        }
    }

    @Test
    void blockedTunerSettingsAndDiagnosticReadersCannotBackpressureDecoderRequests() throws Exception
    {
        try(Rig rig = new Rig(); ExecutorService executor = Executors.newSingleThreadExecutor())
        {
            rig.source.start();
            rig.controller.getLock().lock();
            try
            {
                Future<?> producer = executor.submit(() ->
                {
                    for(int i = 0; i < 10_000; i++)
                    {
                        rig.request(0);
                    }
                    rig.parent.tick();
                });
                producer.get(1, TimeUnit.SECONDS);
                assertNotNull(rig.source.getFrequencyCorrectionSnapshot());
            }
            finally
            {
                rig.controller.getLock().unlock();
            }
        }
    }

    @Test
    void anOldWorkerCannotRepublishItsSnapshotAfterStopAndRestart() throws Exception
    {
        try(Rig rig = new Rig(); ExecutorService executor = Executors.newSingleThreadExecutor())
        {
            CountDownLatch applying = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            rig.source.beforeNonzeroCorrection = () ->
            {
                applying.countDown();
                try
                {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            };
            rig.source.start();
            rig.request(90);
            Future<?> oldTick = executor.submit(rig.parent::tick);
            try
            {
                assertTrue(applying.await(1, TimeUnit.SECONDS));
                rig.source.stop();
                rig.source.start();
            }
            finally
            {
                release.countDown();
            }
            oldTick.get(1, TimeUnit.SECONDS);
            assertNull(rig.source.getFrequencyCorrectionSnapshot());
            rig.parent.tick();
            assertNull(rig.source.getFrequencyCorrectionSnapshot().requestedAdjustmentHz());
        }
    }

    private static final class Rig implements AutoCloseable
    {
        private final TestTunerController controller = new TestTunerController();
        private final TestParent parent = new TestParent(controller);
        private final TestSource source = new TestSource(parent);

        private Rig() throws Exception
        {
            controller.setFrequency(852_000_000);
        }

        private void request(long hertz)
        {
            source.getSourceEventListener().receive(SourceEvent.frequencyCorrectionRequest(hertz));
        }

        @Override
        public void close()
        {
            source.stop();
            parent.dispose();
            controller.getTunerFrequencyErrorManager().dispose();
        }
    }

    /** Keeps the production correction method deterministic without starting its ordinary 500 ms timer. */
    private static final class TestParent extends TunerFrequencyErrorManager
    {
        private ChannelFrequencyErrorManager child;

        private TestParent(TestTunerController controller)
        {
            super(controller);
        }

        @Override
        public void add(ChannelFrequencyErrorManager manager)
        {
            child = manager;
        }

        @Override
        public void remove(ChannelFrequencyErrorManager manager)
        {
            child = null;
        }

        private void tick()
        {
            child.processChannel();
        }
    }

    private static final class TestSource extends TunerChannelSource
    {
        private volatile long appliedCorrection;
        private Runnable beforeNonzeroCorrection;

        private TestSource(TestParent parent)
        {
            super(null, new TunerChannel(852_000_000, 12_500), "correction test", parent);
        }

        @Override public void setFrequency(long frequency) { }
        @Override protected void setSampleRate(double sampleRate) { }
        @Override public double getSampleRate() { return 25_000; }
        @Override public void setListener(Listener<ComplexSamples> listener) { }

        @Override
        public void setFrequencyCorrection(long correction)
        {
            appliedCorrection = correction;
            if(correction != 0 && beforeNonzeroCorrection != null)
            {
                beforeNonzeroCorrection.run();
            }
        }
    }
}
