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

/**
 * Frozen format-4 DDL used only by the immutable format 3 to 4 migration.
 *
 * <p>Do not replace these definitions with calls to the mutable current-schema builders. A later global format must
 * add another adjacent migration instead of changing this historical target.</p>
 */
final class Format4SchemaSql
{
    private static final String SIGNALING_ACTION_COUNTS = """
        acknowledge_count INTEGER NOT NULL DEFAULT 0 CHECK(acknowledge_count >= 0),
        active_count INTEGER NOT NULL DEFAULT 0 CHECK(active_count >= 0),
        busy_count INTEGER NOT NULL DEFAULT 0 CHECK(busy_count >= 0),
        check_count INTEGER NOT NULL DEFAULT 0 CHECK(check_count >= 0),
        check_ack_count INTEGER NOT NULL DEFAULT 0 CHECK(check_ack_count >= 0),
        continue_count INTEGER NOT NULL DEFAULT 0 CHECK(continue_count >= 0),
        data_count INTEGER NOT NULL DEFAULT 0 CHECK(data_count >= 0),
        denial_count INTEGER NOT NULL DEFAULT 0 CHECK(denial_count >= 0),
        emergency_count INTEGER NOT NULL DEFAULT 0 CHECK(emergency_count >= 0),
        gps_count INTEGER NOT NULL DEFAULT 0 CHECK(gps_count >= 0),
        grant_count INTEGER NOT NULL DEFAULT 0 CHECK(grant_count >= 0),
        join_count INTEGER NOT NULL DEFAULT 0 CHECK(join_count >= 0),
        logout_count INTEGER NOT NULL DEFAULT 0 CHECK(logout_count >= 0),
        page_count INTEGER NOT NULL DEFAULT 0 CHECK(page_count >= 0),
        patch_count INTEGER NOT NULL DEFAULT 0 CHECK(patch_count >= 0),
        patch_cancel_count INTEGER NOT NULL DEFAULT 0 CHECK(patch_cancel_count >= 0),
        patch_create_count INTEGER NOT NULL DEFAULT 0 CHECK(patch_create_count >= 0),
        queued_count INTEGER NOT NULL DEFAULT 0 CHECK(queued_count >= 0),
        register_count INTEGER NOT NULL DEFAULT 0 CHECK(register_count >= 0),
        request_count INTEGER NOT NULL DEFAULT 0 CHECK(request_count >= 0),
        status_count INTEGER NOT NULL DEFAULT 0 CHECK(status_count >= 0),
        unknown_count INTEGER NOT NULL DEFAULT 0 CHECK(unknown_count >= 0)
        """;

    private Format4SchemaSql()
    {
    }

    static void create(Statement statement) throws SQLException
    {
        createTrunkedIdentityTables(statement);
        createLogicalCallTables(statement);
        createIndexes(statement);
    }

    private static void createTrunkedIdentityTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_identity_scope (
                scope_id INTEGER PRIMARY KEY AUTOINCREMENT,
                scope_token TEXT NOT NULL UNIQUE,
                protocol_code INTEGER NOT NULL CHECK(protocol_code IN (1, 3, 4)),
                scope_kind_code INTEGER NOT NULL CHECK(scope_kind_code IN (1, 2)),
                identity_domain_code INTEGER NOT NULL DEFAULT 0 CHECK(identity_domain_code IN (0, 1, 2)),
                alias_list_id INTEGER,
                p25_system_key INTEGER REFERENCES p25_system(system_key) ON DELETE CASCADE,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                UNIQUE(p25_system_key, alias_list_id),
                CHECK(
                    (scope_kind_code = 1 AND protocol_code = 1 AND p25_system_key IS NOT NULL
                        AND alias_list_id IS NOT NULL AND alias_list_id > 0)
                    OR
                    (scope_kind_code = 2 AND protocol_code IN (1, 3, 4) AND p25_system_key IS NULL
                        AND alias_list_id IS NULL)
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
                logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(logical_call_count >= 0),
                source_logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(source_logical_call_count >= 0),
                target_logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(target_logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_output_count >= 0),
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
            """.formatted(SIGNALING_ACTION_COUNTS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_zero_local_fq_talkgroup_summary (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                home_wacn INTEGER NOT NULL CHECK(home_wacn BETWEEN 0 AND 1048575),
                home_system_id INTEGER NOT NULL CHECK(home_system_id BETWEEN 0 AND 4095),
                home_talkgroup_id INTEGER NOT NULL CHECK(home_talkgroup_id BETWEEN 1 AND 65534),
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_output_count >= 0),
                PRIMARY KEY(scope_id, home_wacn, home_system_id, home_talkgroup_id)
            ) WITHOUT ROWID
            """.formatted(SIGNALING_ACTION_COUNTS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_radio_talkgroup_summary (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(radio_id > 0),
                talkgroup_id INTEGER NOT NULL CHECK(talkgroup_id > 0),
                target_kind_code INTEGER NOT NULL CHECK(target_kind_code IN (1, 3)),
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_output_count >= 0),
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                PRIMARY KEY(scope_id, radio_id, talkgroup_id, target_kind_code)
            ) WITHOUT ROWID
            """.formatted(SIGNALING_ACTION_COUNTS));
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
    }

    private static void createLogicalCallTables(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_learned_site (
                learned_site_id INTEGER PRIMARY KEY,
                system_key INTEGER NOT NULL REFERENCES p25_system(system_key) ON DELETE CASCADE,
                rfss INTEGER NOT NULL CHECK(rfss BETWEEN 0 AND 255),
                site INTEGER NOT NULL CHECK(site BETWEEN 0 AND 255),
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                UNIQUE(system_key, rfss, site)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_logical_call_bucket (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL,
                logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_output_count >= 0),
                PRIMARY KEY(scope_id, bucket_start_ms)
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_logical_call_identity_bucket (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL,
                identity_role_code INTEGER NOT NULL CHECK(identity_role_code IN (1, 2)),
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (0, 1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id >= 0),
                logical_call_count INTEGER NOT NULL DEFAULT 0 CHECK(logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_output_count >= 0),
                PRIMARY KEY(
                    scope_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
                ),
                CHECK(
                    (identity_kind_code = 0 AND identity_id = 0)
                    OR (identity_kind_code IN (1, 2, 3) AND identity_id > 0)
                ),
                CHECK(
                    identity_role_code = 1
                    OR (identity_role_code = 2 AND identity_kind_code = 2 AND identity_id > 0)
                )
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_call_bucket (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                learned_site_id INTEGER NOT NULL REFERENCES p25_learned_site(learned_site_id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL,
                observed_call_count INTEGER NOT NULL DEFAULT 0 CHECK(observed_call_count >= 0),
                encrypted_observed_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(encrypted_observed_call_count >= 0),
                PRIMARY KEY(scope_id, learned_site_id, bucket_start_ms)
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_site_call_identity_bucket (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                learned_site_id INTEGER NOT NULL REFERENCES p25_learned_site(learned_site_id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL,
                identity_role_code INTEGER NOT NULL CHECK(identity_role_code IN (1, 2)),
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (0, 1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id >= 0),
                observed_call_count INTEGER NOT NULL DEFAULT 0 CHECK(observed_call_count >= 0),
                encrypted_observed_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(encrypted_observed_call_count >= 0),
                PRIMARY KEY(
                    scope_id, learned_site_id, bucket_start_ms,
                    identity_role_code, identity_kind_code, identity_id
                ),
                CHECK(
                    (identity_kind_code = 0 AND identity_id = 0)
                    OR (identity_kind_code IN (1, 2, 3) AND identity_id > 0)
                ),
                CHECK(
                    identity_role_code = 1
                    OR (identity_role_code = 2 AND identity_kind_code = 2 AND identity_id > 0)
                )
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS trunked_signaling_activity_bucket (
                context_id INTEGER NOT NULL REFERENCES receiver_context(id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL,
                %s,
                PRIMARY KEY(context_id, bucket_start_ms)
            ) WITHOUT ROWID
            """.formatted(SIGNALING_ACTION_COUNTS));
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS conventional_call_identity_bucket (
                context_id INTEGER NOT NULL REFERENCES receiver_context(id) ON DELETE CASCADE,
                bucket_start_ms INTEGER NOT NULL,
                identity_role_code INTEGER NOT NULL CHECK(identity_role_code IN (1, 2)),
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (0, 1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id >= 0),
                call_count INTEGER NOT NULL DEFAULT 0 CHECK(call_count >= 0),
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                PRIMARY KEY (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
                ),
                CHECK (
                    (identity_kind_code = 0 AND identity_id = 0)
                    OR (identity_kind_code IN (1, 2, 3) AND identity_id > 0)
                ),
                CHECK (
                    identity_role_code = 1
                    OR (identity_role_code = 2 AND identity_kind_code = 2 AND identity_id > 0)
                )
            ) WITHOUT ROWID
            """);
    }

    private static void createIndexes(Statement statement) throws SQLException
    {
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
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_signaling_activity_time
            ON trunked_signaling_activity_bucket(bucket_start_ms, context_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_logical_call_bucket_time
            ON trunked_logical_call_bucket(bucket_start_ms, scope_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_logical_identity_dashboard_time
            ON trunked_logical_call_identity_bucket(
                bucket_start_ms, identity_role_code, identity_kind_code, scope_id, identity_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_site_call_bucket_time
            ON p25_site_call_bucket(bucket_start_ms, scope_id, learned_site_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_site_call_identity_time
            ON p25_site_call_identity_bucket(
                learned_site_id, scope_id, bucket_start_ms,
                identity_role_code, identity_kind_code, identity_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_site_call_identity_retention
            ON p25_site_call_identity_bucket(
                bucket_start_ms, scope_id, learned_site_id,
                identity_role_code, identity_kind_code, identity_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_conventional_call_identity_dashboard_time
            ON conventional_call_identity_bucket(
                bucket_start_ms, identity_role_code, identity_kind_code, context_id, identity_id
            )
            """);
    }
}
