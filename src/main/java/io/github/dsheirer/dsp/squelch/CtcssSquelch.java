/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.dsp.squelch;

import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.sample.Listener;
import java.util.Objects;

/**
 * Receive gate requiring both the existing modulation-specific squelch and the selected CTCSS tone. The detector
 * always sees pre-gate demodulated audio. Manual monitor override bypasses both qualifications. CTCSS stays private
 * to this receive gate and never produces tone evidence, Activity observations or alert events.
 */
public final class CtcssSquelch implements IAnalogSquelch
{
    private static final float[] EMPTY_AUDIO = new float[0];
    private final IAnalogSquelch mCarrierSquelch;
    private final CtcssDetector mDetector;
    private volatile boolean mSquelched = true;
    private volatile boolean mOverride;
    private Listener<float[]> mAudioListener;
    private Listener<SquelchState> mStateListener;
    private volatile Listener<NoiseSquelchState> mNoiseListener;

    public CtcssSquelch(IAnalogSquelch carrierSquelch, double frequency)
    {
        mCarrierSquelch = Objects.requireNonNull(carrierSquelch);
        mDetector = new CtcssDetector(frequency);
        mCarrierSquelch.setAudioListener(this::receiveCarrierAudio);
        mCarrierSquelch.setSquelchStateListener(state -> {
            if(state == SquelchState.UNSQUELCH && allowed())
            {
                open();
            }
            else if(state == SquelchState.SQUELCH && !mOverride)
            {
                close();
                mDetector.reset();
            }
        });
        mCarrierSquelch.setNoiseSquelchStateListener(state -> {
            Listener<NoiseSquelchState> listener = mNoiseListener;
            if(listener != null)
            {
                listener.receive(getNoiseSquelchState());
            }
        });
    }

    private boolean allowed()
    {
        return mOverride || (!mCarrierSquelch.isSquelched() && mDetector.isQualified());
    }

    @Override
    public boolean isSquelched()
    {
        return !mOverride && (mSquelched || mCarrierSquelch.isSquelched() || !mDetector.isQualified());
    }

    @Override
    public NoiseSquelchState getNoiseSquelchState()
    {
        NoiseSquelchState state = mCarrierSquelch.getNoiseSquelchState();
        return new NoiseSquelchState(isSquelched(), mOverride, state.noise(), state.noiseOpenThreshold(),
            state.noiseCloseThreshold(), state.hysteresis(), state.hysteresisOpenThreshold(),
            state.hysteresisCloseThreshold());
    }

    @Override
    public void setSampleRate(double sampleRate)
    {
        close();
        mCarrierSquelch.setSampleRate(sampleRate);
        mDetector.setSampleRate(sampleRate);
    }

    @Override
    public void reset()
    {
        close();
        mDetector.reset();
        mCarrierSquelch.reset();
    }

    @Override
    public void setNoiseThreshold(float open, float close)
    {
        mCarrierSquelch.setNoiseThreshold(open, close);
    }

    @Override
    public void setHysteresisThreshold(int open, int close)
    {
        mCarrierSquelch.setHysteresisThreshold(open, close);
    }

    @Override
    public void setSquelchOverride(boolean override)
    {
        if(mOverride == override)
        {
            return;
        }
        mOverride = override;
        mCarrierSquelch.setSquelchOverride(override);
        if(allowed())
        {
            open();
        }
        else
        {
            close();
        }
    }

    @Override
    public void setNoiseSquelchStateListener(Listener<NoiseSquelchState> listener)
    {
        mNoiseListener = listener;
    }

    @Override
    public void setAudioListener(Listener<float[]> listener)
    {
        mAudioListener = listener;
    }

    @Override
    public void setSquelchStateListener(Listener<SquelchState> listener)
    {
        mStateListener = listener;
    }

    @Override
    public void process(float[] audio, float[] i, float[] q)
    {
        boolean carrierWasSquelched = mCarrierSquelch.isSquelched();
        mDetector.process(audio);
        if(!mOverride && !mDetector.isQualified())
        {
            close();
        }
        mCarrierSquelch.process(audio, i, q);
        if(!mOverride && mCarrierSquelch.isSquelched())
        {
            //Noise squelch can finish a buffer closed without a trailing partial-audio callback.
            close();
            if(!carrierWasSquelched) mDetector.reset();
        }
    }

    private void receiveCarrierAudio(float[] audio)
    {
        if(allowed())
        {
            open();
            if(mAudioListener != null)
            {
                mAudioListener.receive(audio);
            }
        }
        else if(!mSquelched && mCarrierSquelch.isSquelched() && mDetector.isQualified())
        {
            //Preserve the carrier gate's trailing audio and flush it before ending the call.
            mSquelched = true;
            if(mAudioListener != null)
            {
                mAudioListener.receive(audio);
            }
            broadcast(SquelchState.SQUELCH);
        }
        else
        {
            close();
        }
    }

    private void open()
    {
        if(mSquelched)
        {
            mSquelched = false;
            broadcast(SquelchState.UNSQUELCH);
        }
    }

    private void close()
    {
        if(!mSquelched && !mOverride)
        {
            mSquelched = true;
            //Flush already accepted audio; never pass audio that failed CTCSS qualification.
            if(mAudioListener != null)
            {
                mAudioListener.receive(EMPTY_AUDIO);
            }
            broadcast(SquelchState.SQUELCH);
        }
    }

    private void broadcast(SquelchState state)
    {
        if(mStateListener != null)
        {
            mStateListener.receive(state);
        }
    }
}
