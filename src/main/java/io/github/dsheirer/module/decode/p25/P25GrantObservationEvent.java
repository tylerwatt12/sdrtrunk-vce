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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
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
 * Immutable facts captured when a control-channel grant or grant update is decoded. The live channel, channel
 * descriptor and mutable identifier collection must not cross the asynchronous statistics boundary.
 */
public record P25GrantObservationEvent(String configurationId, DecoderType decoderType,
                                       DecodeEventType eventType, long timestamp, boolean continuation,
                                       boolean confirmedBand, List<Identifier> identifiers, Long frequencyHertz,
                                       Integer channelBand, Integer channelNumber, Integer timeslot, boolean tdma,
                                       String radioSystemKey)
{
    public P25GrantObservationEvent
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

    /** Captures every value used by statistics before this event is posted. */
    public P25GrantObservationEvent(Channel channel, APCO25Channel channelDescriptor,
                                    IdentifierCollection identifiers, DecodeEventType eventType, long timestamp,
                                    boolean continuation, boolean confirmedBand)
    {
        this(ChannelConfigurationKey.configured(channel), decoderType(channel), eventType, timestamp, continuation,
            confirmedBand, identifierList(identifiers), frequency(channelDescriptor), band(channelDescriptor),
            channelNumber(channelDescriptor), timeslot(channelDescriptor, identifiers), isTdma(channelDescriptor),
            RadioSystemKey.p25(channel != null ? channel.getP25SiteIdentity() : null));
    }

    public P25GrantObservationEvent(Channel channel, APCO25Channel channelDescriptor,
                                    IdentifierCollection identifiers, DecodeEventType eventType, long timestamp,
                                    boolean continuation)
    {
        this(channel, channelDescriptor, identifiers, eventType, timestamp, continuation, false);
    }

    /** Returns a new wrapper so the captured collection cannot be changed by a consumer. */
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

    /** Formats the captured numeric channel on the statistics worker, never on the decoder callback. */
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

    private static List<Identifier> identifierList(IdentifierCollection identifiers)
    {
        return identifiers != null ? identifiers.getIdentifiers() : List.of();
    }

    /** Patch membership is the one P25 identifier value that is routinely changed after initial publication. */
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

    private static Long frequency(APCO25Channel descriptor)
    {
        return descriptor != null && descriptor.getDownlinkFrequency() > 0 ? descriptor.getDownlinkFrequency() : null;
    }

    private static Integer band(APCO25Channel descriptor)
    {
        return descriptor != null ? descriptor.getValue().getDownlinkBandIdentifier() : null;
    }

    private static Integer channelNumber(APCO25Channel descriptor)
    {
        if(descriptor == null)
        {
            return null;
        }

        int channelNumber = descriptor.getValue().getDownlinkChannelNumber();
        int timeslotCount = descriptor.getTimeslotCount();
        return timeslotCount > 1 ? channelNumber - channelNumber % timeslotCount : channelNumber;
    }

    private static Integer timeslot(APCO25Channel descriptor, IdentifierCollection identifiers)
    {
        if(descriptor != null && descriptor.isTDMAChannel())
        {
            return descriptor.getTimeslot();
        }

        return identifiers != null && identifiers.getTimeslot() > 0 ? identifiers.getTimeslot() : null;
    }

    private static boolean isTdma(APCO25Channel descriptor)
    {
        return descriptor != null && descriptor.isTDMAChannel();
    }
}
