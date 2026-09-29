/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventProvider;
import io.github.dsheirer.dsp.symbol.Dibit;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.PrimaryDecoder;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandPreloadDataContent;
import io.github.dsheirer.remote.IP25RemoteBitstreamListener;
import io.github.dsheirer.remote.P25RemoteBitstreamPacket;
import io.github.dsheirer.sample.Listener;

/** P25 Phase 1 message decoder for already-demodulated packed dibits received from a remote sender. */
public class P25P1BitstreamDecoder extends PrimaryDecoder implements IP25RemoteBitstreamListener,
    IDecoderStateEventProvider
{
    private final P25P1MessageFramer mMessageFramer = new P25P1MessageFramer();
    private final P25P1MessageProcessor mMessageProcessor;
    private final Listener<P25RemoteBitstreamPacket> mRemoteBitstreamListener = this::receive;
    private P25FrequencyBandPreloadDataContent mFrequencyBandPreload;

    public P25P1BitstreamDecoder(boolean controlNACGuardEnabled)
    {
        mMessageProcessor = new P25P1MessageProcessor(controlNACGuardEnabled);
        mMessageProcessor.setMessageListener(getMessageListener());
        mMessageFramer.setRequireValidNID(controlNACGuardEnabled);
        mMessageFramer.setListener(mMessageProcessor);
    }

    @Subscribe
    public void process(P25P1NACPreloadDataContent preloadData)
    {
        if(preloadData != null && preloadData.hasData())
        {
            mMessageFramer.setExpectedNAC(preloadData.getNAC());
        }
    }

    @Subscribe
    public void process(P25FrequencyBandPreloadDataContent preloadData)
    {
        if(preloadData != null && preloadData.hasData())
        {
            mFrequencyBandPreload = preloadData;
            mMessageProcessor.preload(preloadData);
        }
    }

    private void receive(P25RemoteBitstreamPacket packet)
    {
        if(!isRunning() || packet == null)
        {
            return;
        }

        if(packet.discontinuity())
        {
            resetFraming();
        }

        mMessageFramer.setTimestamp(packet.captureTimestamp());

        for(byte value: packet.dibits())
        {
            for(int index = 0; index < 4; index++)
            {
                mMessageFramer.processWithHardSyncDetect(Dibit.parse(value, index));
            }
        }
    }

    private void resetFraming()
    {
        mMessageFramer.resetForSourceFrequencyChange();
        mMessageProcessor.resetForSourceFrequencyChange();

        if(mFrequencyBandPreload != null)
        {
            mMessageProcessor.preload(mFrequencyBandPreload);
        }
    }

    @Override
    public void start()
    {
        super.start();
        mMessageFramer.start();
    }

    @Override
    public void stop()
    {
        mMessageFramer.stop();
        super.stop();
    }

    @Override
    public void reset()
    {
        resetFraming();
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    @Override
    public Listener<P25RemoteBitstreamPacket> getRemoteBitstreamListener()
    {
        return mRemoteBitstreamListener;
    }

    @Override
    public void setDecoderStateListener(Listener<DecoderStateEvent> listener)
    {
        mMessageProcessor.setDecoderStateListener(listener);
    }

    @Override
    public void removeDecoderStateListener()
    {
        mMessageProcessor.removeDecoderStateListener();
    }
}
