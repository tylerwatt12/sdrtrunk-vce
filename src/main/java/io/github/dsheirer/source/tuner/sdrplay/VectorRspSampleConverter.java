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
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Java Vector API converter for SDRplay signed-short sample buffers.  Each short vector is widened in two parts to
 * floating point vectors of the same bit width.  A scalar tail preserves support for every native buffer length.
 */
public class VectorRspSampleConverter implements IRspSampleConverter
{
    private static final VectorShuffle<Float> ZIP_64_0 = VectorShuffle.makeZip(FloatVector.SPECIES_64, 0);
    private static final VectorShuffle<Float> ZIP_64_1 = VectorShuffle.makeZip(FloatVector.SPECIES_64, 1);
    private static final VectorShuffle<Float> ZIP_128_0 = VectorShuffle.makeZip(FloatVector.SPECIES_128, 0);
    private static final VectorShuffle<Float> ZIP_128_1 = VectorShuffle.makeZip(FloatVector.SPECIES_128, 1);
    private static final VectorShuffle<Float> ZIP_256_0 = VectorShuffle.makeZip(FloatVector.SPECIES_256, 0);
    private static final VectorShuffle<Float> ZIP_256_1 = VectorShuffle.makeZip(FloatVector.SPECIES_256, 1);
    private static final VectorShuffle<Float> ZIP_512_0 = VectorShuffle.makeZip(FloatVector.SPECIES_512, 0);
    private static final VectorShuffle<Float> ZIP_512_1 = VectorShuffle.makeZip(FloatVector.SPECIES_512, 1);

    private final int mVectorBitSize;
    private final int mShortLaneCount;
    private final int mFloatLaneCount;

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
        Objects.requireNonNull(shortSpecies, "Short species cannot be null");
        Objects.requireNonNull(floatSpecies, "Float species cannot be null");

        if(shortSpecies.vectorBitSize() != floatSpecies.vectorBitSize() ||
            shortSpecies.length() != 2 * floatSpecies.length())
        {
            throw new IllegalArgumentException("RSP sample conversion requires equal-width short and float species " +
                "with exactly two short lanes per float lane: short=" + shortSpecies + " float=" + floatSpecies);
        }

        mVectorBitSize = floatSpecies.vectorBitSize();
        if(mVectorBitSize != 64 && mVectorBitSize != 128 && mVectorBitSize != 256 && mVectorBitSize != 512)
        {
            throw new IllegalArgumentException("Unsupported RSP vector width: " + mVectorBitSize);
        }

        mShortLaneCount = shortSpecies.length();
        mFloatLaneCount = floatSpecies.length();
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
        return mVectorBitSize;
    }

    int getShortLaneCount()
    {
        return mShortLaneCount;
    }

    int getFloatLaneCount()
    {
        return mFloatLaneCount;
    }

    @Override
    public void convert(short[] iSamples, short[] qSamples, float[] iOutput, float[] qOutput)
    {
        RspSampleConverterFactory.validate(iSamples, qSamples, iOutput, qOutput);
        int vectorBound = switch(mVectorBitSize)
        {
            case 64 -> convert64(iSamples, qSamples, iOutput, qOutput);
            case 128 -> convert128(iSamples, qSamples, iOutput, qOutput);
            case 256 -> convert256(iSamples, qSamples, iOutput, qOutput);
            case 512 -> convert512(iSamples, qSamples, iOutput, qOutput);
            default -> throw new IllegalStateException("Unsupported RSP vector width: " + mVectorBitSize);
        };

        for(int x = vectorBound; x < iSamples.length; x++)
        {
            iOutput[x] = iSamples[x] * SAMPLE_TO_FLOAT;
            qOutput[x] = qSamples[x] * SAMPLE_TO_FLOAT;
        }
    }

    @Override
    public void convertInterleaved(short[] iSamples, short[] qSamples, float[] output)
    {
        RspSampleConverterFactory.validateInterleaved(iSamples, qSamples, output);
        int vectorBound = switch(mVectorBitSize)
        {
            case 64 -> convertInterleaved64(iSamples, qSamples, output);
            case 128 -> convertInterleaved128(iSamples, qSamples, output);
            case 256 -> convertInterleaved256(iSamples, qSamples, output);
            case 512 -> convertInterleaved512(iSamples, qSamples, output);
            default -> throw new IllegalStateException("Unsupported RSP vector width: " + mVectorBitSize);
        };

        int outputPointer = 2 * vectorBound;

        for(int x = vectorBound; x < iSamples.length; x++)
        {
            output[outputPointer++] = iSamples[x] * SAMPLE_TO_FLOAT;
            output[outputPointer++] = qSamples[x] * SAMPLE_TO_FLOAT;
        }
    }

    /**
     * The Vector API can intrinsify these operations only when the species is a constant at the hot call site.
     * Keeping the selected species in an instance field caused the conversion intermediates to escape as heap
     * objects on Java 25.  The public switch retains runtime calibration while these kernels retain constant species.
     */
    private static int convert64(short[] iSamples, short[] qSamples, float[] iOutput, float[] qOutput)
    {
        int vectorBound = ShortVector.SPECIES_64.loopBound(iSamples.length);
        int floatLaneCount = FloatVector.SPECIES_64.length();

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_64.length())
        {
            IntVector iPacked = ShortVector.fromArray(ShortVector.SPECIES_64, iSamples, x).reinterpretAsInts();
            IntVector qPacked = ShortVector.fromArray(ShortVector.SPECIES_64, qSamples, x).reinterpretAsInts();
            FloatVector iEven = ((FloatVector)iPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_64, 0));
            FloatVector iOdd = ((FloatVector)iPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_64, 0));
            FloatVector qEven = ((FloatVector)qPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_64, 0));
            FloatVector qOdd = ((FloatVector)qPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_64, 0));
            iEven.rearrange(ZIP_64_0, iOdd).mul(SAMPLE_TO_FLOAT).intoArray(iOutput, x);
            iEven.rearrange(ZIP_64_1, iOdd).mul(SAMPLE_TO_FLOAT).intoArray(iOutput, x + floatLaneCount);
            qEven.rearrange(ZIP_64_0, qOdd).mul(SAMPLE_TO_FLOAT).intoArray(qOutput, x);
            qEven.rearrange(ZIP_64_1, qOdd).mul(SAMPLE_TO_FLOAT).intoArray(qOutput, x + floatLaneCount);
        }

        return vectorBound;
    }

    private static int convert128(short[] iSamples, short[] qSamples, float[] iOutput, float[] qOutput)
    {
        int vectorBound = ShortVector.SPECIES_128.loopBound(iSamples.length);
        int floatLaneCount = FloatVector.SPECIES_128.length();

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_128.length())
        {
            IntVector iPacked = ShortVector.fromArray(ShortVector.SPECIES_128, iSamples, x).reinterpretAsInts();
            IntVector qPacked = ShortVector.fromArray(ShortVector.SPECIES_128, qSamples, x).reinterpretAsInts();
            FloatVector iEven = ((FloatVector)iPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_128, 0));
            FloatVector iOdd = ((FloatVector)iPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_128, 0));
            FloatVector qEven = ((FloatVector)qPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_128, 0));
            FloatVector qOdd = ((FloatVector)qPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_128, 0));
            iEven.rearrange(ZIP_128_0, iOdd).mul(SAMPLE_TO_FLOAT).intoArray(iOutput, x);
            iEven.rearrange(ZIP_128_1, iOdd).mul(SAMPLE_TO_FLOAT).intoArray(iOutput, x + floatLaneCount);
            qEven.rearrange(ZIP_128_0, qOdd).mul(SAMPLE_TO_FLOAT).intoArray(qOutput, x);
            qEven.rearrange(ZIP_128_1, qOdd).mul(SAMPLE_TO_FLOAT).intoArray(qOutput, x + floatLaneCount);
        }

        return vectorBound;
    }

    private static int convert256(short[] iSamples, short[] qSamples, float[] iOutput, float[] qOutput)
    {
        int vectorBound = ShortVector.SPECIES_256.loopBound(iSamples.length);
        int floatLaneCount = FloatVector.SPECIES_256.length();

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_256.length())
        {
            IntVector iPacked = ShortVector.fromArray(ShortVector.SPECIES_256, iSamples, x).reinterpretAsInts();
            IntVector qPacked = ShortVector.fromArray(ShortVector.SPECIES_256, qSamples, x).reinterpretAsInts();
            FloatVector iEven = ((FloatVector)iPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_256, 0));
            FloatVector iOdd = ((FloatVector)iPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_256, 0));
            FloatVector qEven = ((FloatVector)qPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_256, 0));
            FloatVector qOdd = ((FloatVector)qPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_256, 0));
            iEven.rearrange(ZIP_256_0, iOdd).mul(SAMPLE_TO_FLOAT).intoArray(iOutput, x);
            iEven.rearrange(ZIP_256_1, iOdd).mul(SAMPLE_TO_FLOAT).intoArray(iOutput, x + floatLaneCount);
            qEven.rearrange(ZIP_256_0, qOdd).mul(SAMPLE_TO_FLOAT).intoArray(qOutput, x);
            qEven.rearrange(ZIP_256_1, qOdd).mul(SAMPLE_TO_FLOAT).intoArray(qOutput, x + floatLaneCount);
        }

        return vectorBound;
    }

    private static int convert512(short[] iSamples, short[] qSamples, float[] iOutput, float[] qOutput)
    {
        int vectorBound = ShortVector.SPECIES_512.loopBound(iSamples.length);
        int floatLaneCount = FloatVector.SPECIES_512.length();

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_512.length())
        {
            IntVector iPacked = ShortVector.fromArray(ShortVector.SPECIES_512, iSamples, x).reinterpretAsInts();
            IntVector qPacked = ShortVector.fromArray(ShortVector.SPECIES_512, qSamples, x).reinterpretAsInts();
            FloatVector iEven = ((FloatVector)iPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_512, 0));
            FloatVector iOdd = ((FloatVector)iPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_512, 0));
            FloatVector qEven = ((FloatVector)qPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_512, 0));
            FloatVector qOdd = ((FloatVector)qPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_512, 0));
            iEven.rearrange(ZIP_512_0, iOdd).mul(SAMPLE_TO_FLOAT).intoArray(iOutput, x);
            iEven.rearrange(ZIP_512_1, iOdd).mul(SAMPLE_TO_FLOAT).intoArray(iOutput, x + floatLaneCount);
            qEven.rearrange(ZIP_512_0, qOdd).mul(SAMPLE_TO_FLOAT).intoArray(qOutput, x);
            qEven.rearrange(ZIP_512_1, qOdd).mul(SAMPLE_TO_FLOAT).intoArray(qOutput, x + floatLaneCount);
        }

        return vectorBound;
    }

    private static int convertInterleaved64(short[] iSamples, short[] qSamples, float[] output)
    {
        int vectorBound = ShortVector.SPECIES_64.loopBound(iSamples.length);
        int floatLaneCount = FloatVector.SPECIES_64.length();

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_64.length())
        {
            IntVector iPacked = ShortVector.fromArray(ShortVector.SPECIES_64, iSamples, x).reinterpretAsInts();
            IntVector qPacked = ShortVector.fromArray(ShortVector.SPECIES_64, qSamples, x).reinterpretAsInts();
            FloatVector iEven = ((FloatVector)iPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_64, 0));
            FloatVector iOdd = ((FloatVector)iPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_64, 0));
            FloatVector qEven = ((FloatVector)qPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_64, 0));
            FloatVector qOdd = ((FloatVector)qPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_64, 0));
            FloatVector i0 = iEven.rearrange(ZIP_64_0, iOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector i1 = iEven.rearrange(ZIP_64_1, iOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector q0 = qEven.rearrange(ZIP_64_0, qOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector q1 = qEven.rearrange(ZIP_64_1, qOdd).mul(SAMPLE_TO_FLOAT);
            int outputOffset = 2 * x;
            i0.rearrange(ZIP_64_0, q0).intoArray(output, outputOffset);
            i0.rearrange(ZIP_64_1, q0).intoArray(output, outputOffset + floatLaneCount);
            i1.rearrange(ZIP_64_0, q1).intoArray(output, outputOffset + 2 * floatLaneCount);
            i1.rearrange(ZIP_64_1, q1).intoArray(output, outputOffset + 3 * floatLaneCount);
        }

        return vectorBound;
    }

    private static int convertInterleaved128(short[] iSamples, short[] qSamples, float[] output)
    {
        int vectorBound = ShortVector.SPECIES_128.loopBound(iSamples.length);
        int floatLaneCount = FloatVector.SPECIES_128.length();

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_128.length())
        {
            IntVector iPacked = ShortVector.fromArray(ShortVector.SPECIES_128, iSamples, x).reinterpretAsInts();
            IntVector qPacked = ShortVector.fromArray(ShortVector.SPECIES_128, qSamples, x).reinterpretAsInts();
            FloatVector iEven = ((FloatVector)iPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_128, 0));
            FloatVector iOdd = ((FloatVector)iPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_128, 0));
            FloatVector qEven = ((FloatVector)qPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_128, 0));
            FloatVector qOdd = ((FloatVector)qPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_128, 0));
            FloatVector i0 = iEven.rearrange(ZIP_128_0, iOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector i1 = iEven.rearrange(ZIP_128_1, iOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector q0 = qEven.rearrange(ZIP_128_0, qOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector q1 = qEven.rearrange(ZIP_128_1, qOdd).mul(SAMPLE_TO_FLOAT);
            int outputOffset = 2 * x;
            i0.rearrange(ZIP_128_0, q0).intoArray(output, outputOffset);
            i0.rearrange(ZIP_128_1, q0).intoArray(output, outputOffset + floatLaneCount);
            i1.rearrange(ZIP_128_0, q1).intoArray(output, outputOffset + 2 * floatLaneCount);
            i1.rearrange(ZIP_128_1, q1).intoArray(output, outputOffset + 3 * floatLaneCount);
        }

        return vectorBound;
    }

    private static int convertInterleaved256(short[] iSamples, short[] qSamples, float[] output)
    {
        int vectorBound = ShortVector.SPECIES_256.loopBound(iSamples.length);
        int floatLaneCount = FloatVector.SPECIES_256.length();

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_256.length())
        {
            IntVector iPacked = ShortVector.fromArray(ShortVector.SPECIES_256, iSamples, x).reinterpretAsInts();
            IntVector qPacked = ShortVector.fromArray(ShortVector.SPECIES_256, qSamples, x).reinterpretAsInts();
            FloatVector iEven = ((FloatVector)iPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_256, 0));
            FloatVector iOdd = ((FloatVector)iPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_256, 0));
            FloatVector qEven = ((FloatVector)qPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_256, 0));
            FloatVector qOdd = ((FloatVector)qPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_256, 0));
            FloatVector i0 = iEven.rearrange(ZIP_256_0, iOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector i1 = iEven.rearrange(ZIP_256_1, iOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector q0 = qEven.rearrange(ZIP_256_0, qOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector q1 = qEven.rearrange(ZIP_256_1, qOdd).mul(SAMPLE_TO_FLOAT);
            int outputOffset = 2 * x;
            i0.rearrange(ZIP_256_0, q0).intoArray(output, outputOffset);
            i0.rearrange(ZIP_256_1, q0).intoArray(output, outputOffset + floatLaneCount);
            i1.rearrange(ZIP_256_0, q1).intoArray(output, outputOffset + 2 * floatLaneCount);
            i1.rearrange(ZIP_256_1, q1).intoArray(output, outputOffset + 3 * floatLaneCount);
        }

        return vectorBound;
    }

    private static int convertInterleaved512(short[] iSamples, short[] qSamples, float[] output)
    {
        int vectorBound = ShortVector.SPECIES_512.loopBound(iSamples.length);
        int floatLaneCount = FloatVector.SPECIES_512.length();

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_512.length())
        {
            IntVector iPacked = ShortVector.fromArray(ShortVector.SPECIES_512, iSamples, x).reinterpretAsInts();
            IntVector qPacked = ShortVector.fromArray(ShortVector.SPECIES_512, qSamples, x).reinterpretAsInts();
            FloatVector iEven = ((FloatVector)iPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_512, 0));
            FloatVector iOdd = ((FloatVector)iPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_512, 0));
            FloatVector qEven = ((FloatVector)qPacked.lanewise(VectorOperators.LSHL, 16)
                .lanewise(VectorOperators.ASHR, 16).convertShape(VectorOperators.I2F, FloatVector.SPECIES_512, 0));
            FloatVector qOdd = ((FloatVector)qPacked.lanewise(VectorOperators.ASHR, 16)
                .convertShape(VectorOperators.I2F, FloatVector.SPECIES_512, 0));
            FloatVector i0 = iEven.rearrange(ZIP_512_0, iOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector i1 = iEven.rearrange(ZIP_512_1, iOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector q0 = qEven.rearrange(ZIP_512_0, qOdd).mul(SAMPLE_TO_FLOAT);
            FloatVector q1 = qEven.rearrange(ZIP_512_1, qOdd).mul(SAMPLE_TO_FLOAT);
            int outputOffset = 2 * x;
            i0.rearrange(ZIP_512_0, q0).intoArray(output, outputOffset);
            i0.rearrange(ZIP_512_1, q0).intoArray(output, outputOffset + floatLaneCount);
            i1.rearrange(ZIP_512_0, q1).intoArray(output, outputOffset + 2 * floatLaneCount);
            i1.rearrange(ZIP_512_1, q1).intoArray(output, outputOffset + 3 * floatLaneCount);
        }

        return vectorBound;
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
