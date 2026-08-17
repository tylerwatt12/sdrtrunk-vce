/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes a small local incident report for host-level troubleshooting without exposing the administrator API.
 * The health sampler calls this off every receiver and decoder path.
 */
final class ReceiverHealthSnapshotWriter
{
    static final String FILE_NAME = "receiver-health.json";
    static final int MAXIMUM_REPORT_BYTES = 1_048_576;
    private static final String FORMAT = "sdrtrunk-vce-receiver-health-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path mTarget;
    private LifecycleState mLastPublishedState;

    ReceiverHealthSnapshotWriter(Path target)
    {
        mTarget = Objects.requireNonNull(target, "Target path cannot be null");
    }

    /**
     * Writes only when the process identity or incident lifecycle changes. Volatile measurements, occurrence counts,
     * and timestamps do not cause a filesystem write.
     */
    void publish(Map<String,Object> snapshot) throws IOException
    {
        LifecycleState state = lifecycleState(snapshot);

        if(state.equals(mLastPublishedState))
        {
            return;
        }

        LinkedHashMap<String,Object> report = new LinkedHashMap<>();
        report.put("format", FORMAT);
        report.put("started_at_ms", snapshot.get("started_at_ms"));
        report.put("generated_at_ms", snapshot.get("generated_at_ms"));
        report.put("summary", snapshot.getOrDefault("summary", Map.of()));
        report.put("active", snapshot.getOrDefault("active", List.of()));
        report.put("resolved", snapshot.getOrDefault("resolved", List.of()));
        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(report);

        if(bytes.length > MAXIMUM_REPORT_BYTES)
        {
            throw new IOException("Receiver health report exceeds the " + MAXIMUM_REPORT_BYTES + " byte limit");
        }

        Path parent = mTarget.getParent();

        if(parent == null)
        {
            throw new IOException("Receiver health report path has no parent directory");
        }

        Files.createDirectories(parent);
        Path staged = parent.resolve("." + mTarget.getFileName() + ".tmp");

        try
        {
            Files.write(staged, bytes);

            try
            {
                Files.move(staged, mTarget, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            catch(AtomicMoveNotSupportedException exception)
            {
                Files.move(staged, mTarget, StandardCopyOption.REPLACE_EXISTING);
            }

            mLastPublishedState = state;
        }
        finally
        {
            Files.deleteIfExists(staged);
        }
    }

    private static LifecycleState lifecycleState(Map<String,Object> snapshot)
    {
        return new LifecycleState(number(snapshot.get("started_at_ms")),
            incidentStates(snapshot.get("active"), false), incidentStates(snapshot.get("resolved"), true));
    }

    private static List<IncidentState> incidentStates(Object value, boolean resolved)
    {
        if(!(value instanceof List<?> list))
        {
            return List.of();
        }

        List<IncidentState> states = new ArrayList<>(list.size());

        for(Object item: list)
        {
            if(item instanceof Map<?,?> incident)
            {
                states.add(new IncidentState(text(incident.get("occurrence_id")), text(incident.get("code")),
                    text(incident.get("severity")), text(incident.get("title")), text(incident.get("scope")),
                    number(incident.get("opened_at_ms")), resolved ? number(incident.get("resolved_at_ms")) : 0));
            }
        }

        return List.copyOf(states);
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static String text(Object value)
    {
        return value != null ? String.valueOf(value) : "";
    }

    private record LifecycleState(long startedAtMs, List<IncidentState> active, List<IncidentState> resolved)
    {
    }

    private record IncidentState(String occurrenceId, String code, String severity, String title, String scope,
                                 long openedAtMs, long resolvedAtMs)
    {
    }
}
