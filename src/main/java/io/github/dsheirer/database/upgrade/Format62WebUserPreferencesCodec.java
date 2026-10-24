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

/** Frozen version-12 preference contract, including the account's automatic page-guide choice. */
final class Format62WebUserPreferencesCodec
{
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format62WebUserPreferencesCodec() {}

    static void validate(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES ||
            !(MAPPER.readTree(json) instanceof ObjectNode target))
        {
            throw new IOException("Version-12 web preferences are missing, oversized, or not a complete object");
        }
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 12 ||
            !(target.get("guides") instanceof ObjectNode guides) || guides.size() != 1 ||
            !guides.has("automatic_page_guides") || !guides.get("automatic_page_guides").isBoolean())
        {
            throw new IOException("Version-12 guides must contain exactly automatic_page_guides as a boolean");
        }
        ObjectNode prior = target.deepCopy();
        prior.put("version", 11);
        prior.remove("guides");
        Format43WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    static BoundedMigration migrateFromFormat61(String json) throws IOException
    {
        Format43WebUserPreferencesCodec.validate(json);
        ObjectNode target = (ObjectNode)MAPPER.readTree(json);
        target.put("version", 12);
        target.putObject("guides").put("automatic_page_guides", true);
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
        return migrateFromFormat61(Format43WebUserPreferencesCodec.defaults()).json();
    }
}
