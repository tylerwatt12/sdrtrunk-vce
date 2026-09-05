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
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRLsn;
import io.github.dsheirer.module.decode.dmr.channel.DMRTier3Channel;
import io.github.dsheirer.module.decode.dmr.channel.DmrRestLsn;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannel;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelDFA;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelFake;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;

/**
 * Immutable numeric channel facts captured on a decoder callback.  Human-readable channel text is intentionally
 * omitted so message and descriptor formatting can happen later on the statistics worker.
 */
public record TrunkedChannelDescriptorSnapshot(Kind kind, Integer primaryChannelNumber,
                                               Integer secondaryChannelNumber, Integer timeslot,
                                               Long downlinkFrequencyHertz, Long uplinkFrequencyHertz)
{
    public TrunkedChannelDescriptorSnapshot
    {
        kind = kind != null ? kind : Kind.UNKNOWN;
        primaryChannelNumber = nonNegative(primaryChannelNumber);
        secondaryChannelNumber = nonNegative(secondaryChannelNumber);
        timeslot = timeslot != null && timeslot >= 0 ? timeslot : null;
        downlinkFrequencyHertz = positive(downlinkFrequencyHertz);
        uplinkFrequencyHertz = positive(uplinkFrequencyHertz);
    }

    /** Captures only fixed-cost scalar getters and never invokes {@link Object#toString()}. */
    public static TrunkedChannelDescriptorSnapshot capture(IChannelDescriptor descriptor)
    {
        if(descriptor == null)
        {
            return null;
        }

        Long downlink = positive(descriptor.getDownlinkFrequency());
        Long uplink = positive(descriptor.getUplinkFrequency());

        if(descriptor instanceof DmrRestLsn rest)
        {
            return new TrunkedChannelDescriptorSnapshot(Kind.DMR_REST_LSN, rest.getLsn(),
                rest.getChannelNumber(), rest.getTimeslot(), downlink, uplink);
        }
        if(descriptor instanceof DMRLsn lsn)
        {
            return new TrunkedChannelDescriptorSnapshot(Kind.DMR_LSN, lsn.getLsn(),
                lsn.getChannelNumber(), lsn.getTimeslot(), downlink, uplink);
        }
        if(descriptor instanceof DMRTier3Channel tierThree)
        {
            return new TrunkedChannelDescriptorSnapshot(Kind.DMR_TIER_III, tierThree.getChannelNumber(),
                null, tierThree.getTimeslot(), downlink, uplink);
        }
        if(descriptor instanceof DMRAbsoluteChannel absolute)
        {
            return new TrunkedChannelDescriptorSnapshot(Kind.DMR_ABSOLUTE, absolute.getChannelNumber(),
                null, absolute.getTimeslot(), downlink, uplink);
        }
        if(descriptor instanceof DMRChannel dmr)
        {
            return new TrunkedChannelDescriptorSnapshot(Kind.DMR_CHANNEL, dmr.getChannelNumber(),
                null, dmr.getTimeslot(), downlink, uplink);
        }
        if(descriptor instanceof NXDNChannelFake)
        {
            return new TrunkedChannelDescriptorSnapshot(Kind.NXDN_FAKE, null, null, null, null, null);
        }
        if(descriptor instanceof NXDNChannelLookup lookup)
        {
            return new TrunkedChannelDescriptorSnapshot(Kind.NXDN_LOOKUP, lookup.getChannelNumber(),
                null, null, downlink, uplink);
        }
        if(descriptor instanceof NXDNChannelDFA dfa)
        {
            return new TrunkedChannelDescriptorSnapshot(Kind.NXDN_DFA, dfa.getOutboundChannelNumber(),
                dfa.getInboundChannelNumber(), null, downlink, uplink);
        }
        if(descriptor instanceof NXDNChannel)
        {
            return new TrunkedChannelDescriptorSnapshot(Kind.NXDN_CHANNEL, null, null, null,
                downlink, uplink);
        }

        return new TrunkedChannelDescriptorSnapshot(Kind.UNKNOWN, null, null, null, downlink, uplink);
    }

    private static Integer nonNegative(Integer value)
    {
        return value != null && value >= 0 ? value : null;
    }

    private static Long positive(Long value)
    {
        return value != null && value > 0 ? value : null;
    }

    private static Long positive(long value)
    {
        return value > 0 ? value : null;
    }

    public enum Kind
    {
        DMR_CHANNEL,
        DMR_TIER_III,
        DMR_LSN,
        DMR_REST_LSN,
        DMR_ABSOLUTE,
        NXDN_CHANNEL,
        NXDN_LOOKUP,
        NXDN_DFA,
        NXDN_FAKE,
        UNKNOWN
    }
}
