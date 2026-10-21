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

package io.github.dsheirer.module.decode.nxdn;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.module.decode.traffic.RadioSystemKeyEvent;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.fm.FmDemodulatorFactory;
import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.dsp.squelch.PowerMonitor;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.buffer.IByteBufferProvider;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * NXDN Decoder for 4800, 9600 and Type-C/D systems.
 */
public class NXDNDecoder extends FeedbackDecoder implements IByteBufferProvider, IComplexSamplesListener,
        ISourceEventListener, Listener<ComplexSamples>
{
    private static final Map<String,float[]> BASEBAND_FILTERS = new HashMap<>();
    private final DecodeConfigNXDN mConfig;
    private final NXDNSymbolProcessor mSymbolProcessor;
    private final NXDNMessageFramer mMessageFramer;
    private final NXDNMessageProcessor mMessageProcessor;
    private IRealDecimationFilter mDecimationFilterI;
    private IRealDecimationFilter mDecimationFilterQ;
    private IRealFilter mBasebandFilterI;
    private IRealFilter mBasebandFilterQ;
    private IRealFilter mPulseShapingFilter;
    private IDemodulator mDemodulator;
    private final PowerMonitor mPowerMonitor = new PowerMonitor();
    private String mAliasRadioSystemKey;

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.NXDN;
    }

    /**
     * Constructs an instance
     * @param config for the decoder
     */
    public NXDNDecoder(DecodeConfigNXDN config)
    {
        mConfig = config;
        mMessageProcessor = new NXDNMessageProcessor(config);
        mMessageProcessor.setMessageListener(getMessageListener());
        mMessageFramer = new NXDNMessageFramer(mMessageProcessor, config.getTransmissionMode());
        mSymbolProcessor = new NXDNSymbolProcessor(mMessageFramer, this);
    }

    /**
     * Preloads NXDN channel details passed from the control channel used to resolve channel frequency information
     * in traffic channel broadcast messaging.
     *
     * @param preloadData containing a DMR network configuration monitor.
     */
    @Subscribe
    public void preload(NXDNChannelInfoPreloadData preloadData)
    {
        if(mMessageProcessor != null)
        {
            mMessageProcessor.preload(preloadData);
        }
    }

    @Override
    public String getProtocolDescription()
    {
        return "NXDN " + mConfig.getTransmissionMode();
    }

    /**
     * Sets the sample rate and configures internal decoder components.
     * @param sampleRate of the channel to decode
     */
    public void setSampleRate(double sampleRate)
    {
        float samplesPerSymbol = (float)sampleRate / mConfig.getTransmissionMode().getSymbolRate();

        if(samplesPerSymbol <= 4.0f)
        {
            throw new IllegalArgumentException("Sample rate [" + sampleRate + "] must be at least " + (mConfig.getTransmissionMode().getSymbolRate() * 4) + " Hz");
        }

        int decimation = 1;

        //Identify decimation that gets us as close to 5.0 Samples Per Symbol as possible
        while(samplesPerSymbol >= 10.0)
        {
            decimation *= 2;
            samplesPerSymbol /= 2;
        }

        mDecimationFilterI = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mDecimationFilterQ = DecimationFilterFactory.getRealDecimationFilter(decimation);
        float decimatedSampleRate = (float)sampleRate / decimation;
        mPowerMonitor.setSampleRate((int)decimatedSampleRate);

        //Set the decimated sample rate to use for PLL error reporting.
        setDecimatedSampleRate(decimatedSampleRate);

        int symbolLength = 26;
        float rrcAlpha = 0.2f;
        float[] rrcTaps = FilterFactory.getRRC(samplesPerSymbol, symbolLength, rrcAlpha);
        mPulseShapingFilter = FilterFactory.getPulseShapingFilter(rrcTaps);
        float[] basebandTaps = getBasebandFilter(decimatedSampleRate, mConfig);
        mBasebandFilterI = FilterFactory.getRealFilter(basebandTaps);
        mBasebandFilterQ = FilterFactory.getRealFilter(basebandTaps);
        mDemodulator = FmDemodulatorFactory.getFmDemodulator();
        mSymbolProcessor.setSamplesPerSymbol(samplesPerSymbol);
        mMessageProcessor.setMessageListener(getMessageListener());
    }

    /**
     * Primary method for processing incoming complex sample buffers
     * @param samples containing channelized complex samples
     */
    @Override
    public void receive(ComplexSamples samples)
    {
        //Update the message framer with the timestamp from the incoming sample buffer.
        mMessageFramer.setTimestamp(samples.timestamp());

        float[] i = mDecimationFilterI.decimateReal(samples.i());
        float[] q = mDecimationFilterQ.decimateReal(samples.q());

        i = mBasebandFilterI.filter(i);
        q = mBasebandFilterQ.filter(q);

        //Measure filtered decoder I/Q power for live signal telemetry.
        mPowerMonitor.process(i, q);

        float[] demodulated = mDemodulator.demodulate(i, q);
        demodulated = mPulseShapingFilter.filter(demodulated);

        //Process demodulated samples into symbols and apply message sync detection and framing.
        mSymbolProcessor.process(demodulated);
    }

    /**
     * Constructs a baseband filter for this decoder using the current sample rate
     */
    private float[] getBasebandFilter(double sampleRate, DecodeConfigNXDN config)
    {

        String mapKey = config.getTransmissionMode().name() + sampleRate;

        float[] coefficients = null;

        if(BASEBAND_FILTERS.containsKey(mapKey))
        {
            coefficients = BASEBAND_FILTERS.get(mapKey);
        }
        else
        {
            ChannelSpecification channel = config.getChannelSpecification();

            FIRFilterSpecification specification = FIRFilterSpecification
                    .lowPassBuilder()
                    .sampleRate(sampleRate)
                    .passBandCutoff(channel.getPassFrequency())
                    .passBandAmplitude(1.0).passBandRipple(0.01)
                    .stopBandAmplitude(0.0).stopBandStart(channel.getStopFrequency())
                    .stopBandRipple(0.01).build();

            try
            {
                coefficients = FilterFactory.getTaps(specification);

                if(coefficients != null)
                {
                    BASEBAND_FILTERS.put(mapKey, coefficients);
                }
            }
            catch(Exception fde) //FilterDesignException
            {
                System.out.println("Error");
            }
        }

        if(coefficients == null)
        {
            throw new IllegalStateException("Unable to design low pass filter for sample rate [" + sampleRate + "]");
        }

        return coefficients;
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public void setBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(listener);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public void removeBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(null);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public boolean hasBufferListeners()
    {
        return mSymbolProcessor.hasBufferListener();
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return this::process;
    }

    /**
     * Registers the processing-chain source-event broadcaster for both decoder feedback and power measurements.
     */
    @Override
    public void setSourceEventListener(Listener<SourceEvent> listener)
    {
        super.setSourceEventListener(listener);
        mPowerMonitor.setSourceEventListener(listener);
    }

    @Override
    public void removeSourceEventListener()
    {
        super.removeSourceEventListener();
        mPowerMonitor.setSourceEventListener(null);
    }

    @Override
    public void stop()
    {
        super.stop();
        mSymbolProcessor.dispose();
        mMessageProcessor.reset();
    }

    @Override
    public void reset()
    {
        super.reset();
        mMessageProcessor.reset();
    }

    @Subscribe
    public void receiveAliasRadioSystemKey(RadioSystemKeyEvent event)
    {
        if(event == null || event.isCallActivation()) return;
        String key = event.radioSystemKey();
        if(key != null && RadioSystemKey.isChannelScoped(key)) key = null;
        if(!java.util.Objects.equals(mAliasRadioSystemKey, key))
        {
            mAliasRadioSystemKey = key;
            mMessageProcessor.reset();
        }
    }

    /**
     * Process source events
     */
    private void process(SourceEvent sourceEvent)
    {
        switch(sourceEvent.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE:
                mMessageProcessor.reset();
                mSymbolProcessor.resetBalance();
                break;
            case NOTIFICATION_FREQUENCY_CORRECTION_CHANGE:
                mSymbolProcessor.resetBalance();
                break;
            case NOTIFICATION_SAMPLE_RATE_CHANGE:
                setSampleRate(sourceEvent.getValue().doubleValue());
                break;
        }
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

}
