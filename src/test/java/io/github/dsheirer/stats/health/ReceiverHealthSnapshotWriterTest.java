/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiverHealthSnapshotWriterTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void writesBoundedIncidentReportOnlyForLifecycleChanges() throws Exception
    {
        Path target = mTemporaryDirectory.resolve("logs").resolve(ReceiverHealthSnapshotWriter.FILE_NAME);
        ReceiverHealthSnapshotWriter writer = new ReceiverHealthSnapshotWriter(target);
        Map<String,Object> warning = incident("occurrence-1", "warning", 1_000, 0, 1, "first observation");
        writer.publish(snapshot(100, 1_000, List.of(warning), List.of(), List.of(Map.of("large", "measurement"))));

        assertTrue(Files.isRegularFile(target));
        assertTrue(Files.size(target) < ReceiverHealthSnapshotWriter.MAXIMUM_REPORT_BYTES);
        Map<String,Object> report = OBJECT_MAPPER.readValue(target.toFile(), new TypeReference<>() {});
        assertEquals("sdrtrunk-vce-receiver-health-v1", report.get("format"));
        assertEquals(100, ((Number)report.get("started_at_ms")).longValue());
        assertEquals(1_000, ((Number)report.get("generated_at_ms")).longValue());
        assertFalse(report.containsKey("measurements"));
        byte[] first = Files.readAllBytes(target);

        Map<String,Object> sameLifecycle = incident("occurrence-1", "warning", 1_000, 0, 99,
            "new observation text");
        writer.publish(snapshot(100, 2_000, List.of(sameLifecycle), List.of(), List.of(Map.of("changed", true))));
        assertArrayEquals(first, Files.readAllBytes(target));

        Map<String,Object> critical = incident("occurrence-1", "critical", 1_000, 0, 100,
            "severity changed");
        writer.publish(snapshot(100, 3_000, List.of(critical), List.of(), List.of()));
        report = OBJECT_MAPPER.readValue(target.toFile(), new TypeReference<>() {});
        assertEquals(3_000, ((Number)report.get("generated_at_ms")).longValue());

        Map<String,Object> resolved = incident("occurrence-1", "critical", 1_000, 4_000, 100,
            "resolved observation");
        writer.publish(snapshot(100, 4_000, List.of(), List.of(resolved), List.of()));
        report = OBJECT_MAPPER.readValue(target.toFile(), new TypeReference<>() {});
        assertTrue(((List<?>)report.get("active")).isEmpty());
        assertEquals(1, ((List<?>)report.get("resolved")).size());
        assertFalse(Files.exists(target.resolveSibling("." + target.getFileName() + ".tmp")));
    }

    @Test
    void refusesAnOversizedReportWithoutReplacingTheLastGoodReport() throws Exception
    {
        Path target = mTemporaryDirectory.resolve(ReceiverHealthSnapshotWriter.FILE_NAME);
        ReceiverHealthSnapshotWriter writer = new ReceiverHealthSnapshotWriter(target);
        writer.publish(snapshot(100, 1_000, List.of(), List.of(), List.of()));
        byte[] first = Files.readAllBytes(target);
        String oversized = "x".repeat(ReceiverHealthSnapshotWriter.MAXIMUM_REPORT_BYTES);
        Map<String,Object> incident = incident("occurrence-2", "critical", 2_000, 0, 1, oversized);

        assertThrows(IOException.class,
            () -> writer.publish(snapshot(100, 2_000, List.of(incident), List.of(), List.of())));
        assertArrayEquals(first, Files.readAllBytes(target));
    }

    private static Map<String,Object> snapshot(long startedAtMs, long generatedAtMs,
                                                List<Map<String,Object>> active,
                                                List<Map<String,Object>> resolved,
                                                List<Map<String,Object>> measurements)
    {
        return Map.of("started_at_ms", startedAtMs, "generated_at_ms", generatedAtMs,
            "summary", Map.of("severity", active.isEmpty() ? "healthy" : "warning",
                "active_count", active.size()), "active", active, "resolved", resolved,
            "measurements", measurements);
    }

    private static Map<String,Object> incident(String occurrenceId, String severity, long openedAtMs,
                                                long resolvedAtMs, long count, String observed)
    {
        return Map.ofEntries(Map.entry("occurrence_id", occurrenceId), Map.entry("code", "receiver-iq-drop"),
            Map.entry("severity", severity), Map.entry("title", "Receiver IQ samples were discarded"),
            Map.entry("scope", "Airspy"), Map.entry("opened_at_ms", openedAtMs),
            Map.entry("last_seen_ms", Math.max(openedAtMs, resolvedAtMs)),
            Map.entry("resolved_at_ms", resolvedAtMs), Map.entry("count", count),
            Map.entry("observed", observed), Map.entry("likely_cause", "test"), Map.entry("impact", "test"),
            Map.entry("check_next", "test"));
    }
}
