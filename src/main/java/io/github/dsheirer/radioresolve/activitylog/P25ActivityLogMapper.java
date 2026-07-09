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

package io.github.dsheirer.radioresolve.activitylog;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
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
    P25ActivityLogRecords.ActivityEvent map(IDecodeEvent event)
    {
        if(event == null)
        {
            return null;
        }

        IdentifierFacts facts = IdentifierFacts.from(event.getIdentifierCollection());
        IChannelDescriptor descriptor = event.getChannelDescriptor();
        Long frequency = frequency(descriptor, facts);
        String channelDescriptor = firstNonBlank(descriptor != null ? descriptor.toString() : null,
            facts.channelDescriptor(), facts.logicalChannelName());
        Integer timeslot = event.hasTimeslot() ? Integer.valueOf(event.getTimeslot()) : facts.timeslot();
        P25ActivityLogRecords.Action action = normalizeAction(event);
        P25ActivityLogRecords.ContextKind contextKind = contextKind(event.getProtocol(), facts);

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
        String guid = blankToNull(facts.radresGuid());
        String contextKey = contextKey(guid, event.getProtocol(), facts, frequency, contextKind);

        if(contextKey == null)
        {
            return null;
        }

        String dedupeKey = null;
        if(isHighChurnCallEvent(event.getEventType()) || action == P25ActivityLogRecords.Action.CONTINUE)
        {
            dedupeKey = String.join("|",
                safe(contextKey),
                safe(action),
                safe(frequency),
                safe(timeslot),
                safe(facts.sourceId()),
                safe(facts.targetId()),
                safe(facts.targetForm()),
                safe(facts.encryptionAlgorithmId()),
                safe(facts.encryptionKeyId()));
        }

        return new P25ActivityLogRecords.ActivityEvent(observedAt, contextKey, guid, contextKind,
            event.getProtocol() != null ? event.getProtocol().name() : null, action,
            event.getEventType() != null ? event.getEventType().name() : null, facts.sourceId(), facts.targetId(),
            facts.targetForm(), frequency, lcn, timeslot, facts.encrypted(), facts.encryptionAlgorithmId(),
            facts.encryptionKeyId(), facts.wacn(), facts.systemId(), facts.nac(), facts.rfss(), facts.site(),
            activityChannelName(contextKind, facts), facts.decoder(), facts.talkerAlias(), dedupeKey);
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
        Long currentControl = currentControl(snapshot.channels());
        String hash = sha256(String.join("|", safe(snapshot.decoder()), safe(snapshot.network()),
            safe(snapshot.currentSite()), safe(snapshot.channels()), safe(snapshot.neighborSites()),
            safe(snapshot.frequencyBands()), safe(snapshot.patchGroups()), safe(snapshot.talkerAliases())));
        String guid = blankToNull(channel.getRadresGuid());

        if(guid == null)
        {
            return null;
        }

        return new P25ActivityLogRecords.SiteSnapshot(event.observedAtEpochMilliseconds(), guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, hash, Protocol.APCO25.name(),
            blankToNull(channel.getName()), blankToNull(channel.getAliasListName()), snapshot.decoder(), wacn,
            system, nac, rfss, site, currentControl, currentControl, snapshot.channels(), snapshot.neighborSites(),
            snapshot.frequencyBands(), snapshot.patchGroups(), snapshot.talkerAliases());
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
        if(details.contains("ACCEPTED AFFILIATION"))
        {
            return P25ActivityLogRecords.Action.JOIN;
        }
        if(details.contains("UNIT REGISTRATION"))
        {
            return P25ActivityLogRecords.Action.REGISTER;
        }
        if(details.contains("GROUP AFFILIATION QUERY RESPONSE"))
        {
            return P25ActivityLogRecords.Action.CHECK_ACK;
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
            if(details.contains("CONTINUE") || details.contains("UPDATE"))
            {
                return P25ActivityLogRecords.Action.CONTINUE;
            }
            if(details.contains("GRANT"))
            {
                return P25ActivityLogRecords.Action.GRANT;
            }
            if(details.contains("ACTIVE"))
            {
                return P25ActivityLogRecords.Action.ACTIVE;
            }

            return P25ActivityLogRecords.Action.CALL;
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

    private static Long frequency(IChannelDescriptor descriptor, IdentifierFacts facts)
    {
        if(descriptor != null && descriptor.getDownlinkFrequency() > 0)
        {
            return descriptor.getDownlinkFrequency();
        }

        return facts.frequencyHertz();
    }

    private static String contextKey(String guid, Protocol protocol, IdentifierFacts facts, Long frequency,
                                     P25ActivityLogRecords.ContextKind contextKind)
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

        String channelName = facts != null ? blankToNull(facts.configuredChannelName()) : null;
        return channelName != null ? contextKind.name() + ":" + protocolName(protocol, facts) + ":" + channelName :
            null;
    }

    private static String activityChannelName(P25ActivityLogRecords.ContextKind contextKind, IdentifierFacts facts)
    {
        if(contextKind == P25ActivityLogRecords.ContextKind.TRUNKED_SITE || facts == null)
        {
            return null;
        }

        return blankToNull(facts.configuredChannelName());
    }

    private static String protocolName(Protocol protocol, IdentifierFacts facts)
    {
        if(protocol != null && protocol != Protocol.UNKNOWN)
        {
            return protocol.name();
        }

        return facts != null && facts.decoder() != null ? facts.decoder() : "UNKNOWN";
    }

    private static P25ActivityLogRecords.ContextKind contextKind(Protocol protocol, IdentifierFacts facts)
    {
        String decoder = facts != null ? facts.decoder() : null;

        if(isDecoder(decoder, DecoderType.P25_CONVENTIONAL))
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25;
        }

        if(isDecoder(decoder, DecoderType.P25_PHASE1) || isDecoder(decoder, DecoderType.P25_PHASE2))
        {
            return P25ActivityLogRecords.ContextKind.TRUNKED_SITE;
        }

        if(protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2)
        {
            return facts != null && facts.hasTrunkedSiteIdentity() ?
                P25ActivityLogRecords.ContextKind.TRUNKED_SITE : P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25;
        }

        if(protocol == Protocol.NBFM || protocol == Protocol.AM || isDecoder(decoder, DecoderType.NBFM) ||
            isDecoder(decoder, DecoderType.AM))
        {
            return P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG;
        }

        return null;
    }

    private static boolean isDecoder(String value, DecoderType decoderType)
    {
        return decoderType != null && (decoderType.toString().equals(value) ||
            decoderType.getShortDisplayString().equals(value) || decoderType.name().equals(value));
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
