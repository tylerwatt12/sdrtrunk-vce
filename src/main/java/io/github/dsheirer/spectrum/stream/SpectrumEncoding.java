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

/**
 * Wire encodings for spectrum-bin payloads.
 *
 * <p>The first protocol version intentionally supports only 32-bit floating-point bins.  The wire identifier and
 * bytes-per-bin metadata leave room for a later quantized encoding without changing the frame envelope.</p>
 */
public enum SpectrumEncoding
{
    FLOAT32(1, Float.BYTES);

    private final int mWireIdentifier;
    private final int mBytesPerBin;

    SpectrumEncoding(int wireIdentifier, int bytesPerBin)
    {
        mWireIdentifier = wireIdentifier;
        mBytesPerBin = bytesPerBin;
    }

    /**
     * Stable unsigned-byte identifier used in the binary frame header.
     */
    public int getWireIdentifier()
    {
        return mWireIdentifier;
    }

    public int getBytesPerBin()
    {
        return mBytesPerBin;
    }

    public static SpectrumEncoding fromWireIdentifier(int wireIdentifier)
    {
        for(SpectrumEncoding encoding: values())
        {
            if(encoding.mWireIdentifier == wireIdentifier)
            {
                return encoding;
            }
        }

        throw new IllegalArgumentException("Unsupported spectrum encoding: " + wireIdentifier);
    }
}
