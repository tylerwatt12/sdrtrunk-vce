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
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
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
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallAttributionEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityEligibility;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/**
 * Maps DMR and NXDN call-start notifications into the compact activity projection.  P25 call identity is owned by
 * the P25 grant/activity path and deliberately does not flow through this tracker mapper.
 */
class TrunkedCallActivityMapper
{
    ReceiverActivityRecords.ActivityEvent map(TrunkedCallStartEvent callStart)
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
        TrunkedIdentityDomain identityDomain = identityDomain(channel);
        if(protocol == Protocol.NXDN && !TrunkedIdentityEligibility.nxdnIdentifiersMatchDomain(identifiers,
            identityDomain))
        {
            return null;
        }
        Identifier source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier target = identifiers != null ? identifiers.getToIdentifier() : null;
        IChannelDescriptor descriptor = event.getChannelDescriptor();
        Long frequency = descriptor != null && descriptor.getDownlinkFrequency() > 0 ?
            descriptor.getDownlinkFrequency() : null;
        Integer timeslot = event.hasTimeslot() ? event.getTimeslot() : null;
        String configurationId = ChannelConfigurationKey.configured(channel);

        if(configurationId == null)
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

        return new ReceiverActivityRecords.ActivityEvent(event.getTimeStart(), configurationId,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, configuredProtocolName(channel, protocol),
            ReceiverActivityRecords.Action.CALL,
            eventType.name(), sourceId, targetId, targetKind, List.of(), frequency,
            descriptor != null ? descriptor.toString() : null, timeslot, encrypted,
            encrypted && encryptionKey != null ? encryptionKey.getAlgorithm() : null,
            encrypted && encryptionKey != null ? encryptionKey.getKey() : null,
            null, intValue(identifiers, Form.SYSTEM), null, null, intValue(identifiers, Form.SITE),
            value(first(identifiers, Form.TALKER_ALIAS)), true, null, null,
            identityDomain, ReceiverActivityRecords.P25Identity.UNKNOWN,
            ReceiverActivityRecords.P25Identity.UNKNOWN, List.of());
    }

    ReceiverActivityRecords.TrunkedCallAttribution map(TrunkedCallAttributionEvent attribution)
    {
        if(attribution == null || attribution.channel() == null || attribution.protocol() == null ||
            attribution.callStartEpochMilliseconds() <= 0)
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
        TrunkedIdentityDomain identityDomain = identityDomain(channel);
        if(protocol == Protocol.NXDN && !TrunkedIdentityEligibility.nxdnIdentifiersMatchDomain(identifiers,
            identityDomain))
        {
            return null;
        }
        Identifier target = identifiers != null ? identifiers.getToIdentifier() : null;
        Identifier source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Integer destinationId = identityId(target);
        String destinationKind = target != null && target.getForm() != null ? target.getForm().name() : null;
        Integer sourceRadio = source != null && source.getForm() == Form.RADIO ? identityId(source) : null;
        String configurationId = ChannelConfigurationKey.configured(channel);
        IChannelDescriptor descriptor = attribution.channelDescriptor();
        Long frequency = descriptor != null && descriptor.getDownlinkFrequency() > 0 ?
            descriptor.getDownlinkFrequency() : null;

        if(configurationId == null)
        {
            return null;
        }

        if(!attribution.destinationBecameKnown() && !attribution.sourceBecameKnown() &&
            !attribution.encryptionBecameKnown() && attribution.encryptionAlgorithmId() == null &&
            attribution.encryptionKeyId() == null)
        {
            return null;
        }

        return new ReceiverActivityRecords.TrunkedCallAttribution(
            attribution.callStartEpochMilliseconds(), configurationId, configuredProtocolName(channel, protocol),
            frequency, attribution.timeslot(),
            destinationId != null ? destinationId : 0, destinationKind, patchMemberTalkgroups(target),
            sourceRadio, attribution.encryptionAlgorithmId(), attribution.encryptionKeyId(),
            attribution.destinationBecameKnown(), attribution.sourceBecameKnown(),
            attribution.encryptionBecameKnown(), attribution.encryptedBeforeObservation(),
            identityDomain);
    }

    private static Integer identityId(Identifier identifier)
    {
        if(identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null &&
            patch.getValue().getPatchGroup() != null)
        {
            Identifier primary = patch.getValue().getPatchGroup();
            int value = primary.getValue() instanceof Number number ? number.intValue() : -1;
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

    private static TrunkedIdentityDomain identityDomain(Channel channel)
    {
        if(channel != null && channel.getDecodeConfiguration() instanceof DecodeConfigNXDN config)
        {
            return config.getTransmissionMode() != null && config.getTransmissionMode().isTypeD() ?
                TrunkedIdentityDomain.NXDN_TYPE_D :
                TrunkedIdentityDomain.NXDN_TYPE_C;
        }

        return TrunkedIdentityDomain.STANDARD;
    }

    private static String configuredProtocolName(Channel channel, Protocol fallback)
    {
        DecoderType decoderType = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        return decoderType == DecoderType.DMR ? Protocol.DMR.name() :
            decoderType == DecoderType.NXDN ? Protocol.NXDN.name() :
            fallback != null ? fallback.name() : Protocol.UNKNOWN.name();
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
