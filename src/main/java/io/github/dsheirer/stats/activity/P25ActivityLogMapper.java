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
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
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
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25EncryptionConfirmationTracker;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25DecodeEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
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
class P25ActivityLogMapper
{
    static final String PROTOCOL_SIGNAL_DEDUPE_PREFIX = "protocol-signal|";

    P25ActivityLogRecords.DmrConventionalCall map(DMRConventionalCallEvent event)
    {
        if(event == null || event.startTimestamp() <= 0 || event.endTimestamp() < event.startTimestamp() ||
            event.frequencyHertz() <= 0 || (event.timeslot() != 1 && event.timeslot() != 2) ||
            event.targetKind() == null)
        {
            return null;
        }

        String guid = blankToNull(event.guid());
        String configurationId = blankToNull(event.channelConfigurationId());
        String channelName = blankToNull(event.channelName());
        String contextKey = ReceiverContextKey.configured(guid, configurationId);

        if(contextKey == null)
        {
            contextKey = ReceiverContextKey.conventionalWithChannelName(
                P25ActivityLogRecords.ContextKind.CONVENTIONAL_DMR, Protocol.DMR.name(), event.frequencyHertz(),
                channelName);
        }

        P25ActivityLogRecords.DmrTargetKind targetKind = switch(event.targetKind())
        {
            case GROUP -> P25ActivityLogRecords.DmrTargetKind.GROUP;
            case PRIVATE -> P25ActivityLogRecords.DmrTargetKind.PRIVATE;
            case UNKNOWN -> P25ActivityLogRecords.DmrTargetKind.UNKNOWN;
        };
        Integer talkgroup = positive(event.talkgroupId());
        Integer sourceRadio = positive(event.sourceRadioId());
        Integer targetRadio = positive(event.targetRadioId());

        if(targetKind != P25ActivityLogRecords.DmrTargetKind.GROUP)
        {
            talkgroup = null;
        }

        if(targetKind != P25ActivityLogRecords.DmrTargetKind.PRIVATE)
        {
            targetRadio = null;
        }

        return new P25ActivityLogRecords.DmrConventionalCall(event.startTimestamp(), event.endTimestamp(),
            contextKey, guid, channelName, blankToNull(event.aliasListName()), event.frequencyHertz(),
            event.timeslot(), targetKind, talkgroup, sourceRadio, targetRadio, event.encrypted());
    }

    P25ActivityLogRecords.NxdnConventionalCall map(NXDNConventionalCallEvent event)
    {
        if(event == null || event.startTimestamp() <= 0 || event.endTimestamp() < event.startTimestamp() ||
            event.frequencyHertz() <= 0 || event.targetKind() == null)
        {
            return null;
        }

        String guid = blankToNull(event.guid());
        String configurationId = blankToNull(event.channelConfigurationId());
        String channelName = blankToNull(event.channelName());
        String contextKey = ReceiverContextKey.configured(guid, configurationId);

        if(contextKey == null)
        {
            contextKey = ReceiverContextKey.conventionalWithChannelName(
                P25ActivityLogRecords.ContextKind.CONVENTIONAL_NXDN, Protocol.NXDN.name(), event.frequencyHertz(),
                channelName);
        }
        P25ActivityLogRecords.NxdnTargetKind targetKind = switch(event.targetKind())
        {
            case GROUP -> P25ActivityLogRecords.NxdnTargetKind.GROUP;
            case PRIVATE -> P25ActivityLogRecords.NxdnTargetKind.PRIVATE;
            case UNKNOWN -> P25ActivityLogRecords.NxdnTargetKind.UNKNOWN;
        };
        Integer talkgroup = positiveNxdn(event.talkgroupId());
        Integer sourceRadio = positiveNxdn(event.sourceRadioId());
        Integer targetRadio = positiveNxdn(event.targetRadioId());

        if(targetKind != P25ActivityLogRecords.NxdnTargetKind.GROUP)
        {
            talkgroup = null;
        }

        if(targetKind != P25ActivityLogRecords.NxdnTargetKind.PRIVATE)
        {
            targetRadio = null;
        }

        return new P25ActivityLogRecords.NxdnConventionalCall(event.startTimestamp(), event.endTimestamp(),
            contextKey, guid, channelName, blankToNull(event.aliasListName()), event.frequencyHertz(),
            targetKind, talkgroup, sourceRadio, targetRadio, event.encrypted());
    }

    P25ActivityLogRecords.TalkerAliasUpdate map(TrunkedTalkerAliasEvent event)
    {
        if(event == null || event.channel() == null || event.radio() == null || event.alias() == null ||
            event.alias().getValue() == null || event.alias().getValue().toString().isBlank() ||
            event.protocol() == null || event.protocol() == Protocol.UNKNOWN)
        {
            return null;
        }

        DecoderType decoderType = event.channel().getDecodeConfiguration() != null ?
            event.channel().getDecodeConfiguration().getDecoderType() : null;

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

        IdentifierFacts facts = IdentifierFacts.from(event.identifiers());
        String guid = firstNonBlank(event.channel().getRadresGuid(), facts.radresGuid());
        String contextKey = contextKey(guid, protocolName(event.protocol(), facts, decoderType), facts, null,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, event.channel().getName(),
            event.channel().getConfigurationId());

        if(contextKey == null)
        {
            return null;
        }

        long observedAt = event.timestamp() > 0 ? event.timestamp() : System.currentTimeMillis();
        return new P25ActivityLogRecords.TalkerAliasUpdate(observedAt, contextKey, guid, facts.wacn(),
            facts.systemId(), event.radio().getValue(), event.alias().getValue().toString().trim(),
            identityDomain(event.identityDomain()));
    }

    private static P25ActivityLogRecords.IdentityDomain identityDomain(TrunkedIdentityDomain domain)
    {
        return switch(domain != null ? domain : TrunkedIdentityDomain.STANDARD)
        {
            case STANDARD -> P25ActivityLogRecords.IdentityDomain.STANDARD;
            case NXDN_TYPE_C -> P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C;
            case NXDN_TYPE_D -> P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D;
        };
    }

    P25ActivityLogRecords.ActivityEvent map(Channel channel, IDecodeEvent event)
    {
        return map(channel, event, null);
    }

    P25ActivityLogRecords.ActivityEvent map(P25CallStartEvent callStart)
    {
        if(callStart == null)
        {
            return null;
        }

        return map(callStart.channel(), callStart.event(), P25ActivityLogRecords.Action.CALL);
    }

    P25ActivityLogRecords.ActivityEvent map(P25GrantObservationEvent observation)
    {
        if(observation == null)
        {
            return null;
        }

        IDecodeEvent event = P25DecodeEvent.builder(observation.eventType(), observation.timestamp())
            .channel(observation.channelDescriptor())
            .identifiers(observation.identifiers())
            .build();
        return map(observation.channel(), event, observation.continuation() ?
            P25ActivityLogRecords.Action.CONTINUE : P25ActivityLogRecords.Action.GRANT);
    }

    P25ActivityLogRecords.ConventionalCallOutput mapConventionalCallOutput(CompletedAudioCall call,
                                                                     P25ActivityLogRecords.CallOutput output)
    {
        return mapConventionalCallOutput(call != null ? call.snapshot() : null, output);
    }

    /** Maps the global winner plus its compact receiver-leg summaries into the statistics projection. */
    P25ActivityLogRecords.ResolvedLogicalCall mapResolvedLogicalCall(CompletedAudioCall call)
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

        String protocol = p25 ? Protocol.APCO25.name() : dmr ? Protocol.DMR.name() : Protocol.NXDN.name();
        List<P25SiteIdentity> learnedSites = call.callLegSummaries().stream()
            .map(summary -> summary.source().p25SiteIdentity())
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted(java.util.Comparator.comparingInt(P25SiteIdentity::wacn)
                .thenComparingInt(P25SiteIdentity::system).thenComparingInt(P25SiteIdentity::rfss)
                .thenComparingInt(P25SiteIdentity::site))
            .toList();
        List<Long> aliasListIds = call.callLegSummaries().stream()
            .map(summary -> summary.source().aliasListId())
            .filter(id -> id > 0)
            .distinct()
            .toList();
        P25SiteIdentity system = null;
        long aliasListId = winnerSource.aliasListId();
        if(p25)
        {
            boolean completeSourceIdentity = !call.callLegSummaries().isEmpty() &&
                call.callLegSummaries().stream().allMatch(summary -> summary.source().trafficChannel() &&
                    summary.source().hasLearnedP25SiteIdentity() && summary.source().hasDurableAliasListId());
            boolean oneAliasList = aliasListIds.size() == 1;
            boolean oneLearnedSystem = !learnedSites.isEmpty();
            if(oneLearnedSystem)
            {
                P25SiteIdentity firstSystem = learnedSites.get(0);
                oneLearnedSystem = learnedSites.stream().allMatch(site -> site.wacn() == firstSystem.wacn() &&
                    site.system() == firstSystem.system());
                if(completeSourceIdentity && oneAliasList && oneLearnedSystem)
                {
                    system = firstSystem;
                    aliasListId = aliasListIds.get(0);
                }
            }

            if(system == null)
            {
                //Missing or conflicting learned scope is deliberately fail-open: keep the call in its receiver
                //context and do not claim any learned-site observation.
                aliasListId = 0;
                learnedSites = List.of();
            }
        }
        else if(call.callLegSummaries().size() != 1)
        {
            //DMR/NXDN cross-site grouping is not defined yet; each traffic leg remains its own logical call.
            return null;
        }

        AudioCallSnapshot snapshot = call.snapshot();
        IdentifierCollection identifiers = snapshot.identifierCollection();
        IdentifierFacts facts = IdentifierFacts.from(identifiers);
        Identifier target = identifiers.getToIdentifier();
        Identifier source = identifiers.getFromIdentifier();
        Integer destination = destinationId(target);
        Integer sourceRadio = source != null && source.getForm() == Form.RADIO ? destinationId(source) : null;
        String guid = firstNonBlank(winnerSource.siteGuid(), facts.radresGuid());
        String contextKey = ReceiverContextKey.configured(guid, winnerSource.channelConfigurationId());
        if(contextKey == null)
        {
            contextKey = outputContextKey(guid, facts);
        }
        if(system == null && contextKey == null)
        {
            return null;
        }
        long timestamp = snapshot.startTimestamp() > 0 ? snapshot.startTimestamp() : snapshot.lastActivityTimestamp();

        if(timestamp <= 0)
        {
            return null;
        }

        return new P25ActivityLogRecords.ResolvedLogicalCall(call.logicalCallId(), timestamp, contextKey, guid,
            protocol, identityDomain(identifiers, nxdn, false), system != null ? system.wacn() : null,
            system != null ? system.system() : null, aliasListId,
            destination != null ? destination : 0, facts.targetForm(),
            facts.patchMemberTalkgroupIds(), sourceRadio, snapshot.isEncrypted() || facts.encrypted(),
            facts.encryptionAlgorithmId(), facts.encryptionKeyId(), p25TargetIdentity(target, p25),
            p25 ? facts.p25PatchMemberIdentities() : List.of(), p25 ? learnedSites : List.of());
    }

    P25ActivityLogRecords.ConventionalCallOutput mapConventionalCallOutput(AudioCallSnapshot snapshot,
                                                                     P25ActivityLogRecords.CallOutput output)
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
        String guid = blankToNull(facts.radresGuid());
        String contextKey = outputContextKey(guid, facts);

        long timestamp = snapshot.startTimestamp() > 0 ? snapshot.startTimestamp() :
            snapshot.lastActivityTimestamp();

        if(timestamp <= 0)
        {
            timestamp = System.currentTimeMillis();
        }

        if(contextKey == null)
        {
            return null;
        }

        Integer timeslot = snapshot.timeslot() > 0 ? Integer.valueOf(snapshot.timeslot()) :
            facts.timeslot();
        return new P25ActivityLogRecords.ConventionalCallOutput(timestamp, contextKey, guid, facts.frequencyHertz(),
            timeslot, destination != null ? destination : 0, facts.targetForm(),
            facts.patchMemberTalkgroupIds(), sourceRadio, output,
            identityDomain(identifiers, DecoderType.NXDN.toString().equals(facts.decoder()), false),
            p25TargetIdentity(targetIdentifier, isP25Decoder(facts.decoder())),
            facts.p25PatchMemberIdentities());
    }

    private static String outputContextKey(String guid, IdentifierFacts facts)
    {
        if(guid != null)
        {
            return "GUID:" + guid;
        }

        if(facts == null)
        {
            return null;
        }

        if(facts.configurationId() != null)
        {
            return "CONFIGURATION:" + facts.configurationId();
        }

        if(facts.hasTrunkedSiteIdentity())
        {
            String key = String.join(":", safe(facts.wacn()), safe(facts.systemId()), safe(facts.rfss()),
                safe(facts.site()));
            return ":::".equals(key) ? null : "P25:" + key;
        }

        if(facts.frequencyHertz() == null || facts.frequencyHertz() <= 0)
        {
            return null;
        }

        String decoder = blankToNull(facts.decoder());

        if(DecoderType.P25_CONVENTIONAL.toString().equals(decoder))
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25.name() + ":" +
                Protocol.APCO25.name() + ":" + facts.frequencyHertz();
        }
        else if(DecoderType.DMR.toString().equals(decoder))
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_DMR.name() + ":" +
                Protocol.DMR.name() + ":" + facts.frequencyHertz();
        }
        else if(DecoderType.NXDN.toString().equals(decoder))
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_NXDN.name() + ":" +
                Protocol.NXDN.name() + ":" + facts.frequencyHertz();
        }
        else if(DecoderType.AM.toString().equals(decoder) || DecoderType.NBFM.toString().equals(decoder))
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG.name() + ":" +
                (DecoderType.AM.toString().equals(decoder) ? Protocol.AM.name() : Protocol.NBFM.name()) + ":" +
                facts.frequencyHertz();
        }

        return null;
    }

    private P25ActivityLogRecords.ActivityEvent map(Channel channel, IDecodeEvent event,
                                                     P25ActivityLogRecords.Action actionOverride)
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
        IChannelDescriptor descriptor = event.getChannelDescriptor();
        Long frequency = frequency(descriptor, facts);
        String channelDescriptor = firstNonBlank(descriptor != null ? descriptor.toString() : null,
            facts.channelDescriptor(), facts.logicalChannelName());
        Integer timeslot = event.hasTimeslot() ? Integer.valueOf(event.getTimeslot()) : facts.timeslot();
        DecoderType decoderType = channel.getDecodeConfiguration().getDecoderType();
        P25ActivityLogRecords.Action action = actionOverride != null ? actionOverride :
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

        P25ActivityLogRecords.ContextKind contextKind = contextKind(channel, decoderType);

        if(contextKind == null)
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
        String guid = blankToNull(channel.getRadresGuid());
        String protocol = protocolName(event.getProtocol(), facts, decoderType);
        String contextKey = contextKey(guid, protocol, facts, frequency, contextKind, channel.getName(),
            channel.getConfigurationId());

        if(contextKey == null)
        {
            return null;
        }

        P25AffiliationEvent affiliationEvent = event instanceof P25AffiliationEvent affiliation ? affiliation : null;
        String sourceRadioId = affiliationEvent != null && affiliationEvent.getRadioId() != null ?
            affiliationEvent.getRadioId().toString() : facts.sourceId();
        String targetId = affiliationEvent != null && affiliationEvent.getTalkgroupId() != null ?
            affiliationEvent.getTalkgroupId().toString() : facts.targetId();
        String targetKind = affiliationEvent != null && affiliationEvent.getTalkgroupId() != null ?
            Form.TALKGROUP.name() : facts.targetForm();
        IdentifierCollection eventIdentifiers = event.getIdentifierCollection();
        Identifier targetIdentifier = affiliationEvent != null ? affiliationTarget(affiliationEvent,
            eventIdentifiers) : eventIdentifiers != null ? eventIdentifiers.getToIdentifier() : null;
        P25ActivityLogRecords.P25TargetIdentity p25TargetIdentity =
            p25TargetIdentity(targetIdentifier, isP25Decoder(decoderType));

        if(p25TargetIdentity.state() == P25ActivityLogRecords.P25IdentityState.UNKNOWN &&
            affiliationEvent != null && affiliationEvent.getTalkgroupId() != null)
        {
            p25TargetIdentity = P25ActivityLogRecords.P25TargetIdentity.ORDINARY;
        }
        P25ActivityLogRecords.RadioPresenceUpdate radioPresenceUpdate = radioPresenceUpdate(affiliationEvent);
        boolean metricsEncrypted = facts.encrypted() && event instanceof P25ChannelGrantEvent grantEvent &&
            P25EncryptionConfirmationTracker.isConfirmed(grantEvent, facts.encryptionAlgorithmId(),
                facts.encryptionKeyId());
        Integer metricsAlgorithmId = metricsEncrypted ? facts.encryptionAlgorithmId() : null;
        Integer metricsKeyId = metricsEncrypted ? facts.encryptionKeyId() : null;

        String dedupeKey = null;
        if(actionOverride == null && (decoderType == DecoderType.DMR || decoderType == DecoderType.NXDN) &&
            isUsefulProtocolSignaling(event.getEventType()))
        {
            dedupeKey = protocolSignalingDedupeKey(contextKey, protocol, action, event, frequency,
                channelDescriptor, timeslot, sourceRadioId, targetId, targetKind);
        }
        else if(actionOverride == null &&
            (isHighChurnCallEvent(event.getEventType()) || action == P25ActivityLogRecords.Action.CONTINUE))
        {
            dedupeKey = String.join("|",
                safe(contextKey),
                safe(action),
                safe(frequency),
                safe(timeslot),
                safe(sourceRadioId),
                safe(targetId),
                safe(targetKind),
                safe(facts.patchMemberTalkgroupIds()),
                safe(metricsAlgorithmId),
                safe(metricsKeyId),
                contextKind == P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG &&
                    event.getEventType() != null && event.getEventType().isVoiceCallEvent() ?
                    Long.toString(event.getTimeStart()) : "");
        }

        return new P25ActivityLogRecords.ActivityEvent(observedAt, contextKey, guid, contextKind,
            protocol, action,
            event.getEventType() != null ? event.getEventType().name() : null, sourceRadioId, targetId,
            targetKind, facts.patchMemberTalkgroupIds(), frequency, lcn, timeslot, metricsEncrypted, metricsAlgorithmId,
            metricsKeyId, facts.wacn(), facts.systemId(), facts.nac(), facts.rfss(), facts.site(),
            activityChannelName(contextKind, channel), decoderType.name(), facts.talkerAlias(),
            action == P25ActivityLogRecords.Action.CALL &&
                (contextKind != P25ActivityLogRecords.ContextKind.TRUNKED_SITE || actionOverride != null), dedupeKey,
            radioPresenceUpdate, identityDomain(channel, event.getIdentifierCollection()), p25TargetIdentity,
            facts.p25PatchMemberIdentities(), blankToNull(channel.getAliasListName()), true);
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

    P25ActivityLogRecords.SiteSnapshot map(SiteMetadataEvent event)
    {
        if(event == null || event.channel() == null || event.snapshot() == null || !event.snapshot().isUseful())
        {
            return null;
        }

        Channel channel = event.channel();
        P25NetworkConfigurationSnapshot snapshot = event.snapshot();
        Integer wacn = snapshot.network() != null ? snapshot.network().wacn() : null;
        Integer system = snapshot.network() != null ? snapshot.network().system() : null;
        Integer nac = snapshot.network() != null ? snapshot.network().nac() : null;
        Integer rfss = snapshot.currentSite() != null ? snapshot.currentSite().rfss() : null;
        Integer site = snapshot.currentSite() != null ? snapshot.currentSite().site() : null;
        Integer lra = snapshot.currentSite() != null && snapshot.currentSite().lra() != null ?
            snapshot.currentSite().lra() : snapshot.network() != null ? snapshot.network().lra() : null;
        Boolean tdma = hasTdma(snapshot);
        Long currentControl = currentControl(snapshot.channels());
        String hash = sha256(String.join("|", safe(snapshot.decoder()), safe(snapshot.network()),
            safe(snapshot.currentSite()), safe(snapshot.channels()), safe(snapshot.neighborSites()),
            safe(snapshot.frequencyBands()), safe(snapshot.patchGroups()),
            safe(snapshot.siteStatus() != null ? snapshot.siteStatus().withoutVolatileTiming() : null),
            safe(snapshot.foreignSystemBands())));
        String guid = blankToNull(channel.getRadresGuid());

        if(guid == null)
        {
            return null;
        }

        return new P25ActivityLogRecords.SiteSnapshot(event.observedAtEpochMilliseconds(), guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, hash, Protocol.APCO25.name(),
            TrunkedSiteMetadataMapper.configuredSiteName(channel), blankToNull(channel.getAliasListName()),
            snapshot.decoder(), wacn,
            system, nac, rfss, site, lra, tdma, snapshot.siteStatus(), currentControl, currentControl,
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

    private static P25ActivityLogRecords.Action normalizeAction(IDecodeEvent event, DecoderType decoderType)
    {
        if(event instanceof P25AffiliationEvent affiliationEvent)
        {
            return switch(affiliationEvent.getOutcome())
            {
                case REQUESTED -> P25ActivityLogRecords.Action.REQUEST;
                case ACCEPTED -> event.getEventType() == DecodeEventType.REGISTER ?
                    P25ActivityLogRecords.Action.REGISTER : P25ActivityLogRecords.Action.JOIN;
                case CONFIRMED -> P25ActivityLogRecords.Action.CHECK_ACK;
                case REJECTED -> P25ActivityLogRecords.Action.DENIAL;
                case CLEARED -> P25ActivityLogRecords.Action.LOGOUT;
                case UNRESOLVED -> event.getEventType() == DecodeEventType.REGISTER ?
                    P25ActivityLogRecords.Action.REGISTER : P25ActivityLogRecords.Action.UNKNOWN;
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
            return P25ActivityLogRecords.Action.REGISTER;
        }
        if(protocolSignaling && eventType == DecodeEventType.RESPONSE &&
            "ALOHA ACKNOWLEDGE".equals(details.strip()))
        {
            return P25ActivityLogRecords.Action.ACKNOWLEDGE;
        }
        if(eventType == DecodeEventType.DEREGISTER)
        {
            return P25ActivityLogRecords.Action.LOGOUT;
        }
        if(eventType == DecodeEventType.DENIAL)
        {
            return P25ActivityLogRecords.Action.DENIAL;
        }
        if(eventType == DecodeEventType.AFFILIATE)
        {
            return P25ActivityLogRecords.Action.JOIN;
        }
        if(eventType == DecodeEventType.REGISTER || eventType == DecodeEventType.REGISTER_ESN ||
            eventType == DecodeEventType.RADIO_REGISTRATION_SERVICE ||
            eventType == DecodeEventType.AUTOMATIC_REGISTRATION_SERVICE)
        {
            return P25ActivityLogRecords.Action.REGISTER;
        }
        if(eventType == DecodeEventType.ACKNOWLEDGE)
        {
            return P25ActivityLogRecords.Action.ACKNOWLEDGE;
        }
        if(eventType == DecodeEventType.RADIO_CHECK)
        {
            return P25ActivityLogRecords.Action.CHECK;
        }
        if(eventType == DecodeEventType.PAGE || eventType == DecodeEventType.CALL_ALERT)
        {
            return P25ActivityLogRecords.Action.PAGE;
        }
        if(eventType == DecodeEventType.REQUEST)
        {
            return P25ActivityLogRecords.Action.REQUEST;
        }
        if(eventType == DecodeEventType.STATUS)
        {
            return P25ActivityLogRecords.Action.STATUS;
        }
        if(eventType == DecodeEventType.EMERGENCY)
        {
            return P25ActivityLogRecords.Action.EMERGENCY;
        }
        if(eventType == DecodeEventType.GPS)
        {
            return P25ActivityLogRecords.Action.GPS;
        }
        if(eventType == DecodeEventType.DYNAMIC_REGROUP)
        {
            if(details.contains("CANCEL") || details.contains("DEACTIVATE") || details.contains("DELETE"))
            {
                return P25ActivityLogRecords.Action.PATCH_CANCEL;
            }
            if(details.contains("ACTIVATE") || details.contains("CREATE"))
            {
                return P25ActivityLogRecords.Action.PATCH_CREATE;
            }

            return P25ActivityLogRecords.Action.PATCH;
        }
        if(eventType == DecodeEventType.QUERY)
        {
            return P25ActivityLogRecords.Action.CHECK;
        }
        if(useDetailHeuristics && details.contains("UNIT REGISTRATION"))
        {
            return P25ActivityLogRecords.Action.REGISTER;
        }
        if(useDetailHeuristics && details.contains("RADIO CHECK ACK"))
        {
            return P25ActivityLogRecords.Action.CHECK_ACK;
        }
        if(useDetailHeuristics && details.contains("RADIO CHECK"))
        {
            return P25ActivityLogRecords.Action.CHECK;
        }
        if(useDetailHeuristics &&
            (details.contains("DENY") || details.contains("DENIED") || details.contains("DENIAL")))
        {
            return P25ActivityLogRecords.Action.DENIAL;
        }
        if(useDetailHeuristics &&
            (details.contains("BUSY") || details.contains("TARGET_GROUP_CURRENTLY_ACTIVE")))
        {
            return P25ActivityLogRecords.Action.BUSY;
        }
        if(useDetailHeuristics && details.contains("QUEUED"))
        {
            return P25ActivityLogRecords.Action.QUEUED;
        }
        if(useDetailHeuristics && details.contains("ACKNOWLEDGE"))
        {
            return P25ActivityLogRecords.Action.ACKNOWLEDGE;
        }
        if(event instanceof P25ChannelGrantEvent)
        {
            return P25ActivityLogRecords.Action.ACTIVE;
        }
        if(eventType != null && eventType.isVoiceCallEvent())
        {
            return P25ActivityLogRecords.Action.CALL;
        }
        if(eventType != null && (DecodeEventType.DATA_CALLS.contains(eventType) ||
            eventType == DecodeEventType.LRRP || eventType == DecodeEventType.SDM ||
            eventType == DecodeEventType.SMS || eventType == DecodeEventType.TEXT_MESSAGE ||
            eventType == DecodeEventType.UNKNOWN_PACKET || eventType == DecodeEventType.XCMP))
        {
            return P25ActivityLogRecords.Action.DATA;
        }

        return P25ActivityLogRecords.Action.UNKNOWN;
    }

    private static P25ActivityLogRecords.RadioPresenceUpdate radioPresenceUpdate(
        P25AffiliationEvent affiliationEvent)
    {
        if(affiliationEvent == null || affiliationEvent.getRadioId() == null || affiliationEvent.getRadioId() <= 0)
        {
            return null;
        }

        P25ActivityLogRecords.RadioPresenceEvidence evidence =
            affiliationEvent.getEventType() == DecodeEventType.REGISTER ?
                P25ActivityLogRecords.RadioPresenceEvidence.REGISTRATION :
                P25ActivityLogRecords.RadioPresenceEvidence.AFFILIATION;

        return switch(affiliationEvent.getOutcome())
        {
            case ACCEPTED, CONFIRMED -> P25ActivityLogRecords.RadioPresenceUpdate.confirmed(
                affiliationEvent.getRadioId(), affiliationEvent.getTalkgroupId() != null &&
                    affiliationEvent.getTalkgroupId() > 0 ? affiliationEvent.getTalkgroupId() : null, evidence);
            case CLEARED -> P25ActivityLogRecords.RadioPresenceUpdate.cleared(affiliationEvent.getRadioId());
            case REQUESTED, REJECTED, UNRESOLVED -> null;
        };
    }

    private static Long frequency(IChannelDescriptor descriptor, IdentifierFacts facts)
    {
        if(descriptor != null && descriptor.getDownlinkFrequency() > 0)
        {
            return descriptor.getDownlinkFrequency();
        }

        return facts.frequencyHertz();
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

    private static P25ActivityLogRecords.P25TargetIdentity p25TargetIdentity(Identifier identifier,
                                                                              boolean p25Decoder)
    {
        Identifier primary = identifier;

        if(identifier instanceof PatchGroupIdentifier patchGroup && patchGroup.getValue() != null)
        {
            primary = patchGroup.getValue().getPatchGroup();
        }

        if(primary instanceof FullyQualifiedTalkgroupIdentifier fullyQualified &&
            fullyQualified.getProtocol() == Protocol.APCO25)
        {
            return P25ActivityLogRecords.P25TargetIdentity.fullyQualified(fullyQualified.getWacn(),
                fullyQualified.getSystem(), fullyQualified.getTalkgroup());
        }

        return (p25Decoder || primary != null && primary.getProtocol() == Protocol.APCO25) &&
            primary instanceof TalkgroupIdentifier ?
            P25ActivityLogRecords.P25TargetIdentity.ORDINARY :
            P25ActivityLogRecords.P25TargetIdentity.UNKNOWN;
    }

    private static List<P25ActivityLogRecords.P25PatchMemberIdentity> p25PatchMemberIdentities(
        Identifier identifier)
    {
        if(!(identifier instanceof PatchGroupIdentifier patchGroup) || patchGroup.getValue() == null)
        {
            return List.of();
        }

        List<P25ActivityLogRecords.P25PatchMemberIdentity> identities = new ArrayList<>();
        for(TalkgroupIdentifier member: patchGroup.getValue().getPatchedTalkgroupIdentifiers())
        {
            if(member == null || member.getValue() == null || member.getValue() <= 0 ||
                member.getProtocol() != Protocol.APCO25)
            {
                continue;
            }

            P25ActivityLogRecords.P25TargetIdentity targetIdentity = p25TargetIdentity(member, true);
            if(targetIdentity.state() != P25ActivityLogRecords.P25IdentityState.UNKNOWN)
            {
                identities.add(new P25ActivityLogRecords.P25PatchMemberIdentity(member.getValue(), targetIdentity));
            }
        }

        return List.copyOf(identities);
    }

    private static Identifier affiliationTarget(P25AffiliationEvent affiliation,
                                                IdentifierCollection identifiers)
    {
        if(affiliation == null || affiliation.getTalkgroupId() == null || identifiers == null)
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

        if(talkgroup != null && talkgroup > 0)
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
            return parsed > 0 ? parsed : null;
        }
        catch(NumberFormatException e)
        {
            return null;
        }
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

    private static P25ActivityLogRecords.IdentityDomain identityDomain(Channel channel,
                                                                       IdentifierCollection identifiers)
    {
        if(channel != null && channel.getDecodeConfiguration() instanceof DecodeConfigNXDN config)
        {
            return identityDomain(identifiers, true,
                config.getTransmissionMode() != null && config.getTransmissionMode().isTypeD());
        }

        return P25ActivityLogRecords.IdentityDomain.STANDARD;
    }

    private static P25ActivityLogRecords.IdentityDomain identityDomain(IdentifierCollection identifiers,
                                                                       boolean nxdn, boolean nxdnTypeD)
    {
        if(identifiers != null)
        {
            for(Identifier identifier: identifiers.getIdentifiers())
            {
                if(identifier instanceof NXDNTalkgroupIdentifier talkgroup)
                {
                    nxdn = true;

                    if(talkgroup.isTypeD())
                    {
                        return P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D;
                    }
                }
                else if(identifier instanceof NXDNRadioIdentifier radio)
                {
                    nxdn = true;

                    if(radio.isTypeD())
                    {
                        return P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D;
                    }
                }
            }
        }

        return nxdnTypeD ? P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D :
            nxdn ? P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C :
                P25ActivityLogRecords.IdentityDomain.STANDARD;
    }

    private static String contextKey(String guid, String protocol, IdentifierFacts facts, Long frequency,
                                     P25ActivityLogRecords.ContextKind contextKind, String configuredChannelName,
                                     String channelConfigurationId)
    {
        String configurationId = facts != null && facts.configurationId() != null ?
            facts.configurationId() : blankToNull(channelConfigurationId);
        String configured = ReceiverContextKey.configured(guid, configurationId);

        if(configured != null)
        {
            return configured;
        }

        if(contextKind == P25ActivityLogRecords.ContextKind.TRUNKED_SITE)
        {
            String key = String.join(":",
                safe(facts != null ? facts.wacn() : null),
                safe(facts != null ? facts.systemId() : null),
                safe(facts != null ? facts.rfss() : null),
                safe(facts != null ? facts.site() : null));
            return ":::".equals(key) ? null : "P25:" + key;
        }

        return ReceiverContextKey.conventional(contextKind, protocol, frequency, configuredChannelName);
    }

    private static String activityChannelName(P25ActivityLogRecords.ContextKind contextKind, Channel channel)
    {
        if(channel == null)
        {
            return null;
        }

        return contextKind == P25ActivityLogRecords.ContextKind.TRUNKED_SITE ?
            TrunkedSiteMetadataMapper.configuredSiteName(channel) : blankToNull(channel.getName());
    }

    private static String protocolName(Protocol protocol, IdentifierFacts facts, DecoderType decoderType)
    {
        //Activity protocol identifies the configured air interface. Some DMR data applications label their payload
        //protocol (for example LRRP), which must not relabel the receiver context.
        if(decoderType == DecoderType.DMR)
        {
            return Protocol.DMR.name();
        }

        if(decoderType == DecoderType.NXDN)
        {
            return Protocol.NXDN.name();
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

    private static P25ActivityLogRecords.ContextKind contextKind(Channel channel, DecoderType decoderType)
    {
        if(decoderType == DecoderType.P25_CONVENTIONAL)
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25;
        }

        if(decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2)
        {
            return P25ActivityLogRecords.ContextKind.TRUNKED_SITE;
        }

        if(decoderType == DecoderType.AM || decoderType == DecoderType.NBFM)
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG;
        }

        if(decoderType == DecoderType.DMR &&
            channel.getDecodeConfiguration() instanceof DecodeConfigDMR config)
        {
            return config.isTrunked() ? P25ActivityLogRecords.ContextKind.TRUNKED_SITE :
                P25ActivityLogRecords.ContextKind.CONVENTIONAL_DMR;
        }

        if(decoderType == DecoderType.NXDN &&
            channel.getDecodeConfiguration() instanceof DecodeConfigNXDN config)
        {
            return config.isTrunked() ? P25ActivityLogRecords.ContextKind.TRUNKED_SITE :
                P25ActivityLogRecords.ContextKind.CONVENTIONAL_NXDN;
        }

        return null;
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
    private static String protocolSignalingDedupeKey(String contextKey, String protocol,
                                                      P25ActivityLogRecords.Action action, IDecodeEvent event,
                                                      Long frequency, String channelDescriptor, Integer timeslot,
                                                      String sourceRadioId, String targetId, String targetKind)
    {
        String details = event.getDetails();
        String subtype = details != null && !details.isBlank() ?
            sha256(details.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT)) : "";
        return PROTOCOL_SIGNAL_DEDUPE_PREFIX + String.join("|",
            safe(contextKey),
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
                                   List<P25ActivityLogRecords.P25PatchMemberIdentity> p25PatchMemberIdentities,
                                   Long frequencyHertz,
                                   String channelDescriptor, String logicalChannelName, boolean encrypted,
                                   Integer encryptionAlgorithmId, Integer encryptionKeyId, Integer wacn,
                                   Integer systemId, Integer nac, Integer rfss, Integer site, String radresGuid,
                                   String configurationId, String configuredChannelName, String decoder,
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
                P25ActivityLogMapper.p25PatchMemberIdentities(target),
                longValue(first(identifiers, Form.CHANNEL_FREQUENCY)),
                value(first(identifiers, Form.CHANNEL_DESCRIPTOR)), value(first(identifiers, Form.CHANNEL_NAME)),
                encryptionIdentifier != null && encryptionIdentifier.isEncrypted(),
                encryptionKey != null && encryptionKey.isEncrypted() ? encryptionKey.getAlgorithm() : null,
                encryptionKey != null && encryptionKey.isEncrypted() ? encryptionKey.getKey() : null,
                intValue(first(identifiers, Form.WACN)), intValue(first(identifiers, Form.SYSTEM)),
                intValue(first(identifiers, Form.NETWORK_ACCESS_CODE)),
                intValue(first(identifiers, Form.RF_SUBSYSTEM)), intValue(first(identifiers, Form.SITE)),
                value(first(identifiers, Form.RADRES_GUID)), value(first(identifiers, Form.UNIQUE_ID)),
                value(first(identifiers, Form.CHANNEL)), value(first(identifiers, Form.DECODER_TYPE)),
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
