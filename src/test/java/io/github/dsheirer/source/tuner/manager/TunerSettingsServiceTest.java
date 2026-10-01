package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
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
import io.github.dsheirer.source.tuner.sdrplay.RspSampleRate;
import io.github.dsheirer.source.tuner.sdrplay.api.DeviceSelectionMode;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceType;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner1Configuration;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.RspDuoTuner2Configuration;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.time.Duration;
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
import java.util.List;
import org.junit.jupiter.api.Test;

class TunerSettingsServiceTest
{
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
    void browseOwnershipRenewalHandoffAndExpiryAreReceiverOwned() throws Exception
    {
        FakeDiscoveredTuner tuner = runningTuner(new TrackingAirspyController(), new CountingChannelManager());
        AtomicLong clock = new AtomicLong(1000);
        try(TunerSettingsService service = new TunerSettingsService(() -> {}, candidate -> candidate == tuner,
            ignored -> tuner, null, () -> List.of(tuner), clock::get))
        {
            var lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            assertTrue(service.verifyBrowse(tuner, lease.leaseId()));
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
            lease = service.browse(tuner, null).get(2, TimeUnit.SECONDS);
            clock.addAndGet(31_000);
            assertFalse(service.verifyBrowse(tuner, lease.leaseId()));
            service.expireBrowsing();
            await(Duration.ofSeconds(2), tuner::isAvailableForAllocation);
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
            TunerSettingsService.SettingUnavailableException failure = assertThrows(
                TunerSettingsService.SettingUnavailableException.class,
                () -> service.set(tuner, "automatic_ppm", false));
            assertEquals("Tuner busy", failure.getMessage());
            tuner.mReleaseStart.countDown();
            await(Duration.ofSeconds(2), () -> service.transition(tuner) == null);
        }
        finally
        {
            tuner.mReleaseStart.countDown();
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
        master.addTunerStatusListener((ignored, previous, current) ->
        {
            if(previous == TunerStatus.ENABLED && current == TunerStatus.DISABLED)
            {
                slave.setEnabled(false);
            }
            else if(previous == TunerStatus.DISABLED && current == TunerStatus.ENABLED)
            {
                slave.setEnabled(true);
            }
        });
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
            assertTrue(slaveStartedInSetup.get(), "the manager cascade must not briefly expose tuner 2");
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
    void liveCenterRejectsChannelsThatWouldNotFit()
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
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.set(tuner, "frequency_mhz", 120.0));
            assertEquals("Channels won't fit", failure.getMessage());
            assertEquals(original, configuration.getFrequency());
            assertEquals(0, controller.mFrequencyCalls.get());
            assertEquals(0, saves.get());
        }
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
        private volatile boolean mBlockStart;
        private TrackingAirspyController mRestartController;
        private CountingChannelManager mRestartChannels;

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

        @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
        @Override public String getId() { return "settings-test"; }

        @Override
        public void start()
        {
            mStartCalls.incrementAndGet();
            mStartEntered.countDown();
            if(mBlockStart)
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
    }

    private static final class TestRspDuoMaster extends DiscoveredRspDuoTuner1
    {
        private final TrackingAirspyController mController = new TrackingAirspyController();
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
        private final TrackingAirspyController mController = new TrackingAirspyController();
        private final CountingChannelManager mChannels = new CountingChannelManager();

        private TestRspDuoSlave(DeviceInfo info)
        {
            super(info);
        }

        private void installRunning()
        {
            mTuner = new FakeTuner(mController, this, mChannels);
        }

        @Override public void start()
        {
            if(isEnabled() && !hasTuner())
            {
                installRunning();
            }
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
        private final SortedSet<TunerChannel> mChannels = new ConcurrentSkipListSet<>();

        @Override public SortedSet<TunerChannel> getTunerChannels() { return new TreeSet<>(mChannels); }
        @Override public String getStateDescription() { return "test"; }
        @Override public int getTunerChannelCount() { return mCount.get(); }
        @Override public void stopAllChannels() { mCount.set(0); }
        @Override public TunerChannelSource getSource(TunerChannel channel, ChannelSpecification specification,
                                                      String threadName) { return null; }
        @Override public void setErrorMessage(String message) { }
        @Override public void process(SourceEvent event) { }
    }
}
