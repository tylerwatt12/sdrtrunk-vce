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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Frozen validator for the version-7 preferences stored by database format 23. */
final class Format23WebUserPreferencesCodec
{
    private static final int VERSION = 7;
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final int MAXIMUM_COLLAPSED_GROUPS_PER_TABLE = 128;
    private static final Pattern STABLE_ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    private Format23WebUserPreferencesCodec()
    {
    }

    static void validate(String json) throws IOException
    {
        ObjectNode target = readObject(json);
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() ||
            version.intValue() != VERSION)
        {
            throw new IOException("Unsupported version-7 web user preference version");
        }
        if(!(target.get("tables") instanceof ObjectNode tables))
        {
            throw new IOException("Version-7 table preferences are missing");
        }

        ObjectNode prior = target.deepCopy();
        ObjectNode priorTables = (ObjectNode)prior.get("tables");
        Iterator<Map.Entry<String,JsonNode>> layouts = tables.fields();
        while(layouts.hasNext())
        {
            Map.Entry<String,JsonNode> entry = layouts.next();
            if(!(entry.getValue() instanceof ObjectNode layout) ||
                !(layout.get("collapsed_groups") instanceof ArrayNode collapsedGroups))
            {
                throw new IOException("Version-7 table preferences contain invalid row-group state");
            }
            validateCollapsedGroups(collapsedGroups);
            ((ObjectNode)priorTables.get(entry.getKey())).remove("collapsed_groups");
        }
        prior.put("version", 6);
        Format22WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    /** Adds the explicit original-palette selection while retaining every existing version-7 preference. */
    static String migrateToFormat31(String json) throws IOException
    {
        validate(json);
        ObjectNode target = readObject(json);
        target.put("version", 8);
        ((ObjectNode)target.get("appearance")).putNull("hue");
        String migrated = MAPPER.writeValueAsString(target);
        Format31WebUserPreferencesCodec.validate(migrated);
        return migrated;
    }

    static String defaults() throws IOException
    {
        //Freeze the version-7 defaults used by the current administrative repair through database format 30.
        String defaults = """
            {"version":7,"appearance":{"theme":"light"},"page_titles":{"prepend_playing_call":false},"playback":{"volume":1.0,"selected_scan_list_ids":[],"target_grouping":true,"target_burst_limit":4},"scanner":{"detail_mode":"normal"},"presentation":{"show_encryption_details":true,"show_control_decode_quality":true,"show_voice_decode_quality":true,"decode_quality_display_mode":"percentage","live_detail_row_limit":200,"show_only_active_trunked_channels":true,"retain_last_call_on_idle_rows":false,"clear_voice_quality_when_idle":false},"tuner":{"floor_db":-140,"ceiling_db":0,"waterfall_speed":1.0,"snap_frequency":true,"smooth_fft":true,"highlight_waterfall_channels":false,"show_idle_channels":false,"profile":"balanced"},"health_alerts":{"disabled_codes":[]},"tables":{}}""";
        validate(defaults);
        return defaults;
    }

    private static void validateCollapsedGroups(ArrayNode groups) throws IOException
    {
        if(groups.size() > MAXIMUM_COLLAPSED_GROUPS_PER_TABLE)
        {
            throw new IOException("Version-7 table row-group preferences exceed the storage bound");
        }
        Set<String> unique = new HashSet<>();
        for(JsonNode group: groups)
        {
            if(!group.isTextual())
            {
                throw new IOException("Version-7 table row-group identifiers must be text");
            }
            String id = group.textValue();
            if(id.length() > 64 || !STABLE_ID.matcher(id).matches() || !unique.add(id))
            {
                throw new IOException("Version-7 table row-group identifier is invalid or duplicated");
            }
        }
    }

    private static ObjectNode readObject(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Version-7 web user preferences are missing or exceed the storage bound");
        }
        if(!(MAPPER.readTree(json) instanceof ObjectNode object))
        {
            throw new IOException("Version-7 web user preferences are not a complete object");
        }
        return object;
    }
}
