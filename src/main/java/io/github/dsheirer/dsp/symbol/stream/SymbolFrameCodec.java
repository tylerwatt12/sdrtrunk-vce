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
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Compact little-endian binary codec for bounded symbol batches.
 *
 * <pre>
 *  0  u8[4] magic "SSYM"
 *  4  u16   version
 *  6  u16   header byte count
 *  8  u32   flags
 * 12  i64   processing-chain generation
 * 20  i64   sequence
 * 28  i64   monotonic timestamp, nanoseconds
 * 36  u32   symbol count
 * 40  u8    encoding (1 = float32 radians)
 * 41  u8[3] reserved, zero
 * 44  u32   payload byte count
 * 48         float32 phase values in radians
 * </pre>
 */
public final class SymbolFrameCodec
{
    public static final int VERSION = 1;
    public static final int HEADER_BYTE_COUNT = 48;
    public static final int MAXIMUM_SYMBOL_COUNT = 120;
    public static final int FLOAT32_ENCODING = 1;
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;
    private static final byte[] MAGIC = "SSYM".getBytes(StandardCharsets.US_ASCII);

    private SymbolFrameCodec()
    {
    }

    public static byte[] encode(SymbolFrame frame)
    {
        ByteBuffer encoded = encodeReadOnly(frame);
        byte[] copy = new byte[encoded.remaining()];
        encoded.get(copy);
        return copy;
    }

    public static ByteBuffer encodeReadOnly(SymbolFrame frame)
    {
        if(frame == null)
        {
            throw new IllegalArgumentException("Symbol frame cannot be null");
        }

        return frame.getEncodedVersionOne();
    }

    static byte[] encodeUncached(SymbolFrame frame)
    {
        int payloadBytes = Math.multiplyExact(frame.getSymbolCount(), Float.BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(Math.addExact(HEADER_BYTE_COUNT, payloadBytes)).order(BYTE_ORDER);
        buffer.put(MAGIC);
        buffer.putShort((short)VERSION);
        buffer.putShort((short)HEADER_BYTE_COUNT);
        buffer.putInt(frame.getFlags());
        buffer.putLong(frame.getGeneration());
        buffer.putLong(frame.getSequence());
        buffer.putLong(frame.getMonotonicTimestampNanos());
        buffer.putInt(frame.getSymbolCount());
        buffer.put((byte)FLOAT32_ENCODING);
        buffer.put((byte)0);
        buffer.put((byte)0);
        buffer.put((byte)0);
        buffer.putInt(payloadBytes);

        for(int x = 0; x < frame.getSymbolCount(); x++)
        {
            buffer.putFloat(frame.getSymbol(x));
        }

        return buffer.array();
    }

    public static SymbolFrame decode(byte[] encoded)
    {
        if(encoded == null)
        {
            throw new IllegalArgumentException("Encoded symbol frame cannot be null");
        }

        return decode(ByteBuffer.wrap(encoded));
    }

    public static SymbolFrame decode(ByteBuffer encoded)
    {
        if(encoded == null)
        {
            throw new IllegalArgumentException("Encoded symbol frame cannot be null");
        }

        ByteBuffer buffer = encoded.slice().order(BYTE_ORDER);

        if(buffer.remaining() < HEADER_BYTE_COUNT)
        {
            throw new IllegalArgumentException("Symbol frame is shorter than its header");
        }

        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);

        if(!Arrays.equals(MAGIC, magic))
        {
            throw new IllegalArgumentException("Invalid symbol frame magic");
        }

        int version = Short.toUnsignedInt(buffer.getShort());
        int headerBytes = Short.toUnsignedInt(buffer.getShort());

        if(version != VERSION || headerBytes != HEADER_BYTE_COUNT)
        {
            throw new IllegalArgumentException("Unsupported symbol frame version or header length");
        }

        int flags = buffer.getInt();
        long generation = buffer.getLong();
        long sequence = buffer.getLong();
        long monotonicTimestampNanos = buffer.getLong();
        int count = buffer.getInt();
        int encoding = Byte.toUnsignedInt(buffer.get());

        if(buffer.get() != 0 || buffer.get() != 0 || buffer.get() != 0)
        {
            throw new IllegalArgumentException("Reserved symbol header bytes must be zero");
        }

        int payloadBytes = buffer.getInt();

        if(count < 1 || count > MAXIMUM_SYMBOL_COUNT || encoding != FLOAT32_ENCODING ||
            payloadBytes != count * Float.BYTES || buffer.remaining() != payloadBytes)
        {
            throw new IllegalArgumentException("Invalid symbol payload description");
        }

        float[] symbols = new float[count];

        for(int x = 0; x < count; x++)
        {
            symbols[x] = buffer.getFloat();
        }

        return SymbolFrame.owned(flags, generation, sequence, monotonicTimestampNanos, symbols);
    }
}
