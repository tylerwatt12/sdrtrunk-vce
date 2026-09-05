/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Strict format-14 to format-15 browser-preference conversion. Format 15 names playback grouping for the stable
 * playback target it actually controls instead of the former overloaded "conversation" term.
 */
final class Format14WebUserPreferencesCodec
{
    private static final int SOURCE_VERSION = 5;
    private static final int TARGET_VERSION = 6;
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format14WebUserPreferencesCodec()
    {
    }

    static String migrate(String json) throws IOException
    {
        Format12WebUserPreferencesCodec.validate(json);
        ObjectNode root = readObject(json);

        if(!(root.get("playback") instanceof ObjectNode playback))
        {
            throw new IOException("Version-5 playback preferences are missing");
        }

        JsonNode grouping = playback.remove("conversation_grouping");
        JsonNode burstLimit = playback.remove("conversation_burst_limit");

        if(grouping == null || !grouping.isBoolean() || burstLimit == null || !burstLimit.isIntegralNumber())
        {
            throw new IOException("Version-5 conversation playback settings are missing or invalid");
        }

        if(playback.has("target_grouping") || playback.has("target_burst_limit"))
        {
            throw new IOException("Version-5 playback preferences contain mixed old and new field names");
        }

        playback.set("target_grouping", grouping);
        playback.set("target_burst_limit", burstLimit);
        root.put("version", TARGET_VERSION);
        return MAPPER.writeValueAsString(root);
    }

    private static ObjectNode readObject(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Version-" + SOURCE_VERSION + " web user preferences are missing or exceed " +
                "the storage bound");
        }

        if(!(MAPPER.readTree(json) instanceof ObjectNode object))
        {
            throw new IOException("Version-" + SOURCE_VERSION + " web user preferences are not a complete object");
        }

        return object;
    }
}
