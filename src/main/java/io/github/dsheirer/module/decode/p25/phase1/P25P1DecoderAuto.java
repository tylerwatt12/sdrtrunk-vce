/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventProvider;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.buffer.IByteBufferProvider;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * P25 Phase 1 automatic waveform decoder. Both fixed decoders are constructed with the processing chain, but only one
 * receives samples at a time. Selection and hysteresis use sample counts and decoded-message validity, so this wrapper
 * adds no timers, locks, queues, decoder construction or observer work to the receiver callback.
 */
public class P25P1DecoderAuto extends FeedbackDecoder implements IByteBufferProvider, IComplexSamplesListener,
    ISourceEventListener, Listener<ComplexSamples>, IDecoderStateEventProvider
{
    private final DecodeConfigP25Phase1 mConfiguration;
    private final P25P1DecoderC4FM mC4FM;
    private final P25P1DecoderLSM mLSM;
    private final P25P1AutoSelector mSelector;
    private final Listener<ComplexSamples> mComplexSamplesListener = this::receive;
    private final Listener<SourceEvent> mSourceEventListener = this::process;
    private final Listener<SourceEvent> mC4FMSourceEventListener;
    private final Listener<SourceEvent> mLSMSourceEventListener;
    private Listener<ByteBuffer> mBufferListener;
    private Listener<DecoderStateEvent> mDecoderStateListener;

    public P25P1DecoderAuto(DecodeConfigP25Phase1 configuration, double initialSampleRate,
                            boolean controlNACGuardEnabled)
    {
        mConfiguration = Objects.requireNonNull(configuration, "P25 Phase 1 configuration cannot be null");
        mC4FM = new P25P1DecoderC4FM(initialSampleRate, controlNACGuardEnabled);
        mLSM = new P25P1DecoderLSM(initialSampleRate, controlNACGuardEnabled);
        mSelector = new P25P1AutoSelector(initialSampleRate, configuration.getEffectiveModulation());
        mC4FMSourceEventListener = mC4FM.getSourceEventListener();
        mLSMSourceEventListener = mLSM.getSourceEventListener();
        configure(mC4FM, Modulation.C4FM);
        configure(mLSM, Modulation.CQPSK);
    }

    private void configure(FeedbackDecoder decoder, Modulation modulation)
    {
        decoder.setMessageListener(message -> receive(modulation, message));
        decoder.setSymbolListener(symbol -> {
            if(isSelected(modulation))
            {
                broadcast(symbol);
            }
        });
        decoder.setSourceEventListener(event -> {
            if(isSelected(modulation))
            {
                broadcast(event);
            }
        });

        if(decoder instanceof IByteBufferProvider provider)
        {
            provider.setBufferListener(buffer -> {
                if(isSelected(modulation) && mBufferListener != null)
                {
                    mBufferListener.receive(buffer);
                }
            });
        }

        if(decoder instanceof IDecoderStateEventProvider provider)
        {
            provider.setDecoderStateListener(event -> {
                if(isSelected(modulation) && mDecoderStateListener != null)
                {
                    mDecoderStateListener.receive(event);
                }
            });
        }
    }

    private boolean isSelected(Modulation modulation)
    {
        return mSelector.isLocked() && mSelector.getActive() == modulation;
    }

    private void receive(Modulation modulation, IMessage message)
    {
        boolean wasLocked = mSelector.isLocked();
        boolean forward = mSelector.receiveMessage(modulation, isSelectionEvidence(message));

        if(!wasLocked && mSelector.isLocked())
        {
            mConfiguration.setEffectiveModulation(mSelector.getActive());
        }

        if(forward && message != null)
        {
            getMessageListener().receive(message);
        }
    }

    /**
     * Only a decoded P25 frame is evidence for the waveform selector.  Sync-loss and dropped-sample notifications are
     * intentionally valid messages for downstream status displays, but must not lock the automatic decoder.
     */
    static boolean isSelectionEvidence(IMessage message)
    {
        return message instanceof P25P1Message && message.isValid();
    }

    @Override
    public void receive(ComplexSamples samples)
    {
        if(mSelector.getActive() == Modulation.CQPSK)
        {
            mLSM.receive(samples);
        }
        else
        {
            mC4FM.receive(samples);
        }

        Modulation next = mSelector.receiveSamples(samples.i().length);

        if(next != null)
        {
            decoder(next).reset();
        }

        if(mSelector.isLocked())
        {
            mConfiguration.setEffectiveModulation(mSelector.getActive());
        }
    }

    @Subscribe
    public void process(P25P1NACPreloadDataContent preloadData)
    {
        mC4FM.process(preloadData);
        mLSM.process(preloadData);
    }

    private void process(SourceEvent event)
    {
        mC4FMSourceEventListener.receive(event);
        mLSMSourceEventListener.receive(event);

        switch(event.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE -> resetSelector();
            case NOTIFICATION_SAMPLE_RATE_CHANGE -> {
                mSelector.setSampleRate(event.getValue().doubleValue());
                resetSelector();
            }
            default -> {
            }
        }
    }

    private void resetSelector()
    {
        mC4FM.reset();
        mLSM.reset();
        mSelector.reset(mConfiguration.getEffectiveModulation());
    }

    private FeedbackDecoder decoder(Modulation modulation)
    {
        return modulation == Modulation.CQPSK ? mLSM : mC4FM;
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    @Override
    public String getProtocolDescription()
    {
        return "P25 Phase 1 Auto (" + mConfiguration.getEffectiveModulation() + ")";
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return mComplexSamplesListener;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventListener;
    }

    @Override
    public void setBufferListener(Listener<ByteBuffer> listener)
    {
        mBufferListener = listener;
    }

    @Override
    public void removeBufferListener(Listener<ByteBuffer> listener)
    {
        if(mBufferListener == listener)
        {
            mBufferListener = null;
        }
    }

    @Override
    public boolean hasBufferListeners()
    {
        return mBufferListener != null;
    }

    @Override
    public void setDecoderStateListener(Listener<DecoderStateEvent> listener)
    {
        mDecoderStateListener = listener;
    }

    @Override
    public void removeDecoderStateListener()
    {
        mDecoderStateListener = null;
    }

    @Override
    public void reset()
    {
        resetSelector();
    }

    @Override
    public void start()
    {
        super.start();
        mC4FM.start();
        mLSM.start();
    }

    @Override
    public void stop()
    {
        mC4FM.stop();
        mLSM.stop();
        super.stop();
    }
}
