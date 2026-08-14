/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.squelch;

import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.sample.Listener;

/**
 * Lock-free carrier squelch for AM.  Each 10-millisecond window measures complex phase coherence across a fixed short
 * lag.  AM changes amplitude but preserves carrier phase, while filtered idle noise decorrelates across the lag.  This
 * scale-independent test acquires an unmodulated or deeply modulated carrier without first observing an idle channel.
 */
public final class CarrierSquelch implements IAnalogSquelch
{
    private static final float MINIMUM_POWER = 1.0e-20f;
    private static final double PHASE_LAG_MILLISECONDS = 0.2;
    private static final int WINDOW_MILLISECONDS = 10;
    private volatile float mOpenThreshold = NoiseSquelch.DEFAULT_NOISE_OPEN_THRESHOLD;
    private volatile float mCloseThreshold = NoiseSquelch.DEFAULT_NOISE_CLOSE_THRESHOLD;
    private volatile int mOpenHysteresis = NoiseSquelch.DEFAULT_HYSTERESIS_OPEN_THRESHOLD;
    private volatile int mCloseHysteresis = NoiseSquelch.DEFAULT_HYSTERESIS_CLOSE_THRESHOLD;
    private volatile boolean mOverride;
    private volatile boolean mSquelch = true;
    private float[] mDelayedI = new float[0];
    private float[] mDelayedQ = new float[0];
    private int mDelayPointer;
    private int mDelayCount;
    private float mCoherenceI;
    private float mCoherenceQ;
    private int mCoherenceCount;
    private int mWindowSampleCount;
    private int mWindowSize;
    private volatile int mHysteresisCount;
    private int mStateBroadcastCount;
    private float mNoise = NoiseSquelch.MAXIMUM_NOISE_THRESHOLD;
    private Listener<float[]> mAudioListener;
    private Listener<SquelchState> mSquelchStateListener;
    private volatile Listener<NoiseSquelchState> mNoiseStateListener;

    public CarrierSquelch(float openThreshold, float closeThreshold, int openHysteresis, int closeHysteresis)
    {
        setNoiseThreshold(openThreshold, closeThreshold);
        setHysteresisThreshold(openHysteresis, closeHysteresis);
    }

    @Override
    public boolean isSquelched()
    {
        return !mOverride && mSquelch;
    }

    @Override
    public void setSampleRate(double sampleRate)
    {
        mWindowSize = Math.max(1, (int)(sampleRate * WINDOW_MILLISECONDS / 1000.0));
        int delaySize = Math.max(1, (int)Math.round(sampleRate * PHASE_LAG_MILLISECONDS / 1000.0));
        mDelayedI = new float[delaySize];
        mDelayedQ = new float[delaySize];
        mDelayPointer = 0;
        mDelayCount = 0;
        mCoherenceI = 0.0f;
        mCoherenceQ = 0.0f;
        mCoherenceCount = 0;
        mWindowSampleCount = 0;
    }

    @Override
    public void setNoiseThreshold(float open, float close)
    {
        if(open < NoiseSquelch.MINIMUM_NOISE_THRESHOLD || close > NoiseSquelch.MAXIMUM_NOISE_THRESHOLD || open > close)
        {
            throw new IllegalArgumentException("Carrier thresholds open/close [" + open + "/" + close +
                "] must be ordered and in range " + NoiseSquelch.MINIMUM_NOISE_THRESHOLD + "-" +
                NoiseSquelch.MAXIMUM_NOISE_THRESHOLD);
        }

        mOpenThreshold = open;
        mCloseThreshold = close;
    }

    @Override
    public void setHysteresisThreshold(int open, int close)
    {
        if(open < NoiseSquelch.MINIMUM_HYSTERESIS_THRESHOLD || close > NoiseSquelch.MAXIMUM_HYSTERESIS_THRESHOLD ||
            open > close)
        {
            throw new IllegalArgumentException("Carrier hysteresis open/close [" + open + "/" + close +
                "] must be ordered and in range " + NoiseSquelch.MINIMUM_HYSTERESIS_THRESHOLD + "-" +
                NoiseSquelch.MAXIMUM_HYSTERESIS_THRESHOLD);
        }

        mOpenHysteresis = open;
        mCloseHysteresis = close;
        mHysteresisCount = Math.min(mHysteresisCount, close);
    }

    @Override
    public void setSquelchOverride(boolean override)
    {
        if(mOverride != override)
        {
            mOverride = override;

            if(override)
            {
                broadcast(SquelchState.UNSQUELCH);
            }
            else if(mSquelch)
            {
                broadcast(SquelchState.SQUELCH);
            }
        }
    }

    @Override
    public void setNoiseSquelchStateListener(Listener<NoiseSquelchState> listener)
    {
        mNoiseStateListener = listener;
    }

    @Override
    public void setAudioListener(Listener<float[]> listener)
    {
        mAudioListener = listener;
    }

    @Override
    public void setSquelchStateListener(Listener<SquelchState> listener)
    {
        mSquelchStateListener = listener;
    }

    @Override
    public void process(float[] audio, float[] i, float[] q)
    {
        if(audio.length != i.length || i.length != q.length)
        {
            throw new IllegalArgumentException("AM audio and filtered IQ buffers must have equal lengths");
        }
        else if(mWindowSize == 0)
        {
            throw new IllegalStateException("Carrier squelch requires a sample rate before processing audio");
        }

        boolean squelchedAtStart = mSquelch;

        for(int x = 0; x < i.length; x++)
        {
            float currentI = i[x];
            float currentQ = q[x];

            if(mDelayCount >= mDelayedI.length)
            {
                float delayedI = mDelayedI[mDelayPointer];
                float delayedQ = mDelayedQ[mDelayPointer];
                float currentPower = currentI * currentI + currentQ * currentQ;
                float delayedPower = delayedI * delayedI + delayedQ * delayedQ;

                if(currentPower > MINIMUM_POWER && delayedPower > MINIMUM_POWER)
                {
                    double normalization = Math.sqrt((double)currentPower * delayedPower);
                    mCoherenceI += (float)((currentI * delayedI + currentQ * delayedQ) / normalization);
                    mCoherenceQ += (float)((currentQ * delayedI - currentI * delayedQ) / normalization);
                    mCoherenceCount++;
                }
            }

            mDelayedI[mDelayPointer] = currentI;
            mDelayedQ[mDelayPointer] = currentQ;
            mDelayPointer++;

            if(mDelayPointer >= mDelayedI.length)
            {
                mDelayPointer = 0;
            }

            if(mDelayCount < mDelayedI.length)
            {
                mDelayCount++;
            }

            mWindowSampleCount++;

            if(mWindowSampleCount >= mWindowSize)
            {
                processWindow();
                mCoherenceI = 0.0f;
                mCoherenceQ = 0.0f;
                mCoherenceCount = 0;
                mWindowSampleCount = 0;
            }
        }

        if(mOverride)
        {
            broadcast(audio);
        }
        else if(squelchedAtStart && !mSquelch)
        {
            broadcast(SquelchState.UNSQUELCH);
            broadcast(audio);
        }
        else if(!squelchedAtStart && mSquelch)
        {
            //The audio listener flushes its resampler when it observes the new closed state.
            broadcast(audio);
            broadcast(SquelchState.SQUELCH);
        }
        else if(!mSquelch)
        {
            broadcast(audio);
        }
    }

    private void processWindow()
    {
        boolean signal = false;

        if(mCoherenceCount > 0)
        {
            float averageI = mCoherenceI / mCoherenceCount;
            float averageQ = mCoherenceQ / mCoherenceCount;
            float coherence = (float)Math.sqrt(averageI * averageI + averageQ * averageQ);
            mNoise = Math.min(Math.max(0.0f, 1.0f - coherence), NoiseSquelch.MAXIMUM_NOISE_THRESHOLD);
            signal = mNoise < (mSquelch ? mOpenThreshold : mCloseThreshold);
        }
        else
        {
            mNoise = NoiseSquelch.MAXIMUM_NOISE_THRESHOLD;
        }

        mHysteresisCount += signal ? 1 : -1;

        if(mSquelch && mHysteresisCount >= mOpenHysteresis)
        {
            mSquelch = false;
            mHysteresisCount = mOpenHysteresis;
        }
        else if(!mSquelch && mHysteresisCount <= 0)
        {
            mSquelch = true;
            mHysteresisCount = 0;
        }

        mHysteresisCount = Math.max(0, Math.min(mHysteresisCount, mCloseHysteresis));

        if(++mStateBroadcastCount >= 5)
        {
            mStateBroadcastCount = 0;
            Listener<NoiseSquelchState> listener = mNoiseStateListener;

            if(listener != null)
            {
                listener.receive(new NoiseSquelchState(mSquelch, mOverride, mNoise, mOpenThreshold, mCloseThreshold,
                    mHysteresisCount, mOpenHysteresis, mCloseHysteresis));
            }
        }
    }

    private void broadcast(float[] audio)
    {
        if(mAudioListener != null)
        {
            mAudioListener.receive(audio);
        }
    }

    private void broadcast(SquelchState state)
    {
        if(mSquelchStateListener != null)
        {
            mSquelchStateListener.receive(state);
        }
    }
}
