/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentCaptureResult;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentReportState;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentState;
import io.github.dsheirer.debug.ReceiverIncidentController.ThreadDumpState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiverIncidentControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void emptyLatestReportIsAlwaysValidJson() throws Exception
    {
        AtomicLong now = new AtomicLong(500_000L);

        try(ReceiverIncidentController controller = controller(now, () -> "{}".getBytes(StandardCharsets.UTF_8)))
        {
            assertTrue(OBJECT_MAPPER.readTree(controller.getLatestIncidentJson()).path("incident").isNull());
            assertEquals(IncidentReportState.NONE, controller.getStatus().latestIncidentReportState());
        }
    }

    @Test
    void tunerHardwareIdsAndRecordingPathsAreStableOpaquePseudonyms() throws Exception
    {
        String serial = "RSPdx-R2-SERIAL-123456";
        String recordingPath = "/Users/tester/private/captures/sensitive-recording.wav";
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
            OBJECT_MAPPER.readTree(telemetry(1L, 600_000L, 0L, 0L, 0L, "enabled", 0L));
        com.fasterxml.jackson.databind.node.ArrayNode tuners =
            (com.fasterxml.jackson.databind.node.ArrayNode)root.path("tuners").path("rows");
        ((com.fasterxml.jackson.databind.node.ObjectNode)tuners.get(0)).put("id", serial);
        com.fasterxml.jackson.databind.node.ObjectNode recording = tuners.get(0).deepCopy();
        recording.put("id", recordingPath);
        tuners.add(recording);

        ReceiverIncidentSample first = ReceiverIncidentSample.from(root, "unpublished-test-salt");
        ReceiverIncidentSample second = ReceiverIncidentSample.from(root, "unpublished-test-salt");
        String persisted = OBJECT_MAPPER.writeValueAsString(first.toMap());
        assertFalse(persisted.contains(serial));
        assertFalse(persisted.contains(recordingPath));
        assertTrue(first.tuners().get(0).id().startsWith("tuner-"));
        assertEquals(first.tuners().get(0).id(), second.tuners().get(0).id());
        assertFalse(first.tuners().get(0).id().equals(first.tuners().get(1).id()));
    }

    @Test
    void ringIsFixedAndDropStartsIncidentWithPreTriggerHistory() throws Exception
    {
        AtomicLong now = new AtomicLong(1_000_000L);

        try(ReceiverIncidentController controller = controller(now, () -> "{}".getBytes(StandardCharsets.UTF_8)))
        {
            for(int x = 0; x < ReceiverIncidentRecorder.SAMPLE_LIMIT + 25; x++)
            {
                controller.acceptTelemetry(telemetry(x + 1L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                    "enabled", 0L));
            }

            assertEquals(ReceiverIncidentRecorder.SAMPLE_LIMIT, controller.getStatus().retainedSamples());
            controller.acceptTelemetry(telemetry(2_000L, now.getAndAdd(1_000L), 1L, 7L, 0L,
                "enabled", 0L));
            assertEquals(IncidentState.RECORDING, controller.getStatus().state());
            assertTrue(controller.getStatus().activeReason().contains("raw IQ loss"));
            assertTrue(controller.getStatus().activeStartedAtMs() <= now.get() - 59_000L,
                "the incident must retain approximately one minute before the trigger");
        }
    }

    @Test
    void sustainedHighRawQueueCapturesThreeBoundedDumpsOffCallerThread() throws Exception
    {
        AtomicLong now = new AtomicLong(2_000_000L);
        AtomicInteger captures = new AtomicInteger();
        String caller = Thread.currentThread().getName();

        try(ReceiverIncidentController controller = controller(now, () -> {
            assertFalse(Thread.currentThread().getName().equals(caller));
            int capture = captures.incrementAndGet();
            return ("{\"capture\":" + capture + "}").getBytes(StandardCharsets.UTF_8);
        }))
        {
            controller.acceptTelemetry(telemetry(1L, now.getAndAdd(1_000L), 0L, 0L, 80L,
                "enabled", 0L));
            controller.acceptTelemetry(telemetry(2L, now.getAndAdd(1_000L), 0L, 0L, 80L,
                "enabled", 0L));
            controller.acceptTelemetry(telemetry(3L, now.getAndAdd(1_000L), 0L, 0L, 80L,
                "enabled", 0L));
            await(() -> captures.get() == 1 && controller.getStatus().activeThreadDumpCount() == 1);
            assertTrue(controller.getStatus().lastThreadDumpDurationMs() >= 0L);

            now.addAndGet(4_000L);
            controller.acceptTelemetry(telemetry(4L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 0L));
            await(() -> captures.get() == 2 && controller.getStatus().activeThreadDumpCount() == 2);
            now.addAndGet(4_000L);
            controller.acceptTelemetry(telemetry(5L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 0L));
            await(() -> captures.get() == 3 && controller.getStatus().activeThreadDumpCount() == 3);

            now.addAndGet(10_000L);
            controller.acceptTelemetry(telemetry(6L, now.getAndAdd(1_000L), 0L, 0L, 80L,
                "enabled", 0L));
            controller.acceptTelemetry(telemetry(7L, now.getAndAdd(1_000L), 0L, 0L, 80L,
                "enabled", 0L));
            controller.acceptTelemetry(telemetry(8L, now.getAndAdd(1_000L), 0L, 0L, 80L,
                "enabled", 0L));
            Thread.sleep(50L);
            assertEquals(3, captures.get(), "one incident is limited to three dumps and the cooldown remains active");
            assertEquals(3, controller.getStatus().activeThreadDumpCount());
            assertEquals(ThreadDumpState.CAPTURED, controller.getStatus().threadDumpState());

            now.addAndGet(121_000L);
            controller.acceptTelemetry(telemetry(9L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 0L));
            await(() -> controller.getStatus().savedIncidentCount() == 1);
            JsonNode report = OBJECT_MAPPER.readTree(controller.getLatestIncidentJson());
            assertTrue(controller.getLatestIncidentJson().length <= ReceiverIncidentRecorder.MAXIMUM_REPORT_BYTES);
            assertEquals(3, report.path("thread_dumps").size());
            assertTrue(report.at("/thread_dumps/0/duration_ms").isIntegralNumber());
            assertTrue(report.at("/thread_dumps/0/metrics_before/tuners/0/raw_waiting_ms").isIntegralNumber());
            assertTrue(report.at("/thread_dumps/0/metrics_after/tuners/0/raw_waiting_ms").isIntegralNumber());
            assertEquals(32, report.at("/capture_policy/thread_stack_depth_limit").asInt());
            assertEquals(512 * 1_024, report.at("/capture_policy/thread_dump_byte_limit").asInt());
        }
    }

    @Test
    void manualIncidentPersistsCachedTextAndJsonAndSurvivesAfterTheFact() throws Exception
    {
        AtomicLong now = new AtomicLong(3_000_000L);

        try(ReceiverIncidentController controller = controller(now, () -> "{}".getBytes(StandardCharsets.UTF_8)))
        {
            for(int x = 0; x < 65; x++)
            {
                controller.acceptTelemetry(telemetry(x + 1L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                    "enabled", 0L));
            }

            assertEquals(IncidentCaptureResult.STARTED,
                controller.captureIncident("Tester saw decoding stop", false));
            now.addAndGet(121_000L);
            controller.acceptTelemetry(telemetry(100L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 0L));
            await(() -> controller.getStatus().savedIncidentCount() == 1);

            assertEquals(IncidentState.ARMED, controller.getStatus().state());
            assertTrue(controller.getLatestIncidentText().contains("Tester saw decoding stop"));
            JsonNode latest = OBJECT_MAPPER.readTree(controller.getLatestIncidentJson());
            assertEquals("Tester saw decoding stop", latest.at("/incident/reasons/0").asText());
            assertTrue(latest.path("timeline").size() >= 60);
            assertTrue(Files.isRegularFile(mTemporaryDirectory.resolve(controller.getStatus().latestIncidentFileName())));
            assertTrue(Files.size(mTemporaryDirectory.resolve(controller.getStatus().latestIncidentFileName())) <=
                ReceiverIncidentRecorder.MAXIMUM_REPORT_BYTES);
            assertEquals(0L, countFilesWithSuffix(mTemporaryDirectory, ".tmp"));
            assertEquals(1, OBJECT_MAPPER.readTree(controller.getIncidentIndexJson()).path("incidents").size());
        }
    }

    @Test
    void startupReadsAndTemporaryFilesAreHardBounded() throws Exception
    {
        Path directory = mTemporaryDirectory.resolve("bounded-startup");
        Files.createDirectories(directory);
        Path oversized = directory.resolve("receiver-incident-oversized.json");
        Path malformed = directory.resolve("receiver-incident-malformed.json");
        Path temporary = directory.resolve("receiver-incident-abandoned.tmp");
        Files.write(oversized, new byte[ReceiverIncidentRecorder.MAXIMUM_REPORT_BYTES + 1]);
        Files.writeString(malformed, "{");
        Files.writeString(temporary, "partial");
        AtomicLong now = new AtomicLong(3_500_000L);

        try(ReceiverIncidentController controller = new ReceiverIncidentController(directory, now::get,
            System::nanoTime, () -> "{}".getBytes(StandardCharsets.UTF_8)))
        {
            await(() -> !Files.exists(oversized) && !Files.exists(malformed) && !Files.exists(temporary));
            assertTrue(OBJECT_MAPPER.readTree(controller.getLatestIncidentJson()).path("incident").isNull());
            assertEquals(0, controller.getStatus().savedIncidentCount());
        }
    }

    @Test
    void tunerErrorAndStaleActiveControlAreAutomaticTriggers() throws Exception
    {
        AtomicLong now = new AtomicLong(4_000_000L);

        try(ReceiverIncidentController controller = controller(now, () -> "{}".getBytes(StandardCharsets.UTF_8)))
        {
            controller.acceptTelemetry(telemetry(1L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "error", 0L));
            assertTrue(controller.getStatus().activeReason().contains("tuner entered an error state"));
        }

        Path other = mTemporaryDirectory.resolve("stale");

        try(ReceiverIncidentController controller = new ReceiverIncidentController(other, now::get,
            System::nanoTime, () -> "{}".getBytes(StandardCharsets.UTF_8)))
        {
            controller.acceptTelemetry(telemetry(2L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 6_000L));
            assertTrue(controller.getStatus().activeReason().contains("active control channel"));
        }
    }

    @Test
    void dumpErrorPayloadIsReportedAsFailure() throws Exception
    {
        AtomicLong now = new AtomicLong(5_000_000L);

        try(ReceiverIncidentController controller = controller(now,
            () -> "{\"error\":\"injected dump failure\"}".getBytes(StandardCharsets.UTF_8)))
        {
            assertEquals(IncidentCaptureResult.STARTED,
                controller.captureIncident("Manual dump failure test", true));
            await(() -> controller.getStatus().threadDumpState() == ThreadDumpState.FAILED);
            assertTrue(controller.getStatus().lastError().contains("injected dump failure"));
            assertEquals(IncidentCaptureResult.REJECTED_BUSY,
                controller.captureIncident("Second dump during cooldown", true));
            now.addAndGet(121_000L);
            controller.acceptTelemetry(telemetry(1L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 0L));
            await(() -> controller.getStatus().latestIncidentReportState() == IncidentReportState.SAVED);
            assertEquals(ThreadDumpState.FAILED, controller.getStatus().threadDumpState());
            assertTrue(controller.getStatus().lastError().contains("injected dump failure"),
                "successful report persistence must not clear the dump failure cause");
        }
    }

    @Test
    void closeDuringDumpPreservesEvidenceBeforeQueuedPersistence() throws Exception
    {
        AtomicLong now = new AtomicLong(6_000_000L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReceiverIncidentController controller = controller(now, () -> {
            entered.countDown();
            awaitLatch(release);
            return "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8);
        });

        assertEquals(IncidentCaptureResult.STARTED, controller.captureIncident("Close race", true));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> closing = CompletableFuture.runAsync(controller::close);
        release.countDown();
        closing.get(3, TimeUnit.SECONDS);
        assertEquals(IncidentState.CLOSED, controller.getStatus().state());
        assertEquals(IncidentReportState.SAVED, controller.getStatus().latestIncidentReportState());
        assertEquals(1, OBJECT_MAPPER.readTree(controller.getLatestIncidentJson()).path("thread_dumps").size());
    }

    @Test
    void boundedWorkerRejectsPersistenceWithoutCallerRuns() throws Exception
    {
        AtomicLong now = new AtomicLong(7_000_000L);
        CountDownLatch dumpEntered = new CountDownLatch(1);
        CountDownLatch releaseDump = new CountDownLatch(1);

        try(ReceiverIncidentController controller = controller(now, () -> {
            dumpEntered.countDown();
            awaitLatch(releaseDump);
            return "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8);
        }))
        {
            controller.acceptTelemetry(telemetry(1L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 0L));
            assertEquals(IncidentCaptureResult.STARTED, controller.captureIncident("blocking dump", true));
            assertTrue(dumpEntered.await(2, TimeUnit.SECONDS));

            for(int incident = 0; incident < 3; incident++)
            {
                now.addAndGet(121_000L);
                controller.acceptTelemetry(telemetry(10L + incident, now.getAndAdd(1_000L), 0L, 0L, 0L,
                    "enabled", 0L));

                if(incident < 2)
                {
                    assertEquals(IncidentCaptureResult.STARTED,
                        controller.captureIncident("queued report " + incident, false));
                }
            }

            assertTrue(controller.getStatus().lastError().contains("bounded diagnostics worker"));
            assertEquals(0L, countReports(mTemporaryDirectory), "a caller-runs policy would write while dump is blocked");
            releaseDump.countDown();
        }
        finally
        {
            releaseDump.countDown();
        }
    }

    @Test
    void perTunerStallIsNotMaskedAndCleanupDiscardDoesNotTrigger() throws Exception
    {
        AtomicLong now = new AtomicLong(8_000_000L);

        try(ReceiverIncidentController controller = controller(now, () -> "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8)))
        {
            for(int x = 1; x <= 4; x++)
            {
                controller.acceptTelemetry(twoTunerTelemetry(x, now.getAndAdd(1_000L), 10L,
                    x * 10L, 0L));
            }

            assertTrue(controller.getStatus().activeReason().contains("made no processing progress"));
            assertFalse(controller.getStatus().activeReason().contains("tuner-1"));
        }

        Path cleanup = mTemporaryDirectory.resolve("cleanup-only");

        try(ReceiverIncidentController controller = new ReceiverIncidentController(cleanup, now::get,
            System::nanoTime, () -> "{}".getBytes(StandardCharsets.UTF_8)))
        {
            controller.acceptTelemetry(twoTunerTelemetry(1, now.getAndAdd(1_000L), 10L, 10L, 0L));
            controller.acceptTelemetry(twoTunerTelemetry(2, now.getAndAdd(1_000L), 20L, 20L, 20L));
            assertEquals(IncidentState.ARMED, controller.getStatus().state());
        }
    }

    @Test
    void neverDecodedControlUsesStartupGrace() throws Exception
    {
        AtomicLong now = new AtomicLong(9_000_000L);

        try(ReceiverIncidentController controller = controller(now, () -> "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8)))
        {
            for(int x = 0; x < 10; x++)
            {
                controller.acceptTelemetry(neverDecodedTelemetry(x + 1L, now.getAndAdd(1_000L)));
            }

            assertEquals(IncidentState.ARMED, controller.getStatus().state());
            controller.acceptTelemetry(neverDecodedTelemetry(11L, now.getAndAdd(1_000L)));
            assertEquals(IncidentState.RECORDING, controller.getStatus().state());
            assertTrue(controller.getStatus().activeReason().contains("startup grace"));
        }
    }

    @Test
    void persistentAutomaticConditionRequiresSustainedRecoveryBeforeRearming() throws Exception
    {
        AtomicLong now = new AtomicLong(10_000_000L);

        try(ReceiverIncidentController controller = controller(now,
            () -> "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8)))
        {
            long sequence = 1L;

            for(int x = 0; x < 11; x++)
            {
                controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            }

            assertEquals(IncidentState.RECORDING, controller.getStatus().state());
            now.addAndGet(121_000L);
            controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            await(() -> controller.getStatus().savedIncidentCount() == 1);

            for(int x = 0; x < 130; x++)
            {
                controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            }

            assertEquals(IncidentState.ARMED, controller.getStatus().state());
            assertEquals(1, controller.getStatus().savedIncidentCount(),
                "one persistent condition must not continuously replace incident history");

            //A one-sample inactive gap occurs during normal multi-frequency rotation and must not rearm the trigger.
            controller.acceptTelemetry(telemetry(sequence++, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 0L));

            for(int x = 0; x < 11; x++)
            {
                controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            }

            assertEquals(IncidentState.ARMED, controller.getStatus().state());
            assertEquals(1, controller.getStatus().savedIncidentCount());

            for(int x = 0; x < 21; x++)
            {
                controller.acceptTelemetry(telemetry(sequence++, now.getAndAdd(1_000L), 0L, 0L, 0L,
                    "enabled", 0L));
            }

            for(int x = 0; x < 11; x++)
            {
                controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            }

            assertEquals(IncidentState.ARMED, controller.getStatus().state(),
                "the returning condition's startup grace must not count toward recovery");

            controller.acceptTelemetry(telemetry(sequence++, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 0L));

            for(int x = 0; x < 31; x++)
            {
                controller.acceptTelemetry(sampleFailedTelemetry(sequence++, now.getAndAdd(1_000L)));
            }

            for(int x = 0; x < 11; x++)
            {
                controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            }

            assertEquals(IncidentState.ARMED, controller.getStatus().state(),
                "missing telemetry is not evidence that the condition recovered");

            for(int x = 0; x < 31; x++)
            {
                controller.acceptTelemetry(telemetry(sequence++, now.getAndAdd(1_000L), 0L, 0L, 0L,
                    "enabled", 0L));
            }

            for(int x = 0; x < 11; x++)
            {
                controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            }

            assertEquals(IncidentState.RECORDING, controller.getStatus().state(),
                "the same condition must rearm after thirty seconds of observed recovery");
        }
    }

    @Test
    void repeatedCounterLossEventsRemainIndependentTriggers() throws Exception
    {
        AtomicLong now = new AtomicLong(18_000_000L);

        try(ReceiverIncidentController controller = controller(now,
            () -> "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8)))
        {
            controller.acceptTelemetry(telemetry(1L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 0L));
            controller.acceptTelemetry(telemetry(2L, now.getAndAdd(1_000L), 1L, 7L, 0L,
                "enabled", 0L));
            long firstCompletion = controller.getStatus().activeExpectedCompletionAtMs();
            now.addAndGet(10_000L);
            controller.acceptTelemetry(telemetry(3L, now.getAndAdd(1_000L), 2L, 14L, 0L,
                "enabled", 0L));
            assertTrue(controller.getStatus().activeExpectedCompletionAtMs() > firstCompletion,
                "a second real loss event must extend the evidence window even inside the recovery interval");
        }
    }

    @Test
    void persistentStaleControlTriggersOnceAndRearmsAfterRecovery() throws Exception
    {
        AtomicLong now = new AtomicLong(20_000_000L);

        try(ReceiverIncidentController controller = controller(now,
            () -> "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8)))
        {
            long sequence = 1L;
            controller.acceptTelemetry(telemetry(sequence++, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 6_000L));
            assertEquals(IncidentState.RECORDING, controller.getStatus().state());
            controller.acceptTelemetry(telemetry(sequence++, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 6_000L));
            now.addAndGet(121_000L);
            controller.acceptTelemetry(telemetry(sequence++, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 6_000L));
            await(() -> controller.getStatus().savedIncidentCount() == 1);

            for(int x = 0; x < 130; x++)
            {
                controller.acceptTelemetry(telemetry(sequence++, now.getAndAdd(1_000L), 0L, 0L, 0L,
                    "enabled", 6_000L));
            }

            assertEquals(IncidentState.ARMED, controller.getStatus().state());
            assertEquals(1, controller.getStatus().savedIncidentCount());

            for(int x = 0; x < 31; x++)
            {
                controller.acceptTelemetry(telemetry(sequence++, now.getAndAdd(1_000L), 0L, 0L, 0L,
                    "enabled", 100L));
            }

            controller.acceptTelemetry(telemetry(sequence, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 6_000L));
            assertEquals(IncidentState.RECORDING, controller.getStatus().state());
        }
    }

    @Test
    void bootChangeResetsAutomaticQualificationState() throws Exception
    {
        AtomicLong now = new AtomicLong(22_000_000L);
        AtomicInteger dumps = new AtomicInteger();

        try(ReceiverIncidentController controller = controller(now, () -> {
            dumps.incrementAndGet();
            return "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8);
        }))
        {
            controller.acceptTelemetry(telemetryForBoot(1L, now.getAndAdd(1_000L), 80L, "error", "boot-a"));
            controller.acceptTelemetry(telemetryForBoot(2L, now.getAndAdd(1_000L), 80L, "error", "boot-a"));
            assertEquals(0, dumps.get());

            controller.acceptTelemetry(telemetryForBoot(3L, now.getAndAdd(1_000L), 80L, "error", "boot-b"));
            controller.acceptTelemetry(telemetryForBoot(4L, now.getAndAdd(1_000L), 80L, "error", "boot-b"));
            assertEquals(0, dumps.get(), "the new boot must qualify sustained conditions from zero");
            controller.acceptTelemetry(telemetryForBoot(5L, now.getAndAdd(1_000L), 80L, "error", "boot-b"));
            await(() -> dumps.get() == 1);
        }
    }

    @Test
    void differentAutomaticConditionAndManualCaptureBypassPersistentLatch() throws Exception
    {
        AtomicLong now = new AtomicLong(12_000_000L);

        try(ReceiverIncidentController controller = controller(now,
            () -> "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8)))
        {
            long sequence = 1L;

            for(int x = 0; x < 11; x++)
            {
                controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            }

            now.addAndGet(121_000L);
            controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            await(() -> controller.getStatus().savedIncidentCount() == 1);

            controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L), 1L, 7L));
            assertEquals(IncidentState.RECORDING, controller.getStatus().state());
            assertTrue(controller.getStatus().activeReason().contains("raw IQ loss"));
            assertFalse(controller.getStatus().activeReason().contains("startup grace"),
                "the already-covered condition must not be re-added to a new incident");
            assertEquals(IncidentCaptureResult.COALESCED,
                controller.captureIncident("Tester also observed a symptom", false));
            assertTrue(controller.getStatus().activeReason().contains("Tester also observed a symptom"));
        }

        Path manual = mTemporaryDirectory.resolve("manual-bypass");
        now.set(14_000_000L);

        try(ReceiverIncidentController controller = new ReceiverIncidentController(manual, now::get,
            System::nanoTime, () -> "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8)))
        {
            long sequence = 1L;

            for(int x = 0; x < 11; x++)
            {
                controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            }

            now.addAndGet(121_000L);
            controller.acceptTelemetry(neverDecodedTelemetry(sequence++, now.getAndAdd(1_000L)));
            await(() -> controller.getStatus().savedIncidentCount() == 1);
            assertEquals(IncidentCaptureResult.STARTED,
                controller.captureIncident("Manual capture while automatic condition remains latched", false));
        }
    }

    @Test
    void automaticLatchStillAdmitsSeverityEscalation() throws Exception
    {
        AtomicLong now = new AtomicLong(16_000_000L);
        AtomicInteger dumps = new AtomicInteger();

        try(ReceiverIncidentController controller = controller(now, () -> {
            dumps.incrementAndGet();
            return "{\"threads\":[]}".getBytes(StandardCharsets.UTF_8);
        }))
        {
            controller.acceptTelemetry(telemetry(1L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 6_000L));
            assertEquals(0, dumps.get());
            controller.acceptTelemetry(telemetry(2L, now.getAndAdd(1_000L), 0L, 0L, 0L,
                "enabled", 6_000L));
            await(() -> dumps.get() == 1);
        }
    }

    private ReceiverIncidentController controller(AtomicLong now, java.util.function.Supplier<byte[]> dumps)
    {
        return new ReceiverIncidentController(mTemporaryDirectory, now::get, System::nanoTime, dumps);
    }

    private static byte[] twoTunerTelemetry(long sequence, long observedAt, long stalledProcessed,
                                            long healthyProcessed, long discarded) throws Exception
    {
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
            OBJECT_MAPPER.readTree(telemetry(sequence, observedAt, 0L, 0L, 0L, "enabled", 0L));
        com.fasterxml.jackson.databind.node.ArrayNode rows =
            (com.fasterxml.jackson.databind.node.ArrayNode)root.path("tuners").path("rows");
        com.fasterxml.jackson.databind.node.ObjectNode stalled =
            (com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0);
        ((com.fasterxml.jackson.databind.node.ObjectNode)stalled.path("raw_input"))
            .put("waiting_ms", 10L).put("processed_buffers", stalledProcessed);
        ((com.fasterxml.jackson.databind.node.ObjectNode)stalled.path("ifft")).put("discarded", discarded);
        ((com.fasterxml.jackson.databind.node.ObjectNode)stalled.path("channel_outputs").path("rows").get(0)
            .path("output")).put("discarded", discarded);
        com.fasterxml.jackson.databind.node.ObjectNode healthy = stalled.deepCopy();
        healthy.put("id", "tuner-2");
        ((com.fasterxml.jackson.databind.node.ObjectNode)healthy.path("raw_input"))
            .put("waiting_ms", 0L).put("processed_buffers", healthyProcessed);
        rows.add(healthy);
        return OBJECT_MAPPER.writeValueAsBytes(root);
    }

    private static byte[] neverDecodedTelemetry(long sequence, long observedAt) throws Exception
    {
        return neverDecodedTelemetry(sequence, observedAt, 0L, 0L);
    }

    private static byte[] neverDecodedTelemetry(long sequence, long observedAt, long rawDropped,
                                                long rawDroppedMs) throws Exception
    {
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
            OBJECT_MAPPER.readTree(telemetry(sequence, observedAt, rawDropped, rawDroppedMs, 0L, "enabled", 0L));
        com.fasterxml.jackson.databind.node.ArrayNode controls = OBJECT_MAPPER.createArrayNode();
        controls.add(OBJECT_MAPPER.createObjectNode().put("active", true).put("decode_health_percent", 0.0d));
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("control_channels")).set("rows", controls);
        return OBJECT_MAPPER.writeValueAsBytes(root);
    }

    private static byte[] sampleFailedTelemetry(long sequence, long observedAt) throws Exception
    {
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
            OBJECT_MAPPER.readTree(telemetry(sequence, observedAt, 0L, 0L, 0L, "enabled", 0L));
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("telemetry")).put("state", "sample_failed");
        return OBJECT_MAPPER.writeValueAsBytes(root);
    }

    private static byte[] telemetryForBoot(long sequence, long observedAt, long rawWaitingMs, String tunerStatus,
                                           String bootId) throws Exception
    {
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
            OBJECT_MAPPER.readTree(telemetry(sequence, observedAt, 0L, 0L, rawWaitingMs, tunerStatus, 0L));
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("telemetry")).put("boot_id", bootId);
        return OBJECT_MAPPER.writeValueAsBytes(root);
    }

    private static long countReports(Path directory) throws Exception
    {
        if(!Files.isDirectory(directory))
        {
            return 0L;
        }

        try(var stream = Files.list(directory))
        {
            return stream.filter(path -> path.getFileName().toString().endsWith(".json")).count();
        }
    }

    private static long countFilesWithSuffix(Path directory, String suffix) throws Exception
    {
        try(var stream = Files.list(directory))
        {
            return stream.filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private static void awaitLatch(CountDownLatch latch)
    {
        try
        {
            latch.await();
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] telemetry(long sequence, long observedAt, long rawDropped, long rawDroppedMs,
                                    long rawWaitingMs, String tunerStatus, long validDecodeAgeMs) throws Exception
    {
        Map<String,Object> raw = Map.of("running", true, "limit_ms", 100L, "waiting_ms", rawWaitingMs,
            "in_flight_ms", 7L, "received_buffers", sequence * 10L, "processed_buffers", sequence * 10L,
            "dropped_buffers", rawDropped, "dropped_ms", rawDroppedMs, "last_ingress_age_ms", 1L,
            "last_completion_age_ms", 2L);
        Map<String,Object> ifft = Map.of("waiting", 0, "limit", 8, "dropped", 0L, "discarded", 0L);
        Map<String,Object> outputs = Map.of("total_count", 1, "rows", List.of(Map.of("output",
            Map.of("outstanding", 0, "dropped", 0L, "discarded", 0L))), "hidden_summary",
            Map.of("maximum_outstanding", 0, "dropped", 0L, "discarded", 0L));
        Map<String,Object> tuner = Map.of("id", "tuner-1", "status", tunerStatus,
            "center_frequency_hz", 851_000_000L, "sample_rate_hz", 10_000_000L, "raw_input", raw,
            "ifft", ifft, "channel_outputs", outputs);
        Map<String,Object> process = Map.of("process_cpu_load", 0.2d, "heap_used_bytes", 100L,
            "heap_committed_bytes", 200L, "heap_max_bytes", 1_000L, "thread_count", 20,
            "deadlocked_thread_count", 0, "garbage_collectors", List.of(Map.of(
                "collection_time_since_previous_sample_ms", 0L)));
        List<Map<String,Object>> controls = validDecodeAgeMs > 0L ? List.of(Map.of("active", true,
            "decode_health_percent", 0.0d, "last_valid_decode_age_ms", validDecodeAgeMs)) : List.of();
        Map<String,Object> root = Map.of("sequence", sequence, "observed_at_ms", observedAt,
            "telemetry", Map.of("boot_id", "boot-test", "state", "ok"), "process", process,
            "tuners", Map.of("rows", List.of(tuner)), "control_channels", Map.of("rows", controls));
        return OBJECT_MAPPER.writeValueAsBytes(root);
    }

    private static void await(Check check) throws Exception
    {
        long deadline = System.nanoTime() + 2_000_000_000L;

        while(!check.ready() && System.nanoTime() < deadline)
        {
            Thread.sleep(10L);
        }

        assertTrue(check.ready(), "timed out waiting for asynchronous diagnostic work");
    }

    @FunctionalInterface
    private interface Check
    {
        boolean ready();
    }
}
