/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process-local incident state for receiver-health measurements.  It deliberately stores only a small active set and
 * a bounded recently-resolved history; it does not own a database or write from receiver callbacks.
 */
final class ReceiverHealthIncidentTracker
{
    static final int MAXIMUM_RESOLVED_INCIDENTS = 200;
    static final long RESOLVED_RETENTION_MILLISECONDS = 24L * 60L * 60L * 1_000L;
    static final long DEFAULT_RESOLUTION_DELAY_MILLISECONDS = 10_000L;

    private final Map<String,MutableIncident> mActive = new LinkedHashMap<>();
    private final Deque<Map<String,Object>> mResolved = new ArrayDeque<>();
    private final Set<String> mObservedThisSample = new HashSet<>();
    private final long mResolutionDelayMilliseconds;
    private long mOccurrenceSequence;

    ReceiverHealthIncidentTracker()
    {
        this(DEFAULT_RESOLUTION_DELAY_MILLISECONDS);
    }

    ReceiverHealthIncidentTracker(long resolutionDelayMilliseconds)
    {
        mResolutionDelayMilliseconds = Math.max(0, resolutionDelayMilliseconds);
    }

    void beginSample()
    {
        mObservedThisSample.clear();
    }

    void observe(String code, String severity, String title, String scope, long now, long count,
                 String observed, String likelyCause, String impact, String checkNext)
    {
        String key = code + '\u0000' + scope;
        mObservedThisSample.add(key);
        MutableIncident incident = mActive.get(key);

        if(incident == null)
        {
            incident = new MutableIncident(++mOccurrenceSequence, code, severity, title, scope, now);
            mActive.put(key, incident);
        }

        incident.severity = severity;
        incident.title = title;
        incident.lastSeenMs = now;
        incident.count = Math.max(incident.count, count);
        incident.observed = observed;
        incident.likelyCause = likelyCause;
        incident.impact = impact;
        incident.checkNext = checkNext;
    }

    void endSample(long now)
    {
        List<String> resolvedKeys = new ArrayList<>();

        for(Map.Entry<String,MutableIncident> entry: mActive.entrySet())
        {
            MutableIncident incident = entry.getValue();

            if(!mObservedThisSample.contains(entry.getKey()) && now - incident.lastSeenMs >=
                mResolutionDelayMilliseconds)
            {
                resolvedKeys.add(entry.getKey());
            }
        }

        for(String key: resolvedKeys)
        {
            MutableIncident incident = mActive.remove(key);

            if(incident != null)
            {
                mResolved.addFirst(incident.toMap(now));
            }
        }

        while(mResolved.size() > MAXIMUM_RESOLVED_INCIDENTS)
        {
            mResolved.removeLast();
        }

        long cutoff = now - RESOLVED_RETENTION_MILLISECONDS;

        while(!mResolved.isEmpty() && number(mResolved.peekLast().get("resolved_at_ms")) < cutoff)
        {
            mResolved.removeLast();
        }
    }

    List<Map<String,Object>> active()
    {
        return mActive.values().stream().sorted(Comparator
            .comparingInt((MutableIncident incident) -> "critical".equals(incident.severity) ? 0 : 1)
            .thenComparingLong(incident -> incident.openedAtMs))
            .map(incident -> incident.toMap(0)).toList();
    }

    List<Map<String,Object>> resolved()
    {
        return List.copyOf(mResolved);
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static final class MutableIncident
    {
        private final long occurrenceId;
        private final String code;
        private String severity;
        private String title;
        private final String scope;
        private final long openedAtMs;
        private long lastSeenMs;
        private long count;
        private String observed;
        private String likelyCause;
        private String impact;
        private String checkNext;

        private MutableIncident(long occurrenceId, String code, String severity, String title, String scope,
                                long openedAtMs)
        {
            this.occurrenceId = occurrenceId;
            this.code = code;
            this.severity = severity;
            this.title = title;
            this.scope = scope;
            this.openedAtMs = openedAtMs;
            lastSeenMs = openedAtMs;
        }

        private Map<String,Object> toMap(long resolvedAtMs)
        {
            LinkedHashMap<String,Object> incident = new LinkedHashMap<>();
            incident.put("occurrence_id", occurrenceId);
            incident.put("code", code);
            incident.put("severity", severity);
            incident.put("title", title);
            incident.put("scope", scope);
            incident.put("opened_at_ms", openedAtMs);
            incident.put("last_seen_ms", lastSeenMs);
            incident.put("resolved_at_ms", resolvedAtMs);
            incident.put("count", count);
            incident.put("observed", observed != null ? observed : "");
            incident.put("likely_cause", likelyCause != null ? likelyCause : "");
            incident.put("impact", impact != null ? impact : "");
            incident.put("check_next", checkNext != null ? checkNext : "");
            return Map.copyOf(incident);
        }
    }
}
