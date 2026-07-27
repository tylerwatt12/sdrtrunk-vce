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

import io.github.dsheirer.module.decode.p25.reference.Vendor;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
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
    private static final long DAY_MILLISECONDS = 24L * HOUR_MILLISECONDS;
    private static final long CURRENT_STATE_WINDOW_MILLISECONDS = 6L * HOUR_MILLISECONDS;
    private static final long QUALITY_BUCKET_MILLISECONDS = 10_000L;
    private static final int QUALITY_DEFAULT_POINTS = 240;
    private static final int QUALITY_MINIMUM_POINTS = 60;
    private static final int QUALITY_MAXIMUM_POINTS = 360;
    private static final int ACTIVITY_TARGET_POINTS = 240;
    private static final int DASHBOARD_HOURS = 24;
    static final String DASHBOARD_SITE_ACTIVITY_SQL = """
        SELECT context.id AS context_id, context.guid, context.channel_name, context.rfss, context.site,
            system.system_key, system.wacn, system.system_id,
            SUM(bucket.call_count) AS call_count,
            SUM(SUM(bucket.call_count)) OVER () AS total_call_count
        FROM p25_site_activity_bucket bucket
        JOIN receiver_context context ON context.id = bucket.context_id
        JOIN p25_system system ON system.system_key = context.system_key
        WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
        GROUP BY context.id, context.guid, context.channel_name, context.rfss, context.site,
            system.system_key, system.wacn, system.system_id
        HAVING SUM(bucket.call_count) > 0
        ORDER BY call_count DESC, system.wacn ASC, system.system_id ASC, context.rfss ASC, context.site ASC
        """;
    static final String ACTIVITY_SELECT_SQL = """
        SELECT id, context_id, context_key, guid, observed_at_ms, channel_kind, protocol, action,
            event_type, source_radio_id, target_id, target_kind_code, target_kind, frequency_hz, lcn, timeslot,
            encrypted, encryption_algorithm_id, encryption_key_id, resolved_channel_name,
            resolved_alias_list_name, resolved_system_key AS system_key, resolved_wacn AS wacn,
            resolved_system_id AS system_id, resolved_nac, resolved_rfss, resolved_site
        FROM p25_activity_event_resolved WHERE 1 = 1
        """;
    static final String ACTIVITY_ORDER_SQL = " ORDER BY observed_at_ms DESC, id DESC LIMIT ?";
    private static final int DIRECTORY_SITE_LIMIT_PER_SYSTEM = 500;
    private static final List<String> CALL_ACTIVITY_FIELDS = List.of(
        "call_count", "recorded_count", "streamed_count"
    );
    private static final List<String> TALKGROUP_ACTIVITY_FIELDS = List.of(
        "acknowledge_count", "active_count", "busy_count", "call_count", "check_count", "check_ack_count",
        "continue_count", "data_count", "denial_count", "emergency_count", "gps_count",
        "join_count", "logout_count", "page_count", "patch_count", "patch_cancel_count", "patch_create_count",
        "queued_count", "register_count", "request_count", "status_count", "unknown_count", "encrypted_count",
        "recorded_count", "streamed_count"
    );
    private static final Map<String,String> SYSTEM_SORT_COLUMNS = Map.ofEntries(
        Map.entry("wacn", "system.wacn"),
        Map.entry("system_id", "system.system_id"),
        Map.entry("site_names", "lower(site_names)"),
        Map.entry("sites", "sites"),
        Map.entry("talkgroups", "talkgroups"),
        Map.entry("radios", "radios"),
        Map.entry("affiliations", "affiliations"),
        Map.entry("first_seen", "system.first_seen_ms"),
        Map.entry("last_seen", "system.last_seen_ms")
    );
    private static final Map<String,String> SITE_SORT_COLUMNS = Map.ofEntries(
        Map.entry("system", "system.wacn * 4096 + system.system_id"),
        Map.entry("wacn", "system.wacn"),
        Map.entry("system_id", "system.system_id"),
        Map.entry("rfss", "site.rfss"),
        Map.entry("site", "site.site"),
        Map.entry("name", "lower(site.channel_name)"),
        Map.entry("protocol", "lower(site.protocol)"),
        Map.entry("decoder", "lower(site.decoder)"),
        Map.entry("control", "site.current_control_hz"),
        Map.entry("control_frequency", "site.current_control_hz"),
        Map.entry("channels", "channels"),
        Map.entry("neighbors", "neighbors"),
        Map.entry("bands", "bands"),
        Map.entry("observations", "site.observation_count"),
        Map.entry("first_seen", "site.first_seen_ms"),
        Map.entry("last_seen", "site.last_seen_ms")
    );
    private static final Map<String,String> TALKGROUP_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.talkgroup_id"),
        Map.entry("talkgroup", "summary.talkgroup_id"),
        Map.entry("alias", aliasSortExpression("alias_talkgroup", "summary.talkgroup_id", "name")),
        Map.entry("name", aliasSortExpression("alias_talkgroup", "summary.talkgroup_id", "name")),
        Map.entry("group", aliasSortExpression("alias_talkgroup", "summary.talkgroup_id", "group_name")),
        Map.entry("calls", "summary.call_count"),
        Map.entry("recorded", "summary.recorded_count"),
        Map.entry("streamed", "summary.streamed_count"),
        Map.entry("grants", "summary.grant_count"),
        Map.entry("encrypted", "summary.encrypted_count"),
        Map.entry("last_source", "summary.last_source_radio_id"),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> RADIO_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.radio_id"),
        Map.entry("radio", "summary.radio_id"),
        Map.entry("alias", aliasSortExpression("alias_radio", "summary.radio_id", "name")),
        Map.entry("name", aliasSortExpression("alias_radio", "summary.radio_id", "name")),
        Map.entry("talker_alias", "lower(summary.last_talker_alias)"),
        Map.entry("talker_alias_seen", "summary.last_talker_alias_seen_ms"),
        Map.entry("last_talkgroup", "summary.last_talkgroup_id"),
        Map.entry("last_talkgroup_name", aliasSortExpression("alias_talkgroup", "summary.last_talkgroup_id", "name")),
        Map.entry("calls", "summary.call_count"),
        Map.entry("grants", "summary.grant_count"),
        Map.entry("encrypted", "summary.encrypted_count"),
        Map.entry("affiliated_talkgroup", aliasSortExpression("alias_talkgroup",
            "affiliation.talkgroup_id", "name")),
        Map.entry("affiliation_updated", "affiliation.updated_at_ms"),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> AFFILIATION_SORT_COLUMNS = Map.ofEntries(
        Map.entry("radio", "affiliation.radio_id"),
        Map.entry("radio_alias", aliasSortExpression("alias_radio", "affiliation.radio_id", "name")),
        Map.entry("talker_alias", "lower(summary.last_talker_alias)"),
        Map.entry("talkgroup", "affiliation.talkgroup_id"),
        Map.entry("talkgroup_alias", aliasSortExpression("alias_talkgroup", "affiliation.talkgroup_id", "name")),
        Map.entry("updated", "affiliation.updated_at_ms"),
        Map.entry("last_seen", "affiliation.updated_at_ms")
    );
    private static final Map<String,String> RELATIONSHIP_SORT_COLUMNS = Map.ofEntries(
        Map.entry("radio", "relationship.radio_id"),
        Map.entry("radio_alias", aliasSortExpression("alias_radio", "relationship.radio_id", "name")),
        Map.entry("talker_alias", "lower(radio.last_talker_alias)"),
        Map.entry("talkgroup", "relationship.talkgroup_id"),
        Map.entry("talkgroup_alias", aliasSortExpression("alias_talkgroup", "relationship.talkgroup_id", "name")),
        Map.entry("calls", "relationship.call_count"),
        Map.entry("grants", "relationship.grant_count"),
        Map.entry("encrypted", "relationship.encrypted_count"),
        Map.entry("affiliated", "EXISTS (SELECT 1 FROM p25_radio_affiliation current_affiliation " +
            "WHERE current_affiliation.system_key = relationship.system_key " +
            "AND current_affiliation.radio_id = relationship.radio_id " +
            "AND current_affiliation.talkgroup_id = relationship.talkgroup_id)"),
        Map.entry("first_seen", "relationship.first_seen_ms"),
        Map.entry("last_seen", "relationship.last_seen_ms")
    );
    private static final Map<String,String> CONVENTIONAL_SORT_COLUMNS = Map.ofEntries(
        Map.entry("name", "lower(context.channel_name)"),
        Map.entry("protocol", "context.protocol_code"),
        Map.entry("decoder", "lower(context.decoder)"),
        Map.entry("frequency", "summary.frequency_hz"),
        Map.entry("slot", "summary.timeslot"),
        Map.entry("nac", "context.nac"),
        Map.entry("calls", "summary.call_count"),
        Map.entry("event", "summary.last_event_type_code"),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> DMR_CONVENTIONAL_TALKGROUP_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.talkgroup_id"),
        Map.entry("talkgroup", "summary.talkgroup_id"),
        Map.entry("alias", dmrAliasSortExpression("alias_talkgroup", "summary.talkgroup_id", "name")),
        Map.entry("name", dmrAliasSortExpression("alias_talkgroup", "summary.talkgroup_id", "name")),
        Map.entry("frequency", "summary.frequency_hz"),
        Map.entry("slot", "summary.timeslot"),
        Map.entry("calls", "summary.call_count"),
        Map.entry("encrypted", "summary.encrypted_count"),
        Map.entry("last_source", "summary.last_source_radio_id"),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> DMR_CONVENTIONAL_RADIO_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.radio_id"),
        Map.entry("radio", "summary.radio_id"),
        Map.entry("alias", dmrAliasSortExpression("alias_radio", "summary.radio_id", "name")),
        Map.entry("name", dmrAliasSortExpression("alias_radio", "summary.radio_id", "name")),
        Map.entry("frequency", "summary.frequency_hz"),
        Map.entry("slot", "summary.timeslot"),
        Map.entry("calls", "summary.call_count"),
        Map.entry("source_calls", "summary.source_call_count"),
        Map.entry("target_calls", "summary.target_call_count"),
        Map.entry("group_calls", "summary.group_call_count"),
        Map.entry("private_calls", "summary.private_call_count"),
        Map.entry("encrypted", "summary.encrypted_count"),
        Map.entry("last_talkgroup", "summary.last_talkgroup_id"),
        Map.entry("last_talkgroup_name",
            dmrAliasSortExpression("alias_talkgroup", "summary.last_talkgroup_id", "name")),
        Map.entry("last_peer", "summary.last_peer_radio_id"),
        Map.entry("last_peer_name", dmrAliasSortExpression("alias_radio", "summary.last_peer_radio_id", "name")),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );

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
            status.putAll(read(connection -> {
                Map<String,Object> details = new LinkedHashMap<>();
                long lastDetailedHistoryMs = scalarLong(connection, """
                    SELECT COALESCE((SELECT observed_at_ms FROM p25_activity_event ORDER BY id DESC LIMIT 1), 0)
                    """);
                details.put("logger", loggerStatus(connection));
                details.put("detailedHistoryAvailable", lastDetailedHistoryMs > 0);
                details.put("lastDetailedHistoryMs", lastDetailedHistoryMs);
                return details;
            }));
        }
        catch(StatsApiException e)
        {
            status.put("logger", List.of());
            status.put("detailedHistoryAvailable", false);
            status.put("lastDetailedHistoryMs", 0);
        }

        return status;
    }

    Map<String,Object> dashboard()
    {
        return read(connection -> {
            Map<String,Object> dashboard = new LinkedHashMap<>();
            dashboard.put("counts", Map.of(
                "systems", scalarLong(connection, """
                    SELECT (SELECT COUNT(*) FROM p25_system) + (SELECT COUNT(*) FROM (
                        SELECT protocol_code, variant_code, identity_domain_code, network_id, system_id,
                            CASE WHEN network_id IS NULL AND system_id IS NULL
                                THEN lower(coalesce(nullif(trim(configured_system), ''),
                                    nullif(trim(channel_name), ''), guid))
                                ELSE '' END AS configured_group
                        FROM trunked_site_snapshot
                        GROUP BY protocol_code, variant_code, identity_domain_code, network_id, system_id,
                            configured_group
                    ))
                    """),
                "sites", scalarLong(connection, """
                    SELECT (SELECT COUNT(*) FROM p25_site_snapshot) +
                        (SELECT COUNT(*) FROM trunked_site_snapshot)
                    """),
                "talkgroups", scalarLong(connection, "SELECT COUNT(*) FROM p25_talkgroup_summary"),
                "radios", scalarLong(connection, "SELECT COUNT(*) FROM p25_radio_summary"),
                "frequencies", scalarLong(connection, """
                    SELECT (SELECT COUNT(*) FROM p25_site_frequency_summary) +
                        (SELECT COUNT(*) FROM trunked_site_channel_summary WHERE frequency_hz > 0)
                    """),
                "conventional", scalarLong(connection, "SELECT COUNT(*) FROM conventional_activity_summary")
            ));
            dashboard.put("lastSeenMs", scalarLong(connection, """
                SELECT MAX(last_seen_ms) FROM (
                    SELECT last_seen_ms FROM p25_site_snapshot
                    UNION ALL SELECT last_seen_ms FROM p25_talkgroup_summary
                    UNION ALL SELECT last_seen_ms FROM p25_radio_summary
                    UNION ALL SELECT last_seen_ms FROM p25_site_frequency_summary
                    UNION ALL SELECT last_seen_ms FROM conventional_activity_summary
                    UNION ALL SELECT last_seen_ms FROM trunked_site_snapshot
                )
                """));

            List<Map<String,Object>> talkgroups = queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, summary.talkgroup_id, summary.call_count,
                    summary.recorded_count, summary.streamed_count, summary.encrypted_count,
                    summary.last_source_radio_id, summary.last_seen_ms
                FROM p25_talkgroup_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                ORDER BY summary.call_count DESC, summary.last_seen_ms DESC
                LIMIT 20
                """);
            mAliasResolver.enrichTalkgroups(connection, talkgroups);
            dashboard.put("topTalkgroups", talkgroups);

            List<Map<String,Object>> radios = queryRows(connection, """
                SELECT system.system_key, system.wacn, system.system_id, summary.radio_id, summary.call_count,
                    summary.grant_count, summary.continue_count,
                    summary.encrypted_count, summary.last_talkgroup_id, summary.last_talker_alias,
                    summary.last_talker_alias_seen_ms, summary.last_seen_ms
                FROM p25_radio_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                ORDER BY summary.call_count DESC, summary.last_seen_ms DESC
                LIMIT 20
                """);
            mAliasResolver.enrichRadios(connection, radios);
            dashboard.put("topRadios", radios);
            dashboard.put("recentSites", queryRows(connection, siteSelect() + """
                 WHERE site.system_key IS NOT NULL AND site.rfss IS NOT NULL AND site.site IS NOT NULL
                 ORDER BY site.last_seen_ms DESC LIMIT 12
                """));
            List<Map<String,Object>> hourlyActivity = hourlyActivity(connection);
            dashboard.put("activityPerHour", hourlyActivity);
            dashboard.put("p25CallActivity", p25CallActivity(connection, hourlyActivity));
            dashboard.put("siteActivity24h", siteActivity24Hours(connection));
            return dashboard;
        });
    }

    Map<String,Object> qualityHistory(StatsRequest request)
    {
        String guid = request.text("guid");
        boolean includeHistory = !"false".equalsIgnoreCase(request.text("include_history"));
        String range = request.text("range");
        range = range != null ? range.toLowerCase() : "6h";
        long requestedRangeMilliseconds = switch(range)
        {
            case "1h" -> HOUR_MILLISECONDS;
            case "6h" -> 6L * HOUR_MILLISECONDS;
            case "24h" -> DAY_MILLISECONDS;
            case "7d" -> 7L * DAY_MILLISECONDS;
            case "30d" -> 30L * DAY_MILLISECONDS;
            default -> throw new StatsApiException(400, "range must be one of 1h, 6h, 24h, 7d, or 30d");
        };
        Integer requestedPoints = request.optionalInt("points");
        int targetPoints = Math.max(QUALITY_MINIMUM_POINTS, Math.min(QUALITY_MAXIMUM_POINTS,
            requestedPoints != null ? requestedPoints : QUALITY_DEFAULT_POINTS));
        long retentionMilliseconds = Math.max(1,
            mUserPreferences.getApplicationPreference().getStatsLoggingRetentionDays()) * DAY_MILLISECONDS;
        long rangeMilliseconds = Math.min(requestedRangeMilliseconds, retentionMilliseconds);
        long rawBucketMilliseconds = Math.max(1, (rangeMilliseconds + targetPoints - 1) / targetPoints);
        long bucketMilliseconds = Math.max(QUALITY_BUCKET_MILLISECONDS,
            ((rawBucketMilliseconds + QUALITY_BUCKET_MILLISECONDS - 1) / QUALITY_BUCKET_MILLISECONDS) *
                QUALITY_BUCKET_MILLISECONDS);
        long toMilliseconds = System.currentTimeMillis();
        long fromMilliseconds = toMilliseconds - rangeMilliseconds;
        String responseRange = range;
        String qualitySiteSelect = qualitySiteSelect();

        return read(connection -> {
            Map<String,Map<String,Object>> sitesByGuid = new LinkedHashMap<>();
            List<Map<String,Object>> sites = queryRows(connection, """
                SELECT site.*
                FROM (
                    %s
                ) site
                WHERE (? IS NULL OR site.guid = ?)
                ORDER BY lower(coalesce(site.channel_name, site.guid)), site.guid
                """.formatted(qualitySiteSelect), guid, guid);

            for(Map<String,Object> site: sites)
            {
                site.put("series", new ArrayList<Map<String,Object>>());
                sitesByGuid.put(site.get("guid").toString(), site);
            }

            List<Map<String,Object>> latest = queryRows(connection, """
                SELECT quality.guid, quality.frequency_hz AS quality_frequency_hz,
                    quality.observed_at_ms AS last_observed_ms, quality.signal_dbfs,
                    quality.average_signal_dbfs, quality.minimum_signal_dbfs, quality.maximum_signal_dbfs,
                    quality.decode_health_pct, quality.valid_frames, quality.invalid_frames,
                    quality.corrected_bits, quality.sync_loss_bits, quality.dropped_bits,
                    quality.last_valid_decode_ms
                FROM (
                    %s
                ) site
                JOIN p25_control_channel_quality quality ON quality.guid = site.guid AND
                    (quality.frequency_hz, quality.bucket_start_ms) = (
                    SELECT candidate.frequency_hz, candidate.bucket_start_ms
                    FROM p25_control_channel_quality candidate
                    WHERE candidate.guid = site.guid
                    ORDER BY candidate.observed_at_ms DESC, candidate.frequency_hz DESC LIMIT 1
                )
                WHERE (? IS NULL OR site.guid = ?)
                """.formatted(qualitySiteSelect), guid, guid);

            for(Map<String,Object> quality: latest)
            {
                Map<String,Object> site = sitesByGuid.get(quality.get("guid"));

                if(site != null)
                {
                    quality.forEach((key, value) -> {
                        if(!"guid".equals(key))
                        {
                            site.put(key, value);
                        }
                    });
                }
            }

            if(includeHistory)
            {
                String guidClause = guid != null ? " AND guid = ?" : "";
                List<Object> seriesParameters = new ArrayList<>(List.of(bucketMilliseconds, bucketMilliseconds,
                    fromMilliseconds, toMilliseconds));

                if(guid != null)
                {
                    seriesParameters.add(guid);
                }

                List<Map<String,Object>> series = queryRows(connection, """
                    SELECT guid, (observed_at_ms / ?) * ? AS time_ms,
                        avg(average_signal_dbfs) AS average_signal_dbfs,
                        min(minimum_signal_dbfs) AS minimum_signal_dbfs,
                        max(maximum_signal_dbfs) AS maximum_signal_dbfs,
                        avg(decode_health_pct) AS decode_health_pct,
                        CASE WHEN min(frequency_hz) = max(frequency_hz) THEN min(frequency_hz) END AS frequency_hz,
                        count(DISTINCT frequency_hz) AS frequency_count, count(*) AS sample_count,
                        max(observed_at_ms) AS last_observed_ms
                    FROM p25_control_channel_quality
                    WHERE observed_at_ms >= ? AND observed_at_ms <= ?%s
                    GROUP BY guid, time_ms
                    ORDER BY guid, time_ms
                    """.formatted(guidClause), seriesParameters.toArray());

                for(Map<String,Object> point: series)
                {
                    Map<String,Object> site = sitesByGuid.get(point.get("guid"));

                    if(site != null && site.get("series") instanceof List<?> values)
                    {
                        @SuppressWarnings("unchecked")
                        List<Map<String,Object>> points = (List<Map<String,Object>>)values;
                        point.remove("guid");
                        points.add(point);
                    }
                }
            }

            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", responseRange);
            response.put("from_ms", fromMilliseconds);
            response.put("to_ms", toMilliseconds);
            response.put("bucket_ms", bucketMilliseconds);
            response.put("target_points", targetPoints);
            response.put("history_included", includeHistory);
            response.put("sites", new ArrayList<>(sitesByGuid.values()));
            return response;
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
                .append(order(request, SYSTEM_SORT_COLUMNS, "last_seen"))
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            return page(queryRows(connection, sql.toString(), parameters.toArray()), request);
        });
    }

    Map<String,Object> systemDirectory(StatsRequest request)
    {
        return read(connection -> {
            String search = request.search();
            String searchLike = search != null ? like(search) : null;
            List<Map<String,Object>> parentRows = queryRows(connection, """
                WITH parents AS (
                    SELECT 1 AS protocol_code, 'P25' AS protocol,
                        'p25:' || system.wacn || ':' || system.system_id AS system_group_key,
                        system.system_key, system.wacn, system.system_id, NULL AS network_id,
                        0 AS variant_code, 0 AS identity_domain_code,
                        NULL AS configured_system, system.first_seen_ms, system.last_seen_ms,
                        COUNT(DISTINCT site.guid) AS sites,
                        (SELECT COUNT(*) FROM p25_talkgroup_summary talkgroup
                            WHERE talkgroup.system_key = system.system_key) AS talkgroups,
                        (SELECT COUNT(*) FROM p25_radio_summary radio
                            WHERE radio.system_key = system.system_key) AS radios,
                        (SELECT COUNT(*) FROM p25_radio_affiliation affiliation
                            WHERE affiliation.system_key = system.system_key) AS affiliations,
                        (SELECT group_concat(name, ', ') FROM (
                            SELECT DISTINCT channel_name AS name FROM p25_site_snapshot names
                            WHERE names.system_key = system.system_key AND channel_name IS NOT NULL
                              AND NOT EXISTS (
                                  SELECT 1 FROM trunked_site_snapshot trunked
                                  WHERE trunked.guid = names.guid
                                    AND trunked.last_seen_ms > names.last_seen_ms
                              )
                            ORDER BY channel_name)) AS site_names,
                        lower('P25 ' || system.wacn || ' ' || system.system_id || ' ' ||
                            coalesce(group_concat(site.channel_name, ' '), '') || ' ' ||
                            coalesce(group_concat(site.guid, ' '), '')) AS search_text
                    FROM p25_system system
                    LEFT JOIN p25_site_snapshot site ON site.system_key = system.system_key
                        AND NOT EXISTS (
                            SELECT 1 FROM trunked_site_snapshot trunked
                            WHERE trunked.guid = site.guid
                              AND trunked.last_seen_ms > site.last_seen_ms
                        )
                    GROUP BY system.system_key

                    UNION ALL

                    SELECT site.protocol_code,
                        CASE site.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                        'trunked:' || site.protocol_code || ':' || site.variant_code || ':' ||
                            site.identity_domain_code || ':' ||
                            coalesce(site.network_id, -1) || ':' || coalesce(site.system_id, -1) || ':' ||
                            CASE WHEN site.network_id IS NULL AND site.system_id IS NULL
                                THEN lower(coalesce(nullif(trim(site.configured_system), ''),
                                    nullif(trim(site.channel_name), ''), site.guid))
                                ELSE '' END AS system_group_key,
                        NULL AS system_key, NULL AS wacn, site.system_id, site.network_id,
                        site.variant_code, site.identity_domain_code,
                        coalesce(min(nullif(trim(site.configured_system), '')),
                            min(nullif(trim(site.channel_name), ''))) AS configured_system,
                        min(site.first_seen_ms) AS first_seen_ms, max(site.last_seen_ms) AS last_seen_ms,
                        count(*) AS sites, 0 AS talkgroups, 0 AS radios, 0 AS affiliations,
                        group_concat(DISTINCT site.channel_name) AS site_names,
                        lower(
                            CASE site.protocol_code WHEN 3 THEN 'DMR ' WHEN 4 THEN 'NXDN ' ELSE '' END ||
                            coalesce(site.network_id, '') || ' ' || coalesce(site.system_id, '') || ' ' ||
                            coalesce(group_concat(site.configured_system, ' '), '') || ' ' ||
                            coalesce(group_concat(site.channel_name, ' '), '') || ' ' ||
                            coalesce(group_concat(site.guid, ' '), '')
                        ) AS search_text
                    FROM trunked_site_snapshot site
                    WHERE NOT EXISTS (
                        SELECT 1 FROM p25_site_snapshot p25
                        WHERE p25.guid = site.guid
                          AND (p25.last_seen_ms > site.last_seen_ms OR
                            (p25.last_seen_ms = site.last_seen_ms AND 1 < site.protocol_code))
                    )
                    GROUP BY site.protocol_code, site.variant_code, site.identity_domain_code, site.network_id,
                        site.system_id,
                        CASE WHEN site.network_id IS NULL AND site.system_id IS NULL
                            THEN lower(coalesce(nullif(trim(site.configured_system), ''),
                                nullif(trim(site.channel_name), ''), site.guid))
                            ELSE '' END
                )
                SELECT protocol_code, protocol, system_group_key, system_key, wacn, system_id, network_id,
                    variant_code, identity_domain_code, configured_system, first_seen_ms, last_seen_ms, sites,
                    talkgroups, radios, affiliations, site_names
                FROM parents
                WHERE (? IS NULL OR search_text LIKE ?)
                ORDER BY protocol_code ASC, wacn IS NULL ASC, wacn ASC,
                    variant_code ASC, identity_domain_code ASC, network_id IS NULL ASC, network_id ASC,
                    system_id IS NULL ASC, system_id ASC,
                    lower(coalesce(configured_system, site_names, system_group_key)), system_group_key
                LIMIT ? OFFSET ?
                """, search, searchLike, request.limit() + 1, request.offset());
            Map<String,Object> response = page(parentRows, request);
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> systems = (List<Map<String,Object>>)response.get("rows");

            if(systems.isEmpty())
            {
                return response;
            }

            List<Long> systemKeys = systems.stream()
                .map(row -> row.get("system_key"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .toList();
            List<String> trunkedGroupKeys = systems.stream()
                .map(row -> row.get("system_group_key"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(key -> key.startsWith("trunked:"))
                .toList();
            Map<String,List<Map<String,Object>>> sitesBySystem = new LinkedHashMap<>();

            if(!systemKeys.isEmpty())
            {
                String placeholders = String.join(",", java.util.Collections.nCopies(systemKeys.size(), "?"));
                List<Object> siteParameters = new ArrayList<>(systemKeys);
                siteParameters.add(DIRECTORY_SITE_LIMIT_PER_SYSTEM);
                List<Map<String,Object>> sites = queryRows(connection, siteSelect() + """
                    JOIN (
                        SELECT guid, row_number() OVER (
                            PARTITION BY system_key
                            ORDER BY rfss IS NULL ASC, rfss ASC, site IS NULL ASC, site ASC, guid ASC
                        ) AS directory_rank
                        FROM p25_site_snapshot
                        WHERE system_key IN (%s)
                          AND NOT EXISTS (
                            SELECT 1 FROM trunked_site_snapshot trunked
                            WHERE trunked.guid = p25_site_snapshot.guid
                              AND trunked.last_seen_ms > p25_site_snapshot.last_seen_ms
                          )
                    ) directory ON directory.guid = site.guid
                    WHERE directory.directory_rank <= ?
                    ORDER BY system.wacn ASC, system.system_id ASC,
                        site.rfss IS NULL ASC, site.rfss ASC, site.site IS NULL ASC, site.site ASC, site.guid ASC
                    """.formatted(placeholders), siteParameters.toArray());

                for(Map<String,Object> site: sites)
                {
                    String groupKey = "p25:" + number(site.get("wacn")) + ":" + number(site.get("system_id"));
                    site.put("protocol_code", 1);
                    site.put("protocol", "P25");
                    site.put("site_kind", "p25");
                    site.put("system_group_key", groupKey);
                    sitesBySystem.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(site);
                }
            }

            if(!trunkedGroupKeys.isEmpty())
            {
                String placeholders = String.join(",",
                    java.util.Collections.nCopies(trunkedGroupKeys.size(), "?"));
                List<Object> siteParameters = new ArrayList<>(trunkedGroupKeys);
                siteParameters.add(DIRECTORY_SITE_LIMIT_PER_SYSTEM);
                List<Map<String,Object>> sites = queryRows(connection, """
                    SELECT * FROM (
                        SELECT site.guid, site.snapshot_hash, site.protocol_code,
                            CASE site.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN'
                                ELSE 'Unknown' END AS protocol,
                            site.variant_code, site.identity_domain_code, site.configured_system, site.channel_name,
                            site.alias_list_name, site.decoder, site.network_id, site.system_id, site.site_id, site.ran,
                            site.model_code, site.brand_code, site.mode_code, site.channel_type_code,
                            site.color_code_ts1, site.color_code_ts2, site.current_repeater,
                            site.service_flags, site.failure_code, site.primary_frequency_hz,
                            site.current_control_hz, site.first_seen_ms, site.last_seen_ms,
                            site.observation_count,
                            'trunked:' || site.protocol_code || ':' || site.variant_code || ':' ||
                                site.identity_domain_code || ':' ||
                                coalesce(site.network_id, -1) || ':' || coalesce(site.system_id, -1) || ':' ||
                                CASE WHEN site.network_id IS NULL AND site.system_id IS NULL
                                    THEN lower(coalesce(nullif(trim(site.configured_system), ''),
                                        nullif(trim(site.channel_name), ''), site.guid))
                                    ELSE '' END AS system_group_key,
                            (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                                WHERE channel.guid = site.guid) AS channels,
                            (SELECT COUNT(*) FROM trunked_site_neighbor_summary neighbor
                                WHERE neighbor.guid = site.guid) AS neighbors,
                            row_number() OVER (
                                PARTITION BY site.protocol_code, site.variant_code, site.identity_domain_code,
                                    site.network_id, site.system_id,
                                    CASE WHEN site.network_id IS NULL AND site.system_id IS NULL
                                        THEN lower(coalesce(nullif(trim(site.configured_system), ''),
                                            nullif(trim(site.channel_name), ''), site.guid))
                                        ELSE '' END
                                ORDER BY site.site_id IS NULL ASC, site.site_id ASC, site.ran IS NULL ASC,
                                    site.ran ASC, site.guid ASC
                            ) AS directory_rank
                        FROM trunked_site_snapshot site
                        WHERE NOT EXISTS (
                            SELECT 1 FROM p25_site_snapshot p25
                            WHERE p25.guid = site.guid
                              AND (p25.last_seen_ms > site.last_seen_ms OR
                                (p25.last_seen_ms = site.last_seen_ms AND 1 < site.protocol_code))
                        )
                    ) ranked
                    WHERE system_group_key IN (%s) AND directory_rank <= ?
                    ORDER BY protocol_code, variant_code, identity_domain_code, network_id IS NULL ASC,
                        network_id ASC, system_id IS NULL ASC, system_id ASC, site_id IS NULL ASC, site_id ASC,
                        ran IS NULL ASC, ran ASC, guid
                    """.formatted(placeholders), siteParameters.toArray());

                for(Map<String,Object> site: sites)
                {
                    site.put("site_kind", "trunked");
                    String groupKey = String.valueOf(site.get("system_group_key"));
                    sitesBySystem.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(site);
                }
            }

            for(Map<String,Object> system: systems)
            {
                String groupKey = String.valueOf(system.get("system_group_key"));
                List<Map<String,Object>> children = sitesBySystem.getOrDefault(groupKey, List.of());
                system.put("children", children);
                system.put("children_truncated", number(system.get("sites")) > children.size());
            }

            return response;
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
                    (SELECT SUM(call_count) FROM p25_talkgroup_summary talkgroup
                        WHERE talkgroup.system_key = system.system_key) AS activity_calls
                FROM p25_system system
                WHERE system.wacn = ? AND system.system_id = ?
                """, wacn, systemId), "System not found"));
            response.put("actionCounts", systemActionCounts(connection, wacn, systemId));
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
                SELECT system.system_key, system.wacn, system.system_id, summary.talkgroup_id,
                    summary.first_seen_ms, summary.last_seen_ms, summary.call_count, summary.encrypted_count,
                    summary.recorded_count, summary.streamed_count
                FROM p25_talkgroup_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                WHERE system.wacn = ? AND system.system_id = ?
                """);
            List<Object> parameters = new ArrayList<>(List.of(wacn, systemId));
            addIdentifierSearch(sql, parameters, request.search(), "summary.talkgroup_id");
            sql.append(" ORDER BY ").append(order(request, TALKGROUP_SORT_COLUMNS, "calls"))
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
            sql.append(" ORDER BY ").append(order(request, RADIO_SORT_COLUMNS, "calls"))
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichRadios(connection, rows);
            mAliasResolver.enrichTalkgroups(connection, rows, "affiliated_talkgroup_id",
                "affiliated_talkgroup_alias_");
            return page(rows, request);
        });
    }

    Map<String,Object> systemTalkerAliases(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");

        return read(connection -> {
            StringBuilder sql = new StringBuilder("""
                SELECT system.system_key, system.wacn, system.system_id, summary.*
                FROM p25_radio_summary summary
                JOIN p25_system system ON system.system_key = summary.system_key
                WHERE system.wacn = ? AND system.system_id = ?
                  AND summary.last_talker_alias IS NOT NULL
                  AND trim(summary.last_talker_alias) <> ''
                """);
            List<Object> parameters = new ArrayList<>(List.of(wacn, systemId));

            if(request.search() != null)
            {
                sql.append(" AND (CAST(summary.radio_id AS TEXT) LIKE ? " +
                    "OR lower(summary.last_talker_alias) LIKE ?)");
                String like = like(request.search());
                parameters.add(like);
                parameters.add(like);
            }

            sql.append(" ORDER BY ").append(order(request, RADIO_SORT_COLUMNS, "talker_alias"))
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichRadios(connection, rows);
            mAliasResolver.enrichTalkgroups(connection, rows, "last_talkgroup_id", "talkgroup_alias_");
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

    Map<String,Object> talkgroupActivity(StatsRequest request)
    {
        int wacn = request.requiredIdentifier("wacn");
        int systemId = request.requiredIdentifier("system_id");
        int talkgroup = request.requiredIdentifier("talkgroup_id");
        ActivityRange requestedRange = activityRange(request);
        long rangeMilliseconds = requestedRange.milliseconds();
        long sourceBuckets = Math.max(1, (rangeMilliseconds + HOUR_MILLISECONDS - 1) / HOUR_MILLISECONDS);
        long combinedHours = Math.max(1, (sourceBuckets + ACTIVITY_TARGET_POINTS - 1) / ACTIVITY_TARGET_POINTS);
        long bucketMilliseconds = combinedHours * HOUR_MILLISECONDS;
        long toMilliseconds = System.currentTimeMillis();
        long throughMilliseconds = Math.floorDiv(toMilliseconds, bucketMilliseconds) * bucketMilliseconds;
        long pointCount = Math.max(1, (rangeMilliseconds + bucketMilliseconds - 1) / bucketMilliseconds);
        long fromMilliseconds = throughMilliseconds - (pointCount - 1) * bucketMilliseconds;
        String responseRange = requestedRange.label();

        return read(connection -> {
            String sums = String.join(",\n                    ", TALKGROUP_ACTIVITY_FIELDS.stream()
                .map(field -> "SUM(bucket." + field + ") AS " + field)
                .toList());
            List<Map<String,Object>> stored = queryRows(connection, """
                SELECT CAST(bucket.bucket_start_ms / ? AS INTEGER) * ? AS time_ms,
                    %s
                FROM p25_site_talkgroup_bucket bucket
                JOIN receiver_context context ON context.id = bucket.context_id
                JOIN p25_system system ON system.system_key = context.system_key
                WHERE system.wacn = ? AND system.system_id = ? AND bucket.talkgroup_id = ?
                    AND bucket.bucket_start_ms >= ?
                GROUP BY time_ms
                ORDER BY time_ms
                """.formatted(sums), bucketMilliseconds, bucketMilliseconds, wacn, systemId, talkgroup,
                fromMilliseconds);
            Map<Long,Map<String,Object>> storedByTime = new LinkedHashMap<>();

            for(Map<String,Object> row: stored)
            {
                if(row.get("time_ms") instanceof Number timestamp)
                {
                    storedByTime.put(timestamp.longValue(), row);
                }
            }

            List<Map<String,Object>> series = new ArrayList<>();
            Map<String,Long> totals = new LinkedHashMap<>();

            for(String field: TALKGROUP_ACTIVITY_FIELDS)
            {
                totals.put(field, 0L);
            }

            for(long timestamp = fromMilliseconds; timestamp <= throughMilliseconds;
                timestamp += bucketMilliseconds)
            {
                Map<String,Object> storedRow = storedByTime.get(timestamp);
                Map<String,Object> point = new LinkedHashMap<>();
                point.put("time_ms", timestamp);

                for(String field: TALKGROUP_ACTIVITY_FIELDS)
                {
                    long value = storedRow != null ? number(storedRow.get(field)) : 0L;
                    point.put(field, value);
                    totals.compute(field, (key, total) -> total + value);
                }

                series.add(point);
            }

            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", responseRange);
            response.put("from_ms", fromMilliseconds);
            response.put("to_ms", toMilliseconds);
            response.put("bucket_ms", bucketMilliseconds);
            response.put("metric_start_ms", p25CallOutputMetricsStartedAt(connection));
            response.put("totals", totals);
            response.put("series", series);
            return response;
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

            sql.append(" ORDER BY ").append(order(request, AFFILIATION_SORT_COLUMNS, "updated"))
                .append(" LIMIT ? OFFSET ?");
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

            sql.append(" ORDER BY ").append(order(request, RELATIONSHIP_SORT_COLUMNS, "last_seen"))
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichRelationships(connection, rows);
            return page(rows, request);
        });
    }

    Map<String,Object> site(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> {
            List<Map<String,Object>> p25Sites = queryRows(connection, siteSelect() + " WHERE site.guid = ?", guid);
            List<Map<String,Object>> trunkedSites =
                queryRows(connection, trunkedSiteSelect() + " WHERE site.guid = ?", guid);
            Map<String,Object> p25Site = p25Sites.isEmpty() ? null : p25Sites.getFirst();
            Map<String,Object> trunkedSite = trunkedSites.isEmpty() ? null : trunkedSites.getFirst();

            if(p25Site == null && trunkedSite == null)
            {
                throw new StatsApiException(404, "Site not found");
            }

            boolean p25OwnsGuid = p25Site != null && (trunkedSite == null ||
                number(p25Site.get("last_seen_ms")) >= number(trunkedSite.get("last_seen_ms")));
            Map<String,Object> site = p25OwnsGuid ? p25Site : trunkedSite;
            site.put("site_type", "trunked");
            site.put("capabilities", siteCapabilities(p25OwnsGuid));

            if(p25OwnsGuid)
            {
                site.put("protocol_code", 1);
                site.put("site_kind", "p25");
                Object mfid = site.get("mfid");

                if(mfid instanceof Number number)
                {
                    site.put("mfid_display", mfidDisplay(number.intValue()));
                }
            }

            return Map.of("site", site);
        });
    }

    Map<String,Object> siteChannels(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> {
            if(isTrunkedSite(connection, guid))
            {
                return Map.of("rows", queryRows(connection, """
                    SELECT NULLIF(channel_number, -1) AS channel_number,
                        NULLIF(inbound_channel_number, -1) AS inbound_channel_number,
                        NULLIF(timeslot, -1) AS timeslot,
                        NULLIF(frequency_hz, -1) AS frequency_hz,
                        NULLIF(frequency_hz, -1) AS downlink_hz,
                        uplink_hz, role_flags, first_seen_ms, last_seen_ms, observation_count,
                        CASE WHEN last_seen_ms >= ? THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM trunked_site_channel_summary
                    WHERE guid = ?
                    ORDER BY channel_number = -1, channel_number, timeslot = -1, timeslot,
                        frequency_hz = -1, frequency_hz
                    LIMIT ?
                    """, System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS, guid, request.limit()));
            }

            return Map.of("rows", queryRows(connection, """
            WITH tag_summary AS (
                SELECT guid, channel_key, group_concat(tag) AS tags,
                    max(CASE WHEN tag = 'CONTROL' THEN observation_count ELSE 0 END) AS control_observations,
                    max(CASE WHEN tag = 'ALTERNATE_CONTROL' THEN observation_count ELSE 0 END) AS alternate_control_observations,
                    max(CASE WHEN tag = 'DATA_ANNOUNCED' THEN observation_count ELSE 0 END) AS data_announcement_observations,
                    max(CASE WHEN tag = 'VOICE' THEN observation_count ELSE 0 END) AS voice_grant_observations,
                    max(CASE WHEN tag = 'DATA' THEN observation_count ELSE 0 END) AS data_grant_observations
                FROM p25_site_channel_tag_summary
                WHERE guid = ?
                GROUP BY guid, channel_key
            ), current_tags AS (
                SELECT guid, channel_key, group_concat(tag) AS current_tags
                FROM p25_site_channel_tag
                WHERE guid = ?
                GROUP BY guid, channel_key
            ), logical AS (
                SELECT summary.guid, summary.channel_key,
                    coalesce(current.descriptor, summary.descriptor) AS descriptor,
                    coalesce(current.downlink_hz, summary.downlink_hz) AS downlink_hz,
                    coalesce(current.uplink_hz, summary.uplink_hz) AS uplink_hz,
                    coalesce(current.tdma, summary.tdma) AS tdma,
                    coalesce(current.timeslots, summary.timeslots) AS timeslots,
                    current.callsign,
                    current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                    summary.observation_count, tags.tags, tags.control_observations,
                    tags.alternate_control_observations, tags.data_announcement_observations,
                    tags.voice_grant_observations, tags.data_grant_observations, active.current_tags
                FROM p25_site_channel_summary summary
                LEFT JOIN p25_site_channel current
                  ON current.guid = summary.guid AND current.channel_key = summary.channel_key
                LEFT JOIN tag_summary tags
                  ON tags.guid = summary.guid AND tags.channel_key = summary.channel_key
                LEFT JOIN current_tags active
                  ON active.guid = summary.guid AND active.channel_key = summary.channel_key
                JOIN p25_site_snapshot site ON site.guid = summary.guid
                WHERE summary.guid = ?
            )
            SELECT group_concat(DISTINCT channel_key) AS channel_key,
                group_concat(DISTINCT descriptor) AS descriptor,
                downlink_hz, max(uplink_hz) AS uplink_hz, max(tdma) AS tdma, max(timeslots) AS timeslots,
                max(callsign) AS callsign,
                max(confirmed_at_ms) AS confirmed_at_ms, min(first_seen_ms) AS first_seen_ms,
                max(last_seen_ms) AS last_seen_ms, sum(observation_count) AS observation_count,
                group_concat(DISTINCT tags) AS tags, group_concat(DISTINCT current_tags) AS current_tags,
                sum(coalesce(control_observations, 0)) AS control_observations,
                sum(coalesce(alternate_control_observations, 0)) AS alternate_control_observations,
                sum(coalesce(data_announcement_observations, 0)) AS data_announcement_observations,
                sum(coalesce(voice_grant_observations, 0)) AS voice_grant_observations,
                sum(coalesce(data_grant_observations, 0)) AS data_grant_observations,
                CASE WHEN max(max(coalesce(confirmed_at_ms, 0), last_seen_ms)) >= ?
                    THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
            FROM logical
            GROUP BY guid, coalesce(CAST(downlink_hz AS TEXT), channel_key)
            ORDER BY coalesce(downlink_hz, 9223372036854775807), channel_key
            """, guid, guid, guid, System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS));
        });
    }

    Map<String,Object> siteTalkgroups(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        ActivityRange range = activityRange(request);
        long throughMilliseconds = Math.floorDiv(System.currentTimeMillis(), HOUR_MILLISECONDS) *
            HOUR_MILLISECONDS;
        long bucketCount = Math.max(1,
            (range.milliseconds() + HOUR_MILLISECONDS - 1) / HOUR_MILLISECONDS);
        long fromMilliseconds = throughMilliseconds - (bucketCount - 1) * HOUR_MILLISECONDS;

        return read(connection -> {
            Map<String,Object> context = first(queryRows(connection, """
                SELECT context.id AS context_id, system.system_key, system.wacn, system.system_id
                FROM receiver_context context
                JOIN p25_system system ON system.system_key = context.system_key
                WHERE context.guid = ?
                """, guid), "Site not found");
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT talkgroup_id, SUM(call_count) AS call_count,
                    SUM(recorded_count) AS recorded_count, SUM(streamed_count) AS streamed_count,
                    SUM(encrypted_count) AS encrypted_count, MAX(bucket_start_ms) AS last_active_ms
                FROM p25_site_talkgroup_bucket
                WHERE context_id = ? AND bucket_start_ms >= ?
                GROUP BY talkgroup_id
                ORDER BY call_count DESC, talkgroup_id
                LIMIT ?
                """, context.get("context_id"), fromMilliseconds, request.limit());

            for(Map<String,Object> row: rows)
            {
                row.put("system_key", context.get("system_key"));
                row.put("wacn", context.get("wacn"));
                row.put("system_id", context.get("system_id"));
            }

            mAliasResolver.enrichTalkgroups(connection, rows);
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", range.label());
            response.put("from_ms", fromMilliseconds);
            response.put("to_ms", System.currentTimeMillis());
            response.put("bucket_ms", HOUR_MILLISECONDS);
            response.put("metric_start_ms", p25CallOutputMetricsStartedAt(connection));
            response.put("rows", rows);
            return response;
        });
    }

    Map<String,Object> siteQuality(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> Map.of("rows", queryRows(connection, """
            SELECT frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs, average_signal_dbfs,
                minimum_signal_dbfs, maximum_signal_dbfs, decode_health_pct, valid_frames, invalid_frames,
                corrected_bits, sync_loss_bits, dropped_bits, last_valid_decode_ms
            FROM p25_control_channel_quality
            WHERE guid = ?
            ORDER BY observed_at_ms DESC
            LIMIT ?
            """, guid, request.limit())));
    }

    Map<String,Object> siteBands(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> {
            long currentSince = System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS;
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("rows", queryRows(connection, """
                SELECT current.band, current.tdma, current.base_hz, current.bandwidth, current.spacing_hz,
                    current.transmit_offset_hz, current.timeslots, current.confirmed_at_ms,
                    summary.first_seen_ms, summary.last_seen_ms, summary.observation_count,
                    CASE WHEN max(current.confirmed_at_ms, coalesce(summary.last_seen_ms, 0)) >= ?
                        THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                FROM p25_site_frequency_band current
                LEFT JOIN p25_site_frequency_band_summary summary
                  ON summary.guid = current.guid AND summary.band = current.band
                WHERE current.guid = ? ORDER BY current.band
                """, currentSince, guid));
            response.put("foreign_rows", queryRows(connection, """
                SELECT summary.foreign_wacn, summary.foreign_system_id, summary.band,
                    coalesce(current.channel_type, summary.channel_type) AS channel_type,
                    coalesce(current.base_hz, summary.base_hz) AS base_hz,
                    coalesce(current.spacing_hz, summary.spacing_hz) AS spacing_hz,
                    coalesce(current.transmit_offset_hz, summary.transmit_offset_hz) AS transmit_offset_hz,
                    current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                    summary.observation_count,
                    CASE WHEN max(coalesce(current.confirmed_at_ms, 0), summary.last_seen_ms) >= ?
                        THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                FROM p25_foreign_system_band_summary summary
                LEFT JOIN p25_foreign_system_band current
                  ON current.guid = summary.guid
                 AND current.foreign_wacn = summary.foreign_wacn
                 AND current.foreign_system_id = summary.foreign_system_id
                 AND current.band = summary.band
                WHERE summary.guid = ?
                ORDER BY CASE WHEN current.band IS NULL THEN 1 ELSE 0 END,
                    summary.foreign_wacn, summary.foreign_system_id, summary.band
                """, currentSince, guid));
            return response;
        });
    }

    Map<String,Object> siteNeighbors(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> {
            long currentSince = System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS;

            if(isTrunkedSite(connection, guid))
            {
                return Map.of("rows", queryRows(connection, """
                    SELECT 'SITE' AS entry_type, variant_code, identity_domain_code,
                        NULLIF(network_id, -1) AS network_id, NULLIF(system_id, -1) AS system_id,
                        NULLIF(site_id, -1) AS site_id,
                        NULLIF(site_id, -1) AS site, NULLIF(channel_number, -1) AS channel_number,
                        NULLIF(frequency_hz, -1) AS frequency_hz,
                        NULLIF(frequency_hz, -1) AS downlink_hz, status_flags,
                        first_seen_ms, last_seen_ms, observation_count,
                        CASE WHEN last_seen_ms >= ? THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM trunked_site_neighbor_summary
                    WHERE guid = ?
                    ORDER BY identity_domain_code, network_id = -1, network_id, system_id = -1, system_id,
                        site_id = -1, site_id, channel_number = -1, channel_number
                    LIMIT ?
                    """, currentSince, guid, request.limit()));
            }

            List<Map<String,Object>> rows = new ArrayList<>(queryRows(connection, """
            SELECT 'SITE' AS entry_type, NULL AS wacn, summary.neighbor_key,
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
                CASE WHEN max(coalesce(current.confirmed_at_ms, 0), summary.last_seen_ms) >= ?
                    THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
            FROM p25_site_neighbor_summary summary
            LEFT JOIN p25_site_neighbor current
              ON current.guid = summary.guid AND current.neighbor_key = summary.neighbor_key
            WHERE summary.guid = ?
            ORDER BY CASE WHEN current.neighbor_key IS NULL THEN 1 ELSE 0 END,
                system_id, rfss, site, summary.neighbor_key
            """, currentSince, guid));
            rows.addAll(queryRows(connection, """
                SELECT 'ISSI' AS entry_type, summary.foreign_wacn AS wacn,
                    printf('%X:%03X', summary.foreign_wacn, summary.foreign_system_id) AS neighbor_key,
                    summary.foreign_system_id AS system_id, NULL AS rfss, NULL AS site, NULL AS lra,
                    NULL AS channel_descriptor, NULL AS downlink_hz, NULL AS uplink_hz,
                    'ISSI ADVERTISED' AS status, MAX(current.confirmed_at_ms) AS confirmed_at_ms,
                    MIN(summary.first_seen_ms) AS first_seen_ms, MAX(summary.last_seen_ms) AS last_seen_ms,
                    SUM(summary.observation_count) AS observation_count,
                    COUNT(*) AS band_count,
                    MAX(CASE WHEN summary.channel_type BETWEEN 0 AND 2 THEN 1 ELSE 0 END) AS has_fdma,
                    MAX(CASE WHEN summary.channel_type BETWEEN 3 AND 5 THEN 1 ELSE 0 END) AS has_tdma,
                    MAX(CASE WHEN summary.channel_type NOT BETWEEN 0 AND 5 THEN 1 ELSE 0 END) AS has_unknown,
                    CASE WHEN MAX(coalesce(current.confirmed_at_ms, 0)) >= ? OR MAX(summary.last_seen_ms) >= ?
                        THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                FROM p25_foreign_system_band_summary summary
                LEFT JOIN p25_foreign_system_band current
                  ON current.guid = summary.guid
                 AND current.foreign_wacn = summary.foreign_wacn
                 AND current.foreign_system_id = summary.foreign_system_id
                 AND current.band = summary.band
                WHERE summary.guid = ?
                GROUP BY summary.foreign_wacn, summary.foreign_system_id
                ORDER BY CASE WHEN MAX(current.confirmed_at_ms) IS NULL THEN 1 ELSE 0 END,
                    summary.foreign_wacn, summary.foreign_system_id
                """, currentSince, currentSince, guid));
            return Map.of("rows", rows);
        });
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
                    CASE WHEN max(current.confirmed_at_ms, coalesce(summary.last_seen_ms, 0)) >= ?
                        THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                FROM p25_site_patch_group current
                JOIN p25_site_snapshot site ON site.guid = current.guid
                LEFT JOIN p25_system system ON system.system_key = site.system_key
                LEFT JOIN p25_site_patch_group_summary summary
                  ON summary.guid = current.guid AND summary.patch_group = current.patch_group
                WHERE current.guid = ? ORDER BY current.patch_group
                """, System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS, guid);
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
            StringBuilder sql = new StringBuilder(ACTIVITY_SELECT_SQL);
            List<Object> parameters = new ArrayList<>();
            long beforeId = request.beforeId();
            Long beforeTimestamp = null;

            if(beforeId != Long.MAX_VALUE)
            {
                List<Map<String,Object>> cursor = queryRows(connection,
                    "SELECT observed_at_ms FROM p25_activity_event WHERE id = ?", beforeId);

                if(cursor.isEmpty())
                {
                    return cursorPage(List.of(), request.limit());
                }

                beforeTimestamp = number(cursor.getFirst().get("observed_at_ms"));
            }

            Integer wacn = request.optionalIdentifier("wacn");
            Integer systemId = request.optionalIdentifier("system_id");
            Integer talkgroup = request.optionalIdentifier("talkgroup_id");
            Integer radio = request.optionalIdentifier("radio_id");
            String guid = request.text("guid");
            String context = request.text("context");

            if("true".equalsIgnoreCase(request.text("hide_grants")))
            {
                sql.append(" AND action <> 'GRANT'");
            }

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

            if(beforeTimestamp != null)
            {
                sql.append(" AND (observed_at_ms < ? OR (observed_at_ms = ? AND id < ?))");
                parameters.add(beforeTimestamp);
                parameters.add(beforeTimestamp);
                parameters.add(beforeId);
            }

            sql.append(ACTIVITY_ORDER_SQL);
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
                    summary.first_seen_ms, summary.last_seen_ms, summary.call_count, summary.last_event_type_code
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

            sql.append(" ORDER BY ").append(order(request, CONVENTIONAL_SORT_COLUMNS, "frequency"))
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            return page(queryRows(connection, sql.toString(), parameters.toArray()), request);
        });
    }

    Map<String,Object> conventionalDetail(StatsRequest request)
    {
        String contextKey = request.requiredText("context");
        return read(connection -> {
            Map<String,Object> response = new LinkedHashMap<>();
            Map<String,Object> context = first(queryRows(connection, """
                SELECT id AS context_id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, nac, primary_frequency_hz, first_seen_ms, last_seen_ms
                FROM receiver_context WHERE context_key = ? AND kind_code <> 1
                """, contextKey), "Conventional context not found");
            boolean dmr = number(context.get("kind_code")) == 3 && number(context.get("protocol_code")) == 3;
            context.put("capabilities", conventionalCapabilities(dmr));
            response.put("context", context);
            response.put("summaries", queryRows(connection, """
                SELECT summary.* FROM conventional_activity_summary summary
                JOIN receiver_context context ON context.id = summary.context_id
                WHERE context.context_key = ? ORDER BY summary.frequency_hz, summary.timeslot
                """, contextKey));
            return response;
        });
    }

    /**
     * Bounded conventional DMR talkgroup summaries for exactly one receiver context.
     */
    Map<String,Object> conventionalTalkgroups(StatsRequest request)
    {
        String contextKey = request.requiredText("context");
        return read(connection -> {
            requireDmrConventionalContext(connection, contextKey);
            StringBuilder sql = new StringBuilder("""
                SELECT context.id AS context_id, context.context_key, context.alias_list_name,
                    summary.frequency_hz, summary.timeslot, summary.talkgroup_id,
                    summary.first_seen_ms, summary.last_seen_ms, summary.call_count,
                    summary.encrypted_count, summary.last_source_radio_id
                FROM dmr_conventional_talkgroup_summary summary
                JOIN receiver_context context ON context.id = summary.context_id
                WHERE context.context_key = ? AND context.kind_code = 3 AND context.protocol_code = 3
                """);
            List<Object> parameters = new ArrayList<>(List.of(contextKey));
            addDmrAliasSearch(sql, parameters, request.search(), "alias_talkgroup", "summary.talkgroup_id");
            sql.append(" ORDER BY ")
                .append(order(request, DMR_CONVENTIONAL_TALKGROUP_SORT_COLUMNS, "calls"))
                .append(", summary.last_seen_ms DESC, summary.frequency_hz ASC, summary.timeslot ASC, ")
                .append("summary.talkgroup_id ASC LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichDmrTalkgroups(connection, rows, "talkgroup_id", "alias_");
            mAliasResolver.enrichDmrRadios(connection, rows, "last_source_radio_id", "last_source_alias_");
            return page(rows, request);
        });
    }

    /**
     * Bounded conventional DMR radio summaries for exactly one receiver context.
     */
    Map<String,Object> conventionalRadios(StatsRequest request)
    {
        String contextKey = request.requiredText("context");
        return read(connection -> {
            requireDmrConventionalContext(connection, contextKey);
            StringBuilder sql = new StringBuilder("""
                SELECT context.id AS context_id, context.context_key, context.alias_list_name,
                    summary.frequency_hz, summary.timeslot, summary.radio_id,
                    summary.first_seen_ms, summary.last_seen_ms, summary.call_count,
                    summary.source_call_count, summary.target_call_count, summary.group_call_count,
                    summary.private_call_count, summary.encrypted_count, summary.last_talkgroup_id,
                    summary.last_peer_radio_id
                FROM dmr_conventional_radio_summary summary
                JOIN receiver_context context ON context.id = summary.context_id
                WHERE context.context_key = ? AND context.kind_code = 3 AND context.protocol_code = 3
                """);
            List<Object> parameters = new ArrayList<>(List.of(contextKey));
            addDmrAliasSearch(sql, parameters, request.search(), "alias_radio", "summary.radio_id");
            sql.append(" ORDER BY ")
                .append(order(request, DMR_CONVENTIONAL_RADIO_SORT_COLUMNS, "calls"))
                .append(", summary.last_seen_ms DESC, summary.frequency_hz ASC, summary.timeslot ASC, ")
                .append("summary.radio_id ASC LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichDmrRadios(connection, rows, "radio_id", "alias_");
            mAliasResolver.enrichDmrTalkgroups(connection, rows, "last_talkgroup_id", "last_talkgroup_alias_");
            mAliasResolver.enrichDmrRadios(connection, rows, "last_peer_radio_id", "last_peer_alias_");
            return page(rows, request);
        });
    }

    private static void requireDmrConventionalContext(Connection connection, String contextKey) throws SQLException
    {
        first(queryRows(connection, """
            SELECT id FROM receiver_context
            WHERE context_key = ? AND kind_code = 3 AND protocol_code = 3
            """, contextKey), "Conventional DMR context not found");
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

        sql.append(" ORDER BY ").append(order(request, SITE_SORT_COLUMNS, "last_seen"))
            .append(" LIMIT ? OFFSET ?");
        addPageParameters(parameters, request);
        return queryRows(connection, sql.toString(), parameters.toArray());
    }

    private static String siteSelect()
    {
        return """
            SELECT site.guid, site.system_key, site.protocol, site.channel_name, site.alias_list_name,
                site.decoder, system.wacn, system.system_id, site.nac, site.rfss, site.site,
                site.lra, site.mfid, site.broadcast_clock_ms, site.micro_slots, site.data_service,
                site.data_access, site.wuid_lease_minutes, site.registration_service, site.tdma,
                site.voice_service,
                site.primary_frequency_hz, site.current_control_hz, site.first_seen_ms, site.last_seen_ms,
                site.observation_count,
                coalesce(
                    (SELECT max(channel.callsign) FROM p25_site_channel channel
                     WHERE channel.guid = site.guid AND channel.downlink_hz = site.current_control_hz),
                    (SELECT channel.callsign FROM p25_site_channel channel
                     WHERE channel.guid = site.guid AND channel.callsign IS NOT NULL
                     ORDER BY channel.confirmed_at_ms DESC LIMIT 1)
                ) AS callsign,
                (SELECT COUNT(*) FROM p25_site_channel channel WHERE channel.guid = site.guid) AS channels,
                (SELECT COUNT(*) FROM p25_site_neighbor neighbor WHERE neighbor.guid = site.guid) AS neighbors,
                (SELECT COUNT(*) FROM p25_site_frequency_band band WHERE band.guid = site.guid) AS bands,
                (SELECT COUNT(*) FROM p25_site_patch_group patch WHERE patch.guid = site.guid) AS patches
            FROM p25_site_snapshot site
            LEFT JOIN p25_system system ON system.system_key = site.system_key
            """;
    }

    /**
     * Normalizes the identity fields needed by the protocol-agnostic control-channel quality views.  The quality
     * buckets remain in the deployed GUID-keyed table whose historical name starts with {@code p25_}; no schema
     * distinction is required because the receiver GUID is the shared identity.  During a retained protocol
     * transition, the newest site observation owns that GUID and P25 wins an exact timestamp tie.
     */
    private static String qualitySiteSelect()
    {
        String candidates = """
            SELECT site.guid, site.channel_name, site.nac, site.rfss, site.site, site.current_control_hz,
                site.last_seen_ms AS site_last_seen_ms, system.wacn, system.system_id,
                1 AS protocol_code, 'P25' AS protocol, 'p25' AS site_kind,
                NULL AS configured_system, NULL AS network_id, NULL AS site_id, NULL AS ran,
                NULL AS variant_code, NULL AS identity_domain_code
            FROM p25_site_snapshot site
            LEFT JOIN p25_system system ON system.system_key = site.system_key

            UNION ALL

            SELECT site.guid, site.channel_name, NULL AS nac, NULL AS rfss, NULL AS site,
                site.current_control_hz,
                site.last_seen_ms AS site_last_seen_ms, NULL AS wacn, site.system_id,
                site.protocol_code,
                CASE site.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                'trunked' AS site_kind, site.configured_system, site.network_id, site.site_id, site.ran,
                site.variant_code, site.identity_domain_code
            FROM trunked_site_snapshot site
            """;
        return """
            SELECT guid, channel_name, nac, rfss, site, current_control_hz, site_last_seen_ms, wacn,
                system_id, protocol_code, protocol, site_kind, configured_system, network_id, site_id, ran,
                variant_code, identity_domain_code
            FROM (
                SELECT candidate.*, row_number() OVER (
                    PARTITION BY candidate.guid
                    ORDER BY candidate.site_last_seen_ms DESC, candidate.protocol_code ASC
                ) AS identity_rank
                FROM (
                    %s
                ) candidate
            ) ranked
            WHERE identity_rank = 1
            """.formatted(candidates);
    }

    private static Map<String,Boolean> siteCapabilities(boolean p25)
    {
        Map<String,Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("info", true);
        capabilities.put("channels", true);
        capabilities.put("quality", true);
        capabilities.put("quality_live", true);
        capabilities.put("quality_history", true);
        capabilities.put("neighbors", true);
        capabilities.put("band_plan", p25);
        capabilities.put("patches", p25);
        capabilities.put("activity", p25);
        capabilities.put("talkgroups", p25);
        return Map.copyOf(capabilities);
    }

    private static Map<String,Boolean> conventionalCapabilities(boolean dmr)
    {
        Map<String,Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("info", true);
        capabilities.put("activity", !dmr);
        capabilities.put("talkgroups", dmr);
        capabilities.put("radios", dmr);
        return Map.copyOf(capabilities);
    }

    private static String trunkedSiteSelect()
    {
        return """
            SELECT site.guid, site.snapshot_hash, site.protocol_code,
                CASE site.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                'trunked' AS site_kind, site.variant_code, site.identity_domain_code, site.configured_system,
                site.channel_name, site.alias_list_name, site.decoder, site.network_id, site.system_id, site.site_id,
                site.ran,
                site.model_code, site.brand_code, site.mode_code, site.channel_type_code,
                site.color_code_ts1, site.color_code_ts2, site.current_repeater, site.service_flags,
                site.failure_code, site.primary_frequency_hz, site.current_control_hz,
                site.first_seen_ms, site.last_seen_ms, site.observation_count,
                (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                    WHERE channel.guid = site.guid) AS channels,
                (SELECT COUNT(*) FROM trunked_site_neighbor_summary neighbor
                    WHERE neighbor.guid = site.guid) AS neighbors,
                0 AS bands, 0 AS patches
            FROM trunked_site_snapshot site
            """;
    }

    private static boolean isTrunkedSite(Connection connection, String guid) throws SQLException
    {
        long protocolCode = scalarLong(connection, """
            SELECT protocol_code
            FROM (
                SELECT 1 AS protocol_code, last_seen_ms
                FROM p25_site_snapshot
                WHERE guid = ?

                UNION ALL

                SELECT protocol_code, last_seen_ms
                FROM trunked_site_snapshot
                WHERE guid = ?
            )
            ORDER BY last_seen_ms DESC, protocol_code ASC
            LIMIT 1
            """, guid, guid);
        return protocolCode == 3 || protocolCode == 4;
    }

    static String mfidDisplay(int value)
    {
        int normalized = value & 0xFF;
        Vendor vendor = Vendor.fromValue(normalized);
        String hex = String.format("0x%02X", normalized);

        if(vendor == Vendor.VUNK || vendor.name().matches("V\\d+"))
        {
            return hex;
        }

        String description = vendor.getDescription().trim().toLowerCase();
        String name = description.isEmpty() ? null : Character.toUpperCase(description.charAt(0)) +
            description.substring(1);
        return name != null ? name + " (" + hex + ")" : hex;
    }

    private List<Map<String,Object>> loggerStatus(Connection connection) throws SQLException
    {
        return queryRows(connection, "SELECT key, value, updated_at_ms FROM logger_status ORDER BY key");
    }

    private static List<Map<String,Object>> hourlyActivity(Connection connection) throws SQLException
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), HOUR_MILLISECONDS) * HOUR_MILLISECONDS;
        long firstHour = currentHour - (DASHBOARD_HOURS - 1L) * HOUR_MILLISECONDS;
        List<Map<String,Object>> stored = queryRows(connection, """
            SELECT bucket_start_ms, SUM(call_count) AS call_count, SUM(continue_count) AS continue_count,
                SUM(join_count) AS join_count,
                SUM(register_count) AS register_count, SUM(denial_count) AS denial_count,
                SUM(busy_count) AS busy_count, SUM(queued_count) AS queued_count,
                SUM(encrypted_count) AS encrypted_count
            FROM (
                SELECT bucket_start_ms, call_count, continue_count, join_count, register_count,
                    denial_count, busy_count, queued_count, encrypted_count
                FROM p25_site_activity_bucket WHERE bucket_start_ms >= ?
                UNION ALL
                SELECT bucket_start_ms, call_count, 0, 0, 0, 0, 0, 0, 0
                FROM conventional_activity_bucket WHERE bucket_start_ms >= ?
            )
            GROUP BY bucket_start_ms
            ORDER BY bucket_start_ms
            """, firstHour, firstHour);
        Map<Long,Map<String,Object>> totals = new LinkedHashMap<>();

        for(Map<String,Object> row: stored)
        {
            Object hour = row.get("bucket_start_ms");

            if(hour instanceof Number hourNumber)
            {
                totals.put(hourNumber.longValue(), row);
            }
        }

        List<Map<String,Object>> result = new ArrayList<>(DASHBOARD_HOURS);

        for(long hour = firstHour; hour <= currentHour; hour += HOUR_MILLISECONDS)
        {
            Map<String,Object> values = totals.get(hour);
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("hour_ms", hour);

            for(String field: List.of("call_count", "continue_count", "join_count",
                "register_count", "denial_count", "busy_count", "queued_count", "encrypted_count"))
            {
                row.put(field, values != null && values.get(field) instanceof Number number ? number.longValue() : 0L);
            }

            result.add(row);
        }

        return result;
    }

    /**
     * Ranks sites using the existing compact hourly buckets. The query is bounded to 24 hours and can use the
     * bucket-time index; no detailed event history is involved.
     */
    private static Map<String,Object> siteActivity24Hours(Connection connection) throws SQLException
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), HOUR_MILLISECONDS) * HOUR_MILLISECONDS;
        long firstHour = currentHour - (DASHBOARD_HOURS - 1L) * HOUR_MILLISECONDS;
        long nextHour = currentHour + HOUR_MILLISECONDS;
        List<Map<String,Object>> rows = queryRows(connection, DASHBOARD_SITE_ACTIVITY_SQL, firstHour, nextHour);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("from_ms", firstHour);
        result.put("to_ms", System.currentTimeMillis());
        result.put("rows", rows);
        return result;
    }

    private static Map<String,Object> p25CallActivity(Connection connection,
                                                       List<Map<String,Object>> hourlyActivity) throws SQLException
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), HOUR_MILLISECONDS) * HOUR_MILLISECONDS;
        long firstHour = currentHour - (DASHBOARD_HOURS - 1L) * HOUR_MILLISECONDS;
        List<Map<String,Object>> stored = queryRows(connection, """
            SELECT bucket_start_ms AS time_ms, SUM(call_count) AS call_count,
                SUM(recorded_count) AS recorded_count, SUM(streamed_count) AS streamed_count
            FROM p25_site_activity_bucket
            WHERE bucket_start_ms >= ?
            GROUP BY bucket_start_ms
            ORDER BY bucket_start_ms
            """, firstHour);
        Map<Long,Map<String,Object>> storedByTime = new LinkedHashMap<>();

        for(Map<String,Object> row: stored)
        {
            if(row.get("time_ms") instanceof Number timestamp)
            {
                storedByTime.put(timestamp.longValue(), row);
            }
        }

        Map<String,Long> totals = new LinkedHashMap<>();

        for(String field: CALL_ACTIVITY_FIELDS)
        {
            totals.put(field, 0L);
        }
        totals.put("non_p25_call_count", 0L);
        Map<Long,Long> allCallsByTime = new LinkedHashMap<>();

        for(Map<String,Object> hour: hourlyActivity)
        {
            if(hour.get("hour_ms") instanceof Number timestamp)
            {
                allCallsByTime.put(timestamp.longValue(), number(hour.get("call_count")));
            }
        }

        List<Map<String,Object>> series = new ArrayList<>(DASHBOARD_HOURS);

        for(long timestamp = firstHour; timestamp <= currentHour; timestamp += HOUR_MILLISECONDS)
        {
            Map<String,Object> storedRow = storedByTime.get(timestamp);
            Map<String,Object> point = new LinkedHashMap<>();
            point.put("time_ms", timestamp);

            for(String field: CALL_ACTIVITY_FIELDS)
            {
                long value = storedRow != null ? number(storedRow.get(field)) : 0L;
                point.put(field, value);
                totals.compute(field, (key, total) -> total + value);
            }

            long nonP25Calls = Math.max(0L,
                allCallsByTime.getOrDefault(timestamp, 0L) - number(point.get("call_count")));
            point.put("non_p25_call_count", nonP25Calls);
            totals.compute("non_p25_call_count", (key, total) -> total + nonP25Calls);

            series.add(point);
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("range", "24h");
        result.put("from_ms", firstHour);
        result.put("to_ms", System.currentTimeMillis());
        result.put("bucket_ms", HOUR_MILLISECONDS);
        result.put("metric_start_ms", p25CallOutputMetricsStartedAt(connection));
        result.put("totals", totals);
        result.put("series", series);
        return result;
    }

    private static long p25CallOutputMetricsStartedAt(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COALESCE((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?), 0)
            """, P25ActivityLogSchema.CALL_OUTPUT_METRICS_STARTED_AT_KEY);
    }

    private static List<Map<String,Object>> systemActionCounts(Connection connection, int wacn, int systemId)
        throws SQLException
    {
        List<Map<String,Object>> totals = queryRows(connection, """
            SELECT SUM(bucket.call_count) AS call_count, SUM(bucket.continue_count) AS continue_count,
                SUM(bucket.join_count) AS join_count,
                SUM(bucket.register_count) AS register_count, SUM(bucket.logout_count) AS logout_count,
                SUM(bucket.denial_count) AS denial_count, SUM(bucket.busy_count) AS busy_count,
                SUM(bucket.queued_count) AS queued_count, SUM(bucket.emergency_count) AS emergency_count,
                SUM(bucket.encrypted_count) AS encrypted_count
            FROM p25_site_activity_bucket bucket
            JOIN receiver_context context ON context.id = bucket.context_id
            JOIN p25_system system ON system.system_key = context.system_key
            WHERE system.wacn = ? AND system.system_id = ?
            """, wacn, systemId);

        if(totals.isEmpty())
        {
            return List.of();
        }

        Map<String,Object> row = totals.getFirst();
        List<Map<String,Object>> result = new ArrayList<>();

        for(String action: List.of("call", "continue", "join", "register", "logout", "denial", "busy",
            "queued", "emergency", "encrypted"))
        {
            long count = number(row.get(action + "_count"));

            if(count > 0)
            {
                result.add(Map.of("action", action.toUpperCase(), "count", count));
            }
        }

        result.sort((left, right) -> Long.compare(number(right.get("count")), number(left.get("count"))));
        return result;
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0L;
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

    private ActivityRange activityRange(StatsRequest request)
    {
        String label = request.text("range");
        label = label != null ? label.toLowerCase() : "24h";
        long requestedMilliseconds = switch(label)
        {
            case "1h" -> HOUR_MILLISECONDS;
            case "6h" -> 6L * HOUR_MILLISECONDS;
            case "24h" -> DAY_MILLISECONDS;
            case "7d" -> 7L * DAY_MILLISECONDS;
            case "30d" -> 30L * DAY_MILLISECONDS;
            default -> throw new StatsApiException(400, "range must be one of 1h, 6h, 24h, 7d, or 30d");
        };
        long retentionMilliseconds = Math.max(1,
            mUserPreferences.getApplicationPreference().getStatsLoggingRetentionDays()) * DAY_MILLISECONDS;
        return new ActivityRange(label, Math.min(requestedMilliseconds, retentionMilliseconds));
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

    private record ActivityRange(String label, long milliseconds)
    {
    }

    /**
     * Produces an exact-alias-list DMR alias expression for conventional identity sorting.
     */
    private static String dmrAliasSortExpression(String identifierTable, String identifierColumn,
                                                 String aliasColumn)
    {
        if(!"alias_talkgroup".equals(identifierTable) && !"alias_radio".equals(identifierTable) ||
            !identifierColumn.matches(
                "summary\\.(?:talkgroup_id|radio_id|last_talkgroup_id|last_peer_radio_id)") ||
            !"name".equals(aliasColumn) && !"group_name".equals(aliasColumn))
        {
            throw new IllegalArgumentException("Unsupported conventional DMR alias sort expression");
        }

        return """
            (SELECT lower(alias.%s)
             FROM %s identifier
             JOIN alias ON alias.id = identifier.alias_id
             WHERE identifier.protocol = 'DMR'
               AND identifier.alias_list_name = context.alias_list_name
               AND ((identifier.ranged <> 0 AND %s BETWEEN identifier.min_value AND identifier.max_value)
                 OR (identifier.ranged = 0 AND identifier.value = %s))
             ORDER BY CASE WHEN identifier.ranged = 0 THEN 1 ELSE 0 END DESC,
                 alias.id
             LIMIT 1)
            """.formatted(aliasColumn, identifierTable, identifierColumn, identifierColumn).strip();
    }

    /**
     * Produces a correlated, allowlisted alias expression that follows the same system alias-list and rule
     * specificity rules as {@link StatsAliasResolver}.  All arguments are class-owned constants; validating them
     * here keeps future sort additions from accidentally turning an ORDER BY expression into SQL input.
     */
    private static String aliasSortExpression(String identifierTable, String identifierColumn, String aliasColumn)
    {
        if(!"alias_talkgroup".equals(identifierTable) && !"alias_radio".equals(identifierTable) ||
            !identifierColumn.matches("(?:summary|affiliation|relationship)\\.(?:talkgroup_id|last_talkgroup_id|radio_id)") ||
            !"name".equals(aliasColumn) && !"group_name".equals(aliasColumn))
        {
            throw new IllegalArgumentException("Unsupported alias sort expression");
        }

        return """
            (SELECT lower(alias.%s)
             FROM %s identifier
             JOIN alias ON alias.id = identifier.alias_id
             WHERE identifier.protocol IN ('APCO25', 'APCO25_PHASE2')
               AND ((identifier.ranged <> 0 AND %s BETWEEN identifier.min_value AND identifier.max_value)
                 OR (identifier.ranged = 0 AND identifier.value = %s))
               AND (identifier.fully_qualified = 0 OR
                 (identifier.wacn = system.wacn AND identifier.system_id = system.system_id))
               AND (EXISTS (SELECT 1 FROM p25_site_snapshot assigned
                     WHERE assigned.system_key = system.system_key
                       AND assigned.alias_list_name = identifier.alias_list_name
                       AND trim(assigned.alias_list_name) <> '')
                 OR (identifier.fully_qualified <> 0 AND NOT EXISTS
                     (SELECT 1 FROM p25_site_snapshot assigned
                      WHERE assigned.system_key = system.system_key
                        AND assigned.alias_list_name IS NOT NULL
                        AND trim(assigned.alias_list_name) <> '')))
             ORDER BY CASE
                 WHEN identifier.fully_qualified <> 0 AND identifier.ranged = 0 THEN 3
                 WHEN identifier.ranged = 0 THEN 2
                 WHEN identifier.fully_qualified <> 0 THEN 1
                 ELSE 0 END DESC,
                 alias.id
             LIMIT 1)
            """.formatted(aliasColumn, identifierTable, identifierColumn, identifierColumn).strip();
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

    private static void addDmrAliasSearch(StringBuilder sql, List<Object> parameters, String search,
                                          String identifierTable, String identifierColumn)
    {
        if(search == null)
        {
            return;
        }

        if(!"alias_talkgroup".equals(identifierTable) && !"alias_radio".equals(identifierTable) ||
            !identifierColumn.matches("summary\\.(?:talkgroup_id|radio_id)"))
        {
            throw new IllegalArgumentException("Unsupported conventional DMR alias search");
        }

        sql.append("""
             AND (CAST(%s AS TEXT) LIKE ?
               OR CAST(summary.frequency_hz AS TEXT) LIKE ?
               OR EXISTS (
                   SELECT 1 FROM %s identifier
                   JOIN alias ON alias.id = identifier.alias_id
                   WHERE identifier.protocol = 'DMR'
                     AND identifier.alias_list_name = context.alias_list_name
                     AND ((identifier.ranged <> 0 AND %s BETWEEN identifier.min_value AND identifier.max_value)
                       OR (identifier.ranged = 0 AND identifier.value = %s))
                     AND (lower(coalesce(alias.name, '')) LIKE ?
                       OR lower(coalesce(alias.group_name, '')) LIKE ?)))
            """.formatted(identifierColumn, identifierTable, identifierColumn, identifierColumn));
        String like = like(search);
        parameters.add(like);
        parameters.add(like);
        parameters.add(like);
        parameters.add(like);
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

    private static long scalarLong(Connection connection, String sql, Object... parameters) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            for(int x = 0; x < parameters.length; x++)
            {
                statement.setObject(x + 1, parameters[x]);
            }

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
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
