/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.source.tuner.sdrplay;

import io.github.dsheirer.buffer.AbstractNativeBuffer;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.util.Iterator;
import java.util.Objects;

/**
 * Native buffer implementation for RSP tuner I/Q sample buffers.
 *
 * Note: in testing with API v3.07, the daemon returns 2016 samples in each of the I and Q arrays.
 */
public class RspNativeBuffer extends AbstractNativeBuffer
{
    private final short[] mISamples;
    private final short[] mQSamples;
    private final IRspSampleConverter mSampleConverter;

    /**
     * Constructs an instance
     * @param i samples array
     * @param q samples array
     * @param timestamp for the first sample
     * @param samplesPerMillisecond used to calculate sub-buffer fragment timestamp offsets from the start of this buffer.
     */
    public RspNativeBuffer(short[] i, short[] q, long timestamp, float samplesPerMillisecond)
    {
        this(i, q, timestamp, samplesPerMillisecond, RspSampleConverterFactory.getConverter());
    }

    /**
     * Constructs an instance with an explicit converter for correctness testing.
     */
    RspNativeBuffer(short[] i, short[] q, long timestamp, float samplesPerMillisecond,
                    IRspSampleConverter sampleConverter)
    {
        super(timestamp, samplesPerMillisecond);
        RspSampleConverterFactory.validateInputs(i, q);
        mISamples = i;
        mQSamples = q;
        mSampleConverter = Objects.requireNonNull(sampleConverter, "Sample converter cannot be null");
    }

    /**
     * Iterator over samples that produces complex sample buffers.
     */
    @Override
    public Iterator<ComplexSamples> iterator()
    {
        return new SampleIterator();
    }

    /**
     * Iterator over samples that produces interleaved complex sample buffers
     */
    @Override
    public Iterator<InterleavedComplexSamples> iteratorInterleaved()
    {
        return new InterleavedSampleIterator();
    }

    @Override
    public int sampleCount()
    {
        return mISamples.length;
    }

    /**
     * Iterator providing (non-interleaved) complex sample buffers
     */
    private class SampleIterator implements Iterator<ComplexSamples>
    {
        private int mSamplePointer;

        @Override
        public boolean hasNext()
        {
            return mSamplePointer < mISamples.length;
        }

        @Override
        public ComplexSamples next()
        {
            float[] i = new float[mISamples.length];
            float[] q = new float[mISamples.length];
            mSampleConverter.convert(mISamples, mQSamples, i, q);

            mSamplePointer += mISamples.length;

            return new ComplexSamples(i, q, getTimestamp());
        }
    }

    /**
     * Interator providing interleaved sample buffers.
     */
    private class InterleavedSampleIterator implements Iterator<InterleavedComplexSamples>
    {
        private int mSamplePointer;

        @Override
        public boolean hasNext()
        {
            return mSamplePointer < mISamples.length;
        }

        @Override
        public InterleavedComplexSamples next()
        {
            float[] samples = new float[mISamples.length * 2];
            mSampleConverter.convertInterleaved(mISamples, mQSamples, samples);

            mSamplePointer += mISamples.length;

            return new InterleavedComplexSamples(samples, getTimestamp());
        }
    }
}
