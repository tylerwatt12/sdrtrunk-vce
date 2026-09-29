/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import java.util.Arrays;
import java.util.Objects;

/** Ordered packed-dibit packet delivered from a remote source to a bit-only P25 decoder. */
public record P25RemoteBitstreamPacket(byte[] dibits, long captureTimestamp, long sequence, boolean discontinuity)
{
    public P25RemoteBitstreamPacket
    {
        Objects.requireNonNull(dibits, "dibits cannot be null");

        if(dibits.length == 0 || dibits.length > RemoteWireMessages.MAXIMUM_DIBIT_BYTES ||
            captureTimestamp < 0L || sequence < 0L)
        {
            throw new IllegalArgumentException("Invalid remote bitstream packet");
        }

        dibits = Arrays.copyOf(dibits, dibits.length);
    }

    @Override
    public byte[] dibits()
    {
        return Arrays.copyOf(dibits, dibits.length);
    }
}
