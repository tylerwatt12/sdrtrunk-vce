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
import java.util.Locale;
import java.util.Set;

/** Frozen version-9 preference boundary and deterministic source-name preference upgrade. */
final class Format35WebUserPreferencesCodec
{
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format35WebUserPreferencesCodec() {}

    static void validate(String json) throws IOException
    {
        ObjectNode target = readObject(json);
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() ||
            version.intValue() != 9 || !(target.get("presentation") instanceof ObjectNode presentation))
        {
            throw new IOException("Version-9 web preferences are incomplete or invalid");
        }
        JsonNode display = presentation.get("source_name_display");
        if(display == null || !display.isTextual() || !Set.of("talker_alias", "source_alias", "both")
            .contains(display.textValue().toLowerCase(Locale.ROOT)))
        {
            throw new IOException("Version-9 presentation.source_name_display is invalid");
        }
        ObjectNode prior = target.deepCopy();
        prior.put("version", 8);
        ((ObjectNode)prior.get("presentation")).remove("source_name_display");
        Format31WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    /** Adds the default while preserving all usable personal settings. */
    static String migrateFromFormat34(String json) throws IOException
    {
        Format31WebUserPreferencesCodec.validate(json);
        ObjectNode target = readObject(json);
        target.put("version", 9);
        ((ObjectNode)target.get("presentation")).put("source_name_display", "talker_alias");
        String migrated = MAPPER.writeValueAsString(target);
        validate(migrated);
        return migrated;
    }

    /** Removes only cached layouts if the new field exceeds the existing document storage limit. */
    static BoundedMigration migrateToBoundedFormat35(String json) throws IOException
    {
        Format31WebUserPreferencesCodec.validate(json);
        ObjectNode target = readObject(json);
        target.put("version", 9);
        ((ObjectNode)target.get("presentation")).put("source_name_display", "talker_alias");
        ObjectNode tables = (ObjectNode)target.get("tables");
        int resetLayouts = 0;
        String migrated = MAPPER.writeValueAsString(target);
        while(migrated.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            String last = null;
            for(var fields = tables.fieldNames(); fields.hasNext(); ) last = fields.next();
            if(last == null) throw new IOException("Personal preferences cannot fit without changing non-layout settings");
            tables.remove(last);
            resetLayouts++;
            migrated = MAPPER.writeValueAsString(target);
        }
        validate(migrated);
        return new BoundedMigration(migrated, resetLayouts);
    }

    static String defaults() throws IOException
    {
        return migrateFromFormat34(Format31WebUserPreferencesCodec.defaults());
    }

    private static ObjectNode readObject(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES ||
            !(MAPPER.readTree(json) instanceof ObjectNode object))
        {
            throw new IOException("Version-9 web preferences are missing, oversized, or not a complete object");
        }
        return object;
    }

    record BoundedMigration(String json, int resetLayouts) {}
}
