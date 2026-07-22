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
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Version-two binary spectrum-frame codec.
 *
 * <p>The four-byte ASCII magic is {@code SFFT}.  Every numeric header field and every payload value is little-endian.
 * The fixed 96-byte header layout is:</p>
 *
 * <pre>
 *  0  u8[4] magic
 *  4  u16   version
 *  6  u16   header byte count
 *  8  u32   flags
 * 12  i64   target generation
 * 20  i64   sequence
 * 28  i64   monotonic timestamp, nanoseconds with opaque process-local origin
 * 36  i64   capture timestamp, Unix epoch nanoseconds (zero unless capture-valid flag is set)
 * 44  i64   center frequency, Hz
 * 52  i64   sample rate, Hz
 * 60  i64   view revision
 * 68  u32   full FFT size
 * 72  u32   first transmitted FFT bin
 * 76  u32   transmitted bin count
 * 80  u8    encoding identifier
 * 81  u8[3] reserved, zero
 * 84  f32   quantization scale
 * 88  f32   quantization offset
 * 92  u32   payload byte count
 * 96         payload
 * </pre>
 *
 * <p>A quantized payload will decode a numeric wire value as {@code value * scale + offset}.  Version one currently
 * emits only {@link SpectrumEncoding#FLOAT32}, whose required scale and offset are 1.0 and 0.0.  A bin's frequency
 * is {@code center - sampleRate / 2 + (firstBin + binIndex) * sampleRate / fftSize}.</p>
 */
public final class SpectrumFrameCodec
{
    public static final int VERSION = 2;
    public static final int HEADER_BYTE_COUNT = 96;
    public static final int MAXIMUM_BIN_COUNT = 1_048_576;
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    public static final int OFFSET_VERSION = 4;
    public static final int OFFSET_HEADER_BYTE_COUNT = 6;
    public static final int OFFSET_FLAGS = 8;
    public static final int OFFSET_TARGET_GENERATION = 12;
    public static final int OFFSET_SEQUENCE = 20;
    public static final int OFFSET_MONOTONIC_TIMESTAMP = 28;
    public static final int OFFSET_CAPTURE_TIMESTAMP = 36;
    public static final int OFFSET_CENTER_FREQUENCY = 44;
    public static final int OFFSET_SAMPLE_RATE = 52;
    public static final int OFFSET_VIEW_REVISION = 60;
    public static final int OFFSET_FFT_SIZE = 68;
    public static final int OFFSET_FIRST_BIN = 72;
    public static final int OFFSET_BIN_COUNT = 76;
    public static final int OFFSET_ENCODING = 80;
    public static final int OFFSET_QUANTIZATION_SCALE = 84;
    public static final int OFFSET_QUANTIZATION_OFFSET = 88;
    public static final int OFFSET_PAYLOAD_BYTE_COUNT = 92;

    private static final byte[] MAGIC = "SFFT".getBytes(StandardCharsets.US_ASCII);

    private SpectrumFrameCodec()
    {
    }

    public static byte[] encode(SpectrumFrame frame)
    {
        if(frame == null)
        {
            throw new IllegalArgumentException("Spectrum frame cannot be null");
        }

        ByteBuffer shared = frame.getEncodedVersionTwo();
        byte[] encoded = new byte[shared.remaining()];
        shared.get(encoded);
        return encoded;
    }

    /**
     * Returns an independently positioned, read-only view of the frame's shared wire payload.  Long-lived transports
     * should use this method instead of {@link #encode(SpectrumFrame)} to avoid allocating one payload per viewer.
     */
    public static ByteBuffer encodeReadOnly(SpectrumFrame frame)
    {
        if(frame == null)
        {
            throw new IllegalArgumentException("Spectrum frame cannot be null");
        }

        return frame.getEncodedVersionTwo();
    }

    /**
     * Creates the immutable version-two representation cached by {@link SpectrumFrame}.  This method is package
     * private so transports cannot accidentally bypass the shared frame cache.
     */
    static byte[] encodeUncached(SpectrumFrame frame)
    {
        if(frame == null)
        {
            throw new IllegalArgumentException("Spectrum frame cannot be null");
        }

        SpectrumEncoding encoding = frame.getEncoding();

        if(encoding != SpectrumEncoding.FLOAT32)
        {
            throw new IllegalArgumentException("Encoding is not implemented: " + encoding);
        }

        int payloadByteCount = payloadByteCount(frame.getBinCount(), encoding);
        ByteBuffer buffer = ByteBuffer.allocate(Math.addExact(HEADER_BYTE_COUNT, payloadByteCount)).order(BYTE_ORDER);
        buffer.put(MAGIC);
        buffer.putShort((short)VERSION);
        buffer.putShort((short)HEADER_BYTE_COUNT);
        buffer.putInt(frame.getFlags());
        buffer.putLong(frame.getTargetGeneration());
        buffer.putLong(frame.getSequence());
        buffer.putLong(frame.getMonotonicTimestampNanos());
        buffer.putLong(frame.getCaptureTimestampEpochNanos());
        buffer.putLong(frame.getCenterFrequencyHz());
        buffer.putLong(frame.getSampleRateHz());
        buffer.putLong(frame.getViewRevision());
        buffer.putInt(frame.getFftSize());
        buffer.putInt(frame.getFirstBin());
        buffer.putInt(frame.getBinCount());
        buffer.put((byte)encoding.getWireIdentifier());
        buffer.put((byte)0);
        buffer.put((byte)0);
        buffer.put((byte)0);
        buffer.putFloat(frame.getQuantizationScale());
        buffer.putFloat(frame.getQuantizationOffset());
        buffer.putInt(payloadByteCount);

        for(int x = 0; x < frame.getBinCount(); x++)
        {
            buffer.putFloat(frame.getBin(x));
        }

        return buffer.array();
    }

    public static SpectrumFrame decode(byte[] encodedFrame)
    {
        if(encodedFrame == null)
        {
            throw new IllegalArgumentException("Encoded spectrum frame cannot be null");
        }

        return decode(ByteBuffer.wrap(encodedFrame));
    }

    /**
     * Decodes one complete frame from the buffer's remaining bytes without changing the caller's position.
     */
    public static SpectrumFrame decode(ByteBuffer encodedFrame)
    {
        if(encodedFrame == null)
        {
            throw new IllegalArgumentException("Encoded spectrum frame cannot be null");
        }

        ByteBuffer buffer = encodedFrame.slice().order(BYTE_ORDER);

        if(buffer.remaining() < HEADER_BYTE_COUNT)
        {
            throw new IllegalArgumentException("Spectrum frame is shorter than the version-two header");
        }

        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);

        if(!Arrays.equals(MAGIC, magic))
        {
            throw new IllegalArgumentException("Invalid spectrum frame magic");
        }

        int version = Short.toUnsignedInt(buffer.getShort());

        if(version != VERSION)
        {
            throw new IllegalArgumentException("Unsupported spectrum frame version: " + version);
        }

        int headerByteCount = Short.toUnsignedInt(buffer.getShort());

        if(headerByteCount != HEADER_BYTE_COUNT)
        {
            throw new IllegalArgumentException("Invalid version-two spectrum header length: " + headerByteCount);
        }

        int flags = buffer.getInt();
        long targetGeneration = buffer.getLong();
        long sequence = buffer.getLong();
        long monotonicTimestampNanos = buffer.getLong();
        long captureTimestampEpochNanos = buffer.getLong();
        long centerFrequencyHz = buffer.getLong();
        long sampleRateHz = buffer.getLong();
        long viewRevision = buffer.getLong();
        int fftSize = buffer.getInt();
        int firstBin = buffer.getInt();
        int binCount = buffer.getInt();

        if(binCount <= 0 || binCount > MAXIMUM_BIN_COUNT)
        {
            throw new IllegalArgumentException("Invalid spectrum bin count: " + Integer.toUnsignedLong(binCount));
        }

        SpectrumEncoding encoding = SpectrumEncoding.fromWireIdentifier(Byte.toUnsignedInt(buffer.get()));

        if(buffer.get() != 0 || buffer.get() != 0 || buffer.get() != 0)
        {
            throw new IllegalArgumentException("Reserved spectrum header bytes must be zero");
        }

        float quantizationScale = buffer.getFloat();
        float quantizationOffset = buffer.getFloat();
        int payloadByteCount = buffer.getInt();
        int expectedPayloadByteCount = payloadByteCount(binCount, encoding);

        if(payloadByteCount != expectedPayloadByteCount)
        {
            throw new IllegalArgumentException("Spectrum payload length does not match its bin count and encoding");
        }

        if(buffer.remaining() != payloadByteCount)
        {
            throw new IllegalArgumentException("Spectrum frame length does not match its declared payload length");
        }

        if(encoding != SpectrumEncoding.FLOAT32)
        {
            throw new IllegalArgumentException("Encoding is not implemented: " + encoding);
        }

        if(Float.compare(quantizationScale, 1.0f) != 0 || Float.compare(quantizationOffset, 0.0f) != 0)
        {
            throw new IllegalArgumentException("FLOAT32 frames use a scale of 1.0 and offset of 0.0");
        }

        float[] bins = new float[binCount];

        for(int x = 0; x < bins.length; x++)
        {
            bins[x] = buffer.getFloat();
        }

        return SpectrumFrame.float32(flags, targetGeneration, sequence, monotonicTimestampNanos,
            captureTimestampEpochNanos, centerFrequencyHz, sampleRateHz, viewRevision, fftSize, firstBin, bins);
    }

    private static int payloadByteCount(int binCount, SpectrumEncoding encoding)
    {
        if(binCount <= 0 || binCount > MAXIMUM_BIN_COUNT)
        {
            throw new IllegalArgumentException("Invalid spectrum bin count: " + binCount);
        }

        return Math.multiplyExact(binCount, encoding.getBytesPerBin());
    }
}
