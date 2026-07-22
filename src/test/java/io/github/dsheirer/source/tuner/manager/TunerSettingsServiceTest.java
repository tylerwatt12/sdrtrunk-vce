/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.airspy.AirspySampleRate;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService.EnabledRequest;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService.TunerSettingsException;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService.UpdateRequest;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerController;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xEmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.r8x.r820t.R820TTunerConfiguration;
import io.github.dsheirer.source.tuner.test.TestTuner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TunerSettingsServiceTest
{
    @Test
    void snapshotsDisabledConfigurationWithoutHardwareIdentityReads() throws Exception
    {
        BlockingDiscoveredTuner discovered = fixture();
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(registry, saves::incrementAndGet))
        {
            String id = registry.snapshots().getFirst().id();
            Map<String,Object> settings = service.settings(id).get(2, TimeUnit.SECONDS);
            assertEquals(id, settings.get("id"));
            assertEquals(Boolean.FALSE, settings.get("enabled"));
            assertEquals(Boolean.TRUE, settings.get("editable"));
            assertEquals(TunerConfiguration.DEFAULT_FREQUENCY, settings.get("centerFrequencyHz"));
            @SuppressWarnings("unchecked")
            Map<String,Object> device = (Map<String,Object>)settings.get("device");
            assertEquals("AIRSPY", device.get("type"));
            assertEquals(10_000_000, device.get("sampleRateHz"));
            assertEquals(0, saves.get(), "read-only snapshots must not write configuration state");
        }
    }

    @Test
    void editsSavedSettingsWhileReceiverIsDisabledWithoutStartingHardware() throws Exception
    {
        BlockingDiscoveredTuner discovered = fixture();
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)discovered.getTunerConfiguration();
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(registry, saves::incrementAndGet))
        {
            String id = registry.snapshots().getFirst().id();
            Map<String,Object> settings = service.settings(id).get(2, TimeUnit.SECONDS);
            long revision = ((Number)settings.get("revision")).longValue();
            UpdateRequest request = airspyRequest(revision, 2.0, false, 18, 102_500_000L);
            Map<String,Object> updated = service.update(id, request, () -> true).get(2, TimeUnit.SECONDS);
            assertEquals(Boolean.FALSE, updated.get("enabled"));
            assertEquals(Boolean.TRUE, updated.get("editable"));
            assertEquals(2.0, configuration.getFrequencyCorrection());
            assertEquals(102_500_000L, configuration.getFrequency());
            assertFalse(configuration.getAutoPPMCorrectionEnabled());
            assertEquals(18, configuration.getGain().getValue());
            assertEquals(1, saves.get());
            assertEquals(1, discovered.startEntered.getCount(),
                "editing a disabled receiver must not initialize or query its hardware");
        }
    }

    @Test
    void webManualPpmRequiresWholeNumbersWithoutChangingFractionalStorage() throws Exception
    {
        BlockingDiscoveredTuner discovered = fixture();
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)discovered.getTunerConfiguration();
        configuration.setFrequencyCorrection(1.5);
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            Map<String,Object> settings = service.settings(id).get(2, TimeUnit.SECONDS);
            assertEquals(1.5, settings.get("frequencyCorrectionPpm"));
            long revision = ((Number)settings.get("revision")).longValue();
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> service.update(id, airspyRequest(revision, 1.5, true, 14), () -> true)
                    .get(2, TimeUnit.SECONDS));
            TunerSettingsException invalid = assertInstanceOf(TunerSettingsException.class, failure.getCause());
            assertEquals(400, invalid.status());
            assertEquals(1.5, configuration.getFrequencyCorrection());
        }
    }

    @Test
    void editsSavedRtlSettingsWhileReceiverIsDisabledWithoutStartingHardware() throws Exception
    {
        R820TTunerConfiguration configuration = new R820TTunerConfiguration();
        configuration.setUniqueID("rtl-test");
        DisabledRtlDiscoveredTuner discovered = new DisabledRtlDiscoveredTuner(configuration);
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            Map<String,Object> settings = service.settings(id).get(2, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, settings.get("editable"));
            long revision = ((Number)settings.get("revision")).longValue();
            UpdateRequest request = new UpdateRequest(revision, 0.0, true,
                TunerConfiguration.DEFAULT_FREQUENCY,
                R8xEmbeddedTuner.MINIMUM_TUNABLE_FREQUENCY_HZ,
                R8xEmbeddedTuner.MAXIMUM_TUNABLE_FREQUENCY_HZ, false, "RTL_R8X",
                RTL2832TunerController.SampleRate.RATE_2_400MHZ.getRate(), null, null, null, null, null,
                null, null, true, "MANUAL", "GAIN_105", "GAIN_222", "GAIN_210");
            Map<String,Object> updated = service.update(id, request, () -> true).get(2, TimeUnit.SECONDS);
            assertEquals(Boolean.FALSE, updated.get("enabled"));
            assertTrue(configuration.isBiasT());
            assertEquals(R8xEmbeddedTuner.MasterGain.MANUAL, configuration.getMasterGain());
            assertEquals(0, discovered.starts.get(),
                "editing a disabled RTL-SDR must not initialize or query its hardware");
        }
    }

    @Test
    void serializesCommandsAndRejectsWorkBeyondTheBoundedQueue() throws Exception
    {
        BlockingDiscoveredTuner discovered = fixture();
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(registry, saves::incrementAndGet))
        {
            String id = registry.snapshots().getFirst().id();
            long revision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS).get("revision")).longValue();
            CompletableFuture<Map<String,Object>> enabling = service.setEnabled(id,
                new EnabledRequest(revision, true, false), () -> true);
            assertTrue(discovered.startEntered.await(2, TimeUnit.SECONDS));
            List<CompletableFuture<Map<String,Object>>> queued = new ArrayList<>();

            for(int index = 0; index < 8; index++)
            {
                queued.add(service.settings(id));
            }

            ExecutionException overflow = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> service.settings(id).get(2, TimeUnit.SECONDS));
            TunerSettingsException unavailable = assertInstanceOf(TunerSettingsException.class,
                overflow.getCause());
            assertEquals(503, unavailable.status());
            discovered.allowStart.countDown();
            assertEquals(Boolean.TRUE, enabling.get(2, TimeUnit.SECONDS).get("enabled"));

            for(CompletableFuture<Map<String,Object>> command: queued)
            {
                assertEquals(id, command.get(2, TimeUnit.SECONDS).get("id"));
            }

            assertEquals(1, discovered.maximumConcurrentStarts.get());
            assertTrue(saves.get() >= 1);
        }
    }

    @Test
    void rechecksTheAdministratorSessionInsideTheWorker() throws Exception
    {
        BlockingDiscoveredTuner discovered = fixture();
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            long revision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS).get("revision")).longValue();
            CompletableFuture<Map<String,Object>> first = service.setEnabled(id,
                new EnabledRequest(revision, true, false), () -> true);
            assertTrue(discovered.startEntered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Map<String,Object>> revoked = service.setEnabled(id,
                new EnabledRequest(revision, false, false), () -> false);
            discovered.allowStart.countDown();
            first.get(2, TimeUnit.SECONDS);
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> revoked.get(2, TimeUnit.SECONDS));
            TunerSettingsException unauthorized = assertInstanceOf(TunerSettingsException.class,
                failure.getCause());
            assertEquals(401, unauthorized.status());
            assertFalse(revoked.isCancelled());
        }
    }

    @Test
    void disabledReceiverWithoutLoadedConfigurationCanBeEnabled() throws Exception
    {
        BlockingDiscoveredTuner discovered = new BlockingDiscoveredTuner();
        discovered.setEnabled(false);
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            Map<String,Object> disabled = service.settings(id).get(2, TimeUnit.SECONDS);
            assertEquals(Boolean.FALSE, disabled.get("enabled"));
            assertEquals(Boolean.FALSE, disabled.get("editable"));
            long revision = ((Number)disabled.get("revision")).longValue();
            discovered.allowStart.countDown();
            Map<String,Object> enabled = service.setEnabled(id,
                new EnabledRequest(revision, true, false), () -> true).get(2, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, enabled.get("enabled"));
        }
    }

    @Test
    void staleRevisionCannotTargetReplacementAtTheSameUsbLocation() throws Exception
    {
        BlockingDiscoveredTuner original = fixture();
        BlockingDiscoveredTuner replacement = fixture();
        AtomicReference<List<DiscoveredTuner>> current = new AtomicReference<>(List.of(original));
        TunerRegistry registry = new TunerRegistry(current::get);

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            long staleRevision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS)
                .get("revision")).longValue();
            current.set(List.of(replacement));
            long replacementRevision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS)
                .get("revision")).longValue();
            assertNotEquals(staleRevision, replacementRevision);

            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> service.setEnabled(id, new EnabledRequest(staleRevision, true, false), () -> true)
                    .get(2, TimeUnit.SECONDS));
            TunerSettingsException changed = assertInstanceOf(TunerSettingsException.class, failure.getCause());
            assertEquals(412, changed.status());
        }
    }

    @Test
    void lockedSampleRateRequiresConfirmationBeforeDisable() throws Exception
    {
        FakeAirspyTuner tuner = new FakeAirspyTuner();
        tuner.controller().setLockedSampleRate(true);
        ImmediateDiscoveredTuner discovered = new ImmediateDiscoveredTuner(tuner, airspyConfiguration());
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            Map<String,Object> settings = service.settings(id).get(2, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, settings.get("radioWorkActive"));
            long revision = ((Number)settings.get("revision")).longValue();
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> service.setEnabled(id, new EnabledRequest(revision, false, false), () -> true)
                    .get(2, TimeUnit.SECONDS));
            TunerSettingsException active = assertInstanceOf(TunerSettingsException.class, failure.getCause());
            assertEquals(409, active.status());
            assertEquals("active_channels", active.code());
        }
    }

    @Test
    void activeReceiverAllowsGainButStillRejectsRetuningSettings() throws Exception
    {
        FakeAirspyTuner tuner = new FakeAirspyTuner();
        tuner.controller().setLockedSampleRate(true);
        AirspyTunerConfiguration configuration = airspyConfiguration();
        ImmediateDiscoveredTuner discovered = new ImmediateDiscoveredTuner(tuner, configuration);
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            Map<String,Object> settings = service.settings(id).get(2, TimeUnit.SECONDS);
            long revision = ((Number)settings.get("revision")).longValue();
            Map<String,Object> updated = service.update(id, airspyRequest(revision, 0.0, true, 18),
                () -> true).get(2, TimeUnit.SECONDS);
            assertEquals(18, configuration.getGain().getValue());

            long updatedRevision = ((Number)updated.get("revision")).longValue();
            ExecutionException centerFailure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> service.update(id, airspyRequest(updatedRevision, 0.0, true, 18,
                    AirspyTunerController.FREQUENCY_DEFAULT + 1_000_000L), () -> true)
                    .get(2, TimeUnit.SECONDS));
            TunerSettingsException activeCenter = assertInstanceOf(TunerSettingsException.class,
                centerFailure.getCause());
            assertEquals("active_channels", activeCenter.code());

            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> service.update(id, airspyRequest(updatedRevision, 1.0, true, 18), () -> true)
                    .get(2, TimeUnit.SECONDS));
            TunerSettingsException active = assertInstanceOf(TunerSettingsException.class, failure.getCause());
            assertEquals(409, active.status());
            assertEquals("active_channels", active.code());
        }
    }

    @Test
    void disableCheckAndStateChangeHoldTheTunerLifecycleMonitor() throws Exception
    {
        FakeAirspyTuner tuner = new FakeAirspyTuner();
        RaceDiscoveredTuner discovered = new RaceDiscoveredTuner(tuner, airspyConfiguration());
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            long revision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS).get("revision")).longValue();
            CompletableFuture<Map<String,Object>> disabling = service.setEnabled(id,
                new EnabledRequest(revision, false, false), () -> true);
            assertTrue(discovered.disableEntered.await(2, TimeUnit.SECONDS));
            CountDownLatch competingLockAcquired = new CountDownLatch(1);
            Thread contender = Thread.ofPlatform().start(() ->
            {
                synchronized(discovered)
                {
                    competingLockAcquired.countDown();
                }
            });
            assertFalse(competingLockAcquired.await(150, TimeUnit.MILLISECONDS));
            discovered.allowDisable.countDown();
            assertEquals(Boolean.FALSE, disabling.get(2, TimeUnit.SECONDS).get("enabled"));
            assertTrue(discovered.lifecycleMonitorHeldDuringDisable.get());
            assertTrue(competingLockAcquired.await(2, TimeUnit.SECONDS));
            contender.join(2_000);
        }
    }

    @Test
    void persistenceRunsAfterTheControllerLockIsReleased() throws Exception
    {
        FakeAirspyTuner tuner = new FakeAirspyTuner();
        ImmediateDiscoveredTuner discovered = new ImmediateDiscoveredTuner(tuner, airspyConfiguration());
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));
        AtomicBoolean saveRan = new AtomicBoolean();
        AtomicBoolean lockHeldDuringSave = new AtomicBoolean();

        try(TunerSettingsService service = new TunerSettingsService(registry, () ->
        {
            saveRan.set(true);
            lockHeldDuringSave.set(tuner.controller().getLock().isHeldByCurrentThread());
        }))
        {
            String id = registry.snapshots().getFirst().id();
            long revision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS).get("revision")).longValue();
            UpdateRequest request = new UpdateRequest(revision, 0.0, false,
                AirspyTunerController.FREQUENCY_DEFAULT,
                AirspyTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ,
                AirspyTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ, false, "AIRSPY",
                AirspyTunerController.DEFAULT_SAMPLE_RATE.getRate(), "LINEARITY", 14, 9, 9, 7,
                false, false, null, null, null, null, null);
            service.update(id, request, () -> true).get(2, TimeUnit.SECONDS);
            assertTrue(saveRan.get());
            assertFalse(lockHeldDuringSave.get());
        }
    }

    @Test
    void changesLiveCenterFrequencyOnlyWhileReceiverIsIdle() throws Exception
    {
        FakeAirspyTuner tuner = new FakeAirspyTuner();
        AirspyTunerConfiguration configuration = airspyConfiguration();
        ImmediateDiscoveredTuner discovered = new ImmediateDiscoveredTuner(tuner, configuration);
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            long revision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS).get("revision")).longValue();
            long requestedCenter = AirspyTunerController.FREQUENCY_DEFAULT + 1_000_000L;
            Map<String,Object> updated = service.update(id,
                airspyRequest(revision, 0.0, true, 14, requestedCenter), () -> true).get(2, TimeUnit.SECONDS);
            assertEquals(requestedCenter, updated.get("centerFrequencyHz"));
            assertEquals(requestedCenter, tuner.controller().getFrequency());
            assertEquals(requestedCenter, configuration.getFrequency());
        }
    }

    @Test
    void staleSavedAirspyRateCanBeReplacedWithTheCurrentSupportedRate() throws Exception
    {
        FakeAirspyTuner tuner = new FakeAirspyTuner();
        AirspyTunerConfiguration configuration = airspyConfiguration();
        configuration.setSampleRate(2_500_000);
        ImmediateDiscoveredTuner discovered = new ImmediateDiscoveredTuner(tuner, configuration);
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            long revision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS).get("revision")).longValue();
            UpdateRequest request = airspyRequest(revision, 0.0, true, 14);
            Map<String,Object> updated = service.update(id, request, () -> true).get(2, TimeUnit.SECONDS);
            @SuppressWarnings("unchecked")
            Map<String,Object> device = (Map<String,Object>)updated.get("device");
            assertEquals(AirspyTunerController.DEFAULT_SAMPLE_RATE.getRate(), device.get("sampleRateHz"));
            assertEquals(AirspyTunerController.DEFAULT_SAMPLE_RATE.getRate(), configuration.getSampleRate());
        }
    }

    @Test
    void failedDeviceWriteRestoresTheLiveFrequencyCorrection() throws Exception
    {
        FakeAirspyTuner tuner = new FakeAirspyTuner();
        tuner.controller().setFrequencyCorrection(2.5);
        tuner.controller().failNextGain = true;
        AirspyTunerConfiguration configuration = airspyConfiguration();
        ImmediateDiscoveredTuner discovered = new ImmediateDiscoveredTuner(tuner, configuration);
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));

        try(TunerSettingsService service = new TunerSettingsService(registry, () -> {}))
        {
            String id = registry.snapshots().getFirst().id();
            long revision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS).get("revision")).longValue();
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> service.update(id, airspyRequest(revision, 1.0, true, 15), () -> true)
                    .get(2, TimeUnit.SECONDS));
            TunerSettingsException unavailable = assertInstanceOf(TunerSettingsException.class, failure.getCause());
            assertEquals(503, unavailable.status());
            assertEquals(2.5, tuner.controller().getFrequencyCorrection());
            assertEquals(AirspyTunerController.LINEARITY_GAIN_DEFAULT, configuration.getGain());
        }
    }

    @Test
    void repeatedCloseRefusesOverlapUntilAnInterruptIgnoringCommandStops() throws Exception
    {
        ShutdownBlockingDiscoveredTuner discovered = new ShutdownBlockingDiscoveredTuner();
        discovered.installConfiguration(airspyConfiguration());
        discovered.setEnabled(false);
        TunerRegistry registry = new TunerRegistry(() -> List.of(discovered));
        TunerSettingsService service = new TunerSettingsService(registry, () -> {}, Duration.ofMillis(30));

        try
        {
            String id = registry.snapshots().getFirst().id();
            long revision = ((Number)service.settings(id).get(2, TimeUnit.SECONDS).get("revision")).longValue();
            service.setEnabled(id, new EnabledRequest(revision, true, false), () -> true);
            assertTrue(discovered.startEntered.await(2, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::close);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::close);
            discovered.allowStart.countDown();
            assertTrue(discovered.startFinished.await(2, TimeUnit.SECONDS));
            service.close();
        }
        finally
        {
            discovered.allowStart.countDown();

            try
            {
                service.close();
            }
            catch(IllegalStateException ignored)
            {
                //The assertions above report a command that failed to leave the worker.
            }
        }
    }

    private static BlockingDiscoveredTuner fixture()
    {
        BlockingDiscoveredTuner discovered = new BlockingDiscoveredTuner();
        discovered.install(new TestTuner(null));
        discovered.installConfiguration(new AirspyTunerConfiguration("airspy-test"));
        discovered.setEnabled(false);
        return discovered;
    }

    private static AirspyTunerConfiguration airspyConfiguration()
    {
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration();
        configuration.setUniqueID("airspy-test");
        return configuration;
    }

    private static UpdateRequest airspyRequest(long revision, double ppm, boolean autoPpm, int gain)
    {
        return airspyRequest(revision, ppm, autoPpm, gain, AirspyTunerController.FREQUENCY_DEFAULT);
    }

    private static UpdateRequest airspyRequest(long revision, double ppm, boolean autoPpm, int gain,
                                                long centerFrequency)
    {
        return new UpdateRequest(revision, ppm, autoPpm, centerFrequency,
            AirspyTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ,
            AirspyTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ, false, "AIRSPY",
            AirspyTunerController.DEFAULT_SAMPLE_RATE.getRate(), "LINEARITY", gain, 9, 9, 7,
            false, false, null, null, null, null, null);
    }

    private static class DisabledRtlDiscoveredTuner extends DiscoveredTuner
    {
        private final AtomicInteger starts = new AtomicInteger();

        private DisabledRtlDiscoveredTuner(R820TTunerConfiguration configuration)
        {
            mTunerConfiguration = configuration;
            setEnabled(false);
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.RTL2832;
        }

        @Override
        public String getId()
        {
            return "RTL disabled discovery";
        }

        @Override
        public void start()
        {
            starts.incrementAndGet();
        }
    }

    private static class BlockingDiscoveredTuner extends DiscoveredTuner
    {
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch allowStart = new CountDownLatch(1);
        private final AtomicInteger activeStarts = new AtomicInteger();
        private final AtomicInteger maximumConcurrentStarts = new AtomicInteger();

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.AIRSPY;
        }

        @Override
        public String getId()
        {
            return "Airspy test discovery";
        }

        @Override
        public void start()
        {
            int active = activeStarts.incrementAndGet();
            maximumConcurrentStarts.accumulateAndGet(active, Math::max);
            startEntered.countDown();

            try
            {
                allowStart.await(2, TimeUnit.SECONDS);
                install(new TestTuner(null));
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                activeStarts.decrementAndGet();
            }
        }

        private void install(TestTuner tuner)
        {
            mTuner = tuner;
        }

        private void installConfiguration(AirspyTunerConfiguration configuration)
        {
            mTunerConfiguration = configuration;
        }
    }

    private static class ShutdownBlockingDiscoveredTuner extends DiscoveredTuner
    {
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch allowStart = new CountDownLatch(1);
        private final CountDownLatch startFinished = new CountDownLatch(1);

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.AIRSPY;
        }

        @Override
        public String getId()
        {
            return "Airspy shutdown discovery";
        }

        @Override
        public void start()
        {
            startEntered.countDown();
            boolean released = false;

            while(!released)
            {
                try
                {
                    allowStart.await();
                    released = true;
                }
                catch(InterruptedException ignored)
                {
                    //Synthetic uninterruptible hardware call used to verify restart exclusion.
                }
            }

            mTuner = new TestTuner(null);
            startFinished.countDown();
        }

        private void installConfiguration(AirspyTunerConfiguration configuration)
        {
            mTunerConfiguration = configuration;
        }
    }

    private static class ImmediateDiscoveredTuner extends DiscoveredTuner
    {
        private ImmediateDiscoveredTuner(Tuner tuner, AirspyTunerConfiguration configuration)
        {
            mTuner = tuner;
            mTunerConfiguration = configuration;
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.AIRSPY;
        }

        @Override
        public String getId()
        {
            return "Airspy immediate discovery";
        }

        @Override
        public void start()
        {
        }
    }

    private static class RaceDiscoveredTuner extends ImmediateDiscoveredTuner
    {
        private final CountDownLatch disableEntered = new CountDownLatch(1);
        private final CountDownLatch allowDisable = new CountDownLatch(1);
        private final AtomicBoolean lifecycleMonitorHeldDuringDisable = new AtomicBoolean();

        private RaceDiscoveredTuner(FakeAirspyTuner tuner, AirspyTunerConfiguration configuration)
        {
            super(tuner, configuration);
        }

        @Override
        public void setEnabled(boolean enabled)
        {
            if(!enabled)
            {
                lifecycleMonitorHeldDuringDisable.set(Thread.holdsLock(this));
                disableEntered.countDown();

                try
                {
                    allowDisable.await(2, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }

            super.setEnabled(enabled);
        }
    }

    private static class FakeAirspyTuner extends Tuner
    {
        private final FakeAirspyController mController;

        private FakeAirspyTuner() throws SourceException
        {
            this(new FakeAirspyController());
        }

        private FakeAirspyTuner(FakeAirspyController controller) throws SourceException
        {
            super(controller, null);
            mController = controller;
            setChannelSourceManager(new TestPolyphaseChannelSourceManager(controller));
            controller.setFrequency(AirspyTunerController.FREQUENCY_DEFAULT);
        }

        private FakeAirspyController controller()
        {
            return mController;
        }

        @Override
        public String getPreferredName()
        {
            return "Fake Airspy";
        }

        @Override
        public String getUniqueID()
        {
            return "fake-airspy";
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.AIRSPY;
        }

        @Override
        public TunerType getTunerType()
        {
            return TunerType.AIRSPY_R820T;
        }

        @Override
        public double getSampleSize()
        {
            return 12.0;
        }

        @Override
        public int getMaximumUSBBitsPerSecond()
        {
            return 0;
        }
    }

    private static class FakeAirspyController extends AirspyTunerController
    {
        private long mFrequency = FREQUENCY_DEFAULT;
        private boolean failNextGain;

        private FakeAirspyController() throws SourceException
        {
            super(0, "test", null);
            mFrequencyController.setSampleRate(DEFAULT_SAMPLE_RATE.getRate());
        }

        @Override
        public List<AirspySampleRate> getSampleRates()
        {
            return List.of(DEFAULT_SAMPLE_RATE);
        }

        @Override
        public void setSampleRate(AirspySampleRate rate) throws SourceException
        {
            mFrequencyController.setSampleRate(rate.getRate());
        }

        @Override
        public void setGain(Gain gain) throws javax.usb.UsbException
        {
            if(failNextGain)
            {
                failNextGain = false;
                throw new javax.usb.UsbException("synthetic gain failure");
            }
        }

        @Override
        public synchronized void setMixerAGC(boolean enabled)
        {
        }

        @Override
        public synchronized void setLNAAGC(boolean enabled)
        {
        }

        @Override
        public synchronized void setLNAGain(int gain)
        {
        }

        @Override
        public void setMixerGain(int gain)
        {
        }

        @Override
        public void setIFGain(int gain)
        {
        }

        @Override
        public synchronized long getTunedFrequency()
        {
            return mFrequency;
        }

        @Override
        public synchronized void setTunedFrequency(long frequency)
        {
            mFrequency = frequency;
        }

        @Override
        public double getCurrentSampleRate()
        {
            return DEFAULT_SAMPLE_RATE.getRate();
        }
    }
}
