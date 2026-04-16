/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.real.RealFIRFilter;
import io.github.dsheirer.dsp.squelch.PowerMonitor;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.buffer.IByteBufferProvider;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APCO25 Phase 1 Linear Simulcast Modulation (LSM) decoder.  Decimates incoming sample buffers to as close as possible
 * to 25 kHz for ~5 samples per symbol.  Employs baseband and pulse shaping filters.  Incorporates an demodulator to
 * process complex baseband (I/Q) sample stream into soft symbols with soft sync detection and message framing. A
 * registered message listener receives the detected and framed messages.
 *
 * As a child of the FeedbackDecoder, this decoder provides periodic PLL measurements to the tuner for automatic PPM
 * correction.  It also provides a stream of demodulated soft symbols (in radians) for display to the user.
 */
public class P25P1DecoderLSM extends FeedbackDecoder implements IByteBufferProvider, IComplexSamplesListener, ISourceEventListener,
    Listener<ComplexSamples>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(P25P1DecoderLSM.class);
    private static final Map<Double,float[]> BASEBAND_FILTERS = new HashMap<>();
    private static final int SYMBOL_RATE = 4800;

    private final P25P1DemodulatorLSM mDemodulator;
    private final P25P1MessageFramer mMessageFramer = new P25P1MessageFramer();
    private final P25P1MessageProcessor mMessageProcessor = new P25P1MessageProcessor();
    private final PowerMonitor mPowerMonitor = new PowerMonitor();
    private IRealDecimationFilter mDecimationFilterI;
    private IRealDecimationFilter mDecimationFilterQ;
    private IRealFilter mBasebandFilterI;
    private IRealFilter mBasebandFilterQ;
    private IRealFilter mPulseShapingFilterI;
    private IRealFilter mPulseShapingFilterQ;

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    public P25P1DecoderLSM(double initialSampleRate)
    {
        mMessageProcessor.setMessageListener(getMessageListener());
        mDemodulator = new P25P1DemodulatorLSM(mMessageFramer, this);
        setSampleRate(initialSampleRate);
    }

    @Override
    public String getProtocolDescription()
    {
        return "P25 Phase 1 LSM";
    }

    /**
     * Sets the sample rate and configures internal components.
     * @param sampleRate of the channel to decode
     */
    public void setSampleRate(double sampleRate)
    {
        if(sampleRate <= SYMBOL_RATE * 2)
        {
            throw new IllegalArgumentException("Sample rate [" + sampleRate + "] must be >9600 (2 * " +
                    SYMBOL_RATE + " symbol rate)");
        }

        mPowerMonitor.setSampleRate((int)sampleRate);

        int decimation = 1;

        //Identify decimation that gets as close to 4.0 Samples Per Symbol as possible (4800 x 4.0 = 19.2 kHz)
        while((sampleRate / decimation) >= 38400)
        {
            decimation *= 2;
        }

        try
        {
            mDecimationFilterI = DecimationFilterFactory.getRealDecimationFilter(decimation);
            mDecimationFilterQ = DecimationFilterFactory.getRealDecimationFilter(decimation);
        }
        catch(Exception _)
        {
            LOGGER.error("Error getting decimation filter for sample rate [{}] decimation [{}]", sampleRate, decimation);
        }

        float decimatedSampleRate = (float)sampleRate / decimation;
        int symbolLength = 16;
        float rolloff = 0.2f;

        float[] taps = FilterFactory.getRootRaisedCosine(decimatedSampleRate / 4800.0, symbolLength, rolloff);
        mPulseShapingFilterI = new RealFIRFilter(taps);
        mPulseShapingFilterQ = new RealFIRFilter(taps);
        mBasebandFilterI = FilterFactory.getRealFilter(getBasebandFilter(decimatedSampleRate));
        mBasebandFilterQ = FilterFactory.getRealFilter(getBasebandFilter(decimatedSampleRate));
        mDemodulator.setSamplesPerSymbol(decimatedSampleRate / SYMBOL_RATE);
        mMessageFramer.setListener(mMessageProcessor);
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

        float[] i = samples.i();
        float[] q = samples.q();

        i = mDecimationFilterI.decimateReal(i);
        q = mDecimationFilterQ.decimateReal(q);

        //Process buffer for power measurements
        mPowerMonitor.process(i, q);

        i = mBasebandFilterI.filter(i);
        q = mBasebandFilterQ.filter(q);

        i = mPulseShapingFilterI.filter(i);
        q = mPulseShapingFilterQ.filter(q);

        //Demodulate samples into symbols with timing, sync detection, and message framing.
        mDemodulator.process(i, q);
    }

    /**
     * Constructs a baseband filter for this decoder using the current sample rate
     */
    private float[] getBasebandFilter(double sampleRate)
    {
        if(BASEBAND_FILTERS.containsKey(sampleRate))
        {
            return BASEBAND_FILTERS.get(sampleRate);
        }

        FIRFilterSpecification specification = FIRFilterSpecification
                .lowPassBuilder()
                .sampleRate(sampleRate)
                .passBandCutoff(7250)
                .passBandAmplitude(1.0).passBandRipple(0.01) //.01
                .stopBandAmplitude(0.0).stopBandStart(8000) //6500
                .stopBandRipple(0.01).build();

        float[] coefficients = null;

        try
        {
            coefficients = FilterFactory.getTaps(specification);
            BASEBAND_FILTERS.put(sampleRate, coefficients);
        }
        catch(Exception e) //FilterDesignException
        {
            LOGGER.error("Error creating baseband filter for sample rate {}", sampleRate, e);
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
        mDemodulator.setBufferListener(listener);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public void removeBufferListener(Listener<ByteBuffer> listener)
    {
        mDemodulator.setBufferListener(null);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public boolean hasBufferListeners()
    {
        return mDemodulator.hasBufferListener();
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return this::process;
    }

    /**
     * Sets the source event listener to receive source events from this decoder.
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
        mPowerMonitor.setSourceEventListener(null);
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
        super.stop();
        mMessageFramer.stop();
    }

    /**
     * Process source events
     */
    private void process(SourceEvent sourceEvent)
    {
        switch(sourceEvent.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE, NOTIFICATION_FREQUENCY_CORRECTION_CHANGE:
                mDemodulator.resetPLL();
                break;
            case NOTIFICATION_SAMPLE_RATE_CHANGE:
                setSampleRate(sourceEvent.getValue().doubleValue());
                break;
            default:
                break;
        }
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }
}
