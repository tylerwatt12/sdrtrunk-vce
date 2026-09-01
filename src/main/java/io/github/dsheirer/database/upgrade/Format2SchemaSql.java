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

package io.github.dsheirer.database.upgrade;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * DDL pinned to global database format 2.  These definitions intentionally do not reference the mutable current
 * startup schema or runtime enums.
 */
final class Format2SchemaSql
{
    private static final List<String> ACTION_COUNT_COLUMNS = List.of(
        "acknowledge_count", "active_count", "busy_count", "call_count", "check_count", "check_ack_count",
        "continue_count", "data_count", "denial_count", "emergency_count", "gps_count", "grant_count",
        "join_count", "logout_count", "page_count", "patch_count", "patch_cancel_count", "patch_create_count",
        "queued_count", "register_count", "request_count", "status_count", "unknown_count");
    private static final String ACTION_COUNT_DEFINITIONS = actionCountDefinitions();

    private Format2SchemaSql()
    {
    }

    static void createAliasSchema(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS alias_list (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE,
                family TEXT NOT NULL CHECK(family IN (
                    'P25', 'DMR', 'NXDN', 'NBFM'
                )),
                unmatched_talkgroup_record_enabled INTEGER NOT NULL DEFAULT 0 CHECK(
                    unmatched_talkgroup_record_enabled IN (0, 1)
                ),
                UNIQUE(name)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS alias (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE RESTRICT,
                name TEXT,
                description TEXT,
                group_name TEXT,
                color INTEGER NOT NULL DEFAULT 0,
                icon_name TEXT,
                stream_as_talkgroup INTEGER,
                record_enabled INTEGER NOT NULL DEFAULT 0,
                matcher_type TEXT NOT NULL CHECK(matcher_type IN (
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
                protocol TEXT,
                value INTEGER,
                min_value INTEGER,
                max_value INTEGER,
                text_value TEXT,
                numeric_value INTEGER,
                tone_sequence TEXT
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS scan_list (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sort_order INTEGER NOT NULL DEFAULT 0 CHECK(sort_order >= 0),
                name TEXT NOT NULL COLLATE NOCASE CHECK(
                    length(trim(name)) BETWEEN 1 AND 100
                ),
                description TEXT CHECK(
                    description IS NULL OR length(trim(description)) BETWEEN 1 AND 1000
                ),
                published INTEGER NOT NULL DEFAULT 1 CHECK(published IN (0, 1)),
                is_default INTEGER NOT NULL DEFAULT 0 CHECK(is_default IN (0, 1)),
                CHECK(is_default = 0 OR published = 1),
                UNIQUE(name)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS alias_scan_list_membership (
                alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
                scan_list_id INTEGER NOT NULL REFERENCES scan_list(id) ON DELETE CASCADE,
                PRIMARY KEY(alias_id, scan_list_id)
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS alias_list_unmatched_talkgroup_scan_list_membership (
                alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE CASCADE,
                scan_list_id INTEGER NOT NULL REFERENCES scan_list(id) ON DELETE CASCADE,
                PRIMARY KEY(alias_list_id, scan_list_id)
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS alias_broadcast_channel (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
                channel_name TEXT NOT NULL CHECK(length(trim(channel_name)) > 0),
                UNIQUE(alias_id, channel_name)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS alias_list_unmatched_talkgroup_stream (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE CASCADE,
                channel_name TEXT NOT NULL CHECK(length(trim(channel_name)) > 0),
                UNIQUE(alias_list_id, channel_name)
            )
            """);
        statement.executeUpdate("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_scan_list_one_default
            ON scan_list(is_default)
            WHERE is_default = 1
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_alias_scan_list_by_list
            ON alias_scan_list_membership(scan_list_id, alias_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_alias_list_unmatched_talkgroup_scan_list_by_list
            ON alias_list_unmatched_talkgroup_scan_list_membership(scan_list_id, alias_list_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_alias_talkgroup_value
            ON alias(protocol, value, alias_list_id, id)
            WHERE matcher_type = 'TALKGROUP'
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_alias_talkgroup_range
            ON alias(protocol, min_value, max_value, alias_list_id, id)
            WHERE matcher_type = 'TALKGROUP_RANGE'
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_alias_radio_value
            ON alias(protocol, value, alias_list_id, id)
            WHERE matcher_type = 'RADIO_ID'
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_alias_radio_range
            ON alias(protocol, min_value, max_value, alias_list_id, id)
            WHERE matcher_type = 'RADIO_ID_RANGE'
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_alias_broadcast_channel_name
            ON alias_broadcast_channel(channel_name)
            """);
        statement.executeUpdate("""
            CREATE VIEW IF NOT EXISTS alias_talkgroup AS
            SELECT alias.id AS alias_id,
                   alias.protocol,
                   alias.value,
                   alias.min_value,
                   alias.max_value,
                   CASE WHEN alias.matcher_type = 'TALKGROUP_RANGE' THEN 1 ELSE 0 END AS ranged,
                   alias_list.name AS alias_list_name
            FROM alias
            JOIN alias_list ON alias_list.id = alias.alias_list_id
            WHERE alias.matcher_type IN (
                  'TALKGROUP',
                  'TALKGROUP_RANGE'
              )
            """);
        statement.executeUpdate("""
            CREATE VIEW IF NOT EXISTS alias_radio AS
            SELECT alias.id AS alias_id,
                   alias.protocol,
                   alias.value,
                   alias.min_value,
                   alias.max_value,
                   CASE WHEN alias.matcher_type = 'RADIO_ID_RANGE' THEN 1 ELSE 0 END AS ranged,
                   alias_list.name AS alias_list_name
            FROM alias
            JOIN alias_list ON alias_list.id = alias.alias_list_id
            WHERE alias.matcher_type IN (
                  'RADIO_ID',
                  'RADIO_ID_RANGE'
              )
            """);
        statement.executeUpdate("""
            INSERT INTO scan_list (sort_order, name, description, published, is_default)
            SELECT 0, 'Default', NULL, 1, 1
            WHERE NOT EXISTS (SELECT 1 FROM scan_list)
            """);
    }

    static void createTrunkedIdentitySchema(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_identity_scope (
                scope_id INTEGER PRIMARY KEY AUTOINCREMENT,
                scope_token TEXT NOT NULL UNIQUE,
                protocol_code INTEGER NOT NULL CHECK(protocol_code IN (1, 3, 4)),
                scope_kind_code INTEGER NOT NULL CHECK(scope_kind_code IN (1, 2)),
                identity_domain_code INTEGER NOT NULL DEFAULT 0 CHECK(identity_domain_code IN (0, 1, 2)),
                p25_system_key INTEGER UNIQUE REFERENCES p25_system(system_key) ON DELETE CASCADE,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                CHECK(
                    (scope_kind_code = 1 AND protocol_code = 1 AND p25_system_key IS NOT NULL)
                    OR
                    (scope_kind_code = 2 AND protocol_code IN (3, 4) AND p25_system_key IS NULL)
                )
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_identity_scope_context (
                context_id INTEGER PRIMARY KEY REFERENCES receiver_context(id) ON DELETE CASCADE,
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_identity_summary (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id > 0),
                p25_identity_state_code INTEGER NOT NULL DEFAULT 0
                    CHECK(p25_identity_state_code IN (0, 1, 2, 3)),
                p25_home_wacn INTEGER,
                p25_home_system_id INTEGER,
                p25_home_talkgroup_id INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                source_call_count INTEGER NOT NULL DEFAULT 0 CHECK(source_call_count >= 0),
                target_call_count INTEGER NOT NULL DEFAULT 0 CHECK(target_call_count >= 0),
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                last_counterpart_kind_code INTEGER CHECK(last_counterpart_kind_code IN (1, 2, 3)),
                last_counterpart_id INTEGER CHECK(last_counterpart_id > 0),
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                last_talker_alias TEXT,
                last_talker_alias_seen_ms INTEGER,
                PRIMARY KEY(scope_id, identity_kind_code, identity_id),
                CHECK(
                    (last_counterpart_kind_code IS NULL AND last_counterpart_id IS NULL)
                    OR
                    (last_counterpart_kind_code IS NOT NULL AND last_counterpart_id IS NOT NULL)
                ),
                CHECK(
                    (p25_identity_state_code = 2
                        AND p25_home_wacn BETWEEN 0 AND 1048575
                        AND p25_home_system_id BETWEEN 0 AND 4095
                        AND p25_home_talkgroup_id BETWEEN 1 AND 65534)
                    OR
                    (p25_identity_state_code != 2
                        AND p25_home_wacn IS NULL
                        AND p25_home_system_id IS NULL
                        AND p25_home_talkgroup_id IS NULL)
                )
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_zero_local_fq_talkgroup_summary (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                home_wacn INTEGER NOT NULL CHECK(home_wacn BETWEEN 0 AND 1048575),
                home_system_id INTEGER NOT NULL CHECK(home_system_id BETWEEN 0 AND 4095),
                home_talkgroup_id INTEGER NOT NULL CHECK(home_talkgroup_id BETWEEN 1 AND 65534),
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                PRIMARY KEY(scope_id, home_wacn, home_system_id, home_talkgroup_id)
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_radio_talkgroup_summary (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(radio_id > 0),
                talkgroup_id INTEGER NOT NULL CHECK(talkgroup_id > 0),
                target_kind_code INTEGER NOT NULL CHECK(target_kind_code IN (1, 3)),
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                PRIMARY KEY(scope_id, radio_id, talkgroup_id, target_kind_code)
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_radio_affiliation (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(radio_id > 0),
                talkgroup_id INTEGER NOT NULL CHECK(talkgroup_id > 0),
                confirmed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(scope_id, radio_id)
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_radio_site_presence (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(radio_id > 0),
                context_id INTEGER NOT NULL
                    REFERENCES trunked_identity_scope_context(context_id) ON DELETE CASCADE,
                evidence_code INTEGER NOT NULL CHECK(evidence_code IN (1, 2)),
                confirmed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(scope_id, radio_id)
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_radio_presence_lifecycle (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(radio_id > 0),
                cleared_at_ms INTEGER NOT NULL,
                PRIMARY KEY(scope_id, radio_id)
            ) WITHOUT ROWID
            """);

        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_identity_scope_context_scope
            ON trunked_identity_scope_context(scope_id, context_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_identity_scope_kind_last_seen
            ON trunked_identity_summary(scope_id, identity_kind_code, last_seen_ms DESC, identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_identity_retention
            ON trunked_identity_summary(last_seen_ms, scope_id, identity_kind_code, identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_zero_local_fq_scope_last_seen
            ON p25_zero_local_fq_talkgroup_summary(
                scope_id, last_seen_ms DESC, home_wacn, home_system_id, home_talkgroup_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_zero_local_fq_retention
            ON p25_zero_local_fq_talkgroup_summary(
                last_seen_ms, scope_id, home_wacn, home_system_id, home_talkgroup_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_talkgroup_reverse
            ON trunked_radio_talkgroup_summary(
                scope_id, talkgroup_id, target_kind_code, last_seen_ms DESC, radio_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_talkgroup_retention
            ON trunked_radio_talkgroup_summary(
                last_seen_ms, scope_id, radio_id, talkgroup_id, target_kind_code
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_affiliation_talkgroup
            ON trunked_radio_affiliation(scope_id, talkgroup_id, confirmed_at_ms DESC, radio_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_affiliation_retention
            ON trunked_radio_affiliation(confirmed_at_ms, scope_id, radio_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_site_presence_context
            ON trunked_radio_site_presence(context_id, confirmed_at_ms DESC, scope_id, radio_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_site_presence_retention
            ON trunked_radio_site_presence(confirmed_at_ms, scope_id, radio_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_presence_lifecycle_retention
            ON trunked_radio_presence_lifecycle(cleared_at_ms, scope_id, radio_id)
            """);
    }

    private static String actionCountDefinitions()
    {
        return ACTION_COUNT_COLUMNS.stream()
            .map(column -> column + " INTEGER NOT NULL DEFAULT 0 CHECK(" + column + " >= 0)")
            .collect(java.util.stream.Collectors.joining(",\n                    "));
    }
}
