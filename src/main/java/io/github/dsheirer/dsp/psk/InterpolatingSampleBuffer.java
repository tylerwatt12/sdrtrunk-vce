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
package io.github.dsheirer.dsp.psk;

import io.github.dsheirer.dsp.filter.interpolator.Interpolator;
import io.github.dsheirer.dsp.filter.interpolator.InterpolatorFactory;
import io.github.dsheirer.sample.complex.Complex;
import java.util.Objects;
import org.apache.commons.math3.util.FastMath;

public class InterpolatingSampleBuffer
{

    private Complex mCurrentSample = new Complex(0,0);
    private Complex mMiddleSample = new Complex(0,0);

    protected float[] mDelayLineInphase;
    protected float[] mDelayLineQuadrature;
    protected int mDelayLinePointer = 0;
    private int mTwiceSamplesPerSymbol;

    private float mSamplingPoint;
    private float mSampleCounterGain = 0.5f;
    //Symbol clock error is parts per million, so the symbol period is fixed and only the timing phase is corrected.
    //A tracked symbol rate could wind up onto its limit and stay there, slipping symbols until the framer lost sync.
    private final float mSamplesPerSymbol;

    private final Interpolator mInterpolator;

    /**
     * Buffer to store complex sample data and produce interpolated samples.
     * @param samplesPerSymbol
     * @param sampleCounterGain for the symbol timing error adjustments
     */
    public InterpolatingSampleBuffer(float samplesPerSymbol, float sampleCounterGain)
    {
        this(samplesPerSymbol, sampleCounterGain, InterpolatorFactory.getInterpolator());
    }

    /** Test seam for verifying complete sample-buffer behavior across interpolator implementations. */
    InterpolatingSampleBuffer(float samplesPerSymbol, float sampleCounterGain, Interpolator interpolator)
    {
        mInterpolator = Objects.requireNonNull(interpolator, "Interpolator cannot be null");
        mSamplingPoint = samplesPerSymbol;
        mSamplesPerSymbol = samplesPerSymbol;
        mTwiceSamplesPerSymbol = (int) FastMath.floor(2.0 * samplesPerSymbol);
        mDelayLineInphase = new float[2 * mTwiceSamplesPerSymbol];
        mDelayLineQuadrature = new float[2 * mTwiceSamplesPerSymbol];

        mSampleCounterGain = sampleCounterGain;
    }

    /**
     * Stores the sample in the buffer and updates pointers.
     * @param sample
     */
    public void receive(Complex sample)
    {
        mSamplingPoint -= 1.0f;

        //Fill up the delay line to use with the interpolator
        mDelayLineInphase[mDelayLinePointer] = sample.inphase();
        mDelayLineInphase[mDelayLinePointer + mTwiceSamplesPerSymbol] = sample.inphase();
        mDelayLineQuadrature[mDelayLinePointer] = sample.quadrature();
        mDelayLineQuadrature[mDelayLinePointer + mTwiceSamplesPerSymbol] = sample.quadrature();

        //Increment pointer and keep pointer in bounds
        mDelayLinePointer++;
        mDelayLinePointer = mDelayLinePointer % mTwiceSamplesPerSymbol;
    }

    /**
     * Updates the internal sample counter with additional samples to add to the symbol sample counter.
     * @param samplesToAdd to the sample counter
     */
    public void increaseSampleCounter(float samplesToAdd)
    {
        mSamplingPoint += samplesToAdd;
    }

    /**
     * Adjusts symbol timing and increments the sample counter to collect another symbol.
     *
     * @param symbolTimingError from a symbol timing error detector
     */
    public void resetAndAdjust(float symbolTimingError)
    {
        //Add another symbol's worth of samples to the counter and adjust timing based on gardner error
        increaseSampleCounter(mSamplesPerSymbol + (symbolTimingError * mSampleCounterGain));
    }

    /**
     * Indicates if this buffer has accumulated a enough samples to represent a full symbol
     */
    public boolean hasSymbol()
    {
        return mSamplingPoint < 1.0f;
    }

    /**
     * Interpolated current sample for the symbol.
     *
     * Note: this method should only be invoked after testing for a complete symbol with the hasSymbol() method.
     */
    public Complex getCurrentSample()
    {
        /* Calculate interpolated current sample */
        mCurrentSample.setValues(getInphase(mSamplingPoint), getQuadrature(mSamplingPoint));
        return mCurrentSample;
    }

    /**
     * Interpolated sample that is 1/2 symbol away from (after) the current sample.
     *
     * Note: this method should only be invoked after testing for a complete symbol with the hasSymbol() method.
     */
    public Complex getMiddleSample()
    {
        float halfSamplesPerSymbol = mSamplesPerSymbol / 2.0f;

        //Interpolated sample that is half a symbol away from (occurred before) the current sample.
        mMiddleSample.setValues(getInphase(halfSamplesPerSymbol),
                                getQuadrature(halfSamplesPerSymbol));
        return mMiddleSample;
    }

    /**
     * Returns the interpolated inphase value for the specified offset
     * @param interpolation into the buffer to calculate the interpolated sample
     * @return inphase value of the interpolated sample
     */
    public float getInphase(float interpolation)
    {
        if(interpolation < 0.0f)
        {
            return mInterpolator.filter(mDelayLineInphase, mDelayLinePointer, 0.0f);
        }
        else if(interpolation < 1.0f)
        {
            return mInterpolator.filter(mDelayLineInphase, mDelayLinePointer, interpolation);
        }
        else
        {
            int offset = (int)FastMath.floor(interpolation);
            return mInterpolator.filter(mDelayLineInphase, mDelayLinePointer + offset, interpolation - offset);
        }
    }

    /**
     * Returns the interpolated quadrature value for the specified offset
     * @param interpolation into the buffer to calculate the interpolated sample
     * @return quadrature value of the interpolated sample
     */
    public float getQuadrature(float interpolation)
    {
        if(interpolation < 0.0f)
        {
            return mInterpolator.filter(mDelayLineQuadrature, mDelayLinePointer, 0.0f);
        }
        else if(interpolation < 1.0f)
        {
            return mInterpolator.filter(mDelayLineQuadrature, mDelayLinePointer, interpolation);
        }
        else
        {
            int offset = (int)FastMath.floor(interpolation);
            return mInterpolator.filter(mDelayLineQuadrature, mDelayLinePointer + offset, interpolation - offset);
        }
    }
}
