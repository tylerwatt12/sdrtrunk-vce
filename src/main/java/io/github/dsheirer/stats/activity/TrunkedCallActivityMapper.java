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

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.traffic.TrunkedCallAttributionEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedChannelDescriptorSnapshot;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
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
        if(callStart == null || callStart.configurationId() == null || callStart.decoderType() == null ||
            callStart.protocol() == null)
        {
            return null;
        }

        Protocol protocol = callStart.protocol();
        DecodeEventType eventType = callStart.eventType();

        if((protocol != Protocol.DMR && protocol != Protocol.NXDN) || eventType == null ||
            !eventType.isVoiceCallEvent() || callStart.callStartEpochMilliseconds() <= 0)
        {
            return null;
        }

        DecoderType decoderType = callStart.decoderType();

        if(protocol == Protocol.DMR && decoderType != DecoderType.DMR ||
            protocol == Protocol.NXDN && decoderType != DecoderType.NXDN)
        {
            return null;
        }

        return new ReceiverActivityRecords.ActivityEvent(callStart.callStartEpochMilliseconds(),
            callStart.configurationId(), ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,
            configuredProtocolName(decoderType, protocol),
            ReceiverActivityRecords.Action.CALL,
            eventType.name(), text(callStart.sourceRadioId()), text(callStart.targetId()),
            callStart.targetForm() != null ? callStart.targetForm().name() : null, List.of(),
            callStart.frequencyHertz(), channelDescriptor(callStart.channelDescriptorSnapshot()),
            callStart.timeslot(), callStart.encrypted(),
            callStart.encryptionAlgorithmId(), callStart.encryptionKeyId(), null, callStart.systemId(), null, null,
            callStart.siteId(), callStart.talkerAlias(), true, null, null, callStart.identityDomain(),
            ReceiverActivityRecords.P25Identity.UNKNOWN,
            ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), callStart.radioSystemKey());
    }

    ReceiverActivityRecords.TrunkedCallAttribution map(TrunkedCallAttributionEvent attribution)
    {
        if(attribution == null || attribution.configurationId() == null || attribution.decoderType() == null ||
            attribution.protocol() == null || attribution.callStartEpochMilliseconds() <= 0)
        {
            return null;
        }

        Protocol protocol = attribution.protocol();
        DecodeTypeMatch typeMatch = new DecodeTypeMatch(attribution.decoderType(), protocol);

        if(!typeMatch.matches())
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
            attribution.callStartEpochMilliseconds(), attribution.configurationId(),
            configuredProtocolName(attribution.decoderType(), protocol), attribution.frequencyHertz(),
            attribution.timeslot(), attribution.destinationId() != null ? attribution.destinationId() : 0,
            attribution.destinationForm() != null ? attribution.destinationForm().name() : null,
            attribution.patchMemberTalkgroupIds(), attribution.sourceRadioId(),
            attribution.encryptionAlgorithmId(), attribution.encryptionKeyId(),
            attribution.destinationBecameKnown(), attribution.sourceBecameKnown(),
            attribution.encryptionBecameKnown(), attribution.encryptedBeforeObservation(),
            attribution.identityDomain(), attribution.radioSystemKey());
    }

    private static String configuredProtocolName(DecoderType decoderType, Protocol fallback)
    {
        return decoderType == DecoderType.DMR ? Protocol.DMR.name() :
            decoderType == DecoderType.NXDN ? Protocol.NXDN.name() :
            fallback != null ? fallback.name() : Protocol.UNKNOWN.name();
    }

    private static String text(Integer value)
    {
        return value != null ? Integer.toString(value) : null;
    }

    /** Runs on the statistics projection worker, never on a decoder or audio callback. */
    private static String channelDescriptor(TrunkedChannelDescriptorSnapshot snapshot)
    {
        if(snapshot == null)
        {
            return null;
        }

        Integer primary = snapshot.primaryChannelNumber();
        Integer secondary = snapshot.secondaryChannelNumber();
        Integer timeslot = snapshot.timeslot();

        return switch(snapshot.kind())
        {
            case DMR_TIER_III -> primary != null ? " LCN:" + primary + " CHANID:" +
                (primary * 2 + (timeslot != null ? timeslot : 0)) : null;
            case DMR_REST_LSN -> primary != null ? "REST:" + primary : null;
            case DMR_LSN -> primary != null ? "LSN:" + primary +
                (secondary != null ? " LCN:" + secondary : "") : null;
            case DMR_ABSOLUTE -> primary != null ? primary + " " +
                (snapshot.downlinkFrequencyHertz() != null ?
                    snapshot.downlinkFrequencyHertz() / 1E6D : 0.0D) : null;
            case DMR_CHANNEL -> primary != null ? "CHAN:" + primary + ":" +
                (timeslot != null ? timeslot : 0) : null;
            case NXDN_LOOKUP -> snapshot.downlinkFrequencyHertz() != null ? nxdnFrequency(snapshot) :
                primary != null ? "CHANNEL-MODE [" + primary + "] IS MISSING CHANNEL:FREQUENCY MAPPING" : null;
            case NXDN_DFA, NXDN_CHANNEL -> nxdnFrequency(snapshot);
            case NXDN_FAKE -> "UNKNOWN";
            case UNKNOWN -> snapshot.downlinkFrequencyHertz() != null ? nxdnFrequency(snapshot) : null;
        };
    }

    private static String nxdnFrequency(TrunkedChannelDescriptorSnapshot snapshot)
    {
        long downlink = snapshot.downlinkFrequencyHertz() != null ? snapshot.downlinkFrequencyHertz() : 0L;
        StringBuilder value = new StringBuilder("DN:").append(downlink / 1E6D);

        if(snapshot.uplinkFrequencyHertz() != null)
        {
            value.append(" UP:").append(snapshot.uplinkFrequencyHertz() / 1E6D);
        }

        return value.append(" MHZ").toString();
    }

    private record DecodeTypeMatch(DecoderType decoderType, Protocol protocol)
    {
        private boolean matches()
        {
            return protocol == Protocol.DMR && decoderType == DecoderType.DMR ||
                protocol == Protocol.NXDN && decoderType == DecoderType.NXDN;
        }
    }
}
