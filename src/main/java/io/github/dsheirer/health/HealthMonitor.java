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
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.nxdn.NXDNMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;

/**
 * Measures how well a standard (not traffic) channel is heard, for node health reports: control frames decoded and
 * lost, symbols without frame sync and seconds locked on a control channel; openings and seconds open on a
 * conventional channel.  The channel's traffic channel manager counts the calls (CallTally) and gives the site's
 * identity.  The totals are kept in the HealthRegistry.
 *
 * Report control units follow the provider format: P25 Phase 1 TSBKs, Phase 2 LCCH MAC bursts, DMR CSBKs and
 * NXDN outbound CAC frames. These differ from the broader RF-frame quality measurements displayed in VCE. Raw
 * integrity is reported even when DMR Ignore CRC permits a decoder to use a failed-CRC message. Measurements are
 * cumulative fixed atomic scalars; report construction never runs on this callback.
 */
public class HealthMonitor extends Module implements IMessageListener, ISquelchStateListener, ISourceEventListener,
    IComplexSamplesListener
{
    /** 4FSK and CQPSK: two bits per symbol, for every protocol counted here. */
    private static final int BITS_PER_SYMBOL = 2;

    private final ChannelHealth mHealth;
    private final DecoderType mDecoderType;
    private final String mProtocol;
    private final boolean mComplexSource;
    private final Listener<IMessage> mMessageListener = this::receive;
    private final Listener<SquelchStateEvent> mSquelchListener = this::receive;
    private final Listener<SourceEvent> mSourceEventListener = this::receive;
    private long mLastLcchKey = Long.MIN_VALUE;
    private volatile double mSampleRate;
    private final Listener<ComplexSamples> mComplexSamplesListener = this::receiveSamples;

    /**
     * @param channel the monitor measures (a standard channel)
     */
    public HealthMonitor(Channel channel)
    {
        this(channel, true);
    }

    /** The runtime source type distinguishes complex samples from remote symbol-rate-only bitstreams. */
    public HealthMonitor(Channel channel, boolean complexSource)
    {
        mComplexSource = complexSource;
        mHealth = HealthRegistry.getInstance().channel(channel);
        mDecoderType = channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        mProtocol = protocol(mDecoderType);
        mHealth.setProtocol(mProtocol);
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

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return mComplexSamplesListener;
    }

    /** O(1) sample-duration observation; no array contents, copies, queues or report state are touched. */
    private void receiveSamples(ComplexSamples samples)
    {
        if(mComplexSource && samples != null)
        {
            mHealth.deliveredSamples(samples.length(), mSampleRate);
        }
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
        else if(mDecoderType == DecoderType.DMR && message instanceof CSBKMessage data)
        {
            //Only CSBK control units belong in this count, not voice/data/idle bursts. The operator's Ignore CRC
            //setting does not change the raw integrity measurement sent to the provider.
            boolean valid = data.getSlotType() != null && data.getSlotType().isValid() && data.isValid();
            mHealth.control("dmr", valid, data.getTimestamp());
        }
        else if(mDecoderType == DecoderType.NXDN && message instanceof NXDNMessage nxdn &&
            nxdn.isRfFrameQualityCarrier() && isOutboundCac(nxdn.getLICH()))
        {
            mHealth.control("nxdn", nxdn.isRfFrameValid(), nxdn.getTimestamp());
        }
        else if(message instanceof SyncLossMessage syncLoss && mProtocol != null)
        {
            mHealth.syncLoss(Math.max(0, syncLoss.getBitsProcessed()) / BITS_PER_SYMBOL);
        }
    }

    /** RCCH CAC frames only; Type-D traffic/SCCH frames are not CAC units. */
    private static boolean isOutboundCac(LICH lich)
    {
        return lich == LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL ||
            lich == LICH.RCCH_OUTBOUND_SINGLE_CAC_IDLE || lich == LICH.RCCH_OUTBOUND_SINGLE_CAC_COMMON;
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
        if(event.getValue() != null)
        {
            if(event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE ||
                event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_ROTATION_SUCCESS)
            {
                mHealth.setFrequency(event.getValue().longValue());
            }
            else if(event.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
            {
                mSampleRate = event.getValue().doubleValue();
                if(mComplexSource)
                {
                    mHealth.sampleRate(mSampleRate);
                }
            }
        }
    }

    @Override
    public void reset()
    {
        mLastLcchKey = Long.MIN_VALUE;
    }

    @Override
    public void start()
    {
        mLastLcchKey = Long.MIN_VALUE;
        mHealth.started(System.currentTimeMillis());
    }

    @Override
    public void stop()
    {
        mHealth.stopped(System.currentTimeMillis());
    }
}
