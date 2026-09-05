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

package io.github.dsheirer.audio.call;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/**
 * Immutable provenance and quality summary for one physical receiver leg in a resolved logical call.  Audio is held
 * only by the elected winner; this compact summary lets statistics count learned-site observations independently
 * from the single logical call without retaining losing audio.
 */
public record CallLegSummary(CallLegId callLegId, CallLegSource source, long startTimestamp, long endTimestamp,
                             VoiceCallQuality voiceCallQuality, long retainedAudioSampleCount,
                             boolean ingressLoss, boolean audioTruncated, boolean winner,
                             CallEncryptionEvidence callEncryptionEvidence,
                             P25IdentityObservation p25SourceIdentity,
                             P25IdentityObservation p25DestinationIdentity,
                             List<P25IdentityObservation> p25PatchMemberIdentities)
{
    public CallLegSummary(CallLegId callLegId, CallLegSource source, long startTimestamp, long endTimestamp,
                          VoiceCallQuality voiceCallQuality, long retainedAudioSampleCount,
                          boolean ingressLoss, boolean audioTruncated, boolean winner,
                          CallEncryptionEvidence callEncryptionEvidence)
    {
        this(callLegId, source, startTimestamp, endTimestamp, voiceCallQuality, retainedAudioSampleCount,
            ingressLoss, audioTruncated, winner, callEncryptionEvidence, null, null, List.of());
    }

    public CallLegSummary(CallLegId callLegId, CallLegSource source, long startTimestamp, long endTimestamp,
                          VoiceCallQuality voiceCallQuality, long retainedAudioSampleCount,
                          boolean ingressLoss, boolean audioTruncated, boolean winner,
                          CallEncryptionEvidence callEncryptionEvidence, Identifier<?> sourceIdentifier,
                          Identifier<?> destinationIdentifier)
    {
        this(callLegId, source, startTimestamp, endTimestamp, voiceCallQuality, retainedAudioSampleCount,
            ingressLoss, audioTruncated, winner, callEncryptionEvidence, observation(sourceIdentifier, false),
            observation(destinationIdentifier, destinationIdentifier instanceof PatchGroupIdentifier),
            patchMembers(destinationIdentifier));
    }

    public CallLegSummary
    {
        if(callLegId == null)
        {
            throw new IllegalArgumentException("Call leg id is required");
        }

        source = source != null ? source : CallLegSource.UNKNOWN;
        startTimestamp = Math.max(0L, startTimestamp);
        endTimestamp = Math.max(startTimestamp, endTimestamp);
        voiceCallQuality = voiceCallQuality != null ? voiceCallQuality : VoiceCallQuality.EMPTY;
        retainedAudioSampleCount = Math.max(0L, retainedAudioSampleCount);
        p25PatchMemberIdentities = p25PatchMemberIdentities != null ?
            List.copyOf(p25PatchMemberIdentities) : List.of();
    }

    private static P25IdentityObservation observation(Identifier<?> identifier, boolean patch)
    {
        Identifier<?> primary = identifier;
        if(identifier instanceof PatchGroupIdentifier patchGroupIdentifier &&
            patchGroupIdentifier.getValue() != null)
        {
            primary = patchGroupIdentifier.getValue().getPatchGroup();
        }
        if(primary == null || primary.getProtocol() != Protocol.APCO25 ||
            !(primary.getValue() instanceof Number number))
        {
            return null;
        }

        if(primary instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
        {
            return new P25IdentityObservation(patch ? Form.PATCH_GROUP : Form.TALKGROUP, number.intValue(),
                fullyQualified.getWacn(), fullyQualified.getSystem(), fullyQualified.getTalkgroup());
        }
        if(primary instanceof FullyQualifiedRadioIdentifier fullyQualified)
        {
            return new P25IdentityObservation(Form.RADIO, number.intValue(), fullyQualified.getWacn(),
                fullyQualified.getSystem(), fullyQualified.getRadio());
        }
        if(primary.getForm() == Form.TALKGROUP || primary.getForm() == Form.RADIO)
        {
            return new P25IdentityObservation(patch ? Form.PATCH_GROUP : primary.getForm(), number.intValue(),
                null, null, null);
        }
        return null;
    }

    private static List<P25IdentityObservation> patchMembers(Identifier<?> destination)
    {
        if(!(destination instanceof PatchGroupIdentifier patchIdentifier) || patchIdentifier.getValue() == null)
        {
            return List.of();
        }

        PatchGroup patchGroup = patchIdentifier.getValue();
        return patchGroup.getPatchedTalkgroupIdentifiers().stream()
            .map(member -> observation(member, false)).filter(java.util.Objects::nonNull).distinct().toList();
    }

    /** Bounded immutable primitive evidence; no mutable identifier collection escapes the completed receiver leg. */
    public record P25IdentityObservation(Form form, int observedLocalId, Integer homeWacn,
                                         Integer homeSystemId, Integer homeIdentityId)
    {
    }
}
