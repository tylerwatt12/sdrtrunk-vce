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
import java.util.Set;

/** Frozen version-14 preference contract, including the account's recording click action. */
final class Format66WebUserPreferencesCodec
{
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format66WebUserPreferencesCodec() {}

    static void validate(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES ||
            !(MAPPER.readTree(json) instanceof ObjectNode target))
            throw new IOException("Version-14 web preferences are missing, oversized, or not a complete object");
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 14 ||
            !(target.get("playback") instanceof ObjectNode playback))
            throw new IOException("Version-14 playback preferences are incomplete or invalid");
        JsonNode mode = playback.get("recording_click_mode");
        if(mode == null || !mode.isTextual() || !Set.of("queue", "once", "continue").contains(mode.textValue()))
            throw new IOException("Version-14 playback.recording_click_mode must be queue, once, or continue");
        ObjectNode prior = target.deepCopy();
        prior.put("version", 13);
        ((ObjectNode)prior.get("playback")).remove("recording_click_mode");
        Format65WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    static BoundedMigration migrateFromFormat65(String json) throws IOException
    {
        Format65WebUserPreferencesCodec.validate(json);
        ObjectNode target = (ObjectNode)MAPPER.readTree(json);
        target.put("version", 14);
        ((ObjectNode)target.get("playback")).put("recording_click_mode", "queue");
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
        return migrateFromFormat65(Format65WebUserPreferencesCodec.defaults()).json();
    }
}
