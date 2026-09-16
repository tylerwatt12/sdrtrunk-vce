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

package io.github.dsheirer.database;

import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.database.upgrade.Format5WebStateValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Expected global SDRTrunk SQLite schema.
 */
public final class SdrTrunkDatabaseSchema
{
    public static final int MAXIMUM_RECEIVER_HEALTH_INCIDENTS = 200;
    private static final String DATABASE_METADATA_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS database_metadata (
            key TEXT NOT NULL PRIMARY KEY CHECK(typeof(key) = 'text' AND length(trim(key)) > 0),
            value TEXT NOT NULL CHECK(typeof(value) = 'text'),
            updated_at_ms INTEGER NOT NULL
                CHECK(typeof(updated_at_ms) = 'integer' AND updated_at_ms > 0)
        )
        """;
    private static final String RECEIVER_HEALTH_INCIDENT_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS receiver_health_incident (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            process_started_at_ms INTEGER NOT NULL CHECK(
                typeof(process_started_at_ms) = 'integer' AND process_started_at_ms > 0
            ),
            occurrence_id INTEGER NOT NULL CHECK(typeof(occurrence_id) = 'integer' AND occurrence_id > 0),
            code TEXT NOT NULL CHECK(
                typeof(code) = 'text' AND length(trim(code)) > 0
                AND length(CAST(code AS BLOB)) <= 80
            ),
            severity TEXT NOT NULL CHECK(
                typeof(severity) = 'text' AND severity IN ('warning', 'critical')
            ),
            title TEXT NOT NULL CHECK(
                typeof(title) = 'text' AND length(trim(title)) > 0
                AND length(CAST(title AS BLOB)) <= 256
            ),
            scope TEXT NOT NULL CHECK(
                typeof(scope) = 'text' AND length(trim(scope)) > 0
                AND length(CAST(scope AS BLOB)) <= 512
            ),
            opened_at_ms INTEGER NOT NULL CHECK(typeof(opened_at_ms) = 'integer' AND opened_at_ms > 0),
            last_seen_at_ms INTEGER NOT NULL CHECK(
                typeof(last_seen_at_ms) = 'integer' AND last_seen_at_ms >= opened_at_ms
            ),
            resolved_at_ms INTEGER NOT NULL DEFAULT 0 CHECK(
                typeof(resolved_at_ms) = 'integer'
                AND (resolved_at_ms = 0 OR resolved_at_ms >= last_seen_at_ms)
            ),
            count INTEGER NOT NULL CHECK(typeof(count) = 'integer' AND count >= 0),
            observed TEXT NOT NULL DEFAULT '' CHECK(
                typeof(observed) = 'text' AND length(CAST(observed AS BLOB)) <= 2048
            ),
            likely_cause TEXT NOT NULL DEFAULT '' CHECK(
                typeof(likely_cause) = 'text' AND length(CAST(likely_cause AS BLOB)) <= 2048
            ),
            impact TEXT NOT NULL DEFAULT '' CHECK(
                typeof(impact) = 'text' AND length(CAST(impact AS BLOB)) <= 2048
            ),
            check_next TEXT NOT NULL DEFAULT '' CHECK(
                typeof(check_next) = 'text' AND length(CAST(check_next AS BLOB)) <= 2048
            ),
            UNIQUE(process_started_at_ms, occurrence_id)
        )
        """;
    private static final String ALIAS_LIST_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_list (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            name TEXT NOT NULL COLLATE NOCASE CHECK(
                typeof(name) = 'text' AND length(trim(name)) BETWEEN 1 AND 25
            ),
            family TEXT NOT NULL CHECK(typeof(family) = 'text' AND family IN (
                'P25', 'DMR', 'NXDN', 'NBFM'
            )),
            unmatched_talkgroup_record_enabled INTEGER NOT NULL DEFAULT 0 CHECK(
                typeof(unmatched_talkgroup_record_enabled) = 'integer'
                AND unmatched_talkgroup_record_enabled IN (0, 1)
            ),
            new_alias_record_enabled INTEGER NOT NULL DEFAULT 0 CHECK(
                typeof(new_alias_record_enabled) = 'integer'
                AND new_alias_record_enabled IN (0, 1)
            ),
            UNIQUE(name)
        )
        """;
    private static final String FORMAT_20_ALIAS_LIST_TABLE_SQL = ALIAS_LIST_TABLE_SQL.replace("""
            new_alias_record_enabled INTEGER NOT NULL DEFAULT 0 CHECK(
                typeof(new_alias_record_enabled) = 'integer'
                AND new_alias_record_enabled IN (0, 1)
            ),
        """, "");
    private static final String ALIAS_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE RESTRICT
                CHECK(typeof(alias_list_id) = 'integer' AND alias_list_id > 0),
            name TEXT NOT NULL CHECK(typeof(name) = 'text' AND length(trim(name)) > 0),
            description TEXT CHECK(description IS NULL OR typeof(description) = 'text'),
            group_name TEXT CHECK(group_name IS NULL OR typeof(group_name) = 'text'),
            color INTEGER NOT NULL DEFAULT 0 CHECK(
                typeof(color) = 'integer' AND color BETWEEN -2147483648 AND 2147483647
            ),
            icon_name TEXT CHECK(icon_name IS NULL OR typeof(icon_name) = 'text'),
            stream_as_talkgroup INTEGER CHECK(stream_as_talkgroup IS NULL OR
                (typeof(stream_as_talkgroup) = 'integer' AND stream_as_talkgroup BETWEEN 1 AND 16777215)),
            record_enabled INTEGER NOT NULL DEFAULT 0
                CHECK(typeof(record_enabled) = 'integer' AND record_enabled IN (0, 1)),
            matcher_type TEXT NOT NULL CHECK(typeof(matcher_type) = 'text' AND matcher_type IN (
                'TALKGROUP',
                'TALKGROUP_RANGE',
                'RADIO_ID',
                'RADIO_ID_RANGE',
                'STATUS',
                'UNIT_STATUS',
                'TONES',
                'DCS',
                'ESN'
            )),
            protocol TEXT CHECK(protocol IS NULL OR
                (typeof(protocol) = 'text' AND protocol IN (
                    'AM', 'APCO25', 'APCO25_PHASE2', 'DMR', 'FLEETSYNC', 'MDC1200', 'NBFM', 'NXDN'
                ))),
            value INTEGER CHECK(value IS NULL OR
                (typeof(value) = 'integer' AND value BETWEEN 0 AND 16777215)),
            min_value INTEGER CHECK(min_value IS NULL OR
                (typeof(min_value) = 'integer' AND min_value BETWEEN 0 AND 16777215)),
            max_value INTEGER CHECK(max_value IS NULL OR
                (typeof(max_value) = 'integer' AND max_value BETWEEN 0 AND 16777215)),
            text_value TEXT CHECK(text_value IS NULL OR
                (typeof(text_value) = 'text' AND length(trim(text_value)) > 0)),
            numeric_value INTEGER CHECK(numeric_value IS NULL OR
                (typeof(numeric_value) = 'integer' AND numeric_value BETWEEN 0 AND 255)),
            tone_sequence TEXT CHECK(tone_sequence IS NULL OR typeof(tone_sequence) = 'text'),
            CHECK(
                (matcher_type IN ('TALKGROUP', 'RADIO_ID')
                    AND protocol IS NOT NULL AND value IS NOT NULL
                    AND min_value IS NULL AND max_value IS NULL AND text_value IS NULL
                    AND numeric_value IS NULL AND tone_sequence IS NULL)
                OR
                (matcher_type IN ('TALKGROUP_RANGE', 'RADIO_ID_RANGE')
                    AND protocol IS NOT NULL AND value IS NULL
                    AND min_value IS NOT NULL AND max_value IS NOT NULL AND min_value < max_value
                    AND text_value IS NULL AND numeric_value IS NULL AND tone_sequence IS NULL)
                OR
                (matcher_type IN ('STATUS', 'UNIT_STATUS')
                    AND protocol IS NULL AND value IS NULL AND min_value IS NULL AND max_value IS NULL
                    AND text_value IS NULL AND numeric_value IS NOT NULL AND tone_sequence IS NULL)
                OR
                (matcher_type = 'TONES'
                    AND protocol IS NULL AND value IS NULL AND min_value IS NULL AND max_value IS NULL
                    AND text_value IS NULL AND numeric_value IS NULL
                    AND tone_sequence IS NOT NULL AND length(tone_sequence) > 0)
                OR
                (matcher_type IN ('DCS', 'ESN')
                    AND protocol IS NULL AND value IS NULL AND min_value IS NULL AND max_value IS NULL
                    AND text_value IS NOT NULL AND numeric_value IS NULL AND tone_sequence IS NULL)
            )
        )
        """;
    private static final String ALIAS_ACTIVITY_SUMMARY_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_activity_summary (
            alias_id INTEGER PRIMARY KEY CHECK(typeof(alias_id) = 'integer' AND alias_id > 0),
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE CASCADE
                CHECK(typeof(alias_list_id) = 'integer' AND alias_list_id > 0),
            protocol_code INTEGER NOT NULL CHECK(
                typeof(protocol_code) = 'integer' AND protocol_code IN (0, 1, 3, 4)
            ),
            metrics_state TEXT NOT NULL CHECK(
                typeof(metrics_state) = 'text' AND metrics_state IN (
                    'unsupported', 'not_collected', 'observed'
                )
            ),
            logical_call_count INTEGER CHECK(logical_call_count IS NULL OR
                (typeof(logical_call_count) = 'integer' AND logical_call_count >= 0)),
            recorded_logical_call_count INTEGER CHECK(recorded_logical_call_count IS NULL OR
                (typeof(recorded_logical_call_count) = 'integer' AND recorded_logical_call_count >= 0)),
            stream_submitted_logical_call_count INTEGER CHECK(stream_submitted_logical_call_count IS NULL OR
                (typeof(stream_submitted_logical_call_count) = 'integer'
                    AND stream_submitted_logical_call_count >= 0)),
            encrypted_logical_call_count INTEGER CHECK(encrypted_logical_call_count IS NULL OR
                (typeof(encrypted_logical_call_count) = 'integer' AND encrypted_logical_call_count >= 0)),
            grant_observation_count INTEGER CHECK(grant_observation_count IS NULL OR
                (typeof(grant_observation_count) = 'integer' AND grant_observation_count >= 0)),
            join_observation_count INTEGER CHECK(join_observation_count IS NULL OR
                (typeof(join_observation_count) = 'integer' AND join_observation_count >= 0)),
            emergency_observation_count INTEGER CHECK(emergency_observation_count IS NULL OR
                (typeof(emergency_observation_count) = 'integer' AND emergency_observation_count >= 0)),
            register_observation_count INTEGER CHECK(register_observation_count IS NULL OR
                (typeof(register_observation_count) = 'integer' AND register_observation_count >= 0)),
            logout_observation_count INTEGER CHECK(logout_observation_count IS NULL OR
                (typeof(logout_observation_count) = 'integer' AND logout_observation_count >= 0)),
            denial_observation_count INTEGER CHECK(denial_observation_count IS NULL OR
                (typeof(denial_observation_count) = 'integer' AND denial_observation_count >= 0)),
            data_observation_count INTEGER CHECK(data_observation_count IS NULL OR
                (typeof(data_observation_count) = 'integer' AND data_observation_count >= 0)),
            other_signaling_observation_count INTEGER CHECK(other_signaling_observation_count IS NULL OR
                (typeof(other_signaling_observation_count) = 'integer'
                    AND other_signaling_observation_count >= 0)),
            signaling_observation_count INTEGER CHECK(signaling_observation_count IS NULL OR
                (typeof(signaling_observation_count) = 'integer' AND signaling_observation_count >= 0)),
            first_evidence_ms INTEGER CHECK(first_evidence_ms IS NULL OR
                (typeof(first_evidence_ms) = 'integer' AND first_evidence_ms >= 0)),
            last_evidence_ms INTEGER CHECK(last_evidence_ms IS NULL OR
                (typeof(last_evidence_ms) = 'integer' AND last_evidence_ms >= 0)),
            updated_at_ms INTEGER NOT NULL CHECK(typeof(updated_at_ms) = 'integer' AND updated_at_ms > 0),
            FOREIGN KEY(alias_id) REFERENCES alias(id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
            CHECK((first_evidence_ms IS NULL AND last_evidence_ms IS NULL) OR
                (first_evidence_ms IS NOT NULL AND last_evidence_ms IS NOT NULL
                    AND last_evidence_ms >= first_evidence_ms)),
            CHECK((protocol_code = 0 AND metrics_state = 'unsupported') OR
                (protocol_code IN (1, 3, 4) AND metrics_state <> 'unsupported'))
        )
        """;
    private static final String SCAN_LIST_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS scan_list (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            sort_order INTEGER NOT NULL DEFAULT 0 CHECK(
                typeof(sort_order) = 'integer' AND sort_order BETWEEN 0 AND 2147483647
            ),
            name TEXT NOT NULL COLLATE NOCASE CHECK(
                typeof(name) = 'text' AND length(trim(name)) BETWEEN 1 AND 100
            ),
            description TEXT CHECK(
                description IS NULL OR
                    (typeof(description) = 'text' AND length(trim(description)) BETWEEN 1 AND 1000)
            ),
            published INTEGER NOT NULL DEFAULT 1
                CHECK(typeof(published) = 'integer' AND published IN (0, 1)),
            is_default INTEGER NOT NULL DEFAULT 0
                CHECK(typeof(is_default) = 'integer' AND is_default IN (0, 1)),
            CHECK(is_default = 0 OR published = 1),
            UNIQUE(name)
        )
        """;
    private static final String ALIAS_SCAN_LIST_MEMBERSHIP_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_scan_list_membership (
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE
                CHECK(typeof(alias_id) = 'integer' AND alias_id > 0),
            scan_list_id INTEGER NOT NULL REFERENCES scan_list(id) ON DELETE CASCADE
                CHECK(typeof(scan_list_id) = 'integer' AND scan_list_id > 0),
            PRIMARY KEY(alias_id, scan_list_id)
        ) WITHOUT ROWID
        """;
    private static final String ALIAS_LIST_UNMATCHED_TALKGROUP_SCAN_LIST_MEMBERSHIP_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_list_unmatched_talkgroup_scan_list_membership (
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE CASCADE
                CHECK(typeof(alias_list_id) = 'integer' AND alias_list_id > 0),
            scan_list_id INTEGER NOT NULL REFERENCES scan_list(id) ON DELETE CASCADE
                CHECK(typeof(scan_list_id) = 'integer' AND scan_list_id > 0),
            PRIMARY KEY(alias_list_id, scan_list_id)
        ) WITHOUT ROWID
        """;
    private static final String ALIAS_LIST_NEW_ALIAS_SCAN_LIST_MEMBERSHIP_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_list_new_alias_scan_list_membership (
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE CASCADE
                CHECK(typeof(alias_list_id) = 'integer' AND alias_list_id > 0),
            scan_list_id INTEGER NOT NULL REFERENCES scan_list(id) ON DELETE CASCADE
                CHECK(typeof(scan_list_id) = 'integer' AND scan_list_id > 0),
            PRIMARY KEY(alias_list_id, scan_list_id)
        ) WITHOUT ROWID
        """;
    private static final String ALIAS_BROADCAST_CHANNEL_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_broadcast_channel (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE
                CHECK(typeof(alias_id) = 'integer' AND alias_id > 0),
            broadcast_configuration_id TEXT NOT NULL REFERENCES
                configuration_broadcast_stream(configuration_id)
                DEFERRABLE INITIALLY DEFERRED CHECK(
                    typeof(broadcast_configuration_id) = 'text'
                    AND length(broadcast_configuration_id) = 36
                    AND broadcast_configuration_id = lower(broadcast_configuration_id)
                    AND substr(broadcast_configuration_id, 9, 1) = '-'
                    AND substr(broadcast_configuration_id, 14, 1) = '-'
                    AND substr(broadcast_configuration_id, 19, 1) = '-'
                    AND substr(broadcast_configuration_id, 24, 1) = '-'
                    AND length(replace(broadcast_configuration_id, '-', '')) = 32
                    AND replace(broadcast_configuration_id, '-', '') NOT GLOB '*[^0-9a-f]*'
                ),
            UNIQUE(alias_id, broadcast_configuration_id)
        )
        """;
    private static final String ALIAS_LIST_UNMATCHED_TALKGROUP_STREAM_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_list_unmatched_talkgroup_stream (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE CASCADE
                CHECK(typeof(alias_list_id) = 'integer' AND alias_list_id > 0),
            broadcast_configuration_id TEXT NOT NULL REFERENCES
                configuration_broadcast_stream(configuration_id)
                DEFERRABLE INITIALLY DEFERRED CHECK(
                    typeof(broadcast_configuration_id) = 'text'
                    AND length(broadcast_configuration_id) = 36
                    AND broadcast_configuration_id = lower(broadcast_configuration_id)
                    AND substr(broadcast_configuration_id, 9, 1) = '-'
                    AND substr(broadcast_configuration_id, 14, 1) = '-'
                    AND substr(broadcast_configuration_id, 19, 1) = '-'
                    AND substr(broadcast_configuration_id, 24, 1) = '-'
                    AND length(replace(broadcast_configuration_id, '-', '')) = 32
                    AND replace(broadcast_configuration_id, '-', '') NOT GLOB '*[^0-9a-f]*'
                ),
            UNIQUE(alias_list_id, broadcast_configuration_id)
        )
        """;
    private static final String ALIAS_LIST_NEW_ALIAS_STREAM_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS alias_list_new_alias_stream (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE CASCADE
                CHECK(typeof(alias_list_id) = 'integer' AND alias_list_id > 0),
            broadcast_configuration_id TEXT NOT NULL REFERENCES
                configuration_broadcast_stream(configuration_id)
                DEFERRABLE INITIALLY DEFERRED CHECK(
                    typeof(broadcast_configuration_id) = 'text'
                    AND length(broadcast_configuration_id) = 36
                    AND broadcast_configuration_id = lower(broadcast_configuration_id)
                    AND substr(broadcast_configuration_id, 9, 1) = '-'
                    AND substr(broadcast_configuration_id, 14, 1) = '-'
                    AND substr(broadcast_configuration_id, 19, 1) = '-'
                    AND substr(broadcast_configuration_id, 24, 1) = '-'
                    AND length(replace(broadcast_configuration_id, '-', '')) = 32
                    AND replace(broadcast_configuration_id, '-', '') NOT GLOB '*[^0-9a-f]*'
                ),
            UNIQUE(alias_list_id, broadcast_configuration_id)
        )
        """;
    private static final String CONFIGURATION_CHANNEL_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS configuration_channel (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            configuration_id TEXT NOT NULL UNIQUE CHECK(
                typeof(configuration_id) = 'text'
                AND length(configuration_id) = 36 AND configuration_id = lower(configuration_id)
                AND substr(configuration_id, 9, 1) = '-'
                AND substr(configuration_id, 14, 1) = '-'
                AND substr(configuration_id, 19, 1) = '-'
                AND substr(configuration_id, 24, 1) = '-'
                AND length(replace(configuration_id, '-', '')) = 32
                AND replace(configuration_id, '-', '') NOT GLOB '*[^0-9a-f]*'
            ),
            channel_kind TEXT NOT NULL CHECK(
                typeof(channel_kind) = 'text' AND channel_kind IN ('TRUNKED', 'CONVENTIONAL')
            ),
            sort_order INTEGER NOT NULL CHECK(
                typeof(sort_order) = 'integer' AND sort_order BETWEEN 0 AND 2147483647
            ),
            system_name TEXT CHECK(system_name IS NULL OR typeof(system_name) = 'text'),
            site_name TEXT CHECK(site_name IS NULL OR typeof(site_name) = 'text'),
            name TEXT CHECK(name IS NULL OR typeof(name) = 'text'),
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE RESTRICT
                DEFERRABLE INITIALLY DEFERRED
                CHECK(typeof(alias_list_id) = 'integer' AND alias_list_id > 0),
            radioresolve_id TEXT CHECK(
                radioresolve_id IS NULL OR (
                    typeof(radioresolve_id) = 'text'
                    AND radioresolve_id = trim(radioresolve_id)
                    AND length(radioresolve_id) = 36
                    AND radioresolve_id = lower(radioresolve_id)
                    AND substr(radioresolve_id, 9, 1) = '-'
                    AND substr(radioresolve_id, 14, 1) = '-'
                    AND substr(radioresolve_id, 19, 1) = '-'
                    AND substr(radioresolve_id, 24, 1) = '-'
                    AND length(replace(radioresolve_id, '-', '')) = 32
                    AND replace(radioresolve_id, '-', '') NOT GLOB '*[^0-9a-f]*'
                )
            ),
            auto_start INTEGER NOT NULL DEFAULT 0
                CHECK(typeof(auto_start) = 'integer' AND auto_start IN (0, 1)),
            auto_start_order INTEGER CHECK(
                auto_start_order IS NULL OR (
                    typeof(auto_start_order) = 'integer'
                    AND auto_start_order BETWEEN -2147483648 AND 2147483647
                )
            ),
            decoder_type TEXT NOT NULL CHECK(
                typeof(decoder_type) = 'text' AND decoder_type IN (
                    'AM', 'DMR', 'NBFM', 'NXDN', 'P25_CONVENTIONAL', 'P25_PHASE1', 'P25_PHASE2'
                )),
            address_domain_code INTEGER NOT NULL DEFAULT 0 CHECK(
                typeof(address_domain_code) = 'integer' AND address_domain_code IN (0, 1, 2)
            ),
            primary_frequency_hz INTEGER CHECK(primary_frequency_hz IS NULL OR
                (typeof(primary_frequency_hz) = 'integer' AND primary_frequency_hz > 0)),
            config_json TEXT NOT NULL CHECK(
                typeof(config_json) = 'text' AND CASE WHEN json_valid(config_json) THEN
                    json_type(config_json, '$') = 'object'
                    AND json_type(config_json, '$.configurationId') IS NULL
                    AND json_type(config_json, '$.system') IS NULL
                    AND json_type(config_json, '$.site') IS NULL
                    AND json_type(config_json, '$.name') IS NULL
                    AND json_type(config_json, '$.aliasListId') IS NULL
                    AND json_type(config_json, '$.aliasListName') IS NULL
                    AND json_type(config_json, '$.radioResolveId') IS NULL
                    AND json_type(config_json, '$.radresGuid') IS NULL
                    AND json_type(config_json, '$.radres_guid') IS NULL
                    AND json_type(config_json, '$.autoStart') IS NULL
                    AND json_type(config_json, '$.enabled') IS NULL
                    AND json_type(config_json, '$.autoStartOrder') IS NULL
                    AND json_type(config_json, '$.order') IS NULL
                    AND json_type(config_json, '$.channelType') IS NULL
                    AND CASE decoder_type
                        WHEN 'DMR' THEN CASE
                            WHEN json_type(config_json, '$.decodeConfiguration.channelMode') = 'text'
                                THEN json_extract(config_json, '$.decodeConfiguration.channelMode')
                                    IN ('CONVENTIONAL', 'TRUNKED')
                            ELSE 0 END
                        WHEN 'NXDN' THEN CASE
                            WHEN json_type(config_json, '$.decodeConfiguration.channelMode') = 'text'
                                THEN json_extract(config_json, '$.decodeConfiguration.channelMode')
                                    IN ('CONVENTIONAL', 'TRUNKED')
                            ELSE 0 END
                        ELSE 1
                    END
                ELSE 0 END
            ),
            CHECK(
                (decoder_type = 'NXDN' AND address_domain_code IN (1, 2))
                OR (decoder_type <> 'NXDN' AND address_domain_code = 0)
            )
        )
        """;
    /** Frozen predecessor DDL used only while the adjacent format-14 migration constructs exact format 15. */
    private static final String FORMAT_15_CONFIGURATION_CHANNEL_TABLE_SQL = CONFIGURATION_CHANNEL_TABLE_SQL.replace(
        """
            alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE RESTRICT
                DEFERRABLE INITIALLY DEFERRED
                CHECK(typeof(alias_list_id) = 'integer' AND alias_list_id > 0),
        """,
        """
            alias_list_id INTEGER REFERENCES alias_list(id)
                DEFERRABLE INITIALLY DEFERRED CHECK(alias_list_id IS NULL OR
                    (typeof(alias_list_id) = 'integer' AND alias_list_id > 0)),
        """);
    private static final String FORMAT_16_CONFIGURATION_CHANNEL_MIGRATION_TABLE_SQL =
        CONFIGURATION_CHANNEL_TABLE_SQL.replaceFirst(
            "(?i)configuration_channel", "configuration_channel_format16");
    private static final String CONFIGURATION_BROADCAST_STREAM_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS configuration_broadcast_stream (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            configuration_id TEXT NOT NULL UNIQUE CHECK(
                typeof(configuration_id) = 'text'
                AND length(configuration_id) = 36 AND configuration_id = lower(configuration_id)
                AND substr(configuration_id, 9, 1) = '-'
                AND substr(configuration_id, 14, 1) = '-'
                AND substr(configuration_id, 19, 1) = '-'
                AND substr(configuration_id, 24, 1) = '-'
                AND length(replace(configuration_id, '-', '')) = 32
                AND replace(configuration_id, '-', '') NOT GLOB '*[^0-9a-f]*'
            ),
            sort_order INTEGER NOT NULL CHECK(
                typeof(sort_order) = 'integer' AND sort_order BETWEEN 0 AND 2147483647
            ),
            config_json TEXT NOT NULL CHECK(
                typeof(config_json) = 'text' AND CASE WHEN json_valid(config_json) THEN
                    json_type(config_json, '$') = 'object'
                    AND json_type(config_json, '$.configurationId') IS NULL
                    AND json_type(config_json, '$.aliasListName') IS NULL
                ELSE 0 END
            )
        )
        """;
    private static final String APPLICATION_SETTINGS_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS application_settings (
            key TEXT NOT NULL PRIMARY KEY CHECK(typeof(key) = 'text' AND length(trim(key)) > 0),
            settings_json TEXT NOT NULL CHECK(typeof(settings_json) = 'text' AND json_valid(settings_json)),
            updated_at_ms INTEGER NOT NULL
                CHECK(typeof(updated_at_ms) = 'integer' AND updated_at_ms > 0)
        )
        """;
    private static final String APPLICATION_ICONS_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS application_icons (
            key TEXT NOT NULL PRIMARY KEY CHECK(typeof(key) = 'text' AND length(trim(key)) > 0),
            icons_json TEXT NOT NULL CHECK(
                typeof(icons_json) = 'text' AND json_valid(icons_json)
                AND json_type(icons_json, '$') = 'object'
            ),
            updated_at_ms INTEGER NOT NULL
                CHECK(typeof(updated_at_ms) = 'integer' AND updated_at_ms > 0)
        )
        """;
    private static final String WEB_USER_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS web_user (
            id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(typeof(id) = 'integer' AND id > 0),
            username TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(
                typeof(username) = 'text' AND length(username) BETWEEN 1 AND 64
                AND username = lower(username) AND substr(username, 1, 1) GLOB '[a-z0-9]'
                AND username NOT GLOB '*[^a-z0-9._-]*'
            ),
            tier TEXT NOT NULL CHECK(typeof(tier) = 'text' AND tier IN ('USER', 'ADMIN')),
            primary_admin INTEGER NOT NULL DEFAULT 0
                CHECK(typeof(primary_admin) = 'integer' AND primary_admin IN (0, 1)),
            credential_version INTEGER NOT NULL
                CHECK(typeof(credential_version) = 'integer' AND credential_version = 1),
            password_algorithm TEXT NOT NULL CHECK(
                typeof(password_algorithm) = 'text' AND password_algorithm = 'PBKDF2WithHmacSHA256'
            ),
            password_iterations INTEGER NOT NULL CHECK(
                typeof(password_iterations) = 'integer'
                AND password_iterations BETWEEN 600000 AND 5000000
            ),
            password_derived_key_bits INTEGER NOT NULL CHECK(
                typeof(password_derived_key_bits) = 'integer' AND password_derived_key_bits = 256
            ),
            password_salt BLOB NOT NULL CHECK(
                typeof(password_salt) = 'blob' AND length(password_salt) BETWEEN 16 AND 64
            ),
            password_hash BLOB NOT NULL CHECK(
                typeof(password_hash) = 'blob' AND length(password_hash) = 32
            ),
            password_changed_at_ms INTEGER NOT NULL CHECK(
                typeof(password_changed_at_ms) = 'integer' AND password_changed_at_ms > 0
            ),
            auth_revision INTEGER NOT NULL DEFAULT 1 CHECK(
                typeof(auth_revision) = 'integer' AND auth_revision > 0
            ),
            preferences_json TEXT NOT NULL CHECK(
                typeof(preferences_json) = 'text' AND length(preferences_json) <= 131072
                AND json_valid(preferences_json) AND json_type(preferences_json, '$') = 'object'
            ),
            preferences_revision INTEGER NOT NULL DEFAULT 1 CHECK(
                typeof(preferences_revision) = 'integer'
                AND preferences_revision BETWEEN 1 AND 9223372036854775806
            ),
            created_at_ms INTEGER NOT NULL CHECK(
                typeof(created_at_ms) = 'integer' AND created_at_ms > 0
            ),
            updated_at_ms INTEGER NOT NULL CHECK(
                typeof(updated_at_ms) = 'integer' AND updated_at_ms > 0
            ),
            CHECK(
                (primary_admin = 1 AND username = 'admin' AND tier = 'ADMIN')
                OR (primary_admin = 0 AND username <> 'admin')
            )
        )
        """;
    private static final String WEB_ACCESS_POLICY_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS web_access_policy (
            capability_id TEXT NOT NULL PRIMARY KEY CHECK(
                typeof(capability_id) = 'text' AND length(capability_id) BETWEEN 1 AND 64
                AND capability_id = lower(capability_id)
            ),
            required_tier TEXT NOT NULL CHECK(
                typeof(required_tier) = 'text' AND required_tier IN ('PUBLIC', 'USER', 'ADMIN')
            ),
            updated_at_ms INTEGER NOT NULL CHECK(
                typeof(updated_at_ms) = 'integer' AND updated_at_ms > 0
            )
        ) WITHOUT ROWID
        """;
    private static final String WEB_USER_PRIMARY_INDEX_SQL = """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_web_user_one_primary_admin
        ON web_user(primary_admin)
        WHERE primary_admin = 1
        """;
    private static final List<SqliteSchemaValidator.Definition> EXACT_ALIAS_OBJECTS = List.of(
        new SqliteSchemaValidator.Definition("table", "alias_list", ALIAS_LIST_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias", ALIAS_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_broadcast_channel",
            ALIAS_BROADCAST_CHANNEL_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_list_unmatched_talkgroup_stream",
            ALIAS_LIST_UNMATCHED_TALKGROUP_STREAM_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_list_new_alias_stream",
            ALIAS_LIST_NEW_ALIAS_STREAM_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "scan_list", SCAN_LIST_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_scan_list_membership",
            ALIAS_SCAN_LIST_MEMBERSHIP_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_list_unmatched_talkgroup_scan_list_membership",
            ALIAS_LIST_UNMATCHED_TALKGROUP_SCAN_LIST_MEMBERSHIP_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "alias_list_new_alias_scan_list_membership",
            ALIAS_LIST_NEW_ALIAS_SCAN_LIST_MEMBERSHIP_TABLE_SQL),
        new SqliteSchemaValidator.Definition("index", "idx_scan_list_one_default", """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_scan_list_one_default
            ON scan_list(is_default)
            WHERE is_default = 1
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_scan_list_by_list", """
            CREATE INDEX IF NOT EXISTS idx_alias_scan_list_by_list
            ON alias_scan_list_membership(scan_list_id, alias_id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_list_unmatched_talkgroup_scan_list_by_list", """
            CREATE INDEX IF NOT EXISTS idx_alias_list_unmatched_talkgroup_scan_list_by_list
            ON alias_list_unmatched_talkgroup_scan_list_membership(scan_list_id, alias_list_id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_list_new_alias_scan_list_by_list", """
            CREATE INDEX IF NOT EXISTS idx_alias_list_new_alias_scan_list_by_list
            ON alias_list_new_alias_scan_list_membership(scan_list_id, alias_list_id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_talkgroup_value", """
            CREATE INDEX IF NOT EXISTS idx_alias_talkgroup_value
            ON alias(protocol, value, alias_list_id, id)
            WHERE matcher_type = 'TALKGROUP'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_talkgroup_range", """
            CREATE INDEX IF NOT EXISTS idx_alias_talkgroup_range
            ON alias(protocol, min_value, max_value, alias_list_id, id)
            WHERE matcher_type = 'TALKGROUP_RANGE'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_radio_value", """
            CREATE INDEX IF NOT EXISTS idx_alias_radio_value
            ON alias(protocol, value, alias_list_id, id)
            WHERE matcher_type = 'RADIO_ID'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_radio_range", """
            CREATE INDEX IF NOT EXISTS idx_alias_radio_range
            ON alias(protocol, min_value, max_value, alias_list_id, id)
            WHERE matcher_type = 'RADIO_ID_RANGE'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_broadcast_configuration",
            "CREATE INDEX IF NOT EXISTS idx_alias_broadcast_configuration " +
                "ON alias_broadcast_channel(broadcast_configuration_id)"),
        new SqliteSchemaValidator.Definition("view", "alias_talkgroup", """
            CREATE VIEW IF NOT EXISTS alias_talkgroup AS
            SELECT alias.id AS alias_id,
                   alias.protocol,
                   alias.value,
                   alias.min_value,
                   alias.max_value,
                   CASE WHEN alias.matcher_type = 'TALKGROUP_RANGE' THEN 1 ELSE 0 END AS ranged,
                   alias.alias_list_id
            FROM alias
            WHERE alias.matcher_type IN (
                  'TALKGROUP',
                  'TALKGROUP_RANGE'
              )
            """),
        new SqliteSchemaValidator.Definition("view", "alias_radio", """
            CREATE VIEW IF NOT EXISTS alias_radio AS
            SELECT alias.id AS alias_id,
                   alias.protocol,
                   alias.value,
                   alias.min_value,
                   alias.max_value,
                   CASE WHEN alias.matcher_type = 'RADIO_ID_RANGE' THEN 1 ELSE 0 END AS ranged,
                   alias.alias_list_id
            FROM alias
            WHERE alias.matcher_type IN (
                  'RADIO_ID',
                  'RADIO_ID_RANGE'
              )
            """)
    );
    private static final List<SqliteSchemaValidator.Definition> EXACT_ALIAS_ACTIVITY_INDEX_OBJECTS = List.of(
        new SqliteSchemaValidator.Definition("index", "idx_alias_list_id", """
            CREATE INDEX IF NOT EXISTS idx_alias_list_id
            ON alias(alias_list_id, id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_list_name_sort", """
            CREATE INDEX IF NOT EXISTS idx_alias_list_name_sort
            ON alias(alias_list_id, lower(coalesce(name, '')), id)
            """));
    private static final List<SqliteSchemaValidator.Definition> EXACT_ALIAS_ACTIVITY_SUMMARY_OBJECTS = List.of(
        new SqliteSchemaValidator.Definition("table", "alias_activity_summary",
            ALIAS_ACTIVITY_SUMMARY_TABLE_SQL),
        new SqliteSchemaValidator.Definition("index", "idx_alias_activity_talkgroup_range", """
            CREATE INDEX IF NOT EXISTS idx_alias_activity_talkgroup_range
            ON alias(alias_list_id, protocol, min_value DESC, max_value DESC, id DESC)
            WHERE matcher_type = 'TALKGROUP_RANGE'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_activity_radio_range", """
            CREATE INDEX IF NOT EXISTS idx_alias_activity_radio_range
            ON alias(alias_list_id, protocol, min_value DESC, max_value DESC, id DESC)
            WHERE matcher_type = 'RADIO_ID_RANGE'
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_activity_matcher_sort", """
            CREATE INDEX IF NOT EXISTS idx_alias_activity_matcher_sort
            ON alias(alias_list_id, matcher_type, id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_activity_type_sort", """
            CREATE INDEX IF NOT EXISTS idx_alias_activity_type_sort
            ON alias(alias_list_id, CASE
                WHEN matcher_type IN ('TALKGROUP', 'TALKGROUP_RANGE') THEN 'talkgroup'
                WHEN matcher_type IN ('RADIO_ID', 'RADIO_ID_RANGE') THEN 'radio'
                ELSE 'other'
            END, id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_activity_group_sort", """
            CREATE INDEX IF NOT EXISTS idx_alias_activity_group_sort
            ON alias(alias_list_id, lower(coalesce(group_name, '')), id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_activity_value_sort", """
            CREATE INDEX IF NOT EXISTS idx_alias_activity_value_sort
            ON alias(alias_list_id, CASE
                WHEN matcher_type IN ('TALKGROUP_RANGE', 'RADIO_ID_RANGE')
                    THEN printf('%020d–%020d', min_value, max_value)
                WHEN value IS NOT NULL THEN printf('%020d', value)
                WHEN numeric_value IS NOT NULL THEN printf('%020d', numeric_value)
                WHEN text_value IS NOT NULL THEN lower(text_value)
                WHEN tone_sequence IS NOT NULL THEN lower(tone_sequence)
                ELSE ''
            END, id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_activity_calls", """
            CREATE INDEX IF NOT EXISTS idx_alias_activity_calls
            ON alias_activity_summary(alias_list_id, logical_call_count DESC, alias_id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_activity_signaling", """
            CREATE INDEX IF NOT EXISTS idx_alias_activity_signaling
            ON alias_activity_summary(alias_list_id, signaling_observation_count DESC, alias_id)
            """),
        new SqliteSchemaValidator.Definition("index", "idx_alias_activity_last_seen", """
            CREATE INDEX IF NOT EXISTS idx_alias_activity_last_seen
            ON alias_activity_summary(alias_list_id, last_evidence_ms DESC, alias_id)
            """));
    private static final List<SqliteSchemaValidator.Definition> EXACT_CONFIGURATION_OBJECTS = List.of(
        new SqliteSchemaValidator.Definition("table", "configuration_channel",
            CONFIGURATION_CHANNEL_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "configuration_broadcast_stream",
            CONFIGURATION_BROADCAST_STREAM_TABLE_SQL));
    private static final List<SqliteSchemaValidator.Definition> EXACT_CORE_OBJECTS = List.of(
        new SqliteSchemaValidator.Definition("table", "database_metadata", DATABASE_METADATA_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "application_settings", APPLICATION_SETTINGS_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "application_icons", APPLICATION_ICONS_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "receiver_health_incident",
            RECEIVER_HEALTH_INCIDENT_TABLE_SQL));
    private static final List<SqliteSchemaValidator.Definition> EXACT_WEB_SETTINGS_OBJECTS = List.of(
        new SqliteSchemaValidator.Definition("table", "web_user", WEB_USER_TABLE_SQL),
        new SqliteSchemaValidator.Definition("table", "web_access_policy", WEB_ACCESS_POLICY_TABLE_SQL),
        new SqliteSchemaValidator.Definition("index", "idx_web_user_one_primary_admin",
            WEB_USER_PRIMARY_INDEX_SQL));
    private static final List<SqliteSchemaValidator.Table> TABLES = tables();
    private static final List<String> INDEXES = List.of(
        "idx_alias_talkgroup_value",
        "idx_alias_talkgroup_range",
        "idx_alias_radio_value",
        "idx_alias_radio_range",
        "idx_alias_list_id",
        "idx_alias_list_name_sort",
        "idx_alias_activity_talkgroup_range",
        "idx_alias_activity_radio_range",
        "idx_alias_activity_matcher_sort",
        "idx_alias_activity_type_sort",
        "idx_alias_activity_group_sort",
        "idx_alias_activity_value_sort",
        "idx_alias_activity_calls",
        "idx_alias_activity_signaling",
        "idx_alias_activity_last_seen",
        "idx_alias_broadcast_configuration",
        "idx_scan_list_one_default",
        "idx_alias_scan_list_by_list",
        "idx_alias_list_unmatched_talkgroup_scan_list_by_list",
        "idx_alias_list_new_alias_scan_list_by_list",
        "idx_configuration_channel_sort",
        "idx_configuration_channel_alias_list",
        "idx_configuration_channel_decoder",
        "idx_configuration_channel_frequency",
        "idx_configuration_channel_unique_radioresolve_id",
        "idx_configuration_broadcast_sort",
        "idx_web_user_one_primary_admin"
    );
    private static final List<String> VIEWS = List.of("alias_talkgroup", "alias_radio");
    private SdrTrunkDatabaseSchema()
    {
    }

    private static List<SqliteSchemaValidator.Table> tables()
    {
        return List.of(
            new SqliteSchemaValidator.Table("database_metadata", "key", "value", "updated_at_ms"),
            new SqliteSchemaValidator.Table("alias_list", "id", "name", "family",
                "unmatched_talkgroup_record_enabled", "new_alias_record_enabled"),
            new SqliteSchemaValidator.Table("alias", "id", "alias_list_id", "name", "description",
                "group_name", "color", "icon_name", "stream_as_talkgroup", "record_enabled",
                "matcher_type", "protocol", "value", "min_value",
                "max_value", "text_value", "numeric_value", "tone_sequence"),
            new SqliteSchemaValidator.Table("alias_activity_summary", "alias_id", "alias_list_id",
                "protocol_code", "metrics_state",
                "logical_call_count", "recorded_logical_call_count", "stream_submitted_logical_call_count",
                "encrypted_logical_call_count", "grant_observation_count", "join_observation_count",
                "emergency_observation_count", "register_observation_count", "logout_observation_count",
                "denial_observation_count", "data_observation_count", "other_signaling_observation_count",
                "signaling_observation_count", "first_evidence_ms", "last_evidence_ms", "updated_at_ms"),
            new SqliteSchemaValidator.Table("alias_broadcast_channel", "id", "alias_id",
                "broadcast_configuration_id"),
            new SqliteSchemaValidator.Table("alias_list_unmatched_talkgroup_stream", "id", "alias_list_id",
                "broadcast_configuration_id"),
            new SqliteSchemaValidator.Table("alias_list_new_alias_stream", "id", "alias_list_id",
                "broadcast_configuration_id"),
            new SqliteSchemaValidator.Table("scan_list", "id", "sort_order", "name", "description", "published",
                "is_default"),
            new SqliteSchemaValidator.Table("alias_scan_list_membership", "alias_id", "scan_list_id"),
            new SqliteSchemaValidator.Table("alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_id",
                "scan_list_id"),
            new SqliteSchemaValidator.Table("alias_list_new_alias_scan_list_membership", "alias_list_id",
                "scan_list_id"),
            new SqliteSchemaValidator.Table("configuration_channel", "id", "configuration_id", "channel_kind",
                "sort_order", "system_name", "site_name",
                "name", "alias_list_id", "radioresolve_id", "auto_start", "auto_start_order", "decoder_type",
                "address_domain_code", "primary_frequency_hz", "config_json"),
            new SqliteSchemaValidator.Table("configuration_broadcast_stream", "id", "configuration_id",
                "sort_order", "config_json"),
            new SqliteSchemaValidator.Table("application_settings", "key", "settings_json", "updated_at_ms"),
            new SqliteSchemaValidator.Table("application_icons", "key", "icons_json", "updated_at_ms"),
            new SqliteSchemaValidator.Table("receiver_health_incident", "id", "process_started_at_ms",
                "occurrence_id", "code", "severity", "title", "scope", "opened_at_ms", "last_seen_at_ms",
                "resolved_at_ms", "count", "observed", "likely_cause", "impact", "check_next"),
            new SqliteSchemaValidator.Table("web_user", "id", "username", "tier", "primary_admin",
                "credential_version", "password_algorithm", "password_iterations", "password_derived_key_bits",
                "password_salt", "password_hash", "password_changed_at_ms", "auth_revision", "preferences_json",
                "preferences_revision", "created_at_ms", "updated_at_ms"),
            new SqliteSchemaValidator.Table("web_access_policy", "capability_id", "required_tier", "updated_at_ms")
        );
    }

    public static void create(Connection connection) throws SQLException
    {
        create(connection, CONFIGURATION_CHANNEL_TABLE_SQL);
        createReceiverHealthIncidentTable(connection);
    }

    /** Creates the exact format-15 target for its immutable adjacent migration. */
    public static void createFormat15(Connection connection) throws SQLException
    {
        create(connection, FORMAT_15_CONFIGURATION_CHANNEL_TABLE_SQL);
    }

    private static void create(Connection connection, String configurationChannelTableSql) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(DATABASE_METADATA_TABLE_SQL);
            for(SqliteSchemaValidator.Definition definition: EXACT_ALIAS_OBJECTS)
            {
                boolean historical = !CONFIGURATION_CHANNEL_TABLE_SQL.equals(configurationChannelTableSql);
                if(historical && (definition.name().equals("alias_list_new_alias_stream") ||
                    definition.name().equals("alias_list_new_alias_scan_list_membership") ||
                    definition.name().equals("idx_alias_list_new_alias_scan_list_by_list")))
                {
                    continue;
                }
                statement.executeUpdate(historical && definition.name().equals("alias_list") ?
                    FORMAT_20_ALIAS_LIST_TABLE_SQL : definition.sql());
            }
            if(CONFIGURATION_CHANNEL_TABLE_SQL.equals(configurationChannelTableSql))
            {
                createAliasCatalogIndexes(connection);
                createAliasActivitySummary(connection);
            }
            statement.executeUpdate("""
                INSERT INTO scan_list (sort_order, name, description, published, is_default)
                SELECT 0, 'Default', NULL, 1, 1
                WHERE NOT EXISTS (SELECT 1 FROM scan_list)
                """);
            statement.executeUpdate(configurationChannelTableSql);
            statement.executeUpdate(CONFIGURATION_BROADCAST_STREAM_TABLE_SQL);
            statement.executeUpdate(APPLICATION_SETTINGS_TABLE_SQL);
            statement.executeUpdate(APPLICATION_ICONS_TABLE_SQL);
            statement.executeUpdate(WEB_USER_TABLE_SQL);
            statement.executeUpdate(WEB_ACCESS_POLICY_TABLE_SQL);
            statement.executeUpdate(WEB_USER_PRIMARY_INDEX_SQL);
            createConfigurationChannelIndexes(connection);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_broadcast_sort ON configuration_broadcast_stream(sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_broadcast_configuration " +
                "ON alias_broadcast_channel(broadcast_configuration_id)");
        }

    }

    /** Creates the current bounded Alias Editor browse and name-sort indexes. */
    public static void createAliasCatalogIndexes(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            for(SqliteSchemaValidator.Definition definition: EXACT_ALIAS_ACTIVITY_INDEX_OBJECTS)
            {
                statement.executeUpdate(definition.sql());
            }
        }
    }

    /** Creates the durable Alias Activity read model for fresh databases and the adjacent staged migrator only. */
    public static void createAliasActivitySummary(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            for(SqliteSchemaValidator.Definition definition: EXACT_ALIAS_ACTIVITY_SUMMARY_OBJECTS)
            {
                statement.executeUpdate(definition.sql());
            }
        }
    }

    /** Creates the independent New Alias routing tables for fresh databases and the adjacent staged migrator only. */
    public static void createNewAliasBehaviorTables(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(ALIAS_LIST_NEW_ALIAS_STREAM_TABLE_SQL);
            statement.executeUpdate(ALIAS_LIST_NEW_ALIAS_SCAN_LIST_MEMBERSHIP_TABLE_SQL);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alias_list_new_alias_scan_list_by_list " +
                "ON alias_list_new_alias_scan_list_membership(scan_list_id, alias_list_id)");
        }
    }

    /** Creates the authoritative saved-channel table. Used by fresh databases and the adjacent staged migrator. */
    public static void createConfigurationChannelTable(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(CONFIGURATION_CHANNEL_TABLE_SQL);
        }
    }

    /** Creates the bounded receiver-health history for fresh databases and the adjacent staged migrator only. */
    public static void createReceiverHealthIncidentTable(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(RECEIVER_HEALTH_INCIDENT_TABLE_SQL);
        }
    }

    /** Creates the current saved-channel table under its fixed adjacent-migration staging name. */
    public static void createConfigurationChannelMigrationTable(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(FORMAT_16_CONFIGURATION_CHANNEL_MIGRATION_TABLE_SQL);
        }
    }

    /** Creates the complete authoritative saved-channel index set. */
    public static void createConfigurationChannelIndexes(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_sort " +
                "ON configuration_channel(sort_order, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_alias_list " +
                "ON configuration_channel(alias_list_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_decoder " +
                "ON configuration_channel(decoder_type)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_configuration_channel_frequency " +
                "ON configuration_channel(primary_frequency_hz)");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS " +
                "idx_configuration_channel_unique_radioresolve_id " +
                "ON configuration_channel(lower(radioresolve_id)) " +
                "WHERE radioresolve_id IS NOT NULL");
        }
    }

    /**
     * Seeds the visible factory Alias List for each supported protocol family and routes each list's unmatched
     * talkgroups to the current Default scan list. This is invoked only for a new database and by the staged
     * release migrator; normal startup remains validation-only.
     *
     * <p>An existing canonical name is reused only when it belongs to the expected family. A wrong-family collision
     * is rejected instead of silently assigning channels to an incompatible list.</p>
     */
    public static void seedDefaultAliasLists(Connection connection) throws SQLException
    {
        long defaultScanListId = requireDefaultScanListId(connection);
        Map<AliasListFamily,Long> existingIds = new EnumMap<>(AliasListFamily.class);

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, family
            FROM alias_list
            WHERE name = ? COLLATE NOCASE
            """))
        {
            for(AliasListFamily family: AliasListFamily.values())
            {
                statement.setString(1, family.getDefaultAliasListName());

                try(ResultSet resultSet = statement.executeQuery())
                {
                    if(resultSet.next())
                    {
                        String persistedFamily = resultSet.getString("family");
                        if(!family.name().equals(persistedFamily))
                        {
                            throw new SQLException("Default Alias List name [" +
                                family.getDefaultAliasListName() + "] belongs to family [" + persistedFamily +
                                "]; expected [" + family.name() + "]");
                        }
                        existingIds.put(family, resultSet.getLong("id"));
                    }
                }
            }
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_list (name, family, unmatched_talkgroup_record_enabled, new_alias_record_enabled)
            VALUES (?, ?, 0, 0)
            """))
        {
            for(AliasListFamily family: AliasListFamily.values())
            {
                if(!existingIds.containsKey(family))
                {
                    statement.setString(1, family.getDefaultAliasListName());
                    statement.setString(2, family.name());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }

        try(PreparedStatement lookup = connection.prepareStatement("""
                SELECT id
                FROM alias_list
                WHERE name = ? COLLATE NOCASE AND family = ?
                """);
            PreparedStatement unmatchedMembership = connection.prepareStatement("""
                INSERT OR IGNORE INTO alias_list_unmatched_talkgroup_scan_list_membership (
                    alias_list_id, scan_list_id
                ) VALUES (?, ?)
                """);
            PreparedStatement newAliasMembership = connection.prepareStatement("""
                INSERT OR IGNORE INTO alias_list_new_alias_scan_list_membership (
                    alias_list_id, scan_list_id
                ) VALUES (?, ?)
                """))
        {
            for(AliasListFamily family: AliasListFamily.values())
            {
                long aliasListId;
                Long existingId = existingIds.get(family);

                if(existingId != null)
                {
                    aliasListId = existingId;
                }
                else
                {
                    lookup.setString(1, family.getDefaultAliasListName());
                    lookup.setString(2, family.name());
                    try(ResultSet resultSet = lookup.executeQuery())
                    {
                        if(!resultSet.next())
                        {
                            throw new SQLException("Unable to resolve seeded Alias List [" +
                                family.getDefaultAliasListName() + "]");
                        }
                        aliasListId = resultSet.getLong("id");
                    }
                }

                unmatchedMembership.setLong(1, aliasListId);
                unmatchedMembership.setLong(2, defaultScanListId);
                unmatchedMembership.addBatch();
                newAliasMembership.setLong(1, aliasListId);
                newAliasMembership.setLong(2, defaultScanListId);
                newAliasMembership.addBatch();
            }
            unmatchedMembership.executeBatch();
            newAliasMembership.executeBatch();
        }
    }

    private static long requireDefaultScanListId(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM scan_list WHERE is_default = 1");
            ResultSet resultSet = statement.executeQuery())
        {
            if(!resultSet.next())
            {
                throw new SQLException("Default Alias Lists require one Default scan list");
            }

            long id = resultSet.getLong("id");
            if(resultSet.next())
            {
                throw new SQLException("Default Alias Lists require exactly one Default scan list");
            }
            return id;
        }
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, VIEWS, List.of());
        SqliteSchemaValidator.validateDefinitions(connection, EXACT_CORE_OBJECTS);
        SqliteSchemaValidator.validateDefinitions(connection, EXACT_ALIAS_OBJECTS);
        SqliteSchemaValidator.validateDefinitions(connection, EXACT_ALIAS_ACTIVITY_INDEX_OBJECTS);
        SqliteSchemaValidator.validateDefinitions(connection, EXACT_ALIAS_ACTIVITY_SUMMARY_OBJECTS);
        SqliteSchemaValidator.validateDefinitions(connection, EXACT_CONFIGURATION_OBJECTS);
        SqliteSchemaValidator.validateDefinitions(connection, EXACT_WEB_SETTINGS_OBJECTS);
        Format5WebStateValidator.validate(connection);

        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM receiver_health_incident"))
        {
            if(!rows.next() || rows.getLong(1) > MAXIMUM_RECEIVER_HEALTH_INCIDENTS)
            {
                throw new SQLException("Receiver-health incident history exceeds its " +
                    MAXIMUM_RECEIVER_HEALTH_INCIDENTS + "-row limit");
            }
        }
    }

}
