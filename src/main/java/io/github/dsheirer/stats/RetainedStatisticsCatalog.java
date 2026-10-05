/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static io.github.dsheirer.stats.StatsSqlRows.queryRows;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.stats.activity.RetainedSiteKey;
import io.github.dsheirer.stats.activity.ReceiverActivityMaintenance;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded, read-only target discovery for retained-statistics administration. */
final class RetainedStatisticsCatalog
{
    private static final long CURRENT_STATE_WINDOW_MS = 6L * 60 * 60 * 1000;
    private static final String CHANNEL_LABEL = "coalesce(nullif(trim(config.name), ''), " +
        "nullif(trim(config.site_name), ''), config.configuration_id)";
    private static final String SITE_LABEL = "coalesce(nullif(trim(config.site_name), ''), " +
        "nullif(trim(config.name), ''), config.configuration_id)";
    private static final String CHANNEL_CONTEXT = "config.system_name AS radio_system_name, " +
        "config.site_name, config.name AS channel_name, config.alias_list_id, " +
        "list.name AS alias_list_name";
    private static final String CHANNEL_SEARCH = "lower(" + CHANNEL_LABEL + " || ' ' || " +
        "coalesce(config.system_name,'') || ' ' || coalesce(config.site_name,'') || ' ' || " +
        "coalesce(config.name,'') || ' ' || coalesce(list.name,'') || ' ' || " +
        "coalesce(CAST(config.primary_frequency_hz AS TEXT),'') || ' ' || " +
        "coalesce(printf('%.6f',config.primary_frequency_hz/1000000.0),'') || ' ' || " +
        "config.configuration_id) LIKE ? ESCAPE '\\'";
    private static final String SYSTEM_LABEL = "coalesce(" +
        StatsSystemNameResolver.configuredNameSql("system") + ", system.system_key)";
    private static final String SITE_SELECT = """
        SELECT config.configuration_id,
               CASE WHEN config.channel_kind='TRUNKED' AND p25.channel_id IS NULL
                         AND trunked.channel_id IS NULL
                    THEN %s || ' · no current site state' ELSE %s END AS label,
               %s, config.channel_kind,
               config.decoder_type AS protocol, system.system_key AS radio_system_key,
               channel.id AS channel_id, channel.first_seen_ms AS channel_first_seen_ms,
               channel.radio_system_id, p25.channel_id IS NOT NULL AS p25_snapshot,
               p25.rfss, p25.site, p25.first_seen_ms AS p25_first_seen_ms,
               trunked.first_seen_ms AS trunked_first_seen_ms,
               trunked.protocol_code, trunked.variant_code,
               trunked.observed_location_category_code,
               trunked.observed_network_id, trunked.observed_system_id,
               trunked.observed_site_id, trunked.observed_ran, trunked.observed_model_code,
               coalesce(p25.last_seen_ms,trunked.last_seen_ms,channel.last_seen_ms) AS last_seen_ms
        FROM receiver_channel channel
        JOIN configuration_channel config ON config.configuration_id=channel.configuration_id
        LEFT JOIN alias_list list ON list.id=config.alias_list_id
        LEFT JOIN radio_system system ON system.id=channel.radio_system_id
        LEFT JOIN p25_site_snapshot p25 ON p25.channel_id=channel.id
        LEFT JOIN trunked_site_snapshot trunked ON trunked.channel_id=channel.id
        WHERE config.channel_kind IN ('CONVENTIONAL','TRUNKED')
        """.formatted(SITE_LABEL, SITE_LABEL, CHANNEL_CONTEXT);
    private static final String IDENTITY_KEY_SQL = "'v1-' || CASE summary.identity_kind_code " +
        "WHEN 1 THEN 'g' WHEN 2 THEN 'r' END || '-' || " +
        "CASE WHEN summary.home_wacn=-1 THEN 'x' ELSE printf('%05x',summary.home_wacn) END || '-' || " +
        "CASE WHEN summary.home_system_id=-1 THEN 'x' ELSE printf('%03x',summary.home_system_id) END || '-' || " +
        "summary.identity_id";
    private static final Set<String> SITE_DATA_TYPES = Set.of("site_state", "frequencies", "band_plans",
        "foreign_band_plans", "neighbors", "patches", "control_quality");
    private static final Set<String> SCOPED_DATA_TYPES = Set.of("all", "site_state", "band_plans",
        "foreign_band_plans", "neighbors", "patches", "control_quality", "relationships",
        "affiliations", "call_activity", "signaling_activity", "hourly_history", "detailed_events",
        "alias_activity", "issi_assignment_history");

    private final Path mDatabasePath;

    RetainedStatisticsCatalog(Path databasePath)
    {
        mDatabasePath = Objects.requireNonNull(databasePath);
    }

    Page sources(String kind, String search, int limit, int offset)
    {
        if("alias_activity".equals(kind))
        {
            boolean matched = search == null || "alias activity".contains(search.toLowerCase(Locale.ROOT));
            Map<String,Object> source = new LinkedHashMap<>();
            source.put("source_kind", "alias_activity");
            source.put("source_key", "alias_activity");
            source.put("label", "Alias Activity");
            source.put("target", scopedTarget("alias_activity", null, null, null, "alias_activity", null,
                List.of("summary")));
            return page(matched && offset == 0 ? List.of(source) : List.of(), limit, offset, matched ? 1 : 0);
        }
        if(!"radio_system".equals(kind) && !"saved_channel".equals(kind))
        {
            throw invalid("kind", "Select a supported statistics source");
        }
        return read(connection -> {
            String sql;
            List<Object> parameters = new ArrayList<>();
            if("radio_system".equals(kind))
            {
                sql = "SELECT system.system_key AS source_key, " + SYSTEM_LABEL +
                    " AS label, " + SYSTEM_LABEL + " AS radio_system_name, " +
                    "CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR' " +
                    "WHEN 4 THEN 'NXDN' END AS protocol, system.last_seen_ms " +
                    "FROM radio_system system WHERE 1=1";
                if(search != null)
                {
                    sql += " AND (lower(" + SYSTEM_LABEL + " || ' ' || system.system_key) " +
                        "LIKE ? ESCAPE '\\' OR " + systemAliasListSearch() + ")";
                    parameters.add(like(search));
                    parameters.add(like(search));
                }
            }
            else
            {
                sql = "SELECT config.configuration_id AS source_key, " + CHANNEL_LABEL +
                    " AS label, " + CHANNEL_CONTEXT + ", config.decoder_type AS protocol, " +
                    "config.channel_kind, config.primary_frequency_hz, " +
                    "system.system_key AS radio_system_key, " +
                    "channel.last_seen_ms FROM receiver_channel channel " +
                    "JOIN configuration_channel config ON config.configuration_id=channel.configuration_id " +
                    "LEFT JOIN alias_list list ON list.id=config.alias_list_id " +
                    "LEFT JOIN radio_system system ON system.id=channel.radio_system_id " +
                    "WHERE config.channel_kind='CONVENTIONAL'";
                if(search != null)
                {
                    sql += " AND " + CHANNEL_SEARCH;
                    parameters.add(like(search));
                }
            }
            String countSql = "SELECT COUNT(*) FROM (" + sql + ")";
            long total = count(connection, countSql, parameters);
            sql += " ORDER BY lower(label), source_key LIMIT ? OFFSET ?";
            parameters.add(limit + 1);
            parameters.add(offset);
            List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
            for(Map<String,Object> row: rows)
            {
                row.put("source_kind", kind);
                if("radio_system".equals(kind))
                {
                    WebEntityRef.put(row, WebEntityRef.radioSystem(String.valueOf(row.get("source_key"))));
                }
                else
                {
                    WebEntityRef.put(row, WebEntityRef.channel(String.valueOf(row.get("source_key"))));
                }
            }
            if("radio_system".equals(kind))
            {
                enrichSystemListContext(connection, rows, "source_key");
            }
            return page(rows, limit, offset, total);
        });
    }

    Page sites(String sourceKind, String sourceKey, String search, int limit, int offset)
    {
        requireSourceKind(sourceKind);
        requireKey(sourceKey, "source_key");
        return read(connection -> sitePage(connection, sourceKind, sourceKey, search, limit, offset));
    }

    ReceiverActivityMaintenance.Preview preview(ScopedData target)
    {
        return read(connection -> ReceiverActivityMaintenance.preview(connection, target));
    }

    /** Captured once when a job is submitted; polling never repeats source discovery queries. */
    String jobLabel(StatsDatabaseMaintenanceRequest.DeletionTarget target)
    {
        if(!(target instanceof ScopedData scoped)) return "Saved statistics";
        if("alias_activity".equals(scoped.sourceKind())) return "Alias Activity";
        return read(connection -> {
            List<Map<String,Object>> sources = "radio_system".equals(scoped.sourceKind()) ?
                queryRows(connection, "SELECT " + SYSTEM_LABEL + " AS label FROM radio_system system " +
                    "WHERE system.system_key=?", scoped.sourceKey()) :
                queryRows(connection, "SELECT " + CHANNEL_LABEL + " AS label FROM configuration_channel config " +
                    "WHERE config.configuration_id=?", scoped.sourceKey());
            String scope = sources.isEmpty() ? scoped.sourceKey() : String.valueOf(sources.getFirst().get("label"));
            if(scoped.siteConfigurationId() != null && !scoped.siteConfigurationId().equals(scoped.sourceKey()))
            {
                List<Map<String,Object>> sites = queryRows(connection, "SELECT " + SITE_LABEL + " AS label " +
                    "FROM configuration_channel config WHERE config.configuration_id=?", scoped.siteConfigurationId());
                scope += " · " + (sites.isEmpty() ? scoped.siteConfigurationId() : sites.getFirst().get("label"));
            }
            String type = switch(scoped.dataType())
            {
                case "all" -> "All retained data";
                case "site_state" -> "Site state";
                case "frequencies" -> "Frequencies";
                case "band_plans" -> "Band plans";
                case "foreign_band_plans" -> "Foreign band plans";
                case "neighbors" -> "Neighbors";
                case "patches" -> "Patch groups";
                case "control_quality" -> "Quality history";
                case "radios" -> "Radio IDs";
                case "talkgroups" -> "Talkgroups";
                case "relationships" -> "Radio relationships";
                case "issi_assignment_history" -> "ISSI assignment history";
                case "affiliations" -> "Affiliations & presence";
                case "call_activity" -> "Call totals";
                case "signaling_activity" -> "Signaling totals";
                case "hourly_history" -> "Hourly history";
                case "detailed_events" -> "Detailed events";
                default -> "Saved statistics";
            };
            return type + " · " + scope;
        });
    }

    Page results(String sourceKind, String sourceKey, String dataType, String siteConfigurationId,
                 String search, int limit, int offset)
    {
        if("alias_activity".equals(sourceKind))
        {
            if(sourceKey != null && !"alias_activity".equals(sourceKey))
                throw invalid("source_key", "Alias Activity has no source selection");
            if(!"alias_activity".equals(dataType) || siteConfigurationId != null)
                throw invalid("data_type", "Select Alias Activity");
            return read(connection -> aliasActivity(connection, search, limit, offset));
        }
        requireSourceKind(sourceKind);
        requireKey(sourceKey, "source_key");
        if(!SCOPED_DATA_TYPES.contains(dataType) &&
            !List.of("frequencies", "radios", "talkgroups", "sites", "channels", "systems")
                .contains(dataType))
        {
            throw invalid("data_type", "Select a supported data type");
        }
        if("alias_activity".equals(dataType))
        {
            throw invalid("data_type", "Alias Activity is a receiver-wide selection");
        }
        if("saved_channel".equals(sourceKind) && !Set.of("all", "frequencies", "radios", "talkgroups",
            "call_activity", "signaling_activity", "hourly_history", "detailed_events", "sites", "channels")
            .contains(dataType))
        {
            throw invalid("data_type", "This data type requires a radio system");
        }
        if("radio_system".equals(sourceKind) && SITE_DATA_TYPES.contains(dataType) &&
            (siteConfigurationId == null || siteConfigurationId.isBlank()))
        {
            throw invalid("site_configuration_id", "Select a saved site first");
        }
        if(siteConfigurationId != null && !SITE_DATA_TYPES.contains(dataType) && !"all".equals(dataType))
        {
            throw invalid("site_configuration_id", "Site selection is not used for this data type");
        }
        if("saved_channel".equals(sourceKind) && siteConfigurationId != null &&
            !sourceKey.equals(siteConfigurationId))
            throw invalid("site_configuration_id", "Site is outside the selected channel");
        return read(connection -> {
            if("saved_channel".equals(sourceKind)) requireConventionalChannel(connection, sourceKey);
            return switch(dataType)
            {
                case "frequencies" -> frequencies(connection, sourceKind, sourceKey,
                    "saved_channel".equals(sourceKind) ? sourceKey : siteConfigurationId,
                    search, limit, offset);
                case "band_plans", "foreign_band_plans" -> bandPlans(connection, sourceKind, sourceKey,
                    siteConfigurationId, dataType, search, limit, offset);
                case "neighbors", "patches" -> siteFacts(connection, sourceKind, sourceKey,
                    siteConfigurationId, dataType, search, limit, offset);
                case "radios", "talkgroups" -> "radio_system".equals(sourceKind) ?
                    identities(connection, sourceKey, dataType, search, limit, offset) :
                    conventionalIdentities(connection, sourceKey, dataType, search, limit, offset);
                case "sites" -> siteResults(connection, sourceKind, sourceKey, search, limit, offset);
                case "channels" -> channelResults(connection, sourceKind, sourceKey, search, limit, offset);
                case "systems" -> systemResults(connection, sourceKey, search, limit, offset);
                case "all", "site_state", "control_quality", "relationships",
                    "affiliations", "call_activity", "signaling_activity", "hourly_history", "detailed_events",
                    "issi_assignment_history" ->
                    aggregateResult(connection, sourceKind, sourceKey, siteConfigurationId,
                        dataType, search, limit, offset);
                default -> throw new IllegalStateException("Unreachable data type");
            };
        });
    }

    private static void requireConventionalChannel(Connection connection, String configurationId)
        throws SQLException
    {
        if(queryRows(connection, "SELECT 1 FROM receiver_channel channel " +
            "JOIN configuration_channel config ON config.configuration_id=channel.configuration_id " +
            "WHERE config.configuration_id=? AND config.channel_kind='CONVENTIONAL' LIMIT 1",
            configurationId).isEmpty())
        {
            throw new StatsApiException(404, "source_not_found", "Conventional channel was not found");
        }
    }

    private static Map<String,Object> selectedSite(Connection connection, String sourceKind,
                                                   String sourceKey, String configurationId)
        throws SQLException
    {
        String predicate = "radio_system".equals(sourceKind) ?
            "system.system_key=? AND config.channel_kind='TRUNKED'" :
            "config.configuration_id=? AND config.channel_kind='CONVENTIONAL'";
        List<Map<String,Object>> rows = queryRows(connection, SITE_SELECT +
            " AND config.configuration_id=? AND " + predicate, configurationId, sourceKey);
        if(rows.isEmpty())
        {
            throw new StatsApiException(404, "site_not_found", "Saved site was not found in this source");
        }
        return rows.getFirst();
    }

    private static Map<String,Object> scopedTarget(String sourceKind, String sourceKey,
                                                    String siteConfigurationId, String expectedSiteKey,
                                                    String dataType, String recordKey, List<String> parts)
    {
        Map<String,Object> target = new LinkedHashMap<>();
        target.put("kind", "scoped_data");
        target.put("source_kind", sourceKind);
        if(sourceKey != null) target.put("source_key", sourceKey);
        if(siteConfigurationId != null)
        {
            target.put("site_configuration_id", siteConfigurationId);
            target.put("expected_site_key", expectedSiteKey);
        }
        target.put("data_type", dataType);
        if(recordKey != null) target.put("record_key", recordKey);
        target.put("parts", parts);
        return target;
    }

    private static List<String> partsFor(String sourceKind, String dataType)
    {
        return switch(dataType)
        {
            case "all" -> "saved_channel".equals(sourceKind) ?
                List.of("summary", "buckets", "events") :
                List.of("current", "summary", "buckets", "events");
            case "site_state", "affiliations" -> List.of("current");
            case "band_plans", "foreign_band_plans", "patches", "neighbors" ->
                List.of("current", "summary");
            case "control_quality", "hourly_history" -> List.of("buckets");
            case "relationships", "alias_activity", "issi_assignment_history" -> List.of("summary");
            case "detailed_events" -> List.of("events");
            case "radios", "talkgroups" -> "saved_channel".equals(sourceKind) ?
                List.of("summary", "events") : List.of("summary", "buckets", "events");
            case "call_activity", "signaling_activity" -> List.of("summary", "buckets", "events");
            default -> List.of("summary");
        };
    }

    private static Page aggregateResult(Connection connection, String sourceKind, String sourceKey,
                                        String siteConfigurationId, String dataType, String search,
                                        int limit, int offset) throws SQLException
    {
        String label = switch(dataType)
        {
            case "all" -> "All retained data";
            case "site_state" -> "Site state";
            case "neighbors" -> "Neighbors";
            case "patches" -> "Patch groups";
            case "control_quality" -> "Control quality";
            case "relationships" -> "Radio relationships";
            case "issi_assignment_history" -> "ISSI assignment history";
            case "affiliations" -> "Affiliations and presence";
            case "call_activity" -> "Call totals";
            case "signaling_activity" -> "Signaling totals";
            case "hourly_history" -> "Hourly history";
            case "detailed_events" -> "Detailed events";
            default -> throw new IllegalArgumentException("Unsupported aggregate type");
        };
        List<Map<String,Object>> sources = "radio_system".equals(sourceKind) ?
            queryRows(connection, "SELECT system.system_key, system.protocol_code, " + SYSTEM_LABEL + " AS label " +
                "FROM radio_system system WHERE system.system_key=?", sourceKey) :
            queryRows(connection, "SELECT config.configuration_id, " + CHANNEL_LABEL + " AS label " +
                "FROM configuration_channel config WHERE config.configuration_id=? " +
                "AND config.channel_kind='CONVENTIONAL'", sourceKey);
        if(sources.isEmpty())
            throw new StatsApiException(404, "source_not_found", "Statistics source was not found");
        if("issi_assignment_history".equals(dataType) &&
            numberOrZero(sources.getFirst().get("protocol_code")) != 1)
            throw invalid("data_type", "ISSI assignment history requires a P25 radio system");
        Map<String,Object> site = siteConfigurationId == null ? null :
            selectedSite(connection, sourceKind, sourceKey, siteConfigurationId);
        if("patches".equals(dataType) && site != null && numberOrZero(site.get("p25_snapshot")) == 0)
            return page(List.of(), limit, offset, 0);
        String scope = String.valueOf(sources.getFirst().get("label"));
        if(site != null) scope += " · " + site.get("label");
        String searchable = (label + " " + scope).toLowerCase(Locale.ROOT);
        if(search != null && !searchable.contains(search.toLowerCase(Locale.ROOT)))
            return page(List.of(), limit, offset, 0);
        if(offset > 0) return page(List.of(), limit, offset, 1);
        List<String> parts = "neighbors".equals(dataType) && site != null &&
            numberOrZero(site.get("p25_snapshot")) == 0 ? List.of("summary") :
            partsFor(sourceKind, dataType);
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("detail", scope);
        row.put("available_parts", parts);
        row.put("target", scopedTarget(sourceKind, sourceKey, siteConfigurationId,
            site == null ? null : siteKey(site), dataType, null, parts));
        WebEntityRef.put(row, "radio_system".equals(sourceKind) ? WebEntityRef.radioSystem(sourceKey) :
            WebEntityRef.channel(sourceKey));
        return page(List.of(row), limit, offset, 1);
    }

    private static Page sitePage(Connection connection, String sourceKind, String sourceKey,
                                 String search, int limit, int offset) throws SQLException
    {
        String predicate = "radio_system".equals(sourceKind) ?
            "system.system_key=? AND config.channel_kind='TRUNKED'" :
            "config.configuration_id=? AND config.channel_kind='CONVENTIONAL'";
        String sql = SITE_SELECT + " AND " + predicate;
        List<Object> parameters = new ArrayList<>(List.of(sourceKey));
        if(search != null)
        {
            sql += " AND " + CHANNEL_SEARCH;
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY lower(label),config.configuration_id LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        for(Map<String,Object> row: rows)
        {
            row.put("site_key", siteKey(row));
            WebEntityRef.put(row, WebEntityRef.channel(String.valueOf(row.get("configuration_id"))));
            row.remove("channel_id");
            row.remove("channel_first_seen_ms");
            row.remove("radio_system_id");
            row.remove("p25_snapshot");
            row.remove("p25_first_seen_ms");
            row.remove("trunked_first_seen_ms");
            row.remove("protocol_code");
            row.remove("variant_code");
            row.remove("observed_location_category_code");
            row.remove("observed_network_id");
            row.remove("observed_system_id");
            row.remove("observed_site_id");
            row.remove("observed_ran");
            row.remove("observed_model_code");
        }
        return page(rows, limit, offset, total);
    }

    private static Page bandPlans(Connection connection, String sourceKind, String sourceKey,
                                  String siteConfigurationId, String dataType, String search,
                                  int limit, int offset) throws SQLException
    {
        Map<String,Object> site = selectedSite(connection, sourceKind, sourceKey, siteConfigurationId);
        if(numberOrZero(site.get("p25_snapshot")) == 0)
            return page(List.of(), limit, offset, 0);
        long channelId = number(site.get("channel_id"));
        boolean foreign = "foreign_band_plans".equals(dataType);
        String sql = foreign ? """
            WITH keys AS (
                SELECT foreign_wacn,foreign_system_id,band
                FROM p25_foreign_system_band WHERE channel_id=?
                UNION SELECT foreign_wacn,foreign_system_id,band
                FROM p25_foreign_system_band_summary WHERE channel_id=?
            )
            SELECT keys.foreign_wacn,keys.foreign_system_id,keys.band,
                coalesce(current.channel_type,summary.channel_type) AS channel_type_code,
                coalesce(current.base_hz,summary.base_hz) AS base_hz,
                coalesce(current.spacing_hz,summary.spacing_hz) AS spacing_hz,
                coalesce(current.transmit_offset_hz,summary.transmit_offset_hz) AS transmit_offset_hz,
                current.confirmed_at_ms,summary.first_seen_ms,summary.last_seen_ms,
                summary.observation_count,current.band IS NOT NULL AS has_current,
                summary.band IS NOT NULL AS has_summary,
                CASE WHEN current.band IS NOT NULL THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
            FROM keys
            LEFT JOIN p25_foreign_system_band current ON current.channel_id=?
                AND current.foreign_wacn=keys.foreign_wacn
                AND current.foreign_system_id=keys.foreign_system_id AND current.band=keys.band
            LEFT JOIN p25_foreign_system_band_summary summary ON summary.channel_id=?
                AND summary.foreign_wacn=keys.foreign_wacn
                AND summary.foreign_system_id=keys.foreign_system_id AND summary.band=keys.band
            """ : """
            WITH keys AS (
                SELECT band FROM p25_site_frequency_band WHERE channel_id=?
                UNION SELECT band FROM p25_site_frequency_band_summary WHERE channel_id=?
            )
            SELECT keys.band,coalesce(current.tdma,summary.tdma) AS tdma,
                coalesce(current.base_hz,summary.base_hz) AS base_hz,
                coalesce(current.bandwidth,summary.bandwidth) AS bandwidth_hz,
                coalesce(current.spacing_hz,summary.spacing_hz) AS spacing_hz,
                coalesce(current.transmit_offset_hz,summary.transmit_offset_hz) AS transmit_offset_hz,
                coalesce(current.timeslots,summary.timeslots) AS timeslots,
                current.confirmed_at_ms,summary.first_seen_ms,summary.last_seen_ms,
                summary.observation_count,current.band IS NOT NULL AS has_current,
                summary.band IS NOT NULL AS has_summary,
                CASE WHEN current.band IS NOT NULL THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
            FROM keys
            LEFT JOIN p25_site_frequency_band current ON current.channel_id=? AND current.band=keys.band
            LEFT JOIN p25_site_frequency_band_summary summary
                ON summary.channel_id=? AND summary.band=keys.band
            """;
        List<Object> parameters = new ArrayList<>(List.of(channelId, channelId, channelId, channelId));
        if(search != null)
        {
            sql += " WHERE (CAST(keys.band AS TEXT) LIKE ? ESCAPE '\\' OR " +
                "printf('%.6f',coalesce(current.base_hz,summary.base_hz)/1000000.0) " +
                "LIKE ? ESCAPE '\\'" + (foreign ?
                " OR printf('%05X',keys.foreign_wacn) LIKE ? ESCAPE '\\' " +
                    "OR CAST(keys.foreign_system_id AS TEXT) LIKE ? ESCAPE '\\' " +
                    "OR EXISTS (SELECT 1 FROM radio_system foreign_system WHERE foreign_system.system_key=" +
                    "printf('p25:%05x:%03x',keys.foreign_wacn,keys.foreign_system_id) AND lower(coalesce(" +
                    StatsSystemNameResolver.configuredNameSql("foreign_system") +
                    ",'')) LIKE ? ESCAPE '\\')" : "") + ")";
            parameters.add(like(search));
            parameters.add(like(search));
            if(foreign)
            {
                parameters.add(like(search));
                parameters.add(like(search));
                parameters.add(like(search));
            }
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += foreign ? " ORDER BY keys.foreign_wacn,keys.foreign_system_id,keys.band LIMIT ? OFFSET ?" :
            " ORDER BY keys.band LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        String ownerKey = siteKey(site);
        for(Map<String,Object> row: rows)
        {
            int band = ((Number)row.get("band")).intValue();
            String key = foreign ? row.get("foreign_wacn") + ":" + row.get("foreign_system_id") + ":" + band :
                String.valueOf(band);
            row.put("label", foreign ? "Foreign band " + band + " · " + row.get("foreign_wacn") + ":" +
                row.get("foreign_system_id") : "Band " + band);
            row.put("detail", site.get("label"));
            row.put("record_key", key);
            row.put("state", recent(row.get("confirmed_at_ms"), row.get("last_seen_ms")) ?
                "CURRENT" : "HISTORICAL");
            List<String> parts = new ArrayList<>();
            if(numberOrZero(row.get("has_current")) != 0) parts.add("current");
            if(numberOrZero(row.get("has_summary")) != 0) parts.add("summary");
            row.put("available_parts", List.copyOf(parts));
            row.put("target", scopedTarget(sourceKind, sourceKey, siteConfigurationId, ownerKey,
                dataType, key, parts));
            copyContext(row, site);
            WebEntityRef.put(row, WebEntityRef.channel(siteConfigurationId));
        }
        return page(rows, limit, offset, total);
    }

    private static Page aliasActivity(Connection connection, String search, int limit, int offset)
        throws SQLException
    {
        String sql = """
            SELECT definition.id AS alias_id,definition.alias_list_id,
                definition.name AS label,definition.description,definition.group_name AS alias_group,
                definition.matcher_type,definition.protocol,definition.value AS native_id,
                list.name AS alias_list_name,summary.metrics_state,
                summary.logical_call_count,summary.first_evidence_ms AS first_seen_ms,
                summary.last_evidence_ms AS last_seen_ms,
                summary.signaling_observation_count
            FROM alias definition
            JOIN alias_list list ON list.id=definition.alias_list_id
            LEFT JOIN alias_activity_summary summary ON summary.alias_id=definition.id
            WHERE 1=1
            """;
        List<Object> parameters = new ArrayList<>();
        if(search != null)
        {
            sql += " AND lower(definition.name || ' ' || coalesce(definition.group_name,'') || ' ' || " +
                "coalesce(definition.description,'') || ' ' || list.name || ' ' || " +
                "coalesce(CAST(definition.value AS TEXT),'')) LIKE ? ESCAPE '\\'";
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY lower(definition.name),definition.id LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        for(Map<String,Object> row: rows)
        {
            row.put("detail", row.get("alias_list_name"));
            row.put("available_parts", List.of("summary"));
            row.put("target", scopedTarget("alias_activity", null, null, null,
                "alias_activity", String.valueOf(row.get("alias_id")), List.of("summary")));
        }
        return page(rows, limit, offset, total);
    }

    private static Page siteFacts(Connection connection, String sourceKind, String sourceKey,
                                  String siteConfigurationId, String dataType, String search,
                                  int limit, int offset) throws SQLException
    {
        Map<String,Object> site = selectedSite(connection, sourceKind, sourceKey, siteConfigurationId);
        if(numberOrZero(site.get("p25_snapshot")) == 0)
        {
            return "neighbors".equals(dataType) ? aggregateResult(connection, sourceKind, sourceKey,
                siteConfigurationId, dataType, search, limit, offset) :
                page(List.of(), limit, offset, 0);
        }
        long channelId = number(site.get("channel_id"));
        boolean neighbor = "neighbors".equals(dataType);
        String sql = neighbor ? """
            WITH keys AS (
                SELECT neighbor_key FROM p25_site_neighbor WHERE channel_id=?
                UNION SELECT neighbor_key FROM p25_site_neighbor_summary WHERE channel_id=?
            )
            SELECT keys.neighbor_key,coalesce(current.system_id,summary.system_id) AS system_id,
                coalesce(current.rfss,summary.rfss) AS rfss,
                coalesce(current.site,summary.site) AS site,
                coalesce(current.channel_descriptor,summary.channel_descriptor) AS channel_descriptor,
                coalesce(current.downlink_hz,summary.downlink_hz) AS downlink_hz,
                coalesce(current.uplink_hz,summary.uplink_hz) AS uplink_hz,
                coalesce(current.status,summary.status) AS status,
                current.confirmed_at_ms,summary.first_seen_ms,summary.last_seen_ms,
                summary.observation_count,current.neighbor_key IS NOT NULL AS has_current,
                summary.neighbor_key IS NOT NULL AS has_summary
            FROM keys
            LEFT JOIN p25_site_neighbor current
                ON current.channel_id=? AND current.neighbor_key=keys.neighbor_key
            LEFT JOIN p25_site_neighbor_summary summary
                ON summary.channel_id=? AND summary.neighbor_key=keys.neighbor_key
            """ : """
            WITH keys AS (
                SELECT local_patch_group_id FROM p25_site_patch_group WHERE channel_id=?
                UNION SELECT local_patch_group_id FROM p25_site_patch_group_summary WHERE channel_id=?
            )
            SELECT keys.local_patch_group_id,
                coalesce(current.version,summary.version) AS version,
                current.confirmed_at_ms,summary.first_seen_ms,summary.last_seen_ms,
                summary.observation_count,
                current.local_patch_group_id IS NOT NULL AS has_current,
                summary.local_patch_group_id IS NOT NULL AS has_summary,
                (SELECT count(*) FROM p25_site_patch_group_talkgroup_summary member
                 WHERE member.channel_id=? AND member.local_patch_group_id=keys.local_patch_group_id)
                    AS talkgroup_count,
                (SELECT count(*) FROM p25_site_patch_group_radio_summary member
                 WHERE member.channel_id=? AND member.local_patch_group_id=keys.local_patch_group_id)
                    AS radio_count
            FROM keys
            LEFT JOIN p25_site_patch_group current
                ON current.channel_id=? AND current.local_patch_group_id=keys.local_patch_group_id
            LEFT JOIN p25_site_patch_group_summary summary
                ON summary.channel_id=? AND summary.local_patch_group_id=keys.local_patch_group_id
            """;
        List<Object> parameters = new ArrayList<>();
        for(int index = 0; index < (neighbor ? 4 : 6); index++) parameters.add(channelId);
        if(search != null)
        {
            sql += neighbor ? " WHERE lower(keys.neighbor_key || ' ' || " +
                "coalesce(CAST(coalesce(current.rfss,summary.rfss) AS TEXT),'') || ' ' || " +
                "coalesce(CAST(coalesce(current.site,summary.site) AS TEXT),'') || ' ' || " +
                "coalesce(current.status,summary.status,'') || ' ' || " +
                "coalesce(printf('%.6f',coalesce(current.downlink_hz,summary.downlink_hz)/1000000.0),'')) " +
                "LIKE ? ESCAPE '\\'" :
                " WHERE CAST(keys.local_patch_group_id AS TEXT) LIKE ? ESCAPE '\\'";
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += neighbor ? " ORDER BY keys.neighbor_key LIMIT ? OFFSET ?" :
            " ORDER BY keys.local_patch_group_id LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        String ownerKey = siteKey(site);
        for(Map<String,Object> row: rows)
        {
            String key = String.valueOf(row.get(neighbor ? "neighbor_key" : "local_patch_group_id"));
            row.put("label", neighbor ? "Neighbor " + key : "Patch group " + key);
            row.put("detail", site.get("label"));
            row.put("record_key", key);
            row.put("state", recent(row.get("confirmed_at_ms"), row.get("last_seen_ms")) ?
                "CURRENT" : "HISTORICAL");
            List<String> parts = new ArrayList<>();
            if(numberOrZero(row.get("has_current")) != 0) parts.add("current");
            if(numberOrZero(row.get("has_summary")) != 0) parts.add("summary");
            row.put("available_parts", List.copyOf(parts));
            row.put("target", scopedTarget(sourceKind, sourceKey, siteConfigurationId, ownerKey,
                dataType, key, parts));
            copyContext(row, site);
            WebEntityRef.put(row, WebEntityRef.channel(siteConfigurationId));
        }
        return page(rows, limit, offset, total);
    }

    private static Page frequencies(Connection connection, String sourceKind, String sourceKey,
                                    String siteConfigurationId, String search, int limit, int offset)
        throws SQLException
    {
        Map<String,Object> site = selectedSite(connection, sourceKind, sourceKey, siteConfigurationId);
        String ownerKey = siteKey(site);
        long channelId = number(site.get("channel_id"));
        String base;
        if("CONVENTIONAL".equals(site.get("channel_kind")))
        {
            // Each projection can exist without the others. Only the conventional summary's
            // call count represents the complete physical-frequency total; identity summaries
            // can count the same call more than once.
            base = "SELECT frequency_hz, MAX(observation_count) AS observation_count, " +
                "MAX(last_seen_ms) AS last_seen_ms FROM (" +
                "SELECT frequency_hz, SUM(call_count) AS observation_count, " +
                "MAX(last_seen_ms) AS last_seen_ms FROM conventional_activity_summary " +
                "WHERE channel_id=? GROUP BY frequency_hz UNION ALL " +
                "SELECT frequency_hz, NULL, NULL " +
                "FROM conventional_activity_bucket WHERE channel_id=? AND frequency_hz>0 " +
                "GROUP BY frequency_hz UNION ALL " +
                "SELECT frequency_hz, NULL, MAX(last_seen_ms) " +
                "FROM dmr_conventional_talkgroup_summary WHERE channel_id=? " +
                "GROUP BY frequency_hz UNION ALL " +
                "SELECT frequency_hz, NULL, MAX(last_seen_ms) " +
                "FROM dmr_conventional_radio_summary WHERE channel_id=? " +
                "GROUP BY frequency_hz) GROUP BY frequency_hz";
        }
        else if(numberOrZero(site.get("p25_snapshot")) != 0)
        {
            base = "WITH keys AS (SELECT channel_key FROM p25_site_channel_summary WHERE channel_id=? " +
                "UNION SELECT channel_key FROM p25_site_channel WHERE channel_id=?) " +
                "SELECT coalesce(current.downlink_hz,summary.downlink_hz) AS frequency_hz, " +
                "SUM(summary.observation_count) AS observation_count, " +
                "MAX(coalesce(summary.last_seen_ms,current.confirmed_at_ms)) AS last_seen_ms " +
                "FROM keys LEFT JOIN p25_site_channel_summary summary " +
                "ON summary.channel_id=? AND summary.channel_key=keys.channel_key " +
                "LEFT JOIN p25_site_channel current " +
                "ON current.channel_id=? AND current.channel_key=keys.channel_key " +
                "GROUP BY coalesce(current.downlink_hz,summary.downlink_hz) " +
                "HAVING frequency_hz IS NOT NULL AND frequency_hz>0";
        }
        else
        {
            base = "SELECT frequency_hz, SUM(observation_count) AS observation_count, " +
                "MAX(last_seen_ms) AS last_seen_ms FROM trunked_site_channel_summary " +
                "WHERE channel_id=? AND frequency_hz>0 GROUP BY frequency_hz";
        }
        String sql = "SELECT * FROM (" + base + ") WHERE 1=1";
        List<Object> parameters = new ArrayList<>();
        int bindCount = "CONVENTIONAL".equals(site.get("channel_kind")) ||
            numberOrZero(site.get("p25_snapshot")) != 0 ? 4 : 1;
        for(int index = 0; index < bindCount; index++)
        {
            parameters.add(channelId);
        }
        if(search != null)
        {
            sql += " AND (CAST(frequency_hz AS TEXT) LIKE ? ESCAPE '\\' " +
                "OR printf('%.6f',frequency_hz/1000000.0) LIKE ? ESCAPE '\\')";
            parameters.add(like(search));
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY frequency_hz LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        for(Map<String,Object> row: rows)
        {
            long hz = number(row.get("frequency_hz"));
            row.put("label", String.format(Locale.ROOT, "%.6f MHz", hz / 1_000_000.0));
            row.put("detail", String.valueOf(site.get("label")));
            copyContext(row, site);
            WebEntityRef.put(row, WebEntityRef.channel(siteConfigurationId));
            List<String> parts = "TRUNKED".equals(site.get("channel_kind")) &&
                numberOrZero(site.get("p25_snapshot")) != 0 ?
                List.of("current", "summary", "buckets", "events") :
                List.of("summary", "buckets", "events");
            row.put("available_parts", parts);
            row.put("target", scopedTarget(sourceKind, sourceKey,
                "radio_system".equals(sourceKind) ? siteConfigurationId : null,
                "radio_system".equals(sourceKind) ? ownerKey : null,
                "frequencies", String.valueOf(hz), parts));
            if("TRUNKED".equals(site.get("channel_kind")) &&
                numberOrZero(site.get("p25_snapshot")) != 0)
                enrichP25Frequency(connection, row, channelId, hz);
            else if("TRUNKED".equals(site.get("channel_kind")))
                enrichTrunkedFrequency(connection, row, channelId, hz);
            else
                enrichConventionalFrequency(connection, row, channelId, hz);
        }
        return page(rows, limit, offset, total);
    }

    private static void enrichP25Frequency(Connection connection, Map<String,Object> row,
                                           long channelId, long frequencyHz) throws SQLException
    {
        List<Map<String,Object>> logical = queryRows(connection, """
            WITH keys AS (
                SELECT channel_key FROM p25_site_channel_summary WHERE channel_id=?
                UNION SELECT channel_key FROM p25_site_channel WHERE channel_id=?
            )
            SELECT keys.channel_key,coalesce(current.descriptor,summary.descriptor) AS descriptor,
                coalesce(current.uplink_hz,summary.uplink_hz) AS uplink_hz,
                coalesce(current.tdma,summary.tdma) AS tdma,
                coalesce(current.timeslots,summary.timeslots) AS timeslots,
                current.confirmed_at_ms,summary.first_seen_ms,summary.last_seen_ms,
                summary.observation_count,
                (SELECT coalesce(sum(tag.observation_count),0) FROM p25_site_channel_tag_summary tag
                 WHERE tag.channel_id=coalesce(summary.channel_id,current.channel_id)
                   AND tag.channel_key=keys.channel_key
                   AND tag.tag='VOICE') AS voice_grant_observations,
                (SELECT group_concat(tag.tag,', ') FROM p25_site_channel_tag_summary tag
                 WHERE tag.channel_id=coalesce(summary.channel_id,current.channel_id)
                   AND tag.channel_key=keys.channel_key)
                    AS observed_tags,
                (SELECT group_concat(tag.tag,', ') FROM p25_site_channel_tag tag
                 WHERE tag.channel_id=coalesce(summary.channel_id,current.channel_id)
                   AND tag.channel_key=keys.channel_key)
                    AS current_tags
            FROM keys
            LEFT JOIN p25_site_channel_summary summary
                ON summary.channel_id=? AND summary.channel_key=keys.channel_key
            LEFT JOIN p25_site_channel current
                ON current.channel_id=? AND current.channel_key=keys.channel_key
            WHERE coalesce(current.downlink_hz,summary.downlink_hz)=?
            ORDER BY keys.channel_key
            """, channelId, channelId, channelId, channelId, frequencyHz);
        List<String> channelKeys = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>();
        Set<String> currentTags = new LinkedHashSet<>();
        long voiceGrants = 0;
        long confirmedAt = 0;
        for(Map<String,Object> logicalRow: logical)
        {
            channelKeys.add(String.valueOf(logicalRow.get("channel_key")));
            if(row.get("descriptor") == null) row.put("descriptor", logicalRow.get("descriptor"));
            if(row.get("uplink_hz") == null) row.put("uplink_hz", logicalRow.get("uplink_hz"));
            if(row.get("timeslots") == null) row.put("timeslots", logicalRow.get("timeslots"));
            if(row.get("tdma") == null) row.put("tdma", logicalRow.get("tdma"));
            voiceGrants += numberOrZero(logicalRow.get("voice_grant_observations"));
            confirmedAt = Math.max(confirmedAt, numberOrZero(logicalRow.get("confirmed_at_ms")));
            addCommaValues(tags, logicalRow.get("observed_tags"));
            addCommaValues(currentTags, logicalRow.get("current_tags"));
        }
        row.put("channel_keys", List.copyOf(channelKeys));
        row.put("channel_key", channelKeys.size() == 1 ? channelKeys.getFirst() : null);
        row.put("logical_channel_count", channelKeys.size());
        row.put("tags", List.copyOf(tags));
        row.put("current_tags", List.copyOf(currentTags));
        row.put("voice_grant_observations", voiceGrants);
        row.put("state", recent(confirmedAt, row.get("last_seen_ms")) ?
            "CURRENT" : "HISTORICAL");
    }

    private static void enrichTrunkedFrequency(Connection connection, Map<String,Object> row,
                                               long channelId, long frequencyHz) throws SQLException
    {
        List<Map<String,Object>> logical = queryRows(connection, """
            SELECT channel_number,inbound_channel_number,timeslot,uplink_hz,role_flags,
                observation_count,last_seen_ms
            FROM trunked_site_channel_summary
            WHERE channel_id=? AND frequency_hz=?
            ORDER BY channel_number,timeslot
            """, channelId, frequencyHz);
        List<String> channels = new ArrayList<>();
        for(Map<String,Object> item: logical)
        {
            if(item.get("channel_number") instanceof Number number && number.longValue() >= 0)
                channels.add(String.valueOf(number.longValue()));
            if(row.get("uplink_hz") == null) row.put("uplink_hz", item.get("uplink_hz"));
            if(row.get("timeslot") == null) row.put("timeslot", item.get("timeslot"));
            if(row.get("role_flags") == null) row.put("role_flags", item.get("role_flags"));
        }
        row.put("channel_keys", List.copyOf(channels));
        row.put("logical_channel_count", logical.size());
        row.put("state", recent(row.get("last_seen_ms")) ? "CURRENT" : "HISTORICAL");
    }

    private static void enrichConventionalFrequency(Connection connection, Map<String,Object> row,
                                                     long channelId, long frequencyHz) throws SQLException
    {
        List<Map<String,Object>> slots = queryRows(connection, """
            SELECT DISTINCT timeslot FROM (
                SELECT timeslot FROM conventional_activity_summary WHERE channel_id=? AND frequency_hz=?
                UNION SELECT timeslot FROM conventional_activity_bucket WHERE channel_id=? AND frequency_hz=?
                UNION SELECT timeslot FROM dmr_conventional_radio_summary WHERE channel_id=? AND frequency_hz=?
                UNION SELECT timeslot FROM dmr_conventional_talkgroup_summary WHERE channel_id=? AND frequency_hz=?
            ) WHERE timeslot IS NOT NULL ORDER BY timeslot
            """, channelId, frequencyHz, channelId, frequencyHz,
            channelId, frequencyHz, channelId, frequencyHz);
        List<Integer> values = new ArrayList<>();
        for(Map<String,Object> slot: slots)
        {
            if(slot.get("timeslot") instanceof Number number) values.add(number.intValue());
        }
        row.put("timeslots", List.copyOf(values));
        row.put("timeslot", values.size() == 1 ? values.getFirst() : null);
        row.put("logical_call_count", row.get("observation_count"));
    }

    private static void addCommaValues(Set<String> destination, Object text)
    {
        if(text instanceof String value)
        {
            for(String part: value.split(","))
            {
                if(!part.isBlank()) destination.add(part.strip());
            }
        }
    }

    private static boolean recent(Object... timestamps)
    {
        long latest = 0;
        for(Object timestamp: timestamps) latest = Math.max(latest, numberOrZero(timestamp));
        return latest >= System.currentTimeMillis() - CURRENT_STATE_WINDOW_MS;
    }

    private static Page identities(Connection connection, String systemKey, String dataType,
                                   String search, int limit, int offset) throws SQLException
    {
        int kind = "radios".equals(dataType) ? 2 : 1;
        StringBuilder sql = new StringBuilder("SELECT summary.id AS identity_summary_id, " +
            "summary.identity_id AS native_id, system.system_key AS radio_system_key, " +
            "system.protocol_code, CASE system.protocol_code WHEN 1 THEN 'P25' " +
            "WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' END AS protocol, " +
            "CASE WHEN system.protocol_code=4 AND system.address_domain_code=2 " +
            "THEN 'nxdn_type_d' WHEN system.protocol_code=4 AND system.address_domain_code=1 " +
            "THEN 'nxdn_type_c' END AS address_domain, " +
            SYSTEM_LABEL + " AS radio_system_name, " +
            "summary.home_wacn, " +
            "summary.home_system_id, summary.last_talker_alias, summary.last_seen_ms, " +
            "summary.logical_call_count, summary.logical_call_count AS observation_count, " +
            IDENTITY_KEY_SQL + " AS identity_key " +
            "FROM radio_system_identity_summary summary JOIN radio_system system " +
            "ON system.id=summary.radio_system_id WHERE system.system_key=? " +
            "AND summary.identity_kind_code=?");
        List<Object> parameters = new ArrayList<>(List.of(systemKey, kind));
        StatsIdentitySearch.append(sql, parameters, search, kind == 2 ? "RADIO_ID" : "TALKGROUP",
            kind == 2);
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql.append(" ORDER BY summary.identity_id, summary.home_wacn, summary.home_system_id, " +
            "summary.id LIMIT ? OFFSET ?");
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        StatsAliasResolver resolver = new StatsAliasResolver();
        if(kind == 2)
        {
            resolver.enrichCanonicalSystemRadios(connection, rows, "identity_summary_id", "native_id", "alias_");
        }
        else
        {
            resolver.enrichCanonicalSystemTalkgroups(connection, rows, "identity_summary_id", "native_id", "alias_");
        }
        enrichSystemListContext(connection, rows, "radio_system_key");
        for(Map<String,Object> row: rows)
        {
            String numericLabel = (kind == 2 ? "Radio " : "Talkgroup ") + row.get("native_id");
            String aliasName = string(row.get("alias_name"));
            row.put("label", aliasName == null ? numericLabel : aliasName + " (" + numericLabel + ")");
            long homeWacn = number(row.get("home_wacn"));
            long homeSystem = number(row.get("home_system_id"));
            String detail = numericLabel;
            if(homeWacn >= 0 || homeSystem >= 0)
            {
                detail += " · Home WACN " + (homeWacn < 0 ? "unknown" :
                    String.format(Locale.ROOT, "%05X", homeWacn)) + " · system " +
                    (homeSystem < 0 ? "unknown" : String.format(Locale.ROOT, "%03X", homeSystem));
            }
            row.put("detail", detail);
            WebEntityRef.put(row, kind == 2 ? WebEntityRef.radio(systemKey,
                String.valueOf(row.get("identity_key"))) : WebEntityRef.talkgroup(systemKey,
                String.valueOf(row.get("identity_key"))));
            List<String> parts = partsFor("radio_system", dataType);
            row.put("available_parts", parts);
            row.put("target", scopedTarget("radio_system", systemKey, null, null,
                dataType, String.valueOf(row.get("identity_key")), parts));
        }
        return page(rows, limit, offset, total);
    }

    private static Page conventionalIdentities(Connection connection, String configurationId,
                                               String dataType, String search, int limit, int offset)
        throws SQLException
    {
        List<Map<String,Object>> channels = queryRows(connection, """
            SELECT channel.id AS channel_id, config.system_name AS radio_system_name,
                   config.site_name, config.name AS channel_name, config.alias_list_id,
                   list.name AS alias_list_name
            FROM receiver_channel channel
            JOIN configuration_channel config ON config.configuration_id=channel.configuration_id
            LEFT JOIN alias_list list ON list.id=config.alias_list_id
            WHERE config.configuration_id=? AND config.channel_kind='CONVENTIONAL'
              AND config.decoder_type='DMR'
            """, configurationId);
        if(channels.isEmpty())
        {
            throw invalid("data_type", "Radio and talkgroup rows require a DMR conventional channel");
        }
        long channelId = number(channels.getFirst().get("channel_id"));
        String identityColumn = "radios".equals(dataType) ? "radio_id" : "talkgroup_id";
        String table = "radios".equals(dataType) ? "dmr_conventional_radio_summary" :
            "dmr_conventional_talkgroup_summary";
        String sql = "SELECT summary." + identityColumn + " AS native_id, summary.frequency_hz, " +
            "summary.timeslot, summary.call_count AS logical_call_count, " +
            "summary.call_count AS observation_count, summary.last_seen_ms, " +
            CHANNEL_CONTEXT + " " +
            "FROM " + table + " summary JOIN receiver_channel channel ON channel.id=summary.channel_id " +
            "JOIN configuration_channel config ON config.configuration_id=channel.configuration_id " +
            "LEFT JOIN alias_list list ON list.id=config.alias_list_id WHERE summary.channel_id=?";
        List<Object> parameters = new ArrayList<>(List.of(channelId));
        if(search != null)
        {
            sql += " AND (CAST(summary." + identityColumn + " AS TEXT) LIKE ? ESCAPE '\\' " +
                "OR CAST(summary.frequency_hz AS TEXT) LIKE ? ESCAPE '\\' OR EXISTS (" +
                "SELECT 1 FROM alias definition " +
                " WHERE definition.alias_list_id=config.alias_list_id AND definition.protocol='DMR' " +
                "AND definition.matcher_type IN ('" + ("radios".equals(dataType) ? "RADIO_ID" : "TALKGROUP") +
                "','" + ("radios".equals(dataType) ? "RADIO_ID_RANGE" : "TALKGROUP_RANGE") +
                "') AND ((definition.matcher_type='" +
                ("radios".equals(dataType) ? "RADIO_ID_RANGE" : "TALKGROUP_RANGE") +
                "' AND summary." + identityColumn +
                " BETWEEN definition.min_value AND definition.max_value) OR " +
                "(definition.matcher_type='" +
                ("radios".equals(dataType) ? "RADIO_ID" : "TALKGROUP") +
                "' AND definition.value=summary." +
                identityColumn + ")) AND " + aliasTextSearch() + "))";
            parameters.add(like(search));
            parameters.add(like(search));
            addAliasTextParameters(parameters, search);
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY summary." + identityColumn + ", summary.frequency_hz, " +
            "summary.timeslot LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        StatsAliasResolver resolver = new StatsAliasResolver();
        if("radios".equals(dataType))
        {
            resolver.enrichDmrRadios(connection, rows, "native_id", "alias_");
        }
        else
        {
            resolver.enrichDmrTalkgroups(connection, rows, "native_id", "alias_");
        }
        for(Map<String,Object> row: rows)
        {
            long frequencyHz = number(row.get("frequency_hz"));
            int timeslot = (int)number(row.get("timeslot"));
            int nativeId = (int)number(row.get("native_id"));
            String numericLabel = ("radios".equals(dataType) ? "Radio " : "Talkgroup ") + nativeId;
            String aliasName = string(row.get("alias_name"));
            row.put("label", aliasName == null ? numericLabel : aliasName + " (" + numericLabel + ")");
            row.put("detail", numericLabel + " · " + String.format(Locale.ROOT, "%.6f MHz · slot %d",
                frequencyHz / 1_000_000.0, timeslot));
            WebEntityRef.put(row, WebEntityRef.channel(configurationId));
            List<String> parts = partsFor("saved_channel", dataType);
            row.put("available_parts", parts);
            row.put("target", scopedTarget("saved_channel", configurationId, null, null,
                dataType, frequencyHz + ":" + timeslot + ":" + nativeId, parts));
        }
        return page(rows, limit, offset, total);
    }

    private static Page siteResults(Connection connection, String sourceKind, String sourceKey,
                                    String search, int limit, int offset) throws SQLException
    {
        String sql;
        List<Object> parameters = new ArrayList<>();
        if("radio_system".equals(sourceKind))
        {
            sql = "SELECT * FROM (" +
                "SELECT 'learned' AS site_kind, system.system_key AS radio_system_key, " +
                "learned.rfss, learned.site, NULL AS configuration_id, " +
                "'RFSS ' || learned.rfss || ' · Site ' || learned.site AS label, " +
                "'Learned site' AS detail, " + SYSTEM_LABEL + " AS radio_system_name, " +
                "NULL AS site_name, NULL AS channel_name, NULL AS alias_list_id, " +
                "NULL AS alias_list_name, learned.last_seen_ms, NULL AS channel_kind, " +
                "NULL AS channel_id, NULL AS channel_first_seen_ms, NULL AS radio_system_id, " +
                "NULL AS p25_snapshot, NULL AS p25_first_seen_ms, NULL AS trunked_first_seen_ms, " +
                "NULL AS protocol_code, NULL AS variant_code, " +
                "NULL AS observed_location_category_code, NULL AS observed_network_id, " +
                "NULL AS observed_system_id, NULL AS observed_site_id, NULL AS observed_ran, " +
                "NULL AS observed_model_code " +
                "FROM p25_learned_site learned JOIN radio_system system " +
                "ON system.id=learned.radio_system_id WHERE system.system_key=? " +
                "UNION ALL " +
                "SELECT 'saved' AS site_kind, system.system_key AS radio_system_key, " +
                "p25.rfss,p25.site,config.configuration_id," + SITE_LABEL + " AS label, " +
                "'Saved channel' AS detail, " + CHANNEL_CONTEXT + ", " +
                "coalesce(p25.last_seen_ms,trunked.last_seen_ms,channel.last_seen_ms) AS last_seen_ms, " +
                "config.channel_kind,channel.id AS channel_id, " +
                "channel.first_seen_ms AS channel_first_seen_ms, channel.radio_system_id, " +
                "p25.channel_id IS NOT NULL AS p25_snapshot, " +
                "p25.first_seen_ms AS p25_first_seen_ms, " +
                "trunked.first_seen_ms AS trunked_first_seen_ms, " +
                "trunked.protocol_code,trunked.variant_code,trunked.observed_location_category_code, " +
                "trunked.observed_network_id,trunked.observed_system_id,trunked.observed_site_id, " +
                "trunked.observed_ran,trunked.observed_model_code " +
                "FROM receiver_channel channel JOIN configuration_channel config " +
                "ON config.configuration_id=channel.configuration_id " +
                "LEFT JOIN alias_list list ON list.id=config.alias_list_id " +
                "JOIN radio_system system ON system.id=channel.radio_system_id " +
                "LEFT JOIN p25_site_snapshot p25 ON p25.channel_id=channel.id " +
                "LEFT JOIN trunked_site_snapshot trunked ON trunked.channel_id=channel.id " +
                "WHERE system.system_key=? AND config.channel_kind='TRUNKED' AND " +
                "(p25.channel_id IS NOT NULL OR trunked.channel_id IS NOT NULL)) " +
                "WHERE 1=1";
            parameters.add(sourceKey);
            parameters.add(sourceKey);
            if(search != null)
            {
                sql += " AND lower(label || ' ' || coalesce(configuration_id,'') || ' ' || " +
                    "coalesce(radio_system_name,'') || ' ' || coalesce(site_name,'') || ' ' || " +
                    "coalesce(channel_name,'') || ' ' || coalesce(alias_list_name,'') || ' ' || " +
                    "coalesce(CAST(rfss AS TEXT),'') || ' ' || coalesce(CAST(site AS TEXT),'')) " +
                    "LIKE ? ESCAPE '\\'";
                parameters.add(like(search));
            }
        }
        else
        {
            sql = "SELECT 'saved' AS site_kind, config.configuration_id, " + SITE_LABEL +
                " AS label, " + CHANNEL_CONTEXT + ", " +
                "system.system_key AS radio_system_key, " +
                "coalesce(p25.last_seen_ms,trunked.last_seen_ms,channel.last_seen_ms) " +
                "AS last_seen_ms, channel.id AS channel_id, " +
                "channel.first_seen_ms AS channel_first_seen_ms, channel.radio_system_id, " +
                "p25.channel_id IS NOT NULL AS p25_snapshot, p25.rfss,p25.site, " +
                "p25.first_seen_ms AS p25_first_seen_ms, " +
                "trunked.first_seen_ms AS trunked_first_seen_ms, " +
                "trunked.protocol_code,trunked.variant_code,trunked.observed_location_category_code, " +
                "trunked.observed_network_id,trunked.observed_system_id,trunked.observed_site_id, " +
                "trunked.observed_ran,trunked.observed_model_code,config.channel_kind " +
                "FROM receiver_channel channel JOIN configuration_channel config " +
                "ON config.configuration_id=channel.configuration_id " +
                "LEFT JOIN alias_list list ON list.id=config.alias_list_id " +
                "LEFT JOIN radio_system system ON system.id=channel.radio_system_id " +
                "LEFT JOIN p25_site_snapshot p25 ON p25.channel_id=channel.id " +
                "LEFT JOIN trunked_site_snapshot trunked ON trunked.channel_id=channel.id " +
                "WHERE config.configuration_id=? AND config.channel_kind='TRUNKED' AND " +
                "(p25.channel_id IS NOT NULL OR trunked.channel_id IS NOT NULL)";
            parameters.add(sourceKey);
            if(search != null)
            {
                sql += " AND " + CHANNEL_SEARCH;
                parameters.add(like(search));
            }
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY lower(label),site_kind,configuration_id,rfss,site LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        if("radio_system".equals(sourceKind))
        {
            enrichSystemListContext(connection, rows, "radio_system_key");
        }
        for(Map<String,Object> row: rows)
        {
            if("learned".equals(row.get("site_kind")))
            {
                WebEntityRef.put(row, WebEntityRef.radioSystem(sourceKey));
                row.put("target", Map.of("kind", "learned_site", "radio_system_key", sourceKey,
                    "rfss", row.get("rfss"), "site", row.get("site"),
                    "include_channel_history", false));
            }
            else
            {
                WebEntityRef.put(row, WebEntityRef.channel(String.valueOf(row.get("configuration_id"))));
                String key = siteKey(row);
                row.put("site_key", key);
                row.put("target", Map.of("kind", "saved_site", "configuration_id",
                    row.get("configuration_id"),
                    "expected_site_key", key, "include_channel_history", false));
            }
            row.remove("p25_snapshot");
            row.remove("channel_id");
            row.remove("channel_first_seen_ms");
            row.remove("radio_system_id");
            row.remove("p25_first_seen_ms");
            row.remove("trunked_first_seen_ms");
            row.remove("protocol_code");
            row.remove("variant_code");
            row.remove("observed_location_category_code");
            row.remove("observed_network_id");
            row.remove("observed_system_id");
            row.remove("observed_site_id");
            row.remove("observed_ran");
            row.remove("observed_model_code");
            row.remove("channel_kind");
        }
        return page(rows, limit, offset, total);
    }

    private static Page channelResults(Connection connection, String sourceKind, String sourceKey,
                                       String search, int limit, int offset) throws SQLException
    {
        String sql = "SELECT config.configuration_id, " + CHANNEL_LABEL + " AS label, " +
            CHANNEL_CONTEXT + ", system.system_key AS radio_system_key, " +
            "config.channel_kind, channel.last_seen_ms FROM receiver_channel channel " +
            "JOIN configuration_channel config ON config.configuration_id=channel.configuration_id " +
            "LEFT JOIN alias_list list ON list.id=config.alias_list_id " +
            "LEFT JOIN radio_system system ON system.id=channel.radio_system_id WHERE " +
            ("radio_system".equals(sourceKind) ? "system.system_key=?" : "config.configuration_id=?");
        List<Object> parameters = new ArrayList<>(List.of(sourceKey));
        if(search != null)
        {
            sql += " AND " + CHANNEL_SEARCH;
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY lower(label),config.configuration_id LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, WebEntityRef.channel(String.valueOf(row.get("configuration_id"))));
            row.put("detail", channelDetail(row));
            row.put("target", Map.of("kind", "channel", "configuration_id",
                row.get("configuration_id")));
        }
        return page(rows, limit, offset, total);
    }

    private static Page systemResults(Connection connection, String sourceKey, String search,
                                      int limit, int offset) throws SQLException
    {
        String sql = "SELECT system.system_key AS radio_system_key, " + SYSTEM_LABEL +
            " AS label, " + SYSTEM_LABEL + " AS radio_system_name, system.last_seen_ms " +
            "FROM radio_system system WHERE system.system_key=?";
        List<Object> parameters = new ArrayList<>(List.of(sourceKey));
        if(search != null)
        {
            sql += " AND (lower(" + SYSTEM_LABEL + " || ' ' || system.system_key) " +
                "LIKE ? ESCAPE '\\' OR " + systemAliasListSearch() + ")";
            parameters.add(like(search));
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY label LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        enrichSystemListContext(connection, rows, "radio_system_key");
        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, WebEntityRef.radioSystem(sourceKey));
            row.put("target", Map.of("kind", "system", "radio_system_key", sourceKey,
                "include_channel_history", false));
        }
        return page(rows, limit, offset, total);
    }

    /** A system can have several saved channels and Alias Lists; report a list only when it is unambiguous. */
    private static void enrichSystemListContext(Connection connection, List<Map<String,Object>> rows,
                                                String keyField) throws SQLException
    {
        Set<String> keys = new LinkedHashSet<>();
        for(Map<String,Object> row: rows)
        {
            String key = string(row.get(keyField));
            if(key != null)
            {
                keys.add(key);
            }
        }
        if(keys.isEmpty())
        {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        String sql = "SELECT assigned.system_key, " +
            "CASE WHEN COUNT(DISTINCT assigned.alias_list_id)=1 THEN MIN(assigned.alias_list_id) END " +
            "AS alias_list_id, CASE WHEN COUNT(DISTINCT assigned.alias_list_id)=1 " +
            "THEN MIN(assigned.alias_list_name) END AS alias_list_name FROM (" +
            "SELECT system.system_key, config.alias_list_id, list.name AS alias_list_name " +
            "FROM radio_system system JOIN receiver_channel channel ON channel.radio_system_id=system.id " +
            "JOIN configuration_channel config ON config.configuration_id=channel.configuration_id " +
            "JOIN alias_list list ON list.id=config.alias_list_id " +
            "WHERE system.system_key IN (" + placeholders + ") UNION " +
            "SELECT system.system_key, config.alias_list_id, list.name AS alias_list_name " +
            "FROM radio_system system JOIN configuration_channel config " +
            "ON config.configuration_id=system.configuration_id " +
            "JOIN alias_list list ON list.id=config.alias_list_id " +
            "WHERE system.system_key IN (" + placeholders + ")) assigned " +
            "GROUP BY assigned.system_key";
        List<Object> parameters = new ArrayList<>(keys);
        parameters.addAll(keys);
        Map<String,Map<String,Object>> context = new LinkedHashMap<>();
        for(Map<String,Object> row: queryRows(connection, sql, parameters.toArray()))
        {
            context.put(String.valueOf(row.get("system_key")), row);
        }
        for(Map<String,Object> row: rows)
        {
            Map<String,Object> assigned = context.get(string(row.get(keyField)));
            if(assigned != null && assigned.get("alias_list_id") != null)
            {
                row.put("alias_list_id", assigned.get("alias_list_id"));
                row.put("alias_list_name", assigned.get("alias_list_name"));
            }
        }
    }

    private static String systemAliasListSearch()
    {
        return "EXISTS (SELECT 1 FROM configuration_channel context_config " +
            "LEFT JOIN alias_list context_list ON context_list.id=context_config.alias_list_id " +
            "WHERE (context_config.configuration_id=system.configuration_id OR EXISTS (" +
            "SELECT 1 FROM receiver_channel context_channel WHERE " +
            "context_channel.radio_system_id=system.id AND " +
            "context_channel.configuration_id=context_config.configuration_id)) AND " +
            "lower(coalesce(context_config.system_name,'') || ' ' || " +
            "coalesce(context_config.site_name,'') || ' ' || " +
            "coalesce(context_config.name,'') || ' ' || coalesce(context_list.name,'')) " +
            "LIKE ? ESCAPE '\\')";
    }

    private static void copyContext(Map<String,Object> row, Map<String,Object> source)
    {
        for(String field: List.of("radio_system_key", "radio_system_name", "site_name", "channel_name",
            "channel_kind", "alias_list_id", "alias_list_name"))
        {
            row.put(field, source.get(field));
        }
    }

    private static String channelDetail(Map<String,Object> row)
    {
        String site = string(row.get("site_name"));
        String system = string(row.get("radio_system_name"));
        return site == null ? system : system == null ? site : site + " · " + system;
    }

    private static String string(Object value)
    {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String aliasTextSearch()
    {
        return "(lower(coalesce(definition.name,'')) LIKE ? ESCAPE '\\' OR " +
            "lower(coalesce(definition.description,'')) LIKE ? ESCAPE '\\' OR " +
            "lower(coalesce(definition.group_name,'')) LIKE ? ESCAPE '\\')";
    }

    private static void addAliasTextParameters(List<Object> parameters, String search)
    {
        String pattern = like(search);
        parameters.add(pattern);
        parameters.add(pattern);
        parameters.add(pattern);
    }

    private <T> T read(Query<T> query)
    {
        try
        {
            if(!Files.isRegularFile(mDatabasePath))
            {
                throw new IOException("Statistics database is missing");
            }
            try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
                mDatabasePath.toUri() + "?mode=ro"))
            {
                try(Statement statement = connection.createStatement())
                {
                    statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
                    statement.execute("PRAGMA query_only=ON");
                }
                connection.setAutoCommit(false);
                T result = query.run(connection);
                if(result instanceof Page page)
                {
                    StatsSystemNameResolver names = new StatsSystemNameResolver(connection);
                    List<Map<String,Object>> rows = new ArrayList<>();
                    for(Map<String,Object> row: page.rows()) rows.add(names.enrichMap(row));
                    @SuppressWarnings("unchecked")
                    T named = (T)new Page(rows, page.limit(), page.offset(), page.hasMore(), page.nextOffset(),
                        page.totalCount());
                    result = named;
                }
                connection.rollback();
                return result;
            }
        }
        catch(StatsApiException exception)
        {
            throw exception;
        }
        catch(IOException | SQLException exception)
        {
            throw new StatsApiException(503, "statistics_unavailable",
                "Retained statistics could not be loaded");
        }
    }

    private static long count(Connection connection, String sql, List<Object> parameters) throws SQLException
    {
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        return rows.isEmpty() ? 0 : number(rows.getFirst().values().iterator().next());
    }

    private static Page page(List<Map<String,Object>> queried, int limit, int offset, long total)
    {
        boolean more = queried.size() > limit;
        List<Map<String,Object>> rows = more ? new ArrayList<>(queried.subList(0, limit)) : queried;
        return new Page(rows, limit, offset, more, more ? offset + limit : null, total);
    }

    private static String siteKey(Map<String,Object> row)
    {
        if("CONVENTIONAL".equals(row.get("channel_kind")))
        {
            return RetainedSiteKey.conventional(number(row.get("channel_id")),
                number(row.get("channel_first_seen_ms")));
        }
        if(numberOrZero(row.get("p25_snapshot")) != 0)
        {
            return RetainedSiteKey.p25(integer(row.get("rfss")), integer(row.get("site")),
                number(row.get("channel_id")), nullableNumber(row.get("radio_system_id")),
                number(row.get("p25_first_seen_ms")));
        }
        if(row.get("trunked_first_seen_ms") == null)
        {
            return RetainedSiteKey.snapshotless(number(row.get("channel_id")),
                nullableNumber(row.get("radio_system_id")), number(row.get("channel_first_seen_ms")));
        }
        return RetainedSiteKey.trunked((int)number(row.get("protocol_code")),
            (int)number(row.get("variant_code")), (int)number(row.get("observed_location_category_code")),
            integer(row.get("observed_network_id")), integer(row.get("observed_system_id")),
            integer(row.get("observed_site_id")), integer(row.get("observed_ran")),
            integer(row.get("observed_model_code")), number(row.get("channel_id")),
            nullableNumber(row.get("radio_system_id")), number(row.get("trunked_first_seen_ms")));
    }

    private static Integer integer(Object value)
    {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long nullableNumber(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long numberOrZero(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static long number(Object value)
    {
        if(value instanceof Number number)
        {
            return number.longValue();
        }
        throw new IllegalStateException("Statistics row is missing a required number");
    }

    private static String like(String value)
    {
        return "%" + value.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static void requireSourceKind(String value)
    {
        if(!"radio_system".equals(value) && !"saved_channel".equals(value))
        {
            throw invalid("source_kind", "Select radio_system or saved_channel");
        }
    }

    private static void requireKey(String value, String field)
    {
        if(value == null || value.isBlank() || value.length() > 512)
        {
            throw invalid(field, field + " is required");
        }
    }

    private static StatsApiException invalid(String field, String message)
    {
        return new StatsApiException(400, "invalid_parameter", message, field);
    }

    record Page(List<Map<String,Object>> rows, int limit, int offset, boolean hasMore, Integer nextOffset,
                long totalCount)
    {
        Map<String,Object> meta()
        {
            Map<String,Object> meta = new LinkedHashMap<>();
            meta.put("limit", limit);
            meta.put("offset", offset);
            meta.put("has_more", hasMore);
            meta.put("next_offset", nextOffset);
            meta.put("total_count", totalCount);
            return meta;
        }
    }

    @FunctionalInterface
    private interface Query<T>
    {
        T run(Connection connection) throws SQLException;
    }
}
