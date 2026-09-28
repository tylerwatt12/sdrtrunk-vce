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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Length-delimited, versioned wire framing for remote P25 bitstreams.  The caller owns the connection and never
 * invokes these blocking methods from a receiver callback.
 */
public final class RemoteWireProtocol
{
    public static final int VERSION = 1;
    public static final int MAXIMUM_FRAME_BYTES = 65_536;

    private RemoteWireProtocol()
    {
    }

    public enum Type
    {
        HELLO(1), CHALLENGE(2), AUTH(3), ACCEPT(4), CATALOG(5), OPEN(6), DATA(7), CLOSE(8),
        HEARTBEAT(9), GAP(10), REJECT(11);

        private final int mCode;

        Type(int code)
        {
            mCode = code;
        }

        public int code()
        {
            return mCode;
        }

        static Type fromCode(int code) throws IOException
        {
            for(Type candidate: values())
            {
                if(candidate.mCode == code)
                {
                    return candidate;
                }
            }

            throw new IOException("Unsupported remote frame type");
        }
    }

    public record Frame(Type type, byte[] payload)
    {
        public Frame
        {
            Objects.requireNonNull(type);
            payload = payload != null ? Arrays.copyOf(payload, payload.length) : new byte[0];

            if(payload.length >= MAXIMUM_FRAME_BYTES)
            {
                throw new IllegalArgumentException("Remote frame exceeds maximum length");
            }
        }

        @Override
        public byte[] payload()
        {
            return Arrays.copyOf(payload, payload.length);
        }
    }

    public static Frame read(DataInputStream input) throws IOException
    {
        Objects.requireNonNull(input);
        int length;

        try
        {
            length = input.readInt();
        }
        catch(EOFException eof)
        {
            return null;
        }

        if(length < 1 || length > MAXIMUM_FRAME_BYTES)
        {
            throw new IOException("Invalid remote frame length");
        }

        Type type = Type.fromCode(input.readUnsignedByte());
        byte[] payload = new byte[length - 1];
        input.readFully(payload);
        return new Frame(type, payload);
    }

    public static void write(DataOutputStream output, Frame frame) throws IOException
    {
        Objects.requireNonNull(output);
        Objects.requireNonNull(frame);
        byte[] payload = frame.payload();
        output.writeInt(payload.length + 1);
        output.writeByte(frame.type().code());
        output.write(payload);
    }
}
