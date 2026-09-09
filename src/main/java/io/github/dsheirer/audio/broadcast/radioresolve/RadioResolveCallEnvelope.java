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
import io.github.dsheirer.audio.broadcast.CompletedCallBroadcastMetadata;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionEvidence;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.TimestampedVoiceFingerprint;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.ConfigurationLongIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned RadioResolve v3 call and one receiver-reception envelope. Display text is confined to optional canonical
 * metadata observations and never becomes an immutable call fact.
 */
public record RadioResolveCallEnvelope(int schemaVersion, String submissionId, long completedAtMs,
                                       String agentVersion, CallFacts call, Reception reception,
                                       MetadataObservations observations)
{
    public static final int SCHEMA_VERSION = 3;
    public static final int QUALITY_METHOD_VERSION = 1;
    /** Exact FNV-1a over the received or successfully decrypted P25 vocoder-frame bits. */
    public static final String FINGERPRINT_METHOD = "p25_vocoder_fnv1a64_v1";
    public static final int MAXIMUM_FINGERPRINTS_PER_RECEPTION = 6_000;
    /** Bounds pathological stuck calls while allowing every normal 60-second linked continuation for a full day. */
    public static final long MAXIMUM_CALL_DURATION_MILLISECONDS = 24L * 60L * 60L * 1_000L;

    public RadioResolveCallEnvelope
    {
        if(schemaVersion != SCHEMA_VERSION)
        {
            throw new IllegalArgumentException("Unsupported RadioResolve call schema version");
        }

        submissionId = UUID.fromString(submissionId).toString();
    }

    /**
     * Creates wire facts. Trunked calls wait for control-channel placement proof; configured conventional channels
     * already carry their canonical RadioResolve identity and are placed immediately.
     */
    public static BuildResult create(AudioRecording recording, String submissionId)
    {
        CompletedCallBroadcastMetadata metadata = recording != null ? recording.getCompletedCallMetadata() : null;
        AudioCallSnapshot snapshot = metadata != null ? metadata.snapshot() : null;

        if(recording == null || snapshot == null || snapshot.identifierCollection() == null ||
            metadata.callLegSummaries().isEmpty())
        {
            return BuildResult.rejected("completed call facts are unavailable");
        }

        CallLegSource selectedSource = snapshot.callLegSource();
        ProtocolProfile protocolProfile = protocolProfile(selectedSource != null ? selectedSource.decoderType() : null);

        if(protocolProfile == null)
        {
            return BuildResult.rejected("protocol is not supported by RadioResolve v3");
        }

        IdentifierCollection identifiers = snapshot.identifierCollection();
        AudioCallRecordingMetadata recordingMetadata = snapshot.recordingMetadata();
        Placement conventionalPlacement = null;
        ConfiguredChannelObservation configuredChannel = null;

        if(protocolProfile.conventional())
        {
            if(selectedSource == null || !selectedSource.isConventional())
            {
                return BuildResult.rejected("selected call source is not a conventional channel");
            }

            String channelUuid = canonicalUuid(selectedSource.radioResolveId());

            if(channelUuid == null)
            {
                return BuildResult.rejected("conventional channel RadioResolve ID is not a valid UUID");
            }

            String systemName = recordingMetadata != null ? normalize(recordingMetadata.systemName(), 255) : null;
            String siteName = recordingMetadata != null ? normalize(recordingMetadata.siteName(), 255) : null;

            if(systemName == null || siteName == null)
            {
                return BuildResult.rejected("conventional channel system and site names are unavailable");
            }

            String channelName = selectedSource.channelName() != null ? selectedSource.channelName() :
                recordingMetadata.channelName();
            configuredChannel = new ConfiguredChannelObservation(channelName, systemName, siteName);
            conventionalPlacement = Placement.conventionalChannel(protocolProfile.placementProtocol(), channelUuid,
                protocolProfile.p25() ? nac(identifiers) : null);
        }

        Party destination = protocolProfile.analog() ? Party.channel(conventionalPlacement.channelUuid()) :
            party(identifiers.getToIdentifier());
        Party source = protocolProfile.analog() ? null : party(identifiers.getFromIdentifier());

        if(!validDestination(destination, conventionalPlacement))
        {
            return BuildResult.rejected("call destination local identity is unavailable");
        }

        long startedAt = snapshot.startTimestamp();
        long endedAt = snapshot.lastActivityTimestamp();

        if(startedAt <= 0L || endedAt < startedAt || endedAt - startedAt > MAXIMUM_CALL_DURATION_MILLISECONDS ||
            recording.getRecordingLength() <= 0L)
        {
            return BuildResult.rejected("call timing is invalid");
        }

        ReceptionProjection projection = reception(metadata.callLegSummaries(), snapshot, recording, startedAt,
            endedAt, frequency(identifiers),
            protocolProfile.conventional() ? null : positiveTimeslot(snapshot.timeslot()), destination, source,
            protocolProfile, conventionalPlacement);

        if(!validDestination(projection.reception().destination(), conventionalPlacement))
        {
            return BuildResult.rejected("call destination local identity is unavailable");
        }

        Encryption encryption = encryption(snapshot);
        CallFacts facts = new CallFacts(null, protocolProfile.voiceProtocol(), startedAt, endedAt, endedAt - startedAt,
            projection.reception().destination(), projection.reception().source(), encryption.state(),
            encryption.algorithmId(), encryption.keyId());
        RadioResolveCallEnvelope envelope = new RadioResolveCallEnvelope(SCHEMA_VERSION, submissionId, endedAt,
            RadioResolveBroadcaster.AGENT_VERSION, facts, projection.reception(),
            observations(recordingMetadata, identifiers, configuredChannel));

        if(protocolProfile.conventional() && !envelope.isReady())
        {
            return BuildResult.rejected("conventional call placement does not match the selected audio leg");
        }

        return new BuildResult(envelope, projection.holdContext(), null);
    }

    /** Applies only site identities proven by metadata from the matching control-channel incarnation. */
    public RadioResolveCallEnvelope withVerifiedPlacements(Placement selectedPlacement,
                                                           List<IndexedPlacement> physicalLegPlacements)
    {
        List<PhysicalLeg> legs = reception != null ? new ArrayList<>(reception.physicalLegs()) : new ArrayList<>();

        if(physicalLegPlacements != null)
        {
            for(IndexedPlacement indexed : physicalLegPlacements)
            {
                if(indexed != null && indexed.index() >= 0 && indexed.index() < legs.size() &&
                    indexed.placement() != null)
                {
                    legs.set(indexed.index(), legs.get(indexed.index()).withPlacement(indexed.placement()));
                }
            }
        }

        Placement effectivePlacement = selectedPlacement != null ? selectedPlacement :
            reception != null ? reception.placement() : null;
        Reception updatedReception = reception != null ? new Reception(
            effectivePlacement, reception.frequencyHz(),
            reception.timeslot(), reception.destination(), reception.source(), reception.quality(),
            reception.fingerprints(), List.copyOf(legs)) : null;
        CallFacts updatedCall = call;

        if(call != null && effectivePlacement != null && effectivePlacement.isTrunkedSite())
        {
            updatedCall = call.withSystem(new SystemIdentity(effectivePlacement.protocol(), effectivePlacement.wacn(),
                effectivePlacement.systemId()));
        }

        return new RadioResolveCallEnvelope(schemaVersion, submissionId, completedAtMs, agentVersion, updatedCall,
            updatedReception, observations);
    }

    /**
     * Applies one server-clock offset to every absolute call and reception timestamp. Fingerprint offsets and
     * durations are relative values and intentionally remain unchanged.
     */
    public RadioResolveCallEnvelope withTimestampOffset(long offsetMilliseconds)
    {
        if(offsetMilliseconds == 0L)
        {
            return this;
        }

        long adjustedCompletedAt = adjustedTimestamp(completedAtMs, offsetMilliseconds);
        CallFacts adjustedCall = call != null ? call.withTimestampOffset(offsetMilliseconds) : null;
        Reception adjustedReception = reception != null ? reception.withTimestampOffset(offsetMilliseconds) : null;
        return new RadioResolveCallEnvelope(schemaVersion, submissionId, adjustedCompletedAt, agentVersion,
            adjustedCall, adjustedReception, observations);
    }

    private static long adjustedTimestamp(long timestamp, long offsetMilliseconds)
    {
        long adjusted = Math.addExact(timestamp, offsetMilliseconds);

        if(adjusted <= 0L)
        {
            throw new IllegalArgumentException("RadioResolve timestamp normalization produced an invalid time");
        }

        return adjusted;
    }

    public boolean isReady()
    {
        if(reception == null || reception.placement() == null || reception.frequencyHz() == null ||
            reception.frequencyHz() <= 0L || call == null || call.destination() == null)
        {
            return false;
        }

        Placement placement = reception.placement();

        if(placement.isTrunkedSite())
        {
            if(call.system() == null || !validNumericParty(call.destination()) ||
                !placement.protocol().equals(call.system().protocol()) ||
                !Objects.equals(placement.wacn(), call.system().wacn()) ||
                !Objects.equals(placement.systemId(), call.system().systemId()))
            {
                return false;
            }
        }
        else if(placement.isConventionalChannel())
        {
            if(call.system() != null || !validDestination(call.destination(), placement) ||
                !placement.matchesVoiceProtocol(call.voiceProtocol()))
            {
                return false;
            }
        }
        else
        {
            return false;
        }

        PhysicalLeg selected = null;

        for(PhysicalLeg leg : reception.physicalLegs())
        {
            if(leg != null && leg.selectedAudio())
            {
                if(selected != null)
                {
                    return false;
                }

                selected = leg;
            }
        }

        return selected != null && selected.placement() != null && selected.frequencyHz() != null &&
            selected.frequencyHz() > 0L && selected.placement().samePlacement(reception.placement()) &&
            Objects.equals(selected.frequencyHz(), reception.frequencyHz()) &&
            Objects.equals(selected.timeslot(), reception.timeslot());
    }

    private static ReceptionProjection reception(List<CallLegSummary> summaries, AudioCallSnapshot snapshot,
                                                  AudioRecording recording, long callStart, long callEnd,
                                                  Long frequencyHz, Integer timeslot, Party fallbackDestination,
                                                  Party fallbackSource, ProtocolProfile protocolProfile,
                                                  Placement conventionalPlacement)
    {
        List<PhysicalLeg> physicalLegs = new ArrayList<>();
        List<PlacementLookup> lookups = new ArrayList<>();
        Quality selectedQuality = quality(snapshot.voiceCallQuality(), sampleCount(recording), false, false);
        Party selectedDestination = fallbackDestination;
        Party selectedSource = fallbackSource;
        Long selectedFrequencyHz = frequencyHz;
        Integer selectedTimeslot = timeslot;
        List<FingerprintFrame> combinedFingerprintFrames = new ArrayList<>();
        PlacementLookup selectedLookup = lookup(-1, snapshot.callLegSource());

        if(summaries != null)
        {
            List<CallLegSummary> ordered = summaries.stream()
                .sorted(Comparator.comparing((CallLegSummary summary) -> !summary.winner())
                    .thenComparingLong(CallLegSummary::startTimestamp)).toList();

            for(CallLegSummary summary : ordered)
            {
                Quality legQuality = quality(summary.voiceCallQuality(), summary.retainedAudioSampleCount(),
                    summary.ingressLoss(), summary.audioTruncated());
                Fingerprints legFingerprints = protocolProfile.p25() ?
                    fingerprints(summary.voiceFrameFingerprints(), callStart, callEnd) : null;

                if(legFingerprints != null)
                {
                    combinedFingerprintFrames.addAll(legFingerprints.frames());
                }

                Long legFrequencyHz = summary.frequencyHz();
                Integer legTimeslot = protocolProfile.conventional() ? null : summary.timeslot();

                if(summary.winner())
                {
                    //The resolved snapshot carries the coordinator's cohort-adjusted expected/missing counts.
                    //Keep those root reception counters while taking physical delivery flags from the audio winner.
                    selectedQuality = quality(snapshot.voiceCallQuality(), summary.retainedAudioSampleCount(),
                        summary.ingressLoss(), summary.audioTruncated());
                    selectedFrequencyHz = legFrequencyHz != null ? legFrequencyHz : frequencyHz;
                    selectedTimeslot = legTimeslot != null ? legTimeslot : timeslot;
                    legFrequencyHz = selectedFrequencyHz;
                    legTimeslot = selectedTimeslot;
                    if(!protocolProfile.analog())
                    {
                        selectedDestination = party(summary.p25DestinationIdentity(),
                            summary.p25PatchMemberIdentities(), fallbackDestination);
                        selectedSource = party(summary.p25SourceIdentity(), List.of(), fallbackSource);
                    }
                }

                int index = physicalLegs.size();
                Placement legPlacement = protocolProfile.conventional() ?
                    conventionalPlacement(summary.source(),
                        protocolProfile.p25() ? conventionalPlacement.nac() : null) : null;
                physicalLegs.add(new PhysicalLeg(summary.winner(), legPlacement, summary.startTimestamp(),
                    summary.endTimestamp(), legFrequencyHz, legTimeslot, legQuality));
                PlacementLookup lookup = lookup(index, summary.source());
                lookups.add(lookup);

                if(summary.winner())
                {
                    selectedLookup = lookup;
                }
            }
        }

        Reception result = new Reception(conventionalPlacement, selectedFrequencyHz, selectedTimeslot,
            selectedDestination, selectedSource, selectedQuality, combinedFingerprints(combinedFingerprintFrames),
            List.copyOf(physicalLegs));
        HoldContext holdContext = protocolProfile.conventional() ? HoldContext.EMPTY :
            new HoldContext(selectedLookup, List.copyOf(lookups));
        return new ReceptionProjection(result, holdContext);
    }

    private static Quality quality(VoiceCallQuality quality, long retainedSamples, boolean ingressLoss,
                                   boolean truncated)
    {
        VoiceCallQuality safe = quality != null ? quality : VoiceCallQuality.EMPTY;
        boolean voiceAvailable = safe.hasMeasurements();
        boolean fecAvailable = safe.fecProtectedBitCount() > 0L &&
            safe.fecErrorCount() <= safe.fecProtectedBitCount();
        return new Quality(QUALITY_METHOD_VERSION,
            voiceAvailable ? safe.decodedFrameCount() : null,
            voiceAvailable ? safe.repeatedFrameCount() : null,
            voiceAvailable ? safe.concealedFrameCount() : null,
            voiceAvailable ? safe.missingFrameCount() : null,
            voiceAvailable ? safe.expectedFrameCount() : null,
            fecAvailable ? safe.fecErrorCount() : null,
            fecAvailable ? safe.fecProtectedBitCount() : null,
            Math.max(0L, retainedSamples), ingressLoss, truncated);
    }

    private static Fingerprints fingerprints(List<TimestampedVoiceFingerprint> evidence, long callStart,
                                             long callEnd)
    {
        if(evidence == null || evidence.isEmpty())
        {
            return null;
        }

        List<FingerprintFrame> frames = new ArrayList<>(Math.min(evidence.size(),
            MAXIMUM_FINGERPRINTS_PER_RECEPTION));
        long maximumOffset = Math.max(0L, callEnd - callStart) +
            VoiceCallQuality.VOICE_FRAME_DURATION_MILLISECONDS * 2L;

        for(TimestampedVoiceFingerprint item : evidence)
        {
            if(item != null && item.fingerprint() != 0L && item.carrierTimestamp() > 0L)
            {
                long offset = item.carrierTimestamp() - callStart;

                if(offset < 0L || offset > maximumOffset)
                {
                    continue;
                }

                frames.add(new FingerprintFrame(offset, String.format(Locale.ROOT, "%016x", item.fingerprint())));
            }
        }

        List<FingerprintFrame> distinct = frames.stream().distinct()
            .sorted(Comparator.comparingLong(FingerprintFrame::offsetMs).thenComparing(FingerprintFrame::digest))
            .limit(MAXIMUM_FINGERPRINTS_PER_RECEPTION).toList();
        return distinct.isEmpty() ? null : new Fingerprints(FINGERPRINT_METHOD, distinct);
    }

    private static Fingerprints combinedFingerprints(List<FingerprintFrame> frames)
    {
        if(frames == null || frames.isEmpty())
        {
            return null;
        }

        List<FingerprintFrame> combined = frames.stream().distinct()
            .sorted(Comparator.comparingLong(FingerprintFrame::offsetMs).thenComparing(FingerprintFrame::digest))
            .limit(MAXIMUM_FINGERPRINTS_PER_RECEPTION).toList();
        return combined.isEmpty() ? null : new Fingerprints(FINGERPRINT_METHOD, combined);
    }

    private static PlacementLookup lookup(int physicalLegIndex, CallLegSource source)
    {
        return new PlacementLookup(null, physicalLegIndex, source != null ? source.channelConfigurationId() : null,
            source != null ? source.siteEvidenceProcessingIncarnation() : 0L,
            source != null ? source.siteEvidenceTuningGeneration() : 0L);
    }

    private static Long frequency(IdentifierCollection identifiers)
    {
        Identifier<?> identifier = identifiers.getIdentifier(IdentifierClass.CONFIGURATION,
            Form.CHANNEL_FREQUENCY, Role.ANY);
        return identifier instanceof ConfigurationLongIdentifier value && value.getValue() > 0L ?
            value.getValue() : null;
    }

    private static Integer nac(IdentifierCollection identifiers)
    {
        for(Identifier<?> identifier : identifiers.getIdentifiers(Form.NETWORK_ACCESS_CODE))
        {
            if(identifier.getIdentifierClass() == IdentifierClass.NETWORK &&
                identifier.getValue() instanceof Number number)
            {
                int value = number.intValue();

                if(value >= 0 && value <= 0xFFF)
                {
                    return value;
                }
            }
        }

        return null;
    }

    private static Placement conventionalPlacement(CallLegSource source, Integer nac)
    {
        ProtocolProfile profile = protocolProfile(source != null ? source.decoderType() : null);
        String channelUuid = source != null && source.isConventional() ? canonicalUuid(source.radioResolveId()) : null;

        if(profile == null || !profile.conventional() || channelUuid == null)
        {
            return null;
        }

        return Placement.conventionalChannel(profile.placementProtocol(), channelUuid, profile.p25() ? nac : null);
    }

    private static boolean validDestination(Party party, Placement placement)
    {
        if(placement != null && placement.isConventionalChannel() && !placement.isP25())
        {
            return party != null && party.isChannel() &&
                Objects.equals(party.channelUuid(), placement.channelUuid());
        }

        return validNumericParty(party);
    }

    private static boolean validNumericParty(Party party)
    {
        return party != null && !party.isChannel() && party.localId() != null && party.localId() > 0L &&
            party.canonicalId() != null && party.canonicalId() > 0L;
    }

    private static Integer positiveTimeslot(int timeslot)
    {
        return timeslot > 0 ? timeslot : null;
    }

    private static Party party(Identifier<?> identifier)
    {
        if(identifier instanceof PatchGroupIdentifier patchIdentifier && patchIdentifier.getValue() != null)
        {
            PatchGroup patch = patchIdentifier.getValue();
            Party primary = party(patch.getPatchGroup());

            if(primary == null)
            {
                return null;
            }

            List<Party> members = new ArrayList<>();
            patch.getPatchedTalkgroupIdentifiers().stream().map(RadioResolveCallEnvelope::party)
                .filter(java.util.Objects::nonNull).forEach(members::add);
            patch.getPatchedRadioIdentifiers().stream().map(RadioResolveCallEnvelope::party)
                .filter(java.util.Objects::nonNull).forEach(members::add);
            return new Party("patch_group", primary.localId(), primary.canonicalId(), primary.homeWacn(),
                primary.homeSystemId(), List.copyOf(members));
        }
        else if(identifier instanceof FullyQualifiedTalkgroupIdentifier fqTalkgroup)
        {
            return new Party("talkgroup", fqTalkgroup.getValue().longValue(), fqTalkgroup.getTalkgroup(),
                fqTalkgroup.getWacn(), fqTalkgroup.getSystem(), List.of());
        }
        else if(identifier instanceof FullyQualifiedRadioIdentifier fqRadio)
        {
            return new Party("radio", fqRadio.getValue().longValue(), fqRadio.getRadio(), fqRadio.getWacn(),
                fqRadio.getSystem(), List.of());
        }
        else if(identifier instanceof TalkgroupIdentifier talkgroup && talkgroup.getValue() != null)
        {
            long value = talkgroup.getValue().longValue();
            return new Party("talkgroup", value, value, null, null, List.of());
        }
        else if(identifier instanceof RadioIdentifier radio && radio.getValue() != null)
        {
            long value = radio.getValue().longValue();
            return new Party("radio", value, value, null, null, List.of());
        }

        return null;
    }

    private static Party party(CallLegSummary.P25IdentityObservation observation,
                               List<CallLegSummary.P25IdentityObservation> memberObservations, Party fallback)
    {
        if(observation == null)
        {
            return fallback;
        }

        String type = observation.form() == Form.PATCH_GROUP ? "patch_group" :
            observation.form() == Form.TALKGROUP ? "talkgroup" :
                observation.form() == Form.RADIO ? "radio" : null;

        if(type == null)
        {
            return fallback;
        }

        List<Party> members = new ArrayList<>();

        if(memberObservations != null)
        {
            for(CallLegSummary.P25IdentityObservation member : memberObservations)
            {
                Party projected = party(member, List.of(), null);

                if(projected != null)
                {
                    members.add(projected);
                }
            }
        }

        long canonicalId = observation.homeIdentityId() != null ? observation.homeIdentityId() :
            observation.observedLocalId();
        return new Party(type, observation.observedLocalId(), canonicalId, observation.homeWacn(),
            observation.homeSystemId(), List.copyOf(members));
    }

    private static Encryption encryption(AudioCallSnapshot snapshot)
    {
        CallEncryptionEvidence evidence = snapshot.callEncryptionEvidence();
        return new Encryption(snapshot.encryptionState().name().toLowerCase(Locale.ROOT),
            evidence != null ? evidence.algorithmId() : null, evidence != null ? evidence.keyId() : null);
    }

    private static MetadataObservations observations(AudioCallRecordingMetadata metadata,
                                                     IdentifierCollection identifiers,
                                                     ConfiguredChannelObservation configuredChannel)
    {
        LabelObservation destination = metadata != null ? useful(new LabelObservation(metadata.destinationAlias(),
            metadata.destinationDescription(), metadata.destinationGroup())) : null;
        LabelObservation source = metadata != null ? useful(new LabelObservation(metadata.sourceAlias(),
            metadata.sourceDescription(), metadata.sourceGroup())) : null;
        String talkerAlias = null;

        for(Identifier<?> identifier : identifiers.getIdentifiers(Role.FROM))
        {
            if(identifier instanceof TalkerAliasIdentifier alias && alias.isValid())
            {
                talkerAlias = normalize(alias.getValue());
                break;
            }
        }

        MetadataObservations result = new MetadataObservations(configuredChannel, destination, source, talkerAlias);
        return result.isEmpty() ? null : result;
    }

    private static LabelObservation useful(LabelObservation observation)
    {
        return observation.isEmpty() ? null : observation;
    }

    private static ProtocolProfile protocolProfile(DecoderType decoder)
    {
        if(decoder == null)
        {
            return null;
        }

        return switch(decoder)
        {
            case AM -> new ProtocolProfile("am", "am", true, true, false);
            case NBFM -> new ProtocolProfile("nbfm", "nbfm", true, true, false);
            case P25_CONVENTIONAL -> new ProtocolProfile("p25_phase1", "p25", true, false, true);
            case P25_PHASE1 -> new ProtocolProfile("p25_phase1", "p25", false, false, true);
            case P25_PHASE2 -> new ProtocolProfile("p25_phase2", "p25", false, false, true);
            default -> null;
        };
    }

    private static long sampleCount(AudioRecording recording)
    {
        return recording != null ? Math.max(0L, recording.getRecordingLength() * 8L) : 0L;
    }

    private static String normalize(String value)
    {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String normalize(String value, int maximumLength)
    {
        String normalized = normalize(value);

        if(normalized == null || normalized.length() <= maximumLength)
        {
            return normalized;
        }

        int end = maximumLength;

        if(end > 0 && Character.isHighSurrogate(normalized.charAt(end - 1)))
        {
            end--;
        }

        return normalized.substring(0, end);
    }

    private static String canonicalUuid(String value)
    {
        String normalized = normalize(value);

        if(normalized == null)
        {
            return null;
        }

        try
        {
            String canonical = UUID.fromString(normalized).toString();
            return canonical.equalsIgnoreCase(normalized) ? canonical : null;
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private record ProtocolProfile(String voiceProtocol, String placementProtocol, boolean conventional,
                                   boolean analog, boolean p25)
    {
    }

    private record ReceptionProjection(Reception reception, HoldContext holdContext)
    {
    }

    public record CallFacts(SystemIdentity system, String voiceProtocol, long startedAtMs, long endedAtMs,
                            long durationMs,
                            Party destination, Party source, String encryptionState,
                            Integer encryptionAlgorithmId, Integer encryptionKeyId)
    {
        private CallFacts withSystem(SystemIdentity value)
        {
            return new CallFacts(value, voiceProtocol, startedAtMs, endedAtMs, durationMs, destination, source,
                encryptionState, encryptionAlgorithmId, encryptionKeyId);
        }

        private CallFacts withTimestampOffset(long offsetMilliseconds)
        {
            return new CallFacts(system, voiceProtocol, adjustedTimestamp(startedAtMs, offsetMilliseconds),
                adjustedTimestamp(endedAtMs, offsetMilliseconds), durationMs, destination, source, encryptionState,
                encryptionAlgorithmId, encryptionKeyId);
        }
    }

    public record SystemIdentity(String protocol, int wacn, int systemId)
    {
        public SystemIdentity
        {
            protocol = normalize(protocol);

            if(!"p25".equals(protocol))
            {
                throw new IllegalArgumentException("RadioResolve v3 system identity requires protocol p25");
            }
        }
    }

    public record Party(String type, Long localId, Long canonicalId, Integer homeWacn, Integer homeSystemId,
                        List<Party> members, String channelUuid)
    {
        public Party(String type, long localId, long canonicalId, Integer homeWacn, Integer homeSystemId,
                     List<Party> members)
        {
            this(type, localId, canonicalId, homeWacn, homeSystemId, members, null);
        }

        public Party
        {
            type = normalize(type);

            if("channel".equals(type))
            {
                channelUuid = canonicalUuid(channelUuid);

                if(channelUuid == null || localId != null || canonicalId != null || homeWacn != null ||
                    homeSystemId != null || members != null && !members.isEmpty())
                {
                    throw new IllegalArgumentException("RadioResolve conventional channel party is invalid");
                }

                members = null;
            }
            else
            {
                channelUuid = null;
                members = members != null ? List.copyOf(members) : List.of();
            }
        }

        public static Party channel(String channelUuid)
        {
            return new Party("channel", null, null, null, null, null, channelUuid);
        }

        public boolean isChannel()
        {
            return "channel".equals(type);
        }
    }

    public record Encryption(String state, Integer algorithmId, Integer keyId)
    {
    }

    public record Placement(String kind, String protocol, Integer wacn, Integer systemId, Integer rfssId,
                            Integer siteId, String channelUuid, Integer nac)
    {
        public static final String TRUNKED_SITE = "trunked_site";
        public static final String CONVENTIONAL_CHANNEL = "conventional_channel";

        public Placement(String protocol, int wacn, int systemId, int rfssId, int siteId, Integer nac)
        {
            this(TRUNKED_SITE, protocol, wacn, systemId, rfssId, siteId, null, nac);
        }

        public Placement
        {
            kind = normalize(kind);
            protocol = normalize(protocol);

            if(TRUNKED_SITE.equals(kind))
            {
                if(!"p25".equals(protocol) || wacn == null || systemId == null || rfssId == null || siteId == null ||
                    channelUuid != null || !validNac(nac))
                {
                    throw new IllegalArgumentException("RadioResolve v3 trunked site placement is invalid");
                }
            }
            else if(CONVENTIONAL_CHANNEL.equals(kind))
            {
                channelUuid = canonicalUuid(channelUuid);

                if(!List.of("am", "nbfm", "p25").contains(protocol) || channelUuid == null || wacn != null ||
                    systemId != null || rfssId != null || siteId != null || !validNac(nac) ||
                    nac != null && !"p25".equals(protocol))
                {
                    throw new IllegalArgumentException("RadioResolve v3 conventional channel placement is invalid");
                }
            }
            else
            {
                throw new IllegalArgumentException("RadioResolve v3 placement kind is invalid");
            }
        }

        public static Placement conventionalChannel(String protocol, String channelUuid, Integer nac)
        {
            return new Placement(CONVENTIONAL_CHANNEL, protocol, null, null, null, null, channelUuid, nac);
        }

        public boolean sameSite(Placement other)
        {
            return samePlacement(other);
        }

        public boolean samePlacement(Placement other)
        {
            if(other == null || !kind.equals(other.kind) || !protocol.equals(other.protocol))
            {
                return false;
            }

            return isTrunkedSite() ? Objects.equals(wacn, other.wacn) &&
                Objects.equals(systemId, other.systemId) && Objects.equals(rfssId, other.rfssId) &&
                Objects.equals(siteId, other.siteId) : Objects.equals(channelUuid, other.channelUuid);
        }

        public boolean isTrunkedSite()
        {
            return TRUNKED_SITE.equals(kind);
        }

        public boolean isConventionalChannel()
        {
            return CONVENTIONAL_CHANNEL.equals(kind);
        }

        public boolean isP25()
        {
            return "p25".equals(protocol);
        }

        private boolean matchesVoiceProtocol(String voiceProtocol)
        {
            return "p25".equals(protocol) ? "p25_phase1".equals(voiceProtocol) :
                protocol.equals(voiceProtocol);
        }

        private static boolean validNac(Integer value)
        {
            return value == null || value >= 0 && value <= 0xFFF;
        }
    }

    public record Reception(Placement placement, Long frequencyHz, Integer timeslot, Party destination,
                            Party source, Quality quality, Fingerprints fingerprints,
                            List<PhysicalLeg> physicalLegs)
    {
        public Reception
        {
            physicalLegs = physicalLegs != null ? List.copyOf(physicalLegs) : List.of();
        }

        private Reception withTimestampOffset(long offsetMilliseconds)
        {
            return new Reception(placement, frequencyHz, timeslot, destination, source, quality, fingerprints,
                physicalLegs.stream().map(leg -> leg != null ? leg.withTimestampOffset(offsetMilliseconds) : null)
                    .toList());
        }
    }

    public record PhysicalLeg(boolean selectedAudio, Placement placement, long startedAtMs, long endedAtMs,
                              Long frequencyHz, Integer timeslot, Quality quality)
    {
        private PhysicalLeg withPlacement(Placement value)
        {
            return new PhysicalLeg(selectedAudio, value, startedAtMs, endedAtMs, frequencyHz, timeslot, quality);
        }

        private PhysicalLeg withTimestampOffset(long offsetMilliseconds)
        {
            return new PhysicalLeg(selectedAudio, placement, adjustedTimestamp(startedAtMs, offsetMilliseconds),
                adjustedTimestamp(endedAtMs, offsetMilliseconds), frequencyHz, timeslot, quality);
        }
    }

    public record Quality(int methodVersion, Long decodedFrames, Long repeatedFrames, Long concealedFrames,
                          Long missingFrames, Long expectedFrames, Long fecErrors, Long fecProtectedBits,
                          long retainedAudioSamples, boolean ingressLoss, boolean audioTruncated)
    {
    }

    public record Fingerprints(String methodVersion, List<FingerprintFrame> frames)
    {
        public Fingerprints
        {
            frames = frames != null ? List.copyOf(frames.subList(0,
                Math.min(frames.size(), MAXIMUM_FINGERPRINTS_PER_RECEPTION))) : List.of();
        }
    }

    public record FingerprintFrame(long offsetMs, String digest)
    {
        public FingerprintFrame
        {
            digest = normalize(digest);

            if(offsetMs < 0L || digest == null || !digest.matches("[0-9a-f]{16}"))
            {
                throw new IllegalArgumentException("Invalid RadioResolve exact fingerprint frame");
            }
        }
    }

    public record MetadataObservations(ConfiguredChannelObservation configuredChannel,
                                       LabelObservation configuredDestination,
                                       LabelObservation configuredSource, String talkerAlias)
    {
        public MetadataObservations(LabelObservation configuredDestination, LabelObservation configuredSource,
                                    String talkerAlias)
        {
            this(null, configuredDestination, configuredSource, talkerAlias);
        }

        public MetadataObservations
        {
            talkerAlias = normalize(talkerAlias, 255);
        }

        private boolean isEmpty()
        {
            return configuredChannel == null && configuredDestination == null && configuredSource == null &&
                talkerAlias == null;
        }
    }

    public record ConfiguredChannelObservation(String observedName, String systemName, String siteName)
    {
        public ConfiguredChannelObservation
        {
            observedName = normalize(observedName, 255);
            systemName = normalize(systemName, 255);
            siteName = normalize(siteName, 255);

            if(systemName == null || siteName == null)
            {
                throw new IllegalArgumentException(
                    "Configured conventional channel system and site names are required");
            }
        }
    }

    public record LabelObservation(String observedName, String observedDescription, String observedCategory)
    {
        public LabelObservation
        {
            observedName = normalize(observedName, 255);
            observedDescription = normalize(observedDescription, 255);
            observedCategory = normalize(observedCategory, 255);
        }

        private boolean isEmpty()
        {
            return observedName == null && observedDescription == null && observedCategory == null;
        }
    }

    /** Internal lookup material retained only in the local spool manifest. */
    public record PlacementLookup(String evidenceSessionId, int physicalLegIndex, String channelConfigurationId,
                                  long processingIncarnation, long tuningGeneration)
    {
        public PlacementLookup
        {
            evidenceSessionId = normalize(evidenceSessionId);
            channelConfigurationId = normalize(channelConfigurationId);
            processingIncarnation = Math.max(0L, processingIncarnation);
            tuningGeneration = Math.max(0L, tuningGeneration);
        }

        public boolean isUsable()
        {
            return evidenceSessionId != null && channelConfigurationId != null && processingIncarnation > 0L &&
                tuningGeneration > 0L;
        }

        private PlacementLookup withEvidenceSession(String sessionId)
        {
            return new PlacementLookup(sessionId, physicalLegIndex, channelConfigurationId, processingIncarnation,
                tuningGeneration);
        }
    }

    /** Internal tuning-generation keys; stored in the spool manifest and omitted from the wire call JSON. */
    public record HoldContext(PlacementLookup selected, List<PlacementLookup> physicalLegs)
    {
        public static final HoldContext EMPTY = new HoldContext(null, List.of());

        public HoldContext
        {
            physicalLegs = physicalLegs != null ? List.copyOf(physicalLegs) : List.of();
        }

        public HoldContext withEvidenceSession(String sessionId)
        {
            String normalized = UUID.fromString(sessionId).toString();
            PlacementLookup boundSelected = selected != null ? selected.withEvidenceSession(normalized) : null;
            List<PlacementLookup> boundLegs = physicalLegs.stream()
                .map(lookup -> lookup != null ? lookup.withEvidenceSession(normalized) : null).toList();
            return new HoldContext(boundSelected, boundLegs);
        }
    }

    public record IndexedPlacement(int index, Placement placement)
    {
    }

    public record BuildResult(RadioResolveCallEnvelope envelope, HoldContext holdContext, String rejectionReason)
    {
        private static BuildResult rejected(String reason)
        {
            return new BuildResult(null, HoldContext.EMPTY, reason);
        }

        public boolean accepted()
        {
            return envelope != null;
        }
    }
}
