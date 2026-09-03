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

/** Frozen version-5 preferences: version 4 plus the independent idle FFT marker switch. */
final class Format12WebUserPreferencesCodec
{
    private static final int VERSION = 5;
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final String IDLE_CHANNELS = "show_idle_channels";
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format12WebUserPreferencesCodec()
    {
    }

    static void validate(String json) throws IOException
    {
        ObjectNode target = readObject(json);
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != VERSION)
        {
            throw new IOException("Unsupported version-5 web user preference version");
        }
        if(!(target.get("tuner") instanceof ObjectNode tuner) || !tuner.path(IDLE_CHANNELS).isBoolean())
        {
            throw new IOException("Version-5 idle FFT marker preference is missing or invalid");
        }
        ObjectNode prior = target.deepCopy();
        prior.put("version", 4);
        ((ObjectNode)prior.get("tuner")).remove(IDLE_CHANNELS);
        Format9WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    static String migrateFromFormat11(String json) throws IOException
    {
        Format9WebUserPreferencesCodec.validate(json);
        ObjectNode target = readObject(json);
        target.put("version", VERSION);
        ((ObjectNode)target.get("tuner")).put(IDLE_CHANNELS, false);
        String migrated = MAPPER.writeValueAsString(target);
        validate(migrated);
        return migrated;
    }

    private static ObjectNode readObject(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Version-5 web user preferences are missing or exceed the storage bound");
        }
        if(!(MAPPER.readTree(json) instanceof ObjectNode object))
        {
            throw new IOException("Version-5 web user preferences are not a complete object");
        }
        return object;
    }
}
