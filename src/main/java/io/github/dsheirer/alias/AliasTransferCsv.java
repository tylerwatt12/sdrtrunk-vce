/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.alias;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.protocol.Protocol;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/** Fixed configuration-only CSV formats. No database IDs, credentials, or activity data. */
public final class AliasTransferCsv
{
    public enum Format { VCE, RADIOREFERENCE }
    public static final int MAX_ROWS = 10_000;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final List<String> HEADERS = List.of("format_version", "name", "description", "group", "color",
        "icon", "matcher_type", "protocol", "value", "minimum", "maximum", "text", "tones",
        "record_enabled", "scan_lists", "streaming_destinations", "stream_as_talkgroup");
    public static final List<String> RR_HEADERS = List.of("Decimal", "Hex", "Alpha Tag", "Mode", "Description",
        "Tag", "Category");
    private static final ObjectMapper JSON = new ObjectMapper();

    private AliasTransferCsv() {}

    public static List<AliasImportService.Input> read(String csv, Format format, AliasListDefinition list)
    {
        if(csv == null || csv.length() > MAX_BYTES) throw new IllegalArgumentException("CSV exceeds 8 MiB");
        if(csv.startsWith("\uFEFF")) csv = csv.substring(1);
        List<AliasImportService.Input> rows = new ArrayList<>();
        try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
            .parse(new StringReader(csv)))
        {
            List<String> expected = format == Format.VCE ? HEADERS : RR_HEADERS;
            if(!parser.getHeaderNames().equals(expected))
                throw new IllegalArgumentException("Expected exact CSV header: " + String.join(",", expected));
            for(CSVRecord row: parser)
            {
                if(rows.size() == MAX_ROWS) throw new IllegalArgumentException("CSV exceeds 10,000 aliases");
                try
                {
                    if(!row.isConsistent()) throw new IllegalArgumentException("Column count does not match header");
                    if(format == Format.RADIOREFERENCE)
                    {
                        Protocol protocol = switch(list.getFamily())
                        {
                            case P25 -> Protocol.APCO25;
                            case DMR -> Protocol.DMR;
                            case NXDN -> Protocol.NXDN;
                            default -> throw new IllegalArgumentException("RadioReference talkgroups require a P25, DMR, or NXDN list");
                        };
                        String mode = row.get("Mode");
                        if(!Set.of("A", "D", "T", "M", "AE", "Ae", "DE", "De", "TE", "Te").contains(mode))
                            throw new IllegalArgumentException("Unsupported RadioReference Mode");
                        Alias alias = new Alias(row.get("Alpha Tag"));
                        alias.setDescription(row.get("Description"));
                        alias.setGroup(row.get("Category"));
                        alias.setMatchIdentifier(new Talkgroup(protocol, Integer.parseInt(row.get("Decimal"))));
                        rows.add(new AliasImportService.Input(alias, true, true, mode.endsWith("E"), null, null));
                    }
                    else
                    {
                        if(!row.get("format_version").equals("1")) throw new IllegalArgumentException("Unsupported format_version");
                        Alias alias = new Alias(unescape(row.get("name")));
                        alias.setDescription(optional(unescape(row.get("description"))));
                        alias.setGroup(optional(unescape(row.get("group"))));
                        alias.setIconName(optional(unescape(row.get("icon"))));
                        alias.setColor(Integer.parseInt(row.get("color")));
                        alias.setMatchIdentifier(matcher(row));
                        Map<String,String> canonical = fields(alias, List.of(), List.of());
                        for(String column: List.of("matcher_type", "protocol", "value", "minimum", "maximum", "text", "tones"))
                        {
                            String submitted = column.equals("text") ? unescape(row.get(column)) : row.get(column);
                            if(!canonical.get(column).equals(submitted))
                                throw new IllegalArgumentException("Noncanonical or inapplicable matcher field: " + column);
                        }
                        alias.setRecordable(bool(row.get("record_enabled")));
                        if(!row.get("stream_as_talkgroup").isEmpty())
                        {
                            int id = Integer.parseInt(row.get("stream_as_talkgroup"));
                            if(id < 1 || id > 65535) throw new IllegalArgumentException("Invalid stream_as_talkgroup");
                            alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(id));
                        }
                        rows.add(new AliasImportService.Input(alias, false, true, false,
                            names(row.get("scan_lists")), names(row.get("streaming_destinations"))));
                    }
                }
                catch(Exception exception)
                {
                    throw new IllegalArgumentException("CSV row " + (row.getRecordNumber() + 1) + ": " + exception.getMessage(), exception);
                }
            }
        }
        catch(java.io.IOException | java.io.UncheckedIOException exception)
        {
            throw new IllegalArgumentException("Invalid CSV: check quoting and record structure", exception);
        }
        if(rows.isEmpty()) throw new IllegalArgumentException("Select a CSV containing at least one alias");
        return List.copyOf(rows);
    }

    private static AliasID matcher(CSVRecord row)
    {
        Protocol protocol = row.get("protocol").isEmpty() ? null : Protocol.valueOf(row.get("protocol"));
        return switch(row.get("matcher_type"))
        {
            case "TALKGROUP" -> new Talkgroup(protocol, integer(row, "value"));
            case "TALKGROUP_RANGE" -> new TalkgroupRange(protocol, integer(row, "minimum"), integer(row, "maximum"));
            case "RADIO_ID" -> new Radio(protocol, integer(row, "value"));
            case "RADIO_ID_RANGE" -> new RadioRange(protocol, integer(row, "minimum"), integer(row, "maximum"));
            case "STATUS" -> { UserStatusID id = new UserStatusID(); id.setStatus(integer(row, "value")); yield id; }
            case "UNIT_STATUS" -> { UnitStatusID id = new UnitStatusID(); id.setStatus(integer(row, "value")); yield id; }
            case "DCS" -> { Dcs id = new Dcs(); id.setDCSCode(DCSCode.valueOf(row.get("text"))); yield id; }
            case "ESN" -> { Esn id = new Esn(); id.setEsn(unescape(row.get("text"))); yield id; }
            case "TONES" -> {
                List<Tone> tones = new ArrayList<>();
                for(String value: row.get("tones").split(";", -1))
                {
                    String[] parts = value.split(":", -1);
                    if(parts.length != 2 || tones.size() >= 64) throw new IllegalArgumentException("Invalid tones");
                    int duration = Integer.parseInt(parts[1]);
                    AmbeTone tone = AmbeTone.valueOf(parts[0]);
                    if(duration < 1 || duration > 50 || !AmbeTone.ALL_VALID_TONES.contains(tone))
                        throw new IllegalArgumentException("Invalid tone or duration");
                    tones.add(new Tone(tone, duration));
                }
                yield new TonesID(new ToneSequence(tones));
            }
            default -> throw new IllegalArgumentException("Unsupported matcher_type");
        };
    }

    public static Map<String,String> fields(Alias alias, Collection<String> scans, Collection<String> streams)
    {
        Map<String,String> row = new LinkedHashMap<>();
        HEADERS.forEach(header -> row.put(header, ""));
        row.put("format_version", "1");
        row.put("name", text(alias.getName()));
        row.put("description", text(alias.getDescription()));
        row.put("group", text(alias.getGroup()));
        row.put("color", String.valueOf(alias.getColor()));
        row.put("icon", text(alias.getIconName()));
        row.put("record_enabled", String.valueOf(alias.isRecordable()));
        row.put("scan_lists", json(scans.stream().sorted().toList()));
        row.put("streaming_destinations", json(streams.stream().sorted().toList()));
        row.put("stream_as_talkgroup", alias.getStreamTalkgroupAlias() == null ? "" :
            String.valueOf(alias.getStreamTalkgroupAlias().getValue()));
        AliasID id = alias.getMatchIdentifier();
        row.put("matcher_type", id.getType().name());
        switch(id)
        {
            case TalkgroupRange value -> { row.put("protocol", value.getProtocol().name()); row.put("minimum", "" + value.getMinTalkgroup()); row.put("maximum", "" + value.getMaxTalkgroup()); }
            case Talkgroup value -> { row.put("protocol", value.getProtocol().name()); row.put("value", "" + value.getValue()); }
            case RadioRange value -> { row.put("protocol", value.getProtocol().name()); row.put("minimum", "" + value.getMinRadio()); row.put("maximum", "" + value.getMaxRadio()); }
            case Radio value -> { row.put("protocol", value.getProtocol().name()); row.put("value", "" + value.getValue()); }
            case UserStatusID value -> row.put("value", "" + value.getStatus());
            case UnitStatusID value -> row.put("value", "" + value.getStatus());
            case Dcs value -> row.put("text", value.getDCSCode().name());
            case Esn value -> row.put("text", text(value.getEsn()));
            case TonesID value -> row.put("tones", String.join(";", value.getToneSequence().getTones().stream()
                .map(tone -> tone.getAmbeTone().name() + ":" + tone.getDuration()).toList()));
            default -> throw new IllegalArgumentException("Unsupported matcher");
        }
        return row;
    }

    static List<String> identity(Alias alias)
    {
        Map<String,String> fields = fields(alias, List.of(), List.of());
        return List.of("matcher_type", "protocol", "value", "minimum", "maximum", "text", "tones").stream()
            .map(fields::get).toList();
    }

    public static String write(List<Map<String,String>> rows)
    {
        try
        {
            StringWriter writer = new StringWriter();
            writer.write('\uFEFF');
            try(CSVPrinter printer = new CSVPrinter(writer, CSVFormat.RFC4180.builder()
                .setHeader(HEADERS.toArray(String[]::new)).get()))
            {
                for(Map<String,String> row: rows)
                    printer.printRecord(HEADERS.stream().map(header ->
                        Set.of("name", "description", "group", "icon", "text").contains(header) ?
                            escape(row.get(header)) : row.get(header)).toList());
            }
            if(writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
                throw new IllegalArgumentException("Export exceeds 8 MiB");
            return writer.toString();
        }
        catch(java.io.IOException exception) { throw new IllegalArgumentException("Unable to write CSV", exception); }
    }

    private static int integer(CSVRecord row, String field) { return Integer.parseInt(row.get(field)); }
    private static boolean bool(String value)
    {
        if(!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("record_enabled must be true or false");
        return Boolean.parseBoolean(value);
    }
    public static List<String> names(String value)
    {
        if(value.isEmpty()) return List.of();
        try
        {
            var tree = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if(!tree.isArray()) throw new IllegalArgumentException("Names must be a JSON array");
            for(var element: tree) if(!element.isTextual()) throw new IllegalArgumentException("Assignment names must be strings");
            List<String> names = JSON.convertValue(tree, new TypeReference<List<String>>() {});
            if(names == null || names.size() > 500 || names.stream().anyMatch(name -> name == null || name.isBlank()) ||
                new HashSet<>(names).size() != names.size()) throw new IllegalArgumentException("Invalid or duplicate names");
            return List.copyOf(names);
        }
        catch(java.io.IOException exception) { throw new IllegalArgumentException("Names must be a JSON array, e.g. [\"Dispatch\"]"); }
    }
    private static String json(Object value)
    {
        try { return JSON.writeValueAsString(value); }
        catch(java.io.IOException exception) { throw new IllegalArgumentException(exception); }
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static String optional(String value) { return value.isEmpty() ? null : value; }
    // Version 1 escapes apostrophes as well as spreadsheet formulas so decoding is unambiguous.
    private static String escape(String value)
    {
        return value != null && !value.isEmpty() && "'=+-@\t\r\n".indexOf(value.charAt(0)) >= 0 ? "'" + value : text(value);
    }
    private static String unescape(String value) { return value.startsWith("'") ? value.substring(1) : value; }
}
