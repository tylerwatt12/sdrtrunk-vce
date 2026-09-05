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
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/**
 * Immutable producer-side late identity/encryption facts for an already-counted DMR or NXDN logical call.
 */
public record TrunkedCallAttributionEvent(String configurationId, DecoderType decoderType, Protocol protocol,
                                          TrunkedIdentityDomain identityDomain, Long frequencyHertz,
                                          TrunkedChannelDescriptorSnapshot channelDescriptorSnapshot,
                                          Integer timeslot,
                                          long callStartEpochMilliseconds, Integer destinationId,
                                          Form destinationForm, List<Integer> patchMemberTalkgroupIds,
                                          Integer sourceRadioId, boolean destinationBecameKnown,
                                          boolean sourceBecameKnown, boolean encryptionBecameKnown,
                                          Integer encryptionAlgorithmId, Integer encryptionKeyId,
                                          boolean encryptedBeforeObservation, String radioSystemKey)
{
    public TrunkedCallAttributionEvent
    {
        configurationId = ChannelConfigurationKey.canonical(configurationId);
        identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.STANDARD;
        frequencyHertz = frequencyHertz != null && frequencyHertz > 0 ? frequencyHertz : null;
        timeslot = timeslot != null && timeslot >= 0 ? timeslot :
            channelDescriptorSnapshot != null ? channelDescriptorSnapshot.timeslot() : null;
        patchMemberTalkgroupIds = patchMemberTalkgroupIds == null ? List.of() : patchMemberTalkgroupIds.stream()
            .filter(member -> member != null && member > 0)
            .filter(member -> !member.equals(destinationId))
            .distinct()
            .sorted()
            .toList();
        radioSystemKey = RadioSystemKey.validateForReceiver(protocol, identityDomain, configurationId,
            radioSystemKey);
    }

    /** Captures every value while still on the producing decoder thread. */
    public TrunkedCallAttributionEvent(Channel channel, Protocol protocol,
                                       IChannelDescriptor channelDescriptor, Integer timeslot,
                                       long callStartEpochMilliseconds, IdentifierCollection identifiers,
                                       boolean destinationBecameKnown, boolean sourceBecameKnown,
                                       boolean encryptionBecameKnown, Integer encryptionAlgorithmId,
                                       Integer encryptionKeyId, boolean encryptedBeforeObservation,
                                       String radioSystemKey)
    {
        this(channel, protocol, TrunkedChannelDescriptorSnapshot.capture(channelDescriptor), timeslot,
            callStartEpochMilliseconds, identifiers, destinationBecameKnown, sourceBecameKnown,
            encryptionBecameKnown, encryptionAlgorithmId, encryptionKeyId, encryptedBeforeObservation,
            radioSystemKey);
    }

    /** Uses the one producer-side descriptor capture already used to correlate this call. */
    TrunkedCallAttributionEvent(Channel channel, Protocol protocol,
                                TrunkedChannelDescriptorSnapshot channelDescriptorSnapshot, Integer timeslot,
                                long callStartEpochMilliseconds, IdentifierCollection identifiers,
                                boolean destinationBecameKnown, boolean sourceBecameKnown,
                                boolean encryptionBecameKnown, Integer encryptionAlgorithmId,
                                Integer encryptionKeyId, boolean encryptedBeforeObservation,
                                String radioSystemKey)
    {
        this(TrunkedCallSnapshot.eligibleConfigurationId(channel, protocol, identifiers), decoderType(channel), protocol,
            TrunkedCallSnapshot.identityDomain(channel, protocol),
            channelDescriptorSnapshot != null ? channelDescriptorSnapshot.downlinkFrequencyHertz() : null,
            channelDescriptorSnapshot, timeslot, callStartEpochMilliseconds,
            TrunkedCallSnapshot.destinationId(identifiers), TrunkedCallSnapshot.targetForm(identifiers),
            TrunkedCallSnapshot.patchMemberTalkgroupIds(identifiers),
            TrunkedCallSnapshot.sourceRadioIdentity(identifiers), destinationBecameKnown, sourceBecameKnown,
            encryptionBecameKnown, encryptionAlgorithmId, encryptionKeyId, encryptedBeforeObservation,
            radioSystemKey);
    }

    private static DecoderType decoderType(Channel channel)
    {
        return channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
    }
}
