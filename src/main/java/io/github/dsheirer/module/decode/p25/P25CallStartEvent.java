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
import java.util.List;

/**
 * Immutable producer-side facts for the instant a P25 call tracker is created. The traffic tracker and its parent
 * channel remain mutable for decoding and display, so neither live object may cross the asynchronous statistics
 * handoff.
 */
public record P25CallStartEvent(String configurationId, DecoderType decoderType, DecodeEventType eventType,
                                long startedAtEpochMilliseconds, List<Identifier> identifiers,
                                Long frequencyHertz, Integer channelBand, Integer channelNumber, Integer timeslot,
                                boolean tdma, boolean encryptionConfirmed, String radioSystemKey)
{
    public P25CallStartEvent
    {
        configurationId = ChannelConfigurationKey.canonical(configurationId);
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
        this(ChannelConfigurationKey.configured(channel), decoderType(channel), eventType(event), start(event),
            identifiers(event), frequency(event), band(event), channelNumber(event), timeslot(event), isTdma(event),
            encryptionConfirmed(event), radioSystemKey);
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

    private static boolean encryptionConfirmed(P25ChannelGrantEvent event)
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
