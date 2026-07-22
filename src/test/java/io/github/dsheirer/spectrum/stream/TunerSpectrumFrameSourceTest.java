/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.spectrum.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.buffer.FloatNativeBuffer;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.manager.TestPolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.test.TestTuner;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import io.github.dsheirer.spectrum.ComplexDftProcessor;
import io.github.dsheirer.spectrum.DFTSize;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TunerSpectrumFrameSourceTest
{
    @Test
    void tapsAnAlreadyRunningTunerAndReleasesEveryListenerAndExecutor() throws Exception
    {
        TestTuner tuner = new TestTuner(null);
        TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
            new TunerSpectrumFrameSource.Configuration(DFTSize.FFT00512, 20), () -> List.of(tuner));
        ArrayBlockingQueue<SpectrumFrame> frames = new ArrayBlockingQueue<>(1);

        source.start(frames::offer);
        SpectrumFrame frame = frames.poll(3, TimeUnit.SECONDS);

        assertTrue(frame != null);
        assertTrue(source.isRunning());
        assertEquals(tuner.getTunerController().getFrequency(), frame.getCenterFrequencyHz());
        assertEquals(Math.round(tuner.getTunerController().getSampleRate()), frame.getSampleRateHz());
        assertEquals(DFTSize.FFT00512.getSize(), frame.getBinCount());
        assertEquals(SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID,
            frame.getFlags() & SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID);

        source.stop();
        assertFalse(source.isRunning());
        long publishedAtStop = source.getPublishedFrameCount();
        Thread.sleep(100);
        assertEquals(publishedAtStop, source.getPublishedFrameCount());
        source.close();
    }

    @Test
    void complexDftDisposeTerminatesItsOwnedExecutor()
    {
        ComplexDftProcessor processor = new ComplexDftProcessor();
        assertFalse(processor.isExecutorTerminated());
        processor.dispose();
        assertTrue(processor.isExecutorTerminated());
        processor.dispose();
    }

    @Test
    void complexDftCanSuppressRepeatedFramesWhenSamplesStall() throws Exception
    {
        AtomicInteger deliveries = new AtomicInteger();

        try(ComplexDftProcessor processor = new ComplexDftProcessor())
        {
            processor.setRepeatLastFrameWhenIdle(false);
            processor.setFrameRate(100);
            processor.addConverter(new io.github.dsheirer.spectrum.converter.DFTResultsConverter()
            {
                @Override
                public void receive(float[] results)
                {
                    deliveries.incrementAndGet();
                }
            });
            float[] samples = new float[DFTSize.FFT04096.getSize() * 2];
            processor.receive(new FloatNativeBuffer(samples, System.currentTimeMillis(), 0.0f));
            // NativeBufferManager moves a completed producer batch on the next non-blocking producer callback.
            processor.receive(new FloatNativeBuffer(samples, System.currentTimeMillis(), 0.0f));
            awaitDelivery(deliveries);
            int deliveredAtStall = deliveries.get();
            Thread.sleep(100);
            assertEquals(deliveredAtStall, deliveries.get(), "an idle tuner must not repeat its last web FFT");
        }
    }

    @Test
    void removesBufferListenerWhenControllerRegistersThenThrows()
    {
        RegisterThenThrowController controller = new RegisterThenThrowController();
        RegisterThenThrowTuner tuner = new RegisterThenThrowTuner(controller);
        TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
            new TunerSpectrumFrameSource.Configuration(DFTSize.FFT00512, 20), () -> List.of(tuner));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> source.start(frame -> {}));

        assertEquals("simulated transfer startup failure", exception.getMessage());
        assertEquals(1, controller.getAddCount());
        assertEquals(1, controller.getRemoveCount());
        assertFalse(controller.isTestListenerRegistered());
        assertFalse(source.isRunning());
        source.close();
    }

    @Test
    void selectsTunerByNonIdentifyingClassAndFailsClosedWhenUnavailable() throws Exception
    {
        String originalPreferred = System.getProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
        String originalClass = System.getProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY);
        ClassedTestTuner rtl = new ClassedTestTuner(TunerClass.RTL2832, "RTL test fixture");
        ClassedTestTuner airspy = new ClassedTestTuner(TunerClass.AIRSPY, "Airspy test fixture");

        try
        {
            System.clearProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
            System.setProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, "AIRSPY");

            try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
                new TunerSpectrumFrameSource.Configuration(DFTSize.FFT00512, 20), () -> List.of(rtl, airspy)))
            {
                ArrayBlockingQueue<SpectrumFrame> frames = new ArrayBlockingQueue<>(1);
                source.start(frames::offer);
                assertTrue(frames.poll(3, TimeUnit.SECONDS) != null);
                assertEquals("Airspy", source.getTargetLabel());
            }

            System.setProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, "HYDRASDR");

            try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
                new TunerSpectrumFrameSource.Configuration(DFTSize.FFT00512, 20), () -> List.of(rtl, airspy)))
            {
                assertThrows(IllegalStateException.class, () -> source.start(frame -> {}));
                assertFalse(source.isRunning());
            }
        }
        finally
        {
            restoreProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY, originalPreferred);
            restoreProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, originalClass);
        }
    }

    @Test
    void legacyClassSelectorFailsClosedWhenTwoReceiversShareTheClass()
    {
        String originalPreferred = System.getProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
        String originalClass = System.getProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY);
        ClassedTestTuner first = new ClassedTestTuner(TunerClass.AIRSPY, "first fixture");
        ClassedTestTuner second = new ClassedTestTuner(TunerClass.AIRSPY, "second fixture");

        try
        {
            System.clearProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
            System.setProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, "AIRSPY");

            try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
                TunerSpectrumFrameSource.Configuration.defaults(), () -> List.of(first, second)))
            {
                assertThrows(IllegalStateException.class, () -> source.start(frame -> {}));
                assertFalse(source.isRunning());
            }
        }
        finally
        {
            restoreProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY, originalPreferred);
            restoreProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, originalClass);
        }
    }

    @Test
    void coalescesZoomRequestsAndPublishesOnlyCroppedLatestRevision() throws Exception
    {
        String originalPreferred = System.getProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
        String originalClass = System.getProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY);
        ClassedTestTuner airspy = new ClassedTestTuner(TunerClass.AIRSPY, "secret fixture identity");
        BlockingQueue<SpectrumFrame> frames = new ArrayBlockingQueue<>(64);

        try
        {
            System.clearProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
            System.clearProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY);

            try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
                TunerSpectrumFrameSource.Configuration.defaults(), () -> List.of(airspy)))
            {
                String airspyId = targetId(source, "Airspy");
                assertTrue(airspyId.matches("TNR_[A-F0-9]{28}"));
                source.requestView(new InteractiveSpectrumFrameSource.ViewRequest(1, airspyId, null));
                source.start(frames::offer);
                SpectrumFrame full = frameForRevision(frames, 1);
                long center = full.getCenterFrequencyHz();
                long sampleRate = full.getSampleRateHz();

                source.requestView(new InteractiveSpectrumFrameSource.ViewRequest(2, airspyId,
                    centeredViewport(center, sampleRate / 2)));
                source.requestView(new InteractiveSpectrumFrameSource.ViewRequest(3, airspyId,
                    centeredViewport(center, sampleRate / 8)));

                SpectrumFrame zoomed = nextNewRevision(frames, 1);
                assertEquals(3, zoomed.getViewRevision(), "the superseded request must never publish");
                assertEquals(DFTSize.FFT32768.getSize(), zoomed.getFftSize());
                assertEquals(InteractiveSpectrumFrameSource.MAXIMUM_TRANSMITTED_BINS, zoomed.getBinCount());
                assertTrue(zoomed.getFirstBin() > 0);
                assertTrue(zoomed.getFirstBin() + zoomed.getBinCount() <= zoomed.getFftSize());
                assertTrue(containsCalculatedBin(zoomed),
                    "the first refined frame must contain a completed FFT, not the resize placeholder");
                assertEquals(0, source.getPublicationErrorCount());
            }
        }
        finally
        {
            restoreProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY, originalPreferred);
            restoreProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, originalClass);
        }
    }

    @Test
    void duplicateSameClassTunersRemainIndividuallySelectable() throws Exception
    {
        ClassedTestTuner first = new ClassedTestTuner(TunerClass.AIRSPY, "first secret identity");
        ClassedTestTuner second = new ClassedTestTuner(TunerClass.AIRSPY, "second secret identity");
        BlockingQueue<SpectrumFrame> frames = new ArrayBlockingQueue<>(64);

        try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
            TunerSpectrumFrameSource.Configuration.defaults(), () -> List.of(first, second)))
        {
            assertEquals(2, source.getTargets().size());
            String secondId = targetId(source, "Airspy 2");
            assertNotEquals(targetId(source, "Airspy 1"), secondId);
            source.requestView(new InteractiveSpectrumFrameSource.ViewRequest(1, secondId, null));
            source.start(frames::offer);
            frameForRevision(frames, 1);
            assertEquals(secondId, source.getAppliedView().targetId());
        }
    }

    @Test
    void stopsPublishingWhenTheActiveTunerIsNoLongerAvailable() throws Exception
    {
        ClassedTestTuner airspy = new ClassedTestTuner(TunerClass.AIRSPY, "Airspy liveness fixture");
        AtomicReference<List<Tuner>> availableTuners = new AtomicReference<>(List.of(airspy));
        BlockingQueue<SpectrumFrame> frames = new ArrayBlockingQueue<>(64);

        try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
            TunerSpectrumFrameSource.Configuration.defaults(), availableTuners::get))
        {
            source.start(frames::offer);
            assertTrue(frames.poll(3, TimeUnit.SECONDS) != null);

            availableTuners.set(List.of());
            awaitStopped(source);
            long publishedAtStop = source.getPublishedFrameCount();
            Thread.sleep(150);

            assertEquals(publishedAtStop, source.getPublishedFrameCount(),
                "a removed tuner must not repeat its last FFT indefinitely");
            assertEquals(0, source.getPublicationErrorCount());
        }
    }

    @Test
    void releasesASourceWhenAScheduledTargetSwitchFailsAndCanRestart() throws Exception
    {
        ClassedTestTuner airspy = new ClassedTestTuner(TunerClass.AIRSPY, "working Airspy fixture");
        RegisterThenThrowController failingController = new RegisterThenThrowController();
        RegisterThenThrowTuner failingTuner = new RegisterThenThrowTuner(failingController);
        BlockingQueue<SpectrumFrame> frames = new ArrayBlockingQueue<>(64);

        try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
            TunerSpectrumFrameSource.Configuration.defaults(), () -> List.of(airspy, failingTuner)))
        {
            String airspyId = targetId(source, "Airspy");
            String failingId = targetId(source, "Test");
            source.requestView(new InteractiveSpectrumFrameSource.ViewRequest(1, airspyId, null));
            source.start(frames::offer);
            frameForRevision(frames, 1);

            source.requestView(new InteractiveSpectrumFrameSource.ViewRequest(2, failingId, null));
            awaitPublicationError(source);
            awaitStopped(source);

            assertEquals(1, failingController.getAddCount());
            assertEquals(1, failingController.getRemoveCount());
            assertFalse(failingController.isTestListenerRegistered());
            assertTrue(source.getPublicationErrorCount() >= 1);

            source.requestView(new InteractiveSpectrumFrameSource.ViewRequest(3, airspyId, null));
            source.start(frames::offer);
            frameForRevision(frames, 3);
            assertTrue(source.isRunning());
        }
    }

    @Test
    void switchesOneSelectedReceiverAtATimeAndResetsToFullWidth() throws Exception
    {
        String originalPreferred = System.getProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
        String originalClass = System.getProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY);
        ClassedTestTuner airspy = new ClassedTestTuner(TunerClass.AIRSPY, "Airspy fixture");
        ClassedTestTuner rtl = new ClassedTestTuner(TunerClass.RTL2832, "RTL fixture");
        BlockingQueue<SpectrumFrame> frames = new ArrayBlockingQueue<>(64);

        try
        {
            System.clearProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
            System.clearProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY);

            try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
                TunerSpectrumFrameSource.Configuration.defaults(), () -> List.of(airspy, rtl)))
            {
                String airspyId = targetId(source, "Airspy");
                String rtlId = targetId(source, "RTL-2832");
                source.requestView(new InteractiveSpectrumFrameSource.ViewRequest(1, airspyId, null));
                source.start(frames::offer);
                SpectrumFrame airspyFrame = frameForRevision(frames, 1);
                source.requestView(new InteractiveSpectrumFrameSource.ViewRequest(2, rtlId, null));
                SpectrumFrame rtlFrame = frameForRevision(frames, 2);

                assertTrue(rtlFrame.getTargetGeneration() > airspyFrame.getTargetGeneration());
                assertEquals(DFTSize.FFT04096.getSize(), rtlFrame.getFftSize());
                assertEquals(0, rtlFrame.getFirstBin());
                assertEquals(rtlId, source.getAppliedView().targetId());
                assertEquals("RTL-2832", source.getAppliedView().targetLabel());
            }
        }
        finally
        {
            restoreProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY, originalPreferred);
            restoreProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, originalClass);
        }
    }

    private static InteractiveSpectrumFrameSource.Viewport centeredViewport(long center, long span)
    {
        return new InteractiveSpectrumFrameSource.Viewport(center - span / 2, center + span / 2);
    }

    private static String targetId(TunerSpectrumFrameSource source, String label)
    {
        return source.getTargets().stream().filter(target -> target.label().equals(label)).findFirst().orElseThrow().id();
    }

    private static SpectrumFrame frameForRevision(BlockingQueue<SpectrumFrame> frames, long revision)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);

        while(System.nanoTime() < deadline)
        {
            SpectrumFrame frame = frames.poll(250, TimeUnit.MILLISECONDS);

            if(frame != null && frame.getViewRevision() == revision)
            {
                return frame;
            }
        }

        throw new AssertionError("No spectrum frame arrived for revision " + revision);
    }

    private static SpectrumFrame nextNewRevision(BlockingQueue<SpectrumFrame> frames, long previousRevision)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);

        while(System.nanoTime() < deadline)
        {
            SpectrumFrame frame = frames.poll(250, TimeUnit.MILLISECONDS);

            if(frame != null && frame.getViewRevision() != previousRevision)
            {
                return frame;
            }
        }

        throw new AssertionError("No updated spectrum frame arrived");
    }

    private static void restoreProperty(String name, String value)
    {
        if(value == null)
        {
            System.clearProperty(name);
        }
        else
        {
            System.setProperty(name, value);
        }
    }

    private static boolean containsCalculatedBin(SpectrumFrame frame)
    {
        for(float bin: frame.getBins())
        {
            if(bin > -195.0f)
            {
                return true;
            }
        }

        return false;
    }

    private static void awaitStopped(TunerSpectrumFrameSource source) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);

        while(source.isRunning() && System.nanoTime() < deadline)
        {
            Thread.sleep(20);
        }

        assertFalse(source.isRunning(), "removed tuner was not released before timeout");
    }

    private static void awaitPublicationError(TunerSpectrumFrameSource source) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);

        while(source.getPublicationErrorCount() == 0 && System.nanoTime() < deadline)
        {
            Thread.sleep(20);
        }

        assertTrue(source.getPublicationErrorCount() > 0,
            "scheduled receiver transfer failure was not observed before timeout");
    }

    private static void awaitDelivery(AtomicInteger deliveries) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while(deliveries.get() == 0 && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }

        assertTrue(deliveries.get() > 0, "calculated FFT was not delivered before timeout");
    }

    private static class ClassedTestTuner extends TestTuner
    {
        private final TunerClass mTunerClass;
        private final String mPreferredName;

        private ClassedTestTuner(TunerClass tunerClass, String preferredName)
        {
            super(null);
            mTunerClass = tunerClass;
            mPreferredName = preferredName;
        }

        @Override
        public TunerClass getTunerClass()
        {
            return mTunerClass;
        }

        @Override
        public String getPreferredName()
        {
            return mPreferredName;
        }
    }

    private static class RegisterThenThrowController extends TestTunerController
    {
        private int mAddCount;
        private int mRemoveCount;
        private boolean mTestListenerRegistered;

        @Override
        public void addBufferListener(Listener<INativeBuffer> listener)
        {
            super.addBufferListener(listener);
            mAddCount++;
            mTestListenerRegistered = true;
            throw new IllegalStateException("simulated transfer startup failure");
        }

        @Override
        public void removeBufferListener(Listener<INativeBuffer> listener)
        {
            super.removeBufferListener(listener);
            mRemoveCount++;
            mTestListenerRegistered = false;
        }

        private int getAddCount()
        {
            return mAddCount;
        }

        private int getRemoveCount()
        {
            return mRemoveCount;
        }

        private boolean isTestListenerRegistered()
        {
            return mTestListenerRegistered;
        }
    }

    private static class RegisterThenThrowTuner extends Tuner
    {
        private RegisterThenThrowTuner(RegisterThenThrowController controller)
        {
            super(controller, null);
            setChannelSourceManager(new TestPolyphaseChannelSourceManager(controller));
        }

        @Override
        public int getMaximumUSBBitsPerSecond()
        {
            return 0;
        }

        @Override
        public String getUniqueID()
        {
            return "register-then-throw";
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public TunerType getTunerType()
        {
            return TunerType.TEST;
        }

        @Override
        public String getPreferredName()
        {
            return "Register Then Throw";
        }

        @Override
        public double getSampleSize()
        {
            return 16.0;
        }
    }
}
