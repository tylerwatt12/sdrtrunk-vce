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

import static io.github.dsheirer.stats.StatsSqlRows.queryRows;

import io.github.dsheirer.module.decode.p25.reference.Vendor;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideBand;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideProfile;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityEligibility;
import io.github.dsheirer.preference.encryption.VoiceEncryptionDisplay;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
    private static final int DASHBOARD_SOURCE_LIMIT = 100;
    static final int MAXIMUM_PATCH_GROUP_PAGE = 100;
    static final int MAXIMUM_PATCH_MEMBERS_PER_GROUP = 32;
    static final int MAXIMUM_PATCH_MEMBER_ROWS = 512;
    static final int MAXIMUM_SYSTEM_ACTIVITY_CONTEXTS = 200;
    static final int MAXIMUM_SYSTEM_DIRECTORY_WITH_SITE_PREVIEW = 25;
    static final int MAXIMUM_SYSTEM_DIRECTORY_SITE_PREVIEW = 25;
    private static final int IDENTITY_ROLE_DESTINATION = P25ActivityLogSchema.IDENTITY_ROLE_DESTINATION;
    private static final int IDENTITY_ROLE_SOURCE = P25ActivityLogSchema.IDENTITY_ROLE_SOURCE;
    private static final int IDENTITY_KIND_CHANNEL_OR_UNKNOWN =
        P25ActivityLogSchema.IDENTITY_KIND_CHANNEL_OR_UNKNOWN;
    private static final int IDENTITY_KIND_TALKGROUP = P25ActivityLogSchema.IDENTITY_KIND_TALKGROUP;
    private static final int IDENTITY_KIND_RADIO = P25ActivityLogSchema.IDENTITY_KIND_RADIO;
    private static final int IDENTITY_KIND_PATCH_GROUP = P25ActivityLogSchema.IDENTITY_KIND_PATCH_GROUP;
    private static final String MATCHING_CONFIGURATION_GUID_CTE = """
        matching_configuration_guid AS MATERIALIZED (
            SELECT DISTINCT radres_guid
            FROM configuration_channel
            WHERE radres_guid IS NOT NULL
              AND lower(coalesce(system_name, '') || ' ' || coalesce(site_name, '') || ' ' ||
                  coalesce(name, '')) LIKE ?
        )
        """;
    static final String DASHBOARD_CALL_ACTIVITY_SQL = """
        SELECT bucket.bucket_start_ms AS time_ms,
            scope.protocol_code,
            'TRUNKED' AS channel_kind,
            SUM(bucket.logical_call_count) AS logical_call_count,
            SUM(bucket.recorded_output_count) AS recorded_logical_call_count,
            SUM(bucket.streamed_output_count) AS stream_submitted_logical_call_count,
            SUM(bucket.encrypted_logical_call_count) AS encrypted_logical_call_count
        FROM trunked_logical_call_bucket AS bucket INDEXED BY idx_trunked_logical_call_bucket_time
        JOIN trunked_identity_scope scope ON scope.scope_id = bucket.scope_id
        WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
        GROUP BY bucket.bucket_start_ms, scope.protocol_code

        UNION ALL

        SELECT bucket.bucket_start_ms AS time_ms,
            CASE
                WHEN context.kind_code = 10 THEN CASE WHEN context.protocol_code = 11 THEN 11 ELSE 10 END
                WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                ELSE coalesce(context.protocol_code, 0)
            END AS protocol_code,
            'CONVENTIONAL' AS channel_kind,
            SUM(bucket.call_count) AS logical_call_count,
            SUM(bucket.recorded_count) AS recorded_logical_call_count,
            SUM(bucket.streamed_count) AS stream_submitted_logical_call_count,
            SUM(bucket.encrypted_count) AS encrypted_logical_call_count
        FROM receiver_context context
        JOIN conventional_activity_bucket AS bucket INDEXED BY idx_conventional_bucket_dashboard_time
            ON bucket.context_id = context.id
        WHERE context.kind_code <> 1
          AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
        GROUP BY bucket.bucket_start_ms,
            CASE
                WHEN context.kind_code = 10 THEN CASE WHEN context.protocol_code = 11 THEN 11 ELSE 10 END
                WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                ELSE coalesce(context.protocol_code, 0)
            END
        ORDER BY time_ms, protocol_code, channel_kind
        """;
    static final String DASHBOARD_SOURCE_ACTIVITY_SQL = """
        WITH source_activity AS (
            SELECT context.id AS context_id, context.context_key, context.guid, config.configuration_id,
                CASE
                    WHEN context.kind_code = 10 THEN CASE WHEN context.protocol_code = 11 THEN 11 ELSE 10 END
                    WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                    ELSE coalesce(context.protocol_code, 0)
                END AS protocol_code,
                'CONVENTIONAL' AS channel_kind, context.channel_name, context.decoder,
                context.primary_frequency_hz, context.current_control_hz, context.system_key,
                system.wacn, system.system_id, context.rfss, context.site,
                nullif(trim(config.system_name), '') AS configured_system,
                nullif(trim(config.site_name), '') AS configured_site,
                nullif(trim(config.name), '') AS configured_name,
                NULL AS network_id, NULL AS site_id, NULL AS ran,
                SUM(bucket.call_count) AS logical_call_count,
                SUM(bucket.recorded_count) AS recorded_logical_call_count,
                SUM(bucket.streamed_count) AS stream_submitted_logical_call_count,
                SUM(bucket.encrypted_count) AS encrypted_logical_call_count
            FROM receiver_context context
            JOIN conventional_activity_bucket AS bucket INDEXED BY idx_conventional_bucket_dashboard_time
                ON bucket.context_id = context.id
            JOIN configuration_channel config
              ON config.channel_kind = 'CONVENTIONAL'
             AND context.context_key = 'CONFIGURATION:' || config.configuration_id
            LEFT JOIN p25_system system ON system.system_key = context.system_key
            WHERE context.kind_code <> 1
              AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
            GROUP BY context.id, context.context_key, context.guid,
                CASE
                    WHEN context.kind_code = 10 THEN CASE WHEN context.protocol_code = 11 THEN 11 ELSE 10 END
                    WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                    ELSE coalesce(context.protocol_code, 0)
                END,
                context.channel_name, context.decoder, context.primary_frequency_hz,
                context.current_control_hz, context.system_key, system.wacn, system.system_id,
                context.rfss, context.site, config.configuration_id, config.system_name,
                config.site_name, config.name
        )
        SELECT source_activity.*, CASE protocol_code
                WHEN 1 THEN 'P25'
                WHEN 3 THEN 'DMR'
                WHEN 4 THEN 'NXDN'
                WHEN 10 THEN 'NBFM'
                WHEN 11 THEN 'AM'
                ELSE 'Unknown'
            END AS protocol,
            SUM(logical_call_count) OVER () AS total_logical_call_count
        FROM source_activity
        WHERE logical_call_count > 0
        ORDER BY logical_call_count DESC, protocol_code, channel_kind,
            lower(coalesce(channel_name, context_key))
        """;
    static final String DASHBOARD_IDENTITY_ACTIVITY_SQL = """
        WITH identity_activity AS (
            SELECT NULL AS context_id, NULL AS context_key, NULL AS guid, NULL AS configuration_id,
                scope.protocol_code,
                CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                    WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                'TRUNKED' AS channel_kind, NULL AS channel_name,
                NULL AS configured_site, NULL AS configured_name,
                %s AS alias_list_name, NULL AS decoder,
                NULL AS primary_frequency_hz, NULL AS current_control_hz,
                scope.p25_system_key AS system_key, system.wacn, system.system_id,
                NULL AS rfss, NULL AS site, NULL AS configured_system,
                NULL AS network_id, NULL AS site_id, NULL AS ran, NULL AS variant_code,
                scope.identity_domain_code, scope.scope_token,
                bucket.identity_kind_code, bucket.identity_id,
                CASE bucket.identity_kind_code WHEN 1 THEN 'Talkgroup' WHEN 2 THEN 'Radio'
                    WHEN 3 THEN 'Patch Group' ELSE 'Channel / Unknown' END AS identity_kind,
                SUM(bucket.logical_call_count) AS logical_call_count,
                SUM(bucket.encrypted_logical_call_count) AS encrypted_logical_call_count,
                SUM(bucket.recorded_output_count) AS recorded_logical_call_count,
                SUM(bucket.streamed_output_count) AS stream_submitted_logical_call_count,
                MAX(bucket.bucket_start_ms) AS last_active_ms,
                MAX(summary.last_talker_alias) AS last_talker_alias,
                MAX(summary.last_talker_alias_seen_ms) AS last_talker_alias_seen_ms
            FROM trunked_logical_call_identity_bucket bucket
                INDEXED BY idx_trunked_logical_identity_dashboard_time
            JOIN trunked_identity_scope scope ON scope.scope_id = bucket.scope_id
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            LEFT JOIN trunked_identity_summary summary
              ON bucket.identity_role_code = 2 AND bucket.identity_kind_code = 2
             AND summary.scope_id = bucket.scope_id AND summary.identity_kind_code = 2
             AND summary.identity_id = bucket.identity_id
            WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
              AND bucket.identity_role_code = ?
            GROUP BY bucket.scope_id, bucket.identity_kind_code, bucket.identity_id

            UNION ALL

            SELECT bucket.context_id, context.context_key, context.guid, config.configuration_id,
                CASE WHEN context.kind_code = 10 THEN
                    CASE WHEN context.protocol_code = 11 THEN 11 ELSE 10 END
                    WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                    ELSE coalesce(context.protocol_code, 0) END AS protocol_code,
                CASE WHEN context.kind_code = 10 AND context.protocol_code = 11 THEN 'AM'
                    WHEN context.kind_code = 10 THEN 'NBFM'
                    WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 'P25'
                    WHEN context.protocol_code = 3 THEN 'DMR'
                    WHEN context.protocol_code = 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                'CONVENTIONAL' AS channel_kind, context.channel_name,
                nullif(trim(config.site_name), '') AS configured_site,
                nullif(trim(config.name), '') AS configured_name, context.alias_list_name,
                context.decoder, context.primary_frequency_hz, context.current_control_hz,
                context.system_key, system.wacn, system.system_id, context.rfss, context.site,
                nullif(trim(config.system_name), '') AS configured_system,
                NULL AS network_id, NULL AS site_id, NULL AS ran,
                NULL AS variant_code, 0 AS identity_domain_code, NULL AS scope_token,
                bucket.identity_kind_code, bucket.identity_id,
                CASE bucket.identity_kind_code WHEN 1 THEN 'Talkgroup' WHEN 2 THEN 'Radio'
                    WHEN 3 THEN 'Patch Group' ELSE 'Channel / Unknown' END AS identity_kind,
                SUM(bucket.call_count) AS logical_call_count,
                SUM(bucket.encrypted_count) AS encrypted_logical_call_count,
                SUM(bucket.recorded_count) AS recorded_logical_call_count,
                SUM(bucket.streamed_count) AS stream_submitted_logical_call_count,
                MAX(bucket.bucket_start_ms) AS last_active_ms,
                NULL AS last_talker_alias, NULL AS last_talker_alias_seen_ms
            FROM conventional_call_identity_bucket bucket
                INDEXED BY idx_conventional_call_identity_dashboard_time
            JOIN receiver_context context ON context.id = bucket.context_id
            JOIN configuration_channel config
              ON config.channel_kind = 'CONVENTIONAL'
             AND context.context_key = 'CONFIGURATION:' || config.configuration_id
            LEFT JOIN p25_system system ON system.system_key = context.system_key
            WHERE context.kind_code <> 1
              AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
              AND bucket.identity_role_code = ?
            GROUP BY bucket.context_id, config.configuration_id, config.system_name,
                config.site_name, config.name,
                bucket.identity_kind_code, bucket.identity_id
        )
        SELECT * FROM identity_activity
        ORDER BY logical_call_count DESC, last_active_ms DESC, protocol_code, channel_kind,
            identity_kind_code, identity_id
        LIMIT ?
        """.formatted(uniqueScopeAliasListExpression());
    private static final List<ActivityAction> DASHBOARD_ACTIVITY_ACTIONS = List.of(
        new ActivityAction("ACKNOWLEDGE", 1, "acknowledge_count"),
        new ActivityAction("ACTIVE", 2, "active_count"),
        new ActivityAction("BUSY", 3, "busy_count"),
        new ActivityAction("CHECK", 5, "check_count"),
        new ActivityAction("CHECK_ACK", 6, "check_ack_count"),
        new ActivityAction("DATA", 8, "data_count"),
        new ActivityAction("DENIAL", 9, "denial_count"),
        new ActivityAction("EMERGENCY", 10, "emergency_count"),
        new ActivityAction("GPS", 11, "gps_count"),
        new ActivityAction("GRANT", 12, "grant_count"),
        new ActivityAction("JOIN", 13, "join_count"),
        new ActivityAction("LOGOUT", 14, "logout_count"),
        new ActivityAction("PAGE", 15, "page_count"),
        new ActivityAction("PATCH", 16, "patch_count"),
        new ActivityAction("PATCH_CANCEL", 17, "patch_cancel_count"),
        new ActivityAction("PATCH_CREATE", 18, "patch_create_count"),
        new ActivityAction("QUEUED", 19, "queued_count"),
        new ActivityAction("REGISTER", 20, "register_count"),
        new ActivityAction("REQUEST", 21, "request_count"),
        new ActivityAction("STATUS", 22, "status_count"),
        new ActivityAction("UNKNOWN", 23, "unknown_count")
    );
    private static final Map<String,ActivityAction> DASHBOARD_ACTIVITY_ACTION_BY_NAME = activityActionMap();
    private static final String TRUNKED_ACTIVITY_ACTION_SQL = activityActionAggregateSql(
        "trunked_signaling_activity_bucket", "idx_trunked_signaling_activity_time");
    private static final String CONVENTIONAL_ACTIVITY_ACTION_SQL = activityActionAggregateSql(
        "conventional_activity_bucket", "idx_conventional_bucket_dashboard_time");
    private static final String ACTIVITY_PROJECTION_SQL = """
        SELECT activity.id, activity.context_id, activity.context_key, activity.guid,
            activity.observed_at_ms, activity.channel_kind,
            activity.channel_kind_code,
            CASE WHEN activity.protocol_code = 11 THEN 'AM' ELSE activity.protocol END AS protocol,
            activity.action, activity.event_type,
            activity.source_radio_id, activity.target_id, activity.target_kind_code, activity.target_kind,
            activity.frequency_hz, activity.lcn, activity.timeslot, activity.encrypted,
            activity.encryption_algorithm_id, activity.encryption_key_id, activity.resolved_channel_name,
            activity.resolved_alias_list_name,
            coalesce(activity.resolved_alias_list_name, trunked.alias_list_name) AS alias_list_name,
            scope.scope_token, scope.identity_domain_code,
            coalesce(scope.protocol_code, activity.protocol_code) AS protocol_code,
            coalesce(site_config.configuration_id, conventional_config.configuration_id) AS configuration_id,
            activity.resolved_system_key AS system_key, activity.resolved_wacn AS wacn,
            activity.resolved_system_id AS system_id, activity.resolved_nac, activity.resolved_rfss,
            activity.resolved_site
        """;
    private static final String ACTIVITY_RELATED_JOINS_SQL = """
        LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = activity.guid
        LEFT JOIN trunked_identity_scope_context ownership ON ownership.context_id = activity.context_id
        LEFT JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
        LEFT JOIN configuration_channel site_config
          ON activity.channel_kind_code = 1 AND site_config.channel_kind = 'TRUNKED'
         AND site_config.radres_guid = activity.guid
        LEFT JOIN configuration_channel conventional_config
          ON activity.channel_kind_code <> 1 AND conventional_config.channel_kind = 'CONVENTIONAL'
         AND activity.context_key = 'CONFIGURATION:' || conventional_config.configuration_id
        """;
    static final String ACTIVITY_SELECT_SQL = ACTIVITY_PROJECTION_SQL + """
        FROM p25_activity_event_resolved activity
        """ + ACTIVITY_RELATED_JOINS_SQL + """
        WHERE 1 = 1
        """;
    static final String ACTIVITY_ORDER_SQL =
        " ORDER BY activity.observed_at_ms DESC, activity.id DESC LIMIT ?";
    private static final List<String> CALL_ACTIVITY_FIELDS = List.of(
        "logical_call_count", "recorded_logical_call_count", "stream_submitted_logical_call_count",
        "encrypted_logical_call_count"
    );
    private static final List<CallActivityGroup> CALL_ACTIVITY_GROUPS = List.of(
        new CallActivityGroup(1, "P25", "TRUNKED", true),
        new CallActivityGroup(1, "P25", "CONVENTIONAL", true),
        new CallActivityGroup(3, "DMR", "TRUNKED", true),
        new CallActivityGroup(3, "DMR", "CONVENTIONAL", true),
        new CallActivityGroup(4, "NXDN", "TRUNKED", true),
        new CallActivityGroup(4, "NXDN", "CONVENTIONAL", true),
        new CallActivityGroup(10, "NBFM", "CONVENTIONAL", true),
        new CallActivityGroup(11, "AM", "CONVENTIONAL", true)
    );
    private static final List<String> TALKGROUP_ACTIVITY_FIELDS = List.of(
        "acknowledge_observation_count", "active_observation_count", "busy_observation_count",
        "check_observation_count", "check_ack_observation_count", "continue_observation_count",
        "data_observation_count", "denial_observation_count", "emergency_observation_count",
        "gps_observation_count", "join_observation_count", "logout_observation_count",
        "page_observation_count", "patch_observation_count", "patch_cancel_observation_count",
        "patch_create_observation_count", "queued_observation_count", "register_observation_count",
        "request_observation_count", "status_observation_count", "unknown_observation_count",
        "grant_observation_count", "logical_call_count", "encrypted_logical_call_count",
        "recorded_logical_call_count", "stream_submitted_logical_call_count"
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
    private static final String TRUNKED_IDENTITY_OBSERVATION_PROJECTION_SQL =
        TALKGROUP_SIGNALING_FIELDS.stream()
            .map(field -> "summary." + field + " AS " + observationCountField(field))
            .collect(java.util.stream.Collectors.joining(",\n                    "));
    private static final String TRUNKED_IDENTITY_METRIC_PROJECTION_SQL = """
        summary.logical_call_count,
                    summary.source_logical_call_count,
                    summary.target_logical_call_count,
                    summary.encrypted_logical_call_count,
                    summary.recorded_output_count AS recorded_logical_call_count,
                    summary.streamed_output_count AS stream_submitted_logical_call_count,
                    %s,
                    %s AS signaling_observation_count
        """.formatted(TRUNKED_IDENTITY_OBSERVATION_PROJECTION_SQL, TALKGROUP_SIGNALING_COUNT_SQL).strip();
    private static final String TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL = """
        summary.identity_kind_code, summary.identity_id,
                    summary.p25_identity_state_code, summary.p25_home_wacn,
                    summary.p25_home_system_id, summary.p25_home_talkgroup_id,
                    summary.first_seen_ms, summary.last_seen_ms,
                    %s,
                    summary.last_counterpart_kind_code, summary.last_counterpart_id,
                    summary.last_encryption_algorithm_id, summary.last_encryption_key_id,
                    summary.last_talker_alias, summary.last_talker_alias_seen_ms
        """.formatted(TRUNKED_IDENTITY_METRIC_PROJECTION_SQL).strip();
    private static final String TRUNKED_RELATIONSHIP_OBSERVATION_PROJECTION_SQL =
        TALKGROUP_SIGNALING_FIELDS.stream()
            .map(field -> "relationship." + field + " AS " + observationCountField(field))
            .collect(java.util.stream.Collectors.joining(",\n                    "));
    private static final String TRUNKED_RELATIONSHIP_METRIC_PROJECTION_SQL = """
        relationship.logical_call_count,
                    relationship.encrypted_logical_call_count,
                    relationship.recorded_output_count AS recorded_logical_call_count,
                    relationship.streamed_output_count AS stream_submitted_logical_call_count,
                    %s
        """.formatted(TRUNKED_RELATIONSHIP_OBSERVATION_PROJECTION_SQL).strip();
    private static final String CONVENTIONAL_ACTIVITY_OBSERVATION_PROJECTION_SQL =
        TALKGROUP_SIGNALING_FIELDS.stream()
            .map(field -> "summary." + field + " AS " + observationCountField(field))
            .collect(java.util.stream.Collectors.joining(",\n                    "));
    private static final String CONVENTIONAL_ACTIVITY_PUBLIC_PROJECTION_SQL = """
        summary.context_id, summary.frequency_hz, summary.timeslot,
                    summary.first_seen_ms, summary.last_seen_ms,
                    summary.call_count AS logical_call_count,
                    summary.encrypted_count AS encrypted_logical_call_count,
                    summary.recorded_count AS recorded_logical_call_count,
                    summary.streamed_count AS stream_submitted_logical_call_count,
                    summary.last_event_type_code,
                    %s
        """.formatted(CONVENTIONAL_ACTIVITY_OBSERVATION_PROJECTION_SQL).strip();
    private static final String CURRENT_RELATIONSHIP_AFFILIATION_SQL =
        "relationship.target_kind_code = 1 AND affiliation.radio_id IS NOT NULL " +
            "AND affiliation.talkgroup_id = relationship.talkgroup_id";
    private static final String RADIO_SITE_SORT_SQL = "CASE WHEN presence.context_id IS NULL THEN NULL " +
        "WHEN scope.protocol_code = 1 THEN printf('%03d:%03d', " +
        "coalesce(presence_p25.rfss, presence_context.rfss, -1), " +
        "coalesce(presence_p25.site, presence_context.site, -1)) " +
        "ELSE printf('%010d', coalesce(presence_trunked.site_id, -1)) END || char(0) || " +
        "lower(coalesce(nullif(trim(presence_config.site_name), ''), " +
        "nullif(trim(presence_config.name), ''), " +
        "presence_context.channel_name, presence_context.guid, ''))";
    private static final Map<String,String> SYSTEM_SORT_COLUMNS = Map.ofEntries(
        Map.entry("wacn", "wacn"),
        Map.entry("system_id", "system_id"),
        Map.entry("site_names", "lower(site_names)"),
        Map.entry("sites", "sites"),
        Map.entry("talkgroups", "talkgroups"),
        Map.entry("radios", "radios"),
        Map.entry("affiliated_radios", "affiliated_radios"),
        Map.entry("first_seen", "first_seen_ms"),
        Map.entry("last_seen", "last_seen_ms")
    );
    private static final Map<String,String> SCOPED_SITE_SORT_COLUMNS = Map.ofEntries(
        Map.entry("system", "scope_token"),
        Map.entry("rfss", "rfss"),
        Map.entry("site", "site_id"),
        Map.entry("name", "lower(coalesce(nullif(trim(config.name), ''), nullif(trim(config.site_name), ''), " +
            "p25.channel_name, trunked.channel_name, context.channel_name))"),
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
        Map.entry("logical_call_count", "summary.logical_call_count"),
        Map.entry("recorded_logical_call_count", "summary.recorded_output_count"),
        Map.entry("stream_submitted_logical_call_count", "summary.streamed_output_count"),
        Map.entry("grant_observation_count", "summary.grant_count"),
        Map.entry("join_observation_count", "summary.join_count"),
        Map.entry("signaling_observation_count", "signaling_observation_count"),
        Map.entry("encrypted_logical_call_count", "summary.encrypted_logical_call_count"),
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
        Map.entry("logical_call_count", "summary.logical_call_count"),
        Map.entry("grant_observation_count", "summary.grant_count"),
        Map.entry("encrypted_logical_call_count", "summary.encrypted_logical_call_count"),
        Map.entry("affiliated_talkgroup", scopeAliasSortExpression("alias_talkgroup",
            "affiliation.talkgroup_id", "name")),
        Map.entry("affiliated", "affiliation.radio_id IS NOT NULL"),
        Map.entry("affiliation_confirmed", "affiliation.confirmed_at_ms"),
        Map.entry("site", RADIO_SITE_SORT_SQL),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> TALKER_ALIAS_SORT_COLUMNS = Map.ofEntries(
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
        Map.entry("logical_call_count", "summary.logical_call_count"),
        Map.entry("grant_observation_count", "summary.grant_count"),
        Map.entry("encrypted_logical_call_count", "summary.encrypted_logical_call_count"),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> RELATIONSHIP_SORT_COLUMNS = Map.ofEntries(
        Map.entry("radio", "relationship.radio_id"),
        Map.entry("radio_alias", scopeAliasSortExpression("alias_radio", "relationship.radio_id", "name")),
        Map.entry("talker_alias", "lower(radio.last_talker_alias)"),
        Map.entry("talkgroup", "relationship.talkgroup_id"),
        Map.entry("talkgroup_alias",
            scopeAliasSortExpression("alias_talkgroup", "relationship.talkgroup_id", "name")),
        Map.entry("logical_call_count", "relationship.logical_call_count"),
        Map.entry("grant_observation_count", "relationship.grant_count"),
        Map.entry("encrypted_logical_call_count", "relationship.encrypted_logical_call_count"),
        Map.entry("affiliated", CURRENT_RELATIONSHIP_AFFILIATION_SQL),
        Map.entry("site", RADIO_SITE_SORT_SQL),
        Map.entry("first_seen", "relationship.first_seen_ms"),
        Map.entry("last_seen", "relationship.last_seen_ms")
    );
    private static final Map<String,String> CONVENTIONAL_SORT_COLUMNS = Map.ofEntries(
        Map.entry("name", "lower(configured.configured_name)"),
        Map.entry("protocol", "lower(configured.decoder)"),
        Map.entry("decoder", "lower(configured.decoder)"),
        Map.entry("frequency", "frequency_hz"),
        Map.entry("nac", "configured.nac"),
        Map.entry("logical_call_count", "logical_call_count"),
        Map.entry("event", "last_event_type_code"),
        Map.entry("first_seen", "activity_first_seen_ms"),
        Map.entry("last_seen", "activity_last_seen_ms")
    );
    private static final Map<String,String> DMR_CONVENTIONAL_TALKGROUP_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.talkgroup_id"),
        Map.entry("talkgroup", "summary.talkgroup_id"),
        Map.entry("alias", dmrAliasSortExpression("alias_talkgroup", "summary.talkgroup_id", "name")),
        Map.entry("name", dmrAliasSortExpression("alias_talkgroup", "summary.talkgroup_id", "name")),
        Map.entry("frequency", "summary.frequency_hz"),
        Map.entry("slot", "summary.timeslot"),
        Map.entry("logical_call_count", "summary.call_count"),
        Map.entry("encrypted_logical_call_count", "summary.encrypted_count"),
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
        Map.entry("logical_call_count", "summary.call_count"),
        Map.entry("source_logical_call_count", "summary.source_call_count"),
        Map.entry("target_logical_call_count", "summary.target_call_count"),
        Map.entry("group_logical_call_count", "summary.group_call_count"),
        Map.entry("private_logical_call_count", "summary.private_call_count"),
        Map.entry("encrypted_logical_call_count", "summary.encrypted_count"),
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
        Map.entry("logical_call_count", "logical_call_count"),
        Map.entry("recorded_logical_call_count", "recorded_logical_call_count"),
        Map.entry("stream_submitted_logical_call_count", "stream_submitted_logical_call_count"),
        Map.entry("encrypted_logical_call_count", "encrypted_logical_call_count"),
        Map.entry("grant_observation_count", "grant_observation_count"),
        Map.entry("join_observation_count", "join_observation_count"),
        Map.entry("emergency_observation_count", "emergency_observation_count"),
        Map.entry("register_observation_count", "register_observation_count"),
        Map.entry("first_seen", "first_seen_ms"),
        Map.entry("last_seen", "last_seen_ms")
    );

    private final UserPreferences mUserPreferences;
    private final Path mDatabasePath;
    private final P25BandplanOverrideRegistry mP25BandplanOverrides;
    private final StatsAliasResolver mAliasResolver = new StatsAliasResolver();
    private final StatsAliasCatalog mAliasCatalog = new StatsAliasCatalog(mAliasResolver);
    private final WebConfiguredEntityRepository mConfiguredEntities = new WebConfiguredEntityRepository();

    StatsWebDatabase(UserPreferences userPreferences)
    {
        this(userPreferences, SdrTrunkDatabasePath.getDatabasePath(userPreferences),
            userPreferences.getP25BandplanOverrideRegistry());
    }

    StatsWebDatabase(UserPreferences userPreferences, Path databasePath)
    {
        this(userPreferences, databasePath,
            new P25BandplanOverrideRegistry(new ApplicationSettingsStore(databasePath)));
    }

    private StatsWebDatabase(UserPreferences userPreferences, Path databasePath,
                             P25BandplanOverrideRegistry p25BandplanOverrides)
    {
        mUserPreferences = userPreferences;
        mDatabasePath = databasePath;
        mP25BandplanOverrides = p25BandplanOverrides;
    }

    /**
     * Loads one complete navigation generation for the live web adapter.  This method is called only by the
     * catalog's low-priority refresh worker; receiver callbacks consume the returned immutable snapshot and never
     * access SQLite.
     */
    WebEntityNavigationCatalog.Snapshot webEntityNavigationSnapshot()
    {
        return readSnapshot(connection -> {
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT configured.*, scope.scope_token, scope.protocol_code AS scope_protocol_code,
                    scope.identity_domain_code AS scope_identity_domain_code,
                    scope.alias_list_id AS scope_alias_list_id
                FROM (
                """ + WebConfiguredEntityRepository.CONFIGURED_CHANNEL_SELECT + """
                ) configured
                LEFT JOIN trunked_identity_scope_context ownership
                    ON ownership.context_id = configured.context_id
                LEFT JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
                ORDER BY configured.configuration_row_id, scope.scope_id
                """);
            Map<Long,WebConfiguredEntityRepository.ConfiguredChannel> configuredByRow = new LinkedHashMap<>();
            Map<Long,List<Map<String,Object>>> exactScopesByRow = new LinkedHashMap<>();

            for(Map<String,Object> row: rows)
            {
                WebConfiguredEntityRepository.ConfiguredChannel configured =
                    WebConfiguredEntityRepository.configuredChannel(row);
                configuredByRow.putIfAbsent(configured.rowId(), configured);
                Long scopeProtocolCode = row.get("scope_protocol_code") instanceof Number value ?
                    value.longValue() : null;
                Long scopeAliasListId = row.get("scope_alias_list_id") instanceof Number value ?
                    value.longValue() : null;
                boolean exactProtocol = scopeProtocolCode != null &&
                    scopeProtocolCode.intValue() == configured.protocolCode();
                boolean exactP25AliasList = configured.protocolCode() != StatsApiProtocol.P25.databaseCode() ||
                    configured.aliasListId() != null && configured.aliasListId().equals(scopeAliasListId);
                String scopeToken = textValue(row.get("scope_token"));

                if(configured.channelKind() == WebConfiguredEntityRepository.ChannelKind.TRUNKED &&
                    exactProtocol && exactP25AliasList && !scopeToken.isBlank())
                {
                    exactScopesByRow.computeIfAbsent(configured.rowId(), ignored -> new ArrayList<>()).add(row);
                }
            }

            List<WebEntityNavigationCatalog.Channel> channels = new ArrayList<>(configuredByRow.size());

            for(WebConfiguredEntityRepository.ConfiguredChannel configured: configuredByRow.values())
            {
                WebEntityRef.KeyRef channelRef = configured.channelKind() ==
                    WebConfiguredEntityRepository.ChannelKind.TRUNKED ? WebEntityRef.site(configured.guid()) :
                    WebEntityRef.conventional(configured.configurationId());
                List<Map<String,Object>> exactScopes = exactScopesByRow.getOrDefault(configured.rowId(), List.of());

                if(exactScopes.size() > 1)
                {
                    throw new StatsApiException(409, "configuration_scope_conflict",
                        "Configured channel has more than one exact learned system scope");
                }

                WebEntityRef.KeyRef systemRef = null;
                int identityDomainCode = 0;

                if(!exactScopes.isEmpty())
                {
                    Map<String,Object> exactScope = exactScopes.getFirst();
                    systemRef = WebEntityRef.system(textValue(exactScope.get("scope_token")));
                    identityDomainCode = (int)number(exactScope.get("scope_identity_domain_code"));
                }

                channels.add(new WebEntityNavigationCatalog.Channel(configured.configurationId(),
                    configured.guid(), channelRef, systemRef, configured.protocolCode(), identityDomainCode));
            }

            return WebEntityNavigationCatalog.Snapshot.of(channels);
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
                    WebConfiguredEntityRepository.ConfiguredChannel configured =
                        mConfiguredEntities.requireSite(connection, guid);
                    Map<String,Object> metadata = exportSiteMetadata(connection, configured);
                    rows = querySiteChannels(connection, configured, queryLimit, 0);
                    addExportMetadata(rows, metadata);
                    fileScope = exportLabel(metadata, "site_name", "site_guid");
                }
                case "site-neighbors" ->
                {
                    String guid = request.requiredText("guid");
                    WebConfiguredEntityRepository.ConfiguredChannel configured =
                        mConfiguredEntities.requireSite(connection, guid);
                    Map<String,Object> metadata = exportSiteMetadata(connection, configured);
                    rows = querySiteNeighbors(connection, configured, queryLimit, 0);
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
                    fileScope = request.requiredText("configuration_id");
                }
                case "conventional-radios" ->
                {
                    rows = queryConventionalRadios(connection, request, queryLimit, 0);
                    fileScope = request.requiredText("configuration_id");
                }
                case "signal-health" ->
                {
                    rows = querySignalHealthExport(connection, queryLimit);
                    fileScope = "all-sites";
                }
                case "site-quality" ->
                {
                    String guid = request.requiredText("guid");
                    WebConfiguredEntityRepository.ConfiguredChannel configured =
                        mConfiguredEntities.requireSite(connection, guid);
                    Map<String,Object> site = configuredSiteReadModel(connection, configured);
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

    Map<String,Object> aliasLists(StatsRequest request)
    {
        return readSnapshot(connection -> mAliasCatalog.aliasLists(connection, request));
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
                        summary.first_seen_ms, summary.last_seen_ms, summary.logical_call_count,
                        summary.recorded_output_count AS recorded_logical_call_count,
                        summary.streamed_output_count AS stream_submitted_logical_call_count,
                        summary.encrypted_logical_call_count,
                        summary.grant_count AS grant_observation_count,
                        summary.join_count AS join_observation_count,
                        summary.emergency_count AS emergency_observation_count,
                        summary.register_count AS register_observation_count,
                        summary.logout_count AS logout_observation_count,
                        summary.denial_count AS denial_observation_count,
                        summary.data_count AS data_observation_count,
                        %s AS other_signaling_observation_count,
                        %s AS signaling_observation_count,
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
                        summary.first_seen_ms, summary.last_seen_ms, summary.logical_call_count,
                        summary.recorded_output_count AS recorded_logical_call_count,
                        summary.streamed_output_count AS stream_submitted_logical_call_count,
                        summary.encrypted_logical_call_count,
                        summary.grant_count AS grant_observation_count,
                        summary.join_count AS join_observation_count,
                        summary.emergency_count AS emergency_observation_count,
                        summary.register_count AS register_observation_count,
                        summary.logout_count AS logout_observation_count,
                        summary.denial_count AS denial_observation_count,
                        summary.data_count AS data_observation_count,
                        %s AS other_signaling_observation_count,
                        %s AS signaling_observation_count,
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
                                  WHERE config.channel_kind = 'CONVENTIONAL'
                                    AND context.context_key = 'CONFIGURATION:' || config.configuration_id
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
                        sum(summary.call_count) AS logical_call_count,
                        coalesce((SELECT sum(bucket.recorded_count)
                            FROM conventional_call_identity_bucket bucket
                            WHERE bucket.context_id = context.id AND bucket.identity_role_code = 1
                              AND bucket.identity_kind_code = 1
                              AND bucket.identity_id = summary.talkgroup_id), 0)
                            AS recorded_logical_call_count,
                        coalesce((SELECT sum(bucket.streamed_count)
                            FROM conventional_call_identity_bucket bucket
                            WHERE bucket.context_id = context.id AND bucket.identity_role_code = 1
                              AND bucket.identity_kind_code = 1
                              AND bucket.identity_id = summary.talkgroup_id), 0)
                            AS stream_submitted_logical_call_count,
                        sum(summary.encrypted_count) AS encrypted_logical_call_count,
                        NULL AS grant_observation_count, NULL AS join_observation_count,
                        NULL AS emergency_observation_count, NULL AS register_observation_count,
                        NULL AS logout_observation_count, NULL AS denial_observation_count,
                        NULL AS data_observation_count, NULL AS other_signaling_observation_count,
                        NULL AS signaling_observation_count,
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
                                  WHERE config.channel_kind = 'CONVENTIONAL'
                                    AND context.context_key = 'CONFIGURATION:' || config.configuration_id
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
                        sum(bucket.call_count) AS logical_call_count,
                        sum(bucket.recorded_count) AS recorded_logical_call_count,
                        sum(bucket.streamed_count) AS stream_submitted_logical_call_count,
                        sum(bucket.encrypted_count) AS encrypted_logical_call_count,
                        NULL AS grant_observation_count, NULL AS join_observation_count,
                        NULL AS emergency_observation_count, NULL AS register_observation_count,
                        NULL AS logout_observation_count, NULL AS denial_observation_count,
                        NULL AS data_observation_count, NULL AS other_signaling_observation_count,
                        NULL AS signaling_observation_count,
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
                    JOIN conventional_call_identity_bucket bucket ON bucket.context_id = context.id
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
    private static List<Map<String,Object>> querySignalHealthExport(Connection connection, int limit)
        throws SQLException
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
            LIMIT ?
            """.formatted(qualitySiteSelect()), limit);
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
        int targetPoints = requestedPoints != null ? requestedPoints : QUALITY_DEFAULT_POINTS;

        if(targetPoints < QUALITY_MINIMUM_POINTS || targetPoints > QUALITY_MAXIMUM_POINTS)
        {
            throw new StatsApiException(400, "invalid_parameter", "points must be between " +
                QUALITY_MINIMUM_POINTS + " and " + QUALITY_MAXIMUM_POINTS, "points");
        }
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
            row.put("protocol", StatsApiProtocol.fromCode(number(site.get("protocol_code"))).name());
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

    private Map<String,Object> exportSiteMetadata(Connection connection,
                                                  WebConfiguredEntityRepository.ConfiguredChannel configured)
        throws SQLException
    {
        String guid = configured.guid();
        Map<String,Object> site = configuredSiteReadModel(connection, configured);
        Map<String,Object> assigned = configuredSiteContext(connection, configured);
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("site_guid", guid);
        metadata.put("site_name", textValue(site.get("channel_name")));
        metadata.put("site_protocol", configured.protocol().name());
        metadata.put("site_scope_token", assigned != null ? textValue(assigned.get("scope_token")) : "");
        metadata.put("site_system_name", textValue(site.get("configured_system")));
        metadata.put("site_wacn", site.get("wacn") != null ? site.get("wacn") : "");
        metadata.put("site_system_id", site.get("system_id") != null ? site.get("system_id") : "");
        metadata.put("site_network_id", site.get("network_id") != null ? site.get("network_id") : "");
        metadata.put("site_rfss", site.get("rfss") != null ? site.get("rfss") : "");
        metadata.put("site_number", site.get("site") != null ? site.get("site") : site.get("site_id"));
        metadata.put("site_nac", site.get("nac") != null ? site.get("nac") : "");
        metadata.put("site_ran", site.get("ran") != null ? site.get("ran") : "");
        return metadata;
    }

    /**
     * Builds one configuration-owned site row and overlays optional retained observations.  Reapplying the configured
     * values last prevents learned labels from replacing administrator-owned names or canonical identity.
     */
    private static Map<String,Object> configuredSiteReadModel(
        Connection connection, WebConfiguredEntityRepository.ConfiguredChannel configured) throws SQLException
    {
        String guid = configured.guid();
        int protocolCode = configured.protocolCode();
        Map<String,Object> site = configured.toApiMap();
        List<Map<String,Object>> observations = protocolCode == StatsApiProtocol.P25.databaseCode() ?
            queryRows(connection, siteSelect() + " WHERE site.guid = ?", guid) :
            protocolCode == StatsApiProtocol.DMR.databaseCode() ||
                protocolCode == StatsApiProtocol.NXDN.databaseCode() ?
                queryRows(connection, trunkedSiteSelect() +
                    " WHERE site.guid = ? AND site.protocol_code = ?", guid, protocolCode) : List.of();

        if(!observations.isEmpty())
        {
            site.putAll(observations.getFirst());
        }

        site.putAll(configured.toApiMap());
        return site;
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
                    UNION ALL SELECT last_seen_ms FROM p25_site_frequency_band_summary
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
        boolean includeHistory = request.booleanValue("include_history", guid != null);

        if(guid == null && includeHistory)
        {
            throw new StatsApiException(400, "invalid_parameter",
                "include_history requires a site-scoped quality route", "include_history");
        }
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
        int targetPoints = requestedPoints != null ? requestedPoints : QUALITY_DEFAULT_POINTS;

        if(targetPoints < QUALITY_MINIMUM_POINTS || targetPoints > QUALITY_MAXIMUM_POINTS)
        {
            throw new StatsApiException(400, "invalid_parameter", "points must be between " +
                QUALITY_MINIMUM_POINTS + " and " + QUALITY_MAXIMUM_POINTS, "points");
        }
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
        int siteLimit = guid == null ? request.limit() : 1;
        int siteOffset = guid == null ? request.offset() : 0;

        return read(connection -> {
            if(guid != null)
            {
                mConfiguredEntities.requireSite(connection, guid);
            }

            Map<String,Map<String,Object>> sitesByGuid = new LinkedHashMap<>();
            List<Map<String,Object>> sites = queryRows(connection, """
                SELECT site.*
                FROM (
                    %s
                ) site
                WHERE (? IS NULL OR site.guid = ?)
                ORDER BY lower(coalesce(site.channel_name, site.guid)), site.guid
                LIMIT ? OFFSET ?
                """.formatted(qualitySiteSelect), guid, guid, siteLimit + 1, siteOffset);
            boolean hasMore = sites.size() > siteLimit;

            if(hasMore)
            {
                sites = new ArrayList<>(sites.subList(0, siteLimit));
            }

            for(Map<String,Object> site: sites)
            {
                site.put("series", new ArrayList<Map<String,Object>>());
                WebEntityRef.put(site, WebEntityRef.site(String.valueOf(site.get("guid"))));
                sitesByGuid.put(site.get("guid").toString(), site);
            }

            List<Map<String,Object>> latest;

            if(sitesByGuid.isEmpty())
            {
                latest = List.of();
            }
            else
            {
                String placeholders = String.join(",", java.util.Collections.nCopies(sitesByGuid.size(), "?"));
                latest = queryRows(connection, """
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
                    WHERE site.guid IN (%s)
                    """.formatted(qualitySiteSelect, placeholders), sitesByGuid.keySet().toArray());
            }

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

            if(guid == null)
            {
                response.put("limit", siteLimit);
                response.put("offset", siteOffset);
                response.put("hasMore", hasMore);
                response.put("nextOffset", hasMore ? siteOffset + siteLimit : null);
            }

            return response;
        });
    }

    Map<String,Object> systemDirectory(StatsRequest request)
    {
        boolean includeSitePreview = request.booleanValue("include_site_preview", false);
        int limit = includeSitePreview ? request.limit(MAXIMUM_SYSTEM_DIRECTORY_WITH_SITE_PREVIEW) : request.limit();
        int offset = request.offset();
        String search = request.search();

        return readSnapshot(connection -> {
            List<Object> parameters = new ArrayList<>();
            StringBuilder sql = new StringBuilder("WITH ");

            if(search != null)
            {
                sql.append(MATCHING_CONFIGURATION_GUID_CTE).append(",\n");
                parameters.add(like(search));
            }

            sql.append("scoped AS (").append(scopeSummarySelect()).append(") SELECT * FROM scoped WHERE 1=1");

            if(search != null)
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
                           LEFT JOIN p25_site_snapshot p25
                             ON scoped.protocol_code = 1 AND p25.guid = context.guid
                           LEFT JOIN trunked_site_snapshot trunked
                             ON scoped.protocol_code IN (3, 4) AND trunked.guid = context.guid
                           LEFT JOIN matching_configuration_guid matching_config
                             ON matching_config.radres_guid = context.guid
                           WHERE ownership.scope_id = scoped.scope_id
                             AND (lower(coalesce(context.guid, '') || ' ' ||
                                 coalesce(context.channel_name, '') || ' ' ||
                                 coalesce(p25.channel_name, '') || ' ' ||
                                 coalesce(trunked.channel_name, '') || ' ' ||
                                 coalesce(trunked.configured_system, '') || ' ' ||
                                 coalesce(CAST(trunked.network_id AS TEXT), '') || ' ' ||
                                 coalesce(CAST(trunked.system_id AS TEXT), '') || ' ' ||
                                 coalesce(CAST(trunked.site_id AS TEXT), '') || ' ' ||
                                 coalesce(CAST(trunked.ran AS TEXT), '') || ' ' ||
                                 coalesce(CAST(p25.rfss AS TEXT), '') || ' ' ||
                                 coalesce(CAST(p25.site AS TEXT), '')) LIKE ?
                                 OR matching_config.radres_guid IS NOT NULL)))
                    """);
                String like = like(search);
                parameters.add(like);
                parameters.add(like);
            }

            sql.append(" ORDER BY ").append(order(request, SYSTEM_SORT_COLUMNS, "last_seen"))
                .append(", scope_token LIMIT ? OFFSET ?");
            addLimitOffset(parameters, limit + 1, offset);
            List<Map<String,Object>> parentRows = queryRows(connection, sql.toString(), parameters.toArray());
            Map<String,Object> response = page(parentRows, limit, offset);
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> pageRows = (List<Map<String,Object>>)response.get("rows");

            for(Map<String,Object> system: pageRows)
            {
                system.put("capabilities", systemCapabilities((int)number(system.get("protocol_code"))));
                WebEntityRef.put(system, WebEntityRef.system(String.valueOf(system.get("scope_token"))));
            }

            if(includeSitePreview)
            {
                attachSystemDirectorySitePreviews(connection, pageRows, search);
                response.put("sitePreviewLimitPerSystem", MAXIMUM_SYSTEM_DIRECTORY_SITE_PREVIEW);
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
                SELECT coalesce(SUM(logical_call_count), 0) AS logical_call_count,
                    coalesce(SUM(recorded_output_count), 0) AS recorded_logical_call_count,
                    coalesce(SUM(streamed_output_count), 0) AS stream_submitted_logical_call_count,
                    coalesce(SUM(encrypted_logical_call_count), 0) AS encrypted_logical_call_count
                FROM trunked_logical_call_bucket
                WHERE scope_id = ?
                """, system.get("scope_id"));

            if(!activity.isEmpty())
            {
                system.putAll(activity.getFirst());
            }

            boolean sharedP25System = number(system.get("protocol_code")) == 1 &&
                number(system.get("scope_kind_code")) == 1;

            if(sharedP25System)
            {
                List<Map<String,Object>> siteActivity = queryRows(connection, """
                    SELECT coalesce(SUM(observed_call_count), 0) AS site_observation_count,
                        coalesce(SUM(encrypted_observed_call_count), 0)
                            AS encrypted_site_observation_count
                    FROM p25_site_call_bucket
                    WHERE scope_id = ?
                    """, system.get("scope_id"));
                if(!siteActivity.isEmpty())
                {
                    system.putAll(siteActivity.getFirst());
                }
            }
            else
            {
                //Receiver-context scopes own exactly one monitored site, so each logical call is one observation
                //for that receiver. This also keeps fail-open P25 calls visible when learned identity is incomplete.
                system.put("site_observation_count", system.get("logical_call_count"));
                system.put("encrypted_site_observation_count", system.get("encrypted_logical_call_count"));
            }

            system.put("capabilities", systemCapabilities((int)number(system.get("protocol_code"))));
            WebEntityRef.put(system, WebEntityRef.system(scopeToken));
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
        return readSnapshot(connection -> {
            requireScope(connection, request.requiredText("scope"));
            List<Map<String,Object>> rows = querySystemTalkgroups(connection, request,
                request.limit() + 1, request.offset());
            Map<String,Object> response = page(rows, request);
            response.put("totalCount", countSystemTalkgroups(connection, request));
            return response;
        });
    }

    private static long countSystemTalkgroups(Connection connection, StatsRequest request) throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM trunked_identity_summary summary
            JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
            WHERE scope.scope_token = ? AND summary.identity_kind_code IN (1, 3)
            """);
        List<Object> parameters = new ArrayList<>(List.of(request.requiredText("scope")));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        return scalarLong(connection, sql.toString(), parameters.toArray());
    }

    private List<Map<String,Object>> querySystemTalkgroups(Connection connection, StatsRequest request,
                                                           int limit, int offset) throws SQLException
    {
        String scopeToken = request.requiredText("scope");
        StringBuilder sql = new StringBuilder("""
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    scope.alias_list_id,
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
                    %s AS alias_list_name,
                    summary.identity_id AS talkgroup_id, summary.identity_kind_code AS target_kind_code,
                    summary.first_seen_ms, summary.last_seen_ms,
                    summary.logical_call_count,
                    summary.encrypted_logical_call_count,
                    summary.recorded_output_count AS recorded_logical_call_count,
                    summary.streamed_output_count AS stream_submitted_logical_call_count,
                    CASE WHEN scope.protocol_code = 1 AND scope.scope_kind_code = 1 THEN coalesce((
                        SELECT SUM(site_calls.observed_call_count)
                        FROM p25_site_call_identity_bucket site_calls
                        WHERE site_calls.scope_id = summary.scope_id
                          AND site_calls.identity_role_code = 1
                          AND site_calls.identity_kind_code = summary.identity_kind_code
                          AND site_calls.identity_id = summary.identity_id
                    ), 0) WHEN scope.scope_kind_code = 2 THEN summary.logical_call_count
                    END AS site_observation_count,
                    %s AS signaling_observation_count
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                WHERE scope.scope_token = ? AND summary.identity_kind_code IN (1, 3)
                """.formatted(uniqueScopeAliasListExpression(), TALKGROUP_SIGNALING_COUNT_SQL));
        List<Object> parameters = new ArrayList<>(List.of(scopeToken));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        sql.append(" ORDER BY ").append(order(request, TALKGROUP_SORT_COLUMNS, "logical_call_count"))
            .append(", summary.identity_kind_code, summary.identity_id LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        enrichScopeTalkgroups(connection, rows, "talkgroup_id", "alias_");

        for(Map<String,Object> row: rows)
        {
            int kind = (int)number(row.get("target_kind_code"));
            int identifier = (int)number(row.get("talkgroup_id"));
            WebEntityRef.put(row, identityReference(row, kind, identifier));
        }

        return rows;
    }

    Map<String,Object> systemRadios(StatsRequest request)
    {
        return readSnapshot(connection -> {
            requireScope(connection, request.requiredText("scope"));
            List<Map<String,Object>> rows = querySystemRadios(connection, request,
                request.limit() + 1, request.offset());
            Map<String,Object> response = page(rows, request);
            response.put("totalCount", countSystemRadios(connection, request));
            return response;
        });
    }

    private static long countSystemRadios(Connection connection, StatsRequest request) throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM trunked_identity_summary summary
            JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
            LEFT JOIN trunked_radio_affiliation affiliation
              ON affiliation.scope_id = scope.scope_id AND affiliation.radio_id = summary.identity_id
            LEFT JOIN trunked_radio_site_presence presence
              ON presence.scope_id = scope.scope_id AND presence.radio_id = summary.identity_id
            LEFT JOIN receiver_context presence_context ON presence_context.id = presence.context_id
            WHERE scope.scope_token = ? AND summary.identity_kind_code = 2
            """);
        List<Object> parameters = new ArrayList<>(List.of(request.requiredText("scope")));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        addRadioFilters(sql, parameters, request, "affiliation.radio_id IS NOT NULL");
        return scalarLong(connection, sql.toString(), parameters.toArray());
    }

    private List<Map<String,Object>> querySystemRadios(Connection connection, StatsRequest request,
                                                       int limit, int offset) throws SQLException
    {
        String scopeToken = request.requiredText("scope");
        StringBuilder sql = new StringBuilder("""
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    scope.alias_list_id,
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
                    %s AS alias_list_name,
                    %s, summary.identity_id AS radio_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_id END AS last_talkgroup_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_kind_code END AS last_talkgroup_kind_code,
                    affiliation.talkgroup_id AS affiliated_talkgroup_id,
                    affiliation.confirmed_at_ms AS affiliation_confirmed_at_ms,
                    CASE WHEN affiliation.radio_id IS NOT NULL THEN 1 ELSE 0 END AS currently_affiliated,
                    %s
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                LEFT JOIN trunked_radio_affiliation affiliation
                  ON affiliation.scope_id = scope.scope_id AND affiliation.radio_id = summary.identity_id
                %s
                WHERE scope.scope_token = ? AND summary.identity_kind_code = 2
                """.formatted(uniqueScopeAliasListExpression(),
            TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL, radioPresenceSelect(),
            radioPresenceJoins("summary.identity_id")));
        List<Object> parameters = new ArrayList<>(List.of(scopeToken));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        addRadioFilters(sql, parameters, request, "affiliation.radio_id IS NOT NULL");
        sql.append(" ORDER BY ").append(order(request, RADIO_SORT_COLUMNS, "logical_call_count"))
            .append(", summary.identity_id LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        enrichScopeRadios(connection, rows, "radio_id", "alias_");
        enrichScopeTalkgroups(connection, rows, "affiliated_talkgroup_id", "affiliated_talkgroup_alias_");
        nestRadioPresence(rows);

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, identityReference(row, IDENTITY_KIND_RADIO,
                (int)number(row.get("radio_id"))));
            WebEntityRef.put(row, "affiliated_talkgroup_entity_ref", identityReference(row,
                IDENTITY_KIND_TALKGROUP, (int)number(row.get("affiliated_talkgroup_id"))));
            WebEntityRef.put(row, "last_talkgroup_entity_ref", identityReference(row,
                (int)number(row.get("last_talkgroup_kind_code")),
                (int)number(row.get("last_talkgroup_id"))));
        }

        return rows;
    }

    /**
     * Fixed-width site-presence projection.  The current-state table contributes at most one row for each radio, so
     * adding this projection cannot multiply a bounded directory page.
     */
    private static String radioPresenceSelect()
    {
        return """
            CASE presence.evidence_code WHEN 1 THEN 'registration' WHEN 2 THEN 'affiliation' END
                AS presence_evidence,
            presence.confirmed_at_ms AS presence_confirmed_at_ms,
            presence_context.guid AS presence_guid,
            presence_config.configuration_id AS presence_configuration_id,
            scope.protocol_code AS presence_protocol_code,
            system.wacn AS presence_wacn,
            CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE presence_trunked.system_id END
                AS presence_system_id,
            presence_trunked.network_id AS presence_network_id,
            CASE WHEN scope.protocol_code = 1 THEN coalesce(presence_p25.nac, presence_context.nac) END
                AS presence_nac,
            CASE WHEN scope.protocol_code = 1 THEN coalesce(presence_p25.rfss, presence_context.rfss) END
                AS presence_rfss,
            CASE WHEN scope.protocol_code = 1 THEN coalesce(presence_p25.site, presence_context.site)
                ELSE presence_trunked.site_id END AS presence_site_id,
            CASE WHEN scope.protocol_code IN (3, 4) THEN presence_trunked.ran END AS presence_ran,
            nullif(trim(presence_config.site_name), '') AS presence_configured_site,
            nullif(trim(presence_config.name), '') AS presence_configured_name,
            coalesce(presence_context.channel_name, presence_p25.channel_name,
                presence_trunked.channel_name) AS presence_channel_name
            """;
    }

    private static String radioPresenceJoins(String radioIdColumn)
    {
        return """
            LEFT JOIN trunked_radio_site_presence presence
              ON presence.scope_id = scope.scope_id AND presence.radio_id = %s
            LEFT JOIN receiver_context presence_context ON presence_context.id = presence.context_id
            LEFT JOIN p25_site_snapshot presence_p25
              ON scope.protocol_code = 1 AND presence_p25.guid = presence_context.guid
            LEFT JOIN trunked_site_snapshot presence_trunked
              ON scope.protocol_code IN (3, 4) AND presence_trunked.guid = presence_context.guid
            LEFT JOIN configuration_channel presence_config
              ON presence_config.radres_guid = presence_context.guid
             AND presence_config.channel_kind = 'TRUNKED'
            """.formatted(radioIdColumn);
    }

    private static void addRadioFilters(StringBuilder sql, List<Object> parameters, StatsRequest request,
                                        String currentlyAffiliatedSql)
    {
        Boolean affiliated = request.optionalBoolean("affiliated");

        if(affiliated != null)
        {
            sql.append(affiliated ? " AND (" : " AND NOT (").append(currentlyAffiliatedSql).append(')');
        }

        String siteGuid = request.text("site_guid");

        if(siteGuid != null)
        {
            sql.append(" AND presence_context.guid = ?");
            parameters.add(siteGuid);
        }
    }

    private static void nestRadioPresence(List<Map<String,Object>> rows)
    {
        for(Map<String,Object> row: rows)
        {
            Object guid = row.remove("presence_guid");
            Object configurationId = row.remove("presence_configuration_id");
            Object evidence = row.remove("presence_evidence");
            Object confirmedAt = row.remove("presence_confirmed_at_ms");

            if(evidence == null || confirmedAt == null)
            {
                removePresenceColumns(row);
                row.put("presence", null);
                continue;
            }

            Map<String,Object> site = new LinkedHashMap<>();
            site.put("guid", guid);
            movePresenceColumn(row, site, "protocol_code");
            movePresenceColumn(row, site, "wacn");
            movePresenceColumn(row, site, "system_id");
            movePresenceColumn(row, site, "network_id");
            movePresenceColumn(row, site, "nac");
            movePresenceColumn(row, site, "rfss");
            movePresenceColumn(row, site, "site_id");
            movePresenceColumn(row, site, "ran");
            movePresenceColumn(row, site, "configured_site");
            movePresenceColumn(row, site, "configured_name");
            movePresenceColumn(row, site, "channel_name");

            if(configurationId != null && guid instanceof String siteGuid && !siteGuid.isBlank())
            {
                WebEntityRef.put(site, WebEntityRef.site(siteGuid));
            }

            Map<String,Object> presence = new LinkedHashMap<>();
            presence.put("evidence", evidence);
            presence.put("confirmed_at_ms", confirmedAt);
            presence.put("site", site);
            row.put("presence", presence);
        }
    }

    private static void movePresenceColumn(Map<String,Object> row, Map<String,Object> site, String name)
    {
        Object value = row.remove("presence_" + name);

        if(value != null || "configured_site".equals(name) || "configured_name".equals(name) ||
            "channel_name".equals(name))
        {
            site.put(name, value);
        }
    }

    private static void removePresenceColumns(Map<String,Object> row)
    {
        for(String name: List.of("protocol_code", "wacn", "system_id", "network_id", "nac", "rfss",
            "site_id", "ran", "configured_site", "configured_name", "channel_name", "configuration_id"))
        {
            row.remove("presence_" + name);
        }
    }

    Map<String,Object> systemTalkerAliases(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");

        return readSnapshot(connection -> {
            requireScope(connection, scopeToken);
            StringBuilder sql = new StringBuilder("""
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    scope.alias_list_id,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    %s AS alias_list_name,
                    %s, summary.identity_id AS radio_id,
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
                """.formatted(uniqueScopeAliasListExpression(),
                TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL));
            List<Object> parameters = new ArrayList<>(List.of(scopeToken));

            addTalkerAliasSearch(sql, parameters, request.search());

            sql.append(" ORDER BY ").append(order(request, TALKER_ALIAS_SORT_COLUMNS, "talker_alias"))
                .append(", summary.identity_id LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            enrichScopeRadios(connection, rows, "radio_id", "alias_");
            enrichScopeTalkgroups(connection, rows, "last_talkgroup_id", "talkgroup_alias_");

            for(Map<String,Object> row: rows)
            {
                WebEntityRef.put(row, identityReference(row, IDENTITY_KIND_RADIO,
                    (int)number(row.get("radio_id"))));
                WebEntityRef.put(row, "last_talkgroup_entity_ref", identityReference(row,
                    (int)number(row.get("last_talkgroup_kind_code")),
                    (int)number(row.get("last_talkgroup_id"))));
            }

            Map<String,Object> response = page(rows, request);
            response.put("totalCount", countSystemTalkerAliases(connection, request));
            return response;
        });
    }

    private static long countSystemTalkerAliases(Connection connection, StatsRequest request) throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM trunked_identity_summary summary
            JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
            WHERE scope.scope_token = ? AND summary.identity_kind_code = 2
              AND summary.last_talker_alias IS NOT NULL
              AND trim(summary.last_talker_alias) <> ''
            """);
        List<Object> parameters = new ArrayList<>(List.of(request.requiredText("scope")));
        addTalkerAliasSearch(sql, parameters, request.search());
        return scalarLong(connection, sql.toString(), parameters.toArray());
    }

    Map<String,Object> talkgroup(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");
        int talkgroup = request.requiredIdentifier("talkgroup_id");
        int identityKind = targetKind(request);

        return readSnapshot(connection -> {
            Map<String,Object> scope = requireScope(connection, scopeToken);
            requireValidIdentity(scope, identityKind, talkgroup, "Group identity not found");
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    scope.alias_list_id,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    %s AS alias_list_name,
                    %s, summary.identity_id AS talkgroup_id,
                    summary.identity_kind_code AS target_kind_code,
                    CASE WHEN summary.last_counterpart_kind_code = 2
                        THEN summary.last_counterpart_id END AS last_source_radio_id,
                    (SELECT COUNT(*) FROM trunked_radio_talkgroup_summary relationship
                        WHERE relationship.scope_id = summary.scope_id
                          AND relationship.talkgroup_id = summary.identity_id
                          AND relationship.target_kind_code = summary.identity_kind_code) AS radios,
                    (SELECT COUNT(*) FROM trunked_radio_affiliation affiliation
                        WHERE summary.identity_kind_code = 1
                          AND affiliation.scope_id = summary.scope_id
                          AND affiliation.talkgroup_id = summary.identity_id) AS affiliated_radios,
                    (SELECT COUNT(DISTINCT presence.context_id)
                      FROM trunked_radio_affiliation affiliation
                      JOIN trunked_radio_site_presence presence
                        ON presence.scope_id = affiliation.scope_id
                       AND presence.radio_id = affiliation.radio_id
                      WHERE summary.identity_kind_code = 1
                        AND affiliation.scope_id = summary.scope_id
                        AND affiliation.talkgroup_id = summary.identity_id) AS affiliated_sites,
                    CASE WHEN scope.protocol_code = 1 AND scope.scope_kind_code = 1 THEN coalesce((
                        SELECT SUM(site_calls.observed_call_count)
                        FROM p25_site_call_identity_bucket site_calls
                        WHERE site_calls.scope_id = summary.scope_id
                          AND site_calls.identity_role_code = 1
                          AND site_calls.identity_kind_code = summary.identity_kind_code
                          AND site_calls.identity_id = summary.identity_id
                    ), 0) WHEN scope.scope_kind_code = 2 THEN summary.logical_call_count
                    END AS site_observation_count
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                WHERE scope.scope_token = ? AND summary.identity_kind_code = ? AND summary.identity_id = ?
                """.formatted(uniqueScopeAliasListExpression(),
                TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL), scopeToken, identityKind, talkgroup);
            enrichScopeTalkgroups(connection, rows, "talkgroup_id", "alias_");
            enrichSummaryEncryption(rows);
            Map<String,Object> row = rows.isEmpty() ? emptyIdentity(scope, identityKind, talkgroup) :
                rows.getFirst();

            if(rows.isEmpty())
            {
                enrichScopeTalkgroups(connection, List.of(row), "talkgroup_id", "alias_");
            }

            row.put("capabilities", talkgroupCapabilities((int)number(row.get("protocol_code")),
                (int)number(row.get("target_kind_code"))));
            WebEntityRef.put(row, identityKind == IDENTITY_KIND_PATCH_GROUP ?
                WebEntityRef.patchGroup(scopeToken, talkgroup) : WebEntityRef.talkgroup(scopeToken, talkgroup));
            WebEntityRef.put(row, "system_entity_ref", WebEntityRef.system(scopeToken));
            WebEntityRef.put(row, "last_source_entity_ref", identityReference(row, IDENTITY_KIND_RADIO,
                (int)number(row.get("last_source_radio_id"))));
            return Map.of("group_identity", row);
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
        long untilMilliseconds = throughMilliseconds + bucketMilliseconds;
        String responseRange = requestedRange.label();

        return read(connection -> {
            Map<String,Object> scope = requireScope(connection, scopeToken);
            requireValidIdentity(scope, identityKind, talkgroup, "Group identity not found");
            List<Map<String,Object>> stored = queryRows(connection, """
                SELECT CAST(bucket.bucket_start_ms / ? AS INTEGER) * ? AS time_ms,
                    SUM(bucket.logical_call_count) AS logical_call_count,
                    SUM(bucket.encrypted_logical_call_count) AS encrypted_logical_call_count,
                    SUM(bucket.recorded_output_count) AS recorded_logical_call_count,
                    SUM(bucket.streamed_output_count) AS stream_submitted_logical_call_count
                FROM trunked_logical_call_identity_bucket bucket
                WHERE bucket.scope_id = ? AND bucket.identity_role_code = ?
                    AND bucket.identity_kind_code = ? AND bucket.identity_id = ?
                    AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
                GROUP BY time_ms
                ORDER BY time_ms
                LIMIT ?
                """, bucketMilliseconds, bucketMilliseconds, scope.get("scope_id"),
                IDENTITY_ROLE_DESTINATION, identityKind, talkgroup, fromMilliseconds, untilMilliseconds,
                pointCount + 1);

            if(stored.size() > pointCount)
            {
                throw new StatsApiException(413, "activity_history_too_large",
                    "Activity history exceeded the requested point limit");
            }
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
                    totals.put(observationCountField(field), number(summary.getFirst().get(field)));
                }
            }

            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", responseRange);
            response.put("from_ms", fromMilliseconds);
            response.put("to_ms", toMilliseconds);
            response.put("bucket_ms", bucketMilliseconds);
            response.put("logical_metric_start_ms", scopeMetricStartedAt(connection));
            response.put("totals", totals);
            response.put("series", series);
            return response;
        });
    }

    Map<String,Object> radio(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");
        int radio = request.requiredIdentifier("radio_id");

        return readSnapshot(connection -> {
            Map<String,Object> scope = requireScope(connection, scopeToken);
            requireValidIdentity(scope, IDENTITY_KIND_RADIO, radio, "Radio not found");
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    scope.alias_list_id,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    %s AS alias_list_name,
                    %s, summary.identity_id AS radio_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_id END AS last_talkgroup_id,
                    CASE WHEN summary.last_counterpart_kind_code IN (1, 3)
                        THEN summary.last_counterpart_kind_code END AS last_talkgroup_kind_code,
                    CASE WHEN summary.last_counterpart_kind_code = 2
                        THEN summary.last_counterpart_id END AS last_peer_radio_id,
                    affiliation.talkgroup_id AS affiliated_talkgroup_id,
                    affiliation.confirmed_at_ms AS affiliation_confirmed_at_ms,
                    CASE WHEN affiliation.radio_id IS NOT NULL THEN 1 ELSE 0 END AS currently_affiliated,
                    (SELECT COUNT(*) FROM trunked_radio_talkgroup_summary relationship
                        WHERE relationship.scope_id = summary.scope_id
                          AND relationship.radio_id = summary.identity_id) AS talkgroups,
                    %s
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                LEFT JOIN trunked_radio_affiliation affiliation
                  ON affiliation.scope_id = scope.scope_id AND affiliation.radio_id = summary.identity_id
                %s
                WHERE scope.scope_token = ? AND summary.identity_kind_code = 2 AND summary.identity_id = ?
                """.formatted(uniqueScopeAliasListExpression(),
                TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL, radioPresenceSelect(),
                radioPresenceJoins("summary.identity_id")), scopeToken, radio);
            enrichScopeRadios(connection, rows, "radio_id", "alias_");
            enrichScopeTalkgroups(connection, rows, "affiliated_talkgroup_id", "affiliated_talkgroup_alias_");
            enrichScopeTalkgroups(connection, rows, "last_talkgroup_id", "last_talkgroup_alias_");
            enrichScopeRadios(connection, rows, "last_peer_radio_id", "last_peer_alias_");
            enrichSummaryEncryption(rows);
            nestRadioPresence(rows);
            Map<String,Object> row = rows.isEmpty() ? emptyIdentity(scope, IDENTITY_KIND_RADIO, radio) :
                rows.getFirst();

            if(rows.isEmpty())
            {
                row.put("talkgroups", 0L);
                row.put("currently_affiliated", 0);
                enrichScopeRadios(connection, List.of(row), "radio_id", "alias_");
            }

            row.put("capabilities", systemCapabilities((int)number(row.get("protocol_code"))));
            WebEntityRef.put(row, WebEntityRef.radio(scopeToken, radio));
            WebEntityRef.put(row, "system_entity_ref", WebEntityRef.system(scopeToken));
            WebEntityRef.put(row, "affiliated_talkgroup_entity_ref", identityReference(row,
                IDENTITY_KIND_TALKGROUP, (int)number(row.get("affiliated_talkgroup_id"))));
            WebEntityRef.put(row, "last_talkgroup_entity_ref", identityReference(row,
                (int)number(row.get("last_talkgroup_kind_code")),
                (int)number(row.get("last_talkgroup_id"))));
            WebEntityRef.put(row, "last_peer_entity_ref", identityReference(row, IDENTITY_KIND_RADIO,
                (int)number(row.get("last_peer_radio_id"))));
            return Map.of("radio", row);
        });
    }

    Map<String,Object> radioTalkgroupRelationships(StatsRequest request)
    {
        String scopeToken = request.requiredText("scope");
        Integer talkgroup = request.optionalIdentifier("talkgroup_id");
        Integer radio = request.optionalIdentifier("radio_id");
        String requestedKind = request.text("kind");

        if(talkgroup == null && radio == null)
        {
            throw new StatsApiException(400, "radio_id or talkgroup_id is required");
        }
        else if(talkgroup == null && requestedKind != null)
        {
            throw new StatsApiException(400, "invalid_parameter", "kind requires talkgroup_id", "kind");
        }

        int targetKind = talkgroup != null ? targetKind(requestedKind) : IDENTITY_KIND_TALKGROUP;

        return readSnapshot(connection -> {
            Map<String,Object> scope = requireScope(connection, scopeToken);

            if(talkgroup != null)
            {
                requireValidIdentity(scope, targetKind, talkgroup, "Group identity not found");
            }

            if(radio != null)
            {
                requireValidIdentity(scope, IDENTITY_KIND_RADIO, radio, "Radio not found");
            }

            StringBuilder sql = new StringBuilder("""
                SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                    scope.alias_list_id,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    scope.p25_system_key AS system_key, system.wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE
                        (SELECT trunked.system_id FROM trunked_identity_scope_context ownership
                         JOIN receiver_context context ON context.id = ownership.context_id
                         LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                         WHERE ownership.scope_id = scope.scope_id ORDER BY ownership.context_id LIMIT 1)
                    END AS system_id,
                    %s AS alias_list_name,
                    relationship.scope_id, relationship.radio_id, relationship.talkgroup_id,
                    relationship.target_kind_code, relationship.first_seen_ms,
                    relationship.last_seen_ms, %s,
                    relationship.last_encryption_algorithm_id,
                    relationship.last_encryption_key_id,
                    radio.last_talker_alias,
                    CASE WHEN %s THEN 1 ELSE 0 END AS currently_affiliated,
                    %s
                FROM trunked_radio_talkgroup_summary relationship
                JOIN trunked_identity_scope scope ON scope.scope_id = relationship.scope_id
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
                LEFT JOIN trunked_identity_summary radio
                  ON radio.scope_id = relationship.scope_id AND radio.identity_kind_code = 2
                 AND radio.identity_id = relationship.radio_id
                LEFT JOIN trunked_radio_affiliation affiliation
                  ON affiliation.scope_id = relationship.scope_id
                 AND affiliation.radio_id = relationship.radio_id
                %s
                WHERE scope.scope_token = ?
                """.formatted(uniqueScopeAliasListExpression(),
                TRUNKED_RELATIONSHIP_METRIC_PROJECTION_SQL,
                CURRENT_RELATIONSHIP_AFFILIATION_SQL, radioPresenceSelect(),
                radioPresenceJoins("relationship.radio_id")));
            List<Object> parameters = new ArrayList<>(List.of(scopeToken));
            addRelationshipIdentityFilters(sql, parameters, talkgroup, targetKind, radio);
            addRadioFilters(sql, parameters, request, CURRENT_RELATIONSHIP_AFFILIATION_SQL);

            sql.append(" ORDER BY ").append(order(request, RELATIONSHIP_SORT_COLUMNS, "last_seen"))
                .append(", relationship.target_kind_code, relationship.talkgroup_id, relationship.radio_id")
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            enrichScopeRadios(connection, rows, "radio_id", "radio_alias_");
            enrichScopeTalkgroups(connection, rows, "talkgroup_id", "talkgroup_alias_");
            nestRadioPresence(rows);

            for(Map<String,Object> row: rows)
            {
                WebEntityRef.put(row, "radio_entity_ref", identityReference(row, IDENTITY_KIND_RADIO,
                    (int)number(row.get("radio_id"))));
                WebEntityRef.put(row, "talkgroup_entity_ref", identityReference(row,
                    (int)number(row.get("target_kind_code")), (int)number(row.get("talkgroup_id"))));
            }

            Map<String,Object> response = page(rows, request);
            response.put("totalCount", countRadioTalkgroupRelationships(connection, scopeToken, talkgroup,
                targetKind, radio, request));
            return response;
        });
    }

    private static long countRadioTalkgroupRelationships(Connection connection, String scopeToken, Integer talkgroup,
                                                         int targetKind, Integer radio, StatsRequest request)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM trunked_radio_talkgroup_summary relationship
            JOIN trunked_identity_scope scope ON scope.scope_id = relationship.scope_id
            LEFT JOIN trunked_radio_affiliation affiliation
              ON affiliation.scope_id = relationship.scope_id
             AND affiliation.radio_id = relationship.radio_id
            LEFT JOIN trunked_radio_site_presence presence
              ON presence.scope_id = relationship.scope_id AND presence.radio_id = relationship.radio_id
            LEFT JOIN receiver_context presence_context ON presence_context.id = presence.context_id
            WHERE scope.scope_token = ?
            """);
        List<Object> parameters = new ArrayList<>(List.of(scopeToken));
        addRelationshipIdentityFilters(sql, parameters, talkgroup, targetKind, radio);
        addRadioFilters(sql, parameters, request, CURRENT_RELATIONSHIP_AFFILIATION_SQL);
        return scalarLong(connection, sql.toString(), parameters.toArray());
    }

    private static void addRelationshipIdentityFilters(StringBuilder sql, List<Object> parameters,
                                                       Integer talkgroup, int targetKind, Integer radio)
    {
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
    }

    Map<String,Object> site(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return readSnapshot(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireSite(connection, guid);
            Map<String,Object> site = configuredSiteReadModel(connection, configured);
            site.put("site_kind", "trunked");
            StatsApiProtocol apiProtocol = configured.protocol();
            site.put("capabilities", apiProtocol.siteCapabilities());
            Map<String,Object> scope = configuredSiteContext(connection, configured);

            if(scope != null)
            {
                String scopeToken = String.valueOf(scope.get("scope_token"));
                site.put("scope_token", scopeToken);
                WebEntityRef.put(site, "system_entity_ref", WebEntityRef.system(scopeToken));
            }

            Object mfid = site.get("mfid");
            if(configured.protocolCode() == 1 && mfid instanceof Number number)
            {
                site.put("mfid_display", mfidDisplay(number.intValue()));
            }

            site.put("affiliated_radios", scalarLong(connection, """
                SELECT COUNT(*)
                FROM trunked_radio_site_presence presence
                JOIN receiver_context context ON context.id = presence.context_id
                JOIN trunked_radio_affiliation affiliation
                  ON affiliation.scope_id = presence.scope_id AND affiliation.radio_id = presence.radio_id
                WHERE context.guid = ?
                """, configured.guid()));

            return Map.of("site", site);
        });
    }

    Map<String,Object> siteChannels(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireSite(connection, guid);
            return page(querySiteChannels(connection, configured, request.limit() + 1,
                request.offset()), request);
        });
    }

    private List<Map<String,Object>> querySiteChannels(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, int limit, int offset) throws SQLException
    {
        String guid = configured.guid();

        if(configured.protocolCode() == StatsApiProtocol.DMR.databaseCode() ||
            configured.protocolCode() == StatsApiProtocol.NXDN.databaseCode())
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

        if(configured.protocolCode() != StatsApiProtocol.P25.databaseCode())
        {
            return List.of();
        }

        List<Map<String,Object>> rows = queryRows(connection, """
            WITH logical AS (
                SELECT summary.guid, summary.channel_key AS raw_channel_key,
                    substr(summary.channel_key, 1, 256) AS channel_key,
                    length(summary.channel_key) > 256 AS channel_key_truncated,
                    substr(coalesce(current.descriptor, summary.descriptor), 1, 256) AS descriptor,
                    length(coalesce(current.descriptor, summary.descriptor)) > 256 AS descriptor_truncated,
                    coalesce(current.downlink_hz, summary.downlink_hz) AS downlink_hz,
                    coalesce(current.uplink_hz, summary.uplink_hz) AS uplink_hz,
                    coalesce(current.tdma, summary.tdma) AS tdma,
                    coalesce(current.timeslots, summary.timeslots) AS timeslots,
                    substr(coalesce(current.callsign, summary.callsign), 1, 256) AS callsign,
                    length(coalesce(current.callsign, summary.callsign)) > 256 AS callsign_truncated,
                    current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                    summary.observation_count,
                    CASE WHEN coalesce(current.downlink_hz, summary.downlink_hz) > 0
                        THEN coalesce(current.downlink_hz, summary.downlink_hz) END AS physical_frequency_hz
                FROM p25_site_channel_summary summary
                LEFT JOIN p25_site_channel current
                  ON current.guid = summary.guid AND current.channel_key = summary.channel_key
                JOIN p25_site_snapshot site ON site.guid = summary.guid
                WHERE summary.guid = ?
            ), physical AS (
                SELECT physical_frequency_hz,
                    CASE WHEN physical_frequency_hz IS NULL THEN raw_channel_key END AS unassigned_channel_key,
                    min(channel_key) AS channel_key, max(channel_key_truncated) AS channel_key_truncated,
                    min(descriptor) AS descriptor, max(descriptor_truncated) AS descriptor_truncated,
                    max(downlink_hz) AS downlink_hz, max(uplink_hz) AS uplink_hz,
                    max(tdma) AS tdma, max(timeslots) AS timeslots, max(callsign) AS callsign,
                    max(callsign_truncated) AS callsign_truncated,
                    max(confirmed_at_ms) AS confirmed_at_ms, min(first_seen_ms) AS first_seen_ms,
                    max(last_seen_ms) AS last_seen_ms, sum(observation_count) AS observation_count,
                    count(*) AS logical_channel_count
                FROM logical
                GROUP BY physical_frequency_hz,
                    CASE WHEN physical_frequency_hz IS NULL THEN raw_channel_key END
            ), selected AS MATERIALIZED (
                SELECT row_number() OVER (ORDER BY physical_frequency_hz IS NULL,
                           physical_frequency_hz, unassigned_channel_key) AS result_id,
                    physical.*
                FROM physical
                ORDER BY physical_frequency_hz IS NULL, physical_frequency_hz, unassigned_channel_key
                LIMIT ? OFFSET ?
            ), summary_tags AS (
                SELECT selected.result_id,
                    sum(CASE WHEN tag.tag = 'CONFIGURED' THEN tag.observation_count ELSE 0 END)
                        AS configured_observations,
                    sum(CASE WHEN tag.tag = 'CONTROL' THEN tag.observation_count ELSE 0 END)
                        AS control_observations,
                    sum(CASE WHEN tag.tag = 'ALTERNATE_CONTROL' THEN tag.observation_count ELSE 0 END)
                        AS alternate_control_observations,
                    sum(CASE WHEN tag.tag = 'CWID' THEN tag.observation_count ELSE 0 END)
                        AS cwid_observations,
                    sum(CASE WHEN tag.tag = 'DATA_ANNOUNCED' THEN tag.observation_count ELSE 0 END)
                        AS data_announcement_observations,
                    sum(CASE WHEN tag.tag = 'VOICE' THEN tag.observation_count ELSE 0 END)
                        AS voice_grant_observations,
                    sum(CASE WHEN tag.tag = 'DATA' THEN tag.observation_count ELSE 0 END)
                        AS data_grant_observations
                FROM selected
                JOIN logical ON (selected.physical_frequency_hz IS NOT NULL AND
                        logical.physical_frequency_hz = selected.physical_frequency_hz)
                    OR (selected.physical_frequency_hz IS NULL AND logical.physical_frequency_hz IS NULL AND
                        logical.raw_channel_key = selected.unassigned_channel_key)
                LEFT JOIN p25_site_channel_tag_summary tag
                  ON tag.guid = logical.guid AND tag.channel_key = logical.raw_channel_key
                 AND tag.tag IN ('CONFIGURED','CONTROL','ALTERNATE_CONTROL','CWID','DATA_ANNOUNCED','VOICE','DATA')
                GROUP BY selected.result_id
            ), current_tags AS (
                SELECT selected.result_id,
                    max(CASE WHEN tag.tag = 'CONFIGURED' THEN 1 ELSE 0 END) AS current_configured,
                    max(CASE WHEN tag.tag = 'CONTROL' THEN 1 ELSE 0 END) AS current_control,
                    max(CASE WHEN tag.tag = 'CURRENT_CONTROL' THEN 1 ELSE 0 END) AS current_current_control,
                    max(CASE WHEN tag.tag = 'ALTERNATE_CONTROL' THEN 1 ELSE 0 END) AS current_alternate_control,
                    max(CASE WHEN tag.tag = 'CWID' THEN 1 ELSE 0 END) AS current_cwid,
                    max(CASE WHEN tag.tag = 'DATA_ANNOUNCED' THEN 1 ELSE 0 END) AS current_data_announced,
                    max(CASE WHEN tag.tag = 'VOICE' THEN 1 ELSE 0 END) AS current_voice,
                    max(CASE WHEN tag.tag = 'DATA' THEN 1 ELSE 0 END) AS current_data
                FROM selected
                JOIN logical ON (selected.physical_frequency_hz IS NOT NULL AND
                        logical.physical_frequency_hz = selected.physical_frequency_hz)
                    OR (selected.physical_frequency_hz IS NULL AND logical.physical_frequency_hz IS NULL AND
                        logical.raw_channel_key = selected.unassigned_channel_key)
                LEFT JOIN p25_site_channel_tag tag
                  ON tag.guid = logical.guid AND tag.channel_key = logical.raw_channel_key
                 AND tag.tag IN ('CONFIGURED','CONTROL','CURRENT_CONTROL','ALTERNATE_CONTROL','CWID',
                                 'DATA_ANNOUNCED','VOICE','DATA')
                GROUP BY selected.result_id
            )
            SELECT selected.channel_key, selected.channel_key_truncated, selected.descriptor,
                selected.descriptor_truncated, selected.downlink_hz, selected.uplink_hz, selected.tdma,
                selected.timeslots, selected.callsign, selected.callsign_truncated, selected.confirmed_at_ms,
                selected.first_seen_ms, selected.last_seen_ms, selected.observation_count,
                selected.logical_channel_count, 1 AS logical_channels_included,
                selected.logical_channel_count > 1 AS logical_channels_truncated,
                summary_tags.configured_observations, summary_tags.control_observations,
                summary_tags.alternate_control_observations, summary_tags.cwid_observations,
                summary_tags.data_announcement_observations, summary_tags.voice_grant_observations,
                summary_tags.data_grant_observations, current_tags.current_configured,
                current_tags.current_control, current_tags.current_current_control,
                current_tags.current_alternate_control, current_tags.current_cwid,
                current_tags.current_data_announced, current_tags.current_voice, current_tags.current_data,
                CASE WHEN max(coalesce(selected.confirmed_at_ms, 0), selected.last_seen_ms) >= ?
                    THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
            FROM selected
            JOIN summary_tags ON summary_tags.result_id = selected.result_id
            JOIN current_tags ON current_tags.result_id = selected.result_id
            ORDER BY selected.physical_frequency_hz IS NULL, selected.physical_frequency_hz,
                selected.unassigned_channel_key
            """, guid, limit, offset, System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS);
        addP25ChannelTags(rows);
        return rows;
    }

    private static void addP25ChannelTags(List<Map<String,Object>> rows)
    {
        for(Map<String,Object> row: rows)
        {
            List<String> observed = new ArrayList<>();
            addTag(observed, row, "configured_observations", "CONFIGURED");
            addTag(observed, row, "control_observations", "CONTROL");
            addTag(observed, row, "alternate_control_observations", "ALTERNATE_CONTROL");
            addTag(observed, row, "cwid_observations", "CWID");
            addTag(observed, row, "data_announcement_observations", "DATA_ANNOUNCED");
            addTag(observed, row, "voice_grant_observations", "VOICE");
            addTag(observed, row, "data_grant_observations", "DATA");
            row.put("tags", List.copyOf(observed));

            List<String> current = new ArrayList<>();
            addCurrentTag(current, row, "current_configured", "CONFIGURED");
            addCurrentTag(current, row, "current_control", "CONTROL");
            addCurrentTag(current, row, "current_current_control", "CURRENT_CONTROL");
            addCurrentTag(current, row, "current_alternate_control", "ALTERNATE_CONTROL");
            addCurrentTag(current, row, "current_cwid", "CWID");
            addCurrentTag(current, row, "current_data_announced", "DATA_ANNOUNCED");
            addCurrentTag(current, row, "current_voice", "VOICE");
            addCurrentTag(current, row, "current_data", "DATA");
            row.put("current_tags", List.copyOf(current));
        }
    }

    private static void addTag(List<String> tags, Map<String,Object> row, String field, String tag)
    {
        if(number(row.get(field)) > 0)
        {
            tags.add(tag);
        }
    }

    private static void addCurrentTag(List<String> tags, Map<String,Object> row, String field, String tag)
    {
        if(number(row.remove(field)) > 0)
        {
            tags.add(tag);
        }
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
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireSite(connection, guid);
            Map<String,Object> context = configuredSiteContext(connection, configured);
            List<Map<String,Object>> rows = List.of();

            if(context != null && number(context.get("protocol_code")) == 1 &&
                number(context.get("scope_kind_code")) == 1)
            {
                rows = queryRows(connection, """
                    SELECT bucket.identity_id AS talkgroup_id, bucket.identity_kind_code,
                        bucket.identity_kind_code AS target_kind_code,
                        SUM(bucket.observed_call_count) AS site_observation_count,
                        SUM(bucket.encrypted_observed_call_count)
                            AS encrypted_site_observation_count,
                        MAX(bucket.bucket_start_ms) AS last_active_ms
                    FROM p25_site_call_identity_bucket bucket
                    JOIN p25_learned_site learned
                      ON learned.learned_site_id = bucket.learned_site_id
                    WHERE bucket.scope_id = ? AND learned.system_key = ?
                      AND learned.rfss = ? AND learned.site = ?
                      AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
                      AND bucket.identity_role_code = ?
                      AND bucket.identity_kind_code IN (1, 3)
                    GROUP BY bucket.identity_kind_code, bucket.identity_id
                    ORDER BY site_observation_count DESC, bucket.identity_id
                    LIMIT ?
                    """, context.get("scope_id"), context.get("system_key"), context.get("rfss"),
                    context.get("site"), fromMilliseconds, throughMilliseconds + HOUR_MILLISECONDS,
                    IDENTITY_ROLE_DESTINATION, request.limit());
            }
            else if(context != null)
            {
                //Receiver-context scopes represent one monitored site/context, so their logical call count is also
                //the site's observation count. Shared P25 system scopes use learned-site identity above.
                rows = queryRows(connection, """
                    SELECT bucket.identity_id AS talkgroup_id, bucket.identity_kind_code,
                        bucket.identity_kind_code AS target_kind_code,
                        SUM(bucket.logical_call_count) AS site_observation_count,
                        SUM(bucket.encrypted_logical_call_count)
                            AS encrypted_site_observation_count,
                        MAX(bucket.bucket_start_ms) AS last_active_ms
                    FROM trunked_logical_call_identity_bucket bucket
                    WHERE bucket.scope_id = ? AND bucket.bucket_start_ms >= ?
                      AND bucket.bucket_start_ms < ? AND bucket.identity_role_code = ?
                      AND bucket.identity_kind_code IN (1, 3)
                    GROUP BY bucket.identity_kind_code, bucket.identity_id
                    ORDER BY site_observation_count DESC, bucket.identity_id
                    LIMIT ?
                    """, context.get("scope_id"), fromMilliseconds,
                    throughMilliseconds + HOUR_MILLISECONDS, IDENTITY_ROLE_DESTINATION,
                    request.limit());
            }

            for(Map<String,Object> row: rows)
            {
                row.put("system_key", context.get("system_key"));
                row.put("wacn", context.get("wacn"));
                row.put("system_id", context.get("system_id"));
                row.put("scope_token", context.get("scope_token"));
                row.put("protocol_code", context.get("protocol_code"));
                row.put("identity_domain_code", context.get("identity_domain_code"));
                row.put("alias_list_id", context.get("alias_list_id"));
                row.put("alias_list_name", context.get("alias_list_name"));
                WebEntityRef.put(row, identityReference(row,
                    (int)number(row.get("target_kind_code")), (int)number(row.get("talkgroup_id"))));
            }

            enrichScopeTalkgroups(connection, rows, "talkgroup_id", "alias_");
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", range.label());
            response.put("from_ms", fromMilliseconds);
            response.put("to_ms", System.currentTimeMillis());
            response.put("bucket_ms", HOUR_MILLISECONDS);
            response.put("logical_metric_start_ms", scopeMetricStartedAt(connection));
            response.put("rows", rows);
            return response;
        });
    }

    Map<String,Object> siteBands(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireSite(connection, guid);

            if(configured.protocolCode() != StatsApiProtocol.P25.databaseCode())
            {
                return Map.of("rows", List.of(), "foreign_rows", List.of(), "foreign_limit", request.limit(),
                    "foreign_offset", request.offset(), "foreign_has_more", false);
            }

            long currentSince = System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS;
            Map<String,Object> response = new LinkedHashMap<>();
            List<Map<String,Object>> identityRows = queryRows(connection, """
                SELECT json_extract(config.config_json,
                           '$.decodeConfiguration.useP25BandplanOverride') = 1 AS override_enabled,
                    coalesce(system.wacn,
                        json_extract(config.config_json, '$.p25SiteIdentity.wacn')) AS wacn,
                    coalesce(system.system_id, snapshot.system_id,
                        json_extract(config.config_json, '$.p25SiteIdentity.system')) AS system_id,
                    coalesce(snapshot.rfss,
                        json_extract(config.config_json, '$.p25SiteIdentity.rfss')) AS rfss,
                    coalesce(snapshot.site,
                        json_extract(config.config_json, '$.p25SiteIdentity.site')) AS site_id
                FROM configuration_channel config
                LEFT JOIN p25_site_snapshot snapshot ON snapshot.guid = config.radres_guid
                LEFT JOIN p25_system system ON system.system_key = snapshot.system_key
                WHERE config.id = ?
                """, configured.rowId());
            P25BandplanOverrideProfile override = null;

            if(!identityRows.isEmpty() && number(identityRows.getFirst().get("override_enabled")) == 1)
            {
                Map<String,Object> identity = identityRows.getFirst();
                Long wacn = nullableNumber(identity.get("wacn"));
                Long system = nullableNumber(identity.get("system_id"));
                Long rfss = nullableNumber(identity.get("rfss"));
                Long site = nullableNumber(identity.get("site_id"));

                if(wacn != null && system != null)
                {
                    override = mP25BandplanOverrides.find(wacn.intValue(), system.intValue(),
                        rfss != null ? rfss.intValue() : null, site != null ? site.intValue() : null).orElse(null);
                }
            }

            if(override != null)
            {
                List<Map<String,Object>> rows = new ArrayList<>();

                for(P25BandplanOverrideBand band: override.bands())
                {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("band", band.identifier());
                    row.put("tdma", band.type().getTimeslotCount() == 2);
                    row.put("base_hz", band.baseFrequency());
                    row.put("bandwidth_hz", band.bandwidth());
                    row.put("spacing_hz", band.channelSpacing());
                    row.put("transmit_offset_hz", band.transmitOffset());
                    row.put("timeslots", band.type().getTimeslotCount());
                    rows.add(row);
                }

                response.put("rows", rows);
                response.put("band_source", "P25_OVERRIDE");
            }
            else
            {
                response.put("rows", queryRows(connection, """
                    SELECT current.band, current.tdma, current.base_hz, current.bandwidth AS bandwidth_hz,
                        current.spacing_hz,
                        current.transmit_offset_hz, current.timeslots, current.confirmed_at_ms,
                        summary.first_seen_ms, summary.last_seen_ms, summary.observation_count,
                        CASE WHEN max(current.confirmed_at_ms, coalesce(summary.last_seen_ms, 0)) >= ?
                            THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM p25_site_frequency_band current
                    LEFT JOIN p25_site_frequency_band_summary summary
                      ON summary.guid = current.guid AND summary.band = current.band
                    WHERE current.guid = ? ORDER BY current.band
                    """, currentSince, guid));
                response.put("band_source", "OTA");
            }
            List<Map<String,Object>> foreignRows = queryRows(connection, """
                SELECT summary.foreign_wacn, summary.foreign_system_id, summary.band,
                    coalesce(current.channel_type, summary.channel_type) AS channel_type_code,
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
                LIMIT ? OFFSET ?
                """, currentSince, guid, request.limit() + 1, request.offset());
            boolean hasMore = foreignRows.size() > request.limit();

            if(hasMore)
            {
                foreignRows = new ArrayList<>(foreignRows.subList(0, request.limit()));
            }

            for(Map<String,Object> row: foreignRows)
            {
                row.put("protocol_code", 1);
            }

            response.put("foreign_rows", foreignRows);
            response.put("foreign_limit", request.limit());
            response.put("foreign_offset", request.offset());
            response.put("foreign_has_more", hasMore);
            response.put("foreign_next_offset", hasMore ? request.offset() + request.limit() : null);
            return response;
        });
    }

    Map<String,Object> siteNeighbors(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireSite(connection, guid);
            return page(querySiteNeighbors(connection, configured, request.limit() + 1,
                request.offset()), request);
        });
    }

    private List<Map<String,Object>> querySiteNeighbors(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, int limit, int offset) throws SQLException
    {
        String guid = configured.guid();
        long currentSince = System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS;
        int protocolCode = configured.protocolCode();

        if(protocolCode == 3 || protocolCode == 4)
        {
            List<Map<String,Object>> rows = queryRows(connection, """
                    SELECT 'SITE' AS entry_type, neighbor.variant_code, neighbor.identity_domain_code,
                        NULLIF(neighbor.network_id, -1) AS network_id,
                        NULLIF(neighbor.system_id, -1) AS system_id,
                        NULLIF(neighbor.site_id, -1) AS site_id,
                        NULLIF(neighbor.channel_number, -1) AS channel_number,
                        NULLIF(neighbor.frequency_hz, -1) AS frequency_hz,
                        NULLIF(neighbor.frequency_hz, -1) AS downlink_hz, neighbor.status_flags,
                        neighbor.first_seen_ms, neighbor.last_seen_ms, neighbor.observation_count,
                        CASE WHEN neighbor.last_seen_ms >= ? THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM trunked_site_neighbor_summary neighbor
                    WHERE neighbor.guid = ?
                    ORDER BY neighbor.identity_domain_code,
                        neighbor.network_id = -1, neighbor.network_id,
                        neighbor.system_id = -1, neighbor.system_id,
                        neighbor.site_id = -1, neighbor.site_id,
                        neighbor.channel_number = -1, neighbor.channel_number,
                        neighbor.variant_code, neighbor.frequency_hz = -1, neighbor.frequency_hz
                    LIMIT ? OFFSET ?
                    """, currentSince, guid, limit, offset);

            for(Map<String,Object> row: rows)
            {
                row.put("protocol_code", protocolCode);
            }

            return rows;
        }

        if(protocolCode != StatsApiProtocol.P25.databaseCode())
        {
            return List.of();
        }

        List<Map<String,Object>> rows = queryRows(connection, """
                WITH combined AS (
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
                        SELECT min(candidate.guid)
                        FROM p25_site_snapshot candidate
                        JOIN configuration_channel candidate_config
                          ON candidate_config.radres_guid = candidate.guid
                         AND candidate_config.channel_kind = 'TRUNKED'
                        WHERE candidate.system_key = neighbor_system.system_key
                          AND candidate.rfss = coalesce(current.rfss, summary.rfss)
                          AND candidate.site = coalesce(current.site, summary.site)
                        HAVING count(*) = 1
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
                    nullif(trim(config.site_name), '') AS neighbor_configured_site,
                    nullif(trim(config.name), '') AS neighbor_configured_name,
                    neighbor_guid, band_count, has_fdma, has_tdma, has_unknown, state
                FROM combined
                LEFT JOIN configuration_channel config ON config.radres_guid = combined.neighbor_guid
                    AND config.channel_kind = 'TRUNKED'
                ORDER BY entry_order, current_order, system_id, rfss, site, neighbor_key
                LIMIT ? OFFSET ?
                """, currentSince, guid, currentSince, currentSince, guid,
                limit, offset);

        for(Map<String,Object> row: rows)
        {
            row.put("site_id", row.remove("site"));
            row.put("protocol_code", protocolCode);

            if(row.get("neighbor_guid") instanceof String neighborGuid && !neighborGuid.isBlank())
            {
                WebEntityRef.put(row, WebEntityRef.site(neighborGuid));
            }
        }

        return rows;
    }

    Map<String,Object> sitePatches(StatsRequest request)
    {
        String guid = request.requiredText("guid");
        int groupLimit = request.limit(MAXIMUM_PATCH_GROUP_PAGE);
        int offset = request.offset();
        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireSite(connection, guid);

            if(configured.protocolCode() != StatsApiProtocol.P25.databaseCode())
            {
                return sitePatchResponse(List.of(), List.of(), List.of(), groupLimit, offset,
                    false, false);
            }

            Map<String,Object> context = configuredSiteContext(connection, configured);
            List<Map<String,Object>> groups = queryRows(connection, """
                SELECT system.system_key, system.wacn,
                    coalesce(system.system_id, site.system_id) AS system_id,
                    current.patch_group, current.version,
                    current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                    summary.observation_count,
                    (SELECT COUNT(*) FROM p25_site_patch_group_talkgroup member
                     WHERE member.guid = current.guid AND member.patch_group = current.patch_group)
                        AS talkgroup_count,
                    (SELECT COUNT(*) FROM p25_site_patch_group_radio member
                     WHERE member.guid = current.guid AND member.patch_group = current.patch_group)
                        AS radio_count,
                    CASE WHEN max(current.confirmed_at_ms, coalesce(summary.last_seen_ms, 0)) >= ?
                        THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                FROM p25_site_patch_group current
                JOIN p25_site_snapshot site ON site.guid = current.guid
                LEFT JOIN p25_system system ON system.system_key = site.system_key
                LEFT JOIN p25_site_patch_group_summary summary
                  ON summary.guid = current.guid AND summary.patch_group = current.patch_group
                WHERE current.guid = ? ORDER BY current.patch_group
                LIMIT ? OFFSET ?
                """, System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS, guid,
                groupLimit + 1, offset);
            boolean hasMore = groups.size() > groupLimit;

            if(hasMore)
            {
                groups = new ArrayList<>(groups.subList(0, groupLimit));
            }

            List<Map<String,Object>> talkgroups = List.of();
            List<Map<String,Object>> radios = List.of();
            boolean talkgroupsTruncated = false;
            boolean radiosTruncated = false;

            if(!groups.isEmpty())
            {
                String placeholders = String.join(",", java.util.Collections.nCopies(groups.size(), "?"));
                List<Object> talkgroupParameters = new ArrayList<>();
                talkgroupParameters.add(guid);

                for(Map<String,Object> group: groups)
                {
                    talkgroupParameters.add(group.get("patch_group"));
                }

                talkgroupParameters.add(MAXIMUM_PATCH_MEMBERS_PER_GROUP);
                talkgroupParameters.add(MAXIMUM_PATCH_MEMBER_ROWS + 1);
                talkgroups = queryRows(connection, """
                    WITH ranked AS (
                        SELECT system.system_key, system.wacn,
                            coalesce(system.system_id, site.system_id) AS system_id, current.patch_group,
                            current.talkgroup_id, current.confirmed_at_ms, summary.first_seen_ms,
                            summary.last_seen_ms, summary.observation_count,
                            row_number() OVER (PARTITION BY current.patch_group
                                ORDER BY current.talkgroup_id) AS member_rank
                        FROM p25_site_patch_group_talkgroup current
                        JOIN p25_site_snapshot site ON site.guid = current.guid
                        LEFT JOIN p25_system system ON system.system_key = site.system_key
                        LEFT JOIN p25_site_patch_group_talkgroup_summary summary
                          ON summary.guid = current.guid AND summary.patch_group = current.patch_group
                            AND summary.talkgroup_id = current.talkgroup_id
                        WHERE current.guid = ? AND current.patch_group IN (%s)
                    )
                    SELECT system_key, wacn, system_id, patch_group, talkgroup_id, confirmed_at_ms,
                        first_seen_ms, last_seen_ms, observation_count
                    FROM ranked WHERE member_rank <= ?
                    ORDER BY patch_group, talkgroup_id LIMIT ?
                    """.formatted(placeholders), talkgroupParameters.toArray());
                List<Object> radioParameters = new ArrayList<>();
                radioParameters.add(guid);

                for(Map<String,Object> group: groups)
                {
                    radioParameters.add(group.get("patch_group"));
                }

                radioParameters.add(MAXIMUM_PATCH_MEMBERS_PER_GROUP);
                radioParameters.add(MAXIMUM_PATCH_MEMBER_ROWS + 1);
                radios = queryRows(connection, """
                    WITH ranked AS (
                        SELECT system.system_key, system.wacn,
                            coalesce(system.system_id, site.system_id) AS system_id, current.patch_group,
                            current.radio_id, current.confirmed_at_ms, summary.first_seen_ms,
                            summary.last_seen_ms, summary.observation_count,
                            row_number() OVER (PARTITION BY current.patch_group
                                ORDER BY current.radio_id) AS member_rank
                        FROM p25_site_patch_group_radio current
                        JOIN p25_site_snapshot site ON site.guid = current.guid
                        LEFT JOIN p25_system system ON system.system_key = site.system_key
                        LEFT JOIN p25_site_patch_group_radio_summary summary
                          ON summary.guid = current.guid AND summary.patch_group = current.patch_group
                            AND summary.radio_id = current.radio_id
                        WHERE current.guid = ? AND current.patch_group IN (%s)
                    )
                    SELECT system_key, wacn, system_id, patch_group, radio_id, confirmed_at_ms,
                        first_seen_ms, last_seen_ms, observation_count
                    FROM ranked WHERE member_rank <= ?
                    ORDER BY patch_group, radio_id LIMIT ?
                    """.formatted(placeholders), radioParameters.toArray());

                talkgroupsTruncated = talkgroups.size() > MAXIMUM_PATCH_MEMBER_ROWS;
                radiosTruncated = radios.size() > MAXIMUM_PATCH_MEMBER_ROWS;

                if(talkgroupsTruncated)
                {
                    talkgroups = new ArrayList<>(talkgroups.subList(0, MAXIMUM_PATCH_MEMBER_ROWS));
                }

                if(radiosTruncated)
                {
                    radios = new ArrayList<>(radios.subList(0, MAXIMUM_PATCH_MEMBER_ROWS));
                }
            }

            mAliasResolver.enrichTalkgroups(connection, groups, "patch_group", "patch_alias_");
            mAliasResolver.enrichTalkgroups(connection, talkgroups, "talkgroup_id", "alias_");
            mAliasResolver.enrichRadios(connection, radios, "radio_id", "alias_");

            for(Map<String,Object> group: groups)
            {
                long patchGroup = number(group.get("patch_group"));
                long includedTalkgroups = countPatchMembers(talkgroups, patchGroup);
                long includedRadios = countPatchMembers(radios, patchGroup);
                group.put("talkgroups_included", includedTalkgroups);
                group.put("radios_included", includedRadios);
                group.put("talkgroups_truncated", number(group.get("talkgroup_count")) > includedTalkgroups);
                group.put("radios_truncated", number(group.get("radio_count")) > includedRadios);

                if(context != null)
                {
                    WebEntityRef.put(group, identityReference(context, IDENTITY_KIND_PATCH_GROUP,
                        (int)patchGroup));
                }
            }

            if(context != null)
            {
                for(Map<String,Object> talkgroup: talkgroups)
                {
                    WebEntityRef.put(talkgroup, identityReference(context, IDENTITY_KIND_TALKGROUP,
                        (int)number(talkgroup.get("talkgroup_id"))));
                }

                for(Map<String,Object> radio: radios)
                {
                    WebEntityRef.put(radio, identityReference(context, IDENTITY_KIND_RADIO,
                        (int)number(radio.get("radio_id"))));
                }
            }

            boolean membersTruncated = talkgroupsTruncated || radiosTruncated || groups.stream()
                .anyMatch(group -> Boolean.TRUE.equals(group.get("talkgroups_truncated")) ||
                    Boolean.TRUE.equals(group.get("radios_truncated")));
            return sitePatchResponse(groups, talkgroups, radios, groupLimit, offset, hasMore,
                membersTruncated);
        });
    }

    private static Map<String,Object> sitePatchResponse(List<Map<String,Object>> groups,
                                                        List<Map<String,Object>> talkgroups,
                                                        List<Map<String,Object>> radios, int limit, int offset,
                                                        boolean hasMore, boolean membersTruncated)
    {
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("groups", groups);
        response.put("talkgroups", talkgroups);
        response.put("radios", radios);
        response.put("member_limit_per_group", MAXIMUM_PATCH_MEMBERS_PER_GROUP);
        response.put("member_limit_total", MAXIMUM_PATCH_MEMBER_ROWS);
        response.put("members_truncated", membersTruncated);
        response.put("limit", limit);
        response.put("offset", offset);
        response.put("hasMore", hasMore);
        response.put("nextOffset", hasMore ? offset + limit : null);
        return response;
    }

    private static long countPatchMembers(List<Map<String,Object>> members, long patchGroup)
    {
        long count = 0;

        for(Map<String,Object> member: members)
        {
            if(number(member.get("patch_group")) == patchGroup)
            {
                count++;
            }
        }

        return count;
    }

    Map<String,Object> dashboardActivityActions(StatsRequest request)
    {
        DashboardActivityWindow window = dashboardActivityWindow(request);

        return readSnapshot(connection -> {
            Map<String,Object> summary = activityActionSummary(connection, window.fromMilliseconds(),
                window.untilMilliseconds());
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", window.range());
            response.put("from_ms", window.fromMilliseconds());
            response.put("to_ms", window.toMilliseconds());
            response.putAll(summary);
            return response;
        });
    }

    Map<String,Object> dashboardActivityRadios(StatsRequest request)
    {
        String requestedAction = request.requiredText("action");
        ActivityAction action = DASHBOARD_ACTIVITY_ACTION_BY_NAME.get(
            requestedAction.toUpperCase(java.util.Locale.ROOT));

        if(action == null)
        {
            throw new StatsApiException(400, "invalid_parameter", "action is not supported", "action");
        }

        DashboardActivityWindow window = dashboardActivityWindow(request);
        int limit = request.limit();
        long offset = request.longOffset();

        return readSnapshot(connection -> {
            long actionObservationCount = activityActionTotal(connection, action, window.fromMilliseconds(),
                window.untilMilliseconds());
            List<Map<String,Object>> queryRows = dashboardActivityRadioRows(connection, action,
                window.fromMilliseconds(), window.untilMilliseconds(), limit, offset);
            Map<String,Object> totals = queryRows.isEmpty() ? Map.of() : queryRows.getFirst();
            long retainedObservationCount = number(totals.get("retained_observation_count"));
            long identifiedObservationCount = number(totals.get("identified_observation_count"));
            long totalCount = number(totals.get("total_count"));
            List<Map<String,Object>> rows = new ArrayList<>(Math.min(limit, queryRows.size()));

            for(Map<String,Object> row: queryRows)
            {
                if(number(row.get("radio_id")) <= 0)
                {
                    continue;
                }

                row.remove("retained_observation_count");
                row.remove("identified_observation_count");
                row.remove("total_count");
                rows.add(row);
            }

            enrichDashboardActivityRadios(connection, rows);
            boolean hasMore = offset < totalCount && rows.size() < totalCount - offset;
            Long nextOffset = hasMore ? Math.addExact(offset, rows.size()) : null;
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", window.range());
            response.put("action", action.name());
            response.put("from_ms", window.fromMilliseconds());
            response.put("to_ms", window.toMilliseconds());
            response.put("action_observation_count", actionObservationCount);
            response.put("retained_observation_count", retainedObservationCount);
            response.put("identified_observation_count", identifiedObservationCount);
            response.put("unknown_source_observation_count",
                retainedObservationCount - identifiedObservationCount);
            response.put("total_count", totalCount);
            response.put("limit", limit);
            response.put("offset", offset);
            response.put("hasMore", hasMore);
            response.put("nextOffset", nextOffset);
            response.put("rows", rows);
            return response;
        });
    }

    private static Map<String,Object> activityActionSummary(Connection connection, long fromMilliseconds,
                                                             long untilMilliseconds) throws SQLException
    {
        List<Map<String,Object>> trunked = queryRows(connection, TRUNKED_ACTIVITY_ACTION_SQL,
            fromMilliseconds, untilMilliseconds);
        List<Map<String,Object>> conventional = queryRows(connection, CONVENTIONAL_ACTIVITY_ACTION_SQL,
            fromMilliseconds, untilMilliseconds);
        Map<String,Object> trunkedCounts = trunked.isEmpty() ? Map.of() : trunked.getFirst();
        Map<String,Object> conventionalCounts = conventional.isEmpty() ? Map.of() : conventional.getFirst();
        List<Map<String,Object>> rows = new ArrayList<>(DASHBOARD_ACTIVITY_ACTIONS.size());
        long total = 0;

        for(ActivityAction action: DASHBOARD_ACTIVITY_ACTIONS)
        {
            long count = number(trunkedCounts.get(action.column())) +
                number(conventionalCounts.get(action.column()));
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("action", action.name());
            row.put("observation_count", count);
            rows.add(row);
            total += count;
        }

        rows.sort((left, right) -> {
            int countOrder = Long.compare(number(right.get("observation_count")),
                number(left.get("observation_count")));
            return countOrder != 0 ? countOrder :
                String.valueOf(left.get("action")).compareTo(String.valueOf(right.get("action")));
        });
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("total_observation_count", total);
        result.put("rows", rows);
        return result;
    }

    private static long activityActionTotal(Connection connection, ActivityAction action, long fromMilliseconds,
                                            long untilMilliseconds) throws SQLException
    {
        String sql = """
            SELECT COALESCE(SUM(action_count), 0)
            FROM (
                SELECT COALESCE(SUM(bucket.%1$s), 0) AS action_count
                FROM trunked_signaling_activity_bucket AS bucket
                    INDEXED BY idx_trunked_signaling_activity_time
                WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
                UNION ALL
                SELECT COALESCE(SUM(bucket.%1$s), 0) AS action_count
                FROM conventional_activity_bucket AS bucket
                    INDEXED BY idx_conventional_bucket_dashboard_time
                WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
            )
            """.formatted(action.column());
        return scalarLong(connection, sql, fromMilliseconds, untilMilliseconds,
            fromMilliseconds, untilMilliseconds);
    }

    private static List<Map<String,Object>> dashboardActivityRadioRows(Connection connection, ActivityAction action,
                                                                       long fromMilliseconds,
                                                                       long untilMilliseconds, int limit,
                                                                       long offset) throws SQLException
    {
        String sql = ("WITH action_slices AS MATERIALIZED (" +
            activityActionSlicesSql(action.column()) + """
            ), grouped AS MATERIALIZED (
                SELECT ownership.scope_id,
                    CASE WHEN ownership.scope_id IS NULL THEN event.context_id END AS fallback_context_id,
                    MIN(event.context_id) AS representative_context_id,
                    event.source_radio_id AS radio_id, COUNT(*) AS observation_count,
                    MAX(event.observed_at_ms) AS last_seen_ms
                FROM action_slices AS slice
                CROSS JOIN p25_activity_event AS event INDEXED BY idx_p25_activity_event_context_time
                LEFT JOIN trunked_identity_scope_context ownership ON ownership.context_id = event.context_id
                WHERE event.context_id = slice.context_id
                  AND event.observed_at_ms >= slice.bucket_start_ms
                  AND event.observed_at_ms < slice.bucket_start_ms + ?
                  AND event.action_code = ?
                GROUP BY ownership.scope_id,
                    CASE WHEN ownership.scope_id IS NULL THEN event.context_id END,
                    event.source_radio_id
            ), totals AS MATERIALIZED (
                SELECT COALESCE(SUM(observation_count), 0) AS retained_observation_count,
                    COALESCE(SUM(CASE WHEN radio_id > 0 THEN observation_count ELSE 0 END), 0)
                        AS identified_observation_count,
                    COALESCE(SUM(CASE WHEN radio_id > 0 THEN 1 ELSE 0 END), 0) AS total_count
                FROM grouped
            ), paged AS MATERIALIZED (
                SELECT scope_id, fallback_context_id, representative_context_id, radio_id,
                    observation_count, last_seen_ms
                FROM grouped
                WHERE radio_id > 0
                ORDER BY observation_count DESC, last_seen_ms DESC,
                    CASE WHEN scope_id IS NULL THEN 1 ELSE 0 END,
                    coalesce(scope_id, fallback_context_id), radio_id
                LIMIT ? OFFSET ?
            )
            SELECT paged.scope_id, paged.representative_context_id AS context_id,
                context.context_key, context.guid, scope.scope_token,
                config.configuration_id,
                nullif(trim(config.system_name), '') AS configured_system,
                nullif(trim(config.site_name), '') AS configured_site,
                nullif(trim(config.name), '') AS configured_name,
                coalesce(scope.identity_domain_code, trunked.identity_domain_code, 0)
                    AS identity_domain_code,
                CASE WHEN scope.protocol_code IS NOT NULL THEN scope.protocol_code
                    WHEN context.kind_code = 10 AND context.protocol_code = 11 THEN 11
                    WHEN context.kind_code = 10 THEN 10
                    ELSE coalesce(context.protocol_code, 0)
                END AS protocol_code,
                CASE CASE WHEN scope.protocol_code IS NOT NULL THEN scope.protocol_code
                    WHEN context.kind_code = 10 AND context.protocol_code = 11 THEN 11
                    WHEN context.kind_code = 10 THEN 10
                    ELSE coalesce(context.protocol_code, 0)
                END
                    WHEN 1 THEN 'APCO25'
                    WHEN 2 THEN 'APCO25_PHASE2'
                    WHEN 3 THEN 'DMR'
                    WHEN 4 THEN 'NXDN'
                    WHEN 10 THEN 'NBFM'
                    WHEN 11 THEN 'AM'
                    ELSE 'UNKNOWN'
                END AS protocol,
                context.kind_code AS channel_kind_code,
                CASE context.kind_code
                    WHEN 1 THEN 'TRUNKED_SITE'
                    WHEN 2 THEN 'CONVENTIONAL_P25'
                    WHEN 3 THEN 'CONVENTIONAL_DMR'
                    WHEN 4 THEN 'CONVENTIONAL_NXDN'
                    WHEN 10 THEN 'CONVENTIONAL_ANALOG'
                    ELSE NULL
                END AS channel_kind,
                context.channel_name AS resolved_channel_name,
                context.alias_list_name AS resolved_alias_list_name,
                CASE WHEN paged.scope_id IS NULL THEN context.alias_list_name
                    ELSE %s END AS alias_list_name,
                coalesce(scope.p25_system_key, context.system_key) AS system_key,
                system.wacn, coalesce(system.system_id, p25.system_id, trunked.system_id) AS system_id,
                trunked.configured_system, trunked.network_id,
                context.nac AS resolved_nac, context.rfss AS resolved_rfss,
                context.site AS resolved_site,
                paged.radio_id, paged.observation_count, paged.last_seen_ms,
                totals.retained_observation_count, totals.identified_observation_count,
                totals.total_count
            FROM totals
            LEFT JOIN paged ON 1 = 1
            LEFT JOIN trunked_identity_scope scope ON scope.scope_id = paged.scope_id
            LEFT JOIN receiver_context context ON context.id = paged.representative_context_id
            LEFT JOIN configuration_channel config
              ON paged.scope_id IS NULL AND config.channel_kind = 'CONVENTIONAL'
             AND context.context_key = 'CONFIGURATION:' || config.configuration_id
            LEFT JOIN p25_system system
                ON system.system_key = coalesce(scope.p25_system_key, context.system_key)
            LEFT JOIN p25_site_snapshot p25
              ON p25.guid = context.guid AND context.kind_code = 1 AND context.protocol_code IN (1, 2)
            LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
            ORDER BY paged.observation_count DESC, paged.last_seen_ms DESC,
                CASE WHEN paged.scope_id IS NULL THEN 1 ELSE 0 END,
                coalesce(paged.scope_id, paged.fallback_context_id), paged.radio_id
            """).formatted(uniqueScopeAliasListExpression());
        return queryRows(connection, sql,
            fromMilliseconds, untilMilliseconds, fromMilliseconds, untilMilliseconds,
            HOUR_MILLISECONDS, action.code(), limit, offset);
    }

    /**
     * Narrows retained-detail work to context-hours whose compact summary records the selected action. Conventional
     * buckets use a reserved frequency-zero row when the event has no projected frequency, so both branches have the
     * same complete hourly invariant without a broad compatibility scan of retained detail.
     */
    private static String activityActionSlicesSql(String actionColumn)
    {
        return """
            SELECT bucket.context_id, bucket.bucket_start_ms
            FROM trunked_signaling_activity_bucket AS bucket
                INDEXED BY idx_trunked_signaling_activity_time
            WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
              AND bucket.%1$s > 0
            UNION
            SELECT bucket.context_id, bucket.bucket_start_ms
            FROM conventional_activity_bucket AS bucket
                INDEXED BY idx_conventional_bucket_dashboard_time
            WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
              AND bucket.%1$s > 0
            """.formatted(actionColumn);
    }

    private void enrichDashboardActivityRadios(Connection connection, List<Map<String,Object>> rows)
        throws SQLException
    {
        if(rows.isEmpty())
        {
            return;
        }

        rows.forEach(row -> row.put("source_radio_id", row.get("radio_id")));
        mAliasResolver.enrichActivity(connection, rows);
        enrichDashboardActivityEntityReferences(rows);

        for(Map<String,Object> row: rows)
        {
            copyActivityAlias(row, "source_alias_", row, "alias_");
            row.remove("source_radio_id");

            for(String suffix: List.of("name", "description", "group", "color", "list_name"))
            {
                row.remove("source_alias_" + suffix);
            }
        }
    }

    private static void enrichDashboardActivityEntityReferences(List<Map<String,Object>> rows)
    {
        for(Map<String,Object> row: rows)
        {
            String scopeToken = textValue(row.get("scope_token"));

            if(!scopeToken.isBlank())
            {
                WebEntityRef.put(row, WebEntityRef.system(scopeToken));
                WebEntityRef.put(row, "radio_entity_ref", identityReference(row, IDENTITY_KIND_RADIO,
                    (int)number(row.get("radio_id"))));
            }
            else
            {
                String configurationId = textValue(row.get("configuration_id"));

                if(!configurationId.isBlank())
                {
                    WebEntityRef conventional = WebEntityRef.conventional(configurationId);
                    WebEntityRef.put(row, conventional);
                    WebEntityRef.put(row, "radio_entity_ref", conventional);
                }
            }
        }
    }

    private static void copyActivityAlias(Map<String,Object> source, String sourcePrefix,
                                          Map<String,Object> target, String targetPrefix)
    {
        for(String suffix: List.of("name", "description", "group", "color", "list_name"))
        {
            Object value = source.get(sourcePrefix + suffix);

            if(value != null && !String.valueOf(value).isBlank() && target.get(targetPrefix + suffix) == null)
            {
                target.put(targetPrefix + suffix, value);
            }
        }
    }

    private DashboardActivityWindow dashboardActivityWindow(StatsRequest request)
    {
        ActivityRange requestedRange = activityRange(request);
        long now = System.currentTimeMillis();
        long bucketCount = Math.max(1,
            (requestedRange.milliseconds() + HOUR_MILLISECONDS - 1) / HOUR_MILLISECONDS);
        long currentHour = Math.floorDiv(now, HOUR_MILLISECONDS) * HOUR_MILLISECONDS;
        long fromMilliseconds = currentHour - (bucketCount - 1) * HOUR_MILLISECONDS;
        return new DashboardActivityWindow(requestedRange.label(), fromMilliseconds,
            currentHour + HOUR_MILLISECONDS, now);
    }

    Map<String,Object> activity(StatsRequest request)
    {
        long beforeId = request.beforeId();
        Integer talkgroup = request.optionalIdentifier("talkgroup_id");
        Integer radio = request.optionalIdentifier("radio_id");
        String scopeToken = request.text("scope");
        String guid = request.text("guid");
        String context = request.text("context");
        String configurationId = request.text("configuration_id");
        boolean hideGrants = request.booleanValue("hide_grants", false);
        String requestedKind = request.text("kind");

        if(talkgroup == null && requestedKind != null)
        {
            throw new StatsApiException(400, "invalid_parameter", "kind requires talkgroup_id", "kind");
        }

        if(configurationId != null && (scopeToken != null || guid != null || context != null))
        {
            throw new StatsApiException(400, "invalid_parameter",
                "configuration_id cannot be combined with scope, guid, or context", "configuration_id");
        }

        int requestedTargetKind = talkgroup != null ? targetKind(requestedKind) : IDENTITY_KIND_TALKGROUP;
        int limit = request.limit();

        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured = configurationId != null ?
                mConfiguredEntities.requireConventional(connection, configurationId) : null;

            if(configured != null && configured.contextId() == null)
            {
                return cursorPage(List.of(), limit);
            }

            StringBuilder sql = new StringBuilder(ACTIVITY_SELECT_SQL);
            List<Object> parameters = new ArrayList<>();
            Long beforeTimestamp = null;

            if(beforeId != Long.MAX_VALUE)
            {
                List<Map<String,Object>> cursor = queryRows(connection,
                    "SELECT observed_at_ms FROM p25_activity_event WHERE id = ?", beforeId);

                if(cursor.isEmpty())
                {
                    return cursorPage(List.of(), limit);
                }

                beforeTimestamp = number(cursor.getFirst().get("observed_at_ms"));
            }

            if(hideGrants)
            {
                sql.append(" AND action <> 'GRANT'");
            }

            if(scopeToken != null)
            {
                if(guid == null && context == null && talkgroup == null && radio == null)
                {
                    List<Long> contextIds = scopeActivityContextIds(connection, scopeToken);

                    if(contextIds.isEmpty())
                    {
                        return cursorPage(List.of(), limit);
                    }

                    appendScopeActivityCandidates(sql, parameters, contextIds, hideGrants, beforeTimestamp,
                        beforeId, limit + 1);
                }
                else
                {
                    sql.append(" AND scope.scope_token = ?");
                    parameters.add(scopeToken);
                }
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
            if(configured != null)
            {
                sql.append(" AND activity.context_id = ?");
                parameters.add(configured.contextId());
            }
            if(talkgroup != null)
            {
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
                sql.append(" AND (activity.observed_at_ms < ? OR " +
                    "(activity.observed_at_ms = ? AND activity.id < ?))");
                parameters.add(beforeTimestamp);
                parameters.add(beforeTimestamp);
                parameters.add(beforeId);
            }

            sql.append(ACTIVITY_ORDER_SQL);
            parameters.add(limit + 1);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            mAliasResolver.enrichActivity(connection, rows);
            enrichActivityEncryption(rows);
            enrichActivityEntityReferences(rows);
            return cursorPage(rows, limit);
        });
    }

    /** Adds only exact configured-channel and learned-scope navigation; retained activity never creates an entity. */
    private static void enrichActivityEntityReferences(List<Map<String,Object>> rows)
    {
        for(Map<String,Object> row: rows)
        {
            String configurationId = textValue(row.get("configuration_id"));
            boolean conventional = number(row.get("channel_kind_code")) != 1;
            WebEntityRef channelReference = null;

            if(!configurationId.isBlank())
            {
                channelReference = conventional ? WebEntityRef.conventional(configurationId) :
                    WebEntityRef.site(textValue(row.get("guid")));
                WebEntityRef.put(row, channelReference);
            }

            String scopeToken = textValue(row.get("scope_token"));
            WebEntityRef sourceReference = null;
            WebEntityRef targetReference = null;
            int source = (int)number(row.get("source_radio_id"));
            int target = (int)number(row.get("target_id"));
            int targetKind = (int)number(row.get("target_kind_code"));

            if(!scopeToken.isBlank())
            {
                sourceReference = identityReference(row, IDENTITY_KIND_RADIO, source);
                targetReference = identityReference(row, targetKind, target);
            }
            else if(conventional && channelReference != null)
            {
                sourceReference = source > 0 ? channelReference : null;
                targetReference = target > 0 && (targetKind == IDENTITY_KIND_TALKGROUP ||
                    targetKind == IDENTITY_KIND_PATCH_GROUP || targetKind == IDENTITY_KIND_RADIO) ?
                    channelReference : null;
            }

            WebEntityRef.put(row, "source_entity_ref", sourceReference);
            WebEntityRef.put(row, "target_entity_ref", targetReference);
        }
    }

    private static List<Long> scopeActivityContextIds(Connection connection, String scopeToken) throws SQLException
    {
        List<Map<String,Object>> rows = queryRows(connection, """
            SELECT ownership.context_id
            FROM trunked_identity_scope_context ownership
            JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
            WHERE scope.scope_token = ?
            ORDER BY ownership.context_id
            LIMIT ?
            """, scopeToken, MAXIMUM_SYSTEM_ACTIVITY_CONTEXTS + 1);

        if(rows.size() > MAXIMUM_SYSTEM_ACTIVITY_CONTEXTS)
        {
            throw new StatsApiException(400, "scope_too_broad",
                "System activity spans too many monitored contexts; open a site activity view instead", "scope");
        }

        return rows.stream().map(row -> number(row.get("context_id"))).toList();
    }

    static void appendScopeActivityCandidates(StringBuilder sql, List<Object> parameters,
                                               List<Long> contextIds, boolean hideGrants,
                                               Long beforeTimestamp, long beforeId, int candidateLimit)
    {
        sql.append(" AND activity.id IN (SELECT candidate.id FROM (");

        for(int index = 0; index < contextIds.size(); index++)
        {
            if(index > 0)
            {
                sql.append(" UNION ALL ");
            }

            sql.append("SELECT id, observed_at_ms FROM (")
                .append("SELECT id, observed_at_ms FROM p25_activity_event_resolved ")
                .append("WHERE context_id = ?");
            parameters.add(contextIds.get(index));

            if(hideGrants)
            {
                sql.append(" AND action <> 'GRANT'");
            }

            if(beforeTimestamp != null)
            {
                sql.append(" AND (observed_at_ms < ? OR (observed_at_ms = ? AND id < ?))");
                parameters.add(beforeTimestamp);
                parameters.add(beforeTimestamp);
                parameters.add(beforeId);
            }

            sql.append(" ORDER BY observed_at_ms DESC, id DESC LIMIT ")
                .append(candidateLimit).append(')');
        }

        sql.append(") candidate ORDER BY candidate.observed_at_ms DESC, candidate.id DESC LIMIT ")
            .append(candidateLimit).append(')');
    }

    Map<String,Object> conventional(StatsRequest request)
    {
        return read(connection -> page(queryConventional(connection, request, request.limit() + 1,
            request.offset()), request));
    }

    private static List<Map<String,Object>> queryConventional(Connection connection, StatsRequest request,
                                                               int limit, int offset) throws SQLException
    {
        StringBuilder sql = new StringBuilder("SELECT configured.*, " +
            "coalesce(configured.observed_primary_frequency_hz, configured.primary_frequency_hz, " +
            "min(summary.frequency_hz)) AS frequency_hz, min(summary.first_seen_ms) AS activity_first_seen_ms, " +
            "max(summary.last_seen_ms) AS activity_last_seen_ms, " +
            "coalesce(sum(summary.call_count), 0) AS logical_call_count, count(summary.context_id) AS summary_count, " +
            "(SELECT latest.last_event_type_code FROM conventional_activity_summary latest " +
            " WHERE latest.context_id = configured.context_id " +
            " ORDER BY latest.last_seen_ms DESC, latest.frequency_hz, latest.timeslot LIMIT 1) " +
            "AS last_event_type_code FROM (" + WebConfiguredEntityRepository.CONFIGURED_CHANNEL_SELECT +
            ") configured LEFT JOIN conventional_activity_summary summary " +
            "ON summary.context_id = configured.context_id WHERE configured.channel_kind = 'CONVENTIONAL'");
        List<Object> parameters = new ArrayList<>();

        if(request.search() != null)
        {
            sql.append(" AND (lower(coalesce(configured.configured_system, '') || ' ' || " +
                "coalesce(configured.configured_site, '') || ' ' || coalesce(configured.configured_name, '') || " +
                "' ' || coalesce(configured.alias_list_name, '') || ' ' || coalesce(configured.decoder, '')) " +
                "LIKE ? OR CAST(coalesce(configured.observed_primary_frequency_hz, " +
                "configured.primary_frequency_hz, summary.frequency_hz) AS TEXT) LIKE ?)");
            String like = like(request.search());
            parameters.add(like);
            parameters.add(like);
        }

        sql.append(" GROUP BY configured.configuration_row_id");
        sql.append(" ORDER BY ").append(order(request, CONVENTIONAL_SORT_COLUMNS, "frequency"))
            .append(", configured.configuration_row_id LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());

        for(Map<String,Object> row: rows)
        {
            StatsApiProtocol protocol = StatsApiProtocol.fromDecoder(textValue(row.get("decoder")));

            if(protocol == StatsApiProtocol.UNKNOWN)
            {
                throw new StatsApiException(500, "configuration_protocol_invalid",
                    "Configured channel has an unsupported primary decoder");
            }

            row.put("protocol_code", protocol.databaseCode());
            row.put("protocol", protocol.wireName());
            row.put("first_seen_ms", row.remove("activity_first_seen_ms"));
            row.put("last_seen_ms", row.remove("activity_last_seen_ms"));
            WebEntityRef.put(row, WebEntityRef.conventional(String.valueOf(row.get("configuration_id"))));
        }

        return rows;
    }

    Map<String,Object> conventionalDetail(StatsRequest request)
    {
        String configurationId = request.requiredText("configuration_id");
        return read(connection -> {
            Map<String,Object> response = new LinkedHashMap<>();
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireConventional(connection, configurationId);
            Map<String,Object> channel = configured.toApiMap();
            channel.put("capabilities", configured.protocol().conventionalCapabilities());
            response.put("channel", channel);
            List<Map<String,Object>> summaries = configured.contextId() == null ? List.of() : queryRows(connection, """
                SELECT %s FROM conventional_activity_summary summary
                WHERE summary.context_id = ? ORDER BY summary.frequency_hz, summary.timeslot
                LIMIT ? OFFSET ?
                """.formatted(CONVENTIONAL_ACTIVITY_PUBLIC_PROJECTION_SQL),
                configured.contextId(), request.limit() + 1, request.offset());
            boolean hasMore = summaries.size() > request.limit();

            if(hasMore)
            {
                summaries = new ArrayList<>(summaries.subList(0, request.limit()));
            }

            response.put("summaries", summaries);
            response.put("limit", request.limit());
            response.put("offset", request.offset());
            response.put("hasMore", hasMore);
            response.put("nextOffset", hasMore ? request.offset() + request.limit() : null);
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
        WebConfiguredEntityRepository.ConfiguredChannel configured = mConfiguredEntities.requireConventional(
            connection, request.requiredText("configuration_id"));

        if(configured.protocolCode() != 3 || configured.contextId() == null)
        {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
                SELECT context.id AS context_id, context.context_key, config.configuration_id,
                    config.alias_list_name, list.id AS alias_list_id,
                    summary.frequency_hz, summary.timeslot, summary.talkgroup_id,
                    summary.first_seen_ms, summary.last_seen_ms,
                    summary.call_count AS logical_call_count,
                    summary.encrypted_count AS encrypted_logical_call_count,
                    summary.last_source_radio_id
                FROM dmr_conventional_talkgroup_summary summary
                JOIN receiver_context context ON context.id = summary.context_id
                JOIN configuration_channel config ON config.configuration_id = ?
                LEFT JOIN alias_list list ON list.name = config.alias_list_name COLLATE NOCASE
                WHERE summary.context_id = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(configured.configurationId(), configured.contextId()));
        addDmrAliasSearch(sql, parameters, request.search(), "alias_talkgroup", "summary.talkgroup_id");
        sql.append(" ORDER BY ")
            .append(order(request, DMR_CONVENTIONAL_TALKGROUP_SORT_COLUMNS, "logical_call_count"))
            .append(", summary.last_seen_ms DESC, summary.frequency_hz ASC, summary.timeslot ASC, ")
            .append("summary.talkgroup_id ASC LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        mAliasResolver.enrichDmrTalkgroups(connection, rows, "talkgroup_id", "alias_");
        mAliasResolver.enrichDmrRadios(connection, rows, "last_source_radio_id", "last_source_alias_");

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, WebEntityRef.conventional(configured.configurationId()));
            row.put("entity_tab", "talkgroups");
        }

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
        WebConfiguredEntityRepository.ConfiguredChannel configured = mConfiguredEntities.requireConventional(
            connection, request.requiredText("configuration_id"));

        if(configured.protocolCode() != 3 || configured.contextId() == null)
        {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
                SELECT context.id AS context_id, context.context_key, config.configuration_id,
                    config.alias_list_name, list.id AS alias_list_id,
                    summary.frequency_hz, summary.timeslot, summary.radio_id,
                    summary.first_seen_ms, summary.last_seen_ms,
                    summary.call_count AS logical_call_count,
                    summary.source_call_count AS source_logical_call_count,
                    summary.target_call_count AS target_logical_call_count,
                    summary.group_call_count AS group_logical_call_count,
                    summary.private_call_count AS private_logical_call_count,
                    summary.encrypted_count AS encrypted_logical_call_count,
                    summary.last_talkgroup_id,
                    summary.last_peer_radio_id
                FROM dmr_conventional_radio_summary summary
                JOIN receiver_context context ON context.id = summary.context_id
                JOIN configuration_channel config ON config.configuration_id = ?
                LEFT JOIN alias_list list ON list.name = config.alias_list_name COLLATE NOCASE
                WHERE summary.context_id = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(configured.configurationId(), configured.contextId()));
        addDmrAliasSearch(sql, parameters, request.search(), "alias_radio", "summary.radio_id");
        sql.append(" ORDER BY ")
            .append(order(request, DMR_CONVENTIONAL_RADIO_SORT_COLUMNS, "logical_call_count"))
            .append(", summary.last_seen_ms DESC, summary.frequency_hz ASC, summary.timeslot ASC, ")
            .append("summary.radio_id ASC LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        mAliasResolver.enrichDmrRadios(connection, rows, "radio_id", "alias_");
        mAliasResolver.enrichDmrTalkgroups(connection, rows, "last_talkgroup_id", "last_talkgroup_alias_");
        mAliasResolver.enrichDmrRadios(connection, rows, "last_peer_radio_id", "last_peer_alias_");

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, WebEntityRef.conventional(configured.configurationId()));
            row.put("entity_tab", "radios");
        }

        return rows;
    }

    /**
     * One bounded directory row per durable trunked identity scope. A learned P25 system scope can own several
     * linked sites; P25 fail-open, DMR, and NXDN scopes each own one receiver context.
     */
    private static String scopeSummarySelect()
    {
        return """
            SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.identity_domain_code,
                scope.alias_list_id,
                %s AS alias_list_name,
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
                      AND identity.identity_kind_code IN (1, 3)) AS group_identities,
                (SELECT COUNT(*) FROM trunked_identity_summary identity
                    WHERE identity.scope_id = scope.scope_id
                      AND identity.identity_kind_code = 1) AS talkgroups,
                (SELECT COUNT(*) FROM trunked_identity_summary identity
                    WHERE identity.scope_id = scope.scope_id
                      AND identity.identity_kind_code = 3) AS patch_groups,
                (SELECT COUNT(*) FROM trunked_identity_summary identity
                    WHERE identity.scope_id = scope.scope_id
                      AND identity.identity_kind_code = 2) AS radios,
                (SELECT COUNT(*) FROM trunked_radio_affiliation affiliation
                    WHERE affiliation.scope_id = scope.scope_id) AS affiliated_radios,
                (SELECT group_concat(name, ', ') FROM (
                    SELECT DISTINCT coalesce(nullif(trim(config.site_name), ''),
                                             nullif(trim(config.name), ''),
                                             nullif(trim(context.channel_name), ''),
                                             nullif(trim(p25.channel_name), ''),
                                             nullif(trim(trunked.channel_name), '')) AS name
                    FROM trunked_identity_scope_context ownership
                    JOIN receiver_context context ON context.id = ownership.context_id
                    LEFT JOIN configuration_channel config
                      ON config.channel_kind = 'TRUNKED' AND config.radres_guid = context.guid
                    LEFT JOIN p25_site_snapshot p25 ON p25.guid = context.guid
                    LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                    WHERE ownership.scope_id = scope.scope_id
                    ORDER BY lower(name), name
                    LIMIT 8)) AS site_names,
                (SELECT count(DISTINCT coalesce(nullif(trim(config.site_name), ''),
                                                nullif(trim(config.name), ''),
                                                nullif(trim(context.channel_name), ''),
                                                nullif(trim(p25.channel_name), ''),
                                                nullif(trim(trunked.channel_name), '')))
                 FROM trunked_identity_scope_context ownership
                 JOIN receiver_context context ON context.id = ownership.context_id
                 LEFT JOIN configuration_channel config
                   ON config.channel_kind = 'TRUNKED' AND config.radres_guid = context.guid
                 LEFT JOIN p25_site_snapshot p25 ON p25.guid = context.guid
                 LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                 WHERE ownership.scope_id = scope.scope_id) AS site_name_count,
                CASE WHEN (SELECT count(DISTINCT coalesce(nullif(trim(config.site_name), ''),
                                                          nullif(trim(config.name), ''),
                                                          nullif(trim(context.channel_name), ''),
                                                          nullif(trim(p25.channel_name), ''),
                                                          nullif(trim(trunked.channel_name), '')))
                           FROM trunked_identity_scope_context ownership
                           JOIN receiver_context context ON context.id = ownership.context_id
                           LEFT JOIN configuration_channel config
                             ON config.channel_kind = 'TRUNKED' AND config.radres_guid = context.guid
                           LEFT JOIN p25_site_snapshot p25 ON p25.guid = context.guid
                           LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
                           WHERE ownership.scope_id = scope.scope_id) > 8
                    THEN 1 ELSE 0 END AS site_names_truncated
            FROM trunked_identity_scope scope
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            """.formatted(uniqueScopeAliasListExpression());
    }

    private static Map<String,Object> requireScope(Connection connection, String scopeToken) throws SQLException
    {
        return first(queryRows(connection, "WITH scoped AS (" + scopeSummarySelect() +
            ") SELECT * FROM scoped WHERE scope_token = ?", scopeToken), "System not found");
    }

    private static String selectedScopesCte(int scopeCount)
    {
        if(scopeCount < 1 || scopeCount > StatsRequest.MAX_LIMIT)
        {
            throw new IllegalArgumentException("Scope count is invalid");
        }

        String requestedScopes = String.join(", ", java.util.Collections.nCopies(scopeCount, "(?)"));
        return "WITH requested_scopes(scope_id) AS (VALUES " + requestedScopes + ")";
    }

    private static List<Map<String,Object>> queryScopeSites(Connection connection, long scopeId, StatsRequest request)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT scope.scope_id, scope.scope_token, scope.protocol_code,
                CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                    WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                'trunked' AS site_kind, config.configuration_id, config.radres_guid AS guid,
                scope.p25_system_key AS system_key,
                CASE WHEN scope.protocol_code = 1 THEN system.wacn END AS wacn,
                CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE trunked.system_id END AS system_id,
                CASE WHEN scope.protocol_code IN (3, 4) THEN trunked.network_id END AS network_id,
                nullif(trim(config.system_name), '') AS configured_system,
                nullif(trim(config.site_name), '') AS configured_site,
                nullif(trim(config.name), '') AS configured_name,
                coalesce(nullif(trim(config.name), ''), p25.channel_name, trunked.channel_name,
                    context.channel_name) AS channel_name,
                config.alias_list_name, alias_list.id AS alias_list_id, config.decoder_type AS decoder,
                CASE WHEN scope.protocol_code = 1 THEN coalesce(p25.nac, context.nac) END AS nac,
                CASE WHEN scope.protocol_code = 1 THEN coalesce(p25.rfss, context.rfss) END AS rfss,
                CASE WHEN scope.protocol_code = 1 THEN coalesce(p25.site, context.site)
                    ELSE trunked.site_id END AS site_id,
                CASE WHEN scope.protocol_code IN (3, 4) THEN trunked.ran END AS ran,
                CASE WHEN scope.protocol_code IN (3, 4) THEN trunked.variant_code END AS variant_code,
                scope.identity_domain_code,
                coalesce(p25.primary_frequency_hz, trunked.primary_frequency_hz,
                    context.primary_frequency_hz, config.primary_frequency_hz) AS primary_frequency_hz,
                coalesce(p25.current_control_hz, trunked.current_control_hz,
                    context.current_control_hz) AS current_control_hz,
                coalesce(p25.first_seen_ms, trunked.first_seen_ms, context.first_seen_ms) AS first_seen_ms,
                coalesce(p25.last_seen_ms, trunked.last_seen_ms, context.last_seen_ms) AS last_seen_ms,
                coalesce(p25.observation_count, trunked.observation_count, 0) AS observation_count,
                CASE WHEN scope.protocol_code = 1 THEN
                    (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                        THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                     FROM p25_site_channel_summary channel WHERE channel.guid = config.radres_guid)
                ELSE (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                      WHERE channel.guid = config.radres_guid) END AS channels,
                CASE WHEN scope.protocol_code = 1 THEN
                    (SELECT COUNT(*) FROM p25_site_neighbor neighbor
                     WHERE neighbor.guid = config.radres_guid)
                ELSE (SELECT COUNT(*) FROM trunked_site_neighbor_summary neighbor
                      WHERE neighbor.guid = config.radres_guid) END AS neighbors,
                CASE WHEN scope.protocol_code = 1 THEN
                    (SELECT COUNT(*) FROM p25_site_frequency_band band
                     WHERE band.guid = config.radres_guid) ELSE 0 END AS bands,
                CASE WHEN scope.protocol_code = 1 THEN
                    (SELECT COUNT(*) FROM p25_site_patch_group patch
                     WHERE patch.guid = config.radres_guid) ELSE 0 END AS patches
            FROM configuration_channel config
            JOIN receiver_context context ON context.context_key = 'GUID:' || config.radres_guid
            JOIN trunked_identity_scope_context ownership ON ownership.context_id = context.id
            JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
            LEFT JOIN alias_list ON alias_list.name = config.alias_list_name COLLATE NOCASE
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            LEFT JOIN p25_site_snapshot p25
                ON scope.protocol_code = 1 AND p25.guid = config.radres_guid
            LEFT JOIN trunked_site_snapshot trunked
                ON scope.protocol_code IN (3, 4) AND trunked.guid = config.radres_guid
                    AND trunked.protocol_code = scope.protocol_code
            WHERE config.channel_kind = 'TRUNKED' AND scope.scope_id = ?
            """);
        List<Object> parameters = new ArrayList<>(List.of(scopeId));

        if(request.search() != null)
        {
            sql.append(" AND (lower(coalesce(config.name, '')) LIKE ? OR " +
                "lower(coalesce(config.site_name, '')) LIKE ? OR lower(coalesce(config.system_name, '')) LIKE ? " +
                "OR lower(config.radres_guid) LIKE ?)");
            parameters.add(like(request.search()));
            parameters.add(like(request.search()));
            parameters.add(like(request.search()));
            parameters.add(like(request.search()));
        }

        sql.append(" ORDER BY ").append(order(request, SCOPED_SITE_SORT_COLUMNS, "last_seen"))
            .append(", config.radres_guid LIMIT ? OFFSET ?");
        parameters.add(request.limit() + 1);
        parameters.add(request.offset());
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, WebEntityRef.site(String.valueOf(row.get("guid"))));
        }

        return rows;
    }

    private static void attachSystemDirectorySitePreviews(Connection connection,
                                                           List<Map<String,Object>> systems,
                                                           String search) throws SQLException
    {
        if(systems.isEmpty())
        {
            return;
        }

        List<Object> parameters = new ArrayList<>(systems.size() + (search != null ? 4 : 2));
        systems.forEach(system -> parameters.add(number(system.get("scope_id"))));
        StringBuilder sql = new StringBuilder(selectedScopesCte(systems.size()));

        if(search != null)
        {
            sql.append(",\n").append(MATCHING_CONFIGURATION_GUID_CTE);
        }

        sql.append("""
            , site_preview_candidate AS (
                SELECT ownership.scope_id, scope.scope_token, scope.alias_list_id, scope.protocol_code,
                    CASE scope.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    context.guid, config.configuration_id,
                    nullif(trim(config.system_name), '') AS configured_system,
                    nullif(trim(config.site_name), '') AS configured_site,
                    nullif(trim(config.name), '') AS configured_name,
                    CASE WHEN scope.protocol_code = 1 THEN system.wacn END AS wacn,
                    CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE trunked.system_id END AS system_id,
                    coalesce(p25.channel_name, trunked.channel_name, context.channel_name) AS channel_name,
                    coalesce(context.alias_list_name, p25.alias_list_name, trunked.alias_list_name)
                        AS alias_list_name,
                    CASE WHEN scope.protocol_code = 1 THEN coalesce(p25.rfss, context.rfss) END AS rfss,
                    CASE WHEN scope.protocol_code = 1 THEN coalesce(p25.site, context.site)
                        ELSE trunked.site_id END AS site_id,
                    CASE WHEN scope.protocol_code IN (3, 4) THEN trunked.ran END AS ran,
                    coalesce(p25.current_control_hz, trunked.current_control_hz,
                        context.current_control_hz) AS current_control_hz,
                    coalesce(p25.last_seen_ms, trunked.last_seen_ms, context.last_seen_ms) AS last_seen_ms,
            """);

        if(search != null)
        {
            sql.append("""
                    CASE WHEN lower(coalesce(context.guid, '') || ' ' ||
                        coalesce(context.channel_name, '') || ' ' ||
                        coalesce(p25.channel_name, '') || ' ' ||
                        coalesce(trunked.channel_name, '') || ' ' ||
                        coalesce(trunked.configured_system, '') || ' ' ||
                        coalesce(CAST(trunked.network_id AS TEXT), '') || ' ' ||
                        coalesce(CAST(trunked.system_id AS TEXT), '') || ' ' ||
                        coalesce(CAST(trunked.site_id AS TEXT), '') || ' ' ||
                        coalesce(CAST(trunked.ran AS TEXT), '') || ' ' ||
                        coalesce(CAST(p25.rfss AS TEXT), '') || ' ' ||
                        coalesce(CAST(p25.site AS TEXT), '')) LIKE ?
                        OR matching_config.radres_guid IS NOT NULL
                        THEN 1 ELSE 0 END AS site_search_match
                """);
            String like = like(search);
            parameters.add(like);
            parameters.add(like);
        }
        else
        {
            sql.append("1 AS site_search_match\n");
        }

        sql.append("""
                FROM trunked_identity_scope_context ownership
                JOIN requested_scopes requested ON requested.scope_id = ownership.scope_id
                JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
                JOIN receiver_context context ON context.id = ownership.context_id
                JOIN configuration_channel config ON config.radres_guid = context.guid
                    AND config.channel_kind = 'TRUNKED'
                LEFT JOIN p25_site_snapshot p25
                    ON scope.protocol_code = 1 AND p25.guid = context.guid
                LEFT JOIN trunked_site_snapshot trunked
                    ON scope.protocol_code IN (3, 4) AND trunked.guid = context.guid
                LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            """);

        if(search != null)
        {
            sql.append("""
                LEFT JOIN matching_configuration_guid matching_config
                    ON matching_config.radres_guid = context.guid
                """);
        }

        sql.append("""
                WHERE scope.protocol_code IN (1, 3, 4)
            ),
            ranked_site_preview AS (
                SELECT candidate.*,
                    row_number() OVER (
                        PARTITION BY candidate.scope_id
                        ORDER BY candidate.site_search_match DESC, candidate.last_seen_ms DESC,
                            candidate.guid
                    ) AS site_preview_rank
                FROM site_preview_candidate candidate
            )
            SELECT preview.scope_id, preview.scope_token, preview.alias_list_id,
                preview.alias_list_name, preview.protocol_code, preview.protocol,
                'trunked' AS site_kind, preview.guid,
                preview.wacn, preview.system_id,
                preview.configured_system, preview.configured_site, preview.configured_name,
                preview.channel_name,
                preview.rfss, preview.site_id, preview.ran, preview.current_control_hz,
                CASE WHEN preview.protocol_code = 1 THEN
                    (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                        THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                     FROM p25_site_channel_summary channel WHERE channel.guid = preview.guid)
                ELSE
                    (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                     WHERE channel.guid = preview.guid)
                END AS channels,
                preview.last_seen_ms
            FROM ranked_site_preview preview
            WHERE preview.site_preview_rank <= ?
            ORDER BY preview.scope_id, preview.site_preview_rank
            LIMIT ?
            """);
        parameters.add(MAXIMUM_SYSTEM_DIRECTORY_SITE_PREVIEW);
        parameters.add(Math.multiplyExact(systems.size(), MAXIMUM_SYSTEM_DIRECTORY_SITE_PREVIEW));
        List<Map<String,Object>> previewRows = queryRows(connection, sql.toString(), parameters.toArray());
        Map<Long,List<Map<String,Object>>> previewsByScope = new LinkedHashMap<>();

        for(Map<String,Object> preview: previewRows)
        {
            WebEntityRef.put(preview, WebEntityRef.site(String.valueOf(preview.get("guid"))));
            long scopeId = number(preview.get("scope_id"));
            previewsByScope.computeIfAbsent(scopeId, ignored -> new ArrayList<>()).add(preview);
        }

        for(Map<String,Object> system: systems)
        {
            List<Map<String,Object>> preview = List.copyOf(
                previewsByScope.getOrDefault(number(system.get("scope_id")), List.of()));
            system.put("site_preview", preview);
            system.put("site_preview_truncated", number(system.get("sites")) > preview.size());
        }
    }

    private static String siteSelect()
    {
        return """
            SELECT site.guid, site.system_key, site.protocol, site.channel_name,
                nullif(trim(config.site_name), '') AS configured_site,
                nullif(trim(config.name), '') AS configured_name,
                CASE upper(json_extract(config.config_json, '$.decodeConfiguration.modulation'))
                    WHEN 'CQPSK' THEN 'CQPSK'
                    WHEN 'C4FM' THEN 'C4FM'
                    WHEN 'AUTO' THEN 'C4FM'
                    ELSE NULL END AS p25_decoder_mode,
                config.alias_list_name, alias_list.id AS alias_list_id,
                site.decoder, system.wacn, coalesce(system.system_id, site.system_id) AS system_id,
                site.nac, site.rfss, site.site AS site_id,
                site.lra, site.active_rfss_network_connection, site.mfid, site.broadcast_clock_ms,
                site.micro_slots, site.data_service,
                site.data_access, site.wuid_lease_minutes, site.registration_service, site.tdma,
                site.voice_service,
                site.primary_frequency_hz, site.current_control_hz, site.first_seen_ms, site.last_seen_ms,
                site.observation_count,
                coalesce(
                    (SELECT max(channel.callsign) FROM p25_site_channel channel
                     WHERE channel.guid = site.guid AND channel.downlink_hz = site.current_control_hz),
                    (SELECT max(channel.callsign) FROM p25_site_channel_summary channel
                     WHERE channel.guid = site.guid AND channel.downlink_hz = site.current_control_hz),
                    (SELECT channel.callsign FROM p25_site_channel channel
                     WHERE channel.guid = site.guid AND channel.callsign IS NOT NULL
                     ORDER BY channel.confirmed_at_ms DESC LIMIT 1),
                    (SELECT channel.callsign FROM p25_site_channel_summary channel
                     WHERE channel.guid = site.guid AND channel.callsign IS NOT NULL
                     ORDER BY channel.last_seen_ms DESC LIMIT 1)
                ) AS callsign,
                (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                    THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                    FROM p25_site_channel_summary channel WHERE channel.guid = site.guid) AS channels,
                (SELECT COUNT(*) FROM p25_site_neighbor neighbor WHERE neighbor.guid = site.guid) AS neighbors,
                (SELECT COUNT(*) FROM p25_site_frequency_band band WHERE band.guid = site.guid) AS bands,
                (SELECT COUNT(*) FROM p25_site_patch_group patch WHERE patch.guid = site.guid) AS patches
            FROM p25_site_snapshot site
            LEFT JOIN p25_system system ON system.system_key = site.system_key
            JOIN configuration_channel config ON config.radres_guid = site.guid
                AND config.channel_kind = 'TRUNKED'
            LEFT JOIN alias_list ON alias_list.name = config.alias_list_name COLLATE NOCASE
            """;
    }

    /**
     * Normalizes the identity fields needed by the protocol-agnostic control-channel quality views.  The quality
     * buckets remain in the deployed GUID-keyed table whose historical name starts with {@code p25_}; no schema
     * distinction is required because the receiver GUID is the shared identity.  Saved configuration gates which
     * GUIDs exist; this activity projection selects the newest retained observation and gives P25 an exact-time tie.
     */
    private static String qualitySiteSelect()
    {
        return """
            WITH candidates AS (
                SELECT site.guid, site.channel_name, site.nac, site.rfss, site.site,
                    site.current_control_hz, site.last_seen_ms AS site_last_seen_ms,
                    system.wacn, coalesce(system.system_id, site.system_id) AS system_id,
                    1 AS protocol_code, 'P25' AS protocol,
                    'trunked' AS site_kind, NULL AS configured_system, NULL AS network_id,
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
                CASE WHEN ranked.protocol_code = 1 THEN config.system_name
                    ELSE ranked.configured_system END AS configured_system,
                nullif(trim(config.site_name), '') AS configured_site,
                nullif(trim(config.name), '') AS configured_name, ranked.network_id, ranked.site_id,
                ranked.ran, ranked.variant_code, ranked.identity_domain_code
            FROM ranked
            JOIN configuration_channel config ON config.radres_guid = ranked.guid
                AND config.channel_kind = 'TRUNKED'
            WHERE ranked.identity_rank = 1
            """;
    }

    private static String trunkedSiteSelect()
    {
        return """
            SELECT site.guid, site.protocol_code,
                CASE site.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                'trunked' AS site_kind, site.variant_code, site.identity_domain_code,
                config.system_name AS configured_system,
                site.channel_name,
                nullif(trim(config.site_name), '') AS configured_site,
                nullif(trim(config.name), '') AS configured_name,
                config.alias_list_name, alias_list.id AS alias_list_id,
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
            JOIN configuration_channel config ON config.radres_guid = site.guid
                AND config.channel_kind = 'TRUNKED'
            LEFT JOIN alias_list ON alias_list.name = config.alias_list_name COLLATE NOCASE
            """;
    }

    /**
     * Resolves the configured site's one exact learned scope.  Configuration establishes the site, so no receiver
     * observation is a valid empty state.  Multiple exact owners are an integrity conflict, never a latest-wins
     * choice.
     */
    private static Map<String,Object> configuredSiteContext(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured) throws SQLException
    {
        if(configured.contextId() == null)
        {
            return null;
        }

        List<Map<String,Object>> rows = queryRows(connection, """
            SELECT context.id AS context_id, context.alias_list_name, scope.alias_list_id,
                scope.scope_id, scope.scope_token,
                scope.protocol_code, scope.scope_kind_code, scope.identity_domain_code,
                scope.p25_system_key AS system_key, system.wacn,
                CASE WHEN scope.protocol_code = 1 THEN system.system_id ELSE trunked.system_id END AS system_id,
                coalesce(p25.rfss, context.rfss) AS rfss,
                coalesce(p25.site, context.site) AS site
            FROM receiver_context context
            JOIN trunked_identity_scope_context ownership ON ownership.context_id = context.id
            JOIN trunked_identity_scope scope ON scope.scope_id = ownership.scope_id
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            LEFT JOIN p25_site_snapshot p25 ON p25.guid = context.guid
            LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
            WHERE context.id = ? AND context.kind_code = 1 AND scope.protocol_code = ?
              AND (? <> 1 OR scope.alias_list_id IS ?)
            ORDER BY scope.scope_id
            """, configured.contextId(), configured.protocolCode(), configured.protocolCode(),
            configured.aliasListId());

        if(rows.size() > 1)
        {
            throw new StatsApiException(409, "site_scope_conflict",
                "Configured site has more than one exact learned system scope");
        }

        if(rows.isEmpty())
        {
            return null;
        }

        Map<String,Object> row = rows.getFirst();

        if(configured.aliasListName() != null)
        {
            row.put("alias_list_name", configured.aliasListName());
        }

        return row;
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
            fromTimestamp, toTimestamp, identityRole,
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

            row.put("identity_role_code", identityRole);
            row.put("identity_role", identityRole == IDENTITY_ROLE_DESTINATION ? "Destination" : "Source");

            if(hasTrunkedScope)
            {
                WebEntityRef.put(row, identityReference(row, identityKind,
                    (int)number(row.get("identity_id"))));
            }
            else if(conventionalDmr && (identityKind == IDENTITY_KIND_TALKGROUP ||
                identityKind == IDENTITY_KIND_RADIO) &&
                row.get("configuration_id") instanceof String configurationId && !configurationId.isBlank())
            {
                WebEntityRef.put(row, WebEntityRef.conventional(configurationId));
                row.put("entity_tab", identityKind == IDENTITY_KIND_RADIO ? "radios" : "talkgroups");
            }
        }

        return rows;
    }

    /**
     * Returns the newest receiver contexts across both topologies. Trunked rows carry their decoded site metadata,
     * while conventional rows remain linkable even before they have produced their first summary bucket.
     */
    private static List<Map<String,Object>> recentReceivers(Connection connection) throws SQLException
    {
        List<Map<String,Object>> rows = queryRows(connection, """
            WITH candidates AS (
                SELECT coalesce(context.context_key, 'site:' || site.guid) AS receiver_key,
                    context.id AS context_id, context.context_key, site.guid,
                    1 AS protocol_code, 'P25' AS protocol, 'TRUNKED' AS channel_kind,
                    coalesce(context.channel_name, site.channel_name) AS channel_name,
                    coalesce(context.alias_list_name, site.alias_list_name) AS alias_list_name,
                    coalesce(context.decoder, site.decoder) AS decoder, NULL AS configured_system,
                    system.wacn, coalesce(system.system_id, site.system_id) AS system_id, NULL AS network_id,
                    coalesce(site.nac, context.nac) AS nac, site.rfss, NULL AS legacy_site,
                    coalesce(site.site, context.site) AS site_id, NULL AS ran,
                    NULL AS variant_code, NULL AS identity_domain_code,
                    coalesce(context.primary_frequency_hz, site.primary_frequency_hz) AS primary_frequency_hz,
                    coalesce(context.current_control_hz, site.current_control_hz) AS current_control_hz,
                    min(site.first_seen_ms, coalesce(context.first_seen_ms, site.first_seen_ms)) AS first_seen_ms,
                    max(site.last_seen_ms, coalesce(context.last_seen_ms, site.last_seen_ms)) AS last_seen_ms,
                    site.last_seen_ms AS metadata_last_seen_ms, site.observation_count,
                    (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                        THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                        FROM p25_site_channel_summary channel WHERE channel.guid = site.guid) AS channels,
                    (SELECT COUNT(*) FROM p25_site_neighbor neighbor WHERE neighbor.guid = site.guid) AS neighbors
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
                    NULL AS wacn, site.system_id, site.network_id, NULL AS nac, NULL AS rfss,
                    NULL AS legacy_site,
                    site.site_id, site.ran, site.variant_code, site.identity_domain_code,
                    coalesce(context.primary_frequency_hz, site.primary_frequency_hz) AS primary_frequency_hz,
                    coalesce(context.current_control_hz, site.current_control_hz) AS current_control_hz,
                    min(site.first_seen_ms, coalesce(context.first_seen_ms, site.first_seen_ms)) AS first_seen_ms,
                    max(site.last_seen_ms, coalesce(context.last_seen_ms, site.last_seen_ms)) AS last_seen_ms,
                    site.last_seen_ms AS metadata_last_seen_ms, site.observation_count,
                    (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                        WHERE channel.guid = site.guid) AS channels,
                    (SELECT COUNT(*) FROM trunked_site_neighbor_summary neighbor
                        WHERE neighbor.guid = site.guid) AS neighbors
                FROM trunked_site_snapshot site
                LEFT JOIN receiver_context context ON context.guid = site.guid AND context.kind_code = 1

                UNION ALL

                SELECT context.context_key AS receiver_key, context.id AS context_id, context.context_key,
                    context.guid,
                    CASE
                        WHEN context.kind_code = 10 THEN CASE WHEN context.protocol_code = 11 THEN 11 ELSE 10 END
                        WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 1
                        ELSE coalesce(context.protocol_code, 0)
                    END AS protocol_code,
                    CASE
                        WHEN context.kind_code = 10 AND context.protocol_code = 11 THEN 'AM'
                        WHEN context.kind_code = 10 THEN 'NBFM'
                        WHEN context.protocol_code IN (1, 2) OR context.kind_code = 2 THEN 'P25'
                        WHEN context.protocol_code = 3 THEN 'DMR'
                        WHEN context.protocol_code = 4 THEN 'NXDN'
                        ELSE 'Unknown'
                    END AS protocol,
                    'CONVENTIONAL' AS channel_kind, context.channel_name, context.alias_list_name,
                    context.decoder, NULL AS configured_system, NULL AS wacn, NULL AS system_id,
                    NULL AS network_id, context.nac, NULL AS rfss, NULL AS legacy_site,
                    NULL AS site_id, NULL AS ran,
                    NULL AS variant_code, NULL AS identity_domain_code, context.primary_frequency_hz,
                    context.current_control_hz, context.first_seen_ms, context.last_seen_ms,
                    context.last_seen_ms AS metadata_last_seen_ms, NULL AS observation_count,
                    0 AS channels, 0 AS neighbors
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
                    NULL AS network_id, context.nac, context.rfss, NULL AS legacy_site,
                    context.site AS site_id, NULL AS ran,
                    NULL AS variant_code, NULL AS identity_domain_code, context.primary_frequency_hz,
                    context.current_control_hz, context.first_seen_ms, context.last_seen_ms,
                    context.last_seen_ms AS metadata_last_seen_ms, NULL AS observation_count,
                    0 AS channels, 0 AS neighbors
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
            SELECT ranked.context_id, ranked.context_key, ranked.guid, config.configuration_id,
                ranked.protocol_code, ranked.protocol, ranked.channel_kind AS channel_kind, ranked.channel_name,
                nullif(trim(config.site_name), '') AS configured_site,
                nullif(trim(config.name), '') AS configured_name,
                ranked.alias_list_name, ranked.decoder,
                nullif(trim(config.system_name), '') AS configured_system, ranked.wacn,
                ranked.system_id, ranked.network_id, ranked.nac, ranked.rfss,
                ranked.site_id, ranked.ran, ranked.variant_code, ranked.identity_domain_code,
                ranked.primary_frequency_hz, ranked.current_control_hz, ranked.first_seen_ms,
                ranked.last_seen_ms, ranked.observation_count, ranked.channels, ranked.neighbors
            FROM ranked
            JOIN configuration_channel config ON config.channel_kind = ranked.channel_kind
             AND ((config.channel_kind = 'TRUNKED' AND config.radres_guid = ranked.guid)
                  OR (config.channel_kind = 'CONVENTIONAL'
                    AND ranked.context_key = 'CONFIGURATION:' || config.configuration_id))
            WHERE receiver_rank = 1
            ORDER BY ranked.last_seen_ms DESC, ranked.protocol_code, ranked.channel_kind,
                lower(coalesce(ranked.channel_name, ranked.context_key, ranked.guid))
            LIMIT 20
            """);

        for(Map<String,Object> row: rows)
        {
            WebEntityRef reference = "TRUNKED".equals(row.get("channel_kind")) ?
                WebEntityRef.site(String.valueOf(row.get("guid"))) :
                WebEntityRef.conventional(String.valueOf(row.get("configuration_id")));
            WebEntityRef.put(row, reference);
        }

        return rows;
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
        List<Map<String,Object>> rows = queryRows(connection, DASHBOARD_SOURCE_ACTIVITY_SQL + " LIMIT ?",
            firstHour, nextHour, DASHBOARD_SOURCE_LIMIT + 1);
        boolean hasMore = rows.size() > DASHBOARD_SOURCE_LIMIT;

        if(hasMore)
        {
            rows = new ArrayList<>(rows.subList(0, DASHBOARD_SOURCE_LIMIT));
        }

        for(Map<String,Object> row: rows)
        {
            if(row.get("configuration_id") instanceof String configurationId && !configurationId.isBlank())
            {
                WebEntityRef.put(row, WebEntityRef.conventional(configurationId));
            }
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("from_ms", firstHour);
        result.put("to_ms", System.currentTimeMillis());
        result.put("rows", rows);
        result.put("limit", DASHBOARD_SOURCE_LIMIT);
        result.put("hasMore", hasMore);
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
        long trunkedLogicalMetricStart = trunkedLogicalCallMetricsStartedAt(connection);
        long conventionalMetricStart = conventionalCallOutputMetricsStartedAt(connection);
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
            Map<String,Object> coverage = callActivityCoverage(group, trunkedLogicalMetricStart,
                conventionalMetricStart, firstHour, now);
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
                    long metricStart = callActivityMetricStartedAt(group, field, trunkedLogicalMetricStart,
                        conventionalMetricStart);
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
        result.put("trunked_logical_metric_start_ms", trunkedLogicalMetricStart);
        result.put("conventional_logical_metric_start_ms", conventionalMetricStart);
        result.put("coverage", coverage);
        result.put("metricCoverage", metricCoverage);
        result.put("totals", totals);
        result.put("breakdown", breakdown);
        result.put("series", series);
        return result;
    }

    private static Map<String,Object> callActivityCoverage(CallActivityGroup group,
                                                            long trunkedLogicalMetricStart,
                                                            long conventionalMetricStart, long firstHour, long now)
    {
        Map<String,Object> coverage = new LinkedHashMap<>();

        for(String field: CALL_ACTIVITY_FIELDS)
        {
            long metricStart = callActivityMetricStartedAt(group, field, trunkedLogicalMetricStart,
                conventionalMetricStart);
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
     * introduced with the conventional output metrics, so earlier empty buckets must not be presented as observed
     * zeros.
     */
    private static long callActivityMetricStartedAt(CallActivityGroup group, String field,
                                                     long trunkedLogicalMetricStart,
                                                     long conventionalMetricStart)
    {
        if(!group.collected())
        {
            return -1;
        }

        if("TRUNKED".equals(group.channelKind()))
        {
            return trunkedLogicalMetricStart > 0 ? trunkedLogicalMetricStart : -1;
        }

        if("recorded_logical_call_count".equals(field) ||
            "stream_submitted_logical_call_count".equals(field))
        {
            return conventionalMetricStart > 0 ? conventionalMetricStart : -1;
        }

        if("encrypted_logical_call_count".equals(field) && "CONVENTIONAL".equals(group.channelKind()))
        {
            if(group.protocolCode() == 10)
            {
                return -1;
            }

            return conventionalMetricStart > 0 ? conventionalMetricStart : -1;
        }

        if(group.protocolCode() == 4 && "CONVENTIONAL".equals(group.channelKind()))
        {
            return conventionalMetricStart > 0 ? conventionalMetricStart : -1;
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

    private static String observationCountField(String databaseField)
    {
        if(databaseField == null || !databaseField.endsWith("_count"))
        {
            throw new IllegalArgumentException("Observation count field must end with _count");
        }

        return databaseField.substring(0, databaseField.length() - "_count".length()) +
            "_observation_count";
    }

    private static long trunkedLogicalCallMetricsStartedAt(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COALESCE((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?), 0)
            """, P25ActivityLogSchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY);
    }

    private static long conventionalCallOutputMetricsStartedAt(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COALESCE((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?), 0)
            """, P25ActivityLogSchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY);
    }

    private static long scopeMetricStartedAt(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COALESCE((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?), 0)
            """, P25ActivityLogSchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY);
    }

    private static int targetKind(StatsRequest request)
    {
        return targetKind(request.text("kind"));
    }

    private static int targetKind(String kind)
    {

        if(kind == null || "talkgroup".equals(kind))
        {
            return IDENTITY_KIND_TALKGROUP;
        }

        if("patch_group".equals(kind))
        {
            return IDENTITY_KIND_PATCH_GROUP;
        }

        throw new StatsApiException(400, "invalid_parameter",
            "kind must be talkgroup or patch_group", "kind");
    }

    /**
     * A learned exact scope owns identity lookup.  Retained activity is optional, but protocol-reserved addresses do
     * not become public entities merely because they are numerically positive.
     */
    private static void requireValidIdentity(Map<String,Object> scope, int identityKind, int identifier,
                                             String notFoundMessage)
    {
        if(!validIdentity(scope, identityKind, identifier))
        {
            throw new StatsApiException(404, notFoundMessage);
        }
    }

    private static boolean validIdentity(Map<String,Object> scope, int identityKind, int identifier)
    {
        int protocolCode = (int)number(scope.get("protocol_code"));
        Protocol protocol = switch(protocolCode)
        {
            case 1 -> Protocol.APCO25;
            case 3 -> Protocol.DMR;
            case 4 -> Protocol.NXDN;
            default -> Protocol.UNKNOWN;
        };
        TrunkedIdentityDomain domain = switch((int)number(scope.get("identity_domain_code")))
        {
            case 1 -> TrunkedIdentityDomain.NXDN_TYPE_C;
            case 2 -> TrunkedIdentityDomain.NXDN_TYPE_D;
            default -> TrunkedIdentityDomain.STANDARD;
        };
        Form form = switch(identityKind)
        {
            case IDENTITY_KIND_TALKGROUP -> Form.TALKGROUP;
            case IDENTITY_KIND_PATCH_GROUP -> Form.PATCH_GROUP;
            case IDENTITY_KIND_RADIO -> Form.RADIO;
            default -> null;
        };

        return TrunkedIdentityEligibility.isEligible(protocol, domain, form, identifier);
    }

    private static WebEntityRef identityReference(Map<String,Object> scope, int identityKind, int identifier)
    {
        if(!validIdentity(scope, identityKind, identifier))
        {
            return null;
        }

        String scopeToken = textValue(scope.get("scope_token"));

        if(scopeToken.isBlank())
        {
            return null;
        }

        return switch(identityKind)
        {
            case IDENTITY_KIND_TALKGROUP -> WebEntityRef.talkgroup(scopeToken, identifier);
            case IDENTITY_KIND_PATCH_GROUP -> WebEntityRef.patchGroup(scopeToken, identifier);
            case IDENTITY_KIND_RADIO -> WebEntityRef.radio(scopeToken, identifier);
            default -> null;
        };
    }

    /** Creates the stable lookup page for a valid identity that has no retained activity summary. */
    private static Map<String,Object> emptyIdentity(Map<String,Object> scope, int identityKind, int identifier)
    {
        Map<String,Object> identity = new LinkedHashMap<>();

        for(String field: List.of("scope_id", "scope_token", "protocol_code", "identity_domain_code",
            "alias_list_id", "alias_list_name", "protocol", "system_key", "wacn", "system_id", "network_id",
            "configured_system"))
        {
            if(scope.get(field) != null)
            {
                identity.put(field, scope.get(field));
            }
        }

        identity.put("identity_kind_code", identityKind);
        identity.put("identity_id", identifier);
        identity.put(identityKind == IDENTITY_KIND_RADIO ? "radio_id" : "talkgroup_id", identifier);
        identity.put("target_kind_code", identityKind);
        identity.put("p25_identity_state_code", 0);
        identity.put("logical_call_count", 0L);
        identity.put("source_logical_call_count", 0L);
        identity.put("target_logical_call_count", 0L);
        identity.put("encrypted_logical_call_count", 0L);
        identity.put("recorded_logical_call_count", 0L);
        identity.put("stream_submitted_logical_call_count", 0L);
        identity.put("signaling_observation_count", 0L);

        for(String field: TALKGROUP_SIGNALING_FIELDS)
        {
            identity.put(observationCountField(field), 0L);
        }

        if(identityKind != IDENTITY_KIND_RADIO)
        {
            identity.put("radios", 0L);
            identity.put("affiliated_radios", 0L);
            identity.put("affiliated_sites", 0L);
            identity.put("site_observation_count", 0L);
        }

        return identity;
    }

    private static boolean booleanParameter(StatsRequest request, String name, boolean defaultValue)
    {
        return request.booleanValue(name, defaultValue);
    }

    private static Map<String,Boolean> systemCapabilities(int protocolCode)
    {
        return StatsApiProtocol.fromCode(protocolCode).systemCapabilities();
    }

    private static Map<String,Boolean> talkgroupCapabilities(int protocolCode, int identityKind)
    {
        return StatsApiProtocol.fromCode(protocolCode)
            .groupIdentityCapabilities(identityKind == IDENTITY_KIND_PATCH_GROUP);
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
            FROM trunked_signaling_activity_bucket bucket
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
                result.add(Map.of("action", field.replace("_count", "").toUpperCase(),
                    "observation_count", count));
            }
        }

        result.sort((left, right) -> Long.compare(number(right.get("observation_count")),
            number(left.get("observation_count"))));
        return result;
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Long nullableNumber(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
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
            if(number(row.get("encrypted_logical_call_count")) == 0)
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
     * A protocol-neutral scope can own several receiver contexts. DMR/NXDN alias resolution is safe only when every
     * nonblank owner names the same Alias List; a null result deliberately suppresses arbitrary alias decoration.
     */
    private static String uniqueScopeAliasListExpression()
    {
        return """
            (SELECT CASE
                WHEN count(DISTINCT lower(coalesce(nullif(trim(context.alias_list_name), ''),
                                                   nullif(trim(site.alias_list_name), '')))) = 1
                    THEN min(coalesce(nullif(trim(context.alias_list_name), ''),
                                      nullif(trim(site.alias_list_name), '')))
                ELSE NULL
             END
             FROM trunked_identity_scope_context ownership
             JOIN receiver_context context ON context.id = ownership.context_id
             LEFT JOIN trunked_site_snapshot site ON site.guid = context.guid
             WHERE ownership.scope_id = scope.scope_id
               AND coalesce(nullif(trim(context.alias_list_name), ''),
                            nullif(trim(site.alias_list_name), '')) IS NOT NULL)
            """.strip();
    }

    /** Exact beats range; duplicate exacts and otherwise tied ranges prefer the newest alias ID. */
    private static String aliasWinnerOrder()
    {
        return """
            CASE WHEN identifier.ranged = 0 THEN 1 ELSE 0 END DESC,
            CASE WHEN identifier.ranged <> 0 THEN identifier.min_value END DESC,
            CASE WHEN identifier.ranged <> 0 THEN identifier.max_value END DESC,
            alias.id DESC
            """.strip();
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
               AND identifier.alias_list_name = context.alias_list_name COLLATE NOCASE
               AND ((identifier.ranged <> 0 AND %s BETWEEN identifier.min_value AND identifier.max_value)
                 OR (identifier.ranged = 0 AND identifier.value = %s))
             ORDER BY %s
             LIMIT 1)
            """.formatted(aliasColumn, identifierTable, identifierColumn, identifierColumn,
            aliasWinnerOrder()).strip();
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
                       AND assigned.alias_list_name = identifier.alias_list_name COLLATE NOCASE
                       AND trim(assigned.alias_list_name) <> '')
             ORDER BY %s
             LIMIT 1)
            """.formatted(aliasColumn, identifierTable, identifierColumn, identifierColumn,
            aliasWinnerOrder()).strip();
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
                           AND assigned.alias_list_name = identifier.alias_list_name COLLATE NOCASE
                           AND trim(assigned.alias_list_name) <> ''))
                 OR
                 (scope.protocol_code IN (3, 4)
                   AND identifier.alias_list_name = %s COLLATE NOCASE)
               )
             ORDER BY %s
             LIMIT 1)
            """.formatted(aliasColumn, identifierTable, protocol, identifierColumn, identifierColumn,
            uniqueScopeAliasListExpression(), aliasWinnerOrder()).strip();
    }

    private static String order(StatsRequest request, Map<String,String> columns, String defaultSort)
    {
        String requested = request.sort(defaultSort);
        String column = columns.get(requested);

        if(column == null)
        {
            throw new StatsApiException(400, "invalid_parameter", "sort is not supported", "sort");
        }

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

    private static void addTalkerAliasSearch(StringBuilder sql, List<Object> parameters, String search)
    {
        if(search != null)
        {
            sql.append("""
                 AND (CAST(summary.identity_id AS TEXT) LIKE ?
                   OR (scope.protocol_code = 4 AND scope.identity_domain_code = 2
                     AND printf('%02d-%04d', ((summary.identity_id >> 11) & 31),
                       (summary.identity_id & 2047)) LIKE ?)
                   OR lower(summary.last_talker_alias) LIKE ?)
                """);
            String like = like(search);
            parameters.add(like);
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
        return page(queriedRows, request.limit(), request.offset());
    }

    private static Map<String,Object> page(List<Map<String,Object>> queriedRows, int limit, int offset)
    {
        boolean hasMore = queriedRows.size() > limit;
        List<Map<String,Object>> rows = hasMore ? new ArrayList<>(queriedRows.subList(0, limit)) : queriedRows;
        Map<String,Object> page = new LinkedHashMap<>();
        page.put("rows", rows);
        page.put("limit", limit);
        page.put("offset", offset);
        page.put("hasMore", hasMore);
        page.put("nextOffset", hasMore ? offset + limit : null);
        return page;
    }

    private static Map<String,Object> cursorPage(List<Map<String,Object>> queriedRows, int limit)
    {
        boolean hasMore = queriedRows.size() > limit;
        List<Map<String,Object>> rows = hasMore ? new ArrayList<>(queriedRows.subList(0, limit)) : queriedRows;
        Object nextBeforeId = hasMore && !rows.isEmpty() ? rows.get(rows.size() - 1).get("id") : null;
        Map<String,Object> page = new LinkedHashMap<>();
        page.put("rows", rows);
        page.put("limit", limit);
        page.put("hasMore", hasMore);
        page.put("nextBeforeId", nextBeforeId);
        return page;
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

    private static Map<String,ActivityAction> activityActionMap()
    {
        Map<String,ActivityAction> actions = new LinkedHashMap<>();

        for(ActivityAction action: DASHBOARD_ACTIVITY_ACTIONS)
        {
            actions.put(action.name(), action);
        }

        return Map.copyOf(actions);
    }

    private static String activityActionAggregateSql(String table, String index)
    {
        String sums = DASHBOARD_ACTIVITY_ACTIONS.stream()
            .map(action -> "COALESCE(SUM(bucket." + action.column() + "), 0) AS " + action.column())
            .collect(java.util.stream.Collectors.joining(",\n                "));
        return "SELECT " + sums + "\nFROM " + table + " AS bucket INDEXED BY " + index +
            "\nWHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?";
    }

    private record ActivityAction(String name, int code, String column)
    {
    }

    private record DashboardActivityWindow(String range, long fromMilliseconds, long untilMilliseconds,
                                           long toMilliseconds)
    {
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
