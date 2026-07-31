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
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallAttributionEvent;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/**
 * Maps protocol-neutral trunked call-start notifications into the existing compact activity projection.
 */
class TrunkedCallActivityMapper
{
    P25ActivityLogRecords.ActivityEvent map(TrunkedCallStartEvent callStart)
    {
        if(callStart == null || callStart.channel() == null || callStart.event() == null)
        {
            return null;
        }

        Channel channel = callStart.channel();
        IDecodeEvent event = callStart.event();
        Protocol protocol = event.getProtocol();
        DecodeEventType eventType = event.getEventType();

        if((protocol != Protocol.DMR && protocol != Protocol.NXDN) || eventType == null ||
            !eventType.isVoiceCallEvent() || event.getTimeStart() <= 0)
        {
            return null;
        }

        DecoderType decoderType = channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;

        if(protocol == Protocol.DMR && decoderType != DecoderType.DMR ||
            protocol == Protocol.NXDN && decoderType != DecoderType.NXDN)
        {
            return null;
        }

        IdentifierCollection identifiers = event.getIdentifierCollection();
        Identifier source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier target = identifiers != null ? identifiers.getToIdentifier() : null;
        IChannelDescriptor descriptor = event.getChannelDescriptor();
        Long frequency = descriptor != null && descriptor.getDownlinkFrequency() > 0 ?
            descriptor.getDownlinkFrequency() : null;
        Integer timeslot = event.hasTimeslot() ? event.getTimeslot() : null;
        String guid = blankToNull(channel.getRadresGuid());
        String configurationId = blankToNull(value(first(identifiers, Form.UNIQUE_ID)));

        if(configurationId == null)
        {
            configurationId = blankToNull(channel.getConfigurationId());
        }

        String contextKey = contextKey(guid, configurationId);

        if(contextKey == null)
        {
            return null;
        }
        EncryptionKeyIdentifier encryptionIdentifier = encryptionIdentifier(identifiers);
        EncryptionKey encryptionKey = encryptionIdentifier != null ? encryptionIdentifier.getValue() : null;
        boolean encrypted = DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(eventType) ||
            encryptionIdentifier != null && encryptionIdentifier.isEncrypted();
        String sourceId = source != null && source.getForm() == Form.RADIO ? value(source) : null;
        String targetId = value(target);
        String targetKind = target != null && target.getForm() != null ? target.getForm().name() : null;

        return new P25ActivityLogRecords.ActivityEvent(event.getTimeStart(), contextKey, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, protocol.name(), P25ActivityLogRecords.Action.CALL,
            eventType.name(), sourceId, targetId, targetKind, List.of(), frequency,
            descriptor != null ? descriptor.toString() : null, timeslot, encrypted,
            encrypted && encryptionKey != null ? encryptionKey.getAlgorithm() : null,
            encrypted && encryptionKey != null ? encryptionKey.getKey() : null,
            null, intValue(identifiers, Form.SYSTEM), null, null, intValue(identifiers, Form.SITE),
            null, decoderType != null ? decoderType.name() : protocol.name(),
            value(first(identifiers, Form.TALKER_ALIAS)), true, null, null,
            identityDomain(channel, identifiers));
    }

    P25ActivityLogRecords.TrunkedCallAttribution map(TrunkedCallAttributionEvent attribution)
    {
        if(attribution == null || attribution.channel() == null || attribution.protocol() == null ||
            attribution.callStartEpochMilliseconds() <= 0 ||
            (!attribution.destinationBecameKnown() && !attribution.sourceBecameKnown() &&
                !attribution.encryptionBecameKnown()))
        {
            return null;
        }

        Channel channel = attribution.channel();
        Protocol protocol = attribution.protocol();
        DecodeTypeMatch typeMatch = DecodeTypeMatch.from(channel, protocol);

        if(!typeMatch.matches())
        {
            return null;
        }

        IdentifierCollection identifiers = attribution.identifiers();
        Identifier target = identifiers != null ? identifiers.getToIdentifier() : null;
        Identifier source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Integer destinationId = identityId(target);
        String destinationKind = target != null && target.getForm() != null ? target.getForm().name() : null;
        Integer sourceRadio = source != null && source.getForm() == Form.RADIO ? identityId(source) : null;
        String guid = blankToNull(channel.getRadresGuid());
        String configurationId = blankToNull(channel.getConfigurationId());
        String contextKey = contextKey(guid, configurationId);
        IChannelDescriptor descriptor = attribution.channelDescriptor();
        Long frequency = descriptor != null && descriptor.getDownlinkFrequency() > 0 ?
            descriptor.getDownlinkFrequency() : null;

        if(contextKey == null)
        {
            return null;
        }

        return new P25ActivityLogRecords.TrunkedCallAttribution(
            attribution.callStartEpochMilliseconds(), contextKey, guid,
            frequency, attribution.timeslot(),
            destinationId != null ? destinationId : 0, destinationKind, patchMemberTalkgroups(target),
            sourceRadio, attribution.destinationBecameKnown(), attribution.sourceBecameKnown(),
            attribution.encryptionBecameKnown(), attribution.encryptedBeforeObservation(),
            identityDomain(channel, identifiers));
    }

    private static String contextKey(String guid, String configurationId)
    {
        return guid != null ? "GUID:" + guid :
            configurationId != null ? "CONFIGURATION:" + configurationId : null;
    }

    private static Integer identityId(Identifier identifier)
    {
        if(identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null &&
            patch.getValue().getPatchGroup() != null)
        {
            int value = patch.getValue().getPatchGroup().getValue();
            return value > 0 ? value : null;
        }

        Integer value = intValue(identifier);
        return value != null && value > 0 ? value : null;
    }

    private static List<Integer> patchMemberTalkgroups(Identifier identifier)
    {
        if(!(identifier instanceof PatchGroupIdentifier patch) || patch.getValue() == null)
        {
            return List.of();
        }

        Integer canonical = identityId(identifier);
        return patch.getValue().getPatchedTalkgroupIdentifiers().stream()
            .filter(member -> member != null && member.getValue() != null && member.getValue() > 0)
            .map(TalkgroupIdentifier::getValue)
            .filter(member -> !member.equals(canonical))
            .distinct()
            .sorted()
            .toList();
    }

    private static EncryptionKeyIdentifier encryptionIdentifier(IdentifierCollection identifiers)
    {
        Identifier identifier = identifiers != null ? identifiers.getEncryptionIdentifier() : null;
        return identifier instanceof EncryptionKeyIdentifier encryption ? encryption : null;
    }

    private static Integer intValue(IdentifierCollection identifiers, Form form)
    {
        return intValue(first(identifiers, form));
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

    private static P25ActivityLogRecords.IdentityDomain identityDomain(Channel channel,
                                                                       IdentifierCollection identifiers)
    {
        if(channel != null && channel.getDecodeConfiguration() instanceof DecodeConfigNXDN config)
        {
            if(config.getTransmissionMode() != null && config.getTransmissionMode().isTypeD())
            {
                return P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D;
            }

            return hasTypeDIdentifier(identifiers) ? P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D :
                P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C;
        }

        return P25ActivityLogRecords.IdentityDomain.STANDARD;
    }

    private static boolean hasTypeDIdentifier(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return false;
        }

        for(Identifier identifier: identifiers.getIdentifiers())
        {
            if(identifier instanceof NXDNTalkgroupIdentifier talkgroup && talkgroup.isTypeD() ||
                identifier instanceof NXDNRadioIdentifier radio && radio.isTypeD())
            {
                return true;
            }
        }

        return false;
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

    private static String value(Identifier identifier)
    {
        return identifier != null && identifier.getValue() != null ? identifier.getValue().toString() : null;
    }

    private static String blankToNull(String value)
    {
        return value != null && !value.isBlank() ? value : null;
    }

    private record DecodeTypeMatch(DecoderType decoderType, Protocol protocol)
    {
        private static DecodeTypeMatch from(Channel channel, Protocol protocol)
        {
            return new DecodeTypeMatch(channel.getDecodeConfiguration() != null ?
                channel.getDecodeConfiguration().getDecoderType() : null, protocol);
        }

        private boolean matches()
        {
            return protocol == Protocol.DMR && decoderType == DecoderType.DMR ||
                protocol == Protocol.NXDN && decoderType == DecoderType.NXDN;
        }
    }
}
