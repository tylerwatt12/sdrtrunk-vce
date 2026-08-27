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

import java.sql.Statement;

/** Frozen format-3 DDL for the identity objects replaced by format 4. */
final class Format3SchemaSql
{
    private static final String ACTION_COUNTS = """
        acknowledge_count INTEGER NOT NULL DEFAULT 0 CHECK(acknowledge_count >= 0),
        active_count INTEGER NOT NULL DEFAULT 0 CHECK(active_count >= 0),
        busy_count INTEGER NOT NULL DEFAULT 0 CHECK(busy_count >= 0),
        call_count INTEGER NOT NULL DEFAULT 0 CHECK(call_count >= 0),
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

    private Format3SchemaSql()
    {
    }

    static void replaceTrunkedIdentitySchema(Statement statement) throws Exception
    {
        statement.executeUpdate("DROP TABLE trunked_radio_site_presence");
        statement.executeUpdate("DROP TABLE trunked_radio_presence_lifecycle");
        statement.executeUpdate("DROP TABLE trunked_radio_affiliation");
        statement.executeUpdate("DROP TABLE trunked_identity_scope_context");
        statement.executeUpdate("DROP TABLE trunked_identity_summary");
        statement.executeUpdate("DROP TABLE p25_zero_local_fq_talkgroup_summary");
        statement.executeUpdate("DROP TABLE trunked_radio_talkgroup_summary");
        statement.executeUpdate("DROP TABLE trunked_identity_scope");

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
            """.formatted(ACTION_COUNTS));
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
            """.formatted(ACTION_COUNTS));
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
            """.formatted(ACTION_COUNTS));
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
}
