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

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DMRConventionalCallEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25EncryptionConfirmationTracker;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25DecodeEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25TalkerAliasEvent;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.protocol.Protocol;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Converts SDRTrunk activity events into compact SQLite log records.
 */
class P25ActivityLogMapper
{
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
        String contextKey;

        if(guid != null)
        {
            contextKey = "GUID:" + guid;
        }
        else if(configurationId != null)
        {
            contextKey = "CONVENTIONAL_DMR:CONFIGURATION:" + configurationId;
        }
        else
        {
            contextKey = "CONVENTIONAL_DMR:DMR:" + event.frequencyHertz() +
                (channelName != null ? ":" + channelName : "");
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

    P25ActivityLogRecords.TalkerAliasUpdate map(P25TalkerAliasEvent event)
    {
        if(event == null || event.channel() == null || event.radio() == null || event.alias() == null ||
            event.alias().getValue() == null || event.alias().getValue().toString().isBlank())
        {
            return null;
        }

        DecoderType decoderType = event.channel().getDecodeConfiguration() != null ?
            event.channel().getDecodeConfiguration().getDecoderType() : null;

        if(decoderType != DecoderType.P25_PHASE1 && decoderType != DecoderType.P25_PHASE2)
        {
            return null;
        }

        IdentifierFacts facts = IdentifierFacts.from(event.identifiers());
        String guid = firstNonBlank(event.channel().getRadresGuid(), facts.radresGuid());
        String contextKey = contextKey(guid, Protocol.APCO25, facts, null,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, event.channel().getName());

        if(contextKey == null)
        {
            return null;
        }

        long observedAt = event.timestamp() > 0 ? event.timestamp() : System.currentTimeMillis();
        return new P25ActivityLogRecords.TalkerAliasUpdate(observedAt, contextKey, guid, facts.wacn(),
            facts.systemId(), event.radio().getValue(), event.alias().getValue().toString().trim());
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

    P25ActivityLogRecords.TalkerAliasUpdate mapTalkerAliasUpdate(P25ActivityLogRecords.ActivityEvent activity)
    {
        if(activity == null || activity.contextKind() != P25ActivityLogRecords.ContextKind.TRUNKED_SITE ||
            activity.sourceRadioId() == null || activity.talkerAlias() == null || activity.talkerAlias().isBlank())
        {
            return null;
        }

        try
        {
            int radio = Integer.parseInt(activity.sourceRadioId());
            return radio > 0 ? new P25ActivityLogRecords.TalkerAliasUpdate(
                activity.observedAtEpochMilliseconds(), activity.contextKey(), activity.guid(), activity.wacn(),
                activity.systemId(), radio, activity.talkerAlias().trim()) : null;
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }

    P25ActivityLogRecords.CompletedCallOutput mapCompletedCallOutput(CompletedAudioCall call,
                                                                     P25ActivityLogRecords.CallOutput output)
    {
        if(call == null || call.snapshot() == null || call.snapshot().identifierCollection() == null ||
            output == null)
        {
            return null;
        }

        IdentifierCollection identifiers = call.snapshot().identifierCollection();
        IdentifierFacts facts = IdentifierFacts.from(identifiers);
        Integer talkgroup = talkgroup(identifiers.getToIdentifier());

        if(facts.radresGuid() == null || facts.radresGuid().isBlank() || talkgroup == null || talkgroup <= 0)
        {
            return null;
        }

        long timestamp = call.snapshot().startTimestamp() > 0 ? call.snapshot().startTimestamp() :
            call.snapshot().lastActivityTimestamp();

        if(timestamp <= 0)
        {
            timestamp = System.currentTimeMillis();
        }

        return new P25ActivityLogRecords.CompletedCallOutput(timestamp, facts.radresGuid(), talkgroup, output);
    }

    private P25ActivityLogRecords.ActivityEvent map(Channel channel, IDecodeEvent event,
                                                     P25ActivityLogRecords.Action actionOverride)
    {
        if(channel == null || event == null || channel.getDecodeConfiguration() == null)
        {
            return null;
        }

        IdentifierFacts facts = IdentifierFacts.from(event.getIdentifierCollection());
        IChannelDescriptor descriptor = event.getChannelDescriptor();
        Long frequency = frequency(descriptor, facts);
        String channelDescriptor = firstNonBlank(descriptor != null ? descriptor.toString() : null,
            facts.channelDescriptor(), facts.logicalChannelName());
        Integer timeslot = event.hasTimeslot() ? Integer.valueOf(event.getTimeslot()) : facts.timeslot();
        P25ActivityLogRecords.Action action = actionOverride != null ? actionOverride : normalizeAction(event);
        DecoderType decoderType = channel.getDecodeConfiguration().getDecoderType();
        P25ActivityLogRecords.ContextKind contextKind = contextKind(decoderType);

        if(contextKind == null)
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
        String contextKey = contextKey(guid, event.getProtocol(), facts, frequency, contextKind, channel.getName());

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
        P25ActivityLogRecords.RadioAffiliationUpdate affiliationUpdate = affiliationUpdate(affiliationEvent);
        boolean metricsEncrypted = facts.encrypted() && event instanceof P25ChannelGrantEvent grantEvent &&
            P25EncryptionConfirmationTracker.isConfirmed(grantEvent, facts.encryptionAlgorithmId(),
                facts.encryptionKeyId());
        Integer metricsAlgorithmId = metricsEncrypted ? facts.encryptionAlgorithmId() : null;
        Integer metricsKeyId = metricsEncrypted ? facts.encryptionKeyId() : null;

        String dedupeKey = null;
        if(actionOverride == null &&
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
                safe(metricsAlgorithmId),
                safe(metricsKeyId));
        }

        return new P25ActivityLogRecords.ActivityEvent(observedAt, contextKey, guid, contextKind,
            event.getProtocol() != null ? event.getProtocol().name() : null, action,
            event.getEventType() != null ? event.getEventType().name() : null, sourceRadioId, targetId,
            targetKind, frequency, lcn, timeslot, metricsEncrypted, metricsAlgorithmId,
            metricsKeyId, facts.wacn(), facts.systemId(), facts.nac(), facts.rfss(), facts.site(),
            activityChannelName(contextKind, channel), decoderType.name(), facts.talkerAlias(),
            action == P25ActivityLogRecords.Action.CALL &&
                (contextKind != P25ActivityLogRecords.ContextKind.TRUNKED_SITE || actionOverride != null), dedupeKey,
            affiliationUpdate);
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
            blankToNull(channel.getName()), blankToNull(channel.getAliasListName()), snapshot.decoder(), wacn,
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

    private static P25ActivityLogRecords.Action normalizeAction(IDecodeEvent event)
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
        String details = event.getDetails() != null ? event.getDetails().toUpperCase() : "";

        if(eventType == DecodeEventType.DEREGISTER)
        {
            return P25ActivityLogRecords.Action.LOGOUT;
        }
        if(eventType == DecodeEventType.AFFILIATE)
        {
            return P25ActivityLogRecords.Action.JOIN;
        }
        if(eventType == DecodeEventType.REGISTER || eventType == DecodeEventType.REGISTER_ESN)
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
        if(eventType == DecodeEventType.PAGE)
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
        if(details.contains("UNIT REGISTRATION"))
        {
            return P25ActivityLogRecords.Action.REGISTER;
        }
        if(details.contains("RADIO CHECK ACK"))
        {
            return P25ActivityLogRecords.Action.CHECK_ACK;
        }
        if(details.contains("RADIO CHECK"))
        {
            return P25ActivityLogRecords.Action.CHECK;
        }
        if(details.contains("DENY") || details.contains("DENIED") || details.contains("DENIAL"))
        {
            return P25ActivityLogRecords.Action.DENIAL;
        }
        if(details.contains("BUSY") || details.contains("TARGET_GROUP_CURRENTLY_ACTIVE"))
        {
            return P25ActivityLogRecords.Action.BUSY;
        }
        if(details.contains("QUEUED"))
        {
            return P25ActivityLogRecords.Action.QUEUED;
        }
        if(details.contains("ACKNOWLEDGE"))
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
        if(eventType != null && DecodeEventType.DATA_CALLS.contains(eventType))
        {
            return P25ActivityLogRecords.Action.DATA;
        }

        return P25ActivityLogRecords.Action.UNKNOWN;
    }

    private static P25ActivityLogRecords.RadioAffiliationUpdate affiliationUpdate(
        P25AffiliationEvent affiliationEvent)
    {
        if(affiliationEvent == null || affiliationEvent.getRadioId() == null || affiliationEvent.getRadioId() <= 0)
        {
            return null;
        }

        return switch(affiliationEvent.getOutcome())
        {
            case ACCEPTED, CONFIRMED -> affiliationEvent.getTalkgroupId() != null &&
                affiliationEvent.getTalkgroupId() > 0 ?
                new P25ActivityLogRecords.RadioAffiliationUpdate(affiliationEvent.getRadioId(),
                    affiliationEvent.getTalkgroupId()) : null;
            case CLEARED -> new P25ActivityLogRecords.RadioAffiliationUpdate(affiliationEvent.getRadioId(), null);
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

    private static Integer positive(Integer value)
    {
        return value != null && value > 0 && value <= DmrActivitySchema.MAXIMUM_DMR_ID ? value : null;
    }

    private static String contextKey(String guid, Protocol protocol, IdentifierFacts facts, Long frequency,
                                     P25ActivityLogRecords.ContextKind contextKind, String configuredChannelName)
    {
        if(guid != null)
        {
            return "GUID:" + guid;
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

        if(frequency != null && frequency > 0)
        {
            return contextKind.name() + ":" + protocolName(protocol, facts) + ":" + frequency;
        }

        String channelName = blankToNull(configuredChannelName);
        return channelName != null ? contextKind.name() + ":" + protocolName(protocol, facts) + ":" + channelName :
            null;
    }

    private static String activityChannelName(P25ActivityLogRecords.ContextKind contextKind, Channel channel)
    {
        if(contextKind == P25ActivityLogRecords.ContextKind.TRUNKED_SITE || channel == null)
        {
            return null;
        }

        return blankToNull(channel.getName());
    }

    private static String protocolName(Protocol protocol, IdentifierFacts facts)
    {
        if(protocol != null && protocol != Protocol.UNKNOWN)
        {
            return protocol.name();
        }

        return facts != null && facts.decoder() != null ? facts.decoder() : "UNKNOWN";
    }

    private static P25ActivityLogRecords.ContextKind contextKind(DecoderType decoderType)
    {
        if(decoderType == DecoderType.P25_CONVENTIONAL)
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25;
        }

        if(decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2)
        {
            return P25ActivityLogRecords.ContextKind.TRUNKED_SITE;
        }

        if(decoderType == DecoderType.NBFM || decoderType == DecoderType.AM)
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG;
        }

        return null;
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
                                   Long frequencyHertz, String channelDescriptor, String logicalChannelName,
                                   boolean encrypted,
                                   Integer encryptionAlgorithmId, Integer encryptionKeyId, Integer wacn,
                                   Integer systemId, Integer nac, Integer rfss, Integer site, String radresGuid,
                                   String configuredChannelName, String decoder, String talkerAlias, Integer timeslot)
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
                value(target), form(target), longValue(first(identifiers, Form.CHANNEL_FREQUENCY)),
                value(first(identifiers, Form.CHANNEL_DESCRIPTOR)), value(first(identifiers, Form.CHANNEL_NAME)),
                encryptionIdentifier != null && encryptionIdentifier.isEncrypted(),
                encryptionKey != null && encryptionKey.isEncrypted() ? encryptionKey.getAlgorithm() : null,
                encryptionKey != null && encryptionKey.isEncrypted() ? encryptionKey.getKey() : null,
                intValue(first(identifiers, Form.WACN)), intValue(first(identifiers, Form.SYSTEM)),
                intValue(first(identifiers, Form.NETWORK_ACCESS_CODE)),
                intValue(first(identifiers, Form.RF_SUBSYSTEM)), intValue(first(identifiers, Form.SITE)),
                value(first(identifiers, Form.RADRES_GUID)), value(first(identifiers, Form.CHANNEL)),
                value(first(identifiers, Form.DECODER_TYPE)), value(first(identifiers, Form.TALKER_ALIAS)),
                timeslot);
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
