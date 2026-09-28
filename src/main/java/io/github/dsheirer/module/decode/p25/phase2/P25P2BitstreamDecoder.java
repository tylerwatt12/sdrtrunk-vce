/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.PrimaryDecoder;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandPreloadDataContent;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.remote.IP25RemoteBitstreamListener;
import io.github.dsheirer.remote.P25RemoteBitstreamPacket;
import io.github.dsheirer.sample.Listener;
import java.nio.ByteBuffer;

/** P25 Phase 2 message decoder for already-demodulated packed dibits received from a remote sender. */
public class P25P2BitstreamDecoder extends PrimaryDecoder implements IP25RemoteBitstreamListener
{
    private final P25P2MessageFramer mMessageFramer = new P25P2MessageFramer(null);
    private final P25P2MessageProcessor mMessageProcessor;
    private P25FrequencyBandPreloadDataContent mFrequencyBandPreload;
    private ScrambleParameters mScrambleParameters;

    public P25P2BitstreamDecoder(boolean controlNACGuardEnabled)
    {
        mMessageProcessor = new P25P2MessageProcessor(controlNACGuardEnabled);
        mMessageProcessor.setMessageListener(getMessageListener());
        mMessageProcessor.setScrambleParametersListener(mMessageFramer::setScrambleParameters);
        mMessageFramer.setAutomaticScrambleUpdatesEnabled(!controlNACGuardEnabled);
        mMessageFramer.setListener(mMessageProcessor);
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

    @Subscribe
    public void process(P25P2ScrambleParametersPreloadData preloadData)
    {
        if(preloadData != null && preloadData.hasData())
        {
            mScrambleParameters = preloadData.getData().copy();
            mMessageFramer.setScrambleParameters(mScrambleParameters);
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
        mMessageFramer.receive(ByteBuffer.wrap(packet.dibits()));
    }

    private void resetFraming()
    {
        mMessageFramer.resetForSourceFrequencyChange();
        mMessageProcessor.resetForSourceFrequencyChange();

        if(mFrequencyBandPreload != null)
        {
            mMessageProcessor.preload(mFrequencyBandPreload);
        }

        if(mScrambleParameters != null)
        {
            mMessageFramer.setScrambleParameters(mScrambleParameters);
        }
    }

    @Override
    public void reset()
    {
        resetFraming();
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE2;
    }

    @Override
    public Listener<P25RemoteBitstreamPacket> getRemoteBitstreamListener()
    {
        return this::receive;
    }
}
