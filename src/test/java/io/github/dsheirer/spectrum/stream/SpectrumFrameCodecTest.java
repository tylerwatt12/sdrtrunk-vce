/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.spectrum.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class SpectrumFrameCodecTest
{
    @Test
    void sharesOneImmutableEncodedPayloadAcrossViewers()
    {
        SpectrumFrame frame = testFrame(1, new float[]{-100.0f, -90.0f});
        ByteBuffer first = SpectrumFrameCodec.encodeReadOnly(frame);
        ByteBuffer second = SpectrumFrameCodec.encodeReadOnly(frame);

        assertSame(frame.getOrCreateEncodedVersionOneBytes(), frame.getOrCreateEncodedVersionOneBytes());
        assertTrue(first.isReadOnly());
        assertTrue(second.isReadOnly());
        assertEquals(first, second);
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> first.put(0, (byte)0));
        first.get();
        assertEquals(0, second.position());
    }

    @Test
    void roundTripsFloat32FrameAndPreservesHeaderFields()
    {
        SpectrumFrame original = testFrame(42, new float[]{-121.25f, -90.0f, -42.5f});
        byte[] encoded = SpectrumFrameCodec.encode(original);
        SpectrumFrame decoded = SpectrumFrameCodec.decode(encoded);

        assertEquals(SpectrumFrameCodec.HEADER_BYTE_COUNT + 3 * Float.BYTES, encoded.length);
        assertEquals(original.getFlags(), decoded.getFlags());
        assertEquals(original.getTargetGeneration(), decoded.getTargetGeneration());
        assertEquals(original.getSequence(), decoded.getSequence());
        assertEquals(original.getMonotonicTimestampNanos(), decoded.getMonotonicTimestampNanos());
        assertEquals(original.getCaptureTimestampEpochNanos(), decoded.getCaptureTimestampEpochNanos());
        assertEquals(original.getCenterFrequencyHz(), decoded.getCenterFrequencyHz());
        assertEquals(original.getSampleRateHz(), decoded.getSampleRateHz());
        assertEquals(SpectrumEncoding.FLOAT32, decoded.getEncoding());
        assertEquals(1.0f, decoded.getQuantizationScale());
        assertEquals(0.0f, decoded.getQuantizationOffset());
        assertArrayEquals(original.getBins(), decoded.getBins());
    }

    @Test
    void versionOneHeaderUsesNormativeOffsetsAndLittleEndianNumbers()
    {
        SpectrumFrame frame = testFrame(42, new float[]{-100.5f, -80.25f});
        byte[] encoded = SpectrumFrameCodec.encode(frame);
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(SpectrumFrameCodec.BYTE_ORDER);

        assertArrayEquals(new byte[]{'S', 'F', 'F', 'T'}, new byte[]{encoded[0], encoded[1], encoded[2], encoded[3]});
        assertEquals(SpectrumFrameCodec.VERSION,
            Short.toUnsignedInt(buffer.getShort(SpectrumFrameCodec.OFFSET_VERSION)));
        assertEquals(SpectrumFrameCodec.HEADER_BYTE_COUNT,
            Short.toUnsignedInt(buffer.getShort(SpectrumFrameCodec.OFFSET_HEADER_BYTE_COUNT)));
        assertEquals(frame.getFlags(), buffer.getInt(SpectrumFrameCodec.OFFSET_FLAGS));
        assertEquals(frame.getTargetGeneration(), buffer.getLong(SpectrumFrameCodec.OFFSET_TARGET_GENERATION));
        assertEquals(frame.getSequence(), buffer.getLong(SpectrumFrameCodec.OFFSET_SEQUENCE));
        assertEquals(frame.getMonotonicTimestampNanos(),
            buffer.getLong(SpectrumFrameCodec.OFFSET_MONOTONIC_TIMESTAMP));
        assertEquals(frame.getCaptureTimestampEpochNanos(),
            buffer.getLong(SpectrumFrameCodec.OFFSET_CAPTURE_TIMESTAMP));
        assertEquals(frame.getCenterFrequencyHz(), buffer.getLong(SpectrumFrameCodec.OFFSET_CENTER_FREQUENCY));
        assertEquals(frame.getSampleRateHz(), buffer.getLong(SpectrumFrameCodec.OFFSET_SAMPLE_RATE));
        assertEquals(frame.getBinCount(), buffer.getInt(SpectrumFrameCodec.OFFSET_BIN_COUNT));
        assertEquals(SpectrumEncoding.FLOAT32.getWireIdentifier(),
            Byte.toUnsignedInt(buffer.get(SpectrumFrameCodec.OFFSET_ENCODING)));
        assertEquals(0, encoded[65]);
        assertEquals(0, encoded[66]);
        assertEquals(0, encoded[67]);
        assertEquals(1.0f, buffer.getFloat(SpectrumFrameCodec.OFFSET_QUANTIZATION_SCALE));
        assertEquals(0.0f, buffer.getFloat(SpectrumFrameCodec.OFFSET_QUANTIZATION_OFFSET));
        assertEquals(frame.getBinCount() * Float.BYTES,
            buffer.getInt(SpectrumFrameCodec.OFFSET_PAYLOAD_BYTE_COUNT));
        assertEquals(frame.getBin(0), buffer.getFloat(SpectrumFrameCodec.HEADER_BYTE_COUNT));
    }

    @Test
    void rejectsHeaderAndPayloadInvariantViolations()
    {
        byte[] badMagic = SpectrumFrameCodec.encode(testFrame(1, new float[]{-100.0f}));
        badMagic[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> SpectrumFrameCodec.decode(badMagic));

        byte[] badReservedByte = SpectrumFrameCodec.encode(testFrame(2, new float[]{-100.0f}));
        badReservedByte[65] = 1;
        assertThrows(IllegalArgumentException.class, () -> SpectrumFrameCodec.decode(badReservedByte));

        byte[] badPayloadLength = SpectrumFrameCodec.encode(testFrame(3, new float[]{-100.0f}));
        ByteBuffer.wrap(badPayloadLength).order(SpectrumFrameCodec.BYTE_ORDER)
            .putInt(SpectrumFrameCodec.OFFSET_PAYLOAD_BYTE_COUNT, 8);
        assertThrows(IllegalArgumentException.class, () -> SpectrumFrameCodec.decode(badPayloadLength));
    }

    @Test
    void decodingByteBufferDoesNotChangeCallerPosition()
    {
        byte[] encoded = SpectrumFrameCodec.encode(testFrame(9, new float[]{-101.0f}));
        ByteBuffer containingBuffer = ByteBuffer.allocate(encoded.length + 2);
        containingBuffer.put((byte)0x11).put((byte)0x22).put(encoded).flip().position(2);

        SpectrumFrame decoded = SpectrumFrameCodec.decode(containingBuffer);

        assertEquals(2, containingBuffer.position());
        assertEquals(9, decoded.getSequence());
    }

    private static SpectrumFrame testFrame(long sequence, float[] bins)
    {
        return SpectrumFrame.float32(
            SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID | SpectrumFrame.FLAG_SYNTHETIC,
            7, sequence, -123_456_789L, 1_770_000_000_123_000_000L,
            851_012_500L, 10_000_000L, bins);
    }
}
