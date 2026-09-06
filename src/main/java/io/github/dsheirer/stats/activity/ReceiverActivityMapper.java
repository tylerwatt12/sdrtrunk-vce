/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IncompleteIdentifier;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DMRConventionalCallEvent;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallEvent;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25EncryptionConfirmationTracker;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityEligibility;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.protocol.Protocol;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Converts SDRTrunk activity events into compact SQLite log records.
 */
class ReceiverActivityMapper
{
    static final String PROTOCOL_SIGNAL_DEDUPE_PREFIX = "protocol-signal|";

    ReceiverActivityRecords.DmrConventionalCall map(DMRConventionalCallEvent event)
    {
        if(event == null || event.startTimestamp() <= 0 || event.endTimestamp() < event.startTimestamp() ||
            event.frequencyHertz() <= 0 || (event.timeslot() != 1 && event.timeslot() != 2) ||
            event.targetKind() == null)
        {
            return null;
        }

        String configurationId = ChannelConfigurationKey.canonical(event.channelConfigurationId());

        if(configurationId == null)
        {
            return null;
        }

        ReceiverActivityRecords.DmrTargetKind targetKind = switch(event.targetKind())
        {
            case GROUP -> ReceiverActivityRecords.DmrTargetKind.GROUP;
            case PRIVATE -> ReceiverActivityRecords.DmrTargetKind.PRIVATE;
            case UNKNOWN -> ReceiverActivityRecords.DmrTargetKind.UNKNOWN;
        };
        Integer talkgroup = positive(event.talkgroupId());
        Integer sourceRadio = positive(event.sourceRadioId());
        Integer targetRadio = positive(event.targetRadioId());

        if(targetKind != ReceiverActivityRecords.DmrTargetKind.GROUP)
        {
            talkgroup = null;
        }

        if(targetKind != ReceiverActivityRecords.DmrTargetKind.PRIVATE)
        {
            targetRadio = null;
        }

        return new ReceiverActivityRecords.DmrConventionalCall(event.startTimestamp(), event.endTimestamp(),
            configurationId, event.frequencyHertz(),
            event.timeslot(), targetKind, talkgroup, sourceRadio, targetRadio, event.encrypted());
    }

    ReceiverActivityRecords.NxdnConventionalCall map(NXDNConventionalCallEvent event)
    {
        if(event == null || event.startTimestamp() <= 0 || event.endTimestamp() < event.startTimestamp() ||
            event.frequencyHertz() <= 0 || event.targetKind() == null)
        {
            return null;
        }

        String configurationId = ChannelConfigurationKey.canonical(event.channelConfigurationId());

        if(configurationId == null)
        {
            return null;
        }
        ReceiverActivityRecords.NxdnTargetKind targetKind = switch(event.targetKind())
        {
            case GROUP -> ReceiverActivityRecords.NxdnTargetKind.GROUP;
            case PRIVATE -> ReceiverActivityRecords.NxdnTargetKind.PRIVATE;
            case UNKNOWN -> ReceiverActivityRecords.NxdnTargetKind.UNKNOWN;
        };
        Integer talkgroup = positiveNxdn(event.talkgroupId());
        Integer sourceRadio = positiveNxdn(event.sourceRadioId());
        Integer targetRadio = positiveNxdn(event.targetRadioId());

        if(targetKind != ReceiverActivityRecords.NxdnTargetKind.GROUP)
        {
            talkgroup = null;
        }

        if(targetKind != ReceiverActivityRecords.NxdnTargetKind.PRIVATE)
        {
            targetRadio = null;
        }

        return new ReceiverActivityRecords.NxdnConventionalCall(event.startTimestamp(), event.endTimestamp(),
            configurationId, event.frequencyHertz(),
            targetKind, talkgroup, sourceRadio, targetRadio, event.encrypted(),
            event.identityDomain());
    }

    ReceiverActivityRecords.TalkerAliasUpdate map(TrunkedTalkerAliasEvent event)
    {
        if(event == null || event.configurationId() == null || event.radio() == null ||
            event.talkerAlias() == null || event.talkerAlias().isBlank() || event.decoderType() == null ||
            event.protocol() == null || event.protocol() == Protocol.UNKNOWN ||
            event.callStartEpochMilliseconds() <= 0)
        {
            return null;
        }

        DecoderType decoderType = event.decoderType();

        boolean decoderMatches = switch(event.protocol())
        {
            case APCO25 -> decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2;
            case DMR -> decoderType == DecoderType.DMR;
            case NXDN -> decoderType == DecoderType.NXDN;
            default -> false;
        };

        if(!decoderMatches)
        {
            return null;
        }

        IdentifierFacts facts = IdentifierFacts.from(event.identifierCollection());
        long observedAt = event.observedAtEpochMilliseconds() > 0 ?
            event.observedAtEpochMilliseconds() : System.currentTimeMillis();
        return new ReceiverActivityRecords.TalkerAliasUpdate(observedAt, event.callStartEpochMilliseconds(),
            event.configurationId(),
            configuredProtocolName(decoderType, event.protocol()),
            facts.wacn(),
            facts.systemId(), event.radio().getValue(),
            p25Identity(event.radio(), event.protocol() == Protocol.APCO25),
            event.talkerAlias(),
            event.identityDomain(), event.radioSystemKey());
    }

    ReceiverActivityRecords.ActivityEvent map(Channel channel, IDecodeEvent event)
    {
        return map(channel, event, null);
    }

    ReceiverActivityRecords.ActivityEvent map(P25CallStartEvent callStart)
    {
        if(callStart == null || callStart.configurationId() == null || callStart.decoderType() == null)
        {
            return null;
        }

        DecoderType decoderType = callStart.decoderType();
        ReceiverActivityRecords.ReceiverKind receiverKind = decoderType == DecoderType.P25_CONVENTIONAL ?
            ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25 :
            decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2 ?
                ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE : null;

        if(receiverKind == null)
        {
            return null;
        }

        IdentifierCollection identifiers = callStart.identifierCollection();
        IdentifierFacts facts = IdentifierFacts.from(identifiers);
        Long frequency = callStart.frequencyHertz() != null ? callStart.frequencyHertz() : facts.frequencyHertz();
        String channelDescriptor = firstNonBlank(callStart.channelDescriptor(), facts.channelDescriptor(),
            facts.logicalChannelName());
        Integer timeslot = callStart.timeslot() != null ? callStart.timeslot() : facts.timeslot();
        Identifier targetIdentifier = identifiers.getToIdentifier();
        Identifier sourceIdentifier = identifiers.getFromIdentifier();
        boolean encrypted = facts.encrypted() && callStart.encryptionConfirmed();
        Integer encryptionAlgorithmId = encrypted ? facts.encryptionAlgorithmId() : null;
        Integer encryptionKeyId = encrypted ? facts.encryptionKeyId() : null;
        long observedAt = callStart.startedAtEpochMilliseconds() > 0 ?
            callStart.startedAtEpochMilliseconds() : System.currentTimeMillis();

        return new ReceiverActivityRecords.ActivityEvent(observedAt, callStart.configurationId(), receiverKind,
            protocolName(callStart.protocol(), facts, decoderType), ReceiverActivityRecords.Action.CALL,
            callStart.eventType() != null ? callStart.eventType().name() : null, facts.sourceId(), facts.targetId(),
            facts.targetForm(), facts.patchMemberTalkgroupIds(), frequency, channelDescriptor, timeslot, encrypted,
            encryptionAlgorithmId, encryptionKeyId, facts.wacn(), facts.systemId(), facts.nac(), facts.rfss(),
            facts.site(), facts.talkerAlias(), true, null, null, TrunkedIdentityDomain.STANDARD,
            p25TargetIdentity(targetIdentifier, true), p25Identity(sourceIdentifier, true),
            facts.p25PatchMemberIdentities(), callStart.radioSystemKey());
    }

    ReceiverActivityRecords.ActivityEvent map(P25GrantObservationEvent observation)
    {
        if(observation == null || observation.configurationId() == null || observation.decoderType() == null)
        {
            return null;
        }

        DecoderType decoderType = observation.decoderType();
        ReceiverActivityRecords.ReceiverKind receiverKind = decoderType == DecoderType.P25_CONVENTIONAL ?
            ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25 :
            decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2 ?
                ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE : null;

        if(receiverKind == null)
        {
            return null;
        }

        IdentifierCollection identifiers = observation.identifierCollection();
        IdentifierFacts facts = IdentifierFacts.from(identifiers);
        Long frequency = observation.frequencyHertz() != null ? observation.frequencyHertz() : facts.frequencyHertz();
        String descriptor = firstNonBlank(observation.channelDescriptor(), facts.channelDescriptor(),
            facts.logicalChannelName());
        Integer timeslot = observation.timeslot() != null ? observation.timeslot() : facts.timeslot();
        Identifier target = identifiers.getToIdentifier();
        Identifier source = identifiers.getFromIdentifier();
        ReceiverActivityRecords.Action action = observation.continuation() ?
            ReceiverActivityRecords.Action.CONTINUE : ReceiverActivityRecords.Action.GRANT;
        long observedAt = observation.timestamp() > 0 ? observation.timestamp() : System.currentTimeMillis();

        return new ReceiverActivityRecords.ActivityEvent(observedAt, observation.configurationId(), receiverKind,
            protocolName(observation.protocol(), facts, decoderType), action,
            observation.eventType() != null ? observation.eventType().name() : null, facts.sourceId(), facts.targetId(),
            facts.targetForm(), facts.patchMemberTalkgroupIds(), frequency, descriptor, timeslot, false, null, null,
            facts.wacn(), facts.systemId(), facts.nac(), facts.rfss(), facts.site(), facts.talkerAlias(), false, null,
            null, TrunkedIdentityDomain.STANDARD, p25TargetIdentity(target, true), p25Identity(source, true),
            facts.p25PatchMemberIdentities(), observation.radioSystemKey());
    }

    ReceiverActivityRecords.ConventionalCallOutput mapConventionalCallOutput(CompletedAudioCall call,
                                                                     ReceiverActivityRecords.CallOutput output)
    {
        return mapConventionalCallOutput(call != null ? call.snapshot() : null, output);
    }

    /** Maps the global winner plus its compact receiver-leg summaries into the statistics projection. */
    ReceiverActivityRecords.ResolvedLogicalCall mapResolvedLogicalCall(CompletedAudioCall call)
    {
        if(call == null || call.snapshot() == null || call.snapshot().identifierCollection() == null)
        {
            return null;
        }

        CallLegSource winnerSource = call.callLegSummaries().stream().filter(summary -> summary.winner())
            .map(summary -> summary.source()).findFirst().orElse(CallLegSource.UNKNOWN);
        DecoderType decoderType = winnerSource.decoderType();
        boolean p25 = decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2;
        boolean dmr = decoderType == DecoderType.DMR;
        boolean nxdn = decoderType == DecoderType.NXDN;

        if(!winnerSource.trafficChannel() || (!p25 && !dmr && !nxdn))
        {
            return null;
        }

        String protocol = p25 && decoderType == DecoderType.P25_PHASE2 ? Protocol.APCO25_PHASE2.name() :
            p25 ? Protocol.APCO25.name() : dmr ? Protocol.DMR.name() : Protocol.NXDN.name();
        List<P25SiteIdentity> learnedSites = call.callLegSummaries().stream()
            .map(summary -> summary.source().p25SiteIdentity())
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted(java.util.Comparator.comparingInt(P25SiteIdentity::wacn)
                .thenComparingInt(P25SiteIdentity::system).thenComparingInt(P25SiteIdentity::rfss)
                .thenComparingInt(P25SiteIdentity::site))
            .toList();
        if(!p25 && call.callLegSummaries().size() != 1)
        {
            //DMR/NXDN cross-site grouping is not defined yet; each traffic leg remains its own logical call.
            return null;
        }

        AudioCallSnapshot snapshot = call.snapshot();
        IdentifierCollection identifiers = snapshot.identifierCollection();
        IdentifierFacts facts = IdentifierFacts.from(identifiers);
        P25SiteIdentity winnerSite = winnerSource.p25SiteIdentity();
        Integer systemWacn = p25 ? winnerSite != null ? Integer.valueOf(winnerSite.wacn()) : facts.wacn() : null;
        Integer systemId = p25 ? winnerSite != null ? Integer.valueOf(winnerSite.system()) : facts.systemId() : null;
        if(p25 && (systemWacn == null || systemId == null || systemWacn < 0 || systemWacn > 0xFFFFF ||
            systemId < 0 || systemId > 0xFFF))
        {
            return null;
        }
        if(p25 && learnedSites.stream().anyMatch(site -> site.wacn() != systemWacn ||
            site.system() != systemId))
        {
            return null;
        }
        Identifier target = identifiers.getToIdentifier();
        Identifier source = identifiers.getFromIdentifier();
        Integer destination = destinationId(target);
        Integer sourceRadio = source != null && source.getForm() == Form.RADIO ? destinationId(source) : null;
        ReceiverActivityRecords.P25Identity resolvedTargetIdentity = p25TargetIdentity(target, p25);
        ReceiverActivityRecords.P25Identity resolvedSourceIdentity = p25Identity(source, p25);
        String configurationId = ChannelConfigurationKey.canonical(winnerSource.channelConfigurationId());
        if(configurationId == null)
        {
            return null;
        }
        long timestamp = snapshot.startTimestamp() > 0 ? snapshot.startTimestamp() : snapshot.lastActivityTimestamp();

        if(timestamp <= 0)
        {
            return null;
        }

        List<ReceiverActivityRecords.P25SiteCallObservation> siteObservations = p25 ?
            p25SiteCallObservations(call.callLegSummaries(), systemWacn, systemId, target, source,
                resolvedTargetIdentity, resolvedSourceIdentity) : List.of();

        if(nxdn && !TrunkedIdentityEligibility.nxdnIdentifiersMatchDomain(identifiers,
            winnerSource.identityDomain()))
        {
            return null;
        }

        String radioSystemKey = null;
        if(dmr || nxdn)
        {
            Protocol receiverProtocol = dmr ? Protocol.DMR : Protocol.NXDN;
            radioSystemKey = winnerSource.radioSystemKey() != null ?
                RadioSystemKey.validateForReceiver(receiverProtocol, winnerSource.identityDomain(), configurationId,
                    winnerSource.radioSystemKey()) :
                RadioSystemKey.effectiveForReceiver(receiverProtocol, winnerSource.identityDomain(), configurationId,
                    null);
        }

        return new ReceiverActivityRecords.ResolvedLogicalCall(call.logicalCallId(), timestamp, configurationId,
            protocol, winnerSource.identityDomain(), systemWacn, systemId,
            destination != null ? destination : 0, facts.targetForm(),
            facts.patchMemberTalkgroupIds(), sourceRadio, snapshot.isEncrypted() || facts.encrypted(),
            facts.encryptionAlgorithmId(), facts.encryptionKeyId(), resolvedTargetIdentity,
            resolvedSourceIdentity, p25 ? facts.p25PatchMemberIdentities() : List.of(), siteObservations,
            radioSystemKey);
    }

    private static List<ReceiverActivityRecords.P25SiteCallObservation> p25SiteCallObservations(
        List<CallLegSummary> summaries, int systemWacn, int systemId, Identifier<?> resolvedTarget,
        Identifier<?> resolvedSource, ReceiverActivityRecords.P25Identity resolvedTargetIdentity,
        ReceiverActivityRecords.P25Identity resolvedSourceIdentity)
    {
        if(summaries == null)
        {
            return List.of();
        }

        java.util.Map<String,ReceiverActivityRecords.P25SiteCallObservation> observations =
            new java.util.LinkedHashMap<>();
        Integer resolvedTargetLocal = destinationId(resolvedTarget);
        Integer resolvedSourceLocal = destinationId(resolvedSource);

        List<CallLegSummary> ordered = summaries.stream().filter(java.util.Objects::nonNull)
            .sorted(java.util.Comparator.comparingLong(CallLegSummary::endTimestamp).reversed()
                .thenComparing(summary -> summary.callLegId().toString()))
            .toList();
        for(CallLegSummary summary: ordered)
        {
            CallLegSource legSource = summary != null ? summary.source() : null;
            P25SiteIdentity site = legSource != null ? legSource.p25SiteIdentity() : null;
            String legConfigurationId = legSource != null ?
                ChannelConfigurationKey.canonical(legSource.channelConfigurationId()) : null;
            if(site == null || legConfigurationId == null || site.wacn() != systemWacn ||
                site.system() != systemId)
            {
                continue;
            }

            CallLegSummary.P25IdentityObservation legTarget = summary.p25DestinationIdentity();
            CallLegSummary.P25IdentityObservation legSourceIdentity = summary.p25SourceIdentity();
            Integer localTarget = legTarget != null ? legTarget.observedLocalId() : null;
            Integer localSource = legSourceIdentity != null && legSourceIdentity.form() == Form.RADIO ?
                legSourceIdentity.observedLocalId() : null;
            ReceiverActivityRecords.P25Identity targetEvidence = p25Identity(legTarget);
            ReceiverActivityRecords.P25Identity sourceEvidence = p25Identity(legSourceIdentity);

            //An ordinary leg can share the exact local alias supplied by the resolved fully-qualified leg. Preserve
            //that canonical tuple while retaining this leg's own site-local observation.
            if(!targetEvidence.isStableFullyQualified() && resolvedTargetIdentity.isStableFullyQualified() &&
                localTarget != null && localTarget.equals(resolvedTargetLocal))
            {
                targetEvidence = resolvedTargetIdentity;
            }
            if(!sourceEvidence.isStableFullyQualified() && resolvedSourceIdentity.isStableFullyQualified() &&
                localSource != null && localSource.equals(resolvedSourceLocal))
            {
                sourceEvidence = resolvedSourceIdentity;
            }

            ReceiverActivityRecords.P25SiteCallObservation observation =
                new ReceiverActivityRecords.P25SiteCallObservation(legConfigurationId, site,
                localSource, localTarget, legTarget != null && legTarget.form() != null ?
                    legTarget.form().name() : null, targetEvidence, sourceEvidence,
                summary.p25PatchMemberIdentities().stream().map(CallLegSummary.P25IdentityObservation::observedLocalId)
                    .filter(value -> value > 0).toList(),
                summary.p25PatchMemberIdentities().stream()
                    .filter(memberObservation -> p25Identity(memberObservation).isStableFullyQualified())
                    .map(memberObservation -> new ReceiverActivityRecords.P25PatchMemberIdentity(
                        memberObservation.observedLocalId(), p25Identity(memberObservation)))
                    .toList());
            String key = legConfigurationId + ':' + site.wacn() + ':' + site.system() + ':' +
                site.rfss() + ':' + site.site();
            observations.putIfAbsent(key, observation);
        }

        return List.copyOf(observations.values());
    }

    private static ReceiverActivityRecords.P25Identity p25Identity(
        CallLegSummary.P25IdentityObservation observation)
    {
        if(observation == null)
        {
            return ReceiverActivityRecords.P25Identity.UNKNOWN;
        }
        if(observation.homeWacn() != null && observation.homeSystemId() != null &&
            observation.homeIdentityId() != null)
        {
            return observation.form() == Form.RADIO ?
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(observation.homeWacn(),
                    observation.homeSystemId(), observation.homeIdentityId()) :
                ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(observation.homeWacn(),
                    observation.homeSystemId(), observation.homeIdentityId());
        }
        return observation.form() == Form.RADIO || observation.form() == Form.TALKGROUP ||
            observation.form() == Form.PATCH_GROUP ? ReceiverActivityRecords.P25Identity.ORDINARY :
            ReceiverActivityRecords.P25Identity.UNKNOWN;
    }

    ReceiverActivityRecords.ConventionalCallOutput mapConventionalCallOutput(AudioCallSnapshot snapshot,
                                                                     ReceiverActivityRecords.CallOutput output)
    {
        if(snapshot == null || snapshot.identifierCollection() == null || output == null)
        {
            return null;
        }

        IdentifierCollection identifiers = snapshot.identifierCollection();
        IdentifierFacts facts = IdentifierFacts.from(identifiers);
        CallLegSource callLegSource = snapshot.callLegSource() != null ? snapshot.callLegSource() : CallLegSource.UNKNOWN;
        DecoderType sourceDecoder = callLegSource.decoderType();
        if(callLegSource.trafficChannel())
        {
            return null;
        }
        if(sourceDecoder == DecoderType.P25_PHASE1 || sourceDecoder == DecoderType.P25_PHASE2 ||
            DecoderType.P25_PHASE1.toString().equals(facts.decoder()) ||
            DecoderType.P25_PHASE2.toString().equals(facts.decoder()))
        {
            return null;
        }
        Identifier targetIdentifier = identifiers.getToIdentifier();
        Integer destination = destinationId(targetIdentifier);
        Identifier sourceIdentifier = identifiers.getFromIdentifier();
        Integer sourceRadio = sourceIdentifier != null && sourceIdentifier.getForm() == Form.RADIO ?
            destinationId(sourceIdentifier) : null;
        String configurationId = ChannelConfigurationKey.canonical(facts.configurationId());

        long timestamp = snapshot.startTimestamp() > 0 ? snapshot.startTimestamp() :
            snapshot.lastActivityTimestamp();

        if(timestamp <= 0)
        {
            timestamp = System.currentTimeMillis();
        }

        if(configurationId == null)
        {
            return null;
        }

        Integer timeslot = snapshot.timeslot() > 0 ? Integer.valueOf(snapshot.timeslot()) :
            facts.timeslot();
        if(sourceDecoder == DecoderType.NXDN &&
            !TrunkedIdentityEligibility.nxdnIdentifiersMatchDomain(identifiers, callLegSource.identityDomain()))
        {
            return null;
        }
        ReceiverActivityRecords.ReceiverKind receiverKind = conventionalReceiverKind(sourceDecoder);
        if(receiverKind == null)
        {
            return null;
        }
        String protocol = sourceDecoder != null && sourceDecoder.getProtocol() != null ?
            sourceDecoder.getProtocol().name() : Protocol.UNKNOWN.name();
        return new ReceiverActivityRecords.ConventionalCallOutput(timestamp, configurationId, receiverKind, protocol,
            facts.frequencyHertz(),
            timeslot, destination != null ? destination : 0, facts.targetForm(),
            facts.patchMemberTalkgroupIds(), sourceRadio, output,
            callLegSource.identityDomain(),
            p25TargetIdentity(targetIdentifier, isP25Decoder(facts.decoder())),
            facts.p25PatchMemberIdentities());
    }

    private ReceiverActivityRecords.ActivityEvent map(Channel channel, IDecodeEvent event,
                                                     ReceiverActivityRecords.Action actionOverride)
    {
        return map(channel, event, actionOverride, null);
    }

    private ReceiverActivityRecords.ActivityEvent map(Channel channel, IDecodeEvent event,
                                                       ReceiverActivityRecords.Action actionOverride,
                                                       String radioSystemKey)
    {
        if(channel == null || event == null || channel.getDecodeConfiguration() == null)
        {
            return null;
        }

        //DMR/NXDN voice statistics are owned by their immutable call-start/completion notifications. Mutable raw
        //tracker updates must never become a second call or action observation.
        if(actionOverride == null && isTypedCallOwnedObservation(channel, event))
        {
            return null;
        }

        IdentifierFacts facts = IdentifierFacts.from(event.getIdentifierCollection());
        TrunkedIdentityDomain configuredIdentityDomain = configuredIdentityDomain(channel);
        if(channel.getDecodeConfiguration().getDecoderType() == DecoderType.NXDN &&
            !TrunkedIdentityEligibility.nxdnIdentifiersMatchDomain(event.getIdentifierCollection(),
                configuredIdentityDomain))
        {
            return null;
        }
        IChannelDescriptor descriptor = event.getChannelDescriptor();
        Long frequency = frequency(descriptor, facts);
        String channelDescriptor = firstNonBlank(descriptor != null ? descriptor.toString() : null,
            facts.channelDescriptor(), facts.logicalChannelName());
        Integer timeslot = event.hasTimeslot() ? Integer.valueOf(event.getTimeslot()) : facts.timeslot();
        DecoderType decoderType = channel.getDecodeConfiguration().getDecoderType();
        ReceiverActivityRecords.Action action = actionOverride != null ? actionOverride :
            normalizeAction(event, decoderType);

        //NXDN has no TDMA slot. Its decode events use zero as a UI placeholder, while stored conventional calls and
        //completed outputs have no slot. Normalize them to one physical-channel summary key.
        if(decoderType == DecoderType.NXDN)
        {
            timeslot = null;
        }

        //The conventional traffic manager rebroadcasts its mutable tracker for desktop/event-log consumers. Statistics
        //use the one-time P25CallStartEvent instead, so tracker updates cannot create duplicate activity rows or counts.
        if(actionOverride == null && decoderType == DecoderType.P25_CONVENTIONAL &&
            event instanceof P25ChannelGrantEvent && event.getEventType() != null &&
            event.getEventType().isVoiceCallEvent())
        {
            return null;
        }

        ReceiverActivityRecords.ReceiverKind receiverKind = receiverKind(channel, decoderType);

        if(receiverKind == null)
        {
            return null;
        }

        if(actionOverride == null && (decoderType == DecoderType.DMR || decoderType == DecoderType.NXDN) &&
            !isUsefulProtocolSignaling(event.getEventType()))
        {
            return null;
        }

        long observedAt = Math.max(event.getTimeEnd(), event.getTimeStart());

        if(observedAt <= 0)
        {
            observedAt = System.currentTimeMillis();
        }

        String lcn = channelDescriptor;
        String protocol = protocolName(event.getProtocol(), facts, decoderType);
        String configurationId = ChannelConfigurationKey.configured(channel);

        if(configurationId == null)
        {
            return null;
        }

        P25AffiliationEvent affiliationEvent = event instanceof P25AffiliationEvent affiliation ? affiliation : null;
        String sourceRadioId = affiliationEvent != null && affiliationEvent.getRadioId() != null ?
            affiliationEvent.getRadioId().toString() : facts.sourceId();
        String targetId;
        String targetKind;

        if(affiliationEvent != null)
        {
            targetId = affiliationEvent.getTalkgroupId() != null ?
                affiliationEvent.getTalkgroupId().toString() : null;
            targetKind = affiliationEvent.getTalkgroupId() != null ? Form.TALKGROUP.name() : null;
        }
        else
        {
            targetId = facts.targetId();
            targetKind = facts.targetForm();
        }

        IdentifierCollection eventIdentifiers = event.getIdentifierCollection();
        Identifier targetIdentifier = affiliationEvent != null ? affiliationTarget(affiliationEvent,
            eventIdentifiers) : eventIdentifiers != null ? eventIdentifiers.getToIdentifier() : null;
        ReceiverActivityRecords.P25Identity p25TargetIdentity =
            p25TargetIdentity(targetIdentifier, isP25Decoder(decoderType));
        ReceiverActivityRecords.P25Identity p25SourceIdentity = affiliationEvent != null ?
            p25Identity(affiliationEvent.getRadioIdentifier(), true) :
            p25Identity(eventIdentifiers != null ? eventIdentifiers.getFromIdentifier() : null,
                isP25Decoder(decoderType));

        ReceiverActivityRecords.RadioPresenceUpdate radioPresenceUpdate = radioPresenceUpdate(affiliationEvent);
        boolean metricsEncrypted = facts.encrypted() && event instanceof P25ChannelGrantEvent grantEvent &&
            P25EncryptionConfirmationTracker.isConfirmed(grantEvent, facts.encryptionAlgorithmId(),
                facts.encryptionKeyId());
        Integer metricsAlgorithmId = metricsEncrypted ? facts.encryptionAlgorithmId() : null;
        Integer metricsKeyId = metricsEncrypted ? facts.encryptionKeyId() : null;

        String dedupeKey = null;
        if(actionOverride == null && (decoderType == DecoderType.DMR || decoderType == DecoderType.NXDN) &&
            isUsefulProtocolSignaling(event.getEventType()))
        {
            dedupeKey = protocolSignalingDedupeKey(configurationId, protocol, action, event, frequency,
                channelDescriptor, timeslot, sourceRadioId, targetId, targetKind);
        }
        else if(actionOverride == null &&
            (isHighChurnCallEvent(event.getEventType()) || action == ReceiverActivityRecords.Action.CONTINUE))
        {
            dedupeKey = String.join("|",
                safe(configurationId),
                safe(action),
                safe(frequency),
                safe(timeslot),
                safe(sourceRadioId),
                safe(targetId),
                safe(targetKind),
                safe(facts.patchMemberTalkgroupIds()),
                safe(metricsAlgorithmId),
                safe(metricsKeyId),
                receiverKind == ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_ANALOG &&
                    event.getEventType() != null && event.getEventType().isVoiceCallEvent() ?
                    Long.toString(event.getTimeStart()) : "");
        }

        return new ReceiverActivityRecords.ActivityEvent(observedAt, configurationId, receiverKind,
            protocol, action,
            event.getEventType() != null ? event.getEventType().name() : null, sourceRadioId, targetId,
            targetKind, facts.patchMemberTalkgroupIds(), frequency, lcn, timeslot, metricsEncrypted, metricsAlgorithmId,
            metricsKeyId, facts.wacn(), facts.systemId(), facts.nac(), facts.rfss(), facts.site(), facts.talkerAlias(),
            action == ReceiverActivityRecords.Action.CALL &&
                (receiverKind != ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE || actionOverride != null), dedupeKey,
            radioPresenceUpdate, configuredIdentityDomain, p25TargetIdentity,
            p25SourceIdentity,
            facts.p25PatchMemberIdentities(), radioSystemKey);
    }

    static boolean isTypedCallOwnedObservation(Channel channel, IDecodeEvent event)
    {
        if(channel == null || channel.getDecodeConfiguration() == null || event == null ||
            event.getEventType() == null || !event.getEventType().isVoiceCallEvent())
        {
            return false;
        }

        DecoderType decoderType = channel.getDecodeConfiguration().getDecoderType();
        return decoderType == DecoderType.DMR || decoderType == DecoderType.NXDN;
    }

    ReceiverActivityRecords.SiteSnapshot map(SiteMetadataEvent event)
    {
        if(event == null || event.receiverContext() == null || event.snapshot() == null ||
            !event.snapshot().isUseful() || !event.matchesCurrentChannel())
        {
            return null;
        }

        P25NetworkConfigurationSnapshot snapshot = event.snapshot();
        P25NetworkConfigurationSnapshot.CurrentSite currentSite = snapshot.currentSite();
        Integer wacn = snapshot.network() != null ? snapshot.network().wacn() : null;
        Integer system = snapshot.network() != null && snapshot.network().system() != null ?
            snapshot.network().system() : currentSite != null ? currentSite.system() : null;
        Integer nac = snapshot.network() != null && snapshot.network().nac() != null ?
            snapshot.network().nac() : currentSite != null ? currentSite.nac() : null;
        Integer rfss = currentSite != null ? currentSite.rfss() : null;
        Integer site = currentSite != null ? currentSite.site() : null;
        P25SiteIdentity coherentIdentity = P25SiteIdentity.from(snapshot);

        if(snapshot.network() != null && currentSite != null &&
            (snapshot.network().system() != null && currentSite.system() != null &&
                !snapshot.network().system().equals(currentSite.system()) ||
             snapshot.network().nac() != null && currentSite.nac() != null &&
                !snapshot.network().nac().equals(currentSite.nac())))
        {
            //Network and current-site signaling must describe one coherent source even while RFSS/Site is partial.
            return null;
        }

        if(wacn != null && system != null && rfss != null && site != null && coherentIdentity == null)
        {
            //Do not combine independently stabilized values into a site identity that the P25 tuple itself rejects.
            return null;
        }

        if(coherentIdentity != null)
        {
            if(!event.isSourceAdvertisedControlChannel())
            {
                //A stale snapshot from a previous tuning epoch cannot establish the saved receiver's site.
                return null;
            }

            wacn = coherentIdentity.wacn();
            system = coherentIdentity.system();
            rfss = coherentIdentity.rfss();
            site = coherentIdentity.site();
        }
        Integer lra = currentSite != null && currentSite.lra() != null ? currentSite.lra() :
            snapshot.network() != null ? snapshot.network().lra() : null;
        Boolean activeRfssNetworkConnection = currentSite != null ? currentSite.activeRfssNetworkConnection() : null;
        Boolean tdma = hasTdma(snapshot);
        Long currentControl = currentControl(snapshot.channels());
        String hash = sha256(String.join("|", safe(snapshot.decoder()), safe(snapshot.network()),
            safe(snapshot.currentSite()), safe(snapshot.channels()), safe(snapshot.neighborSites()),
            safe(snapshot.frequencyBands()), safe(snapshot.patchGroups()),
            safe(snapshot.siteStatus() != null ? snapshot.siteStatus().withoutVolatileTiming() : null),
            safe(snapshot.foreignSystemBands())));
        String configurationId = event.receiverContext().configurationId();

        if(configurationId == null)
        {
            return null;
        }

        return new ReceiverActivityRecords.SiteSnapshot(event.observedAtEpochMilliseconds(), configurationId,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, hash, Protocol.APCO25.name(),
            wacn, system, nac, rfss, site, lra, activeRfssNetworkConnection, tdma, snapshot.siteStatus(),
            event.sourceFrequency(), currentControl, currentControl,
            snapshot.channels(), snapshot.neighborSites(),
            snapshot.frequencyBands(), snapshot.patchGroups(), snapshot.foreignSystemBands());
    }

    private static Boolean hasTdma(P25NetworkConfigurationSnapshot snapshot)
    {
        boolean observed = false;

        for(P25NetworkConfigurationSnapshot.FrequencyBand band: safeList(snapshot.frequencyBands()))
        {
            if(band.tdma() != null)
            {
                observed = true;
                if(band.tdma())
                {
                    return true;
                }
            }
        }

        for(P25NetworkConfigurationSnapshot.Channel channel: safeList(snapshot.channels()))
        {
            if(channel.tdma() != null)
            {
                observed = true;
                if(channel.tdma())
                {
                    return true;
                }
            }
        }

        return observed ? false : null;
    }

    private static <T> List<T> safeList(List<T> values)
    {
        return values != null ? values : List.of();
    }

    private static boolean isHighChurnCallEvent(DecodeEventType eventType)
    {
        return eventType != null && (eventType.isVoiceCallEvent() ||
            DecodeEventType.DATA_CALLS.contains(eventType) ||
            eventType == DecodeEventType.CALL_NO_TUNER ||
            eventType == DecodeEventType.CALL_DO_NOT_MONITOR);
    }

    private static ReceiverActivityRecords.Action normalizeAction(IDecodeEvent event, DecoderType decoderType)
    {
        if(event instanceof P25AffiliationEvent affiliationEvent)
        {
            return switch(affiliationEvent.getOutcome())
            {
                case REQUESTED -> ReceiverActivityRecords.Action.REQUEST;
                case ACCEPTED -> event.getEventType() == DecodeEventType.REGISTER ?
                    ReceiverActivityRecords.Action.REGISTER : ReceiverActivityRecords.Action.JOIN;
                case CONFIRMED -> ReceiverActivityRecords.Action.CHECK_ACK;
                case REJECTED -> ReceiverActivityRecords.Action.DENIAL;
                case CLEARED -> ReceiverActivityRecords.Action.LOGOUT;
                case UNRESOLVED -> event.getEventType() == DecodeEventType.REGISTER ?
                    ReceiverActivityRecords.Action.REGISTER : ReceiverActivityRecords.Action.UNKNOWN;
            };
        }

        DecodeEventType eventType = event.getEventType();
        String details = event.getDetails() != null ? event.getDetails().toUpperCase(Locale.ROOT) : "";
        boolean protocolSignaling = decoderType == DecoderType.DMR || decoderType == DecoderType.NXDN ||
            event.getProtocol() == Protocol.DMR || event.getProtocol() == Protocol.NXDN;
        boolean useDetailHeuristics = decoderType != DecoderType.DMR && decoderType != DecoderType.NXDN &&
            event.getProtocol() != Protocol.DMR && event.getProtocol() != Protocol.NXDN;

        if(protocolSignaling && eventType == DecodeEventType.COMMAND && "REGISTER".equals(details.strip()))
        {
            return ReceiverActivityRecords.Action.REGISTER;
        }
        if(protocolSignaling && eventType == DecodeEventType.RESPONSE &&
            "ALOHA ACKNOWLEDGE".equals(details.strip()))
        {
            return ReceiverActivityRecords.Action.ACKNOWLEDGE;
        }
        if(eventType == DecodeEventType.DEREGISTER)
        {
            return ReceiverActivityRecords.Action.LOGOUT;
        }
        if(eventType == DecodeEventType.DENIAL)
        {
            return ReceiverActivityRecords.Action.DENIAL;
        }
        if(eventType == DecodeEventType.AFFILIATE)
        {
            return ReceiverActivityRecords.Action.JOIN;
        }
        if(eventType == DecodeEventType.REGISTER || eventType == DecodeEventType.REGISTER_ESN ||
            eventType == DecodeEventType.RADIO_REGISTRATION_SERVICE ||
            eventType == DecodeEventType.AUTOMATIC_REGISTRATION_SERVICE)
        {
            return ReceiverActivityRecords.Action.REGISTER;
        }
        if(eventType == DecodeEventType.ACKNOWLEDGE)
        {
            return ReceiverActivityRecords.Action.ACKNOWLEDGE;
        }
        if(eventType == DecodeEventType.RADIO_CHECK)
        {
            return ReceiverActivityRecords.Action.CHECK;
        }
        if(eventType == DecodeEventType.PAGE || eventType == DecodeEventType.CALL_ALERT)
        {
            return ReceiverActivityRecords.Action.PAGE;
        }
        if(eventType == DecodeEventType.REQUEST)
        {
            return ReceiverActivityRecords.Action.REQUEST;
        }
        if(eventType == DecodeEventType.STATUS)
        {
            return ReceiverActivityRecords.Action.STATUS;
        }
        if(eventType == DecodeEventType.EMERGENCY)
        {
            return ReceiverActivityRecords.Action.EMERGENCY;
        }
        if(eventType == DecodeEventType.GPS)
        {
            return ReceiverActivityRecords.Action.GPS;
        }
        if(eventType == DecodeEventType.DYNAMIC_REGROUP)
        {
            if(details.contains("CANCEL") || details.contains("DEACTIVATE") || details.contains("DELETE"))
            {
                return ReceiverActivityRecords.Action.PATCH_CANCEL;
            }
            if(details.contains("ACTIVATE") || details.contains("CREATE"))
            {
                return ReceiverActivityRecords.Action.PATCH_CREATE;
            }

            return ReceiverActivityRecords.Action.PATCH;
        }
        if(eventType == DecodeEventType.QUERY)
        {
            return ReceiverActivityRecords.Action.CHECK;
        }
        if(useDetailHeuristics && details.contains("UNIT REGISTRATION"))
        {
            return ReceiverActivityRecords.Action.REGISTER;
        }
        if(useDetailHeuristics && details.contains("RADIO CHECK ACK"))
        {
            return ReceiverActivityRecords.Action.CHECK_ACK;
        }
        if(useDetailHeuristics && details.contains("RADIO CHECK"))
        {
            return ReceiverActivityRecords.Action.CHECK;
        }
        if(useDetailHeuristics &&
            (details.contains("DENY") || details.contains("DENIED") || details.contains("DENIAL")))
        {
            return ReceiverActivityRecords.Action.DENIAL;
        }
        if(useDetailHeuristics &&
            (details.contains("BUSY") || details.contains("TARGET_GROUP_CURRENTLY_ACTIVE")))
        {
            return ReceiverActivityRecords.Action.BUSY;
        }
        if(useDetailHeuristics && details.contains("QUEUED"))
        {
            return ReceiverActivityRecords.Action.QUEUED;
        }
        if(useDetailHeuristics && details.contains("ACKNOWLEDGE"))
        {
            return ReceiverActivityRecords.Action.ACKNOWLEDGE;
        }
        if(event instanceof P25ChannelGrantEvent)
        {
            return ReceiverActivityRecords.Action.ACTIVE;
        }
        if(eventType != null && eventType.isVoiceCallEvent())
        {
            return ReceiverActivityRecords.Action.CALL;
        }
        if(eventType != null && (DecodeEventType.DATA_CALLS.contains(eventType) ||
            eventType == DecodeEventType.LRRP || eventType == DecodeEventType.SDM ||
            eventType == DecodeEventType.SMS || eventType == DecodeEventType.TEXT_MESSAGE ||
            eventType == DecodeEventType.UNKNOWN_PACKET || eventType == DecodeEventType.XCMP))
        {
            return ReceiverActivityRecords.Action.DATA;
        }

        return ReceiverActivityRecords.Action.UNKNOWN;
    }

    private static ReceiverActivityRecords.RadioPresenceUpdate radioPresenceUpdate(
        P25AffiliationEvent affiliationEvent)
    {
        if(affiliationEvent == null || affiliationEvent.getRadioId() == null)
        {
            return null;
        }

        ReceiverActivityRecords.P25Identity radioIdentity =
            p25Identity(affiliationEvent.getRadioIdentifier(), true);
        ReceiverActivityRecords.P25Identity talkgroupIdentity =
            p25TargetIdentity(affiliationEvent.getTalkgroupIdentifier(), true);
        int radioId = affiliationEvent.getRadioId();
        Integer talkgroupId = affiliationEvent.getTalkgroupId();
        boolean validRadio = radioId > 0 || radioId == 0 && radioIdentity.isStableFullyQualified();
        boolean validTalkgroup = talkgroupId == null || talkgroupId > 0 ||
            talkgroupId == 0 && talkgroupIdentity.isStableFullyQualified();
        if(!validRadio || !validTalkgroup)
        {
            return null;
        }

        ReceiverActivityRecords.RadioPresenceEvidence evidence =
            affiliationEvent.getEventType() == DecodeEventType.REGISTER ?
                ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION :
                ReceiverActivityRecords.RadioPresenceEvidence.AFFILIATION;

        return switch(affiliationEvent.getOutcome())
        {
            case ACCEPTED, CONFIRMED -> ReceiverActivityRecords.RadioPresenceUpdate.confirmed(
                radioId, talkgroupId, evidence, radioIdentity, talkgroupIdentity);
            case CLEARED -> ReceiverActivityRecords.RadioPresenceUpdate.cleared(radioId, radioIdentity);
            case REQUESTED, REJECTED, UNRESOLVED -> null;
        };
    }

    private static Long frequency(IChannelDescriptor descriptor, IdentifierFacts facts)
    {
        if(descriptor != null && descriptor.getDownlinkFrequency() > 0)
        {
            return descriptor.getDownlinkFrequency();
        }

        Long identifierFrequency = facts.frequencyHertz();
        return identifierFrequency != null && identifierFrequency > 0 ? identifierFrequency : null;
    }

    private static Integer talkgroup(Identifier identifier)
    {
        if(identifier instanceof TalkgroupIdentifier talkgroup)
        {
            return talkgroup.getValue();
        }

        if(identifier instanceof PatchGroupIdentifier patchGroup && patchGroup.getValue() != null &&
            patchGroup.getValue().getPatchGroup() != null)
        {
            return patchGroup.getValue().getPatchGroup().getValue();
        }

        return null;
    }

    private static ReceiverActivityRecords.P25Identity p25TargetIdentity(Identifier identifier,
                                                                              boolean p25Decoder)
    {
        Identifier primary = identifier;

        if(identifier instanceof PatchGroupIdentifier patchGroup && patchGroup.getValue() != null)
        {
            primary = patchGroup.getValue().getPatchGroup();
        }

        if(primary instanceof IncompleteIdentifier)
        {
            return ReceiverActivityRecords.P25Identity.UNKNOWN;
        }

        if(primary instanceof FullyQualifiedTalkgroupIdentifier fullyQualified &&
            fullyQualified.getProtocol() == Protocol.APCO25)
        {
            return ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(fullyQualified.getWacn(),
                fullyQualified.getSystem(), fullyQualified.getTalkgroup());
        }

        if(primary instanceof FullyQualifiedRadioIdentifier fullyQualified &&
            fullyQualified.getProtocol() == Protocol.APCO25)
        {
            return ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(fullyQualified.getWacn(),
                fullyQualified.getSystem(), fullyQualified.getRadio());
        }

        return (p25Decoder || primary != null && primary.getProtocol() == Protocol.APCO25) &&
            (primary instanceof TalkgroupIdentifier || primary instanceof RadioIdentifier) ?
            ReceiverActivityRecords.P25Identity.ORDINARY :
            ReceiverActivityRecords.P25Identity.UNKNOWN;
    }

    private static ReceiverActivityRecords.P25Identity p25Identity(Identifier identifier, boolean p25Decoder)
    {
        if(identifier instanceof IncompleteIdentifier)
        {
            return ReceiverActivityRecords.P25Identity.UNKNOWN;
        }

        if(identifier instanceof FullyQualifiedRadioIdentifier fullyQualified &&
            fullyQualified.getProtocol() == Protocol.APCO25)
        {
            return ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(fullyQualified.getWacn(),
                fullyQualified.getSystem(), fullyQualified.getRadio());
        }

        if(identifier instanceof FullyQualifiedTalkgroupIdentifier fullyQualified &&
            fullyQualified.getProtocol() == Protocol.APCO25)
        {
            return ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(fullyQualified.getWacn(),
                fullyQualified.getSystem(), fullyQualified.getTalkgroup());
        }

        return (p25Decoder || identifier != null && identifier.getProtocol() == Protocol.APCO25) &&
            (identifier instanceof RadioIdentifier || identifier instanceof TalkgroupIdentifier) ?
            ReceiverActivityRecords.P25Identity.ORDINARY : ReceiverActivityRecords.P25Identity.UNKNOWN;
    }

    private static List<ReceiverActivityRecords.P25PatchMemberIdentity> p25PatchMemberIdentities(
        Identifier identifier)
    {
        if(!(identifier instanceof PatchGroupIdentifier patchGroup) || patchGroup.getValue() == null)
        {
            return List.of();
        }

        List<ReceiverActivityRecords.P25PatchMemberIdentity> identities = new ArrayList<>();
        for(TalkgroupIdentifier member: patchGroup.getValue().getPatchedTalkgroupIdentifiers())
        {
            if(member == null || member.getValue() == null || member.getValue() < 0 ||
                member.getProtocol() != Protocol.APCO25)
            {
                continue;
            }

            ReceiverActivityRecords.P25Identity targetIdentity = p25TargetIdentity(member, true);
            if(targetIdentity.isStableFullyQualified())
            {
                identities.add(new ReceiverActivityRecords.P25PatchMemberIdentity(member.getValue(), targetIdentity));
            }
        }

        return List.copyOf(identities);
    }

    private static Identifier affiliationTarget(P25AffiliationEvent affiliation,
                                                IdentifierCollection identifiers)
    {
        if(affiliation == null || affiliation.getTalkgroupId() == null)
        {
            return null;
        }

        if(affiliation.getTalkgroupIdentifier() != null)
        {
            return affiliation.getTalkgroupIdentifier();
        }

        if(identifiers == null)
        {
            return null;
        }

        Identifier ordinaryMatch = null;

        for(Identifier identifier: identifiers.getIdentifiers())
        {
            Integer value = talkgroup(identifier);

            if(value != null && value.equals(affiliation.getTalkgroupId()))
            {
                Identifier primary = identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null ?
                    patch.getValue().getPatchGroup() : identifier;

                if(primary instanceof FullyQualifiedTalkgroupIdentifier)
                {
                    return identifier;
                }

                ordinaryMatch = identifier;
            }
        }

        return ordinaryMatch;
    }

    private static boolean isP25Decoder(DecoderType decoderType)
    {
        return decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2 ||
            decoderType == DecoderType.P25_CONVENTIONAL;
    }

    private static boolean isP25Decoder(String decoder)
    {
        return DecoderType.P25_PHASE1.toString().equals(decoder) ||
            DecoderType.P25_PHASE2.toString().equals(decoder) ||
            DecoderType.P25_CONVENTIONAL.toString().equals(decoder);
    }

    private static Integer destinationId(Identifier identifier)
    {
        Integer talkgroup = talkgroup(identifier);

        if(talkgroup != null && (talkgroup > 0 || talkgroup == 0 && hasStableP25Home(identifier)))
        {
            return talkgroup;
        }

        if(identifier == null || identifier.getValue() == null)
        {
            return null;
        }

        try
        {
            int parsed = identifier.getValue() instanceof Number number ? number.intValue() :
                Integer.parseInt(identifier.getValue().toString());
            return parsed > 0 || parsed == 0 && hasStableP25Home(identifier) ? parsed : null;
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }

    private static boolean hasStableP25Home(Identifier identifier)
    {
        if(identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null)
        {
            identifier = patch.getValue().getPatchGroup();
        }
        return identifier instanceof FullyQualifiedTalkgroupIdentifier talkgroup &&
            talkgroup.getProtocol() == Protocol.APCO25 ||
            identifier instanceof FullyQualifiedRadioIdentifier radio && radio.getProtocol() == Protocol.APCO25;
    }

    private static String targetValue(Identifier identifier)
    {
        if(identifier instanceof PatchGroupIdentifier)
        {
            Integer patchGroup = talkgroup(identifier);
            return patchGroup != null ? patchGroup.toString() : null;
        }

        return identifier != null && identifier.getValue() != null ? identifier.getValue().toString() : null;
    }

    private static List<Integer> patchMemberTalkgroups(Identifier identifier)
    {
        if(!(identifier instanceof PatchGroupIdentifier patchGroup) || patchGroup.getValue() == null)
        {
            return List.of();
        }

        Integer canonical = talkgroup(identifier);
        return patchGroup.getValue().getPatchedTalkgroupIdentifiers().stream()
            .filter(member -> member != null && member.getValue() != null && member.getValue() > 0)
            .map(TalkgroupIdentifier::getValue)
            .filter(member -> !member.equals(canonical))
            .distinct()
            .sorted()
            .toList();
    }

    private static Integer positive(Integer value)
    {
        return value != null && value > 0 && value <= DmrActivitySchema.MAXIMUM_DMR_ID ? value : null;
    }

    private static Integer positiveNxdn(Integer value)
    {
        return value != null && value > 0 && value <= 0xFFFF ? value : null;
    }

    private static TrunkedIdentityDomain configuredIdentityDomain(Channel channel)
    {
        if(channel != null && channel.getDecodeConfiguration() instanceof DecodeConfigNXDN config)
        {
            return config.getTransmissionMode() != null && config.getTransmissionMode().isTypeD() ?
                TrunkedIdentityDomain.NXDN_TYPE_D : TrunkedIdentityDomain.NXDN_TYPE_C;
        }

        return TrunkedIdentityDomain.STANDARD;
    }

    private static String protocolName(Protocol protocol, IdentifierFacts facts, DecoderType decoderType)
    {
        //Activity protocol identifies the configured air interface. Some DMR data applications label their payload
        //protocol (for example LRRP), which must not relabel the saved receiver channel.
        if(decoderType == DecoderType.DMR)
        {
            return Protocol.DMR.name();
        }

        if(decoderType == DecoderType.NXDN)
        {
            return Protocol.NXDN.name();
        }

        if(decoderType == DecoderType.P25_PHASE2)
        {
            return Protocol.APCO25_PHASE2.name();
        }

        if(decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_CONVENTIONAL)
        {
            return Protocol.APCO25.name();
        }

        if(protocol != null && protocol != Protocol.UNKNOWN)
        {
            return protocol.name();
        }

        if(facts != null && facts.decoder() != null)
        {
            return facts.decoder();
        }

        return decoderType != null ? decoderType.name() : "UNKNOWN";
    }

    private static String configuredProtocolName(DecoderType decoderType, Protocol fallback)
    {
        return protocolName(fallback, null, decoderType);
    }

    private static ReceiverActivityRecords.ReceiverKind receiverKind(Channel channel, DecoderType decoderType)
    {
        if(decoderType == DecoderType.P25_CONVENTIONAL)
        {
            return ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25;
        }

        if(decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2)
        {
            return ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE;
        }

        if(decoderType == DecoderType.AM || decoderType == DecoderType.NBFM)
        {
            return ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_ANALOG;
        }

        if(decoderType == DecoderType.DMR &&
            channel.getDecodeConfiguration() instanceof DecodeConfigDMR config)
        {
            return config.isTrunked() ? ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE :
                ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_DMR;
        }

        if(decoderType == DecoderType.NXDN &&
            channel.getDecodeConfiguration() instanceof DecodeConfigNXDN config)
        {
            return config.isTrunked() ? ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE :
                ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_NXDN;
        }

        return null;
    }

    /**
     * Maps the immutable source decoder carried by a completed conventional call.  The producer only invokes this
     * path for conventional decoders, so DMR and NXDN do not need the live Channel object to distinguish topology.
     */
    private static ReceiverActivityRecords.ReceiverKind conventionalReceiverKind(DecoderType decoderType)
    {
        return switch(decoderType)
        {
            case P25_CONVENTIONAL -> ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25;
            case DMR -> ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_DMR;
            case NXDN -> ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_NXDN;
            case AM, NBFM -> ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_ANALOG;
            case null, default -> null;
        };
    }

    /**
     * Keeps semantic signaling observations while excluding frame progress, mutable call updates and decoder noise.
     * Message bodies are never retained.
     */
    private static boolean isUsefulProtocolSignaling(DecodeEventType eventType)
    {
        return eventType != null && switch(eventType)
        {
            case ACKNOWLEDGE, AFFILIATE, AUTOMATIC_REGISTRATION_SERVICE, CALL_ALERT, COMMAND, DATA_CALL,
                 DATA_CALL_ENCRYPTED, DATA_PACKET, DENIAL, DEREGISTER, EMERGENCY, GPS, LRRP, PAGE, QUERY, RADIO_CHECK,
                 RADIO_REGISTRATION_SERVICE, REGISTER, REGISTER_ESN, REQUEST, RESPONSE, SDM, SMS, STATUS,
                 TEXT_MESSAGE, XCMP -> true;
            default -> false;
        };
    }

    /**
     * Creates a compact key for one semantic DMR/NXDN signaling operation. The detail digest differentiates known
     * command and response subtypes without retaining message bodies. Event type, participants, physical channel and
     * slot remain separate key components so unrelated operations can never suppress one another.
     */
    private static String protocolSignalingDedupeKey(String configurationId, String protocol,
                                                      ReceiverActivityRecords.Action action, IDecodeEvent event,
                                                      Long frequency, String channelDescriptor, Integer timeslot,
                                                      String sourceRadioId, String targetId, String targetKind)
    {
        String details = event.getDetails();
        String subtype = details != null && !details.isBlank() ?
            sha256(details.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT)) : "";
        return PROTOCOL_SIGNAL_DEDUPE_PREFIX + String.join("|",
            safe(configurationId),
            safe(protocol),
            safe(event.getEventType()),
            subtype,
            safe(action),
            safe(sourceRadioId),
            safe(targetId),
            safe(targetKind),
            safe(frequency),
            safe(channelDescriptor),
            safe(timeslot));
    }

    private static Long currentControl(List<P25NetworkConfigurationSnapshot.Channel> channels)
    {
        if(channels == null)
        {
            return null;
        }

        return channels.stream()
            .filter(channel -> channel != null && "primary_control".equals(channel.role()))
            .map(P25NetworkConfigurationSnapshot.Channel::downlink)
            .filter(frequency -> frequency != null && frequency > 0)
            .findFirst()
            .orElse(null);
    }

    private static String sha256(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException e)
        {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String safe(Object value)
    {
        return value != null ? value.toString() : "";
    }

    private static String blankToNull(String value)
    {
        return value != null && !value.isBlank() ? value : null;
    }

    private static String firstNonBlank(String... values)
    {
        if(values != null)
        {
            for(String value: values)
            {
                String nonBlank = blankToNull(value);

                if(nonBlank != null)
                {
                    return nonBlank;
                }
            }
        }

        return null;
    }

    private record IdentifierFacts(String sourceId, String sourceForm, String targetId, String targetForm,
                                   List<Integer> patchMemberTalkgroupIds,
                                   List<ReceiverActivityRecords.P25PatchMemberIdentity> p25PatchMemberIdentities,
                                   Long frequencyHertz,
                                   String channelDescriptor, String logicalChannelName, boolean encrypted,
                                   Integer encryptionAlgorithmId, Integer encryptionKeyId, Integer wacn,
                                   Integer systemId, Integer nac, Integer rfss, Integer site,
                                   String configurationId, String decoder,
                                   String talkerAlias, Integer timeslot)
    {
        static IdentifierFacts from(IdentifierCollection identifiers)
        {
            Identifier source = identifiers != null ? identifiers.getFromIdentifier() : null;
            Identifier target = identifiers != null ? identifiers.getToIdentifier() : null;
            EncryptionKeyIdentifier encryptionIdentifier = encryptionIdentifier(identifiers);
            EncryptionKey encryptionKey = encryptionIdentifier != null ? encryptionIdentifier.getValue() : null;
            Integer timeslot = identifiers != null && identifiers.getTimeslot() > 0 ? identifiers.getTimeslot() : null;
            String sourceForm = form(source);

            return new IdentifierFacts(Form.RADIO.name().equals(sourceForm) ? value(source) : null, sourceForm,
                targetValue(target), form(target), patchMemberTalkgroups(target),
                ReceiverActivityMapper.p25PatchMemberIdentities(target),
                longValue(first(identifiers, Form.CHANNEL_FREQUENCY)),
                value(first(identifiers, Form.CHANNEL_DESCRIPTOR)), value(first(identifiers, Form.CHANNEL_NAME)),
                encryptionIdentifier != null && encryptionIdentifier.isEncrypted(),
                encryptionKey != null && encryptionKey.isEncrypted() ? encryptionKey.getAlgorithm() : null,
                encryptionKey != null && encryptionKey.isEncrypted() ? encryptionKey.getKey() : null,
                intValue(first(identifiers, Form.WACN)), intValue(first(identifiers, Form.SYSTEM)),
                intValue(first(identifiers, Form.NETWORK_ACCESS_CODE)),
                intValue(first(identifiers, Form.RF_SUBSYSTEM)), intValue(first(identifiers, Form.SITE)),
                value(first(identifiers, Form.UNIQUE_ID)), value(first(identifiers, Form.DECODER_TYPE)),
                value(first(identifiers, Form.TALKER_ALIAS)), timeslot);
        }

        boolean hasTrunkedSiteIdentity()
        {
            return wacn != null || systemId != null || rfss != null || site != null;
        }

        private static Identifier first(IdentifierCollection identifiers, Form form)
        {
            if(identifiers == null)
            {
                return null;
            }

            List<Identifier> matches = identifiers.getIdentifiers(form);
            return matches.isEmpty() ? null : matches.get(0);
        }

        private static EncryptionKeyIdentifier encryptionIdentifier(IdentifierCollection identifiers)
        {
            if(identifiers == null)
            {
                return null;
            }

            Identifier identifier = identifiers.getEncryptionIdentifier();
            return identifier instanceof EncryptionKeyIdentifier encryptionKeyIdentifier ? encryptionKeyIdentifier : null;
        }

        private static String value(Identifier identifier)
        {
            if(identifier == null || identifier.getValue() == null)
            {
                return null;
            }

            return identifier.getValue().toString();
        }

        private static String form(Identifier identifier)
        {
            return identifier != null && identifier.getForm() != null ? identifier.getForm().name() : null;
        }

        private static Integer intValue(Identifier identifier)
        {
            if(identifier == null || identifier.getValue() == null)
            {
                return null;
            }

            if(identifier.getValue() instanceof Number number)
            {
                return number.intValue();
            }

            try
            {
                return Integer.parseInt(identifier.getValue().toString());
            }
            catch(NumberFormatException e)
            {
                return null;
            }
        }

        private static Long longValue(Identifier identifier)
        {
            if(identifier == null || identifier.getValue() == null)
            {
                return null;
            }

            if(identifier.getValue() instanceof Number number)
            {
                return number.longValue();
            }

            try
            {
                return Long.parseLong(identifier.getValue().toString());
            }
            catch(NumberFormatException e)
            {
                return null;
            }
        }

    }
}
