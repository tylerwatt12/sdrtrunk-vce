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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Native buffer factory for SDRPlay RSP tuners.
 *
 * The SDRPlay API automatically changes the length of sample buffers according to the sample rate.  This causes
 * problems for down-stream processing components that may be optimized for vector operations and depend on the
 * sample arrays being a power-of-2 length.  This class repackages the incoming sample stream into arrays of
 * power-of-2 length.
 */
public class RspNativeBufferFactory
{
    private short[] mIResidual = new short[0];
    private short[] mQResidual = new short[0];
    private long mResidualTimestamp = System.currentTimeMillis();
    private int mIncomingBufferLength = 0;
    private int mOptimalBufferLength = 128;
    private float mSamplesPerMillisecond;

    /**
     * Constructs an instance.
     * @param sampleRate of the tuner
     */
    public RspNativeBufferFactory(RspSampleRate sampleRate)
    {
        setSampleRate(sampleRate);
    }

    /**
     * Set or update the sample rate.
     * @param sampleRate to set.
     */
    public void setSampleRate(RspSampleRate sampleRate)
    {
        mSamplesPerMillisecond = sampleRate.getSamplesPerMillisecond();
    }

    /**
     * Repackages the samples into optimal length buffers and returns zero or more RSP native buffers.
     * @param i samples
     * @param q samples
     * @param timestamp of samples
     * @return zero or more repackaged RSP native buffers
     */
    public List<RspNativeBuffer> get(short[] i, short[] q, long timestamp)
    {
        updateBufferLength(i.length);
        int sampleCount = mIResidual.length + i.length;
        List<RspNativeBuffer> buffers = sampleCount < mOptimalBufferLength ?
            Collections.emptyList() : new ArrayList<>();
        int offset = 0;

        while(sampleCount - offset >= mOptimalBufferLength)
        {
            buffers.add(new RspNativeBuffer(copySamples(mIResidual, i, offset, mOptimalBufferLength),
                copySamples(mQResidual, q, offset, mOptimalBufferLength), mResidualTimestamp, mSamplesPerMillisecond));
            offset += mOptimalBufferLength;
            mResidualTimestamp += (long)(mOptimalBufferLength / mSamplesPerMillisecond);
        }

        mIResidual = copySamples(mIResidual, i, offset, sampleCount - offset);
        mQResidual = copySamples(mQResidual, q, offset, sampleCount - offset);

        if(offset > 0)
        {
            //Preserve the existing timestamp update only when at least one output buffer was emitted.
            mResidualTimestamp = timestamp;
        }

        return buffers;
    }

    /** Copies a range of the logical residual + incoming stream into an independently owned array. */
    private static short[] copySamples(short[] residual, short[] incoming, int offset, int length)
    {
        short[] samples = new short[length];
        int residualCount = Math.min(length, Math.max(0, residual.length - offset));
        System.arraycopy(residual, Math.min(offset, residual.length), samples, 0, residualCount);
        System.arraycopy(incoming, Math.max(0, offset - residual.length), samples, residualCount, length - residualCount);
        return samples;
    }

    /**
     * Updates the optimal native buffer length based on the incoming buffer size.  Optimal length is a power-of-2
     * value that is closest to the buffer length to minimize the quantity of residual samples from each arriving buffer.
     * @param length
     */
    private void updateBufferLength(int length)
    {
        if(mIncomingBufferLength != length)
        {
            int optimal = 128;

            while((optimal) < length)
            {
                optimal *= 2;
            }

            mOptimalBufferLength = optimal;
            mIncomingBufferLength = length;
        }
    }
}
