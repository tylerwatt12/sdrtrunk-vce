/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.health;

import io.github.dsheirer.audio.squelch.ISquelchStateListener;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessage;
import io.github.dsheirer.module.decode.nxdn.NXDNMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;

/**
 * Measures how well a standard (not traffic) channel is heard, for node health reports: control frames decoded and
 * lost, symbols without frame sync and seconds locked on a control channel; openings and seconds open on a
 * conventional channel.  The channel's traffic channel manager counts the calls (CallTally) and gives the site's
 * identity.  The totals are kept in the HealthRegistry.
 *
 * Frames are counted as the ControlChannelQualityMonitor counts them, so a report agrees with the decode health VCE
 * shows: a P25 Phase 1 TSBK, a P25 Phase 2 LCCH MAC (once per burst), a DMR burst, an NXDN RF frame.  Unlike that
 * monitor, which keeps a rolling 30 seconds for display, this keeps running totals and never skips a message.
 */
public class HealthMonitor extends Module implements IMessageListener, ISquelchStateListener, ISourceEventListener
{
    /** 4FSK and CQPSK: two bits per symbol, for every protocol counted here. */
    private static final int BITS_PER_SYMBOL = 2;

    private final ChannelHealth mHealth;
    private final DecoderType mDecoderType;
    private final String mProtocol;
    private final boolean mIgnoreDmrCrcChecksums;
    private final Listener<IMessage> mMessageListener = this::receive;
    private final Listener<SquelchStateEvent> mSquelchListener = this::receive;
    private final Listener<SourceEvent> mSourceEventListener = this::receive;
    private long mLastLcchKey = Long.MIN_VALUE;

    /**
     * @param channel the monitor measures (a standard channel)
     */
    public HealthMonitor(Channel channel)
    {
        mHealth = HealthRegistry.getInstance().channel(channel);
        mDecoderType = channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        mProtocol = protocol(mDecoderType);
        mIgnoreDmrCrcChecksums = channel.getDecodeConfiguration() instanceof DecodeConfigDMR config &&
            config.getIgnoreCRCChecksums();
        mHealth.setSyncLossMeasured(measuresSyncLoss(mDecoderType));
    }

    /**
     * Symbol rate of a control channel this decoder decodes, baud, as a node health report gives it, or null when it
     * isn't fixed by the decoder (NXDN runs at 2400 or 4800).
     */
    static Integer symbolRate(DecoderType decoderType)
    {
        if(decoderType == null)
        {
            return null;
        }

        return switch(decoderType)
        {
            case P25_PHASE1, DMR -> 4800;
            case P25_PHASE2 -> 6000;
            default -> null;
        };
    }

    /**
     * True when the decoder says when it loses frame sync (SyncLossMessage): P25 and NXDN do, DMR doesn't.
     */
    static boolean measuresSyncLoss(DecoderType decoderType)
    {
        return decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2 ||
            decoderType == DecoderType.NXDN;
    }

    /**
     * Mode of a conventional channel with this decoder, as a node health report names it, or null when the decoder
     * can't be a conventional channel.
     */
    public static String conventionalMode(DecoderType decoderType)
    {
        if(decoderType == null)
        {
            return null;
        }

        return switch(decoderType)
        {
            case NBFM -> "fm";
            case AM -> "am";
            case P25_PHASE1 -> "p25";
            case DMR -> "dmr";
            case NXDN -> "nxdn";
            default -> null;
        };
    }

    /**
     * Protocol of a site whose control channel this decoder decodes, as a node health report names it, or null.
     */
    static String protocol(DecoderType decoderType)
    {
        if(decoderType == null)
        {
            return null;
        }

        return switch(decoderType)
        {
            case P25_PHASE1, P25_PHASE2 -> "p25";
            case DMR -> "dmr";
            case NXDN -> "nxdn";
            default -> null;
        };
    }

    public ChannelHealth getHealth()
    {
        return mHealth;
    }

    @Override
    public Listener<IMessage> getMessageListener()
    {
        return mMessageListener;
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchListener;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventListener;
    }

    void receive(IMessage message)
    {
        if(message instanceof TSBKMessage tsbk)
        {
            mHealth.control("p25", tsbk.isValid(), tsbk.getTimestamp());

            if(tsbk.isValid())
            {
                identity("nac", tsbk.getNAC());
            }
        }
        else if(message instanceof MacMessage mac && mac.getDataUnitID() != null && mac.getDataUnitID().isLCCH())
        {
            //A burst can carry several MAC structures, each a message: count the burst once.
            long key = mac.getTimestamp() * 4 + mac.getTimeslot();

            if(key != mLastLcchKey)
            {
                mLastLcchKey = key;
                mHealth.control("p25", mac.isValid(), mac.getTimestamp());
            }
        }
        else if(mDecoderType == DecoderType.DMR && message instanceof DataMessage data)
        {
            //The physical burst, as the quality monitor counts it: a valid slot type, and a valid CRC unless the user
            //chose to ignore CRCs.
            boolean valid = data.getSlotType() != null && data.getSlotType().isValid() &&
                (data.isValid() || mIgnoreDmrCrcChecksums);
            mHealth.control("dmr", valid, data.getTimestamp());
        }
        else if(mDecoderType == DecoderType.NXDN && message instanceof NXDNMessage nxdn &&
            nxdn.isRfFrameQualityCarrier())
        {
            mHealth.control("nxdn", nxdn.isRfFrameValid(), nxdn.getTimestamp());
        }
        else if(message instanceof SyncLossMessage syncLoss && mProtocol != null)
        {
            mHealth.syncLoss(Math.max(0, syncLoss.getBitsProcessed()) / BITS_PER_SYMBOL);
        }
    }

    private void identity(String name, Identifier<?> identifier)
    {
        if(identifier != null && identifier.getValue() instanceof Integer value)
        {
            mHealth.identity(name, value);
        }
    }

    private void receive(SquelchStateEvent event)
    {
        if(event.getSquelchState() == SquelchState.UNSQUELCH)
        {
            mHealth.opened(System.currentTimeMillis());
        }
        else if(event.getSquelchState() == SquelchState.SQUELCH)
        {
            mHealth.closed(System.currentTimeMillis());
        }
    }

    private void receive(SourceEvent event)
    {
        if(event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE && event.getValue() != null)
        {
            mHealth.setFrequency(event.getValue().longValue());
        }
    }

    @Override
    public void reset()
    {
    }

    @Override
    public void start()
    {
        mHealth.started(System.currentTimeMillis());
    }

    @Override
    public void stop()
    {
        mHealth.stopped(System.currentTimeMillis());
    }
}
