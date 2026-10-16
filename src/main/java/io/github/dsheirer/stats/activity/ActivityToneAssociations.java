/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.audio.call.AudioCallId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Writer-owned bounded receipts. A batch works on a copy and publishes it only after SQLite commits. */
final class ActivityToneAssociations
{
    private static final int MAXIMUM_CALLS = 4096, MAXIMUM_PENDING = 128;
    private static final long MAXIMUM_AGE_MS = 30 * 60_000L;
    private final Map<String,Call> mCalls;
    private final Map<String,List<ReceiverActivityRecords.CompletedToneObservation>> mPending;
    private record Call(long id, String configuration, long acceptedAt, Set<AudioCallId> completedSegments) {}

    ActivityToneAssociations() { mCalls = new LinkedHashMap<>(); mPending = new LinkedHashMap<>(); }
    private ActivityToneAssociations(ActivityToneAssociations previous)
    {
        mCalls = new LinkedHashMap<>(previous.mCalls); mPending = new LinkedHashMap<>(previous.mPending);
    }
    ActivityToneAssociations copy() { return new ActivityToneAssociations(this); }

    void call(Connection connection, String token, String configuration, Long id, long now) throws SQLException
    {
        prune(now);
        if(token == null || id == null) return;
        mCalls.putIfAbsent(token, new Call(id, configuration, now, Set.of()));
        var waiting = mPending.remove(token);
        if(waiting != null) for(var observation: waiting) tone(connection, observation, now);
        trim(mCalls, MAXIMUM_CALLS);
    }

    boolean tone(Connection connection, ReceiverActivityRecords.CompletedToneObservation observation, long now)
        throws SQLException
    {
        prune(now);
        String token = observation.sequence().snapshot().activityCallToken();
        if(token == null) return false;
        Call call = mCalls.get(token);
        if(call != null)
        {
            if(!call.configuration().equals(observation.activity().configurationId()) ||
                call.completedSegments().contains(observation.sequence().callId())) return false;
            if(!ToneActivitySchema.record(connection, observation, true, call.id())) return false;
            var segments = new HashSet<>(call.completedSegments());
            segments.add(observation.sequence().callId());
            mCalls.put(token, new Call(call.id(), call.configuration(), call.acceptedAt(), Set.copyOf(segments)));
            return true;
        }
        List<ReceiverActivityRecords.CompletedToneObservation> previous = mPending.getOrDefault(token, List.of());
        if(previous.size() < 4 && previous.stream().noneMatch(item ->
            item.sequence().callId().equals(observation.sequence().callId())))
        {
            var next = new ArrayList<>(previous); next.add(observation); mPending.put(token, List.copyOf(next));
            trim(mPending, MAXIMUM_PENDING);
        }
        return false;
    }

    private void prune(long now)
    {
        mCalls.values().removeIf(call -> now - call.acceptedAt() > MAXIMUM_AGE_MS);
        mPending.values().removeIf(items -> items.isEmpty() ||
            now - items.getLast().sequence().completedTimestamp() > MAXIMUM_AGE_MS);
    }
    private static void trim(Map<?,?> map, int limit)
    {
        while(map.size() > limit) { var iterator = map.keySet().iterator(); iterator.next(); iterator.remove(); }
    }
}
