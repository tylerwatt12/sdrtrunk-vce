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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Frozen version-15 contract for personal workspace display choices. */
final class Format67WebUserPreferencesCodec
{
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final List<String> EVENTS = List.of("emergency", "movement", "denial", "patch", "busy", "queued",
        "check", "page", "logout", "call");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Format67WebUserPreferencesCodec() {}

    static void validate(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES ||
            !(MAPPER.readTree(json) instanceof ObjectNode target)) throw invalid();
        JsonNode version = target.get("version");
        if(version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 15)
            throw invalid();
        JsonNode live = target.get("live_workspace");
        fields(live, "details_panel_percent", "details_visibility", "details_active_tab", "picker_collapsed",
            "active_channel_table_id", "event_filters", "message_filters");
        JsonNode percent = live.get("details_panel_percent");
        if(!percent.isIntegralNumber() || !percent.canConvertToInt() || percent.intValue() < 15 || percent.intValue() > 65)
            throw invalid();
        choice(live.get("details_visibility"), Set.of("automatic", "collapsed", "expanded"));
        choice(live.get("details_active_tab"), Set.of("events", "messages", "channel"));
        bool(live.get("picker_collapsed"));
        if(!live.get("active_channel_table_id").isNull()) text(live.get("active_channel_table_id"), 256);
        filters(live.get("event_filters")); filters(live.get("message_filters"));
        JsonNode p25 = target.get("p25_visualizer");
        fields(p25, "events");
        JsonNode events = p25.get("events");
        fields(events, EVENTS.toArray(String[]::new));
        for(String id: EVENTS)
        {
            JsonNode event = events.get(id);
            fields(event, "highlight", "auto_zoom");
            bool(event.get("highlight")); bool(event.get("auto_zoom"));
        }
        JsonNode tuner = target.get("tuner");
        if(!(tuner instanceof ObjectNode)) throw invalid();
        bool(tuner.get("fft_auto_range_on_retune")); bool(tuner.get("waterfall_auto_range_on_retune"));
        ObjectNode prior = target.deepCopy();
        prior.put("version", 14);
        prior.remove(List.of("live_workspace", "p25_visualizer"));
        ((ObjectNode)prior.get("tuner")).remove(List.of("fft_auto_range_on_retune", "waterfall_auto_range_on_retune"));
        Format66WebUserPreferencesCodec.validate(MAPPER.writeValueAsString(prior));
    }

    private static void filters(JsonNode value) throws IOException
    {
        fields(value, "excluded_leaf_keys", "excluded_timeslots", "excluded_validity", "search");
        texts(value.get("excluded_leaf_keys"), 256, 256);
        texts(value.get("excluded_timeslots"), 256, 64);
        texts(value.get("excluded_validity"), 2, 7);
        for(JsonNode item: value.get("excluded_validity")) choice(item, Set.of("valid", "invalid"));
        text(value.get("search"), 256);
    }

    private static void fields(JsonNode value, String... keys) throws IOException
    {
        if(value == null || !value.isObject() || value.size() != keys.length ||
            java.util.Arrays.stream(keys).anyMatch(key -> !value.has(key))) throw invalid();
    }
    private static void bool(JsonNode value) throws IOException
    {
        if(value == null || !value.isBoolean()) throw invalid();
    }
    private static void text(JsonNode value, int length) throws IOException
    {
        if(value == null || !value.isTextual() || value.textValue().length() > length) throw invalid();
    }
    private static void choice(JsonNode value, Set<String> values) throws IOException
    {
        if(value == null || !value.isTextual() || !values.contains(value.textValue())) throw invalid();
    }
    private static void texts(JsonNode value, int count, int length) throws IOException
    {
        if(value == null || !value.isArray() || value.size() > count) throw invalid();
        Set<String> unique = new HashSet<>();
        for(JsonNode item: value)
        {
            text(item, length);
            if(!unique.add(item.textValue())) throw invalid();
        }
    }
    private static IOException invalid() { return new IOException("Version-15 workspace display preferences are invalid"); }

    static BoundedMigration migrateFromFormat66(String json) throws IOException
    {
        Format66WebUserPreferencesCodec.validate(json);
        ObjectNode target = (ObjectNode)MAPPER.readTree(json);
        target.put("version", 15);
        ObjectNode live = target.putObject("live_workspace");
        live.put("details_panel_percent", 25); live.put("details_visibility", "automatic");
        live.put("details_active_tab", "events"); live.put("picker_collapsed", false); live.putNull("active_channel_table_id");
        for(String name: List.of("event_filters", "message_filters"))
        {
            ObjectNode filters = live.putObject(name);
            filters.putArray("excluded_leaf_keys"); filters.putArray("excluded_timeslots");
            filters.putArray("excluded_validity"); filters.put("search", "");
        }
        ObjectNode events = target.putObject("p25_visualizer").putObject("events");
        Set<String> zoom = Set.of("emergency", "movement", "patch", "busy", "queued");
        for(String id: EVENTS)
        {
            ObjectNode event = events.putObject(id); event.put("highlight", true); event.put("auto_zoom", zoom.contains(id));
        }
        ObjectNode tuner = (ObjectNode)target.get("tuner");
        tuner.put("fft_auto_range_on_retune", true); tuner.put("waterfall_auto_range_on_retune", true);
        ObjectNode tables = (ObjectNode)target.get("tables");
        int resetLayouts = 0;
        String migrated = MAPPER.writeValueAsString(target);
        while(migrated.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            String last = null;
            for(var names = tables.fieldNames(); names.hasNext(); ) last = names.next();
            if(last == null) throw new IOException("Personal preferences cannot fit without changing non-layout settings");
            tables.remove(last); resetLayouts++; migrated = MAPPER.writeValueAsString(target);
        }
        validate(migrated);
        return new BoundedMigration(migrated, resetLayouts);
    }

    record BoundedMigration(String json, int resetLayouts) {}
    static String defaults() throws IOException { return migrateFromFormat66(Format66WebUserPreferencesCodec.defaults()).json(); }
}
