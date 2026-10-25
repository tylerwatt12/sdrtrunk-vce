package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController.Gain;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.sdrplay.RspSampleRate;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerController;
import io.github.dsheirer.source.tuner.sdrplay.api.DeviceSelectionMode;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceType;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.IControlRspDuo;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner1Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner2Configuration;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.time.Duration;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

class TunerSettingsServiceTest
{
    @Test
    void passiveInventoryReadsDoNotRegisterOperatorSessions()
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(true);
        tuner.setTunerConfiguration(new AirspyTunerConfiguration(tuner.getId()));
        AtomicInteger trackedDeviceChecks = new AtomicInteger();
        try(TunerSettingsService service = new TunerSettingsService(() -> {}, candidate -> {
            trackedDeviceChecks.incrementAndGet();
            return false;
        }, ignored -> null))
        {
            assertNull(service.error(tuner));
            assertNull(service.transition(tuner));
            assertNull(service.restoreResult(tuner));
            assertNull(service.operation(tuner));
            assertTrue(service.stoppedChannels(tuner).isEmpty());
            assertFalse(service.verifyBrowse(tuner, "expired-browser"));
            assertFalse(service.describe(tuner).isEmpty());
            service.expireBrowsing();
            assertEquals(0, trackedDeviceChecks.get(), "passive reads cannot retain a discovered device");
        }
    }

    @Test
    void retiresAbsentCompletedSessionsButPreservesAnInFlightOperation() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(true);
        tuner.setTunerConfiguration(new AirspyTunerConfiguration(tuner.getId()));
        tuner.prepareRestart(new TrackingAirspyController(), new CountingChannelManager());
        tuner.mBlockStart = true;
        AtomicBoolean present = new AtomicBoolean(true);
        try(TunerSettingsService service = new TunerSettingsService(() -> {},
            candidate -> candidate == tuner && present.get(), ignored -> null))
        {
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            assertTrue(tuner.mStartEntered.await(2, TimeUnit.SECONDS));
            present.set(false);
            service.expireBrowsing();
            assertEquals("running", service.operation(tuner).state());
            tuner.mReleaseStart.countDown();
            await(Duration.ofSeconds(2), () -> {
                service.expireBrowsing();
                return service.operation(tuner) == null;
            });
            assertNull(service.transition(tuner));
        }
        finally { tuner.mReleaseStart.countDown(); tuner.stop(); }
    }

    @Test
    void absentTunerSessionRetainsItsStoppedChannelRecoveryUntilRestored() throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel channel = channels.addStandard("Recovery control", 851_012_500L);
        AtomicBoolean present = new AtomicBoolean(true);
        try(TunerSettingsService service = new TunerSettingsService(() -> {},
            candidate -> candidate == tuner && present.get(), ignored -> null, channels, () -> List.of(tuner)))
        {
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            present.set(false);
            service.expireBrowsing();
            assertEquals(List.of(channel.getConfigurationId()),
                service.stoppedChannels(tuner).stream().map(TunerSettingsService.ChannelInfo::id).toList());
            assertNotNull(service.operation(tuner));
            present.set(true);
            service.restoreStoppedChannels(tuner);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            assertTrue(service.stoppedChannels(tuner).isEmpty());
        }
        finally { channels.close(); }
    }

    @Test
    void spectrumObservesStoppedRecordingWithoutStartingItOrChangingItsMode() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(true);
        tuner.testTunerClass = TunerClass.RECORDING_TUNER;
        AtomicInteger saves = new AtomicInteger();
        try(TunerSettingsService service = service(tuner, saves))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            assertFalse(lease.canTune());
            assertFalse(lease.takeover());
            assertEquals(List.of(), lease.stoppedChannels());
            assertEquals(DiscoveredTuner.OperatorState.DISABLED, tuner.getOperatorState());
            assertFalse(tuner.hasTuner());
            assertEquals(0, tuner.mStartCalls.get());
            assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
            service.browse(tuner, lease.leaseId()).get(2, TimeUnit.SECONDS);
            service.releaseBrowse(tuner, lease.leaseId(), true).get(2, TimeUnit.SECONDS);
            assertEquals(DiscoveredTuner.OperatorState.DISABLED, tuner.getOperatorState());
            assertEquals(0, tuner.mStartCalls.get());
            assertEquals(0, saves.get());
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.browse(tuner, null, true));
        }
    }

    @Test
    void recordingBrowsePreservesActiveChannelsAndTheExistingOperatorMode() throws Exception
    {
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(3);
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), channels);
        tuner.testTunerClass = TunerClass.RECORDING_TUNER;
        AtomicInteger saves = new AtomicInteger();
        try(TunerSettingsService service = service(tuner, saves))
        {
            for(boolean setup: List.of(false, true))
            {
                if(setup) assertTrue(tuner.holdForSetup());
                var previous = tuner.getOperatorState();
                Tuner runtime = tuner.getTuner();
                var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
                assertFalse(lease.canTune());
                assertFalse(lease.takeover());
                assertEquals(3, channels.getTunerChannelCount());
                assertEquals(previous, tuner.getOperatorState());
                service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
                assertEquals(previous, tuner.getOperatorState());
                assertEquals(runtime, tuner.getTuner());
                assertEquals(3, channels.getTunerChannelCount());
            }
            assertEquals(0, saves.get());
        }
    }

    @Test
    void searchTuningReservesIdleHardwareAndDoesNotSaveHops() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, channels);
        AtomicInteger saves = new AtomicInteger();
        try(TunerSettingsService service = service(tuner, saves))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            long savedCenter = tuner.getTunerConfiguration().getFrequency();
            try(var hold = service.holdForSearch(tuner, lease.leaseId()))
            {
                hold.tune(851_000_000);
                assertTrue(hold.valid());
                assertEquals(851_000_000, controller.getFrequency());
                assertEquals(savedCenter, tuner.getTunerConfiguration().getFrequency());
                assertEquals(0, saves.get());
                assertThrows(TunerSettingsService.SettingUnavailableException.class,
                    () -> service.set(tuner, "frequency_mhz", 852.0, lease.leaseId()));
                assertThrows(TunerSettingsService.SettingUnavailableException.class,
                    () -> service.releaseBrowse(tuner, lease.leaseId(), false));
                channels.mCount.set(1);
                assertThrows(TunerSettingsService.SettingUnavailableException.class, () -> hold.tune(852_000_000));
                assertEquals(851_000_000, controller.getFrequency());
                channels.mCount.set(0);
                hold.tune(852_000_000);
                assertTrue(hold.valid());
            }
            assertFalse(tuner.isDiscoveryHeld());
            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void recordingSearchLeaseKeepsFixedCenterAndNeverOffersRetuning() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        FakeDiscoveredTuner tuner = runningTuner(controller,new CountingChannelManager());
        tuner.testTunerClass = TunerClass.RECORDING_TUNER;
        AtomicInteger saves = new AtomicInteger();
        try(TunerSettingsService service = service(tuner,saves))
        {
            long center = controller.getFrequency();
            var lease = service.browse(tuner,null).get(2,TimeUnit.SECONDS);
            assertFalse(lease.canTune());
            try(var hold = service.holdForSearch(tuner,lease.leaseId()))
            {
                hold.tune(center); assertTrue(hold.valid());
                assertThrows(TunerSettingsService.SettingUnavailableException.class,() -> hold.tune(center+25_000));
                assertEquals(center,controller.getFrequency()); assertEquals(0,saves.get());
            }
            service.releaseBrowse(tuner,lease.leaseId(),false).get(2,TimeUnit.SECONDS);
            assertFalse(tuner.isDiscoveryHeld()); assertEquals(center,controller.getFrequency());
        }
    }

    @Test
    void searchRejectsOccupiedAndLockedReceivers() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, channels);
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            channels.mCount.set(1);
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            String occupiedLeaseId = lease.leaseId();
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.holdForSearch(tuner, occupiedLeaseId));
            service.releaseBrowse(tuner, lease.leaseId(), false).get();
            channels.mCount.set(0);
            lease = service.browse(tuner, null).get();
            controller.setCenterFrequencyLocked(true);
            String id = lease.leaseId();
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.holdForSearch(tuner, id));
            controller.setCenterFrequencyLocked(false);
            service.releaseBrowse(tuner, id, false).get();
        }
    }

    @Test
    void searchCannotTuneAfterLeaseExpiryOrWhileControllerIsContended() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        FakeDiscoveredTuner tuner = runningTuner(controller, new CountingChannelManager());
        AtomicLong clock = new AtomicLong(1000);
        try(TunerSettingsService service = new TunerSettingsService(() -> {}, candidate -> candidate == tuner,
            ignored -> tuner, null, () -> List.of(tuner), clock::get))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            long originalCenter = controller.getFrequency();
            try(var hold = service.holdForSearch(tuner, lease.leaseId()))
            {
                CountDownLatch locked = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                Thread competitor = new Thread(() ->
                {
                    controller.getLock().lock();
                    locked.countDown();
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                    finally { controller.getLock().unlock(); }
                });
                competitor.start();
                assertTrue(locked.await(1, TimeUnit.SECONDS));
                try
                {
                    assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                        assertThrows(TunerSettingsService.SettingUnavailableException.class,
                            () -> hold.tune(851_000_000)));
                }
                finally { release.countDown(); competitor.join(1000); }
                hold.tune(852_000_000);
                clock.addAndGet(31_000);
                assertThrows(TunerSettingsService.SettingUnavailableException.class,
                    () -> hold.tune(851_000_000));
                assertEquals(852_000_000, controller.getFrequency());
                hold.restoreSearchCenter();
                assertEquals(originalCenter, controller.getFrequency());
                assertEquals(2, controller.mFrequencyCalls.get());
            }
            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void spectrumBorrowsDisabledHardwareAndRestoresWithoutPersisting() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(true);
        tuner.setTunerConfiguration(new AirspyTunerConfiguration(tuner.getId()));
        CountingChannelManager channels = new CountingChannelManager();
        tuner.prepareRestart(new TrackingAirspyController(), channels);
        AtomicInteger saves = new AtomicInteger();
        try(TunerSettingsService service = service(tuner, saves))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            assertTrue(lease.canTune());
            assertEquals(DiscoveredTuner.OperatorState.SETUP, tuner.getOperatorState());
            assertFalse(tuner.isAvailableForAllocation());
            assertEquals(0, channels.getTunerChannelCount());
            assertEquals(1, tuner.mStartCalls.get());
            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
            assertEquals(DiscoveredTuner.OperatorState.DISABLED, tuner.getOperatorState());
            assertEquals(0, saves.get());
        }
    }

    @Test
    void occupiedSpectrumRemainsReadOnlyAndNeverStopsChannels() throws Exception
    {
        CountingChannelManager channels = new CountingChannelManager();
        channels.mCount.set(2);
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), channels);
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            assertFalse(lease.canTune());
            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());
            assertEquals(2, channels.mCount.get());
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(tuner, "frequency_mhz", 851.0, lease.leaseId()));
            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
            assertEquals(2, channels.mCount.get());
            assertTrue(tuner.isAvailableForAllocation());
        }
    }

    @Test
    void takeoverStopsAndRestoresOnlyItsRememberedChannelsAndCenterLock() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, sources);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(true);
        controller.setCenterFrequencyLocked(true);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel original = channels.addStandard("County control", 851_012_500L);
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = service(tuner, saves, channels))
        {
            var lease = service.browse(tuner, null, true).get(2, TimeUnit.SECONDS);

            assertTrue(lease.takeover());
            assertTrue(lease.canTune());
            assertEquals(original.getConfigurationId(), lease.stoppedChannels().getFirst().id());
            assertEquals(0, sources.mCount.get());
            assertEquals(DiscoveredTuner.OperatorState.SETUP, tuner.getOperatorState());
            assertTrue(configuration.isCenterFrequencyLocked(), "temporary unlock must never enter saved config");
            assertFalse(controller.isCenterFrequencyLocked());
            assertEquals(false, service.describe(tuner).stream().filter(setting ->
                "center_frequency_locked".equals(setting.id())).findFirst().orElseThrow().value());
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(tuner, "center_frequency_locked", true, lease.leaseId()));
            assertEquals(0, saves.get());

            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);

            assertEquals(List.of("stop:" + original.getConfigurationId(),
                "start:" + original.getConfigurationId()), channels.operations());
            assertEquals(1, sources.mCount.get());
            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());
            assertTrue(configuration.isCenterFrequencyLocked());
            assertTrue(controller.isCenterFrequencyLocked());
            assertTrue(service.stoppedChannels(tuner).isEmpty());
        }
        finally { channels.close(); }
    }

    @Test
    void takeoverHandoffStartsSelectedChannelBeforeResumingCompatibleOriginalChannels() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, sources);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(true);
        controller.setCenterFrequencyLocked(true);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel original = channels.addStandard("County control", 851_012_500L);

        try(TunerSettingsService service = service(tuner, new AtomicInteger(), channels))
        {
            var lease = service.browse(tuner, null, true).get(2, TimeUnit.SECONDS);
            String result = service.handoffBrowse(tuner, lease.leaseId(), () ->
            {
                channels.record("selected-start");
                assertEquals(0, sources.mCount.get());
                assertTrue(configuration.isCenterFrequencyLocked());
                assertTrue(controller.isCenterFrequencyLocked());
                return "started";
            }).get(2, TimeUnit.SECONDS);

            assertEquals("started", result);
            assertEquals(List.of("stop:" + original.getConfigurationId(),
                "selected-start", "start:" + original.getConfigurationId()), channels.operations());
        }
        finally { channels.close(); }
    }

    @Test
    void expiredTakeoverRestoresChannelsAndCenterLock() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, sources);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(true);
        controller.setCenterFrequencyLocked(true);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        channels.addStandard("County control", 851_012_500L);
        AtomicLong clock = new AtomicLong(1000);

        try(TunerSettingsService service = new TunerSettingsService(() -> {}, candidate -> candidate == tuner,
            ignored -> tuner, channels, () -> List.of(tuner), clock::get))
        {
            var lease = service.browse(tuner, null, true).get(2, TimeUnit.SECONDS);
            clock.addAndGet(31_000);
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
            service.expireBrowsing();

            await(Duration.ofSeconds(2), () -> sources.mCount.get() == 1);
            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());
            assertTrue(configuration.isCenterFrequencyLocked());
            assertTrue(controller.isCenterFrequencyLocked());
        }
        finally { channels.close(); }
    }

    @Test
    void serviceCloseRestoresAnOutstandingTakeover() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, sources);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(true);
        controller.setCenterFrequencyLocked(true);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        channels.addStandard("County control", 851_012_500L);
        TunerSettingsService service = service(tuner, new AtomicInteger(), channels);

        try
        {
            service.browse(tuner, null, true).get(2, TimeUnit.SECONDS);
            service.close();

            assertEquals(1, sources.mCount.get());
            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());
            assertTrue(configuration.isCenterFrequencyLocked());
            assertTrue(controller.isCenterFrequencyLocked());
        }
        finally
        {
            service.close();
            channels.close();
        }
    }

    @Test
    void failedTakeoverCleanupIndependentlyRestoresLocksAndRetainsRecoveryChannels() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, sources);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(true);
        controller.setCenterFrequencyLocked(true);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel original = channels.addStandard("County control", 851_012_500L);
        tuner.mFailEnterLive = true;
        controller.mFailNextCenterUnlock = true;
        channels.mFailStarts = true;

        try(TunerSettingsService service = service(tuner, new AtomicInteger(), channels))
        {
            assertThrows(CompletionException.class, () -> service.browse(tuner, null, true).join());

            assertEquals(List.of("stop:" + original.getConfigurationId()), channels.operations());
            assertEquals(original.getConfigurationId(), service.stoppedChannels(tuner).getFirst().id());
            assertTrue(configuration.isCenterFrequencyLocked());
            assertTrue(controller.isCenterFrequencyLocked());

            channels.mFailStarts = false;
            service.restoreStoppedChannels(tuner);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
        }
        finally { channels.close(); }
    }

    @Test
    void takeoverRetunePersistsTheOriginalCenterLockInsteadOfTheTemporaryRuntimeUnlock() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, sources);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(true);
        controller.setCenterFrequencyLocked(true);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        channels.addStandard("County control", 851_012_500L);
        AtomicInteger saves = new AtomicInteger();
        AtomicBoolean persistedLock = new AtomicBoolean();

        try(TunerSettingsService service = new TunerSettingsService(() -> {
            saves.incrementAndGet();
            persistedLock.set(configuration.isCenterFrequencyLocked());
        }, candidate -> candidate == tuner, ignored -> tuner, channels, () -> List.of(tuner)))
        {
            var lease = service.browse(tuner, null, true).get(2, TimeUnit.SECONDS);
            var center = service.describe(tuner).stream().filter(setting ->
                "frequency_mhz".equals(setting.id())).findFirst().orElseThrow();
            assertTrue(center.editable());

            assertEquals("applied", service.set(tuner, "frequency_mhz", 120.1, lease.leaseId()).status());
            assertEquals(1, saves.get());
            assertTrue(persistedLock.get());
            assertTrue(configuration.isCenterFrequencyLocked());
            assertFalse(controller.isCenterFrequencyLocked());

            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
            assertTrue(controller.isCenterFrequencyLocked());
        }
        finally { channels.close(); }
    }

    @Test
    void failedCenterLockRestoreKeepsTakeoverLeaseForRetry() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, sources);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(true);
        controller.setCenterFrequencyLocked(true);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel original = channels.addStandard("County control", 851_012_500L);

        try(TunerSettingsService service = service(tuner, new AtomicInteger(), channels))
        {
            var lease = service.browse(tuner, null, true).join();
            controller.mFailNextCenterLock = true;

            assertThrows(CompletionException.class,
                () -> service.releaseBrowse(tuner, lease.leaseId(), false).join());
            assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
            assertFalse(controller.isCenterFrequencyLocked());
            assertEquals(1, sources.mCount.get());

            service.releaseBrowse(tuner, lease.leaseId(), false).join();
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
            assertTrue(controller.isCenterFrequencyLocked());
            assertEquals(List.of("stop:" + original.getConfigurationId(),
                "start:" + original.getConfigurationId()), channels.operations());
        }
        finally { channels.close(); }
    }

    @Test
    void failedCenterLockRestoreDoesNotOverwriteALaterOperatorOnRetry()
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, sources);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(true);
        controller.setCenterFrequencyLocked(true);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        channels.addStandard("County control", 851_012_500L);

        try(TunerSettingsService service = service(tuner, new AtomicInteger(), channels))
        {
            var lease = service.browse(tuner, null, true).join();
            controller.mFailNextCenterLock = true;

            assertThrows(CompletionException.class,
                () -> service.releaseBrowse(tuner, lease.leaseId(), false).join());
            assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
            assertEquals(1, sources.mCount.get(), "mode and channel recovery completed before lock recovery failed");

            configuration.setCenterFrequencyLocked(false);
            assertTrue(tuner.enterLive(), "a later operator action advances tuner ownership");
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));

            service.releaseBrowse(tuner, lease.leaseId(), false).join();

            assertFalse(configuration.isCenterFrequencyLocked());
            assertFalse(controller.isCenterFrequencyLocked());
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
        }
        finally { channels.close(); }
    }

    @Test
    void lostTakeoverOwnershipDoesNotRestoreStaleCenterLockState()
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        FakeDiscoveredTuner tuner = runningTuner(controller, new CountingChannelManager());
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(true);
        controller.setCenterFrequencyLocked(true);

        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            var lease = service.browse(tuner, null, true).join();
            configuration.setCenterFrequencyLocked(false);
            assertTrue(tuner.enterLive(), "a later operator action takes ownership");

            service.releaseBrowse(tuner, lease.leaseId(), false).join();

            assertFalse(configuration.isCenterFrequencyLocked());
            assertFalse(controller.isCenterFrequencyLocked());
            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());
        }
    }

    @Test
    void takeoverNeverRestartsAChannelWhoseCapturedIncarnationWasAlreadyStopped() throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel concurrent = channels.addStandard("Concurrent stop", 851_012_500L);
        Channel owned = channels.addStandard("Owned stop", 852_012_500L);
        channels.mNotOwnedStops.add(concurrent);

        try(TunerSettingsService service = service(tuner, new AtomicInteger(), channels))
        {
            var lease = service.browse(tuner, null, true).get(2, TimeUnit.SECONDS);
            assertEquals(List.of(owned.getConfigurationId()),
                lease.stoppedChannels().stream().map(TunerSettingsService.ChannelInfo::id).toList());

            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
            assertEquals(1, sources.mCount.get());
            assertEquals(List.of("stop:" + concurrent.getConfigurationId(), "stop:" + owned.getConfigurationId(),
                "start:" + owned.getConfigurationId()), channels.operations());
        }
        finally { channels.close(); }
    }

    @Test
    void takeoverRollbackKeepsEverySuccessfulStopWhenLaterCleanupFails()
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel first = channels.addStandard("First", 851_012_500L);
        Channel second = channels.addStandard("Second", 852_012_500L);
        channels.mCleanupFailureStops.add(second);

        try(TunerSettingsService service = service(tuner, new AtomicInteger(), channels))
        {
            assertThrows(CompletionException.class, () -> service.browse(tuner, null, true).join());
            assertEquals(2, sources.mCount.get());
            assertTrue(service.stoppedChannels(tuner).isEmpty());
            assertEquals(List.of("stop:" + first.getConfigurationId(), "stop:" + second.getConfigurationId(),
                "start:" + first.getConfigurationId(), "start:" + second.getConfigurationId()),
                channels.operations());
        }
        finally { channels.close(); }
    }

    @Test
    void pairedTakeoverRestoresHealthyMemberAndLeavesFailedMemberRecoveryActionable()
    {
        DeviceInfo masterInfo = new DeviceInfo(DeviceType.RSPduo, "takeover-partial-pair");
        masterInfo.setDeviceSelectionMode(DeviceSelectionMode.MASTER_TUNER_1);
        DeviceInfo slaveInfo = masterInfo.copy();
        slaveInfo.setDeviceSelectionMode(DeviceSelectionMode.SLAVE_TUNER_2);
        TestRspDuoMaster master = new TestRspDuoMaster(masterInfo);
        TestRspDuoSlave slave = new TestRspDuoSlave(slaveInfo);
        master.setTunerConfiguration(new RspDuoTuner1Configuration(master.getId()));
        slave.setTunerConfiguration(new RspDuoTuner2Configuration(slave.getId()));
        master.installRunning();
        slave.installRunning();
        TestChannelProcessingManager channels = new TestChannelProcessingManager(
            Map.of(master, master.mChannels, slave, slave.mChannels));
        Channel masterChannel = channels.addStandard(master, "Master control", 851_012_500L);
        Channel slaveChannel = channels.addStandard(slave, "Slave control", 852_012_500L);

        try(TunerSettingsService service = new TunerSettingsService(() -> {},
            candidate -> candidate == master || candidate == slave,
            id -> id.equals(master.getId()) ? master : id.equals(slave.getId()) ? slave : null,
            channels, () -> List.of(master, slave)))
        {
            var lease = service.browse(master, null, true).join();
            slave.mFailEnterLive = true;
            assertThrows(CompletionException.class,
                () -> service.releaseBrowse(master, lease.leaseId(), false).join());

            assertEquals(DiscoveredTuner.OperatorState.LIVE, master.getOperatorState());
            assertEquals(1, master.mChannels.mCount.get());
            assertTrue(service.stoppedChannels(master).isEmpty());
            assertEquals(DiscoveredTuner.OperatorState.SETUP, slave.getOperatorState());
            assertEquals(0, slave.mChannels.mCount.get());
            assertEquals(List.of(slaveChannel.getConfigurationId()),
                service.stoppedChannels(slave).stream().map(TunerSettingsService.ChannelInfo::id).toList());
            assertTrue(channels.operations().contains("start:" + masterChannel.getConfigurationId()));
            assertTrue(service.verifyBrowse(master, lease.leaseId()));

            service.releaseBrowse(master, lease.leaseId(), false).join();
            assertFalse(service.verifyBrowse(master, lease.leaseId()));
            assertEquals(1, slave.mChannels.mCount.get());
            assertTrue(service.stoppedChannels(slave).isEmpty());
        }
        finally { channels.close(); }
    }

    @Test
    void failedChannelRestoreKeepsTakeoverLeaseUntilRetryCompletes() throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel retained = channels.addStandard("Retained", 851_012_500L);
        channels.mFailStarts = true;

        try(TunerSettingsService service = service(tuner, new AtomicInteger(), channels))
        {
            var lease = service.browse(tuner, null, true).join();
            assertThrows(CompletionException.class,
                () -> service.releaseBrowse(tuner, lease.leaseId(), false).join());
            assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
            assertEquals(List.of(retained.getConfigurationId()),
                service.stoppedChannels(tuner).stream().map(TunerSettingsService.ChannelInfo::id).toList());

            channels.mFailStarts = false;
            service.releaseBrowse(tuner, lease.leaseId(), false).join();
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
            assertTrue(service.stoppedChannels(tuner).isEmpty());
            assertEquals(1, sources.mCount.get());
        }
        finally { channels.close(); }
    }

    @Test
    void closingGateRejectsNewWorkWhileRestoringTakeover() throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        channels.addStandard("County control", 851_012_500L);
        TunerSettingsService service = service(tuner, new AtomicInteger(), channels);
        try
        {
            service.browse(tuner, null, true).join();
            channels.mBlockStarts = true;
            CompletableFuture<Void> closing = CompletableFuture.runAsync(service::close);
            assertTrue(channels.mStartEntered.await(1, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> service.browse(tuner, null));
            assertThrows(IllegalStateException.class, () -> service.set(tuner, "frequency_mhz", 120.1));
            channels.mReleaseStart.countDown();
            closing.get(2, TimeUnit.SECONDS);
            assertEquals(1, sources.mCount.get());
        }
        finally
        {
            channels.mReleaseStart.countDown();
            service.close();
            channels.close();
        }
    }

    @Test
    void failedCloseReopensAdmissionAndKeepsTakeoverOwnerForRetry()
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        channels.addStandard("County control", 851_012_500L);
        TunerSettingsService service = service(tuner, new AtomicInteger(), channels);
        try
        {
            var lease = service.browse(tuner, null, true).join();
            assertTrue(tuner.tryAcquireForAllocation());
            try
            {
                assertThrows(IllegalStateException.class, () -> service.closeWithin(2, TimeUnit.SECONDS));
            }
            finally { tuner.releaseAfterAllocation(); }

            assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
            service.releaseBrowse(tuner, lease.leaseId(), false).join();
            assertEquals(1, sources.mCount.get());
        }
        finally
        {
            service.close();
            channels.close();
        }
    }

    @Test
    void closePreservesFiniteRestartFailureUntilACompleteRetry()
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel original = channels.addStandard("County control", 851_012_500L);
        TunerSettingsService service = service(tuner, new AtomicInteger(), channels);
        try
        {
            var lease = service.browse(tuner, null, true).join();
            channels.mFailStarts = true;

            assertThrows(IllegalStateException.class, () -> service.closeWithin(2, TimeUnit.SECONDS));
            assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
            assertEquals(List.of(original.getConfigurationId()),
                service.stoppedChannels(tuner).stream().map(TunerSettingsService.ChannelInfo::id).toList());
            assertEquals(0, sources.mCount.get());

            channels.mFailStarts = false;
            service.closeWithin(2, TimeUnit.SECONDS);
            assertEquals(1, sources.mCount.get());
            assertTrue(service.stoppedChannels(tuner).isEmpty());
        }
        finally
        {
            service.close();
            channels.close();
        }
    }

    @Test
    void finalReceiverShutdownKeepsUnresumedSavedChannelsStoppedWithoutWeakeningOrdinaryClose()
        throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        TrackingAirspyController controller = new TrackingAirspyController();
        FakeDiscoveredTuner tuner = runningTuner(controller, sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel original = channels.addStandard("Prior control", 155_100_000L);
        TunerSettingsService service = service(tuner, new AtomicInteger(), channels);
        try
        {
            var lease = service.browse(tuner, null, true).get(2, TimeUnit.SECONDS);
            channels.mFailStarts = true;
            assertEquals("selected-running", service.handoffBrowse(tuner, lease.leaseId(), () -> "selected-running")
                .get(2, TimeUnit.SECONDS));
            assertEquals(List.of(original.getConfigurationId()),
                service.stoppedChannels(tuner).stream().map(TunerSettingsService.ChannelInfo::id).toList());
            assertThrows(IllegalStateException.class, () -> service.closeWithin(1, TimeUnit.SECONDS),
                "ordinary service close must still require explicit prior-channel recovery");

            List<String> beforeShutdown = channels.operations();
            long center = controller.getFrequency();
            channels.mFailStarts = false;
            service.closeForReceiverShutdownWithin(2, TimeUnit.SECONDS);

            assertEquals(beforeShutdown, channels.operations(), "final shutdown must not attempt old-channel restart");
            assertEquals(center, controller.getFrequency());
            assertEquals(List.of(original.getConfigurationId()),
                service.stoppedChannels(tuner).stream().map(TunerSettingsService.ChannelInfo::id).toList(),
                "the shutdown disposition retains its recovery evidence");
            assertEquals(List.of(original.getConfigurationId()), service.restoreResult(tuner).failed().stream()
                .map(TunerSettingsService.ChannelInfo::id).toList());
            assertThrows(IllegalStateException.class,
                () -> service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP));
        }
        finally { service.close(); channels.close(); }
    }

    @Test
    void finalReceiverShutdownTimesOutWithoutInterruptingAnAcceptedHardwareWriteAndCanRetry()
        throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(true);
        tuner.setTunerConfiguration(new AirspyTunerConfiguration(tuner.getId()));
        tuner.prepareRestart(new TrackingAirspyController(), new CountingChannelManager());
        tuner.mBlockStart = true;
        TunerSettingsService service = service(tuner, new AtomicInteger());
        try
        {
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            assertTrue(tuner.mStartEntered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                () -> service.closeForReceiverShutdownWithin(25, TimeUnit.MILLISECONDS));
            assertEquals("running", service.operation(tuner).state());
            assertFalse(tuner.mStartInterrupted.get());
            tuner.mReleaseStart.countDown();
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            service.closeForReceiverShutdownWithin(2, TimeUnit.SECONDS);
            assertFalse(tuner.mStartInterrupted.get());
            assertEquals(1, tuner.mStartCalls.get());
        }
        finally { tuner.mReleaseStart.countDown(); service.close(); }
    }

    @Test
    void closeRetryOwnsGenerationAdvancedByFailedPairedModeRestore()
    {
        DeviceInfo masterInfo = new DeviceInfo(DeviceType.RSPduo, "close-retry-pair");
        masterInfo.setDeviceSelectionMode(DeviceSelectionMode.MASTER_TUNER_1);
        DeviceInfo slaveInfo = masterInfo.copy();
        slaveInfo.setDeviceSelectionMode(DeviceSelectionMode.SLAVE_TUNER_2);
        TestRspDuoMaster master = new TestRspDuoMaster(masterInfo);
        TestRspDuoSlave slave = new TestRspDuoSlave(slaveInfo);
        master.setTunerConfiguration(new RspDuoTuner1Configuration(master.getId()));
        slave.setTunerConfiguration(new RspDuoTuner2Configuration(slave.getId()));
        master.installRunning();
        slave.installRunning();
        TestChannelProcessingManager channels = new TestChannelProcessingManager(
            Map.of(master, master.mChannels, slave, slave.mChannels));
        channels.addStandard(master, "Master control", 851_012_500L);
        Channel slaveChannel = channels.addStandard(slave, "Slave control", 852_012_500L);
        TunerSettingsService service = new TunerSettingsService(() -> {},
            candidate -> candidate == master || candidate == slave,
            id -> id.equals(master.getId()) ? master : id.equals(slave.getId()) ? slave : null,
            channels, () -> List.of(master, slave));
        try
        {
            var lease = service.browse(master, null, true).join();
            long setupGeneration = ((DiscoveredTuner)slave).operatorGeneration();
            slave.mAdvanceGenerationBeforeFail = true;

            assertThrows(IllegalStateException.class, () -> service.closeWithin(2, TimeUnit.SECONDS));
            assertTrue(((DiscoveredTuner)slave).operatorGeneration() > setupGeneration);
            assertTrue(service.verifyBrowse(master, lease.leaseId()));
            assertEquals(List.of(slaveChannel.getConfigurationId()),
                service.stoppedChannels(slave).stream().map(TunerSettingsService.ChannelInfo::id).toList());
            assertEquals(1, master.mChannels.mCount.get());
            assertEquals(0, slave.mChannels.mCount.get());

            service.closeWithin(2, TimeUnit.SECONDS);
            assertEquals(1, master.mChannels.mCount.get());
            assertEquals(1, slave.mChannels.mCount.get());
        }
        finally
        {
            service.close();
            channels.close();
        }
    }

    @Test
    void closeReportsAnAcceptedBrowseFailureInsteadOfDiscardingIt() throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        channels.addStandard("County control", 851_012_500L);
        channels.mBlockSnapshots = true;
        channels.mFailSnapshots = true;
        TunerSettingsService service = service(tuner, new AtomicInteger(), channels);
        try
        {
            CompletableFuture<TunerSettingsService.BrowseLease> browse = service.browse(tuner, null, true);
            assertTrue(channels.mSnapshotEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> unblock = CompletableFuture.runAsync(() ->
            {
                try { Thread.sleep(50); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                channels.mReleaseSnapshot.countDown();
            });

            assertThrows(IllegalStateException.class, () -> service.closeWithin(2, TimeUnit.SECONDS));
            unblock.join();
            assertThrows(CompletionException.class, browse::join);
            assertEquals(1, sources.mCount.get());
            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());

            channels.mBlockSnapshots = false;
            channels.mFailSnapshots = false;
            service.closeWithin(2, TimeUnit.SECONDS);
        }
        finally
        {
            channels.mReleaseSnapshot.countDown();
            service.close();
            channels.close();
        }
    }

    @Test
    void closePreservesManualSetupRecoveryUntilChannelsAreRestored() throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel original = channels.addStandard("County control", 851_012_500L);
        TunerSettingsService service = service(tuner, new AtomicInteger(), channels);
        try
        {
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            assertEquals(List.of(original.getConfigurationId()),
                service.stoppedChannels(tuner).stream().map(TunerSettingsService.ChannelInfo::id).toList());

            assertThrows(IllegalStateException.class, () -> service.closeWithin(2, TimeUnit.SECONDS));
            assertEquals(List.of(original.getConfigurationId()),
                service.stoppedChannels(tuner).stream().map(TunerSettingsService.ChannelInfo::id).toList());

            service.restoreStoppedChannels(tuner);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            service.closeWithin(2, TimeUnit.SECONDS);
            assertEquals(1, sources.mCount.get());
        }
        finally
        {
            service.close();
            channels.close();
        }
    }

    @Test
    void browseOwnershipRenewalHandoffAndExpiryAreReceiverOwned() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        AtomicLong clock = new AtomicLong(1000);
        try(TunerSettingsService service = new TunerSettingsService(() -> {}, candidate -> candidate == tuner,
            ignored -> tuner, null, () -> List.of(tuner), clock::get))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
            assertTrue(service.ownsBrowse(tuner, lease.leaseId()));
            assertFalse(service.ownsBrowse(tuner, "another-browser"));
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.browse(tuner, null));
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.releaseBrowse(tuner, "another-browser", false));
            clock.addAndGet(10_000);
            var renewed = service.browse(tuner, lease.leaseId()).get();
            assertEquals(lease.leaseId(), renewed.leaseId());
            assertTrue(renewed.expiresAtEpochMs() > lease.expiresAtEpochMs());
            service.releaseBrowse(tuner, lease.leaseId(), true).get(2, TimeUnit.SECONDS);
            assertTrue(tuner.isAvailableForAllocation());
            assertFalse(service.ownsBrowse(tuner, lease.leaseId()));
            lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            clock.addAndGet(31_000);
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
            assertTrue(service.ownsBrowse(tuner, lease.leaseId()),
                "expired heartbeat does not transfer an owned lease before receiver restoration finishes");
            service.expireBrowsing();
            await(Duration.ofSeconds(2), tuner::isAvailableForAllocation);
            assertFalse(service.ownsBrowse(tuner, lease.leaseId()));
        }
    }

    @Test
    void browseCleanupDoesNotUndoLaterOperatorMode() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            tuner.setEnabled(false);
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
            assertFalse(service.ownsBrowse(tuner, lease.leaseId()), "a later operator generation owns the tuner");
            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
            assertEquals(DiscoveredTuner.OperatorState.DISABLED, tuner.getOperatorState());
            assertFalse(tuner.hasTuner());
        }
    }

    @Test
    void handoffKeepsGroupedAllocationGateAndCenterHoldThroughStartup() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            String result = service.handoffBrowse(tuner, lease.leaseId(), () ->
            {
                assertTrue(tuner.isAvailableForAllocation());
                assertTrue(tuner.isDiscoveryHeld());
                assertTrue(tuner.tryAcquireForAllocation(), "startup can reenter its own allocation gate");
                tuner.releaseAfterAllocation();
                assertFalse(CompletableFuture.supplyAsync(tuner::tryAcquireForAllocation)
                    .orTimeout(1, TimeUnit.SECONDS).join(), "competing allocation must fail without waiting");
                return "started";
            }).get(2, TimeUnit.SECONDS);
            assertEquals("started", result);
            assertTrue(tuner.isAvailableForAllocation());
            assertFalse(tuner.isDiscoveryHeld());
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
        }
    }

    @Test
    void handoffTransfersOnlyItsOwnedProbeHoldAndKeepsProtectionUntilStartupReturns() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            try(var hold = service.holdForProbe(tuner))
            {
                var handoff = service.handoffBrowse(tuner, lease.leaseId(), hold, () ->
                {
                    assertFalse(hold.valid(), "the transferred hold is consumed under the allocation gate");
                    hold.close();
                    assertTrue(tuner.isDiscoveryHeld(), "closing the consumed hold must not clear handoff protection");
                    assertTrue(tuner.tryAcquireForAllocation());
                    tuner.releaseAfterAllocation();
                    entered.countDown();
                    try { assertTrue(finish.await(2, TimeUnit.SECONDS)); }
                    catch(InterruptedException exception) { throw new AssertionError(exception); }
                    return "started";
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(tuner.tryAcquireForAllocation(), "another thread cannot take the receiver during startup");
                assertThrows(TunerSettingsService.SettingUnavailableException.class, () -> service.holdForProbe(tuner));
                finish.countDown();
                assertEquals("started", handoff.get(2, TimeUnit.SECONDS));
                assertFalse(tuner.isDiscoveryHeld());
                assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
            }
        }
        finally { finish.countDown(); }
    }

    @Test
    void unrelatedProbeHoldCannotBeConsumedByBrowseReleaseOrAnotherTuner() throws Exception
    {
        FakeDiscoveredTuner first = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        FakeDiscoveredTuner other = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        try(TunerSettingsService service = new TunerSettingsService(() -> {},
            candidate -> candidate == first || candidate == other, ignored -> first))
        {
            var lease = service.browse(first, null).get(2, TimeUnit.SECONDS);
            try(var foreign = service.holdForProbe(other))
            {
                assertThrows(TunerSettingsService.SettingUnavailableException.class,
                    () -> service.handoffBrowse(first, lease.leaseId(), foreign, () -> "unexpected"));
                assertTrue(foreign.valid());
            }
            try(var hold = service.holdForProbe(first))
            {
                assertThrows(TunerSettingsService.SettingUnavailableException.class,
                    () -> service.handoffBrowse(first, lease.leaseId(), () -> "unexpected"));
                assertTrue(hold.valid());
            }
            service.releaseBrowse(first, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedPreparationRetainsOwnedBrowseForExactRetryButAnAttemptedStartConsumesIt() throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            try(var hold = service.holdForProbe(tuner))
            {
                sources.mCount.set(1);
                var failed = service.handoffBrowse(tuner, lease.leaseId(), hold, () -> "unexpected");
                ExecutionException exception = assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));
                assertTrue(exception.getCause().getMessage().contains("sample source"));
                assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
                assertFalse(tuner.isDiscoveryHeld());
            }
            sources.mCount.set(0);
            var failedStart = service.handoffBrowse(tuner, lease.leaseId(), () ->
            { throw new TunerSettingsService.SettingUnavailableException("Selected tuner is unavailable"); });
            assertThrows(ExecutionException.class, () -> failedStart.get(2, TimeUnit.SECONDS));
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
            assertFalse(tuner.isDiscoveryHeld());
            assertNull(service.transition(tuner));
        }
    }

    @Test
    void rejectedLifecycleQueueLeavesTheProbeAndBrowseOwnedByTheirCaller() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            java.lang.reflect.Field field = TunerSettingsService.class.getDeclaredField("mExecutor");
            field.setAccessible(true);
            var executor = (java.util.concurrent.ThreadPoolExecutor)field.get(service);
            Runnable blocked = () -> { entered.countDown(); try { release.await(2, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); } };
            executor.execute(blocked); executor.execute(blocked);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for(int count = 0; count < 32; count++) executor.execute(() -> {});
            try(var hold = service.holdForProbe(tuner))
            {
                var failed = service.handoffBrowse(tuner, lease.leaseId(), hold, () -> "unexpected");
                assertThrows(ExecutionException.class, () -> failed.get(1, TimeUnit.SECONDS));
                assertTrue(hold.valid());
                assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
                assertNull(service.transition(tuner));
                assertEquals("failed", service.operation(tuner).state());
            }
            AtomicInteger normalStarts = new AtomicInteger();
            AtomicInteger currentCenterStarts = new AtomicInteger();
            var savedStart = service.startSavedChannel(tuner, lease.leaseId(),
                () -> currentCenterStarts.incrementAndGet(), () -> normalStarts.incrementAndGet());
            assertThrows(ExecutionException.class, () -> savedStart.get(1, TimeUnit.SECONDS));
            assertEquals(0, currentCenterStarts.get());
            assertEquals(0, normalStarts.get(), "queue rejection must not bypass the owned handoff");
            assertTrue(service.ownsBrowse(tuner, lease.leaseId()));
            release.countDown();
            await(Duration.ofSeconds(2), () -> executor.getQueue().isEmpty());
            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
        }
        finally { release.countDown(); }
    }

    @Test
    void savedStartFallsBackOutsideLifecycleLockOnlyAfterTheBrowseOwnerIsGone() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        AtomicInteger currentCenterStarts = new AtomicInteger();
        AtomicInteger normalStarts = new AtomicInteger();
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            java.lang.reflect.Field field = TunerSettingsService.class.getDeclaredField("mLifecycleLock");
            field.setAccessible(true);
            Object lock = field.get(service);
            assertEquals("normal", service.startSavedChannel(tuner, "former-lease", () ->
            {
                currentCenterStarts.incrementAndGet();
                return "unexpected";
            }, () ->
            {
                assertFalse(Thread.holdsLock(lock), "normal channel startup must not run under the settings monitor");
                normalStarts.incrementAndGet();
                return "normal";
            }).get(1, TimeUnit.SECONDS));
            assertEquals(0, currentCenterStarts.get());
            assertEquals(1, normalStarts.get());
        }
    }

    @Test
    void savedStartNeverFallsBackAroundAnotherBrowsersOwnedReceiver() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        AtomicInteger starts = new AtomicInteger();
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            var owner = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            assertThrows(TunerSettingsService.SettingUnavailableException.class, () ->
                service.startSavedChannel(tuner, "another-browser", starts::incrementAndGet, starts::incrementAndGet));
            assertEquals(0, starts.get());
            assertTrue(service.ownsBrowse(tuner, owner.leaseId()));
            service.releaseBrowse(tuner, owner.leaseId(), false).get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void probeHoldRejectsRetuneAndLifecycleButKeepsLiveAllocationAvailable()
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        try(TunerSettingsService service = service(tuner, new AtomicInteger());
            TunerSettingsService.ProbeHold hold = service.holdForProbe(tuner))
        {
            assertTrue(hold.valid());
            assertTrue(tuner.isDiscoveryHeld());
            assertTrue(tuner.isAvailableForAllocation());
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(tuner, "frequency_mhz", 851.0));
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP));
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.holdForProbe(tuner));
            hold.close();
            assertFalse(tuner.isDiscoveryHeld());
            assertFalse(hold.valid());
        }
    }

    @Test
    void browsingFailsFastWhenAllocationIsAlreadyReserved() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            assertTrue(tuner.tryAcquireForAllocation());
            try
            {
                var request = service.browse(tuner, null);
                assertThrows(CompletionException.class, request::join);
                assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());
            }
            finally { tuner.releaseAfterAllocation(); }
        }
    }

    @Test
    void idleMemberCannotStartBrowsingWhilePairedHardwareIsOccupied()
    {
        DeviceInfo masterInfo = new DeviceInfo(DeviceType.RSPduo, "browse-pair-test");
        masterInfo.setDeviceSelectionMode(DeviceSelectionMode.MASTER_TUNER_1);
        DeviceInfo slaveInfo = masterInfo.copy();
        slaveInfo.setDeviceSelectionMode(DeviceSelectionMode.SLAVE_TUNER_2);
        TestRspDuoMaster master = new TestRspDuoMaster(masterInfo);
        TestRspDuoSlave slave = new TestRspDuoSlave(slaveInfo);
        master.setTunerConfiguration(new RspDuoTuner1Configuration(master.getId()));
        slave.setTunerConfiguration(new RspDuoTuner2Configuration(slave.getId()));
        master.installRunning();
        slave.installRunning();
        slave.mChannels.mCount.set(1);
        try(TunerSettingsService service = new TunerSettingsService(() -> {},
            candidate -> candidate == master || candidate == slave,
            id -> id.equals(master.getId()) ? master : id.equals(slave.getId()) ? slave : null))
        {
            assertThrows(CompletionException.class, () -> service.browse(master, null).join());
            assertEquals(DiscoveredTuner.OperatorState.LIVE, master.getOperatorState());
            assertEquals(DiscoveredTuner.OperatorState.LIVE, slave.getOperatorState());
            assertEquals(1, slave.mChannels.mCount.get());
            try(var hold = service.holdForProbe(master))
            {
                assertTrue(((DiscoveredTuner)slave).isDiscoveryHeld());
                assertThrows(TunerSettingsService.SettingUnavailableException.class,
                    () -> service.requestState(slave, DiscoveredTuner.OperatorState.DISABLED));
            }
            assertFalse(((DiscoveredTuner)slave).isDiscoveryHeld());
        }
    }

    @Test
    void stoppedChannelMetadataUsesPublicGroupingAndFrequencyFields()
    {
        TunerSettingsService.ChannelInfo info = new TunerSettingsService.ChannelInfo(
            "traffic-42", "Voice", "saved-parent", "traffic", 851_012_500L, "Regional system", "North site");
        var json = ApiHttpResponse.normalizePayload(info);

        assertEquals("traffic-42", json.path("id").asText());
        assertEquals("saved-parent", json.path("parent_id").asText());
        assertEquals("traffic", json.path("kind").asText());
        assertEquals(851_012_500L, json.path("frequency_hz").asLong());
        assertEquals("Regional system", json.path("system").asText());
        assertEquals("North site", json.path("site").asText());
    }

    @Test
    void liveSettingAppliesOnceAndIsNeverQueued()
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setGain(Gain.CUSTOM);
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = service(tuner, saves))
        {
            assertEquals("applied", service.set(tuner, "if_gain", 7).status());
            assertEquals(7, configuration.getIFGain());
            assertEquals(1, ((TrackingAirspyController)tuner.getTuner().getTunerController()).mIfGainCalls.get());
            assertEquals(1, saves.get());
            assertNull(service.transition(tuner));
        }
    }

    @Test
    void descriptorsExposeSetupAvailabilityAndGenericDependencies()
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(true);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);

        TunerSettingCatalog.SettingDescriptor sampleRate = descriptor(tuner, "sample_rate");
        assertEquals("setup", sampleRate.availability());
        assertTrue(sampleRate.editable());
        assertFalse(sampleRate.options().isEmpty(), "disabled tuners retain a usable sample-rate choice");

        TunerSettingCatalog.SettingDescriptor ppm = descriptor(tuner, "frequency_correction_ppm");
        assertFalse(ppm.editable());
        assertEquals("Turn off Auto PPM", ppm.unavailableReason());
        assertEquals(new TunerSettingCatalog.Dependency("automatic_ppm", false), ppm.dependencies().getFirst());

        configuration.setCenterFrequencyLocked(true);
        TunerSettingCatalog.SettingDescriptor center = descriptor(tuner, "frequency_mhz");
        assertFalse(center.editable());
        assertEquals("Unlock center", center.unavailableReason());
        assertEquals(new TunerSettingCatalog.Dependency("center_frequency_locked", false),
            center.dependencies().getFirst());
    }

    @Test
    void sampleRateChangesOnlyOutsideLive()
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(true);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = service(tuner, saves))
        {
            Object current = descriptor(tuner, "sample_rate").value();
            assertEquals("applied", service.set(tuner, "sample_rate", current).status());
            assertEquals(1, saves.get());

            tuner.install(new FakeTuner(new TrackingAirspyController(), tuner, new CountingChannelManager()));
            tuner.setEnabled(true);
            TunerSettingsService.SettingUnavailableException failure = assertThrows(
                TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(tuner, "sample_rate", current));
            assertEquals("Use Setup", failure.getMessage());
        }
    }

    @Test
    void setupRejectsAnOverCapacityRecoveryPlanBeforeStoppingAnyChannel() throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        for(int index = 0; index < 65; index++)
        {
            channels.addStandard("Channel " + index, 851_000_000L + index * 12_500L);
        }
        AtomicBoolean snapshotWasGated = new AtomicBoolean();
        channels.mSnapshotObserver = () -> snapshotWasGated.set(!tuner.isAvailableForAllocation());

        try(TunerSettingsService service = service(tuner, new AtomicInteger(), channels))
        {
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);

            assertTrue(snapshotWasGated.get(), "capacity is checked only after new allocation is blocked");
            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());
            assertTrue(tuner.isAvailableForAllocation());
            assertEquals(65, sources.mCount.get());
            assertTrue(channels.operations().isEmpty(), "no channel is stopped by a rejected plan");
            assertTrue(service.stoppedChannels(tuner).isEmpty());
            assertNotNull(service.error(tuner));
        }
        finally { channels.close(); }
    }

    @Test
    void setupKeepsHardwareRunningAndBlocksOrdinaryAllocation() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());

        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            assertTrue(tuner.isAvailableForAllocation());
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);

            assertEquals(DiscoveredTuner.OperatorState.SETUP, tuner.getOperatorState());
            assertTrue(tuner.hasTuner());
            assertFalse(tuner.isAvailableForAllocation());

            tuner.beginRestoreAllocation();
            try
            {
                assertTrue(tuner.isAvailableForAllocation(), "only the restore worker can allocate in Setup");
            }
            finally
            {
                tuner.endRestoreAllocation();
            }
            assertFalse(tuner.isAvailableForAllocation());

            service.requestState(tuner, DiscoveredTuner.OperatorState.LIVE);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            assertTrue(tuner.isAvailableForAllocation());
        }
    }

    @Test
    void disableStopsWithoutRestartAndSetupFailureRollsBack() throws Exception
    {
        FakeDiscoveredTuner running = runningTuner(new TrackingAirspyController(), new CountingChannelManager());

        try(TunerSettingsService service = service(running, new AtomicInteger()))
        {
            service.requestState(running, DiscoveredTuner.OperatorState.DISABLED);
            await(Duration.ofSeconds(2), () -> service.transition(running) == null);
            assertEquals(DiscoveredTuner.OperatorState.DISABLED, running.getOperatorState());
            assertFalse(running.hasTuner());
            assertEquals(0, running.mStartCalls.get(), "disable never starts hardware or channels");
        }

        FakeDiscoveredTuner unavailable = new FakeDiscoveredTuner(true);
        unavailable.setTunerConfiguration(new AirspyTunerConfiguration(unavailable.getId()));
        try(TunerSettingsService service = service(unavailable, new AtomicInteger()))
        {
            service.requestState(unavailable, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(unavailable) == null);
            assertEquals(DiscoveredTuner.OperatorState.DISABLED, unavailable.getOperatorState());
            assertFalse(unavailable.isEnabled());
            assertNotNull(service.error(unavailable));
        }
    }

    @Test
    void erroredLiveTunerCanRestartInSetupAndCanStillBeDisabled() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, channels);
        tuner.prepareRestart(controller, channels);

        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            tuner.setErrorMessage("Expected test fault");
            assertEquals(TunerStatus.ERROR, tuner.getTunerStatus());
            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());

            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            assertEquals(TunerStatus.ENABLED, tuner.getTunerStatus());
            assertEquals(DiscoveredTuner.OperatorState.SETUP, tuner.getOperatorState());
            assertTrue(tuner.hasTuner());
            assertFalse(tuner.isAvailableForAllocation());
            assertEquals(1, tuner.mStartCalls.get());

            service.requestState(tuner, DiscoveredTuner.OperatorState.LIVE);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            tuner.setErrorMessage("Expected second test fault");
            service.requestState(tuner, DiscoveredTuner.OperatorState.DISABLED);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            assertEquals(TunerStatus.DISABLED, tuner.getTunerStatus());
            assertEquals(DiscoveredTuner.OperatorState.DISABLED, tuner.getOperatorState());
            assertFalse(tuner.isEnabled());
            assertEquals(1, tuner.mStartCalls.get(), "disable must not restart errored hardware");
        }
    }

    @Test
    void transitionRejectsConcurrentSettingInsteadOfRetainingIt() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(true);
        tuner.setTunerConfiguration(new AirspyTunerConfiguration(tuner.getId()));
        tuner.mBlockStart = true;

        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            assertTrue(tuner.mStartEntered.await(2, TimeUnit.SECONDS));
            assertEquals("starting_setup", service.operation(tuner).kind());
            assertEquals("running", service.operation(tuner).state());
            assertEquals(null, service.operation(tuner).completedAtEpochMs());
            TunerSettingsService.SettingUnavailableException failure = assertThrows(
                TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(tuner, "automatic_ppm", false));
            assertEquals("Tuner busy", failure.getMessage());
            tuner.mReleaseStart.countDown();
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            assertEquals("failed", service.operation(tuner).state());
            assertEquals("Tuner unavailable", service.operation(tuner).error());
            assertNotNull(service.operation(tuner).completedAtEpochMs());
        }
        finally
        {
            tuner.mReleaseStart.countDown();
        }
    }

    @Test
    void stateBrowseAndRestoreUseTheSameLatestFiniteCompletionWithoutQueuingSettings() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            var setup = service.operation(tuner);
            assertEquals("completed", setup.state());
            assertEquals("starting_setup", setup.kind());
            assertTrue(setup.completedAtEpochMs() >= setup.acceptedAtEpochMs());

            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            var browse = service.operation(tuner);
            assertEquals("completed", browse.state());
            assertEquals("starting_browse", browse.kind());
            assertFalse(setup.id().equals(browse.id()));
            service.releaseBrowse(tuner, lease.leaseId(), false).get(2, TimeUnit.SECONDS);
            assertEquals("completed", service.operation(tuner).state());
            assertEquals("ending_browse", service.operation(tuner).kind());

            service.restoreStoppedChannels(tuner);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            var restore = service.operation(tuner);
            assertEquals("completed", restore.state());
            assertEquals("restoring", restore.kind());
            assertEquals("applied", service.set(tuner, "automatic_ppm", false).status());
            assertEquals(restore, service.operation(tuner), "ordinary settings do not create queued work");
        }
    }

    @Test
    void nativeStartupLinkageFailureCompletesBrowseAndReleasesItsFiniteAdmission() throws Exception
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(true);
        tuner.setTunerConfiguration(new AirspyTunerConfiguration(tuner.getId()));
        tuner.mStartFailure = new UnsatisfiedLinkError("expected missing test native library");
        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            var browse = service.browse(tuner, null);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> browse.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof UnsatisfiedLinkError);
            assertNull(service.transition(tuner));
            assertEquals("failed", service.operation(tuner).state());
            assertEquals("Change failed", service.operation(tuner).error());
            assertNotNull(service.operation(tuner).completedAtEpochMs());

            tuner.mStartFailure = null;
            service.requestState(tuner, DiscoveredTuner.OperatorState.DISABLED);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            assertEquals("completed", service.operation(tuner).state());
        }
    }

    @Test
    void blockedLifecycleDoesNotBlockAnUnrelatedTuner() throws Exception
    {
        FakeDiscoveredTuner blocked = new FakeDiscoveredTuner(true);
        blocked.setTunerConfiguration(new AirspyTunerConfiguration(blocked.getId()));
        blocked.prepareRestart(new TrackingAirspyController(), new CountingChannelManager());
        blocked.mBlockStart = true;
        FakeDiscoveredTuner independent = runningTuner(new TrackingAirspyController(),
            new CountingChannelManager());

        try(TunerSettingsService service = new TunerSettingsService(() -> { },
            candidate -> candidate == blocked || candidate == independent, ignored -> null))
        {
            service.requestState(blocked, DiscoveredTuner.OperatorState.SETUP);
            assertTrue(blocked.mStartEntered.await(2, TimeUnit.SECONDS));
            service.requestState(independent, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(independent) == null);

            assertEquals(DiscoveredTuner.OperatorState.SETUP, independent.getOperatorState());
            assertNotNull(service.transition(blocked), "the blocked device still owns only its lifecycle worker");
            blocked.mReleaseStart.countDown();
            await(Duration.ofSeconds(2), () -> service.transition(blocked) == null);
        }
        finally
        {
            blocked.mReleaseStart.countDown();
        }
    }

    @Test
    void acceptedPpmSurvivesNotificationFailureAndIsPersisted()
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        controller.addListener(event ->
        {
            if(event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CORRECTION_CHANGE)
            {
                throw new SourceException("Expected notification failure");
            }
        });
        FakeDiscoveredTuner tuner = runningTuner(controller, new CountingChannelManager());
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setAutoPPMCorrectionEnabled(false);
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = service(tuner, saves))
        {
            assertEquals("applied", service.set(tuner, "frequency_correction_ppm", 1.2).status());
            assertEquals(1.2, configuration.getFrequencyCorrection());
            assertEquals(1.2, controller.getFrequencyCorrection());
            assertEquals(1, saves.get());
            assertEquals("Applied; notification failed", service.error(tuner));
        }
    }

    @Test
    void acceptedCenterSurvivesNotificationFailureAndIsPersisted()
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        controller.addListener(event ->
        {
            if(event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE)
            {
                throw new SourceException("Expected notification failure");
            }
        });
        FakeDiscoveredTuner tuner = runningTuner(controller, new CountingChannelManager());
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(false);
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = service(tuner, saves))
        {
            assertEquals("applied", service.set(tuner, "frequency_mhz", 120.0).status());
            assertEquals(120_000_000L, configuration.getFrequency());
            assertEquals(120_000_000L, controller.getFrequency());
            assertEquals(1, saves.get());
            assertEquals("Applied; notification failed", service.error(tuner));
        }
    }

    @Test
    void rspDuoMasterLifecycleKeepsThePairGatedAndRateRequiresDisabledTunerTwo() throws Exception
    {
        DeviceInfo masterInfo = new DeviceInfo(DeviceType.RSPduo, "group-test");
        masterInfo.setDeviceSelectionMode(DeviceSelectionMode.MASTER_TUNER_1);
        DeviceInfo slaveInfo = masterInfo.copy();
        slaveInfo.setDeviceSelectionMode(DeviceSelectionMode.SLAVE_TUNER_2);
        TestRspDuoMaster master = new TestRspDuoMaster(masterInfo);
        TestRspDuoSlave slave = new TestRspDuoSlave(slaveInfo);
        RspDuoTuner1Configuration masterConfig = new RspDuoTuner1Configuration(master.getId());
        RspDuoTuner2Configuration slaveConfig = new RspDuoTuner2Configuration(slave.getId());
        masterConfig.setSampleRate(RspSampleRate.DUO_RATE_2_000);
        slaveConfig.setSampleRate(RspSampleRate.DUO_RATE_2_000);
        master.setTunerConfiguration(masterConfig);
        slave.setTunerConfiguration(slaveConfig);
        master.installRunning();
        slave.installRunning();
        AtomicBoolean slaveStartedInSetup = new AtomicBoolean();
        slave.addTunerStatusListener((ignored, previous, current) ->
        {
            if(current == TunerStatus.ENABLED)
            {
                slaveStartedInSetup.set(slave.getOperatorState() == DiscoveredTuner.OperatorState.SETUP &&
                    !slave.isAvailableForAllocation());
            }
        });
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = new TunerSettingsService(saves::incrementAndGet,
            candidate -> candidate == master || candidate == slave,
            id -> id.equals(master.getId()) ? master : id.equals(slave.getId()) ? slave : null))
        {
            service.requestState(master, DiscoveredTuner.OperatorState.DISABLED);
            await(Duration.ofSeconds(2), () -> service.transition(master) == null);
            assertEquals(DiscoveredTuner.OperatorState.DISABLED, master.getOperatorState());
            assertEquals(DiscoveredTuner.OperatorState.DISABLED, slave.getOperatorState());

            master.blockNextStart();
            service.requestState(master, DiscoveredTuner.OperatorState.SETUP);
            assertTrue(master.mStartEntered.await(2, TimeUnit.SECONDS));
            assertEquals(null, service.transition(slave), "a group reservation is not a tuner 2 transition");
            assertEquals("Tuner busy", assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.requestState(slave, DiscoveredTuner.OperatorState.SETUP)).getMessage());
            master.mReleaseStart.countDown();
            await(Duration.ofSeconds(2), () -> service.transition(master) == null);
            assertEquals(DiscoveredTuner.OperatorState.SETUP, master.getOperatorState());
            assertEquals(DiscoveredTuner.OperatorState.SETUP, slave.getOperatorState());
            assertTrue(slaveStartedInSetup.get(), "explicit paired startup must not briefly expose tuner 2");
            assertFalse(master.isAvailableForAllocation());
            assertFalse(slave.isAvailableForAllocation());

            service.requestState(master, DiscoveredTuner.OperatorState.LIVE);
            await(Duration.ofSeconds(2), () -> service.transition(master) == null);
            assertEquals(DiscoveredTuner.OperatorState.LIVE, master.getOperatorState());
            assertEquals(DiscoveredTuner.OperatorState.SETUP, slave.getOperatorState());

            service.requestState(master, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(master) == null);
            TunerSettingCatalog.SettingDescriptor blockedRate = service.describe(master).stream()
                .filter(setting -> "sample_rate".equals(setting.id())).findFirst().orElseThrow();
            assertFalse(blockedRate.editable());
            assertEquals("Disable tuner 2", blockedRate.unavailableReason());
            assertEquals("Disable tuner 2", assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(master, "sample_rate", RspSampleRate.DUO_RATE_1_000.name())).getMessage());

            service.requestState(slave, DiscoveredTuner.OperatorState.DISABLED);
            await(Duration.ofSeconds(2), () -> service.transition(slave) == null);
            assertTrue(service.describe(master).stream().filter(setting -> "sample_rate".equals(setting.id()))
                .findFirst().orElseThrow().editable());
            assertEquals("applied",
                service.set(master, "sample_rate", RspSampleRate.DUO_RATE_1_000.name()).status());
            assertEquals(RspSampleRate.DUO_RATE_1_000, masterConfig.getSampleRate());
            assertEquals(RspSampleRate.DUO_RATE_1_000, slaveConfig.getSampleRate());
            assertEquals(1, saves.get());
        }
    }

    @Test
    void completedPairedTransitionImmediatelyAcceptsNextRequest()
    {
        DeviceInfo masterInfo = new DeviceInfo(DeviceType.RSPduo, "completion-pair-test");
        masterInfo.setDeviceSelectionMode(DeviceSelectionMode.MASTER_TUNER_1);
        DeviceInfo slaveInfo = masterInfo.copy();
        slaveInfo.setDeviceSelectionMode(DeviceSelectionMode.SLAVE_TUNER_2);
        TestRspDuoMaster master = new TestRspDuoMaster(masterInfo);
        TestRspDuoSlave slave = new TestRspDuoSlave(slaveInfo);
        master.setTunerConfiguration(new RspDuoTuner1Configuration(master.getId()));
        slave.setTunerConfiguration(new RspDuoTuner2Configuration(slave.getId()));
        master.installRunning();
        slave.installRunning();
        try(TunerSettingsService service = new TunerSettingsService(() -> {},
            candidate -> candidate == master || candidate == slave,
            id -> id.equals(master.getId()) ? master : id.equals(slave.getId()) ? slave : null))
        {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                for(int attempt = 0; attempt < 50; attempt++)
                {
                    assertEquals("accepted", service.requestState(master, DiscoveredTuner.OperatorState.SETUP).status());
                    while(service.transition(master) != null && !Thread.currentThread().isInterrupted()) Thread.onSpinWait();
                    assertNull(service.transition(master));
                    //There is deliberately no sleep after the public completion signal.
                    assertEquals("accepted", service.requestState(slave, DiscoveredTuner.OperatorState.DISABLED).status());
                    while(service.transition(slave) != null && !Thread.currentThread().isInterrupted()) Thread.onSpinWait();
                    assertNull(service.transition(slave));
                    assertEquals(DiscoveredTuner.OperatorState.DISABLED, slave.getOperatorState());
                }
            });
        }
    }

    @Test
    void activeCenterRejectsRetuneWithoutChangingConfigurationOrHardware()
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        channels.mChannels.add(new TunerChannel(150_000_000L, 12_500));
        channels.mCount.set(1);
        FakeDiscoveredTuner tuner = runningTuner(controller, channels);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(false);
        long original = configuration.getFrequency();
        AtomicInteger saves = new AtomicInteger();

        try(TunerSettingsService service = service(tuner, saves))
        {
            TunerSettingsService.SettingUnavailableException failure =
                assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(tuner, "frequency_mhz", 120.0));
            assertEquals("Channels are using this tuner", failure.getMessage());
            assertEquals(original, configuration.getFrequency());
            assertEquals(0, controller.mFrequencyCalls.get());
            assertEquals(0, saves.get());
        }
    }

    @Test
    void activeCenterRejectsEvenFittingRetuneAndDisablesFrequencyDescriptor() throws Exception
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        controller.setFrequency(120_000_000L);
        CountingChannelManager channels = new CountingChannelManager();
        channels.mChannels.add(new TunerChannel(120_000_000L, 12_500));
        channels.mCount.set(1);
        FakeDiscoveredTuner tuner = runningTuner(controller, channels);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(false);
        configuration.setFrequency(120_000_000L);
        int calls = controller.mFrequencyCalls.get();
        AtomicInteger saves = new AtomicInteger();
        try(TunerSettingsService service = service(tuner, saves))
        {
            var descriptor = service.describe(tuner).stream().filter(setting ->
                "frequency_mhz".equals(setting.id())).findFirst().orElseThrow();
            assertFalse(descriptor.editable());
            assertEquals("Channels are using this tuner", descriptor.unavailableReason());
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(tuner, "frequency_mhz", 120.1));
            assertEquals(120_000_000L, configuration.getFrequency());
            assertEquals(120_000_000L, controller.getFrequency());
            assertEquals(calls, controller.mFrequencyCalls.get());
            assertEquals(0, saves.get());
        }
    }

    @Test
    void centerRechecksOccupancyInsideControllerAndAllocationLocksBeforeHardwareWrite()
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        CountingChannelManager channels = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(controller, channels);
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(false);
        long original = configuration.getFrequency();
        AtomicInteger saves = new AtomicInteger();
        AtomicInteger observations = new AtomicInteger();
        channels.mCountReadObserver = count -> {
            if(observations.incrementAndGet() == 1)
            {
                assertEquals(0, count);
                //A channel allocation won after the initial unlocked occupancy read.
                channels.mCount.set(1);
            }
            else
            {
                assertTrue(controller.getLock().isHeldByCurrentThread());
                assertFalse(CompletableFuture.supplyAsync(() -> {
                    boolean acquired = tuner.tryAcquireForAllocation();
                    if(acquired) tuner.releaseAfterAllocation();
                    return acquired;
                }).orTimeout(1, TimeUnit.SECONDS).join());
            }
        };
        try(TunerSettingsService service = service(tuner, saves))
        {
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(tuner, "frequency_mhz", 120.1));
            assertEquals(2, observations.get());
            assertEquals(original, configuration.getFrequency());
            assertEquals(0, controller.mFrequencyCalls.get());
            assertEquals(0, saves.get());
        }
    }

    @Test
    void idleUnlockedFrequencyRemainsEditableAndAppliesOnce()
    {
        TrackingAirspyController controller = new TrackingAirspyController();
        FakeDiscoveredTuner tuner = runningTuner(controller, new CountingChannelManager());
        AirspyTunerConfiguration configuration = (AirspyTunerConfiguration)tuner.getTunerConfiguration();
        configuration.setCenterFrequencyLocked(false);
        AtomicInteger saves = new AtomicInteger();
        try(TunerSettingsService service = service(tuner, saves))
        {
            var descriptor = service.describe(tuner).stream().filter(setting ->
                "frequency_mhz".equals(setting.id())).findFirst().orElseThrow();
            assertTrue(descriptor.editable());
            assertNull(descriptor.unavailableReason());
            assertEquals("applied", service.set(tuner, "frequency_mhz", 120.1).status());
            assertEquals(120_100_000L, configuration.getFrequency());
            assertEquals(120_100_000L, controller.getFrequency());
            assertEquals(1, controller.mFrequencyCalls.get());
            assertEquals(1, saves.get());
        }
    }

    @Test
    void manualRestoreRetainsFailedChannelForSuccessfulRetry() throws Exception
    {
        CountingChannelManager sources = new CountingChannelManager();
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), sources);
        TestChannelProcessingManager channels = new TestChannelProcessingManager(tuner, sources);
        Channel original = channels.addStandard("County control", 851_012_500L);

        try(TunerSettingsService service = service(tuner, new AtomicInteger(), channels))
        {
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            channels.mFailStarts = true;

            service.restoreStoppedChannels(tuner);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);

            assertEquals(DiscoveredTuner.OperatorState.SETUP, tuner.getOperatorState());
            assertEquals(List.of(original.getConfigurationId()),
                service.stoppedChannels(tuner).stream().map(TunerSettingsService.ChannelInfo::id).toList());
            assertEquals(List.of(original.getConfigurationId()),
                service.restoreResult(tuner).failed().stream().map(TunerSettingsService.ChannelInfo::id).toList());
            assertEquals(0, sources.mCount.get());

            channels.mFailStarts = false;
            service.restoreStoppedChannels(tuner);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);

            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());
            assertTrue(service.stoppedChannels(tuner).isEmpty());
            assertTrue(service.restoreResult(tuner).failed().isEmpty());
            assertEquals(1, sources.mCount.get());
        }
        finally { channels.close(); }
    }

    @Test
    void restoreIsOneFiniteAttemptThenGoesLive() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());

        try(TunerSettingsService service = service(tuner, new AtomicInteger()))
        {
            service.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            service.restoreStoppedChannels(tuner);
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
            assertEquals(DiscoveredTuner.OperatorState.LIVE, tuner.getOperatorState());
            assertNotNull(service.restoreResult(tuner));
            assertTrue(service.restoreResult(tuner).failed().isEmpty());
            assertNull(service.transition(tuner));
        }
    }

    private static FakeDiscoveredTuner runningTuner(TrackingAirspyController controller,
                                                     CountingChannelManager channels)
    {
        FakeDiscoveredTuner tuner = new FakeDiscoveredTuner(false);
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(tuner.getId());
        tuner.setTunerConfiguration(configuration);
        tuner.install(new FakeTuner(controller, tuner, channels));
        return tuner;
    }

    private static TunerSettingsService service(FakeDiscoveredTuner tuner, AtomicInteger saves)
    {
        return new TunerSettingsService(saves::incrementAndGet, candidate -> candidate == tuner, ignored -> null);
    }

    private static TunerSettingsService service(FakeDiscoveredTuner tuner, AtomicInteger saves,
                                                ChannelProcessingManager channels)
    {
        return new TunerSettingsService(saves::incrementAndGet, candidate -> candidate == tuner, ignored -> tuner,
            channels, () -> List.of(tuner));
    }

    private static TunerSettingCatalog.SettingDescriptor descriptor(DiscoveredTuner tuner, String id)
    {
        return TunerSettingCatalog.describe(tuner).stream().filter(setting -> id.equals(setting.id())).findFirst()
            .orElseThrow();
    }

    private static void await(Duration limit, java.util.function.BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + limit.toNanos();
        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for tuner operation");
    }

    private static final class FakeDiscoveredTuner extends DiscoveredTuner
    {
        private final AtomicInteger mStartCalls = new AtomicInteger();
        private final CountDownLatch mStartEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseStart = new CountDownLatch(1);
        private final AtomicBoolean mStartInterrupted = new AtomicBoolean();
        private volatile boolean mBlockStart;
        private volatile Error mStartFailure;
        private TrackingAirspyController mRestartController;
        private CountingChannelManager mRestartChannels;
        private volatile boolean mFailEnterLive;

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

        private void prepareRestart(TrackingAirspyController controller, CountingChannelManager channels)
        {
            mRestartController = controller;
            mRestartChannels = channels;
        }

        private TunerClass testTunerClass = TunerClass.TEST_TUNER;
        @Override public TunerClass getTunerClass() { return testTunerClass; }
        @Override public String getId() { return "settings-test"; }

        @Override
        public boolean enterLive()
        {
            if(mFailEnterLive)
            {
                mFailEnterLive = false;
                return false;
            }
            return super.enterLive();
        }

        @Override
        public void start()
        {
            mStartCalls.incrementAndGet();
            if(mStartFailure != null) throw mStartFailure;
            mStartEntered.countDown();
            if(mBlockStart)
            {
                try
                {
                    mReleaseStart.await(2, TimeUnit.SECONDS);
                }
                catch(InterruptedException e)
                {
                    mStartInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            }
            if(!hasTuner() && mRestartController != null)
            {
                install(new FakeTuner(mRestartController, this, mRestartChannels));
            }
        }
    }

    private static final class TrackingAirspyController extends AirspyTunerController
    {
        private final AtomicInteger mIfGainCalls = new AtomicInteger();
        private final AtomicInteger mFrequencyCalls = new AtomicInteger();
        private volatile boolean mFailNextCenterUnlock;
        private volatile boolean mFailNextCenterLock;

        private TrackingAirspyController()
        {
            super(0, "test", null);
        }

        @Override public void setIFGain(int value) { mIfGainCalls.incrementAndGet(); }
        @Override public void setFrequency(long frequency) throws SourceException
        {
            mFrequencyCalls.incrementAndGet();
            super.setFrequency(frequency);
        }
        @Override public synchronized void setTunedFrequency(long frequency) { }
        @Override public void apply(TunerConfiguration configuration) { }
        @Override public void setCenterFrequencyLocked(boolean locked)
        {
            if(locked && mFailNextCenterLock)
            {
                mFailNextCenterLock = false;
                throw new IllegalStateException("center lock failed");
            }
            if(!locked && mFailNextCenterUnlock)
            {
                mFailNextCenterUnlock = false;
                throw new IllegalStateException("center unlock failed");
            }
            super.setCenterFrequencyLocked(locked);
        }
    }

    private static final class TestRspDuoMaster extends DiscoveredRspDuoTuner1
    {
        private final TrackingRspController mController = new TrackingRspController();
        private final CountingChannelManager mChannels = new CountingChannelManager();
        private final CountDownLatch mStartEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseStart = new CountDownLatch(1);
        private volatile boolean mBlockNextStart;

        private TestRspDuoMaster(DeviceInfo info)
        {
            super(info);
        }

        private void installRunning()
        {
            mTuner = new FakeTuner(mController, this, mChannels);
        }

        private void blockNextStart()
        {
            mBlockNextStart = true;
        }

        @Override public void start()
        {
            if(isEnabled() && !hasTuner())
            {
                mStartEntered.countDown();
                if(mBlockNextStart)
                {
                    try
                    {
                        mReleaseStart.await(2, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
                installRunning();
            }
        }
    }

    private static final class TestRspDuoSlave extends DiscoveredRspDuoTuner2
    {
        private final TrackingRspController mController = new TrackingRspController();
        private final CountingChannelManager mChannels = new CountingChannelManager();
        private volatile boolean mAdvanceGenerationBeforeFail;
        private volatile boolean mFailEnterLive;

        private TestRspDuoSlave(DeviceInfo info)
        {
            super(info);
        }

        private void installRunning()
        {
            mTuner = new FakeTuner(mController, this, mChannels);
        }

        @Override
        public boolean enterLive()
        {
            if(mAdvanceGenerationBeforeFail)
            {
                mAdvanceGenerationBeforeFail = false;
                super.enterLive();
                return false;
            }
            if(mFailEnterLive)
            {
                mFailEnterLive = false;
                return false;
            }
            return super.enterLive();
        }

        @Override public void start()
        {
            if(isEnabled() && !hasTuner())
            {
                installRunning();
            }
        }
    }

    /** A real RSP controller boundary with an in-memory vendor control, not an unrelated Airspy controller. */
    private static final class TrackingRspController extends RspTunerController<IControlRspDuo>
    {
        private TrackingRspController()
        {
            super((IControlRspDuo)Proxy.newProxyInstance(IControlRspDuo.class.getClassLoader(),
                new Class<?>[]{IControlRspDuo.class}, (proxy, method, arguments) -> {
                    if("getSupportedSampleRates".equals(method.getName())) return RspSampleRate.getDualTunerSampleRates();
                    if("getCurrentSampleRate".equals(method.getName())) return 2_000_000.0;
                    if(method.getReturnType() == int.class) return 0;
                    if(method.getReturnType() == long.class) return 0L;
                    if(method.getReturnType() == boolean.class) return false;
                    return null;
                }), null);
        }
        @Override public TunerType getTunerType() { return TunerType.RSP_DUO_1; }
        @Override public void apply(TunerConfiguration configuration) {}
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
        private final SortedSet<TunerChannel> mChannels = new ConcurrentSkipListSet<>();
        private IntConsumer mCountReadObserver;

        @Override public SortedSet<TunerChannel> getTunerChannels() { return new TreeSet<>(mChannels); }
        @Override public String getStateDescription() { return "test"; }
        @Override public int getTunerChannelCount()
        {
            int count = mCount.get();
            if(mCountReadObserver != null) mCountReadObserver.accept(count);
            return count;
        }
        @Override public void stopAllChannels() { mCount.set(0); }
        @Override public TunerChannelSource getSource(TunerChannel channel, ChannelSpecification specification,
                                                      String threadName) { return null; }
        @Override public void setErrorMessage(String message) { }
        @Override public void process(SourceEvent event) { }
    }

    private static final class TestChannelProcessingManager extends ChannelProcessingManager
    {
        private final Map<String,CountingChannelManager> mSourcesByTuner = new HashMap<>();
        private final List<TunerChannelAssignment> mActive = new ArrayList<>();
        private final Map<Channel,TunerChannelAssignment> mKnown = new HashMap<>();
        private final Map<Channel,String> mTunerIds = new HashMap<>();
        private final List<String> mOperations = new ArrayList<>();
        private final Set<Channel> mNotOwnedStops = new java.util.HashSet<>();
        private final Set<Channel> mCleanupFailureStops = new java.util.HashSet<>();
        private final CountDownLatch mStartEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseStart = new CountDownLatch(1);
        private final CountDownLatch mSnapshotEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseSnapshot = new CountDownLatch(1);
        private volatile boolean mBlockStarts;
        private volatile boolean mFailStarts;
        private volatile boolean mBlockSnapshots;
        private volatile boolean mFailSnapshots;
        private Runnable mSnapshotObserver;

        private TestChannelProcessingManager(DiscoveredTuner tuner, CountingChannelManager sources)
        {
            super(null, null, null, new UserPreferences());
            mSourcesByTuner.put(tuner.getId(), sources);
        }

        private TestChannelProcessingManager(Map<DiscoveredTuner,CountingChannelManager> sources)
        {
            super(null, null, null, new UserPreferences());
            sources.forEach((tuner, manager) -> mSourcesByTuner.put(tuner.getId(), manager));
        }

        private synchronized Channel addStandard(String name, long frequencyHz)
        {
            String tunerId = mSourcesByTuner.keySet().iterator().next();
            return addStandard(tunerId, name, frequencyHz);
        }

        private synchronized Channel addStandard(DiscoveredTuner tuner, String name, long frequencyHz)
        {
            return addStandard(tuner.getId(), name, frequencyHz);
        }

        private Channel addStandard(String tunerId, String name, long frequencyHz)
        {
            Channel channel = new Channel(name);
            String id = channel.getConfigurationId();
            TunerChannelAssignment assignment = new TunerChannelAssignment(channel, id, name, true, id,
                "standard", frequencyHz, "Regional system", "North site", 1L);
            mKnown.put(channel, assignment);
            mTunerIds.put(channel, tunerId);
            mActive.add(assignment);
            updateCounts();
            return channel;
        }

        private synchronized void record(String operation)
        {
            mOperations.add(operation);
        }

        private synchronized List<String> operations()
        {
            return List.copyOf(mOperations);
        }

        @Override
        public synchronized List<TunerChannelAssignment> getChannelsUsingTuner(String tunerIdentity)
        {
            mSnapshotEntered.countDown();
            if(mSnapshotObserver != null) mSnapshotObserver.run();
            if(mBlockSnapshots)
            {
                try { mReleaseSnapshot.await(2, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            if(mFailSnapshots) throw new IllegalStateException("snapshot failed");
            return mActive.stream().filter(assignment ->
                tunerIdentity.equals(mTunerIds.get(assignment.channel()))).toList();
        }

        @Override
        public synchronized StopCurrentResult stopIfCurrent(TunerChannelAssignment expected) throws ChannelException
        {
            Channel channel = expected.channel();
            TunerChannelAssignment assignment = mKnown.get(channel);
            if(assignment != null)
            {
                mOperations.add("stop:" + assignment.id());
                if(!mActive.remove(assignment)) return StopCurrentResult.NOT_OWNED;
                updateCounts();
                if(mNotOwnedStops.remove(channel)) return StopCurrentResult.NOT_OWNED;
                if(mCleanupFailureStops.remove(channel))
                    return StopCurrentResult.CLAIMED_WITH_CLEANUP_FAILURE;
                return StopCurrentResult.CLAIMED;
            }
            return StopCurrentResult.NOT_OWNED;
        }

        @Override
        public synchronized void start(Channel channel) throws ChannelException
        {
            TunerChannelAssignment assignment = mKnown.get(channel);
            mOperations.add("start:" + assignment.id());
            mStartEntered.countDown();
            if(mBlockStarts)
            {
                try { mReleaseStart.await(2, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            if(mFailStarts) throw new ChannelException("restart failed");
            if(!mActive.contains(assignment)) mActive.add(assignment);
            updateCounts();
        }

        @Override
        public synchronized void startAtCurrentCenter(Channel channel, DiscoveredTuner selectedTuner)
            throws ChannelException
        {
            assertEquals(mTunerIds.get(channel), selectedTuner.getId());
            assertTrue(selectedTuner.isDiscoveryHeld(), "remembered channel recovery must retain the center hold");
            start(channel);
        }

        private void updateCounts()
        {
            mSourcesByTuner.forEach((tunerId, sources) -> sources.mCount.set((int)mActive.stream()
                .filter(assignment -> tunerId.equals(mTunerIds.get(assignment.channel()))).count()));
        }
    }
}
