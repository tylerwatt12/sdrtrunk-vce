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

package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.real.RealFIRFilter;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFactory;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloat;
import io.github.dsheirer.dsp.squelch.PowerMonitor;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.carrier.CarrierOffsetProcessor;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.dmr.audio.DMRAudioModule;
import io.github.dsheirer.module.decode.dmr.message.DMRBurst;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessageWithLinkControl;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.FullLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ShortLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.buffer.IByteBufferProvider;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.wave.ComplexWaveSource;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DMR DQPSK decoder.
 *
 * Processes the sample stream using an optimal (scalar or vector) DQPSK demodulator.  The demodulated sample stream
 * is processed by the DMRSoftSymbolProcessor at each symbol time interval using a primary and two lagging soft sync
 * detectors.  The sync detectors use correlation of the demodulated soft symbols with all known sync patterns to
 * detect the sync and align the symbol timing and symbol period to the optimal location and symbol period as
 * calculated across each detected burst.  Once the symbol processor has fingerprinted the channel type, it optimizes
 * sync detection for one of three detected modes (BASE, MOBILE or DIRECT) to reduce sync correlation workload.
 *
 * The DMRSoftSymbolMessageFramer receives sync detections from the soft symbol processor and buffers symbols from the
 * start of the burst through the sync so that the symbols can be resampled when the sync is detected, ensuring that
 * the burst symbols are accurate using the symbol timing and period values calculated on the sync detection.
 *
 * For Direct Mode where one timeslot is empty, the symbol message framer uses two message burst buffers that alternate
 * for each burst so that one buffer tracks the active timeslot and the other buffer tracks the empty timeslot to
 * ensure accurate burst detection and tracking.
 *
 * The DMRMessageProcessor processes messages from the message framer to extract and reassemble link control and
 * embedded link control parameters.
 */
public class DMRDecoder extends FeedbackDecoder implements IByteBufferProvider, IComplexSamplesListener,
                ISourceEventListener, Listener<ComplexSamples>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DMRDecoder.class);
    private static final int SYMBOL_RATE = 4800;
    private static final Map<Double,float[]> BASEBAND_FILTERS = new HashMap<>();
    private DifferentialDemodulatorFloat mDemodulator;
    private final DMRMessageFramer mMessageFramer;
    private final DMRSoftSymbolProcessor mSymbolProcessor;
    private final DMRMessageProcessor mMessageProcessor;
    private IRealFilter mIBasebandFilter;
    private IRealFilter mQBasebandFilter;
    private IRealDecimationFilter mDecimationFilterI;
    private IRealDecimationFilter mDecimationFilterQ;
    private RealFIRFilter mRRCFilterI;
    private RealFIRFilter mRRCFilterQ;
    private final PowerMonitor mPowerMonitor = new PowerMonitor();
    private final CarrierOffsetProcessor mCarrierOffsetProcessor = new CarrierOffsetProcessor();

    /**
     * Constructs an instance
     * @param config for the DMR decoder
     */
    public DMRDecoder(DecodeConfigDMR config, boolean isTrafficChannel)
    {
        DMRCrcMaskManager crcMaskManager = new DMRCrcMaskManager(config.getIgnoreCRCChecksums());
        mMessageFramer = new DMRMessageFramer(crcMaskManager);
        mSymbolProcessor = new DMRSoftSymbolProcessor(mMessageFramer, this);
        mMessageProcessor = new DMRMessageProcessor(config, crcMaskManager);
        mMessageProcessor.setMessageListener(getMessageListener());
        setSampleRate(25000.0);

        if(isTrafficChannel)
        {
            mSymbolProcessor.setBaseStationMode(true);
        }
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.DMR;
    }

    /**
     * Sets the sample rate and configures internal decoder components.
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
        mCarrierOffsetProcessor.setSampleRate(sampleRate);

        mIBasebandFilter = FilterFactory.getRealFilter(getBasebandFilter(sampleRate));
        mQBasebandFilter = FilterFactory.getRealFilter(getBasebandFilter(sampleRate));

        int decimation = 1;

        //Identify decimation that gets us as close to 4.0 Samples Per Symbol as possible (19.2 kHz)
        while((sampleRate / decimation) >= 38400)
        {
            decimation *= 2;
        }

        mDecimationFilterI = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mDecimationFilterQ = DecimationFilterFactory.getRealDecimationFilter(decimation);

        float decimatedSampleRate = (float)sampleRate / decimation;
        float rrcAlpha = Math.abs((float)(5760.0 / decimatedSampleRate));
        int symbolLength = (int)Math.floor((-44 * rrcAlpha) + 33);
        symbolLength += symbolLength % 2; //Make the symbol length even

        if(symbolLength < 0)
        {
            symbolLength = 2;
        }

        float[] taps = FilterFactory.getRootRaisedCosine(decimatedSampleRate / SYMBOL_RATE, symbolLength, rrcAlpha);
        mRRCFilterI = new RealFIRFilter(taps);
        mRRCFilterQ = new RealFIRFilter(taps);

        mDemodulator = DifferentialDemodulatorFactory.getFloatDemodulator(decimatedSampleRate, SYMBOL_RATE);
        mSymbolProcessor.setSamplesPerSymbol(mDemodulator.getSamplesPerSymbol());
        mMessageFramer.setListener(mMessageProcessor);
        mMessageProcessor.setMessageListener(getMessageListener());
    }

    /**
     * Primary method for processing incoming complex sample buffers
     * @param samples containing channelized complex samples
     */
    @Override
    public void receive(ComplexSamples samples)
    {

        mMessageFramer.setTimestamp(samples.timestamp());

        float[] i = mIBasebandFilter.filter(samples.i());
        float[] q = mQBasebandFilter.filter(samples.q());

        //Process buffer for power measurements
        mPowerMonitor.process(i, q);

        i = mDecimationFilterI.decimateReal(i);
        q = mDecimationFilterQ.decimateReal(q);

        i = mRRCFilterI.filter(i);
        q = mRRCFilterQ.filter(q);

        float[] demodulated = mDemodulator.demodulate(i, q);
        mSymbolProcessor.receive(demodulated);

        //Estimate carrier offset and broadcast at each update. This value is used in the channel spectral display,
        // and it's also processed by the tuner's PPM error monitor to auto-adjust the tuner PPM value.
        if(mCarrierOffsetProcessor.process(samples))
        {
            //Tuner PPM Monitor - negate the value to indicate channel error from tuner's PPM that's causing the offset
            mPowerMonitor.broadcast(SourceEvent.frequencyErrorMeasurement(-mCarrierOffsetProcessor.getEstimatedOffset()));

            //Channel spectral display - when there's a carrier send the estimate, otherwise send a zero to cause the
            //display to blank the carrier offset measurement indicator line
            if(mCarrierOffsetProcessor.hasCarrier())
            {
                mPowerMonitor.broadcast(SourceEvent.carrierOffsetMeasurement(mCarrierOffsetProcessor.getEstimatedOffset()));
            }
            else
            {
                mPowerMonitor.broadcast(SourceEvent.carrierOffsetMeasurement(0));
            }
        }
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
                .passBandCutoff(5100)
                .passBandAmplitude(1.0).passBandRipple(0.01) //.01
                .stopBandAmplitude(0.0).stopBandStart(6500) //6500
                .stopBandRipple(0.01).build();

        float[] coefficients = null;

        try
        {
            coefficients = FilterFactory.getTaps(specification);
            BASEBAND_FILTERS.put(sampleRate, coefficients);
        }
        catch(Exception e) //FilterDesignException
        {
            LOGGER.error("Error designing low pass filter for sample rate [{}]", sampleRate, e);
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

    @Override
    public String getProtocolDescription()
    {
        return "DMR C4FM";
    }

    /**
     * Sets the source event listener to receive source events from this decoder.
     */
    @Override
    public void setSourceEventListener(Listener<SourceEvent> listener)
    {
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
        if(sourceEvent != null)
        {
            switch(sourceEvent.getEvent())
            {
                case NOTIFICATION_SAMPLE_RATE_CHANGE:
                    setSampleRate(sourceEvent.getValue().doubleValue());
                    break;
                case NOTIFICATION_FREQUENCY_CORRECTION_CHANGE:
                    mCarrierOffsetProcessor.reset();
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }
}
