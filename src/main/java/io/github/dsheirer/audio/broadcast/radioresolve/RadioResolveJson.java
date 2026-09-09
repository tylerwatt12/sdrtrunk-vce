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
package io.github.dsheirer.audio.broadcast.radioresolve;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/** Shared deterministic JSON encoding for the RadioResolve v3 wire contract and local spool manifests. */
final class RadioResolveJson
{
    static final Gson GSON = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerTypeAdapter(RadioResolveCallEnvelope.FingerprintFrame.class, new FingerprintFrameAdapter())
        .disableHtmlEscaping()
        .create();

    private RadioResolveJson()
    {
    }

    /** Keeps high-volume exact frame evidence compact as {@code [offset_ms, "fnv1a64"]} pairs. */
    private static class FingerprintFrameAdapter extends TypeAdapter<RadioResolveCallEnvelope.FingerprintFrame>
    {
        @Override
        public void write(JsonWriter writer, RadioResolveCallEnvelope.FingerprintFrame frame) throws IOException
        {
            if(frame == null)
            {
                writer.nullValue();
                return;
            }

            writer.beginArray();
            writer.value(frame.offsetMs());
            writer.value(frame.digest());
            writer.endArray();
        }

        @Override
        public RadioResolveCallEnvelope.FingerprintFrame read(JsonReader reader) throws IOException
        {
            if(reader.peek() == JsonToken.NULL)
            {
                reader.nextNull();
                return null;
            }

            reader.beginArray();
            long offset = reader.nextLong();
            String digest = reader.nextString();

            while(reader.hasNext())
            {
                reader.skipValue();
            }

            reader.endArray();
            return new RadioResolveCallEnvelope.FingerprintFrame(offset, digest);
        }
    }
}
