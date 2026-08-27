/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.LogicalCallId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogicalCallDiagnosticServiceTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void writesOnlyWhitelistedSanitizedFieldsAndLocalOutputConfirmation() throws Exception
    {
        Path diagnosticDirectory = mTemporaryDirectory.resolve("diagnostics");
        LogicalCallDiagnosticConfiguration configuration = configuration(diagnosticDirectory, 8, 8, 32_768, 2,
            16_384, Duration.ofSeconds(1));
        LogicalCallDiagnosticService service = new LogicalCallDiagnosticService(configuration);
        LogicalCallDiagnosticDecision decision = decision(1, "/Users/private/internal/channel.json",
            "token=DO-NOT-PERSIST", List.of("token=STREAM-PROVIDER-SECRET"));

        assertTrue(service.offer(decision));
        assertTrue(service.offerOutput(new LogicalCallDiagnosticOutputEvent(1, 2_500,
            LogicalCallDiagnosticOutputType.STREAM_SUBMITTED)));
        service.close();

        String jsonl = Files.readString(diagnosticDirectory.resolve(
            LogicalCallDiagnosticFileWriter.ACTIVE_FILE_NAME));
        List<JsonNode> records = parseLines(jsonl);
        assertEquals("session_header", records.getFirst().path("record_type").asText());
        assertEquals(LogicalCallDiagnosticJsonEncoder.FORMAT, records.getFirst().path("format").asText());
        assertFalse(records.getFirst().path("contains_audio").asBoolean(true));
        assertFalse(records.getFirst().path("contains_voice_fingerprints").asBoolean(true));
        assertFalse(records.getFirst().path("contains_encryption_message_indicators").asBoolean(true));
        assertFalse(records.getFirst().path("contains_credentials").asBoolean(true));

        JsonNode decisionRecord = records.stream()
            .filter(record -> "logical_call_decision".equals(record.path("record_type").asText()))
            .findFirst().orElseThrow();
        assertEquals(1, decisionRecord.path("call_identity").path("logical_call_sequence").asLong());
        assertEquals("[redacted_path]", decisionRecord.path("call_identity").path("destination_alias").asText());
        assertEquals("[redacted_secret]", decisionRecord.path("call_identity").path("source_alias").asText());
        assertEquals("[redacted_secret]",
            decisionRecord.path("output_policy").path("stream_route_names").get(0).asText());
        assertEquals("CLEAR", decisionRecord.path("call_identity").path("encryption_state").asText());
        assertFalse(decisionRecord.path("call_identity").has("encrypted"));
        assertTrue(decisionRecord.has("receiver_copies"));
        assertEquals("MARCS Site 1",
            decisionRecord.path("receiver_copies").get(0).path("channel_name").asText());
        assertEquals(98.0d,
            decisionRecord.path("receiver_copies").get(0).path("quality_percent").asDouble());
        assertTrue(decisionRecord.path("receiver_copies").get(0).path("overlap_with_selected").isNull(),
            "An independent call has no proven comparison target");
        assertEquals(1, decisionRecord.path("comparison_summary").path("comparison_count").asLong());
        assertEquals(1,
            decisionRecord.path("comparison_summary").path("separated_count").asLong());
        assertEquals(1, decisionRecord.path("comparison_summary").path("rejection_reason_counts")
            .path("DESTINATION_MISMATCH").asLong());
        assertFalse(decisionRecord.has("pair_decisions"));
        assertFalse(decisionRecord.has("receiver_legs"));
        assertFalse(decisionRecord.has("truncated_pair_decision_count"));

        JsonNode outputRecord = records.stream()
            .filter(record -> "output_confirmation".equals(record.path("record_type").asText()))
            .findFirst().orElseThrow();
        assertEquals("local_submission_only", outputRecord.path("confirmation_scope").asText());
        assertEquals("STREAM_SUBMITTED", outputRecord.path("output_type").asText());

        assertFalse(jsonl.contains("9223372036854775000"), "Process-local coordinator ID must not be persisted");
        assertFalse(jsonl.contains("DO-NOT-PERSIST"));
        assertFalse(jsonl.contains("STREAM-PROVIDER-SECRET"));
        assertFalse(jsonl.contains("\"voice_fingerprint\":"));
        assertFalse(jsonl.contains("\"encryption_message_indicator\":"));
        assertFalse(jsonl.contains(mTemporaryDirectory.toString()), "No absolute diagnostic path belongs in JSONL");
        assertEquals(1, service.snapshot().status().outputConfirmationsObserved());
        assertEquals(0, service.snapshot().status().recordedConfirmationsObserved());
        assertEquals(1, service.snapshot().status().streamSubmittedConfirmationsObserved());
        assertEquals(1, service.snapshot().recentDecisions().size(),
            "Output confirmations use their explicit counter rather than consuming the decision ring");
    }

    @Test
    void writesWinnerRelativeOverlapForEachConfirmedReceiverCopy() throws Exception
    {
        LogicalCallDiagnosticLeg selected = leg(0, 1_000L, 6_220L, true);
        LogicalCallDiagnosticLeg lateCopy = leg(1, 5_278L, 6_179L, false);
        LogicalCallDiagnosticDecision decision = new LogicalCallDiagnosticDecision(1L, 7_000L,
            new LogicalCallId(1L, 1L), LogicalCallDecisionOutcome.MERGED,
            identity(1L, "Dispatch", "Radio 1001"),
            new LogicalCallDiagnosticOutputPolicy(false, List.of(), 0, false), winner(),
            List.of(selected, lateCopy), new LogicalCallDiagnosticEvidence(1L, 0L, 0L,
                Map.of(LogicalCallMergeProof.SHARED_VOICE_CONTENT, 1L), Map.of()), List.of());
        JsonNode encoded = OBJECT_MAPPER.readTree(new LogicalCallDiagnosticJsonEncoder().encodeDecision(decision));
        JsonNode selectedOverlap = encoded.path("receiver_copies").get(0).path("overlap_with_selected");
        JsonNode lateOverlap = encoded.path("receiver_copies").get(1).path("overlap_with_selected");

        assertEquals(5_220L, selectedOverlap.path("overlap_ms").asLong());
        assertEquals(100.0d, selectedOverlap.path("shorter_copy_overlap_percent").asDouble(), 0.0001d);
        assertEquals(100.0d, selectedOverlap.path("selected_copy_coverage_percent").asDouble(), 0.0001d);
        assertEquals(901L, lateOverlap.path("overlap_ms").asLong());
        assertEquals(100.0d, lateOverlap.path("shorter_copy_overlap_percent").asDouble(), 0.0001d);
        assertEquals(901.0d * 100.0d / 5_220.0d,
            lateOverlap.path("selected_copy_coverage_percent").asDouble(), 0.0001d);
        assertEquals(4_278L, lateOverlap.path("start_offset_from_selected_ms").asLong());
        assertEquals(-41L, lateOverlap.path("end_offset_from_selected_ms").asLong());
    }

    @Test
    void slowSerializerNeverRunsOnOfferingThreadAndQueueDropsWithoutWaiting() throws Exception
    {
        BlockingEncoder encoder = new BlockingEncoder();
        LogicalCallDiagnosticConfiguration configuration = configuration(mTemporaryDirectory.resolve("slow"), 4, 2,
            32_768, 2, 16_384, Duration.ofSeconds(1));
        LogicalCallDiagnosticService service = new LogicalCallDiagnosticService(configuration, encoder);

        try
        {
            assertTrue(service.offer(decision(1)));
            assertTrue(encoder.awaitEntered());
            long started = System.nanoTime();
            for(int sequence = 2; sequence <= 20; sequence++)
            {
                assertTrue(service.offer(decision(sequence)),
                    "The in-memory decision must remain accepted when only the file queue is saturated");
            }

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 500, "A blocked serializer must never delay the offering observer thread");
            assertNotEquals(Thread.currentThread(), encoder.encodingThread());
            assertTrue(encoder.encodingThread().getName().contains("logical-call diagnostic writer"));
            assertTrue(service.status().recordsDroppedAtQueue() > 0);
            assertEquals(List.of(17L, 18L, 19L, 20L), service.snapshot().recentDecisions().stream()
                .map(LogicalCallDiagnosticDecision::decisionSequence).toList());
            assertEquals(16, service.snapshot().recentDecisionsEvicted());
        }
        finally
        {
            encoder.release();
            service.close();
        }
    }

    @Test
    void closeReturnsAtDeadlineWhenSerializerCannotDrain() throws Exception
    {
        BlockingEncoder encoder = new BlockingEncoder();
        LogicalCallDiagnosticConfiguration configuration = configuration(mTemporaryDirectory.resolve("close"), 4, 4,
            32_768, 2, 16_384, Duration.ofMillis(100));
        LogicalCallDiagnosticService service = new LogicalCallDiagnosticService(configuration, encoder);

        try
        {
            assertTrue(service.offer(decision(1)));
            assertTrue(encoder.awaitEntered());
            assertTimeoutPreemptively(Duration.ofMillis(500), service::close,
                "Lifecycle close must return even when optional diagnostic serialization is stuck");
            assertFalse(service.status().accepting());
        }
        finally
        {
            encoder.release();
        }

        assertTrue(waitUntil(() -> service.status().writerTerminated(), Duration.ofSeconds(2)));
        assertTrue(service.status().fileRecordsDropped() >= 1);
        assertEquals(LogicalCallDiagnosticFileState.CLOSED, service.status().fileState());
    }

    @Test
    void rotatesOnlyOwnedFilesAndKeepsEverySegmentBoundedWithHeader() throws Exception
    {
        Path diagnosticDirectory = mTemporaryDirectory.resolve("rotation");
        Files.createDirectories(diagnosticDirectory);
        Path unrelated = diagnosticDirectory.resolve("keep-me.txt");
        Files.writeString(unrelated, "unrelated");
        LogicalCallDiagnosticConfiguration configuration = configuration(diagnosticDirectory, 64, 64, 16_384, 2,
            8_192, Duration.ofSeconds(2));
        LogicalCallDiagnosticService service = new LogicalCallDiagnosticService(configuration);
        int accepted = 0;

        for(int sequence = 1; sequence <= 64; sequence++)
        {
            if(service.offer(decision(sequence)))
            {
                accepted++;
            }
        }

        service.close();
        assertTrue(accepted > 16, "Test must write enough decisions to cross a segment boundary");
        assertTrue(Files.isRegularFile(unrelated));
        List<Path> ownedFiles;

        try(Stream<Path> paths = Files.list(diagnosticDirectory))
        {
            ownedFiles = paths.filter(path -> path.getFileName().toString().startsWith("logical-call-diagnostics"))
                .sorted(Comparator.comparing(Path::toString)).toList();
        }

        assertEquals(2, ownedFiles.size());

        for(Path ownedFile: ownedFiles)
        {
            assertTrue(Files.size(ownedFile) <= configuration.maximumFileBytes());
            List<JsonNode> lines = parseLines(Files.readString(ownedFile));
            assertEquals("session_header", lines.getFirst().path("record_type").asText());
            assertEquals(LogicalCallDiagnosticJsonEncoder.FORMAT, lines.getFirst().path("format").asText());
        }

        assertEquals(2, service.status().retainedFileCount());
        assertTrue(service.status().activeFileBytes() <= configuration.maximumFileBytes());
        assertEquals(0, service.status().oversizedRecordsDropped());
    }

    @Test
    void approvedRecordLimitHoldsAndRotatesMaximumRealisticDecisionEvidence() throws Exception
    {
        List<LogicalCallDiagnosticLeg> legs = new ArrayList<>();

        for(int index = 0; index < 32; index++)
        {
            legs.add(leg(index));
        }

        LogicalCallDiagnosticEvidence evidence = new LogicalCallDiagnosticEvidence(9_000_000L, 8_000_000L,
            7_000_000L,
            Map.of(LogicalCallMergeProof.SHARED_VOICE_CONTENT, 9_000_000L,
                LogicalCallMergeProof.MATCHING_SOURCE_IDENTITY_FALLBACK, 7_000_000L,
                LogicalCallMergeProof.MATCHING_ENCRYPTION_MESSAGE_INDICATOR, 6_000_000L),
            Map.of(LogicalCallSeparationReason.DESTINATION_MISMATCH, 8_000_000L,
                LogicalCallSeparationReason.SOURCE_IDENTITY_MISMATCH, 7_000_000L,
                LogicalCallSeparationReason.INSUFFICIENT_DUPLICATE_PROOF, 6_000_000L));

        List<String> streamRoutes = new ArrayList<>();

        for(int index = 0; index < 64; index++)
        {
            streamRoutes.add(index + "-" + "\uD83D\uDE92".repeat(254));
        }

        LogicalCallDiagnosticOutputPolicy outputPolicy = new LogicalCallDiagnosticOutputPolicy(true,
            streamRoutes, streamRoutes.size(), true);
        assertEquals(LogicalCallDiagnosticOutputPolicy.MAXIMUM_RETAINED_STREAM_ROUTE_NAMES,
            outputPolicy.streamRoutingKeys().size());
        assertEquals(streamRoutes.size(), outputPolicy.streamRoutingKeyCount());

        LogicalCallDiagnosticDecision maximumDecision = new LogicalCallDiagnosticDecision(1, 3_000,
            new LogicalCallId(9_223_372_036_854_775_000L, 1), LogicalCallDecisionOutcome.MERGED,
            identity(1, "Dispatch", "Radio 1001"),
            outputPolicy,
            winner(), legs, evidence, List.of());
        byte[] encoded = new LogicalCallDiagnosticJsonEncoder().encodeDecision(maximumDecision);
        JsonNode encodedDecision = OBJECT_MAPPER.readTree(encoded);

        assertTrue(encoded.length <= LogicalCallDiagnosticConfiguration.DEFAULT_MAXIMUM_RECORD_BYTES,
            () -> "32-copy decision encoded to " + encoded.length + " bytes, above the approved " +
                LogicalCallDiagnosticConfiguration.DEFAULT_MAXIMUM_RECORD_BYTES + "-byte limit");
        assertEquals(streamRoutes.size(),
            encodedDecision.path("output_policy").path("stream_route_count").asInt());
        assertEquals(LogicalCallDiagnosticOutputPolicy.MAXIMUM_RETAINED_STREAM_ROUTE_NAMES,
            encodedDecision.path("output_policy").path("stream_route_names").size());
        assertEquals(24_000_000L,
            encodedDecision.path("comparison_summary").path("comparison_count").asLong());
        assertFalse(encodedDecision.has("pair_decisions"));
        assertFalse(encodedDecision.path("comparison_summary").has("represented_comparison_count"));
        assertFalse(encodedDecision.path("comparison_summary").has("omitted_comparison_count"));

        long maximumFileBytes = 128L * 1024L;
        int recordsNeededToRotate = (int)(maximumFileBytes / ((long)encoded.length + 1L)) + 2;
        Path directory = mTemporaryDirectory.resolve("maximum-evidence-rotation");
        LogicalCallDiagnosticService service = new LogicalCallDiagnosticService(configuration(directory, 256, 256,
            maximumFileBytes, 2, LogicalCallDiagnosticConfiguration.DEFAULT_MAXIMUM_RECORD_BYTES,
            Duration.ofSeconds(2)));

        for(int index = 0; index < recordsNeededToRotate; index++)
        {
            assertTrue(service.offer(maximumDecision));
        }

        service.close();
        assertEquals(recordsNeededToRotate, service.status().fileRecordsWritten());
        assertEquals(0, service.status().oversizedRecordsDropped());

        try(Stream<Path> paths = Files.list(directory))
        {
            List<Path> segments = paths.filter(Files::isRegularFile).toList();
            assertEquals(2, segments.size());

            for(Path segment: segments)
            {
                assertTrue(Files.size(segment) <= maximumFileBytes);
                assertEquals("session_header",
                    parseLines(Files.readString(segment)).getFirst().path("record_type").asText());
            }
        }
    }

    @Test
    void comparisonVolumeChangesOnlyCompactCounterDigits() throws Exception
    {
        LogicalCallDiagnosticEvidence small = new LogicalCallDiagnosticEvidence(1, 1, 1,
            Map.of(LogicalCallMergeProof.SHARED_VOICE_CONTENT, 1L),
            Map.of(LogicalCallSeparationReason.DESTINATION_MISMATCH, 1L));
        LogicalCallDiagnosticEvidence large = new LogicalCallDiagnosticEvidence(1_000_000, 1_000_000, 1_000_000,
            Map.of(LogicalCallMergeProof.SHARED_VOICE_CONTENT, 1_000_000L),
            Map.of(LogicalCallSeparationReason.DESTINATION_MISMATCH, 1_000_000L));
        LogicalCallDiagnosticJsonEncoder encoder = new LogicalCallDiagnosticJsonEncoder();
        byte[] smallRecord = encoder.encodeDecision(decision(1, small));
        byte[] largeRecord = encoder.encodeDecision(decision(2, large));

        assertTrue(largeRecord.length - smallRecord.length < 128,
            "Comparison volume must change only the digits in fixed-size counters");
        JsonNode largeJson = OBJECT_MAPPER.readTree(largeRecord);
        assertEquals(3_000_000L, largeJson.path("comparison_summary").path("comparison_count").asLong());
        assertFalse(largeJson.has("pair_decisions"));
        assertFalse(new String(largeRecord, StandardCharsets.UTF_8).contains("first_leg_id"));
        assertFalse(new String(largeRecord, StandardCharsets.UTF_8).contains("second_leg_id"));
    }

    @Test
    void configuredApplicationLogDirectoryGetsOneOwnedChild()
    {
        Path configuredApplicationLogs = mTemporaryDirectory.resolve("configured-application-logs");

        try(LogicalCallDiagnosticService service = new LogicalCallDiagnosticService(configuredApplicationLogs))
        {
            assertEquals(configuredApplicationLogs.resolve(LogicalCallDiagnosticPath.DIRECTORY_NAME).normalize(),
                service.diagnosticDirectory());
        }
    }

    @Test
    void productionFileRingRemainsFixedAtEightMiB()
    {
        assertEquals(4, LogicalCallDiagnosticConfiguration.DEFAULT_MAXIMUM_FILES);
        assertEquals(2L * 1024L * 1024L,
            LogicalCallDiagnosticConfiguration.DEFAULT_MAXIMUM_FILE_BYTES);
        assertEquals(8L * 1024L * 1024L,
            LogicalCallDiagnosticConfiguration.DEFAULT_MAXIMUM_FILES *
                LogicalCallDiagnosticConfiguration.DEFAULT_MAXIMUM_FILE_BYTES);
    }

    private static LogicalCallDiagnosticConfiguration configuration(Path directory, int recentCapacity,
                                                                    int queueCapacity, long maximumFileBytes,
                                                                    int maximumFiles, int maximumRecordBytes,
                                                                    Duration closeTimeout)
    {
        return new LogicalCallDiagnosticConfiguration(directory, recentCapacity, queueCapacity, maximumFileBytes,
            maximumFiles, maximumRecordBytes, closeTimeout);
    }

    private static LogicalCallDiagnosticDecision decision(long sequence)
    {
        return decision(sequence, "Dispatch", "Radio 1001", List.of("Primary Route"));
    }

    private static LogicalCallDiagnosticDecision decision(long sequence, String destinationAlias, String sourceAlias,
                                                           List<String> streamRoutes)
    {
        return new LogicalCallDiagnosticDecision(sequence, 2_000 + sequence,
            new LogicalCallId(9_223_372_036_854_775_000L, sequence), LogicalCallDecisionOutcome.INDEPENDENT,
            identity(sequence, destinationAlias, sourceAlias),
            new LogicalCallDiagnosticOutputPolicy(false, streamRoutes, streamRoutes.size(), true), winner(),
            List.of(leg(0)), new LogicalCallDiagnosticEvidence(0, 1, 0, Map.of(),
                Map.of(LogicalCallSeparationReason.DESTINATION_MISMATCH, 1L)),
            List.of(LogicalCallSeparationReason.NO_CANDIDATE_LEG));
    }

    private static LogicalCallDiagnosticDecision decision(long sequence, LogicalCallDiagnosticEvidence evidence)
    {
        return new LogicalCallDiagnosticDecision(sequence, 2_000 + sequence,
            new LogicalCallId(9_223_372_036_854_775_000L, sequence), LogicalCallDecisionOutcome.MERGED,
            identity(sequence, "Dispatch", "Radio 1001"),
            new LogicalCallDiagnosticOutputPolicy(false, List.of("Primary Route"), 1, true), winner(),
            List.of(leg(0)), evidence, List.of());
    }

    private static LogicalCallDiagnosticCallIdentity identity(long sequence, String destinationAlias,
                                                               String sourceAlias)
    {
        return new LogicalCallDiagnosticCallIdentity(sequence, "P25", "P25P2", 1_000, 2_000, 2_100, 100,
            "1001", destinationAlias, "2002", sourceAlias, CallEncryptionState.CLEAR, 0xBEE00, 0x123, 42,
            "Regional Alias List", 1);
    }

    private static LogicalCallDiagnosticWinner winner()
    {
        return new LogicalCallDiagnosticWinner("leg-0", null, LogicalCallWinnerCriterion.SINGLE_LEG,
            new LogicalCallDiagnosticWinner.CriterionValue("only leg", 1L, 1L),
            LogicalCallDiagnosticWinner.CriterionValue.empty());
    }

    private static LogicalCallDiagnosticLeg leg(int index)
    {
        long start = 1_000L + index;
        return leg(index, start, start + 5_000L, index == 0);
    }

    private static LogicalCallDiagnosticLeg leg(int index, long start, long end, boolean selected)
    {
        long expected = 500;
        return new LogicalCallDiagnosticLeg("leg-" + index, "P25P2", "channel-" + index,
            "MARCS Site " + (index + 1), "learned-site-guid-" + index, 42, 0xBEE00, 0x123, 1, index + 1,
            start, end, Math.max(0L, end - start),
            expected, 498, 490, 490, 1, 3, 2, 10, 5_000, 98.0, 0.01, 0.002, 0.002,
            40_000, false, false, selected);
    }

    private static List<JsonNode> parseLines(String jsonl) throws Exception
    {
        List<JsonNode> records = new ArrayList<>();

        for(String line: jsonl.lines().toList())
        {
            if(!line.isBlank())
            {
                records.add(OBJECT_MAPPER.readTree(line));
            }
        }

        return records;
    }

    private static boolean waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();

        while(System.nanoTime() - deadline < 0)
        {
            if(condition.getAsBoolean())
            {
                return true;
            }

            Thread.sleep(10);
        }

        return condition.getAsBoolean();
    }

    private static final class BlockingEncoder implements LogicalCallDiagnosticRecordEncoder
    {
        private final LogicalCallDiagnosticJsonEncoder mDelegate = new LogicalCallDiagnosticJsonEncoder();
        private final CountDownLatch mEntered = new CountDownLatch(1);
        private final CountDownLatch mRelease = new CountDownLatch(1);
        private final AtomicReference<Thread> mEncodingThread = new AtomicReference<>();

        @Override
        public byte[] encodeSessionHeader(String sessionId, long sessionStartedAtEpochMillis, long segmentNumber,
                                          LogicalCallDiagnosticConfiguration configuration)
        {
            return mDelegate.encodeSessionHeader(sessionId, sessionStartedAtEpochMillis, segmentNumber,
                configuration);
        }

        @Override
        public byte[] encodeDecision(LogicalCallDiagnosticDecision decision)
        {
            mEncodingThread.compareAndSet(null, Thread.currentThread());
            mEntered.countDown();

            while(mRelease.getCount() > 0)
            {
                try
                {
                    mRelease.await();
                }
                catch(InterruptedException exception)
                {
                    // Intentionally remain blocked so the test can prove close has a hard deadline.
                }
            }

            return mDelegate.encodeDecision(decision);
        }

        @Override
        public byte[] encodeOutput(LogicalCallDiagnosticOutputEvent event)
        {
            return mDelegate.encodeOutput(event);
        }

        boolean awaitEntered() throws InterruptedException
        {
            return mEntered.await(1, TimeUnit.SECONDS);
        }

        Thread encodingThread()
        {
            return mEncodingThread.get();
        }

        void release()
        {
            mRelease.countDown();
        }
    }
}
