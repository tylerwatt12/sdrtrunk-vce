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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * Complete, bounded CSV response assembled before HTTP headers are sent.  This prevents a successful-looking,
 * silently truncated download when a row or byte limit is exceeded.
 */
record StatsCsvExport(String fileName, byte[] content, int rowCount)
{
    static final int MAX_ROWS = 10_000;
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final byte[] UTF_8_BOM = {(byte)0xEF, (byte)0xBB, (byte)0xBF};
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    static StatsCsvExport create(String dataset, String scopeLabel, List<Map<String,Object>> rows)
        throws IOException
    {
        return create(dataset, scopeLabel, rows, MAX_BYTES);
    }

    static StatsCsvExport create(String dataset, String scopeLabel, List<Map<String,Object>> rows, int maximumBytes)
        throws IOException
    {
        if(rows.size() > MAX_ROWS)
        {
            throw new StatsApiException(413, "CSV export exceeds the " + MAX_ROWS + " row limit");
        }

        List<Column> columns = columns(dataset);
        LimitedOutputStream output = new LimitedOutputStream(maximumBytes);
        output.write(UTF_8_BOM);
        CSVFormat format = CSVFormat.RFC4180.builder()
            .setHeader(columns.stream().map(Column::header).toArray(String[]::new))
            .setRecordSeparator("\r\n")
            .get();

        try(OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            CSVPrinter printer = new CSVPrinter(writer, format))
        {
            for(Map<String,Object> row: rows)
            {
                List<Object> values = new ArrayList<>(columns.size());

                for(Column column: columns)
                {
                    values.add(csvSafe(column.value().apply(row)));
                }

                printer.printRecord(values);
            }
        }
        catch(SizeLimitException e)
        {
            throw new StatsApiException(413, "CSV export exceeds the " +
                (MAX_BYTES / (1024 * 1024)) + " MiB size limit");
        }

        return new StatsCsvExport(fileName(dataset, scopeLabel), output.toByteArray(), rows.size());
    }

    private static List<Column> columns(String dataset)
    {
        return switch(dataset)
        {
            case "system-talkgroups" -> List.of(
                text("protocol", "protocol"), text("system_name", "configured_system"),
                text("scope", "scope_token"), text("wacn_hex", row -> p25Hex(row, "wacn", 5)),
                number("wacn", "wacn"), text("system_id_hex", row -> p25Hex(row, "system_id", 3)),
                number("system_id", "system_id"), number("network_id", "network_id"),
                number("talkgroup_id", "talkgroup_id"),
                text("address_domain", StatsCsvExport::addressDomain),
                text("formatted_talkgroup_id", row -> nxdnDisplay(row, "talkgroup_id")),
                text("identity_type", row -> numberValue(row.get("target_kind_code")) == 3 ? "patch_group" :
                    "talkgroup"), text("alias", "alias_name"), text("description", "alias_description"),
                text("group", "alias_group"), text("alias_list", "alias_list_name"),
                number("calls", "call_count"),
                number("recorded", "recorded_count"), number("streamed", "streamed_count"),
                number("encrypted", "encrypted_count"), number("signaling_observations", "signaling_count"),
                time("first_seen_utc", "first_seen_ms"), time("last_seen_utc", "last_seen_ms")
            );
            case "system-radios" -> List.of(
                text("protocol", "protocol"), text("system_name", "configured_system"),
                text("scope", "scope_token"), text("wacn_hex", row -> p25Hex(row, "wacn", 5)),
                number("wacn", "wacn"), text("system_id_hex", row -> p25Hex(row, "system_id", 3)),
                number("system_id", "system_id"), number("network_id", "network_id"),
                number("radio_id", "radio_id"),
                text("address_domain", StatsCsvExport::addressDomain),
                text("formatted_radio_id", row -> nxdnDisplay(row, "radio_id")),
                text("alias", "alias_name"), text("description", "alias_description"),
                text("group", "alias_group"), text("alias_list", "alias_list_name"),
                text("talker_alias", "last_talker_alias"),
                time("talker_alias_seen_utc", "last_talker_alias_seen_ms"),
                number("last_talkgroup_id", "last_talkgroup_id"),
                text("last_talkgroup_alias", "last_talkgroup_alias_name"),
                number("affiliated_talkgroup_id", "affiliated_talkgroup_id"),
                text("affiliated_talkgroup_alias", "affiliated_talkgroup_alias_name"),
                time("affiliation_confirmed_utc", "affiliation_confirmed_at_ms"), number("calls", "call_count"),
                number("recorded", "recorded_count"), number("streamed", "streamed_count"),
                number("encrypted", "encrypted_count"), time("first_seen_utc", "first_seen_ms"),
                time("last_seen_utc", "last_seen_ms")
            );
            case "site-channels" -> List.of(
                text("protocol", "site_protocol"), text("system_name", "site_system_name"),
                text("scope", "site_scope_token"), text("site_guid", "site_guid"),
                text("site_name", "site_name"), text("wacn_hex", row -> siteP25Hex(row, "site_wacn", 5)),
                number("wacn", "site_wacn"),
                text("system_id_hex", row -> siteP25Hex(row, "site_system_id", 3)),
                number("system_id", "site_system_id"), number("network_id", "site_network_id"),
                text("rfss_hex", row -> siteP25Hex(row, "site_rfss", 2)), number("rfss", "site_rfss"),
                text("site_id_hex", row -> siteP25Hex(row, "site_number", 2)),
                number("site_id", row -> firstValue(row, "site_number", "site_id")),
                text("nac_hex", row -> siteP25Hex(row, "site_nac", 3)), number("nac", "site_nac"),
                number("ran", "site_ran"), text("channel", row -> firstValue(row, "channel_number",
                    "channel_key")), text("p25_descriptor", "descriptor"),
                text("inbound_channel", "inbound_channel_number"), number("timeslot", "timeslot"),
                number("tdma", "tdma"), number("timeslots", "timeslots"),
                number("downlink_hz", row -> firstValue(row, "downlink_hz",
                    "frequency_hz")), text("downlink_mhz", row -> megahertz(firstValue(row, "downlink_hz",
                    "frequency_hz"))), number("uplink_hz", "uplink_hz"),
                text("uplink_mhz", row -> megahertz(row.get("uplink_hz"))), text("callsign", "callsign"),
                text("use", StatsCsvExport::channelUse), text("source", StatsCsvExport::channelSource),
                text("current_tags", "current_tags"), text("observed_tags", "tags"), text("state", "state"),
                number("control_observations", "control_observations"),
                number("alternate_control_observations", "alternate_control_observations"),
                number("data_announcement_observations", "data_announcement_observations"),
                number("voice_grant_observations", "voice_grant_observations"),
                number("data_grant_observations", "data_grant_observations"),
                number("observations", "observation_count"), time("first_seen_utc", "first_seen_ms"),
                time("last_seen_utc", "last_seen_ms")
            );
            case "site-neighbors" -> List.of(
                text("protocol", "site_protocol"), text("system_name", "site_system_name"),
                text("source_scope", "site_scope_token"), text("source_site_guid", "site_guid"),
                text("source_site_name", "site_name"), text("entry_type", "entry_type"),
                text("neighbor_name", "neighbor_name"), text("neighbor_guid", "neighbor_guid"),
                text("wacn_hex", row -> siteP25Hex(row, "wacn", 5)), number("wacn", "wacn"),
                text("system_id_hex", row -> siteP25Hex(row, "system_id", 3)),
                number("system_id", "system_id"), number("network_id", "network_id"),
                text("rfss_hex", row -> siteP25Hex(row, "rfss", 2)), number("rfss", "rfss"),
                text("site_id_hex", row -> siteP25Hex(row, "site", 2)),
                number("site_id", row -> firstValue(row, "site", "site_id")),
                text("lra_hex", row -> siteP25Hex(row, "lra", 2)), number("lra", "lra"),
                text("channel", row -> firstValue(row, "channel_descriptor", "channel_number")),
                number("control_frequency_hz", row -> firstValue(row, "downlink_hz", "frequency_hz")),
                text("control_frequency_mhz", row -> megahertz(firstValue(row, "downlink_hz", "frequency_hz"))),
                number("uplink_hz", "uplink_hz"),
                text("uplink_mhz", row -> megahertz(row.get("uplink_hz"))),
                text("variant", StatsCsvExport::variant),
                text("site_classification", StatsCsvExport::siteClassification),
                number("band_count", "band_count"),
                number("has_fdma", "has_fdma"), number("has_tdma", "has_tdma"),
                number("has_unknown_mode", "has_unknown"),
                text("status", StatsCsvExport::neighborStatus), text("state", "state"),
                number("observations", "observation_count"), time("first_seen_utc", "first_seen_ms"),
                time("last_seen_utc", "last_seen_ms")
            );
            case "conventional-channels" -> List.of(
                text("protocol", row -> protocol(row.get("protocol_code"))), text("context", "context_key"),
                text("channel_name", "channel_name"), text("alias_list", "alias_list_name"),
                text("decoder", "decoder"), number("configured_frequency_hz", "primary_frequency_hz"),
                text("configured_frequency_mhz", row -> megahertz(row.get("primary_frequency_hz"))),
                number("observed_frequency_hz", "frequency_hz"),
                text("observed_frequency_mhz", row -> megahertz(row.get("frequency_hz"))),
                number("timeslot", row -> nonNegative(row.get("timeslot"))), number("nac", "nac"),
                number("calls", "call_count"),
                text("last_event_type", StatsCsvExport::lastEventType), time("first_seen_utc", "first_seen_ms"),
                time("last_seen_utc", "last_seen_ms")
            );
            case "conventional-talkgroups" -> List.of(
                text("protocol", row -> "DMR"), text("context", "context_key"),
                text("alias_list", "alias_list_name"), number("frequency_hz", "frequency_hz"),
                text("frequency_mhz", row -> megahertz(row.get("frequency_hz"))),
                number("timeslot", "timeslot"), number("talkgroup_id", "talkgroup_id"),
                text("alias", "alias_name"), text("description", "alias_description"),
                text("group", "alias_group"), number("calls", "call_count"),
                number("encrypted", "encrypted_count"), number("last_source_radio_id", "last_source_radio_id"),
                text("last_source_alias", "last_source_alias_name"), time("first_seen_utc", "first_seen_ms"),
                time("last_seen_utc", "last_seen_ms")
            );
            case "conventional-radios" -> List.of(
                text("protocol", row -> "DMR"), text("context", "context_key"),
                text("alias_list", "alias_list_name"), number("frequency_hz", "frequency_hz"),
                text("frequency_mhz", row -> megahertz(row.get("frequency_hz"))),
                number("timeslot", "timeslot"), number("radio_id", "radio_id"),
                text("alias", "alias_name"), text("description", "alias_description"),
                text("group", "alias_group"), number("calls", "call_count"),
                number("source_calls", "source_call_count"), number("target_calls", "target_call_count"),
                number("group_calls", "group_call_count"), number("private_calls", "private_call_count"),
                number("encrypted", "encrypted_count"), number("last_talkgroup_id", "last_talkgroup_id"),
                text("last_talkgroup_alias", "last_talkgroup_alias_name"),
                number("last_peer_radio_id", "last_peer_radio_id"),
                text("last_peer_alias", "last_peer_alias_name"), time("first_seen_utc", "first_seen_ms"),
                time("last_seen_utc", "last_seen_ms")
            );
            case "signal-health" -> List.of(
                text("protocol", "protocol"), text("system_name", row -> firstValue(row,
                    "configured_system", "channel_name")), text("site_guid", "guid"),
                text("site_name", "channel_name"), text("wacn_hex", row -> p25Hex(row, "wacn", 5)),
                number("wacn", "wacn"), text("system_id_hex", row -> p25Hex(row, "system_id", 3)),
                number("system_id", "system_id"), number("network_id", "network_id"),
                text("rfss_hex", row -> p25Hex(row, "rfss", 2)), number("rfss", "rfss"),
                text("site_id_hex", row -> p25Hex(row, "site", 2)),
                number("site_id", row -> firstValue(row, "site", "site_id")),
                text("nac_hex", row -> p25Hex(row, "nac", 3)), number("nac", "nac"),
                number("ran", "ran"), number("frequency_hz", "quality_frequency_hz"),
                text("frequency_mhz", row -> megahertz(row.get("quality_frequency_hz"))),
                time("observed_utc", "last_observed_ms"), number("sample_age_seconds", "sample_age_seconds"),
                number("signal_dbfs", "signal_dbfs"),
                number("average_signal_dbfs", "average_signal_dbfs"),
                number("minimum_signal_dbfs", "minimum_signal_dbfs"),
                number("maximum_signal_dbfs", "maximum_signal_dbfs"),
                number("decode_health_pct", "decode_health_pct"),
                number("valid_frames_rolling_30s", "valid_frames"),
                number("invalid_frames_rolling_30s", "invalid_frames"),
                number("corrected_bits_rolling_30s", "corrected_bits"),
                number("sync_loss_bits_rolling_30s", "sync_loss_bits"),
                number("dropped_bits_rolling_30s", "dropped_bits"),
                time("last_valid_decode_utc", "last_valid_decode_ms")
            );
            case "site-quality" -> List.of(
                text("protocol", "protocol"), text("system_name", row -> firstValue(row,
                    "configured_system", "channel_name")), text("site_guid", "guid"),
                text("site_name", "channel_name"), text("wacn_hex", row -> p25Hex(row, "wacn", 5)),
                number("wacn", "wacn"), text("system_id_hex", row -> p25Hex(row, "system_id", 3)),
                number("system_id", "system_id"), number("network_id", "network_id"),
                text("rfss_hex", row -> p25Hex(row, "rfss", 2)), number("rfss", "rfss"),
                text("site_id_hex", row -> p25Hex(row, "site", 2)),
                number("site_id", row -> firstValue(row, "site", "site_id")),
                text("nac_hex", row -> p25Hex(row, "nac", 3)), number("nac", "nac"),
                number("ran", "ran"), text("range", "range"),
                number("bucket_ms", "bucket_ms"), time("bucket_start_utc", "time_ms"),
                time("bucket_end_utc", "bucket_end_ms"),
                time("last_observed_utc", "last_observed_ms"),
                number("frequency_hz", "frequency_hz"),
                text("frequency_mhz", row -> megahertz(row.get("frequency_hz"))),
                number("frequency_count", "frequency_count"), number("sample_count", "sample_count"),
                number("average_signal_dbfs", "average_signal_dbfs"),
                number("minimum_signal_dbfs", "minimum_signal_dbfs"),
                number("maximum_signal_dbfs", "maximum_signal_dbfs"),
                number("average_decode_health_pct", "decode_health_pct"),
                number("minimum_decode_health_pct", "minimum_decode_health_pct"),
                number("maximum_decode_health_pct", "maximum_decode_health_pct")
            );
            case "aliases" -> List.of(
                number("alias_id", "alias_id"), number("alias_list_id", "alias_list_id"),
                text("alias_list", "alias_list_name"),
                text("family", row -> StatsApiV1Payload.aliasFamily(String.valueOf(row.get("family")))),
                text("name", "name"),
                text("description", "description"), text("group", "group"), number("color", "color"),
                text("icon", "icon_name"), number("stream_as_talkgroup", "stream_as_talkgroup"),
                number("record_enabled", "record_enabled"), number("priority", "priority"),
                text("identity_type", "identity_type"),
                text("matcher_type", row -> StatsApiV1Payload.aliasMatcherType(
                    String.valueOf(row.get("matcher_type")))),
                text("matcher", "matcher_label"), text("protocol", StatsCsvExport::aliasProtocol),
                text("protocol_variant", StatsCsvExport::aliasProtocolVariant),
                text("identifier", "identifier_display"), number("value", "value"),
                number("min_value", "min_value"), number("max_value", "max_value"),
                text("text_value", "text_value"), number("numeric_value", "numeric_value"),
                text("tone_sequence", row -> row.get("tone_sequence") instanceof String value ?
                    value.toLowerCase(Locale.ROOT) : ""), number("exact", "exact"),
                number("ranged", "ranged"),
                text("broadcast_channels", row -> row.get("broadcast_channels") instanceof List<?> values ?
                    String.join("; ", values.stream().map(String::valueOf).toList()) : ""),
                text("metrics_state", "metrics_state"), number("coverage_scopes", "coverage_scope_count"),
                number("observed_scopes", "observed_scope_count"), number("calls", "call_count"),
                number("recorded", "recorded_count"), number("streamed", "streamed_count"),
                number("encrypted_evidence", "encrypted_evidence_count"),
                number("grants", "grant_count"), number("joins", "join_count"),
                number("emergencies", "emergency_count"), number("registrations", "register_count"),
                number("logouts", "logout_count"), number("denials", "denial_count"),
                number("data", "data_count"), number("other_signaling", "other_signaling_count"),
                number("relationships", "relationship_count"),
                number("join_relationships", "join_relationship_count"),
                number("current_affiliations", "current_affiliation_count"),
                time("first_evidence_utc", "first_evidence_ms"),
                time("last_evidence_utc", "last_evidence_ms")
            );
            default -> throw new StatsApiException(400, "Unsupported CSV dataset");
        };
    }

    private static Column text(String header, String key)
    {
        return text(header, row -> row.get(key));
    }

    private static Column text(String header, Function<Map<String,Object>,Object> value)
    {
        return new Column(header, value);
    }

    private static Column number(String header, String key)
    {
        return number(header, row -> row.get(key));
    }

    private static Column number(String header, Function<Map<String,Object>,Object> value)
    {
        return new Column(header, value);
    }

    private static Column time(String header, String key)
    {
        return new Column(header, row -> utc(row.get(key)));
    }

    private static String utc(Object value)
    {
        return value instanceof Number number && number.longValue() > 0 ?
            Instant.ofEpochMilli(number.longValue()).toString() : "";
    }

    private static String megahertz(Object value)
    {
        return value instanceof Number number && number.longValue() > 0 ?
            BigDecimal.valueOf(number.longValue()).movePointLeft(6).stripTrailingZeros().toPlainString() : "";
    }

    private static String p25Hex(Map<String,Object> row, String key, int width)
    {
        return "P25".equals(row.get("protocol")) ? hex(row.get(key), width) : "";
    }

    private static String siteP25Hex(Map<String,Object> row, String key, int width)
    {
        return "P25".equals(row.get("site_protocol")) ? hex(row.get(key), width) : "";
    }

    private static String nxdnDisplay(Map<String,Object> row, String key)
    {
        if(!"NXDN".equals(row.get("protocol")) || numberValue(row.get("identity_domain_code")) != 2 ||
            !(row.get(key) instanceof Number number))
        {
            return "";
        }

        int identifier = number.intValue();
        return String.format(Locale.ROOT, "%02d-%04d", (identifier >> 11) & 31, identifier & 2047);
    }

    private static String hex(Object value, int width)
    {
        return value instanceof Number number ? String.format(Locale.ROOT, "%0" + width + "X",
            number.longValue()) : "";
    }

    private static String protocol(Object value)
    {
        return switch((int)numberValue(value))
        {
            case 1, 2 -> "P25";
            case 3 -> "DMR";
            case 4 -> "NXDN";
            case 10 -> "NBFM";
            case 11 -> "AM";
            default -> "Unknown";
        };
    }

    private static StatsApiProtocol apiProtocol(Map<String,Object> row)
    {
        Object code = row.get("protocol_code");
        StatsApiProtocol protocol = code instanceof Number number ? StatsApiProtocol.fromCode(number.longValue()) :
            StatsApiProtocol.UNKNOWN;

        if(protocol == StatsApiProtocol.UNKNOWN)
        {
            protocol = StatsApiProtocol.fromName(String.valueOf(firstValue(row, "protocol", "site_protocol")));
        }

        return protocol;
    }

    private static String addressDomain(Map<String,Object> row)
    {
        return apiProtocol(row).addressDomain(numberValue(row.get("identity_domain_code")));
    }

    private static String variant(Map<String,Object> row)
    {
        return apiProtocol(row).variant(numberValue(row.get("variant_code")));
    }

    private static String aliasProtocol(Map<String,Object> row)
    {
        return row.get("protocol") instanceof String value && !value.isBlank() ?
            StatsApiProtocol.fromName(value).wireName() : "";
    }

    private static String aliasProtocolVariant(Map<String,Object> row)
    {
        return switch(row.get("protocol"))
        {
            case String value when "APCO25".equals(value) -> "phase_1";
            case String value when "APCO25_PHASE2".equals(value) -> "phase_2";
            default -> "";
        };
    }

    private static String siteClassification(Map<String,Object> row)
    {
        return apiProtocol(row).siteClassification(numberValue(row.get("identity_domain_code")));
    }

    private static String lastEventType(Map<String,Object> row)
    {
        long code = numberValue(row.get("last_event_type_code"));
        io.github.dsheirer.module.decode.event.DecodeEventType[] values =
            io.github.dsheirer.module.decode.event.DecodeEventType.values();
        return code >= 1 && code <= values.length ? values[(int)code - 1].name().toLowerCase(Locale.ROOT) : "";
    }

    private static Object firstValue(Map<String,Object> row, String... keys)
    {
        for(String key: keys)
        {
            Object value = row.get(key);

            if(value != null && !(value instanceof String text && text.isBlank()))
            {
                return value;
            }
        }

        return "";
    }

    private static long numberValue(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static Object nonNegative(Object value)
    {
        return value instanceof Number number && number.longValue() >= 0 ? value : "";
    }

    private static String channelUse(Map<String,Object> row)
    {
        long flags = numberValue(row.get("role_flags"));
        List<String> values = new ArrayList<>();
        if((flags & 1) != 0) values.add("Current Control");
        if((flags & 2) != 0) values.add("Alternate Control");
        if((flags & 4) != 0) values.add("Traffic");
        return String.join(", ", values);
    }

    private static String channelSource(Map<String,Object> row)
    {
        long flags = numberValue(row.get("role_flags"));
        List<String> values = new ArrayList<>();
        if((flags & 8) != 0) values.add("Over The Air");
        if((flags & 16) != 0) values.add("Configured LCN Map");
        if((flags & 32) != 0) values.add("Broadcast Frequency");
        return String.join(", ", values);
    }

    private static String neighborStatus(Map<String,Object> row)
    {
        Object status = row.get("status");
        if(status != null) return String.valueOf(status);
        long flags = numberValue(row.get("status_flags"));
        List<String> values = new ArrayList<>();
        if((flags & 1) != 0) values.add("Linked");
        if((flags & 2) != 0) values.add("Isolated");
        return String.join(", ", values);
    }

    private static Object csvSafe(Object value)
    {
        if(!(value instanceof CharSequence characters))
        {
            return value != null ? value : "";
        }

        String text = characters.toString();
        int candidate = 0;
        while(candidate < text.length() && Character.isWhitespace(text.charAt(candidate))) candidate++;
        boolean leadingLineBreak = !text.isEmpty() && (text.charAt(0) == '\t' || text.charAt(0) == '\r' ||
            text.charAt(0) == '\n');
        boolean formula = candidate < text.length() && (text.charAt(candidate) == '=' ||
            text.charAt(candidate) == '+' || text.charAt(candidate) == '-' || text.charAt(candidate) == '@');

        if(leadingLineBreak || formula)
        {
            return "'" + text;
        }

        return text;
    }

    private static String fileName(String dataset, String scopeLabel)
    {
        String scope = scopeLabel != null ? scopeLabel : "all";
        String safe = scope.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if(safe.isBlank()) safe = "all";
        if(safe.length() > 48) safe = safe.substring(0, 48).replaceAll("-+$", "");
        return "sdrtrunk-" + dataset + "-" + safe + "-" + FILE_TIME.format(Instant.now()) + ".csv";
    }

    private record Column(String header, Function<Map<String,Object>,Object> value)
    {
    }

    private static final class LimitedOutputStream extends OutputStream
    {
        private final int mLimit;
        private final ByteArrayOutputStream mOutput = new ByteArrayOutputStream();

        private LimitedOutputStream(int limit)
        {
            mLimit = limit;
        }

        @Override
        public void write(int value) throws IOException
        {
            ensureCapacity(1);
            mOutput.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException
        {
            ensureCapacity(length);
            mOutput.write(values, offset, length);
        }

        private void ensureCapacity(int additionalBytes) throws SizeLimitException
        {
            if(additionalBytes > mLimit - mOutput.size())
            {
                throw new SizeLimitException();
            }
        }

        private byte[] toByteArray()
        {
            return mOutput.toByteArray();
        }
    }

    private static final class SizeLimitException extends IOException
    {
    }
}
