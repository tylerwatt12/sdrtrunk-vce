/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Catalog-worker-only rendezvous. No PCM, mutable alias list or Activity database is retained. */
final class ManagedRecordingToneAssociations
{
    static final int MAXIMUM_CALLS = 1024;
    private final Map<Key,CompletedToneSequence> mOutcomes = new LinkedHashMap<>();
    private final Map<Long,PendingRecording> mRecordings = new LinkedHashMap<>();

    /** These are the exact fields checked by CompletedToneSequence.matches, not a time-window search. */
    record Key(AudioCallId call, CallLegId leg, CallLegSource source, long started, long completed)
    {
        static Key from(AudioCallSnapshot snapshot)
        {
            return snapshot != null && snapshot.complete() ? new Key(snapshot.callId(), snapshot.callLegId(),
                snapshot.callLegSource(), snapshot.startTimestamp(), snapshot.lastActivityTimestamp()) : null;
        }
    }

    private record PendingRecording(Key key, long retryAfter) {}
    record Attachment(Key key, long recordingId, CompletedToneSequence sequence) {}

    void indexed(Key key, long recordingId, boolean alreadyHasTones)
    {
        if(key == null || alreadyHasTones) return;
        mRecordings.putIfAbsent(recordingId, new PendingRecording(key, 0));
        trim(mRecordings);
    }

    void completed(CompletedToneSequence sequence)
    {
        if(sequence == null || sequence.tones().isEmpty()) return;
        mOutcomes.putIfAbsent(Key.from(sequence.snapshot()), sequence);
        trim(mOutcomes);
    }

    List<Attachment> ready(long now)
    {
        List<Attachment> ready = new ArrayList<>();
        for(var item: mRecordings.entrySet())
        {
            PendingRecording pending = item.getValue();
            CompletedToneSequence sequence = mOutcomes.get(pending.key());
            if(sequence == null || now < pending.retryAfter()) continue;
            ready.add(new Attachment(pending.key(), item.getKey(), sequence));
            if(ready.size() == 32) break;
        }
        return ready;
    }

    boolean hasReady(long now)
    {
        return mRecordings.values().stream().anyMatch(pending -> pending.retryAfter() <= now &&
            mOutcomes.containsKey(pending.key()));
    }

    void attached(Attachment attachment) { mRecordings.remove(attachment.recordingId()); }

    void clear() { mRecordings.clear(); mOutcomes.clear(); }

    void failed(Attachment attachment, long now)
    {
        mRecordings.computeIfPresent(attachment.recordingId(),
            (id, pending) -> new PendingRecording(pending.key(), now + 1000));
    }

    private static void trim(Map<?,?> map)
    {
        while(map.size() > MAXIMUM_CALLS)
        { var oldest = map.keySet().iterator(); oldest.next(); oldest.remove(); }
    }
}
