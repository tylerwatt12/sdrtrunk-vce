/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.settings;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One strict JSON boundary for persisted and HTTP web-user preference documents. */
public final class WebUserPreferencesCodec
{
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private WebUserPreferencesCodec()
    {
    }

    public static WebUserPreferences decode(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > WebUserPreferences.MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Web user preferences are missing or exceed the storage bound");
        }

        try
        {
            return MAPPER.readValue(json, WebUserPreferences.class);
        }
        catch(IllegalArgumentException exception)
        {
            throw new IOException("Web user preferences are invalid", exception);
        }
    }

    public static WebUserPreferences decode(byte[] json) throws IOException
    {
        if(json == null || json.length == 0 || json.length > WebUserPreferences.MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Web user preferences are missing or exceed the storage bound");
        }

        try
        {
            return MAPPER.readValue(json, WebUserPreferences.class);
        }
        catch(IllegalArgumentException exception)
        {
            throw new IOException("Web user preferences are invalid", exception);
        }
    }

    public static String encode(WebUserPreferences preferences) throws IOException
    {
        String json = MAPPER.writeValueAsString(preferences);
        if(json.getBytes(StandardCharsets.UTF_8).length > WebUserPreferences.MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Web user preferences exceed the storage bound");
        }
        return json;
    }
}
