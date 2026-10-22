/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.application.ComputerInformation;
import io.github.dsheirer.channel.quality.ControlChannelQualityMonitor;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelManager;
import io.github.dsheirer.source.tuner.LoggingTunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.test.TestTuner;
import io.github.dsheirer.source.tuner.usb.USBTunerController;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.support.WebDiagnosticLogService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiverHealthServiceTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void zgcConcurrentCycleTimeDoesNotCountAsPauseTime()
    {
        long pauses = ReceiverHealthService.gcPauseTime("ZGC Minor Cycles", 1_200) +
            ReceiverHealthService.gcPauseTime("ZGC Major Cycles", 800) +
            ReceiverHealthService.gcPauseTime("ZGC Minor Pauses", 1) +
            ReceiverHealthService.gcPauseTime("ZGC Major Pauses", 2);

        assertEquals(3L, pauses, "Concurrent cycle work must not trigger the 500 ms pause warning");
    }

    @Test
    void g1PauseCountersAreRetainedIncludingItsConcurrentCollectorName()
    {
        long pauses = ReceiverHealthService.gcPauseTime("G1 Young Generation", 21) +
            ReceiverHealthService.gcPauseTime("G1 Old Generation", 34) +
            ReceiverHealthService.gcPauseTime("G1 Concurrent GC", 55);

        assertEquals(110L, pauses, "G1 Concurrent GC reports stop-the-world phases despite its name");
        assertEquals(500L, ReceiverHealthService.gcPauseTime("Unknown collector", 500));
    }

    @Test
    void unavailableGcTimesDoNotBecomeNegativePauseTime()
    {
        assertEquals(0L, ReceiverHealthService.gcPauseTime("ZGC Minor Pauses", -1));
        assertEquals(0L, ReceiverHealthService.gcPauseTime("G1 Concurrent GC", -1));
        assertEquals(0L, ReceiverHealthService.gcPauseTime("ZGC Major Cycles", -1));
    }

    @Test
    void compactEvidenceIsOnlyBuiltWhileLoggingIsEnabledAndDoesNotChangeTheHealthApi() throws Exception
    {
        ApplicationPreference preferences = new ApplicationPreference(ignored -> {})
        {
            private boolean enabled;
            @Override public boolean isWebDiagnosticLoggingEnabled() { return enabled; }
            @Override public void setWebDiagnosticLoggingEnabled(boolean value) { enabled = value; }
        };
        AtomicLong webReads = new AtomicLong();
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1_000L);
            WebDiagnosticLogService logging = new WebDiagnosticLogService(() -> mTemporaryDirectory, preferences))
        {
            service.setWebDiagnosticLogService(logging);
            service.setWebStatusSupplier(() -> { webReads.incrementAndGet(); return Map.of(); });
            Field builderField = ReceiverHealthService.class.getDeclaredField("mDiagnosticCore");
            builderField.setAccessible(true);
            Object builder = builderField.get(service);
            Field sequence = ReceiverHealthDiagnosticCore.class.getDeclaredField("mSequence");
            sequence.setAccessible(true);
            service.sampleNow();
            assertEquals(0L, sequence.getLong(builder));
            logging.setEnabled(true);
            service.sampleNow();
            assertEquals(1L, sequence.getLong(builder));
            logging.setEnabled(false);
            service.sampleNow();
            assertEquals(1L, sequence.getLong(builder));
            assertEquals(3L, webReads.get(), "Logging must reuse the existing web snapshot read");
            assertEquals(Set.of("started_at_ms", "generated_at_ms", "summary", "active", "resolved",
                "measurements", "computer", "radioresolve_accepted_calls"), service.snapshot().keySet());
        }
    }

    @Test
    void includesCachedSafeComputerFactsOnlyAfterObserverSampling()
    {
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1000L))
        {
            assertFalse(service.snapshot().containsKey("computer"), "Construction must not discover host metadata");
            service.sampleNow();
            @SuppressWarnings("unchecked")
            Map<String,Object> computer = (Map<String,Object>)service.snapshot().get("computer");
            Map<String,Object> shared = ComputerInformation.current().snapshot();
            shared.forEach((key, value) -> {
                if(!"application_uptime_ms".equals(key)) assertEquals(value, computer.get(key));
            });
            assertTrue(computer.containsKey("installed_memory_bytes"));
            assertTrue(computer.containsKey("jvm_memory_limit_bytes"));
            assertFalse(computer.containsKey("hostname"));
        }
    }

    @Test
    void channelizerMeasurementIncludesQueueAndDropEvidence()
    {
        PolyphaseChannelManager.PipelineStatus pipeline = new PolyphaseChannelManager.PipelineStatus(
            2, 7, 8, 3, 0, List.of());
        String detail = ReceiverHealthService.channelizerDetail(pipeline);
        assertTrue(detail.contains("Peak 7 chunks"));
        assertTrue(detail.contains("Limit 8"));
        assertTrue(detail.contains("Lost 3"));
        assertFalse(detail.contains("high_water"));
    }

    @Test
    void warningSignalPathIncidentsCannotProduceAHealthyOrEmptySummary()
    {
        Map<String,Object> summary = ReceiverHealthService.summarize(List.of(
            incident("usb-delivery-rate-low", "warning")));

        assertEquals("warning", summary.get("severity"));
        assertEquals(1, summary.get("active_count"));
        assertEquals(1L, summary.get("warning_count"));
        assertEquals(0L, summary.get("critical_count"));
        assertFalse(summary.containsKey("diagnostic_count"));
    }

    @Test
    void criticalSignalPathIncidentsCannotProduceAHealthyOrEmptySummary()
    {
        Map<String,Object> summary = ReceiverHealthService.summarize(List.of(
            incident("receiver-iq-drop", "critical"),
            incident("channelizer-drop", "critical"),
            incident("channel-output-drop", "critical"),
            incident("usb-sample-loss", "critical")));

        assertEquals("critical", summary.get("severity"));
        assertEquals(4, summary.get("active_count"));
        assertEquals(0L, summary.get("warning_count"));
        assertEquals(4L, summary.get("critical_count"));
        assertFalse(summary.containsKey("diagnostic_count"));
    }

    @Test
    void removesTheLegacyLocalIncidentSnapshotAndTemporaryFile() throws Exception
    {
        Path target = mTemporaryDirectory.resolve(ReceiverHealthService.LEGACY_SNAPSHOT_FILE_NAME);
        Path staged = target.resolveSibling("." + target.getFileName() + ".tmp");
        Files.writeString(target, "legacy");
        Files.writeString(staged, "legacy temporary");

        ReceiverHealthService.removeLegacySnapshotFiles(target);

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(staged));
    }

    @Test
    void keepsObserverLossInformationalWithoutOpeningAnIncident()
    {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicLong eventDrops = new AtomicLong(2);

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setWebStatusSupplier(() -> Map.of(
                "server", Map.of("live_transport", Map.of(
                    "active_clients", 1,
                    "rejected_clients", 0,
                    "slow_disconnects", 0,
                    "event_drops", eventDrops.get())),
                "web_player", Map.of(),
                "diagnostics", Map.of()));
            service.sampleNow();

            Map<String,Object> snapshot = service.snapshot();
            assertEquals("healthy", map(snapshot.get("summary")).get("severity"));
            List<Map<String,Object>> active = rows(snapshot.get("active"));
            assertTrue(active.isEmpty());
            assertEquals(9, rows(snapshot.get("measurements")).size());
            assertEquals("info", measurement(snapshot, "supporting", "Web").get("severity"));

            clock.set(11_000);
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty(),
                () -> "Unexpected active incidents: " + service.snapshot().get("active"));
            assertTrue(rows(service.snapshot().get("resolved")).isEmpty(),
                () -> "Unexpected resolved incidents: " + service.snapshot().get("resolved"));
        }
    }

    @Test
    void exposesOnlyTheAcknowledgedRadioResolveCallCount()
    {
        AtomicLong accepted = new AtomicLong();

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            () -> 1_000L))
        {
            service.setRadioResolveAcceptedCalls(accepted::get);
            service.sampleNow();
            assertEquals(0L, service.snapshot().get("radioresolve_accepted_calls"));

            accepted.set(3);
            service.sampleNow();
            assertEquals(3L, service.snapshot().get("radioresolve_accepted_calls"));
        }
    }

    @Test
    void distinguishesWebCallCapacityDropsFromEncoderFailures()
    {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicBoolean outputFailed = new AtomicBoolean();

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setWebStatusSupplier(() -> Map.of(
                "server", Map.of("live_transport", Map.of()),
                "web_player", Map.of(
                    "published_calls", 7,
                    "active_feeds", 1,
                    "encoder_queue_depth", 0,
                    "dropped_encoder_capacity", outputFailed.get() ? 2 : 0,
                    "encoder_failures", outputFailed.get() ? 3 : 0,
                    "rejected_feeds", 0,
                    "rejected_audio_responses", 0),
                "diagnostics", Map.of()));
            service.sampleNow();
            outputFailed.set(true);
            clock.set(2_000);
            service.sampleNow();

            Map<String,Object> webAudio = measurement(service.snapshot(), "supporting", "Browser audio");
            String detail = String.valueOf(webAudio.get("detail"));
            assertEquals("warning", webAudio.get("severity"));
            assertTrue(detail.contains("Dropped 2"));
            assertTrue(detail.contains("Audio preparation failures 3"));
            assertFalse(detail.contains("capacity_drops"));

            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertEquals("healthy", map(service.snapshot().get("summary")).get("severity"));
        }
    }

    @Test
    void historicUsbLossCountsAreVisibleWithoutOpeningNewIncidents() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.ingressDrops = 7;
            usb.copyFailures = 3;
            usb.conversionFailures = 2;
            usb.listenerFailures = 4;
            usb.shortTransfers = 5;
            usb.sample(1_000, 1_000, 2_400_000);
            usb.sample(2_000, 2_000, 4_800_000);
            assertTrue(usb.codes().isEmpty());
            assertTrue(usb.measurements.stream().anyMatch(row ->
                String.valueOf(row.get("detail")).contains("Dropped 7 chunks")));
            assertTrue(usb.measurements.stream().anyMatch(row ->
                String.valueOf(row.get("detail")).contains("Processing errors 5")));
        }
    }

    @Test
    void isolatedUsbLossAndSlowCallbackRemainDiagnosticAfterTimePasses() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.sample(0, 0, 0);
            usb.ingressDrops = 1;
            usb.shortTransfers = 1;
            usb.slowCallbacks = 1;
            usb.sample(1_000, 1_000, 2_400_000);
            for(int second = 2; second <= 12; second++)
                usb.sample(second * 1_000L, second * 1_000L, second * 2_400_000L);
            assertTrue(usb.codes().isEmpty());
            assertTrue(usb.incidents.resolved().isEmpty(), "An isolated event must never open then silently resolve");
        }
    }

    @Test
    void repeatedIncomingPayloadAndConversionLossesRequireTenSecondsOfEvidence() throws Exception
    {
        for(int kind = 0; kind < 3; kind++)
        {
            try(UsbFixture usb = new UsbFixture())
            {
                usb.sample(0, 0, 0);
                for(int second = 1; second <= 11; second++)
                {
                    if(kind == 0) usb.ingressDrops = second;
                    else if(kind == 1) usb.copyFailures = second;
                    else usb.conversionFailures = second;
                    usb.sample(second * 1_000L, second * 1_000L, second * 2_400_000L);
                    if(second < 11) assertTrue(usb.codes().isEmpty(), "kind=" + kind + ", second=" + second);
                }
                assertEquals(Set.of(kind == 0 ? "receiver-ingress-drop" : "usb-sample-loss"), usb.codes());
                assertEquals("warning", usb.incidents.active().getFirst().get("severity"),
                    "Continuing loss needs attention without claiming that reception has stopped");
            }
        }
    }

    @Test
    void repeatedFullPayloadStatusAndListenerErrorsDoNotClaimRadioLoss() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.sample(0, 0, 0);
            for(int second = 1; second <= 12; second++)
            {
                usb.statusErrors = second;
                usb.listenerFailures = second;
                usb.sample(second * 1_000L, second * 1_000L, second * 2_400_000L);
            }
            assertTrue(usb.codes().isEmpty());
            assertTrue(usb.measurements.stream().anyMatch(row ->
                String.valueOf(row.get("detail")).contains("Errors 12")));
            assertTrue(usb.measurements.stream().anyMatch(row ->
                String.valueOf(row.get("detail")).contains("Delivery failures 12")));
        }
    }

    @Test
    void aStreamReplacementCannotConfirmThePreviousStreamsPendingLoss() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.sample(0, 0, 0);
            usb.shortTransfers = 1;
            usb.sample(1_000, 1_000, 2_400_000);
            usb.shortTransfers = 2;
            usb.sample(10_000, 10_000, 24_000_000);
            usb.sequence++;
            usb.shortTransfers = 0;
            usb.sample(11_000, 11_000, 26_400_000);
            usb.shortTransfers = 1;
            usb.sample(12_000, 12_000, 28_800_000);
            usb.shortTransfers = 2;
            usb.sample(21_000, 21_000, 50_400_000);
            assertTrue(usb.codes().isEmpty(), "The replacement stream needs its own ten-second evidence period");
            usb.shortTransfers = 3;
            usb.sample(22_000, 22_000, 52_800_000);
            assertEquals(Set.of("usb-sample-loss"), usb.codes());
        }
    }

    @Test
    void usbClockCorrectionsCannotCreateLowRateOrStoppedDataIncidents() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.sample(100_000, 1_000, 0);
            usb.sample(1_000_000, 2_000, 2_400_000);
            usb.sample(500, 3_000, 4_800_000);
            usb.sample(800, 11_000, 24_000_000);
            assertTrue(usb.codes().isEmpty());
            assertTrue(usb.measurements.stream().filter(row ->
                String.valueOf(row.get("label")).endsWith("radio data rate"))
                .allMatch(row -> String.valueOf(row.get("detail")).contains("100.0%") ||
                    String.valueOf(row.get("detail")).contains("warming up")));
        }
    }

    @Test
    void usbAbsenceIsConfirmedAtTenSecondsWithoutAnAdditionalDebounce() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.lastDelivery = 0L;
            usb.sample(1_000, 0, 0);
            usb.sample(10_999, 9_999, 0);
            assertTrue(usb.codes().isEmpty());
            usb.sample(11_000, 10_000, 0);
            assertEquals(Set.of("usb-sample-loss"), usb.codes());
            assertEquals("critical", usb.incidents.active().getFirst().get("severity"));
            assertTrue(String.valueOf(usb.incidents.active().getFirst().get("observed")).contains("10 seconds"));
        }
    }

    @Test
    void sustainedLowUsbDeliveryOpensOnlyAfterTheCompleteTenSecondWindow() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.sample(0, 0, 0);
            for(int second = 1; second <= 10; second++)
            {
                usb.sample(second * 1_000L, second * 1_000L, second * 1_200_000L);
                if(second < 10) assertTrue(usb.codes().isEmpty());
            }
            assertEquals(Set.of("usb-delivery-rate-low"), usb.codes());
            assertEquals("warning", usb.incidents.active().getFirst().get("severity"));
        }
    }

    @Test
    void reappearingProducerDoesNotReplayItsLargerHistoricCounters() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.ingressDrops = 7;
            usb.sample(1_000, 1_000, 2_400_000);
            usb.wall.set(2_000);
            usb.nanos.set(TimeUnit.MILLISECONDS.toNanos(2_000));
            usb.service.sampleNow(); // No registered tuner: the old owner disappears.
            usb.ingressDrops = 100;
            usb.sample(4_000, 4_000, 9_600_000);
            usb.ingressDrops = 101;
            usb.sample(13_000, 13_000, 31_200_000);
            usb.ingressDrops = 102;
            usb.sample(14_000, 14_000, 33_600_000);
            assertTrue(usb.codes().isEmpty(), "Historic100 is a baseline, not the first event of a continuing loss");
            usb.ingressDrops = 103;
            usb.sample(23_000, 23_000, 55_200_000);
            assertEquals(Set.of("receiver-ingress-drop"), usb.codes());
        }
    }

    @Test
    void receivingIntentSurvivesFailedTunerTeardownButAnIdleSpareIsNotRequired() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            var receiving = new io.github.dsheirer.source.tuner.manager.TestPolyphaseChannelSourceManager(
                usb.tuner.getTunerController())
            {
                @Override public int getTunerChannelCount() { return 1; }
            };
            assertEquals(true, usb.prepareOwner.invoke(usb.service, "required", usb.tuner.getTunerController(), receiving, 1L));
            assertEquals(true, usb.prepareOwner.invoke(usb.service, "required", null, null, 1L),
                "A failed tuner can release its source manager before status is sampled");
            assertEquals(false, usb.prepareOwner.invoke(usb.service, "spare", usb.tuner.getTunerController(),
                usb.tuner.getChannelSourceManager(), 1L));
            assertEquals(false, usb.prepareOwner.invoke(usb.service, "spare", null, null, 1L));
        }
    }

    @Test
    void spectrumOnlyUsbFaultsStayVisibleWithoutReceiverIncidentsAndResumeNeedsFreshEvidence() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.receiving = false;
            usb.sample(0, 0, 0);
            for(int second = 1; second <= 12; second++)
            {
                usb.copyFailures = second;
                usb.sample(second * 1_000L, second * 1_000L, second * 2_400_000L);
            }
            assertTrue(usb.codes().isEmpty());
            assertTrue(usb.measurements.stream().anyMatch(row ->
                String.valueOf(row.get("detail")).contains("Processing errors 12")));
            usb.receiving = true;
            for(int second = 13; second <= 24; second++)
            {
                usb.copyFailures = second;
                usb.sample(second * 1_000L, second * 1_000L, second * 2_400_000L);
                if(second < 24) assertTrue(usb.codes().isEmpty(), "Idle work cannot confirm receiver loss");
            }
            assertEquals(Set.of("usb-sample-loss"), usb.codes());
        }
    }

    @Test
    void scatteredSmallUsbFaultsDoNotAccumulateAcrossHealthyMeasurements() throws Exception
    {
        try(UsbFixture usb = new UsbFixture())
        {
            usb.sample(0, 0, 0);
            for(int second = 1; second <= 20; second++)
            {
                if(second == 1 || second == 5 || second == 9 || second == 13 || second == 17)
                    usb.copyFailures++;
                usb.sample(second * 1_000L, second * 1_000L, second * 2_400_000L);
                assertTrue(usb.codes().isEmpty(), "A few separated faults are not continuing receiver loss");
            }
            assertTrue(usb.incidents.resolved().isEmpty(), "No hidden incident should have opened between checks");
            assertTrue(usb.measurements.stream().anyMatch(row ->
                String.valueOf(row.get("detail")).contains("Processing errors 5")),
                "All isolated faults must remain visible in the diagnostic counters");
        }
    }

    @Test
    void closeInterruptsAndJoinsAnInProgressObserverSample() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            System::currentTimeMillis);
        service.setWebStatusSupplier(() ->
        {
            entered.countDown();

            try
            {
                new CountDownLatch(1).await();
            }
            catch(InterruptedException interruptedException)
            {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }

            return Map.of();
        });
        service.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        service.close();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test
    void transientObserverFailureDoesNotReplayCumulativeLossOnRecovery()
    {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicBoolean fail = new AtomicBoolean();

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setWebStatusSupplier(() ->
            {
                if(fail.get())
                {
                    throw new IllegalStateException("temporary observer failure");
                }

                return Map.of("server", Map.of("live_transport", Map.of(
                    "active_clients", 0, "rejected_clients", 0, "slow_disconnects", 0, "event_drops", 2)),
                    "web_player", Map.of(), "diagnostics", Map.of());
            });
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());

            clock.set(11_000);
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertTrue(rows(service.snapshot().get("resolved")).isEmpty());

            fail.set(true);
            clock.set(12_000);
            service.sampleNow();
            fail.set(false);
            clock.set(13_000);
            service.sampleNow();

            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertTrue(rows(service.snapshot().get("resolved")).isEmpty());
        }
    }

    @Test
    void reportsSiteLevelControlLockLossAcrossAlternateFrequencySearch()
    {
        AtomicLong clock = new AtomicLong(1_000);
        TestControl monitor = new TestControl("00000000-0000-0000-0000-000000000071");
        monitor.observe(773_831_250L, 1_000, 1_000, 10, 0);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setControlQualitySupplier(() -> List.of(monitor));
            service.sampleNow();
            clock.set(10_999);
            monitor.observe(774_281_250L, 10_999, 0, 0, 0);
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
            clock.set(11_000);
            monitor.observe(774_531_250L, 11_000, 0, 0, 0);
            service.sampleNow();
            assertEquals("control-channel-lock-lost", controlIncidents(service.snapshot()).getFirst().get("code"));
            assertEquals("critical", map(service.snapshot().get("summary")).get("severity"));
            monitor.observe(774_531_250L, 11_001, 11_001, 1, 0);
            clock.set(11_001);
            service.sampleNow();
            clock.set(21_001);
            monitor.observe(774_531_250L, 21_001, 21_001, 1, 0);
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty(), "Received frames resolve the loss after its normal hold");
        }
    }

    @Test
    void restartAndReplacementCannotInheritThePreviousDecodersLastFrame()
    {
        AtomicLong clock = new AtomicLong(1_000);
        TestControl monitor = new TestControl("00000000-0000-0000-0000-000000000071");
        AtomicReference<TestControl> current = new AtomicReference<>(monitor);
        monitor.observe(773_831_250L, 1_000, 1_000, 10, 0);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setControlQualitySupplier(() -> List.of(current.get()));
            service.sampleNow();
            clock.set(11_000);
            monitor.generation++;
            monitor.observe(773_831_250L, 11_000, 0, 0, 0);
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
            monitor.observe(773_831_250L, 11_001, 11_001, 1, 0);
            clock.set(11_001);
            service.sampleNow();
            current.set(new TestControl("00000000-0000-0000-0000-000000000071"));
            current.get().observe(773_831_250L, 30_000, 0, 0, 0);
            clock.set(30_000);
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
            current.get().observation = null;
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
        }
    }

    @Test
    void conventionalReceiversNeverBecomeControlLossIncidents()
    {
        AtomicLong clock = new AtomicLong(30_000);
        TestControl monitor = new TestControl("00000000-0000-0000-0000-000000000071");
        monitor.channel.setDecodeConfiguration(new DecodeConfigDMR());
        monitor.observe(452_012_500L, 30_000, 1_000, 10, 0);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setControlQualitySupplier(() -> List.of(monitor));
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
            assertTrue(rows(rows(service.snapshot().get("measurements")).stream()
                .filter(section -> "decoders".equals(section.get("id"))).findFirst().orElseThrow().get("rows")).isEmpty());
        }
    }

    @Test
    void staleQualityIsUnavailableButStillPreservesEvidenceOfARealDecoderStall()
    {
        AtomicLong clock = new AtomicLong(1_000);
        TestControl monitor = new TestControl("00000000-0000-0000-0000-000000000071");
        monitor.observe(773_831_250L, 1_000, 1_000, 10, 48);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setControlQualitySupplier(() -> List.of(monitor));
            service.sampleNow();
            assertTrue(String.valueOf(measurement(service.snapshot(), "decoders", monitor.scope()).get("detail"))
                .contains("Dropped 48 bits"));
            clock.set(7_000);
            service.sampleNow();
            assertEquals("Unavailable", measurement(service.snapshot(), "decoders", monitor.scope()).get("value"));
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
            clock.set(11_000);
            service.sampleNow();
            assertEquals("control-channel-lock-lost", controlIncidents(service.snapshot()).getFirst().get("code"));
        }
    }

    @Test
    void keepsSameNamedSitesAsSeparateControlLockIncidents()
    {
        AtomicLong clock = new AtomicLong(1_000);
        TestControl first = new TestControl("00000000-0000-0000-0000-000000000071");
        TestControl second = new TestControl("00000000-0000-0000-0000-000000000072");
        first.observe(773_831_250L, 1_000, 1_000, 10, 0);
        second.observe(856_162_500L, 1_000, 1_000, 10, 0);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setControlQualitySupplier(() -> List.of(first, second));
            service.sampleNow();
            clock.set(11_000);
            service.sampleNow();
            assertEquals(2, controlIncidents(service.snapshot()).size());
        }
    }

    @Test
    void aControlReceiverThatNeverAcquiredFramesStillReportsTenSecondAbsence()
    {
        AtomicLong wall = new AtomicLong(1_000);
        AtomicLong nanos = new AtomicLong();
        TestControl monitor = new TestControl("00000000-0000-0000-0000-000000000071");
        monitor.observe(773_831_250L, 1_000, 0, 0, 0);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            wall::get, null, nanos::get))
        {
            service.setControlQualitySupplier(() -> List.of(monitor));
            service.sampleNow();
            nanos.set(TimeUnit.MILLISECONDS.toNanos(9_999));
            wall.set(10_999);
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
            nanos.set(TimeUnit.SECONDS.toNanos(10));
            wall.set(11_000);
            service.sampleNow();
            assertEquals(1, controlIncidents(service.snapshot()).size());
        }
    }

    @Test
    void controlFrameProgressAndAbsenceIgnoreBothDirectionsOfWallClockCorrection()
    {
        AtomicLong wall = new AtomicLong(100_000);
        AtomicLong nanos = new AtomicLong();
        TestControl monitor = new TestControl("00000000-0000-0000-0000-000000000071");
        monitor.observe(773_831_250L, wall.get(), wall.get(), 10, 0);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            wall::get, null, nanos::get))
        {
            service.setControlQualitySupplier(() -> List.of(monitor));
            service.sampleNow();
            wall.set(1_000_000);
            nanos.set(TimeUnit.MILLISECONDS.toNanos(9_999));
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty(), "Forward wall time is not receiver absence");
            wall.set(50_000);
            nanos.set(TimeUnit.SECONDS.toNanos(10));
            monitor.observe(773_831_250L, wall.get(), wall.get(), 1, 0);
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty(), "A real frame with an earlier wall timestamp is progress");
            wall.set(500);
            nanos.set(TimeUnit.MILLISECONDS.toNanos(19_999));
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
            nanos.set(TimeUnit.SECONDS.toNanos(20));
            service.sampleNow();
            assertEquals(1, controlIncidents(service.snapshot()).size(), "Real absence still alarms despite backward wall time");
        }
    }

    @Test
    void staleDisplaySnapshotDoesNotHideRealFrameProgressOrInventAStall()
    {
        AtomicLong clock = new AtomicLong(1_000);
        TestControl monitor = new TestControl("00000000-0000-0000-0000-000000000071");
        monitor.observe(773_831_250L, 1_000, 1_000, 10, 0);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setControlQualitySupplier(() -> List.of(monitor));
            service.sampleNow();
            for(long time: new long[]{11_000, 21_000})
            {
                clock.set(time);
                monitor.validFrameSequence++; // Receiver has decoded a real frame; its display publication is stale.
                service.sampleNow();
                assertEquals("Unavailable", measurement(service.snapshot(), "decoders", monitor.scope()).get("value"));
                assertTrue(controlIncidents(service.snapshot()).isEmpty());
            }
            clock.set(31_000);
            service.sampleNow();
            assertEquals(1, controlIncidents(service.snapshot()).size());
        }
    }

    @Test
    void unavailableControlEvidenceCannotResolveAnActiveLoss()
    {
        AtomicLong clock = new AtomicLong(1_000);
        TestControl monitor = new TestControl("00000000-0000-0000-0000-000000000071");
        monitor.observe(773_831_250L, 1_000, 1_000, 10, 0);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setControlQualitySupplier(() -> List.of(monitor));
            service.sampleNow();
            clock.set(11_000);
            service.sampleNow();
            assertEquals(1, controlIncidents(service.snapshot()).size());
            monitor.observation = null;
            clock.set(21_000);
            service.sampleNow();
            assertEquals(1, controlIncidents(service.snapshot()).size());
            monitor.observe(773_831_250L, 21_001, 21_001, 1, 0);
            clock.set(21_001);
            service.sampleNow();
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
        }
    }

    @Test
    void publishesReceiverMeasurementsWithFriendlyNamesAndNativeSiteFactsWithoutActivityTables()
    {
        AtomicLong clock = new AtomicLong(2_000);
        TestControl monitor = new TestControl("00000000-0000-0000-0000-000000000071");
        monitor.channel.setSystem("GCRCN");
        monitor.channel.setSite("Cleveland");
        monitor.channel.setName("Cleveland Control");
        monitor.channel.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x49F, 1, 2));
        monitor.observe(851_012_500L, 2_000, 2_000, 10, 0);
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get, null, () -> TimeUnit.MILLISECONDS.toNanos(clock.get())))
        {
            service.setControlQualitySupplier(() -> List.of(monitor));
            service.setRadioResolveAcceptedCalls(() -> 37);
            service.sampleNow();
            Map<String,Object> measurement = measurement(service.snapshot(), "decoders", monitor.scope());
            assertEquals("GCRCN", measurement.get("system_name"));
            assertEquals("Cleveland Control", measurement.get("channel_name"));
            assertEquals(monitor.channel.getConfigurationId(), measurement.get("configuration_id"));
            assertEquals(0xBEE00, measurement.get("wacn"));
            assertEquals(0x49F, measurement.get("system_id"));
            assertEquals(1, measurement.get("rfss"));
            assertEquals(2, measurement.get("site_id"));
            assertEquals(37L, service.snapshot().get("radioresolve_accepted_calls"));
            assertTrue(controlIncidents(service.snapshot()).isEmpty());
        }
    }

    private static final class TestControl extends ControlChannelQualityMonitor
    {
        private final Channel channel;
        private ReceiverObservation observation;
        private long generation = 1;
        private long validFrameSequence;
        private long lastFrameAtMs;

        private TestControl(String configuration)
        {
            this(controlChannel(configuration));
        }

        private TestControl(Channel channel)
        {
            super(channel, 773_831_250L, ignored -> {});
            this.channel = channel;
        }

        private static Channel controlChannel(String configuration)
        {
            Channel channel = new Channel("Control", Channel.ChannelType.STANDARD);
            channel.setConfigurationId(configuration);
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            channel.setSystem("County");
            channel.setSite("Downtown");
            return channel;
        }

        private void observe(long frequency, long observed, long lastValid, long valid, long dropped)
        {
            if(valid > 0 && (validFrameSequence == 0 || lastValid != lastFrameAtMs))
            {
                validFrameSequence++;
                lastFrameAtMs = lastValid;
            }
            observation = new ReceiverObservation(new ControlChannelQualitySnapshot(channel,
                SiteReceiverContext.capture(channel, null, frequency), frequency, observed, true,
                -45.0, -45.0, -45.0, -45.0, valid > 0 ? 98.0 : null, valid, 0, 0, 0, dropped, lastValid),
                1, generation);
        }

        @Override public ReceiverObservation getReceiverObservation() { return observation; }
        @Override public long getValidFrameSequence() { return validFrameSequence; }
        private String scope() { return "channel:" + channel.getConfigurationId(); }
    }

    private static List<Map<String,Object>> controlIncidents(Map<String,Object> snapshot)
    {
        return rows(snapshot.get("active")).stream()
            .filter(row -> "control-channel-lock-lost".equals(row.get("code"))).toList();
    }

    private static Map<String,Object> measurement(Map<String,Object> snapshot, String sectionId, String scope)
    {
        return rows(snapshot.get("measurements")).stream()
            .filter(section -> sectionId.equals(section.get("id")))
            .flatMap(section -> rows(section.get("rows")).stream())
            .filter(row -> scope.equals(row.get("scope")))
            .findFirst().orElseThrow();
    }

    private static Map<String,Object> incident(String code, String severity)
    {
        return Map.of("code", code, "severity", severity);
    }

    private static final class UsbFixture implements AutoCloseable
    {
        private final Tuner tuner = new TestTuner(new LoggingTunerErrorListener());
        private boolean receiving = true;
        private final io.github.dsheirer.source.tuner.manager.ChannelSourceManager sourceManager =
            new io.github.dsheirer.source.tuner.manager.TestPolyphaseChannelSourceManager(tuner.getTunerController())
            {
                @Override public int getTunerChannelCount() { return receiving ? 1 : 0; }
            };
        private final AtomicLong wall = new AtomicLong(1_000);
        private final AtomicLong nanos = new AtomicLong();
        private final ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            wall::get, null, nanos::get);
        private final ReceiverHealthIncidentTracker incidents;
        private final Method collect;
        private final Method prepareOwner;
        private final List<Map<String,Object>> measurements = new java.util.ArrayList<>();
        private long sequence = 1;
        private long ingressDrops;
        private long copyFailures;
        private long conversionFailures;
        private long listenerFailures;
        private long shortTransfers;
        private long statusErrors;
        private long slowCallbacks;
        private Long lastDelivery;

        private UsbFixture() throws Exception
        {
            Field field = ReceiverHealthService.class.getDeclaredField("mIncidents");
            field.setAccessible(true);
            incidents = (ReceiverHealthIncidentTracker)field.get(service);
            collect = ReceiverHealthService.class.getDeclaredMethod("collectUsb", long.class, String.class,
                String.class, Tuner.class, boolean.class, USBTunerController.UsbTransferHealthSnapshot.class,
                List.class, ReceiverHealthDiagnosticCore.Tuner.class);
            collect.setAccessible(true);
            prepareOwner = ReceiverHealthService.class.getDeclaredMethod("prepareCounterOwner", String.class,
                io.github.dsheirer.source.tuner.TunerController.class, Object.class, long.class);
            prepareOwner.setAccessible(true);
        }

        private void sample(long wallMs, long monotonicMs, long deliveredBytes) throws Exception
        {
            long observedNanos = TimeUnit.MILLISECONDS.toNanos(monotonicMs);
            wall.set(wallMs);
            nanos.set(observedNanos);
            long last = lastDelivery != null ? lastDelivery : monotonicMs;
            var snapshot = new USBTunerController.UsbTransferHealthSnapshot(true, sequence, observedNanos,
                1_024, 1, 0, "unknown", 8, 8, 0, 0,
                deliveredBytes > 0 ? 1 : 0, deliveredBytes > 0 ? 1 : 0,
                0, 0, statusErrors, 0, 0, deliveredBytes, deliveredBytes, deliveredBytes,
                0, 0, shortTransfers, 0, 0, 0,
                1_000, wallMs, wallMs, 0, TimeUnit.MILLISECONDS.toNanos(last), deliveredBytes > 0,
                0, 0, 0, 0, 0, 100, slowCallbacks, 0, slowCallbacks > 0 ? 25_000_000 : 0,
                16, 0, 0, ingressDrops, ingressDrops * 1_024,
                copyFailures, conversionFailures, listenerFailures, 0, 0);
            incidents.beginSample(observedNanos);
            prepareOwner.invoke(service, "usb-test", tuner.getTunerController(), sourceManager, sequence);
            measurements.clear();
            collect.invoke(service, wallMs, "usb-test", "USB test tuner", tuner, receiving, snapshot, measurements, null);
            incidents.endSample(wallMs);
        }

        private Set<String> codes()
        {
            return incidents.active().stream().map(incident -> String.valueOf(incident.get("code")))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        @Override public void close() { service.close(); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value)
    {
        return (Map<String,Object>)value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Object value)
    {
        return (List<Map<String,Object>>)value;
    }
}
