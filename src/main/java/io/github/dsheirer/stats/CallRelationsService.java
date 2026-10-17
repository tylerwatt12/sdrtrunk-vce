/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.alerts.TopicAlertService;
import io.github.dsheirer.audio.call.SourceCallReference;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.RecordingCall;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Bounded local navigation between independently retained call datasets, using only exact saved references. */
final class CallRelationsService
{
    private static final int MAX_SOURCES = 128;
    private static final int MAX_ROWS = 100;
    private final ActivityLookup mActivity;
    private final Supplier<ManagedRecordingCatalog> mCatalog;
    private final AlertLookup mAlerts;
    private final UnaryOperator<List<Map<String,Object>>> mLabels;

    CallRelationsService(StatsWebDatabase database, Supplier<ManagedRecordingCatalog> catalog,
                         TopicAlertService alerts, ManagedRecordingLabels labels)
    {
        this(new ActivityLookup()
        {
            @Override public String source(long eventId) throws SQLException
            {
                try { return database.activitySourceCallReference(eventId); }
                catch(StatsApiException exception) { throw new SQLException("Activity is temporarily unavailable", exception); }
            }
            @Override public List<Map<String,Object>> find(List<String> sources) throws SQLException
            {
                try { return database.activityBySourceCallReferences(sources); }
                catch(StatsApiException exception) { throw new SQLException("Activity is temporarily unavailable", exception); }
            }
        }, catalog, alerts != null ? alerts::historyBySourceCallReferences : sources -> List.of(), labels::decorate);
    }

    CallRelationsService(ActivityLookup activity, Supplier<ManagedRecordingCatalog> catalog,
                         AlertLookup alerts, UnaryOperator<List<Map<String,Object>>> labels)
    {
        mActivity = activity; mCatalog = catalog; mAlerts = alerts; mLabels = labels;
    }

    Map<String,Object> forActivity(long eventId, boolean activityAllowed, boolean recordingsAllowed)
        throws SQLException
    {
        String source = mActivity.source(eventId);
        return related(source != null ? List.of(source) : List.of(), activityAllowed, recordingsAllowed);
    }

    /** A missing or unavailable recording is not a source for further navigation. */
    Map<String,Object> forRecording(long recordingId, boolean activityAllowed, boolean recordingsAllowed)
        throws SQLException
    {
        ManagedRecordingCatalog catalog = mCatalog.get();
        if(catalog == null || catalog.find(recordingId) == null) return null;
        return related(catalog.sourceCallReferences(recordingId), activityAllowed, recordingsAllowed);
    }

    Map<String,Object> resolve(String source, boolean activityAllowed, boolean recordingsAllowed) throws SQLException
    {
        return related(List.of(source), activityAllowed, recordingsAllowed);
    }

    private Map<String,Object> related(List<String> sourceReferences, boolean activityAllowed, boolean recordingsAllowed)
        throws SQLException
    {
        if(sourceReferences.size() > MAX_SOURCES) throw new IllegalArgumentException("Too many source call references");
        var unique = new LinkedHashSet<String>();
        for(String source: sourceReferences)
        {
            if(source == null) throw new IllegalArgumentException("Missing source call reference");
            unique.add(SourceCallReference.canonical(source));
        }
        List<String> sources = List.copyOf(unique);
        if(sources.isEmpty()) return Map.of("activity_rows", List.of(), "recordings", List.of(), "alerts", List.of());
        List<Map<String,Object>> activity = activityAllowed ? safeRows(mActivity.find(sources), false) : List.of();
        List<Map<String,Object>> recordings = List.of();
        ManagedRecordingCatalog catalog = recordingsAllowed ? mCatalog.get() : null;
        if(catalog != null)
        {
            Map<Long,RecordingCall> calls = new LinkedHashMap<>();
            for(int start = 0; start < sources.size(); start += MAX_ROWS)
                for(RecordingCall call: catalog.findBySourceCallReferences(
                    sources.subList(start, Math.min(start + MAX_ROWS, sources.size())), MAX_ROWS))
                    calls.putIfAbsent(call.id(), call);
            recordings = safeRows(mLabels.apply(calls.values().stream().sorted(
                Comparator.comparingLong(RecordingCall::startMs).thenComparingLong(RecordingCall::id))
                .limit(MAX_ROWS).map(RecordingCall::toMap).toList()), false);
        }
        return Map.of("activity_rows", activity, "recordings", recordings,
            "alerts", safeRows(mAlerts.find(sources), true));
    }

    /** Public related summaries never carry private correlation keys or action destinations from another policy. */
    private static List<Map<String,Object>> safeRows(List<Map<String,Object>> rows, boolean alert)
    {
        List<Map<String,Object>> safe = new ArrayList<>();
        for(Map<String,Object> row: rows.stream().limit(MAX_ROWS).toList())
        {
            var copy = new LinkedHashMap<>(row);
            copy.remove("source_call_uuid");
            if(alert) copy.remove("action");
            safe.add(java.util.Collections.unmodifiableMap(copy));
        }
        return List.copyOf(safe);
    }

    interface ActivityLookup
    {
        String source(long eventId) throws SQLException;
        List<Map<String,Object>> find(List<String> sources) throws SQLException;
    }

    @FunctionalInterface interface AlertLookup
    {
        List<Map<String,Object>> find(List<String> sources) throws SQLException;
    }
}
