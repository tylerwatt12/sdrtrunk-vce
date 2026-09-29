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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Bounded, read-only target discovery for retained-statistics administration. */
final class RetainedStatisticsCatalog
{
    private static final String CHANNEL_LABEL = "coalesce(nullif(trim(config.site_name), ''), " +
        "nullif(trim(config.name), ''), config.configuration_id)";
    private static final String SYSTEM_LABEL = "coalesce((SELECT nullif(trim(config.system_name), '') " +
        "FROM receiver_channel channel JOIN configuration_channel config " +
        "ON config.configuration_id=channel.configuration_id WHERE channel.radio_system_id=system.id " +
        "AND nullif(trim(config.system_name), '') IS NOT NULL " +
        "ORDER BY channel.last_seen_ms DESC LIMIT 1), system.system_key)";
    private static final String SITE_SELECT = """
        SELECT config.configuration_id, %s AS label, config.channel_kind,
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
        LEFT JOIN radio_system system ON system.id=channel.radio_system_id
        LEFT JOIN p25_site_snapshot p25 ON p25.channel_id=channel.id
        LEFT JOIN trunked_site_snapshot trunked ON trunked.channel_id=channel.id
        WHERE (config.channel_kind='CONVENTIONAL' OR p25.channel_id IS NOT NULL
               OR trunked.channel_id IS NOT NULL)
        """.formatted(CHANNEL_LABEL);
    private static final String IDENTITY_KEY_SQL = "'v1-' || CASE summary.identity_kind_code " +
        "WHEN 1 THEN 'g' WHEN 2 THEN 'r' END || '-' || " +
        "CASE WHEN summary.home_wacn=-1 THEN 'x' ELSE printf('%05x',summary.home_wacn) END || '-' || " +
        "CASE WHEN summary.home_system_id=-1 THEN 'x' ELSE printf('%03x',summary.home_system_id) END || '-' || " +
        "summary.identity_id";

    private final Path mDatabasePath;

    RetainedStatisticsCatalog(Path databasePath)
    {
        mDatabasePath = Objects.requireNonNull(databasePath);
    }

    Page sources(String kind, String search, int limit, int offset)
    {
        if(!"radio_system".equals(kind) && !"saved_channel".equals(kind))
        {
            throw invalid("kind", "Select radio_system or saved_channel");
        }
        return read(connection -> {
            String sql;
            List<Object> parameters = new ArrayList<>();
            if("radio_system".equals(kind))
            {
                sql = "SELECT system.system_key AS source_key, " + SYSTEM_LABEL +
                    " AS label, CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR' " +
                    "WHEN 4 THEN 'NXDN' END AS protocol, system.last_seen_ms " +
                    "FROM radio_system system WHERE 1=1";
                if(search != null)
                {
                    sql += " AND lower(" + SYSTEM_LABEL + " || ' ' || system.system_key) LIKE ? ESCAPE '\\'";
                    parameters.add(like(search));
                }
            }
            else
            {
                sql = "SELECT config.configuration_id AS source_key, " + CHANNEL_LABEL +
                    " AS label, config.decoder_type AS protocol, config.channel_kind, " +
                    "system.system_key AS radio_system_key, " +
                    "channel.last_seen_ms FROM receiver_channel channel " +
                    "JOIN configuration_channel config ON config.configuration_id=channel.configuration_id " +
                    "LEFT JOIN radio_system system ON system.id=channel.radio_system_id WHERE 1=1";
                if(search != null)
                {
                    sql += " AND lower(" + CHANNEL_LABEL + " || ' ' || config.configuration_id) " +
                        "LIKE ? ESCAPE '\\'";
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

    Page results(String sourceKind, String sourceKey, String dataType, String siteConfigurationId,
                 String search, int limit, int offset)
    {
        requireSourceKind(sourceKind);
        requireKey(sourceKey, "source_key");
        if(!List.of("frequencies", "radios", "talkgroups", "sites", "channels", "systems")
            .contains(dataType))
        {
            throw invalid("data_type", "Select a supported data type");
        }
        if("saved_channel".equals(sourceKind) && !List.of("frequencies", "radios", "talkgroups",
            "sites", "channels")
            .contains(dataType))
        {
            throw invalid("data_type", "This data type requires a radio system");
        }
        if("frequencies".equals(dataType) && (siteConfigurationId == null || siteConfigurationId.isBlank()))
        {
            throw invalid("site_configuration_id", "Select a saved site first");
        }
        if(!"frequencies".equals(dataType) && siteConfigurationId != null)
        {
            throw invalid("site_configuration_id", "Site selection is only used for frequencies");
        }
        return read(connection -> switch(dataType)
        {
            case "frequencies" -> frequencies(connection, sourceKind, sourceKey, siteConfigurationId,
                search, limit, offset);
            case "radios", "talkgroups" -> "radio_system".equals(sourceKind) ?
                identities(connection, sourceKey, dataType, search, limit, offset) :
                conventionalIdentities(connection, sourceKey, dataType, search, limit, offset);
            case "sites" -> siteResults(connection, sourceKind, sourceKey, search, limit, offset);
            case "channels" -> channelResults(connection, sourceKind, sourceKey, search, limit, offset);
            case "systems" -> systemResults(connection, sourceKey, search, limit, offset);
            default -> throw new IllegalStateException("Unreachable data type");
        });
    }

    private static Page sitePage(Connection connection, String sourceKind, String sourceKey,
                                 String search, int limit, int offset) throws SQLException
    {
        String predicate = "radio_system".equals(sourceKind) ? "system.system_key=?" :
            "config.configuration_id=?";
        String sql = SITE_SELECT + " AND " + predicate;
        List<Object> parameters = new ArrayList<>(List.of(sourceKey));
        if(search != null)
        {
            sql += " AND lower(" + CHANNEL_LABEL + " || ' ' || config.configuration_id) " +
                "LIKE ? ESCAPE '\\'";
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

    private static Page frequencies(Connection connection, String sourceKind, String sourceKey,
                                    String siteConfigurationId, String search, int limit, int offset)
        throws SQLException
    {
        String siteSql = SITE_SELECT + " AND config.configuration_id=? AND " +
            ("radio_system".equals(sourceKind) ? "system.system_key=?" : "config.configuration_id=?");
        List<Map<String,Object>> siteRows = queryRows(connection, siteSql, siteConfigurationId, sourceKey);
        if(siteRows.isEmpty())
        {
            throw new StatsApiException(404, "site_not_found", "Saved site was not found in this source");
        }
        Map<String,Object> site = siteRows.getFirst();
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
            base = "SELECT coalesce(current.downlink_hz,summary.downlink_hz) AS frequency_hz, " +
                "SUM(summary.observation_count) AS observation_count, " +
                "MAX(summary.last_seen_ms) AS last_seen_ms " +
                "FROM p25_site_channel_summary summary LEFT JOIN p25_site_channel current " +
                "ON current.channel_id=summary.channel_id AND current.channel_key=summary.channel_key " +
                "WHERE summary.channel_id=? GROUP BY coalesce(current.downlink_hz,summary.downlink_hz) " +
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
        for(int index = 0; index < ("CONVENTIONAL".equals(site.get("channel_kind")) ? 4 : 1); index++)
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
            row.put("target", Map.of("kind", "frequency", "site_configuration_id", siteConfigurationId,
                "expected_site_key", ownerKey, "frequency_hz", hz));
        }
        return page(rows, limit, offset, total);
    }

    private static Page identities(Connection connection, String systemKey, String dataType,
                                   String search, int limit, int offset) throws SQLException
    {
        int kind = "radios".equals(dataType) ? 2 : 1;
        String sql = "SELECT summary.identity_id AS native_id, summary.home_wacn, " +
            "summary.home_system_id, summary.last_seen_ms, " +
            "summary.logical_call_count AS observation_count, " + IDENTITY_KEY_SQL + " AS identity_key " +
            "FROM radio_system_identity_summary summary JOIN radio_system system " +
            "ON system.id=summary.radio_system_id WHERE system.system_key=? " +
            "AND summary.identity_kind_code=?";
        List<Object> parameters = new ArrayList<>(List.of(systemKey, kind));
        if(search != null)
        {
            sql += " AND CAST(summary.identity_id AS TEXT) LIKE ? ESCAPE '\\'";
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY summary.identity_id, summary.home_wacn, summary.home_system_id, " +
            "summary.id LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        for(Map<String,Object> row: rows)
        {
            row.put("label", (kind == 2 ? "Radio " : "Talkgroup ") + row.get("native_id"));
            long homeWacn = number(row.get("home_wacn"));
            long homeSystem = number(row.get("home_system_id"));
            if(homeWacn >= 0 || homeSystem >= 0)
            {
                row.put("detail", "Home WACN " + (homeWacn < 0 ? "unknown" :
                    String.format(Locale.ROOT, "%05X", homeWacn)) + " · system " +
                    (homeSystem < 0 ? "unknown" : String.format(Locale.ROOT, "%03X", homeSystem)));
            }
            row.put("target", Map.of("kind", kind == 2 ? "radio" : "talkgroup",
                "radio_system_key", systemKey, "identity_key", row.get("identity_key")));
        }
        return page(rows, limit, offset, total);
    }

    private static Page conventionalIdentities(Connection connection, String configurationId,
                                               String dataType, String search, int limit, int offset)
        throws SQLException
    {
        List<Map<String,Object>> channels = queryRows(connection, """
            SELECT channel.id AS channel_id FROM receiver_channel channel
            JOIN configuration_channel config ON config.configuration_id=channel.configuration_id
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
        String sql = "SELECT " + identityColumn + " AS native_id, frequency_hz, timeslot, " +
            "call_count AS observation_count, last_seen_ms FROM " + table + " WHERE channel_id=?";
        List<Object> parameters = new ArrayList<>(List.of(channelId));
        if(search != null)
        {
            sql += " AND CAST(" + identityColumn + " AS TEXT) LIKE ? ESCAPE '\\'";
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY " + identityColumn + ", frequency_hz, timeslot LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        for(Map<String,Object> row: rows)
        {
            long frequencyHz = number(row.get("frequency_hz"));
            int timeslot = (int)number(row.get("timeslot"));
            int nativeId = (int)number(row.get("native_id"));
            row.put("label", ("radios".equals(dataType) ? "Radio " : "Talkgroup ") + nativeId);
            row.put("detail", String.format(Locale.ROOT, "%.6f MHz · slot %d",
                frequencyHz / 1_000_000.0, timeslot));
            row.put("target", Map.of("kind", "radios".equals(dataType) ? "conventional_radio" :
                "conventional_talkgroup", "configuration_id", configurationId,
                "frequency_hz", frequencyHz, "timeslot", timeslot, "native_id", nativeId));
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
                "'Learned site' AS detail, learned.last_seen_ms, NULL AS channel_kind, " +
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
                "p25.rfss,p25.site,config.configuration_id," + CHANNEL_LABEL + " AS label, " +
                "'Saved channel' AS detail, " +
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
                    "coalesce(CAST(rfss AS TEXT),'') || ' ' || coalesce(CAST(site AS TEXT),'')) " +
                    "LIKE ? ESCAPE '\\'";
                parameters.add(like(search));
            }
        }
        else
        {
            sql = "SELECT 'saved' AS site_kind, config.configuration_id, " + CHANNEL_LABEL +
                " AS label, coalesce(p25.last_seen_ms,trunked.last_seen_ms,channel.last_seen_ms) " +
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
                "LEFT JOIN p25_site_snapshot p25 ON p25.channel_id=channel.id " +
                "LEFT JOIN trunked_site_snapshot trunked ON trunked.channel_id=channel.id " +
                "WHERE config.configuration_id=? AND config.channel_kind='TRUNKED' AND " +
                "(p25.channel_id IS NOT NULL OR trunked.channel_id IS NOT NULL)";
            parameters.add(sourceKey);
            if(search != null)
            {
                sql += " AND lower(" + CHANNEL_LABEL + ") LIKE ? ESCAPE '\\'";
                parameters.add(like(search));
            }
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY lower(label),site_kind,configuration_id,rfss,site LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        for(Map<String,Object> row: rows)
        {
            if("learned".equals(row.get("site_kind")))
            {
                row.put("target", Map.of("kind", "learned_site", "radio_system_key", sourceKey,
                    "rfss", row.get("rfss"), "site", row.get("site"),
                    "include_channel_history", false));
            }
            else
            {
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
            "config.channel_kind, channel.last_seen_ms FROM receiver_channel channel " +
            "JOIN configuration_channel config ON config.configuration_id=channel.configuration_id " +
            "LEFT JOIN radio_system system ON system.id=channel.radio_system_id WHERE " +
            ("radio_system".equals(sourceKind) ? "system.system_key=?" : "config.configuration_id=?");
        List<Object> parameters = new ArrayList<>(List.of(sourceKey));
        if(search != null)
        {
            sql += " AND lower(" + CHANNEL_LABEL + ") LIKE ? ESCAPE '\\'";
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY lower(label),config.configuration_id LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        for(Map<String,Object> row: rows)
        {
            row.put("target", Map.of("kind", "channel", "configuration_id",
                row.get("configuration_id")));
        }
        return page(rows, limit, offset, total);
    }

    private static Page systemResults(Connection connection, String sourceKey, String search,
                                      int limit, int offset) throws SQLException
    {
        String sql = "SELECT system.system_key AS radio_system_key, " + SYSTEM_LABEL +
            " AS label, system.last_seen_ms FROM radio_system system WHERE system.system_key=?";
        List<Object> parameters = new ArrayList<>(List.of(sourceKey));
        if(search != null)
        {
            sql += " AND lower(" + SYSTEM_LABEL + " || ' ' || system.system_key) " +
                "LIKE ? ESCAPE '\\'";
            parameters.add(like(search));
        }
        long total = count(connection, "SELECT COUNT(*) FROM (" + sql + ")", parameters);
        sql += " ORDER BY label LIMIT ? OFFSET ?";
        parameters.add(limit + 1);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql, parameters.toArray());
        for(Map<String,Object> row: rows)
        {
            row.put("target", Map.of("kind", "system", "radio_system_key", sourceKey,
                "include_channel_history", false));
        }
        return page(rows, limit, offset, total);
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
                number(row.get("channel_id")), integer(row.get("radio_system_id")),
                number(row.get("p25_first_seen_ms")));
        }
        return RetainedSiteKey.trunked((int)number(row.get("protocol_code")),
            (int)number(row.get("variant_code")), (int)number(row.get("observed_location_category_code")),
            integer(row.get("observed_network_id")), integer(row.get("observed_system_id")),
            integer(row.get("observed_site_id")), integer(row.get("observed_ran")),
            integer(row.get("observed_model_code")), number(row.get("channel_id")),
            integer(row.get("radio_system_id")), number(row.get("trunked_first_seen_ms")));
    }

    private static Integer integer(Object value)
    {
        return value instanceof Number number ? number.intValue() : null;
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
