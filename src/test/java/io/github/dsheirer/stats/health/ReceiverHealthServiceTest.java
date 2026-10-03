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

import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelManager;
import io.github.dsheirer.source.tuner.LoggingTunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.test.TestTuner;
import io.github.dsheirer.source.tuner.usb.USBTunerController;
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
            incident("receiver-queue-pressure", "warning"),
            incident("channelizer-queue-pressure", "warning"),
            incident("usb-transfer-gap", "warning")));

        assertEquals("warning", summary.get("severity"));
        assertEquals(3, summary.get("active_count"));
        assertEquals(3L, summary.get("warning_count"));
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
            clock::get))
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
            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertTrue(rows(service.snapshot().get("resolved")).isEmpty());
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

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            clock::get))
        {
            service.setWebStatusSupplier(() -> Map.of(
                "server", Map.of("live_transport", Map.of()),
                "web_player", Map.of(
                    "published_calls", 7,
                    "active_feeds", 1,
                    "encoder_queue_depth", 0,
                    "dropped_encoder_capacity", 2,
                    "encoder_failures", 3,
                    "rejected_feeds", 0,
                    "rejected_audio_responses", 0),
                "diagnostics", Map.of()));
            service.sampleNow();

            Map<String,Object> webAudio = measurement(service.snapshot(), "supporting", "Browser audio");
            String detail = String.valueOf(webAudio.get("detail"));
            assertEquals("warning", webAudio.get("severity"));
            assertTrue(detail.contains("Dropped 2"));
            assertTrue(detail.contains("Audio preparation failures 3"));
            assertFalse(detail.contains("capacity_drops"));

            Map<String,Object> incident = rows(service.snapshot().get("active")).stream()
                .filter(row -> "web-audio-drop".equals(row.get("code"))).findFirst().orElseThrow();
            assertEquals("Browser audio was not available for a call", incident.get("title"));
            assertEquals(5L, incident.get("count"));
            assertEquals("5 browser calls dropped or failed", incident.get("observed"));
            assertTrue(String.valueOf(incident.get("likely_cause")).contains("could not keep up"));
            assertTrue(String.valueOf(incident.get("likely_cause")).contains("prepare the call's audio"));
        }
    }

    @Test
    void routesUsbIngressFailureCountersToTheirIntendedIncidents() throws Exception
    {
        assertEquals(Set.of("receiver-listener-failure"), usbIncidentCodes(0, 0, 0, 1));
        assertEquals(Set.of("receiver-ingress-drop"), usbIncidentCodes(1, 0, 0, 0));
        assertEquals(Set.of("usb-sample-loss"), usbIncidentCodes(0, 1, 0, 0));
        assertEquals(Set.of("usb-sample-loss"), usbIncidentCodes(0, 0, 1, 0));
    }

    @Test
    void usbAlertKeepsMeasuredSlowHandlingClueAfterLaterSamplesAndResolution() throws Exception
    {
        Tuner tuner = new TestTuner(new LoggingTunerErrorListener());

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1_000L))
        {
            ReceiverHealthIncidentTracker incidents = usbIncidentTracker(service);
            Method collectUsb = usbCollector();
            List<Map<String,Object>> measurements = new java.util.ArrayList<>();

            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 1_000L,
                usbTimingSnapshot(1_000L, 0, 0, 0, 0));
            measurements.clear();
            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 2_000L,
                usbTimingSnapshot(2_000L, 1_200_000L, 100, 1, 0));

            Map<String,Object> alert = incidents.active().stream()
                .filter(row -> "usb-delivery-rate-low".equals(row.get("code"))).findFirst().orElseThrow();
            assertTrue(String.valueOf(alert.get("likely_cause")).contains("VCE took at least 25 ms"));
            assertTrue(measurements.stream().anyMatch(row ->
                String.valueOf(row.get("label")).contains("USB data handling time") &&
                    String.valueOf(row.get("detail")).contains("Took at least 25 ms 1")));

            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 3_000L,
                usbTimingSnapshot(3_000L, 2_200_000L, 200, 1, 0));
            alert = incidents.active().stream()
                .filter(row -> "usb-delivery-rate-low".equals(row.get("code"))).findFirst().orElseThrow();
            assertTrue(String.valueOf(alert.get("likely_cause")).contains("VCE took at least 25 ms"));

            incidents.beginSample();
            incidents.endSample(14_000L);
            Map<String,Object> cleared = incidents.resolved().stream()
                .filter(row -> "usb-delivery-rate-low".equals(row.get("code"))).findFirst().orElseThrow();
            assertTrue(String.valueOf(cleared.get("likely_cause")).contains("VCE took at least 25 ms"));
        }
    }

    @Test
    void usbAlertDoesNotBlameVceWhenMeasuredCallbacksWereFast() throws Exception
    {
        Tuner tuner = new TestTuner(new LoggingTunerErrorListener());

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1_000L))
        {
            ReceiverHealthIncidentTracker incidents = usbIncidentTracker(service);
            Method collectUsb = usbCollector();
            List<Map<String,Object>> measurements = new java.util.ArrayList<>();

            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 1_000L,
                usbTimingSnapshot(1_000L, 0, 100, 1, 0));
            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 2_000L,
                usbTimingSnapshot(2_000L, 1_200_000L, 200, 1, 0));

            Map<String,Object> alert = incidents.active().stream()
                .filter(row -> "usb-delivery-rate-low".equals(row.get("code"))).findFirst().orElseThrow();
            String cause = String.valueOf(alert.get("likely_cause"));
            assertTrue(cause.contains("took less than 25 ms"));
            assertTrue(cause.contains("may have happened before data reached VCE or between USB deliveries"));
        }
    }

    @Test
    void usbAlertSaysWhenNoCallbackTimingWasAvailable() throws Exception
    {
        Tuner tuner = new TestTuner(new LoggingTunerErrorListener());

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1_000L))
        {
            ReceiverHealthIncidentTracker incidents = usbIncidentTracker(service);
            Method collectUsb = usbCollector();
            List<Map<String,Object>> measurements = new java.util.ArrayList<>();

            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 1_000L,
                usbTimingSnapshot(1_000L, 0, 0, 0, 0));
            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 2_000L,
                usbTimingSnapshot(2_000L, 1_200_000L, 0, 0, 0));

            Map<String,Object> alert = incidents.active().stream()
                .filter(row -> "usb-delivery-rate-low".equals(row.get("code"))).findFirst().orElseThrow();
            assertTrue(String.valueOf(alert.get("likely_cause")).contains("could not measure"));
        }
    }

    @Test
    void usbTimingClueDoesNotLeakFromAnEarlierDifferentAlert() throws Exception
    {
        Tuner tuner = new TestTuner(new LoggingTunerErrorListener());

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1_000L))
        {
            ReceiverHealthIncidentTracker incidents = usbIncidentTracker(service);
            Method collectUsb = usbCollector();
            List<Map<String,Object>> measurements = new java.util.ArrayList<>();

            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 1_000L,
                usbTimingSnapshot(1_000L, 0, 0, 0, 0));
            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 2_000L,
                usbTimingSnapshot(2_000L, 2_400_000L, 100, 1, 0, 1, 1, 0));
            Map<String,Object> pause = incidents.active().stream()
                .filter(row -> "usb-transfer-gap".equals(row.get("code"))).findFirst().orElseThrow();
            assertTrue(String.valueOf(pause.get("likely_cause")).contains("VCE took at least 25 ms"));

            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 3_000L,
                usbTimingSnapshot(3_000L, 4_800_000L, 200, 1, 0, 1, 1, 0));
            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 4_000L,
                usbTimingSnapshot(4_000L, 6_000_000L, 300, 1, 0, 1, 1, 0));
            Map<String,Object> rate = incidents.active().stream()
                .filter(row -> "usb-delivery-rate-low".equals(row.get("code"))).findFirst().orElseThrow();
            assertTrue(String.valueOf(rate.get("likely_cause")).contains("took less than 25 ms"));
        }
    }

    @Test
    void usbAlertKeepsItsClueWhenTheTunerRestartsBeforeItClears() throws Exception
    {
        Tuner tuner = new TestTuner(new LoggingTunerErrorListener());

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1_000L))
        {
            ReceiverHealthIncidentTracker incidents = usbIncidentTracker(service);
            Method collectUsb = usbCollector();
            List<Map<String,Object>> measurements = new java.util.ArrayList<>();

            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 1_000L,
                usbTimingSnapshot(1_000L, 0, 0, 0, 0));
            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 2_000L,
                usbTimingSnapshot(2_000L, 1_200_000L, 100, 1, 1));
            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 3_000L,
                usbTimingSnapshot(3_000L, 0, 100, 1, 1, 2, 0, 0));
            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 4_000L,
                usbTimingSnapshot(4_000L, 1_200_000L, 200, 1, 1, 2, 0, 0));

            Map<String,Object> alert = incidents.active().stream()
                .filter(row -> "usb-delivery-rate-low".equals(row.get("code"))).findFirst().orElseThrow();
            assertTrue(String.valueOf(alert.get("likely_cause")).contains("VCE took at least 100 ms"));
        }
    }

    @Test
    void incompleteUsbDataAlertAlsoIncludesTheTimingClue() throws Exception
    {
        Tuner tuner = new TestTuner(new LoggingTunerErrorListener());

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1_000L))
        {
            ReceiverHealthIncidentTracker incidents = usbIncidentTracker(service);
            Method collectUsb = usbCollector();
            List<Map<String,Object>> measurements = new java.util.ArrayList<>();

            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 1_000L,
                usbTimingSnapshot(1_000L, 0, 0, 0, 0));
            collectUsbSample(incidents, collectUsb, service, tuner, measurements, 2_000L,
                usbTimingSnapshot(2_000L, 2_400_000L, 100, 1, 0, 1, 0, 1));

            Map<String,Object> alert = incidents.active().stream()
                .filter(row -> "usb-sample-loss".equals(row.get("code"))).findFirst().orElseThrow();
            assertTrue(String.valueOf(alert.get("likely_cause")).contains("VCE took at least 25 ms"));
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
            clock::get))
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
        AtomicReference<ChannelActivityModel.SnapshotSet> activity = new AtomicReference<>(
            activity(773_831_250L, 1_000L, 1_000L, 10));

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setChannelActivitySnapshotSupplier(activity::get);
            service.sampleNow();
            assertEquals("healthy", map(service.snapshot().get("summary")).get("severity"));

            clock.set(10_999L);
            activity.set(activity(774_281_250L, 10_999L, 0, 0));
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());

            clock.set(11_000L);
            activity.set(activity(774_531_250L, 11_000L, 0, 0));
            service.sampleNow();
            List<Map<String,Object>> active = rows(service.snapshot().get("active"));
            assertEquals(1, active.size());
            assertEquals("control-channel-lock-lost", active.getFirst().get("code"));
            assertEquals("critical", map(service.snapshot().get("summary")).get("severity"));

            clock.set(11_001L);
            activity.set(activity(774_781_250L, 11_001L, 0, 0));
            service.sampleNow();
            assertEquals(1, rows(service.snapshot().get("active")).size());
        }
    }

    @Test
    void doesNotReportControlLockLossBeforeAValidControlFrame()
    {
        AtomicLong clock = new AtomicLong(30_000L);

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setChannelActivitySnapshotSupplier(() -> activity(774_281_250L, clock.get(), 0, 0));
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertEquals("healthy", map(service.snapshot().get("summary")).get("severity"));
        }
    }

    @Test
    void keepsRawDecoderDropEvidenceAsAnInformationalMeasurement()
    {
        AtomicLong clock = new AtomicLong(1_000L);

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setChannelActivitySnapshotSupplier(() -> activity(773_831_250L, clock.get(), 0, 0, 48));
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertTrue(rows(service.snapshot().get("resolved")).isEmpty());
            Map<String,Object> summary = map(service.snapshot().get("summary"));
            assertEquals("healthy", summary.get("severity"));
            assertEquals(0, summary.get("active_count"));
            assertFalse(summary.containsKey("diagnostic_count"));
            assertTrue(String.valueOf(measurement(service.snapshot(), "decoders", "site-table").get("detail"))
                .contains("Dropped 48 bits"));
        }
    }

    @Test
    void keepsSameNamedSitesAsSeparateControlLockIncidents()
    {
        AtomicLong clock = new AtomicLong(1_000L);
        AtomicReference<ChannelActivityModel.SnapshotSet> activity = new AtomicReference<>(
            new ChannelActivityModel.SnapshotSet(1_000L, List.of(
                controlTable("site-a", 773_831_250L, 1_000L, 1_000L, 10, 0),
                controlTable("site-b", 856_162_500L, 1_000L, 1_000L, 10, 0))));

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setChannelActivitySnapshotSupplier(activity::get);
            service.sampleNow();
            clock.set(11_000L);
            activity.set(new ChannelActivityModel.SnapshotSet(11_000L, List.of(
                controlTable("site-a", 774_281_250L, 11_000L, 0, 0, 0),
                controlTable("site-b", 855_987_500L, 11_000L, 0, 0, 0))));
            service.sampleNow();
            assertEquals(2, rows(service.snapshot().get("active")).size());
            assertEquals(2, map(service.snapshot().get("summary")).get("active_count"));
        }
    }

    @Test
    void publishesFreshControlMeasurementsWithFriendlyNamesAndNativeSiteFacts()
    {
        AtomicLong clock = new AtomicLong(1_000L);
        AtomicLong acknowledgedCalls = new AtomicLong(37L);
        String configuration = "00000000-0000-0000-0000-000000000071";
        String tableId = "channel:" + configuration;
        long frequency = 851_012_500L;
        ChannelActivitySnapshot control = controlTable(tableId, frequency, 2_000L, 2_000L, 10, 0);
        ChannelActivitySnapshot named = new ChannelActivitySnapshot(tableId, "BEE00-49F", "GCRCN",
            "Cleveland", "Cleveland Control", configuration, true, true, List.of(), control.rows(),
            new ChannelActivitySnapshot.Site(0xBEE00, 0x49F, 1, 2, 0x49F));

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setRadioResolveAcceptedCalls(acknowledgedCalls::get);
            service.setChannelActivitySnapshotSupplier(() ->
                new ChannelActivityModel.SnapshotSet(clock.get(), List.of(named)));
            clock.set(2_000L);
            service.sampleNow();

            Map<String,Object> snapshot = service.snapshot();
            assertEquals(2_000L, snapshot.get("generated_at_ms"));
            assertEquals(37L, snapshot.get("radioresolve_accepted_calls"));
            Map<String,Object> measurement = measurement(snapshot, "decoders", tableId);
            assertEquals("GCRCN", measurement.get("system_name"));
            assertEquals("Cleveland Control", measurement.get("channel_name"));
            assertEquals(configuration, measurement.get("configuration_id"));
            assertEquals(frequency, measurement.get("frequency_hz"));
            assertEquals(0xBEE00, measurement.get("wacn"));
            assertEquals(0x49F, measurement.get("system_id"));
            assertEquals(1, measurement.get("rfss"));
            assertEquals(2, measurement.get("site_id"));
            assertEquals("healthy", map(snapshot.get("summary")).get("severity"));

            clock.set(2_500L);
            acknowledgedCalls.set(40L);
            service.sampleNow();
            assertEquals(2_500L, service.snapshot().get("generated_at_ms"),
                "Control measurement metadata must not prevent later health snapshots from publishing");
            assertEquals(40L, service.snapshot().get("radioresolve_accepted_calls"),
                "The acknowledged upload counter must stay current while control channels are active");
        }
    }

    @Test
    void publishesControlMeasurementsBeforeOptionalNamesAndNativeIdentityAreKnown()
    {
        AtomicLong clock = new AtomicLong(1_000L);
        ChannelActivitySnapshot control = controlTable("unnamed-control", 851_012_500L,
            2_000L, 2_000L, 10, 0);
        ChannelActivitySnapshot unnamed = new ChannelActivitySnapshot(control.tableId(), "Control channel",
            null, null, null, null, true, true, List.of(), control.rows(),
            new ChannelActivitySnapshot.Site(null, null, null, null, null));

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setChannelActivitySnapshotSupplier(() ->
                new ChannelActivityModel.SnapshotSet(clock.get(), List.of(unnamed)));
            clock.set(2_000L);
            service.sampleNow();

            assertEquals(2_000L, service.snapshot().get("generated_at_ms"));
            Map<String,Object> measurement = measurement(service.snapshot(), "decoders", "unnamed-control");
            assertEquals("", measurement.get("system_name"));
            assertEquals("", measurement.get("channel_name"));
            assertEquals("", measurement.get("configuration_id"));
            assertNull(measurement.get("wacn"));
            assertNull(measurement.get("system_id"));
            assertNull(measurement.get("rfss"));
            assertNull(measurement.get("site_id"));
            assertTrue(rows(service.snapshot().get("active")).isEmpty());
        }
    }

    private static ChannelActivityModel.SnapshotSet activity(long frequencyHz, long observedAtMs,
                                                              long lastValidDecodeMs, long validFrames)
    {
        return activity(frequencyHz, observedAtMs, lastValidDecodeMs, validFrames, 0);
    }

    private static ChannelActivityModel.SnapshotSet activity(long frequencyHz, long observedAtMs,
                                                              long lastValidDecodeMs, long validFrames,
                                                              long droppedBits)
    {
        return new ChannelActivityModel.SnapshotSet(observedAtMs, List.of(controlTable("site-table", frequencyHz,
            observedAtMs, lastValidDecodeMs, validFrames, droppedBits)));
    }

    private static ChannelActivitySnapshot controlTable(String tableId, long frequencyHz, long observedAtMs,
                                                         long lastValidDecodeMs, long validFrames,
                                                         long droppedBits)
    {
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("control-" + frequencyHz, "Control",
            null, "ACTIVE", List.of("CURRENT_CONTROL"), 1L, null, frequencyHz, null, -45.0,
            validFrames > 0 ? 98.0 : null, observedAtMs, validFrames, 0, 0, 0, droppedBits, lastValidDecodeMs,
            null, null, null, null, null, null, null, null, null, null, null, null, "P25_PHASE1", null, null,
            "CURRENT_CONTROL");
        return new ChannelActivitySnapshot(tableId, "County · Downtown", "County",
            "Downtown", "Control", null, true, true, List.of(), List.of(row));
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

    private static Set<String> usbIncidentCodes(long saturationDrops, long copyFailures,
                                                 long conversionFailures, long listenerFailures) throws Exception
    {
        Tuner tuner = new TestTuner(new LoggingTunerErrorListener());

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 2_000L))
        {
            ReceiverHealthIncidentTracker incidents = usbIncidentTracker(service);
            Method collectUsb = usbCollector();
            List<Map<String,Object>> rows = new java.util.ArrayList<>();

            incidents.beginSample();
            collectUsb.invoke(service, 1_000L, "usb-test", "USB test tuner", tuner,
                usbSnapshot(1_000L, 0, 0, 0, 0), rows);
            incidents.endSample(1_000L);
            incidents.beginSample();
            collectUsb.invoke(service, 2_000L, "usb-test", "USB test tuner", tuner,
                usbSnapshot(2_000L, saturationDrops, copyFailures, conversionFailures, listenerFailures), rows);
            incidents.endSample(2_000L);
            return incidents.active().stream().map(incident -> String.valueOf(incident.get("code")))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static USBTunerController.UsbTransferHealthSnapshot usbSnapshot(long now, long saturationDrops,
                                                                             long copyFailures,
                                                                             long conversionFailures,
                                                                             long listenerFailures)
    {
        long deliveredBytes = now == 1_000L ? 0 : 2_400_000L;
        return usbSnapshot(now, saturationDrops, copyFailures, conversionFailures, listenerFailures,
            deliveredBytes, 0, 0, 0);
    }

    private static USBTunerController.UsbTransferHealthSnapshot usbTimingSnapshot(long now, long deliveredBytes,
                                                                                   long callbacks, long slow25,
                                                                                   long slow100)
    {
        return usbTimingSnapshot(now, deliveredBytes, callbacks, slow25, slow100, 1, 0, 0);
    }

    private static USBTunerController.UsbTransferHealthSnapshot usbTimingSnapshot(long now, long deliveredBytes,
                                                                                   long callbacks, long slow25,
                                                                                   long slow100, long streamSequence,
                                                                                   long longGaps, long shortTransfers)
    {
        return usbSnapshot(now, 0, 0, 0, 0, deliveredBytes, callbacks, slow25, slow100, streamSequence,
            longGaps, shortTransfers);
    }

    private static USBTunerController.UsbTransferHealthSnapshot usbSnapshot(long now, long saturationDrops,
                                                                             long copyFailures,
                                                                             long conversionFailures,
                                                                             long listenerFailures,
                                                                             long deliveredBytes, long callbacks,
                                                                             long slow25, long slow100)
    {
        return usbSnapshot(now, saturationDrops, copyFailures, conversionFailures, listenerFailures,
            deliveredBytes, callbacks, slow25, slow100, 1, 0, 0);
    }

    private static USBTunerController.UsbTransferHealthSnapshot usbSnapshot(long now, long saturationDrops,
                                                                             long copyFailures,
                                                                             long conversionFailures,
                                                                             long listenerFailures,
                                                                             long deliveredBytes, long callbacks,
                                                                             long slow25, long slow100,
                                                                             long streamSequence, long longGaps,
                                                                             long shortTransfers)
    {
        return new USBTunerController.UsbTransferHealthSnapshot(true, streamSequence, 1_024, 1, 0, "unknown",
            8, 8, 0, 0, deliveredBytes > 0 ? 1 : 0, deliveredBytes > 0 ? 1 : 0,
            0, 0, 0, 0, 0, deliveredBytes, deliveredBytes, deliveredBytes, 0, 0,
            shortTransfers, 0, 0, 0, 1_000L, now, now, 0, 0, longGaps, 0, 0,
            callbacks, slow25, slow100, slow25 > 0 ? 25_000_000L : 0,
            16, 0, 0, saturationDrops, saturationDrops * 1_024, copyFailures, conversionFailures,
            listenerFailures, 0, 0);
    }

    private static ReceiverHealthIncidentTracker usbIncidentTracker(ReceiverHealthService service) throws Exception
    {
        Field incidentsField = ReceiverHealthService.class.getDeclaredField("mIncidents");
        incidentsField.setAccessible(true);
        return (ReceiverHealthIncidentTracker)incidentsField.get(service);
    }

    private static Method usbCollector() throws Exception
    {
        Method collectUsb = ReceiverHealthService.class.getDeclaredMethod("collectUsb", long.class,
            String.class, String.class, Tuner.class, USBTunerController.UsbTransferHealthSnapshot.class,
            List.class);
        collectUsb.setAccessible(true);
        return collectUsb;
    }

    private static void collectUsbSample(ReceiverHealthIncidentTracker incidents, Method collectUsb,
                                         ReceiverHealthService service, Tuner tuner,
                                         List<Map<String,Object>> measurements, long now,
                                         USBTunerController.UsbTransferHealthSnapshot snapshot) throws Exception
    {
        incidents.beginSample();
        collectUsb.invoke(service, now, "usb-test", "USB test tuner", tuner, snapshot, measurements);
        incidents.endSample(now);
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
