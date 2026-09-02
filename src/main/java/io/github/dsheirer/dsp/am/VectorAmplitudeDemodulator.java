/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.am;

import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.vector.calibrate.Implementation;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vector SIMD AM envelope detector. DC removal, squelch, filtering, gain and resampling are owned by the shared
 * analog decoder.
 */
public class VectorAmplitudeDemodulator implements IDemodulator
{
    private final VectorSpecies<Float> mSpecies;

    /**
     * Constructs an instance for the requested SIMD implementation.
     *
     * @param implementation supported vector implementation
     */
    public VectorAmplitudeDemodulator(Implementation implementation)
    {
        mSpecies = switch(implementation)
        {
            case VECTOR_SIMD_PREFERRED -> FloatVector.SPECIES_PREFERRED;
            case VECTOR_SIMD_64 -> FloatVector.SPECIES_64;
            case VECTOR_SIMD_128 -> FloatVector.SPECIES_128;
            case VECTOR_SIMD_256 -> FloatVector.SPECIES_256;
            case VECTOR_SIMD_512 -> FloatVector.SPECIES_512;
            default -> throw new IllegalArgumentException("Vector implementation required: " + implementation);
        };
    }

    @Override
    public float[] demodulate(float[] i, float[] q)
    {
        if(i.length != q.length)
        {
            throw new IllegalArgumentException("I and Q sample buffers must have the same length");
        }

        float[] demodulated = new float[i.length];
        int x = 0;
        int vectorBound = mSpecies.loopBound(i.length);

        for(; x < vectorBound; x += mSpecies.length())
        {
            FloatVector inphase = FloatVector.fromArray(mSpecies, i, x);
            FloatVector quadrature = FloatVector.fromArray(mSpecies, q, x);
            inphase.mul(inphase).add(quadrature.mul(quadrature)).sqrt().intoArray(demodulated, x);
        }

        //Scalar tail supports arbitrary buffer lengths instead of imposing a SIMD-alignment requirement on callers.
        for(; x < i.length; x++)
        {
            demodulated[x] = (float)Math.sqrt((i[x] * i[x]) + (q[x] * q[x]));
        }

        return demodulated;
    }
}
