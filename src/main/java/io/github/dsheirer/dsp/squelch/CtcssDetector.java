/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.dsp.squelch;

import java.util.Arrays;

/**
 * Bounded streaming receive-tone qualification. Only the selected tone and its two neighboring standard frequencies
 * are measured, with a fixed 400 ms history and 100 ms updates. There are no events, observers or external work.
 * Raw demodulated audio, before listening filters and before either squelch gate, must be supplied at its actual rate.
 */
public final class CtcssDetector
{
    private final double mFrequency;
    private final double[] mCoefficients = new double[3];
    private final Biquad mHighPass = new Biquad();
    private final Biquad mLowPass = new Biquad();
    private float[] mSamples = new float[0];
    private double[] mWindow = new double[0];
    private int mPointer;
    private int mCount;
    private int mHop;
    private int mHopCount;
    private boolean mQualified;

    public CtcssDetector(double frequency)
    {
        if(frequency == 0.0 || !CtcssFrequencies.isSupported(frequency))
        {
            throw new IllegalArgumentException("A standard CTCSS frequency is required");
        }
        mFrequency = frequency;
    }

    public void setSampleRate(double sampleRate)
    {
        if(!Double.isFinite(sampleRate) || sampleRate < 1000.0)
        {
            throw new IllegalArgumentException("CTCSS sample rate must be at least 1000 Hz");
        }
        mSamples = new float[(int)Math.round(sampleRate * 0.4)];
        mWindow = new double[mSamples.length];
        mHop = Math.max(1, (int)Math.round(sampleRate * 0.1));
        for(int x = 0; x < mWindow.length; x++)
        {
            mWindow[x] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * x / (mWindow.length - 1));
        }
        int index = CtcssFrequencies.STANDARD.indexOf(mFrequency);
        double lower = index > 0 ? CtcssFrequencies.STANDARD.get(index - 1) : 63.0;
        double upper = index + 1 < CtcssFrequencies.STANDARD.size() ?
            CtcssFrequencies.STANDARD.get(index + 1) : 260.0;
        mCoefficients[0] = 2.0 * Math.cos(2.0 * Math.PI * mFrequency / sampleRate);
        mCoefficients[1] = 2.0 * Math.cos(2.0 * Math.PI * lower / sampleRate);
        mCoefficients[2] = 2.0 * Math.cos(2.0 * Math.PI * upper / sampleRate);
        mHighPass.configure(sampleRate, 40.0, true);
        mLowPass.configure(sampleRate, 300.0, false);
        reset();
    }

    public void reset()
    {
        Arrays.fill(mSamples, 0.0f);
        mPointer = 0;
        mCount = 0;
        mHopCount = 0;
        mQualified = false;
        mHighPass.reset();
        mLowPass.reset();
    }

    public boolean isQualified()
    {
        return mQualified;
    }

    public void process(float[] samples)
    {
        if(mSamples.length == 0)
        {
            throw new IllegalStateException("CTCSS detector requires a sample rate");
        }
        for(float sample: samples)
        {
            double filtered = mLowPass.filter(mHighPass.filter(Float.isFinite(sample) ? sample : 0.0));
            mSamples[mPointer] = (float)filtered;
            mPointer = (mPointer + 1) % mSamples.length;
            mCount = Math.min(mCount + 1, mSamples.length);
            if(++mHopCount >= mHop)
            {
                mHopCount = 0;
                if(mCount == mSamples.length)
                {
                    qualify();
                }
            }
        }
    }

    private void qualify()
    {
        double s0 = 0.0, s1 = 0.0, s2 = 0.0;
        double t0 = 0.0, t1 = 0.0, t2 = 0.0;
        double energy = 0.0;
        for(int x = 0; x < mSamples.length; x++)
        {
            double sample = mSamples[(mPointer + x) % mSamples.length] * mWindow[x];
            energy += sample * sample;
            double next = sample + mCoefficients[0] * s0 - t0;
            t0 = s0;
            s0 = next;
            next = sample + mCoefficients[1] * s1 - t1;
            t1 = s1;
            s1 = next;
            next = sample + mCoefficients[2] * s2 - t2;
            t2 = s2;
            s2 = next;
        }
        double target = s0 * s0 + t0 * t0 - mCoefficients[0] * s0 * t0;
        double lower = s1 * s1 + t1 * t1 - mCoefficients[1] * s1 * t1;
        double upper = s2 * s2 + t2 * t2 - mCoefficients[2] * s2 * t2;
        mQualified = energy / mSamples.length > 1.0e-12 &&
            2.0 * target > 0.35 * mSamples.length * energy &&
            target > 1.15 * Math.max(lower, upper);
    }

    private static final class Biquad
    {
        private double mB0, mB1, mB2, mA1, mA2, mX1, mX2, mY1, mY2;

        private void configure(double sampleRate, double frequency, boolean highPass)
        {
            double omega = 2.0 * Math.PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / Math.sqrt(2.0);
            double a0 = 1.0 + alpha;
            mB0 = (highPass ? 1.0 + cosine : 1.0 - cosine) / (2.0 * a0);
            mB1 = (highPass ? -(1.0 + cosine) : 1.0 - cosine) / a0;
            mB2 = mB0;
            mA1 = -2.0 * cosine / a0;
            mA2 = (1.0 - alpha) / a0;
        }

        private double filter(double sample)
        {
            double output = mB0 * sample + mB1 * mX1 + mB2 * mX2 - mA1 * mY1 - mA2 * mY2;
            mX2 = mX1;
            mX1 = sample;
            mY2 = mY1;
            mY1 = output;
            return output;
        }

        private void reset()
        {
            mX1 = mX2 = mY1 = mY2 = 0.0;
        }
    }
}
