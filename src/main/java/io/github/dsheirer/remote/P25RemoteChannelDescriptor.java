/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.protocol.Protocol;
import java.text.DecimalFormat;

/** Frequency-resolved descriptor for a remote carrier whose over-the-air band/channel numbers are not required. */
public class P25RemoteChannelDescriptor implements IChannelDescriptor
{
    private static final DecimalFormat FREQUENCY_FORMATTER = new DecimalFormat("0.0000");
    private final long mFrequency;
    private final P25RemotePhase mPhase;

    public P25RemoteChannelDescriptor(long frequency, P25RemotePhase phase)
    {
        if(frequency <= 0L || phase == null)
        {
            throw new IllegalArgumentException("Invalid remote P25 channel descriptor");
        }

        mFrequency = frequency;
        mPhase = phase;
    }

    @Override
    public long getDownlinkFrequency()
    {
        return mFrequency;
    }

    @Override
    public long getUplinkFrequency()
    {
        return mFrequency;
    }

    @Override
    public int[] getFrequencyBandIdentifiers()
    {
        return new int[0];
    }

    @Override
    public void setFrequencyBand(IFrequencyBand bandIdentifier)
    {
        //The sender supplied the already-resolved RF frequency.
    }

    @Override
    public boolean isTDMAChannel()
    {
        return mPhase == P25RemotePhase.PHASE_2;
    }

    @Override
    public int getTimeslotCount()
    {
        return isTDMAChannel() ? 2 : 1;
    }

    @Override
    public Protocol getProtocol()
    {
        return isTDMAChannel() ? Protocol.APCO25_PHASE2 : Protocol.APCO25;
    }

    @Override
    public String toString()
    {
        return FREQUENCY_FORMATTER.format(mFrequency / 1E6d) + " MHz REMOTE";
    }
}
