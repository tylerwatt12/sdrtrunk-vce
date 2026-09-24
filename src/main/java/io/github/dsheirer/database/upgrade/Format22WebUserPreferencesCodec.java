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
import java.util.Iterator;
import java.util.Map;

/** Frozen version-6 preferences and their exact conversion to version 7 row-group disclosure state. */
final class Format22WebUserPreferencesCodec
{
    private static final int VERSION = 6;
    private static final int TARGET_VERSION = 7;
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    private Format22WebUserPreferencesCodec()
    {
    }

    static void validate(String json) throws IOException
    {
        ObjectNode source = readObject(json);
        JsonNode version = source.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() ||
            version.intValue() != VERSION)
        {
            throw new IOException("Unsupported version-6 web user preference version");
        }
        if(!(source.get("playback") instanceof ObjectNode playback) ||
            !playback.path("target_grouping").isBoolean() ||
            !playback.path("target_burst_limit").isIntegralNumber() ||
            playback.has("conversation_grouping") || playback.has("conversation_burst_limit"))
        {
            throw new IOException("Version-6 target playback preferences are missing or invalid");
        }

        ObjectNode prior = source.deepCopy();
        prior.put("version", 5);
        ObjectNode priorPlayback = (ObjectNode)prior.get("playback");
        priorPlayback.set("conversation_grouping", priorPlayback.remove("target_grouping"));
        priorPlayback.set("conversation_burst_limit", priorPlayback.remove("target_burst_limit"));
        Format12WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    static String migrateToFormat23(String json) throws IOException
    {
        validate(json);
        ObjectNode target = readObject(json);
        if(!(target.get("tables") instanceof ObjectNode tables))
        {
            throw new IOException("Version-6 table preferences are missing");
        }
        Iterator<Map.Entry<String,JsonNode>> layouts = tables.fields();
        while(layouts.hasNext())
        {
            JsonNode value = layouts.next().getValue();
            if(!(value instanceof ObjectNode layout) || layout.has("collapsed_groups"))
            {
                throw new IOException("Version-6 table preferences contain invalid row-group state");
            }
            layout.set("collapsed_groups", MAPPER.createArrayNode());
        }
        target.put("version", TARGET_VERSION);
        String migrated = MAPPER.writeValueAsString(target);
        Format23WebUserPreferencesCodec.validate(migrated);
        return migrated;
    }

    static String format6Defaults() throws IOException
    {
        return Format14WebUserPreferencesCodec.migrate(Format12WebUserPreferencesCodec.defaults());
    }

    static String defaults() throws IOException
    {
        return migrateToFormat23(format6Defaults());
    }

    private static ObjectNode readObject(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Version-6 web user preferences are missing or exceed the storage bound");
        }
        if(!(MAPPER.readTree(json) instanceof ObjectNode object))
        {
            throw new IOException("Version-6 web user preferences are not a complete object");
        }
        return object;
    }
}
