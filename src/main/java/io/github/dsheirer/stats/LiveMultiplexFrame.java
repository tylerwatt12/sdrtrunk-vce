/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.web.http.ApiHttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable wire bytes shared by viewers. Preparation is confined to web/diagnostic observer workers. */
final class LiveMultiplexFrame
{
    static final int HEADER_BYTES = 16;
    static final int GZIP_FLAG = 0x80;
    private static final int MINIMUM_COMPRESSION_BYTES = 1_024;
    private static final AtomicLong ENCODINGS = new AtomicLong();
    private static final AtomicLong COMPRESSIONS = new AtomicLong();
    private final byte[] mPlain;
    private volatile boolean mCompressionPrepared;
    private byte[] mGzip;

    private LiveMultiplexFrame(int kind, int topic, byte[] payload)
    {
        mPlain = envelope(kind, topic, payload, 0);
        ENCODINGS.incrementAndGet();
    }

    static LiveMultiplexFrame json(int topic, String event, Object data) throws IOException
    {
        return new LiveMultiplexFrame(1, topic,
            ApiHttpResponse.encodePayload(Map.of("event", event, "data", data)));
    }

    static LiveMultiplexFrame diagnostic(int topic, byte[] payload)
    {
        return new LiveMultiplexFrame(2, topic, payload);
    }

    /** Returned arrays are immutable; queues and writers retain references rather than copying them. */
    byte[] bytes(boolean gzip) throws IOException
    {
        if(gzip && !mCompressionPrepared)
        {
            synchronized(this)
            {
                if(!mCompressionPrepared)
                {
                    int payloadBytes = mPlain.length - HEADER_BYTES;
                    if(payloadBytes >= MINIMUM_COMPRESSION_BYTES)
                    {
                        COMPRESSIONS.incrementAndGet();
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream(payloadBytes / 2);
                        try(GZIPOutputStream output = new GZIPOutputStream(buffer)
                        {
                            { def.setLevel(Deflater.BEST_SPEED); }
                        })
                        {
                            output.write(mPlain, HEADER_BYTES, payloadBytes);
                        }
                        byte[] compressed = buffer.toByteArray();
                        if(compressed.length * 100L <= payloadBytes * 85L)
                        {
                            int kind = Byte.toUnsignedInt(mPlain[5]) | GZIP_FLAG;
                            int topic = Short.toUnsignedInt(ByteBuffer.wrap(mPlain).getShort(6));
                            mGzip = envelope(kind, topic, compressed, payloadBytes);
                        }
                    }
                    mCompressionPrepared = true;
                }
            }
        }
        return gzip && mGzip != null ? mGzip : mPlain;
    }

    static byte[] envelope(int kind, int topic, byte[] payload, int inflatedBytes)
    {
        return ByteBuffer.allocate(Math.addExact(HEADER_BYTES, payload.length)).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x534C4D58).put((byte)2).put((byte)kind).putShort((short)topic)
            .putInt(payload.length).putInt(inflatedBytes).put(payload).array();
    }

    static long encodingCount() { return ENCODINGS.get(); }
    static long compressionCount() { return COMPRESSIONS.get(); }
}
