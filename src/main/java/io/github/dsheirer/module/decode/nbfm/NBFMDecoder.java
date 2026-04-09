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
package io.github.dsheirer.module.decode.nbfm;

import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventProvider;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.design.FilterDesignException;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.resample.RealResampler;
import io.github.dsheirer.dsp.fm.FmDemodulatorFactory;
import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.dsp.squelch.INoiseSquelchController;
import io.github.dsheirer.dsp.squelch.NoiseSquelch;
import io.github.dsheirer.dsp.squelch.NoiseSquelchState;
import io.github.dsheirer.dsp.squelch.SquelchTailRemover;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.SquelchControlDecoder;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.sample.real.IRealBufferProvider;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NBFM decoder with integrated noise squelch.  Demodulates complex sample buffers and feeds unfiltered, demodulated
 * audio to Noise Squelch.  Squelch operates on the noise level with open and close thresholds to pass low-noise audio
 * and block high-noise audio.  Audio is filtered and resampled to 8 kHz for downstream consumers.
 */
public class NBFMDecoder extends SquelchControlDecoder implements ISourceEventListener, IComplexSamplesListener,
        Listener<ComplexSamples>, IRealBufferProvider, IDecoderStateEventProvider, INoiseSquelchController
{
    private static final Logger mLog = LoggerFactory.getLogger(NBFMDecoder.class);
    private static final double DEMODULATED_AUDIO_SAMPLE_RATE = 8000.0;
    private final IDemodulator mDemodulator = FmDemodulatorFactory.getFmDemodulator();
    private final SourceEventProcessor mSourceEventProcessor = new SourceEventProcessor();
    private final NoiseSquelch mNoiseSquelch;
    private IRealFilter mIBasebandFilter;
    private IRealFilter mQBasebandFilter;
    private IRealDecimationFilter mIDecimationFilter;
    private IRealDecimationFilter mQDecimationFilter;
    private Listener<float[]> mResampledBufferListener;
    private Listener<DecoderStateEvent> mDecoderStateEventListener;
    private RealResampler mResampler;
    private final double mChannelBandwidth;
    private final DecodeConfigNBFM.DeemphasisMode mDeemphasisMode;
    private final boolean mLowPassEnabled;
    private final int mLowPassCutoff;
    private final boolean mVoiceEnhanceEnabled;
    private final float mVoiceEnhanceAmount;
    private float mDeemphasisAlpha;
    private float mPreviousDeemphasis;
    private final boolean mSquelchTailRemovalEnabled;
    private SquelchTailRemover mSquelchTailRemover;
    private IRealFilter mAudioLowPassFilter;
    private float[] mAudioLowPassCoefficients;
    private float mVoiceEnhanceX1;
    private float mVoiceEnhanceX2;
    private float mVoiceEnhanceY1;
    private float mVoiceEnhanceY2;
    private float mVoiceEnhanceB0;
    private float mVoiceEnhanceB1;
    private float mVoiceEnhanceB2;
    private float mVoiceEnhanceA1;
    private float mVoiceEnhanceA2;

    /**
     * Constructs an instance
     *
     * @param config to setup the NBFM decoder and noise squelch control.
     */
    public NBFMDecoder(DecodeConfigNBFM config)
    {
        super(config);

        //Save channel bandwidth to setup channel baseband filter.
        mChannelBandwidth = config.getBandwidth().getValue();
        mDeemphasisMode = config.getDeemphasis();
        mLowPassEnabled = config.isLowPassEnabled();
        mLowPassCutoff = config.getLowPassCutoff();
        mVoiceEnhanceEnabled = config.isVoiceEnhanceEnabled();
        mVoiceEnhanceAmount = config.getVoiceEnhanceAmount();
        mSquelchTailRemovalEnabled = config.isSquelchTailRemovalEnabled();
        mNoiseSquelch = new NoiseSquelch(config.getSquelchNoiseOpenThreshold(), config.getSquelchNoiseCloseThreshold(),
                config.getSquelchHysteresisOpenThreshold(), config.getSquelchHysteresisCloseThreshold());

        if(mSquelchTailRemovalEnabled)
        {
            mSquelchTailRemover = new SquelchTailRemover(config.getSquelchTailRemovalMs(), config.getSquelchHeadRemovalMs());
        }

        //Send squelch controlled audio to the resampler and notify the decoder state that the call continues.
        mNoiseSquelch.setAudioListener(audio -> {
            // if squelch is closing (it hasn't propagated yet to mute the audio)
            //  call the resampler with lastBatch set to true. This will zero pad the input buffer and ensure
            //  the output buffer gets emptied.
            if(mNoiseSquelch.isSquelched())
            {
                float[] processedAudio = applyDeemphasis(audio);

                if(mSquelchTailRemovalEnabled)
                {
                    mSquelchTailRemover.process(processedAudio);
                }
                else
                {
                    mResampler.resample(processedAudio, true);
                }
            }
            else
            {
                float[] processedAudio = applyDeemphasis(audio);

                if(mSquelchTailRemovalEnabled)
                {
                    mSquelchTailRemover.process(processedAudio);
                }
                else
                {
                    mResampler.resample(processedAudio);     // this method will set lastBatch to false
                }

                notifyCallContinuation();
            }
        });

        //Notify the decoder state of call starts and ends
        mNoiseSquelch.setSquelchStateListener(squelchState -> {
            if(squelchState == SquelchState.SQUELCH)
            {
                if(mSquelchTailRemovalEnabled)
                {
                    mSquelchTailRemover.squelchClose();
                    mResampler.flush();
                }

                notifyCallEnd();
            }
            else
            {
                mPreviousDeemphasis = 0.0f;
                resetAudioLowPassFilter();
                resetVoiceEnhanceFilter();

                if(mSquelchTailRemovalEnabled)
                {
                    mSquelchTailRemover.squelchOpen();
                }

                notifyCallStart();
            }
        });
    }

    /**
     * Decoder type.
     * @return type
     */
    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.NBFM;
    }

    /**
     * Decode configuration for this decoder.
     * @return configuration
     */
    @Override
    public DecodeConfigNBFM getDecodeConfiguration()
    {
        return (DecodeConfigNBFM)super.getDecodeConfiguration();
    }

    /**
     * Register the noise squelch state listener.  This will normally be a GUI noise squelch state view/controller.
     * @param listener to receive states or pass null to de-register a listener.
     */
    @Override
    public void setNoiseSquelchStateListener(Listener<NoiseSquelchState> listener)
    {
        mNoiseSquelch.setNoiseSquelchStateListener(listener);
    }

    /**
     * Applies new open and close noise threshold values for the noise squelch.
     * @param open for the open noise variance calculation in range 0.1 - 0.5 where open <= close value.
     * @param close for the close noise variance calculation. in range 0.1 - 0.5 where close >= open.
     */
    @Override
    public void setNoiseThreshold(float open, float close)
    {
        mNoiseSquelch.setNoiseThreshold(open, close);

        //Update the channel configuration and schedule a playlist save.
        getDecodeConfiguration().setSquelchNoiseOpenThreshold(open);
        getDecodeConfiguration().setSquelchNoiseCloseThreshold(close);
    }

    /**
     * Sets the open and close hysteresis thresholds in units of 10 milliseconds.
     * @param open in range 1-10, recommend: 4 where open <= close
     * @param close in range 1-10, recommend: 6 where close >= open.
     */
    @Override
    public void setHysteresisThreshold(int open, int close)
    {
        mNoiseSquelch.setHysteresisThreshold(open, close);
        getDecodeConfiguration().setSquelchHysteresisOpenThreshold(open);
        getDecodeConfiguration().setSquelchHysteresisCloseThreshold(close);
    }

    /**
     * Sets the squelch override state to temporarily bypass/override squelch control and pass all audio.
     * @param override (true) or (false) to turn off squelch override.
     */
    @Override
    public void setSquelchOverride(boolean override)
    {
        mNoiseSquelch.setSquelchOverride(override);
    }

    /**
     * Implements the ISourceEventListener interface
     */
    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return mSourceEventProcessor;
    }

    /**
     * Module interface methods - unused.
     */
    @Override
    public void reset()
    {
        /* no action required */
    }

    @Override
    public void start()
    {
        /* no action required */
    }

    @Override
    public void stop()
    {
        /* no action required */
    }

    /**
     * Broadcasts the demodulated, resampled to 8 kHz audio samples to the registered listener.
     *
     * @param demodulatedSamples to broadcast
     */
    protected void broadcast(float[] demodulatedSamples)
    {
        if(mLowPassEnabled && mAudioLowPassFilter != null)
        {
            demodulatedSamples = mAudioLowPassFilter.filter(demodulatedSamples);
        }

        if(mVoiceEnhanceEnabled)
        {
            demodulatedSamples = applyVoiceEnhancement(demodulatedSamples);
        }

        if(mResampledBufferListener != null)
        {
            mResampledBufferListener.receive(demodulatedSamples);
        }
    }

    /**
     * Implements the IRealBufferProvider interface to register a listener for demodulated audio samples.
     *
     * @param listener to receive demodulated, resampled audio sample buffers.
     */
    @Override
    public void setBufferListener(Listener<float[]> listener)
    {
        mResampledBufferListener = listener;
    }

    /**
     * Implements the IRealBufferProvider interface to deregister a listener from receiving demodulated audio samples.
     */
    @Override
    public void removeBufferListener()
    {
        mResampledBufferListener = null;
    }

    /**
     * Implements the IComplexSampleListener interface to receive a stream of complex sample buffers.
     */
    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }

    /**
     * Implements the Listener<ComplexSample> interface to receive a stream of complex I/Q sample buffers
     */
    @Override
    public void receive(ComplexSamples samples)
    {
        if(mIDecimationFilter == null || mQDecimationFilter == null)
        {
            throw new IllegalStateException("NBFM demodulator module must receive a sample rate change source event " +
                    "before it can process complex sample buffers");
        }

        float[] decimatedI = mIDecimationFilter.decimateReal(samples.i());
        float[] decimatedQ = mQDecimationFilter.decimateReal(samples.q());

        float[] filteredI = mIBasebandFilter.filter(decimatedI);
        float[] filteredQ = mQBasebandFilter.filter(decimatedQ);

        float[] demodulated = mDemodulator.demodulate(filteredI, filteredQ);

        mNoiseSquelch.process(demodulated);

        //Once we process the sample buffer, if the ending state is squelch closed, update the decoder state that we
        // are idle.
        if(mNoiseSquelch.isSquelched())
        {
            notifyIdle();
        }
    }

    /**
     * Broadcasts a call start state event
     */
    private void notifyCallStart()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.START, State.CALL, 0));
    }

    /**
     * Broadcasts a call continuation state event
     */
    private void notifyCallContinuation()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.CONTINUATION, State.CALL, 0));
    }

    /**
     * Broadcasts a call end state event
     */
    private void notifyCallEnd()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.END, State.CALL, 0));
    }

    /**
     * Broadcasts an idle notification
     */
    private void notifyIdle()
    {
        broadcast(new DecoderStateEvent(this, DecoderStateEvent.Event.CONTINUATION, State.IDLE, 0));
    }

    /**
     * Broadcasts the decoder state event to an optional registered listener
     */
    private void broadcast(DecoderStateEvent event)
    {
        if(mDecoderStateEventListener != null)
        {
            mDecoderStateEventListener.receive(event);
        }
    }

    /**
     * Sets the decoder state listener
     */
    @Override
    public void setDecoderStateListener(Listener<DecoderStateEvent> listener)
    {
        mDecoderStateEventListener = listener;
    }

    /**
     * Removes the decoder state event listener
     */
    @Override
    public void removeDecoderStateListener()
    {
        mDecoderStateEventListener = null;
    }

    /**
     * Updates the decoder to process complex sample buffers at the specified sample rate.
     * @param sampleRate of the incoming complex sample buffer stream.
     */
    private void setSampleRate(double sampleRate)
    {
        int decimationRate = 0;
        double decimatedSampleRate = sampleRate;

        if(sampleRate / 2 >= (mChannelBandwidth * 2))
        {
            decimationRate = 2;

            while(sampleRate / decimationRate / 2 >= (mChannelBandwidth * 2))
            {
                decimationRate *= 2;
            }
        }

        if(decimationRate > 0)
        {
            decimatedSampleRate /= decimationRate;
        }

        mIDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimationRate);
        mQDecimationFilter = DecimationFilterFactory.getRealDecimationFilter(decimationRate);

        if((decimatedSampleRate < (2.0 * mChannelBandwidth)))
        {
            throw new IllegalStateException(getDecoderType().name() + " demodulator with channel bandwidth [" + mChannelBandwidth + "] requires a channel sample rate of [" + (2.0 * mChannelBandwidth + "] - sample rate of [" + decimatedSampleRate + "] is not supported"));
        }

        mNoiseSquelch.setSampleRate(decimatedSampleRate);

        int passBandStop = (int) (mChannelBandwidth * .8);
        int stopBandStart = (int) mChannelBandwidth;

        float[] coefficients = null;

        FIRFilterSpecification specification = FIRFilterSpecification.lowPassBuilder().sampleRate(decimatedSampleRate * 2).gridDensity(16).oddLength(true).passBandCutoff(passBandStop).passBandAmplitude(1.0).passBandRipple(0.01).stopBandStart(stopBandStart).stopBandAmplitude(0.0).stopBandRipple(0.005) //Approximately 90 dB attenuation
                .build();

        try
        {
            coefficients = FilterFactory.getTaps(specification);
        }
        catch(FilterDesignException fde)
        {
            mLog.error("Couldn't design demodulator remez filter for sample rate [" + sampleRate + "] pass frequency [" + passBandStop + "] and stop frequency [" + stopBandStart + "] - will proceed using sinc (low-pass) filter");
        }

        if(coefficients == null)
        {
            mLog.info("Unable to use remez filter designer for sample rate [" + decimatedSampleRate + "] pass band stop [" + passBandStop + "] and stop band start [" + stopBandStart + "] - will proceed using simple low pass filter design");
            coefficients = FilterFactory.getLowPass(decimatedSampleRate, passBandStop, stopBandStart, 60, WindowType.HAMMING, true);
        }

        mIBasebandFilter = FilterFactory.getRealFilter(coefficients);
        mQBasebandFilter = FilterFactory.getRealFilter(coefficients);

        mResampler = new RealResampler(decimatedSampleRate, DEMODULATED_AUDIO_SAMPLE_RATE, 4192, 512);
        mResampler.setListener(NBFMDecoder.this::broadcast);

        if(mSquelchTailRemovalEnabled)
        {
            mSquelchTailRemover.setOutputListener(mResampler::resample);
        }

        configureAudioLowPassFilter();
        configureVoiceEnhanceFilter();
        updateDeemphasisAlpha();
    }

    private void configureAudioLowPassFilter()
    {
        if(!mLowPassEnabled)
        {
            mAudioLowPassCoefficients = null;
            mAudioLowPassFilter = null;
            return;
        }

        mAudioLowPassCoefficients = FilterFactory.getLowPass(DEMODULATED_AUDIO_SAMPLE_RATE, mLowPassCutoff, 41,
                WindowType.HAMMING);
        resetAudioLowPassFilter();
    }

    private void resetAudioLowPassFilter()
    {
        if(mLowPassEnabled && mAudioLowPassCoefficients != null)
        {
            mAudioLowPassFilter = FilterFactory.getRealFilter(mAudioLowPassCoefficients.clone());
        }
    }

    private void configureVoiceEnhanceFilter()
    {
        if(!mVoiceEnhanceEnabled || mVoiceEnhanceAmount < 0.01f)
        {
            return;
        }

        double centerFreq = 2800.0;
        double q = 1.5;
        double dbGain = 6.0 * (mVoiceEnhanceAmount / 100.0f);
        double w0 = 2.0 * Math.PI * centerFreq / DEMODULATED_AUDIO_SAMPLE_RATE;
        double a = Math.pow(10.0, dbGain / 40.0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double cosW0 = Math.cos(w0);

        double b0 = 1.0 + alpha * a;
        double b1 = -2.0 * cosW0;
        double b2 = 1.0 - alpha * a;
        double a0 = 1.0 + alpha / a;
        double a1 = -2.0 * cosW0;
        double a2 = 1.0 - alpha / a;

        mVoiceEnhanceB0 = (float)(b0 / a0);
        mVoiceEnhanceB1 = (float)(b1 / a0);
        mVoiceEnhanceB2 = (float)(b2 / a0);
        mVoiceEnhanceA1 = (float)(a1 / a0);
        mVoiceEnhanceA2 = (float)(a2 / a0);
        resetVoiceEnhanceFilter();
    }

    private void resetVoiceEnhanceFilter()
    {
        mVoiceEnhanceX1 = 0.0f;
        mVoiceEnhanceX2 = 0.0f;
        mVoiceEnhanceY1 = 0.0f;
        mVoiceEnhanceY2 = 0.0f;
    }

    private float[] applyVoiceEnhancement(float[] samples)
    {
        if(mVoiceEnhanceAmount < 0.01f)
        {
            return samples;
        }

        for(int x = 0; x < samples.length; x++)
        {
            float output = mVoiceEnhanceB0 * samples[x] + mVoiceEnhanceB1 * mVoiceEnhanceX1 +
                    mVoiceEnhanceB2 * mVoiceEnhanceX2 - mVoiceEnhanceA1 * mVoiceEnhanceY1 -
                    mVoiceEnhanceA2 * mVoiceEnhanceY2;

            mVoiceEnhanceX2 = mVoiceEnhanceX1;
            mVoiceEnhanceX1 = samples[x];
            mVoiceEnhanceY2 = mVoiceEnhanceY1;
            mVoiceEnhanceY1 = output;
            samples[x] = output;
        }

        return samples;
    }

    private float[] applyDeemphasis(float[] samples)
    {
        if(mDeemphasisMode == DecodeConfigNBFM.DeemphasisMode.NONE)
        {
            return samples;
        }

        for(int x = 0; x < samples.length; x++)
        {
            mPreviousDeemphasis += mDeemphasisAlpha * (samples[x] - mPreviousDeemphasis);
            samples[x] = mPreviousDeemphasis;
        }

        return samples;
    }

    private void updateDeemphasisAlpha()
    {
        if(mDeemphasisMode == DecodeConfigNBFM.DeemphasisMode.NONE)
        {
            mDeemphasisAlpha = 1.0f;
            return;
        }

        double tau = mDeemphasisMode.getMicroseconds() / 1_000_000.0;
        double dt = 1.0 / DEMODULATED_AUDIO_SAMPLE_RATE;
        mDeemphasisAlpha = (float)(dt / (tau + dt));
    }

    /**
     * Monitors sample rate change source event(s) to set up the filters, decimation, and demodulator.
     */
    public class SourceEventProcessor implements Listener<SourceEvent>
    {
        @Override
        public void receive(SourceEvent sourceEvent)
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
            {
                setSampleRate(sourceEvent.getValue().doubleValue());
            }
        }
    }
}
