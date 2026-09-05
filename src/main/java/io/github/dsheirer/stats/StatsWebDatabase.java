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
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionDisplay;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
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
import java.util.Locale;
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
    static final int MAXIMUM_RADIO_SYSTEM_DIRECTORY_WITH_CHANNEL_PREVIEW = 25;
    static final int MAXIMUM_RADIO_SYSTEM_DIRECTORY_CHANNEL_PREVIEW = 25;
    private static final int IDENTITY_ROLE_DESTINATION = ReceiverActivitySchema.IDENTITY_ROLE_DESTINATION;
    private static final int IDENTITY_ROLE_SOURCE = ReceiverActivitySchema.IDENTITY_ROLE_SOURCE;
    private static final int IDENTITY_KIND_CHANNEL_OR_UNKNOWN =
        ReceiverActivitySchema.IDENTITY_KIND_CHANNEL_OR_UNKNOWN;
    private static final int IDENTITY_KIND_TALKGROUP = ReceiverActivitySchema.IDENTITY_KIND_TALKGROUP;
    private static final int IDENTITY_KIND_RADIO = ReceiverActivitySchema.IDENTITY_KIND_RADIO;
    private static final int IDENTITY_KIND_PATCH_GROUP = ReceiverActivitySchema.IDENTITY_KIND_PATCH_GROUP;
    private static final String IDENTITY_KEY_SQL = "'v1-' || CASE %1$s.identity_kind_code " +
        "WHEN 1 THEN 'g' WHEN 2 THEN 'r' WHEN 3 THEN 'p' END || '-' || " +
        "CASE WHEN %1$s.home_wacn = -1 THEN 'x' ELSE printf('%%05x', %1$s.home_wacn) END || '-' || " +
        "CASE WHEN %1$s.home_system_id = -1 THEN 'x' ELSE printf('%%03x', %1$s.home_system_id) END || '-' || " +
        "%1$s.identity_id";
    static final String DASHBOARD_CALL_ACTIVITY_SQL = """
        SELECT bucket.bucket_start_ms AS time_ms,
            system.protocol_code,
            'TRUNKED' AS channel_kind,
            SUM(bucket.logical_call_count) AS logical_call_count,
            SUM(bucket.recorded_output_count) AS recorded_logical_call_count,
            SUM(bucket.streamed_output_count) AS stream_submitted_logical_call_count,
            SUM(bucket.encrypted_logical_call_count) AS encrypted_logical_call_count
        FROM trunked_logical_call_bucket AS bucket INDEXED BY idx_trunked_logical_call_bucket_time
        JOIN radio_system system ON system.id = bucket.radio_system_id
        WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
        GROUP BY bucket.bucket_start_ms, system.protocol_code

        UNION ALL

        SELECT bucket.bucket_start_ms AS time_ms,
            CASE config.decoder_type
                WHEN 'DMR' THEN 3 WHEN 'NXDN' THEN 4 WHEN 'NBFM' THEN 10 WHEN 'AM' THEN 11
                ELSE 1 END AS protocol_code,
            'CONVENTIONAL' AS channel_kind,
            SUM(bucket.call_count) AS logical_call_count,
            SUM(bucket.recorded_count) AS recorded_logical_call_count,
            SUM(bucket.streamed_count) AS stream_submitted_logical_call_count,
            SUM(bucket.encrypted_count) AS encrypted_logical_call_count
        FROM receiver_channel channel
        JOIN conventional_activity_bucket AS bucket INDEXED BY idx_conventional_bucket_dashboard_time
            ON bucket.channel_id = channel.id
        JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
        WHERE config.channel_kind = 'CONVENTIONAL'
          AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
        GROUP BY bucket.bucket_start_ms,
            CASE config.decoder_type
                WHEN 'DMR' THEN 3 WHEN 'NXDN' THEN 4 WHEN 'NBFM' THEN 10 WHEN 'AM' THEN 11
                ELSE 1 END
        ORDER BY time_ms, protocol_code, channel_kind
        """;
    static final String DASHBOARD_SOURCE_ACTIVITY_SQL = """
        WITH source_activity AS (
            SELECT channel.id AS channel_id, config.configuration_id,
                CASE config.decoder_type
                    WHEN 'DMR' THEN 3 WHEN 'NXDN' THEN 4 WHEN 'NBFM' THEN 10 WHEN 'AM' THEN 11
                    ELSE 1 END AS protocol_code,
                'CONVENTIONAL' AS channel_kind,
                config.decoder_type AS decoder, config.primary_frequency_hz,
                system.system_key AS radio_system_key,
                system.p25_wacn AS wacn, system.p25_system_id AS system_id,
                nullif(trim(config.system_name), '') AS system_name,
                nullif(trim(config.site_name), '') AS site_name,
                nullif(trim(config.name), '') AS name,
                NULL AS current_control_hz, NULL AS network_id, NULL AS site_id, NULL AS ran,
                SUM(bucket.call_count) AS logical_call_count,
                SUM(bucket.recorded_count) AS recorded_logical_call_count,
                SUM(bucket.streamed_count) AS stream_submitted_logical_call_count,
                SUM(bucket.encrypted_count) AS encrypted_logical_call_count
            FROM receiver_channel channel
            JOIN conventional_activity_bucket AS bucket INDEXED BY idx_conventional_bucket_dashboard_time
                ON bucket.channel_id = channel.id
            JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            WHERE config.channel_kind = 'CONVENTIONAL'
              AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
            GROUP BY channel.id,
                CASE config.decoder_type
                    WHEN 'DMR' THEN 3 WHEN 'NXDN' THEN 4 WHEN 'NBFM' THEN 10 WHEN 'AM' THEN 11
                    ELSE 1 END,
                config.configuration_id, config.name, config.decoder_type, config.primary_frequency_hz,
                system.system_key, system.p25_wacn, system.p25_system_id, config.system_name,
                config.site_name
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
            lower(coalesce(name, configuration_id))
        """;
    static final String DASHBOARD_IDENTITY_ACTIVITY_SQL = """
        WITH identity_activity AS (
            SELECT NULL AS channel_id, NULL AS configuration_id,
                system.protocol_code,
                CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                    WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                'TRUNKED' AS channel_kind, NULL AS name, NULL AS site_name,
                NULL AS alias_list_name, NULL AS alias_list_id, NULL AS decoder,
                NULL AS primary_frequency_hz, NULL AS current_control_hz,
                system.system_key AS radio_system_key, system.p25_wacn AS wacn,
                system.p25_system_id AS system_id,
                NULL AS rfss, NULL AS system_name,
                NULL AS network_id, NULL AS site_id, NULL AS ran, NULL AS variant_code,
                system.address_domain_code,
                summary.id AS identity_summary_id, summary.identity_kind_code,
                summary.identity_id AS native_id,
                summary.home_wacn, summary.home_system_id,
                %s AS identity_key,
                CASE summary.identity_kind_code WHEN 1 THEN 'Talkgroup' WHEN 2 THEN 'Radio'
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
            JOIN radio_system system ON system.id = bucket.radio_system_id
            JOIN radio_system_identity_summary summary
              ON summary.radio_system_id = bucket.radio_system_id
             AND summary.id = bucket.identity_summary_id
            WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
              AND bucket.identity_role_code = ?
            GROUP BY bucket.radio_system_id, summary.id

            UNION ALL

            SELECT bucket.channel_id, config.configuration_id,
                CASE config.decoder_type
                    WHEN 'DMR' THEN 3 WHEN 'NXDN' THEN 4 WHEN 'NBFM' THEN 10 WHEN 'AM' THEN 11
                    ELSE 1 END AS protocol_code,
                CASE config.decoder_type WHEN 'DMR' THEN 'DMR' WHEN 'NXDN' THEN 'NXDN'
                    WHEN 'NBFM' THEN 'NBFM' WHEN 'AM' THEN 'AM' ELSE 'P25' END AS protocol,
                'CONVENTIONAL' AS channel_kind, nullif(trim(config.name), '') AS name,
                nullif(trim(config.site_name), '') AS site_name, alias_list.name AS alias_list_name,
                config.alias_list_id, config.decoder_type AS decoder, config.primary_frequency_hz,
                NULL AS current_control_hz, system.system_key AS radio_system_key,
                system.p25_wacn AS wacn, system.p25_system_id AS system_id, NULL AS rfss,
                nullif(trim(config.system_name), '') AS system_name,
                NULL AS network_id, NULL AS site_id, NULL AS ran,
                NULL AS variant_code, coalesce(system.address_domain_code, 0) AS address_domain_code,
                NULL AS identity_summary_id, bucket.identity_kind_code,
                bucket.identity_id AS native_id,
                NULL AS home_wacn, NULL AS home_system_id, NULL AS identity_key,
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
            JOIN receiver_channel channel ON channel.id = bucket.channel_id
            JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            LEFT JOIN alias_list ON alias_list.id = config.alias_list_id
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            WHERE config.channel_kind = 'CONVENTIONAL'
              AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
              AND bucket.identity_role_code = ?
            GROUP BY bucket.channel_id, config.configuration_id, config.system_name,
                config.site_name, config.name,
                bucket.identity_kind_code, bucket.identity_id
        )
        SELECT * FROM identity_activity
        ORDER BY logical_call_count DESC, last_active_ms DESC, protocol_code, channel_kind,
            identity_kind_code, native_id
        LIMIT ?
        """.formatted(IDENTITY_KEY_SQL.formatted("summary"));
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
        SELECT activity.id, activity.channel_id, activity.configuration_id,
            activity.observed_at_ms, activity.channel_kind,
            activity.channel_kind_code,
            CASE WHEN activity.protocol_code = 11 THEN 'AM' ELSE activity.protocol END AS protocol,
            activity.action, activity.event_type,
            activity.source_observed_local_id AS source_radio_id,
            activity.target_observed_local_id AS target_id,
            activity.source_identity_key, activity.target_identity_key,
            activity.source_identity_kind_code, activity.target_identity_kind_code,
            activity.source_identity_id AS source_native_id,
            activity.target_identity_id AS target_native_id,
            activity.target_kind_code, activity.target_kind,
            activity.frequency_hz, activity.lcn, activity.timeslot, activity.encrypted,
            activity.encryption_algorithm_id, activity.encryption_key_id,
            activity.resolved_channel_name AS name,
            activity.resolved_alias_list_name AS alias_list_name,
            config.alias_list_id,
            activity.resolved_system_key AS radio_system_key, system.address_domain_code,
            coalesce(system.protocol_code, activity.protocol_code) AS protocol_code,
            activity.resolved_wacn AS wacn, activity.resolved_system_id AS system_id,
            activity.resolved_nac AS nac, activity.resolved_rfss AS rfss,
            activity.resolved_site AS site_id
        """;
    private static final String ACTIVITY_RELATED_JOINS_SQL = """
        LEFT JOIN configuration_channel config ON config.configuration_id = activity.configuration_id
        LEFT JOIN radio_system system ON system.system_key = activity.resolved_system_key
        """;
    static final String ACTIVITY_SELECT_SQL = ACTIVITY_PROJECTION_SQL + """
        FROM receiver_activity_event_resolved activity
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
    private static final List<String> GROUP_IDENTITY_SIGNALING_FIELDS = List.of(
        "grant_count", "join_count", "register_count", "active_count", "continue_count", "denial_count",
        "emergency_count", "request_count", "busy_count", "queued_count", "acknowledge_count",
        "check_count", "check_ack_count", "page_count", "status_count", "gps_count", "logout_count",
        "patch_count", "patch_create_count", "patch_cancel_count", "data_count", "unknown_count"
    );
    private static final List<String> IDENTITY_EVIDENCE_FIELDS = GROUP_IDENTITY_SIGNALING_FIELDS.stream()
        .filter(field -> !"continue_count".equals(field) && !"unknown_count".equals(field))
        .toList();
    private static final String GROUP_IDENTITY_SIGNALING_COUNT_SQL = IDENTITY_EVIDENCE_FIELDS.stream()
        .map(field -> "summary." + field)
        .collect(java.util.stream.Collectors.joining(" + "));
    private static final String OTHER_GROUP_IDENTITY_SIGNALING_COUNT_SQL =
        "summary.acknowledge_count + summary.active_count + summary.busy_count + " +
            "summary.check_count + summary.check_ack_count + summary.continue_count + " +
            "summary.gps_count + summary.page_count + summary.patch_count + " +
            "summary.patch_cancel_count + summary.patch_create_count + summary.queued_count + " +
            "summary.request_count + summary.status_count + summary.unknown_count";
    private static final String TRUNKED_IDENTITY_OBSERVATION_PROJECTION_SQL =
        GROUP_IDENTITY_SIGNALING_FIELDS.stream()
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
        """.formatted(TRUNKED_IDENTITY_OBSERVATION_PROJECTION_SQL, GROUP_IDENTITY_SIGNALING_COUNT_SQL).strip();
    private static final String TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL = """
        summary.id AS identity_summary_id, summary.identity_kind_code, summary.identity_id AS native_id,
                    summary.home_wacn, summary.home_system_id,
                    %s AS identity_key,
                    summary.first_seen_ms, summary.last_seen_ms,
                    %s,
                    summary.last_encryption_algorithm_id, summary.last_encryption_key_id,
                    summary.last_talker_alias, summary.last_talker_alias_seen_ms
        """.formatted(IDENTITY_KEY_SQL.formatted("summary"), TRUNKED_IDENTITY_METRIC_PROJECTION_SQL).strip();
    private static final String TRUNKED_RELATIONSHIP_OBSERVATION_PROJECTION_SQL =
        GROUP_IDENTITY_SIGNALING_FIELDS.stream()
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
        GROUP_IDENTITY_SIGNALING_FIELDS.stream()
            .map(field -> "summary." + field + " AS " + observationCountField(field))
            .collect(java.util.stream.Collectors.joining(",\n                    "));
    private static final String CONVENTIONAL_ACTIVITY_PUBLIC_PROJECTION_SQL = """
        summary.channel_id, summary.frequency_hz, summary.timeslot,
                    summary.first_seen_ms, summary.last_seen_ms,
                    summary.call_count AS logical_call_count,
                    summary.encrypted_count AS encrypted_logical_call_count,
                    summary.recorded_count AS recorded_logical_call_count,
                    summary.streamed_count AS stream_submitted_logical_call_count,
                    summary.last_event_type_code,
                    %s
        """.formatted(CONVENTIONAL_ACTIVITY_OBSERVATION_PROJECTION_SQL).strip();
    private static final String CURRENT_RELATIONSHIP_AFFILIATION_SQL =
        "relationship.group_kind_code = 1 AND affiliation.radio_identity_id IS NOT NULL " +
            "AND affiliation.talkgroup_identity_id = relationship.group_identity_id";
    private static final String RADIO_CHANNEL_SORT_SQL = "CASE WHEN presence.channel_id IS NULL THEN NULL " +
        "WHEN system.protocol_code = 1 THEN printf('%03d:%03d', " +
        "coalesce(presence_p25.rfss, -1), coalesce(presence_p25.site, -1)) " +
        "ELSE printf('%010d', coalesce(presence_trunked.site_id, -1)) END || char(0) || " +
        "lower(coalesce(nullif(trim(presence_config.site_name), ''), " +
        "nullif(trim(presence_config.name), ''), presence_config.configuration_id, ''))";
    private static final Map<String,String> SYSTEM_SORT_COLUMNS = Map.ofEntries(
        Map.entry("wacn", "wacn"),
        Map.entry("system_id", "system_id"),
        Map.entry("channel_names", "lower(channel_names)"),
        Map.entry("channels", "channels"),
        Map.entry("talkgroups", "talkgroups"),
        Map.entry("radios", "radios"),
        Map.entry("affiliated_radios", "affiliated_radios"),
        Map.entry("first_seen", "first_seen_ms"),
        Map.entry("last_seen", "last_seen_ms")
    );
    private static final Map<String,String> RADIO_SYSTEM_CHANNEL_SORT_COLUMNS = Map.ofEntries(
        Map.entry("radio_system", "radio_system_key"),
        Map.entry("rfss", "rfss"),
        Map.entry("site", "site_id"),
        Map.entry("name", "lower(coalesce(nullif(trim(config.name), ''), nullif(trim(config.site_name), ''), " +
            "config.name))"),
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
    private static final Map<String,String> GROUP_IDENTITY_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.identity_id"),
        Map.entry("group_identity", "summary.identity_id"),
        Map.entry("kind", "summary.identity_kind_code"),
        Map.entry("alias", radioSystemAliasSortExpression("alias_talkgroup", "summary.identity_id", "name")),
        Map.entry("name", radioSystemAliasSortExpression("alias_talkgroup", "summary.identity_id", "name")),
        Map.entry("alias_group", radioSystemAliasSortExpression("alias_talkgroup", "summary.identity_id", "group_name")),
        Map.entry("logical_call_count", "summary.logical_call_count"),
        Map.entry("recorded_logical_call_count", "summary.recorded_output_count"),
        Map.entry("stream_submitted_logical_call_count", "summary.streamed_output_count"),
        Map.entry("grant_observation_count", "summary.grant_count"),
        Map.entry("join_observation_count", "summary.join_count"),
        Map.entry("signaling_observation_count", "signaling_observation_count"),
        Map.entry("encrypted_logical_call_count", "summary.encrypted_logical_call_count"),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> RADIO_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.identity_id"),
        Map.entry("radio", "summary.identity_id"),
        Map.entry("alias", radioSystemAliasSortExpression("alias_radio", "summary.identity_id", "name")),
        Map.entry("name", radioSystemAliasSortExpression("alias_radio", "summary.identity_id", "name")),
        Map.entry("talker_alias", "lower(summary.last_talker_alias)"),
        Map.entry("talker_alias_seen", "summary.last_talker_alias_seen_ms"),
        Map.entry("logical_call_count", "summary.logical_call_count"),
        Map.entry("grant_observation_count", "summary.grant_count"),
        Map.entry("encrypted_logical_call_count", "summary.encrypted_logical_call_count"),
        Map.entry("affiliated_talkgroup", "affiliated_group.identity_id"),
        Map.entry("affiliated", "affiliation.radio_identity_id IS NOT NULL"),
        Map.entry("affiliation_confirmed", "affiliation.confirmed_at_ms"),
        Map.entry("channel", RADIO_CHANNEL_SORT_SQL),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> TALKER_ALIAS_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.identity_id"),
        Map.entry("radio", "summary.identity_id"),
        Map.entry("alias", radioSystemAliasSortExpression("alias_radio", "summary.identity_id", "name")),
        Map.entry("name", radioSystemAliasSortExpression("alias_radio", "summary.identity_id", "name")),
        Map.entry("talker_alias", "lower(summary.last_talker_alias)"),
        Map.entry("talker_alias_seen", "summary.last_talker_alias_seen_ms"),
        Map.entry("logical_call_count", "summary.logical_call_count"),
        Map.entry("grant_observation_count", "summary.grant_count"),
        Map.entry("encrypted_logical_call_count", "summary.encrypted_logical_call_count"),
        Map.entry("first_seen", "summary.first_seen_ms"),
        Map.entry("last_seen", "summary.last_seen_ms")
    );
    private static final Map<String,String> RELATIONSHIP_SORT_COLUMNS = Map.ofEntries(
        Map.entry("radio", "radio.identity_id"),
        Map.entry("talker_alias", "lower(radio.last_talker_alias)"),
        Map.entry("group_identity", "group_identity.identity_id"),
        Map.entry("logical_call_count", "relationship.logical_call_count"),
        Map.entry("grant_observation_count", "relationship.grant_count"),
        Map.entry("encrypted_logical_call_count", "relationship.encrypted_logical_call_count"),
        Map.entry("affiliated", CURRENT_RELATIONSHIP_AFFILIATION_SQL),
        Map.entry("channel", RADIO_CHANNEL_SORT_SQL),
        Map.entry("first_seen", "relationship.first_seen_ms"),
        Map.entry("last_seen", "relationship.last_seen_ms")
    );
    private static final Map<String,String> CHANNEL_DIRECTORY_SORT_COLUMNS = Map.ofEntries(
        Map.entry("name", "lower(coalesce(configured.name, ''))"),
        Map.entry("system", "lower(coalesce(configured.system_name, ''))"),
        Map.entry("site", "lower(coalesce(configured.site_name, ''))"),
        Map.entry("type", "configured.channel_kind"),
        Map.entry("protocol", "lower(configured.decoder)"),
        Map.entry("frequency", "coalesce(configured.observed_primary_frequency_hz, " +
            "configured.primary_frequency_hz, 0)"),
        Map.entry("first_seen", "configured.first_seen_ms"),
        Map.entry("last_seen", "configured.last_seen_ms")
    );
    private static final Map<String,String> DMR_CONVENTIONAL_GROUP_IDENTITY_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "summary.talkgroup_id"),
        Map.entry("group_identity", "summary.talkgroup_id"),
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
    private static final Map<String,String> CHANNEL_GROUP_IDENTITY_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "coalesce(observed_local_id, native_id)"),
        Map.entry("group_identity", "coalesce(observed_local_id, native_id)"),
        Map.entry("kind", "group_identity_kind_code"),
        Map.entry("alias", "lower(coalesce(matched_alias_name, ''))"),
        Map.entry("name", "lower(coalesce(matched_alias_name, ''))"),
        Map.entry("frequency", "frequency_hz"),
        Map.entry("slot", "timeslot"),
        Map.entry("logical_call_count", "logical_call_count"),
        Map.entry("encrypted_logical_call_count", "encrypted_logical_call_count"),
        Map.entry("recorded_logical_call_count", "recorded_logical_call_count"),
        Map.entry("stream_submitted_logical_call_count", "stream_submitted_logical_call_count"),
        Map.entry("last_source", "last_source_radio_id"),
        Map.entry("first_seen", "first_seen_ms"),
        Map.entry("last_seen", "last_seen_ms")
    );
    private static final Map<String,String> CHANNEL_RADIO_SORT_COLUMNS = Map.ofEntries(
        Map.entry("id", "coalesce(observed_local_id, native_id)"),
        Map.entry("radio", "coalesce(observed_local_id, native_id)"),
        Map.entry("alias", "lower(coalesce(matched_alias_name, ''))"),
        Map.entry("name", "lower(coalesce(matched_alias_name, ''))"),
        Map.entry("frequency", "frequency_hz"),
        Map.entry("slot", "timeslot"),
        Map.entry("logical_call_count", "logical_call_count"),
        Map.entry("source_logical_call_count", "source_logical_call_count"),
        Map.entry("target_logical_call_count", "target_logical_call_count"),
        Map.entry("encrypted_logical_call_count", "encrypted_logical_call_count"),
        Map.entry("first_seen", "first_seen_ms"),
        Map.entry("last_seen", "last_seen_ms")
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
    private static final Map<String,String> OBSERVED_GROUP_IDENTITY_SORT_COLUMNS = Map.ofEntries(
        Map.entry("system", "lower(coalesce(system_name, radio_system_key, configuration_id))"),
        Map.entry("protocol", "protocol_code"),
        Map.entry("topology", "topology"),
        Map.entry("id", "group_identity_id"),
        Map.entry("group_identity", "group_identity_id"),
        Map.entry("kind", "group_identity_kind_code"),
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
        return readSnapshot(connection -> WebEntityNavigationCatalog.Snapshot.of(mConfiguredEntities.channels(
            connection).stream().map(configured -> new WebEntityNavigationCatalog.Channel(
                configured.configurationId(), WebEntityRef.channel(configured.configurationId()),
                configured.radioSystemKey() != null ? WebEntityRef.radioSystem(configured.radioSystemKey()) : null,
                configured.protocolCode(), configured.addressDomainCode() != null ?
                configured.addressDomainCode() : 0, configured.p25Wacn(), configured.p25SystemId())).toList()));
    }

    Map<String,Object> status()
    {
        Path path = getDatabasePath();
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("database_exists", Files.isRegularFile(path));
        status.put("database_bytes", fileBytes(path));
        status.put("wal_bytes", fileBytes(Path.of(path + "-wal")));
        status.put("shm_bytes", fileBytes(Path.of(path + "-shm")));
        status.put("stats_logging_enabled", mUserPreferences.getApplicationPreference().isStatsLoggingEnabled());
        status.put("detailed_history_enabled", mUserPreferences.getApplicationPreference().isStatsDetailedHistoryEnabled());
        status.put("retention_days", mUserPreferences.getApplicationPreference().getStatsLoggingRetentionDays());

        try
        {
            status.putAll(read(connection -> {
                Map<String,Object> details = new LinkedHashMap<>();
                long lastDetailedHistoryMs = scalarLong(connection, """
                    SELECT COALESCE((SELECT observed_at_ms FROM receiver_activity_event ORDER BY id DESC LIMIT 1), 0)
                    """);
                details.put("logger", loggerStatus(connection));
                details.put("detailed_history_available", lastDetailedHistoryMs > 0);
                details.put("last_detailed_history_ms", lastDetailedHistoryMs);
                return details;
            }));
        }
        catch(StatsApiException e)
        {
            status.put("logger", List.of());
            status.put("detailed_history_available", false);
            status.put("last_detailed_history_ms", 0);
        }

        return status;
    }

    /**
     * Produces one complete CSV dataset from one explicit read transaction.  The page controls are intentionally not
     * used; search and allowlisted sort controls retain the same meaning as their corresponding JSON table.
     */
    StatsCsvExport csvExport(String dataset, StatsRequest request)
    {
        if(!List.of("radio-system-group-identities", "radio-system-radios", "channel-frequencies", "channel-neighbors",
            "channels", "channel-group-identities", "channel-radios", "signal-health",
            "channel-quality", "aliases").contains(dataset))
        {
            throw new StatsApiException(400, "Unsupported CSV dataset");
        }

        return readSnapshot(connection -> {
            int queryLimit = StatsCsvExport.MAX_ROWS + 1;
            List<Map<String,Object>> rows;
            String fileScope;

            switch(dataset)
            {
                case "radio-system-group-identities" ->
                {
                    String radioSystemKey = request.requiredText("radio_system_key");
                    Map<String,Object> radioSystem = requireRadioSystem(connection, radioSystemKey);
                    rows = queryRadioSystemGroupIdentities(connection, radioSystemKey, request, queryLimit, 0);
                    addExportMetadata(rows, Map.of(
                        "system_name", textValue(radioSystem.get("system_name")),
                        "radio_system_key", textValue(radioSystem.get("radio_system_key"))));
                    fileScope = exportLabel(radioSystem, "system_name", "radio_system_key");
                }
                case "radio-system-radios" ->
                {
                    String radioSystemKey = request.requiredText("radio_system_key");
                    Map<String,Object> radioSystem = requireRadioSystem(connection, radioSystemKey);
                    rows = queryRadioSystemRadios(connection, radioSystemKey, request, queryLimit, 0);
                    addExportMetadata(rows, Map.of(
                        "system_name", textValue(radioSystem.get("system_name")),
                        "radio_system_key", textValue(radioSystem.get("radio_system_key"))));
                    fileScope = exportLabel(radioSystem, "system_name", "radio_system_key");
                }
                case "channel-frequencies" ->
                {
                    String configurationId = request.requiredText("configuration_id");
                    WebConfiguredEntityRepository.ConfiguredChannel configured =
                        mConfiguredEntities.requireChannel(connection, configurationId);
                    Map<String,Object> metadata = channelExportMetadata(connection, configured);
                    rows = configured.channelKind() == WebConfiguredEntityRepository.ChannelKind.TRUNKED ?
                        queryTrunkedChannelFrequencies(connection, configured, queryLimit, 0) :
                        queryConventionalFrequencySummaries(connection, configured, queryLimit, 0);
                    addExportMetadata(rows, metadata);
                    fileScope = exportLabel(metadata, "name", "configuration_id");
                }
                case "channel-neighbors" ->
                {
                    String configurationId = request.requiredText("configuration_id");
                    WebConfiguredEntityRepository.ConfiguredChannel configured =
                        mConfiguredEntities.requireChannel(connection, configurationId);
                    Map<String,Object> metadata = channelExportMetadata(connection, configured);
                    rows = queryTrunkedChannelNeighbors(connection, configured, queryLimit, 0);
                    addExportMetadata(rows, metadata);
                    fileScope = exportLabel(metadata, "name", "configuration_id");
                }
                case "channels" ->
                {
                    rows = queryChannelDirectory(connection, request, queryLimit, 0);
                    fileScope = "all-channels";
                }
                case "channel-group-identities" ->
                {
                    WebConfiguredEntityRepository.ConfiguredChannel configured = mConfiguredEntities.requireChannel(
                        connection, request.requiredText("configuration_id"));
                    rows = configured.channelKind() == WebConfiguredEntityRepository.ChannelKind.TRUNKED ?
                        queryTrunkedChannelGroupIdentities(connection, configured, request, queryLimit, 0) :
                        queryConventionalGroupIdentities(connection, configured, request, queryLimit, 0);
                    addExportMetadata(rows, channelIdentityExportMetadata(configured));
                    fileScope = configured.configurationId();
                }
                case "channel-radios" ->
                {
                    WebConfiguredEntityRepository.ConfiguredChannel configured = mConfiguredEntities.requireChannel(
                        connection, request.requiredText("configuration_id"));
                    rows = queryChannelRadios(connection, configured, request, queryLimit, 0);
                    addExportMetadata(rows, channelIdentityExportMetadata(configured));
                    fileScope = configured.configurationId();
                }
                case "signal-health" ->
                {
                    rows = querySignalHealthExport(connection, queryLimit);
                    fileScope = "all-channels";
                }
                case "channel-quality" ->
                {
                    String configurationId = request.requiredText("configuration_id");
                    WebConfiguredEntityRepository.ConfiguredChannel configured =
                        mConfiguredEntities.requireChannel(connection, configurationId);
                    Map<String,Object> channel = configuredTrunkedChannelReadModel(connection, configured);
                    rows = queryChannelQualityExport(connection, request, configured, channel);
                    fileScope = exportLabel(channel, "name", "configuration_id");
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

    List<Long> matchingAliasIds(StatsRequest request)
    {
        return readSnapshot(connection -> mAliasCatalog.matchingAliasIds(connection, request));
    }

    Map<String,Object> alias(int aliasId)
    {
        return readSnapshot(connection -> mAliasCatalog.alias(connection, aliasId));
    }

    /**
     * Returns one bounded row per observed P25, DMR, or NXDN group identity assigned to one durable Alias List.
     * The default excludes identities with an exact definition so an ordinary range cannot hide groups that still
     * need names.
     */
    Map<String,Object> observedGroupIdentities(int aliasListId, StatsRequest request)
    {
        return observedGroupIdentities(aliasListId, request, ignored -> {});
    }

    /**
     * Runs the observed-group-identity query and exposes the exact immutable statement to focused query-plan diagnostics.
     */
    Map<String,Object> observedGroupIdentities(int aliasListId, StatsRequest request,
                                          Consumer<ObservedGroupIdentityQuery> queryObserver)
    {
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
            String family = String.valueOf(aliasList.get("family"));

            if(!Set.of("P25", "DMR", "NXDN").contains(family))
            {
                throw new StatsApiException(400,
                    "Observed group discovery is available only for P25, DMR, and NXDN alias lists");
            }

            String aliasListName = String.valueOf(aliasList.get("name"));
            int protocolCode = switch(family)
            {
                case "P25" -> 1;
                case "DMR" -> 3;
                case "NXDN" -> 4;
                default -> throw new IllegalStateException();
            };
            String decoderPredicate = switch(family)
            {
                case "P25" -> "config.decoder_type LIKE 'P25%'";
                case "DMR" -> "config.decoder_type = 'DMR'";
                case "NXDN" -> "config.decoder_type = 'NXDN'";
                default -> "0";
            };
            StringBuilder sql = new StringBuilder("""
                WITH observed AS (
                    SELECT ? AS alias_list_id, ? AS alias_list_name, ? AS family,
                        system.id AS radio_system_id, system.system_key AS radio_system_key,
                        channel.id AS channel_id, config.configuration_id,
                        system.protocol_code,
                        CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                            WHEN 4 THEN 'NXDN' END AS protocol,
                        config.address_domain_code, 'TRUNKED' AS topology,
                        nullif(trim(config.system_name), '') AS system_name,
                        coalesce(nullif(trim(config.site_name), ''), nullif(trim(config.name), '')) AS channel_names,
                        system.p25_wacn AS wacn, system.p25_system_id AS system_id,
                        config.primary_frequency_hz AS frequency_hz, NULL AS timeslot,
                        identity.identity_id AS native_id,
                        calls.observed_local_id AS group_identity_id,
                        identity.identity_kind_code AS group_identity_kind_code,
                        %s AS identity_key,
                        min(calls.bucket_start_ms) AS first_seen_ms,
                        max(calls.last_observed_at_ms) AS last_seen_ms,
                        sum(calls.observed_call_count) AS logical_call_count,
                        NULL AS recorded_logical_call_count,
                        NULL AS stream_submitted_logical_call_count,
                        sum(calls.encrypted_observed_call_count) AS encrypted_logical_call_count,
                        NULL AS signaling_observation_count,
                        EXISTS (SELECT 1 FROM alias definition INDEXED BY idx_alias_talkgroup_value
                            WHERE definition.alias_list_id = ? AND definition.matcher_type = 'TALKGROUP'
                              AND definition.value = calls.observed_local_id
                              AND definition.protocol IN ('APCO25', 'APCO25_PHASE2')) AS has_exact_definition
                    FROM configuration_channel config INDEXED BY idx_configuration_channel_alias_list
                    JOIN receiver_channel channel ON channel.configuration_id = config.configuration_id
                    JOIN radio_system system ON system.id = channel.radio_system_id
                    JOIN p25_site_call_identity_bucket calls ON calls.channel_id = channel.id
                      AND calls.radio_system_id = system.id AND calls.identity_role_code = 1
                    JOIN radio_system_identity_summary identity ON identity.id = calls.identity_summary_id
                      AND identity.radio_system_id = calls.radio_system_id
                    WHERE config.alias_list_id = ? AND system.protocol_code = 1
                      AND identity.identity_kind_code IN (1, 3)
                      AND calls.observed_local_id IS NOT NULL
                    GROUP BY channel.id, identity.id, calls.observed_local_id

                    UNION ALL

                    SELECT ? AS alias_list_id, ? AS alias_list_name, ? AS family,
                        system.id AS radio_system_id, system.system_key AS radio_system_key,
                        channel.id AS channel_id, config.configuration_id,
                        system.protocol_code,
                        CASE system.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' END AS protocol,
                        config.address_domain_code, 'TRUNKED' AS topology,
                        nullif(trim(config.system_name), '') AS system_name,
                        coalesce(nullif(trim(config.site_name), ''), nullif(trim(config.name), '')) AS channel_names,
                        system.p25_wacn AS wacn, system.p25_system_id AS system_id,
                        config.primary_frequency_hz AS frequency_hz, NULL AS timeslot,
                        identity.identity_id AS native_id,
                        identity.identity_id AS group_identity_id,
                        identity.identity_kind_code AS group_identity_kind_code,
                        %s AS identity_key,
                        min(calls.bucket_start_ms) AS first_seen_ms,
                        max(calls.bucket_start_ms) AS last_seen_ms,
                        sum(calls.logical_call_count) AS logical_call_count,
                        sum(calls.recorded_output_count) AS recorded_logical_call_count,
                        sum(calls.streamed_output_count) AS stream_submitted_logical_call_count,
                        sum(calls.encrypted_logical_call_count) AS encrypted_logical_call_count,
                        NULL AS signaling_observation_count,
                        EXISTS (SELECT 1 FROM alias definition INDEXED BY idx_alias_talkgroup_value
                            WHERE definition.alias_list_id = ? AND definition.matcher_type = 'TALKGROUP'
                              AND definition.value = identity.identity_id
                              AND definition.protocol = CASE system.protocol_code WHEN 3 THEN 'DMR' ELSE 'NXDN' END)
                            AS has_exact_definition
                    FROM configuration_channel config INDEXED BY idx_configuration_channel_alias_list
                    JOIN receiver_channel channel ON channel.configuration_id = config.configuration_id
                    JOIN radio_system system ON system.id = channel.radio_system_id
                    JOIN trunked_logical_call_identity_bucket calls ON calls.radio_system_id = system.id
                      AND calls.identity_role_code = 1
                    JOIN radio_system_identity_summary identity ON identity.id = calls.identity_summary_id
                      AND identity.radio_system_id = calls.radio_system_id
                    WHERE config.alias_list_id = ? AND system.protocol_code = %d
                      AND system.protocol_code IN (3, 4) AND identity.identity_kind_code IN (1, 3)
                    GROUP BY channel.id, identity.id

                    UNION ALL

                    SELECT ? AS alias_list_id, ? AS alias_list_name, ? AS family,
                        NULL AS radio_system_id, NULL AS radio_system_key,
                        channel.id AS channel_id, config.configuration_id,
                        %d AS protocol_code, ? AS protocol,
                        config.address_domain_code, 'CONVENTIONAL' AS topology,
                        coalesce(nullif(trim(config.system_name), ''), nullif(trim(config.name), '')) AS system_name,
                        nullif(trim(config.name), '') AS channel_names,
                        NULL AS wacn, NULL AS system_id, config.primary_frequency_hz AS frequency_hz,
                        CASE WHEN config.decoder_type = 'DMR' THEN (
                            SELECT CASE WHEN count(DISTINCT detail.timeslot) = 1 THEN min(detail.timeslot) END
                            FROM dmr_conventional_talkgroup_summary detail
                            WHERE detail.channel_id = channel.id AND detail.talkgroup_id = bucket.identity_id) END,
                        bucket.identity_id AS native_id, bucket.identity_id AS group_identity_id,
                        bucket.identity_kind_code AS group_identity_kind_code, NULL AS identity_key,
                        min(bucket.bucket_start_ms) AS first_seen_ms,
                        max(bucket.bucket_start_ms) AS last_seen_ms,
                        sum(bucket.call_count) AS logical_call_count,
                        sum(bucket.recorded_count) AS recorded_logical_call_count,
                        sum(bucket.streamed_count) AS stream_submitted_logical_call_count,
                        sum(bucket.encrypted_count) AS encrypted_logical_call_count,
                        NULL AS signaling_observation_count,
                        EXISTS (SELECT 1 FROM alias definition INDEXED BY idx_alias_talkgroup_value
                            WHERE definition.alias_list_id = ? AND definition.matcher_type = 'TALKGROUP'
                              AND definition.value = bucket.identity_id
                              AND ((%d = 1 AND definition.protocol IN ('APCO25', 'APCO25_PHASE2'))
                                OR (%d = 3 AND definition.protocol = 'DMR')
                                OR (%d = 4 AND definition.protocol = 'NXDN'))) AS has_exact_definition
                    FROM conventional_call_identity_bucket bucket
                    JOIN receiver_channel channel ON channel.id = bucket.channel_id
                    JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
                    WHERE config.alias_list_id = ? AND config.channel_kind = 'CONVENTIONAL'
                      AND %s AND bucket.identity_role_code = 1
                      AND bucket.identity_kind_code IN (1, 3)
                    GROUP BY channel.id, bucket.identity_kind_code, bucket.identity_id
                ) SELECT * FROM observed WHERE 1 = 1
                """.formatted(IDENTITY_KEY_SQL.formatted("identity"),
                    IDENTITY_KEY_SQL.formatted("identity"), protocolCode, protocolCode,
                    protocolCode, protocolCode, protocolCode, decoderPredicate));
            List<Object> parameters = new ArrayList<>(List.of(
                aliasListId, aliasListName, family, aliasListId, aliasListId,
                aliasListId, aliasListName, family, aliasListId, aliasListId,
                aliasListId, aliasListName, family, family, aliasListId, aliasListId));

            if(!includeExact)
            {
                sql.append(" AND has_exact_definition = 0");
            }

            if(request.search() != null)
            {
                sql.append("""
                     AND lower(coalesce(system_name, '') || ' ' || coalesce(channel_names, '') || ' ' ||
                       coalesce(radio_system_key, '') || ' ' || coalesce(configuration_id, '') || ' ' ||
                       protocol || ' ' || topology || ' ' || group_identity_kind_label || ' ' ||
                       CAST(group_identity_id AS TEXT) || ' ' ||
                       coalesce(identity_key, '') || ' ' || coalesce(CAST(native_id AS TEXT), '')) LIKE ?
                    """);
                parameters.add(like(request.search()));
            }

            sql.append(" ORDER BY ").append(order(request, OBSERVED_GROUP_IDENTITY_SORT_COLUMNS, "last_seen"))
                .append(", topology, protocol_code, coalesce(radio_system_key, configuration_id), ")
                .append("group_identity_kind_code, group_identity_id, identity_key LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            ObservedGroupIdentityQuery query = new ObservedGroupIdentityQuery(sql.toString(), parameters);
            queryObserver.accept(query);
            List<Map<String,Object>> rows = queryRows(connection, query.sql(), query.parameters().toArray());
            mAliasResolver.resolveObservedGroupIdentities(connection, rows);

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

    /** Exact statement and bindings used by the observed-group-identity read model. */
    record ObservedGroupIdentityQuery(String sql, List<Object> parameters)
    {
        ObservedGroupIdentityQuery
        {
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * Latest quality snapshot for every known monitored trunked channel. A left join deliberately retains channels
     * that have not produced a quality sample yet; their measurement columns remain null in JSON/CSV.
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
            LEFT JOIN trunked_control_channel_quality quality ON quality.channel_id = site.channel_id AND
                (quality.frequency_hz, quality.bucket_start_ms) = (
                    SELECT candidate.frequency_hz, candidate.bucket_start_ms
                    FROM trunked_control_channel_quality candidate
                    WHERE candidate.channel_id = site.channel_id
                    ORDER BY candidate.observed_at_ms DESC, candidate.frequency_hz DESC
                    LIMIT 1
                )
            ORDER BY lower(coalesce(site.name, site.configuration_id)), site.configuration_id
            LIMIT ?
            """.formatted(qualityChannelSelect()), limit);
        long now = System.currentTimeMillis();

        for(Map<String,Object> row: rows)
        {
            if(row.get("last_observed_ms") instanceof Number observed)
            {
                row.put("sample_age_seconds", Math.max(0, (now - observed.longValue()) / 1_000L));
            }

            row.remove("channel_id");
            row.remove("radio_system_id");
        }

        return rows;
    }

    /**
     * Chart-compatible bounded quality export.  Signal and health percentages are aggregated; rolling 30-second
     * frame/bit counters are intentionally omitted because summing overlapping windows would produce false totals.
     */
    private List<Map<String,Object>> queryChannelQualityExport(Connection connection, StatsRequest request,
        WebConfiguredEntityRepository.ConfiguredChannel configured, Map<String,Object> channel) throws SQLException
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
            FROM trunked_control_channel_quality INDEXED BY idx_trunked_control_quality_channel_time
            WHERE channel_id = ? AND observed_at_ms >= ? AND observed_at_ms <= ?
            GROUP BY time_ms
            ORDER BY time_ms
            LIMIT ?
            """, bucketMilliseconds, bucketMilliseconds, configured.channelId(), fromMilliseconds, toMilliseconds,
            targetPoints + 2);

        if(rows.size() > targetPoints + 1)
        {
            throw new StatsApiException(413, "Channel quality export exceeds the bounded point limit");
        }

        for(Map<String,Object> row: rows)
        {
            row.put("configuration_id", configured.configurationId());
            row.put("radio_system_key", channel.get("radio_system_key"));
            row.put("range", range);
            row.put("bucket_ms", bucketMilliseconds);
            row.put("bucket_end_ms", number(row.get("time_ms")) + bucketMilliseconds);
            row.put("protocol", StatsApiProtocol.fromCode(number(channel.get("protocol_code"))).name());
            row.put("system_name", channel.get("system_name"));
            row.put("site_name", channel.get("site_name"));
            row.put("name", channel.get("name"));
            for(String field: List.of("wacn", "system_id", "network_id", "rfss", "site", "site_id", "nac",
                "ran"))
            {
                row.put(field, channel.get(field));
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

    private static Map<String,Object> channelIdentityExportMetadata(
        WebConfiguredEntityRepository.ConfiguredChannel configured)
    {
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("protocol_code", configured.protocolCode());
        metadata.put("configuration_id", configured.configurationId());
        if(configured.radioSystemKey() != null)
        {
            metadata.put("radio_system_key", configured.radioSystemKey());
        }
        if(configured.aliasListId() != null)
        {
            metadata.put("alias_list_id", configured.aliasListId());
        }
        if(configured.aliasListName() != null)
        {
            metadata.put("alias_list_name", configured.aliasListName());
        }
        return metadata;
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

    private Map<String,Object> channelExportMetadata(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured)
        throws SQLException
    {
        Map<String,Object> channel = configured.channelKind() == WebConfiguredEntityRepository.ChannelKind.TRUNKED ?
            configuredTrunkedChannelReadModel(connection, configured) : configured.toApiMap();
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("configuration_id", configured.configurationId());
        metadata.put("name", textValue(channel.get("name")));
        metadata.put("protocol", configured.protocol().wireName());
        metadata.put("radio_system_key", textValue(channel.get("radio_system_key")));
        metadata.put("system_name", textValue(channel.get("system_name")));
        metadata.put("site_name", textValue(channel.get("site_name")));
        for(String field: List.of("wacn", "system_id", "network_id", "rfss", "site_id", "nac", "ran"))
        {
            metadata.put(field, channel.get(field) != null ? channel.get(field) : "");
        }
        return metadata;
    }

    /**
     * Builds one configuration-owned site row and overlays optional retained observations.  Reapplying the configured
     * values last prevents learned labels from replacing administrator-owned names or canonical identity.
     */
    private static Map<String,Object> configuredTrunkedChannelReadModel(
        Connection connection, WebConfiguredEntityRepository.ConfiguredChannel configured) throws SQLException
    {
        int protocolCode = configured.protocolCode();
        Map<String,Object> channel = configured.toApiMap();
        List<Map<String,Object>> observations = configured.channelId() == null ? List.of() :
            protocolCode == StatsApiProtocol.P25.databaseCode() ?
            queryRows(connection, trunkedChannelSelect() + " WHERE site.channel_id = ?", configured.channelId()) :
            protocolCode == StatsApiProtocol.DMR.databaseCode() ||
                protocolCode == StatsApiProtocol.NXDN.databaseCode() ?
                queryRows(connection, trunkedSiteSelect() +
                    " WHERE site.channel_id = ? AND site.protocol_code = ?", configured.channelId(), protocolCode) :
                List.of();

        if(!observations.isEmpty())
        {
            channel.putAll(observations.getFirst());
        }

        channel.putAll(configured.toApiMap());
        return channel;
    }

    Map<String,Object> dashboard()
    {
        return read(connection -> {
            Map<String,Object> dashboard = new LinkedHashMap<>();
            dashboard.put("counts", Map.of(
                "radio_systems", scalarLong(connection, "SELECT COUNT(*) FROM radio_system"),
                "trunked_channels", scalarLong(connection,
                    "SELECT COUNT(*) FROM configuration_channel WHERE channel_kind = 'TRUNKED'"),
                "conventional_channels", scalarLong(connection,
                    "SELECT COUNT(*) FROM configuration_channel WHERE channel_kind = 'CONVENTIONAL'")
            ));
            dashboard.put("last_seen_ms", scalarLong(connection, """
                SELECT MAX(last_seen_ms) FROM (
                    SELECT last_seen_ms FROM p25_site_snapshot
                    UNION ALL SELECT last_seen_ms FROM radio_system
                    UNION ALL SELECT last_seen_ms FROM radio_system_identity_summary
                    UNION ALL SELECT last_seen_ms FROM p25_site_frequency_band_summary
                    UNION ALL SELECT last_seen_ms FROM conventional_activity_summary
                    UNION ALL SELECT last_seen_ms FROM trunked_site_snapshot
                )
                """));

            long now = System.currentTimeMillis();
            long firstIdentityHour = Math.floorDiv(now, HOUR_MILLISECONDS) * HOUR_MILLISECONDS -
                (DASHBOARD_HOURS - 1L) * HOUR_MILLISECONDS;
            dashboard.put("top_destinations", topCallIdentities(connection, IDENTITY_ROLE_DESTINATION,
                firstIdentityHour, now));
            dashboard.put("top_sources", topCallIdentities(connection, IDENTITY_ROLE_SOURCE,
                firstIdentityHour, now));
            dashboard.put("recent_channels", recentChannels(connection));
            dashboard.put("call_activity", callActivity(connection));
            dashboard.put("source_activity_24h", sourceActivity24Hours(connection));
            return dashboard;
        });
    }

    Map<String,Object> qualityHistory(StatsRequest request)
    {
        return qualityHistory(request, null);
    }

    private Map<String,Object> qualityHistory(StatsRequest request,
        WebConfiguredEntityRepository.ConfiguredChannel selected)
    {
        boolean includeHistory = request.booleanValue("include_history", selected != null);

        if(selected == null && includeHistory)
        {
            throw new StatsApiException(400, "invalid_parameter",
                "include_history is available for one selected channel", "include_history");
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
        int channelLimit = selected == null ? request.limit() : 1;
        int channelOffset = selected == null ? request.offset() : 0;

        return read(connection -> {
            List<Object> parameters = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                SELECT channel.*,
                    quality.frequency_hz AS quality_frequency_hz,
                    quality.observed_at_ms AS last_observed_ms,
                    quality.signal_dbfs, quality.average_signal_dbfs,
                    quality.minimum_signal_dbfs, quality.maximum_signal_dbfs,
                    quality.decode_health_pct, quality.valid_frames, quality.invalid_frames,
                    quality.corrected_bits, quality.sync_loss_bits, quality.dropped_bits,
                    quality.last_valid_decode_ms
                FROM (
                    %s
                ) channel
                LEFT JOIN trunked_control_channel_quality quality ON quality.channel_id = channel.channel_id
                  AND (quality.frequency_hz, quality.bucket_start_ms) = (
                    SELECT candidate.frequency_hz, candidate.bucket_start_ms
                    FROM trunked_control_channel_quality candidate
                    WHERE candidate.channel_id = channel.channel_id
                    ORDER BY candidate.observed_at_ms DESC, candidate.frequency_hz DESC LIMIT 1)
                WHERE channel.channel_kind = 'TRUNKED'
                """.formatted(qualityChannelSelect()));

            if(selected != null)
            {
                sql.append(" AND channel.configuration_id = ?");
                parameters.add(selected.configurationId());
            }

            sql.append(" ORDER BY lower(coalesce(channel.name, channel.configuration_id)), ")
                .append("channel.configuration_id LIMIT ? OFFSET ?");
            addLimitOffset(parameters, channelLimit + 1, channelOffset);
            List<Map<String,Object>> channels = queryRows(connection, sql.toString(), parameters.toArray());
            boolean hasMore = channels.size() > channelLimit;

            if(hasMore)
            {
                channels = new ArrayList<>(channels.subList(0, channelLimit));
            }

            Map<Long,Map<String,Object>> channelsById = new LinkedHashMap<>();
            for(Map<String,Object> channel: channels)
            {
                channel.put("series", new ArrayList<Map<String,Object>>());
                WebEntityRef.put(channel, WebEntityRef.channel(String.valueOf(channel.get("configuration_id"))));
                if(channel.get("channel_id") instanceof Number channelId)
                {
                    channelsById.put(channelId.longValue(), channel);
                }
            }

            if(includeHistory && selected != null && selected.channelId() != null)
            {
                List<Object> seriesParameters = new ArrayList<>(List.of(bucketMilliseconds, bucketMilliseconds,
                    selected.channelId(), fromMilliseconds, toMilliseconds));

                List<Map<String,Object>> series = queryRows(connection, """
                    SELECT channel_id, (observed_at_ms / ?) * ? AS time_ms,
                        avg(average_signal_dbfs) AS average_signal_dbfs,
                        min(minimum_signal_dbfs) AS minimum_signal_dbfs,
                        max(maximum_signal_dbfs) AS maximum_signal_dbfs,
                        avg(decode_health_pct) AS decode_health_pct,
                        CASE WHEN min(frequency_hz) = max(frequency_hz) THEN min(frequency_hz) END AS frequency_hz,
                        count(DISTINCT frequency_hz) AS frequency_count, count(*) AS sample_count,
                        max(observed_at_ms) AS last_observed_ms
                    FROM trunked_control_channel_quality
                    WHERE channel_id = ? AND observed_at_ms >= ? AND observed_at_ms <= ?
                    GROUP BY channel_id, time_ms
                    ORDER BY time_ms
                    """, seriesParameters.toArray());

                for(Map<String,Object> point: series)
                {
                    Map<String,Object> channel = channelsById.get(number(point.get("channel_id")));

                    if(channel != null && channel.get("series") instanceof List<?> values)
                    {
                        @SuppressWarnings("unchecked")
                        List<Map<String,Object>> points = (List<Map<String,Object>>)values;
                        point.remove("channel_id");
                        points.add(point);
                    }
                }
            }

            channels.forEach(channel -> {
                channel.remove("channel_id");
                channel.remove("radio_system_id");
            });

            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", responseRange);
            response.put("from_ms", fromMilliseconds);
            response.put("to_ms", toMilliseconds);
            response.put("bucket_ms", bucketMilliseconds);
            response.put("target_points", targetPoints);
            response.put("history_included", includeHistory);
            response.put("channels", channels);

            if(selected == null)
            {
                response.put("limit", channelLimit);
                response.put("offset", channelOffset);
                response.put("has_more", hasMore);
                response.put("next_offset", hasMore ? channelOffset + channelLimit : null);
            }

            return response;
        });
    }

    Map<String,Object> radioSystemDirectory(StatsRequest request)
    {
        boolean includeChannelPreview = request.booleanValue("include_channel_preview", false);
        int limit = includeChannelPreview ? request.limit(MAXIMUM_RADIO_SYSTEM_DIRECTORY_WITH_CHANNEL_PREVIEW) : request.limit();
        int offset = request.offset();
        String search = request.search();

        return readSnapshot(connection -> {
            List<Object> parameters = new ArrayList<>();
            StringBuilder sql = new StringBuilder("WITH radio_systems AS (")
                .append(radioSystemSummarySelect()).append(") SELECT * FROM radio_systems WHERE 1=1");

            addRadioSystemDirectorySearch(sql, parameters, search);

            sql.append(" ORDER BY ").append(order(request, SYSTEM_SORT_COLUMNS, "last_seen"))
                .append(", radio_system_key LIMIT ? OFFSET ?");
            addLimitOffset(parameters, limit + 1, offset);
            List<Map<String,Object>> parentRows = queryRows(connection, sql.toString(), parameters.toArray());
            Map<String,Object> response = page(parentRows, limit, offset);
            StringBuilder countSql = new StringBuilder("WITH radio_systems AS (")
                .append(radioSystemSummarySelect()).append(") SELECT COUNT(*) FROM radio_systems WHERE 1=1");
            List<Object> countParameters = new ArrayList<>();
            addRadioSystemDirectorySearch(countSql, countParameters, search);
            response.put("total_count", scalarLong(connection, countSql.toString(), countParameters.toArray()));
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> pageRows = (List<Map<String,Object>>)response.get("rows");

            for(Map<String,Object> system: pageRows)
            {
                system.put("capabilities", radioSystemCapabilities((int)number(system.get("protocol_code"))));
                WebEntityRef.put(system, WebEntityRef.radioSystem(String.valueOf(system.get("radio_system_key"))));
            }

            attachRadioSystemAliasLists(connection, pageRows);

            if(includeChannelPreview)
            {
                attachRadioSystemChannelPreviews(connection, pageRows, search);
                response.put("channel_preview_limit_per_system", MAXIMUM_RADIO_SYSTEM_DIRECTORY_CHANNEL_PREVIEW);
            }

            return response;
        });
    }

    private static void addRadioSystemDirectorySearch(StringBuilder sql, List<Object> parameters, String search)
    {
        if(search == null)
        {
            return;
        }

        sql.append("""
             AND (lower(protocol || ' ' || radio_system_key || ' ' ||
               coalesce(system_name, '') || ' ' || coalesce(channel_names, '') || ' ' ||
               coalesce(CAST(wacn AS TEXT), '') || ' ' ||
               CASE WHEN wacn IS NOT NULL THEN printf('%05X', wacn) ELSE '' END || ' ' ||
               coalesce(CAST(system_id AS TEXT), '') || ' ' ||
               CASE WHEN system_id IS NOT NULL THEN printf('%03X', system_id) ELSE '' END || ' ' ||
               coalesce(CAST(network_id AS TEXT), '')) LIKE ?
               OR EXISTS (
                   SELECT 1
                   FROM receiver_channel channel
                   JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
                   LEFT JOIN p25_site_snapshot p25 ON p25.channel_id = channel.id
                   LEFT JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                   WHERE channel.radio_system_id = radio_systems.radio_system_id
                     AND lower(coalesce(config.configuration_id, '') || ' ' ||
                         coalesce(config.system_name, '') || ' ' || coalesce(config.site_name, '') || ' ' ||
                         coalesce(config.name, '') || ' ' || coalesce(CAST(trunked.network_id AS TEXT), '') || ' ' ||
                         coalesce(CAST(trunked.system_id AS TEXT), '') || ' ' ||
                         coalesce(CAST(trunked.site_id AS TEXT), '') || ' ' ||
                         coalesce(CAST(trunked.ran AS TEXT), '') || ' ' ||
                         coalesce(CAST(p25.rfss AS TEXT), '') || ' ' ||
                         coalesce(CAST(p25.site AS TEXT), '')) LIKE ?))
            """);
        String searchPattern = like(search);
        parameters.add(searchPattern);
        parameters.add(searchPattern);
    }

    Map<String,Object> radioSystem(String radioSystemKey)
    {
        return read(connection -> {
            Map<String,Object> response = new LinkedHashMap<>();
            Map<String,Object> system = requireRadioSystem(connection, radioSystemKey);
            List<Map<String,Object>> activity = queryRows(connection, """
                SELECT coalesce(SUM(logical_call_count), 0) AS logical_call_count,
                    coalesce(SUM(recorded_output_count), 0) AS recorded_logical_call_count,
                    coalesce(SUM(streamed_output_count), 0) AS stream_submitted_logical_call_count,
                    coalesce(SUM(encrypted_logical_call_count), 0) AS encrypted_logical_call_count
                FROM trunked_logical_call_bucket
                WHERE radio_system_id = ?
                """, system.get("radio_system_id"));

            if(!activity.isEmpty())
            {
                system.putAll(activity.getFirst());
            }

            if(number(system.get("protocol_code")) == StatsApiProtocol.P25.databaseCode())
            {
                List<Map<String,Object>> channelActivity = queryRows(connection, """
                    SELECT coalesce(SUM(observed_call_count), 0) AS channel_observation_count,
                        coalesce(SUM(encrypted_observed_call_count), 0)
                            AS encrypted_channel_observation_count
                    FROM p25_site_call_bucket
                    WHERE radio_system_id = ?
                    """, system.get("radio_system_id"));
                if(!channelActivity.isEmpty())
                {
                    system.putAll(channelActivity.getFirst());
                }
            }
            else
            {
                system.put("channel_observation_count", system.get("logical_call_count"));
                system.put("encrypted_channel_observation_count", system.get("encrypted_logical_call_count"));
            }

            system.put("capabilities", radioSystemCapabilities((int)number(system.get("protocol_code"))));
            WebEntityRef.put(system, WebEntityRef.radioSystem(radioSystemKey));
            attachRadioSystemAliasLists(connection, List.of(system));
            response.put("radio_system", system);
            response.put("action_counts", systemActionCounts(connection, number(system.get("radio_system_id"))));
            return response;
        });
    }

    Map<String,Object> radioSystemChannels(String radioSystemKey, StatsRequest request)
    {
        return read(connection -> {
            Map<String,Object> system = requireRadioSystem(connection, radioSystemKey);
            return page(queryRadioSystemChannels(connection, number(system.get("radio_system_id")), request),
                request);
        });
    }

    Map<String,Object> radioSystemGroupIdentities(String radioSystemKey, StatsRequest request)
    {
        return readSnapshot(connection -> {
            requireRadioSystem(connection, radioSystemKey);
            List<Map<String,Object>> rows = queryRadioSystemGroupIdentities(connection, radioSystemKey, request,
                request.limit() + 1, request.offset());
            Map<String,Object> response = page(rows, request);
            response.put("total_count", countRadioSystemGroupIdentities(connection, radioSystemKey, request));
            return response;
        });
    }

    private static long countRadioSystemGroupIdentities(Connection connection, String radioSystemKey,
                                                         StatsRequest request) throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM radio_system_identity_summary summary
            JOIN radio_system system ON system.id = summary.radio_system_id
            WHERE system.system_key = ? AND summary.identity_kind_code IN (1, 3)
            """);
        List<Object> parameters = new ArrayList<>(List.of(radioSystemKey));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        return scalarLong(connection, sql.toString(), parameters.toArray());
    }

    private List<Map<String,Object>> queryRadioSystemGroupIdentities(Connection connection, String radioSystemKey,
                                                                      StatsRequest request, int limit, int offset)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
                SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                    system.protocol_code, system.address_domain_code AS address_domain_code,
                    CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    system.p25_wacn AS wacn, coalesce(system.p25_system_id, (
                        SELECT min(trunked.system_id)
                        FROM receiver_channel channel
                        JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                        WHERE channel.radio_system_id = system.id)) AS system_id,
                    (SELECT min(trunked.network_id)
                        FROM receiver_channel channel
                        JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                        WHERE channel.radio_system_id = system.id) AS network_id,
                    summary.id AS identity_summary_id,
                    summary.identity_id AS native_id,
                    summary.identity_kind_code AS group_identity_kind_code,
                    summary.home_wacn, summary.home_system_id,
                    %s AS identity_key,
                    summary.first_seen_ms, summary.last_seen_ms,
                    summary.logical_call_count,
                    summary.encrypted_logical_call_count,
                    summary.recorded_output_count AS recorded_logical_call_count,
                    summary.streamed_output_count AS stream_submitted_logical_call_count,
                    CASE WHEN system.protocol_code = 1 THEN coalesce((
                        SELECT SUM(site_calls.observed_call_count)
                        FROM p25_site_call_identity_bucket site_calls
                        WHERE site_calls.radio_system_id = summary.radio_system_id
                          AND site_calls.identity_role_code = 1
                          AND site_calls.identity_summary_id = summary.id
                    ), 0) ELSE summary.logical_call_count END AS channel_observation_count,
                    %s AS signaling_observation_count
                FROM radio_system_identity_summary summary
                JOIN radio_system system ON system.id = summary.radio_system_id
                WHERE system.system_key = ? AND summary.identity_kind_code IN (1, 3)
                """.formatted(IDENTITY_KEY_SQL.formatted("summary"),
                GROUP_IDENTITY_SIGNALING_COUNT_SQL));
        List<Object> parameters = new ArrayList<>(List.of(radioSystemKey));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        sql.append(" ORDER BY ").append(order(request, GROUP_IDENTITY_SORT_COLUMNS, "logical_call_count"))
            .append(", summary.identity_kind_code, summary.identity_id LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        enrichRadioSystemGroupIdentities(connection, rows, "native_id", "alias_");

        for(Map<String,Object> row: rows)
        {
            int kind = (int)number(row.get("group_identity_kind_code"));
            WebEntityRef.put(row, identityReference(row, kind, textValue(row.get("identity_key"))));
        }

        return rows;
    }

    Map<String,Object> radioSystemRadios(String radioSystemKey, StatsRequest request)
    {
        return readSnapshot(connection -> {
            requireRadioSystem(connection, radioSystemKey);
            List<Map<String,Object>> rows = queryRadioSystemRadios(connection, radioSystemKey, request,
                request.limit() + 1, request.offset());
            Map<String,Object> response = page(rows, request);
            response.put("total_count", countRadioSystemRadios(connection, radioSystemKey, request));
            return response;
        });
    }

    private static long countRadioSystemRadios(Connection connection, String radioSystemKey, StatsRequest request)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM radio_system_identity_summary summary
            JOIN radio_system system ON system.id = summary.radio_system_id
            LEFT JOIN trunked_radio_affiliation affiliation
              ON affiliation.radio_system_id = system.id AND affiliation.radio_identity_id = summary.id
            LEFT JOIN trunked_radio_channel_presence presence
              ON presence.radio_system_id = system.id AND presence.radio_identity_id = summary.id
            LEFT JOIN receiver_channel presence_channel ON presence_channel.id = presence.channel_id
            LEFT JOIN configuration_channel presence_config
              ON presence_config.configuration_id = presence_channel.configuration_id
            WHERE system.system_key = ? AND summary.identity_kind_code = 2
            """);
        List<Object> parameters = new ArrayList<>(List.of(radioSystemKey));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        addRadioFilters(sql, parameters, request, "affiliation.radio_identity_id IS NOT NULL");
        return scalarLong(connection, sql.toString(), parameters.toArray());
    }

    private List<Map<String,Object>> queryRadioSystemRadios(Connection connection, String radioSystemKey,
                                                             StatsRequest request, int limit, int offset)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
                SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                    system.protocol_code, system.address_domain_code AS address_domain_code,
                    CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    system.p25_wacn AS wacn, coalesce(system.p25_system_id, (
                        SELECT min(trunked.system_id)
                        FROM receiver_channel channel
                        JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                        WHERE channel.radio_system_id = system.id)) AS system_id,
                    (SELECT min(trunked.network_id)
                        FROM receiver_channel channel
                        JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                        WHERE channel.radio_system_id = system.id) AS network_id,
                    %s,
                    affiliated_group.identity_id AS affiliated_talkgroup_id,
                    %s AS affiliated_talkgroup_identity_key,
                    affiliation.confirmed_at_ms AS affiliation_confirmed_at_ms,
                    CASE WHEN affiliation.radio_identity_id IS NOT NULL THEN 1 ELSE 0 END AS currently_affiliated,
                    %s
                FROM radio_system_identity_summary summary
                JOIN radio_system system ON system.id = summary.radio_system_id
                LEFT JOIN trunked_radio_affiliation affiliation
                  ON affiliation.radio_system_id = system.id AND affiliation.radio_identity_id = summary.id
                LEFT JOIN radio_system_identity_summary affiliated_group
                  ON affiliated_group.id = affiliation.talkgroup_identity_id
                 AND affiliated_group.radio_system_id = affiliation.radio_system_id
                %s
                WHERE system.system_key = ? AND summary.identity_kind_code = 2
                """.formatted(TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL,
            IDENTITY_KEY_SQL.formatted("affiliated_group"), radioPresenceSelect(),
            radioPresenceJoins("summary.id")));
        List<Object> parameters = new ArrayList<>(List.of(radioSystemKey));
        addIdentifierSearch(sql, parameters, request.search(), "summary.identity_id");
        addRadioFilters(sql, parameters, request, "affiliation.radio_identity_id IS NOT NULL");
        sql.append(" ORDER BY ").append(order(request, RADIO_SORT_COLUMNS, "logical_call_count"))
            .append(", summary.identity_id LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        enrichRadioSystemRadios(connection, rows, "native_id", "alias_");
        nestRadioPresence(rows);

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, identityReference(row, IDENTITY_KIND_RADIO,
                textValue(row.get("identity_key"))));
            WebEntityRef.put(row, "affiliated_talkgroup_entity_ref", identityReference(row,
                IDENTITY_KIND_TALKGROUP, textValue(row.get("affiliated_talkgroup_identity_key"))));
        }

        return rows;
    }

    /** Fixed-width channel-presence projection; current state contributes at most one row for each radio. */
    private static String radioPresenceSelect()
    {
        return """
            CASE presence.evidence_code WHEN 1 THEN 'registration' WHEN 2 THEN 'affiliation' END
                AS presence_evidence,
            presence.confirmed_at_ms AS presence_confirmed_at_ms,
            presence_config.configuration_id AS presence_configuration_id,
            system.protocol_code AS presence_protocol_code,
            system.p25_wacn AS presence_wacn,
            CASE WHEN system.protocol_code = 1 THEN system.p25_system_id ELSE presence_trunked.system_id END
                AS presence_system_id,
            presence_trunked.network_id AS presence_network_id,
            CASE WHEN system.protocol_code = 1 THEN presence_p25.nac END
                AS presence_nac,
            CASE WHEN system.protocol_code = 1 THEN presence_p25.rfss END
                AS presence_rfss,
            CASE WHEN system.protocol_code = 1 THEN presence_p25.site
                ELSE presence_trunked.site_id END AS presence_site_id,
            CASE WHEN system.protocol_code IN (3, 4) THEN presence_trunked.ran END AS presence_ran,
            nullif(trim(presence_config.site_name), '') AS presence_site_name,
            nullif(trim(presence_config.name), '') AS presence_name
            """;
    }

    private static String radioPresenceJoins(String radioIdColumn)
    {
        return """
            LEFT JOIN trunked_radio_channel_presence presence
              ON presence.radio_system_id = system.id AND presence.radio_identity_id = %s
            LEFT JOIN receiver_channel presence_channel ON presence_channel.id = presence.channel_id
            LEFT JOIN p25_site_snapshot presence_p25
              ON system.protocol_code = 1 AND presence_p25.channel_id = presence_channel.id
            LEFT JOIN trunked_site_snapshot presence_trunked
              ON system.protocol_code IN (3, 4) AND presence_trunked.channel_id = presence_channel.id
            LEFT JOIN configuration_channel presence_config
              ON presence_config.configuration_id = presence_channel.configuration_id
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

        String configurationId = request.text("configuration_id");

        if(configurationId != null)
        {
            sql.append(" AND presence_config.configuration_id = ?");
            parameters.add(configurationId);
        }
    }

    private static void nestRadioPresence(List<Map<String,Object>> rows)
    {
        for(Map<String,Object> row: rows)
        {
            Object configurationId = row.remove("presence_configuration_id");
            Object evidence = row.remove("presence_evidence");
            Object confirmedAt = row.remove("presence_confirmed_at_ms");

            if(evidence == null || confirmedAt == null)
            {
                removePresenceColumns(row);
                row.put("presence", null);
                continue;
            }

            Map<String,Object> channel = new LinkedHashMap<>();
            channel.put("configuration_id", configurationId);
            movePresenceColumn(row, channel, "protocol_code");
            movePresenceColumn(row, channel, "wacn");
            movePresenceColumn(row, channel, "system_id");
            movePresenceColumn(row, channel, "network_id");
            movePresenceColumn(row, channel, "nac");
            movePresenceColumn(row, channel, "rfss");
            movePresenceColumn(row, channel, "site_id");
            movePresenceColumn(row, channel, "ran");
            movePresenceColumn(row, channel, "site_name");
            movePresenceColumn(row, channel, "name");

            if(configurationId instanceof String channelId && !channelId.isBlank())
            {
                WebEntityRef.put(channel, WebEntityRef.channel(channelId));
            }

            Map<String,Object> presence = new LinkedHashMap<>();
            presence.put("evidence", evidence);
            presence.put("confirmed_at_ms", confirmedAt);
            presence.put("channel", channel);
            row.put("presence", presence);
        }
    }

    private static void movePresenceColumn(Map<String,Object> row, Map<String,Object> channel, String name)
    {
        Object value = row.remove("presence_" + name);

        if(value != null || "site_name".equals(name) || "name".equals(name))
        {
            channel.put(name, value);
        }
    }

    private static void removePresenceColumns(Map<String,Object> row)
    {
        for(String name: List.of("protocol_code", "wacn", "system_id", "network_id", "nac", "rfss",
            "site_id", "ran", "site_name", "name", "configuration_id"))
        {
            row.remove("presence_" + name);
        }
    }

    Map<String,Object> radioSystemTalkerAliases(String radioSystemKey, StatsRequest request)
    {
        return readSnapshot(connection -> {
            requireRadioSystem(connection, radioSystemKey);
            StringBuilder sql = new StringBuilder("""
                SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                    system.protocol_code, system.address_domain_code AS address_domain_code,
                    CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    system.p25_wacn AS wacn, coalesce(system.p25_system_id, (
                        SELECT min(trunked.system_id)
                        FROM receiver_channel channel
                        JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                        WHERE channel.radio_system_id = system.id)) AS system_id,
                    %s
                FROM radio_system_identity_summary summary
                JOIN radio_system system ON system.id = summary.radio_system_id
                WHERE system.system_key = ? AND summary.identity_kind_code = 2
                  AND summary.last_talker_alias IS NOT NULL
                  AND trim(summary.last_talker_alias) <> ''
                """.formatted(TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL));
            List<Object> parameters = new ArrayList<>(List.of(radioSystemKey));

            addTalkerAliasSearch(sql, parameters, request.search());

            sql.append(" ORDER BY ").append(order(request, TALKER_ALIAS_SORT_COLUMNS, "talker_alias"))
                .append(", summary.identity_id LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            enrichRadioSystemRadios(connection, rows, "native_id", "alias_");

            for(Map<String,Object> row: rows)
            {
                WebEntityRef.put(row, identityReference(row, IDENTITY_KIND_RADIO,
                    textValue(row.get("identity_key"))));
            }

            Map<String,Object> response = page(rows, request);
            response.put("total_count", countRadioSystemTalkerAliases(connection, radioSystemKey, request));
            return response;
        });
    }

    private static long countRadioSystemTalkerAliases(Connection connection, String radioSystemKey,
                                                       StatsRequest request) throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM radio_system_identity_summary summary
            JOIN radio_system system ON system.id = summary.radio_system_id
            WHERE system.system_key = ? AND summary.identity_kind_code = 2
              AND summary.last_talker_alias IS NOT NULL
              AND trim(summary.last_talker_alias) <> ''
            """);
        List<Object> parameters = new ArrayList<>(List.of(radioSystemKey));
        addTalkerAliasSearch(sql, parameters, request.search());
        return scalarLong(connection, sql.toString(), parameters.toArray());
    }

    Map<String,Object> radioSystemGroupIdentity(String radioSystemKey, String identityKey)
    {
        RadioSystemIdentityKey.Identity identity = parseIdentityKey(identityKey, Set.of(
            IDENTITY_KIND_TALKGROUP, IDENTITY_KIND_PATCH_GROUP), "identity_key");
        int identityKind = identity.kindCode();
        int groupIdentity = identity.identityId();

        return readSnapshot(connection -> {
            Map<String,Object> radioSystem = requireRadioSystem(connection, radioSystemKey);
            requireCompatibleIdentity(radioSystem, identity, "Group identity not found");
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                    system.protocol_code, system.address_domain_code AS address_domain_code,
                    CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    system.p25_wacn AS wacn, coalesce(system.p25_system_id, (
                        SELECT min(trunked.system_id)
                        FROM receiver_channel channel
                        JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                        WHERE channel.radio_system_id = system.id)) AS system_id,
                    %s,
                    summary.identity_kind_code AS group_identity_kind_code,
                    (SELECT COUNT(*) FROM trunked_radio_group_summary relationship
                        WHERE relationship.radio_system_id = summary.radio_system_id
                          AND relationship.group_identity_id = summary.id
                          AND relationship.group_kind_code = summary.identity_kind_code) AS radios,
                    (SELECT COUNT(*) FROM trunked_radio_affiliation affiliation
                        WHERE summary.identity_kind_code = 1
                          AND affiliation.radio_system_id = summary.radio_system_id
                          AND affiliation.talkgroup_identity_id = summary.id) AS affiliated_radios,
                    (SELECT COUNT(DISTINCT presence.channel_id)
                      FROM trunked_radio_affiliation affiliation
                      JOIN trunked_radio_channel_presence presence
                        ON presence.radio_system_id = affiliation.radio_system_id
                       AND presence.radio_identity_id = affiliation.radio_identity_id
                      WHERE summary.identity_kind_code = 1
                        AND affiliation.radio_system_id = summary.radio_system_id
                        AND affiliation.talkgroup_identity_id = summary.id) AS affiliated_channels,
                    CASE WHEN system.protocol_code = 1 THEN coalesce((
                        SELECT SUM(site_calls.observed_call_count)
                        FROM p25_site_call_identity_bucket site_calls
                        WHERE site_calls.radio_system_id = summary.radio_system_id
                          AND site_calls.identity_role_code = 1
                          AND site_calls.identity_summary_id = summary.id
                    ), 0) ELSE summary.logical_call_count END AS channel_observation_count
                FROM radio_system_identity_summary summary
                JOIN radio_system system ON system.id = summary.radio_system_id
                WHERE system.system_key = ? AND summary.identity_kind_code = ?
                  AND summary.home_wacn = ? AND summary.home_system_id = ? AND summary.identity_id = ?
                """.formatted(TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL), radioSystemKey, identityKind,
                identity.homeWacn(), identity.homeSystemId(), groupIdentity);
            enrichRadioSystemGroupIdentities(connection, rows, "native_id", "alias_");
            enrichSummaryEncryption(rows);
            if(rows.isEmpty())
            {
                rows = List.of(unobservedGroupIdentity(radioSystem, identityKey, identity));
                enrichRadioSystemGroupIdentities(connection, rows, "native_id", "alias_");
            }
            Map<String,Object> row = rows.getFirst();

            row.put("capabilities", groupIdentityCapabilities((int)number(row.get("protocol_code")),
                (int)number(row.get("group_identity_kind_code"))));
            WebEntityRef.put(row, identityKind == IDENTITY_KIND_PATCH_GROUP ?
                WebEntityRef.patchGroup(radioSystemKey, identityKey) :
                WebEntityRef.talkgroup(radioSystemKey, identityKey));
            WebEntityRef.put(row, "radio_system_entity_ref", WebEntityRef.radioSystem(radioSystemKey));
            return Map.of("group_identity", row);
        });
    }

    /** A valid configured Alias can be opened before the receiver has retained activity for it. */
    private static Map<String,Object> unobservedGroupIdentity(Map<String,Object> radioSystem, String identityKey,
                                                               RadioSystemIdentityKey.Identity identity)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        for(String field: List.of("radio_system_id", "radio_system_key", "protocol_code", "address_domain_code",
            "protocol", "wacn", "system_id", "network_id", "system_name"))
        {
            if(radioSystem.get(field) != null)
            {
                row.put(field, radioSystem.get(field));
            }
        }
        row.put("identity_key", identityKey);
        row.put("native_id", identity.identityId());
        row.put("group_identity_kind_code", identity.kindCode());
        row.put("logical_call_count", 0L);
        row.put("source_logical_call_count", 0L);
        row.put("target_logical_call_count", 0L);
        row.put("encrypted_logical_call_count", 0L);
        row.put("recorded_logical_call_count", 0L);
        row.put("stream_submitted_logical_call_count", 0L);
        row.put("signaling_observation_count", 0L);
        row.put("radios", 0L);
        row.put("affiliated_radios", 0L);
        row.put("affiliated_channels", 0L);
        row.put("channel_observation_count", 0L);
        for(String field: GROUP_IDENTITY_SIGNALING_FIELDS)
        {
            row.put(observationCountField(field), 0L);
        }
        return row;
    }

    Map<String,Object> radioSystemGroupIdentityActivity(String radioSystemKey, String identityKey,
                                                         StatsRequest request)
    {
        RadioSystemIdentityKey.Identity identity = parseIdentityKey(identityKey, Set.of(
            IDENTITY_KIND_TALKGROUP, IDENTITY_KIND_PATCH_GROUP), "identity_key");
        int identityKind = identity.kindCode();
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
            Map<String,Object> radioSystem = requireRadioSystem(connection, radioSystemKey);
            requireCompatibleIdentity(radioSystem, identity, "Group identity not found");
            long summaryId = requireIdentitySummaryId(connection, number(radioSystem.get("radio_system_id")),
                identity, "Group identity not found");
            List<Map<String,Object>> stored = queryRows(connection, """
                SELECT CAST(bucket.bucket_start_ms / ? AS INTEGER) * ? AS time_ms,
                    SUM(bucket.logical_call_count) AS logical_call_count,
                    SUM(bucket.encrypted_logical_call_count) AS encrypted_logical_call_count,
                    SUM(bucket.recorded_output_count) AS recorded_logical_call_count,
                    SUM(bucket.streamed_output_count) AS stream_submitted_logical_call_count
                FROM trunked_logical_call_identity_bucket bucket
                WHERE bucket.radio_system_id = ? AND bucket.identity_role_code = ?
                    AND bucket.identity_summary_id = ?
                    AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
                GROUP BY time_ms
                ORDER BY time_ms
                LIMIT ?
                """, bucketMilliseconds, bucketMilliseconds, radioSystem.get("radio_system_id"),
                IDENTITY_ROLE_DESTINATION, summaryId, fromMilliseconds, untilMilliseconds,
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
                FROM radio_system_identity_summary
                WHERE id = ? AND radio_system_id = ?
                """, summaryId, radioSystem.get("radio_system_id"));

            if(!summary.isEmpty())
            {
                for(String field: GROUP_IDENTITY_SIGNALING_FIELDS)
                {
                    totals.put(observationCountField(field), number(summary.getFirst().get(field)));
                }
            }

            Map<String,Object> response = new LinkedHashMap<>();
            response.put("range", responseRange);
            response.put("from_ms", fromMilliseconds);
            response.put("to_ms", toMilliseconds);
            response.put("bucket_ms", bucketMilliseconds);
            response.put("logical_metric_start_ms", radioSystemMetricStartedAt(connection));
            response.put("totals", totals);
            response.put("series", series);
            return response;
        });
    }

    Map<String,Object> radio(String radioSystemKey, String identityKey)
    {
        RadioSystemIdentityKey.Identity identity = parseIdentityKey(identityKey,
            Set.of(IDENTITY_KIND_RADIO), "identity_key");
        int radio = identity.identityId();
        return readSnapshot(connection -> {
            Map<String,Object> radioSystem = requireRadioSystem(connection, radioSystemKey);
            requireCompatibleIdentity(radioSystem, identity, "Radio not found");
            List<Map<String,Object>> rows = queryRows(connection, """
                SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                    system.protocol_code, system.address_domain_code AS address_domain_code,
                    CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    system.p25_wacn AS wacn, coalesce(system.p25_system_id, (
                        SELECT min(trunked.system_id)
                        FROM receiver_channel channel
                        JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                        WHERE channel.radio_system_id = system.id)) AS system_id,
                    %s,
                    affiliated_group.identity_id AS affiliated_talkgroup_id,
                    %s AS affiliated_talkgroup_identity_key,
                    affiliation.confirmed_at_ms AS affiliation_confirmed_at_ms,
                    CASE WHEN affiliation.radio_identity_id IS NOT NULL THEN 1 ELSE 0 END AS currently_affiliated,
                    (SELECT COUNT(*) FROM trunked_radio_group_summary relationship
                        WHERE relationship.radio_system_id = summary.radio_system_id
                          AND relationship.radio_identity_id = summary.id) AS groups,
                    %s
                FROM radio_system_identity_summary summary
                JOIN radio_system system ON system.id = summary.radio_system_id
                LEFT JOIN trunked_radio_affiliation affiliation
                  ON affiliation.radio_system_id = system.id AND affiliation.radio_identity_id = summary.id
                LEFT JOIN radio_system_identity_summary affiliated_group
                  ON affiliated_group.id = affiliation.talkgroup_identity_id
                 AND affiliated_group.radio_system_id = affiliation.radio_system_id
                %s
                WHERE system.system_key = ? AND summary.identity_kind_code = 2
                  AND summary.home_wacn = ? AND summary.home_system_id = ? AND summary.identity_id = ?
                """.formatted(TRUNKED_IDENTITY_DIRECTORY_PROJECTION_SQL,
                IDENTITY_KEY_SQL.formatted("affiliated_group"), radioPresenceSelect(),
                radioPresenceJoins("summary.id")), radioSystemKey, identity.homeWacn(),
                identity.homeSystemId(), radio);
            enrichRadioSystemRadios(connection, rows, "native_id", "alias_");
            enrichSummaryEncryption(rows);
            nestRadioPresence(rows);
            if(rows.isEmpty())
            {
                throw new StatsApiException(404, "Radio not found");
            }
            Map<String,Object> row = rows.getFirst();

            row.put("capabilities", radioSystemCapabilities((int)number(row.get("protocol_code"))));
            WebEntityRef.put(row, identityReference(row, IDENTITY_KIND_RADIO,
                textValue(row.get("identity_key"))));
            WebEntityRef.put(row, "radio_system_entity_ref", WebEntityRef.radioSystem(radioSystemKey));
            WebEntityRef.put(row, "affiliated_talkgroup_entity_ref", identityReference(row,
                IDENTITY_KIND_TALKGROUP, textValue(row.get("affiliated_talkgroup_identity_key"))));
            return Map.of("radio", row);
        });
    }

    Map<String,Object> radioSystemRelationships(String radioSystemKey, StatsRequest request)
    {
        String groupIdentityKey = request.text("group_identity_key");
        String radioIdentityKey = request.text("radio_identity_key");

        if(groupIdentityKey == null && radioIdentityKey == null)
        {
            throw new StatsApiException(400, "radio_identity_key or group_identity_key is required");
        }

        RadioSystemIdentityKey.Identity groupIdentity = groupIdentityKey != null ?
            parseIdentityKey(groupIdentityKey, Set.of(IDENTITY_KIND_TALKGROUP, IDENTITY_KIND_PATCH_GROUP),
                "group_identity_key") : null;
        RadioSystemIdentityKey.Identity radio = radioIdentityKey != null ?
            parseIdentityKey(radioIdentityKey, Set.of(IDENTITY_KIND_RADIO), "radio_identity_key") : null;

        return readSnapshot(connection -> {
            Map<String,Object> radioSystem = requireRadioSystem(connection, radioSystemKey);

            if(groupIdentity != null)
            {
                requireCompatibleIdentity(radioSystem, groupIdentity, "Group identity not found");
            }

            if(radio != null)
            {
                requireCompatibleIdentity(radioSystem, radio, "Radio not found");
            }

            Long groupSummaryId = groupIdentity != null ? requireIdentitySummaryId(connection,
                number(radioSystem.get("radio_system_id")), groupIdentity, "Group identity not found") : null;
            Long radioSummaryId = radio != null ? requireIdentitySummaryId(connection,
                number(radioSystem.get("radio_system_id")), radio, "Radio not found") : null;

            StringBuilder sql = new StringBuilder("""
                SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                    system.protocol_code, system.address_domain_code AS address_domain_code,
                    CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    system.p25_wacn AS wacn, coalesce(system.p25_system_id, (
                        SELECT min(trunked.system_id)
                        FROM receiver_channel channel
                        JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                        WHERE channel.radio_system_id = system.id)) AS system_id,
                    relationship.radio_system_id, radio.identity_id AS radio_native_id,
                    radio.id AS radio_identity_summary_id,
                    %s AS radio_identity_key,
                    group_identity.identity_id AS group_native_id,
                    group_identity.id AS group_identity_summary_id,
                    %s AS group_identity_key,
                    relationship.group_kind_code AS group_identity_kind_code,
                    relationship.first_seen_ms,
                    relationship.last_seen_ms, %s,
                    relationship.last_encryption_algorithm_id,
                    relationship.last_encryption_key_id,
                    radio.last_talker_alias,
                    CASE WHEN %s THEN 1 ELSE 0 END AS currently_affiliated,
                    %s
                FROM trunked_radio_group_summary relationship
                JOIN radio_system system ON system.id = relationship.radio_system_id
                JOIN radio_system_identity_summary radio
                  ON radio.radio_system_id = relationship.radio_system_id AND radio.identity_kind_code = 2
                 AND radio.id = relationship.radio_identity_id
                JOIN radio_system_identity_summary group_identity
                  ON group_identity.radio_system_id = relationship.radio_system_id
                 AND group_identity.identity_kind_code = relationship.group_kind_code
                 AND group_identity.id = relationship.group_identity_id
                LEFT JOIN trunked_radio_affiliation affiliation
                  ON affiliation.radio_system_id = relationship.radio_system_id
                 AND affiliation.radio_identity_id = relationship.radio_identity_id
                %s
                WHERE system.system_key = ?
                """.formatted(IDENTITY_KEY_SQL.formatted("radio"),
                IDENTITY_KEY_SQL.formatted("group_identity"), TRUNKED_RELATIONSHIP_METRIC_PROJECTION_SQL,
                CURRENT_RELATIONSHIP_AFFILIATION_SQL, radioPresenceSelect(),
                radioPresenceJoins("relationship.radio_identity_id")));
            List<Object> parameters = new ArrayList<>(List.of(radioSystemKey));
            addRelationshipIdentityFilters(sql, parameters, groupSummaryId, radioSummaryId);
            addRadioFilters(sql, parameters, request, CURRENT_RELATIONSHIP_AFFILIATION_SQL);

            sql.append(" ORDER BY ").append(order(request, RELATIONSHIP_SORT_COLUMNS, "last_seen"))
                .append(", relationship.group_kind_code, group_identity.identity_id, radio.identity_id")
                .append(" LIMIT ? OFFSET ?");
            addPageParameters(parameters, request);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            enrichRadioSystemRadios(connection, rows, "radio_identity_summary_id", "radio_native_id",
                "radio_alias_");
            enrichRadioSystemGroupIdentities(connection, rows, "group_identity_summary_id",
                "group_native_id", "group_identity_alias_");
            nestRadioPresence(rows);

            for(Map<String,Object> row: rows)
            {
                WebEntityRef.put(row, "radio_entity_ref", identityReference(row, IDENTITY_KIND_RADIO,
                    textValue(row.get("radio_identity_key"))));
                WebEntityRef.put(row, "group_identity_entity_ref", identityReference(row,
                    (int)number(row.get("group_identity_kind_code")),
                    textValue(row.get("group_identity_key"))));
            }

            Map<String,Object> response = page(rows, request);
            response.put("total_count", countRadioGroupRelationships(connection, radioSystemKey, groupSummaryId,
                radioSummaryId, request));
            return response;
        });
    }

    private static long countRadioGroupRelationships(Connection connection, String systemKey, Long groupSummaryId,
                                                      Long radioSummaryId, StatsRequest request)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
            FROM trunked_radio_group_summary relationship
            JOIN radio_system system ON system.id = relationship.radio_system_id
            LEFT JOIN trunked_radio_affiliation affiliation
              ON affiliation.radio_system_id = relationship.radio_system_id
             AND affiliation.radio_identity_id = relationship.radio_identity_id
            LEFT JOIN trunked_radio_channel_presence presence
              ON presence.radio_system_id = relationship.radio_system_id
             AND presence.radio_identity_id = relationship.radio_identity_id
            LEFT JOIN receiver_channel presence_channel ON presence_channel.id = presence.channel_id
            LEFT JOIN configuration_channel presence_config
              ON presence_config.configuration_id = presence_channel.configuration_id
            WHERE system.system_key = ?
            """);
        List<Object> parameters = new ArrayList<>(List.of(systemKey));
        addRelationshipIdentityFilters(sql, parameters, groupSummaryId, radioSummaryId);
        addRadioFilters(sql, parameters, request, CURRENT_RELATIONSHIP_AFFILIATION_SQL);
        return scalarLong(connection, sql.toString(), parameters.toArray());
    }

    private static void addRelationshipIdentityFilters(StringBuilder sql, List<Object> parameters,
                                                       Long groupSummaryId, Long radioSummaryId)
    {
        if(groupSummaryId != null)
        {
            sql.append(" AND relationship.group_identity_id = ?");
            parameters.add(groupSummaryId);
        }

        if(radioSummaryId != null)
        {
            sql.append(" AND relationship.radio_identity_id = ?");
            parameters.add(radioSummaryId);
        }
    }

    Map<String,Object> trunkedChannelDetail(String configurationId)
    {
        return readSnapshot(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireChannel(connection, configurationId);
            Map<String,Object> channel = configuredTrunkedChannelReadModel(connection, configured);
            StatsApiProtocol apiProtocol = configured.protocol();
            channel.put("capabilities", apiProtocol.trunkedChannelCapabilities());

            if(configured.radioSystemKey() != null)
            {
                channel.put("radio_system_key", configured.radioSystemKey());
                WebEntityRef.put(channel, "radio_system_entity_ref",
                    WebEntityRef.radioSystem(configured.radioSystemKey()));
            }

            Object mfid = channel.get("mfid");
            if(configured.protocolCode() == 1 && mfid instanceof Number number)
            {
                channel.put("mfid_display", mfidDisplay(number.intValue()));
            }

            channel.put("affiliated_radios", configured.channelId() == null ? 0L : scalarLong(connection, """
                SELECT COUNT(*)
                FROM trunked_radio_channel_presence presence
                JOIN trunked_radio_affiliation affiliation
                  ON affiliation.radio_system_id = presence.radio_system_id
                 AND affiliation.radio_identity_id = presence.radio_identity_id
                WHERE presence.channel_id = ?
                """, configured.channelId()));

            return Map.of("channel", channel);
        });
    }

    Map<String,Object> trunkedChannelFrequencies(String configurationId, StatsRequest request)
    {
        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireChannel(connection, configurationId);
            return page(queryTrunkedChannelFrequencies(connection, configured, request.limit() + 1,
                request.offset()), request);
        });
    }

    private List<Map<String,Object>> queryTrunkedChannelFrequencies(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, int limit, int offset) throws SQLException
    {
        if(configured.channelId() == null)
        {
            return List.of();
        }

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
                    WHERE channel_id = ?
                    ORDER BY channel_number = -1, channel_number, timeslot = -1, timeslot,
                        frequency_hz = -1, frequency_hz,
                        inbound_channel_number = -1, inbound_channel_number
                    LIMIT ? OFFSET ?
                    """, System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS, configured.channelId(),
                    limit, offset);
        }

        if(configured.protocolCode() != StatsApiProtocol.P25.databaseCode())
        {
            return List.of();
        }

        List<Map<String,Object>> rows = queryRows(connection, """
            WITH logical AS (
                SELECT summary.channel_id, summary.channel_key AS raw_channel_key,
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
                  ON current.channel_id = summary.channel_id AND current.channel_key = summary.channel_key
                JOIN p25_site_snapshot site ON site.channel_id = summary.channel_id
                WHERE summary.channel_id = ?
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
                  ON tag.channel_id = logical.channel_id AND tag.channel_key = logical.raw_channel_key
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
                  ON tag.channel_id = logical.channel_id AND tag.channel_key = logical.raw_channel_key
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
            """, configured.channelId(), limit, offset,
            System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS);
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

    Map<String,Object> trunkedChannelGroupIdentities(String configurationId, StatsRequest request)
    {
        ActivityRange range = activityRange(request);

        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireChannel(connection, configurationId);
            List<Map<String,Object>> rows = queryTrunkedChannelGroupIdentities(connection, configured, request,
                request.limit() + 1, request.offset());
            Map<String,Object> response = new LinkedHashMap<>(page(rows, request));
            response.put("range", range.label());
            response.put("from_ms", System.currentTimeMillis() - range.milliseconds());
            response.put("to_ms", System.currentTimeMillis());
            response.put("bucket_ms", HOUR_MILLISECONDS);
            response.put("logical_metric_start_ms", radioSystemMetricStartedAt(connection));
            return response;
        });
    }

    private List<Map<String,Object>> queryTrunkedChannelGroupIdentities(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, StatsRequest request, int limit, int offset)
        throws SQLException
    {
        ActivityRange range = activityRange(request);
        long throughMilliseconds = Math.floorDiv(System.currentTimeMillis(), HOUR_MILLISECONDS) * HOUR_MILLISECONDS;
        long bucketCount = Math.max(1, (range.milliseconds() + HOUR_MILLISECONDS - 1) / HOUR_MILLISECONDS);
        long fromMilliseconds = throughMilliseconds - (bucketCount - 1) * HOUR_MILLISECONDS;
        Long radioSystemId = configured.radioSystemId();
        String search = request.search();
        String requestedOrder = order(request, CHANNEL_GROUP_IDENTITY_SORT_COLUMNS, "logical_call_count");
        String groupedSql;
        List<Object> parameters = new ArrayList<>();

        if(radioSystemId != null && configured.protocolCode() == StatsApiProtocol.P25.databaseCode() &&
            configured.channelId() != null)
        {
            groupedSql = """
                SELECT summary.id AS identity_summary_id,
                    summary.identity_id AS native_id,
                    summary.identity_kind_code AS group_identity_kind_code,
                    %s AS identity_key,
                    (SELECT latest.observed_local_id
                     FROM p25_site_call_identity_bucket latest
                     WHERE latest.radio_system_id = bucket.radio_system_id
                       AND latest.channel_id = bucket.channel_id
                       AND latest.identity_role_code = bucket.identity_role_code
                       AND latest.identity_summary_id = bucket.identity_summary_id
                     ORDER BY latest.last_observed_at_ms DESC, latest.bucket_start_ms DESC
                     LIMIT 1) AS observed_local_id,
                    SUM(bucket.observed_call_count) AS logical_call_count,
                    SUM(bucket.encrypted_observed_call_count) AS encrypted_logical_call_count,
                    NULL AS recorded_logical_call_count, NULL AS stream_submitted_logical_call_count,
                    NULL AS frequency_hz, NULL AS timeslot, NULL AS last_source_radio_id,
                    MIN(bucket.bucket_start_ms) AS first_seen_ms,
                    MAX(bucket.bucket_start_ms) AS last_seen_ms
                FROM p25_site_call_identity_bucket bucket
                JOIN radio_system_identity_summary summary
                  ON summary.id = bucket.identity_summary_id
                 AND summary.radio_system_id = bucket.radio_system_id
                WHERE bucket.radio_system_id = ?
                  AND bucket.channel_id = ?
                  AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
                  AND bucket.identity_role_code = ? AND summary.identity_kind_code IN (1, 3)
                GROUP BY summary.id
                """.formatted(IDENTITY_KEY_SQL.formatted("summary"));
            parameters.addAll(List.of(radioSystemId, configured.channelId(), fromMilliseconds,
                throughMilliseconds + HOUR_MILLISECONDS, IDENTITY_ROLE_DESTINATION));
        }
        else if(radioSystemId != null && configured.protocolCode() != StatsApiProtocol.P25.databaseCode())
        {
            groupedSql = """
                SELECT summary.id AS identity_summary_id,
                    summary.identity_id AS native_id,
                    summary.identity_id AS observed_local_id,
                    summary.identity_kind_code AS group_identity_kind_code,
                    %s AS identity_key,
                    SUM(bucket.logical_call_count) AS logical_call_count,
                    SUM(bucket.encrypted_logical_call_count) AS encrypted_logical_call_count,
                    SUM(bucket.recorded_output_count) AS recorded_logical_call_count,
                    SUM(bucket.streamed_output_count) AS stream_submitted_logical_call_count,
                    NULL AS frequency_hz, NULL AS timeslot, NULL AS last_source_radio_id,
                    MIN(bucket.bucket_start_ms) AS first_seen_ms,
                    MAX(bucket.bucket_start_ms) AS last_seen_ms
                FROM trunked_logical_call_identity_bucket bucket
                JOIN radio_system_identity_summary summary
                  ON summary.id = bucket.identity_summary_id
                 AND summary.radio_system_id = bucket.radio_system_id
                WHERE bucket.radio_system_id = ? AND bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
                  AND bucket.identity_role_code = ? AND summary.identity_kind_code IN (1, 3)
                GROUP BY summary.id
                """.formatted(IDENTITY_KEY_SQL.formatted("summary"));
            parameters.addAll(List.of(radioSystemId, fromMilliseconds,
                throughMilliseconds + HOUR_MILLISECONDS, IDENTITY_ROLE_DESTINATION));
        }
        else
        {
            return List.of();
        }

        String aliasProjection = channelAliasProjection(configured, "alias_talkgroup",
            "grouped.observed_local_id");
        StringBuilder sql = new StringBuilder("WITH grouped AS (").append(groupedSql).append("), presented AS (")
            .append("SELECT grouped.*, ")
            .append(aliasProjection).append(" FROM grouped) SELECT * FROM presented");
        addChannelIdentitySearch(sql, parameters, search, "coalesce(observed_local_id, native_id)");
        sql.append(" ORDER BY ").append(requestedOrder)
            .append(", group_identity_kind_code ASC, native_id ASC LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        removeMatchedAliasFields(rows);

        for(Map<String,Object> row: rows)
        {
            row.put("configuration_id", configured.configurationId());
            row.put("radio_system_key", configured.radioSystemKey());
            row.put("protocol_code", configured.protocolCode());
            row.put("address_domain_code", configured.addressDomainCode());
            row.put("alias_list_id", configured.aliasListId());
            row.put("alias_list_name", configured.aliasListName());
            WebEntityRef.put(row, identityReference(row,
                (int)number(row.get("group_identity_kind_code")),
                textValue(row.get("identity_key"))));
        }

        enrichChannelGroupAliases(connection, configured.protocol(), rows);
        return rows;
    }

    Map<String,Object> trunkedChannelFrequencyBands(String configurationId, StatsRequest request)
    {
        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireChannel(connection, configurationId);

            if(configured.protocolCode() != StatsApiProtocol.P25.databaseCode() || configured.channelId() == null)
            {
                return Map.of("rows", List.of(), "foreign_rows", List.of(), "foreign_limit", request.limit(),
                    "foreign_offset", request.offset(), "foreign_has_more", false);
            }

            long currentSince = System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS;
            Map<String,Object> response = new LinkedHashMap<>();
            List<Map<String,Object>> identityRows = queryRows(connection, """
                SELECT json_extract(config.config_json,
                           '$.decodeConfiguration.useP25BandplanOverride') = 1 AS override_enabled,
                    coalesce(system.p25_wacn,
                        json_extract(config.config_json, '$.p25SiteIdentity.wacn')) AS wacn,
                    coalesce(system.p25_system_id,
                        json_extract(config.config_json, '$.p25SiteIdentity.system')) AS system_id,
                    coalesce(snapshot.rfss,
                        json_extract(config.config_json, '$.p25SiteIdentity.rfss')) AS rfss,
                    coalesce(snapshot.site,
                        json_extract(config.config_json, '$.p25SiteIdentity.site')) AS site_id
                FROM configuration_channel config
                LEFT JOIN receiver_channel channel ON channel.configuration_id = config.configuration_id
                LEFT JOIN p25_site_snapshot snapshot ON snapshot.channel_id = channel.id
                LEFT JOIN radio_system system ON system.id = channel.radio_system_id
                WHERE config.id = ?
                """, configured.rowId());
            Map<String,Object> identity = identityRows.isEmpty() ? Map.of() : identityRows.getFirst();
            response.put("wacn", identity.get("wacn"));
            response.put("system_id", identity.get("system_id"));
            response.put("rfss", identity.get("rfss"));
            response.put("site_id", identity.get("site_id"));
            P25BandplanOverrideProfile override = null;

            if(!identityRows.isEmpty() && number(identityRows.getFirst().get("override_enabled")) == 1)
            {
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
                      ON summary.channel_id = current.channel_id AND summary.band = current.band
                    WHERE current.channel_id = ? ORDER BY current.band
                    """, currentSince, configured.channelId()));
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
                  ON current.channel_id = summary.channel_id
                 AND current.foreign_wacn = summary.foreign_wacn
                 AND current.foreign_system_id = summary.foreign_system_id
                 AND current.band = summary.band
                WHERE summary.channel_id = ?
                ORDER BY CASE WHEN current.band IS NULL THEN 1 ELSE 0 END,
                    summary.foreign_wacn, summary.foreign_system_id, summary.band
                LIMIT ? OFFSET ?
                """, currentSince, configured.channelId(), request.limit() + 1, request.offset());
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

    Map<String,Object> trunkedChannelNeighbors(String configurationId, StatsRequest request)
    {
        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireChannel(connection, configurationId);
            return page(queryTrunkedChannelNeighbors(connection, configured, request.limit() + 1,
                request.offset()), request);
        });
    }

    private List<Map<String,Object>> queryTrunkedChannelNeighbors(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, int limit, int offset) throws SQLException
    {
        if(configured.channelId() == null)
        {
            return List.of();
        }

        long currentSince = System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS;
        int protocolCode = configured.protocolCode();

        if(protocolCode == 3 || protocolCode == 4)
        {
            List<Map<String,Object>> rows = queryRows(connection, """
                    SELECT 'CHANNEL' AS entry_type, neighbor.variant_code, neighbor.location_category_code,
                        NULLIF(neighbor.network_id, -1) AS network_id,
                        NULLIF(neighbor.system_id, -1) AS system_id,
                        NULLIF(neighbor.site_id, -1) AS site_id,
                        NULLIF(neighbor.channel_number, -1) AS channel_number,
                        NULLIF(neighbor.frequency_hz, -1) AS frequency_hz,
                        NULLIF(neighbor.frequency_hz, -1) AS downlink_hz, neighbor.status_flags,
                        neighbor.first_seen_ms, neighbor.last_seen_ms, neighbor.observation_count,
                        CASE WHEN neighbor.last_seen_ms >= ? THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM trunked_site_neighbor_summary neighbor
                    WHERE neighbor.channel_id = ?
                    ORDER BY neighbor.location_category_code,
                        neighbor.network_id = -1, neighbor.network_id,
                        neighbor.system_id = -1, neighbor.system_id,
                        neighbor.site_id = -1, neighbor.site_id,
                        neighbor.channel_number = -1, neighbor.channel_number,
                        neighbor.variant_code, neighbor.frequency_hz = -1, neighbor.frequency_hz
                    LIMIT ? OFFSET ?
                    """, currentSince, configured.channelId(), limit, offset);

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
                        'CHANNEL' AS entry_type, source_system.p25_wacn AS wacn, summary.neighbor_key,
                        coalesce(current.system_id, summary.system_id) AS system_id,
                        coalesce(current.rfss, summary.rfss) AS rfss,
                        coalesce(current.site, summary.site) AS site_id,
                        coalesce(current.lra, summary.lra) AS lra,
                        coalesce(current.channel_descriptor, summary.channel_descriptor) AS channel_descriptor,
                        coalesce(current.downlink_hz, summary.downlink_hz) AS downlink_hz,
                        coalesce(current.uplink_hz, summary.uplink_hz) AS uplink_hz,
                        coalesce(current.status, summary.status) AS status,
                        current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                        summary.observation_count,
                        nullif(trim(neighbor_config.site_name), '') AS neighbor_site_name,
                        nullif(trim(neighbor_config.name), '') AS neighbor_name,
                        neighbor_config.configuration_id AS neighbor_configuration_id,
                        NULL AS band_count, NULL AS has_fdma, NULL AS has_tdma, NULL AS has_unknown,
                        CASE WHEN max(coalesce(current.confirmed_at_ms, 0), summary.last_seen_ms) >= ?
                            THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM p25_site_neighbor_summary summary
                    LEFT JOIN p25_site_neighbor current
                      ON current.channel_id = summary.channel_id
                     AND current.neighbor_key = summary.neighbor_key
                    JOIN receiver_channel source_channel ON source_channel.id = summary.channel_id
                    JOIN radio_system source_system ON source_system.id = source_channel.radio_system_id
                    LEFT JOIN radio_system neighbor_system
                      ON neighbor_system.p25_wacn = source_system.p25_wacn
                     AND neighbor_system.p25_system_id = coalesce(current.system_id, summary.system_id)
                    LEFT JOIN p25_site_snapshot neighbor_site
                      ON neighbor_site.channel_id = (
                        SELECT min(candidate.channel_id)
                        FROM p25_site_snapshot candidate
                        JOIN receiver_channel candidate_channel
                          ON candidate_channel.id = candidate.channel_id
                        WHERE candidate_channel.radio_system_id = neighbor_system.id
                          AND candidate.rfss = coalesce(current.rfss, summary.rfss)
                          AND candidate.site = coalesce(current.site, summary.site)
                        HAVING count(*) = 1
                     )
                    LEFT JOIN receiver_channel neighbor_channel
                      ON neighbor_channel.id = neighbor_site.channel_id
                    LEFT JOIN configuration_channel neighbor_config
                      ON neighbor_config.configuration_id = neighbor_channel.configuration_id
                    WHERE summary.channel_id = ?

                    UNION ALL

                    SELECT 1 AS entry_order,
                        CASE WHEN MAX(current.confirmed_at_ms) IS NULL THEN 1 ELSE 0 END AS current_order,
                        'ISSI' AS entry_type, summary.foreign_wacn AS wacn,
                        printf('%X:%03X', summary.foreign_wacn, summary.foreign_system_id) AS neighbor_key,
                        summary.foreign_system_id AS system_id, NULL AS rfss, NULL AS site_id, NULL AS lra,
                        NULL AS channel_descriptor, NULL AS downlink_hz, NULL AS uplink_hz,
                        'ISSI ADVERTISED' AS status, MAX(current.confirmed_at_ms) AS confirmed_at_ms,
                        MIN(summary.first_seen_ms) AS first_seen_ms, MAX(summary.last_seen_ms) AS last_seen_ms,
                        SUM(summary.observation_count) AS observation_count,
                        NULL AS neighbor_site_name, NULL AS neighbor_name,
                        NULL AS neighbor_configuration_id,
                        COUNT(*) AS band_count,
                        MAX(CASE WHEN summary.channel_type BETWEEN 0 AND 2 THEN 1 ELSE 0 END) AS has_fdma,
                        MAX(CASE WHEN summary.channel_type BETWEEN 3 AND 5 THEN 1 ELSE 0 END) AS has_tdma,
                        MAX(CASE WHEN summary.channel_type NOT BETWEEN 0 AND 5 THEN 1 ELSE 0 END) AS has_unknown,
                        CASE WHEN MAX(coalesce(current.confirmed_at_ms, 0)) >= ?
                                  OR MAX(summary.last_seen_ms) >= ?
                            THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                    FROM p25_foreign_system_band_summary summary
                    LEFT JOIN p25_foreign_system_band current
                      ON current.channel_id = summary.channel_id
                     AND current.foreign_wacn = summary.foreign_wacn
                     AND current.foreign_system_id = summary.foreign_system_id
                     AND current.band = summary.band
                    WHERE summary.channel_id = ?
                    GROUP BY summary.foreign_wacn, summary.foreign_system_id
                )
                SELECT entry_type, wacn, neighbor_key, system_id, rfss, site_id, lra, channel_descriptor,
                    downlink_hz, uplink_hz, status, confirmed_at_ms, first_seen_ms, last_seen_ms,
                    observation_count, neighbor_site_name, neighbor_name,
                    neighbor_configuration_id, band_count, has_fdma, has_tdma, has_unknown, state
                FROM combined
                ORDER BY entry_order, current_order, system_id, rfss, site_id, neighbor_key
                LIMIT ? OFFSET ?
                """, currentSince, configured.channelId(), currentSince, currentSince, configured.channelId(),
                limit, offset);

        for(Map<String,Object> row: rows)
        {
            row.put("protocol_code", protocolCode);

            Object neighborConfigurationId = row.get("neighbor_configuration_id");

            if(neighborConfigurationId != null)
            {
                WebEntityRef.put(row, WebEntityRef.channel(String.valueOf(neighborConfigurationId)));
            }
        }

        return rows;
    }

    Map<String,Object> trunkedChannelPatchGroups(String configurationId, StatsRequest request)
    {
        int groupLimit = request.limit(MAXIMUM_PATCH_GROUP_PAGE);
        int offset = request.offset();
        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireChannel(connection, configurationId);

            if(configured.protocolCode() != StatsApiProtocol.P25.databaseCode() || configured.channelId() == null)
            {
                return trunkedChannelPatchResponse(List.of(), List.of(), List.of(), groupLimit, offset,
                    false, false);
            }

            List<Map<String,Object>> groups = queryRows(connection, """
                SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                    system.protocol_code, system.address_domain_code AS address_domain_code,
                    system.p25_wacn AS wacn, system.p25_system_id AS system_id,
                    current.local_patch_group_id, current.version,
                    current.confirmed_at_ms, summary.first_seen_ms, summary.last_seen_ms,
                    summary.observation_count,
                    (SELECT COUNT(*) FROM p25_site_patch_group_talkgroup member
                     WHERE member.channel_id = current.channel_id
                       AND member.local_patch_group_id = current.local_patch_group_id)
                        AS talkgroup_count,
                    (SELECT COUNT(*) FROM p25_site_patch_group_radio member
                     WHERE member.channel_id = current.channel_id
                       AND member.local_patch_group_id = current.local_patch_group_id)
                        AS radio_count,
                    CASE WHEN max(current.confirmed_at_ms, coalesce(summary.last_seen_ms, 0)) >= ?
                        THEN 'CURRENT' ELSE 'HISTORICAL' END AS state
                FROM p25_site_patch_group current
                JOIN receiver_channel channel ON channel.id = current.channel_id
                JOIN radio_system system ON system.id = channel.radio_system_id
                LEFT JOIN p25_site_patch_group_summary summary
                  ON summary.channel_id = current.channel_id
                 AND summary.local_patch_group_id = current.local_patch_group_id
                WHERE current.channel_id = ? ORDER BY current.local_patch_group_id
                LIMIT ? OFFSET ?
                """, System.currentTimeMillis() - CURRENT_STATE_WINDOW_MILLISECONDS, configured.channelId(),
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
                talkgroupParameters.add(configured.channelId());

                for(Map<String,Object> group: groups)
                {
                    talkgroupParameters.add(group.get("local_patch_group_id"));
                }

                talkgroupParameters.add(MAXIMUM_PATCH_MEMBERS_PER_GROUP);
                talkgroupParameters.add(MAXIMUM_PATCH_MEMBER_ROWS + 1);
                talkgroups = queryRows(connection, """
                    WITH ranked AS (
                        SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                            system.protocol_code, system.address_domain_code AS address_domain_code,
                            system.p25_wacn AS wacn, system.p25_system_id AS system_id,
                            current.local_patch_group_id, current.local_talkgroup_id,
                            current.confirmed_at_ms, summary.first_seen_ms,
                            summary.last_seen_ms, summary.observation_count,
                            row_number() OVER (PARTITION BY current.local_patch_group_id
                                ORDER BY current.local_talkgroup_id) AS member_rank
                        FROM p25_site_patch_group_talkgroup current
                        JOIN receiver_channel channel ON channel.id = current.channel_id
                        JOIN radio_system system ON system.id = channel.radio_system_id
                        LEFT JOIN p25_site_patch_group_talkgroup_summary summary
                          ON summary.channel_id = current.channel_id
                         AND summary.local_patch_group_id = current.local_patch_group_id
                         AND summary.local_talkgroup_id = current.local_talkgroup_id
                        WHERE current.channel_id = ? AND current.local_patch_group_id IN (%s)
                    )
                    SELECT radio_system_id, radio_system_key, protocol_code, address_domain_code,
                        wacn, system_id, local_patch_group_id, local_talkgroup_id, confirmed_at_ms,
                        first_seen_ms, last_seen_ms, observation_count
                    FROM ranked WHERE member_rank <= ?
                    ORDER BY local_patch_group_id, local_talkgroup_id LIMIT ?
                    """.formatted(placeholders), talkgroupParameters.toArray());
                List<Object> radioParameters = new ArrayList<>();
                radioParameters.add(configured.channelId());

                for(Map<String,Object> group: groups)
                {
                    radioParameters.add(group.get("local_patch_group_id"));
                }

                radioParameters.add(MAXIMUM_PATCH_MEMBERS_PER_GROUP);
                radioParameters.add(MAXIMUM_PATCH_MEMBER_ROWS + 1);
                radios = queryRows(connection, """
                    WITH ranked AS (
                        SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                            system.protocol_code, system.address_domain_code AS address_domain_code,
                            system.p25_wacn AS wacn, system.p25_system_id AS system_id,
                            current.local_patch_group_id, current.local_radio_id,
                            current.confirmed_at_ms, summary.first_seen_ms,
                            summary.last_seen_ms, summary.observation_count,
                            row_number() OVER (PARTITION BY current.local_patch_group_id
                                ORDER BY current.local_radio_id) AS member_rank
                        FROM p25_site_patch_group_radio current
                        JOIN receiver_channel channel ON channel.id = current.channel_id
                        JOIN radio_system system ON system.id = channel.radio_system_id
                        LEFT JOIN p25_site_patch_group_radio_summary summary
                          ON summary.channel_id = current.channel_id
                         AND summary.local_patch_group_id = current.local_patch_group_id
                         AND summary.local_radio_id = current.local_radio_id
                        WHERE current.channel_id = ? AND current.local_patch_group_id IN (%s)
                    )
                    SELECT radio_system_id, radio_system_key, protocol_code, address_domain_code,
                        wacn, system_id, local_patch_group_id, local_radio_id, confirmed_at_ms,
                        first_seen_ms, last_seen_ms, observation_count
                    FROM ranked WHERE member_rank <= ?
                    ORDER BY local_patch_group_id, local_radio_id LIMIT ?
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

            groups.forEach(row -> row.put("alias_list_id", configured.aliasListId()));
            talkgroups.forEach(row -> row.put("alias_list_id", configured.aliasListId()));
            radios.forEach(row -> row.put("alias_list_id", configured.aliasListId()));
            mAliasResolver.enrichTalkgroups(connection, groups, "local_patch_group_id", "patch_alias_");
            mAliasResolver.enrichTalkgroups(connection, talkgroups, "local_talkgroup_id", "alias_");
            mAliasResolver.enrichRadios(connection, radios, "local_radio_id", "alias_");

            for(Map<String,Object> group: groups)
            {
                long patchGroup = number(group.get("local_patch_group_id"));
                long includedTalkgroups = countPatchMembers(talkgroups, patchGroup);
                long includedRadios = countPatchMembers(radios, patchGroup);
                group.put("talkgroups_included", includedTalkgroups);
                group.put("radios_included", includedRadios);
                group.put("talkgroups_truncated", number(group.get("talkgroup_count")) > includedTalkgroups);
                group.put("radios_truncated", number(group.get("radio_count")) > includedRadios);

            }

            boolean membersTruncated = talkgroupsTruncated || radiosTruncated || groups.stream()
                .anyMatch(group -> Boolean.TRUE.equals(group.get("talkgroups_truncated")) ||
                    Boolean.TRUE.equals(group.get("radios_truncated")));
            return trunkedChannelPatchResponse(groups, talkgroups, radios, groupLimit, offset, hasMore,
                membersTruncated);
        });
    }

    private static Map<String,Object> trunkedChannelPatchResponse(List<Map<String,Object>> groups,
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
        response.put("has_more", hasMore);
        response.put("next_offset", hasMore ? offset + limit : null);
        return response;
    }

    private static long countPatchMembers(List<Map<String,Object>> members, long patchGroup)
    {
        long count = 0;

        for(Map<String,Object> member: members)
        {
            if(number(member.get("local_patch_group_id")) == patchGroup)
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
                if(number(row.get("native_id")) <= 0)
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
            response.put("has_more", hasMore);
            response.put("next_offset", nextOffset);
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
                SELECT event.radio_system_id, NULL AS fallback_channel_id,
                    NULL AS representative_channel_id, event.source_identity_summary_id,
                    NULL AS observed_local_id, COUNT(*) AS observation_count,
                    MAX(event.observed_at_ms) AS last_seen_ms
                FROM action_slices AS slice
                CROSS JOIN receiver_activity_event AS event
                    INDEXED BY idx_receiver_activity_event_channel_time
                JOIN receiver_channel channel ON channel.id = event.channel_id
                JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
                WHERE event.channel_id = slice.channel_id
                  AND event.observed_at_ms >= slice.bucket_start_ms
                  AND event.observed_at_ms < slice.bucket_start_ms + ?
                  AND event.action_code = ?
                  AND config.channel_kind = 'TRUNKED'
                GROUP BY event.radio_system_id, event.source_identity_summary_id

                UNION ALL

                SELECT NULL AS radio_system_id, event.channel_id AS fallback_channel_id,
                    event.channel_id AS representative_channel_id, NULL AS source_identity_summary_id,
                    event.source_observed_local_id AS observed_local_id,
                    COUNT(*) AS observation_count, MAX(event.observed_at_ms) AS last_seen_ms
                FROM action_slices AS slice
                CROSS JOIN receiver_activity_event AS event
                    INDEXED BY idx_receiver_activity_event_channel_time
                JOIN receiver_channel channel ON channel.id = event.channel_id
                JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
                WHERE event.channel_id = slice.channel_id
                  AND event.observed_at_ms >= slice.bucket_start_ms
                  AND event.observed_at_ms < slice.bucket_start_ms + ?
                  AND event.action_code = ?
                  AND config.channel_kind = 'CONVENTIONAL'
                GROUP BY event.channel_id, event.source_observed_local_id
            ), totals AS MATERIALIZED (
                SELECT COALESCE(SUM(observation_count), 0) AS retained_observation_count,
                    COALESCE(SUM(CASE WHEN source_identity_summary_id IS NOT NULL OR observed_local_id > 0
                        THEN observation_count ELSE 0 END), 0)
                        AS identified_observation_count,
                    COALESCE(SUM(CASE WHEN source_identity_summary_id IS NOT NULL OR observed_local_id > 0
                        THEN 1 ELSE 0 END), 0) AS total_count
                FROM grouped
            ), paged AS MATERIALIZED (
                SELECT radio_system_id, fallback_channel_id, representative_channel_id,
                    source_identity_summary_id, observed_local_id, observation_count, last_seen_ms
                FROM grouped
                WHERE source_identity_summary_id IS NOT NULL OR observed_local_id > 0
                ORDER BY observation_count DESC, last_seen_ms DESC,
                    CASE WHEN radio_system_id IS NULL THEN 1 ELSE 0 END,
                    coalesce(radio_system_id, fallback_channel_id),
                    coalesce(source_identity_summary_id, observed_local_id)
                LIMIT ? OFFSET ?
            )
            SELECT paged.radio_system_id, paged.representative_channel_id AS channel_id,
                CASE WHEN paged.radio_system_id IS NULL THEN config.configuration_id END AS configuration_id,
                CASE WHEN paged.radio_system_id IS NULL THEN nullif(trim(config.system_name), '') ELSE (
                    SELECT CASE WHEN count(DISTINCT lower(trim(system_config.system_name))) = 1
                        THEN min(trim(system_config.system_name)) END
                    FROM receiver_channel system_channel
                    JOIN configuration_channel system_config
                      ON system_config.configuration_id = system_channel.configuration_id
                    WHERE system_channel.radio_system_id = paged.radio_system_id
                      AND system_config.system_name IS NOT NULL
                      AND trim(system_config.system_name) <> '') END AS system_name,
                CASE WHEN paged.radio_system_id IS NULL THEN nullif(trim(config.site_name), '') END AS site_name,
                CASE WHEN paged.radio_system_id IS NULL THEN nullif(trim(config.name), '') END AS name,
                CASE WHEN paged.radio_system_id IS NULL THEN alias_list.name END AS alias_list_name,
                CASE WHEN paged.radio_system_id IS NULL THEN config.alias_list_id END AS alias_list_id,
                system.system_key AS radio_system_key,
                coalesce(system.address_domain_code, 0) AS address_domain_code,
                CASE WHEN system.protocol_code IS NOT NULL THEN system.protocol_code
                    WHEN config.decoder_type = 'DMR' THEN 3 WHEN config.decoder_type = 'NXDN' THEN 4
                    WHEN config.decoder_type = 'NBFM' THEN 10 WHEN config.decoder_type = 'AM' THEN 11
                    ELSE 1 END AS protocol_code,
                CASE CASE WHEN system.protocol_code IS NOT NULL THEN system.protocol_code
                    WHEN config.decoder_type = 'DMR' THEN 3 WHEN config.decoder_type = 'NXDN' THEN 4
                    WHEN config.decoder_type = 'NBFM' THEN 10 WHEN config.decoder_type = 'AM' THEN 11
                    ELSE 1 END
                    WHEN 1 THEN 'APCO25'
                    WHEN 2 THEN 'APCO25_PHASE2'
                    WHEN 3 THEN 'DMR'
                    WHEN 4 THEN 'NXDN'
                    WHEN 10 THEN 'NBFM'
                    WHEN 11 THEN 'AM'
                    ELSE 'UNKNOWN'
                END AS protocol,
                CASE WHEN paged.radio_system_id IS NOT NULL THEN 1
                    WHEN config.decoder_type LIKE 'P25%%' THEN 2
                    WHEN config.decoder_type = 'DMR' THEN 3 WHEN config.decoder_type = 'NXDN' THEN 4
                    ELSE 10 END AS channel_kind_code,
                CASE WHEN paged.radio_system_id IS NOT NULL THEN 'TRUNKED'
                    ELSE config.channel_kind END AS channel_kind,
                system.p25_wacn AS wacn,
                system.p25_system_id AS system_id,
                identity.identity_id AS native_id,
                identity.home_wacn, identity.home_system_id,
                %s AS identity_key,
                coalesce(paged.observed_local_id, identity.identity_id) AS observed_local_id,
                paged.observation_count, paged.last_seen_ms,
                totals.retained_observation_count, totals.identified_observation_count,
                totals.total_count
            FROM totals
            LEFT JOIN paged ON 1 = 1
            LEFT JOIN receiver_channel channel ON channel.id = paged.representative_channel_id
            LEFT JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            LEFT JOIN alias_list ON alias_list.id = config.alias_list_id
            LEFT JOIN radio_system system ON system.id = paged.radio_system_id
            LEFT JOIN radio_system_identity_summary identity
              ON identity.id = paged.source_identity_summary_id
             AND identity.radio_system_id = paged.radio_system_id
            ORDER BY paged.observation_count DESC, paged.last_seen_ms DESC,
                CASE WHEN paged.radio_system_id IS NULL THEN 1 ELSE 0 END,
                coalesce(paged.radio_system_id, paged.fallback_channel_id),
                coalesce(paged.source_identity_summary_id, paged.observed_local_id)
            """).formatted(IDENTITY_KEY_SQL.formatted("identity"));
        return queryRows(connection, sql,
            fromMilliseconds, untilMilliseconds, fromMilliseconds, untilMilliseconds,
            HOUR_MILLISECONDS, action.code(), HOUR_MILLISECONDS, action.code(), limit, offset);
    }

    /**
     * Narrows retained-detail work to channel-hours whose compact summary records the selected action. Conventional
     * buckets use a reserved frequency-zero row when the event has no projected frequency, so both branches have the
     * same complete hourly invariant without a broad compatibility scan of retained detail.
     */
    private static String activityActionSlicesSql(String actionColumn)
    {
        return """
            SELECT bucket.channel_id, bucket.bucket_start_ms
            FROM trunked_signaling_activity_bucket AS bucket
                INDEXED BY idx_trunked_signaling_activity_time
            WHERE bucket.bucket_start_ms >= ? AND bucket.bucket_start_ms < ?
              AND bucket.%1$s > 0
            UNION
            SELECT bucket.channel_id, bucket.bucket_start_ms
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

        rows.forEach(row -> row.put("source_radio_id", row.get("observed_local_id")));
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
            String systemKey = textValue(row.get("radio_system_key"));

            if(!systemKey.isBlank())
            {
                WebEntityRef.put(row, WebEntityRef.radioSystem(systemKey));
                WebEntityRef.put(row, "radio_entity_ref", identityReference(row, IDENTITY_KIND_RADIO,
                    textValue(row.get("identity_key"))));
            }
            else
            {
                String configurationId = textValue(row.get("configuration_id"));

                if(!configurationId.isBlank())
                {
                    WebEntityRef conventional = WebEntityRef.channel(configurationId);
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
        String groupIdentityKey = request.text("group_identity_key");
        String radioIdentityKey = request.text("radio_identity_key");
        String systemKey = request.text("radio_system_key");
        String configurationId = request.text("configuration_id");
        boolean hideGrants = request.booleanValue("hide_grants", false);

        if(configurationId != null && systemKey != null)
        {
            throw new StatsApiException(400, "invalid_parameter",
                "configuration_id cannot be combined with radio_system_key", "configuration_id");
        }

        if((groupIdentityKey != null || radioIdentityKey != null) && systemKey == null)
        {
            throw new StatsApiException(400, "invalid_parameter",
                "radio_system_key is required with an identity key", "radio_system_key");
        }

        RadioSystemIdentityKey.Identity groupIdentity = groupIdentityKey != null ? parseIdentityKey(
            groupIdentityKey, Set.of(IDENTITY_KIND_TALKGROUP, IDENTITY_KIND_PATCH_GROUP),
            "group_identity_key") : null;
        RadioSystemIdentityKey.Identity radioIdentity = radioIdentityKey != null ? parseIdentityKey(
            radioIdentityKey, Set.of(IDENTITY_KIND_RADIO), "radio_identity_key") : null;
        int limit = request.limit();

        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured = configurationId != null ?
                mConfiguredEntities.requireChannel(connection, configurationId) : null;

            if(systemKey != null)
            {
                Map<String,Object> radioSystem = requireRadioSystem(connection, systemKey);
                if(groupIdentity != null)
                {
                    requireCompatibleIdentity(radioSystem, groupIdentity, "Group identity not found");
                }
                if(radioIdentity != null)
                {
                    requireCompatibleIdentity(radioSystem, radioIdentity, "Radio not found");
                }
            }

            if(configured != null && configured.channelId() == null)
            {
                return cursorPage(List.of(), limit);
            }

            StringBuilder sql = new StringBuilder(ACTIVITY_SELECT_SQL);
            List<Object> parameters = new ArrayList<>();
            Long beforeTimestamp = null;

            if(beforeId != Long.MAX_VALUE)
            {
                List<Map<String,Object>> cursor = queryRows(connection,
                    "SELECT observed_at_ms FROM receiver_activity_event WHERE id = ?", beforeId);

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

            if(systemKey != null)
            {
                sql.append(" AND activity.resolved_system_key = ?");
                parameters.add(systemKey);
            }
            if(configured != null)
            {
                sql.append(" AND activity.channel_id = ?");
                parameters.add(configured.channelId());
            }
            if(groupIdentityKey != null)
            {
                sql.append("""
                     AND (activity.target_identity_key = ? OR activity.id IN (
                         SELECT member.event_id
                         FROM activity_event_identity_member member
                         JOIN radio_system_identity_summary identity
                           ON identity.id = member.identity_summary_id
                          AND identity.radio_system_id = member.radio_system_id
                         WHERE %s = ?
                     ))
                    """.formatted(IDENTITY_KEY_SQL.formatted("identity")));
                parameters.add(groupIdentityKey);
                parameters.add(groupIdentityKey);
            }
            if(radioIdentityKey != null)
            {
                sql.append(" AND (activity.source_identity_key = ? OR activity.target_identity_key = ?)");
                parameters.add(radioIdentityKey);
                parameters.add(radioIdentityKey);
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

    /** Adds only exact saved-channel and learned-radio-system navigation; retained activity never creates an entity. */
    private static void enrichActivityEntityReferences(List<Map<String,Object>> rows)
    {
        for(Map<String,Object> row: rows)
        {
            String configurationId = textValue(row.get("configuration_id"));
            boolean conventional = number(row.get("channel_kind_code")) != 1;
            WebEntityRef channelReference = null;

            if(!configurationId.isBlank())
            {
                channelReference = WebEntityRef.channel(configurationId);
                WebEntityRef.put(row, channelReference);
            }

            String systemKey = textValue(row.get("radio_system_key"));
            WebEntityRef sourceReference = null;
            WebEntityRef targetReference = null;
            int targetKind = (int)number(row.get("target_kind_code"));

            if(!systemKey.isBlank())
            {
                sourceReference = identityReference(row, IDENTITY_KIND_RADIO,
                    textValue(row.get("source_identity_key")));
                targetReference = identityReference(row, targetKind,
                    textValue(row.get("target_identity_key")));
            }
            else if(conventional && channelReference != null)
            {
                int source = (int)number(row.get("source_radio_id"));
                int target = (int)number(row.get("target_id"));
                sourceReference = source > 0 ? channelReference : null;
                targetReference = target > 0 && (targetKind == IDENTITY_KIND_TALKGROUP ||
                    targetKind == IDENTITY_KIND_PATCH_GROUP || targetKind == IDENTITY_KIND_RADIO) ?
                    channelReference : null;
            }

            WebEntityRef.put(row, "source_entity_ref", sourceReference);
            WebEntityRef.put(row, "target_entity_ref", targetReference);
        }
    }

    Map<String,Object> channelDirectory(StatsRequest request)
    {
        String type = request.text("type");
        String protocol = request.text("protocol");
        int limit = request.limit();
        int offset = request.offset();

        if(type != null && !Set.of("trunked", "conventional").contains(type.toLowerCase(Locale.ROOT)))
        {
            throw new StatsApiException(400, "invalid_parameter",
                "type must be trunked or conventional", "type");
        }
        if(protocol != null && !Set.of("p25", "dmr", "nxdn", "am", "nbfm")
            .contains(protocol.toLowerCase(Locale.ROOT)))
        {
            throw new StatsApiException(400, "invalid_parameter",
                "protocol must be p25, dmr, nxdn, am, or nbfm", "protocol");
        }

        return read(connection -> {
            Map<String,Object> response = page(queryChannelDirectory(connection, request, limit + 1, offset), request);
            StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM (" +
                WebConfiguredEntityRepository.CONFIGURED_CHANNEL_SELECT + ") configured WHERE 1=1");
            List<Object> countParameters = new ArrayList<>();
            addChannelDirectoryFilters(countSql, countParameters, request);
            response.put("total_count", scalarLong(connection, countSql.toString(), countParameters.toArray()));
            return response;
        });
    }

    private static List<Map<String,Object>> queryChannelDirectory(Connection connection, StatsRequest request,
                                                                   int limit, int offset) throws SQLException
    {
        StringBuilder sql = new StringBuilder("SELECT configured.* FROM (" +
            WebConfiguredEntityRepository.CONFIGURED_CHANNEL_SELECT + ") configured WHERE 1=1");
        List<Object> parameters = new ArrayList<>();

        addChannelDirectoryFilters(sql, parameters, request);

        sql.append(" ORDER BY ").append(order(request, CHANNEL_DIRECTORY_SORT_COLUMNS, "name"))
            .append(", configured.configuration_row_id LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        List<Map<String,Object>> presented = new ArrayList<>(rows.size());

        for(Map<String,Object> row: rows)
        {
            presented.add(new LinkedHashMap<>(WebConfiguredEntityRepository.configuredChannel(row).toApiMap()));
        }

        return presented;
    }

    private static void addChannelDirectoryFilters(StringBuilder sql, List<Object> parameters,
                                                    StatsRequest request)
    {
        String type = request.text("type");
        String protocol = request.text("protocol");

        if(type != null)
        {
            sql.append(" AND configured.channel_kind = ?");
            parameters.add(type.toUpperCase(Locale.ROOT));
        }
        if(protocol != null)
        {
            String normalized = protocol.toLowerCase(Locale.ROOT);
            sql.append(" AND ").append(switch(normalized)
            {
                case "p25" -> "upper(configured.decoder) LIKE '%P25%'";
                case "dmr" -> "upper(configured.decoder) = 'DMR'";
                case "nxdn" -> "upper(configured.decoder) = 'NXDN'";
                case "am" -> "upper(configured.decoder) = 'AM'";
                case "nbfm" -> "upper(configured.decoder) = 'NBFM'";
                default -> throw new IllegalStateException("Unsupported protocol filter");
            });
        }
        if(request.search() != null)
        {
            sql.append(" AND lower(coalesce(configured.system_name, '') || ' ' || " +
                "coalesce(configured.site_name, '') || ' ' || " +
                "coalesce(configured.name, '') || ' ' || " +
                "coalesce(configured.alias_list_name, '') || ' ' || " +
                "coalesce(configured.decoder, '') || ' ' || configured.configuration_id) LIKE ?");
            parameters.add(like(request.search()));
        }
    }

    Map<String,Object> channelDetail(String configurationId, StatsRequest request)
    {
        WebConfiguredEntityRepository.ChannelKind kind = channelKind(configurationId);
        Map<String,Object> source = kind == WebConfiguredEntityRepository.ChannelKind.TRUNKED ?
            trunkedChannelDetail(configurationId) : conventionalChannelDetail(configurationId, request);
        Map<String,Object> response = new LinkedHashMap<>(source);

        response.putIfAbsent("summaries", List.of());

        return response;
    }

    Map<String,Object> channelFrequencies(String configurationId, StatsRequest request)
    {
        if(channelKind(configurationId) ==
            WebConfiguredEntityRepository.ChannelKind.TRUNKED)
        {
            return trunkedChannelFrequencies(configurationId, request);
        }

        Map<String,Object> detail = conventionalChannelDetail(configurationId, request);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("rows", detail.getOrDefault("summaries", List.of()));
        response.put("limit", detail.get("limit"));
        response.put("offset", detail.get("offset"));
        response.put("has_more", detail.get("has_more"));
        response.put("next_offset", detail.get("next_offset"));
        return response;
    }

    Map<String,Object> channelGroupIdentities(String configurationId, StatsRequest request)
    {
        WebConfiguredEntityRepository.ChannelKind kind = channelKind(configurationId);
        if(kind == WebConfiguredEntityRepository.ChannelKind.TRUNKED)
        {
            return trunkedChannelGroupIdentities(configurationId, request);
        }

        return conventionalChannelGroupIdentities(configurationId, request);
    }

    Map<String,Object> channelRadios(String configurationId, StatsRequest request)
    {
        return readSnapshot(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured = mConfiguredEntities.requireChannel(
                connection, configurationId);
            List<Map<String,Object>> rows = queryChannelRadios(connection, configured, request,
                request.limit() + 1, request.offset());
            return page(rows, request);
        });
    }

    private List<Map<String,Object>> queryChannelRadios(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, StatsRequest request, int limit, int offset)
        throws SQLException
    {
        if(configured.channelKind() == WebConfiguredEntityRepository.ChannelKind.CONVENTIONAL)
        {
            return queryConventionalRadios(connection, configured, request, limit, offset);
        }

        if(configured.radioSystemKey() == null || configured.radioSystemKey().isBlank())
        {
            request.search();
            order(request, CHANNEL_RADIO_SORT_COLUMNS, "logical_call_count");
            return List.of();
        }

        return queryTrunkedChannelRadios(connection, configured, request, limit, offset);
    }

    /**
     * Returns radios observed by exactly one saved trunked channel. P25 systems can span several saved channels, so
     * this must use the channel-owned site buckets instead of the system-wide radio directory.
     */
    private List<Map<String,Object>> queryTrunkedChannelRadios(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, StatsRequest request, int limit, int offset)
        throws SQLException
    {
        String search = request.search();
        String requestedOrder = order(request, CHANNEL_RADIO_SORT_COLUMNS, "logical_call_count");
        String groupedSql;
        List<Object> parameters = new ArrayList<>();

        if(configured.radioSystemId() != null && configured.channelId() != null &&
            configured.protocolCode() == StatsApiProtocol.P25.databaseCode())
        {
            groupedSql = """
                SELECT summary.id AS identity_summary_id, summary.identity_id AS native_id,
                    %s AS identity_key,
                    (SELECT latest.observed_local_id
                     FROM p25_site_call_identity_bucket latest
                     WHERE latest.radio_system_id = bucket.radio_system_id
                       AND latest.channel_id = bucket.channel_id
                       AND latest.identity_summary_id = bucket.identity_summary_id
                     ORDER BY latest.last_observed_at_ms DESC, latest.bucket_start_ms DESC
                     LIMIT 1) AS observed_local_id,
                    NULL AS frequency_hz, NULL AS timeslot,
                    MIN(bucket.bucket_start_ms) AS first_seen_ms,
                    MAX(bucket.bucket_start_ms) AS last_seen_ms,
                    SUM(bucket.observed_call_count) AS logical_call_count,
                    SUM(bucket.encrypted_observed_call_count) AS encrypted_logical_call_count,
                    SUM(CASE WHEN bucket.identity_role_code = 2
                        THEN bucket.observed_call_count ELSE 0 END) AS source_logical_call_count,
                    SUM(CASE WHEN bucket.identity_role_code = 1
                        THEN bucket.observed_call_count ELSE 0 END) AS target_logical_call_count
                FROM p25_site_call_identity_bucket bucket
                JOIN radio_system_identity_summary summary
                  ON summary.id = bucket.identity_summary_id
                 AND summary.radio_system_id = bucket.radio_system_id
                WHERE bucket.radio_system_id = ? AND bucket.channel_id = ?
                  AND summary.identity_kind_code = 2
                GROUP BY summary.id
                """.formatted(IDENTITY_KEY_SQL.formatted("summary"));
            parameters.add(configured.radioSystemId());
            parameters.add(configured.channelId());
        }
        else if(configured.radioSystemId() != null)
        {
            groupedSql = """
                SELECT summary.id AS identity_summary_id, summary.identity_id AS native_id,
                    summary.identity_id AS observed_local_id,
                    %s AS identity_key,
                    NULL AS frequency_hz, NULL AS timeslot,
                    MIN(bucket.bucket_start_ms) AS first_seen_ms,
                    MAX(bucket.bucket_start_ms) AS last_seen_ms,
                    SUM(bucket.logical_call_count) AS logical_call_count,
                    SUM(bucket.encrypted_logical_call_count) AS encrypted_logical_call_count,
                    SUM(CASE WHEN bucket.identity_role_code = 2
                        THEN bucket.logical_call_count ELSE 0 END) AS source_logical_call_count,
                    SUM(CASE WHEN bucket.identity_role_code = 1
                        THEN bucket.logical_call_count ELSE 0 END) AS target_logical_call_count
                FROM trunked_logical_call_identity_bucket bucket
                JOIN radio_system_identity_summary summary
                  ON summary.id = bucket.identity_summary_id
                 AND summary.radio_system_id = bucket.radio_system_id
                WHERE bucket.radio_system_id = ? AND summary.identity_kind_code = 2
                GROUP BY summary.id
                """.formatted(IDENTITY_KEY_SQL.formatted("summary"));
            parameters.add(configured.radioSystemId());
        }
        else
        {
            return List.of();
        }

        String aliasProjection = channelAliasProjection(configured, "alias_radio", "grouped.observed_local_id");
        StringBuilder sql = new StringBuilder("WITH grouped AS (").append(groupedSql).append("), presented AS (")
            .append("SELECT grouped.*, ? AS configuration_id, ? AS radio_system_key, ? AS protocol_code, ")
            .append("? AS address_domain_code, ? AS alias_list_name, ? AS alias_list_id, ")
            .append(aliasProjection).append(" FROM grouped) SELECT * FROM presented");
        parameters.add(configured.configurationId());
        parameters.add(configured.radioSystemKey());
        parameters.add(configured.protocolCode());
        parameters.add(configured.addressDomainCode());
        parameters.add(configured.aliasListName());
        parameters.add(configured.aliasListId());
        addChannelIdentitySearch(sql, parameters, search, "coalesce(observed_local_id, native_id)");
        sql.append(" ORDER BY ").append(requestedOrder).append(", native_id ASC LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        removeMatchedAliasFields(rows);
        enrichChannelRadioAliases(connection, configured.protocol(), rows);

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, identityReference(row, IDENTITY_KIND_RADIO,
                textValue(row.get("identity_key"))));
        }

        return rows;
    }

    Map<String,Object> channelQuality(String configurationId, StatsRequest request)
    {
        WebConfiguredEntityRepository.ConfiguredChannel configured = read(connection ->
            mConfiguredEntities.requireChannel(connection, configurationId));
        if(configured.channelKind() != WebConfiguredEntityRepository.ChannelKind.TRUNKED)
        {
            request.text("range");
            request.text("points");
            request.text("include_history");
            return Map.of("channels", List.of());
        }

        return qualityHistory(request, configured);
    }

    Map<String,Object> channelBands(String configurationId, StatsRequest request)
    {
        return trunkedChannelFrequencyBands(configurationId, request);
    }

    Map<String,Object> channelNeighbors(String configurationId, StatsRequest request)
    {
        return trunkedChannelNeighbors(configurationId, request);
    }

    Map<String,Object> channelPatches(String configurationId, StatsRequest request)
    {
        return trunkedChannelPatchGroups(configurationId, request);
    }

    private WebConfiguredEntityRepository.ChannelKind channelKind(String configurationId)
    {
        return read(connection -> mConfiguredEntities.requireChannel(connection, configurationId).channelKind());
    }

    Map<String,Object> conventionalChannelDetail(String configurationId, StatsRequest request)
    {
        return read(connection -> {
            Map<String,Object> response = new LinkedHashMap<>();
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireChannel(connection, configurationId);
            Map<String,Object> channel = configured.toApiMap();
            channel.put("capabilities", configured.protocol().conventionalChannelCapabilities());
            response.put("channel", channel);
            List<Map<String,Object>> summaries = configured.channelId() == null ? List.of() : queryRows(connection, """
                SELECT %s FROM conventional_activity_summary summary
                WHERE summary.channel_id = ? ORDER BY summary.frequency_hz, summary.timeslot
                LIMIT ? OFFSET ?
                """.formatted(CONVENTIONAL_ACTIVITY_PUBLIC_PROJECTION_SQL),
                configured.channelId(), request.limit() + 1, request.offset());
            boolean hasMore = summaries.size() > request.limit();

            if(hasMore)
            {
                summaries = new ArrayList<>(summaries.subList(0, request.limit()));
            }

            response.put("summaries", summaries);
            response.put("limit", request.limit());
            response.put("offset", request.offset());
            response.put("has_more", hasMore);
            response.put("next_offset", hasMore ? request.offset() + request.limit() : null);
            return response;
        });
    }

    private static List<Map<String,Object>> queryConventionalFrequencySummaries(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, int limit, int offset) throws SQLException
    {
        if(configured.channelId() == null)
        {
            return List.of();
        }

        return queryRows(connection, """
            SELECT %s FROM conventional_activity_summary summary
            WHERE summary.channel_id = ? ORDER BY summary.frequency_hz, summary.timeslot
            LIMIT ? OFFSET ?
            """.formatted(CONVENTIONAL_ACTIVITY_PUBLIC_PROJECTION_SQL), configured.channelId(), limit, offset);
    }

    /**
     * Bounded conventional digital group summaries for exactly one saved channel.
     */
    Map<String,Object> conventionalChannelGroupIdentities(String configurationId, StatsRequest request)
    {
        if(request.text("range") != null)
        {
            throw new StatsApiException(400, "invalid_parameter",
                "range is available only for trunked channel group identities", "range");
        }

        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireChannel(connection, configurationId);
            List<Map<String,Object>> rows = queryConventionalGroupIdentities(connection, configured, request,
                request.limit() + 1, request.offset());
            return page(rows, request);
        });
    }

    private List<Map<String,Object>> queryConventionalGroupIdentities(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, StatsRequest request, int limit, int offset)
        throws SQLException
    {
        if(configured.channelId() == null || configured.protocol() == StatsApiProtocol.AM ||
            configured.protocol() == StatsApiProtocol.NBFM)
        {
            request.search();
            order(request, CHANNEL_GROUP_IDENTITY_SORT_COLUMNS, "logical_call_count");
            return List.of();
        }

        if(configured.protocol() != StatsApiProtocol.DMR)
        {
            String aliasProjection = channelAliasProjection(configured, "alias_talkgroup",
                "grouped.native_id");
            StringBuilder sql = new StringBuilder("""
                WITH grouped AS (
                    SELECT bucket.channel_id, bucket.identity_id AS native_id,
                        NULL AS observed_local_id,
                        bucket.identity_kind_code AS group_identity_kind_code,
                        NULL AS frequency_hz, NULL AS timeslot, NULL AS last_source_radio_id,
                        min(bucket.bucket_start_ms) AS first_seen_ms,
                        max(bucket.bucket_start_ms) AS last_seen_ms,
                        sum(bucket.call_count) AS logical_call_count,
                        sum(bucket.encrypted_count) AS encrypted_logical_call_count,
                        sum(bucket.recorded_count) AS recorded_logical_call_count,
                        sum(bucket.streamed_count) AS stream_submitted_logical_call_count
                    FROM conventional_call_identity_bucket bucket
                    WHERE bucket.channel_id = ? AND bucket.identity_role_code = ?
                      AND bucket.identity_kind_code IN (1, 3)
                    GROUP BY bucket.channel_id, bucket.identity_kind_code, bucket.identity_id
                ), presented AS (
                    SELECT grouped.*, ? AS configuration_id, ? AS alias_list_name,
                        ? AS alias_list_id,
                """).append(aliasProjection).append(" FROM grouped) SELECT * FROM presented");
            List<Object> parameters = new ArrayList<>();
            parameters.add(configured.channelId());
            parameters.add(IDENTITY_ROLE_DESTINATION);
            parameters.add(configured.configurationId());
            parameters.add(configured.aliasListName());
            parameters.add(configured.aliasListId());
            addChannelIdentitySearch(sql, parameters, request.search(), "native_id");
            sql.append(" ORDER BY ")
                .append(order(request, CHANNEL_GROUP_IDENTITY_SORT_COLUMNS, "logical_call_count"))
                .append(", group_identity_kind_code ASC, native_id ASC LIMIT ? OFFSET ?");
            addLimitOffset(parameters, limit, offset);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            removeMatchedAliasFields(rows);
            enrichChannelGroupAliases(connection, configured.protocol(), rows);

            for(Map<String,Object> row: rows)
            {
                WebEntityRef.put(row, WebEntityRef.channel(configured.configurationId()));
                row.put("entity_tab", "groups");
            }

            return rows;
        }

        StringBuilder sql = new StringBuilder("""
                SELECT summary.channel_id, config.configuration_id,
                    list.name AS alias_list_name, config.alias_list_id,
                    summary.frequency_hz, summary.timeslot,
                    summary.talkgroup_id AS native_id,
                    NULL AS observed_local_id,
                    1 AS group_identity_kind_code,
                    summary.first_seen_ms, summary.last_seen_ms,
                    summary.call_count AS logical_call_count,
                    summary.encrypted_count AS encrypted_logical_call_count,
                    summary.last_source_radio_id
                FROM dmr_conventional_talkgroup_summary summary
                JOIN configuration_channel config ON config.configuration_id = ?
                LEFT JOIN alias_list list ON list.id = config.alias_list_id
                WHERE summary.channel_id = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(configured.configurationId(), configured.channelId()));
        addDmrAliasSearch(sql, parameters, request.search(), "alias_talkgroup", "summary.talkgroup_id");
        sql.append(" ORDER BY ")
            .append(order(request, DMR_CONVENTIONAL_GROUP_IDENTITY_SORT_COLUMNS, "logical_call_count"))
            .append(", summary.last_seen_ms DESC, summary.frequency_hz ASC, summary.timeslot ASC, ")
            .append("summary.talkgroup_id ASC LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        mAliasResolver.enrichDmrTalkgroups(connection, rows, "native_id", "alias_");
        mAliasResolver.enrichDmrRadios(connection, rows, "last_source_radio_id", "last_source_alias_");

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, WebEntityRef.channel(configured.configurationId()));
            row.put("entity_tab", "groups");
        }

        return rows;
    }

    /**
     * Bounded conventional digital radio summaries for exactly one saved channel.
     */
    Map<String,Object> conventionalChannelRadios(String configurationId, StatsRequest request)
    {
        return read(connection -> {
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                mConfiguredEntities.requireChannel(connection, configurationId);
            List<Map<String,Object>> rows = queryConventionalRadios(connection, configured, request,
                request.limit() + 1, request.offset());
            return page(rows, request);
        });
    }

    private List<Map<String,Object>> queryConventionalRadios(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured, StatsRequest request, int limit, int offset)
        throws SQLException
    {
        if(configured.channelId() == null || configured.protocol() == StatsApiProtocol.AM ||
            configured.protocol() == StatsApiProtocol.NBFM)
        {
            request.search();
            order(request, CHANNEL_RADIO_SORT_COLUMNS, "logical_call_count");
            return List.of();
        }

        if(configured.protocol() != StatsApiProtocol.DMR)
        {
            String aliasProjection = channelAliasProjection(configured, "alias_radio", "grouped.native_id");
            StringBuilder sql = new StringBuilder("""
                WITH grouped AS (
                    SELECT bucket.channel_id, bucket.identity_id AS native_id,
                        NULL AS observed_local_id,
                        NULL AS frequency_hz, NULL AS timeslot,
                        min(bucket.bucket_start_ms) AS first_seen_ms,
                        max(bucket.bucket_start_ms) AS last_seen_ms,
                        sum(bucket.call_count) AS logical_call_count,
                        sum(bucket.encrypted_count) AS encrypted_logical_call_count,
                        sum(CASE WHEN bucket.identity_role_code = 2
                            THEN bucket.call_count ELSE 0 END) AS source_logical_call_count,
                        sum(CASE WHEN bucket.identity_role_code = 1
                            THEN bucket.call_count ELSE 0 END) AS target_logical_call_count,
                        sum(bucket.recorded_count) AS recorded_logical_call_count,
                        sum(bucket.streamed_count) AS stream_submitted_logical_call_count
                    FROM conventional_call_identity_bucket bucket
                    WHERE bucket.channel_id = ? AND bucket.identity_kind_code = 2
                    GROUP BY bucket.channel_id, bucket.identity_id
                ), presented AS (
                    SELECT grouped.*, ? AS configuration_id, ? AS protocol_code,
                        ? AS alias_list_name, ? AS alias_list_id,
                """).append(aliasProjection).append(" FROM grouped) SELECT * FROM presented");
            List<Object> parameters = new ArrayList<>();
            parameters.add(configured.channelId());
            parameters.add(configured.configurationId());
            parameters.add(configured.protocolCode());
            parameters.add(configured.aliasListName());
            parameters.add(configured.aliasListId());
            addChannelIdentitySearch(sql, parameters, request.search(), "native_id");
            sql.append(" ORDER BY ").append(order(request, CHANNEL_RADIO_SORT_COLUMNS, "logical_call_count"))
                .append(", native_id ASC LIMIT ? OFFSET ?");
            addLimitOffset(parameters, limit, offset);
            List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
            removeMatchedAliasFields(rows);
            enrichChannelRadioAliases(connection, configured.protocol(), rows);

            for(Map<String,Object> row: rows)
            {
                WebEntityRef.put(row, WebEntityRef.channel(configured.configurationId()));
                row.put("entity_tab", "radios");
            }

            return rows;
        }

        StringBuilder sql = new StringBuilder("""
                SELECT summary.channel_id, config.configuration_id,
                    list.name AS alias_list_name, config.alias_list_id,
                    summary.frequency_hz, summary.timeslot, summary.radio_id AS native_id,
                    NULL AS observed_local_id,
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
                JOIN configuration_channel config ON config.configuration_id = ?
                LEFT JOIN alias_list list ON list.id = config.alias_list_id
                WHERE summary.channel_id = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(configured.configurationId(), configured.channelId()));
        addDmrAliasSearch(sql, parameters, request.search(), "alias_radio", "summary.radio_id");
        sql.append(" ORDER BY ")
            .append(order(request, DMR_CONVENTIONAL_RADIO_SORT_COLUMNS, "logical_call_count"))
            .append(", summary.last_seen_ms DESC, summary.frequency_hz ASC, summary.timeslot ASC, ")
            .append("summary.radio_id ASC LIMIT ? OFFSET ?");
        addLimitOffset(parameters, limit, offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        mAliasResolver.enrichDmrRadios(connection, rows, "native_id", "alias_");
        mAliasResolver.enrichDmrTalkgroups(connection, rows, "last_talkgroup_id", "last_talkgroup_alias_");
        mAliasResolver.enrichDmrRadios(connection, rows, "last_peer_radio_id", "last_peer_alias_");

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, WebEntityRef.channel(configured.configurationId()));
            row.put("entity_tab", "radios");
        }

        return rows;
    }

    private void enrichChannelGroupAliases(Connection connection, StatsApiProtocol protocol,
                                            List<Map<String,Object>> rows) throws SQLException
    {
        rows.forEach(row -> row.put("alias_lookup_id", row.get("observed_local_id") != null ?
            row.get("observed_local_id") : row.get("native_id")));
        if(protocol == StatsApiProtocol.P25)
        {
            mAliasResolver.enrichP25ConventionalTalkgroups(connection, rows, "alias_lookup_id", "alias_");
        }
        else if(protocol == StatsApiProtocol.NXDN)
        {
            mAliasResolver.enrichNxdnTalkgroups(connection, rows, "alias_lookup_id", "alias_");
        }
        else if(protocol == StatsApiProtocol.DMR)
        {
            mAliasResolver.enrichDmrTalkgroups(connection, rows, "alias_lookup_id", "alias_");
        }
        rows.forEach(row -> row.remove("alias_lookup_id"));
    }

    private void enrichChannelRadioAliases(Connection connection, StatsApiProtocol protocol,
                                            List<Map<String,Object>> rows) throws SQLException
    {
        rows.forEach(row -> row.put("alias_lookup_id", row.get("observed_local_id") != null ?
            row.get("observed_local_id") : row.get("native_id")));
        if(protocol == StatsApiProtocol.P25)
        {
            mAliasResolver.enrichP25ConventionalRadios(connection, rows, "alias_lookup_id", "alias_");
        }
        else if(protocol == StatsApiProtocol.NXDN)
        {
            mAliasResolver.enrichNxdnRadios(connection, rows, "alias_lookup_id", "alias_");
        }
        else if(protocol == StatsApiProtocol.DMR)
        {
            mAliasResolver.enrichDmrRadios(connection, rows, "alias_lookup_id", "alias_");
        }
        rows.forEach(row -> row.remove("alias_lookup_id"));
    }

    /** One bounded directory row per learned radio system. */
    static String radioSystemSummarySelect()
    {
        return """
            SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                system.protocol_code, system.address_domain_code AS address_domain_code,
                CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                    WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                system.p25_wacn AS wacn,
                coalesce(system.p25_system_id, (SELECT CASE WHEN count(DISTINCT trunked.system_id) = 1
                    THEN min(trunked.system_id) END
                    FROM receiver_channel channel
                    JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                    WHERE channel.radio_system_id = system.id)) AS system_id,
                (SELECT CASE WHEN count(DISTINCT trunked.network_id) = 1 THEN min(trunked.network_id) END
                    FROM receiver_channel channel
                    JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                    WHERE channel.radio_system_id = system.id) AS network_id,
                coalesce((SELECT CASE WHEN count(DISTINCT trunked.variant_code) = 1
                    THEN min(trunked.variant_code) END
                    FROM receiver_channel channel
                    JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
                    WHERE channel.radio_system_id = system.id), 0) AS variant_code,
                (SELECT CASE WHEN count(DISTINCT lower(trim(config.system_name))) = 1
                    THEN min(trim(config.system_name)) END
                    FROM receiver_channel channel
                    JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
                    WHERE channel.radio_system_id = system.id
                      AND config.system_name IS NOT NULL AND trim(config.system_name) <> '') AS system_name,
                system.first_seen_ms, system.last_seen_ms,
                (SELECT COUNT(*) FROM receiver_channel channel
                    WHERE channel.radio_system_id = system.id) AS channels,
                (SELECT COUNT(*) FROM radio_system_identity_summary identity
                    WHERE identity.radio_system_id = system.id
                      AND identity.identity_kind_code IN (1, 3)) AS group_identities,
                (SELECT COUNT(*) FROM radio_system_identity_summary identity
                    WHERE identity.radio_system_id = system.id
                      AND identity.identity_kind_code = 1) AS talkgroups,
                (SELECT COUNT(*) FROM radio_system_identity_summary identity
                    WHERE identity.radio_system_id = system.id
                      AND identity.identity_kind_code = 3) AS patch_groups,
                (SELECT COUNT(*) FROM radio_system_identity_summary identity
                    WHERE identity.radio_system_id = system.id
                      AND identity.identity_kind_code = 2) AS radios,
                (SELECT COUNT(*) FROM trunked_radio_affiliation affiliation
                    WHERE affiliation.radio_system_id = system.id) AS affiliated_radios,
                (SELECT group_concat(name, ', ') FROM (
                    SELECT DISTINCT coalesce(nullif(trim(config.site_name), ''),
                                             nullif(trim(config.name), '')) AS name
                    FROM receiver_channel channel
                    JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
                    WHERE channel.radio_system_id = system.id
                    ORDER BY lower(name), name
                    LIMIT 8)) AS channel_names,
                (SELECT count(DISTINCT coalesce(nullif(trim(config.site_name), ''),
                                                nullif(trim(config.name), '')))
                 FROM receiver_channel channel
                 JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
                 WHERE channel.radio_system_id = system.id) AS channel_name_count,
                CASE WHEN (SELECT count(DISTINCT coalesce(nullif(trim(config.site_name), ''),
                                                          nullif(trim(config.name), '')))
                           FROM receiver_channel channel
                           JOIN configuration_channel config
                             ON config.configuration_id = channel.configuration_id
                           WHERE channel.radio_system_id = system.id) > 8
                    THEN 1 ELSE 0 END AS channel_names_truncated
            FROM radio_system system
            """;
    }

    private static Map<String,Object> requireRadioSystem(Connection connection, String systemKey) throws SQLException
    {
        return first(queryRows(connection, "WITH radio_systems AS (" + radioSystemSummarySelect() +
            ") SELECT * FROM radio_systems WHERE radio_system_key = ?", systemKey), "Radio system not found");
    }

    private static void attachRadioSystemAliasLists(Connection connection, List<Map<String,Object>> systems)
        throws SQLException
    {
        if(systems.isEmpty())
        {
            return;
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(systems.size(), "?"));
        Object[] ids = systems.stream().map(system -> system.get("radio_system_id")).toArray();
        List<Map<String,Object>> assignments = queryRows(connection, """
            SELECT channel.radio_system_id, list.id, list.name
            FROM receiver_channel channel
            JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            JOIN alias_list list ON list.id = config.alias_list_id
            WHERE channel.radio_system_id IN (%s)
            GROUP BY channel.radio_system_id, list.id, list.name
            ORDER BY channel.radio_system_id, lower(list.name), list.id
            """.formatted(placeholders), ids);
        Map<Long,List<Map<String,Object>>> bySystem = new LinkedHashMap<>();

        for(Map<String,Object> assignment: assignments)
        {
            long radioSystemId = number(assignment.remove("radio_system_id"));
            bySystem.computeIfAbsent(radioSystemId, ignored -> new ArrayList<>()).add(assignment);
        }

        for(Map<String,Object> system: systems)
        {
            List<Map<String,Object>> aliasLists = List.copyOf(bySystem.getOrDefault(
                number(system.get("radio_system_id")), List.of()));
            system.put("alias_lists", aliasLists);
            system.put("alias_list_count", aliasLists.size());
        }
    }

    private static String selectedRadioSystemsCte(int systemCount)
    {
        if(systemCount < 1 || systemCount > StatsRequest.MAX_LIMIT)
        {
            throw new IllegalArgumentException("Radio-system count is invalid");
        }

        String requestedSystems = String.join(", ", java.util.Collections.nCopies(systemCount, "(?)"));
        return "WITH requested_radio_systems(radio_system_id) AS (VALUES " + requestedSystems + ")";
    }

    private static List<Map<String,Object>> queryRadioSystemChannels(Connection connection, long radioSystemId, StatsRequest request)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                system.protocol_code, system.address_domain_code AS address_domain_code,
                CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                    WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                config.channel_kind, config.configuration_id,
                system.p25_wacn AS wacn,
                coalesce(system.p25_system_id, trunked.system_id) AS system_id,
                trunked.network_id,
                nullif(trim(config.system_name), '') AS system_name,
                nullif(trim(config.site_name), '') AS site_name,
                nullif(trim(config.name), '') AS name,
                alias_list.name AS alias_list_name, config.alias_list_id, config.decoder_type AS decoder,
                CASE WHEN system.protocol_code = 1 THEN p25.nac END AS nac,
                CASE WHEN system.protocol_code = 1 THEN p25.rfss END AS rfss,
                CASE WHEN system.protocol_code = 1 THEN p25.site
                    ELSE trunked.site_id END AS site_id,
                CASE WHEN system.protocol_code IN (3, 4) THEN trunked.ran END AS ran,
                CASE WHEN system.protocol_code IN (3, 4) THEN trunked.variant_code END AS variant_code,
                coalesce(p25.primary_frequency_hz, trunked.primary_frequency_hz,
                    config.primary_frequency_hz) AS primary_frequency_hz,
                coalesce(p25.current_control_hz, trunked.current_control_hz) AS current_control_hz,
                coalesce(p25.first_seen_ms, trunked.first_seen_ms, receiver_channel.first_seen_ms) AS first_seen_ms,
                coalesce(p25.last_seen_ms, trunked.last_seen_ms, receiver_channel.last_seen_ms) AS last_seen_ms,
                coalesce(p25.observation_count, trunked.observation_count, 0) AS observation_count,
                CASE WHEN system.protocol_code = 1 THEN
                    (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                        THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                     FROM p25_site_channel_summary channel WHERE channel.channel_id = receiver_channel.id)
                ELSE (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                      WHERE channel.channel_id = receiver_channel.id) END AS learned_channels,
                CASE WHEN system.protocol_code = 1 THEN
                    (SELECT COUNT(*) FROM p25_site_neighbor neighbor
                     WHERE neighbor.channel_id = receiver_channel.id)
                ELSE (SELECT COUNT(*) FROM trunked_site_neighbor_summary neighbor
                      WHERE neighbor.channel_id = receiver_channel.id) END AS neighbors,
                CASE WHEN system.protocol_code = 1 THEN
                    (SELECT COUNT(*) FROM p25_site_frequency_band band
                     WHERE band.channel_id = receiver_channel.id) ELSE 0 END AS bands,
                CASE WHEN system.protocol_code = 1 THEN
                    (SELECT COUNT(*) FROM p25_site_patch_group patch
                     WHERE patch.channel_id = receiver_channel.id) ELSE 0 END AS patches
            FROM receiver_channel
            JOIN configuration_channel config ON config.configuration_id = receiver_channel.configuration_id
            JOIN radio_system system ON system.id = receiver_channel.radio_system_id
            LEFT JOIN alias_list ON alias_list.id = config.alias_list_id
            LEFT JOIN p25_site_snapshot p25
                ON system.protocol_code = 1 AND p25.channel_id = receiver_channel.id
            LEFT JOIN trunked_site_snapshot trunked
                ON system.protocol_code IN (3, 4) AND trunked.channel_id = receiver_channel.id
                    AND trunked.protocol_code = system.protocol_code
            WHERE receiver_channel.radio_system_id = ?
            """);
        List<Object> parameters = new ArrayList<>(List.of(radioSystemId));

        if(request.search() != null)
        {
            sql.append(" AND lower(config.configuration_id || ' ' || coalesce(config.name, '') || ' ' || " +
                "coalesce(config.site_name, '') || ' ' || coalesce(config.system_name, '')) LIKE ?");
            parameters.add(like(request.search()));
        }

        sql.append(" ORDER BY ").append(order(request, RADIO_SYSTEM_CHANNEL_SORT_COLUMNS, "last_seen"))
            .append(", config.configuration_id LIMIT ? OFFSET ?");
        parameters.add(request.limit() + 1);
        parameters.add(request.offset());
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, WebEntityRef.channel(String.valueOf(row.get("configuration_id"))));
        }

        return rows;
    }

    private static void attachRadioSystemChannelPreviews(Connection connection,
                                                           List<Map<String,Object>> systems,
                                                           String search) throws SQLException
    {
        if(systems.isEmpty())
        {
            return;
        }

        List<Object> parameters = new ArrayList<>(systems.size() + (search != null ? 3 : 2));
        systems.forEach(system -> parameters.add(number(system.get("radio_system_id"))));
        StringBuilder sql = new StringBuilder(selectedRadioSystemsCte(systems.size()));

        sql.append("""
            , channel_preview_candidate AS (
                SELECT system.id AS radio_system_id, system.system_key AS radio_system_key,
                    system.protocol_code,
                    CASE system.protocol_code WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR'
                        WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                    config.configuration_id, config.alias_list_id,
                    alias_list.name AS alias_list_name,
                    nullif(trim(config.system_name), '') AS system_name,
                    nullif(trim(config.site_name), '') AS site_name,
                    nullif(trim(config.name), '') AS name,
                    system.p25_wacn AS wacn,
                    coalesce(system.p25_system_id, trunked.system_id) AS system_id,
                    CASE WHEN system.protocol_code = 1 THEN p25.rfss END AS rfss,
                    CASE WHEN system.protocol_code = 1 THEN p25.site
                        ELSE trunked.site_id END AS site_id,
                    CASE WHEN system.protocol_code IN (3, 4) THEN trunked.ran END AS ran,
                    coalesce(p25.current_control_hz, trunked.current_control_hz) AS current_control_hz,
                    coalesce(p25.last_seen_ms, trunked.last_seen_ms, channel.last_seen_ms) AS last_seen_ms,
            """);

        if(search != null)
        {
            sql.append("""
                    CASE WHEN lower(config.configuration_id || ' ' || coalesce(config.name, '') || ' ' ||
                        coalesce(config.site_name, '') || ' ' || coalesce(config.system_name, '') || ' ' ||
                        coalesce(CAST(trunked.network_id AS TEXT), '') || ' ' ||
                        coalesce(CAST(trunked.system_id AS TEXT), '') || ' ' ||
                        coalesce(CAST(trunked.site_id AS TEXT), '') || ' ' ||
                        coalesce(CAST(trunked.ran AS TEXT), '') || ' ' ||
                        coalesce(CAST(p25.rfss AS TEXT), '') || ' ' || coalesce(CAST(p25.site AS TEXT), '')) LIKE ?
                        THEN 1 ELSE 0 END AS channel_search_match
                """);
            parameters.add(like(search));
        }
        else
        {
            sql.append("1 AS channel_search_match\n");
        }

        sql.append("""
                FROM receiver_channel channel
                JOIN requested_radio_systems requested ON requested.radio_system_id = channel.radio_system_id
                JOIN radio_system system ON system.id = channel.radio_system_id
                JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
                LEFT JOIN alias_list ON alias_list.id = config.alias_list_id
                LEFT JOIN p25_site_snapshot p25
                    ON system.protocol_code = 1 AND p25.channel_id = channel.id
                LEFT JOIN trunked_site_snapshot trunked
                    ON system.protocol_code IN (3, 4) AND trunked.channel_id = channel.id
                WHERE system.protocol_code IN (1, 3, 4)
            ),
            ranked_channel_preview AS (
                SELECT candidate.*,
                    row_number() OVER (
                        PARTITION BY candidate.radio_system_id
                        ORDER BY candidate.channel_search_match DESC, candidate.last_seen_ms DESC,
                            candidate.configuration_id
                    ) AS channel_preview_rank
                FROM channel_preview_candidate candidate
            )
            SELECT preview.radio_system_id, preview.radio_system_key, preview.alias_list_id,
                preview.alias_list_name, preview.protocol_code, preview.protocol,
                preview.configuration_id,
                preview.wacn, preview.system_id,
                preview.system_name, preview.site_name, preview.name,
                preview.rfss, preview.site_id, preview.ran, preview.current_control_hz,
                CASE WHEN preview.protocol_code = 1 THEN
                    (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                        THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                     FROM p25_site_channel_summary channel
                     JOIN receiver_channel receiver ON receiver.id = channel.channel_id
                     WHERE receiver.configuration_id = preview.configuration_id)
                ELSE
                    (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                     JOIN receiver_channel receiver ON receiver.id = channel.channel_id
                     WHERE receiver.configuration_id = preview.configuration_id)
                END AS learned_channels,
                preview.last_seen_ms
            FROM ranked_channel_preview preview
            WHERE preview.channel_preview_rank <= ?
            ORDER BY preview.radio_system_id, preview.channel_preview_rank
            LIMIT ?
            """);
        parameters.add(MAXIMUM_RADIO_SYSTEM_DIRECTORY_CHANNEL_PREVIEW);
        parameters.add(Math.multiplyExact(systems.size(), MAXIMUM_RADIO_SYSTEM_DIRECTORY_CHANNEL_PREVIEW));
        List<Map<String,Object>> previewRows = queryRows(connection, sql.toString(), parameters.toArray());
        Map<Long,List<Map<String,Object>>> previewsBySystem = new LinkedHashMap<>();

        for(Map<String,Object> preview: previewRows)
        {
            WebEntityRef.put(preview, WebEntityRef.channel(String.valueOf(preview.get("configuration_id"))));
            long radioSystemId = number(preview.get("radio_system_id"));
            previewsBySystem.computeIfAbsent(radioSystemId, ignored -> new ArrayList<>()).add(preview);
        }

        for(Map<String,Object> system: systems)
        {
            List<Map<String,Object>> preview = List.copyOf(
                previewsBySystem.getOrDefault(number(system.get("radio_system_id")), List.of()));
            system.put("channel_preview", preview);
            system.put("channel_preview_truncated", number(system.get("channels")) > preview.size());
        }
    }

    private static String trunkedChannelSelect()
    {
        return """
            SELECT site.channel_id, channel.configuration_id,
                system.id AS radio_system_id, system.system_key AS radio_system_key,
                system.protocol_code, system.address_domain_code AS address_domain_code,
                'P25' AS protocol,
                config.system_name AS system_name, nullif(trim(config.site_name), '') AS site_name,
                nullif(trim(config.name), '') AS name,
                CASE upper(json_extract(config.config_json, '$.decodeConfiguration.modulation'))
                    WHEN 'CQPSK' THEN 'CQPSK'
                    WHEN 'C4FM' THEN 'C4FM'
                    WHEN 'AUTO' THEN 'C4FM'
                    ELSE NULL END AS p25_decoder_mode,
                alias_list.name AS alias_list_name, config.alias_list_id,
                config.decoder_type AS decoder, system.p25_wacn AS wacn,
                system.p25_system_id AS system_id,
                site.nac, site.rfss, site.site AS site_id,
                site.lra, site.active_rfss_network_connection, site.mfid, site.broadcast_clock_ms,
                site.micro_slots, site.data_service,
                site.data_access, site.wuid_lease_minutes, site.registration_service, site.tdma,
                site.voice_service,
                site.primary_frequency_hz, site.current_control_hz, site.first_seen_ms, site.last_seen_ms,
                site.observation_count,
                coalesce(
                    (SELECT max(channel.callsign) FROM p25_site_channel channel
                     WHERE channel.channel_id = site.channel_id AND channel.downlink_hz = site.current_control_hz),
                    (SELECT max(channel.callsign) FROM p25_site_channel_summary channel
                     WHERE channel.channel_id = site.channel_id AND channel.downlink_hz = site.current_control_hz),
                    (SELECT channel.callsign FROM p25_site_channel channel
                     WHERE channel.channel_id = site.channel_id AND channel.callsign IS NOT NULL
                     ORDER BY channel.confirmed_at_ms DESC LIMIT 1),
                    (SELECT channel.callsign FROM p25_site_channel_summary channel
                     WHERE channel.channel_id = site.channel_id AND channel.callsign IS NOT NULL
                     ORDER BY channel.last_seen_ms DESC LIMIT 1)
                ) AS callsign,
                (SELECT COUNT(DISTINCT CASE WHEN channel.downlink_hz > 0
                    THEN 'f:' || channel.downlink_hz ELSE 'k:' || channel.channel_key END)
                    FROM p25_site_channel_summary channel WHERE channel.channel_id = site.channel_id) AS channels,
                (SELECT COUNT(*) FROM p25_site_neighbor neighbor
                    WHERE neighbor.channel_id = site.channel_id) AS neighbors,
                (SELECT COUNT(*) FROM p25_site_frequency_band band
                    WHERE band.channel_id = site.channel_id) AS bands,
                (SELECT COUNT(*) FROM p25_site_patch_group patch
                    WHERE patch.channel_id = site.channel_id) AS patches
            FROM p25_site_snapshot site
            JOIN receiver_channel channel ON channel.id = site.channel_id
            JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            LEFT JOIN alias_list ON alias_list.id = config.alias_list_id
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            """;
    }

    /**
     * Normalizes the identity fields needed by protocol-neutral control-channel quality views. Quality is owned by
     * the saved receiver channel, and this projection selects the newest retained observation for that channel.
     */
    private static String qualityChannelSelect()
    {
        return """
            SELECT channel.id AS channel_id, config.configuration_id, config.channel_kind,
                p25.nac, p25.rfss,
                coalesce(p25.current_control_hz, trunked.current_control_hz) AS current_control_hz,
                coalesce(p25.last_seen_ms, trunked.last_seen_ms, channel.last_seen_ms) AS site_last_seen_ms,
                system.p25_wacn AS wacn,
                coalesce(system.p25_system_id, trunked.system_id) AS system_id,
                coalesce(system.protocol_code, trunked.protocol_code) AS protocol_code,
                CASE coalesce(system.protocol_code, trunked.protocol_code)
                    WHEN 1 THEN 'P25' WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                config.system_name AS system_name,
                nullif(trim(config.site_name), '') AS site_name,
                nullif(trim(config.name), '') AS name, trunked.network_id,
                CASE WHEN system.protocol_code = 1 THEN p25.site ELSE trunked.site_id END AS site_id,
                trunked.ran, trunked.variant_code,
                system.address_domain_code, trunked.location_category_code,
                system.id AS radio_system_id, system.system_key AS radio_system_key
            FROM configuration_channel config
            LEFT JOIN receiver_channel channel ON channel.configuration_id = config.configuration_id
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            LEFT JOIN p25_site_snapshot p25 ON p25.channel_id = channel.id
            LEFT JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
            """;
    }

    private static String trunkedSiteSelect()
    {
        return """
            SELECT site.channel_id, channel.configuration_id,
                system.id AS radio_system_id, system.system_key AS radio_system_key,
                site.protocol_code,
                CASE site.protocol_code WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' ELSE 'Unknown' END AS protocol,
                site.variant_code, system.address_domain_code, site.location_category_code,
                config.system_name AS system_name, nullif(trim(config.site_name), '') AS site_name,
                nullif(trim(config.name), '') AS name,
                alias_list.name AS alias_list_name, config.alias_list_id,
                config.decoder_type AS decoder, site.network_id, site.system_id, site.site_id,
                site.ran,
                site.model_code, site.brand_code, site.mode_code, site.channel_type_code,
                site.color_code_ts1, site.color_code_ts2, site.current_repeater, site.service_flags,
                site.failure_code, site.primary_frequency_hz, site.current_control_hz,
                site.first_seen_ms, site.last_seen_ms, site.observation_count,
                (SELECT COUNT(*) FROM trunked_site_channel_summary channel
                    WHERE channel.channel_id = site.channel_id) AS channels,
                (SELECT COUNT(*) FROM trunked_site_neighbor_summary neighbor
                    WHERE neighbor.channel_id = site.channel_id) AS neighbors,
                0 AS bands, 0 AS patches
            FROM trunked_site_snapshot site
            JOIN receiver_channel channel ON channel.id = site.channel_id
            JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            LEFT JOIN alias_list ON alias_list.id = config.alias_list_id
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            """;
    }

    /** Returns the learned radio-system identity for one saved channel, if one has been observed. */
    private static Map<String,Object> configuredTrunkedChannelRadioSystem(Connection connection,
        WebConfiguredEntityRepository.ConfiguredChannel configured) throws SQLException
    {
        if(configured.channelId() == null)
        {
            return null;
        }

        List<Map<String,Object>> rows = queryRows(connection, """
            SELECT channel.id AS channel_id, config.alias_list_id, alias_list.name AS alias_list_name,
                system.id AS radio_system_id, system.system_key AS radio_system_key,
                system.protocol_code, system.address_domain_code AS address_domain_code,
                system.p25_wacn AS wacn, system.p25_system_id AS system_id,
                p25.rfss, p25.site AS site_id
            FROM receiver_channel channel
            JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            LEFT JOIN alias_list ON alias_list.id = config.alias_list_id
            JOIN radio_system system ON system.id = channel.radio_system_id
            LEFT JOIN p25_site_snapshot p25 ON p25.channel_id = channel.id
            WHERE channel.id = ? AND system.protocol_code = ?
            """, configured.channelId(), configured.protocolCode());

        if(rows.size() > 1)
        {
            throw new StatsApiException(409, "channel_radio_system_conflict",
                "Configured channel has more than one learned radio system");
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
            FROM statistics_status
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

        mAliasResolver.enrichCanonicalSystemTalkgroups(connection, p25Talkgroups, "identity_summary_id",
            "native_id", "alias_");
        mAliasResolver.enrichCanonicalSystemRadios(connection, p25Radios, "identity_summary_id",
            "native_id", "alias_");
        mAliasResolver.enrichP25ConventionalTalkgroups(connection, p25ConventionalTalkgroups,
            "native_id", "alias_");
        mAliasResolver.enrichP25ConventionalRadios(connection, p25ConventionalRadios,
            "native_id", "alias_");
        mAliasResolver.enrichDmrTalkgroups(connection, dmrTalkgroups, "native_id", "alias_");
        mAliasResolver.enrichDmrRadios(connection, dmrRadios, "native_id", "alias_");
        mAliasResolver.enrichNxdnTalkgroups(connection, nxdnTalkgroups, "native_id", "alias_");
        mAliasResolver.enrichNxdnRadios(connection, nxdnRadios, "native_id", "alias_");

        for(Map<String,Object> row: rows)
        {
            int protocolCode = (int)number(row.get("protocol_code"));
            int identityKind = (int)number(row.get("identity_kind_code"));
            boolean trunked = "TRUNKED".equals(row.get("channel_kind"));
            boolean conventionalDigital = !trunked && (protocolCode == 1 || protocolCode == 3 || protocolCode == 4);
            boolean hasRadioSystem = trunked && row.get("radio_system_key") instanceof String token && !token.isBlank();

            row.put("identity_role_code", identityRole);
            row.put("identity_role", identityRole == IDENTITY_ROLE_DESTINATION ? "Destination" : "Source");

            if(hasRadioSystem)
            {
                WebEntityRef.put(row, identityReference(row, identityKind,
                    textValue(row.get("identity_key"))));
            }
            else if(conventionalDigital && (identityKind == IDENTITY_KIND_TALKGROUP ||
                identityKind == IDENTITY_KIND_RADIO) &&
                row.get("configuration_id") instanceof String configurationId && !configurationId.isBlank())
            {
                WebEntityRef.put(row, WebEntityRef.channel(configurationId));
                row.put("entity_tab", identityKind == IDENTITY_KIND_RADIO ? "radios" : "groups");
            }
        }

        return rows;
    }

    /**
     * Returns the newest saved channels across both topologies, including channels with no retained observations.
     */
    private static List<Map<String,Object>> recentChannels(Connection connection) throws SQLException
    {
        List<Map<String,Object>> rows = queryRows(connection, """
            SELECT config.configuration_id, channel.id AS channel_id, config.channel_kind,
                config.system_name AS system_name, config.site_name AS site_name, config.name AS name,
                alias_list.name AS alias_list_name, config.alias_list_id,
                config.decoder_type AS decoder,
                CASE config.decoder_type WHEN 'DMR' THEN 3 WHEN 'NXDN' THEN 4
                    WHEN 'NBFM' THEN 10 WHEN 'AM' THEN 11 ELSE 1 END AS protocol_code,
                CASE config.decoder_type WHEN 'DMR' THEN 'DMR' WHEN 'NXDN' THEN 'NXDN'
                    WHEN 'NBFM' THEN 'NBFM' WHEN 'AM' THEN 'AM' ELSE 'P25' END AS protocol,
                system.id AS radio_system_id, system.system_key AS radio_system_key,
                system.address_domain_code AS address_domain_code,
                system.p25_wacn AS wacn, coalesce(system.p25_system_id, trunked.system_id) AS system_id,
                trunked.network_id, p25.nac, p25.rfss,
                CASE WHEN system.protocol_code = 1 THEN p25.site ELSE trunked.site_id END AS site_id,
                trunked.ran, trunked.variant_code,
                coalesce(p25.primary_frequency_hz, trunked.primary_frequency_hz,
                    config.primary_frequency_hz) AS primary_frequency_hz,
                coalesce(p25.current_control_hz, trunked.current_control_hz) AS current_control_hz,
                coalesce(p25.first_seen_ms, trunked.first_seen_ms, channel.first_seen_ms) AS first_seen_ms,
                coalesce(p25.last_seen_ms, trunked.last_seen_ms, channel.last_seen_ms) AS last_seen_ms,
                coalesce(p25.observation_count, trunked.observation_count, 0) AS observation_count,
                CASE WHEN system.protocol_code = 1 THEN
                    (SELECT COUNT(DISTINCT CASE WHEN learned.downlink_hz > 0
                        THEN 'f:' || learned.downlink_hz ELSE 'k:' || learned.channel_key END)
                     FROM p25_site_channel_summary learned WHERE learned.channel_id = channel.id)
                ELSE (SELECT COUNT(*) FROM trunked_site_channel_summary learned
                    WHERE learned.channel_id = channel.id) END AS learned_channels,
                CASE WHEN system.protocol_code = 1 THEN
                    (SELECT COUNT(*) FROM p25_site_neighbor learned WHERE learned.channel_id = channel.id)
                ELSE (SELECT COUNT(*) FROM trunked_site_neighbor_summary learned
                    WHERE learned.channel_id = channel.id) END AS neighbors
            FROM configuration_channel config
            LEFT JOIN alias_list ON alias_list.id = config.alias_list_id
            LEFT JOIN receiver_channel channel ON channel.configuration_id = config.configuration_id
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            LEFT JOIN p25_site_snapshot p25 ON p25.channel_id = channel.id
            LEFT JOIN trunked_site_snapshot trunked ON trunked.channel_id = channel.id
            ORDER BY coalesce(p25.last_seen_ms, trunked.last_seen_ms, channel.last_seen_ms, 0) DESC,
                config.sort_order, config.id
            LIMIT 20
            """);

        for(Map<String,Object> row: rows)
        {
            WebEntityRef.put(row, WebEntityRef.channel(String.valueOf(row.get("configuration_id"))));
        }

        return rows;
    }

    /**
     * Ranks all call-producing saved receiver channels using the existing compact hourly buckets. The query is bounded to
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
                WebEntityRef.put(row, WebEntityRef.channel(configurationId));
            }
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("from_ms", firstHour);
        result.put("to_ms", System.currentTimeMillis());
        result.put("rows", rows);
        result.put("limit", DASHBOARD_SOURCE_LIMIT);
        result.put("has_more", hasMore);
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
        result.put("metric_coverage", metricCoverage);
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
            """, ReceiverActivitySchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY);
    }

    private static long conventionalCallOutputMetricsStartedAt(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COALESCE((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?), 0)
            """, ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY);
    }

    private static long radioSystemMetricStartedAt(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COALESCE((SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key = ?), 0)
            """, ReceiverActivitySchema.RADIO_SYSTEM_METRICS_STARTED_AT_KEY);
    }

    private static RadioSystemIdentityKey.Identity parseIdentityKey(String value, Set<Integer> allowedKinds,
                                                                     String field)
    {
        try
        {
            RadioSystemIdentityKey.Identity identity = RadioSystemIdentityKey.parse(value);
            if(allowedKinds == null || !allowedKinds.contains(identity.kindCode()))
            {
                throw new IllegalArgumentException("Identity kind is not valid for this resource");
            }
            return identity;
        }
        catch(IllegalArgumentException exception)
        {
            throw new StatsApiException(400, "invalid_path", "identity_key is not valid", field);
        }
    }

    private static void requireCompatibleIdentity(Map<String,Object> radioSystem,
                                                   RadioSystemIdentityKey.Identity identity,
                                                   String notFoundMessage)
    {
        int protocolCode = (int)number(radioSystem.get("protocol_code"));
        if(!validIdentity(radioSystem, identity.kindCode(), identity.identityId()) ||
            protocolCode == 1 != identity.hasHome())
        {
            throw new StatsApiException(404, notFoundMessage);
        }
    }

    private static long requireIdentitySummaryId(Connection connection, long radioSystemId,
                                                  RadioSystemIdentityKey.Identity identity,
                                                  String notFoundMessage) throws SQLException
    {
        List<Map<String,Object>> rows = queryRows(connection, """
            SELECT id
            FROM radio_system_identity_summary
            WHERE radio_system_id = ? AND identity_kind_code = ?
              AND home_wacn = ? AND home_system_id = ? AND identity_id = ?
            """, radioSystemId, identity.kindCode(), identity.homeWacn(), identity.homeSystemId(),
            identity.identityId());
        if(rows.isEmpty())
        {
            throw new StatsApiException(404, notFoundMessage);
        }
        return number(rows.getFirst().get("id"));
    }

    private static boolean validIdentity(Map<String,Object> radioSystem, int identityKind, int identifier)
    {
        int protocolCode = (int)number(radioSystem.get("protocol_code"));
        Protocol protocol = switch(protocolCode)
        {
            case 1 -> Protocol.APCO25;
            case 3 -> Protocol.DMR;
            case 4 -> Protocol.NXDN;
            default -> Protocol.UNKNOWN;
        };
        TrunkedIdentityDomain domain = switch((int)number(radioSystem.get("address_domain_code")))
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

    private static WebEntityRef identityReference(Map<String,Object> radioSystem, int identityKind,
                                                   String identityKey)
    {
        RadioSystemIdentityKey.Identity identity;

        try
        {
            identity = RadioSystemIdentityKey.parse(identityKey);
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }

        int protocolCode = (int)number(radioSystem.get("protocol_code"));
        if(identity.kindCode() != identityKind || !validIdentity(radioSystem, identityKind, identity.identityId()) ||
            protocolCode == 1 != identity.hasHome())
        {
            return null;
        }

        String systemKey = textValue(radioSystem.get("radio_system_key"));

        if(systemKey.isBlank())
        {
            return null;
        }

        return switch(identityKind)
        {
            case IDENTITY_KIND_TALKGROUP -> WebEntityRef.talkgroup(systemKey, identityKey);
            case IDENTITY_KIND_PATCH_GROUP -> WebEntityRef.patchGroup(systemKey, identityKey);
            case IDENTITY_KIND_RADIO -> WebEntityRef.radio(systemKey, identityKey);
            default -> null;
        };
    }

    private static boolean booleanParameter(StatsRequest request, String name, boolean defaultValue)
    {
        return request.booleanValue(name, defaultValue);
    }

    private static Map<String,Boolean> radioSystemCapabilities(int protocolCode)
    {
        return StatsApiProtocol.fromCode(protocolCode).radioSystemCapabilities();
    }

    private static Map<String,Boolean> groupIdentityCapabilities(int protocolCode, int identityKind)
    {
        return StatsApiProtocol.fromCode(protocolCode)
            .groupIdentityCapabilities(identityKind == IDENTITY_KIND_PATCH_GROUP);
    }

    private void enrichRadioSystemGroupIdentities(Connection connection, List<Map<String,Object>> rows,
                                              String identifierColumn,
                                       String prefix) throws SQLException
    {
        enrichRadioSystemGroupIdentities(connection, rows, "identity_summary_id", identifierColumn, prefix);
    }

    private void enrichRadioSystemGroupIdentities(Connection connection, List<Map<String,Object>> rows,
                                                   String summaryIdColumn, String identifierColumn,
                                                   String prefix) throws SQLException
    {
        List<Map<String,Object>> p25 = protocolRows(rows, 1);
        List<Map<String,Object>> dmr = protocolRows(rows, 3);
        List<Map<String,Object>> nxdn = protocolRows(rows, 4);
        mAliasResolver.enrichCanonicalSystemTalkgroups(connection, p25, summaryIdColumn,
            identifierColumn, prefix);
        mAliasResolver.enrichCanonicalSystemTalkgroups(connection, dmr, summaryIdColumn,
            identifierColumn, prefix);
        mAliasResolver.enrichCanonicalSystemTalkgroups(connection, nxdn, summaryIdColumn,
            identifierColumn, prefix);
    }

    private void enrichRadioSystemRadios(Connection connection, List<Map<String,Object>> rows,
                                         String identifierColumn,
                                   String prefix) throws SQLException
    {
        enrichRadioSystemRadios(connection, rows, "identity_summary_id", identifierColumn, prefix);
    }

    private void enrichRadioSystemRadios(Connection connection, List<Map<String,Object>> rows,
                                         String summaryIdColumn, String identifierColumn,
                                         String prefix) throws SQLException
    {
        List<Map<String,Object>> p25 = protocolRows(rows, 1);
        List<Map<String,Object>> dmr = protocolRows(rows, 3);
        List<Map<String,Object>> nxdn = protocolRows(rows, 4);
        mAliasResolver.enrichCanonicalSystemRadios(connection, p25, summaryIdColumn,
            identifierColumn, prefix);
        mAliasResolver.enrichCanonicalSystemRadios(connection, dmr, summaryIdColumn,
            identifierColumn, prefix);
        mAliasResolver.enrichCanonicalSystemRadios(connection, nxdn, summaryIdColumn,
            identifierColumn, prefix);
    }

    private static List<Map<String,Object>> protocolRows(List<Map<String,Object>> rows, int protocolCode)
    {
        return rows.stream().filter(row -> number(row.get("protocol_code")) == protocolCode).toList();
    }

    private static List<Map<String,Object>> systemActionCounts(Connection connection, long radioSystemId)
        throws SQLException
    {
        String sums = GROUP_IDENTITY_SIGNALING_FIELDS.stream()
            .map(field -> "SUM(bucket." + field + ") AS " + field)
            .collect(java.util.stream.Collectors.joining(", "));
        List<Map<String,Object>> totals = queryRows(connection, """
            SELECT %s
            FROM trunked_signaling_activity_bucket bucket
            JOIN receiver_channel channel ON channel.id = bucket.channel_id
            WHERE channel.radio_system_id = ?
            """.formatted(sums), radioSystemId);

        if(totals.isEmpty())
        {
            return List.of();
        }

        Map<String,Object> row = totals.getFirst();
        List<Map<String,Object>> result = new ArrayList<>();

        for(String field: GROUP_IDENTITY_SIGNALING_FIELDS)
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
               AND identifier.alias_list_id = config.alias_list_id
               AND ((identifier.ranged <> 0 AND %s BETWEEN identifier.min_value AND identifier.max_value)
                 OR (identifier.ranged = 0 AND identifier.value = %s))
             ORDER BY %s
             LIMIT 1)
            """.formatted(aliasColumn, identifierTable, identifierColumn, identifierColumn,
            aliasWinnerOrder()).strip();
    }

    /**
     * Projects the winning Alias from exactly the saved channel's Alias List so search and sort happen before
     * pagination. The bounded page is still enriched through {@link StatsAliasResolver} for its public Alias fields.
     */
    private static String channelAliasProjection(WebConfiguredEntityRepository.ConfiguredChannel configured,
                                                  String identifierTable, String identifierColumn)
    {
        return channelAliasValueExpression(configured, identifierTable, identifierColumn, "name") +
            " AS matched_alias_name, " +
            channelAliasValueExpression(configured, identifierTable, identifierColumn, "description") +
            " AS matched_alias_description, " +
            channelAliasValueExpression(configured, identifierTable, identifierColumn, "group_name") +
            " AS matched_alias_group";
    }

    private static String channelAliasValueExpression(WebConfiguredEntityRepository.ConfiguredChannel configured,
                                                       String identifierTable, String identifierColumn,
                                                       String aliasColumn)
    {
        if(!"alias_talkgroup".equals(identifierTable) && !"alias_radio".equals(identifierTable) ||
            !identifierColumn.matches("grouped\\.(?:native_id|observed_local_id)") ||
            !Set.of("name", "description", "group_name").contains(aliasColumn))
        {
            throw new IllegalArgumentException("Unsupported saved-channel Alias expression");
        }

        if(configured.aliasListId() == null)
        {
            return "NULL";
        }

        String protocols = switch(configured.protocol())
        {
            case P25 -> "'APCO25','APCO25_PHASE2'";
            case DMR -> "'DMR'";
            case NXDN -> "'NXDN'";
            default -> throw new IllegalArgumentException("Analog channels do not have identity directories");
        };
        return """
            (SELECT alias.%s
             FROM %s identifier
             JOIN alias ON alias.id = identifier.alias_id
             WHERE identifier.protocol IN (%s)
               AND identifier.alias_list_id = %d
               AND ((identifier.ranged <> 0 AND %s BETWEEN identifier.min_value AND identifier.max_value)
                 OR (identifier.ranged = 0 AND identifier.value = %s))
             ORDER BY %s
             LIMIT 1)
            """.formatted(aliasColumn, identifierTable, protocols, configured.aliasListId(), identifierColumn,
            identifierColumn, aliasWinnerOrder()).strip();
    }

    private static void addChannelIdentitySearch(StringBuilder sql, List<Object> parameters, String search,
                                                  String identifierColumn)
    {
        if(search == null)
        {
            return;
        }

        if(!Set.of("native_id", "coalesce(observed_local_id, native_id)").contains(identifierColumn))
        {
            throw new IllegalArgumentException("Unsupported saved-channel identity search");
        }

        sql.append(" WHERE (CAST(").append(identifierColumn).append(" AS TEXT) LIKE ?")
            .append(" OR lower(coalesce(matched_alias_name, '')) LIKE ?")
            .append(" OR lower(coalesce(matched_alias_description, '')) LIKE ?")
            .append(" OR lower(coalesce(matched_alias_group, '')) LIKE ?)");
        String like = like(search);
        parameters.add(like);
        parameters.add(like);
        parameters.add(like);
        parameters.add(like);
    }

    private static void removeMatchedAliasFields(List<Map<String,Object>> rows)
    {
        for(Map<String,Object> row: rows)
        {
            row.remove("matched_alias_name");
            row.remove("matched_alias_description");
            row.remove("matched_alias_group");
        }
    }

    /**
     * Sorts by an alias only when all channel-owned Alias Lists that match this identity agree on one value.
     */
    private static String radioSystemAliasSortExpression(String identifierTable, String identifierColumn,
                                                   String aliasColumn)
    {
        if(!"alias_talkgroup".equals(identifierTable) && !"alias_radio".equals(identifierTable) ||
            !"summary.identity_id".equals(identifierColumn) ||
            !"name".equals(aliasColumn) && !"group_name".equals(aliasColumn))
        {
            throw new IllegalArgumentException("Unsupported radio-system alias sort expression");
        }

        String protocol =
            "CASE system.protocol_code WHEN 1 THEN 'APCO25' WHEN 3 THEN 'DMR' WHEN 4 THEN 'NXDN' END";
        return """
            (SELECT CASE WHEN count(DISTINCT lower(alias.%s)) = 1 THEN min(lower(alias.%s)) END
             FROM %s identifier
             JOIN alias ON alias.id = identifier.alias_id
             WHERE (identifier.protocol = %s OR
                    (system.protocol_code = 1 AND identifier.protocol = 'APCO25_PHASE2'))
               AND ((identifier.ranged <> 0 AND %s BETWEEN identifier.min_value AND identifier.max_value)
                 OR (identifier.ranged = 0 AND identifier.value = %s))
               AND EXISTS (
                   SELECT 1
                   FROM receiver_channel channel
                   JOIN configuration_channel config
                     ON config.configuration_id = channel.configuration_id
                   WHERE channel.radio_system_id = system.id
                     AND config.alias_list_id = identifier.alias_list_id))
            """.formatted(aliasColumn, aliasColumn, identifierTable, protocol, identifierColumn,
            identifierColumn).strip();
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
                .append("(system.protocol_code = 4 AND system.address_domain_code = 2 ")
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
                   OR (system.protocol_code = 4 AND system.address_domain_code = 2
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
                     AND identifier.alias_list_id = config.alias_list_id
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
        page.put("has_more", hasMore);
        page.put("next_offset", hasMore ? offset + limit : null);
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
        page.put("has_more", hasMore);
        page.put("next_before_id", nextBeforeId);
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
