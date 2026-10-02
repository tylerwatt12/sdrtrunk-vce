/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Frozen validator for version-8 preferences, including the optional custom theme hue. */
final class Format31WebUserPreferencesCodec
{
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format31WebUserPreferencesCodec() {}

    static void validate(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES ||
            !(MAPPER.readTree(json) instanceof ObjectNode target))
        {
            throw new IOException("Version-8 web preferences are missing, oversized, or not a complete object");
        }
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 8 ||
            !(target.get("appearance") instanceof ObjectNode appearance) || !appearance.has("hue"))
        {
            throw new IOException("Version-8 web appearance preferences are incomplete or invalid");
        }
        JsonNode hue = appearance.get("hue");
        if(!hue.isNull() && (!hue.isIntegralNumber() || !hue.canConvertToInt() ||
            hue.intValue() < 0 || hue.intValue() > 359))
        {
            throw new IOException("Version-8 appearance.hue must be null or an integer between 0 and 359");
        }
        ObjectNode prior = target.deepCopy();
        prior.put("version", 7);
        ((ObjectNode)prior.get("appearance")).remove("hue");
        Format23WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    static String defaults() throws IOException
    {
        return Format23WebUserPreferencesCodec.migrateToFormat31(Format23WebUserPreferencesCodec.defaults());
    }
}
