package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController.Gain;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerConfiguration;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController.HackRFSampleRate;
import io.github.dsheirer.source.tuner.sdrplay.RspSampleRate;
import io.github.dsheirer.source.tuner.sdrplay.rsp1.IControlRsp1;
import io.github.dsheirer.source.tuner.sdrplay.rsp1.Rsp1TunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.rsp1.Rsp1TunerController;
import io.github.dsheirer.source.tuner.sdrplay.api.DeviceSelectionMode;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceType;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner1Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner2Configuration;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TunerSettingsServiceTest
{
    @Test
    void queuedChangeWaitsForLifecycleReservationAndPersistsOffRequestThread() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner();
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);
        AtomicInteger saves = new AtomicInteger();
        AtomicBoolean present = new AtomicBoolean(true);

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> present.get(), ignored -> null))
        {
            assertTrue(tuner.tryAcquireForAllocation());
            try
            {
                assertEquals("queued", service.set(tuner, "automatic_ppm", false).status());
                assertTrue(configuration.getAutoPPMCorrectionEnabled());
                assertTrue(service.hasPending(tuner));
                assertEquals(false, service.describe(tuner).stream()
                    .filter(setting -> setting.id().equals("automatic_ppm")).findFirst().orElseThrow().pendingValue());
            }
            finally
            {
                tuner.releaseAfterAllocation();
            }

            await(Duration.ofSeconds(3), () -> !configuration.getAutoPPMCorrectionEnabled());
            assertEquals(1, saves.get());
            assertFalse(service.hasPending(tuner));
        }
    }

    @Test
    void removedTunerDoesNotReceiveQueuedChange() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner();
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);
        AtomicBoolean present = new AtomicBoolean(true);
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> present.get(), ignored -> null))
        {
            assertTrue(tuner.tryAcquireForAllocation());
            try
            {
                service.set(tuner, "automatic_ppm", false);
                present.set(false);
            }
            finally
            {
                tuner.releaseAfterAllocation();
            }

            await(Duration.ofSeconds(3), () -> !service.hasPending(tuner));
            assertTrue(configuration.getAutoPPMCorrectionEnabled());
            assertEquals(0, saves.get());
        }
    }

    @Test
    void queuedManualGainIsDiscardedIfPresetChangedBeforeHardwareApply() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner();
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        configuration.setGain(Gain.CUSTOM);
        int originalIfGain = configuration.getIFGain();
        tuner.setTunerConfiguration(configuration);
        CountDownLatch persistenceStarted = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);

        try(TunerSettingsService service = new TunerSettingsService(() ->
        {
            persistenceStarted.countDown();
            try
            {
                releasePersistence.await(3, TimeUnit.SECONDS);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }, ignored -> true, ignored -> null))
        {
            service.set(tuner, "automatic_ppm", false);
            assertTrue(persistenceStarted.await(3, TimeUnit.SECONDS));
            try
            {
                service.set(tuner, "if_gain", 7);
                configuration.setGain(Gain.LINEARITY_1);
            }
            finally
            {
                releasePersistence.countDown();
            }

            await(Duration.ofSeconds(3), () -> service.error(tuner) != null && !service.hasPending(tuner));
            assertEquals(originalIfGain, configuration.getIFGain());
            assertFalse(service.hasPending(tuner));
        }
    }

    @Test
    void activeGainUsesDirectSetterWhileFrequencyCorrectionWaitsForIdle() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(1);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        configuration.setGain(Gain.CUSTOM);
        tuner.setTunerConfiguration(configuration);
        tuner.install(new FakeTuner(controller, tuner, channels));
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> true, ignored -> null))
        {
            assertFalse(service.describe(tuner).stream().filter(setting -> setting.id().equals("if_gain"))
                .findFirst().orElseThrow().requiresIdle());
            assertTrue(service.describe(tuner).stream().filter(setting ->
                setting.id().equals("frequency_correction_ppm")).findFirst().orElseThrow().requiresIdle());

            assertTrue(tuner.tryAcquireForAllocation());
            try
            {
                service.set(tuner, "if_gain", 7);
                await(Duration.ofSeconds(3), () -> controller.mIfGainCalls.get() == 1);
            }
            finally
            {
                tuner.releaseAfterAllocation();
            }
            assertEquals(7, configuration.getIFGain());
            assertEquals(0, controller.mApplyCalls.get());

            service.set(tuner, "frequency_correction_ppm", 1.5);
            Thread.sleep(100);
            assertEquals(0.0, configuration.getFrequencyCorrection());
            assertTrue(service.hasPending(tuner));

            channels.mCount.set(0);
            controller.setLockedSampleRate(true);
            Thread.sleep(100);
            assertEquals(0.0, configuration.getFrequencyCorrection());
            controller.setLockedSampleRate(false);
            await(Duration.ofSeconds(3), () -> configuration.getFrequencyCorrection() == 1.5);
            assertEquals(1, controller.mApplyCalls.get());
        }
    }

    @Test
    void slowPersistenceDoesNotHoldAllocationReservationOrControllerLock() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(1);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        configuration.setGain(Gain.CUSTOM);
        tuner.setTunerConfiguration(configuration);
        tuner.install(new FakeTuner(controller, tuner, channels));
        CountDownLatch persistenceStarted = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        CountDownLatch persistenceCompleted = new CountDownLatch(1);

        try(TunerSettingsService service = new TunerSettingsService(() ->
        {
            persistenceStarted.countDown();
            try
            {
                if(!releasePersistence.await(3, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("persistence wait timed out");
                }
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("persistence interrupted", e);
            }
            finally
            {
                persistenceCompleted.countDown();
            }
        }, ignored -> true, ignored -> null))
        {
            service.set(tuner, "if_gain", 7);
            try
            {
                assertTrue(persistenceStarted.await(3, TimeUnit.SECONDS));
                assertTrue(tuner.tryAcquireForAllocation(), "a slow save must not reject channel allocation");
                try
                {
                    assertTrue(controller.getLock().tryLock(), "a slow save must not hold the tuner controller");
                    controller.getLock().unlock();
                }
                finally
                {
                    tuner.releaseAfterAllocation();
                }
            }
            finally
            {
                releasePersistence.countDown();
            }

            assertEquals(7, configuration.getIFGain());
            assertTrue(persistenceCompleted.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void rspBasebandGainAppliesLiveWhileLnaAndRateWaitForIdle() throws Exception
    {
        AtomicInteger gainCalls = new AtomicInteger();
        AtomicInteger sampleRateCalls = new AtomicInteger();
        IControlRsp1 control = (IControlRsp1)Proxy.newProxyInstance(IControlRsp1.class.getClassLoader(),
            new Class<?>[]{IControlRsp1.class}, (proxy, method, arguments) -> switch(method.getName())
            {
                case "getMaximumLNASetting" -> 5;
                case "setGain" -> { gainCalls.incrementAndGet(); yield null; }
                case "setSampleRate" -> { sampleRateCalls.incrementAndGet(); yield null; }
                default -> defaultValue(method.getReturnType());
            });
        Rsp1TunerController controller = new Rsp1TunerController(control, null);
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        Rsp1TunerConfiguration configuration = new Rsp1TunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(1);
        tuner.install(new FakeTuner(controller, tuner, channels));

        try(TunerSettingsService service = new TunerSettingsService(() -> { }, ignored -> true, ignored -> null))
        {
            assertEquals(5, service.describe(tuner).stream().filter(setting -> setting.id().equals("lna"))
                .findFirst().orElseThrow().maximum());
            assertTrue(service.describe(tuner).stream().filter(setting -> setting.id().equals("lna"))
                .findFirst().orElseThrow().requiresIdle());
            service.set(tuner, "baseband_gain_reduction", 42);
            await(Duration.ofSeconds(3), () -> gainCalls.get() == 1);
            assertEquals(42, configuration.getBasebandGainReduction());

            service.set(tuner, "lna", 2);
            Thread.sleep(100);
            assertEquals(1, gainCalls.get());
            channels.mCount.set(0);
            await(Duration.ofSeconds(3), () -> gainCalls.get() == 2);
            assertEquals(2, configuration.getLNA());

            channels.mCount.set(1);
            service.set(tuner, "sample_rate", RspSampleRate.RATE_1_000.name());
            Thread.sleep(100);
            assertEquals(0, sampleRateCalls.get());
            channels.mCount.set(0);
            await(Duration.ofSeconds(3), () -> sampleRateCalls.get() == 1);
            assertEquals(RspSampleRate.RATE_1_000, configuration.getSampleRate());
            assertEquals(RspSampleRate.RATE_1_000.getEffectiveSampleRate(), controller.getSampleRate());
        }
    }

    @Test
    void queuedRspLnaIsRejectedIfRetuneNarrowsItsValidRange() throws Exception
    {
        AtomicInteger maximumLna = new AtomicInteger(9);
        AtomicInteger gainCalls = new AtomicInteger();
        IControlRsp1 control = (IControlRsp1)Proxy.newProxyInstance(IControlRsp1.class.getClassLoader(),
            new Class<?>[]{IControlRsp1.class}, (proxy, method, arguments) -> switch(method.getName())
            {
                case "getMaximumLNASetting" -> maximumLna.get();
                case "setGain" -> { gainCalls.incrementAndGet(); yield null; }
                default -> defaultValue(method.getReturnType());
            });
        Rsp1TunerController controller = new Rsp1TunerController(control, null);
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        Rsp1TunerConfiguration configuration = new Rsp1TunerConfiguration(tuner.getId());
        configuration.setLNA(2);
        tuner.setTunerConfiguration(configuration);
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(0);
        tuner.install(new FakeTuner(controller, tuner, channels));
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> true, ignored -> null))
        {
            assertTrue(tuner.tryAcquireForAllocation());
            try
            {
                service.set(tuner, "lna", 8);
                maximumLna.set(6); // A retune changed the device's frequency-dependent LNA range.
            }
            finally
            {
                tuner.releaseAfterAllocation();
            }

            await(Duration.ofSeconds(3), () -> service.error(tuner) != null);
            assertEquals(2, configuration.getLNA());
            assertEquals(0, gainCalls.get());
            assertEquals(0, saves.get());
            assertFalse(service.hasPending(tuner));
            assertTrue(service.error(tuner).contains("maximum 6"));
        }
    }

    @Test
    void rspDuoSharedRateWaitsForDisabledSlaveAndKeepsBothConfigurationsAligned() throws Exception
    {
        DeviceInfo masterInfo = new DeviceInfo(DeviceType.RSPduo, "shared-test");
        masterInfo.setDeviceSelectionMode(DeviceSelectionMode.MASTER_TUNER_1);
        DeviceInfo slaveInfo = masterInfo.copy();
        slaveInfo.setDeviceSelectionMode(DeviceSelectionMode.SLAVE_TUNER_2);
        DiscoveredRspDuoTuner1 master = new DiscoveredRspDuoTuner1(masterInfo);
        DiscoveredRspDuoTuner2 slave = new DiscoveredRspDuoTuner2(slaveInfo);
        RspDuoTuner1Configuration masterConfiguration = new RspDuoTuner1Configuration(master.getId());
        RspDuoTuner2Configuration slaveConfiguration = new RspDuoTuner2Configuration(slave.getId());
        masterConfiguration.setSampleRate(RspSampleRate.DUO_RATE_2_000);
        slaveConfiguration.setSampleRate(RspSampleRate.DUO_RATE_2_000);
        slaveConfiguration.setExternalReferenceOutput(true);
        master.setTunerConfiguration(masterConfiguration);
        slave.setTunerConfiguration(slaveConfiguration);
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> true, id -> id.equals(master.getId()) ? master : id.equals(slave.getId()) ? slave : null))
        {
            assertEquals(false, service.describe(slave).stream()
                .filter(setting -> setting.id().equals("external_reference")).findFirst().orElseThrow().value());
            service.set(master, "sample_rate", RspSampleRate.DUO_RATE_1_000.name());
            await(Duration.ofSeconds(3), () -> service.error(master) != null);
            assertEquals(RspSampleRate.DUO_RATE_2_000, masterConfiguration.getSampleRate());
            assertTrue(service.hasPending(master));
            assertTrue(service.error(master).contains("Disable RSPduo tuner 2"));

            slave.setEnabled(false);
            await(Duration.ofSeconds(3), () -> masterConfiguration.getSampleRate() == RspSampleRate.DUO_RATE_1_000);
            assertEquals(RspSampleRate.DUO_RATE_1_000, slaveConfiguration.getSampleRate());
            assertEquals(1, saves.get());
            assertEquals(null, service.error(master));
        }
    }

    @Test
    void failedPersistenceAfterConfigAcceptanceDoesNotFalselyRestorePreviousValue() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner();
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);

        try(TunerSettingsService service = new TunerSettingsService(() ->
            { throw new IllegalStateException("test persistence failure"); }, ignored -> true, ignored -> null))
        {
            service.set(tuner, "automatic_ppm", false);
            await(Duration.ofSeconds(3), () -> service.error(tuner) != null);
            assertFalse(configuration.getAutoPPMCorrectionEnabled());
            assertFalse(service.hasPending(tuner));
            assertTrue(service.error(tuner).contains("could not save"));
        }
    }

    @Test
    void frequencyLimitsWaitForIdleThenMoveTogetherAndResetToHardwareBounds() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        ExtentTrackingAirspyController controller = new ExtentTrackingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(1);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        configuration.setMinimumFrequency(100_000_000L);
        configuration.setMaximumFrequency(200_000_000L);
        tuner.setTunerConfiguration(configuration);
        tuner.install(new FakeTuner(controller, tuner, channels));
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> true, ignored -> null))
        {
            service.set(tuner, TunerSettingCatalog.MINIMUM_FREQUENCY, 195.000001);
            Thread.sleep(100);
            assertEquals(100_000_000L, configuration.getMinimumFrequency());
            assertTrue(service.hasPending(tuner));

            channels.mCount.set(0);
            await(Duration.ofSeconds(3), () -> configuration.getMinimumFrequency() == 195_000_001L);
            assertEquals(205_000_001L, configuration.getMaximumFrequency());
            assertEquals(195_000_001L, configuration.getFrequency());
            assertEquals(195_000_001L, controller.getFrequency());
            assertEquals(195_000_001L, controller.getMinimumFrequency());
            assertEquals(205_000_001L, controller.getMaximumFrequency());
            assertEquals(1, saves.get());
            assertFalse(service.hasPending(tuner));

            channels.mCount.set(1);
            service.set(tuner, TunerSettingCatalog.RESET_FREQUENCY_EXTENTS, true);
            assertEquals(true, service.describe(tuner).stream().filter(setting ->
                setting.id().equals(TunerSettingCatalog.RESET_FREQUENCY_EXTENTS))
                .findFirst().orElseThrow().pendingValue());
            Thread.sleep(100);
            assertEquals(195_000_001L, configuration.getMinimumFrequency());
            channels.mCount.set(0);
            await(Duration.ofSeconds(3), () -> configuration.getMinimumFrequency() ==
                AirspyTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ);
            assertEquals(AirspyTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ,
                configuration.getMaximumFrequency());
            assertEquals(2, saves.get());
            assertFalse(service.hasPending(tuner));
            assertEquals(null, service.error(tuner));
        }
    }

    @Test
    void pairedFrequencyLimitsRejectAnInvalidGapWithoutChangingControllerOrPersistence() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner();
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        configuration.setMinimumFrequency(100_000_000L);
        configuration.setMaximumFrequency(200_000_000L);
        tuner.setTunerConfiguration(configuration);
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> true, ignored -> null))
        {
            assertTrue(tuner.tryAcquireForAllocation());
            try
            {
                service.set(tuner, TunerSettingCatalog.MINIMUM_FREQUENCY, 195.0);
                service.set(tuner, TunerSettingCatalog.MAXIMUM_FREQUENCY, 200.0);
            }
            finally
            {
                tuner.releaseAfterAllocation();
            }

            await(Duration.ofSeconds(3), () -> !service.hasPending(tuner));
            assertEquals(100_000_000L, configuration.getMinimumFrequency());
            assertEquals(200_000_000L, configuration.getMaximumFrequency());
            assertEquals(0, saves.get());
            assertTrue(service.error(tuner).contains("span at least the sample rate"));
        }
    }

    @Test
    void frequencyLimitActionCanBeCancelledBeforeIdleAndFailedRetuneRestoresPreviousBounds() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        ExtentTrackingAirspyController controller = new ExtentTrackingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(1);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        configuration.setMinimumFrequency(100_000_000L);
        configuration.setMaximumFrequency(200_000_000L);
        tuner.setTunerConfiguration(configuration);
        tuner.install(new FakeTuner(controller, tuner, channels));
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> true, ignored -> null))
        {
            service.set(tuner, TunerSettingCatalog.RESET_FREQUENCY_EXTENTS, true);
            assertEquals("cancelled", service.cancel(tuner,
                TunerSettingCatalog.RESET_FREQUENCY_EXTENTS).status());
            channels.mCount.set(0);
            Thread.sleep(100);
            assertEquals(100_000_000L, configuration.getMinimumFrequency());
            assertEquals(0, saves.get());

            controller.mRejectRetune = true;
            service.set(tuner, TunerSettingCatalog.MINIMUM_FREQUENCY, 195.0);
            await(Duration.ofSeconds(3), () -> service.error(tuner) != null && !service.hasPending(tuner));
            assertEquals(100_000_000L, configuration.getMinimumFrequency());
            assertEquals(200_000_000L, configuration.getMaximumFrequency());
            assertEquals(AirspyTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ,
                controller.getMinimumFrequency());
            assertEquals(AirspyTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ,
                controller.getMaximumFrequency());
            assertEquals(101_100_000L, controller.getFrequency());
            assertEquals(0, saves.get());
        }
    }

    @Test
    void queuedIdleSettingDoesNotReserveAnActiveTunerOnRetries() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(1);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);
        tuner.install(new FakeTuner(new ExtentTrackingAirspyController(), tuner, channels));

        try(TunerSettingsService service = new TunerSettingsService(() -> { }, ignored -> true, ignored -> null))
        {
            service.set(tuner, TunerSettingCatalog.MINIMUM_FREQUENCY, 100.0);
            Thread.sleep(1_200);
            assertTrue(service.hasPending(tuner));
            assertEquals(0, tuner.mReservationAttempts.get(),
                "A busy tuner must not be reserved by periodic idle-setting retries");
        }
    }

    @Test
    void queuedSampleRateRechecksFrequencySpanBeforeChangingSavedConfiguration() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner();
        HackRFTunerConfiguration configuration = new HackRFTunerConfiguration(tuner.getId());
        configuration.setMinimumFrequency(100_000_000L);
        configuration.setMaximumFrequency(120_000_000L);
        tuner.setTunerConfiguration(configuration);
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> true, ignored -> null))
        {
            assertTrue(tuner.tryAcquireForAllocation());
            try
            {
                service.set(tuner, "sample_rate", HackRFSampleRate.RATE_8_0.name());
                configuration.setMaximumFrequency(102_000_000L);
            }
            finally
            {
                tuner.releaseAfterAllocation();
            }

            await(Duration.ofSeconds(3), () -> !service.hasPending(tuner));
            assertEquals(HackRFSampleRate.RATE_5_0, configuration.getSampleRate());
            assertEquals(0, saves.get());
            assertTrue(service.error(tuner).contains("widen them first"));
        }
    }

    @Test
    void closeCancelsUnclaimedSettingsAndRejectsFurtherRequests() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner();
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);
        AtomicInteger saves = new AtomicInteger();
        TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> true, ignored -> null);

        assertTrue(tuner.tryAcquireForAllocation());
        try
        {
            service.set(tuner, "automatic_ppm", false);
            service.close();
        }
        finally
        {
            tuner.releaseAfterAllocation();
        }

        assertTrue(configuration.getAutoPPMCorrectionEnabled());
        assertFalse(service.hasPending(tuner));
        assertEquals(0, saves.get());
        assertThrows(IllegalStateException.class, () -> service.set(tuner, "automatic_ppm", false));
        assertThrows(IllegalStateException.class, () -> service.requestEnabled(tuner, true));
    }

    @Test
    void closeWaitsForClaimedHardwareWriteAndSave() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        BlockingAirspyController controller = new BlockingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(1);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        configuration.setGain(Gain.CUSTOM);
        tuner.setTunerConfiguration(configuration);
        tuner.install(new FakeTuner(controller, tuner, channels));
        AtomicInteger saves = new AtomicInteger();
        TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            ignored -> true, ignored -> null);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        try
        {
            service.set(tuner, "if_gain", 7);
            assertTrue(controller.mEntered.await(3, TimeUnit.SECONDS));
            Thread closer = new Thread(() ->
            {
                try
                {
                    service.close();
                }
                catch(Throwable failure)
                {
                    closeFailure.set(failure);
                }
                finally
                {
                    closeFinished.countDown();
                }
            });
            closer.start();

            assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS),
                "close must not return during an in-flight hardware write");
            controller.mRelease.countDown();
            assertTrue(closeFinished.await(3, TimeUnit.SECONDS));
            assertEquals(null, closeFailure.get());
            assertTrue(controller.mCompleted.get());
            assertEquals(1, saves.get(), "a claimed setting must finish persistence before shutdown returns");
        }
        finally
        {
            controller.mRelease.countDown();
            service.close();
        }
    }

    @Test
    void closeReportsWhenClaimedHardwareWriteDoesNotQuiesceWithinBound() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        BlockingAirspyController controller = new BlockingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(1);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        configuration.setGain(Gain.CUSTOM);
        tuner.setTunerConfiguration(configuration);
        tuner.install(new FakeTuner(controller, tuner, channels));
        TunerSettingsService service = new TunerSettingsService(() -> { }, ignored -> true, ignored -> null);

        try
        {
            service.set(tuner, "if_gain", 7);
            assertTrue(controller.mEntered.await(3, TimeUnit.SECONDS));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.closeWithin(100, TimeUnit.MILLISECONDS));
            assertTrue(failure.getMessage().contains("hardware maintenance may still be active"));
            assertFalse(controller.mCompleted.get());
        }
        finally
        {
            controller.mRelease.countDown();
            service.closeWithin(3, TimeUnit.SECONDS);
        }
        assertTrue(controller.mCompleted.get());
    }

    private static Object defaultValue(Class<?> type)
    {
        if(type == boolean.class) return false;
        if(type == int.class) return 0;
        if(type == long.class) return 0L;
        if(type == float.class) return 0.0f;
        if(type == double.class) return 0.0d;
        return null;
    }

    private static void await(Duration limit, java.util.function.BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + limit.toNanos();

        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(20);
        }

        assertTrue(condition.getAsBoolean(), "timed out waiting for tuner settings worker");
    }

    private static final class FakeDiscoveredTuner extends DiscoveredTuner
    {
        private final AtomicInteger mReservationAttempts = new AtomicInteger();

        private FakeDiscoveredTuner()
        {
            this(true);
        }

        private FakeDiscoveredTuner(boolean disabled)
        {
            if(disabled)
            {
                setEnabled(false);
            }
        }

        private void install(Tuner tuner)
        {
            mTuner = tuner;
        }

        @Override
        boolean tryAcquireForAllocation()
        {
            mReservationAttempts.incrementAndGet();
            return super.tryAcquireForAllocation();
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public String getId()
        {
            return "settings-test";
        }

        @Override
        public void start()
        {
        }
    }

    private static final class TrackingAirspyController extends AirspyTunerController
    {
        private final AtomicInteger mIfGainCalls = new AtomicInteger();
        private final AtomicInteger mApplyCalls = new AtomicInteger();

        private TrackingAirspyController()
        {
            super(0, "test", null);
        }

        @Override
        public void setIFGain(int value)
        {
            mIfGainCalls.incrementAndGet();
        }

        @Override
        public void apply(TunerConfiguration configuration) throws SourceException
        {
            mApplyCalls.incrementAndGet();
        }
    }

    private static final class BlockingAirspyController extends AirspyTunerController
    {
        private final CountDownLatch mEntered = new CountDownLatch(1);
        private final CountDownLatch mRelease = new CountDownLatch(1);
        private final AtomicBoolean mCompleted = new AtomicBoolean();

        private BlockingAirspyController()
        {
            super(0, "blocking-test", null);
        }

        @Override
        public void setIFGain(int value)
        {
            mEntered.countDown();
            try
            {
                if(!mRelease.await(3, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("Timed out waiting to finish hardware test write");
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Hardware test write was interrupted", exception);
            }
            mCompleted.set(true);
        }
    }

    private static final class ExtentTrackingAirspyController extends AirspyTunerController
    {
        private long mFrequency = 101_100_000L;
        private boolean mRejectRetune;

        private ExtentTrackingAirspyController()
        {
            super(0, "extents-test", null);
        }

        @Override
        public long getFrequency()
        {
            return mFrequency;
        }

        @Override
        public void setFrequency(long frequency) throws SourceException
        {
            if(mRejectRetune)
            {
                throw new SourceException("Simulated hardware retune failure");
            }
            if(frequency < getMinimumFrequency() || frequency > getMaximumFrequency())
            {
                throw new SourceException("Retuned outside frequency limits");
            }
            mFrequency = frequency;
        }
    }

    private static final class FakeTuner extends Tuner
    {
        private FakeTuner(TunerController controller, ITunerErrorListener listener,
                          ChannelSourceManager channelSourceManager)
        {
            super(controller, listener, channelSourceManager);
        }

        @Override public int getMaximumUSBBitsPerSecond() { return 0; }
        @Override public String getUniqueID() { return "settings-test"; }
        @Override public TunerClass getTunerClass() { return TunerClass.AIRSPY; }
        @Override public String getPreferredName() { return "Test Airspy"; }
        @Override public double getSampleSize() { return 12.0; }
    }

    private static final class CountingChannelManager extends ChannelSourceManager
    {
        private final AtomicInteger mCount = new AtomicInteger();

        @Override public SortedSet<TunerChannel> getTunerChannels() { return new TreeSet<>(); }
        @Override public String getStateDescription() { return "test"; }
        @Override public int getTunerChannelCount() { return mCount.get(); }
        @Override public void stopAllChannels() { mCount.set(0); }
        @Override public TunerChannelSource getSource(TunerChannel channel, ChannelSpecification specification,
                                                      String threadName) { return null; }
        @Override public void setErrorMessage(String message) { }
        @Override public void process(SourceEvent event) { }
    }
}
