/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts a successful recording or streamer handoff once for a logical call, even when a long call is split into
 * linked one-minute audio segments.
 */
class CallOutputDeduplicator
{
    private static final int MAXIMUM_TRACKED_CALL_IDS = 65536;
    private static final long RETENTION_MILLISECONDS = 24L * 60L * 60L * 1000L;

    private final Map<AudioCallId,CallChain> mCallChains = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<OutputKey,Long> mObservedOutputs = new LinkedHashMap<>(256, 0.75f, true);

    synchronized boolean firstOutput(AudioCallSnapshot snapshot, P25ActivityLogRecords.CallOutput output, long now)
    {
        if(snapshot == null || output == null || snapshot.callId() == null)
        {
            return true;
        }

        cleanup(now);
        AudioCallId root = snapshot.callId();

        if(snapshot.linkedCallId() != null)
        {
            CallChain linked = mCallChains.get(snapshot.linkedCallId());
            root = linked != null ? linked.root() : snapshot.linkedCallId();
        }

        mCallChains.put(snapshot.callId(), new CallChain(root, now));
        boolean first = mObservedOutputs.put(new OutputKey(root, output), now) == null;
        enforceMaximumSize(mCallChains);
        enforceMaximumSize(mObservedOutputs);
        return first;
    }

    synchronized void clear()
    {
        mCallChains.clear();
        mObservedOutputs.clear();
    }

    private void cleanup(long now)
    {
        removeExpired(mCallChains, now);
        removeExpired(mObservedOutputs, now);
    }

    private static <K,V> void enforceMaximumSize(Map<K,V> map)
    {
        Iterator<K> iterator = map.keySet().iterator();

        while(map.size() > MAXIMUM_TRACKED_CALL_IDS && iterator.hasNext())
        {
            iterator.next();
            iterator.remove();
        }
    }

    private static <K,V> void removeExpired(Map<K,V> map, long now)
    {
        Iterator<Map.Entry<K,V>> iterator = map.entrySet().iterator();

        while(iterator.hasNext())
        {
            Map.Entry<K,V> entry = iterator.next();
            long observedAt = entry.getValue() instanceof CallChain chain ? chain.observedAt() :
                entry.getValue() instanceof Long timestamp ? timestamp : now;

            if(now >= observedAt && now - observedAt > RETENTION_MILLISECONDS)
            {
                iterator.remove();
            }
            else
            {
                break;
            }
        }
    }

    private record CallChain(AudioCallId root, long observedAt)
    {
    }

    private record OutputKey(AudioCallId root, P25ActivityLogRecords.CallOutput output)
    {
    }
}
