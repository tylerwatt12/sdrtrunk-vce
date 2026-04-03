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
import java.util.NoSuchElementException;

/**
 * Factory for creating native buffers from SDRconnect IQ data.
 * SDRconnect sends 16-bit signed integer I/Q samples in little-endian format.
 *
 * Samples are stored in interleaved form (IQIQ…) because the primary consumer — the polyphase
 * channelizer — always calls iteratorInterleaved().  Storing interleaved avoids a per-call
 * allocation in that hot path.  iterator() (split ComplexSamples) de-interleaves lazily on demand.
 */
public class SDRconnectNativeBufferFactory extends AbstractNativeBufferFactory
{
    // Scale factor to convert 16-bit signed to float (-1.0 to 1.0)
    private static final float SCALE = 1.0f / 32768.0f;

    @Override
    public INativeBuffer getBuffer(ByteBuffer buffer, long timestamp)
    {
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // 4 bytes per complex sample (2 for I, 2 for Q); store interleaved (IQIQ…)
        int sampleCount = buffer.remaining() / 4;
        float[] interleaved = new float[sampleCount * 2];

        for(int i = 0; i < interleaved.length; i += 2)
        {
            interleaved[i]     = buffer.getShort() * SCALE;
            interleaved[i + 1] = buffer.getShort() * SCALE;
        }

        return new SDRconnectNativeBuffer(interleaved, timestamp);
    }

    /**
     * Native buffer implementation for SDRconnect IQ data.
     * Internally holds a single interleaved float[] (IQIQ…) allocated once per packet.
     *
     * iteratorInterleaved() returns a view backed by this same array — no copy is made.
     * Callers MUST NOT mutate the samples() array of the returned InterleavedComplexSamples.
     * All current framework consumers (polyphase channelizer, wave recorder, spectrum display)
     * are read-only and satisfy this contract.
     */
    public static class SDRconnectNativeBuffer implements INativeBuffer
    {
        private final float[] mInterleaved;
        private final long mTimestamp;

        public SDRconnectNativeBuffer(float[] interleaved, long timestamp)
        {
            mInterleaved = interleaved;
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
                    if(!mHasNext)
                    {
                        throw new NoSuchElementException("No more complex samples available");
                    }

                    mHasNext = false;

                    // De-interleave into split I/Q arrays for consumers that require ComplexSamples
                    int count = mInterleaved.length / 2;
                    float[] i = new float[count];
                    float[] q = new float[count];

                    for(int idx = 0; idx < count; idx++)
                    {
                        i[idx] = mInterleaved[idx * 2];
                        q[idx] = mInterleaved[idx * 2 + 1];
                    }

                    return new ComplexSamples(i, q, mTimestamp);
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
                    if(!mHasNext)
                    {
                        throw new NoSuchElementException("No more interleaved complex samples available");
                    }

                    mHasNext = false;
                    // Return the pre-computed interleaved array directly — no allocation
                    return new InterleavedComplexSamples(mInterleaved, mTimestamp);
                }
            };
        }

        @Override
        public int sampleCount()
        {
            return mInterleaved.length / 2;
        }

        @Override
        public long getTimestamp()
        {
            return mTimestamp;
        }
    }
}
