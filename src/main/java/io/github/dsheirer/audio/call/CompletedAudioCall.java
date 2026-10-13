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

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.ConfigurationLongIdentifier;
import java.util.List;

/**
 * Final immutable representation of one resolved logical call.  The elected receiver leg owns the audio and
 * snapshot.  All physical receiver legs remain represented by compact summaries so that site observations can be
 * counted without treating each received copy as another system call. Standard recording filenames and tags use
 * the winner's observed identifiers, retained separately from identities promoted by the logical-call resolver.
 */
public record CompletedAudioCall(LogicalCallId logicalCallId, AudioCallSnapshot snapshot,
                                 List<float[]> audioBuffers, ResolvedCallPolicy resolvedPolicy,
                                 List<CallLegSummary> callLegSummaries,
                                 @JsonIgnore IdentifierCollection recordingIdentifierCollection,
                                 @JsonIgnore AudioCallSnapshot nativeAudioSnapshot,
                                 @JsonIgnore io.github.dsheirer.audio.tone.CompletedToneSequence completedToneSequence)
{
    /** Compatibility constructor: a caller without multi-receiver resolution owns its snapshot's native audio. */
    public CompletedAudioCall(LogicalCallId logicalCallId, AudioCallSnapshot snapshot,
                              List<float[]> audioBuffers, ResolvedCallPolicy resolvedPolicy,
                              List<CallLegSummary> callLegSummaries, IdentifierCollection recordingIdentifierCollection)
    {
        this(logicalCallId, snapshot, audioBuffers, resolvedPolicy, callLegSummaries,
            recordingIdentifierCollection, snapshot, null);
    }

    /**
     * Existing callers without a logical-call identity promotion use their snapshot's recording observations.
     * The full constructor deliberately preserves a null recording collection when the winning leg had none.
     */
    public CompletedAudioCall(LogicalCallId logicalCallId, AudioCallSnapshot snapshot,
                              List<float[]> audioBuffers, ResolvedCallPolicy resolvedPolicy,
                              List<CallLegSummary> callLegSummaries)
    {
        this(logicalCallId, snapshot, audioBuffers, resolvedPolicy, callLegSummaries,
            snapshot != null ? snapshot.identifierCollection() : null);
    }

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
        nativeAudioSnapshot = nativeAudioSnapshot != null ? nativeAudioSnapshot : snapshot;
        if(!nativeAudioSnapshot.callId().equals(snapshot.callId()) ||
            !nativeAudioSnapshot.callLegId().equals(snapshot.callLegId()) ||
            !nativeAudioSnapshot.callLegSource().equals(snapshot.callLegSource()) ||
            completedToneSequence != null && !completedToneSequence.matches(nativeAudioSnapshot))
            throw new IllegalArgumentException("Tone evidence must belong to the captured native audio winner");

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

    /** Worker-only recording view; no PCM copy and no mutation of receiver or logical-call facts. */
    public CompletedAudioCall withCompletedTones(io.github.dsheirer.audio.tone.CompletedToneSequence sequence)
    {
        return new CompletedAudioCall(logicalCallId, snapshot, audioBuffers, resolvedPolicy, callLegSummaries,
            recordingIdentifierCollection, nativeAudioSnapshot, sequence);
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
        io.github.dsheirer.identifier.IdentifierCollection identifiers = snapshot.identifierCollection();
        return List.of(new CallLegSummary(snapshot.callLegId(), snapshot.callLegSource(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.voiceCallQuality(),
            sampleCount(audioBuffers), false, false, true, snapshot.callEncryptionEvidence(),
            identifiers != null ? identifiers.getFromIdentifier() : null,
            identifiers != null ? identifiers.getToIdentifier() : null, List.of(), frequency(identifiers),
            snapshot.timeslot() > 0 ? snapshot.timeslot() : null));
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

    private static Long frequency(IdentifierCollection identifiers)
    {
        Identifier<?> identifier = identifiers != null ? identifiers.getIdentifier(IdentifierClass.CONFIGURATION,
            Form.CHANNEL_FREQUENCY, Role.ANY) : null;
        return identifier instanceof ConfigurationLongIdentifier value && value.getValue() > 0L ?
            value.getValue() : null;
    }
}
