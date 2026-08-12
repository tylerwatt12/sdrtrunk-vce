/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class TunerDiagnosticServiceTest
{
    @Test
    void remainsInertUntilAViewerOpensAndDoesNotExposeHardwareIdentity()
    {
        FakeController controller = new FakeController(851_000_000L, 10_000_000.0);
        Object hardwareIdentity = new Object()
        {
            @Override
            public String toString()
            {
                return "serial-SECRET-192.168.1.20";
            }
        };
        AtomicReference<List<TunerDiagnosticService.AvailableTarget>> available = new AtomicReference<>(List.of(
            target(hardwareIdentity, TunerClass.AIRSPY, controller, 4)));
        AtomicInteger enumerations = new AtomicInteger();
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = new TunerDiagnosticService(() ->
        {
            enumerations.incrementAndGet();
            return available.get();
        }, processors);

        assertEquals(0, enumerations.get());
        assertEquals(0, controller.addCount.get());
        assertEquals(0, processors.createCount.get());

        TunerDiagnosticService.Target first = service.targets().getFirst();
        TunerDiagnosticService.Target again = service.targets().getFirst();
        assertEquals(first.targetId(), again.targetId());
        assertEquals("Airspy 1", first.label());
        assertFalse(first.targetId().contains("SECRET"));
        assertFalse(first.label().contains("SECRET"));
        assertEquals(851_000_000L, first.centerFrequencyHz());
        assertEquals(10_000_000L, first.sampleRateHz());
        assertEquals(4, first.activeChannelCount());
        assertEquals(0, controller.addCount.get());
        assertEquals(0, processors.createCount.get());
        assertEquals(2, enumerations.get());

        service.close();
        assertEquals(0, controller.removeCount.get());
        assertEquals(0, processors.closeCount.get());
    }

    @Test
    void excludesTunersThatCouldRequireDiagnosticsToStartSampleTransfer()
    {
        FakeController noChannels = new FakeController(100_000_000L, 2_500_000.0);
        FakeController noExistingListener = new FakeController(200_000_000L, 2_500_000.0, false);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, noChannels, 0),
            target(new Object(), TunerClass.RTL2832, noExistingListener, 1)), processors);

        assertTrue(service.targets().isEmpty());
        assertEquals(0, noChannels.addCount.get());
        assertEquals(0, noExistingListener.addCount.get());
        assertEquals(0, processors.createCount.get());
        service.close();
    }

    @Test
    void sharesOneProducerAndImmediatelyDetachesAfterTheLastViewer()
    {
        FakeController controller = new FakeController(773_106_250L, 10_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(target(new Object(), TunerClass.AIRSPY, controller, 2)),
            processors);
        String targetId = service.targets().getFirst().targetId();

        TunerDiagnosticService.Session first = service.tryOpen(targetId).session();
        TunerDiagnosticService.Session second = service.tryOpen(targetId).session();
        assertEquals(1, controller.addCount.get());
        assertEquals(1, processors.createCount.get());
        assertEquals(1, service.activeProducerCount());
        assertEquals(2, service.activeSessionCount());
        assertEquals(TunerDiagnosticService.FFT_SIZE, first.state().fftSize());
        assertEquals(TunerDiagnosticService.FRAMES_PER_SECOND, first.state().framesPerSecond());

        first.close();
        assertEquals(0, controller.removeCount.get());
        assertEquals(0, processors.closeCount.get());
        assertEquals(1, service.activeSessionCount());

        second.close();
        assertEquals(1, controller.removeCount.get());
        assertEquals(1, processors.closeCount.get());
        assertEquals(0, service.activeProducerCount());
        assertEquals(0, service.activeSessionCount());

        TunerDiagnosticService.Session reopened = service.tryOpen(targetId).session();
        assertEquals(2, controller.addCount.get());
        assertEquals(2, processors.createCount.get());
        reopened.close();
        service.close();
    }

    @Test
    void closesAllActiveSessionsWithoutClosingTheService()
    {
        FakeController firstController = new FakeController(150_000_000L, 2_500_000.0);
        FakeController secondController = new FakeController(250_000_000L, 2_400_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, firstController, 1),
            target(new Object(), TunerClass.RTL2832, secondController, 1)), processors);
        List<TunerDiagnosticService.Target> targets = service.targets();
        TunerDiagnosticService.Session first = service.tryOpen(targets.get(0).targetId()).session();
        TunerDiagnosticService.Session firstShared = service.tryOpen(targets.get(0).targetId()).session();
        TunerDiagnosticService.Session second = service.tryOpen(targets.get(1).targetId()).session();

        service.closeActiveSessions();

        assertTrue(first.isClosed());
        assertTrue(firstShared.isClosed());
        assertTrue(second.isClosed());
        assertEquals(1, firstController.removeCount.get());
        assertEquals(1, secondController.removeCount.get());
        assertEquals(2, processors.closeCount.get());
        assertEquals(0, service.activeProducerCount());
        assertEquals(0, service.activeSessionCount());

        TunerDiagnosticService.Session reopened = service.tryOpen(targets.get(0).targetId()).session();
        assertFalse(reopened.isClosed());
        assertEquals(2, firstController.addCount.get());
        reopened.close();
        service.close();
    }

    @Test
    void boundsDistinctProducersAndTotalSessions()
    {
        FakeProcessorFactory processors = new FakeProcessorFactory();
        List<TunerDiagnosticService.AvailableTarget> threeTargets = List.of(
            target(new Object(), TunerClass.AIRSPY, new FakeController(100_000_000L, 2_500_000.0), 1),
            target(new Object(), TunerClass.RTL2832, new FakeController(200_000_000L, 2_400_000.0), 1),
            target(new Object(), TunerClass.HACKRF, new FakeController(300_000_000L, 8_000_000.0), 1));
        TunerDiagnosticService service = service(threeTargets, processors);
        List<TunerDiagnosticService.Target> targets = service.targets();

        assertEquals(TunerDiagnosticService.OpenStatus.OPEN, service.tryOpen(targets.get(0).targetId()).status());
        assertEquals(TunerDiagnosticService.OpenStatus.OPEN, service.tryOpen(targets.get(1).targetId()).status());
        assertEquals(TunerDiagnosticService.OpenStatus.BUSY, service.tryOpen(targets.get(2).targetId()).status());
        assertEquals(TunerDiagnosticService.MAXIMUM_PRODUCERS, service.activeProducerCount());
        service.close();

        FakeController controller = new FakeController(400_000_000L, 2_500_000.0);
        TunerDiagnosticService sessionBoundService = service(
            List.of(target(new Object(), TunerClass.AIRSPY, controller, 1)), new FakeProcessorFactory());
        String targetId = sessionBoundService.targets().getFirst().targetId();

        for(int x = 0; x < TunerDiagnosticService.MAXIMUM_SESSIONS; x++)
        {
            assertEquals(TunerDiagnosticService.OpenStatus.OPEN, sessionBoundService.tryOpen(targetId).status());
        }

        assertEquals(TunerDiagnosticService.OpenStatus.BUSY, sessionBoundService.tryOpen(targetId).status());
        assertEquals(1, controller.addCount.get());
        sessionBoundService.close();
        assertEquals(1, controller.removeCount.get());
    }

    @Test
    void refusesMissingTargetsAndCleansUpFailedAttachment()
    {
        FakeController controller = new FakeController(500_000_000L, 2_500_000.0);
        AtomicReference<List<TunerDiagnosticService.AvailableTarget>> available = new AtomicReference<>(List.of(
            target(new Object(), TunerClass.RSP, controller, 1)));
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = new TunerDiagnosticService(available::get, processors);
        String targetId = service.targets().getFirst().targetId();
        available.set(List.of());

        assertEquals(TunerDiagnosticService.OpenStatus.NOT_FOUND, service.tryOpen(targetId).status());
        assertEquals(0, controller.addCount.get());
        service.close();

        FakeController failingController = new FakeController(600_000_000L, 2_500_000.0);
        failingController.failAdd = true;
        FakeProcessorFactory failingProcessors = new FakeProcessorFactory();
        TunerDiagnosticService failingService = service(List.of(
            target(new Object(), TunerClass.RSP, failingController, 1)), failingProcessors);
        String failingTarget = failingService.targets().getFirst().targetId();

        assertEquals(TunerDiagnosticService.OpenStatus.UNAVAILABLE, failingService.tryOpen(failingTarget).status());
        assertEquals(1, failingController.addCount.get());
        assertEquals(1, failingController.removeCount.get());
        assertEquals(1, failingProcessors.closeCount.get());
        assertEquals(0, failingService.activeProducerCount());
        failingService.close();
    }

    @Test
    void releasesAnOpenProducerWhenItsTunerDisappears()
    {
        FakeController controller = new FakeController(650_000_000L, 2_500_000.0);
        AtomicReference<List<TunerDiagnosticService.AvailableTarget>> available = new AtomicReference<>(List.of(
            target(new Object(), TunerClass.RSP, controller, 1)));
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = new TunerDiagnosticService(available::get, processors);
        TunerDiagnosticService.Session session = service.tryOpen(service.targets().getFirst().targetId()).session();
        available.set(List.of());

        assertEquals("unavailable", session.state().state());
        assertTrue(session.isClosed());
        assertEquals(1, controller.removeCount.get());
        assertEquals(1, processors.closeCount.get());
        assertEquals(0, service.activeProducerCount());
        assertEquals(0, service.activeSessionCount());
        service.close();
    }

    @Test
    void capturesObservationTimeAtTheTunerCallbackBoundary()
    {
        FakeController controller = new FakeController(700_000_000L, 2_500_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.RECORDING_TUNER, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        service.tryOpen(targetId);
        long before = System.currentTimeMillis();

        controller.emit(new EmptyNativeBuffer(123L));

        long after = System.currentTimeMillis();
        assertTrue(processors.lastObservedAtMs.get() >= before);
        assertTrue(processors.lastObservedAtMs.get() <= after);
        assertNotEquals(123L, processors.lastObservedAtMs.get());
        service.close();
    }

    @Test
    void nativeCallbackNeverWaitsForTheTunerControllerLock() throws Exception
    {
        FakeController controller = new FakeController(705_000_000L, 2_500_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.RECORDING_TUNER, controller, 1)), processors);
        service.tryOpen(service.targets().getFirst().targetId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        controller.getLock().lock();

        try
        {
            Future<?> callback = executor.submit(() -> controller.emit(new EmptyNativeBuffer(1L)));
            callback.get(250, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue(processors.lastObservedAtMs.get() > 0);
        }
        finally
        {
            controller.getLock().unlock();
            executor.shutdownNow();
            service.close();
        }
    }

    @Test
    void capturesTuningMetadataWithEachSampleBuffer()
    {
        FakeController controller = new FakeController(700_000_000L, 2_500_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.RECORDING_TUNER, controller, 1)), processors);
        service.tryOpen(service.targets().getFirst().targetId());

        controller.emit(new EmptyNativeBuffer(1L));
        assertEquals(700_000_000L, processors.lastCenterFrequencyHz.get());
        assertEquals(2_500_000L, processors.lastSampleRateHz.get());

        controller.retune(710_000_000L, 3_200_000.0);
        controller.emit(new EmptyNativeBuffer(2L));
        assertEquals(710_000_000L, processors.lastCenterFrequencyHz.get());
        assertEquals(3_200_000L, processors.lastSampleRateHz.get());
        service.close();
    }

    @Test
    void fansOutOneEncodedFrameInstanceAndDropsObsoleteFrames() throws Exception
    {
        FakeController controller = new FakeController(800_000_000L, 10_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session first = service.tryOpen(targetId).session();
        TunerDiagnosticService.Session second = service.tryOpen(targetId).session();
        float[] values = new float[TunerDiagnosticService.FFT_SIZE];
        TunerDiagnosticService.FftResult shared = new TunerDiagnosticService.FftResult(System.currentTimeMillis(),
            800_000_000L, 10_000_000L, TunerDiagnosticService.FFT_SIZE, values);

        processors.publish(shared);

        DiagnosticStreamFrame firstShared = first.poll(Duration.ZERO);
        assertSame(firstShared, second.poll(Duration.ZERO));

        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 800_000_000L,
            10_000_000L, TunerDiagnosticService.FFT_SIZE, values));
        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 800_000_000L,
            10_000_000L, TunerDiagnosticService.FFT_SIZE, values));

        assertEquals(3, first.poll(Duration.ZERO).sequence());
        first.close();
        second.close();
        service.close();
    }

    @Test
    void sharesOneFixedSourceFftAcrossViewportsAndMaxPoolsWideViews() throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 8_192_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 3)), processors);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session full = service.tryOpen(targetId).session();
        TunerDiagnosticService.Session twoTimes = service.tryOpen(targetId,
            new TunerDiagnosticService.Viewport(97_952_000L, 102_048_000L)).session();
        TunerDiagnosticService.Session eightTimes = service.tryOpen(targetId,
            new TunerDiagnosticService.Viewport(99_488_000L, 100_512_000L)).session();

        assertEquals(1, controller.addCount.get());
        assertEquals(1, processors.createCount.get());
        assertEquals(TunerDiagnosticService.FFT_SIZE, processors.fftSize.get());

        TunerDiagnosticService.State fullState = full.state();
        TunerDiagnosticService.State eightTimesState = eightTimes.state();
        assertEquals(fullState.generation(), eightTimesState.generation());
        assertNotEquals(fullState.revision(), eightTimesState.revision());

        float[] bins = new float[TunerDiagnosticService.FFT_SIZE];
        java.util.Arrays.fill(bins, -120.0f);
        bins[6_173] = -12.5f;
        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
            8_192_000L, TunerDiagnosticService.FFT_SIZE, bins));

        DiagnosticStreamFrame fullFrame = full.poll(Duration.ZERO);
        DiagnosticStreamFrame twoFrame = twoTimes.poll(Duration.ZERO);
        DiagnosticStreamFrame eightFrame = eightTimes.poll(Duration.ZERO);
        assertEquals(0, fullFrame.firstBin());
        assertEquals(16_384, fullFrame.sourceBinCount());
        assertEquals(4_096, fullFrame.valueCount());
        assertEquals(4_096, twoFrame.firstBin());
        assertEquals(8_192, twoFrame.sourceBinCount());
        assertEquals(4_096, twoFrame.valueCount());
        assertEquals(7_168, eightFrame.firstBin());
        assertEquals(2_048, eightFrame.sourceBinCount());
        assertEquals(2_048, eightFrame.valueCount());
        assertEquals(fullFrame.sequence(), twoFrame.sequence());
        assertEquals(twoFrame.sequence(), eightFrame.sequence());

        eightTimes.close();
        twoTimes.close();
        assertEquals(TunerDiagnosticService.FFT_SIZE, processors.fftSize.get());
        assertEquals(0, controller.removeCount.get());
        full.close();
        assertEquals(1, controller.removeCount.get());
        service.close();
    }

    @Test
    void boundsViewportProjectionAgainstTheFixedSourceFft()
    {
        long sampleRate = 8_192_000L;
        TunerDiagnosticService.FrameLayout full = TunerDiagnosticService.frameLayout(100_000_000L,
            sampleRate, TunerDiagnosticService.FFT_SIZE, null);
        assertEquals(0, full.firstBin());
        assertEquals(16_384, full.sourceBinCount());
        assertEquals(4_096, full.transmittedBinCount());

        TunerDiagnosticService.FrameLayout outside = TunerDiagnosticService.frameLayout(100_000_000L,
            sampleRate, TunerDiagnosticService.FFT_SIZE, new TunerDiagnosticService.Viewport(1, 2));
        assertEquals(0, outside.firstBin());
        assertEquals(2_048, outside.sourceBinCount());
        assertEquals(2_048, outside.transmittedBinCount());
    }

    @Test
    void recalculatesZoomedSessionMetadataAfterRetune() throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 8_192_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session session = service.tryOpen(targetId,
            new TunerDiagnosticService.Viewport(99_488_000L, 100_512_000L)).session();
        TunerDiagnosticService.State before = session.state();

        controller.retune(200_000_000L, 4_096_000.0);
        TunerDiagnosticService.State after = session.state();

        assertTrue(after.revision() > before.revision());
        assertEquals(200_000_000L, after.centerFrequencyHz());
        assertEquals(4_096_000L, after.sampleRateHz());
        assertEquals(16_384, after.fftSize());
        assertEquals(197_952_000.0, after.visibleStartFrequencyHz());
        assertEquals(198_976_000.0, after.visibleEndFrequencyHz());

        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 200_000_000L,
            4_096_000L, 16_384, new float[16_384]));
        DiagnosticStreamFrame frame = session.poll(Duration.ZERO);
        assertEquals(200_000_000L, frame.centerFrequencyHz());
        assertEquals(4_096_000, frame.sampleRateHz());
        assertEquals(16_384, frame.fftSize());
        assertEquals(after.firstBin(), frame.firstBin());
        assertEquals(after.sourceBinCount(), frame.sourceBinCount());
        session.close();
        service.close();
    }

    @Test
    void viewportChangesNeverReconfigureTheSourceFft() throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 8_192_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session full = service.tryOpen(targetId).session();
        TunerDiagnosticService.OpenResult zoomedResult = service.tryOpen(targetId,
            new TunerDiagnosticService.Viewport(99_488_000L, 100_512_000L));
        assertEquals(TunerDiagnosticService.OpenStatus.OPEN, zoomedResult.status());
        assertEquals(2, service.activeSessionCount());
        assertEquals(1, service.activeProducerCount());
        assertEquals(1, processors.createCount.get());
        assertEquals(TunerDiagnosticService.FFT_SIZE, processors.fftSize.get());

        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
            8_192_000L, TunerDiagnosticService.FFT_SIZE, new float[TunerDiagnosticService.FFT_SIZE]));
        assertEquals(TunerDiagnosticService.FFT_SIZE, full.poll(Duration.ZERO).fftSize());
        assertEquals(TunerDiagnosticService.FFT_SIZE, zoomedResult.session().poll(Duration.ZERO).fftSize());
        zoomedResult.session().close();
        full.close();
        service.close();
    }

    @Test
    void updatesOneSessionViewportWithoutReattachingOrRebuildingTheProducer() throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 8_192_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session session = service.tryOpen(targetId).session();
        long generation = session.state().generation();

        for(int x = 0; x < 100; x++)
        {
            long halfSpan = 128_000L + (x * 1_000L);
            session.updateViewport(new TunerDiagnosticService.Viewport(100_000_000L - halfSpan,
                100_000_000L + halfSpan));
        }

        TunerDiagnosticService.State state = session.state();
        assertEquals(generation, state.generation());
        assertEquals(1, controller.addCount.get());
        assertEquals(0, controller.removeCount.get());
        assertEquals(1, processors.createCount.get());
        assertEquals(0, processors.closeCount.get());
        assertEquals(1, service.activeProducerCount());
        assertEquals(1, service.activeSessionCount());

        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
            8_192_000L, TunerDiagnosticService.FFT_SIZE, new float[TunerDiagnosticService.FFT_SIZE]));
        DiagnosticStreamFrame frame = session.poll(Duration.ZERO);
        assertEquals(state.firstBin(), frame.firstBin());
        assertEquals(state.sourceBinCount(), frame.sourceBinCount());

        session.close();
        assertEquals(1, controller.removeCount.get());
        assertEquals(1, processors.closeCount.get());
        service.close();
    }

    @Test
    void stateRevisionChangesOnlyWhenThatSessionStateChanges()
    {
        FakeController controller = new FakeController(100_000_000L, 8_192_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session first = service.tryOpen(targetId).session();
        TunerDiagnosticService.Session second = service.tryOpen(targetId).session();

        try
        {
            long firstRevision = first.state().revision();
            long secondRevision = second.state().revision();
            assertEquals(firstRevision, first.state().revision());
            assertEquals(secondRevision, second.state().revision());

            first.updateViewport(new TunerDiagnosticService.Viewport(99_500_000L, 100_500_000L));
            assertTrue(first.state().revision() > firstRevision);
            assertEquals(secondRevision, second.state().revision(),
                "one viewer's zoom must not create state churn for another viewer");
        }
        finally
        {
            second.close();
            first.close();
            service.close();
        }
    }

    @Test
    void rapidViewportUpdatesCannotStrandPublishedFrames() throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 8_192_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session session = service.tryOpen(targetId).session();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        float[] values = new float[TunerDiagnosticService.FFT_SIZE];

        try
        {
            Future<?> updater = executor.submit(() ->
            {
                await(start);

                for(int x = 0; x < 500; x++)
                {
                    long halfSpan = 128_000L + x;
                    session.updateViewport(new TunerDiagnosticService.Viewport(100_000_000L - halfSpan,
                        100_000_000L + halfSpan));
                }
            });
            Future<?> publisher = executor.submit(() ->
            {
                await(start);

                for(int x = 0; x < 500; x++)
                {
                    processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(),
                        100_000_000L, 8_192_000L, TunerDiagnosticService.FFT_SIZE, values));
                }
            });

            start.countDown();
            updater.get(5, TimeUnit.SECONDS);
            publisher.get(5, TimeUnit.SECONDS);
            TunerDiagnosticService.Viewport finalViewport =
                new TunerDiagnosticService.Viewport(99_500_000L, 100_500_000L);
            session.updateViewport(finalViewport);
            processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
                8_192_000L, TunerDiagnosticService.FFT_SIZE, values));

            DiagnosticStreamFrame frame = session.poll(Duration.ofSeconds(1));
            TunerDiagnosticService.State state = session.state();
            assertTrue(frame != null);
            assertEquals(state.firstBin(), frame.firstBin());
            assertEquals(state.sourceBinCount(), frame.sourceBinCount());
            assertEquals(1, controller.addCount.get());
            assertEquals(0, controller.removeCount.get());
            assertEquals(1, processors.createCount.get());
        }
        finally
        {
            executor.shutdownNow();
            session.close();
            service.close();
        }
    }

    @Test
    void rejectsAnOldLayoutFrameAfterAdvertisingANewViewport() throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 8_192_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session session = service.tryOpen(targetId).session();
        float[] values = new float[TunerDiagnosticService.FFT_SIZE];

        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
            8_192_000L, TunerDiagnosticService.FFT_SIZE, values));
        session.updateViewport(new TunerDiagnosticService.Viewport(99_500_000L, 100_500_000L));
        TunerDiagnosticService.State state = session.state();

        assertNull(session.poll(Duration.ZERO));

        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
            8_192_000L, TunerDiagnosticService.FFT_SIZE, values));
        DiagnosticStreamFrame current = session.poll(Duration.ofSeconds(1));
        assertTrue(current != null);
        assertEquals(state.firstBin(), current.firstBin());
        assertEquals(state.sourceBinCount(), current.sourceBinCount());

        session.close();
        service.close();
    }

    @Test
    void nativeCallbackCarriesItsEntryEpochAcrossAConcurrentRetune() throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 8_192_000.0);
        AtomicLong configuration = new AtomicLong(1);
        AtomicLong receivedConfiguration = new AtomicLong();
        CountDownLatch configurationCaptured = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        TunerDiagnosticService.ProcessorFactory factory = (target, consumer) ->
            new TunerDiagnosticService.FrameProcessor()
            {
                @Override
                public void receive(INativeBuffer buffer, long observedAtEpochMs, long ingressConfiguration)
                {
                    receivedConfiguration.set(ingressConfiguration);
                    received.countDown();
                }

                @Override
                public long configuration()
                {
                    long captured = configuration.get();
                    configurationCaptured.countDown();
                    await(releaseCallback);
                    return captured;
                }

                @Override
                public void updateMetadata(long centerFrequencyHz, long sampleRateHz)
                {
                    configuration.incrementAndGet();
                }

                @Override
                public void close()
                {
                }
            };
        List<TunerDiagnosticService.AvailableTarget> targets = List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1));
        TunerDiagnosticService service = new TunerDiagnosticService(() -> targets, factory);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session session = service.tryOpen(targetId).session();
        long entryConfiguration = configuration.get();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try
        {
            Future<?> callback = executor.submit(() -> controller.emit(new EmptyNativeBuffer(111)));
            assertTrue(configurationCaptured.await(1, TimeUnit.SECONDS));
            controller.retune(101_000_000L, 10_000_000.0);
            assertTrue(configuration.get() > 1);
            releaseCallback.countDown();
            callback.get(1, TimeUnit.SECONDS);
            assertTrue(received.await(1, TimeUnit.SECONDS));
            assertEquals(entryConfiguration, receivedConfiguration.get(),
                "a pre-retune buffer must retain the configuration captured at callback entry");
        }
        finally
        {
            releaseCallback.countDown();
            executor.shutdownNow();
            session.close();
            service.close();
        }
    }

    @Test
    void keepsAttachAndDetachBalancedDuringConcurrentPublicationAndClosure() throws Exception
    {
        FakeController controller = new FakeController(900_000_000L, 10_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        service.tryOpen(targetId);
        float[] values = new float[TunerDiagnosticService.FFT_SIZE];
        TunerDiagnosticService.FftResult frame = new TunerDiagnosticService.FftResult(System.currentTimeMillis(),
            900_000_000L, 10_000_000L, TunerDiagnosticService.FFT_SIZE, values);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);

        try
        {
            Future<?> publisher = executor.submit(() ->
            {
                await(start);

                for(int x = 0; x < 500; x++)
                {
                    processors.publish(frame);
                }
            });
            Future<?> viewer = executor.submit(() ->
            {
                await(start);

                for(int x = 0; x < 100; x++)
                {
                    TunerDiagnosticService.OpenResult result = service.tryOpen(targetId);

                    if(result.session() != null)
                    {
                        result.session().close();
                    }
                }
            });
            Future<?> closer = executor.submit(() ->
            {
                await(start);

                for(int x = 0; x < 100; x++)
                {
                    service.closeActiveSessions();
                }
            });
            start.countDown();
            publisher.get();
            viewer.get();
            closer.get();
        }
        finally
        {
            service.close();
            executor.shutdownNow();
        }

        assertEquals(controller.addCount.get(), controller.removeCount.get());
        assertEquals(processors.createCount.get(), processors.closeCount.get());
        assertEquals(0, service.activeProducerCount());
        assertEquals(0, service.activeSessionCount());
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await();
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test was interrupted", exception);
        }
    }

    private static TunerDiagnosticService service(List<TunerDiagnosticService.AvailableTarget> targets,
                                                  FakeProcessorFactory processors)
    {
        return new TunerDiagnosticService(() -> targets, processors);
    }

    private static TunerDiagnosticService.AvailableTarget target(Object identity, TunerClass tunerClass,
                                                                  TunerController controller, int channelCount)
    {
        return new TunerDiagnosticService.AvailableTarget(identity, tunerClass, controller, () -> channelCount);
    }

    private static final class FakeProcessorFactory implements TunerDiagnosticService.ProcessorFactory
    {
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicLong lastObservedAtMs = new AtomicLong();
        private final AtomicLong lastCenterFrequencyHz = new AtomicLong();
        private final AtomicLong lastSampleRateHz = new AtomicLong();
        private final AtomicInteger fftSize = new AtomicInteger();
        private final AtomicReference<Consumer<TunerDiagnosticService.FftResult>> consumer = new AtomicReference<>();

        @Override
        public TunerDiagnosticService.FrameProcessor create(TunerDiagnosticService.TargetSnapshot target,
                                                             Consumer<TunerDiagnosticService.FftResult> consumer)
        {
            createCount.incrementAndGet();
            fftSize.set(TunerDiagnosticService.FFT_SIZE);
            this.consumer.set(consumer);
            return new TunerDiagnosticService.FrameProcessor()
            {
                @Override
                public void receive(INativeBuffer buffer, long observedAtEpochMs, long ingressConfiguration)
                {
                    lastObservedAtMs.set(observedAtEpochMs);
                }

                @Override
                public long configuration()
                {
                    return 1;
                }

                @Override
                public void updateMetadata(long centerFrequencyHz, long sampleRateHz)
                {
                    lastCenterFrequencyHz.set(centerFrequencyHz);
                    lastSampleRateHz.set(sampleRateHz);
                }

                @Override
                public void close()
                {
                    closeCount.incrementAndGet();
                }
            };
        }

        private void publish(TunerDiagnosticService.FftResult frame)
        {
            consumer.get().accept(frame);
        }
    }

    private static final class FakeController extends TunerController
    {
        private volatile long mFrequency;
        private volatile double mSampleRate;
        private final AtomicInteger addCount = new AtomicInteger();
        private final AtomicInteger removeCount = new AtomicInteger();
        private final boolean mExistingListener;
        private volatile Listener<INativeBuffer> mListener;
        private volatile ISourceEventProcessor mSourceEventProcessor;
        private boolean failAdd;

        private FakeController(long frequency, double sampleRate)
        {
            this(frequency, sampleRate, true);
        }

        private FakeController(long frequency, double sampleRate, boolean existingListener)
        {
            super(new NoOpTunerErrorListener());
            mFrequency = frequency;
            mSampleRate = sampleRate;
            mExistingListener = existingListener;
        }

        @Override
        public void start() throws SourceException
        {
        }

        @Override
        public void stop()
        {
        }

        @Override
        public TunerType getTunerType()
        {
            return TunerType.TEST;
        }

        @Override
        public int getBufferSampleCount()
        {
            return 2_048;
        }

        @Override
        public long getFrequency()
        {
            return mFrequency;
        }

        @Override
        public double getSampleRate()
        {
            return mSampleRate;
        }

        @Override
        public long getTunedFrequency()
        {
            return mFrequency;
        }

        @Override
        public void setTunedFrequency(long frequency)
        {
        }

        @Override
        public double getCurrentSampleRate()
        {
            return mSampleRate;
        }

        @Override
        public void addBufferListener(Listener<INativeBuffer> listener)
        {
            addCount.incrementAndGet();
            mListener = listener;

            if(failAdd)
            {
                throw new IllegalStateException("Test attachment failure");
            }
        }

        @Override
        public void removeBufferListener(Listener<INativeBuffer> listener)
        {
            removeCount.incrementAndGet();

            if(mListener == listener)
            {
                mListener = null;
            }
        }

        @Override
        public boolean hasBufferListeners()
        {
            return mExistingListener || mListener != null;
        }

        @Override
        public void addListener(ISourceEventProcessor processor)
        {
            mSourceEventProcessor = processor;
        }

        @Override
        public void removeListener(ISourceEventProcessor processor)
        {
            if(mSourceEventProcessor == processor)
            {
                mSourceEventProcessor = null;
            }
        }

        private void emit(INativeBuffer buffer)
        {
            Listener<INativeBuffer> listener = mListener;

            if(listener != null)
            {
                listener.receive(buffer);
            }
        }

        private void retune(long frequency, double sampleRate)
        {
            getLock().lock();

            try
            {
                mFrequency = frequency;
                mSampleRate = sampleRate;
                ISourceEventProcessor processor = mSourceEventProcessor;

                if(processor != null)
                {
                    processor.process(SourceEvent.frequencyChange(null, frequency));
                    processor.process(SourceEvent.sampleRateChange(sampleRate));
                }
            }
            catch(SourceException exception)
            {
                throw new IllegalStateException(exception);
            }
            finally
            {
                getLock().unlock();
            }
        }
    }

    private static final class NoOpTunerErrorListener implements ITunerErrorListener
    {
        @Override
        public void setErrorMessage(String errorMessage)
        {
        }

        @Override
        public void tunerRemoved()
        {
        }
    }

    private record EmptyNativeBuffer(long getTimestamp) implements INativeBuffer
    {
        @Override
        public Iterator<ComplexSamples> iterator()
        {
            return List.<ComplexSamples>of().iterator();
        }

        @Override
        public Iterator<InterleavedComplexSamples> iteratorInterleaved()
        {
            return List.<InterleavedComplexSamples>of().iterator();
        }

        @Override
        public int sampleCount()
        {
            return 0;
        }
    }
}
