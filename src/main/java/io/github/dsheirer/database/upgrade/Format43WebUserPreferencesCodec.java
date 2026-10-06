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

/** Frozen validator for version-11 preferences, including the Live row density choice. */
final class Format43WebUserPreferencesCodec
{
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format43WebUserPreferencesCodec() {}

    static void validate(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES ||
            !(MAPPER.readTree(json) instanceof ObjectNode target))
        {
            throw new IOException("Version-11 web preferences are missing, oversized, or not a complete object");
        }
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 11 ||
            !(target.get("presentation") instanceof ObjectNode presentation))
        {
            throw new IOException("Version-11 Live presentation preferences are incomplete or invalid");
        }
        JsonNode density = presentation.get("live_row_density");
        if(density == null || !density.isTextual() || !Set.of("normal", "dense").contains(density.textValue()))
        {
            throw new IOException("Version-11 presentation.live_row_density must be normal or dense");
        }
        ObjectNode prior = target.deepCopy();
        prior.put("version", 10);
        ((ObjectNode)prior.get("presentation")).remove("live_row_density");
        Format36WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    /** Adds Normal density while retaining every version-10 personal setting. */
    static String migrateFromFormat42(String json) throws IOException
    {
        Format36WebUserPreferencesCodec.validate(json);
        ObjectNode target = (ObjectNode)MAPPER.readTree(json);
        addDensity(target);
        String migrated = MAPPER.writeValueAsString(target);
        validate(migrated);
        return migrated;
    }

    /** Removes only necessary cached layouts when the new choice exceeds the unchanged storage bound. */
    static BoundedMigration migrateToBoundedFormat43(String json) throws IOException
    {
        Format36WebUserPreferencesCodec.validate(json);
        ObjectNode target = (ObjectNode)MAPPER.readTree(json);
        addDensity(target);
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

    private static void addDensity(ObjectNode target)
    {
        target.put("version", 11);
        ((ObjectNode)target.get("presentation")).put("live_row_density", "normal");
    }

    record BoundedMigration(String json, int resetLayouts) { }

    static String defaults() throws IOException
    {
        return migrateFromFormat42(Format36WebUserPreferencesCodec.defaults());
    }
}
