/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay;

import io.github.dsheirer.vector.calibrate.Implementation;
import java.util.Objects;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Java Vector API converter for SDRplay signed-short sample buffers.  Each short vector is widened in two parts to
 * floating point vectors of the same bit width.  A scalar tail preserves support for every native buffer length.
 */
public class VectorRspSampleConverter implements IRspSampleConverter
{
    private final VectorSpecies<Float> mFloatSpecies;
    private final VectorSpecies<Short> mShortSpecies;
    private final int[] mEvenOutputIndexes;
    private final int[] mOddOutputIndexes;

    /**
     * Constructs an instance for an explicit SIMD implementation.
     */
    public VectorRspSampleConverter(Implementation implementation)
    {
        this(getSpeciesPair(implementation));
    }

    private VectorRspSampleConverter(SpeciesPair speciesPair)
    {
        this(speciesPair.shortSpecies(), speciesPair.floatSpecies());
    }

    /**
     * Package-private constructor for verifying the widening-shape invariant with explicit species.
     */
    VectorRspSampleConverter(VectorSpecies<Short> shortSpecies, VectorSpecies<Float> floatSpecies)
    {
        mShortSpecies = Objects.requireNonNull(shortSpecies, "Short species cannot be null");
        mFloatSpecies = Objects.requireNonNull(floatSpecies, "Float species cannot be null");

        if(mShortSpecies.vectorBitSize() != mFloatSpecies.vectorBitSize() ||
            mShortSpecies.length() != 2 * mFloatSpecies.length())
        {
            throw new IllegalArgumentException("RSP sample conversion requires equal-width short and float species " +
                "with exactly two short lanes per float lane: short=" + mShortSpecies + " float=" + mFloatSpecies);
        }

        mEvenOutputIndexes = new int[mFloatSpecies.length()];
        mOddOutputIndexes = new int[mFloatSpecies.length()];

        for(int x = 0; x < mFloatSpecies.length(); x++)
        {
            mEvenOutputIndexes[x] = 2 * x;
            mOddOutputIndexes[x] = 2 * x + 1;
        }
    }

    /**
     * Gets the explicit vector bit width used for the preferred implementation.  Element types can report different
     * preferred shapes on a runtime, so the converter uses the widest implemented shape that is no wider than either
     * preference.  Matching bit widths guarantee that one short vector widens into exactly two float vectors.
     */
    public static int getPreferredVectorBitSize()
    {
        return selectPreferredVectorBitSize(ShortVector.SPECIES_PREFERRED.vectorBitSize(),
            FloatVector.SPECIES_PREFERRED.vectorBitSize());
    }

    /**
     * Selects a supported common vector width.  Package-private arguments make species-selection edge cases testable
     * without depending on the host CPU's preferred species.
     */
    static int selectPreferredVectorBitSize(int shortPreferredBitSize, int floatPreferredBitSize)
    {
        int commonBitSize = Math.min(shortPreferredBitSize, floatPreferredBitSize);

        if(commonBitSize >= 512)
        {
            return 512;
        }
        else if(commonBitSize >= 256)
        {
            return 256;
        }
        else if(commonBitSize >= 128)
        {
            return 128;
        }
        else if(commonBitSize >= 64)
        {
            return 64;
        }

        throw new IllegalStateException("No supported common RSP vector width for short preference " +
            shortPreferredBitSize + " bits and float preference " + floatPreferredBitSize + " bits");
    }

    /**
     * Package-private accessors for focused species-selection tests.
     */
    int getVectorBitSize()
    {
        return mFloatSpecies.vectorBitSize();
    }

    int getShortLaneCount()
    {
        return mShortSpecies.length();
    }

    int getFloatLaneCount()
    {
        return mFloatSpecies.length();
    }

    @Override
    public void convert(short[] iSamples, short[] qSamples, float[] iOutput, float[] qOutput)
    {
        RspSampleConverterFactory.validate(iSamples, qSamples, iOutput, qOutput);
        int x = 0;
        int vectorBound = mShortSpecies.loopBound(iSamples.length);
        int floatLaneCount = mFloatSpecies.length();

        for(; x < vectorBound; x += mShortSpecies.length())
        {
            ShortVector iVector = ShortVector.fromArray(mShortSpecies, iSamples, x);
            ShortVector qVector = ShortVector.fromArray(mShortSpecies, qSamples, x);

            widenAndScale(iVector, 0).intoArray(iOutput, x);
            widenAndScale(iVector, 1).intoArray(iOutput, x + floatLaneCount);
            widenAndScale(qVector, 0).intoArray(qOutput, x);
            widenAndScale(qVector, 1).intoArray(qOutput, x + floatLaneCount);
        }

        for(; x < iSamples.length; x++)
        {
            iOutput[x] = iSamples[x] * SAMPLE_TO_FLOAT;
            qOutput[x] = qSamples[x] * SAMPLE_TO_FLOAT;
        }
    }

    @Override
    public void convertInterleaved(short[] iSamples, short[] qSamples, float[] output)
    {
        RspSampleConverterFactory.validateInterleaved(iSamples, qSamples, output);
        int x = 0;
        int vectorBound = mShortSpecies.loopBound(iSamples.length);
        int floatLaneCount = mFloatSpecies.length();

        for(; x < vectorBound; x += mShortSpecies.length())
        {
            ShortVector iVector = ShortVector.fromArray(mShortSpecies, iSamples, x);
            ShortVector qVector = ShortVector.fromArray(mShortSpecies, qSamples, x);
            int firstOutputOffset = 2 * x;
            int secondOutputOffset = 2 * (x + floatLaneCount);

            widenAndScale(iVector, 0).intoArray(output, firstOutputOffset, mEvenOutputIndexes, 0);
            widenAndScale(qVector, 0).intoArray(output, firstOutputOffset, mOddOutputIndexes, 0);
            widenAndScale(iVector, 1).intoArray(output, secondOutputOffset, mEvenOutputIndexes, 0);
            widenAndScale(qVector, 1).intoArray(output, secondOutputOffset, mOddOutputIndexes, 0);
        }

        int outputPointer = 2 * x;

        for(; x < iSamples.length; x++)
        {
            output[outputPointer++] = iSamples[x] * SAMPLE_TO_FLOAT;
            output[outputPointer++] = qSamples[x] * SAMPLE_TO_FLOAT;
        }
    }

    private FloatVector widenAndScale(ShortVector vector, int part)
    {
        return ((FloatVector)vector.convertShape(VectorOperators.S2F, mFloatSpecies, part)).mul(SAMPLE_TO_FLOAT);
    }

    private static SpeciesPair getSpeciesPair(Implementation implementation)
    {
        Objects.requireNonNull(implementation, "Implementation cannot be null");
        int vectorBitSize = switch(implementation)
        {
            case VECTOR_SIMD_PREFERRED -> getPreferredVectorBitSize();
            case VECTOR_SIMD_64 -> 64;
            case VECTOR_SIMD_128 -> 128;
            case VECTOR_SIMD_256 -> 256;
            case VECTOR_SIMD_512 -> 512;
            default -> throw new IllegalArgumentException("Vector implementation required: " + implementation);
        };

        return switch(vectorBitSize)
        {
            case 64 -> new SpeciesPair(ShortVector.SPECIES_64, FloatVector.SPECIES_64);
            case 128 -> new SpeciesPair(ShortVector.SPECIES_128, FloatVector.SPECIES_128);
            case 256 -> new SpeciesPair(ShortVector.SPECIES_256, FloatVector.SPECIES_256);
            case 512 -> new SpeciesPair(ShortVector.SPECIES_512, FloatVector.SPECIES_512);
            default -> throw new IllegalStateException("Unsupported RSP vector width: " + vectorBitSize);
        };
    }

    private record SpeciesPair(VectorSpecies<Short> shortSpecies, VectorSpecies<Float> floatSpecies)
    {
    }
}
