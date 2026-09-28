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
package io.github.dsheirer.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Stable primitive metadata on the remote wire.  No decoder objects or alias/output policy cross this boundary. */
public final class RemoteWireMessages
{
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final int MAXIMUM_DIBIT_BYTES = 4_096;
    public static final int MAXIMUM_FEEDS = 32;

    private RemoteWireMessages()
    {
    }

    public record Hello(int version, String senderId)
    {
        public Hello
        {
            senderId = uuid(senderId);
        }
    }

    public record Accepted(String sessionId, String hostProof)
    {
        public Accepted
        {
            sessionId = uuid(sessionId);
            hostProof = label(hostProof);
            if(hostProof.isBlank()) throw new IllegalArgumentException("Missing host authentication proof");
        }
    }

    public record Feed(String feedId, String system, String site, String name, String decoder,
                       long frequency, boolean running)
    {
        public Feed
        {
            feedId = uuid(feedId);
            system = label(system);
            site = label(site);
            name = label(name);
            decoder = label(decoder);
            if(frequency < 0)
            {
                throw new IllegalArgumentException("Invalid feed frequency");
            }
        }
    }

    public record Catalog(List<Feed> feeds)
    {
        public Catalog
        {
            feeds = feeds != null ? List.copyOf(feeds) : List.of();
            if(feeds.size() > MAXIMUM_FEEDS)
            {
                throw new IllegalArgumentException("Too many advertised feeds");
            }
        }
    }

    public record Open(String streamId, String feedId, boolean control, String phase, long frequency,
                       long generation, long captureTimestamp, Integer nac, Integer wacn, Integer systemId)
    {
        public Open
        {
            streamId = uuid(streamId);
            feedId = uuid(feedId);
            phase = label(phase);
            if(frequency <= 0 || generation < 0 || captureTimestamp < 0 ||
                nac != null && (nac < 0 || nac > 0xFFF) ||
                wacn != null && (wacn < 0 || wacn > 0xFFFFF) ||
                systemId != null && (systemId < 0 || systemId > 0xFFF))
            {
                throw new IllegalArgumentException("Invalid remote stream metadata");
            }
        }
    }

    public record Close(String streamId, long generation)
    {
        public Close
        {
            streamId = uuid(streamId);
            if(generation < 0) throw new IllegalArgumentException("Invalid stream generation");
        }
    }

    public record Gap(String streamId, long generation, long nextSequence, long droppedPackets)
    {
        public Gap
        {
            streamId = uuid(streamId);
            if(generation < 0 || nextSequence < 0 || droppedPackets < 0)
            {
                throw new IllegalArgumentException("Invalid remote gap");
            }
        }
    }

    public record Data(String streamId, long generation, long sequence, long captureTimestamp, byte[] dibits)
    {
        public Data
        {
            streamId = uuid(streamId);
            Objects.requireNonNull(dibits);
            dibits = dibits.clone();
            if(generation < 0 || sequence < 0 || captureTimestamp < 0 || dibits.length == 0 ||
                dibits.length > MAXIMUM_DIBIT_BYTES)
            {
                throw new IllegalArgumentException("Invalid remote bit packet");
            }
        }

        @Override
        public byte[] dibits()
        {
            return dibits.clone();
        }
    }

    public static <T> byte[] json(T value) throws IOException
    {
        byte[] bytes = JSON.writeValueAsBytes(value);
        if(bytes.length >= RemoteWireProtocol.MAXIMUM_FRAME_BYTES)
        {
            throw new IOException("Remote metadata exceeds frame limit");
        }
        return bytes;
    }

    public static <T> T parse(byte[] bytes, Class<T> type) throws IOException
    {
        if(bytes == null || bytes.length >= RemoteWireProtocol.MAXIMUM_FRAME_BYTES)
        {
            throw new IOException("Invalid remote metadata length");
        }
        try
        {
            return JSON.readValue(bytes, type);
        }
        catch(RuntimeException exception)
        {
            throw new IOException("Invalid remote metadata", exception);
        }
    }

    public static byte[] encodeData(Data packet) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(48 + packet.dibits.length);
        DataOutputStream output = new DataOutputStream(bytes);
        UUID streamId = UUID.fromString(packet.streamId);
        output.writeLong(streamId.getMostSignificantBits());
        output.writeLong(streamId.getLeastSignificantBits());
        output.writeLong(packet.generation);
        output.writeLong(packet.sequence);
        output.writeLong(packet.captureTimestamp);
        output.write(packet.dibits);
        return bytes.toByteArray();
    }

    public static Data decodeData(byte[] bytes) throws IOException
    {
        if(bytes == null || bytes.length <= 40 || bytes.length > 40 + MAXIMUM_DIBIT_BYTES)
        {
            throw new IOException("Invalid remote data length");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        String streamId = new UUID(input.readLong(), input.readLong()).toString();
        long generation = input.readLong();
        long sequence = input.readLong();
        long timestamp = input.readLong();
        byte[] dibits = input.readAllBytes();
        try
        {
            return new Data(streamId, generation, sequence, timestamp, dibits);
        }
        catch(IllegalArgumentException exception)
        {
            throw new IOException("Invalid remote data", exception);
        }
    }

    private static String uuid(String text)
    {
        return UUID.fromString(Objects.requireNonNull(text)).toString();
    }

    private static String label(String text)
    {
        if(text == null) return "";
        String normalized = text.strip();
        if(normalized.length() > 160 || normalized.chars().anyMatch(character -> Character.isISOControl(character)))
        {
            throw new IllegalArgumentException("Invalid remote label");
        }
        return normalized;
    }
}
