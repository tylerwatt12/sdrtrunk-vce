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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastDeliveryEvidence;
import io.github.dsheirer.audio.broadcast.CompletedCallBroadcastMetadata;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.TimestampedVoiceFingerprint;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RadioResolveCallEnvelopeTest
{
    @Test
    void callAndOneReceptionUseStableSnakeCaseFacts()
    {
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.build(Path.of("call.mp3"));
        RadioResolveCallEnvelope envelope = build.envelope().withVerifiedPlacements(
            RadioResolveTestFixtures.placement(), List.of());
        JsonObject root = RadioResolveJson.GSON.toJsonTree(envelope).getAsJsonObject();
        JsonObject call = root.getAsJsonObject("call");
        JsonObject reception = root.getAsJsonObject("reception");

        assertEquals(3, root.get("schema_version").getAsInt());
        assertEquals(RadioResolveTestFixtures.SUBMISSION_ID, root.get("submission_id").getAsString());
        assertEquals(RadioResolveTestFixtures.END, root.get("completed_at_ms").getAsLong(),
            "completion must remain the actual call end, even when uploaded from backlog");
        assertEquals("p25", call.getAsJsonObject("system").get("protocol").getAsString());
        assertEquals(RadioResolveTestFixtures.SYSTEM,
            call.getAsJsonObject("system").get("system_id").getAsInt());
        assertEquals("p25_phase2", call.get("voice_protocol").getAsString());
        assertEquals(2_000L, call.get("duration_ms").getAsLong());
        assertEquals("encrypted", call.get("encryption_state").getAsString());
        assertEquals(0x84, call.get("encryption_algorithm_id").getAsInt());
        assertEquals(0x1234, call.get("encryption_key_id").getAsInt());
        assertFalse(call.has("frequency_hz"));
        assertFalse(call.has("timeslot"));
        assertEquals(RadioResolveTestFixtures.FREQUENCY, reception.get("frequency_hz").getAsLong());
        assertEquals(2, reception.get("timeslot").getAsInt());
        assertFalse(root.has("node"));
        assertFalse(root.has("receiver_id"));
        assertFalse(root.toString().contains("legacy-v2-guid"));
    }

    @Test
    void representativeCallFixtureMatchesWireContract() throws Exception
    {
        JsonObject actual = RadioResolveJson.GSON.toJsonTree(
            RadioResolveTestFixtures.readyEnvelope(Path.of("call.mp3"))).getAsJsonObject();

        try(InputStreamReader reader = new InputStreamReader(java.util.Objects.requireNonNull(getClass()
            .getResourceAsStream("/io/github/dsheirer/audio/broadcast/radioresolve/v3-call-envelope.json")),
            StandardCharsets.UTF_8))
        {
            assertEquals(JsonParser.parseReader(reader), actual);
        }
    }

    @Test
    void nbfmConventionalCallMatchesTaggedChannelFixture() throws Exception
    {
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.conventionalBuild(
            Path.of("call.mp3"), DecoderType.NBFM);

        assertTrue(build.accepted());
        assertEquals(RadioResolveCallEnvelope.HoldContext.EMPTY, build.holdContext(),
            "configured conventional placement must never wait for control-channel metadata");
        assertTrue(build.envelope().isReady());
        assertFixture("/io/github/dsheirer/audio/broadcast/radioresolve/v3-nbfm-conventional-call-envelope.json",
            build.envelope());

        JsonObject root = RadioResolveJson.GSON.toJsonTree(build.envelope()).getAsJsonObject();
        JsonObject call = root.getAsJsonObject("call");
        JsonObject destination = call.getAsJsonObject("destination");
        assertFalse(call.has("system"));
        assertEquals(2, destination.size());
        assertEquals("channel", destination.get("type").getAsString());
        assertEquals(RadioResolveTestFixtures.CONVENTIONAL_CHANNEL_ID,
            destination.get("channel_uuid").getAsString());
        assertFalse(root.toString().contains("mbe"));
    }

    @Test
    void p25ConventionalCallMatchesTaggedChannelFixtureAndRetainsDigitalParties() throws Exception
    {
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.conventionalBuild(
            Path.of("call.mp3"), DecoderType.P25_CONVENTIONAL);

        assertTrue(build.accepted());
        assertEquals(RadioResolveCallEnvelope.HoldContext.EMPTY, build.holdContext());
        assertTrue(build.envelope().isReady());
        assertFixture("/io/github/dsheirer/audio/broadcast/radioresolve/v3-p25-conventional-call-envelope.json",
            build.envelope());

        JsonObject root = RadioResolveJson.GSON.toJsonTree(build.envelope()).getAsJsonObject();
        JsonObject call = root.getAsJsonObject("call");
        JsonObject placement = root.getAsJsonObject("reception").getAsJsonObject("placement");
        assertFalse(call.has("system"));
        assertEquals("p25_phase1", call.get("voice_protocol").getAsString());
        assertEquals("talkgroup", call.getAsJsonObject("destination").get("type").getAsString());
        assertEquals("radio", call.getAsJsonObject("source").get("type").getAsString());
        assertEquals(0x293, placement.get("nac").getAsInt());
        assertTrue(root.getAsJsonObject("reception").has("fingerprints"));
    }

    @Test
    void amConventionalCallUsesAmForVoiceAndPlacementProtocols()
    {
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.conventionalBuild(
            Path.of("call.mp3"), DecoderType.AM);

        assertTrue(build.accepted());
        assertTrue(build.envelope().isReady());
        assertEquals("am", build.envelope().call().voiceProtocol());
        assertEquals("am", build.envelope().reception().placement().protocol());
        assertFalse(RadioResolveJson.GSON.toJsonTree(build.envelope()).getAsJsonObject()
            .getAsJsonObject("reception").has("timeslot"));
    }

    @Test
    void analogConventionalCallDoesNotProjectAnInBandRadioAsACallSource()
    {
        AudioRecording base = RadioResolveTestFixtures.conventionalRecording(Path.of("call.mp3"), DecoderType.NBFM);
        List<Identifier> identifierList = new ArrayList<>(base.getIdentifierCollection().getIdentifiers());
        identifierList.add(APCO25FullyQualifiedRadioIdentifier.createFrom(700_001,
            RadioResolveTestFixtures.WACN, RadioResolveTestFixtures.SYSTEM, 900_001));
        IdentifierCollection identifiers = new IdentifierCollection(identifierList);
        AudioCallSnapshot original = base.getCompletedCallMetadata().snapshot();
        AudioCallSnapshot snapshot = new AudioCallSnapshot(original.callId(), original.linkedCallId(),
            original.aliasList(), identifiers, original.broadcastChannels(), original.startTimestamp(),
            original.lastActivityTimestamp(), original.burstCount(), original.burstGeneration(),
            original.lastBurstStartTimestamp(), original.lastBurstEndTimestamp(), original.burstActive(),
            original.complete(), original.encryptionState(), original.recordAudio(), original.recordingMetadata(),
            original.voiceCallQuality(), original.callLegId(), original.callLegSource(),
            original.callEncryptionEvidence());
        AudioRecording recording = new AudioRecording(base.getPath(), base.getBroadcastChannels(), identifiers,
            base.getStartTime(), base.getRecordingLength(), base.getDeliveryEvidence(),
            new CompletedCallBroadcastMetadata(snapshot,
                base.getCompletedCallMetadata().callLegSummaries()));

        RadioResolveCallEnvelope.BuildResult build = RadioResolveCallEnvelope.create(recording,
            RadioResolveTestFixtures.SUBMISSION_ID);
        assertTrue(build.accepted());
        JsonObject root = RadioResolveJson.GSON.toJsonTree(build.envelope()).getAsJsonObject();
        assertFalse(root.getAsJsonObject("call").has("source"));
        assertFalse(root.getAsJsonObject("reception").has("source"));
    }

    @Test
    void conventionalCallsRequireCanonicalUuidAndConfiguredPlacementNames()
    {
        CallLegSource invalidId = RadioResolveTestFixtures.source(DecoderType.NBFM, "not-a-uuid");
        RadioResolveCallEnvelope.BuildResult badId = RadioResolveCallEnvelope.create(
            RadioResolveTestFixtures.conventionalRecording(Path.of("call.mp3"), DecoderType.NBFM, invalidId,
                RadioResolveTestFixtures.conventionalMetadata(DecoderType.NBFM),
                RadioResolveTestFixtures.FREQUENCY, List.of()), RadioResolveTestFixtures.SUBMISSION_ID);
        assertFalse(badId.accepted());
        assertEquals("conventional channel RadioResolve ID is not a valid UUID", badId.rejectionReason());

        AudioCallRecordingMetadata labels = RadioResolveTestFixtures.conventionalMetadata(DecoderType.NBFM);
        AudioCallRecordingMetadata missingSystem = new AudioCallRecordingMetadata(null, labels.siteName(),
            labels.radioResolveId(), labels.channelName(), labels.channelIdentity(), labels.aliasListName(),
            labels.destinationProtocol(), labels.destinationValue(), labels.destinationIdentity(),
            labels.destinationAlias(), labels.destinationDescription(), labels.destinationGroup(),
            labels.destinationMatcherIdentity(), labels.destinationRecordEnabled(), labels.sourceProtocol(),
            labels.sourceValue(), labels.sourceAlias(), labels.sourceDescription(), labels.sourceGroup());
        RadioResolveCallEnvelope.BuildResult missingName = RadioResolveCallEnvelope.create(
            RadioResolveTestFixtures.conventionalRecording(Path.of("call.mp3"), DecoderType.NBFM,
                RadioResolveTestFixtures.source(DecoderType.NBFM,
                    RadioResolveTestFixtures.CONVENTIONAL_CHANNEL_ID), missingSystem,
                RadioResolveTestFixtures.FREQUENCY, List.of()), RadioResolveTestFixtures.SUBMISSION_ID);
        assertFalse(missingName.accepted());
        assertEquals("conventional channel system and site names are unavailable",
            missingName.rejectionReason());
    }

    @Test
    void conventionalChannelUuidAcceptsConfiguredUppercaseAndSerializesCanonically()
    {
        CallLegSource uppercase = RadioResolveTestFixtures.source(DecoderType.NBFM,
            RadioResolveTestFixtures.CONVENTIONAL_CHANNEL_ID.toUpperCase(java.util.Locale.ROOT));
        RadioResolveCallEnvelope.BuildResult build = RadioResolveCallEnvelope.create(
            RadioResolveTestFixtures.conventionalRecording(Path.of("call.mp3"), DecoderType.NBFM, uppercase,
                RadioResolveTestFixtures.conventionalMetadata(DecoderType.NBFM),
                RadioResolveTestFixtures.FREQUENCY, List.of()), RadioResolveTestFixtures.SUBMISSION_ID);

        assertTrue(build.accepted());
        assertEquals(RadioResolveTestFixtures.CONVENTIONAL_CHANNEL_ID,
            build.envelope().reception().placement().channelUuid());
        assertEquals(RadioResolveTestFixtures.CONVENTIONAL_CHANNEL_ID,
            build.envelope().call().destination().channelUuid());
    }

    @Test
    void conventionalCallsRequireConventionalSelectedSourceAndPositiveFrequency()
    {
        CallLegSource trunked = new CallLegSource(DecoderType.NBFM,
            RadioResolveTestFixtures.CONVENTIONAL_CONFIGURATION_ID, "County Fire Dispatch",
            RadioResolveTestFixtures.CONVENTIONAL_CHANNEL_ID, 1L, null, TrunkedIdentityDomain.STANDARD,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED, false, null, 0L, 0L);
        RadioResolveCallEnvelope.BuildResult wrongKind = RadioResolveCallEnvelope.create(
            RadioResolveTestFixtures.conventionalRecording(Path.of("call.mp3"), DecoderType.NBFM, trunked,
                RadioResolveTestFixtures.conventionalMetadata(DecoderType.NBFM),
                RadioResolveTestFixtures.FREQUENCY, List.of()), RadioResolveTestFixtures.SUBMISSION_ID);
        assertFalse(wrongKind.accepted());
        assertEquals("selected call source is not a conventional channel", wrongKind.rejectionReason());

        RadioResolveCallEnvelope.BuildResult noFrequency = RadioResolveCallEnvelope.create(
            RadioResolveTestFixtures.conventionalRecording(Path.of("call.mp3"), DecoderType.NBFM,
                RadioResolveTestFixtures.source(DecoderType.NBFM,
                    RadioResolveTestFixtures.CONVENTIONAL_CHANNEL_ID),
                RadioResolveTestFixtures.conventionalMetadata(DecoderType.NBFM), null, List.of()),
            RadioResolveTestFixtures.SUBMISSION_ID);
        assertFalse(noFrequency.accepted());
        assertEquals("conventional call placement does not match the selected audio leg",
            noFrequency.rejectionReason());
    }

    @Test
    void conventionalCallsRequireExactlyOneSelectedLegWithMatchingCanonicalChannel()
    {
        AudioRecording base = RadioResolveTestFixtures.conventionalRecording(Path.of("call.mp3"), DecoderType.NBFM);
        CallLegSummary winner = base.getCompletedCallMetadata().callLegSummaries().getFirst();
        CallLegSummary secondWinner = new CallLegSummary(new CallLegId(31L, 41L, 0), winner.source(),
            winner.startTimestamp(), winner.endTimestamp(), winner.voiceCallQuality(),
            winner.retainedAudioSampleCount(), winner.ingressLoss(), winner.audioTruncated(), true,
            winner.callEncryptionEvidence(), winner.p25SourceIdentity(), winner.p25DestinationIdentity(),
            winner.p25PatchMemberIdentities(), winner.voiceFrameFingerprints(), winner.frequencyHz(),
            winner.timeslot());
        RadioResolveCallEnvelope.BuildResult duplicateWinner = RadioResolveCallEnvelope.create(
            withSummaries(base, List.of(winner, secondWinner)), RadioResolveTestFixtures.SUBMISSION_ID);
        assertFalse(duplicateWinner.accepted());

        CallLegSource otherChannel = RadioResolveTestFixtures.source(DecoderType.NBFM,
            "87654321-cba9-4fed-8876-543210fedcba");
        CallLegSummary mismatched = new CallLegSummary(winner.callLegId(), otherChannel,
            winner.startTimestamp(), winner.endTimestamp(), winner.voiceCallQuality(),
            winner.retainedAudioSampleCount(), winner.ingressLoss(), winner.audioTruncated(), true,
            winner.callEncryptionEvidence(), winner.p25SourceIdentity(), winner.p25DestinationIdentity(),
            winner.p25PatchMemberIdentities(), winner.voiceFrameFingerprints(), winner.frequencyHz(),
            winner.timeslot());
        RadioResolveCallEnvelope.BuildResult mismatch = RadioResolveCallEnvelope.create(
            withSummaries(base, List.of(mismatched)), RadioResolveTestFixtures.SUBMISSION_ID);
        assertFalse(mismatch.accepted());
        assertEquals("conventional call placement does not match the selected audio leg",
            mismatch.rejectionReason());
    }

    @Test
    void serverClockOffsetMovesEveryAbsoluteCallTimeExactlyOncePerEnvelopeTransformation()
    {
        RadioResolveCallEnvelope original = RadioResolveTestFixtures.readyEnvelope(Path.of("call.mp3"));
        RadioResolveCallEnvelope adjusted = original.withTimestampOffset(275L);

        assertEquals(original.completedAtMs() + 275L, adjusted.completedAtMs());
        assertEquals(original.call().startedAtMs() + 275L, adjusted.call().startedAtMs());
        assertEquals(original.call().endedAtMs() + 275L, adjusted.call().endedAtMs());
        assertEquals(original.call().durationMs(), adjusted.call().durationMs());

        for(int index = 0; index < original.reception().physicalLegs().size(); index++)
        {
            RadioResolveCallEnvelope.PhysicalLeg before = original.reception().physicalLegs().get(index);
            RadioResolveCallEnvelope.PhysicalLeg after = adjusted.reception().physicalLegs().get(index);
            assertEquals(before.startedAtMs() + 275L, after.startedAtMs());
            assertEquals(before.endedAtMs() + 275L, after.endedAtMs());
        }

        assertEquals(original.reception().fingerprints(), adjusted.reception().fingerprints(),
            "relative voice-frame offsets must not move with the receiver wall clock");
    }

    @Test
    void selectedLegCarriesFullyQualifiedPartiesQualityAndUnionEvidence()
    {
        RadioResolveCallEnvelope envelope = RadioResolveTestFixtures.readyEnvelope(Path.of("call.mp3"));
        RadioResolveCallEnvelope.Party destination = envelope.reception().destination();
        RadioResolveCallEnvelope.Party source = envelope.reception().source();

        assertEquals(4_400L, destination.localId());
        assertEquals(12_345L, destination.canonicalId());
        assertEquals(0xABCDE, destination.homeWacn());
        assertEquals(0x321, destination.homeSystemId());
        assertEquals(700_001L, source.localId());
        assertEquals(900_001L, source.canonicalId());
        assertEquals(destination, envelope.call().destination());
        assertEquals(source, envelope.call().source());
        assertEquals(90L, envelope.reception().quality().decodedFrames());
        assertEquals(2L, envelope.reception().quality().repeatedFrames());
        assertEquals(3L, envelope.reception().quality().concealedFrames());
        assertEquals(15L, envelope.reception().quality().missingFrames());
        assertEquals(110L, envelope.reception().quality().expectedFrames());
        assertEquals(12L, envelope.reception().quality().fecErrors());
        assertEquals(7_200L, envelope.reception().quality().fecProtectedBits());
        assertTrue(envelope.reception().quality().ingressLoss());
        assertTrue(envelope.reception().quality().audioTruncated());

        List<RadioResolveCallEnvelope.FingerprintFrame> frames = envelope.reception().fingerprints().frames();
        assertEquals(List.of(0L, 20L, 60L), frames.stream()
            .map(RadioResolveCallEnvelope.FingerprintFrame::offsetMs).toList());
        assertEquals(List.of("1111111111111111", "2222222222222222", "4444444444444444"),
            frames.stream().map(RadioResolveCallEnvelope.FingerprintFrame::digest).toList());
        assertEquals(2, envelope.reception().physicalLegs().size());
        assertEquals(5L, envelope.reception().physicalLegs().get(0).quality().missingFrames());
        assertEquals(100L, envelope.reception().physicalLegs().get(0).quality().expectedFrames());
        assertEquals(RadioResolveTestFixtures.FREQUENCY,
            envelope.reception().physicalLegs().get(0).frequencyHz());
        assertEquals(855_012_500L, envelope.reception().physicalLegs().get(1).frequencyHz());
        JsonObject receptionJson = RadioResolveJson.GSON.toJsonTree(envelope.reception()).getAsJsonObject();
        assertFalse(receptionJson.getAsJsonArray("physical_legs").get(0).getAsJsonObject().has("fingerprints"),
            "the bounded union is stored once per reception, not repeated for every physical leg");
    }

    @Test
    void unavailableQualityMeasurementsAreOmittedInsteadOfSerializedAsZeros()
    {
        JsonObject root = RadioResolveJson.GSON.toJsonTree(
            RadioResolveTestFixtures.readyEnvelope(Path.of("call.mp3"))).getAsJsonObject();
        JsonObject unavailable = root.getAsJsonObject("reception").getAsJsonArray("physical_legs")
            .get(1).getAsJsonObject().getAsJsonObject("quality");

        assertFalse(unavailable.has("decoded_frames"));
        assertFalse(unavailable.has("expected_frames"));
        assertFalse(unavailable.has("fec_errors"));
        assertFalse(unavailable.has("fec_protected_bits"));
        assertEquals(8_000L, unavailable.get("retained_audio_samples").getAsLong());
        assertTrue(unavailable.get("ingress_loss").getAsBoolean());
    }

    @Test
    void inconsistentFecCountersAreOmittedWithoutDiscardingTheCall()
    {
        AudioRecording base = RadioResolveTestFixtures.recording(Path.of("call.mp3"));
        CompletedCallBroadcastMetadata metadata = base.getCompletedCallMetadata();
        AudioCallSnapshot snapshot = metadata.snapshot();
        VoiceCallQuality inconsistent = new VoiceCallQuality(90, 2, 3, 15, 11, 10);
        AudioCallSnapshot updatedSnapshot = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), snapshot.identifierCollection(), snapshot.broadcastChannels(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.burstCount(),
            snapshot.burstGeneration(), snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(),
            snapshot.burstActive(), snapshot.complete(), snapshot.encryptionState(), snapshot.recordAudio(),
            snapshot.recordingMetadata(), inconsistent, snapshot.callLegId(), snapshot.callLegSource(),
            snapshot.callEncryptionEvidence());
        List<CallLegSummary> summaries = metadata.callLegSummaries().stream().map(summary -> summary.winner() ?
            new CallLegSummary(summary.callLegId(), summary.source(), summary.startTimestamp(),
                summary.endTimestamp(), inconsistent, summary.retainedAudioSampleCount(), summary.ingressLoss(),
                summary.audioTruncated(), true, summary.callEncryptionEvidence(), summary.p25SourceIdentity(),
                summary.p25DestinationIdentity(), summary.p25PatchMemberIdentities(),
                summary.voiceFrameFingerprints(), summary.frequencyHz(), summary.timeslot()) : summary).toList();
        AudioRecording recording = new AudioRecording(base.getPath(), base.getBroadcastChannels(),
            base.getIdentifierCollection(), base.getStartTime(), base.getRecordingLength(), base.getDeliveryEvidence(),
            new CompletedCallBroadcastMetadata(updatedSnapshot, summaries));

        RadioResolveCallEnvelope.BuildResult result = RadioResolveCallEnvelope.create(recording,
            RadioResolveTestFixtures.SUBMISSION_ID);
        assertTrue(result.accepted());
        JsonObject root = RadioResolveJson.GSON.toJsonTree(result.envelope()).getAsJsonObject();
        JsonObject quality = root.getAsJsonObject("reception").getAsJsonObject("quality");
        JsonObject selectedLegQuality = root.getAsJsonObject("reception").getAsJsonArray("physical_legs")
            .get(0).getAsJsonObject().getAsJsonObject("quality");

        assertEquals(90L, quality.get("decoded_frames").getAsLong());
        assertEquals(110L, quality.get("expected_frames").getAsLong());
        assertFalse(quality.has("fec_errors"));
        assertFalse(quality.has("fec_protected_bits"));
        assertFalse(selectedLegQuality.has("fec_errors"));
        assertFalse(selectedLegQuality.has("fec_protected_bits"));
    }

    @Test
    void configuredLabelsAndTalkerAliasStayOutsideImmutableCallFacts()
    {
        JsonObject root = RadioResolveJson.GSON.toJsonTree(
            RadioResolveTestFixtures.readyEnvelope(Path.of("call.mp3"))).getAsJsonObject();
        String immutableFacts = root.getAsJsonObject("call").toString();

        assertFalse(immutableFacts.contains("Dispatch"));
        assertFalse(immutableFacts.contains("Engine 7"));
        assertEquals("Dispatch", root.getAsJsonObject("observations")
            .getAsJsonObject("configured_destination").get("observed_name").getAsString());
        assertEquals("Engine 7", root.getAsJsonObject("observations")
            .getAsJsonObject("configured_source").get("observed_name").getAsString());
        assertEquals("OTA UNIT 7", root.getAsJsonObject("observations").get("talker_alias").getAsString());
    }

    @Test
    void configuredObservationsAreBoundedForCanonicalColumns()
    {
        String oversized = "x".repeat(300);
        RadioResolveCallEnvelope.LabelObservation label = new RadioResolveCallEnvelope.LabelObservation(oversized,
            oversized, oversized);
        RadioResolveCallEnvelope.MetadataObservations observations =
            new RadioResolveCallEnvelope.MetadataObservations(label, label, oversized);

        assertEquals(255, label.observedName().length());
        assertEquals(255, label.observedDescription().length());
        assertEquals(255, label.observedCategory().length());
        assertEquals(255, observations.talkerAlias().length());
    }

    @Test
    void wireReadinessRequiresSelectedLegPlacementAndFrequency()
    {
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.build(Path.of("call.mp3"));
        RadioResolveCallEnvelope rootPlacementOnly = build.envelope().withVerifiedPlacements(
            RadioResolveTestFixtures.placement(), List.of());

        assertFalse(rootPlacementOnly.isReady(),
            "the server requires the selected physical leg to carry matching placement and frequency");

        RadioResolveCallEnvelope.PlacementLookup selectedLookup = build.holdContext().selected();
        int winnerIndex = selectedLookup.physicalLegIndex();
        RadioResolveCallEnvelope ready = build.envelope().withVerifiedPlacements(
            RadioResolveTestFixtures.placement(),
            List.of(new RadioResolveCallEnvelope.IndexedPlacement(winnerIndex, RadioResolveTestFixtures.placement())));
        assertTrue(ready.isReady());
    }

    @Test
    void fingerprintEvidenceIsDefensivelyBoundedToTwoMinutes()
    {
        List<RadioResolveCallEnvelope.FingerprintFrame> frames = new ArrayList<>();

        for(int index = 0; index < RadioResolveCallEnvelope.MAXIMUM_FINGERPRINTS_PER_RECEPTION + 5; index++)
        {
            frames.add(new RadioResolveCallEnvelope.FingerprintFrame(index * 20L,
                String.format("%016x", index + 1L)));
        }

        RadioResolveCallEnvelope.Fingerprints bounded = new RadioResolveCallEnvelope.Fingerprints(
            RadioResolveCallEnvelope.FINGERPRINT_METHOD, frames);
        assertEquals(6_000, bounded.frames().size());
        assertEquals(119_980L, bounded.frames().getLast().offsetMs());
    }

    @Test
    void patchDestinationPreservesFullyQualifiedPrimaryTalkgroupAndIssiMembers()
    {
        AudioRecording baseRecording = RadioResolveTestFixtures.recording(Path.of("call.mp3"));
        AudioCallSnapshot base = baseRecording.getCompletedCallMetadata().snapshot();
        PatchGroup patch = new PatchGroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(4_500, 0xABCDE,
            0x321, 13_000), 7);
        patch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(4_401, 0xABCDE,
            0x321, 12_346));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createTo(700_002,
            RadioResolveTestFixtures.WACN, RadioResolveTestFixtures.SYSTEM, 900_002));
        Identifier<?> destination = APCO25PatchGroup.create(patch);
        Identifier<?> source = base.identifierCollection().getFromIdentifier();
        IdentifierCollection identifiers = new IdentifierCollection(List.of(destination, source,
            FrequencyConfigurationIdentifier.create(RadioResolveTestFixtures.FREQUENCY)));
        AudioCallSnapshot snapshot = new AudioCallSnapshot(base.callId(), base.linkedCallId(), base.aliasList(),
            identifiers, base.broadcastChannels(), base.startTimestamp(), base.lastActivityTimestamp(),
            base.burstCount(), base.burstGeneration(), base.lastBurstStartTimestamp(), base.lastBurstEndTimestamp(),
            base.burstActive(), base.complete(), base.encryptionState(), base.recordAudio(), base.recordingMetadata(),
            base.voiceCallQuality(), base.callLegId(), base.callLegSource(), base.callEncryptionEvidence());
        CallLegSummary summary = new CallLegSummary(snapshot.callLegId(), snapshot.callLegSource(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.voiceCallQuality(), 16_000,
            false, false, true, snapshot.callEncryptionEvidence(), source, destination,
            List.of(new TimestampedVoiceFingerprint(0x5555555555555555L, snapshot.startTimestamp())),
            RadioResolveTestFixtures.FREQUENCY, 2);
        AudioRecording recording = new AudioRecording(Path.of("call.mp3"), List.of(), identifiers,
            snapshot.startTimestamp(), 2_000L, BroadcastDeliveryEvidence.EMPTY,
            new CompletedCallBroadcastMetadata(snapshot, List.of(summary)));
        RadioResolveCallEnvelope envelope = RadioResolveCallEnvelope.create(recording,
            RadioResolveTestFixtures.SUBMISSION_ID).envelope().withVerifiedPlacements(
            RadioResolveTestFixtures.placement(), List.of());
        RadioResolveCallEnvelope.Party projected = envelope.reception().destination();

        assertEquals("patch_group", projected.type());
        assertEquals(4_500L, projected.localId());
        assertEquals(13_000L, projected.canonicalId());
        assertEquals(2, projected.members().size());
        assertEquals(List.of("talkgroup", "radio"), projected.members().stream()
            .map(RadioResolveCallEnvelope.Party::type).toList());
        assertEquals(List.of(12_346L, 900_002L), projected.members().stream()
            .map(RadioResolveCallEnvelope.Party::canonicalId).toList());
    }

    @Test
    void rejectsFullyQualifiedDestinationWithoutAPositiveObservedLocalIdentity()
    {
        AudioRecording base = RadioResolveTestFixtures.recording(Path.of("call.mp3"));
        CompletedCallBroadcastMetadata metadata = base.getCompletedCallMetadata();
        List<CallLegSummary> summaries = metadata.callLegSummaries().stream().map(summary -> summary.winner() ?
            new CallLegSummary(summary.callLegId(), summary.source(), summary.startTimestamp(),
                summary.endTimestamp(), summary.voiceCallQuality(), summary.retainedAudioSampleCount(),
                summary.ingressLoss(), summary.audioTruncated(), true, summary.callEncryptionEvidence(),
                summary.p25SourceIdentity(),
                new CallLegSummary.P25IdentityObservation(Form.TALKGROUP, 0, 0xABCDE, 0x321, 12_345),
                summary.p25PatchMemberIdentities(), summary.voiceFrameFingerprints(), summary.frequencyHz(),
                summary.timeslot()) : summary).toList();
        AudioRecording recording = new AudioRecording(base.getPath(), base.getBroadcastChannels(),
            base.getIdentifierCollection(), base.getStartTime(), base.getRecordingLength(), base.getDeliveryEvidence(),
            new CompletedCallBroadcastMetadata(metadata.snapshot(), summaries));

        RadioResolveCallEnvelope.BuildResult result = RadioResolveCallEnvelope.create(recording,
            RadioResolveTestFixtures.SUBMISSION_ID);

        assertFalse(result.accepted());
        assertEquals("call destination local identity is unavailable", result.rejectionReason());
    }

    @Test
    void rejectsPathologicalLinkedCallLongerThanTheSharedOneDayContract()
    {
        AudioRecording recording = RadioResolveTestFixtures.recording(Path.of("call.mp3"),
            RadioResolveTestFixtures.START, RadioResolveCallEnvelope.MAXIMUM_CALL_DURATION_MILLISECONDS + 1L);

        RadioResolveCallEnvelope.BuildResult result = RadioResolveCallEnvelope.create(recording,
            RadioResolveTestFixtures.SUBMISSION_ID);

        assertFalse(result.accepted());
        assertEquals("call timing is invalid", result.rejectionReason());
    }

    private static AudioRecording withSummaries(AudioRecording base, List<CallLegSummary> summaries)
    {
        CompletedCallBroadcastMetadata metadata = base.getCompletedCallMetadata();
        return new AudioRecording(base.getPath(), base.getBroadcastChannels(), base.getIdentifierCollection(),
            base.getStartTime(), base.getRecordingLength(), base.getDeliveryEvidence(),
            new CompletedCallBroadcastMetadata(metadata.snapshot(), summaries));
    }

    private void assertFixture(String resource, RadioResolveCallEnvelope envelope) throws Exception
    {
        JsonObject actual = RadioResolveJson.GSON.toJsonTree(envelope).getAsJsonObject();

        try(InputStreamReader reader = new InputStreamReader(java.util.Objects.requireNonNull(getClass()
            .getResourceAsStream(resource)), StandardCharsets.UTF_8))
        {
            assertEquals(JsonParser.parseReader(reader), actual);
        }
    }
}
