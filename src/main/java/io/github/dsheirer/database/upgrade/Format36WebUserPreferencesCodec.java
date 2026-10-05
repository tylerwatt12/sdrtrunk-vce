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

/** Frozen validator for version-10 preferences, including the Live calls sorting choice. */
final class Format36WebUserPreferencesCodec
{
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format36WebUserPreferencesCodec() {}

    static void validate(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES ||
            !(MAPPER.readTree(json) instanceof ObjectNode target))
        {
            throw new IOException("Version-10 web preferences are missing, oversized, or not a complete object");
        }
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 10 ||
            !(target.get("presentation") instanceof ObjectNode presentation) ||
            !presentation.has("live_channel_sort"))
        {
            throw new IOException("Version-10 Live presentation preferences are incomplete or invalid");
        }
        JsonNode sort = presentation.get("live_channel_sort");
        if(!sort.isTextual() || !Set.of("lcn", "order_appeared", "frequency").contains(sort.textValue()))
        {
            throw new IOException("Version-10 presentation.live_channel_sort must be lcn, order_appeared, or frequency");
        }
        ObjectNode prior = target.deepCopy();
        prior.put("version", 9);
        ((ObjectNode)prior.get("presentation")).remove("live_channel_sort");
        Format35WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    /** Adds the view's initial sort selection while retaining every version-9 personal setting. */
    static String migrateFromFormat35(String json) throws IOException
    {
        Format35WebUserPreferencesCodec.validate(json);
        ObjectNode target = (ObjectNode)MAPPER.readTree(json);
        addLiveSort(target);
        String migrated = MAPPER.writeValueAsString(target);
        validate(migrated);
        return migrated;
    }

    /** Removes only necessary cached layouts when the new field exceeds the unchanged storage bound. */
    static BoundedMigration migrateToBoundedFormat36(String json) throws IOException
    {
        Format35WebUserPreferencesCodec.validate(json);
        ObjectNode target = (ObjectNode)MAPPER.readTree(json);
        addLiveSort(target);
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

    private static void addLiveSort(ObjectNode target)
    {
        target.put("version", 10);
        ObjectNode presentation = (ObjectNode)target.get("presentation");
        presentation.put("live_channel_sort", presentation.get("show_only_active_trunked_channels").booleanValue() ?
            "order_appeared" : "lcn");
    }

    record BoundedMigration(String json, int resetLayouts) { }

    static String defaults() throws IOException
    {
        return migrateFromFormat35(Format35WebUserPreferencesCodec.defaults());
    }
}
