/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.LogicalCallId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogicalCallDiagnosticServiceTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void retainsOnlyConfirmedDuplicatesAndUnrelatedCallsCannotEvictThem()
    {
        try(LogicalCallDiagnosticService service = new LogicalCallDiagnosticService(
            new LogicalCallDiagnosticConfiguration(4)))
        {
            assertTrue(service.offer(decision(1, LogicalCallDecisionOutcome.MERGED)));

            for(int sequence = 2; sequence <= 101; sequence++)
            {
                assertTrue(service.offer(decision(sequence, LogicalCallDecisionOutcome.INDEPENDENT)));
            }

            assertTrue(service.offer(decision(102, LogicalCallDecisionOutcome.FAIL_OPEN)));
            assertEquals(List.of(1L), sequences(service.snapshot()));
            assertEquals(0, service.snapshot().duplicatesEvicted());
            assertEquals(1, service.status().decisionsObserved());

            for(int sequence = 103; sequence <= 107; sequence++)
            {
                assertTrue(service.offer(decision(sequence, LogicalCallDecisionOutcome.MERGED)));
            }

            assertEquals(List.of(104L, 105L, 106L, 107L), sequences(service.snapshot()));
            assertEquals(2, service.snapshot().duplicatesEvicted());
        }
    }

    @Test
    void memoryOnlyServiceNeedsNoFilePathOrWriterAndLeavesDirectoryUntouched() throws Exception
    {
        Path unrelated = mTemporaryDirectory.resolve("keep-me.txt");
        Files.writeString(unrelated, "unrelated");

        try(LogicalCallDiagnosticService service = new LogicalCallDiagnosticService())
        {
            assertTrue(service.offer(decision(1, LogicalCallDecisionOutcome.MERGED)));
            assertTrue(service.offer(decision(2, LogicalCallDecisionOutcome.INDEPENDENT)));
            assertEquals(List.of(1L), sequences(service.snapshot()));
            assertEquals(256, LogicalCallDiagnosticConfiguration.DEFAULT_RECENT_DUPLICATE_CAPACITY);
        }

        try(Stream<Path> paths = Files.list(mTemporaryDirectory))
        {
            assertEquals(List.of(unrelated), paths.toList());
        }
    }

    @Test
    void closeRejectsNewDecisionsAndKeepsExistingSnapshotAvailable()
    {
        LogicalCallDiagnosticService service = new LogicalCallDiagnosticService(
            new LogicalCallDiagnosticConfiguration(4));
        assertTrue(service.offer(decision(1, LogicalCallDecisionOutcome.MERGED)));
        service.close();
        service.close();

        assertFalse(service.offer(decision(2, LogicalCallDecisionOutcome.MERGED)));
        assertFalse(service.status().accepting());
        assertEquals(1, service.status().recordsRejectedAfterClose());
        assertEquals(List.of(1L), sequences(service.snapshot()));
    }

    @Test
    void heldSnapshotCannotDelayOffersOrGrowTheRing() throws Exception
    {
        try(LogicalCallDiagnosticService service = new LogicalCallDiagnosticService(
            new LogicalCallDiagnosticConfiguration(4)))
        {
            assertTrue(service.offer(decision(1, LogicalCallDecisionOutcome.MERGED)));
            LogicalCallDiagnosticServiceSnapshot heldSnapshot = service.snapshot();
            long start = System.nanoTime();

            for(int sequence = 2; sequence <= 2_001; sequence++)
            {
                assertTrue(service.offer(decision(sequence, LogicalCallDecisionOutcome.MERGED)));
            }

            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 2,
                "Holding an old client snapshot must not backpressure diagnostic offers");
            assertEquals(List.of(1L), sequences(heldSnapshot));
            assertEquals(List.of(1_998L, 1_999L, 2_000L, 2_001L), sequences(service.snapshot()));
            assertEquals(1_997, service.snapshot().duplicatesEvicted());
        }
    }

    @Test
    void capacityMustBeFixedPowerOfTwo()
    {
        assertThrows(IllegalArgumentException.class, () -> new LogicalCallDiagnosticConfiguration(0));
        assertThrows(IllegalArgumentException.class, () -> new LogicalCallDiagnosticConfiguration(3));
        assertThrows(IllegalArgumentException.class, () -> new LogicalCallDiagnosticConfiguration(512));
    }

    private static List<Long> sequences(LogicalCallDiagnosticServiceSnapshot snapshot)
    {
        return snapshot.recentDuplicates().stream()
            .map(LogicalCallDiagnosticDecision::decisionSequence).toList();
    }

    private static LogicalCallDiagnosticDecision decision(long sequence, LogicalCallDecisionOutcome outcome)
    {
        return new LogicalCallDiagnosticDecision(sequence, 1_000L + sequence,
            new LogicalCallId(77, sequence), outcome, null, null, null, List.of(),
            LogicalCallDiagnosticEvidence.EMPTY, List.of());
    }
}
