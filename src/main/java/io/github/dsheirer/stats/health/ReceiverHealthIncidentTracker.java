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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Process-local incident state for receiver-health measurements.  It deliberately stores only a small active set and
 * a bounded recently-resolved history; it does not own a database or write from receiver callbacks.
 */
final class ReceiverHealthIncidentTracker
{
    static final int MAXIMUM_RESOLVED_INCIDENTS = 200;
    static final long RESOLVED_RETENTION_MILLISECONDS = 24L * 60L * 60L * 1_000L;
    static final long DEFAULT_RESOLUTION_DELAY_MILLISECONDS = 10_000L;
    private static final long RECURRENCE_HOLD_NANOSECONDS = TimeUnit.SECONDS.toNanos(10);

    private final Map<String,MutableIncident> mActive = new LinkedHashMap<>();
    private final Map<String,PendingIncident> mPending = new LinkedHashMap<>();
    private final Deque<Map<String,Object>> mResolved = new ArrayDeque<>();
    private final Map<String,Map<String,Object>> mLifecycleChanges = new LinkedHashMap<>();
    private final Set<String> mObservedThisSample = new HashSet<>();
    private final long mResolutionDelayMilliseconds;
    private long mOccurrenceSequence;
    private long mSampleNanos;

    ReceiverHealthIncidentTracker()
    {
        this(DEFAULT_RESOLUTION_DELAY_MILLISECONDS);
    }

    ReceiverHealthIncidentTracker(long resolutionDelayMilliseconds)
    {
        mResolutionDelayMilliseconds = Math.max(0, resolutionDelayMilliseconds);
    }

    void beginSample(long nowNanos)
    {
        mSampleNanos = nowNanos;
        mObservedThisSample.clear();
        mLifecycleChanges.clear();
    }

    /** A new event must confirm continuing loss; elapsed time alone never promotes an isolated event. */
    void observeRecurring(String code, String severity, String title, String scopeId, String scope, long now, long count,
                          String observed, String likelyCause, String impact, String checkNext)
    {
        String key = code + '\u0000' + scopeId;
        mObservedThisSample.add(key);
        if(!mActive.containsKey(key))
        {
            PendingIncident pending = mPending.get(key);
            if(pending == null || mSampleNanos - pending.lastSeenNanos >= RECURRENCE_HOLD_NANOSECONDS)
            {
                mPending.put(key, new PendingIncident(scopeId, mSampleNanos));
                return;
            }
            pending.lastSeenNanos = mSampleNanos;
            if(mSampleNanos - pending.firstSeenNanos < RECURRENCE_HOLD_NANOSECONDS) return;
        }
        observe(code, severity, title, scopeId, scope, now, count, observed, likelyCause, impact, checkNext);
    }

    /** An interrupted sample cannot establish that an unconfirmed loss continued. */
    void discardPending()
    {
        mPending.clear();
    }

    /** Missing measurements preserve existing incidents, but cannot establish recurring evidence. */
    void preserve(String scopeId)
    {
        for(Map.Entry<String,MutableIncident> entry: mActive.entrySet())
        {
            if(Objects.equals(scopeId, entry.getValue().scopeId)) mObservedThisSample.add(entry.getKey());
        }
        mPending.values().removeIf(pending -> Objects.equals(scopeId, pending.scopeId));
    }

    void observe(String code, String severity, String title, String scopeId, String scope, long now, long count,
                 String observed, String likelyCause, String impact, String checkNext)
    {
        String key = code + '\u0000' + scopeId;
        mPending.remove(key);
        mObservedThisSample.add(key);
        MutableIncident incident = mActive.get(key);
        boolean lifecycleChanged = incident == null;

        if(incident == null)
        {
            incident = new MutableIncident(++mOccurrenceSequence, code, severity, title, scopeId, scope, now, mSampleNanos);
            mActive.put(key, incident);
        }
        else if(!Objects.equals(incident.severity, severity) || !Objects.equals(incident.title, title))
        {
            lifecycleChanged = true;
        }

        incident.severity = severity;
        incident.title = title;
        incident.scope = scope;
        incident.lastSeenMs = Math.max(incident.lastSeenMs, now);
        incident.lastSeenNanos = mSampleNanos;
        incident.count = Math.max(incident.count, count);
        incident.observed = observed;
        incident.likelyCause = likelyCause;
        incident.impact = impact;
        incident.checkNext = checkNext;

        if(lifecycleChanged || mLifecycleChanges.containsKey(key))
        {
            mLifecycleChanges.put(key, incident.toMap(0));
        }
    }

    void endSample(long now)
    {
        mPending.entrySet().removeIf(entry -> !mObservedThisSample.contains(entry.getKey()) ||
            mSampleNanos - entry.getValue().lastSeenNanos >= RECURRENCE_HOLD_NANOSECONDS);
        List<String> resolvedKeys = new ArrayList<>();

        for(Map.Entry<String,MutableIncident> entry: mActive.entrySet())
        {
            MutableIncident incident = entry.getValue();

            if(!mObservedThisSample.contains(entry.getKey()) && mSampleNanos - incident.lastSeenNanos >=
                TimeUnit.MILLISECONDS.toNanos(mResolutionDelayMilliseconds))
            {
                resolvedKeys.add(entry.getKey());
            }
        }

        for(String key: resolvedKeys)
        {
            MutableIncident incident = mActive.remove(key);

            if(incident != null)
            {
                Map<String,Object> resolved = incident.toMap(Math.max(now, incident.lastSeenMs));
                mResolved.addFirst(resolved);
                mLifecycleChanges.put(key, resolved);
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

    List<Map<String,Object>> lifecycleChanges()
    {
        return List.copyOf(mLifecycleChanges.values());
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static final class PendingIncident
    {
        private final String scopeId;
        private final long firstSeenNanos;
        private long lastSeenNanos;

        private PendingIncident(String scopeId, long nowNanos)
        {
            this.scopeId = scopeId;
            firstSeenNanos = nowNanos;
            lastSeenNanos = nowNanos;
        }
    }

    private static final class MutableIncident
    {
        private final long occurrenceId;
        private final String code;
        private String severity;
        private String title;
        private final String scopeId;
        private String scope;
        private final long openedAtMs;
        private long lastSeenMs;
        private long lastSeenNanos;
        private long count;
        private String observed;
        private String likelyCause;
        private String impact;
        private String checkNext;

        private MutableIncident(long occurrenceId, String code, String severity, String title, String scopeId, String scope,
                                long openedAtMs, long openedAtNanos)
        {
            this.occurrenceId = occurrenceId;
            this.code = code;
            this.severity = severity;
            this.title = title;
            this.scopeId = scopeId;
            this.scope = scope;
            this.openedAtMs = openedAtMs;
            lastSeenMs = openedAtMs;
            lastSeenNanos = openedAtNanos;
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
