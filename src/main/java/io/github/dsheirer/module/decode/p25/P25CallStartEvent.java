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
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import java.util.List;
import java.util.Objects;

/**
 * Immutable producer-side facts for the instant a P25 call tracker is created. The traffic tracker and its parent
 * channel remain mutable for decoding and display, so neither live object may cross the asynchronous statistics
 * handoff.
 */
public record P25CallStartEvent(String configurationId, DecoderType decoderType, DecodeEventType eventType,
                                long startedAtEpochMilliseconds, List<Identifier> identifiers,
                                Long frequencyHertz, Integer channelBand, Integer channelNumber, Integer timeslot,
                                boolean tdma, CallEncryptionState encryptionState, boolean encryptionConfirmed, String radioSystemKey, String callToken)
{
    public P25CallStartEvent
    {
        configurationId = ChannelConfigurationKey.canonical(configurationId);
        encryptionState = encryptionState != null ? encryptionState : CallEncryptionState.UNKNOWN;
        identifiers = snapshotIdentifiers(identifiers);
        frequencyHertz = frequencyHertz != null && frequencyHertz > 0 ? frequencyHertz : null;
        channelBand = channelBand != null && channelBand >= 0 ? channelBand : null;
        channelNumber = channelNumber != null && channelNumber >= 0 ? channelNumber : null;
        timeslot = timeslot != null && timeslot >= 0 ? timeslot : null;
        Protocol protocol = decoderType == DecoderType.P25_PHASE2 ? Protocol.APCO25_PHASE2 : Protocol.APCO25;
        radioSystemKey = radioSystemKey != null ? RadioSystemKey.nativeFor(protocol,
            TrunkedIdentityDomain.STANDARD, radioSystemKey) : null;
    }

    /** Captures all facts before publishing to the asynchronous statistics worker. */
    public P25CallStartEvent(Channel channel, P25ChannelGrantEvent event, String radioSystemKey)
    {
        this(channel, event, radioSystemKey, null);
    }

    public P25CallStartEvent(Channel channel, P25ChannelGrantEvent event, String radioSystemKey, String callToken)
    {
        this(ChannelConfigurationKey.configured(channel), decoderType(channel), eventType(event), start(event),
            identifiers(event), frequency(event), band(event), channelNumber(event), timeslot(event), isTdma(event),
            encryptionState(event), encryptionConfirmed(event), radioSystemKey, callToken);
    }

    /** Captures conventional producers without an exact call token. */
    public P25CallStartEvent(String configurationId, DecoderType decoderType, DecodeEventType eventType,
                             long startedAtEpochMilliseconds, List<Identifier> identifiers,
                             Long frequencyHertz, Integer channelBand, Integer channelNumber, Integer timeslot,
                             boolean tdma, CallEncryptionState encryptionState, boolean encryptionConfirmed, String radioSystemKey)
    {
        this(configurationId, decoderType, eventType, startedAtEpochMilliseconds, identifiers, frequencyHertz,
            channelBand, channelNumber, timeslot, tdma, encryptionState, encryptionConfirmed, radioSystemKey, null);
    }

    /** Fills compatible call facts without moving the original owner, start or established physical resource. */
    public P25CallStartEvent withIdentifiers(P25ChannelGrantEvent event, CallEncryptionState encryptionState)
    {
        boolean compatible = compatibleDescriptor(event);
        return new P25CallStartEvent(configurationId, decoderType, currentEventType(event, encryptionState),
            startedAtEpochMilliseconds, identifiers(event),
            compatible && frequencyHertz == null ? frequency(event) : frequencyHertz,
            compatible && channelBand == null ? band(event) : channelBand,
            compatible && channelNumber == null ? channelNumber(event) : channelNumber,
            compatible && timeslot == null ? timeslot(event) : timeslot,
            tdma || compatible && channelBand == null && channelNumber == null && isTdma(event),
            encryptionState,
            currentEncryptionConfirmed(event), radioSystemKey, callToken);
    }

    /** Cheap comparison before copying identifiers for a changed immutable snapshot. */
    boolean sameMetadata(P25ChannelGrantEvent event, CallEncryptionState state, boolean confirmed)
    {
        if(eventType != currentEventType(event, state) || encryptionState != state ||
            encryptionConfirmed != confirmed) return false;
        return !compatibleDescriptor(event) ||
            (frequencyHertz != null || frequency(event) == null) &&
            (channelBand != null || band(event) == null) &&
            (channelNumber != null || channelNumber(event) == null) &&
            (timeslot != null || timeslot(event) == null) &&
            (tdma || channelBand != null || channelNumber != null || !isTdma(event));
    }

    private boolean compatibleDescriptor(P25ChannelGrantEvent event)
    {
        return compatible(frequencyHertz, frequency(event)) && compatible(channelBand, band(event)) &&
            compatible(channelNumber, channelNumber(event)) && compatible(timeslot, timeslot(event));
    }

    private static boolean compatible(Object retained, Object current)
    {
        return retained == null || current == null || Objects.equals(retained, current);
    }

    private DecodeEventType currentEventType(P25ChannelGrantEvent event, CallEncryptionState state)
    {
        DecodeEventType candidate = eventType(event);
        DecodeEventType type = eventType;
        if(candidate != null && candidate.isVoiceCallEvent() &&
            (type == null || voiceFamily(type) == 0 || voiceFamily(type) == voiceFamily(candidate))) type = candidate;
        Identifier target = event != null && event.getIdentifierCollection() != null ?
            event.getIdentifierCollection().getToIdentifier() : null;
        if(type != null && type.isVoiceCallEvent() && voiceFamily(type) == 0 && target != null)
        {
            type = switch(target.getForm())
            {
                case TALKGROUP -> DecodeEventType.CALL_GROUP;
                case PATCH_GROUP -> DecodeEventType.CALL_PATCH_GROUP;
                case RADIO -> DecodeEventType.CALL_UNIT_TO_UNIT;
                case TELEPHONE_NUMBER -> DecodeEventType.CALL_INTERCONNECT;
                default -> type;
            };
        }
        if(type == null || !type.isVoiceCallEvent() || !state.isKnown()) return type;
        return switch(voiceFamily(type))
        {
            case 1 -> state.isEncrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP;
            case 2 -> state.isEncrypted() ? DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED : DecodeEventType.CALL_PATCH_GROUP;
            case 3 -> state.isEncrypted() ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED : DecodeEventType.CALL_UNIT_TO_UNIT;
            case 4 -> state.isEncrypted() ? DecodeEventType.CALL_INTERCONNECT_ENCRYPTED : DecodeEventType.CALL_INTERCONNECT;
            default -> state.isEncrypted() ? DecodeEventType.CALL_ENCRYPTED : DecodeEventType.CALL;
        };
    }

    private static int voiceFamily(DecodeEventType type)
    {
        return switch(type)
        {
            case CALL_GROUP, CALL_GROUP_ENCRYPTED -> 1;
            case CALL_PATCH_GROUP, CALL_PATCH_GROUP_ENCRYPTED -> 2;
            case CALL_UNIT_TO_UNIT, CALL_UNIT_TO_UNIT_ENCRYPTED -> 3;
            case CALL_INTERCONNECT, CALL_INTERCONNECT_ENCRYPTED -> 4;
            default -> 0;
        };
    }

    CallEncryptionState currentEncryptionState(P25ChannelGrantEvent event)
    {
        return hasEncryptionEvidence(event) ? encryptionState(event) : encryptionState;
    }

    boolean currentEncryptionConfirmed(P25ChannelGrantEvent event)
    {
        if(encryptionConfirmed(event)) return true;
        if(P25EncryptionConfirmationTracker.isConflicted(event)) return false;
        if(!encryptionConfirmed || !encryptionState.isEncrypted() || event == null ||
            event.getIdentifierCollection() == null) return false;
        Identifier current = event.getIdentifierCollection().getEncryptionIdentifier();
        if(!(current instanceof EncryptionKeyIdentifier key) || key.getValue() == null) return false;
        for(Identifier identifier: identifiers)
        {
            if(identifier instanceof EncryptionKeyIdentifier previous && previous.getValue() != null)
                return previous.getValue().getAlgorithm() == key.getValue().getAlgorithm() &&
                    previous.getValue().getKey() == key.getValue().getKey();
        }
        return false;
    }

    public P25CallStartEvent(Channel channel, P25ChannelGrantEvent event)
    {
        this(channel, event, RadioSystemKey.p25(channel != null ? channel.getP25SiteIdentity() : null));
    }

    /** Returns a new collection and new composite identifiers so consumers cannot change the captured event state. */
    public IdentifierCollection identifierCollection()
    {
        IdentifierCollection collection = new IdentifierCollection(snapshotIdentifiers(identifiers));
        collection.setTimeslot(timeslot != null ? timeslot : 0);
        return collection;
    }

    /** Prevents callers from obtaining the record's internally captured mutable composite identifier. */
    @Override
    public List<Identifier> identifiers()
    {
        return snapshotIdentifiers(identifiers);
    }

    public Protocol protocol()
    {
        return decoderType == DecoderType.P25_PHASE2 ? Protocol.APCO25_PHASE2 : Protocol.APCO25;
    }

    /** Formats the captured numeric P25 channel on the statistics worker. */
    public String channelDescriptor()
    {
        if(channelBand == null || channelNumber == null)
        {
            return null;
        }

        String descriptor = channelBand + "-" + channelNumber;
        return tdma && timeslot != null ? descriptor + " TS" + timeslot : descriptor;
    }

    private static DecoderType decoderType(Channel channel)
    {
        return channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
    }

    private static DecodeEventType eventType(P25ChannelGrantEvent event)
    {
        return event != null ? event.getEventType() : null;
    }

    private static long start(P25ChannelGrantEvent event)
    {
        return event != null ? event.getTimeStart() : 0;
    }

    private static List<Identifier> identifiers(P25ChannelGrantEvent event)
    {
        IdentifierCollection collection = event != null ? event.getIdentifierCollection() : null;
        return collection != null ? collection.getIdentifiers() : List.of();
    }

    /** Copies the one composite P25 identifier whose member lists can change while a call is active. */
    private static List<Identifier> snapshotIdentifiers(List<Identifier> identifiers)
    {
        if(identifiers == null || identifiers.isEmpty())
        {
            return List.of();
        }

        return identifiers.stream()
            .filter(identifier -> !(identifier instanceof APCO25Channel))
            .map(identifier ->
        {
            if(identifier instanceof APCO25PatchGroup patchIdentifier && patchIdentifier.getValue() != null)
            {
                PatchGroup original = patchIdentifier.getValue();
                PatchGroup snapshot = new PatchGroup(original.getPatchGroup(), original.getVersion());
                snapshot.addPatchedTalkgroups(List.copyOf(original.getPatchedTalkgroupIdentifiers()));
                snapshot.addPatchedRadios(List.copyOf(original.getPatchedRadioIdentifiers()));
                return APCO25PatchGroup.create(snapshot);
            }

            return identifier;
        }).toList();
    }

    private static Long frequency(P25ChannelGrantEvent event)
    {
        IChannelDescriptor descriptor = event != null ? event.getChannelDescriptor() : null;
        return descriptor != null && descriptor.getDownlinkFrequency() > 0 ? descriptor.getDownlinkFrequency() : null;
    }

    private static Integer band(P25ChannelGrantEvent event)
    {
        IChannelDescriptor descriptor = event != null ? event.getChannelDescriptor() : null;
        return descriptor instanceof APCO25Channel channel ? channel.getValue().getDownlinkBandIdentifier() : null;
    }

    private static Integer channelNumber(P25ChannelGrantEvent event)
    {
        IChannelDescriptor descriptor = event != null ? event.getChannelDescriptor() : null;
        if(!(descriptor instanceof APCO25Channel channel))
        {
            return null;
        }

        int channelNumber = channel.getValue().getDownlinkChannelNumber();
        int timeslotCount = channel.getTimeslotCount();
        return timeslotCount > 1 ? channelNumber - channelNumber % timeslotCount : channelNumber;
    }

    private static Integer timeslot(P25ChannelGrantEvent event)
    {
        return event != null && event.hasTimeslot() ? event.getTimeslot() : null;
    }

    private static boolean isTdma(P25ChannelGrantEvent event)
    {
        IChannelDescriptor descriptor = event != null ? event.getChannelDescriptor() : null;
        return descriptor != null && descriptor.isTDMAChannel();
    }

    /** Captures call encryption independently of whether algorithm/key details have repeated confirmation. */
    public static CallEncryptionState encryptionState(P25ChannelGrantEvent event)
    {
        return event != null ? encryptionState(event.getEventType(), event.getServiceOptions(),
            event.getIdentifierCollection()) : CallEncryptionState.UNKNOWN;
    }

    private static boolean hasEncryptionEvidence(P25ChannelGrantEvent event)
    {
        return event != null && (event.hasServiceOptions() ||
            event.getIdentifierCollection() != null && event.getIdentifierCollection().getEncryptionIdentifier() != null ||
            event.getEventType() != null && (DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(event.getEventType()) ||
                event.getEventType() == DecodeEventType.DATA_CALL_ENCRYPTED));
    }

    static CallEncryptionState encryptionState(DecodeEventType eventType, ServiceOptions serviceOptions,
        IdentifierCollection identifiers)
    {
        Identifier identifier = identifiers != null ? identifiers.getEncryptionIdentifier() : null;
        CallEncryptionState keyState = identifier instanceof EncryptionKeyIdentifier key && key.getValue() != null ?
            CallEncryptionState.fromEncrypted(key.isEncrypted()) : CallEncryptionState.UNKNOWN;
        CallEncryptionState signaledState = serviceOptions != null ?
            CallEncryptionState.fromEncrypted(serviceOptions.isEncrypted()) :
            eventType != null && (DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(eventType) ||
                eventType == DecodeEventType.DATA_CALL_ENCRYPTED) ?
                CallEncryptionState.ENCRYPTED : CallEncryptionState.UNKNOWN;
        if(keyState.isKnown() && signaledState.isKnown() && keyState != signaledState)
            return CallEncryptionState.UNKNOWN;
        return keyState.isKnown() ? keyState : signaledState;
    }

    static boolean encryptionConfirmed(P25ChannelGrantEvent event)
    {
        IdentifierCollection identifiers = event != null ? event.getIdentifierCollection() : null;
        Identifier identifier = identifiers != null ? identifiers.getEncryptionIdentifier() : null;

        if(identifier instanceof EncryptionKeyIdentifier encryptionIdentifier && encryptionIdentifier.isEncrypted())
        {
            EncryptionKey encryptionKey = encryptionIdentifier.getValue();
            return encryptionKey != null && P25EncryptionConfirmationTracker.isConfirmed(event,
                encryptionKey.getAlgorithm(), encryptionKey.getKey());
        }

        return false;
    }
}
