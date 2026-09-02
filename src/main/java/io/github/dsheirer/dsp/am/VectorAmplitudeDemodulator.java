/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.am;

import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.vector.calibrate.Implementation;
import jdk.incubator.vector.FloatVector;

/**
 * Vector SIMD AM envelope detector. DC removal, squelch, filtering, gain and resampling are owned by the shared
 * analog decoder.
 */
public class VectorAmplitudeDemodulator implements IDemodulator
{
    private final int mVectorBitSize;

    /**
     * Constructs an instance for the requested SIMD implementation.
     *
     * @param implementation supported vector implementation
     */
    public VectorAmplitudeDemodulator(Implementation implementation)
    {
        mVectorBitSize = switch(implementation)
        {
            case VECTOR_SIMD_PREFERRED -> FloatVector.SPECIES_PREFERRED.vectorBitSize();
            case VECTOR_SIMD_64 -> 64;
            case VECTOR_SIMD_128 -> 128;
            case VECTOR_SIMD_256 -> 256;
            case VECTOR_SIMD_512 -> 512;
            default -> throw new IllegalArgumentException("Vector implementation required: " + implementation);
        };

        if(mVectorBitSize != 64 && mVectorBitSize != 128 && mVectorBitSize != 256 && mVectorBitSize != 512)
        {
            throw new IllegalStateException("Unsupported preferred float vector width: " + mVectorBitSize);
        }
    }

    @Override
    public float[] demodulate(float[] i, float[] q)
    {
        if(i.length != q.length)
        {
            throw new IllegalArgumentException("I and Q sample buffers must have the same length");
        }

        float[] demodulated = new float[i.length];
        int x = switch(mVectorBitSize)
        {
            case 64 -> demodulate64(i, q, demodulated);
            case 128 -> demodulate128(i, q, demodulated);
            case 256 -> demodulate256(i, q, demodulated);
            case 512 -> demodulate512(i, q, demodulated);
            default -> throw new IllegalStateException("Unsupported float vector width: " + mVectorBitSize);
        };

        //Scalar tail supports arbitrary buffer lengths instead of imposing a SIMD-alignment requirement on callers.
        for(; x < i.length; x++)
        {
            demodulated[x] = (float)Math.sqrt((i[x] * i[x]) + (q[x] * q[x]));
        }

        return demodulated;
    }

    private static int demodulate64(float[] i, float[] q, float[] demodulated)
    {
        int vectorBound = FloatVector.SPECIES_64.loopBound(i.length);

        for(int x = 0; x < vectorBound; x += FloatVector.SPECIES_64.length())
        {
            FloatVector inphase = FloatVector.fromArray(FloatVector.SPECIES_64, i, x);
            FloatVector quadrature = FloatVector.fromArray(FloatVector.SPECIES_64, q, x);
            inphase.mul(inphase).add(quadrature.mul(quadrature)).sqrt().intoArray(demodulated, x);
        }

        return vectorBound;
    }

    private static int demodulate128(float[] i, float[] q, float[] demodulated)
    {
        int vectorBound = FloatVector.SPECIES_128.loopBound(i.length);

        for(int x = 0; x < vectorBound; x += FloatVector.SPECIES_128.length())
        {
            FloatVector inphase = FloatVector.fromArray(FloatVector.SPECIES_128, i, x);
            FloatVector quadrature = FloatVector.fromArray(FloatVector.SPECIES_128, q, x);
            inphase.mul(inphase).add(quadrature.mul(quadrature)).sqrt().intoArray(demodulated, x);
        }

        return vectorBound;
    }

    private static int demodulate256(float[] i, float[] q, float[] demodulated)
    {
        int vectorBound = FloatVector.SPECIES_256.loopBound(i.length);

        for(int x = 0; x < vectorBound; x += FloatVector.SPECIES_256.length())
        {
            FloatVector inphase = FloatVector.fromArray(FloatVector.SPECIES_256, i, x);
            FloatVector quadrature = FloatVector.fromArray(FloatVector.SPECIES_256, q, x);
            inphase.mul(inphase).add(quadrature.mul(quadrature)).sqrt().intoArray(demodulated, x);
        }

        return vectorBound;
    }

    private static int demodulate512(float[] i, float[] q, float[] demodulated)
    {
        int vectorBound = FloatVector.SPECIES_512.loopBound(i.length);

        for(int x = 0; x < vectorBound; x += FloatVector.SPECIES_512.length())
        {
            FloatVector inphase = FloatVector.fromArray(FloatVector.SPECIES_512, i, x);
            FloatVector quadrature = FloatVector.fromArray(FloatVector.SPECIES_512, q, x);
            inphase.mul(inphase).add(quadrature.mul(quadrature)).sqrt().intoArray(demodulated, x);
        }

        return vectorBound;
    }
}
