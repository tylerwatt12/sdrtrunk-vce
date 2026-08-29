/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict frozen decoder for the version-1 preference document stored by database formats 5 and 6. */
final class Format6WebUserPreferencesCodec
{
    private static final int VERSION = 1;
    private static final int MAXIMUM_JSON_BYTES = 131_072;
    private static final int MAXIMUM_TABLES = 128;
    private static final int MAXIMUM_COLUMNS_PER_TABLE = 128;
    private static final int MAXIMUM_SELECTED_SCAN_LISTS = 128;
    private static final Pattern STABLE_ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private Format6WebUserPreferencesCodec()
    {
    }

    static void validate(String json) throws IOException
    {
        decode(json);
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
            throw new IOException("Version-1 web user preferences are invalid", exception);
        }
    }

    /** Creates the exact version-1 default document required by the format-4-to-5 migration boundary. */
    static String defaults(boolean showEncryptionDetails, boolean showControlDecodeQuality,
                           boolean showVoiceDecodeQuality, String decodeQualityDisplayMode,
                           int liveDetailRowLimit) throws IOException
    {
        Document preferences = new Document(VERSION, new Appearance("light"), new PageTitles(false),
            new Playback(1.0, List.of()), new Scanner("normal"),
            new Presentation(showEncryptionDetails, showControlDecodeQuality, showVoiceDecodeQuality,
                decodeQualityDisplayMode, liveDetailRowLimit),
            new Tuner(-140, 0, 1, true, true, false, "balanced"), Map.of());
        String json = MAPPER.writeValueAsString(preferences);
        requireBounded(json);
        return json;
    }

    private static void requireBounded(String json) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JSON_BYTES)
        {
            throw new IOException("Version-1 web user preferences are missing or exceed the storage bound");
        }
    }

    private record Document(int version, Appearance appearance, PageTitles pageTitles, Playback playback,
                            Scanner scanner, Presentation presentation, Tuner tuner,
                            Map<String,TableLayout> tables)
    {
        private Document
        {
            if(version != VERSION)
            {
                throw new IllegalArgumentException("Unsupported version-1 web user preference version");
            }
            Objects.requireNonNull(appearance, "appearance is required");
            Objects.requireNonNull(pageTitles, "page_titles is required");
            Objects.requireNonNull(playback, "playback is required");
            Objects.requireNonNull(scanner, "scanner is required");
            Objects.requireNonNull(presentation, "presentation is required");
            Objects.requireNonNull(tuner, "tuner is required");
            Objects.requireNonNull(tables, "tables is required");
            if(tables.size() > MAXIMUM_TABLES)
            {
                throw new IllegalArgumentException("Too many saved table layouts");
            }
            for(Map.Entry<String,TableLayout> entry: tables.entrySet())
            {
                requireStableId(entry.getKey(), "Table identifier");
                Objects.requireNonNull(entry.getValue(), "Table layout cannot be null");
            }
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

    private record Playback(double volume, List<Long> selectedScanListIds)
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
                throw new IllegalArgumentException("Too many selected scan lists");
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

    private record TableLayout(List<String> schema, List<String> columnOrder, Map<String,Integer> columnWidths,
                               List<String> hiddenColumns)
    {
        private TableLayout
        {
            validateIds(schema, "table.schema");
            validateIds(columnOrder, "table.column_order");
            validateIds(hiddenColumns, "table.hidden_columns");
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
            for(Map.Entry<String,Integer> entry: columnWidths.entrySet())
            {
                requireStableId(entry.getKey(), "Column identifier");
                Integer width = Objects.requireNonNull(entry.getValue(), "Column width cannot be null");
                if(width < 48 || width > 1200)
                {
                    throw new IllegalArgumentException("Column width must be between 48 and 1200 pixels");
                }
            }
            schema = List.copyOf(schema);
            columnOrder = List.copyOf(columnOrder);
            columnWidths = Map.copyOf(columnWidths);
            hiddenColumns = List.copyOf(hiddenColumns);
        }
    }

    private static void validateIds(List<String> ids, String label)
    {
        Objects.requireNonNull(ids, label + " is required");
        if(ids.size() > MAXIMUM_COLUMNS_PER_TABLE)
        {
            throw new IllegalArgumentException(label + " exceeds the column bound");
        }
        Set<String> unique = new HashSet<>();
        for(String id: ids)
        {
            String validated = requireStableId(id, "Column identifier");
            if(!unique.add(validated))
            {
                throw new IllegalArgumentException(label + " contains duplicate column identifiers");
            }
        }
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
        if(value == null || !choices.contains(value))
        {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }

}
