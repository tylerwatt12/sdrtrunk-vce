/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.call;

import java.util.List;

/**
 * Final immutable representation of one resolved logical call.  The elected receiver leg owns the audio and
 * snapshot.  All physical receiver legs remain represented by compact summaries so that site observations can be
 * counted without treating each received copy as another system call.
 */
public record CompletedAudioCall(LogicalCallId logicalCallId, AudioCallSnapshot snapshot,
                                 List<float[]> audioBuffers, ResolvedCallPolicy resolvedPolicy,
                                 List<CallLegSummary> callLegSummaries)
{
    /**
     * Creates an independent one-leg logical call.  This is used by conventional receivers and bounded component
     * tests that do not pass through the multi-site resolver.
     */
    public CompletedAudioCall(AudioCallSnapshot snapshot, List<float[]> audioBuffers)
    {
        this(singleLegLogicalId(snapshot), snapshot, audioBuffers, ResolvedCallPolicy.capture(snapshot),
            singleLegSummary(snapshot, audioBuffers));
    }

    /**
     * Creates an independent one-leg logical call with an already captured output policy.
     */
    public CompletedAudioCall(AudioCallSnapshot snapshot, List<float[]> audioBuffers,
                              ResolvedCallPolicy resolvedPolicy)
    {
        this(singleLegLogicalId(snapshot), snapshot, audioBuffers, resolvedPolicy,
            singleLegSummary(snapshot, audioBuffers));
    }

    public CompletedAudioCall
    {
        if(logicalCallId == null)
        {
            throw new IllegalArgumentException("Logical call id is required");
        }

        if(snapshot == null)
        {
            throw new IllegalArgumentException("Winning snapshot is required");
        }

        audioBuffers = audioBuffers != null ? List.copyOf(audioBuffers) : List.of();
        resolvedPolicy = resolvedPolicy != null ? resolvedPolicy : ResolvedCallPolicy.capture(snapshot);
        callLegSummaries = callLegSummaries != null ? List.copyOf(callLegSummaries) : List.of();

        long winners = callLegSummaries.stream().filter(CallLegSummary::winner).count();

        if(callLegSummaries.isEmpty() || winners != 1)
        {
            throw new IllegalArgumentException("A resolved logical call requires exactly one winning receiver leg");
        }
    }

    public boolean hasAudio()
    {
        return !audioBuffers.isEmpty();
    }

    public long getDuration()
    {
        long sampleCount = 0;

        for(float[] audioBuffer : audioBuffers)
        {
            if(audioBuffer != null)
            {
                sampleCount += audioBuffer.length;
            }
        }

        return sampleCount / 8;
    }

    public int receiverLegCount()
    {
        return callLegSummaries.size();
    }

    private static LogicalCallId singleLegLogicalId(AudioCallSnapshot snapshot)
    {
        AudioCallId callId = snapshot != null ? snapshot.callId() : null;

        if(callId == null)
        {
            throw new IllegalArgumentException("A one-leg logical call requires a physical call id");
        }

        return new LogicalCallId(callId.producerId(), Math.max(1L, callId.sequence()));
    }

    private static List<CallLegSummary> singleLegSummary(AudioCallSnapshot snapshot, List<float[]> audioBuffers)
    {
        return List.of(new CallLegSummary(snapshot.callLegId(), snapshot.callLegSource(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.voiceCallQuality(),
            sampleCount(audioBuffers), false, false, true, snapshot.callEncryptionEvidence()));
    }

    private static long sampleCount(List<float[]> audioBuffers)
    {
        long sampleCount = 0L;

        if(audioBuffers != null)
        {
            for(float[] audioBuffer : audioBuffers)
            {
                if(audioBuffer != null)
                {
                    sampleCount += audioBuffer.length;
                }
            }
        }

        return sampleCount;
    }
}
