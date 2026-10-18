/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Optional page enrichment uses exact catalog associations, without reading recording metadata or audio files. */
final class ActivityRecordingAvailability
{
    private static final long PAGE_BUDGET_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(250);
    private final ActivitySourceLookup mActivity;
    private final TimedRecordingLookup mRecordings;

    ActivityRecordingAvailability(StatsWebDatabase database, Supplier<ManagedRecordingCatalog> catalog)
    {
        this(database::activitySourceCallReferences, (sources, limit, deadline) -> {
            ManagedRecordingCatalog current = catalog.get();
            return current != null ? current.recordingIdsBySourceCallReferences(sources, limit, deadline) : Map.of();
        });
    }

    ActivityRecordingAvailability(ActivitySourceLookup activity, RecordingLookup recordings)
    {
        this(activity, (sources, limit, deadline) -> recordings.find(sources, limit));
    }

    private ActivityRecordingAvailability(ActivitySourceLookup activity, TimedRecordingLookup recordings)
    {
        mActivity = activity;
        mRecordings = recordings;
    }

    @SuppressWarnings("unchecked")
    void enrich(Map<String,Object> page, boolean recordingsAllowed)
    {
        List<Map<String,Object>> original = (List<Map<String,Object>>)page.get("rows");
        if(original == null || original.isEmpty()) return;
        List<Map<String,Object>> rows = new ArrayList<>(original.size());
        for(Map<String,Object> originalRow: original)
        {
            Map<String,Object> row = new LinkedHashMap<>(originalRow);
            row.remove("has_recording");
            if(recordingsAllowed) row.put("has_recording", false);
            rows.add(row);
        }
        page.put("rows", rows);
        if(!recordingsAllowed) return;

        long deadline = System.nanoTime() + PAGE_BUDGET_NANOSECONDS;
        try
        {
            List<Long> ids = rows.stream().map(row -> row.get("id"))
                .filter(Number.class::isInstance).map(Number.class::cast).map(Number::longValue)
                .filter(id -> id > 0).distinct().toList();
            if(ids.isEmpty()) return;
            Map<Long,String> references = mActivity.sources(ids);
            List<String> sources = new ArrayList<>(new LinkedHashSet<>(references.values()));
            Set<String> available = new LinkedHashSet<>();
            for(int start = 0; start < sources.size(); start += ManagedRecordingCatalog.MAX_PAGE_SIZE)
            {
                requireBudget(deadline);
                List<String> batch = sources.subList(start,
                    Math.min(start + ManagedRecordingCatalog.MAX_PAGE_SIZE, sources.size()));
                Map<String,List<Long>> matches = mRecordings.find(batch, 1, deadline);
                for(String source: batch)
                {
                    List<Long> recordings = matches.get(source);
                    if(recordings != null && !recordings.isEmpty()) available.add(source);
                }
            }
            requireBudget(deadline);
            for(Map<String,Object> row: rows)
            {
                if(row.get("id") instanceof Number id)
                    row.put("has_recording", available.contains(references.get(id.longValue())));
            }
        }
        catch(SQLException | RuntimeException ignored)
        {
            // An optional catalog lookup must not delay or fail the Activity page, or imply unconfirmed audio exists.
        }
    }

    private static void requireBudget(long deadline) throws SQLException
    {
        if(Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new SQLException("Recording availability lookup exceeded its page budget");
    }

    @FunctionalInterface interface ActivitySourceLookup
    {
        Map<Long,String> sources(List<Long> ids) throws SQLException;
    }

    @FunctionalInterface interface RecordingLookup
    {
        Map<String,List<Long>> find(List<String> sources, int limitPerSource) throws SQLException;
    }

    @FunctionalInterface private interface TimedRecordingLookup
    {
        Map<String,List<Long>> find(List<String> sources, int limitPerSource, long deadlineNanos) throws SQLException;
    }
}
