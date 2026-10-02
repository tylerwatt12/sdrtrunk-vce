/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.settings;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
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
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .registerModule(new SimpleModule().addDeserializer(WebUserPreferences.Appearance.class,
            new AppearanceDeserializer()));

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

    /** Only the optional hue may be null; every appearance field must still be present and correctly typed. */
    private static final class AppearanceDeserializer extends StdDeserializer<WebUserPreferences.Appearance>
    {
        private AppearanceDeserializer()
        {
            super(WebUserPreferences.Appearance.class);
        }

        @Override
        public WebUserPreferences.Appearance deserialize(JsonParser parser, DeserializationContext context)
            throws IOException
        {
            JsonNode appearance = context.readTree(parser);
            if(!appearance.isObject() || appearance.size() != 2 || !appearance.has("theme") ||
                !appearance.get("theme").isTextual() || !appearance.has("hue"))
            {
                throw JsonMappingException.from(parser, "appearance must contain exactly theme and hue");
            }
            JsonNode hue = appearance.get("hue");
            if(!hue.isNull() && (!hue.isIntegralNumber() || !hue.canConvertToInt()))
            {
                throw JsonMappingException.from(parser, "appearance.hue must be null or an integer between 0 and 359");
            }
            return new WebUserPreferences.Appearance(appearance.get("theme").textValue(),
                hue.isNull() ? null : hue.intValue());
        }
    }
}
