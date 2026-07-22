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

package io.github.dsheirer.dsp.symbol.stream;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Immutable bounded batch of demodulated symbol phase measurements.
 */
public final class SymbolFrame
{
    private static final float MAXIMUM_PHASE = (float)Math.PI;
    private final int mFlags;
    private final long mGeneration;
    private final long mSequence;
    private final long mMonotonicTimestampNanos;
    private final float[] mSymbols;
    private volatile byte[] mEncodedVersionOne;

    public SymbolFrame(int flags, long generation, long sequence, long monotonicTimestampNanos, float[] symbols)
    {
        this(flags, generation, sequence, monotonicTimestampNanos, symbols, true);
    }

    private SymbolFrame(int flags, long generation, long sequence, long monotonicTimestampNanos, float[] symbols,
                        boolean copy)
    {
        if(generation < 0 || sequence < 0)
        {
            throw new IllegalArgumentException("Symbol frame generation and sequence cannot be negative");
        }

        if(symbols == null || symbols.length < 1 || symbols.length > SymbolFrameCodec.MAXIMUM_SYMBOL_COUNT)
        {
            throw new IllegalArgumentException("Symbol count must be between 1 and " +
                SymbolFrameCodec.MAXIMUM_SYMBOL_COUNT);
        }

        for(float symbol: symbols)
        {
            if(!Float.isFinite(symbol) || symbol < -MAXIMUM_PHASE || symbol > MAXIMUM_PHASE)
            {
                throw new IllegalArgumentException("Symbol phase must be finite and in the range -PI to PI");
            }
        }

        mFlags = flags;
        mGeneration = generation;
        mSequence = sequence;
        mMonotonicTimestampNanos = monotonicTimestampNanos;
        mSymbols = copy ? Arrays.copyOf(symbols, symbols.length) : symbols;
    }

    /**
     * Takes ownership of a producer-created array that will not be modified after publication.
     */
    public static SymbolFrame owned(int flags, long generation, long sequence, long monotonicTimestampNanos,
                                    float[] symbols)
    {
        return new SymbolFrame(flags, generation, sequence, monotonicTimestampNanos, symbols, false);
    }

    public int getFlags()
    {
        return mFlags;
    }

    public long getGeneration()
    {
        return mGeneration;
    }

    public long getSequence()
    {
        return mSequence;
    }

    public long getMonotonicTimestampNanos()
    {
        return mMonotonicTimestampNanos;
    }

    public int getSymbolCount()
    {
        return mSymbols.length;
    }

    public float getSymbol(int index)
    {
        return mSymbols[index];
    }

    public float[] getSymbols()
    {
        return Arrays.copyOf(mSymbols, mSymbols.length);
    }

    public ByteBuffer getEncodedVersionOne()
    {
        return ByteBuffer.wrap(getOrCreateEncodedVersionOne()).asReadOnlyBuffer()
            .order(SymbolFrameCodec.BYTE_ORDER);
    }

    private byte[] getOrCreateEncodedVersionOne()
    {
        byte[] encoded = mEncodedVersionOne;

        if(encoded == null)
        {
            synchronized(this)
            {
                encoded = mEncodedVersionOne;

                if(encoded == null)
                {
                    encoded = SymbolFrameCodec.encodeUncached(this);
                    mEncodedVersionOne = encoded;
                }
            }
        }

        return encoded;
    }
}
