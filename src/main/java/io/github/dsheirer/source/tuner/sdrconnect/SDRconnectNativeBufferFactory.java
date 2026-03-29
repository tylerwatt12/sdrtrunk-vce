/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.source.tuner.sdrconnect;

import io.github.dsheirer.buffer.AbstractNativeBufferFactory;
import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;

/**
 * Factory for creating native buffers from SDRconnect IQ data.
 * SDRconnect sends 16-bit signed integer I/Q samples in little-endian format.
 */
public class SDRconnectNativeBufferFactory extends AbstractNativeBufferFactory
{
    // Scale factor to convert 16-bit signed to float (-1.0 to 1.0)
    private static final float SCALE = 1.0f / 32768.0f;

    /**
     * Constructs an instance
     */
    public SDRconnectNativeBufferFactory()
    {
    }

    @Override
    public INativeBuffer getBuffer(ByteBuffer buffer, long timestamp)
    {
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Calculate number of complex samples (4 bytes per sample: 2 for I, 2 for Q)
        int sampleCount = buffer.remaining() / 4;

        // Convert to float arrays
        float[] iSamples = new float[sampleCount];
        float[] qSamples = new float[sampleCount];

        for(int i = 0; i < sampleCount; i++)
        {
            short iVal = buffer.getShort();
            short qVal = buffer.getShort();
            iSamples[i] = iVal * SCALE;
            qSamples[i] = qVal * SCALE;
        }

        return new SDRconnectNativeBuffer(iSamples, qSamples, timestamp);
    }

    /**
     * Native buffer implementation for SDRconnect IQ data
     */
    public static class SDRconnectNativeBuffer implements INativeBuffer
    {
        private final float[] mISamples;
        private final float[] mQSamples;
        private final long mTimestamp;

        public SDRconnectNativeBuffer(float[] iSamples, float[] qSamples, long timestamp)
        {
            mISamples = iSamples;
            mQSamples = qSamples;
            mTimestamp = timestamp;
        }

        @Override
        public Iterator<ComplexSamples> iterator()
        {
            return new Iterator<>()
            {
                private boolean mHasNext = true;

                @Override
                public boolean hasNext()
                {
                    return mHasNext;
                }

                @Override
                public ComplexSamples next()
                {
                    mHasNext = false;
                    return new ComplexSamples(mISamples, mQSamples, mTimestamp);
                }
            };
        }

        @Override
        public Iterator<InterleavedComplexSamples> iteratorInterleaved()
        {
            return new Iterator<>()
            {
                private boolean mHasNext = true;

                @Override
                public boolean hasNext()
                {
                    return mHasNext;
                }

                @Override
                public InterleavedComplexSamples next()
                {
                    mHasNext = false;

                    // Interleave the samples
                    float[] interleaved = new float[mISamples.length * 2];
                    for(int i = 0; i < mISamples.length; i++)
                    {
                        interleaved[i * 2] = mISamples[i];
                        interleaved[i * 2 + 1] = mQSamples[i];
                    }

                    return new InterleavedComplexSamples(interleaved, mTimestamp);
                }
            };
        }

        @Override
        public int sampleCount()
        {
            return mISamples.length;
        }

        @Override
        public long getTimestamp()
        {
            return mTimestamp;
        }
    }
}
