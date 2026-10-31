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
            new AppearanceDeserializer()).addDeserializer(WebUserPreferences.Guides.class,
            new GuidesDeserializer()).addDeserializer(WebUserPreferences.Playback.class,
            new PlaybackDeserializer()).addDeserializer(WebUserPreferences.LiveWorkspace.class,
            new LiveWorkspaceDeserializer()).addDeserializer(WebUserPreferences.DetailFilters.class,
            new DetailFiltersDeserializer()).addDeserializer(WebUserPreferences.P25Visualizer.class,
            new P25VisualizerDeserializer()).addDeserializer(WebUserPreferences.Tuner.class,
            new TunerDeserializer()));

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

    private static void requireFields(JsonNode value, JsonParser parser, String... keys) throws IOException
    {
        if(!value.isObject() || value.size() != keys.length ||
            java.util.Arrays.stream(keys).anyMatch(key -> !value.has(key)))
            throw JsonMappingException.from(parser, "Preference settings contain unknown or missing fields");
    }

    private static boolean bool(JsonNode value, JsonParser parser) throws IOException
    {
        if(value == null || !value.isBoolean()) throw JsonMappingException.from(parser, "Preference choice must be a boolean");
        return value.booleanValue();
    }

    private static int integer(JsonNode value, JsonParser parser) throws IOException
    {
        if(value == null || !value.isIntegralNumber() || !value.canConvertToInt())
            throw JsonMappingException.from(parser, "Preference choice must be an integer");
        return value.intValue();
    }

    private static double number(JsonNode value, JsonParser parser) throws IOException
    {
        if(value == null || !value.isNumber()) throw JsonMappingException.from(parser, "Preference choice must be a number");
        return value.doubleValue();
    }

    private static String text(JsonNode value, JsonParser parser) throws IOException
    {
        if(value == null || !value.isTextual()) throw JsonMappingException.from(parser, "Preference choice must be a string");
        return value.textValue();
    }

    private static java.util.List<String> texts(JsonNode value, JsonParser parser) throws IOException
    {
        if(value == null || !value.isArray()) throw JsonMappingException.from(parser, "Preference choices must be a list");
        java.util.List<String> result = new java.util.ArrayList<>();
        for(JsonNode item: value) result.add(text(item, parser));
        return result;
    }

    private static final class LiveWorkspaceDeserializer extends StdDeserializer<WebUserPreferences.LiveWorkspace>
    {
        private LiveWorkspaceDeserializer() { super(WebUserPreferences.LiveWorkspace.class); }
        @Override public WebUserPreferences.LiveWorkspace deserialize(JsonParser parser, DeserializationContext context)
            throws IOException
        {
            JsonNode value = context.readTree(parser);
            requireFields(value, parser, "details_panel_percent", "details_visibility", "details_active_tab", "picker_collapsed",
                "active_channel_table_id", "event_filters", "message_filters");
            return new WebUserPreferences.LiveWorkspace(integer(value.get("details_panel_percent"), parser),
                text(value.get("details_visibility"), parser), text(value.get("details_active_tab"), parser),
                bool(value.get("picker_collapsed"), parser), value.get("active_channel_table_id").isNull() ? null :
                text(value.get("active_channel_table_id"), parser),
                MAPPER.treeToValue(value.get("event_filters"), WebUserPreferences.DetailFilters.class),
                MAPPER.treeToValue(value.get("message_filters"), WebUserPreferences.DetailFilters.class));
        }
    }

    private static final class DetailFiltersDeserializer extends StdDeserializer<WebUserPreferences.DetailFilters>
    {
        private DetailFiltersDeserializer() { super(WebUserPreferences.DetailFilters.class); }
        @Override public WebUserPreferences.DetailFilters deserialize(JsonParser parser, DeserializationContext context)
            throws IOException
        {
            JsonNode value = context.readTree(parser);
            requireFields(value, parser, "excluded_leaf_keys", "excluded_timeslots", "excluded_validity", "search");
            return new WebUserPreferences.DetailFilters(texts(value.get("excluded_leaf_keys"), parser),
                texts(value.get("excluded_timeslots"), parser), texts(value.get("excluded_validity"), parser),
                text(value.get("search"), parser));
        }
    }

    private static final class P25VisualizerDeserializer extends StdDeserializer<WebUserPreferences.P25Visualizer>
    {
        private P25VisualizerDeserializer() { super(WebUserPreferences.P25Visualizer.class); }
        @Override public WebUserPreferences.P25Visualizer deserialize(JsonParser parser, DeserializationContext context)
            throws IOException
        {
            JsonNode value = context.readTree(parser);
            requireFields(value, parser, "events");
            JsonNode events = value.get("events");
            requireFields(events, parser, WebUserPreferences.P25Visualizer.EVENT_IDS.toArray(String[]::new));
            java.util.Map<String,WebUserPreferences.P25Event> choices = new java.util.LinkedHashMap<>();
            for(String id: WebUserPreferences.P25Visualizer.EVENT_IDS)
            {
                JsonNode event = events.get(id);
                requireFields(event, parser, "highlight", "auto_zoom");
                choices.put(id, new WebUserPreferences.P25Event(bool(event.get("highlight"), parser), bool(event.get("auto_zoom"), parser)));
            }
            return new WebUserPreferences.P25Visualizer(choices);
        }
    }

    private static final class TunerDeserializer extends StdDeserializer<WebUserPreferences.Tuner>
    {
        private TunerDeserializer() { super(WebUserPreferences.Tuner.class); }
        @Override public WebUserPreferences.Tuner deserialize(JsonParser parser, DeserializationContext context)
            throws IOException
        {
            JsonNode value = context.readTree(parser);
            requireFields(value, parser, "floor_db", "ceiling_db", "waterfall_speed", "snap_frequency", "smooth_fft",
                "highlight_waterfall_channels", "show_idle_channels", "profile", "fft_auto_range_on_retune",
                "waterfall_auto_range_on_retune");
            return new WebUserPreferences.Tuner(integer(value.get("floor_db"), parser), integer(value.get("ceiling_db"), parser),
                number(value.get("waterfall_speed"), parser), bool(value.get("snap_frequency"), parser),
                bool(value.get("smooth_fft"), parser), bool(value.get("highlight_waterfall_channels"), parser),
                bool(value.get("show_idle_channels"), parser), text(value.get("profile"), parser),
                bool(value.get("fft_auto_range_on_retune"), parser), bool(value.get("waterfall_auto_range_on_retune"), parser));
        }
    }

    /** Playback choices are explicit JSON scalars; numeric strings and fractional seconds are refused. */
    private static final class PlaybackDeserializer extends StdDeserializer<WebUserPreferences.Playback>
    {
        private PlaybackDeserializer() { super(WebUserPreferences.Playback.class); }

        @Override
        public WebUserPreferences.Playback deserialize(JsonParser parser, DeserializationContext context)
            throws IOException
        {
            JsonNode playback = context.readTree(parser);
            if(!playback.isObject() || playback.size() != 6 || !playback.has("volume") ||
                !playback.get("volume").isNumber() || !playback.has("selected_scan_list_ids") ||
                !playback.get("selected_scan_list_ids").isArray() || !playback.has("target_grouping") ||
                !playback.get("target_grouping").isBoolean() || !playback.has("target_burst_limit") ||
                !playback.get("target_burst_limit").isIntegralNumber() ||
                !playback.get("target_burst_limit").canConvertToInt() ||
                !playback.has("call_display_hangtime_seconds") ||
                !playback.get("call_display_hangtime_seconds").isIntegralNumber() ||
                !playback.get("call_display_hangtime_seconds").canConvertToInt() ||
                !playback.has("recording_click_mode") || !playback.get("recording_click_mode").isTextual())
            {
                throw JsonMappingException.from(parser, "playback must contain exactly the typed playback choices");
            }
            java.util.List<Long> selected = new java.util.ArrayList<>();
            for(JsonNode id: playback.get("selected_scan_list_ids"))
            {
                if(!id.isIntegralNumber() || !id.canConvertToLong())
                    throw JsonMappingException.from(parser, "Selected scan-list identifiers must be integers");
                selected.add(id.longValue());
            }
            return new WebUserPreferences.Playback(playback.get("volume").doubleValue(), selected,
                playback.get("target_grouping").booleanValue(), playback.get("target_burst_limit").intValue(),
                playback.get("call_display_hangtime_seconds").intValue(), playback.get("recording_click_mode").textValue());
        }
    }

    /** A saved tour choice must be an explicit JSON boolean, without scalar coercion or extra fields. */
    private static final class GuidesDeserializer extends StdDeserializer<WebUserPreferences.Guides>
    {
        private GuidesDeserializer() { super(WebUserPreferences.Guides.class); }

        @Override
        public WebUserPreferences.Guides deserialize(JsonParser parser, DeserializationContext context)
            throws IOException
        {
            JsonNode guides = context.readTree(parser);
            if(!guides.isObject() || guides.size() != 1 || !guides.has("automatic_page_guides") ||
                !guides.get("automatic_page_guides").isBoolean())
            {
                throw JsonMappingException.from(parser, "guides must contain exactly automatic_page_guides as a boolean");
            }
            return new WebUserPreferences.Guides(guides.get("automatic_page_guides").booleanValue());
        }
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
