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
import io.github.dsheirer.dsp.symbol.ISyncDetectListener;
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
    private static final long ACQUISITION_LOG_THRESHOLD_MS = 250;
    protected static final float SYMBOL_TIMING_GAIN = 0.1f;
    private static final Map<Double, float[]> BASEBAND_FILTER_CACHE = new ConcurrentHashMap<>();
    protected InterpolatingSampleBuffer mInterpolatingSampleBuffer;
    protected DQPSKGardnerDemodulator mQPSKDemodulator;
    protected CostasLoop mCostasLoop;
    protected FrequencyCorrectionSyncMonitor mFrequencyCorrectionSyncMonitor;
    protected P25P2MessageFramer mMessageFramer;
    protected IRealFilter mIBasebandFilter;
    protected IRealFilter mQBasebandFilter;
    private final AcquisitionMonitor mAcquisitionMonitor = new AcquisitionMonitor();
    private DecodeConfigP25Phase2 mDecodeConfigP25Phase2;
    private long mAcquisitionStartMillis;
    private long mAcquisitionSampleBufferCount;
    private long mFirstDibitMillis;
    private long mFirstCandidateMillis;
    private boolean mAwaitingFirstSync;
    private boolean mAwaitingFirstDibit;
    private boolean mAwaitingFirstCandidate;
    private String mAcquisitionReason = "startup";
    private int mAcquisitionCandidateCount;
    private boolean mFirstCandidateAccepted;
    private boolean mFirstCandidatePhaseAligned;
    private int mFirstCandidateBitErrors;
    private boolean mFirstCandidateIISCH1Valid;
    private int mFirstCandidateIISCH1BitErrors;
    private boolean mFirstCandidateIISCH2Valid;
    private int mFirstCandidateIISCH2BitErrors;
    private int mFirstCandidateDibitsProcessed;
    private int mFirstCandidateSync1BitErrors;
    private int mFirstCandidateDetectorBitErrors;

    public P25P2DecoderHDQPSK(DecodeConfigP25Phase2 decodeConfigP25Phase2, double initialSampleRate)
    {
        super(6000.0);
        mDecodeConfigP25Phase2 = decodeConfigP25Phase2;
        setSampleRate(initialSampleRate);
    }

    @Override
    public void start()
    {
        super.start();
        beginAcquisition("start");
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
        logAcquisitionOutcome("stop");
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

        mFrequencyCorrectionSyncMonitor = new FrequencyCorrectionSyncMonitor(mCostasLoop, this);
        mMessageFramer.setSyncDetectListener(mAcquisitionMonitor);
        mMessageFramer.setSyncObservationListener(mAcquisitionMonitor);
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
        if(mAwaitingFirstSync)
        {
            mAcquisitionSampleBufferCount++;
        }

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
            logAcquisitionOutcome("sample-rate-change");
            mCostasLoop.reset();
            mFrequencyCorrectionSyncMonitor.reset();
            setSampleRate(sourceEvent.getValue().doubleValue());
            beginAcquisition("sample-rate-change");
        }
        else if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CORRECTION_CHANGE)
        {
            //Retain current PLL bandwidth — this event fires at channel startup (replayed source events)
            //and on live tuner PPM corrections; in both cases the loop can track out the residual offset
            //without widening bandwidth.
            logAcquisitionOutcome("frequency-correction-change");
            mCostasLoop.reset();
            beginAcquisition("frequency-correction-change");
        }
    }

    /**
     * Resets this decoder to prepare for processing a new channel
     */
    @Override
    public void reset()
    {
        logAcquisitionOutcome("reset");
        mCostasLoop.reset();
        mFrequencyCorrectionSyncMonitor.reset();
        beginAcquisition("reset");
    }

    private void beginAcquisition(String reason)
    {
        mAcquisitionReason = reason;
        mAcquisitionStartMillis = System.currentTimeMillis();
        mAcquisitionSampleBufferCount = 0;
        mAcquisitionCandidateCount = 0;
        mFirstDibitMillis = 0;
        mFirstCandidateMillis = 0;
        mAwaitingFirstSync = true;
        mAwaitingFirstDibit = true;
        mAwaitingFirstCandidate = true;
        mFirstCandidateAccepted = false;
        mFirstCandidatePhaseAligned = false;
        mFirstCandidateBitErrors = 0;
        mFirstCandidateIISCH1Valid = false;
        mFirstCandidateIISCH1BitErrors = 0;
        mFirstCandidateIISCH2Valid = false;
        mFirstCandidateIISCH2BitErrors = 0;
        mFirstCandidateDibitsProcessed = 0;
        mFirstCandidateSync1BitErrors = 0;
        mFirstCandidateDetectorBitErrors = 0;
    }

    private void logAcquisitionOutcome(String reason)
    {
        if(mAwaitingFirstSync && mAcquisitionSampleBufferCount > 0)
        {
            long elapsed = System.currentTimeMillis() - mAcquisitionStartMillis;
            mLog.warn("P25P2 acquisition no sync outcome previousReason:{} endReason:{} elapsedMs:{} sampleBuffers:{}",
                mAcquisitionReason, reason, elapsed, mAcquisitionSampleBufferCount);
        }

        mAwaitingFirstSync = false;
        mAwaitingFirstDibit = false;
        mAwaitingFirstCandidate = false;
    }

    private final class AcquisitionMonitor implements ISyncDetectListener, P25P2SyncObservationListener
    {
        @Override
        public void firstDibitReceived()
        {
            if(mAwaitingFirstDibit)
            {
                mFirstDibitMillis = System.currentTimeMillis();
                mAwaitingFirstDibit = false;
            }
        }

        @Override
        public void syncCandidateEvaluated(int totalBitErrors, boolean accepted, boolean iisch1Valid, int iisch1BitErrors,
                                           boolean iisch2Valid, int iisch2BitErrors, int dibitsProcessed,
                                           int sync1BitErrorCount, int syncDetectorBitErrorCount, boolean phaseAligned)
        {
            mAcquisitionCandidateCount++;

            if(mAwaitingFirstCandidate)
            {
                mFirstCandidateMillis = System.currentTimeMillis();
                mAwaitingFirstCandidate = false;
                mFirstCandidateAccepted = accepted;
                mFirstCandidatePhaseAligned = phaseAligned;
                mFirstCandidateBitErrors = totalBitErrors;
                mFirstCandidateIISCH1Valid = iisch1Valid;
                mFirstCandidateIISCH1BitErrors = iisch1BitErrors;
                mFirstCandidateIISCH2Valid = iisch2Valid;
                mFirstCandidateIISCH2BitErrors = iisch2BitErrors;
                mFirstCandidateDibitsProcessed = dibitsProcessed;
                mFirstCandidateSync1BitErrors = sync1BitErrorCount;
                mFirstCandidateDetectorBitErrors = syncDetectorBitErrorCount;
            }
        }

        @Override
        public void syncDetected(int bitErrors)
        {
            mFrequencyCorrectionSyncMonitor.syncDetected(bitErrors);
        }

        @Override
        public void syncLost(int bitsProcessed)
        {
            mFrequencyCorrectionSyncMonitor.syncLost(bitsProcessed);
        }

        @Override
        public void syncAcquired(int bitErrors)
        {
            if(mAwaitingFirstSync)
            {
                long now = System.currentTimeMillis();
                long elapsed = now - mAcquisitionStartMillis;
                long elapsedFromFirstDibit = mFirstDibitMillis > 0 ? now - mFirstDibitMillis : -1;
                long elapsedFromFirstCandidate = mFirstCandidateMillis > 0 ? now - mFirstCandidateMillis : -1;
                boolean logAcquisition = elapsed >= ACQUISITION_LOG_THRESHOLD_MS ||
                    mAcquisitionCandidateCount > 1 || mFirstCandidatePhaseAligned;

                if(logAcquisition)
                {
                    mLog.info("P25P2 acquisition reason:{} elapsedMs:{} sampleBuffers:{} bitErrors:{} bandwidth:{} " +
                            "elapsedFromFirstDibitMs:{} elapsedFromFirstCandidateMs:{} candidateCount:{} firstAcceptedCandidate:{} " +
                            "firstCandidate[accepted:{},bitErrors:{},iisch1Valid:{},iisch1BitErrors:{},iisch2Valid:{},iisch2BitErrors:{},dibitsProcessed:{},sync1BitErrors:{},detectorBitErrors:{},phaseAligned:{}]",
                        mAcquisitionReason, elapsed, mAcquisitionSampleBufferCount, bitErrors,
                        mFrequencyCorrectionSyncMonitor.getCurrentBandwidth(), elapsedFromFirstDibit, elapsedFromFirstCandidate,
                        mAcquisitionCandidateCount, mAcquisitionCandidateCount == 1, mFirstCandidateAccepted,
                        mFirstCandidateBitErrors, mFirstCandidateIISCH1Valid, mFirstCandidateIISCH1BitErrors,
                        mFirstCandidateIISCH2Valid, mFirstCandidateIISCH2BitErrors, mFirstCandidateDibitsProcessed,
                        mFirstCandidateSync1BitErrors, mFirstCandidateDetectorBitErrors, mFirstCandidatePhaseAligned);
                }

                mAwaitingFirstSync = false;
            }
        }
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
