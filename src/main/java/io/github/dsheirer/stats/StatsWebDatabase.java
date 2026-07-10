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

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.preference.UserPreferences;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only, bounded SQLite read models for the embedded Stats Server.
 */
class StatsWebDatabase
{
    private static final Logger mLog = LoggerFactory.getLogger(StatsWebDatabase.class);
    private static final long HOUR_MILLISECONDS = 3_600_000L;
    private static final int DASHBOARD_HOURS = 24;

    private final UserPreferences mUserPreferences;
    private final Path mDatabasePath;
    private final StatsAliasResolver mAliasResolver = new StatsAliasResolver();

    StatsWebDatabase(UserPreferences userPreferences)
    {
        this(userPreferences, SdrTrunkDatabasePath.getDatabasePath(userPreferences));
    }

    StatsWebDatabase(UserPreferences userPreferences, Path databasePath)
    {
        mUserPreferences = userPreferences;
        mDatabasePath = databasePath;
    }

    Map<String,Object> status()
    {
        Path path = getDatabasePath();
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("databasePath", path.toString());
        status.put("databaseExists", Files.isRegularFile(path));
        status.put("databaseBytes", fileBytes(path));
        status.put("walBytes", fileBytes(Path.of(path + "-wal")));
        status.put("shmBytes", fileBytes(Path.of(path + "-shm")));
        status.put("statsLoggingEnabled", mUserPreferences.getApplicationPreference().isStatsLoggingEnabled());
        status.put("detailedHistoryEnabled", mUserPreferences.getApplicationPreference().isStatsDetailedHistoryEnabled());
        status.put("retentionDays", mUserPreferences.getApplicationPreference().getStatsLoggingRetentionDays());

        try
        {
            status.put("logger", read(this::loggerStatus));
        }
        catch(StatsApiException e)
        {
            status.put("logger", List.of());
        }

        return status;
    }

    Map<String,Object> dashboard()
    {
        return read(connection -> {
            Map<String,Object> dashboard = new LinkedHashMap<>();
            dashboard.put("counts", Map.of(
                "systems", scalarLong(connection, "SELECT COUNT(*) FROM p25_system"),
                "sites", scalarLong(connection, "SELECT COUNT(*) FROM p25_site_snapshot"),
                "talkgroups", scalarLong(connection, "SELECT COUNT(*) FROM p25_talkgroup_summary"),
                "radios", scalarLong(connection, "SELECT COUNT(*) FROM p25_radio_summary"),
                "frequencies", scalarLong(connection, "SELECT COUNT(*) FROM p25_site_frequency_summary"),
                "conventional", scalarLong(connection, "SELECT COUNT(*) FROM conventional_activity_summary")
            ));
            dashboard.put("lastSeenMs", scalarLong(connection, """
                SELECT MAX(last_seen_ms) FROM (
                    SELECT last_seen_ms FROM p25_site_snapshot
                    UNION ALL SELECT last_seen_ms FROM p25_talkgroup_summary
                    UNION ALL SELECT last_seen_ms FROM p25_radio_summary
                    UNION ALL SELECT last_seen_ms FROM p25_site_frequency_summary
                    UNION ALL SELECT last_seen_ms FROM conventional_activity_summary
                )
                """));

            List<Map<String,Object>> talkgroups = queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, summary.talkgroup_id, summary.hits,
                    summary.encrypted_count, summary.last_source_radio_id, summary.last_seen_ms
                FROM p25_talkgroup_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                ORDER BY summary.hits DESC, summary.last_seen_ms DESC
                LIMIT 20
                """);
            mAliasResolver.enrichTalkgroups(connection, talkgroups);
            dashboard.put("topTalkgroups", talkgroups);

            List<Map<String,Object>> radios = queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, summary.radio_id, summary.hits,
                    summary.encrypted_count, summary.last_talkgroup_id, summary.last_talker_alias,
                    summary.last_talker_alias_seen_ms, summary.last_seen_ms
                FROM p25_radio_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                ORDER BY summary.hits DESC, summary.last_seen_ms DESC
                LIMIT 20
                """);
            mAliasResolver.enrichRadios(connection, radios);
            dashboard.put("topRadios", radios);
            dashboard.put("recentSites", queryRows(connection, siteSelect() + " ORDER BY site.last_seen_ms DESC LIMIT 12"));
            dashboard.put("actionMix", queryRows(connection, """
                SELECT action, COUNT(*) AS hits
                FROM p25_activity_event_resolved
                WHERE observed_at_ms >= ?
                GROUP BY action
                ORDER BY hits DESC, action
                """, System.currentTimeMillis() - 86_400_000L));
            dashboard.put("hitsPerHour", hourlyHits(connection));
            return dashboard;
        });
    }

    List<Map<String,Object>> activityByIds(List<Long> rowIds)
    {
        if(rowIds == null || rowIds.isEmpty())
        {
            return List.of();
        }

        return read(connection -> {
            String placeholders = String.join(",", java.util.Collections.nCopies(rowIds.size(), "?"));
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT id, context_id, context_key, guid, observed_at_ms, channel_kind, protocol, action,
                    event_type, source_radio_id, target_id, target_kind_code, target_kind, frequency_hz, lcn, timeslot,
                    encrypted, encryption_algorithm_id, encryption_key_id, resolved_channel_name,
                    resolved_alias_list_name, resolved_system_key AS system_key, resolved_wacn AS wacn,
                    resolved_system_id AS system_id, resolved_nac, resolved_rfss, resolved_site
                FROM p25_activity_event_resolved
                WHERE id IN (%s)
                ORDER BY id
                """.formatted(placeholders), rowIds.toArray());
            mAliasResolver.enrichActivity(connection, rows);
            return rows;
        });
    }

    Map<String,Object> systems(StatsRequest request)
    {
        return read(connection -> {
            String search = request.search();
            StringBuilder sql = new StringBuilder("""
                SELECT system.system_key, system.wacn, system.system_id, system.first_seen_ms,
                    system.last_seen_ms, COUNT(DISTINCT site.guid) AS sites,
                    (SELECT COUNT(*) FROM p25_talkgroup_summary talkgroup
                        WHERE talkgroup.system_key = system.system_key) AS talkgroups,
                    (SELECT COUNT(*) FROM p25_radio_summary radio
                        WHERE radio.system_key = system.system_key) AS radios,
                    (SELECT COUNT(*) FROM p25_radio_affiliation affiliation
                        WHERE affiliation.system_key = system.system_key) AS affiliations,
                    (SELECT group_concat(name, ', ') FROM (
                        SELECT DISTINCT channel_name AS name FROM p25_site_snapshot names
                        WHERE names.system_key = system.system_key AND channel_name IS NOT NULL
                        ORDER BY channel_name)) AS site_names
                FROM p25_system system
                LEFT JOIN p25_site_snapshot site ON site.system_key = system.system_key
                """);
            List<Object> parameters = new ArrayList<>();

            if(search != null)
            {
                sql.append(" WHERE CAST(system.wacn AS TEXT) LIKE ? OR CAST(system.system_id AS TEXT) LIKE ? " +
                    "OR EXISTS (SELECT 1 FROM p25_site_snapshot matched WHERE matched.system_key = system.system_key " +
                    "AND lower(matched.channel_name) LIKE ?)");
                String like = like(search);
                parameters.add(like);
                parameters.add(like);
                parameters.add(like);
            }

            sql.append(" GROUP BY system.system_key ORDER BY ")
                .append(order(request, Map.of(
                    "wacn", "system.wacn", "system_id", "system.system_id", "sites", "sites",
                    "talkgroups", "talkgroups", "radios", "radios", "last_seen", "system.last_seen_ms"),
                    "last_seen"))
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            return page(queryRows(connection, sql.toString(), parameters.toArray()), request);
        });
    }

    Map<String,Object> sites(StatsRequest request)
    {
        return read(connection -> page(querySites(connection, request, request.optionalIdentifier("wacn"),
            request.optionalIdentifier("system_id")), request));
    }

    Map<String,Object> system(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");

        return read(connection -> {
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("system", first(queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, system.first_seen_ms,
                    system.last_seen_ms,
                    (SELECT COUNT(*) FROM p25_site_snapshot site WHERE site.system_key = system.system_key) AS sites,
                    (SELECT COUNT(*) FROM p25_talkgroup_summary talkgroup
                        WHERE talkgroup.system_key = system.system_key) AS talkgroups,
                    (SELECT COUNT(*) FROM p25_radio_summary radio
                        WHERE radio.system_key = system.system_key) AS radios,
                    (SELECT COUNT(*) FROM p25_radio_affiliation affiliation
                        WHERE affiliation.system_key = system.system_key) AS affiliations,
                    (SELECT SUM(hits) FROM p25_talkgroup_summary talkgroup
                        WHERE talkgroup.system_key = system.system_key) AS activity_hits
                FROM p25_system system
                WHERE system.wacn = ? AND system.system_id = ?
                """, wacn, systemId), "System not found"));
            response.put("actionCounts", queryRows(connection, """
                SELECT action, COUNT(*) AS hits
                FROM p25_activity_event_resolved
                WHERE resolved_wacn = ? AND resolved_system_id = ?
                GROUP BY action ORDER BY hits DESC, action
                """, wacn, systemId));
            return response;
        });
    }

    Map<String,Object> systemSites(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");
        return read(connection -> page(querySites(connection, request, wacn, systemId), request));
    }

    Map<String,Object> systemTalkgroups(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");

        return read(connection -> {
            StringBuilder sql = new StringBuilder("""
                SELECT system.system_key, system.wacn, system.system_id, summary.*
                FROM p25_talkgroup_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                WHERE system.wacn = ? AND system.system_id = ?
                """);
            List<Object> parameters = new ArrayList<>(List.of(wacn, systemId));
            addIdentifierSearch(sql, parameters, request.search(), "summary.talkgroup_id");
            sql.append(" ORDER BY ").append(order(request, Map.of(
                "id", "summary.talkgroup_id", "hits", "summary.hits", "encrypted", "summary.encrypted_count",
                "first_seen", "summary.first_seen_ms", "last_seen", "summary.last_seen_ms"), "hits"))
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichTalkgroups(connection, rows);
            return page(rows, request);
        });
    }

    Map<String,Object> systemRadios(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");

        return read(connection -> {
            StringBuilder sql = new StringBuilder("""
                SELECT system.system_key, system.wacn, system.system_id, summary.*,
                    affiliation.talkgroup_id AS affiliated_talkgroup_id,
                    affiliation.updated_at_ms AS affiliation_updated_at_ms
                FROM p25_radio_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                LEFT JOIN p25_radio_affiliation affiliation
                  ON affiliation.system_key = summary.system_key AND affiliation.radio_id = summary.radio_id
                WHERE system.wacn = ? AND system.system_id = ?
                """);
            List<Object> parameters = new ArrayList<>(List.of(wacn, systemId));
            addIdentifierSearch(sql, parameters, request.search(), "summary.radio_id");
            sql.append(" ORDER BY ").append(order(request, Map.of(
                "id", "summary.radio_id", "hits", "summary.hits", "encrypted", "summary.encrypted_count",
                "first_seen", "summary.first_seen_ms", "last_seen", "summary.last_seen_ms"), "hits"))
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichRadios(connection, rows);
            mAliasResolver.enrichTalkgroups(connection, rows, "affiliated_talkgroup_id",
                "affiliated_talkgroup_alias_");
            return page(rows, request);
        });
    }

    Map<String,Object> talkgroup(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");
        int talkgroup = request.requiredIdentifier("talkgroup_id");

        return read(connection -> {
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, summary.*,
                    (SELECT COUNT(*) FROM p25_radio_talkgroup_summary relationship
                        WHERE relationship.system_key = summary.system_key
                          AND relationship.talkgroup_id = summary.talkgroup_id) AS radios,
                    (SELECT COUNT(*) FROM p25_radio_affiliation affiliation
                        WHERE affiliation.system_key = summary.system_key
                          AND affiliation.talkgroup_id = summary.talkgroup_id) AS affiliated_radios
                FROM p25_talkgroup_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                WHERE system.wacn = ? AND system.system_id = ? AND summary.talkgroup_id = ?
                """, wacn, systemId, talkgroup);
            mAliasResolver.enrichTalkgroups(connection, rows);
            return Map.of("talkgroup", first(rows, "Talkgroup not found"));
        });
    }

    Map<String,Object> radio(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");
        int radio = request.requiredIdentifier("radio_id");

        return read(connection -> {
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, summary.*,
                    affiliation.talkgroup_id AS affiliated_talkgroup_id,
                    affiliation.updated_at_ms AS affiliation_updated_at_ms,
                    (SELECT COUNT(*) FROM p25_radio_talkgroup_summary relationship
                        WHERE relationship.system_key = summary.system_key
                          AND relationship.radio_id = summary.radio_id) AS talkgroups
                FROM p25_radio_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                LEFT JOIN p25_radio_affiliation affiliation
                  ON affiliation.system_key = summary.system_key AND affiliation.radio_id = summary.radio_id
                WHERE system.wacn = ? AND system.system_id = ? AND summary.radio_id = ?
                """, wacn, systemId, radio);
            mAliasResolver.enrichRadios(connection, rows);
            mAliasResolver.enrichTalkgroups(connection, rows, "affiliated_talkgroup_id",
                "affiliated_talkgroup_alias_");
            return Map.of("radio", first(rows, "Radio not found"));
        });
    }

    Map<String,Object> currentAffiliations(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");
        Integer talkgroup = request.optionalIdentifier("talkgroup_id");
        Integer radio = request.optionalIdentifier("radio_id");

        return read(connection -> {
            StringBuilder sql = new StringBuilder("""
                SELECT system.system_key, system.wacn, system.system_id, affiliation.radio_id,
                    affiliation.talkgroup_id, affiliation.updated_at_ms, summary.last_talker_alias
                FROM p25_radio_affiliation affiliation
                JOIN p25_system system ON system.system_key = affiliation.system_key
                LEFT JOIN p25_radio_summary summary
                  ON summary.system_key = affiliation.system_key AND summary.radio_id = affiliation.radio_id
                WHERE system.wacn = ? AND system.system_id = ?
                """);
            List<Object> parameters = new ArrayList<>(List.of(wacn, systemId));

            if(talkgroup != null)
            {
                sql.append(" AND affiliation.talkgroup_id = ?");
                parameters.add(talkgroup);
            }

            if(radio != null)
            {
                sql.append(" AND affiliation.radio_id = ?");
                parameters.add(radio);
            }

            sql.append(" ORDER BY affiliation.updated_at_ms DESC LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichRadios(connection, rows);
            mAliasResolver.enrichTalkgroups(connection, rows);
            return page(rows, request);
        });
    }

    Map<String,Object> radioTalkgroupRelationships(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");
        Integer talkgroup = request.optionalIdentifier("talkgroup_id");
        Integer radio = request.optionalIdentifier("radio_id");

        return read(connection -> {
            StringBuilder sql = new StringBuilder("""
                SELECT system.system_key, system.wacn, system.system_id, relationship.*,
                    radio.last_talker_alias
                FROM p25_radio_talkgroup_summary relationship
                JOIN p25_system system ON system.system_key = relationship.system_key
                LEFT JOIN p25_radio_summary radio
                  ON radio.system_key = relationship.system_key AND radio.radio_id = relationship.radio_id
                WHERE system.wacn = ? AND system.system_id = ?
                """);
            List<Object> parameters = new ArrayList<>(List.of(wacn, systemId));

            if(talkgroup != null)
            {
                sql.append(" AND relationship.talkgroup_id = ?");
                parameters.add(talkgroup);
            }

            if(radio != null)
            {
                sql.append(" AND relationship.radio_id = ?");
                parameters.add(radio);
            }

            sql.append(" ORDER BY ").append(order(request, Map.of(
                "hits", "relationship.hits", "first_seen", "relationship.first_seen_ms",
                "last_seen", "relationship.last_seen_ms", "radio", "relationship.radio_id",
                "talkgroup", "relationship.talkgroup_id"), "last_seen")).append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichRelationships(connection, rows);
            return page(rows, request);
        });
    }

    Map<String,Object> site(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> Map.of("site", first(queryRows(connection,
            siteSelect() + " WHERE site.guid = ?", guid), "Site not found")));
    }

    Map<String,Object> siteChannels(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> Map.of("rows", queryRows(connection, """
            SELECT summary.channel_key, coalesce(current.descriptor, summary.descriptor) AS descriptor,
                coalesce(current.role, summary.role) AS role,
                coalesce(current.downlink_hz, summary.downlink_hz) AS downlink_hz,
                coalesce(current.uplink_hz, summary.uplink_hz) AS uplink_hz,
                coalesce(current.tdma, summary.tdma) AS tdma,
                coalesce(current.timeslots, summary.timeslots) AS timeslots, current.confirmed_at_ms,
                summary.first_seen_ms, summary.last_seen_ms, summary.observation_count,
                summary.primary_control_observations, summary.alternate_control_observations,
                summary.traffic_observations,
                CASE WHEN max(coalesce(current.confirmed_at_ms, 0), summary.last_seen_ms) >= ?
                    THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
            FROM p25_site_channel_summary summary
            LEFT JOIN p25_site_channel current
              ON current.guid = summary.guid AND current.channel_key = summary.channel_key
            WHERE summary.guid = ?
            ORDER BY coalesce(current.downlink_hz, summary.downlink_hz, 9223372036854775807), summary.channel_key
            """, System.currentTimeMillis() - 15_000L, guid)));
    }

    Map<String,Object> siteBands(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> Map.of("rows", queryRows(connection, """
            SELECT current.band, current.tdma, current.base_hz, current.bandwidth, current.spacing_hz,
                current.transmit_offset_hz, current.timeslots, current.confirmed_at_ms,
                summary.first_seen_ms, summary.last_seen_ms, summary.observation_count,
                CASE WHEN current.confirmed_at_ms >= ? THEN 'CURRENT' ELSE 'STALE' END AS state
            FROM p25_site_frequency_band current
            LEFT JOIN p25_site_frequency_band_summary summary
              ON summary.guid = current.guid AND summary.band = current.band
            WHERE current.guid = ? ORDER BY current.band
            """, System.currentTimeMillis() - 15_000L, guid)));
    }

    Map<String,Object> siteNeighbors(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> Map.of("rows", queryRows(connection, """
            SELECT summary.neighbor_key,
                coalesce(current.system_id, summary.system_id) AS system_id,
                coalesce(current.rfss, summary.rfss) AS rfss,
                coalesce(current.site, summary.site) AS site,
                coalesce(current.lra, summary.lra) AS lra,
                coalesce(current.channel_descriptor, summary.channel_descriptor) AS channel_descriptor,
                coalesce(current.downlink_hz, summary.downlink_hz) AS downlink_hz,
                coalesce(current.uplink_hz, summary.uplink_hz) AS uplink_hz,
                coalesce(current.status, summary.status) AS status,
                current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                summary.observation_count,
                CASE WHEN current.neighbor_key IS NULL THEN 'HISTORICAL'
                     WHEN current.confirmed_at_ms >= ? THEN 'CURRENT' ELSE 'STALE' END AS state
            FROM p25_site_neighbor_summary summary
            LEFT JOIN p25_site_neighbor current
              ON current.guid = summary.guid AND current.neighbor_key = summary.neighbor_key
            WHERE summary.guid = ?
            ORDER BY CASE WHEN current.neighbor_key IS NULL THEN 1 ELSE 0 END,
                system_id, rfss, site, summary.neighbor_key
            """, System.currentTimeMillis() - 15_000L, guid)));
    }

    Map<String,Object> sitePatches(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> {
            Map<String,Object> response = new LinkedHashMap<>();
            List<Map<String,Object>> groups = queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, current.patch_group, current.version,
                    current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                    summary.observation_count,
                    CASE WHEN current.confirmed_at_ms >= ? THEN 'CURRENT' ELSE 'STALE' END AS state
                FROM p25_site_patch_group current
                JOIN p25_site_snapshot site ON site.guid = current.guid
                LEFT JOIN p25_system system ON system.system_key = site.system_key
                LEFT JOIN p25_site_patch_group_summary summary
                  ON summary.guid = current.guid AND summary.patch_group = current.patch_group
                WHERE current.guid = ? ORDER BY current.patch_group
                """, System.currentTimeMillis() - 15_000L, guid);
            List<Map<String,Object>> talkgroups = queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, current.patch_group, current.talkgroup_id,
                    current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                    summary.observation_count
                FROM p25_site_patch_group_talkgroup current
                JOIN p25_site_snapshot site ON site.guid = current.guid
                LEFT JOIN p25_system system ON system.system_key = site.system_key
                LEFT JOIN p25_site_patch_group_talkgroup_summary summary
                  ON summary.guid = current.guid AND summary.patch_group = current.patch_group
                    AND summary.talkgroup_id = current.talkgroup_id
                WHERE current.guid = ? ORDER BY current.patch_group, current.talkgroup_id
                """, guid);
            List<Map<String,Object>> radios = queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, current.patch_group, current.radio_id,
                    current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                    summary.observation_count
                FROM p25_site_patch_group_radio current
                JOIN p25_site_snapshot site ON site.guid = current.guid
                LEFT JOIN p25_system system ON system.system_key = site.system_key
                LEFT JOIN p25_site_patch_group_radio_summary summary
                  ON summary.guid = current.guid AND summary.patch_group = current.patch_group
                    AND summary.radio_id = current.radio_id
                WHERE current.guid = ? ORDER BY current.patch_group, current.radio_id
                """, guid);
            mAliasResolver.enrichTalkgroups(connection, groups, "patch_group", "patch_alias_");
            mAliasResolver.enrichTalkgroups(connection, talkgroups, "talkgroup_id", "alias_");
            mAliasResolver.enrichRadios(connection, radios, "radio_id", "alias_");
            response.put("groups", groups);
            response.put("talkgroups", talkgroups);
            response.put("radios", radios);
            return response;
        });
    }

    Map<String,Object> activity(StatsRequest request)
    {
        return read(connection -> {
            StringBuilder sql = new StringBuilder("""
                SELECT id, context_id, context_key, guid, observed_at_ms, channel_kind, protocol, action,
                    event_type, source_radio_id, target_id, target_kind_code, target_kind, frequency_hz, lcn, timeslot,
                    encrypted, encryption_algorithm_id, encryption_key_id, resolved_channel_name,
                    resolved_alias_list_name, resolved_system_key AS system_key, resolved_wacn AS wacn,
                    resolved_system_id AS system_id, resolved_nac, resolved_rfss, resolved_site
                FROM p25_activity_event_resolved WHERE id < ?
                """);
            List<Object> parameters = new ArrayList<>();
            parameters.add(request.beforeId());
            Integer wacn = request.optionalIdentifier("wacn");
            Integer systemId = request.optionalIdentifier("system_id");
            Integer talkgroup = request.optionalIdentifier("talkgroup_id");
            Integer radio = request.optionalIdentifier("radio_id");
            String guid = request.text("guid");
            String context = request.text("context");

            if(wacn != null)
            {
                sql.append(" AND resolved_wacn = ?");
                parameters.add(wacn);
            }
            if(systemId != null)
            {
                sql.append(" AND resolved_system_id = ?");
                parameters.add(systemId);
            }
            if(guid != null)
            {
                sql.append(" AND guid = ?");
                parameters.add(guid);
            }
            if(context != null)
            {
                sql.append(" AND context_key = ?");
                parameters.add(context);
            }
            if(talkgroup != null)
            {
                sql.append(" AND target_id = ? AND target_kind_code IN (1, 3)");
                parameters.add(talkgroup);
            }
            if(radio != null)
            {
                sql.append(" AND (source_radio_id = ? OR (target_id = ? AND target_kind_code = 2))");
                parameters.add(radio);
                parameters.add(radio);
            }

            sql.append(" ORDER BY id DESC LIMIT ?");
            parameters.add(request.limit() + 1);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichActivity(connection, rows);
            return cursorPage(rows, request.limit());
        });
    }

    Map<String,Object> conventional(StatsRequest request)
    {
        return read(connection -> {
            StringBuilder sql = new StringBuilder("""
                SELECT context.id AS context_id, context.context_key, context.guid, context.kind_code,
                    context.protocol_code, context.channel_name, context.alias_list_name, context.decoder,
                    context.nac, context.primary_frequency_hz, summary.frequency_hz, summary.timeslot,
                    summary.first_seen_ms, summary.last_seen_ms, summary.hits, summary.last_event_type_code
                FROM conventional_activity_summary summary
                JOIN receiver_context context ON context.id = summary.context_id
                WHERE context.kind_code <> 1
                """);
            List<Object> parameters = new ArrayList<>();

            if(request.search() != null)
            {
                sql.append(" AND (lower(context.channel_name) LIKE ? OR CAST(summary.frequency_hz AS TEXT) LIKE ?)");
                String like = like(request.search());
                parameters.add(like);
                parameters.add(like);
            }

            sql.append(" ORDER BY ").append(order(request, Map.of(
                "name", "context.channel_name", "frequency", "summary.frequency_hz", "hits", "summary.hits",
                "last_seen", "summary.last_seen_ms"), "frequency")).append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            return page(queryRows(connection, sql.toString(), parameters.toArray()), request);
        });
    }

    Map<String,Object> conventionalDetail(StatsRequest request)
    {
        String contextKey = request.requiredText("context");
        return read(connection -> {
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("context", first(queryRows(connection, """
                SELECT id AS context_id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, nac, primary_frequency_hz, first_seen_ms, last_seen_ms
                FROM receiver_context WHERE context_key = ? AND kind_code <> 1
                """, contextKey), "Conventional context not found"));
            response.put("summaries", queryRows(connection, """
                SELECT summary.* FROM conventional_activity_summary summary
                JOIN receiver_context context ON context.id = summary.context_id
                WHERE context.context_key = ? ORDER BY summary.frequency_hz, summary.timeslot
                """, contextKey));
            return response;
        });
    }

    private List<Map<String,Object>> querySites(Connection connection, StatsRequest request, Integer wacn,
                                                 Integer systemId) throws SQLException
    {
        StringBuilder sql = new StringBuilder(siteSelect()).append(" WHERE 1=1");
        List<Object> parameters = new ArrayList<>();

        if(wacn != null)
        {
            sql.append(" AND system.wacn = ?");
            parameters.add(wacn);
        }
        if(systemId != null)
        {
            sql.append(" AND system.system_id = ?");
            parameters.add(systemId);
        }
        if(request.search() != null)
        {
            sql.append(" AND (lower(site.channel_name) LIKE ? OR lower(site.guid) LIKE ?)");
            String like = like(request.search());
            parameters.add(like);
            parameters.add(like);
        }

        sql.append(" ORDER BY ").append(order(request, Map.of(
            "name", "site.channel_name", "wacn", "system.wacn", "system_id", "system.system_id",
            "rfss", "site.rfss", "site", "site.site", "last_seen", "site.last_seen_ms"), "last_seen"))
            .append(" LIMIT ? OFFSET ?");
        addPageParameters(parameters, request);
        return queryRows(connection, sql.toString(), parameters.toArray());
    }

    private static String siteSelect()
    {
        return """
            SELECT site.guid, site.system_key, site.protocol, site.channel_name, site.alias_list_name,
                site.decoder, system.wacn, system.system_id, site.nac, site.rfss, site.site,
                site.primary_frequency_hz, site.current_control_hz, site.first_seen_ms, site.last_seen_ms,
                site.observation_count,
                (SELECT COUNT(*) FROM p25_site_channel channel WHERE channel.guid = site.guid) AS channels,
                (SELECT COUNT(*) FROM p25_site_neighbor neighbor WHERE neighbor.guid = site.guid) AS neighbors,
                (SELECT COUNT(*) FROM p25_site_frequency_band band WHERE band.guid = site.guid) AS bands,
                (SELECT COUNT(*) FROM p25_site_patch_group patch WHERE patch.guid = site.guid) AS patches
            FROM p25_site_snapshot site
            LEFT JOIN p25_system system ON system.system_key = site.system_key
            """;
    }

    private List<Map<String,Object>> loggerStatus(Connection connection) throws SQLException
    {
        return queryRows(connection, "SELECT key, value, updated_at_ms FROM logger_status ORDER BY key");
    }

    private static List<Map<String,Object>> hourlyHits(Connection connection) throws SQLException
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), HOUR_MILLISECONDS) * HOUR_MILLISECONDS;
        long firstHour = currentHour - (DASHBOARD_HOURS - 1L) * HOUR_MILLISECONDS;
        List<Map<String,Object>> stored = queryRows(connection, """
            SELECT bucket_start_ms, SUM(hits) AS hits
            FROM (
                SELECT bucket_start_ms, hits FROM p25_site_talkgroup_bucket WHERE bucket_start_ms >= ?
                UNION ALL
                SELECT bucket_start_ms, hits FROM conventional_activity_bucket WHERE bucket_start_ms >= ?
            )
            GROUP BY bucket_start_ms
            ORDER BY bucket_start_ms
            """, firstHour, firstHour);
        Map<Long,Long> totals = new LinkedHashMap<>();

        for(Map<String,Object> row: stored)
        {
            Object hour = row.get("bucket_start_ms");
            Object hits = row.get("hits");

            if(hour instanceof Number hourNumber && hits instanceof Number hitNumber)
            {
                totals.put(hourNumber.longValue(), hitNumber.longValue());
            }
        }

        List<Map<String,Object>> result = new ArrayList<>(DASHBOARD_HOURS);

        for(long hour = firstHour; hour <= currentHour; hour += HOUR_MILLISECONDS)
        {
            result.add(Map.of("hour_ms", hour, "hits", totals.getOrDefault(hour, 0L)));
        }

        return result;
    }

    private <T> T read(Query<T> query)
    {
        try(Connection connection = openReadOnly())
        {
            return query.execute(connection);
        }
        catch(StatsApiException e)
        {
            throw e;
        }
        catch(IOException | SQLException e)
        {
            mLog.warn("Stats Server database query failed", e);
            throw new StatsApiException(503, "Stats database is unavailable");
        }
    }

    private Connection openReadOnly() throws IOException, SQLException
    {
        Path databasePath = getDatabasePath();

        if(!Files.isRegularFile(databasePath))
        {
            throw new IOException("Stats database is missing");
        }

        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
            statement.execute("PRAGMA query_only=ON");
        }

        return connection;
    }

    private Path getDatabasePath()
    {
        return mDatabasePath;
    }

    private static long fileBytes(Path path)
    {
        try
        {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        }
        catch(IOException e)
        {
            return 0;
        }
    }

    private static String order(StatsRequest request, Map<String,String> columns, String defaultSort)
    {
        String column = columns.getOrDefault(request.sort(defaultSort), columns.get(defaultSort));
        return column + (request.descending() ? " DESC" : " ASC");
    }

    private static void addPageParameters(List<Object> parameters, StatsRequest request)
    {
        parameters.add(request.limit() + 1);
        parameters.add(request.offset());
    }

    private static void addIdentifierSearch(StringBuilder sql, List<Object> parameters, String search,
                                            String column)
    {
        if(search != null)
        {
            sql.append(" AND CAST(").append(column).append(" AS TEXT) LIKE ?");
            parameters.add(like(search));
        }
    }

    private static String like(String value)
    {
        return "%" + value.toLowerCase() + "%";
    }

    private static Map<String,Object> page(List<Map<String,Object>> queriedRows, StatsRequest request)
    {
        int limit = request.limit();
        boolean hasMore = queriedRows.size() > limit;
        List<Map<String,Object>> rows = hasMore ? new ArrayList<>(queriedRows.subList(0, limit)) : queriedRows;
        Map<String,Object> page = new LinkedHashMap<>();
        page.put("rows", rows);
        page.put("limit", limit);
        page.put("offset", request.offset());
        page.put("hasMore", hasMore);
        page.put("nextOffset", hasMore ? request.offset() + limit : null);
        return page;
    }

    private static Map<String,Object> cursorPage(List<Map<String,Object>> queriedRows, int limit)
    {
        boolean hasMore = queriedRows.size() > limit;
        List<Map<String,Object>> rows = hasMore ? new ArrayList<>(queriedRows.subList(0, limit)) : queriedRows;
        Object nextBeforeId = hasMore && !rows.isEmpty() ? rows.get(rows.size() - 1).get("id") : null;
        return Map.of("rows", rows, "limit", limit, "hasMore", hasMore,
            "nextBeforeId", nextBeforeId != null ? nextBeforeId : 0);
    }

    private static Map<String,Object> first(List<Map<String,Object>> rows, String notFoundMessage)
    {
        if(rows.isEmpty())
        {
            throw new StatsApiException(404, notFoundMessage);
        }

        return rows.get(0);
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static List<Map<String,Object>> queryRows(Connection connection, String sql, Object... parameters)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            for(int x = 0; x < parameters.length; x++)
            {
                statement.setObject(x + 1, parameters[x]);
            }

            try(ResultSet resultSet = statement.executeQuery())
            {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                List<Map<String,Object>> rows = new ArrayList<>();

                while(resultSet.next())
                {
                    Map<String,Object> row = new LinkedHashMap<>();

                    for(int column = 1; column <= columnCount; column++)
                    {
                        row.put(metaData.getColumnLabel(column), resultSet.getObject(column));
                    }

                    rows.add(row);
                }

                return rows;
            }
        }
    }

    private interface Query<T>
    {
        T execute(Connection connection) throws IOException, SQLException;
    }
}
