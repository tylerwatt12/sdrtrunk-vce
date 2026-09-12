/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import java.util.Objects;

/**
 * Immutable receiver-health incident state handed to the bounded statistics database writer.
 */
public record ReceiverHealthIncidentRecord(long processStartedAtMs, long occurrenceId, String code,
                                           String severity, String title, String scope, long openedAtMs,
                                           long lastSeenAtMs, long resolvedAtMs, long count, String observed,
                                           String likelyCause, String impact, String checkNext)
    implements ReceiverActivityRecord
{
    public ReceiverHealthIncidentRecord
    {
        if(processStartedAtMs <= 0 || occurrenceId <= 0 || openedAtMs <= 0 || lastSeenAtMs < openedAtMs ||
            resolvedAtMs < 0 || resolvedAtMs > 0 && resolvedAtMs < lastSeenAtMs || count < 0)
        {
            throw new IllegalArgumentException("Receiver-health incident timestamps and counts are invalid");
        }

        code = required(code, "code");
        severity = required(severity, "severity");
        if(!"warning".equals(severity) && !"critical".equals(severity))
        {
            throw new IllegalArgumentException("Receiver-health incident severity is invalid");
        }
        title = required(title, "title");
        scope = required(scope, "scope");
        observed = Objects.requireNonNullElse(observed, "");
        likelyCause = Objects.requireNonNullElse(likelyCause, "");
        impact = Objects.requireNonNullElse(impact, "");
        checkNext = Objects.requireNonNullElse(checkNext, "");
    }

    @Override
    public long observedAtEpochMilliseconds()
    {
        return lastSeenAtMs;
    }

    private static String required(String value, String label)
    {
        if(value == null || value.isBlank())
        {
            throw new IllegalArgumentException("Receiver-health incident " + label + " is required");
        }
        return value;
    }
}
