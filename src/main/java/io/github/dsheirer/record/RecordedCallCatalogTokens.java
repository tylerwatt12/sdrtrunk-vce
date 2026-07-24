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
package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.AudioCallId;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.CRC32;

/**
 * Stable opaque URL tokens for public recorded-call IDs and pagination cursors.
 */
final class RecordedCallCatalogTokens
{
    private static final byte VERSION = 1;
    private static final String CALL_PREFIX = "c1_";
    private static final String CURSOR_PREFIX = "r1_";
    private static final int CALL_BODY_BYTES = 1 + Long.BYTES + Long.BYTES + Long.BYTES + Integer.BYTES;
    private static final int CURSOR_BODY_BYTES = 1 + Long.BYTES + Long.BYTES + Long.BYTES + Integer.BYTES;
    private static final int CHECKSUM_BYTES = Integer.BYTES;

    private RecordedCallCatalogTokens()
    {
    }

    static String callId(long completedAtMs, AudioCallId callId)
    {
        requireValues(completedAtMs, callId);
        ByteBuffer buffer = ByteBuffer.allocate(CALL_BODY_BYTES + CHECKSUM_BYTES);
        buffer.put(VERSION);
        buffer.putLong(completedAtMs);
        buffer.putLong(callId.producerId());
        buffer.putLong(callId.sequence());
        buffer.putInt(callId.timeslot());
        addChecksum(buffer, CALL_BODY_BYTES);
        return CALL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    static CursorValues parseCallId(String token)
    {
        ByteBuffer buffer = decode(token, CALL_PREFIX, CALL_BODY_BYTES + CHECKSUM_BYTES);
        requireVersion(buffer);
        CursorValues values = new CursorValues(buffer.getLong(), new AudioCallId(buffer.getLong(), buffer.getLong(),
            buffer.getInt()));
        requireValues(values.completedAtMs(), values.callId());
        return values;
    }

    static String cursor(long completedAtMs, AudioCallId callId)
    {
        requireValues(completedAtMs, callId);
        ByteBuffer buffer = ByteBuffer.allocate(CURSOR_BODY_BYTES + CHECKSUM_BYTES);
        buffer.put(VERSION);
        buffer.putLong(completedAtMs);
        buffer.putLong(callId.producerId());
        buffer.putLong(callId.sequence());
        buffer.putInt(callId.timeslot());
        addChecksum(buffer, CURSOR_BODY_BYTES);
        return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    static CursorValues parseCursor(String token)
    {
        ByteBuffer buffer = decode(token, CURSOR_PREFIX, CURSOR_BODY_BYTES + CHECKSUM_BYTES);
        requireVersion(buffer);
        CursorValues values = new CursorValues(buffer.getLong(), new AudioCallId(buffer.getLong(), buffer.getLong(),
            buffer.getInt()));
        requireValues(values.completedAtMs(), values.callId());
        return values;
    }

    private static ByteBuffer decode(String token, String prefix, int expectedBytes)
    {
        if(token == null || !token.startsWith(prefix) || token.length() > 96)
        {
            throw new IllegalArgumentException("Recorded-call token is invalid");
        }

        byte[] bytes;

        try
        {
            bytes = Base64.getUrlDecoder().decode(token.substring(prefix.length()));
        }
        catch(IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("Recorded-call token is invalid", exception);
        }

        if(bytes.length != expectedBytes ||
            !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token.substring(prefix.length())))
        {
            throw new IllegalArgumentException("Recorded-call token is invalid");
        }

        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, expectedBytes - CHECKSUM_BYTES);
        int stored = ByteBuffer.wrap(bytes, expectedBytes - CHECKSUM_BYTES, CHECKSUM_BYTES).getInt();

        if(stored != (int)checksum.getValue())
        {
            throw new IllegalArgumentException("Recorded-call token checksum is invalid");
        }

        return ByteBuffer.wrap(bytes, 0, expectedBytes - CHECKSUM_BYTES);
    }

    private static void requireVersion(ByteBuffer buffer)
    {
        if(buffer.get() != VERSION)
        {
            throw new IllegalArgumentException("Recorded-call token version is unsupported");
        }
    }

    private static void addChecksum(ByteBuffer buffer, int bodyLength)
    {
        CRC32 checksum = new CRC32();
        checksum.update(buffer.array(), 0, bodyLength);
        buffer.putInt((int)checksum.getValue());
    }

    private static void requireValues(long completedAtMs, AudioCallId callId)
    {
        if(completedAtMs <= 0 || callId == null || callId.timeslot() < 0)
        {
            throw new IllegalArgumentException("Recorded-call token values are invalid");
        }
    }

    record CursorValues(long completedAtMs, AudioCallId callId)
    {
    }
}
