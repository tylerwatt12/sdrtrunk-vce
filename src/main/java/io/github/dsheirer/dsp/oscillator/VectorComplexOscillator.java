/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.dsp.oscillator;

import io.github.dsheirer.sample.complex.ComplexSamples;
import java.util.Arrays;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import org.apache.commons.math3.util.FastMath;

/**
 * Complex oscillator that uses JDK17 SIMD vector operations to generate complex sample arrays.
 *
 * Note: this class uses a bank of oscillators that are each rotated synchronously, where the oscillator is similar to
 * the ComplexOscillator class, but where each oscillator is offset in phase by one sample more than the previous and
 * the entire bank is rotated at the sample phase times the SIMD lane width for each sample generation increment.
 */
public class VectorComplexOscillator extends AbstractOscillator implements IComplexOscillator
{
    private static final VectorSpecies<Float> VECTOR_SPECIES = FloatVector.SPECIES_PREFERRED;

    private final float[] mPreviousInphases;
    private final float[] mPreviousQuadratures;
    private final float[] mGainInitials; //Set to 3.0f as the first constant in the gain calculation
    private final int[] mInphaseIndexes;
    private final int[] mQuadratureIndexes;

    /*
     * Deliberately has no field initializer. AbstractOscillator invokes update() from its constructor before subclass
     * field initializers run, and that initial update publishes the first configured phase increment here.
     */
    private volatile float mPendingAnglePerSample;
    private float mActiveAnglePerSample = Float.NaN;

    /**
     * Constructs an instance
     *
     * @param frequency  in hertz
     * @param sampleRate in hertz
     */
    public VectorComplexOscillator(double frequency, double sampleRate)
    {
        super(frequency, sampleRate);

        int laneCount = VECTOR_SPECIES.length();
        mPreviousInphases = new float[laneCount];
        mPreviousQuadratures = new float[laneCount];
        mPreviousInphases[laneCount - 1] = 1.0f;

        mGainInitials = new float[VECTOR_SPECIES.length()];
        Arrays.fill(mGainInitials, 3.0f);

        mInphaseIndexes = new int[laneCount];
        mQuadratureIndexes = new int[laneCount];

        for(int x = 0; x < laneCount; x++)
        {
            mInphaseIndexes[x] = 2 * x;
            mQuadratureIndexes[x] = (2 * x) + 1;
        }
    }

    /**
     * Publishes a new phase increment for the sample-producing thread. The oscillator bank is only mutated by the
     * producing thread at its next buffer boundary, so a frequency correction cannot race an in-progress SIMD loop.
     */
    @Override
    protected void update()
    {
        super.update();
        mPendingAnglePerSample = getAnglePerSample();
    }

    /**
     * Applies the latest published phase increment and preserves the phase of the last generated sample.
     *
     * @return phase increment to use for the complete next buffer
     */
    private float applyPendingUpdate()
    {
        float pendingAnglePerSample = mPendingAnglePerSample;

        if(Float.floatToRawIntBits(pendingAnglePerSample) != Float.floatToRawIntBits(mActiveAnglePerSample))
        {
            int lastLane = VECTOR_SPECIES.length() - 1;
            rebuildPreviousBank(mPreviousInphases[lastLane], mPreviousQuadratures[lastLane], pendingAnglePerSample);
            mActiveAnglePerSample = pendingAnglePerSample;
        }

        return mActiveAnglePerSample;
    }

    /**
     * Rebuilds the SIMD oscillator bank ending with the supplied most-recent sample. Each preceding lane is one
     * sample earlier in phase, which lets the next vector rotation produce the next contiguous lane-width of samples.
     */
    private void rebuildPreviousBank(float lastInphase, float lastQuadrature, float anglePerSample)
    {
        //Preserve the phase of the most recently generated sample and rebuild the preceding SIMD lanes at the newly
        //configured phase increment.  A vector generation step advances the entire bank by one lane-width, so this
        //layout causes its first output lane to be exactly one sample after the last output, matching the scalar
        //oscillator.  The old layout started lane zero at the last output and filled later lanes forward, which made
        //the first generated vector start lane-width-minus-one samples too far ahead.
        int lastLane = VECTOR_SPECIES.length() - 1;

        for(int x = 0; x < VECTOR_SPECIES.length(); x++)
        {
            double offset = (x - lastLane) * anglePerSample;
            float cosineOffset = (float)FastMath.cos(offset);
            float sineOffset = (float)FastMath.sin(offset);
            mPreviousInphases[x] = (lastInphase * cosineOffset) - (lastQuadrature * sineOffset);
            mPreviousQuadratures[x] = (lastInphase * sineOffset) + (lastQuadrature * cosineOffset);
        }
    }

    /**
     * Generates complex samples.
     * @param sampleCount number of samples to generate and length of the resulting float array.
     * @return generated samples
     */
    @Override public float[] generate(int sampleCount)
    {
        if(sampleCount < 0)
        {
            throw new IllegalArgumentException("Sample count cannot be negative: " + sampleCount);
        }

        float anglePerSample = applyPendingUpdate();
        float[] samples = new float[sampleCount * 2];
        FloatVector previousInphase = FloatVector.fromArray(VECTOR_SPECIES, mPreviousInphases, 0);
        FloatVector previousQuadrature = FloatVector.fromArray(VECTOR_SPECIES, mPreviousQuadratures, 0);
        FloatVector gainInitials = FloatVector.fromArray(VECTOR_SPECIES, mGainInitials, 0);

        //Sine and cosine angle per sample, with the rotation angle multiplied by the SIMD lane width
        float cosAngle = (float)(FastMath.cos(anglePerSample * VECTOR_SPECIES.length()));
        float sinAngle = (float)(FastMath.sin(anglePerSample * VECTOR_SPECIES.length()));

        FloatVector gain;
        FloatVector inphase;
        FloatVector quadrature;

        int gainCounter = 0;
        int vectorBound = VECTOR_SPECIES.loopBound(sampleCount);

        for(int samplePointer = 0; samplePointer < vectorBound; samplePointer += VECTOR_SPECIES.length())
        {
            if(++gainCounter % 10 == 0)
            {
                gain = gainInitials.sub(previousInphase.pow(2.0f).add(previousQuadrature.pow(2.0f))).div(2.0f);
                inphase = previousInphase.mul(cosAngle).sub(previousQuadrature.mul(sinAngle)).mul(gain);
                quadrature = previousInphase.mul(sinAngle).add(previousQuadrature.mul(cosAngle)).mul(gain);
            }
            else
            {
                inphase = previousInphase.mul(cosAngle).sub(previousQuadrature.mul(sinAngle));
                quadrature = previousInphase.mul(sinAngle).add(previousQuadrature.mul(cosAngle));
            }

            int outputOffset = samplePointer * 2;
            inphase.intoArray(samples, outputOffset, mInphaseIndexes, 0);
            quadrature.intoArray(samples, outputOffset, mQuadratureIndexes, 0);

            previousInphase = inphase;
            previousQuadrature = quadrature;
        }

        int lastLane = VECTOR_SPECIES.length() - 1;
        float lastInphase = previousInphase.lane(lastLane);
        float lastQuadrature = previousQuadrature.lane(lastLane);

        if(vectorBound < sampleCount)
        {
            float cosineAngle = (float)FastMath.cos(anglePerSample);
            float sineAngle = (float)FastMath.sin(anglePerSample);

            for(int samplePointer = vectorBound; samplePointer < sampleCount; samplePointer++)
            {
                float tailGain = (3.0f - ((lastInphase * lastInphase) +
                    (lastQuadrature * lastQuadrature))) / 2.0f;
                float tailInphase = ((lastInphase * cosineAngle) - (lastQuadrature * sineAngle)) * tailGain;
                float tailQuadrature = ((lastInphase * sineAngle) + (lastQuadrature * cosineAngle)) * tailGain;
                int outputOffset = samplePointer * 2;
                samples[outputOffset] = tailInphase;
                samples[outputOffset + 1] = tailQuadrature;
                lastInphase = tailInphase;
                lastQuadrature = tailQuadrature;
            }

            rebuildPreviousBank(lastInphase, lastQuadrature, anglePerSample);
        }
        else
        {
            previousInphase.intoArray(mPreviousInphases, 0);
            previousQuadrature.intoArray(mPreviousQuadratures, 0);
        }

        return samples;
    }

    /**
     * Generates complex samples.
     * @param sampleCount number of samples to generate and length of the resulting float array.
     * @param timestamp of the first sample
     * @return generated samples
     */
    @Override public ComplexSamples generateComplexSamples(int sampleCount, long timestamp)
    {
        if(sampleCount < 0)
        {
            throw new IllegalArgumentException("Sample count cannot be negative: " + sampleCount);
        }

        float anglePerSample = applyPendingUpdate();
        float[] iSamples = new float[sampleCount];
        float[] qSamples = new float[sampleCount];

        FloatVector previousInphase = FloatVector.fromArray(VECTOR_SPECIES, mPreviousInphases, 0);
        FloatVector previousQuadrature = FloatVector.fromArray(VECTOR_SPECIES, mPreviousQuadratures, 0);
        FloatVector gainInitials = FloatVector.fromArray(VECTOR_SPECIES, mGainInitials, 0);

        //Sine and cosine angle per sample, with the rotation angle multiplied by the SIMD lane width
        float cosAngle = (float)(FastMath.cos(anglePerSample * VECTOR_SPECIES.length()));
        float sinAngle = (float)(FastMath.sin(anglePerSample * VECTOR_SPECIES.length()));

        FloatVector gain;
        FloatVector inphase;
        FloatVector quadrature;
        int vectorBound = VECTOR_SPECIES.loopBound(sampleCount);

        for(int samplePointer = 0; samplePointer < vectorBound; samplePointer += VECTOR_SPECIES.length())
        {
            gain = gainInitials.sub(previousInphase.pow(2.0f).add(previousQuadrature.pow(2.0f))).div(2.0f);
            inphase = previousInphase.mul(cosAngle).sub(previousQuadrature.mul(sinAngle)).mul(gain);
            quadrature = previousInphase.mul(sinAngle).add(previousQuadrature.mul(cosAngle)).mul(gain);

            inphase.intoArray(iSamples, samplePointer);
            quadrature.intoArray(qSamples, samplePointer);

            previousInphase = inphase;
            previousQuadrature = quadrature;
        }

        int lastLane = VECTOR_SPECIES.length() - 1;
        float lastInphase = previousInphase.lane(lastLane);
        float lastQuadrature = previousQuadrature.lane(lastLane);

        if(vectorBound < sampleCount)
        {
            float cosineAngle = (float)FastMath.cos(anglePerSample);
            float sineAngle = (float)FastMath.sin(anglePerSample);

            for(int samplePointer = vectorBound; samplePointer < sampleCount; samplePointer++)
            {
                float tailGain = (3.0f - ((lastInphase * lastInphase) +
                    (lastQuadrature * lastQuadrature))) / 2.0f;
                float tailInphase = ((lastInphase * cosineAngle) - (lastQuadrature * sineAngle)) * tailGain;
                float tailQuadrature = ((lastInphase * sineAngle) + (lastQuadrature * cosineAngle)) * tailGain;
                iSamples[samplePointer] = tailInphase;
                qSamples[samplePointer] = tailQuadrature;
                lastInphase = tailInphase;
                lastQuadrature = tailQuadrature;
            }

            rebuildPreviousBank(lastInphase, lastQuadrature, anglePerSample);
        }
        else
        {
            previousInphase.intoArray(mPreviousInphases, 0);
            previousQuadrature.intoArray(mPreviousQuadratures, 0);
        }

        return new ComplexSamples(iSamples, qSamples, timestamp);
    }
}
