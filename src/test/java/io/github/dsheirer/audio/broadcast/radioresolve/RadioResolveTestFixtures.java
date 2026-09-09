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
package io.github.dsheirer.audio.broadcast.radioresolve;

import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastDeliveryEvidence;
import io.github.dsheirer.audio.broadcast.CompletedCallBroadcastMetadata;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionEvidence;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.TimestampedVoiceFingerprint;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class RadioResolveTestFixtures
{
    static final String SUBMISSION_ID = "11111111-2222-4333-8444-555555555555";
    static final String CHANNEL_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    static final String CONVENTIONAL_CHANNEL_ID = "12345678-9abc-4def-8123-456789abcdef";
    static final String CONVENTIONAL_CONFIGURATION_ID = "fedcba98-7654-4321-8fed-cba987654321";
    static final long START = 1_700_000_000_000L;
    static final long END = START + 2_000L;
    static final int WACN = 0xBEE00;
    static final int SYSTEM = 0x348;
    static final int RFSS = 2;
    static final int SITE = 1;
    static final long FREQUENCY = 854_087_500L;

    private RadioResolveTestFixtures()
    {
    }

    static AudioRecording recording(Path path)
    {
        return recording(path, START);
    }

    static AudioRecording recording(Path path, long start)
    {
        return recording(path, start, 2_000L);
    }

    static AudioRecording recording(Path path, long start, long duration)
    {
        long end = start + duration;
        Identifier<?> destination = APCO25FullyQualifiedTalkgroupIdentifier.createTo(4_400, 0xABCDE, 0x321,
            12_345);
        Identifier<?> source = APCO25FullyQualifiedRadioIdentifier.createFrom(700_001, WACN, SYSTEM, 900_001);
        List<Identifier> identifierList = new ArrayList<>();
        identifierList.add(destination);
        identifierList.add(source);
        identifierList.add(FrequencyConfigurationIdentifier.create(FREQUENCY));
        identifierList.add(P25TalkerAliasIdentifier.create("OTA UNIT 7"));
        IdentifierCollection identifiers = new IdentifierCollection(identifierList);
        CallLegSource winningSource = source(17L, 3L);
        AudioCallId callId = new AudioCallId(10L, 20L, 2);
        CallLegId winningLegId = CallLegId.from(callId);
        VoiceCallQuality resolvedQuality = new VoiceCallQuality(90, 2, 3, 15, 12, 7_200);
        VoiceCallQuality winnerQuality = new VoiceCallQuality(90, 2, 3, 5, 12, 7_200);
        AudioCallRecordingMetadata labels = new AudioCallRecordingMetadata("Old display system",
            "Old display site", "legacy-v2-guid", "Control", CHANNEL_ID, "County",
            "APCO25", "4400", "legacy identity", "Dispatch", "Primary dispatch", "Fire",
            "legacy matcher", true, "APCO25", "700001", "Engine 7", "Portable", "Operations");
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null, identifiers, Set.of(), start, end,
            1, 1, start, end, false, true, CallEncryptionState.ENCRYPTED, false, labels, resolvedQuality,
            winningLegId, winningSource, new CallEncryptionEvidence(0x84, 0x1234, 99L));

        CallLegSummary winner = new CallLegSummary(winningLegId, winningSource, start, end, winnerQuality, 16_000,
            true, true, true, snapshot.callEncryptionEvidence(), source, destination,
            List.of(new TimestampedVoiceFingerprint(0x1111111111111111L, start),
                new TimestampedVoiceFingerprint(0x2222222222222222L, start + 20L),
                new TimestampedVoiceFingerprint(0x2222222222222222L, start + 20L),
                new TimestampedVoiceFingerprint(0x7777777777777777L, start - 20L),
                new TimestampedVoiceFingerprint(0x3333333333333333L, end + 100L)),
            FREQUENCY, 2);
        CallLegSource losingSource = new CallLegSource(DecoderType.P25_PHASE2,
            "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff", "Traffic B", null, 2L,
            new P25SiteIdentity(WACN, SYSTEM, RFSS, SITE), TrunkedIdentityDomain.STANDARD,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED, true, null, 22L, 4L);
        CallLegSummary loser = new CallLegSummary(new CallLegId(11L, 21L, 1), losingSource, start + 40L,
            end - 20L, VoiceCallQuality.EMPTY, 8_000, true, true, false, null, source, destination,
            List.of(new TimestampedVoiceFingerprint(0x2222222222222222L, start + 20L),
                new TimestampedVoiceFingerprint(0x4444444444444444L, start + 60L)),
            855_012_500L, 1);
        CompletedCallBroadcastMetadata completed = new CompletedCallBroadcastMetadata(snapshot,
            List.of(loser, winner));
        return new AudioRecording(path, List.of(), identifiers, start, duration,
            BroadcastDeliveryEvidence.EMPTY, completed);
    }

    static RadioResolveCallEnvelope.BuildResult build(Path path)
    {
        return RadioResolveCallEnvelope.create(recording(path), SUBMISSION_ID);
    }

    static RadioResolveCallEnvelope.BuildResult build(Path path, long start, String submissionId)
    {
        return RadioResolveCallEnvelope.create(recording(path, start), submissionId);
    }

    static AudioRecording conventionalRecording(Path path, DecoderType decoder)
    {
        return conventionalRecording(path, decoder, source(decoder, CONVENTIONAL_CHANNEL_ID),
            conventionalMetadata(decoder), FREQUENCY, List.of());
    }

    static AudioRecording conventionalRecording(Path path, DecoderType decoder, CallLegSource selectedSource,
                                                 AudioCallRecordingMetadata recordingMetadata,
                                                 Long selectedFrequency, List<CallLegSummary> replacementSummaries)
    {
        boolean p25 = decoder == DecoderType.P25_CONVENTIONAL;
        Identifier<?> destination = p25 ? APCO25FullyQualifiedTalkgroupIdentifier.createTo(4_400, 0xABCDE,
            0x321, 12_345) : null;
        Identifier<?> source = p25 ? APCO25FullyQualifiedRadioIdentifier.createFrom(700_001, WACN, SYSTEM,
            900_001) : null;
        List<Identifier> identifierList = new ArrayList<>();

        if(destination != null)
        {
            identifierList.add(destination);
        }

        if(source != null)
        {
            identifierList.add(source);
        }

        if(selectedFrequency != null && selectedFrequency > 0L)
        {
            identifierList.add(FrequencyConfigurationIdentifier.create(selectedFrequency));
        }

        if(p25)
        {
            identifierList.add(APCO25Nac.create(0x293));
            identifierList.add(P25TalkerAliasIdentifier.create("CONVENTIONAL UNIT 7"));
        }

        IdentifierCollection identifiers = new IdentifierCollection(identifierList);
        AudioCallId callId = new AudioCallId(30L, 40L, 0);
        CallLegId callLegId = CallLegId.from(callId);
        VoiceCallQuality quality = p25 ? new VoiceCallQuality(80, 1, 2, 3, 4, 3_200) : VoiceCallQuality.EMPTY;
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null, identifiers, Set.of(), START, END,
            1, 1, START, END, false, true, CallEncryptionState.CLEAR, false, recordingMetadata, quality,
            callLegId, selectedSource, null);
        List<TimestampedVoiceFingerprint> fingerprints = p25 ?
            List.of(new TimestampedVoiceFingerprint(0x5555555555555555L, START),
                new TimestampedVoiceFingerprint(0x6666666666666666L, START + 20L)) : List.of();
        CallLegSummary selected = new CallLegSummary(callLegId, selectedSource, START, END, quality, 16_000,
            false, false, true, null, source, destination, fingerprints, selectedFrequency, null);
        List<CallLegSummary> summaries = replacementSummaries != null && !replacementSummaries.isEmpty() ?
            replacementSummaries : List.of(selected);
        return new AudioRecording(path, List.of(), identifiers, START, END - START,
            BroadcastDeliveryEvidence.EMPTY, new CompletedCallBroadcastMetadata(snapshot, summaries));
    }

    static CallLegSource source(DecoderType decoder, String radioResolveId)
    {
        return new CallLegSource(decoder, CONVENTIONAL_CONFIGURATION_ID, "County Fire Dispatch", radioResolveId,
            1L, null, TrunkedIdentityDomain.STANDARD,
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, false, null, 0L, 0L);
    }

    static AudioCallRecordingMetadata conventionalMetadata(DecoderType decoder)
    {
        boolean p25 = decoder == DecoderType.P25_CONVENTIONAL;
        return new AudioCallRecordingMetadata("County Public Safety", "North Conventional",
            CONVENTIONAL_CHANNEL_ID, "County Fire Dispatch", CONVENTIONAL_CONFIGURATION_ID, "County",
            p25 ? "APCO25" : null, p25 ? "4400" : null, p25 ? "4400" : null,
            p25 ? "Dispatch" : null, p25 ? "Primary dispatch" : null, p25 ? "Fire" : null,
            p25 ? "APCO25:4400" : null, true, p25 ? "APCO25" : null, p25 ? "700001" : null,
            p25 ? "Engine 7" : null, p25 ? "Portable" : null, p25 ? "Operations" : null);
    }

    static RadioResolveCallEnvelope.BuildResult conventionalBuild(Path path, DecoderType decoder)
    {
        return RadioResolveCallEnvelope.create(conventionalRecording(path, decoder), SUBMISSION_ID);
    }

    static RadioResolveCallEnvelope readyEnvelope(Path path)
    {
        return readyEnvelope(build(path));
    }

    static RadioResolveCallEnvelope readyEnvelope(Path path, long start, String submissionId)
    {
        return readyEnvelope(build(path, start, submissionId));
    }

    private static RadioResolveCallEnvelope readyEnvelope(RadioResolveCallEnvelope.BuildResult build)
    {
        return build.envelope().withVerifiedPlacements(placement(),
            List.of(new RadioResolveCallEnvelope.IndexedPlacement(0, placement()),
                new RadioResolveCallEnvelope.IndexedPlacement(1,
                    new RadioResolveCallEnvelope.Placement("p25", WACN, SYSTEM, 3, 4, 0x348))));
    }

    static CallLegSource source(long incarnation, long tuningGeneration)
    {
        return new CallLegSource(DecoderType.P25_PHASE2, CHANNEL_ID, "Traffic A", "legacy-v2-guid", 1L,
            new P25SiteIdentity(0xAAAAA, 0x111, 9, 9), TrunkedIdentityDomain.STANDARD,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED, true, null, incarnation, tuningGeneration);
    }

    static RadioResolveCallEnvelope.Placement placement()
    {
        return new RadioResolveCallEnvelope.Placement("p25", WACN, SYSTEM, RFSS, SITE, 0x348);
    }
}
