/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
package io.github.dsheirer.module.decode.p25.phase2;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.state.MultiChannelState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.design.FilterDesignException;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.psk.DQPSKGardnerDemodulator;
import io.github.dsheirer.dsp.psk.InterpolatingSampleBuffer;
import io.github.dsheirer.dsp.psk.pll.CostasLoop;
import io.github.dsheirer.dsp.psk.pll.FrequencyCorrectionSyncMonitor;
import io.github.dsheirer.dsp.psk.pll.PLLBandwidth;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierUpdateListener;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.audio.P25P2AudioModule;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.module.decode.p25.phase2.message.P25P2Message;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.wave.ComplexWaveSource;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P25 Phase 2 HDQPSK 2-timeslot Decoder
 */
public class P25P2DecoderHDQPSK extends P25P2Decoder implements IdentifierUpdateListener
{
    private static final Logger mLog = LoggerFactory.getLogger(P25P2DecoderHDQPSK.class);
    protected static final float SYMBOL_TIMING_GAIN = 0.1f;
    private static final Map<Double, float[]> BASEBAND_FILTER_CACHE = new ConcurrentHashMap<>();
    protected InterpolatingSampleBuffer mInterpolatingSampleBuffer;
    protected DQPSKGardnerDemodulator mQPSKDemodulator;
    protected CostasLoop mCostasLoop;
    protected P25P2MessageFramer mMessageFramer;
    protected IRealFilter mIBasebandFilter;
    protected IRealFilter mQBasebandFilter;
    private DecodeConfigP25Phase2 mDecodeConfigP25Phase2;

    public P25P2DecoderHDQPSK(DecodeConfigP25Phase2 decodeConfigP25Phase2)
    {
        super(6000.0);
        mDecodeConfigP25Phase2 = decodeConfigP25Phase2;
        setSampleRate(DecodeConfigP25Phase2.CHANNEL_SAMPLE_RATE);
    }

    @Override
    public void start()
    {
        super.start();
        mQPSKDemodulator.start();

        //Refresh the scramble parameters each time we start in case they change
        if(mDecodeConfigP25Phase2 != null && mDecodeConfigP25Phase2.getScrambleParameters() != null &&
                !mDecodeConfigP25Phase2.isAutoDetectScrambleParameters())
        {
            mMessageFramer.setScrambleParameters(mDecodeConfigP25Phase2.getScrambleParameters());
        }
    }

    @Override
    public void stop()
    {
        super.stop();
        mQPSKDemodulator.stop();
    }

    public void setSampleRate(double sampleRate)
    {
        if(Double.compare(sampleRate, getSampleRate()) == 0 && mQPSKDemodulator != null)
        {
            return;
        }

        super.setSampleRate(sampleRate);

        float[] filterTaps = getBasebandFilter(sampleRate);
        mIBasebandFilter = FilterFactory.getRealFilter(filterTaps);
        mQBasebandFilter = FilterFactory.getRealFilter(filterTaps);
        mCostasLoop = new CostasLoop(getSampleRate(), getSymbolRate());
        mCostasLoop.setPLLBandwidth(PLLBandwidth.BW_300);

        mInterpolatingSampleBuffer = new InterpolatingSampleBuffer(getSamplesPerSymbol(), SYMBOL_TIMING_GAIN);
        mQPSKDemodulator = new DQPSKGardnerDemodulator(mCostasLoop, mInterpolatingSampleBuffer);

        if(mMessageFramer != null)
        {
            getDibitBroadcaster().removeListener(mMessageFramer);
        }

        //The Costas Loop receives symbol-inversion correction requests when detected.
        //The PLL gain monitor receives sync detect/loss signals from the message framer
        mMessageFramer = new P25P2MessageFramer(mCostasLoop);

        if(mDecodeConfigP25Phase2 !=null)
        {
            mMessageFramer.setScrambleParameters(mDecodeConfigP25Phase2.getScrambleParameters());
        }

        FrequencyCorrectionSyncMonitor frequencyCorrectionSyncMonitor =
                new FrequencyCorrectionSyncMonitor(mCostasLoop, this);
        mMessageFramer.setSyncDetectListener(frequencyCorrectionSyncMonitor);
        mMessageFramer.setListener(getMessageProcessor());
        mMessageFramer.setSampleRate(sampleRate);

        mQPSKDemodulator.setSymbolListener(getDibitBroadcaster());
        getDibitBroadcaster().addListener(mMessageFramer);
    }

    /**
     * Primary method for processing incoming complex sample buffers
     * @param samples containing channelized complex samples
     */
    @Override
    public void receive(ComplexSamples samples)
    {
        float[] i = mIBasebandFilter.filter(samples.i());
        float[] q = mQBasebandFilter.filter(samples.q());

        //Process the buffer for power measurements
        mPowerMonitor.process(i, q);

        mQPSKDemodulator.receive(new ComplexSamples(i, q, samples.timestamp()));
    }

    /**
     * Returns baseband lowpass filter taps for the given sample rate, designing and caching them on first use.
     * The filter has a 6.5 kHz passband and 7.2 kHz stopband, matching the 6 kbaud P25 Phase 2 symbol rate.
     */
    private static float[] getBasebandFilter(double sampleRate)
    {
        return BASEBAND_FILTER_CACHE.computeIfAbsent(sampleRate, rate ->
        {
            FIRFilterSpecification specification = FIRFilterSpecification.lowPassBuilder()
                .sampleRate(rate)
                .passBandCutoff(DecodeConfigP25Phase2.CHANNEL_PASS_FREQUENCY)
                .passBandAmplitude(1.0)
                .passBandRipple(0.005)
                .stopBandAmplitude(0.0)
                .stopBandStart(DecodeConfigP25Phase2.CHANNEL_STOP_FREQUENCY)
                .stopBandRipple(0.01)
                .build();

            try
            {
                float[] taps = FilterFactory.getTaps(specification);
                mLog.debug("P25P2 baseband filter designed: sampleRate:{} tapCount:{}", rate, taps.length);
                return taps;
            }
            catch(FilterDesignException fde)
            {
                throw new IllegalStateException("Couldn't design baseband filter for P25 Phase 2 decoder at rate " + rate, fde);
            }
        });
    }

    @Override
    protected void process(SourceEvent sourceEvent)
    {
        if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
        {
            mCostasLoop.reset();
            setSampleRate(sourceEvent.getValue().doubleValue());
        }
        else if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CORRECTION_CHANGE)
        {
            //Reset the PLL if/when the tuner PPM changes so that we can re-lock
            mCostasLoop.reset();
        }
    }

    /**
     * Resets this decoder to prepare for processing a new channel
     */
    @Override
    public void reset()
    {
        mCostasLoop.reset();
    }

    @Override
    public Listener<IdentifierUpdateNotification> getIdentifierUpdateListener()
    {
        return new Listener<IdentifierUpdateNotification>()
        {
            @Override
            public void receive(IdentifierUpdateNotification identifierUpdateNotification)
            {
                if(identifierUpdateNotification.getIdentifier().getForm() == Form.SCRAMBLE_PARAMETERS && mMessageFramer != null)
                {
                    ScrambleParameters scrambleParameters = (ScrambleParameters)identifierUpdateNotification.getIdentifier().getValue();
                    mMessageFramer.setScrambleParameters(scrambleParameters);
                }
            }
        };
    }

    /**
     * Accepts late Phase 2 scramble-parameter updates for already-running traffic channels. The control channel may
     * learn WACN/system/NAC after this decoder has started, so we update the framer in-place rather than waiting for
     * this traffic channel to rediscover the parameters from its own Phase 2 signaling.
     */
    @Subscribe
    public void process(P25P2ScrambleParametersPreloadData preloadData)
    {
        if(preloadData != null && preloadData.hasData() && mMessageFramer != null)
        {
            mMessageFramer.setScrambleParameters(preloadData.getData());
        }
    }
}
