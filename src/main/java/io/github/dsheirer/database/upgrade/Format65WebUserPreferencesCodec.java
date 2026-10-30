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

/** Frozen version-13 preference contract, including the account's call display hangtime. */
final class Format65WebUserPreferencesCodec
{
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format65WebUserPreferencesCodec() {}

    static void validate(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES ||
            !(MAPPER.readTree(json) instanceof ObjectNode target))
        {
            throw new IOException("Version-13 web preferences are missing, oversized, or not a complete object");
        }
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 13 ||
            !(target.get("playback") instanceof ObjectNode playback))
        {
            throw new IOException("Version-13 playback preferences are incomplete or invalid");
        }
        JsonNode hangtime = playback.get("call_display_hangtime_seconds");
        if(hangtime == null || !hangtime.isIntegralNumber() || !hangtime.canConvertToInt() ||
            hangtime.intValue() < 0 || hangtime.intValue() > 60)
        {
            throw new IOException("Version-13 playback.call_display_hangtime_seconds must be an integer from 0 to 60");
        }
        ObjectNode prior = target.deepCopy();
        prior.put("version", 12);
        ((ObjectNode)prior.get("playback")).remove("call_display_hangtime_seconds");
        Format62WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    static BoundedMigration migrateFromFormat64(String json) throws IOException
    {
        Format62WebUserPreferencesCodec.validate(json);
        ObjectNode target = (ObjectNode)MAPPER.readTree(json);
        target.put("version", 13);
        ((ObjectNode)target.get("playback")).put("call_display_hangtime_seconds", 5);
        ObjectNode tables = (ObjectNode)target.get("tables");
        int resetLayouts = 0;
        String migrated = MAPPER.writeValueAsString(target);
        while(migrated.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            String last = null;
            for(var names = tables.fieldNames(); names.hasNext(); ) last = names.next();
            if(last == null) throw new IOException("Personal preferences cannot fit without changing non-layout settings");
            tables.remove(last);
            resetLayouts++;
            migrated = MAPPER.writeValueAsString(target);
        }
        validate(migrated);
        return new BoundedMigration(migrated, resetLayouts);
    }

    record BoundedMigration(String json, int resetLayouts) {}

    static String defaults() throws IOException
    {
        return migrateFromFormat64(Format62WebUserPreferencesCodec.defaults()).json();
    }
}
