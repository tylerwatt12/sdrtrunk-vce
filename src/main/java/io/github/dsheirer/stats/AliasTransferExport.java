/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.AliasTransferCsv;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Produces one importable, configuration-only VCE Alias CSV in bounded batches.  The validated temporary file keeps
 * both the heap and the HTTP response bounded and lets the controller advertise an exact content length only after
 * every source row has been read and encoded successfully.
 */
final class AliasTransferExport
{
    /* The catalog streams per-alias collections for this bounded batch instead of materializing the whole export. */
    static final int BATCH_SIZE = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private AliasTransferExport()
    {
    }

    @FunctionalInterface
    interface BatchConsumer
    {
        void accept(List<Map<String,Object>> rows) throws SQLException;
    }

    @FunctionalInterface
    interface BatchSource
    {
        void forEach(BatchConsumer consumer) throws SQLException;
    }

    static Prepared prepare(long aliasListId, boolean filtered, BatchSource source) throws IOException, SQLException
    {
        return prepare(null, aliasListId, filtered, source);
    }

    static Prepared prepare(Path directory, long aliasListId, boolean filtered, BatchSource source)
        throws IOException, SQLException
    {
        if(aliasListId < 1)
        {
            throw new StatsApiException(400, "invalid_parameter", "list must be a positive integer", "list");
        }

        Objects.requireNonNull(source, "Alias export source cannot be null");
        Path temporary = directory != null ? Files.createTempFile(directory, "sdrtrunk-alias-export-", ".csv") :
            Files.createTempFile("sdrtrunk-alias-export-", ".csv");

        try
        {
            long[] rowCount = new long[1];

            try(BufferedWriter output = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(temporary,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING), StandardCharsets.UTF_8), 64 * 1024);
                AliasTransferCsv.StreamingWriter csv = AliasTransferCsv.streamingWriter(output))
            {
                source.forEach(rows ->
                {
                    if(rows == null || rows.size() > BATCH_SIZE)
                    {
                        throw new SQLException("Alias export source returned an invalid batch");
                    }

                    try
                    {
                        for(Map<String,Object> row: rows)
                        {
                            Map<String,String> configuration = configurationRow(row);
                            csv.write(configuration);
                            rowCount[0]++;
                        }
                    }
                    catch(IOException exception)
                    {
                        throw new UncheckedIOException(exception);
                    }
                });
                csv.flush();
            }
            catch(UncheckedIOException exception)
            {
                throw exception.getCause();
            }

            return new Prepared(temporary,
                "vce-alias-list-" + aliasListId + (filtered ? "-filtered" : "") + ".csv",
                rowCount[0], Files.size(temporary));
        }
        catch(IOException | SQLException | RuntimeException exception)
        {
            try
            {
                Files.deleteIfExists(temporary);
            }
            catch(IOException deleteError)
            {
                exception.addSuppressed(deleteError);
            }

            throw exception;
        }
    }

    static Map<String,String> configurationRow(Map<String,Object> source)
    {
        Objects.requireNonNull(source, "Alias export row cannot be null");
        Map<String,String> row = new LinkedHashMap<>();
        AliasTransferCsv.HEADERS.forEach(header -> row.put(header, ""));
        String matcherType = text(source.get("matcher_type"));
        row.put("format_version", "2");
        row.put("alias_list", text(source.get("alias_list_name")));
        row.put("name", text(source.get("name")));
        row.put("description", text(source.get("description")));
        row.put("group", text(source.get("group")));
        row.put("color", integer(source.get("color")));
        row.put("icon", text(source.get("icon_name")));
        row.put("matcher_type", matcherType);
        row.put("protocol", text(source.get("protocol")));
        row.put("value", switch(matcherType)
        {
            case "STATUS", "UNIT_STATUS" -> integer(source.get("numeric_value"));
            default -> integer(source.get("value"));
        });
        row.put("minimum", integer(source.get("min_value")));
        row.put("maximum", integer(source.get("max_value")));
        row.put("text", text(source.get("text_value")));
        row.put("tones", text(source.get("tone_sequence")));
        row.put("record_enabled", bool(source.get("record_enabled")));
        row.put("scan_lists", jsonNames(source.get("scan_lists"), "scan_lists"));
        row.put("streaming_destinations", jsonNames(source.get("broadcast_channels"),
            "broadcast_channels"));
        row.put("stream_as_talkgroup", integer(source.get("stream_as_talkgroup")));
        return row;
    }

    private static String jsonNames(Object value, String field)
    {
        if(value == null)
        {
            return "[]";
        }
        else if(!(value instanceof Collection<?>))
        {
            throw new IllegalArgumentException(field + " must be a collection");
        }

        Collection<?> values = (Collection<?>)value;

        if(values.isEmpty())
        {
            return "[]";
        }

        List<String> names = new ArrayList<>(values.size());

        for(Object candidate: values)
        {
            if(!(candidate instanceof String name) || name.isBlank())
            {
                throw new IllegalArgumentException(field + " contains an invalid name");
            }

            names.add(name);
        }

        names.sort(String::compareTo);

        try
        {
            return JSON.writeValueAsString(names);
        }
        catch(JsonProcessingException exception)
        {
            throw new IllegalArgumentException("Unable to encode " + field, exception);
        }
    }

    private static String bool(Object value)
    {
        if(value instanceof Boolean bool)
        {
            return Boolean.toString(bool);
        }
        else if(value instanceof Number number && (number.longValue() == 0 || number.longValue() == 1))
        {
            return Boolean.toString(number.longValue() == 1);
        }
        else if(value instanceof String text && ("true".equals(text.toLowerCase(Locale.ROOT)) ||
            "false".equals(text.toLowerCase(Locale.ROOT))))
        {
            return text.toLowerCase(Locale.ROOT);
        }

        throw new IllegalArgumentException("record_enabled is invalid");
    }

    private static String integer(Object value)
    {
        if(value == null)
        {
            return "";
        }
        else if(value instanceof Number number)
        {
            return Long.toString(number.longValue());
        }

        String text = String.valueOf(value);

        try
        {
            return Long.toString(Long.parseLong(text));
        }
        catch(NumberFormatException exception)
        {
            throw new IllegalArgumentException("Alias numeric configuration value is invalid", exception);
        }
    }

    private static String text(Object value)
    {
        return value != null ? String.valueOf(value) : "";
    }

    record Prepared(Path path, String fileName, long rowCount, long byteCount) implements AutoCloseable
    {
        Prepared
        {
            Objects.requireNonNull(path, "Prepared export path cannot be null");
            Objects.requireNonNull(fileName, "Prepared export file name cannot be null");

            if(rowCount < 0 || byteCount < 0)
            {
                throw new IllegalArgumentException("Prepared export sizes cannot be negative");
            }
        }

        @Override
        public void close() throws IOException
        {
            Files.deleteIfExists(path);
        }
    }
}
