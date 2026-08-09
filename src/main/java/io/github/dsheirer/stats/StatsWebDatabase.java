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
import io.github.dsheirer.preference.encryption.VoiceEncryptionDisplay;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
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
import java.util.Set;
import java.util.function.Consumer;
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
    private static final int DASHBOARD_IDENTITY_LIMIT = 20;
    private static final int IDENTITY_ROLE_DESTINATION = P25ActivityLogSchema.IDENTITY_ROLE_DESTINATION;
    private static final int IDENTITY_ROLE_SOURCE = P25ActivityLogSchema.IDENTITY_ROLE_SOURCE;
    private static final int IDENTITY_KIND_CHANNEL_OR_UNKNOWN =
        P25ActivityLogSchema.IDENTITY_KIND_CHANNEL_OR_UNKNOWN;
    private static final int IDENTITY_KIND_TALKGROUP = P25ActivityLogSchema.IDENTITY_KIND_TALKGROUP;
    private static final int IDENTITY_KIND_RADIO = P25ActivityLogSchema.IDENTITY_KIND_RADIO;
    private static final int IDENTITY_KIND_PATCH_GROUP = P25ActivityLogSchema.IDENTITY_KIND_PATCH_GROUP;
    private static final String FIRST_CONFIGURATION_CHANNEL_CTE = """
        first_configuration_channel AS MATERIALIZED (
            SELECT radres_guid, system_name AS configured_system,
                nullif(trim(site_name), '') AS configured_site,
                nullif(trim(name), '') AS configured_name
            FROM (
                SELECT radres_guid, system_name, site_name, name,
                    row_number() OVER (
                        PARTITION BY radres_guid ORDER BY sort_order, id
                    ) AS configuration_rank
                FROM configuration_channel
                WHERE radres_guid IS NOT NULL
            ) ranked_configuration
            WHERE configuration_rank = 1
        )
        """;
    static final String DASHBOARD_CALL_ACTIVITY_SQL = """
        SELECT bucket.bucket_start_ms AS time_ms,
            CASE
                WHEN context.protocol_code IN (1, 2) THEN 1
                WHEN context.kind_code = 10 THEN 10
                ELSE coalesce(context.protocol_code, 0)
            END AS protocol_code,
            'TRUNKED' AS channel_kind,
            SUM(bucket.call_count) AS call_count,
            SUM(bucket.recorded_count) AS recorded_count,
            SUM(bucket.streamed_count) AS streamed_count,
            SUM(bucket.encrypted_count) AS encrypted_count
        FROM p25_site_activity_bucket AS bucket INDEXED BY idx_p25_site_activity_bucket_time
        JOIN receiver_context context ON context.id = bucket.context_id
        WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
        GROUP BY bucket.bucket_start_ms,
            CASE
                WHEN context.protocol_code IN (1, 2) THEN 1
                WHEN context.kind_code = 10 THEN 10
                ELSE coalesce(context.protocol_code, 0)
            END

        UNION ALL

        SELECT bucket.bucket_start_ms AS time_ms,
            CASE
                WHEN context.kind_code = 10 THEN 10
                WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                ELSE coalesce(context.protocol_code, 0)
            END AS protocol_code,
            'CONVENTIONAL' AS channel_kind,
            SUM(bucket.call_count) AS call_count,
            SUM(bucket.recorded_count) AS recorded_count,
            SUM(bucket.streamed_count) AS streamed_count,
            SUM(bucket.encrypted_count) AS encrypted_count
        FROM receiver_context context
        JOIN conventional_activity_bucket AS bucket INDEXED BY idx_conventional_bucket_dashboard_time
            ON bucket.context_id = context.id
        WHERE context.kind_code <> 1
          AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
        GROUP BY bucket.bucket_start_ms,
            CASE
                WHEN context.kind_code = 10 THEN 10
                WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                ELSE coalesce(context.protocol_code, 0)
            END
        ORDER BY time_ms, protocol_code, channel_kind
        """;
    static final String DASHBOARD_SOURCE_ACTIVITY_SQL = "WITH " + FIRST_CONFIGURATION_CHANNEL_CTE + """
        , source_activity AS (
            SELECT context.id AS context_id, context.context_key, context.guid,
                CASE
                    WHEN context.protocol_code IN (1, 2) THEN 1
                    WHEN context.kind_code = 10 THEN 10
                    ELSE coalesce(context.protocol_code, 0)
                END AS protocol_code,
                'TRUNKED' AS channel_kind,
                coalesce(context.channel_name, trunked.channel_name) AS channel_name,
                context.decoder, context.primary_frequency_hz, context.current_control_hz,
                context.system_key, system.wacn, system.system_id, context.rfss, context.site,
                trunked.configured_system, trunked.network_id, trunked.site_id, trunked.ran,
                CASE WHEN EXISTS (
                    SELECT 1 FROM p25_site_snapshot detail WHERE detail.guid = context.guid
                ) OR EXISTS (
                    SELECT 1 FROM trunked_site_snapshot detail WHERE detail.guid = context.guid
                ) THEN 1 ELSE 0 END AS detail_available,
                SUM(bucket.call_count) AS call_count,
                SUM(bucket.recorded_count) AS recorded_count,
                SUM(bucket.streamed_count) AS streamed_count,
                SUM(bucket.encrypted_count) AS encrypted_count
            FROM p25_site_activity_bucket AS bucket INDEXED BY idx_p25_site_activity_bucket_time
            JOIN receiver_context context ON context.id = bucket.context_id
            LEFT JOIN p25_system system ON system.system_key = context.system_key
            LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
            WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
            GROUP BY context.id, context.context_key, context.guid,
                CASE
                    WHEN context.protocol_code IN (1, 2) THEN 1
                    WHEN context.kind_code = 10 THEN 10
                    ELSE coalesce(context.protocol_code, 0)
                END,
                coalesce(context.channel_name, trunked.channel_name), context.decoder,
                context.primary_frequency_hz, context.current_control_hz, context.system_key,
                system.wacn, system.system_id, context.rfss, context.site,
                trunked.configured_system, trunked.network_id, trunked.site_id, trunked.ran

            UNION ALL

            SELECT context.id AS context_id, context.context_key, context.guid,
                CASE
                    WHEN context.kind_code = 10 THEN 10
                    WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                    ELSE coalesce(context.protocol_code, 0)
                END AS protocol_code,
                'CONVENTIONAL' AS channel_kind, context.channel_name, context.decoder,
                context.primary_frequency_hz, context.current_control_hz, context.system_key,
                system.wacn, system.system_id, context.rfss, context.site,
                NULL AS configured_system, NULL AS network_id, NULL AS site_id, NULL AS ran,
                1 AS detail_available,
                SUM(bucket.call_count) AS call_count,
                SUM(bucket.recorded_count) AS recorded_count,
                SUM(bucket.streamed_count) AS streamed_count,
                SUM(bucket.encrypted_count) AS encrypted_count
            FROM receiver_context context
            JOIN conventional_activity_bucket AS bucket INDEXED BY idx_conventional_bucket_dashboard_time
                ON bucket.context_id = context.id
            LEFT JOIN p25_system system ON system.system_key = context.system_key
            WHERE context.kind_code <> 1
              AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
            GROUP BY context.id, context.context_key, context.guid,
                CASE
                    WHEN context.kind_code = 10 THEN 10
                    WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                    ELSE coalesce(context.protocol_code, 0)
                END,
                context.channel_name, context.decoder, context.primary_frequency_hz,
                context.current_control_hz, context.system_key, system.wacn, system.system_id,
                context.rfss, context.site
        )
        SELECT source_activity.*,
            CASE WHEN channel_kind = 'TRUNKED' THEN config.configured_site END AS configured_site,
            CASE WHEN channel_kind = 'TRUNKED' THEN config.configured_name END AS configured_name,
            CASE protocol_code
                WHEN 1 THEN 'P25'
                WHEN 3 THEN 'DMR'
                WHEN 4 THEN 'NXDN'
                WHEN 10 THEN 'NBFM'
                ELSE 'Unknown'
            END AS protocol,
            SUM(call_count) OVER () AS total_call_count
        FROM source_activity
        LEFT JOIN first_configuration_channel config ON config.radres_guid = source_activity.guid
        WHERE call_count > 0
        ORDER BY call_count DESC, protocol_code, channel_kind, lower(coalesce(channel_name, context_key))
        """;
    static final String DASHBOARD_IDENTITY_ACTIVITY_SQL = "WITH " + FIRST_CONFIGURATION_CHANNEL_CTE + """
        SELECT bucket.context_id, context.context_key, context.guid,
            CASE
                WHEN context.protocol_code IN (1, 2) THEN 1
                WHEN context.kind_code = 10 THEN 10
                ELSE coalesce(context.protocol_code, 0)
            END AS protocol_code,
            CASE
                WHEN context.protocol_code IN (1, 2) THEN 'P25'
                WHEN context.kind_code = 10 THEN 'NBFM'
                WHEN context.protocol_code = 3 THEN 'DMR'
                WHEN context.protocol_code = 4 THEN 'NXDN'
                ELSE 'Unknown'
            END AS protocol,
            CASE WHEN context.kind_code = 1 THEN 'TRUNKED' ELSE 'CONVENTIONAL' END AS channel_kind,
            coalesce(context.channel_name, trunked.channel_name) AS channel_name,
            config.configured_site, config.configured_name,
            coalesce(context.alias_list_name, trunked.alias_list_name) AS alias_list_name,
            context.decoder, context.primary_frequency_hz,
            context.current_control_hz, context.system_key, system.wacn, system.system_id,
            context.rfss, context.site, trunked.configured_system, trunked.network_id,
            trunked.site_id, trunked.ran, trunked.variant_code,
            coalesce(scope.identity_domain_code, trunked.identity_domain_code, 0) AS identity_domain_code,
            scope.scope_token,
            bucket.identity_kind_code, bucket.identity_id,
            CASE bucket.identity_kind_code
                WHEN 1 THEN 'Talkgroup'
                WHEN 2 THEN 'Radio'
                WHEN 3 THEN 'Patch Group'
                ELSE 'Channel / Unknown'
            END AS identity_kind,
            CASE WHEN context.kind_code <> 1 OR EXISTS (
                SELECT 1 FROM p25_site_snapshot detail WHERE detail.guid = context.guid
            ) OR EXISTS (
                SELECT 1 FROM trunked_site_snapshot detail WHERE detail.guid = context.guid
            ) THEN 1 ELSE 0 END AS receiver_detail_available,
            SUM(bucket.call_count) AS call_count,
            SUM(bucket.encrypted_count) AS encrypted_count,
            SUM(bucket.recorded_count) AS recorded_count,
            SUM(bucket.streamed_count) AS streamed_count,
            MAX(bucket.bucket_start_ms) AS last_active_ms,
            MAX(radio.last_talker_alias) AS last_talker_alias,
            MAX(radio.last_talker_alias_seen_ms) AS last_talker_alias_seen_ms
        FROM call_identity_bucket AS bucket INDEXED BY idx_call_identity_bucket_dashboard_time
        JOIN receiver_context context ON context.id = bucket.context_id
        LEFT JOIN trunked_identity_scope_context ownership ON ownership.context_id = context.id
        LEFT JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
        LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
        LEFT JOIN trunked_identity_summary radio
          ON bucket.identity_role_code = 2
         AND bucket.identity_kind_code = 2
         AND radio.scope_id = scope.scope_id
         AND radio.identity_kind_code = 2
         AND radio.identity_id = bucket.identity_id
        LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
        LEFT JOIN first_configuration_channel config ON config.radres_guid = context.guid
        WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
          AND bucket.identity_role_code = ?
        GROUP BY bucket.context_id, scope.scope_token, bucket.identity_kind_code, bucket.identity_id,
            config.configured_site, config.configured_name
        ORDER BY call_count DESC, last_active_ms DESC, protocol_code, channel_kind,
            bucket.identity_kind_code, bucket.identity_id
        LIMIT ?
        """;
    static final String ACTIVITY_SELECT_SQL = """
        SELECT activity.id, activity.context_id, activity.context_key, activity.guid,
            activity.observed_at_ms, activity.channel_kind,
            activity.channel_kind_code, activity.protocol, activity.action, activity.event_type,
            activity.source_radio_id, activity.target_id, activity.target_kind_code, activity.target_kind,
            activity.frequency_hz, activity.lcn, activity.timeslot, activity.encrypted,
            activity.encryption_algorithm_id, activity.encryption_key_id, activity.resolved_channel_name,
            activity.resolved_alias_list_name,
            coalesce(activity.resolved_alias_list_name, trunked.alias_list_name) AS alias_list_name,
            scope.scope_token, scope.identity_domain_code,
            activity.resolved_system_key AS system_key, activity.resolved_wacn AS wacn,
            activity.resolved_system_id AS system_id, activity.resolved_nac, activity.resolved_rfss,
            activity.resolved_site
        FROM p25_activity_event_resolved activity
        LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = activity.guid
        LEFT JOIN trunked_identity_scope_context ownership ON ownership.context_id = activity.context_id
        LEFT JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
        WHERE 1 = 1
        """;
    static final String ACTIVITY_ORDER_SQL =
        " ORDER BY activity.observed_at_ms DESC, activity.id DESC LIMIT ?";
    private static final int DIRECTORY_SITE_LIMIT_PER_SYSTEM = 500;
    private static final List<String> CALL_ACTIVITY_FIELDS = List.of(
        "call_count", "recorded_count", "streamed_count", "encrypted_count"
    );
    private static final List<CallActivityGroup> CALL_ACTIVITY_GROUPS = List.of(
        new CallActivityGroup(1, "P25", "TRUNKED", true),
        new CallActivityGroup(1, "P25", "CONVENTIONAL", true),
        new CallActivityGroup(3, "DMR", "TRUNKED", true),
        new CallActivityGroup(3, "DMR", "CONVENTIONAL", true),
        new CallActivityGroup(4, "NXDN", "TRUNKED", true),
        new CallActivityGroup(4, "NXDN", "CONVENTIONAL", true),
        new CallActivityGroup(10, "NBFM", "CONVENTIONAL", true)
    );
    private static final List<String> TALKGROUP_ACTIVITY_FIELDS = List.of(
        "acknowledge_count", "active_count", "busy_count", "call_count", "check_count", "check_ack_count",
        "continue_count", "data_count", "denial_count", "emergency_count", "gps_count",
        "join_count", "logout_count", "page_count", "patch_count", "patch_cancel_count", "patch_create_count",
        "queued_count", "register_count", "request_count", "status_count", "unknown_count", "encrypted_count",
        "recorded_count", "streamed_count"
    );
    private static final List<String> TALKGROUP_SIGNALING_FIELDS = List.of(
        "grant_count", "join_count", "register_count", "active_count", "continue_count", "denial_count",
        "emergency_count", "request_count", "busy_count", "queued_count", "acknowledge_count",
        "check_count", "check_ack_count", "page_count", "status_count", "gps_count", "logout_count",
        "patch_count", "patch_create_count", "patch_cancel_count", "data_count", "unknown_count"
    );
    private static final List<String> IDENTITY_EVIDENCE_FIELDS = TALKGROUP_SIGNALING_FIELDS.stream()
        .filter(field -> !"continue_count".equals(field) && !"unknown_count".equals(field))
        .toList();
    private static final String TALKGROUP_SIGNALING_COUNT_SQL = IDENTITY_EVIDENCE_FIELDS.stream()
        .map(field -> "summary." + field)
        .collect(java.util.stream.Collectors.joining(" + "));
    private static final String OTHER_TALKGROUP_SIGNALING_COUNT_SQL =
        "summary.acknowledge_count + summary.active_count + summary.busy_count + " +
            "summary.check_count + summary.check_ack_count + summary.continue_count + " +
            "summary.gps_count + summary.page_count + summary.patch_count + " +
            "summary.patch_cancel_count + summary.patch_create_count + summary.queued_count + " +
            "summary.request_count + summary.status_count + summary.unknown_count";
    private static final Map<String,String> SYSTEM_SORT_COLUMNS = Map.ofEntries(
        Map.entry("wacn", "wacn"),
        Map.entry("system_id", "system_id"),
        Map.entry("site_names", "lower(site_names)"),
        Map.entry("sites", "sites"),
        Map.entry("talkgroups", "talkgroups"),
        Map.entry("radios", "radios"),
        Map.entry("affiliations", "affiliations"),
        Map.entry("first_seen", "first_seen_ms"),
        Map.entry("last_seen", "last_seen_ms")
    );
    private static final Map<String,String> SCOPED_SITE_SORT_COLUMNS = Map.ofEntries(
        Map.entry("system", "scope_token"),
        Map.entry("rfss", "rfss"),
        Map.entry("site", "coalesce(site, site_id)"),
        Map.entry("name", "lower(coalesce(configured_name, configured_site, channel_name))"),
        Map.entry("protocol", "protocol_code"),
        Map.entry("decoder", "lower(decoder)"),
        Map.entry("control", "current_control_hz"),
        Map.entry("control_frequency", "current_control_hz"),
        Map.entry("channels", "channels"),
        Map.entry("neighbors", "neighbors"),
        Map.entry("bands", "bands"),
        Map.entry("observations", "observation_count"),
        Map.entry("first_seen", "first_seen_ms"),
        Map.entry("last_seen", "last_seen_ms")
    );
    private static final Map<String,String> TALKGROUP_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.identity_id"),
        Map.entry("talkgroup", "summary.identity_id"),
        Map.entry("alias", scopeAliasSortExpression("alias_talkgroup", "summary.identity_id", "name")),
        Map.entry("name", scopeAliasSortExpression("alias_talkgroup", "summary.identity_id", "name")),
        Map.entry("group", scopeAliasSortExpression("alias_talkgroup", "summary.identity_id", "group_name")),
        Map.entry("calls", "summary.call_count"),
        Map.entry("recorded", "summary.recorded_count"),
        Map.entry("streamed", "summary.streamed_count"),
        Map.entry("grants", "summary.grant_count"),
        Map.entry("affiliations", "summary.join_count"),
        Map.entry("signaling", "signaling_count"),
        Map.entry("evidence", "signaling_count"),
        Map.entry("encrypted", "summary.encrypted_count"),
        Map.entry("last_source", "CASE WHEN summary.last_counterpart_kind_code = 2 " +
            "THEN summary.last_counterpart_id END"),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> RADIO_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.identity_id"),
        Map.entry("radio", "summary.identity_id"),
        Map.entry("alias", scopeAliasSortExpression("alias_radio", "summary.identity_id", "name")),
        Map.entry("name", scopeAliasSortExpression("alias_radio", "summary.identity_id", "name")),
        Map.entry("talker_alias", "lower(summary.last_talker_alias)"),
        Map.entry("talker_alias_seen", "summary.last_talker_alias_seen_ms"),
        Map.entry("last_talkgroup", "CASE WHEN summary.last_counterpart_kind_code IN (1, 3) " +
            "THEN summary.last_counterpart_id END"),
        Map.entry("last_talkgroup_name", "CASE WHEN summary.last_counterpart_kind_code IN (1, 3) THEN " +
            scopeAliasSortExpression("alias_talkgroup", "summary.last_counterpart_id", "name") + " END"),
        Map.entry("calls", "summary.call_count"),
        Map.entry("grants", "summary.grant_count"),
        Map.entry("encrypted", "summary.encrypted_count"),
        Map.entry("affiliated_talkgroup", scopeAliasSortExpression("alias_talkgroup",
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
        Map.entry("radio_alias", scopeAliasSortExpression("alias_radio", "relationship.radio_id", "name")),
        Map.entry("talker_alias", "lower(radio.last_talker_alias)"),
        Map.entry("talkgroup", "relationship.talkgroup_id"),
        Map.entry("talkgroup_alias",
            scopeAliasSortExpression("alias_talkgroup", "relationship.talkgroup_id", "name")),
        Map.entry("calls", "relationship.call_count"),
        Map.entry("grants", "relationship.grant_count"),
        Map.entry("encrypted", "relationship.encrypted_count"),
        Map.entry("affiliated", "EXISTS (SELECT 1 FROM p25_radio_affiliation current_affiliation " +
            "WHERE current_affiliation.system_key = scope.p25_system_key " +
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
    private static final Map<String,String> OBSERVED_TALKGROUP_SORT_COLUMNS = Map.ofEntries(
        Map.entry("system", "lower(coalesce(system_name, scope_key))"),
        Map.entry("protocol", "protocol_code"),
        Map.entry("topology", "topology"),
        Map.entry("id", "talkgroup_id"),
        Map.entry("talkgroup", "talkgroup_id"),
        Map.entry("kind", "target_kind_code"),
        Map.entry("calls", "call_count"),
        Map.entry("recorded", "recorded_count"),
        Map.entry("streamed", "streamed_count"),
        Map.entry("encrypted", "encrypted_count"),
        Map.entry("grants", "grant_count"),
        Map.entry("joins", "join_count"),
        Map.entry("emergencies", "emergency_count"),
        Map.entry("registrations", "register_count"),
        Map.entry("first_seen", "first_seen_ms"),
        Map.entry("last_seen", "last_seen_ms")
    );

    private final UserPreferences mUserPreferences;
    private final Path mDatabasePath;
    private final StatsAliasResolver mAliasResolver = new StatsAliasResolver();
    private final StatsAliasCatalog mAliasCatalog = new StatsAliasCatalog(mAliasResolver);

    StatsWebDatabase(UserPreferences userPreferences)
    {
        this(userPreferences, SdrTrunkDatabasePath.getDatabasePath(userPreferences));
    }

    StatsWebDatabase(UserPreferences userPreferences, Path databasePath)
    {
        mUserPreferences = userPreferences;
        mDatabasePath = databasePath;
    }

    String scopeTokenForGuid(String guid)
    {
        if(guid == null || guid.isBlank())
        {
            return null;
        }

        return read(connection -> {
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT scope.scope_token
                FROM trunked_identity_scope_context ownership
                JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
                JOIN receiver_context context ON context.id = ownership.context_id
                WHERE context.guid = ?
                ORDER BY ownership.last_seen_ms DESC
                LIMIT 1
                """, guid);
            return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get("scope_token"));
        });
    }

    Map<String,Object> status()
    {
        Path path = getDatabasePath();
        Map<String,Object> status = new LinkedHashMap<>();
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

    /**
     * Produces one complete CSV dataset from one explicit read transaction.  The page controls are intentionally not
     * used; search and allowlisted sort controls retain the same meaning as their corresponding JSON table.
     */
    StatsCsvExport csvExport(StatsRequest request)
    {
        String dataset = request.requiredText("dataset");

        if(!List.of("system-talkgroups", "system-radios", "site-channels", "site-neighbors",
            "conventional-channels", "conventional-talkgroups", "conventional-radios", "signal-health",
            "site-quality", "aliases").contains(dataset))
        {
            throw new StatsApiException(400, "Unsupported CSV dataset");
        }

        return readSnapshot(connection -> {
            int queryLimit = StatsCsvExport.MAX_ROWS + 1;
            List<Map<String,Object>> rows;
            String fileScope;

            switch(dataset)
            {
                case "system-talkgroups" ->
                {
                    Map<String,Object> scope = requireScope(connection, request.requiredText("scope"));
                    rows = querySystemTalkgroups(connection, request, queryLimit, 0);
                    addExportMetadata(rows, Map.of(
                        "configured_system", textValue(scope.get("configured_system")),
                        "scope_token", textValue(scope.get("scope_token"))));
                    fileScope = exportLabel(scope, "configured_system", "scope_token");
                }
                case "system-radios" ->
                {
                    Map<String,Object> scope = requireScope(connection, request.requiredText("scope"));
                    rows = querySystemRadios(connection, request, queryLimit, 0);
                    enrichScopeTalkgroups(connection, rows, "last_talkgroup_id", "last_talkgroup_alias_");
                    addExportMetadata(rows, Map.of(
                        "configured_system", textValue(scope.get("configured_system")),
                        "scope_token", textValue(scope.get("scope_token"))));
                    fileScope = exportLabel(scope, "configured_system", "scope_token");
                }
                case "site-channels" ->
                {
                    String guid = request.requiredText("guid");
                    Map<String,Object> metadata = exportSiteMetadata(connection, guid);
                    rows = querySiteChannels(connection, guid, queryLimit, 0);
                    addExportMetadata(rows, metadata);
                    fileScope = exportLabel(metadata, "site_name", "site_guid");
                }
                case "site-neighbors" ->
                {
                    String guid = request.requiredText("guid");
                    Map<String,Object> metadata = exportSiteMetadata(connection, guid);
                    rows = querySiteNeighbors(connection, guid, queryLimit, 0);
                    addExportMetadata(rows, metadata);
                    fileScope = exportLabel(metadata, "site_name", "site_guid");
                }
                case "conventional-channels" ->
                {
                    rows = queryConventional(connection, request, queryLimit, 0);
                    fileScope = "all-conventional";
                }
                case "conventional-talkgroups" ->
                {
                    rows = queryConventionalTalkgroups(connection, request, queryLimit, 0);
                    fileScope = request.requiredText("context");
                }
                case "conventional-radios" ->
                {
                    rows = queryConventionalRadios(connection, request, queryLimit, 0);
                    fileScope = request.requiredText("context");
                }
                case "signal-health" ->
                {
                    rows = querySignalHealthExport(connection);
                    fileScope = "all-sites";
                }
                case "site-quality" ->
                {
                    String guid = request.requiredText("guid");
                    Map<String,Object> site = first(queryRows(connection, "SELECT * FROM (" +
                        qualitySiteSelect() + ") WHERE guid = ?", guid), "Site not found");
                    rows = querySiteQualityExport(connection, request, guid, site);
                    fileScope = exportLabel(site, "channel_name", "guid");
                }
                case "aliases" ->
                {
                    rows = mAliasCatalog.exportRows(connection, request, StatsCsvExport.MAX_ROWS);
                    fileScope = request.text("list") != null ? request.text("list") : "all-aliases";
                }
                default -> throw new StatsApiException(400, "Unsupported CSV dataset");
            }

            return StatsCsvExport.create(dataset, fileScope, rows);
        });
    }

    Map<String,Object> aliasLists()
    {
        return readSnapshot(mAliasCatalog::aliasLists);
    }

    Map<String,Object> aliases(StatsRequest request)
    {
        return readSnapshot(connection -> mAliasCatalog.aliases(connection, request));
    }

    Map<String,Object> alias(StatsRequest request)
    {
        return readSnapshot(connection -> mAliasCatalog.alias(connection, request.requiredIdentifier("id")));
    }

    /**
     * Invalidates read-model alias rules after a committed administrator edit.  Package visibility keeps mutation
     * ownership in the web service while still making the refresh explicit and testable.
     */
    void invalidateAliasCache()
    {
        mAliasResolver.invalidate();
    }

    /**
     * Returns one bounded row per observed P25, DMR, or NXDN talkgroup (or patch) assigned to one durable alias
     * list.  The default excludes identities with an exact definition so an ordinary range cannot hide talkgroups
     * that still need names.
     */
    Map<String,Object> observedTalkgroups(StatsRequest request)
    {
        return observedTalkgroups(request, ignored -> {});
    }

    /**
     * Runs the observed-talkgroup query and exposes the exact immutable statement to focused query-plan diagnostics.
     */
    Map<String,Object> observedTalkgroups(StatsRequest request, Consumer<ObservedTalkgroupQuery> queryObserver)
    {
        int aliasListId = request.requiredIdentifier("list");

        if(aliasListId <= 0)
        {
            throw new StatsApiException(400, "list is invalid");
        }

        boolean includeExact = booleanParameter(request, "include_exact", false);

        return readSnapshot(connection -> {
            Map<String,Object> aliasList = first(queryRows(connection, """
                SELECT id AS alias_list_id, name, family
                FROM alias_list
                WHERE id = ?
                """, aliasListId), "Alias list not found");
            if(!Set.of("P25", "DMR", "NXDN").contains(aliasList.get("family")))
            {
                throw new StatsApiException(400,
                    "Observed talkgroup discovery is available only for P25, DMR, and NXDN alias lists");
            }
            String aliasListName = String.valueOf(aliasList.get("name"));
            StringBuilder sql = new StringBuilder("""
                WITH scoped AS (
                    %s
                ), observed AS (
                    SELECT ? AS alias_list_id, ? AS alias_list_name, scoped.family,
                        scoped.scope_id, scoped.scope_token, scoped.scope_token AS scope_key,
                        NULL AS context_id, NULL AS context_key,
                        scoped.protocol_code, scoped.protocol, scoped.identity_domain_code,
                        summary.p25_identity_state_code, summary.p25_home_wacn,
                        summary.p25_home_system_id, summary.p25_home_talkgroup_id,
                        'TRUNKED' AS topology,
                        coalesce(scoped.configured_system, scoped.site_names, scoped.scope_token) AS system_name,
                        scoped.site_names, scoped.system_key, scoped.wacn, scoped.system_id,
                        scoped.network_id, NULL AS frequency_hz, NULL AS timeslot,
                        NULL AS frequency_count, NULL AS timeslot_count,
                        summary.identity_id AS talkgroup_id,
                        summary.identity_kind_code AS target_kind_code,
                        CASE summary.identity_kind_code WHEN 3 THEN 'Patch Group' ELSE 'Talkgroup' END
                            AS identity_kind,
                        summary.first_seen_ms, summary.last_seen_ms, summary.call_count,
                        summary.recorded_count, summary.streamed_count, summary.encrypted_count,
                        summary.grant_count, summary.join_count, summary.emergency_count,
                        summary.register_count, summary.logout_count, summary.denial_count,
                        summary.data_count, %s AS other_signaling_count,
                        %s AS signaling_count,
                        -- Repeating the partial-index predicate lets SQLite prove each forced lookup is valid.
                        (
                            (scoped.protocol_code = 1 AND EXISTS (
                                SELECT 1 FROM alias definition INDEXED BY idx_alias_talkgroup_value
                                WHERE definition.alias_list_id = ?
                                  AND definition.matcher_type = 'TALKGROUP'
                                  AND definition.protocol IN ('APCO25', 'APCO25_PHASE2')
                                  AND definition.value = summary.identity_id
                            ))
                            OR
                            (scoped.protocol_code = 3 AND EXISTS (
                                SELECT 1 FROM alias definition INDEXED BY idx_alias_talkgroup_value
                                WHERE definition.alias_list_id = ?
                                  AND definition.matcher_type = 'TALKGROUP'
                                  AND definition.protocol = 'DMR'
                                  AND definition.value = summary.identity_id
                            ))
                            OR
                            (scoped.protocol_code = 4 AND EXISTS (
                                SELECT 1 FROM alias definition INDEXED BY idx_alias_talkgroup_value
                                WHERE definition.alias_list_id = ?
                                  AND definition.matcher_type = 'TALKGROUP'
                                  AND definition.protocol = 'NXDN'
                                  AND definition.value = summary.identity_id
                            ))
                        ) AS has_exact_definition
                    FROM (
                        SELECT scoped.*, ? AS family
                        FROM scoped
                        WHERE ((? = 'P25' AND scoped.protocol_code = 1)
                            OR (? = 'DMR' AND scoped.protocol_code = 3)
                            OR (? = 'NXDN' AND scoped.protocol_code = 4))
                          AND EXISTS (
                            SELECT 1
                            FROM trunked_identity_scope_context ownership
                            JOIN receiver_context context ON context.id = ownership.context_id
                            LEFT JOIN p25_site_snapshot p25 ON p25.guid = context.guid
                            LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                            WHERE ownership.scope_id = scoped.scope_id
                              AND coalesce(context.alias_list_name, p25.alias_list_name,
                                trunked.alias_list_name) = ? COLLATE NOCASE
                          )
                    ) scoped
                    JOIN trunked_identity_summary summary ON summary.scope_id = scoped.scope_id
                    WHERE summary.identity_kind_code IN (1, 3)

                    UNION ALL

                    SELECT ? AS alias_list_id, ? AS alias_list_name, 'P25' AS family,
                        scoped.scope_id, scoped.scope_token, scoped.scope_token AS scope_key,
                        NULL AS context_id, NULL AS context_key,
                        scoped.protocol_code, scoped.protocol, scoped.identity_domain_code,
                        2 AS p25_identity_state_code, summary.home_wacn AS p25_home_wacn,
                        summary.home_system_id AS p25_home_system_id,
                        summary.home_talkgroup_id AS p25_home_talkgroup_id,
                        'TRUNKED' AS topology,
                        coalesce(scoped.configured_system, scoped.site_names, scoped.scope_token) AS system_name,
                        scoped.site_names, scoped.system_key, scoped.wacn, scoped.system_id,
                        scoped.network_id, NULL AS frequency_hz, NULL AS timeslot,
                        NULL AS frequency_count, NULL AS timeslot_count,
                        0 AS talkgroup_id, 1 AS target_kind_code, 'Talkgroup' AS identity_kind,
                        summary.first_seen_ms, summary.last_seen_ms, summary.call_count,
                        summary.recorded_count, summary.streamed_count, summary.encrypted_count,
                        summary.grant_count, summary.join_count, summary.emergency_count,
                        summary.register_count, summary.logout_count, summary.denial_count,
                        summary.data_count, %s AS other_signaling_count,
                        %s AS signaling_count,
                        0 AS has_exact_definition
                    FROM (
                        SELECT scoped.*
                        FROM scoped
                        WHERE ? = 'P25' AND scoped.protocol_code = 1
                          AND EXISTS (
                            SELECT 1
                            FROM trunked_identity_scope_context ownership
                            JOIN receiver_context context ON context.id = ownership.context_id
                            LEFT JOIN p25_site_snapshot p25 ON p25.guid = context.guid
                            LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                            WHERE ownership.scope_id = scoped.scope_id
                              AND coalesce(context.alias_list_name, p25.alias_list_name,
                                trunked.alias_list_name) = ? COLLATE NOCASE
                          )
                    ) scoped
                    JOIN p25_zero_local_fq_talkgroup_summary AS summary
                        INDEXED BY idx_p25_zero_local_fq_scope_last_seen
                      ON summary.scope_id = scoped.scope_id

                    UNION ALL

                    SELECT ? AS alias_list_id, ? AS alias_list_name, 'DMR' AS family,
                        NULL AS scope_id, NULL AS scope_token, context.context_key AS scope_key,
                        context.id AS context_id, context.context_key,
                        3 AS protocol_code, 'DMR' AS protocol, NULL AS identity_domain_code,
                        0 AS p25_identity_state_code, NULL AS p25_home_wacn,
                        NULL AS p25_home_system_id, NULL AS p25_home_talkgroup_id,
                        'CONVENTIONAL' AS topology,
                        coalesce((SELECT nullif(trim(config.system_name), '')
                                  FROM configuration_channel config
                                  WHERE config.radres_guid = context.guid
                                  ORDER BY config.sort_order, config.id LIMIT 1), context.channel_name,
                            context.context_key) AS system_name,
                        context.channel_name AS site_names, NULL AS system_key, NULL AS wacn,
                        NULL AS system_id, NULL AS network_id,
                        CASE WHEN min(summary.frequency_hz) = max(summary.frequency_hz)
                            THEN min(summary.frequency_hz) END AS frequency_hz,
                        CASE WHEN min(summary.timeslot) = max(summary.timeslot)
                            THEN min(summary.timeslot) END AS timeslot,
                        count(DISTINCT summary.frequency_hz) AS frequency_count,
                        count(DISTINCT summary.timeslot) AS timeslot_count,
                        summary.talkgroup_id, 1 AS target_kind_code, 'Talkgroup' AS identity_kind,
                        min(summary.first_seen_ms) AS first_seen_ms,
                        max(summary.last_seen_ms) AS last_seen_ms,
                        sum(summary.call_count) AS call_count,
                        coalesce((SELECT sum(bucket.recorded_count)
                            FROM call_identity_bucket bucket
                            WHERE bucket.context_id = context.id AND bucket.identity_role_code = 1
                              AND bucket.identity_kind_code = 1
                              AND bucket.identity_id = summary.talkgroup_id), 0) AS recorded_count,
                        coalesce((SELECT sum(bucket.streamed_count)
                            FROM call_identity_bucket bucket
                            WHERE bucket.context_id = context.id AND bucket.identity_role_code = 1
                              AND bucket.identity_kind_code = 1
                              AND bucket.identity_id = summary.talkgroup_id), 0) AS streamed_count,
                        sum(summary.encrypted_count) AS encrypted_count,
                        NULL AS grant_count, NULL AS join_count, NULL AS emergency_count,
                        NULL AS register_count, NULL AS logout_count, NULL AS denial_count,
                        NULL AS data_count, NULL AS other_signaling_count, NULL AS signaling_count,
                        EXISTS (
                            SELECT 1 FROM alias definition INDEXED BY idx_alias_talkgroup_value
                            WHERE definition.alias_list_id = ?
                              AND definition.matcher_type = 'TALKGROUP'
                              AND definition.protocol = 'DMR'
                              AND definition.value = summary.talkgroup_id
                        ) AS has_exact_definition
                    FROM receiver_context context
                    JOIN dmr_conventional_talkgroup_summary summary ON summary.context_id = context.id
                    WHERE ? = 'DMR' AND context.kind_code = 3 AND context.protocol_code = 3
                      AND context.alias_list_name = ? COLLATE NOCASE
                    GROUP BY context.id, context.context_key, context.channel_name, summary.talkgroup_id

                    UNION ALL

                    SELECT ? AS alias_list_id, ? AS alias_list_name, ? AS family,
                        NULL AS scope_id, NULL AS scope_token, context.context_key AS scope_key,
                        context.id AS context_id, context.context_key,
                        CASE WHEN context.protocol_code IN (1, 2) THEN 1 ELSE 4 END AS protocol_code,
                        CASE WHEN context.protocol_code IN (1, 2) THEN 'P25' ELSE 'NXDN' END AS protocol,
                        NULL AS identity_domain_code,
                        0 AS p25_identity_state_code, NULL AS p25_home_wacn,
                        NULL AS p25_home_system_id, NULL AS p25_home_talkgroup_id,
                        'CONVENTIONAL' AS topology,
                        coalesce((SELECT nullif(trim(config.system_name), '')
                                  FROM configuration_channel config
                                  WHERE config.radres_guid = context.guid
                                  ORDER BY config.sort_order, config.id LIMIT 1), context.channel_name,
                            context.context_key) AS system_name,
                        context.channel_name AS site_names, NULL AS system_key, NULL AS wacn,
                        NULL AS system_id, NULL AS network_id,
                        context.primary_frequency_hz AS frequency_hz, NULL AS timeslot,
                        CASE WHEN context.primary_frequency_hz IS NULL THEN 0 ELSE 1 END AS frequency_count,
                        NULL AS timeslot_count,
                        bucket.identity_id AS talkgroup_id,
                        bucket.identity_kind_code AS target_kind_code,
                        CASE bucket.identity_kind_code WHEN 3 THEN 'Patch Group' ELSE 'Talkgroup' END
                            AS identity_kind,
                        min(bucket.bucket_start_ms) AS first_seen_ms,
                        max(bucket.bucket_start_ms) AS last_seen_ms,
                        sum(bucket.call_count) AS call_count,
                        sum(bucket.recorded_count) AS recorded_count,
                        sum(bucket.streamed_count) AS streamed_count,
                        sum(bucket.encrypted_count) AS encrypted_count,
                        NULL AS grant_count, NULL AS join_count, NULL AS emergency_count,
                        NULL AS register_count, NULL AS logout_count, NULL AS denial_count,
                        NULL AS data_count, NULL AS other_signaling_count, NULL AS signaling_count,
                        (
                            (context.protocol_code IN (1, 2) AND EXISTS (
                                SELECT 1 FROM alias definition INDEXED BY idx_alias_talkgroup_value
                                WHERE definition.alias_list_id = ?
                                  AND definition.matcher_type = 'TALKGROUP'
                                  AND definition.protocol IN ('APCO25', 'APCO25_PHASE2')
                                  AND definition.value = bucket.identity_id
                            ))
                            OR
                            (context.protocol_code = 4 AND EXISTS (
                                SELECT 1 FROM alias definition INDEXED BY idx_alias_talkgroup_value
                                WHERE definition.alias_list_id = ?
                                  AND definition.matcher_type = 'TALKGROUP'
                                  AND definition.protocol = 'NXDN'
                                  AND definition.value = bucket.identity_id
                            ))
                        ) AS has_exact_definition
                    FROM receiver_context context
                    JOIN call_identity_bucket bucket ON bucket.context_id = context.id
                    WHERE context.alias_list_name = ? COLLATE NOCASE
                      AND ((? = 'P25' AND context.kind_code = 2
                            AND context.protocol_code IN (1, 2))
                        OR (? = 'NXDN' AND context.kind_code = 4 AND context.protocol_code = 4))
                      AND bucket.identity_role_code = 1
                      AND bucket.identity_kind_code IN (1, 3)
                    GROUP BY context.id, context.context_key, context.protocol_code, context.channel_name,
                        context.primary_frequency_hz, bucket.identity_kind_code, bucket.identity_id
                )
                SELECT * FROM observed
                WHERE 1 = 1
                """.formatted(scopeSummarySelect(), OTHER_TALKGROUP_SIGNALING_COUNT_SQL,
                TALKGROUP_SIGNALING_COUNT_SQL, OTHER_TALKGROUP_SIGNALING_COUNT_SQL,
                TALKGROUP_SIGNALING_COUNT_SQL));
            List<Object> parameters = new ArrayList<>(List.of(
                aliasListId, aliasListName, aliasListId, aliasListId, aliasListId,
                aliasList.get("family"), aliasList.get("family"), aliasList.get("family"),
                aliasList.get("family"), aliasListName,
                aliasListId, aliasListName, aliasList.get("family"), aliasListName,
                aliasListId, aliasListName, aliasListId, aliasList.get("family"), aliasListName,
                aliasListId, aliasListName, aliasList.get("family"), aliasListId, aliasListId, aliasListName,
                aliasList.get("family"), aliasList.get("family")));

            if(!includeExact)
            {
                sql.append(" AND has_exact_definition = 0");
            }

            if(request.search() != null)
            {
                sql.append("""
                     AND lower(coalesce(system_name, '') || ' ' || coalesce(site_names, '') || ' ' ||
                       coalesce(scope_key, '') || ' ' || protocol || ' ' || topology || ' ' ||
                       identity_kind || ' ' || CAST(talkgroup_id AS TEXT) || ' ' ||
                       coalesce(CAST(p25_home_wacn AS TEXT), '') || ' ' ||
                       coalesce(CAST(p25_home_system_id AS TEXT), '') || ' ' ||
                       coalesce(CAST(p25_home_talkgroup_id AS TEXT), '') || ' ' ||
                       CASE WHEN p25_identity_state_code = 2
                         THEN printf('%05X-%03X-%d', p25_home_wacn, p25_home_system_id,
                           p25_home_talkgroup_id)
                         ELSE '' END) LIKE ?
                    """);
                parameters.add(like(request.search()));
            }

            sql.append(" ORDER BY ").append(order(request, OBSERVED_TALKGROUP_SORT_COLUMNS, "last_seen"))
                .append(", topology, protocol_code, scope_key, target_kind_code, talkgroup_id, ")
                .append("p25_home_wacn, p25_home_system_id, p25_home_talkgroup_id LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            ObservedTalkgroupQuery query = new ObservedTalkgroupQuery(sql.toString(), parameters);
            queryObserver.accept(query);
            List<Map<String,Object>> rows = queryRows(connection, query.sql(), query.parameters().toArray());
            mAliasResolver.resolveObservedTalkgroups(connection, rows);

            for(Map<String,Object> row: rows)
            {
                row.remove("has_exact_definition");
            }

            Map<String,Object> response = page(rows, request);
            response.put("alias_list", aliasList);
            response.put("include_exact", includeExact);
            return response;
        });
    }

    /** Exact statement and bindings used by the observed-talkgroup read model. */
    record ObservedTalkgroupQuery(String sql, List<Object> parameters)
    {
        ObservedTalkgroupQuery
        {
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * Latest quality snapshot for every known monitored trunked site.  A left join deliberately retains sites that
     * have not produced a quality sample yet; their measurement columns remain null in JSON/CSV.
     */
    private static List<Map<String,Object>> querySignalHealthExport(Connection connection) throws SQLException
    {
        List<Map<String,Object>> rows = queryRows(connection, """
            SELECT site.*, quality.frequency_hz AS quality_frequency_hz,
                quality.observed_at_ms AS last_observed_ms, quality.signal_dbfs,
                quality.average_signal_dbfs, quality.minimum_signal_dbfs, quality.maximum_signal_dbfs,
                quality.decode_health_pct, quality.valid_frames, quality.invalid_frames,
                quality.corrected_bits, quality.sync_loss_bits, quality.dropped_bits,
                quality.last_valid_decode_ms
            FROM (
                %s
            ) site
            LEFT JOIN p25_control_channel_quality quality ON quality.guid = site.guid AND
                (quality.frequency_hz, quality.bucket_start_ms) = (
                    SELECT candidate.frequency_hz, candidate.bucket_start_ms
                    FROM p25_control_channel_quality candidate
                    WHERE candidate.guid = site.guid
                    ORDER BY candidate.observed_at_ms DESC, candidate.frequency_hz DESC
                    LIMIT 1
                )
            ORDER BY lower(coalesce(site.channel_name, site.guid)), site.guid
            """.formatted(qualitySiteSelect()));
        long now = System.currentTimeMillis();

        for(Map<String,Object> row: rows)
        {
            if(row.get("last_observed_ms") instanceof Number observed)
            {
                row.put("sample_age_seconds", Math.max(0, (now - observed.longValue()) / 1_000L));
            }
        }

        return rows;
    }

    /**
     * Chart-compatible bounded quality export.  Signal and health percentages are aggregated; rolling 30-second
     * frame/bit counters are intentionally omitted because summing overlapping windows would produce false totals.
     */
    private List<Map<String,Object>> querySiteQualityExport(Connection connection, StatsRequest request, String guid,
                                                             Map<String,Object> site) throws SQLException
    {
        String range = request.requiredText("range").toLowerCase();
        long requestedMilliseconds = switch(range)
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
        long rangeMilliseconds = Math.min(requestedMilliseconds, retentionMilliseconds);
        Integer requestedPoints = request.optionalInt("points");
        int targetPoints = Math.max(QUALITY_MINIMUM_POINTS, Math.min(QUALITY_MAXIMUM_POINTS,
            requestedPoints != null ? requestedPoints : QUALITY_DEFAULT_POINTS));
        long rawBucketMilliseconds = Math.max(1,
            (rangeMilliseconds + targetPoints - 1) / targetPoints);
        long bucketMilliseconds = Math.max(QUALITY_BUCKET_MILLISECONDS,
            ((rawBucketMilliseconds + QUALITY_BUCKET_MILLISECONDS - 1) / QUALITY_BUCKET_MILLISECONDS) *
                QUALITY_BUCKET_MILLISECONDS);
        long toMilliseconds = System.currentTimeMillis();
        long fromMilliseconds = toMilliseconds - rangeMilliseconds;
        List<Map<String,Object>> rows = queryRows(connection, """
            SELECT (observed_at_ms / ?) * ? AS time_ms,
                avg(average_signal_dbfs) AS average_signal_dbfs,
                min(minimum_signal_dbfs) AS minimum_signal_dbfs,
                max(maximum_signal_dbfs) AS maximum_signal_dbfs,
                avg(decode_health_pct) AS decode_health_pct,
                min(decode_health_pct) AS minimum_decode_health_pct,
                max(decode_health_pct) AS maximum_decode_health_pct,
                CASE WHEN min(frequency_hz) = max(frequency_hz) THEN min(frequency_hz) END AS frequency_hz,
                count(DISTINCT frequency_hz) AS frequency_count, count(*) AS sample_count,
                max(observed_at_ms) AS last_observed_ms
            FROM p25_control_channel_quality INDEXED BY idx_p25_control_quality_guid_time
            WHERE guid = ? AND observed_at_ms >= ? AND observed_at_ms <= ?
            GROUP BY time_ms
            ORDER BY time_ms
            LIMIT ?
            """, bucketMilliseconds, bucketMilliseconds, guid, fromMilliseconds, toMilliseconds,
            targetPoints + 2);

        if(rows.size() > targetPoints + 1)
        {
            throw new StatsApiException(413, "Site quality export exceeds the bounded point limit");
        }

        for(Map<String,Object> row: rows)
        {
            row.put("guid", guid);
            row.put("range", range);
            row.put("bucket_ms", bucketMilliseconds);
            row.put("bucket_end_ms", number(row.get("time_ms")) + bucketMilliseconds);
            row.put("protocol", site.get("protocol"));
            row.put("configured_system", site.get("configured_system"));
            row.put("configured_site", site.get("configured_site"));
            row.put("configured_name", site.get("configured_name"));
            row.put("channel_name", site.get("channel_name"));
            for(String field: List.of("wacn", "system_id", "network_id", "rfss", "site", "site_id", "nac",
                "ran"))
            {
                row.put(field, site.get(field));
            }
        }

        return rows;
    }

    private static void addExportMetadata(List<Map<String,Object>> rows, Map<String,Object> metadata)
    {
        for(Map<String,Object> row: rows)
        {
            row.putAll(metadata);
        }
    }

    private static String exportLabel(Map<String,Object> values, String preferredKey, String fallbackKey)
    {
        String preferred = textValue(values.get(preferredKey));
        return !preferred.isBlank() ? preferred : textValue(values.get(fallbackKey));
    }

    private static String textValue(Object value)
    {
        return value != null ? String.valueOf(value) : "";
    }

    private static Map<String,Object> exportSiteMetadata(Connection connection, String guid) throws SQLException
    {
        int currentProtocol = currentSiteProtocolCode(connection, guid);
        List<Map<String,Object>> currentSites = currentProtocol == 1 ?
            queryRows(connection, siteSelect() + " WHERE site.guid = ?", guid) :
            currentProtocol == 3 || currentProtocol == 4 ?
                queryRows(connection, trunkedSiteSelect() + " WHERE site.guid = ?", guid) : List.of();

        if(currentSites.isEmpty() && currentProtocol != 0)
        {
            currentSites = queryRows(connection, """
                SELECT context.guid, context.channel_name, context.nac, context.rfss, context.site,
                    system.wacn, system.system_id, NULL AS configured_system, NULL AS network_id,
                    NULL AS site_id, NULL AS ran
                FROM receiver_context context
                LEFT JOIN p25_system system ON system.system_key = context.system_key
                WHERE context.guid = ? AND context.kind_code = 1
                LIMIT 1
                """, guid);
        }

        Map<String,Object> site = first(currentSites, "Site not found");
        List<Map<String,Object>> ownership = queryRows(connection, """
            SELECT scope.scope_token, config.system_name
            FROM receiver_context context
            LEFT JOIN trunked_identity_scope_context assigned ON assigned.context_id = context.id
            LEFT JOIN trunked_identity_scope scope ON scope.scope_id = assigned.scope_id
            LEFT JOIN configuration_channel config ON config.radres_guid = context.guid
            WHERE context.guid = ?
            ORDER BY assigned.last_seen_ms DESC
            LIMIT 1
            """, guid);
        Map<String,Object> assigned = ownership.isEmpty() ? Map.of() : ownership.getFirst();
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("site_guid", guid);
        metadata.put("site_name", textValue(site.get("channel_name")));
        metadata.put("site_protocol", currentProtocol == 1 ? "P25" : currentProtocol == 3 ? "DMR" :
            currentProtocol == 4 ? "NXDN" : "Unknown");
        metadata.put("site_scope_token", textValue(assigned.get("scope_token")));
        metadata.put("site_system_name", !textValue(site.get("configured_system")).isBlank() ?
            textValue(site.get("configured_system")) : textValue(assigned.get("system_name")));
        metadata.put("site_wacn", site.get("wacn") != null ? site.get("wacn") : "");
        metadata.put("site_system_id", site.get("system_id") != null ? site.get("system_id") : "");
        metadata.put("site_network_id", site.get("network_id") != null ? site.get("network_id") : "");
        metadata.put("site_rfss", site.get("rfss") != null ? site.get("rfss") : "");
        metadata.put("site_number", site.get("site") != null ? site.get("site") : site.get("site_id"));
        metadata.put("site_nac", site.get("nac") != null ? site.get("nac") : "");
        metadata.put("site_ran", site.get("ran") != null ? site.get("ran") : "");
        return metadata;
    }

    Map<String,Object> dashboard()
    {
        return read(connection -> {
            Map<String,Object> dashboard = new LinkedHashMap<>();
            dashboard.put("counts", Map.of(
                "trunked_systems", scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_scope"),
                "trunked_sites", scalarLong(connection,
                    "SELECT COUNT(*) FROM trunked_identity_scope_context"),
                "conventional_channels", scalarLong(connection,
                    "SELECT COUNT(*) FROM receiver_context WHERE kind_code <> 1")
            ));
            dashboard.put("lastSeenMs", scalarLong(connection, """
                SELECT MAX(last_seen_ms) FROM (
                    SELECT last_seen_ms FROM p25_site_snapshot
                    UNION ALL SELECT last_seen_ms FROM trunked_identity_scope
                    UNION ALL SELECT last_seen_ms FROM trunked_identity_summary
                    UNION ALL SELECT last_seen_ms FROM p25_site_frequency_summary
                    UNION ALL SELECT last_seen_ms FROM conventional_activity_summary
                    UNION ALL SELECT last_seen_ms FROM trunked_site_snapshot
                )
                """));

            long now = System.currentTimeMillis();
            long firstIdentityHour = Math.floorDiv(now, HOUR_MILLISECONDS) * HOUR_MILLISECONDS -
                (DASHBOARD_HOURS - 1L) * HOUR_MILLISECONDS;
            dashboard.put("topDestinations", topCallIdentities(connection, IDENTITY_ROLE_DESTINATION,
                firstIdentityHour, now));
            dashboard.put("topSources", topCallIdentities(connection, IDENTITY_ROLE_SOURCE,
                firstIdentityHour, now));
            dashboard.put("recentReceivers", recentReceivers(connection));
            dashboard.put("callActivity", callActivity(connection));
            dashboard.put("sourceActivity24h", sourceActivity24Hours(connection));
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
                SELECT activity.id, activity.context_id, activity.context_key, activity.guid,
                    activity.observed_at_ms, activity.channel_kind,
                    activity.channel_kind_code, activity.protocol, activity.action, activity.event_type,
                    activity.source_radio_id, activity.target_id, activity.target_kind_code, activity.target_kind,
                    activity.frequency_hz, activity.lcn, activity.timeslot, activity.encrypted,
                    activity.encryption_algorithm_id, activity.encryption_key_id, activity.resolved_channel_name,
                    activity.resolved_alias_list_name,
                    coalesce(activity.resolved_alias_list_name, trunked.alias_list_name) AS alias_list_name,
                    scope.scope_token, scope.identity_domain_code,
                    activity.resolved_system_key AS system_key, activity.resolved_wacn AS wacn,
                    activity.resolved_system_id AS system_id, activity.resolved_nac, activity.resolved_rfss,
                    activity.resolved_site
                FROM p25_activity_event_resolved activity
                LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = activity.guid
                LEFT JOIN trunked_identity_scope_context ownership ON ownership.context_id = activity.context_id
                LEFT JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
                WHERE activity.id IN (%s)
                ORDER BY activity.id
                """.formatted(placeholders), rowIds.toArray());
            mAliasResolver.enrichActivity(connection, rows);
            enrichActivityEncryption(rows);
            enrichActivityTalkgroupMembers(connection, rows);
            return rows;
        });
    }

    private static void enrichActivityTalkgroupMembers(Connection connection, List<Map<String,Object>> rows)
        throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        List<Long> eventIds = rows.stream()
            .map(row -> row.get("id"))
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .map(Number::longValue)
            .toList();

        if(eventIds.isEmpty())
        {
            return;
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(eventIds.size(), "?"));
        List<Map<String,Object>> members = queryRows(connection, """
            SELECT event_id, talkgroup_id
            FROM activity_event_talkgroup_member
            WHERE event_id IN (%s)
            ORDER BY event_id, talkgroup_id
            """.formatted(placeholders), eventIds.toArray());
        Map<Long,List<Long>> membersByEventId = new LinkedHashMap<>();

        for(Map<String,Object> member: members)
        {
            if(member.get("event_id") instanceof Number eventId &&
                member.get("talkgroup_id") instanceof Number talkgroupId)
            {
                membersByEventId.computeIfAbsent(eventId.longValue(), ignored -> new ArrayList<>())
                    .add(talkgroupId.longValue());
            }
        }

        for(Map<String,Object> row: rows)
        {
            if(row.get("id") instanceof Number eventId)
            {
                row.put("member_talkgroup_ids",
                    List.copyOf(membersByEventId.getOrDefault(eventId.longValue(), List.of())));
            }
        }
    }

    Map<String,Object> systemDirectory(StatsRequest request)
    {
        return read(connection -> {
            StringBuilder sql = new StringBuilder("WITH scoped AS (")
                .append(scopeSummarySelect()).append(") SELECT * FROM scoped WHERE 1=1");
            List<Object> parameters = new ArrayList<>();

            if(request.search() != null)
            {
                sql.append("""
                     AND (lower(protocol || ' ' || scope_token || ' ' ||
                       coalesce(configured_system, '') || ' ' || coalesce(site_names, '') || ' ' ||
                       coalesce(CAST(wacn AS TEXT), '') || ' ' ||
                       CASE WHEN wacn IS NOT NULL THEN printf('%05X', wacn) ELSE '' END || ' ' ||
                       coalesce(CAST(system_id AS TEXT), '') || ' ' ||
                       CASE WHEN system_id IS NOT NULL THEN printf('%03X', system_id) ELSE '' END || ' ' ||
                       coalesce(CAST(network_id AS TEXT), '')) LIKE ?
                       OR EXISTS (
                           SELECT 1
                           FROM trunked_identity_scope_context ownership
                           JOIN receiver_context context ON context.id = ownership.context_id
                           LEFT JOIN p25_site_snapshot p25 ON p25.guid = context.guid
                           LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                           LEFT JOIN configuration_channel config ON config.radres_guid = context.guid
                           WHERE ownership.scope_id = scoped.scope_id
                             AND lower(coalesce(context.guid, '') || ' ' ||
                                 coalesce(context.channel_name, '') || ' ' ||
                                 coalesce(p25.channel_name, '') || ' ' ||
                                 coalesce(trunked.channel_name, '') || ' ' ||
                                 coalesce(trunked.configured_system, '') || ' ' ||
                                 coalesce(config.system_name, '') || ' ' ||
                                 coalesce(config.site_name, '') || ' ' ||
                                 coalesce(config.name, '') || ' ' ||
                                 coalesce(CAST(trunked.network_id AS TEXT), '') || ' ' ||
                                 coalesce(CAST(trunked.system_id AS TEXT), '') || ' ' ||
                                 coalesce(CAST(trunked.site_id AS TEXT), '') || ' ' ||
                                 coalesce(CAST(trunked.ran AS TEXT), '') || ' ' ||
                                 coalesce(CAST(p25.rfss AS TEXT), '') || ' ' ||
                                 coalesce(CAST(p25.site AS TEXT), '')) LIKE ?))
                    """);
                String like = like(request.search());
                parameters.add(like);
                parameters.add(like);
            }

            sql.append(" ORDER BY ").append(order(request, SYSTEM_SORT_COLUMNS, "last_seen"))
                .append(", scope_token LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> parentRows = queryRows(connection, sql.toString(), parameters.toArray());
            Map<String,Object> response = page(parentRows, request);
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> systems = (List<Map<String,Object>>)response.get("rows");

            for(Map<String,Object> system: systems)
            {
                List<Map<String,Object>> children = queryScopeSites(connection,
                    number(system.get("scope_id")),
                    new StatsRequest(Map.of("limit", String.valueOf(DIRECTORY_SITE_LIMIT_PER_SYSTEM))));
                if(children.size() > DIRECTORY_SITE_LIMIT_PER_SYSTEM)
                {
                    children = new ArrayList<>(children.subList(0, DIRECTORY_SITE_LIMIT_PER_SYSTEM));
                }
                system.put("children", children);
                system.put("children_truncated", number(system.get("sites")) > children.size());
                system.put("capabilities", systemCapabilities((int)number(system.get("protocol_code"))));
            }

            return response;
        });
    }

    Map<String,Object> system(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");

        return read(connection -> {
            Map<String,Object> response = new LinkedHashMap<>();
            Map<String,Object> system = requireScope(connection, scopeToken);
            List<Map<String,Object>> activity = queryRows(connection, """
                SELECT coalesce(SUM(bucket.call_count), 0) AS activity_retained_calls,
                    coalesce(SUM(bucket.recorded_count), 0) AS activity_recorded,
                    coalesce(SUM(bucket.streamed_count), 0) AS activity_streamed,
                    coalesce(SUM(bucket.encrypted_count), 0) AS activity_encrypted
                FROM trunked_identity_scope_context ownership
                LEFT JOIN p25_site_activity_bucket bucket ON bucket.context_id = ownership.context_id
                WHERE ownership.scope_id = ?
                """, system.get("scope_id"));

            if(!activity.isEmpty())
            {
                system.putAll(activity.getFirst());
                system.put("activity_calls", activity.getFirst().get("activity_retained_calls"));
            }

            system.put("capabilities", systemCapabilities((int)number(system.get("protocol_code"))));
            response.put("system", system);
            response.put("actionCounts", systemActionCounts(connection, number(system.get("scope_id"))));
            return response;
        });
    }

    Map<String,Object> systemSites(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");
        return read(connection -> {
            Map<String,Object> scope = requireScope(connection, scopeToken);
            return page(queryScopeSites(connection, number(scope.get("scope_id")), request), request);
        });
    }

    Map<String,Object> systemTalkgroups(StatsRequest request)
    {
        return read(connection -> {
            List<Map<String,Object>> rows = querySystemTalkgroups(connection, request,
                request.limit() + 1, request.offset());
            return page(rows, request);
        });
    }

    private List<Map<String,Object>> querySystemTalkgroups(Connection connection, StatsRequest request,
                                                           int limit, int offset) throws SQLException
    {
        String scopeToken = request.requiredText("scope");
        StringBuilder sql = new StringBuilder("""
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    (SELECT trunked.network_id FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                     WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                        AS network_id,
                    (SELECT context.alias_list_name FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                        AS alias_list_name,
                    summary.identity_id AS talkgroup_id, summary.identity_kind_code AS target_kind_code,
                    summary.first_seen_ms, summary.last_seen_ms, summary.call_count, summary.encrypted_count,
                    summary.recorded_count, summary.streamed_count, %s AS signaling_count
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                WHERE scope.scope_token = ? AND summary.identity_kind_code IN (1, 3)
                """.formatted(TALKGROUP_SIGNALING_COUNT_SQL));
        List<Object> parameters = new ArrayList<>(List.of(scopeToken));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        sql.append(" ORDER BY ").append(order(request, TALKGROUP_SORT_COLUMNS, "calls"))
            .append(", summary.identity_kind_code, summary.identity_id LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        enrichScopeTalkgroups(connection, rows, "talkgroup_id", "alias_");
        return rows;
    }

    Map<String,Object> systemRadios(StatsRequest request)
    {
        return read(connection -> {
            List<Map<String,Object>> rows = querySystemRadios(connection, request,
                request.limit() + 1, request.offset());
            return page(rows, request);
        });
    }

    private List<Map<String,Object>> querySystemRadios(Connection connection, StatsRequest request,
                                                       int limit, int offset) throws SQLException
    {
        String scopeToken = request.requiredText("scope");
        StringBuilder sql = new StringBuilder("""
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    (SELECT trunked.network_id FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                     WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                        AS network_id,
                    (SELECT context.alias_list_name FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                        AS alias_list_name,
                    summary.*, summary.identity_id AS radio_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_id END AS last_talkgroup_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_kind_code END AS last_talkgroup_kind_code,
                    affiliation.talkgroup_id AS affiliated_talkgroup_id,
                    affiliation.updated_at_ms AS affiliation_updated_at_ms
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                LEFT JOIN p25_radio_affiliation affiliation
                  ON scope.protocol_code = 1 AND affiliation.system_key = scope.p25_system_key
                 AND affiliation.radio_id = summary.identity_id
                WHERE scope.scope_token = ? AND summary.identity_kind_code = 2
                """);
        List<Object> parameters = new ArrayList<>(List.of(scopeToken));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        sql.append(" ORDER BY ").append(order(request, RADIO_SORT_COLUMNS, "calls"))
            .append(", summary.identity_id LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        enrichScopeRadios(connection, rows, "radio_id", "alias_");
        enrichScopeTalkgroups(connection, rows, "affiliated_talkgroup_id", "affiliated_talkgroup_alias_");
        return rows;
    }

    Map<String,Object> systemTalkerAliases(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");

        return read(connection -> {
            StringBuilder sql = new StringBuilder("""
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    (SELECT context.alias_list_name FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                        AS alias_list_name,
                    summary.*, summary.identity_id AS radio_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_id END AS last_talkgroup_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_kind_code END AS last_talkgroup_kind_code
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                WHERE scope.scope_token = ? AND summary.identity_kind_code = 2
                  AND summary.last_talker_alias IS NOT NULL
                  AND trim(summary.last_talker_alias) <> ''
                """);
            List<Object> parameters = new ArrayList<>(List.of(scopeToken));

            if(request.search() != null)
            {
                sql.append("""
                     AND (CAST(summary.identity_id AS TEXT) LIKE ?
                       OR (scope.protocol_code = 4 AND scope.identity_domain_code = 2
                         AND printf('%02d-%04d', ((summary.identity_id >> 11) & 31),
                           (summary.identity_id & 2047)) LIKE ?)
                       OR lower(summary.last_talker_alias) LIKE ?)
                    """);
                String like = like(request.search());
                parameters.add(like);
                parameters.add(like);
                parameters.add(like);
            }

            sql.append(" ORDER BY ").append(order(request, RADIO_SORT_COLUMNS, "talker_alias"))
                .append(", summary.identity_id LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            enrichScopeRadios(connection, rows, "radio_id", "alias_");
            enrichScopeTalkgroups(connection, rows, "last_talkgroup_id", "talkgroup_alias_");
            return page(rows, request);
        });
    }

    Map<String,Object> talkgroup(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");
        int talkgroup = request.requiredIdentifier("talkgroup_id");
        int identityKind = targetKind(request);

        return read(connection -> {
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    (SELECT context.alias_list_name FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                        AS alias_list_name,
                    summary.*, summary.identity_id AS talkgroup_id,
                    summary.identity_kind_code AS target_kind_code,
                    CASE WHEN summary.last_counterpart_kind_code = 2
                        THEN summary.last_counterpart_id END AS last_source_radio_id,
                    (SELECT COUNT(*) FROM trunked_radio_talkgroup_summary relationship
                        WHERE relationship.scope_id = summary.scope_id
                          AND relationship.talkgroup_id = summary.identity_id
                          AND relationship.target_kind_code = summary.identity_kind_code) AS radios,
                    (SELECT COUNT(*) FROM p25_radio_affiliation affiliation
                        WHERE summary.identity_kind_code = 1
                          AND scope.protocol_code = 1
                          AND affiliation.system_key = scope.p25_system_key
                          AND affiliation.talkgroup_id = summary.identity_id) AS affiliated_radios
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                WHERE scope.scope_token = ? AND summary.identity_kind_code = ? AND summary.identity_id = ?
                """, scopeToken, identityKind, talkgroup);
            enrichScopeTalkgroups(connection, rows, "talkgroup_id", "alias_");
            enrichSummaryEncryption(rows);
            Map<String,Object> row = first(rows, "Talkgroup not found");
            row.put("capabilities", talkgroupCapabilities((int)number(row.get("protocol_code")),
                (int)number(row.get("target_kind_code"))));
            return Map.of("talkgroup", row);
        });
    }

    Map<String,Object> talkgroupActivity(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");
        int talkgroup = request.requiredIdentifier("talkgroup_id");
        int identityKind = targetKind(request);
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
            Map<String,Object> scope = requireScope(connection, scopeToken);
            String sums = String.join(",\n                    ", CALL_ACTIVITY_FIELDS.stream()
                .map(field -> "SUM(bucket." + field + ") AS " + field)
                .toList());
            List<Map<String,Object>> stored = queryRows(connection, """
                SELECT CAST(bucket.bucket_start_ms / ? AS INTEGER) * ? AS time_ms,
                    %s
                FROM call_identity_bucket bucket
                JOIN trunked_identity_scope_context ownership ON ownership.context_id = bucket.context_id
                WHERE ownership.scope_id = ? AND bucket.identity_role_code = ?
                    AND bucket.identity_kind_code = ? AND bucket.identity_id = ?
                    AND bucket.bucket_start_ms >= ?
                GROUP BY time_ms
                ORDER BY time_ms
                """.formatted(sums), bucketMilliseconds, bucketMilliseconds, scope.get("scope_id"),
                IDENTITY_ROLE_DESTINATION, identityKind, talkgroup, fromMilliseconds);
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

                for(String field: CALL_ACTIVITY_FIELDS)
                {
                    long value = storedRow != null ? number(storedRow.get(field)) : 0L;
                    point.put(field, value);
                    totals.compute(field, (key, total) -> total + value);
                }

                series.add(point);
            }

            List<Map<String,Object>> summary = queryRows(connection, """
                SELECT *
                FROM trunked_identity_summary
                WHERE scope_id = ? AND identity_kind_code = ? AND identity_id = ?
                """, scope.get("scope_id"), identityKind, talkgroup);

            if(!summary.isEmpty())
            {
                for(String field: TALKGROUP_SIGNALING_FIELDS)
                {
                    totals.put(field, number(summary.getFirst().get(field)));
                }
            }

            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", responseRange);
            response.put("from_ms", fromMilliseconds);
            response.put("to_ms", toMilliseconds);
            response.put("bucket_ms", bucketMilliseconds);
            response.put("metric_start_ms", scopeMetricStartedAt(connection));
            response.put("totals", totals);
            response.put("series", series);
            return response;
        });
    }

    Map<String,Object> radio(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");
        int radio = request.requiredIdentifier("radio_id");

        return read(connection -> {
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    (SELECT context.alias_list_name FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                        AS alias_list_name,
                    summary.*, summary.identity_id AS radio_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_id END AS last_talkgroup_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_kind_code END AS last_talkgroup_kind_code,
                    CASE WHEN summary.last_counterpart_kind_code = 2
                        THEN summary.last_counterpart_id END AS last_peer_radio_id,
                    affiliation.talkgroup_id AS affiliated_talkgroup_id,
                    affiliation.updated_at_ms AS affiliation_updated_at_ms,
                    (SELECT COUNT(*) FROM trunked_radio_talkgroup_summary relationship
                        WHERE relationship.scope_id = summary.scope_id
                          AND relationship.radio_id = summary.identity_id) AS talkgroups
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                LEFT JOIN p25_radio_affiliation affiliation
                  ON scope.protocol_code = 1 AND affiliation.system_key = scope.p25_system_key
                 AND affiliation.radio_id = summary.identity_id
                WHERE scope.scope_token = ? AND summary.identity_kind_code = 2 AND summary.identity_id = ?
                """, scopeToken, radio);
            enrichScopeRadios(connection, rows, "radio_id", "alias_");
            enrichScopeTalkgroups(connection, rows, "affiliated_talkgroup_id", "affiliated_talkgroup_alias_");
            enrichScopeTalkgroups(connection, rows, "last_talkgroup_id", "last_talkgroup_alias_");
            enrichScopeRadios(connection, rows, "last_peer_radio_id", "last_peer_alias_");
            enrichSummaryEncryption(rows);
            Map<String,Object> row = first(rows, "Radio not found");
            row.put("capabilities", systemCapabilities((int)number(row.get("protocol_code"))));
            return Map.of("radio", row);
        });
    }

    Map<String,Object> currentAffiliations(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");
        Integer talkgroup = request.optionalIdentifier("talkgroup_id");
        Integer radio = request.optionalIdentifier("radio_id");

        return read(connection -> {
            Map<String,Object> scopeRow = requireScope(connection, scopeToken);

            if(number(scopeRow.get("protocol_code")) != 1)
            {
                throw new StatsApiException(404, "Current affiliation is not available for this protocol");
            }

            StringBuilder sql = new StringBuilder("""
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, 'P25' AS protocol,
                    scope.p25_system_key AS system_key, system.wacn, system.system_id,
                    affiliation.radio_id, affiliation.talkgroup_id, affiliation.updated_at_ms,
                    summary.last_talker_alias
                FROM p25_radio_affiliation affiliation
                JOIN trunked_identity_scope scope ON scope.p25_system_key = affiliation.system_key
                JOIN p25_system system ON system.system_key = scope.p25_system_key
                LEFT JOIN trunked_identity_summary summary
                  ON summary.scope_id = scope.scope_id AND summary.identity_kind_code = 2
                 AND summary.identity_id = affiliation.radio_id
                WHERE scope.scope_token = ?
                """);
            List<Object> parameters = new ArrayList<>(List.of(scopeToken));

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
                .append(", affiliation.radio_id LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            enrichScopeRadios(connection, rows, "radio_id", "alias_");
            enrichScopeTalkgroups(connection, rows, "talkgroup_id", "alias_");
            return page(rows, request);
        });
    }

    Map<String,Object> radioTalkgroupRelationships(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");
        Integer talkgroup = request.optionalIdentifier("talkgroup_id");
        Integer radio = request.optionalIdentifier("radio_id");

        if(talkgroup == null && radio == null)
        {
            throw new StatsApiException(400, "radio_id or talkgroup_id is required");
        }

        int targetKind = talkgroup != null ? targetKind(request) : IDENTITY_KIND_TALKGROUP;

        return read(connection -> {
            StringBuilder sql = new StringBuilder("""
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    (SELECT context.alias_list_name FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                        AS alias_list_name,
                    relationship.*,
                    radio.last_talker_alias
                FROM trunked_radio_talkgroup_summary relationship
                JOIN trunked_identity_scope scope ON scope.scope_id = relationship.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                LEFT JOIN trunked_identity_summary radio
                  ON radio.scope_id = relationship.scope_id AND radio.identity_kind_code = 2
                 AND radio.identity_id = relationship.radio_id
                WHERE scope.scope_token = ?
                """);
            List<Object> parameters = new ArrayList<>(List.of(scopeToken));

            if(talkgroup != null)
            {
                sql.append(" AND relationship.talkgroup_id = ? AND relationship.target_kind_code = ?");
                parameters.add(talkgroup);
                parameters.add(targetKind);
            }

            if(radio != null)
            {
                sql.append(" AND relationship.radio_id = ?");
                parameters.add(radio);
            }

            sql.append(" ORDER BY ").append(order(request, RELATIONSHIP_SORT_COLUMNS, "last_seen"))
                .append(", relationship.target_kind_code, relationship.talkgroup_id, relationship.radio_id")
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            enrichScopeRadios(connection, rows, "radio_id", "radio_alias_");
            enrichScopeTalkgroups(connection, rows, "talkgroup_id", "talkgroup_alias_");
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
            int currentProtocol = currentSiteProtocolCode(connection, guid);
            boolean p25OwnsGuid = currentProtocol == 1;
            Map<String,Object> site = p25OwnsGuid ? p25Site :
                currentProtocol == 3 || currentProtocol == 4 ? trunkedSite : null;

            if(site == null && currentProtocol != 0)
            {
                List<Map<String,Object>> fallback = queryRows(connection, """
                    SELECT context.guid, context.channel_name,
                        (SELECT nullif(trim(config.site_name), '')
                         FROM configuration_channel config
                         WHERE config.radres_guid = context.guid
                         ORDER BY config.sort_order, config.id LIMIT 1) AS configured_site,
                        (SELECT nullif(trim(config.name), '')
                         FROM configuration_channel config
                         WHERE config.radres_guid = context.guid
                         ORDER BY config.sort_order, config.id LIMIT 1) AS configured_name,
                        context.alias_list_name,
                        (SELECT list.id FROM alias_list list
                         WHERE list.name = context.alias_list_name COLLATE NOCASE LIMIT 1) AS alias_list_id,
                        context.decoder,
                        context.system_key, system.wacn, system.system_id, context.nac, context.rfss,
                        context.site, context.primary_frequency_hz, context.current_control_hz,
                        context.first_seen_ms, context.last_seen_ms, 0 AS observation_count,
                        0 AS channels, 0 AS neighbors, 0 AS bands, 0 AS patches
                    FROM receiver_context context
                    LEFT JOIN p25_system system ON system.system_key = context.system_key
                    WHERE context.guid = ? AND context.kind_code = 1
                    LIMIT 1
                    """, guid);
                site = fallback.isEmpty() ? null : fallback.getFirst();
            }

            if(site == null)
            {
                throw new StatsApiException(404, "Site not found");
            }

            site.put("site_type", "trunked");
            site.put("capabilities", siteCapabilities(p25OwnsGuid));
            List<Map<String,Object>> scopes = queryRows(connection, """
                SELECT scope.scope_token
                FROM trunked_identity_scope_context ownership
                JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
                JOIN receiver_context context ON context.id = ownership.context_id
                WHERE context.guid = ?
                ORDER BY ownership.last_seen_ms DESC
                LIMIT 1
                """, guid);

            if(!scopes.isEmpty())
            {
                site.put("scope_token", scopes.getFirst().get("scope_token"));
            }

            if(p25OwnsGuid)
            {
                site.put("protocol_code", 1);
                site.put("protocol", "P25");
                site.put("site_kind", "p25");
                Object mfid = site.get("mfid");

                if(mfid instanceof Number number)
                {
                    site.put("mfid_display", mfidDisplay(number.intValue()));
                }
            }
            else
            {
                site.put("protocol_code", currentProtocol);
                site.put("protocol", currentProtocol == 3 ? "DMR" :
                    currentProtocol == 4 ? "NXDN" : "Unknown");
                site.put("site_kind", "trunked");
            }

            return Map.of("site", site);
        });
    }

    Map<String,Object> siteChannels(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> page(querySiteChannels(connection, guid, request.limit() + 1,
            request.offset()), request));
    }

    private List<Map<String,Object>> querySiteChannels(Connection connection, String guid, int limit, int offset)
        throws SQLException
    {
        if(isTrunkedSite(connection, guid))
        {
            return queryRows(connection, """
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
                        frequency_hz = -1, frequency_hz,
                        inbound_channel_number = -1, inbound_channel_number
                    LIMIT ? OFFSET ?
                    """, System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS, guid,
                    limit, offset);
        }

        return queryRows(connection, """
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
            GROUP BY guid, CASE WHEN downlink_hz > 0
                THEN 'f:' || downlink_hz ELSE 'k:' || channel_key END
            ORDER BY coalesce(downlink_hz, 9223372036854775807), channel_key
            LIMIT ? OFFSET ?
            """, guid, guid, guid, System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS,
                limit, offset);
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
                SELECT context.id AS context_id, context.alias_list_name, scope.scope_id, scope.scope_token,
                    scope.protocol_code, scope.identity_domain_code,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE trunked.system_id END AS system_id
                FROM receiver_context context
                JOIN trunked_identity_scope_context ownership ON ownership.context_id = context.id
                JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                WHERE context.guid = ?
                """, guid), "Site not found");
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT identity_id AS talkgroup_id, identity_kind_code,
                    identity_kind_code AS target_kind_code, SUM(call_count) AS call_count,
                    SUM(recorded_count) AS recorded_count, SUM(streamed_count) AS streamed_count,
                    SUM(encrypted_count) AS encrypted_count, MAX(bucket_start_ms) AS last_active_ms
                FROM call_identity_bucket
                WHERE context_id = ? AND bucket_start_ms >= ?
                  AND identity_role_code = ? AND identity_kind_code IN (1, 3)
                GROUP BY identity_kind_code, identity_id
                ORDER BY call_count DESC, identity_id
                LIMIT ?
                """, context.get("context_id"), fromMilliseconds, IDENTITY_ROLE_DESTINATION, request.limit());

            for(Map<String,Object> row: rows)
            {
                row.put("system_key", context.get("system_key"));
                row.put("wacn", context.get("wacn"));
                row.put("system_id", context.get("system_id"));
                row.put("scope_token", context.get("scope_token"));
                row.put("protocol_code", context.get("protocol_code"));
                row.put("identity_domain_code", context.get("identity_domain_code"));
                row.put("alias_list_name", context.get("alias_list_name"));
            }

            enrichScopeTalkgroups(connection, rows, "talkgroup_id", "alias_");
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", range.label());
            response.put("from_ms", fromMilliseconds);
            response.put("to_ms", System.currentTimeMillis());
            response.put("bucket_ms", HOUR_MILLISECONDS);
            response.put("metric_start_ms", scopeMetricStartedAt(connection));
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
            requireCurrentP25Site(connection, guid);
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
        return read(connection -> page(querySiteNeighbors(connection, guid, request.limit() + 1,
            request.offset()), request));
    }

    private List<Map<String,Object>> querySiteNeighbors(Connection connection, String guid, int limit, int offset)
        throws SQLException
    {
        long currentSince = System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS;

        if(isTrunkedSite(connection, guid))
        {
            return queryRows(connection, "WITH " + FIRST_CONFIGURATION_CHANNEL_CTE + """
                    SELECT 'SITE' AS entry_type, neighbor.variant_code, neighbor.identity_domain_code,
                        NULLIF(neighbor.network_id, -1) AS network_id,
                        NULLIF(neighbor.system_id, -1) AS system_id,
                        NULLIF(neighbor.site_id, -1) AS site_id,
                        NULLIF(neighbor.site_id, -1) AS site,
                        NULLIF(neighbor.channel_number, -1) AS channel_number,
                        NULLIF(neighbor.frequency_hz, -1) AS frequency_hz,
                        NULLIF(neighbor.frequency_hz, -1) AS downlink_hz, neighbor.status_flags,
                        neighbor.first_seen_ms, neighbor.last_seen_ms, neighbor.observation_count,
                        NULLIF(trim(resolved.channel_name), '') AS neighbor_name,
                        config.configured_site AS neighbor_configured_site,
                        config.configured_name AS neighbor_configured_name,
                        resolved.guid AS neighbor_guid,
                        CASE WHEN neighbor.last_seen_ms >= ? THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM trunked_site_neighbor_summary neighbor
                    JOIN trunked_site_snapshot source ON source.guid = neighbor.guid
                    LEFT JOIN trunked_site_snapshot resolved ON resolved.guid = (
                        SELECT min(candidate.guid)
                        FROM trunked_site_snapshot candidate
                        WHERE candidate.guid <> neighbor.guid
                          AND candidate.protocol_code = source.protocol_code
                          AND candidate.variant_code = neighbor.variant_code
                          AND candidate.identity_domain_code = neighbor.identity_domain_code
                          AND neighbor.variant_code <> 0
                          AND neighbor.identity_domain_code <> 0
                          AND neighbor.site_id <> -1
                          AND (neighbor.network_id <> -1 OR neighbor.system_id <> -1)
                          AND coalesce(candidate.network_id, -1) = neighbor.network_id
                          AND coalesce(candidate.system_id, -1) = neighbor.system_id
                          AND coalesce(candidate.site_id, -1) = neighbor.site_id
                          AND NULLIF(trim(source.configured_system), '') IS NOT NULL
                          AND lower(trim(candidate.configured_system)) = lower(trim(source.configured_system))
                          AND EXISTS (
                              SELECT 1
                              FROM receiver_context candidate_context
                              JOIN trunked_identity_scope_context candidate_ownership
                                ON candidate_ownership.context_id = candidate_context.id
                              JOIN trunked_identity_scope candidate_scope
                                ON candidate_scope.scope_id = candidate_ownership.scope_id
                              WHERE candidate_context.guid = candidate.guid
                                AND candidate_context.kind_code = 1
                                AND candidate_context.protocol_code = candidate.protocol_code
                                AND candidate_scope.protocol_code = candidate.protocol_code
                          )
                        HAVING count(*) = 1
                    )
                    LEFT JOIN first_configuration_channel config ON config.radres_guid = resolved.guid
                    WHERE neighbor.guid = ?
                    ORDER BY neighbor.identity_domain_code,
                        neighbor.network_id = -1, neighbor.network_id,
                        neighbor.system_id = -1, neighbor.system_id,
                        neighbor.site_id = -1, neighbor.site_id,
                        neighbor.channel_number = -1, neighbor.channel_number,
                        neighbor.variant_code, neighbor.frequency_hz = -1, neighbor.frequency_hz
                    LIMIT ? OFFSET ?
                    """, currentSince, guid, limit, offset);
        }

        return queryRows(connection, "WITH " + FIRST_CONFIGURATION_CHANNEL_CTE + """
                , combined AS (
                    SELECT 0 AS entry_order,
                        CASE WHEN current.neighbor_key IS NULL THEN 1 ELSE 0 END AS current_order,
                        'SITE' AS entry_type, source_system.wacn AS wacn, summary.neighbor_key,
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
                        nullif(trim(neighbor_site.channel_name), '') AS neighbor_name,
                        neighbor_site.guid AS neighbor_guid,
                        NULL AS band_count, NULL AS has_fdma, NULL AS has_tdma, NULL AS has_unknown,
                        CASE WHEN max(coalesce(current.confirmed_at_ms, 0), summary.last_seen_ms) >= ?
                            THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM p25_site_neighbor_summary summary
                    LEFT JOIN p25_site_neighbor current
                      ON current.guid = summary.guid AND current.neighbor_key = summary.neighbor_key
                    LEFT JOIN p25_site_snapshot source_site ON source_site.guid = summary.guid
                    LEFT JOIN p25_system source_system ON source_system.system_key = source_site.system_key
                    LEFT JOIN p25_system neighbor_system
                      ON neighbor_system.wacn = source_system.wacn
                     AND neighbor_system.system_id = coalesce(current.system_id, summary.system_id)
                    LEFT JOIN p25_site_snapshot neighbor_site
                      ON neighbor_site.system_key = neighbor_system.system_key
                     AND neighbor_site.rfss = coalesce(current.rfss, summary.rfss)
                     AND neighbor_site.site = coalesce(current.site, summary.site)
                     AND neighbor_site.guid = (
                        SELECT candidate.guid
                        FROM p25_site_snapshot candidate
                        JOIN receiver_context candidate_context ON candidate_context.guid = candidate.guid
                        JOIN trunked_identity_scope_context candidate_ownership
                          ON candidate_ownership.context_id = candidate_context.id
                        JOIN trunked_identity_scope candidate_scope
                          ON candidate_scope.scope_id = candidate_ownership.scope_id
                        WHERE candidate_scope.protocol_code = 1
                          AND candidate_scope.p25_system_key = neighbor_system.system_key
                          AND candidate.system_key = neighbor_system.system_key
                          AND candidate.rfss = coalesce(current.rfss, summary.rfss)
                          AND candidate.site = coalesce(current.site, summary.site)
                        ORDER BY candidate.last_seen_ms DESC, candidate.guid ASC
                        LIMIT 1
                     )
                    WHERE summary.guid = ?

                    UNION ALL

                    SELECT 1 AS entry_order,
                        CASE WHEN MAX(current.confirmed_at_ms) IS NULL THEN 1 ELSE 0 END AS current_order,
                        'ISSI' AS entry_type, summary.foreign_wacn AS wacn,
                        printf('%X:%03X', summary.foreign_wacn, summary.foreign_system_id) AS neighbor_key,
                        summary.foreign_system_id AS system_id, NULL AS rfss, NULL AS site, NULL AS lra,
                        NULL AS channel_descriptor, NULL AS downlink_hz, NULL AS uplink_hz,
                        'ISSI ADVERTISED' AS status, MAX(current.confirmed_at_ms) AS confirmed_at_ms,
                        MIN(summary.first_seen_ms) AS first_seen_ms, MAX(summary.last_seen_ms) AS last_seen_ms,
                        SUM(summary.observation_count) AS observation_count,
                        NULL AS neighbor_name, NULL AS neighbor_guid,
                        COUNT(*) AS band_count,
                        MAX(CASE WHEN summary.channel_type BETWEEN 0 AND 2 THEN 1 ELSE 0 END) AS has_fdma,
                        MAX(CASE WHEN summary.channel_type BETWEEN 3 AND 5 THEN 1 ELSE 0 END) AS has_tdma,
                        MAX(CASE WHEN summary.channel_type NOT BETWEEN 0 AND 5 THEN 1 ELSE 0 END) AS has_unknown,
                        CASE WHEN MAX(coalesce(current.confirmed_at_ms, 0)) >= ?
                                  OR MAX(summary.last_seen_ms) >= ?
                            THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM p25_foreign_system_band_summary summary
                    LEFT JOIN p25_foreign_system_band current
                      ON current.guid = summary.guid
                     AND current.foreign_wacn = summary.foreign_wacn
                     AND current.foreign_system_id = summary.foreign_system_id
                     AND current.band = summary.band
                    WHERE summary.guid = ?
                    GROUP BY summary.foreign_wacn, summary.foreign_system_id
                )
                SELECT entry_type, wacn, neighbor_key, system_id, rfss, site, lra, channel_descriptor,
                    downlink_hz, uplink_hz, status, confirmed_at_ms, first_seen_ms, last_seen_ms,
                    observation_count, neighbor_name,
                    config.configured_site AS neighbor_configured_site,
                    config.configured_name AS neighbor_configured_name,
                    neighbor_guid, band_count, has_fdma, has_tdma, has_unknown, state
                FROM combined
                LEFT JOIN first_configuration_channel config ON config.radres_guid = combined.neighbor_guid
                ORDER BY entry_order, current_order, system_id, rfss, site, neighbor_key
                LIMIT ? OFFSET ?
                """, currentSince, guid, currentSince, currentSince, guid,
                limit, offset);
    }

    Map<String,Object> sitePatches(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> {
            requireCurrentP25Site(connection, guid);
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

            Integer talkgroup = request.optionalIdentifier("talkgroup_id");
            Integer radio = request.optionalIdentifier("radio_id");
            String scopeToken = request.text("scope");
            String guid = request.text("guid");
            String context = request.text("context");

            if("true".equalsIgnoreCase(request.text("hide_grants")))
            {
                sql.append(" AND action <> 'GRANT'");
            }

            if(scopeToken != null)
            {
                sql.append(" AND scope.scope_token = ?");
                parameters.add(scopeToken);
            }
            if(guid != null)
            {
                sql.append(" AND activity.guid = ?");
                parameters.add(guid);
            }
            if(context != null)
            {
                sql.append(" AND context_key = ?");
                parameters.add(context);
            }
            if(talkgroup != null)
            {
                int requestedTargetKind = targetKind(request);

                if(requestedTargetKind == IDENTITY_KIND_TALKGROUP)
                {
                    sql.append("""
                         AND activity.id IN (
                             SELECT event.id
                             FROM p25_activity_event event
                             WHERE event.target_id = ? AND event.target_kind_code = ?
                             UNION
                             SELECT member.event_id
                             FROM activity_event_talkgroup_member member
                             WHERE member.talkgroup_id = ?
                         )
                        """);
                    parameters.add(talkgroup);
                    parameters.add(requestedTargetKind);
                    parameters.add(talkgroup);
                }
                else
                {
                    sql.append(" AND target_id = ? AND target_kind_code = ?");
                    parameters.add(talkgroup);
                    parameters.add(requestedTargetKind);
                }
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
            enrichActivityEncryption(rows);
            return cursorPage(rows, request.limit());
        });
    }

    Map<String,Object> conventional(StatsRequest request)
    {
        return read(connection -> page(queryConventional(connection, request, request.limit() + 1,
            request.offset()), request));
    }

    private static List<Map<String,Object>> queryConventional(Connection connection, StatsRequest request,
                                                               int limit, int offset) throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
                SELECT context.id AS context_id, context.context_key, context.guid, context.kind_code,
                    CASE WHEN context.kind_code = 10 THEN 10 ELSE context.protocol_code END AS protocol_code,
                    context.channel_name, context.alias_list_name,
                    (SELECT list.id FROM alias_list list
                     WHERE list.name = context.alias_list_name COLLATE NOCASE LIMIT 1) AS alias_list_id,
                    context.decoder,
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
            .append(", context.id, summary.frequency_hz, summary.timeslot LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        return queryRows(connection, sql.toString(), parameters.toArray());
    }

    Map<String,Object> conventionalDetail(StatsRequest request)
    {
        String contextKey = request.requiredText("context");
        return read(connection -> {
            Map<String,Object> response = new LinkedHashMap<>();
            Map<String,Object> context = first(queryRows(connection, """
                SELECT id AS context_id, context_key, guid, kind_code,
                    CASE WHEN kind_code = 10 THEN 10 ELSE protocol_code END AS protocol_code, channel_name,
                    alias_list_name,
                    (SELECT list.id FROM alias_list list
                     WHERE list.name = receiver_context.alias_list_name COLLATE NOCASE LIMIT 1) AS alias_list_id,
                    decoder, nac, primary_frequency_hz, first_seen_ms, last_seen_ms
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
        return read(connection -> {
            List<Map<String,Object>> rows = queryConventionalTalkgroups(connection, request,
                request.limit() + 1, request.offset());
            return page(rows, request);
        });
    }

    private List<Map<String,Object>> queryConventionalTalkgroups(Connection connection, StatsRequest request,
                                                                 int limit, int offset) throws SQLException
    {
        String contextKey = request.requiredText("context");
        requireDmrConventionalContext(connection, contextKey);
        StringBuilder sql = new StringBuilder("""
                SELECT context.id AS context_id, context.context_key, context.alias_list_name,
                    (SELECT list.id FROM alias_list list
                     WHERE list.name = context.alias_list_name COLLATE NOCASE LIMIT 1) AS alias_list_id,
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
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        mAliasResolver.enrichDmrTalkgroups(connection, rows, "talkgroup_id", "alias_");
        mAliasResolver.enrichDmrRadios(connection, rows, "last_source_radio_id", "last_source_alias_");
        return rows;
    }

    /**
     * Bounded conventional DMR radio summaries for exactly one receiver context.
     */
    Map<String,Object> conventionalRadios(StatsRequest request)
    {
        return read(connection -> {
            List<Map<String,Object>> rows = queryConventionalRadios(connection, request,
                request.limit() + 1, request.offset());
            return page(rows, request);
        });
    }

    private List<Map<String,Object>> queryConventionalRadios(Connection connection, StatsRequest request,
                                                             int limit, int offset) throws SQLException
    {
        String contextKey = request.requiredText("context");
        requireDmrConventionalContext(connection, contextKey);
        StringBuilder sql = new StringBuilder("""
                SELECT context.id AS context_id, context.context_key, context.alias_list_name,
                    (SELECT list.id FROM alias_list list
                     WHERE list.name = context.alias_list_name COLLATE NOCASE LIMIT 1) AS alias_list_id,
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
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        mAliasResolver.enrichDmrRadios(connection, rows, "radio_id", "alias_");
        mAliasResolver.enrichDmrTalkgroups(connection, rows, "last_talkgroup_id", "last_talkgroup_alias_");
        mAliasResolver.enrichDmrRadios(connection, rows, "last_peer_radio_id", "last_peer_alias_");
        return rows;
    }

    private static void requireDmrConventionalContext(Connection connection, String contextKey) throws SQLException
    {
        first(queryRows(connection, """
            SELECT id FROM receiver_context
            WHERE context_key = ? AND kind_code = 3 AND protocol_code = 3
            """, contextKey), "Conventional DMR context not found");
    }

    /**
     * One bounded directory row per durable trunked identity scope. P25 scopes own multiple linked site contexts;
     * DMR and NXDN scopes own exactly one configured receiver context.
     */
    private static String scopeSummarySelect()
    {
        return """
            SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                    WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                scope.scope_kind_code, scope.p25_system_key AS system_key, system.wacn,
                CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                    (SELECT trunked.system_id
                     FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                     WHERE ownership.scope_id = scope.scope_id
                     ORDER BY ownership.context_id LIMIT 1)
                END AS system_id,
                (SELECT trunked.network_id
                 FROM trunked_identity_scope_context ownership
                 JOIN receiver_context context ON context.id = ownership.context_id
                 LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                 WHERE ownership.scope_id = scope.scope_id
                 ORDER BY ownership.context_id LIMIT 1) AS network_id,
                coalesce((SELECT trunked.variant_code
                    FROM trunked_identity_scope_context ownership
                    JOIN receiver_context context ON context.id = ownership.context_id
                    LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                    WHERE ownership.scope_id = scope.scope_id
                    ORDER BY ownership.context_id LIMIT 1), 0) AS variant_code,
                CASE WHEN scope.protocol_code = 1 THEN
                    (SELECT CASE WHEN COUNT(DISTINCT lower(trim(config.system_name))) = 1
                                THEN min(trim(config.system_name)) END
                     FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     LEFT JOIN configuration_channel config ON config.radres_guid = context.guid
                     WHERE ownership.scope_id = scope.scope_id
                       AND config.system_name IS NOT NULL AND trim(config.system_name) <> '')
                ELSE
                    (SELECT coalesce(nullif(trim(trunked.configured_system), ''),
                                     nullif(trim(config.system_name), ''))
                     FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                     LEFT JOIN configuration_channel config ON config.radres_guid = context.guid
                     WHERE ownership.scope_id = scope.scope_id
                     ORDER BY ownership.context_id LIMIT 1)
                END AS configured_system,
                scope.first_seen_ms, scope.last_seen_ms,
                (SELECT COUNT(*) FROM trunked_identity_scope_context ownership
                    WHERE ownership.scope_id = scope.scope_id) AS sites,
                (SELECT COUNT(*) FROM trunked_identity_summary identity
                    WHERE identity.scope_id = scope.scope_id
                      AND identity.identity_kind_code IN (1, 3)) AS talkgroups,
                (SELECT COUNT(*) FROM trunked_identity_summary identity
                    WHERE identity.scope_id = scope.scope_id
                      AND identity.identity_kind_code = 2) AS radios,
                (SELECT COUNT(*) FROM p25_radio_affiliation affiliation
                    WHERE scope.protocol_code = 1
                      AND affiliation.system_key = scope.p25_system_key) AS affiliations,
                (SELECT group_concat(name, ', ') FROM (
                    SELECT DISTINCT coalesce(nullif(trim(context.channel_name), ''),
                                             nullif(trim(p25.channel_name), ''),
                                             nullif(trim(trunked.channel_name), '')) AS name
                    FROM trunked_identity_scope_context ownership
                    JOIN receiver_context context ON context.id = ownership.context_id
                    LEFT JOIN p25_site_snapshot p25 ON p25.guid = context.guid
                    LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                    WHERE ownership.scope_id = scope.scope_id
                    ORDER BY name)) AS site_names
            FROM trunked_identity_scope scope
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            """;
    }

    private static Map<String,Object> requireScope(Connection connection, String scopeToken) throws SQLException
    {
        return first(queryRows(connection, "WITH scoped AS (" + scopeSummarySelect() +
            ") SELECT * FROM scoped WHERE scope_token = ?", scopeToken), "System not found");
    }

    private static List<Map<String,Object>> queryScopeSites(Connection connection, long scopeId, StatsRequest request)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("WITH " + FIRST_CONFIGURATION_CHANNEL_CTE + """
            , scoped_sites AS (
                SELECT scope.scope_id, scope.scope_token, 1 AS protocol_code, 'P25' AS protocol,
                    'p25' AS site_kind, context.guid, scope.p25_system_key AS system_key,
                    system.wacn, system.system_id, NULL AS network_id, NULL AS configured_system,
                    coalesce(site.channel_name, context.channel_name) AS channel_name,
                    coalesce(context.alias_list_name, site.alias_list_name) AS alias_list_name,
                    (SELECT list.id FROM alias_list list
                     WHERE list.name = coalesce(context.alias_list_name, site.alias_list_name) COLLATE NOCASE
                     LIMIT 1) AS alias_list_id,
                    coalesce(site.decoder, context.decoder) AS decoder,
                    site.nac, site.rfss, site.site, NULL AS site_id, NULL AS ran,
                    NULL AS variant_code, NULL AS identity_domain_code,
                    coalesce(site.primary_frequency_hz, context.primary_frequency_hz) AS primary_frequency_hz,
                    coalesce(site.current_control_hz, context.current_control_hz) AS current_control_hz,
                    coalesce(site.first_seen_ms, context.first_seen_ms) AS first_seen_ms,
                    coalesce(site.last_seen_ms, context.last_seen_ms) AS last_seen_ms,
                    coalesce(site.observation_count, 0) AS observation_count,
                    (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                        THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                     FROM p25_site_channel_summary channel WHERE channel.guid = context.guid) AS channels,
                    (SELECT COUNT(*) FROM p25_site_neighbor neighbor
                     WHERE neighbor.guid = context.guid) AS neighbors,
                    (SELECT COUNT(*) FROM p25_site_frequency_band band
                     WHERE band.guid = context.guid) AS bands,
                    (SELECT COUNT(*) FROM p25_site_patch_group patch
                     WHERE patch.guid = context.guid) AS patches
                FROM trunked_identity_scope_context ownership
                JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
                JOIN receiver_context context ON context.id = ownership.context_id
                LEFT JOIN p25_site_snapshot site ON site.guid = context.guid
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                WHERE ownership.scope_id = ? AND scope.protocol_code = 1

                UNION ALL

                SELECT scope.scope_id, scope.scope_token, scope.protocol_code,
                    CASE scope.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN'
                        ELSE 'Unknown' END AS protocol,
                    'trunked' AS site_kind, context.guid, NULL AS system_key, NULL AS wacn,
                    site.system_id, site.network_id, site.configured_system,
                    coalesce(site.channel_name, context.channel_name) AS channel_name,
                    coalesce(context.alias_list_name, site.alias_list_name) AS alias_list_name,
                    (SELECT list.id FROM alias_list list
                     WHERE list.name = coalesce(context.alias_list_name, site.alias_list_name) COLLATE NOCASE
                     LIMIT 1) AS alias_list_id,
                    coalesce(site.decoder, context.decoder) AS decoder,
                    NULL AS nac, NULL AS rfss, NULL AS site, site.site_id, site.ran,
                    site.variant_code, site.identity_domain_code,
                    coalesce(site.primary_frequency_hz, context.primary_frequency_hz) AS primary_frequency_hz,
                    coalesce(site.current_control_hz, context.current_control_hz) AS current_control_hz,
                    coalesce(site.first_seen_ms, context.first_seen_ms) AS first_seen_ms,
                    coalesce(site.last_seen_ms, context.last_seen_ms) AS last_seen_ms,
                    coalesce(site.observation_count, 0) AS observation_count,
                    (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                     WHERE channel.guid = context.guid) AS channels,
                    (SELECT COUNT(*) FROM trunked_site_neighbor_summary neighbor
                     WHERE neighbor.guid = context.guid) AS neighbors,
                    0 AS bands, 0 AS patches
                FROM trunked_identity_scope_context ownership
                JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
                JOIN receiver_context context ON context.id = ownership.context_id
                LEFT JOIN trunked_site_snapshot site ON site.guid = context.guid
                WHERE ownership.scope_id = ? AND scope.protocol_code IN (3, 4)
            )
            SELECT scoped_sites.*, config.configured_site, config.configured_name
            FROM scoped_sites
            LEFT JOIN first_configuration_channel config ON config.radres_guid = scoped_sites.guid
            WHERE 1=1
            """);
        List<Object> parameters = new ArrayList<>(List.of(scopeId, scopeId));

        if(request.search() != null)
        {
            sql.append(" AND (lower(channel_name) LIKE ? OR lower(config.configured_site) LIKE ? " +
                "OR lower(config.configured_name) LIKE ? OR lower(guid) LIKE ?)");
            parameters.add(like(request.search()));
            parameters.add(like(request.search()));
            parameters.add(like(request.search()));
            parameters.add(like(request.search()));
        }

        sql.append(" ORDER BY ").append(order(request, SCOPED_SITE_SORT_COLUMNS, "last_seen"))
            .append(", guid LIMIT ? OFFSET ?");
        parameters.add(request.limit() + 1);
        parameters.add(request.offset());
        return queryRows(connection, sql.toString(), parameters.toArray());
    }

    private static String siteSelect()
    {
        return """
            SELECT site.guid, site.system_key, site.protocol, site.channel_name,
                (SELECT nullif(trim(config.site_name), '')
                 FROM configuration_channel config
                 WHERE config.radres_guid = site.guid
                 ORDER BY config.sort_order, config.id LIMIT 1) AS configured_site,
                (SELECT nullif(trim(config.name), '')
                 FROM configuration_channel config
                 WHERE config.radres_guid = site.guid
                 ORDER BY config.sort_order, config.id LIMIT 1) AS configured_name,
                site.alias_list_name,
                (SELECT list.id FROM alias_list list
                 WHERE list.name = site.alias_list_name COLLATE NOCASE LIMIT 1) AS alias_list_id,
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
                (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                    THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                    FROM p25_site_channel_summary channel WHERE channel.guid = site.guid) AS channels,
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
        return "WITH " + FIRST_CONFIGURATION_CHANNEL_CTE + """
            , candidates AS (
                SELECT site.guid, site.channel_name, site.nac, site.rfss, site.site,
                    site.current_control_hz, site.last_seen_ms AS site_last_seen_ms,
                    system.wacn, system.system_id, 1 AS protocol_code, 'P25' AS protocol,
                    'p25' AS site_kind, NULL AS configured_system, NULL AS network_id,
                    NULL AS site_id, NULL AS ran, NULL AS variant_code, NULL AS identity_domain_code
                FROM p25_site_snapshot site
                LEFT JOIN p25_system system ON system.system_key = site.system_key

                UNION ALL

                SELECT site.guid, site.channel_name, NULL AS nac, NULL AS rfss, NULL AS site,
                    site.current_control_hz, site.last_seen_ms AS site_last_seen_ms,
                    NULL AS wacn, site.system_id, site.protocol_code,
                    CASE site.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN'
                        ELSE 'Unknown' END AS protocol,
                    'trunked' AS site_kind, site.configured_system, site.network_id,
                    site.site_id, site.ran, site.variant_code, site.identity_domain_code
                FROM trunked_site_snapshot site
            ),
            ranked AS (
                SELECT candidate.*, row_number() OVER (
                    PARTITION BY candidate.guid
                    ORDER BY candidate.site_last_seen_ms DESC, candidate.protocol_code ASC
                ) AS identity_rank
                FROM candidates candidate
            )
            SELECT ranked.guid, ranked.channel_name, ranked.nac, ranked.rfss, ranked.site,
                ranked.current_control_hz, ranked.site_last_seen_ms, ranked.wacn, ranked.system_id,
                ranked.protocol_code, ranked.protocol, ranked.site_kind,
                CASE WHEN ranked.protocol_code = 1 THEN config.configured_system
                    ELSE ranked.configured_system END AS configured_system,
                config.configured_site, config.configured_name, ranked.network_id, ranked.site_id,
                ranked.ran, ranked.variant_code, ranked.identity_domain_code
            FROM ranked
            LEFT JOIN first_configuration_channel config ON config.radres_guid = ranked.guid
            WHERE ranked.identity_rank = 1
            """;
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
        capabilities.put("activity", true);
        capabilities.put("talkgroups", true);
        return Map.copyOf(capabilities);
    }

    private static Map<String,Boolean> conventionalCapabilities(boolean dmr)
    {
        Map<String,Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("info", true);
        capabilities.put("activity", true);
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
                site.channel_name,
                (SELECT nullif(trim(config.site_name), '')
                 FROM configuration_channel config
                 WHERE config.radres_guid = site.guid
                 ORDER BY config.sort_order, config.id LIMIT 1) AS configured_site,
                (SELECT nullif(trim(config.name), '')
                 FROM configuration_channel config
                 WHERE config.radres_guid = site.guid
                 ORDER BY config.sort_order, config.id LIMIT 1) AS configured_name,
                site.alias_list_name,
                (SELECT list.id FROM alias_list list
                 WHERE list.name = site.alias_list_name COLLATE NOCASE LIMIT 1) AS alias_list_id,
                site.decoder, site.network_id, site.system_id, site.site_id,
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
        int protocolCode = currentSiteProtocolCode(connection, guid);
        return protocolCode == 3 || protocolCode == 4;
    }

    private static void requireCurrentP25Site(Connection connection, String guid) throws SQLException
    {
        if(currentSiteProtocolCode(connection, guid) != 1)
        {
            throw new StatsApiException(404, "P25 site not found");
        }
    }

    private static int currentSiteProtocolCode(Connection connection, String guid) throws SQLException
    {
        return (int)scalarLong(connection, """
            SELECT coalesce(
                (SELECT CASE WHEN protocol_code IN (1, 2) THEN 1 ELSE protocol_code END
                 FROM receiver_context
                 WHERE guid = ? AND kind_code = 1
                 LIMIT 1),
                (SELECT protocol_code
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
                 LIMIT 1),
                0
            )
            """, guid, guid, guid);
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
        // This response is browser-visible.  Keep it to the one numeric value the UI uses so diagnostic exception
        // text such as last_write_error cannot expose local paths or SQL details.
        return queryRows(connection, """
            SELECT key, CAST(value AS INTEGER) AS value, updated_at_ms
            FROM logger_status
            WHERE key = 'last_successful_write_ms'
            """);
    }

    /**
     * Ranks identities observed in the compact hourly call buckets. Identity roles and kinds are deliberately
     * normalized here so the dashboard never has to infer a protocol-specific meaning from an integer ID.
     */
    private List<Map<String,Object>> topCallIdentities(Connection connection, int identityRole,
                                                       long fromTimestamp, long toTimestamp) throws SQLException
    {
        if(identityRole != IDENTITY_ROLE_DESTINATION && identityRole != IDENTITY_ROLE_SOURCE)
        {
            throw new IllegalArgumentException("Unsupported call identity role");
        }

        List<Map<String,Object>> rows = queryRows(connection, DASHBOARD_IDENTITY_ACTIVITY_SQL,
            fromTimestamp, toTimestamp, identityRole, DASHBOARD_IDENTITY_LIMIT);
        List<Map<String,Object>> p25Talkgroups = new ArrayList<>();
        List<Map<String,Object>> p25Radios = new ArrayList<>();
        List<Map<String,Object>> p25ConventionalTalkgroups = new ArrayList<>();
        List<Map<String,Object>> p25ConventionalRadios = new ArrayList<>();
        List<Map<String,Object>> dmrTalkgroups = new ArrayList<>();
        List<Map<String,Object>> dmrRadios = new ArrayList<>();
        List<Map<String,Object>> nxdnTalkgroups = new ArrayList<>();
        List<Map<String,Object>> nxdnRadios = new ArrayList<>();

        for(Map<String,Object> row: rows)
        {
            int protocolCode = (int)number(row.get("protocol_code"));
            int identityKind = (int)number(row.get("identity_kind_code"));
            boolean trunked = "TRUNKED".equals(row.get("channel_kind"));

            if(protocolCode == 1 && (identityKind == IDENTITY_KIND_TALKGROUP ||
                identityKind == IDENTITY_KIND_PATCH_GROUP))
            {
                (trunked ? p25Talkgroups : p25ConventionalTalkgroups).add(row);
            }
            else if(protocolCode == 1 && identityKind == IDENTITY_KIND_RADIO)
            {
                (trunked ? p25Radios : p25ConventionalRadios).add(row);
            }
            else if(protocolCode == 3 && (identityKind == IDENTITY_KIND_TALKGROUP ||
                identityKind == IDENTITY_KIND_PATCH_GROUP))
            {
                dmrTalkgroups.add(row);
            }
            else if(protocolCode == 3 && identityKind == IDENTITY_KIND_RADIO)
            {
                dmrRadios.add(row);
            }
            else if(protocolCode == 4 && (identityKind == IDENTITY_KIND_TALKGROUP ||
                identityKind == IDENTITY_KIND_PATCH_GROUP))
            {
                nxdnTalkgroups.add(row);
            }
            else if(protocolCode == 4 && identityKind == IDENTITY_KIND_RADIO)
            {
                nxdnRadios.add(row);
            }
        }

        mAliasResolver.enrichTalkgroups(connection, p25Talkgroups, "identity_id", "alias_");
        mAliasResolver.enrichRadios(connection, p25Radios, "identity_id", "alias_");
        mAliasResolver.enrichP25ConventionalTalkgroups(connection, p25ConventionalTalkgroups,
            "identity_id", "alias_");
        mAliasResolver.enrichP25ConventionalRadios(connection, p25ConventionalRadios,
            "identity_id", "alias_");
        mAliasResolver.enrichDmrTalkgroups(connection, dmrTalkgroups, "identity_id", "alias_");
        mAliasResolver.enrichDmrRadios(connection, dmrRadios, "identity_id", "alias_");
        mAliasResolver.enrichNxdnTalkgroups(connection, nxdnTalkgroups, "identity_id", "alias_");
        mAliasResolver.enrichNxdnRadios(connection, nxdnRadios, "identity_id", "alias_");

        for(Map<String,Object> row: rows)
        {
            int protocolCode = (int)number(row.get("protocol_code"));
            int identityKind = (int)number(row.get("identity_kind_code"));
            boolean trunked = "TRUNKED".equals(row.get("channel_kind"));
            boolean conventionalDmr = protocolCode == 3 && !trunked;
            boolean hasTrunkedScope = trunked && row.get("scope_token") instanceof String token && !token.isBlank();
            String detailView = null;

            if(hasTrunkedScope && (identityKind == IDENTITY_KIND_TALKGROUP ||
                identityKind == IDENTITY_KIND_PATCH_GROUP))
            {
                detailView = "talkgroup";
            }
            else if(hasTrunkedScope && identityKind == IDENTITY_KIND_RADIO)
            {
                detailView = "radio";
            }
            else if(conventionalDmr && identityKind == IDENTITY_KIND_TALKGROUP)
            {
                detailView = "conventional-talkgroups";
            }
            else if(conventionalDmr && identityKind == IDENTITY_KIND_RADIO)
            {
                detailView = "conventional-radios";
            }

            row.put("identity_role_code", identityRole);
            row.put("identity_role", identityRole == IDENTITY_ROLE_DESTINATION ? "Destination" : "Source");
            row.put("identity_detail_view", detailView);
            row.put("identity_detail_available", detailView != null ? 1 : 0);
        }

        return rows;
    }

    /**
     * Returns the newest receiver contexts across both topologies. Trunked rows carry their decoded site metadata,
     * while conventional rows remain linkable even before they have produced their first summary bucket.
     */
    private static List<Map<String,Object>> recentReceivers(Connection connection) throws SQLException
    {
        return queryRows(connection, "WITH " + FIRST_CONFIGURATION_CHANNEL_CTE + """
            , candidates AS (
                SELECT coalesce(context.context_key, 'site:' || site.guid) AS receiver_key,
                    context.id AS context_id, context.context_key, site.guid,
                    1 AS protocol_code, 'P25' AS protocol, 'TRUNKED' AS channel_kind,
                    coalesce(context.channel_name, site.channel_name) AS channel_name,
                    coalesce(context.alias_list_name, site.alias_list_name) AS alias_list_name,
                    coalesce(context.decoder, site.decoder) AS decoder, NULL AS configured_system,
                    system.wacn, system.system_id, NULL AS network_id,
                    coalesce(site.nac, context.nac) AS nac, site.rfss, site.site,
                    NULL AS site_id, NULL AS ran, NULL AS variant_code, NULL AS identity_domain_code,
                    coalesce(context.primary_frequency_hz, site.primary_frequency_hz) AS primary_frequency_hz,
                    coalesce(context.current_control_hz, site.current_control_hz) AS current_control_hz,
                    min(site.first_seen_ms, coalesce(context.first_seen_ms, site.first_seen_ms)) AS first_seen_ms,
                    max(site.last_seen_ms, coalesce(context.last_seen_ms, site.last_seen_ms)) AS last_seen_ms,
                    site.last_seen_ms AS metadata_last_seen_ms, site.observation_count,
                    (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                        THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                        FROM p25_site_channel_summary channel WHERE channel.guid = site.guid) AS channels,
                    (SELECT COUNT(*) FROM p25_site_neighbor neighbor WHERE neighbor.guid = site.guid) AS neighbors,
                    1 AS detail_available
                FROM p25_site_snapshot site
                LEFT JOIN p25_system system ON system.system_key = site.system_key
                LEFT JOIN receiver_context context ON context.guid = site.guid AND context.kind_code = 1

                UNION ALL

                SELECT coalesce(context.context_key, 'site:' || site.guid) AS receiver_key,
                    context.id AS context_id, context.context_key, site.guid, site.protocol_code,
                    CASE site.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    'TRUNKED' AS channel_kind, coalesce(context.channel_name, site.channel_name) AS channel_name,
                    coalesce(context.alias_list_name, site.alias_list_name) AS alias_list_name,
                    coalesce(context.decoder, site.decoder) AS decoder, site.configured_system,
                    NULL AS wacn, site.system_id, site.network_id, NULL AS nac, NULL AS rfss, NULL AS site,
                    site.site_id, site.ran, site.variant_code, site.identity_domain_code,
                    coalesce(context.primary_frequency_hz, site.primary_frequency_hz) AS primary_frequency_hz,
                    coalesce(context.current_control_hz, site.current_control_hz) AS current_control_hz,
                    min(site.first_seen_ms, coalesce(context.first_seen_ms, site.first_seen_ms)) AS first_seen_ms,
                    max(site.last_seen_ms, coalesce(context.last_seen_ms, site.last_seen_ms)) AS last_seen_ms,
                    site.last_seen_ms AS metadata_last_seen_ms, site.observation_count,
                    (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                        WHERE channel.guid = site.guid) AS channels,
                    (SELECT COUNT(*) FROM trunked_site_neighbor_summary neighbor
                        WHERE neighbor.guid = site.guid) AS neighbors,
                    1 AS detail_available
                FROM trunked_site_snapshot site
                LEFT JOIN receiver_context context ON context.guid = site.guid AND context.kind_code = 1

                UNION ALL

                SELECT context.context_key AS receiver_key, context.id AS context_id, context.context_key,
                    context.guid,
                    CASE
                        WHEN context.kind_code = 10 THEN 10
                        WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                        ELSE coalesce(context.protocol_code, 0)
                    END AS protocol_code,
                    CASE
                        WHEN context.kind_code = 10 THEN 'NBFM'
                        WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 'P25'
                        WHEN context.protocol_code = 3 THEN 'DMR'
                        WHEN context.protocol_code = 4 THEN 'NXDN'
                        ELSE 'Unknown'
                    END AS protocol,
                    'CONVENTIONAL' AS channel_kind, context.channel_name, context.alias_list_name,
                    context.decoder, NULL AS configured_system, NULL AS wacn, NULL AS system_id,
                    NULL AS network_id, context.nac, NULL AS rfss, NULL AS site, NULL AS site_id, NULL AS ran,
                    NULL AS variant_code, NULL AS identity_domain_code, context.primary_frequency_hz,
                    context.current_control_hz, context.first_seen_ms, context.last_seen_ms,
                    context.last_seen_ms AS metadata_last_seen_ms, NULL AS observation_count,
                    0 AS channels, 0 AS neighbors, 1 AS detail_available
                FROM receiver_context context
                WHERE context.kind_code <> 1

                UNION ALL

                SELECT context.context_key AS receiver_key, context.id AS context_id, context.context_key,
                    context.guid,
                    CASE WHEN context.protocol_code IN (1, 2) THEN 1
                        ELSE coalesce(context.protocol_code, 0) END AS protocol_code,
                    CASE
                        WHEN context.protocol_code IN (1, 2) THEN 'P25'
                        WHEN context.protocol_code = 3 THEN 'DMR'
                        WHEN context.protocol_code = 4 THEN 'NXDN'
                        ELSE 'Unknown'
                    END AS protocol,
                    'TRUNKED' AS channel_kind, context.channel_name, context.alias_list_name,
                    context.decoder, NULL AS configured_system, NULL AS wacn, NULL AS system_id,
                    NULL AS network_id, context.nac, context.rfss, context.site, NULL AS site_id, NULL AS ran,
                    NULL AS variant_code, NULL AS identity_domain_code, context.primary_frequency_hz,
                    context.current_control_hz, context.first_seen_ms, context.last_seen_ms,
                    context.last_seen_ms AS metadata_last_seen_ms, NULL AS observation_count,
                    0 AS channels, 0 AS neighbors, 0 AS detail_available
                FROM receiver_context context
                WHERE context.kind_code = 1
                  AND NOT EXISTS (
                    SELECT 1 FROM p25_site_snapshot site WHERE site.guid = context.guid
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM trunked_site_snapshot site WHERE site.guid = context.guid
                  )
            ),
            ranked AS (
                SELECT candidates.*, row_number() OVER (
                    PARTITION BY receiver_key ORDER BY metadata_last_seen_ms DESC, protocol_code ASC
                ) AS receiver_rank
                FROM candidates
            )
            SELECT context_id, context_key, guid, protocol_code, protocol, channel_kind, channel_name,
                CASE WHEN channel_kind = 'TRUNKED' THEN config.configured_site END AS configured_site,
                CASE WHEN channel_kind = 'TRUNKED' THEN config.configured_name END AS configured_name,
                alias_list_name, decoder, ranked.configured_system, wacn,
                system_id, network_id, nac, rfss, site,
                site_id, ran, variant_code, identity_domain_code, primary_frequency_hz,
                current_control_hz, first_seen_ms, last_seen_ms, observation_count, channels, neighbors,
                detail_available
            FROM ranked
            LEFT JOIN first_configuration_channel config ON config.radres_guid = ranked.guid
            WHERE receiver_rank = 1
            ORDER BY last_seen_ms DESC, protocol_code, channel_kind,
                lower(coalesce(channel_name, context_key, guid))
            LIMIT 20
            """);
    }

    /**
     * Ranks all call-producing receiver contexts using the existing compact hourly buckets. The query is bounded to
     * 24 hours and uses the time-leading trunked and conventional bucket indexes; detailed event history is never
     * consulted.
     */
    private static Map<String,Object> sourceActivity24Hours(Connection connection) throws SQLException
    {
        long currentHour = Math.floorDiv(System.currentTimeMillis(), HOUR_MILLISECONDS) * HOUR_MILLISECONDS;
        long firstHour = currentHour - (DASHBOARD_HOURS - 1L) * HOUR_MILLISECONDS;
        long nextHour = currentHour + HOUR_MILLISECONDS;
        List<Map<String,Object>> rows = queryRows(connection, DASHBOARD_SOURCE_ACTIVITY_SQL,
            firstHour, nextHour, firstHour, nextHour);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("from_ms", firstHour);
        result.put("to_ms", System.currentTimeMillis());
        result.put("rows", rows);
        return result;
    }

    /**
     * Builds a flat, protocol-neutral call series. P25 phase 1 and phase 2 share the P25 protocol row while channel
     * topology remains an independent dimension. A collected source is zero-filled for every hour; an unsupported
     * source uses null values and explicit NOT_COLLECTED coverage so it can never be mistaken for a quiet receiver.
     */
    private static Map<String,Object> callActivity(Connection connection) throws SQLException
    {
        long now = System.currentTimeMillis();
        long currentHour = Math.floorDiv(now, HOUR_MILLISECONDS) * HOUR_MILLISECONDS;
        long firstHour = currentHour - (DASHBOARD_HOURS - 1L) * HOUR_MILLISECONDS;
        long nextHour = currentHour + HOUR_MILLISECONDS;
        long p25OutputMetricStart = p25CallOutputMetricsStartedAt(connection);
        long allModeMetricStart = allModeCallOutputMetricsStartedAt(connection);
        List<Map<String,Object>> stored = queryRows(connection, DASHBOARD_CALL_ACTIVITY_SQL,
            firstHour, nextHour, firstHour, nextHour);
        Map<String,Map<Long,Map<String,Object>>> storedByGroupAndTime = new LinkedHashMap<>();

        for(Map<String,Object> row: stored)
        {
            if(row.get("time_ms") instanceof Number timestamp &&
                row.get("protocol_code") instanceof Number protocolCode &&
                row.get("channel_kind") instanceof String channelKind)
            {
                String key = callActivityKey(protocolCode.intValue(), channelKind);
                storedByGroupAndTime.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .put(timestamp.longValue(), row);
            }
        }

        Map<String,Object> totals = new LinkedHashMap<>();

        for(String field: CALL_ACTIVITY_FIELDS)
        {
            totals.put(field, 0L);
        }

        List<Map<String,Object>> breakdown = new ArrayList<>(CALL_ACTIVITY_GROUPS.size());
        List<Map<String,Object>> series = new ArrayList<>(DASHBOARD_HOURS * CALL_ACTIVITY_GROUPS.size());

        for(CallActivityGroup group: CALL_ACTIVITY_GROUPS)
        {
            Map<String,Object> coverage = callActivityCoverage(group, p25OutputMetricStart,
                allModeMetricStart, firstHour, now);
            Map<String,Object> groupTotals = new LinkedHashMap<>();

            for(String field: CALL_ACTIVITY_FIELDS)
            {
                groupTotals.put(field, "NOT_COLLECTED".equals(coverage.get(field)) ? null : 0L);
            }

            Map<Long,Map<String,Object>> storedByTime =
                storedByGroupAndTime.getOrDefault(group.key(), Map.of());

            for(long timestamp = firstHour; timestamp <= currentHour; timestamp += HOUR_MILLISECONDS)
            {
                Map<String,Object> storedRow = storedByTime.get(timestamp);
                Map<String,Object> point = new LinkedHashMap<>();
                point.put("time_ms", timestamp);
                point.put("protocol_code", group.protocolCode());
                point.put("protocol", group.protocol());
                point.put("channel_kind", group.channelKind());

                for(String field: CALL_ACTIVITY_FIELDS)
                {
                    String fieldCoverage = String.valueOf(coverage.get(field));
                    long metricStart = callActivityMetricStartedAt(group, field, p25OutputMetricStart,
                        allModeMetricStart);
                    boolean beforeMetricCollection = metricStart > 0 &&
                        timestamp + HOUR_MILLISECONDS <= metricStart;
                    Object value;

                    if("NOT_COLLECTED".equals(fieldCoverage) || beforeMetricCollection)
                    {
                        value = null;
                    }
                    else
                    {
                        value = storedRow != null ? number(storedRow.get(field)) : 0L;
                        groupTotals.compute(field,
                            (key, total) -> ((Number)total).longValue() + ((Number)value).longValue());
                        totals.compute(field,
                            (key, total) -> ((Number)total).longValue() + ((Number)value).longValue());
                    }

                    point.put(field, value);
                }

                series.add(point);
            }

            Map<String,Object> groupRow = new LinkedHashMap<>();
            groupRow.put("protocol_code", group.protocolCode());
            groupRow.put("protocol", group.protocol());
            groupRow.put("channel_kind", group.channelKind());
            groupRow.put("coverage", coverage);
            groupRow.put("totals", groupTotals);
            breakdown.add(groupRow);
        }

        List<Map<String,Object>> coverage = new ArrayList<>(breakdown.size());

        for(Map<String,Object> group: breakdown)
        {
            Map<String,Object> groupCoverage = mapValue(group, "coverage");
            Map<String,Object> coverageRow = new LinkedHashMap<>();
            coverageRow.put("protocol_code", group.get("protocol_code"));
            coverageRow.put("protocol", group.get("protocol"));
            coverageRow.put("channel_kind", group.get("channel_kind"));
            coverageRow.putAll(groupCoverage);
            boolean noneCollected = CALL_ACTIVITY_FIELDS.stream()
                .allMatch(field -> "NOT_COLLECTED".equals(groupCoverage.get(field)));
            boolean fullyCollected = CALL_ACTIVITY_FIELDS.stream()
                .allMatch(field -> "COLLECTED".equals(groupCoverage.get(field)));
            coverageRow.put("status", fullyCollected ? "COLLECTED" :
                noneCollected ? "NOT_COLLECTED" : "PARTIAL");
            coverage.add(coverageRow);
        }

        Map<String,Object> metricCoverage = new LinkedHashMap<>();

        for(String field: CALL_ACTIVITY_FIELDS)
        {
            boolean anyCollected = breakdown.stream()
                .map(row -> mapValue(row, "coverage"))
                .anyMatch(row -> !"NOT_COLLECTED".equals(row.get(field)));
            boolean allCollected = breakdown.stream()
                .map(row -> mapValue(row, "coverage"))
                .allMatch(row -> "COLLECTED".equals(row.get(field)));
            metricCoverage.put(field,
                allCollected ? "COLLECTED" : anyCollected ? "PARTIAL" : "NOT_COLLECTED");

            if(!anyCollected)
            {
                totals.put(field, null);
            }
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("range", "24h");
        result.put("from_ms", firstHour);
        result.put("to_ms", now);
        result.put("bucket_ms", HOUR_MILLISECONDS);
        result.put("metric_start_ms", allModeMetricStart);
        result.put("coverage", coverage);
        result.put("metricCoverage", metricCoverage);
        result.put("totals", totals);
        result.put("breakdown", breakdown);
        result.put("series", series);
        return result;
    }

    private static Map<String,Object> callActivityCoverage(CallActivityGroup group, long p25OutputMetricStart,
                                                            long allModeMetricStart, long firstHour, long now)
    {
        Map<String,Object> coverage = new LinkedHashMap<>();

        for(String field: CALL_ACTIVITY_FIELDS)
        {
            long metricStart = callActivityMetricStartedAt(group, field, p25OutputMetricStart,
                allModeMetricStart);
            String status;

            if(!group.collected() || metricStart < 0 || metricStart > now)
            {
                status = "NOT_COLLECTED";
            }
            else if(metricStart > firstHour)
            {
                status = "PARTIAL";
            }
            else
            {
                status = "COLLECTED";
            }

            coverage.put(field, status);
        }

        return coverage;
    }

    /**
     * Returns zero when a metric has existed for the full retained history, a positive collection start for a metric
     * introduced in a later schema, or -1 when collection is not available. DMR and NXDN trunked call starts were
     * introduced with the all-mode output metrics, so earlier empty buckets must not be presented as observed zeros.
     */
    private static long callActivityMetricStartedAt(CallActivityGroup group, String field,
                                                     long p25OutputMetricStart, long allModeMetricStart)
    {
        if(!group.collected())
        {
            return -1;
        }

        if("recorded_count".equals(field) || "streamed_count".equals(field))
        {
            long startedAt = group.protocolCode() == 1 && "TRUNKED".equals(group.channelKind()) ?
                p25OutputMetricStart : allModeMetricStart;
            return startedAt > 0 ? startedAt : -1;
        }

        if("encrypted_count".equals(field) && "CONVENTIONAL".equals(group.channelKind()))
        {
            if(group.protocolCode() == 10)
            {
                return -1;
            }

            return allModeMetricStart > 0 ? allModeMetricStart : -1;
        }

        if((group.protocolCode() == 3 || group.protocolCode() == 4) &&
            "TRUNKED".equals(group.channelKind()))
        {
            return allModeMetricStart > 0 ? allModeMetricStart : -1;
        }

        if(group.protocolCode() == 4 && "CONVENTIONAL".equals(group.channelKind()))
        {
            return allModeMetricStart > 0 ? allModeMetricStart : -1;
        }

        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> mapValue(Map<String,Object> row, String key)
    {
        return (Map<String,Object>)row.get(key);
    }

    private static String callActivityKey(int protocolCode, String channelKind)
    {
        return protocolCode + ":" + channelKind;
    }

    private static long p25CallOutputMetricsStartedAt(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COALESCE((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?), 0)
            """, P25ActivityLogSchema.CALL_OUTPUT_METRICS_STARTED_AT_KEY);
    }

    private static long allModeCallOutputMetricsStartedAt(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COALESCE((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?), 0)
            """, P25ActivityLogSchema.ALL_MODE_CALL_OUTPUT_METRICS_STARTED_AT_KEY);
    }

    private static long scopeMetricStartedAt(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COALESCE((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?), 0)
            """, P25ActivityLogSchema.TRUNKED_IDENTITY_METRICS_STARTED_AT_KEY);
    }

    private static int targetKind(StatsRequest request)
    {
        String kind = request.text("kind");

        if(kind == null || "talkgroup".equalsIgnoreCase(kind))
        {
            return IDENTITY_KIND_TALKGROUP;
        }

        if("patch".equalsIgnoreCase(kind))
        {
            return IDENTITY_KIND_PATCH_GROUP;
        }

        throw new StatsApiException(400, "kind must be talkgroup or patch");
    }

    private static boolean booleanParameter(StatsRequest request, String name, boolean defaultValue)
    {
        String value = request.text(name);

        if(value == null)
        {
            return defaultValue;
        }
        else if("true".equalsIgnoreCase(value))
        {
            return true;
        }
        else if("false".equalsIgnoreCase(value))
        {
            return false;
        }

        throw new StatsApiException(400, name + " must be true or false");
    }

    private static Map<String,Boolean> systemCapabilities(int protocolCode)
    {
        boolean p25 = protocolCode == 1;
        Map<String,Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("talkgroups", true);
        capabilities.put("radios", true);
        capabilities.put("activity", true);
        capabilities.put("talker_aliases", protocolCode == 1 || protocolCode == 3 || protocolCode == 4);
        capabilities.put("current_affiliations", p25);
        capabilities.put("patches", p25);
        return Map.copyOf(capabilities);
    }

    private static Map<String,Boolean> talkgroupCapabilities(int protocolCode, int identityKind)
    {
        Map<String,Boolean> capabilities = new LinkedHashMap<>(systemCapabilities(protocolCode));
        capabilities.put("current_affiliations", protocolCode == 1 && identityKind == IDENTITY_KIND_TALKGROUP);
        return Map.copyOf(capabilities);
    }

    private void enrichScopeTalkgroups(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                                       String prefix) throws SQLException
    {
        List<Map<String,Object>> p25 = protocolRows(rows, 1);
        List<Map<String,Object>> dmr = protocolRows(rows, 3);
        List<Map<String,Object>> nxdn = protocolRows(rows, 4);
        mAliasResolver.enrichTalkgroups(connection, p25, identifierColumn, prefix);
        mAliasResolver.enrichDmrTalkgroups(connection, dmr, identifierColumn, prefix);
        mAliasResolver.enrichNxdnTalkgroups(connection, nxdn, identifierColumn, prefix);
    }

    private void enrichScopeRadios(Connection connection, List<Map<String,Object>> rows, String identifierColumn,
                                   String prefix) throws SQLException
    {
        List<Map<String,Object>> p25 = protocolRows(rows, 1);
        List<Map<String,Object>> dmr = protocolRows(rows, 3);
        List<Map<String,Object>> nxdn = protocolRows(rows, 4);
        mAliasResolver.enrichRadios(connection, p25, identifierColumn, prefix);
        mAliasResolver.enrichDmrRadios(connection, dmr, identifierColumn, prefix);
        mAliasResolver.enrichNxdnRadios(connection, nxdn, identifierColumn, prefix);
    }

    private static List<Map<String,Object>> protocolRows(List<Map<String,Object>> rows, int protocolCode)
    {
        return rows.stream().filter(row -> number(row.get("protocol_code")) == protocolCode).toList();
    }

    private static List<Map<String,Object>> systemActionCounts(Connection connection, long scopeId)
        throws SQLException
    {
        String sums = TALKGROUP_SIGNALING_FIELDS.stream()
            .map(field -> "SUM(bucket." + field + ") AS " + field)
            .collect(java.util.stream.Collectors.joining(", "));
        List<Map<String,Object>> totals = queryRows(connection, """
            SELECT %s
            FROM p25_site_activity_bucket bucket
            JOIN trunked_identity_scope_context ownership ON ownership.context_id = bucket.context_id
            WHERE ownership.scope_id = ?
            """.formatted(sums), scopeId);

        if(totals.isEmpty())
        {
            return List.of();
        }

        Map<String,Object> row = totals.getFirst();
        List<Map<String,Object>> result = new ArrayList<>();

        for(String field: TALKGROUP_SIGNALING_FIELDS)
        {
            long count = number(row.get(field));

            if(count > 0)
            {
                result.add(Map.of("action", field.replace("_count", "").toUpperCase(), "count", count));
            }
        }

        result.sort((left, right) -> Long.compare(number(right.get("count")), number(left.get("count"))));
        return result;
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * Adds protocol-aware encryption names to detailed activity without persisting duplicate display strings.
     */
    private static void enrichActivityEncryption(List<Map<String,Object>> rows)
    {
        for(Map<String,Object> row: rows)
        {
            if(number(row.get("encrypted")) == 0)
            {
                continue;
            }

            VoiceEncryptionProtocol protocol =
                VoiceEncryptionProtocol.fromProtocolName(String.valueOf(row.get("protocol")));
            Integer algorithm = integer(row.get("encryption_algorithm_id"));
            Integer key = integer(row.get("encryption_key_id"));
            row.put("encryption_display", VoiceEncryptionDisplay.format(protocol, algorithm, key));
            row.put("encryption_full_display", VoiceEncryptionDisplay.formatFull(protocol, algorithm, key));
        }
    }

    /**
     * Shared identity summaries retain only the latest raw algorithm and key IDs. Translate them at read time so the
     * Java GUI and web interface use the same protocol-specific vocabulary.
     */
    private static void enrichSummaryEncryption(List<Map<String,Object>> rows)
    {
        for(Map<String,Object> row: rows)
        {
            if(number(row.get("encrypted_count")) == 0)
            {
                continue;
            }

            VoiceEncryptionProtocol protocol =
                VoiceEncryptionProtocol.fromProtocolName(String.valueOf(row.get("protocol")));
            Integer algorithm = integer(row.get("last_encryption_algorithm_id"));
            row.put("last_encryption_algorithm_display",
                VoiceEncryptionDisplay.compactAlgorithm(protocol, algorithm));
            row.put("last_encryption_algorithm_name",
                VoiceEncryptionDisplay.fullAlgorithm(protocol, algorithm));
        }
    }

    private static Integer integer(Object value)
    {
        return value instanceof Number number ? number.intValue() : null;
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

    private <T> T readSnapshot(Query<T> query)
    {
        try(Connection connection = openReadOnly())
        {
            connection.setAutoCommit(false);

            try
            {
                T result = query.execute(connection);
                connection.rollback();
                return result;
            }
            catch(RuntimeException | IOException | SQLException e)
            {
                try
                {
                    connection.rollback();
                }
                catch(SQLException rollbackError)
                {
                    e.addSuppressed(rollbackError);
                }

                throw e;
            }
        }
        catch(StatsApiException e)
        {
            throw e;
        }
        catch(IOException | SQLException e)
        {
            mLog.warn("Stats Server snapshot database query failed", e);
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
               AND EXISTS (
                     SELECT 1
                     FROM trunked_identity_scope assigned_scope
                     JOIN trunked_identity_scope_context assigned_ownership
                       ON assigned_ownership.scope_id = assigned_scope.scope_id
                     JOIN receiver_context assigned_context
                       ON assigned_context.id = assigned_ownership.context_id
                     JOIN p25_site_snapshot assigned
                       ON assigned.guid = assigned_context.guid
                      AND assigned.system_key = assigned_scope.p25_system_key
                     WHERE assigned_scope.protocol_code = 1
                       AND assigned_scope.p25_system_key = system.system_key
                       AND assigned.alias_list_name = identifier.alias_list_name
                       AND trim(assigned.alias_list_name) <> '')
             ORDER BY CASE WHEN identifier.ranged = 0 THEN 1 ELSE 0 END DESC,
                 alias.id
             LIMIT 1)
            """.formatted(aliasColumn, identifierTable, identifierColumn, identifierColumn).strip();
    }

    /**
     * Protocol-neutral identity sort expression. P25 resolves across every alias list assigned to its linked sites;
     * DMR and NXDN resolve only against the exact alias list assigned to their one owning context.
     */
    private static String scopeAliasSortExpression(String identifierTable, String identifierColumn,
                                                   String aliasColumn)
    {
        if(!"alias_talkgroup".equals(identifierTable) && !"alias_radio".equals(identifierTable) ||
            !identifierColumn.matches(
                "(?:summary|affiliation|relationship)\\.(?:identity_id|talkgroup_id|radio_id|last_counterpart_id)") ||
            !"name".equals(aliasColumn) && !"group_name".equals(aliasColumn))
        {
            throw new IllegalArgumentException("Unsupported scoped alias sort expression");
        }

        String protocol = "alias_talkgroup".equals(identifierTable) ?
            "CASE scope.protocol_code WHEN 1 THEN 'APCO25' WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' END" :
            "CASE scope.protocol_code WHEN 1 THEN 'APCO25' WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' END";
        return """
            (SELECT lower(alias.%s)
             FROM %s identifier
             JOIN alias ON alias.id = identifier.alias_id
             WHERE (identifier.protocol = %s OR
                    (scope.protocol_code = 1 AND identifier.protocol = 'APCO25_PHASE2'))
               AND ((identifier.ranged <> 0 AND %s BETWEEN identifier.min_value AND identifier.max_value)
                 OR (identifier.ranged = 0 AND identifier.value = %s))
               AND (
                 (scope.protocol_code = 1
                   AND EXISTS (
                         SELECT 1
                         FROM trunked_identity_scope_context assigned_ownership
                         JOIN receiver_context assigned_context
                           ON assigned_context.id = assigned_ownership.context_id
                         JOIN p25_site_snapshot assigned
                           ON assigned.guid = assigned_context.guid
                          AND assigned.system_key = scope.p25_system_key
                         WHERE assigned_ownership.scope_id = scope.scope_id
                           AND assigned.alias_list_name = identifier.alias_list_name
                           AND trim(assigned.alias_list_name) <> ''))
                 OR
                 (scope.protocol_code IN (3, 4)
                   AND identifier.alias_list_name = (
                     SELECT context.alias_list_name
                     FROM trunked_identity_scope_context ownership
                     JOIN receiver_context context ON context.id = ownership.context_id
                     WHERE ownership.scope_id = scope.scope_id
                       AND context.alias_list_name IS NOT NULL
                       AND trim(context.alias_list_name) <> ''
                     ORDER BY ownership.context_id
                     LIMIT 1))
               )
             ORDER BY CASE WHEN identifier.ranged = 0 THEN 1 ELSE 0 END DESC,
                 alias.id
             LIMIT 1)
            """.formatted(aliasColumn, identifierTable, protocol, identifierColumn, identifierColumn).strip();
    }

    private static String order(StatsRequest request, Map<String,String> columns, String defaultSort)
    {
        String column = columns.getOrDefault(request.sort(defaultSort), columns.get(defaultSort));
        return column + (request.descending() ? " DESC" : " ASC");
    }

    private static void addPageParameters(List<Object> parameters, StatsRequest request)
    {
        addLimitOffset(parameters, request.limit() + 1, request.offset());
    }

    private static void addLimitOffset(List<Object> parameters, int limit, int offset)
    {
        parameters.add(limit);
        parameters.add(offset);
    }

    private static void addIdentifierSearch(StringBuilder sql, List<Object> parameters, String search,
                                            String column)
    {
        if(search != null)
        {
            sql.append(" AND (CAST(").append(column).append(" AS TEXT) LIKE ? OR ")
                .append("(scope.protocol_code = 4 AND scope.identity_domain_code = 2 ")
                .append("AND printf('%02d-%04d', ((").append(column).append(" >> 11) & 31), (")
                .append(column).append(" & 2047)) LIKE ?))");
            String like = like(search);
            parameters.add(like);
            parameters.add(like);
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

    private record CallActivityGroup(int protocolCode, String protocol, String channelKind, boolean collected)
    {
        private String key()
        {
            return callActivityKey(protocolCode, channelKind);
        }
    }

    private interface Query<T>
    {
        T execute(Connection connection) throws IOException, SQLException;
    }
}
