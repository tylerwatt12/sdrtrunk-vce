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
package io.github.dsheirer.module.decode.traffic;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.protocol.Protocol;

/**
 * Immutable producer-side facts for one DMR or NXDN logical call start. Live channel, descriptor, identifier, and
 * decode-event objects must not cross the asynchronous statistics handoff.
 */
public record TrunkedCallStartEvent(String configurationId, DecoderType decoderType, Protocol protocol,
                                    TrunkedIdentityDomain identityDomain, DecodeEventType eventType,
                                    long callStartEpochMilliseconds, Integer sourceRadioId, Integer targetId,
                                    Form targetForm, Long frequencyHertz,
                                    TrunkedChannelDescriptorSnapshot channelDescriptorSnapshot,
                                    Integer timeslot, boolean encrypted, Integer encryptionAlgorithmId,
                                    Integer encryptionKeyId, Integer systemId, Integer siteId, String talkerAlias,
                                    String radioSystemKey)
{
    public TrunkedCallStartEvent
    {
        configurationId = ChannelConfigurationKey.canonical(configurationId);
        identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.STANDARD;
        frequencyHertz = frequencyHertz != null && frequencyHertz > 0 ? frequencyHertz : null;
        timeslot = timeslot != null && timeslot >= 0 ? timeslot :
            channelDescriptorSnapshot != null ? channelDescriptorSnapshot.timeslot() : null;
        talkerAlias = normalized(talkerAlias);

        if(!encrypted)
        {
            encryptionAlgorithmId = null;
            encryptionKeyId = null;
        }

        radioSystemKey = RadioSystemKey.validateForReceiver(protocol, identityDomain, configurationId,
            radioSystemKey);
    }

    /** Captures every value while still on the producing decoder thread. */
    public TrunkedCallStartEvent(Channel channel, Protocol protocol, IChannelDescriptor channelDescriptor,
                                 Integer timeslot, IdentifierCollection identifiers, DecodeEventType eventType,
                                 long callStartEpochMilliseconds, String radioSystemKey)
    {
        this(channel, protocol, TrunkedChannelDescriptorSnapshot.capture(channelDescriptor), timeslot,
            identifiers, eventType, callStartEpochMilliseconds, radioSystemKey);
    }

    /** Uses the one producer-side descriptor capture already used to correlate this call. */
    TrunkedCallStartEvent(Channel channel, Protocol protocol,
                          TrunkedChannelDescriptorSnapshot channelDescriptorSnapshot,
                          Integer timeslot, IdentifierCollection identifiers, DecodeEventType eventType,
                          long callStartEpochMilliseconds, String radioSystemKey)
    {
        this(TrunkedCallSnapshot.eligibleConfigurationId(channel, protocol, identifiers), decoderType(channel), protocol,
            TrunkedCallSnapshot.identityDomain(channel, protocol), eventType, callStartEpochMilliseconds,
            TrunkedCallSnapshot.sourceRadioId(identifiers), TrunkedCallSnapshot.targetId(identifiers),
            TrunkedCallSnapshot.targetForm(identifiers),
            channelDescriptorSnapshot != null ? channelDescriptorSnapshot.downlinkFrequencyHertz() : null,
            channelDescriptorSnapshot, timeslot,
            encrypted(eventType, identifiers), encryptionAlgorithmId(identifiers), encryptionKeyId(identifiers),
            TrunkedCallSnapshot.integerValue(identifiers, io.github.dsheirer.identifier.Form.SYSTEM),
            TrunkedCallSnapshot.integerValue(identifiers, io.github.dsheirer.identifier.Form.SITE),
            TrunkedCallSnapshot.talkerAlias(identifiers),
            radioSystemKey);
    }

    private static DecoderType decoderType(Channel channel)
    {
        return channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
    }

    private static boolean encrypted(DecodeEventType eventType, IdentifierCollection identifiers)
    {
        Identifier identifier = identifiers != null ? identifiers.getEncryptionIdentifier() : null;
        return eventType != null && DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(eventType) ||
            identifier instanceof EncryptionKeyIdentifier encryption && encryption.isEncrypted();
    }

    private static Integer encryptionAlgorithmId(IdentifierCollection identifiers)
    {
        EncryptionKey key = encryptionKey(identifiers);
        return key != null ? key.getAlgorithm() : null;
    }

    private static Integer encryptionKeyId(IdentifierCollection identifiers)
    {
        EncryptionKey key = encryptionKey(identifiers);
        return key != null ? key.getKey() : null;
    }

    private static EncryptionKey encryptionKey(IdentifierCollection identifiers)
    {
        Identifier identifier = identifiers != null ? identifiers.getEncryptionIdentifier() : null;
        return identifier instanceof EncryptionKeyIdentifier encryption ? encryption.getValue() : null;
    }

    private static String normalized(String value)
    {
        return value != null && !value.isBlank() ? value.strip() : null;
    }
}
