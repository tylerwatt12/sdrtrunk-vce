/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.ModuleEventBusMessage;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.channel.DmrRestLsn;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import java.util.Objects;

/**
 * Small, immutable request to move a Capacity Plus rest channel.  This request deliberately contains no message or
 * decode-event history.  The decoder callback publishes it to a bounded lifecycle worker that owns conversion and
 * replacement-channel startup.
 */
public record DMRRestChannelHandoffRequest(DMRTrafficChannelManager owner, Channel parentChannel,
                                           long currentFrequency, int restChannelNumber, int restTimeslot,
                                           long restDownlinkFrequency, long restUplinkFrequency,
                                           DMRNetworkConfigurationSnapshot networkConfigurationSnapshot,
                                           long generation) implements ModuleEventBusMessage
{
    public DMRRestChannelHandoffRequest(DMRTrafficChannelManager owner, Channel parentChannel,
                                        long currentFrequency, DMRChannel restChannel, long generation)
    {
        this(owner, parentChannel, currentFrequency, restChannel, null, generation);
    }

    public DMRRestChannelHandoffRequest(DMRTrafficChannelManager owner, Channel parentChannel,
                                        long currentFrequency, DMRChannel restChannel,
                                        DMRNetworkConfigurationSnapshot networkConfigurationSnapshot,
                                        long generation)
    {
        this(owner, parentChannel, currentFrequency,
            Objects.requireNonNull(restChannel, "DMR rest channel cannot be null").getChannelNumber(),
            restChannel.getTimeslot(), restChannel.getDownlinkFrequency(), restChannel.getUplinkFrequency(),
            networkConfigurationSnapshot, generation);
    }

    public DMRRestChannelHandoffRequest
    {
        Objects.requireNonNull(owner, "DMR traffic channel manager cannot be null");
        Objects.requireNonNull(parentChannel, "DMR parent channel cannot be null");

        if(currentFrequency <= 0)
        {
            throw new IllegalArgumentException("Current frequency must be positive");
        }

        if(restChannelNumber <= 0 || (restTimeslot != 1 && restTimeslot != 2) || restDownlinkFrequency <= 0)
        {
            throw new IllegalArgumentException("Rest-channel identity and downlink frequency must be valid");
        }

        if(generation <= 0)
        {
            throw new IllegalArgumentException("Handoff generation must be positive");
        }
    }

    /**
     * Creates the decoder descriptor on the lifecycle worker.  The queued handoff itself contains only immutable
     * primitive state and cannot be changed after the decoder callback offers it.
     */
    public DmrRestLsn createRestChannel()
    {
        int lsn = ((restChannelNumber - 1) * 2) + restTimeslot;
        DmrRestLsn restChannel = new DmrRestLsn(lsn);
        TimeslotFrequency frequency = new TimeslotFrequency();
        frequency.setNumber(restChannelNumber);
        frequency.setDownlinkFrequency(restDownlinkFrequency);
        frequency.setUplinkFrequency(restUplinkFrequency);
        restChannel.setTimeslotFrequency(frequency);
        return restChannel;
    }

    /**
     * Indicates whether the supplied descriptor names the same queued target without retaining that mutable object.
     */
    public boolean matchesRestChannel(DMRChannel restChannel)
    {
        return restChannel != null && restChannelNumber == restChannel.getChannelNumber() &&
            restTimeslot == restChannel.getTimeslot() && restDownlinkFrequency == restChannel.getDownlinkFrequency() &&
            restUplinkFrequency == restChannel.getUplinkFrequency();
    }
}
