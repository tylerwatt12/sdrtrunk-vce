/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.spectrum.stream;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable transport-neutral spectrum frame.
 *
 * <p>The monotonic timestamp uses the same semantics as {@link System#nanoTime()}: its origin is intentionally opaque
 * and only differences between timestamps from one process are meaningful.  The capture timestamp is Unix epoch
 * nanoseconds and is valid only when {@link #FLAG_CAPTURE_TIMESTAMP_VALID} is set; otherwise it must be zero.</p>
 */
public final class SpectrumFrame
{
    public static final int FLAG_CAPTURE_TIMESTAMP_VALID = 1;
    public static final int FLAG_SYNTHETIC = 1 << 1;

    private final int mFlags;
    private final long mTargetGeneration;
    private final long mSequence;
    private final long mMonotonicTimestampNanos;
    private final long mCaptureTimestampEpochNanos;
    private final long mCenterFrequencyHz;
    private final long mSampleRateHz;
    private final SpectrumEncoding mEncoding;
    private final float mQuantizationScale;
    private final float mQuantizationOffset;
    private final float[] mBins;
    private volatile byte[] mEncodedVersionOne;

    public SpectrumFrame(int flags, long targetGeneration, long sequence, long monotonicTimestampNanos,
                         long captureTimestampEpochNanos, long centerFrequencyHz, long sampleRateHz,
                         SpectrumEncoding encoding, float quantizationScale, float quantizationOffset, float[] bins)
    {
        this(flags, targetGeneration, sequence, monotonicTimestampNanos, captureTimestampEpochNanos,
            centerFrequencyHz, sampleRateHz, encoding, quantizationScale, quantizationOffset, bins, true);
    }

    private SpectrumFrame(int flags, long targetGeneration, long sequence, long monotonicTimestampNanos,
                          long captureTimestampEpochNanos, long centerFrequencyHz, long sampleRateHz,
                          SpectrumEncoding encoding, float quantizationScale, float quantizationOffset, float[] bins,
                          boolean copyBins)
    {
        if(targetGeneration < 0)
        {
            throw new IllegalArgumentException("Target generation cannot be negative");
        }

        if(sequence < 0)
        {
            throw new IllegalArgumentException("Sequence cannot be negative");
        }

        boolean captureTimestampValid = (flags & FLAG_CAPTURE_TIMESTAMP_VALID) != 0;

        if(captureTimestampValid && captureTimestampEpochNanos <= 0)
        {
            throw new IllegalArgumentException("A valid capture timestamp must contain positive Unix epoch nanoseconds");
        }

        if(!captureTimestampValid && captureTimestampEpochNanos != 0)
        {
            throw new IllegalArgumentException("Capture timestamp must be zero when its valid flag is not set");
        }

        if(centerFrequencyHz < 0)
        {
            throw new IllegalArgumentException("Center frequency cannot be negative");
        }

        if(sampleRateHz <= 0)
        {
            throw new IllegalArgumentException("Sample rate must be positive");
        }

        if(!Float.isFinite(quantizationScale) || quantizationScale <= 0.0f)
        {
            throw new IllegalArgumentException("Quantization scale must be finite and positive");
        }

        if(!Float.isFinite(quantizationOffset))
        {
            throw new IllegalArgumentException("Quantization offset must be finite");
        }

        mEncoding = Objects.requireNonNull(encoding, "Spectrum encoding cannot be null");

        if(mEncoding == SpectrumEncoding.FLOAT32 &&
            (Float.compare(quantizationScale, 1.0f) != 0 || Float.compare(quantizationOffset, 0.0f) != 0))
        {
            throw new IllegalArgumentException("FLOAT32 frames use a scale of 1.0 and offset of 0.0");
        }

        Objects.requireNonNull(bins, "Spectrum bins cannot be null");

        if(bins.length == 0 || bins.length > SpectrumFrameCodec.MAXIMUM_BIN_COUNT)
        {
            throw new IllegalArgumentException("Spectrum bin count must be between 1 and " +
                SpectrumFrameCodec.MAXIMUM_BIN_COUNT);
        }

        mFlags = flags;
        mTargetGeneration = targetGeneration;
        mSequence = sequence;
        mMonotonicTimestampNanos = monotonicTimestampNanos;
        mCaptureTimestampEpochNanos = captureTimestampEpochNanos;
        mCenterFrequencyHz = centerFrequencyHz;
        mSampleRateHz = sampleRateHz;
        mQuantizationScale = quantizationScale;
        mQuantizationOffset = quantizationOffset;
        mBins = copyBins ? Arrays.copyOf(bins, bins.length) : bins;
    }

    public static SpectrumFrame float32(int flags, long targetGeneration, long sequence,
                                        long monotonicTimestampNanos, long captureTimestampEpochNanos,
                                        long centerFrequencyHz, long sampleRateHz, float[] bins)
    {
        return new SpectrumFrame(flags, targetGeneration, sequence, monotonicTimestampNanos,
            captureTimestampEpochNanos, centerFrequencyHz, sampleRateHz, SpectrumEncoding.FLOAT32, 1.0f, 0.0f, bins);
    }

    /**
     * Takes ownership of a newly allocated bin array.  Package-local producers use this only when no caller retains
     * or mutates that array after publication, avoiding a second high-rate payload copy.
     */
    static SpectrumFrame float32Owned(int flags, long targetGeneration, long sequence,
                                      long monotonicTimestampNanos, long captureTimestampEpochNanos,
                                      long centerFrequencyHz, long sampleRateHz, float[] bins)
    {
        return new SpectrumFrame(flags, targetGeneration, sequence, monotonicTimestampNanos,
            captureTimestampEpochNanos, centerFrequencyHz, sampleRateHz, SpectrumEncoding.FLOAT32, 1.0f, 0.0f, bins,
            false);
    }

    public int getFlags()
    {
        return mFlags;
    }

    public long getTargetGeneration()
    {
        return mTargetGeneration;
    }

    public long getSequence()
    {
        return mSequence;
    }

    public long getMonotonicTimestampNanos()
    {
        return mMonotonicTimestampNanos;
    }

    public long getCaptureTimestampEpochNanos()
    {
        return mCaptureTimestampEpochNanos;
    }

    public long getCenterFrequencyHz()
    {
        return mCenterFrequencyHz;
    }

    public long getSampleRateHz()
    {
        return mSampleRateHz;
    }

    public int getBinCount()
    {
        return mBins.length;
    }

    public SpectrumEncoding getEncoding()
    {
        return mEncoding;
    }

    public float getQuantizationScale()
    {
        return mQuantizationScale;
    }

    public float getQuantizationOffset()
    {
        return mQuantizationOffset;
    }

    public float getBin(int index)
    {
        return mBins[index];
    }

    /**
     * Returns a defensive copy of the spectrum bins.
     */
    public float[] getBins()
    {
        return Arrays.copyOf(mBins, mBins.length);
    }

    void copyBinsTo(float[] destination)
    {
        System.arraycopy(mBins, 0, destination, 0, mBins.length);
    }

    /**
     * Returns a new read-only view over this frame's shared version-one wire representation.  Encoding is performed
     * lazily on a transport thread, at most once for the frame, so publishing a frame never serializes data and ten
     * viewers do not create ten payload arrays.
     */
    public ByteBuffer getEncodedVersionOne()
    {
        return ByteBuffer.wrap(getOrCreateEncodedVersionOneBytes()).asReadOnlyBuffer()
            .order(SpectrumFrameCodec.BYTE_ORDER);
    }

    byte[] getOrCreateEncodedVersionOneBytes()
    {
        byte[] encoded = mEncodedVersionOne;

        if(encoded == null)
        {
            synchronized(this)
            {
                encoded = mEncodedVersionOne;

                if(encoded == null)
                {
                    encoded = SpectrumFrameCodec.encodeUncached(this);
                    mEncodedVersionOne = encoded;
                }
            }
        }

        return encoded;
    }
}
