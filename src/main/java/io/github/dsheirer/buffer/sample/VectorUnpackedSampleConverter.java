/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.buffer.sample;

import io.github.dsheirer.buffer.DcCorrectionManager;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import jdk.incubator.vector.ShortVector;

/**
 * Vector implementation of the unpacked 12-bit sample converter.
 */
public class VectorUnpackedSampleConverter implements ISampleConverter
{
    private final int mVectorBitSize;

    /**
     * Manages DC calculations and processing interval
     */
    private final DcCorrectionManager mDcCalculationManager = new DcCorrectionManager();

    /**
     * Constructs an instance using the preferred SIMD width.
     */
    public VectorUnpackedSampleConverter()
    {
        this(selectPreferredVectorBitSize(ShortVector.SPECIES_PREFERRED.vectorBitSize()));
    }

    /**
     * Package-private constructor for exercising each fixed-width kernel in focused tests.
     */
    VectorUnpackedSampleConverter(int vectorBitSize)
    {
        if(vectorBitSize != 64 && vectorBitSize != 128 && vectorBitSize != 256 && vectorBitSize != 512)
        {
            throw new IllegalArgumentException("Unsupported unpacked sample converter vector width: " +
                vectorBitSize);
        }

        mVectorBitSize = vectorBitSize;
    }

    private static int selectPreferredVectorBitSize(int preferredBitSize)
    {
        if(preferredBitSize >= 512)
        {
            return 512;
        }
        else if(preferredBitSize >= 256)
        {
            return 256;
        }
        else if(preferredBitSize >= 128)
        {
            return 128;
        }
        else if(preferredBitSize >= 64)
        {
            return 64;
        }

        throw new IllegalStateException("No supported unpacked sample converter vector width for preferred width " +
            preferredBitSize);
    }

    @Override
    public short[] convert(ByteBuffer buffer)
    {
        boolean shouldCalculateDc = mDcCalculationManager.shouldCalculateDc();
        short[] samples = new short[buffer.capacity() / 2];
        MemorySegment input = getInputSegment(buffer);
        int vectorBound = switch(mVectorBitSize)
        {
            case 64 -> convert64(input, samples);
            case 128 -> convert128(input, samples);
            case 256 -> convert256(input, samples);
            case 512 -> convert512(input, samples);
            default -> throw new IllegalStateException("Unsupported unpacked sample converter vector width: " +
                mVectorBitSize);
        };

        long dcAccumulator = 0;

        if(shouldCalculateDc)
        {
            //A short-lane ADD reduction can overflow above 32,767.  Accumulate the widened stored values so the
            //periodic DC estimate remains identical to the scalar converter for the full unsigned 12-bit range.
            for(int x = 0; x < vectorBound; x++)
            {
                dcAccumulator += samples[x];
            }
        }

        int rawPointer = vectorBound * 2;

        for(int samplesOffset = vectorBound; samplesOffset < samples.length; samplesOffset++)
        {
            byte b1 = buffer.get(rawPointer++);
            byte b2 = buffer.get(rawPointer++);
            short sample = (short)(((b2 & 0x0F) << 8) | (b1 & 0xFF));
            samples[samplesOffset] = sample;

            if(shouldCalculateDc)
            {
                dcAccumulator += sample;
            }
        }

        if(shouldCalculateDc)
        {
            float averageDcNow = ((float)dcAccumulator / (float)samples.length) - 2048.0f;
            averageDcNow *= SampleBufferIterator.SCALE_SIGNED_12_BIT_TO_FLOAT;
            mDcCalculationManager.adjust(averageDcNow);
        }

        return samples;
    }

    /**
     * Creates a segment beginning at absolute byte index zero without changing the source buffer's state.  The
     * converter has always used absolute reads and the buffer capacity for its output size, so a non-zero position is
     * intentionally ignored while the original limit continues to bound readable bytes.
     */
    private static MemorySegment getInputSegment(ByteBuffer buffer)
    {
        if(buffer.position() == 0)
        {
            return MemorySegment.ofBuffer(buffer);
        }

        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(0);
        return MemorySegment.ofBuffer(duplicate);
    }

    /**
     * These width-specific kernels keep the species constant at the hot call site so Java 25 can intrinsify the
     * contiguous little-endian loads instead of materializing vector values on the heap.
     */
    private static int convert64(MemorySegment input, short[] samples)
    {
        int vectorBound = ShortVector.SPECIES_64.loopBound(samples.length);

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_64.length())
        {
            ShortVector.fromMemorySegment(ShortVector.SPECIES_64, input, (long)x * Short.BYTES,
                    ByteOrder.LITTLE_ENDIAN)
                .and((short)0x0FFF)
                .intoArray(samples, x);
        }

        return vectorBound;
    }

    private static int convert128(MemorySegment input, short[] samples)
    {
        int vectorBound = ShortVector.SPECIES_128.loopBound(samples.length);

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_128.length())
        {
            ShortVector.fromMemorySegment(ShortVector.SPECIES_128, input, (long)x * Short.BYTES,
                    ByteOrder.LITTLE_ENDIAN)
                .and((short)0x0FFF)
                .intoArray(samples, x);
        }

        return vectorBound;
    }

    private static int convert256(MemorySegment input, short[] samples)
    {
        int vectorBound = ShortVector.SPECIES_256.loopBound(samples.length);

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_256.length())
        {
            ShortVector.fromMemorySegment(ShortVector.SPECIES_256, input, (long)x * Short.BYTES,
                    ByteOrder.LITTLE_ENDIAN)
                .and((short)0x0FFF)
                .intoArray(samples, x);
        }

        return vectorBound;
    }

    private static int convert512(MemorySegment input, short[] samples)
    {
        int vectorBound = ShortVector.SPECIES_512.loopBound(samples.length);

        for(int x = 0; x < vectorBound; x += ShortVector.SPECIES_512.length())
        {
            ShortVector.fromMemorySegment(ShortVector.SPECIES_512, input, (long)x * Short.BYTES,
                    ByteOrder.LITTLE_ENDIAN)
                .and((short)0x0FFF)
                .intoArray(samples, x);
        }

        return vectorBound;
    }

    @Override
    public float getAverageDc()
    {
        return mDcCalculationManager.getAverageDc();
    }
}
