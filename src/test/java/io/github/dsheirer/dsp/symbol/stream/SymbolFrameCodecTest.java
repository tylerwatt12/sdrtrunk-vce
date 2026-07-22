/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.dsp.symbol.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SymbolFrameCodecTest
{
    @Test
    void returnsIndependentReadOnlyViewsOfCachedPayload()
    {
        SymbolFrame frame = testFrame(1, new float[]{-1.25f, 0.0f, 2.5f});
        ByteBuffer first = SymbolFrameCodec.encodeReadOnly(frame);
        ByteBuffer second = SymbolFrameCodec.encodeReadOnly(frame);

        assertTrue(first.isReadOnly());
        assertTrue(second.isReadOnly());
        assertEquals(first, second);
        assertThrows(ReadOnlyBufferException.class, () -> first.put(0, (byte)0));

        first.get();
        assertEquals(0, second.position());
        assertEquals(SymbolFrameCodec.HEADER_BYTE_COUNT + 3 * Float.BYTES, second.remaining());
    }

    @Test
    void roundTripsFloat32SymbolsAndPreservesHeaderFields()
    {
        SymbolFrame original = testFrame(42, new float[]{-3.0f, -0.25f, 0.0f, 3.0f});
        byte[] encoded = SymbolFrameCodec.encode(original);
        SymbolFrame decoded = SymbolFrameCodec.decode(encoded);

        assertEquals(SymbolFrameCodec.HEADER_BYTE_COUNT + 4 * Float.BYTES, encoded.length);
        assertEquals(original.getFlags(), decoded.getFlags());
        assertEquals(original.getGeneration(), decoded.getGeneration());
        assertEquals(original.getSequence(), decoded.getSequence());
        assertEquals(original.getMonotonicTimestampNanos(), decoded.getMonotonicTimestampNanos());
        assertArrayEquals(original.getSymbols(), decoded.getSymbols());
    }

    @Test
    void versionOneHeaderUsesNormativeOffsetsAndLittleEndianNumbers()
    {
        SymbolFrame frame = testFrame(42, new float[]{-1.5f, 2.25f});
        byte[] encoded = SymbolFrameCodec.encode(frame);
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(SymbolFrameCodec.BYTE_ORDER);

        assertArrayEquals(new byte[]{'S', 'S', 'Y', 'M'}, Arrays.copyOf(encoded, 4));
        assertEquals(SymbolFrameCodec.VERSION, Short.toUnsignedInt(buffer.getShort(4)));
        assertEquals(SymbolFrameCodec.HEADER_BYTE_COUNT, Short.toUnsignedInt(buffer.getShort(6)));
        assertEquals(frame.getFlags(), buffer.getInt(8));
        assertEquals(frame.getGeneration(), buffer.getLong(12));
        assertEquals(frame.getSequence(), buffer.getLong(20));
        assertEquals(frame.getMonotonicTimestampNanos(), buffer.getLong(28));
        assertEquals(frame.getSymbolCount(), buffer.getInt(36));
        assertEquals(SymbolFrameCodec.FLOAT32_ENCODING, Byte.toUnsignedInt(buffer.get(40)));
        assertEquals(0, encoded[41]);
        assertEquals(0, encoded[42]);
        assertEquals(0, encoded[43]);
        assertEquals(frame.getSymbolCount() * Float.BYTES, buffer.getInt(44));
        assertEquals(frame.getSymbol(0), buffer.getFloat(SymbolFrameCodec.HEADER_BYTE_COUNT));
    }

    @Test
    void rejectsHeaderAndPayloadInvariantViolations()
    {
        byte[] badMagic = encodedSingleSymbol();
        badMagic[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> SymbolFrameCodec.decode(badMagic));

        byte[] badVersion = encodedSingleSymbol();
        ByteBuffer.wrap(badVersion).order(SymbolFrameCodec.BYTE_ORDER).putShort(4, (short)2);
        assertThrows(IllegalArgumentException.class, () -> SymbolFrameCodec.decode(badVersion));

        byte[] badHeaderLength = encodedSingleSymbol();
        ByteBuffer.wrap(badHeaderLength).order(SymbolFrameCodec.BYTE_ORDER).putShort(6, (short)44);
        assertThrows(IllegalArgumentException.class, () -> SymbolFrameCodec.decode(badHeaderLength));

        byte[] badReservedByte = encodedSingleSymbol();
        badReservedByte[41] = 1;
        assertThrows(IllegalArgumentException.class, () -> SymbolFrameCodec.decode(badReservedByte));

        byte[] badCount = encodedSingleSymbol();
        ByteBuffer.wrap(badCount).order(SymbolFrameCodec.BYTE_ORDER).putInt(36, 0);
        assertThrows(IllegalArgumentException.class, () -> SymbolFrameCodec.decode(badCount));

        byte[] badEncoding = encodedSingleSymbol();
        badEncoding[40] = 2;
        assertThrows(IllegalArgumentException.class, () -> SymbolFrameCodec.decode(badEncoding));

        byte[] badPayloadLength = encodedSingleSymbol();
        ByteBuffer.wrap(badPayloadLength).order(SymbolFrameCodec.BYTE_ORDER).putInt(44, 8);
        assertThrows(IllegalArgumentException.class, () -> SymbolFrameCodec.decode(badPayloadLength));

        byte[] nonFinitePayload = encodedSingleSymbol();
        ByteBuffer.wrap(nonFinitePayload).order(SymbolFrameCodec.BYTE_ORDER)
            .putFloat(SymbolFrameCodec.HEADER_BYTE_COUNT, Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> SymbolFrameCodec.decode(nonFinitePayload));

        byte[] trailingByte = Arrays.copyOf(encodedSingleSymbol(), SymbolFrameCodec.HEADER_BYTE_COUNT + Float.BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> SymbolFrameCodec.decode(trailingByte));
    }

    @Test
    void rejectsInvalidSymbolFrameValues()
    {
        assertThrows(IllegalArgumentException.class, () -> new SymbolFrame(0, -1, 0, 0, new float[]{0.0f}));
        assertThrows(IllegalArgumentException.class, () -> new SymbolFrame(0, 0, -1, 0, new float[]{0.0f}));
        assertThrows(IllegalArgumentException.class, () -> new SymbolFrame(0, 0, 0, 0, new float[0]));
        assertThrows(IllegalArgumentException.class, () -> new SymbolFrame(0, 0, 0, 0,
            new float[SymbolFrameCodec.MAXIMUM_SYMBOL_COUNT + 1]));
        assertThrows(IllegalArgumentException.class, () -> new SymbolFrame(0, 0, 0, 0,
            new float[]{Float.NaN}));
        assertThrows(IllegalArgumentException.class, () -> new SymbolFrame(0, 0, 0, 0,
            new float[]{Math.nextUp((float)Math.PI)}));
    }

    @Test
    void decodingByteBufferDoesNotChangeCallerPosition()
    {
        byte[] encoded = SymbolFrameCodec.encode(testFrame(9, new float[]{-1.0f}));
        ByteBuffer containingBuffer = ByteBuffer.allocate(encoded.length + 2);
        containingBuffer.put((byte)0x11).put((byte)0x22).put(encoded).flip().position(2);

        SymbolFrame decoded = SymbolFrameCodec.decode(containingBuffer);

        assertEquals(2, containingBuffer.position());
        assertEquals(9, decoded.getSequence());
    }

    private static byte[] encodedSingleSymbol()
    {
        return SymbolFrameCodec.encode(testFrame(1, new float[]{0.5f}));
    }

    private static SymbolFrame testFrame(long sequence, float[] symbols)
    {
        return new SymbolFrame(0x21, 7, sequence, -123_456_789L, symbols);
    }
}
