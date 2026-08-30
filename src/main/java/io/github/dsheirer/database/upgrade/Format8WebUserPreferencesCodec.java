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
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict frozen codec for the version-3 preference document stored by database format 8. */
final class Format8WebUserPreferencesCodec
{
    private static final int VERSION = 3;
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final int MAXIMUM_TABLES = 128;
    private static final int MAXIMUM_COLUMNS_PER_TABLE = 128;
    private static final int MAXIMUM_SELECTED_SCAN_LISTS = 16;
    private static final int MINIMUM_CONVERSATION_BURST_LIMIT = 1;
    private static final int MAXIMUM_CONVERSATION_BURST_LIMIT = 20;
    private static final int MAXIMUM_DISABLED_HEALTH_ALERT_CODES = 128;
    private static final Pattern STABLE_ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private Format8WebUserPreferencesCodec()
    {
    }

    static void validate(String json) throws IOException
    {
        decode(json);
    }

    /** Converts one exact format-7 document into one exact format-8 document. */
    static String migrateFromFormat7(String json) throws IOException
    {
        Format7WebUserPreferencesCodec.validate(json);
        JsonNode parsed = MAPPER.readTree(json);
        if(!(parsed instanceof ObjectNode target))
        {
            throw new IOException("Version-2 web user preferences are not a complete object");
        }

        target.put("version", VERSION);
        ObjectNode healthAlerts = target.putObject("health_alerts");
        healthAlerts.putArray("disabled_codes");
        return encode(decode(MAPPER.writeValueAsString(target)));
    }

    private static Document decode(String json) throws IOException
    {
        requireBounded(json);
        try
        {
            return MAPPER.readValue(json, Document.class);
        }
        catch(IllegalArgumentException exception)
        {
            throw new IOException("Version-3 web user preferences are invalid", exception);
        }
    }

    private static String encode(Document preferences) throws IOException
    {
        String json = MAPPER.writeValueAsString(preferences);
        requireBounded(json);
        return json;
    }

    private static void requireBounded(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Version-3 web user preferences are missing or exceed the storage bound");
        }
    }

    private record Document(int version, Appearance appearance, PageTitles pageTitles, Playback playback,
                            Scanner scanner, Presentation presentation, Tuner tuner,
                            HealthAlerts healthAlerts, Map<String,TableLayout> tables)
    {
        private Document
        {
            if(version != VERSION)
            {
                throw new IllegalArgumentException("Unsupported version-3 web user preference version");
            }

            Objects.requireNonNull(appearance, "appearance is required");
            Objects.requireNonNull(pageTitles, "page_titles is required");
            Objects.requireNonNull(playback, "playback is required");
            Objects.requireNonNull(scanner, "scanner is required");
            Objects.requireNonNull(presentation, "presentation is required");
            Objects.requireNonNull(tuner, "tuner is required");
            Objects.requireNonNull(healthAlerts, "health_alerts is required");
            Objects.requireNonNull(tables, "tables is required");

            if(tables.size() > MAXIMUM_TABLES)
            {
                throw new IllegalArgumentException("Too many saved table layouts");
            }

            Map<String,TableLayout> canonicalTables = new LinkedHashMap<>();
            tables.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String id = requireStableId(entry.getKey(), "Table identifier");
                canonicalTables.put(id, Objects.requireNonNull(entry.getValue(), "Table layout cannot be null"));
            });
            tables = Map.copyOf(canonicalTables);
        }
    }

    private record Appearance(String theme)
    {
        private Appearance
        {
            theme = requireOneOf(theme, "theme", Set.of("light", "dark"));
        }
    }

    private record PageTitles(boolean prependPlayingCall)
    {
    }

    private record Playback(double volume, List<Long> selectedScanListIds, boolean conversationGrouping,
                            int conversationBurstLimit)
    {
        private Playback
        {
            if(!Double.isFinite(volume) || volume < 0.0 || volume > 1.0)
            {
                throw new IllegalArgumentException("playback.volume must be between 0 and 1");
            }

            Objects.requireNonNull(selectedScanListIds, "playback.selected_scan_list_ids is required");
            if(selectedScanListIds.size() > MAXIMUM_SELECTED_SCAN_LISTS)
            {
                throw new IllegalArgumentException("playback.selected_scan_list_ids cannot contain more than " +
                    MAXIMUM_SELECTED_SCAN_LISTS + " scan lists");
            }

            Set<Long> unique = new HashSet<>();
            for(Long id: selectedScanListIds)
            {
                if(id == null || id <= 0 || !unique.add(id))
                {
                    throw new IllegalArgumentException(
                        "Selected scan-list identifiers must be unique positive integers");
                }
            }

            if(conversationBurstLimit < MINIMUM_CONVERSATION_BURST_LIMIT ||
                conversationBurstLimit > MAXIMUM_CONVERSATION_BURST_LIMIT)
            {
                throw new IllegalArgumentException("playback.conversation_burst_limit must be between " +
                    MINIMUM_CONVERSATION_BURST_LIMIT + " and " + MAXIMUM_CONVERSATION_BURST_LIMIT);
            }
            selectedScanListIds = List.copyOf(selectedScanListIds);
        }
    }

    private record Scanner(String detailMode)
    {
        private Scanner
        {
            detailMode = requireOneOf(detailMode, "scanner.detail_mode",
                Set.of("simple", "normal", "advanced", "engineer"));
        }
    }

    private record Presentation(boolean showEncryptionDetails, boolean showControlDecodeQuality,
                                boolean showVoiceDecodeQuality, String decodeQualityDisplayMode,
                                int liveDetailRowLimit)
    {
        private Presentation
        {
            decodeQualityDisplayMode = requireOneOf(decodeQualityDisplayMode,
                "presentation.decode_quality_display_mode", Set.of("percentage", "detailed"));
            if(liveDetailRowLimit < 25 || liveDetailRowLimit > 500)
            {
                throw new IllegalArgumentException(
                    "presentation.live_detail_row_limit is outside the supported range");
            }
        }
    }

    private record Tuner(int floorDb, int ceilingDb, double waterfallSpeed, boolean snapFrequency,
                         boolean smoothFft, boolean highlightWaterfallChannels, String profile)
    {
        private Tuner
        {
            if(floorDb < -200 || floorDb > -5 || ceilingDb < -195 || ceilingDb > 0 || ceilingDb - floorDb < 5)
            {
                throw new IllegalArgumentException("Tuner floor and ceiling are invalid");
            }
            if(!Double.isFinite(waterfallSpeed) || waterfallSpeed < 0.25 || waterfallSpeed > 4.0)
            {
                throw new IllegalArgumentException("tuner.waterfall_speed must be between 0.25 and 4");
            }
            profile = requireOneOf(profile, "tuner.profile",
                Set.of("efficient", "balanced", "high-detail", "maximum-detail"));
        }
    }

    private record HealthAlerts(List<String> disabledCodes)
    {
        private HealthAlerts
        {
            Objects.requireNonNull(disabledCodes, "health_alerts.disabled_codes is required");
            if(disabledCodes.size() > MAXIMUM_DISABLED_HEALTH_ALERT_CODES)
            {
                throw new IllegalArgumentException("health_alerts.disabled_codes cannot contain more than " +
                    MAXIMUM_DISABLED_HEALTH_ALERT_CODES + " alert codes");
            }

            Set<String> unique = new HashSet<>();
            List<String> canonical = disabledCodes.stream()
                .map(code -> requireStableId(code, "Health alert code"))
                .sorted()
                .toList();
            for(String code: canonical)
            {
                if(!unique.add(code))
                {
                    throw new IllegalArgumentException(
                        "health_alerts.disabled_codes contains duplicate alert codes");
                }
            }
            disabledCodes = List.copyOf(canonical);
        }
    }

    private record TableLayout(List<String> schema, List<String> columnOrder, Map<String,Integer> columnWidths,
                               List<String> hiddenColumns)
    {
        private TableLayout
        {
            schema = canonicalIds(schema, "table.schema");
            columnOrder = canonicalIds(columnOrder, "table.column_order");
            hiddenColumns = canonicalIds(hiddenColumns, "table.hidden_columns");
            Objects.requireNonNull(columnWidths, "table.column_widths is required");

            if(schema.size() > MAXIMUM_COLUMNS_PER_TABLE ||
                !new HashSet<>(schema).equals(new HashSet<>(columnOrder)))
            {
                throw new IllegalArgumentException(
                    "Table schema and column order must contain the same bounded columns");
            }

            Set<String> schemaIds = Set.copyOf(schema);
            if(!schemaIds.containsAll(hiddenColumns) || !schemaIds.containsAll(columnWidths.keySet()))
            {
                throw new IllegalArgumentException("Table widths and hidden columns must belong to its schema");
            }
            if(hiddenColumns.size() == schema.size())
            {
                throw new IllegalArgumentException("A saved table layout must keep at least one column visible");
            }

            Map<String,Integer> canonicalWidths = new LinkedHashMap<>();
            columnWidths.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String id = requireStableId(entry.getKey(), "Column identifier");
                Integer width = Objects.requireNonNull(entry.getValue(), "Column width cannot be null");
                if(width < 48 || width > 1200)
                {
                    throw new IllegalArgumentException("Column width must be between 48 and 1200 pixels");
                }
                canonicalWidths.put(id, width);
            });
            columnWidths = Map.copyOf(canonicalWidths);
        }
    }

    private static List<String> canonicalIds(List<String> ids, String label)
    {
        Objects.requireNonNull(ids, label + " is required");
        if(ids.size() > MAXIMUM_COLUMNS_PER_TABLE)
        {
            throw new IllegalArgumentException(label + " exceeds the column bound");
        }

        Set<String> unique = new HashSet<>();
        List<String> copy = ids.stream().map(id -> requireStableId(id, "Column identifier")).toList();
        for(String id: copy)
        {
            if(!unique.add(id))
            {
                throw new IllegalArgumentException(label + " contains duplicate column identifiers");
            }
        }
        return List.copyOf(copy);
    }

    private static String requireStableId(String value, String label)
    {
        if(value == null || value.length() > 64 || !STABLE_ID.matcher(value).matches())
        {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }

    private static String requireOneOf(String value, String label, Set<String> choices)
    {
        if(value == null)
        {
            throw new IllegalArgumentException(label + " is required");
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        if(!choices.contains(normalized))
        {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

}
