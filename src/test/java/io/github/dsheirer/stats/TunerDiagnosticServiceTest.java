/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
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
    void remainsInertUntilAViewerOpensAndShowsTunerNameAndSerial()
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
            target(hardwareIdentity, TunerClass.AIRSPY, "Airspy R2", "A1B2C3D4", controller, 4)));
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
        assertEquals("Airspy R2 · A1B2C3D4", first.label());
        assertEquals("Airspy R2", first.name());
        assertEquals("A1B2C3D4", first.serial());
        assertFalse(first.targetId().contains("SECRET"));
        assertEquals(851_000_000L, first.centerFrequencyHz());
        assertEquals(10_000_000L, first.sampleRateHz());
        assertEquals(4, first.activeChannelCount());
        assertEquals(0, controller.addCount.get());
        assertEquals(0, processors.createCount.get());

        service.close();
        assertEquals(0, controller.removeCount.get());
        assertEquals(0, processors.closeCount.get());
    }

    @Test
    void listsEnabledIdleTunersAndStartsSamplesOnlyWhenSelected()
    {
        FakeController noChannels = new FakeController(100_000_000L, 2_500_000.0);
        FakeController noExistingListener = new FakeController(200_000_000L, 2_500_000.0, false);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, noChannels, 0),
            target(new Object(), TunerClass.RTL2832, noExistingListener, 1)), processors);

        List<TunerDiagnosticService.Target> targets = service.targets();
        assertEquals(2, targets.size());
        assertEquals(0, targets.getFirst().activeChannelCount());
        assertEquals(0, noChannels.addCount.get());
        assertEquals(0, noExistingListener.addCount.get());
        assertEquals(0, processors.createCount.get());

        TunerDiagnosticService.Session session = service.tryOpen(targets.getFirst().targetId()).session();
        assertNotNull(session);
        assertEquals(1, noChannels.addCount.get());
        assertEquals(1, processors.createCount.get());
        session.close();
        assertEquals(1, noChannels.removeCount.get());
        service.close();
    }

    @Test
    void permitsExactlyOneSessionAndDetachesItImmediately()
    {
        FakeController firstController = new FakeController(773_106_250L, 10_000_000.0);
        FakeController secondController = new FakeController(851_000_000L, 8_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, firstController, 2),
            target(new Object(), TunerClass.RTL2832, secondController, 1)), processors);
        List<TunerDiagnosticService.Target> targets = service.targets();

        TunerDiagnosticService.OpenResult first = service.tryOpen(targets.get(0).targetId());
        assertEquals(TunerDiagnosticService.OpenStatus.OPEN, first.status());
        assertEquals(TunerDiagnosticService.OpenStatus.BUSY,
            service.tryOpen(targets.get(0).targetId()).status());
        assertEquals(TunerDiagnosticService.OpenStatus.BUSY,
            service.tryOpen(targets.get(1).targetId()).status());
        assertEquals(1, service.activeProducerCount());
        assertEquals(1, service.activeSessionCount());
        assertEquals("balanced", first.session().state().profile());
        assertEquals(8_192, first.session().state().fftSize());
        assertEquals(10, first.session().state().framesPerSecond());
        assertEquals(8, first.session().state().quantizationBits());

        first.session().close();
        assertEquals(1, firstController.addCount.get() + secondController.addCount.get());
        assertEquals(1, firstController.removeCount.get() + secondController.removeCount.get());
        assertEquals(1, processors.closeCount.get());
        assertEquals(0, service.activeProducerCount());
        assertEquals(0, service.activeSessionCount());

        TunerDiagnosticService.Session reopened = service.tryOpen(targets.get(1).targetId()).session();
        assertNotNull(reopened);
        reopened.close();
        service.close();
    }

    @Test
    void appliesSpectrumProfilesAndRestoresTheReceiverQueueOnClose()
    {
        FakeController controller = new FakeController(100_000_000L, 10_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        FakeReceiverQueue queue = new FakeReceiverQueue(100, 7, 9);
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1, queue)), processors);
        TunerDiagnosticService.Session session = service.tryOpen(service.targets().getFirst().targetId(), null,
            TunerDiagnosticService.SpectrumProfile.MAXIMUM_DETAIL).session();
        TunerDiagnosticService.State state = session.state();

        assertEquals("maximum-detail", state.profile());
        assertEquals(32_768, state.fftSize());
        assertEquals(20, state.framesPerSecond());
        assertEquals(32, state.maximumDecimation());
        assertEquals(200, state.iqQueueDurationMilliseconds());
        assertEquals(8, state.quantizationBits());
        assertEquals(7, state.receiverDroppedBuffers());
        assertEquals(9, state.receiverDroppedMilliseconds());
        assertEquals(TunerDiagnosticService.SpectrumProfile.MAXIMUM_DETAIL, processors.initialProfile.get());
        assertEquals(TunerDiagnosticService.SpectrumProfile.MAXIMUM_DETAIL, processors.lastProfile.get());
        assertEquals(List.of(200L), queue.requests);

        session.updateProfile(TunerDiagnosticService.SpectrumProfile.EFFICIENT);
        assertEquals(TunerDiagnosticService.SpectrumProfile.EFFICIENT, processors.lastProfile.get());
        assertEquals(2_048, session.state().fftSize());
        assertEquals(List.of(200L), queue.requests);

        session.close();
        assertEquals(List.of(200L, 100L), queue.requests);
        service.close();
    }

    @Test
    void concurrentOpenRaceHasOneWinner()
        throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 10_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        ConcurrentLinkedQueue<TunerDiagnosticService.Session> sessions = new ConcurrentLinkedQueue<>();
        List<? extends Future<?>> attempts = java.util.stream.IntStream.range(0, 32)
            .mapToObj(ignored -> executor.submit(() ->
            {
                await(start);
                TunerDiagnosticService.OpenResult result = service.tryOpen(targetId);

                if(result.status() == TunerDiagnosticService.OpenStatus.OPEN)
                {
                    opened.incrementAndGet();
                    sessions.add(result.session());
                }
            })).toList();

        try
        {
            start.countDown();

            for(Future<?> attempt: attempts)
            {
                attempt.get(2, TimeUnit.SECONDS);
            }

            assertEquals(1, opened.get());
            assertEquals(1, controller.addCount.get());
            assertEquals(1, processors.createCount.get());
        }
        finally
        {
            sessions.forEach(TunerDiagnosticService.Session::close);
            executor.shutdownNow();
            service.close();
        }

        assertEquals(controller.addCount.get(), controller.removeCount.get());
        assertEquals(processors.createCount.get(), processors.closeCount.get());
    }

    @Test
    void closesTheActiveSessionWithoutClosingTheService()
    {
        FakeController controller = new FakeController(150_000_000L, 2_500_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        String targetId = service.targets().getFirst().targetId();
        TunerDiagnosticService.Session session = service.tryOpen(targetId).session();

        service.closeActiveSessions();

        assertTrue(session.isClosed());
        assertEquals(1, controller.removeCount.get());
        assertEquals(1, processors.closeCount.get());
        assertEquals(0, service.activeSessionCount());

        TunerDiagnosticService.Session reopened = service.tryOpen(targetId).session();
        assertNotNull(reopened);
        reopened.close();
        service.close();
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
        assertEquals(0, failingService.activeSessionCount());
        failingService.close();
    }

    @Test
    void releasesTheSessionWhenItsTunerDisappears()
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
        assertEquals(0, service.activeSessionCount());
        service.close();
    }

    @Test
    void tunerCallbackOnlyCapturesTimeAndNeverWaitsForTheControllerLock()
        throws Exception
    {
        FakeController controller = new FakeController(705_000_000L, 2_500_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.RECORDING_TUNER, controller, 1)), processors);
        service.tryOpen(service.targets().getFirst().targetId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        controller.getLock().lock();
        long before = System.currentTimeMillis();

        try
        {
            Future<?> callback = executor.submit(() -> controller.emit(new EmptyNativeBuffer(123L)));
            callback.get(250, TimeUnit.MILLISECONDS);
            assertTrue(processors.lastObservedAtMs.get() >= before);
            assertNotEquals(123L, processors.lastObservedAtMs.get());
        }
        finally
        {
            controller.getLock().unlock();
            executor.shutdownNow();
            service.close();
        }
    }

    @Test
    void capturesRetuneMetadataWithoutReadingTheControllerOnTheSampleCallback()
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
    void outputQueueKeepsOnlyTheNewestFrame()
        throws Exception
    {
        FakeController controller = new FakeController(800_000_000L, 10_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        TunerDiagnosticService.Session session = service.tryOpen(service.targets().getFirst().targetId()).session();
        float[] values = new float[TunerDiagnosticService.FFT_SIZE];

        for(int x = 0; x < 3; x++)
        {
            processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 800_000_000L,
                10_000_000L, TunerDiagnosticService.FFT_SIZE, values));
        }

        DiagnosticStreamFrame frame = session.poll(Duration.ZERO);
        assertNotNull(frame);
        assertEquals(3, frame.sequence());
        assertNull(session.poll(Duration.ZERO));
        session.close();
        service.close();
    }

    @Test
    void selectsGuardedD1ThroughD32AnalysisPlans()
    {
        long center = 100_000_000L;
        long sampleRate = 10_000_000L;
        TunerDiagnosticService.AnalysisPlan overview = TunerDiagnosticService.analysisPlan(center, sampleRate, null);
        TunerDiagnosticService.AnalysisPlan d2 = TunerDiagnosticService.analysisPlan(center, sampleRate,
            centered(center, 3_500_000L));
        TunerDiagnosticService.AnalysisPlan d4 = TunerDiagnosticService.analysisPlan(center, sampleRate,
            centered(center, 1_800_000L));
        TunerDiagnosticService.AnalysisPlan guardedFromD8 = TunerDiagnosticService.analysisPlan(center, sampleRate,
            centered(center, 1_100_000L));
        TunerDiagnosticService.AnalysisPlan d8 = TunerDiagnosticService.analysisPlan(center, sampleRate,
            centered(center, 900_000L));
        TunerDiagnosticService.AnalysisPlan guardedFromD16 = TunerDiagnosticService.analysisPlan(center, sampleRate,
            centered(center, 550_000L));
        TunerDiagnosticService.AnalysisPlan d16 = TunerDiagnosticService.analysisPlan(center, sampleRate,
            centered(center, 450_000L));
        TunerDiagnosticService.AnalysisPlan guardedFromD32 = TunerDiagnosticService.analysisPlan(center, sampleRate,
            centered(center, 275_000L));
        TunerDiagnosticService.AnalysisPlan d32 = TunerDiagnosticService.analysisPlan(center, sampleRate,
            centered(center, 225_000L));
        TunerDiagnosticService.AnalysisPlan maximumDetail = TunerDiagnosticService.analysisPlan(center, sampleRate,
            centered(center, 100_000L));

        assertEquals(1, overview.decimation());
        assertEquals(10_000_000L, overview.sampleRateHz());
        assertEquals(2, d2.decimation());
        assertEquals(5_000_000L, d2.sampleRateHz());
        assertEquals(4, d4.decimation());
        assertEquals(2_500_000L, d4.sampleRateHz());
        assertEquals(4, guardedFromD8.decimation(),
            "a viewport wider than the central 80% must stay out of the D8 transition band");
        assertEquals(8, d8.decimation());
        assertEquals(1_250_000L, d8.sampleRateHz());
        assertEquals(8, guardedFromD16.decimation());
        assertEquals(16, d16.decimation());
        assertEquals(625_000L, d16.sampleRateHz());
        assertEquals(16, guardedFromD32.decimation());
        assertEquals(32, d32.decimation());
        assertEquals(312_500L, d32.sampleRateHz());
        assertEquals(32, maximumDetail.decimation());
        assertEquals(d32.sampleRateHz(), maximumDetail.sampleRateHz(),
            "views below tunerSpan/32 reuse the bounded max-detail lens");
        assertEquals(38.14697265625, maximumDetail.sampleRateHz() / (double)TunerDiagnosticService.FFT_SIZE,
            0.0001);
    }

    @Test
    void limitsLensDecimationToTheTunerSampleBudget()
    {
        TunerDiagnosticService.AnalysisPlan slowerTuner = TunerDiagnosticService.analysisPlan(10_000_000L,
            768_000L, centered(10_000_000L, 10_000L));

        assertEquals(8, slowerTuner.decimation());
        assertEquals(96_000L, slowerTuner.sampleRateHz());
    }

    @Test
    void spectrumProfilesBoundTheLensAndSampleBudget()
    {
        TunerDiagnosticService.AnalysisPlan plan = TunerDiagnosticService.analysisPlan(100_000_000L,
            10_000_000L, centered(100_000_000L, 100_000L),
            TunerDiagnosticService.SpectrumProfile.MAXIMUM_DETAIL);

        assertEquals(8, plan.decimation());
        assertEquals(1_250_000L, plan.sampleRateHz());
    }

    @Test
    void preservesTheGuardAtTheTunerEdgeOrFallsBackToOverview()
    {
        TunerDiagnosticService.AnalysisPlan touchingEdge = TunerDiagnosticService.analysisPlan(100_000_000L,
            10_000_000L, new TunerDiagnosticService.Viewport(104_300_000L, 104_900_000L));
        TunerDiagnosticService.AnalysisPlan guardedEdge = TunerDiagnosticService.analysisPlan(100_000_000L,
            10_000_000L, new TunerDiagnosticService.Viewport(104_200_000L, 104_700_000L));

        assertEquals(1, touchingEdge.decimation(),
            "a zoom lens must not trade anti-alias guard for resolution at the sampled band edge");
        assertEquals(16, guardedEdge.decimation());
        assertEquals(104_450_000L, guardedEdge.centerFrequencyHz());
        assertEquals(104_137_500.0, guardedEdge.startFrequencyHz());
        assertEquals(104_762_500.0, guardedEdge.endFrequencyHz());
    }

    @Test
    void stateKeepsFullTunerBoundsWhileFramesDescribeTheAnalysisLens()
        throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 10_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 3)), processors);
        TunerDiagnosticService.Viewport viewport = centered(100_000_000L, 800_000L);
        TunerDiagnosticService.Session session = service.tryOpen(service.targets().getFirst().targetId(), viewport)
            .session();
        TunerDiagnosticService.State state = session.state();

        assertEquals(100_000_000L, state.centerFrequencyHz());
        assertEquals(10_000_000L, state.sampleRateHz());
        assertEquals(viewport.startFrequencyHz(), state.requestedStartFrequencyHz().longValue());
        assertEquals(viewport.endFrequencyHz(), state.requestedEndFrequencyHz().longValue());
        assertEquals(viewport, processors.initialViewport.get());
        assertEquals(TunerDiagnosticService.SpectrumProfile.BALANCED, processors.initialProfile.get());
        assertEquals(99_375_000.0, state.visibleStartFrequencyHz());
        assertEquals(100_625_000.0, state.visibleEndFrequencyHz());
        assertEquals(0, state.firstBin());
        assertEquals(TunerDiagnosticService.FFT_SIZE, state.sourceBinCount());
        assertEquals(TunerDiagnosticService.FFT_SIZE, state.transmittedBinCount());

        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
            1_250_000L, TunerDiagnosticService.FFT_SIZE, new float[TunerDiagnosticService.FFT_SIZE]));
        DiagnosticStreamFrame frame = session.poll(Duration.ZERO);
        assertNotNull(frame);
        assertEquals(100_000_000L, frame.centerFrequencyHz());
        assertEquals(1_250_000L, frame.sampleRateHz());
        assertEquals(0, frame.firstBin());
        assertEquals(TunerDiagnosticService.FFT_SIZE, frame.valueCount());
        session.close();
        service.close();
    }

    @Test
    void viewportChangesDoNotReattachAndRejectAnObsoleteAnalysisFrame()
        throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 10_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        TunerDiagnosticService.Session session = service.tryOpen(service.targets().getFirst().targetId()).session();
        long generation = session.state().generation();
        long oldRevision = session.state().revision();
        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
            10_000_000L, TunerDiagnosticService.FFT_SIZE, new float[TunerDiagnosticService.FFT_SIZE]));

        TunerDiagnosticService.Viewport viewport = centered(100_000_000L, 800_000L);
        session.updateViewport(viewport);
        TunerDiagnosticService.State state = session.state();

        assertEquals(generation, state.generation());
        assertTrue(state.revision() > oldRevision);
        assertEquals(1, controller.addCount.get());
        assertEquals(0, controller.removeCount.get());
        assertEquals(1, processors.createCount.get());
        assertEquals(viewport, processors.lastViewport.get());
        assertNull(session.poll(Duration.ZERO), "the queued overview frame must not be labelled as zoom data");

        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
            1_250_000L, TunerDiagnosticService.FFT_SIZE, new float[TunerDiagnosticService.FFT_SIZE]));
        assertNotNull(session.poll(Duration.ofSeconds(1)));
        session.close();
        service.close();
    }

    @Test
    void recalculatesTheLensAfterSampleRateChanges()
        throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 10_000_000.0);
        FakeProcessorFactory processors = new FakeProcessorFactory();
        TunerDiagnosticService service = service(List.of(
            target(new Object(), TunerClass.AIRSPY, controller, 1)), processors);
        TunerDiagnosticService.Viewport viewport = centered(100_000_000L, 800_000L);
        TunerDiagnosticService.Session session = service.tryOpen(service.targets().getFirst().targetId(), viewport)
            .session();

        controller.retune(100_000_000L, 8_000_000.0);
        TunerDiagnosticService.State state = session.state();

        assertEquals(8_000_000L, state.sampleRateHz());
        assertEquals(99_500_000.0, state.visibleStartFrequencyHz());
        assertEquals(100_500_000.0, state.visibleEndFrequencyHz());
        assertEquals(8_000_000L, processors.lastSampleRateHz.get());

        processors.publish(new TunerDiagnosticService.FftResult(System.currentTimeMillis(), 100_000_000L,
            1_000_000L, TunerDiagnosticService.FFT_SIZE, new float[TunerDiagnosticService.FFT_SIZE]));
        DiagnosticStreamFrame frame = session.poll(Duration.ofSeconds(1));
        assertNotNull(frame);
        assertEquals(1_000_000L, frame.sampleRateHz());
        session.close();
        service.close();
    }

    @Test
    void nativeCallbackCarriesItsEntryEpochAcrossAConcurrentRetune()
        throws Exception
    {
        FakeController controller = new FakeController(100_000_000L, 8_192_000.0);
        AtomicLong configuration = new AtomicLong(1);
        AtomicLong receivedConfiguration = new AtomicLong();
        CountDownLatch configurationCaptured = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        TunerDiagnosticService.ProcessorFactory factory = (target, viewport, profile, consumer) ->
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
        TunerDiagnosticService.Session session = service.tryOpen(service.targets().getFirst().targetId()).session();
        long entryConfiguration = configuration.get();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try
        {
            Future<?> callback = executor.submit(() -> controller.emit(new EmptyNativeBuffer(111)));
            assertTrue(configurationCaptured.await(1, TimeUnit.SECONDS));
            controller.retune(101_000_000L, 10_000_000.0);
            releaseCallback.countDown();
            callback.get(1, TimeUnit.SECONDS);
            assertTrue(received.await(1, TimeUnit.SECONDS));
            assertEquals(entryConfiguration, receivedConfiguration.get());
        }
        finally
        {
            releaseCallback.countDown();
            executor.shutdownNow();
            session.close();
            service.close();
        }
    }

    private static TunerDiagnosticService.Viewport centered(long centerFrequencyHz, long spanHz)
    {
        return new TunerDiagnosticService.Viewport(centerFrequencyHz - spanHz / 2,
            centerFrequencyHz + spanHz / 2);
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

    private static TunerDiagnosticService.AvailableTarget target(Object identity, TunerClass tunerClass,
                                                                  String name, String serial,
                                                                  TunerController controller, int channelCount)
    {
        return new TunerDiagnosticService.AvailableTarget(identity, tunerClass, name, serial, controller,
            () -> channelCount, TunerDiagnosticService.ReceiverQueueControl.UNSUPPORTED);
    }

    private static TunerDiagnosticService.AvailableTarget target(Object identity, TunerClass tunerClass,
                                                                  TunerController controller, int channelCount,
                                                                  TunerDiagnosticService.ReceiverQueueControl queue)
    {
        return new TunerDiagnosticService.AvailableTarget(identity, tunerClass, controller, () -> channelCount,
            queue);
    }

    private static final class FakeProcessorFactory implements TunerDiagnosticService.ProcessorFactory
    {
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicLong lastObservedAtMs = new AtomicLong();
        private final AtomicLong lastCenterFrequencyHz = new AtomicLong();
        private final AtomicLong lastSampleRateHz = new AtomicLong();
        private final AtomicReference<TunerDiagnosticService.Viewport> lastViewport = new AtomicReference<>();
        private final AtomicReference<TunerDiagnosticService.Viewport> initialViewport = new AtomicReference<>();
        private final AtomicReference<TunerDiagnosticService.SpectrumProfile> initialProfile =
            new AtomicReference<>();
        private final AtomicReference<TunerDiagnosticService.SpectrumProfile> lastProfile =
            new AtomicReference<>();
        private final AtomicReference<Consumer<TunerDiagnosticService.FftResult>> consumer = new AtomicReference<>();

        @Override
        public TunerDiagnosticService.FrameProcessor create(TunerDiagnosticService.TargetSnapshot target,
                                                             TunerDiagnosticService.Viewport viewport,
                                                             TunerDiagnosticService.SpectrumProfile profile,
                                                             Consumer<TunerDiagnosticService.FftResult> consumer)
        {
            createCount.incrementAndGet();
            initialViewport.set(viewport);
            initialProfile.set(profile);
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
                public void updateViewport(TunerDiagnosticService.Viewport viewport)
                {
                    lastViewport.set(viewport);
                }

                @Override
                public void updateProfile(TunerDiagnosticService.SpectrumProfile profile)
                {
                    lastProfile.set(profile);
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
            Consumer<TunerDiagnosticService.FftResult> active = consumer.get();

            if(active != null)
            {
                active.accept(frame);
            }
        }
    }

    private static final class FakeReceiverQueue implements TunerDiagnosticService.ReceiverQueueControl
    {
        private final List<Long> requests = new CopyOnWriteArrayList<>();
        private final long droppedBuffers;
        private final long droppedMilliseconds;
        private volatile long duration;

        private FakeReceiverQueue(long duration, long droppedBuffers, long droppedMilliseconds)
        {
            this.duration = duration;
            this.droppedBuffers = droppedBuffers;
            this.droppedMilliseconds = droppedMilliseconds;
        }

        @Override
        public TunerDiagnosticService.ReceiverQueueSnapshot status()
        {
            return new TunerDiagnosticService.ReceiverQueueSnapshot(true, duration, duration, 0,
                droppedBuffers, droppedMilliseconds);
        }

        @Override
        public void request(long durationMilliseconds)
        {
            duration = durationMilliseconds;
            requests.add(durationMilliseconds);
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
