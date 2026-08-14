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

package io.github.dsheirer.stats;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Immutable, encode-once binary diagnostic frame shared by every viewer of a producer.
 */
record DiagnosticStreamFrame(int type, long generation, long sequence, long observedAtEpochMs,
                             long encodedAtEpochMs, long centerFrequencyHz, int sampleRateHz, int fftSize,
                             int firstBin, int sourceBinCount, int valueCount, byte[] encoded)
{
    static final int MAGIC = 0x53444447;
    static final int VERSION = 1;
    static final int HEADER_BYTES = 72;
    static final int TYPE_STATE = 1;
    static final int TYPE_CHANNEL_SIGNAL = 2;
    static final int TYPE_CHANNEL_SYMBOLS = 3;
    static final int TYPE_TUNER_FFT = 4;
    static final int TYPE_HEARTBEAT = 127;

    DiagnosticStreamFrame
    {
        if(type < 1 || type > 255 || generation < 0 || sequence < 0 || observedAtEpochMs < 0 ||
            encodedAtEpochMs < 0 || centerFrequencyHz < 0 || sampleRateHz < 0 || fftSize < 0 || firstBin < 0 ||
            sourceBinCount < 0 || valueCount < 0)
        {
            throw new IllegalArgumentException("Diagnostic frame metadata is invalid");
        }

        Objects.requireNonNull(encoded, "Encoded diagnostic frame cannot be null");
    }

    static DiagnosticStreamFrame float32(int type, long generation, long sequence, long observedAtEpochMs,
                                         long centerFrequencyHz, long sampleRateHz, int fftSize, float[] values)
    {
        return float32(type, generation, sequence, observedAtEpochMs, centerFrequencyHz, sampleRateHz, fftSize,
            0, fftSize > 0 ? fftSize : values != null ? values.length : 0, values);
    }

    static DiagnosticStreamFrame float32(int type, long generation, long sequence, long observedAtEpochMs,
                                         long centerFrequencyHz, long sampleRateHz, int fftSize, int firstBin,
                                         int sourceBinCount, float[] values)
    {
        if(type != TYPE_CHANNEL_SIGNAL && type != TYPE_CHANNEL_SYMBOLS && type != TYPE_TUNER_FFT)
        {
            throw new IllegalArgumentException("Diagnostic float frame type is invalid");
        }

        Objects.requireNonNull(values, "Diagnostic values cannot be null");

        if(sampleRateHz < 0 || sampleRateHz > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Diagnostic sample rate is invalid");
        }

        int payloadBytes = Math.multiplyExact(values.length, Float.BYTES);
        if(firstBin < 0 || sourceBinCount < 0 ||
            (fftSize > 0 && (long)firstBin + sourceBinCount > fftSize))
        {
            throw new IllegalArgumentException("Diagnostic bin range is invalid");
        }

        ByteBuffer buffer = header(type, payloadBytes, values.length, generation, sequence, observedAtEpochMs,
            0, centerFrequencyHz, (int)sampleRateHz, fftSize, firstBin, sourceBinCount);

        for(float raw: values)
        {
            float value = Float.isFinite(raw) ? raw : -196.0f;

            if(type == TYPE_CHANNEL_SYMBOLS)
            {
                value = Math.max(-(float)Math.PI, Math.min((float)Math.PI, value));
            }
            else
            {
                value = Math.max(-196.0f, Math.min(20.0f, value));
            }

            buffer.putFloat(value);
        }

        long encodedAt = System.currentTimeMillis();
        buffer.putLong(40, encodedAt);

        return new DiagnosticStreamFrame(type, generation, sequence, observedAtEpochMs, encodedAt,
            centerFrequencyHz, (int)sampleRateHz, fftSize, firstBin, sourceBinCount, values.length, buffer.array());
    }

    static DiagnosticStreamFrame tunerFft(long generation, long sequence, long observedAtEpochMs,
                                          long centerFrequencyHz, long sampleRateHz, int fftSize,
                                          int quantizationBits, float[] values)
    {
        if(quantizationBits == 32)
        {
            return float32(TYPE_TUNER_FFT, generation, sequence, observedAtEpochMs, centerFrequencyHz,
                sampleRateHz, fftSize, values);
        }

        if(quantizationBits != 2 && quantizationBits != 4 && quantizationBits != 8 && quantizationBits != 16)
        {
            throw new IllegalArgumentException("Diagnostic quantization is not supported");
        }

        Objects.requireNonNull(values, "Diagnostic values cannot be null");

        if(sampleRateHz < 0 || sampleRateHz > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Diagnostic sample rate is invalid");
        }

        int payloadBytes = Math.toIntExact(((long)values.length * quantizationBits + 7) / Byte.SIZE);
        int maximumCode = (1 << quantizationBits) - 1;
        ByteBuffer buffer = header(TYPE_TUNER_FFT, payloadBytes, values.length,
            generation, sequence, observedAtEpochMs, 0, centerFrequencyHz, (int)sampleRateHz, fftSize, 0, fftSize);
        int packed = 0;
        int packedBits = 0;

        for(float raw: values)
        {
            float value = Float.isFinite(raw) ? Math.max(-196.0f, Math.min(20.0f, raw)) : -196.0f;
            int code = Math.round((value + 196.0f) * maximumCode / 216.0f);

            if(quantizationBits < 8)
            {
                packed |= code << packedBits;
                packedBits += quantizationBits;

                if(packedBits == Byte.SIZE)
                {
                    buffer.put((byte)packed);
                    packed = 0;
                    packedBits = 0;
                }
            }
            else if(quantizationBits == 8)
            {
                buffer.put((byte)code);
            }
            else
            {
                buffer.putShort((short)code);
            }
        }

        if(packedBits > 0)
        {
            buffer.put((byte)packed);
        }

        long encodedAt = System.currentTimeMillis();
        buffer.putLong(40, encodedAt);
        return new DiagnosticStreamFrame(TYPE_TUNER_FFT, generation, sequence, observedAtEpochMs, encodedAt,
            centerFrequencyHz, (int)sampleRateHz, fftSize, 0, fftSize, values.length, buffer.array());
    }

    static DiagnosticStreamFrame jsonState(long generation, long revision, byte[] json)
    {
        Objects.requireNonNull(json, "Diagnostic state JSON cannot be null");
        long now = System.currentTimeMillis();
        ByteBuffer buffer = header(TYPE_STATE, json.length, 0, generation, revision, now, now, 0, 0, 0, 0, 0);
        buffer.put(json);
        return new DiagnosticStreamFrame(TYPE_STATE, generation, revision, now, now, 0, 0, 0, 0, 0, 0,
            buffer.array());
    }

    static DiagnosticStreamFrame heartbeat()
    {
        long now = System.currentTimeMillis();
        ByteBuffer buffer = header(TYPE_HEARTBEAT, 0, 0, 0, 0, now, now, 0, 0, 0, 0, 0);
        return new DiagnosticStreamFrame(TYPE_HEARTBEAT, 0, 0, now, now, 0, 0, 0, 0, 0, 0, buffer.array());
    }

    private static ByteBuffer header(int type, int payloadBytes, int valueCount, long generation, long sequence,
                                     long observedAtEpochMs, long encodedAtEpochMs, long centerFrequencyHz,
                                     int sampleRateHz, int fftSize, int firstBin, int sourceBinCount)
    {
        ByteBuffer buffer = ByteBuffer.allocate(Math.addExact(HEADER_BYTES, payloadBytes))
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put((byte)VERSION);
        buffer.put((byte)type);
        buffer.putShort((short)HEADER_BYTES);
        buffer.putInt(payloadBytes);
        buffer.putInt(valueCount);
        buffer.putLong(generation);
        buffer.putLong(sequence);
        buffer.putLong(observedAtEpochMs);
        buffer.putLong(encodedAtEpochMs);
        buffer.putLong(centerFrequencyHz);
        buffer.putInt(sampleRateHz);
        buffer.putInt(fftSize);
        buffer.putInt(firstBin);
        buffer.putInt(sourceBinCount);
        return buffer;
    }
}
