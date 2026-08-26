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

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Exact shared schema emitted by published v0.6.2 Alpha 8, Alpha 9, and Alpha 10, for release-boundary tests only. */
public final class Alpha9TestDatabase
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
    private static final String PREDECESSOR_METRIC_ACTION_COUNTS = """
        acknowledge_count INTEGER NOT NULL DEFAULT 0,
        active_count INTEGER NOT NULL DEFAULT 0,
        busy_count INTEGER NOT NULL DEFAULT 0,
        call_count INTEGER NOT NULL DEFAULT 0,
        check_count INTEGER NOT NULL DEFAULT 0,
        check_ack_count INTEGER NOT NULL DEFAULT 0,
        continue_count INTEGER NOT NULL DEFAULT 0,
        data_count INTEGER NOT NULL DEFAULT 0,
        denial_count INTEGER NOT NULL DEFAULT 0,
        emergency_count INTEGER NOT NULL DEFAULT 0,
        gps_count INTEGER NOT NULL DEFAULT 0,
        grant_count INTEGER NOT NULL DEFAULT 0,
        join_count INTEGER NOT NULL DEFAULT 0,
        logout_count INTEGER NOT NULL DEFAULT 0,
        page_count INTEGER NOT NULL DEFAULT 0,
        patch_count INTEGER NOT NULL DEFAULT 0,
        patch_cancel_count INTEGER NOT NULL DEFAULT 0,
        patch_create_count INTEGER NOT NULL DEFAULT 0,
        queued_count INTEGER NOT NULL DEFAULT 0,
        register_count INTEGER NOT NULL DEFAULT 0,
        request_count INTEGER NOT NULL DEFAULT 0,
        status_count INTEGER NOT NULL DEFAULT 0,
        unknown_count INTEGER NOT NULL DEFAULT 0
        """;

    private Alpha9TestDatabase()
    {
    }

    public static Path create(Path database) throws Exception
    {
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);

            try
            {
                replaceAliasSchema(statement);
                replaceLogicalCallStatisticsSchema(statement);
                replaceTrunkedIdentitySchema(statement);
                replaceP25SiteProjectionSchema(statement);
                replaceLogicalCallStatisticsMetadata(statement);
                statement.executeUpdate("UPDATE database_metadata SET value='4' WHERE key='alias_schema_version'");
                statement.executeUpdate(
                    "UPDATE database_metadata SET value='24' WHERE key='p25_activity_schema_version'");
                statement.executeUpdate(
                    "DELETE FROM database_metadata WHERE key='initial_admin_setup'");
                connection.commit();
            }
            catch(Exception e)
            {
                connection.rollback();
                throw e;
            }
            finally
            {
                connection.setAutoCommit(true);
                statement.execute("PRAGMA foreign_keys=ON");
            }

            String fingerprint = SqliteSchemaValidator.fingerprint(connection);

            if(!Alpha9DatabaseMigration.SOURCE_SCHEMA_FINGERPRINT.equals(fingerprint))
            {
                throw new IllegalStateException(
                    "Alpha 8/Alpha 9/Alpha 10 fixture fingerprint mismatch: " + fingerprint);
            }
        }

        return database;
    }

    /**
     * Reverses the unreleased logical-call statistics rewrite so this fixture remains the exact public-release
     * source, rather than a hybrid of the source metadata and current-main tables.
     */
    private static void replaceLogicalCallStatisticsSchema(Statement statement) throws Exception
    {
        statement.executeUpdate("DROP TABLE p25_site_call_identity_bucket");
        statement.executeUpdate("DROP TABLE p25_site_call_bucket");
        statement.executeUpdate("DROP TABLE trunked_logical_call_identity_bucket");
        statement.executeUpdate("DROP TABLE trunked_logical_call_bucket");
        statement.executeUpdate("DROP TABLE trunked_signaling_activity_bucket");
        statement.executeUpdate("DROP TABLE conventional_call_identity_bucket");
        statement.executeUpdate("DROP TABLE p25_learned_site");
        statement.executeUpdate("ALTER TABLE receiver_context DROP COLUMN alias_list_id");
        statement.executeUpdate("""
            CREATE TABLE p25_site_frequency_summary (
                context_id INTEGER NOT NULL,
                frequency_hz INTEGER NOT NULL,
                timeslot INTEGER NOT NULL DEFAULT -1,
                lcn_band INTEGER,
                lcn_number INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                last_source_radio_id INTEGER,
                last_target_id INTEGER,
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                PRIMARY KEY(context_id, frequency_hz, timeslot)
            )
            """.formatted(PREDECESSOR_METRIC_ACTION_COUNTS));
        statement.executeUpdate("""
            CREATE TABLE p25_site_talkgroup_bucket (
                context_id INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                bucket_start_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                recorded_count INTEGER NOT NULL DEFAULT 0,
                streamed_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(context_id, talkgroup_id, bucket_start_ms)
            )
            """.formatted(PREDECESSOR_METRIC_ACTION_COUNTS));
        statement.executeUpdate("""
            CREATE TABLE p25_site_activity_bucket (
                context_id INTEGER NOT NULL,
                bucket_start_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0,
                recorded_count INTEGER NOT NULL DEFAULT 0,
                streamed_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(context_id, bucket_start_ms)
            )
            """.formatted(PREDECESSOR_METRIC_ACTION_COUNTS));
        statement.executeUpdate("""
            CREATE TABLE call_identity_bucket (
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
        statement.executeUpdate("""
            CREATE INDEX idx_p25_site_talkgroup_bucket_time
            ON p25_site_talkgroup_bucket(context_id, bucket_start_ms)
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_p25_site_talkgroup_bucket_talkgroup_time
            ON p25_site_talkgroup_bucket(talkgroup_id, bucket_start_ms)
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_p25_site_activity_bucket_time
            ON p25_site_activity_bucket(bucket_start_ms)
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_call_identity_bucket_dashboard_time
            ON call_identity_bucket(
                bucket_start_ms, identity_role_code, identity_kind_code, context_id, identity_id
            )
            """);
    }

    private static void replaceLogicalCallStatisticsMetadata(Statement statement) throws Exception
    {
        statement.executeUpdate("""
            DELETE FROM database_metadata
            WHERE key IN (
                'conventional_call_output_metrics_started_at_ms',
                'trunked_logical_call_metrics_started_at_ms'
            )
            """);
        statement.executeUpdate("""
            INSERT OR REPLACE INTO database_metadata(key, value, updated_at_ms)
            VALUES ('p25_call_output_metrics_started_at_ms', '1', 1),
                   ('all_mode_call_output_metrics_started_at_ms', '1', 1)
            """);
    }

    private static void replaceAliasSchema(Statement statement) throws Exception
    {
        statement.executeUpdate("DROP VIEW alias_talkgroup");
        statement.executeUpdate("DROP VIEW alias_radio");
        statement.executeUpdate("DROP TABLE alias_list_unmatched_talkgroup_scan_list_membership");
        statement.executeUpdate("DROP TABLE alias_scan_list_membership");
        statement.executeUpdate("DROP TABLE scan_list");
        statement.executeUpdate("DROP TABLE alias_list_unmatched_talkgroup_stream");
        statement.executeUpdate("DROP TABLE alias_broadcast_channel");
        statement.executeUpdate("DROP TABLE alias");
        statement.executeUpdate("DROP TABLE alias_list");
        statement.executeUpdate("""
            CREATE TABLE alias_list (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE,
                family TEXT NOT NULL CHECK(family IN ('P25', 'DMR', 'NXDN', 'NBFM')),
                UNIQUE(name)
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE alias (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                alias_list_id INTEGER NOT NULL REFERENCES alias_list(id) ON DELETE RESTRICT,
                name TEXT,
                description TEXT,
                group_name TEXT,
                color INTEGER NOT NULL DEFAULT 0,
                icon_name TEXT,
                stream_as_talkgroup INTEGER,
                record_enabled INTEGER NOT NULL DEFAULT 0,
                priority INTEGER,
                matcher_type TEXT NOT NULL CHECK(matcher_type IN (
                    'TALKGROUP', 'TALKGROUP_RANGE', 'P25_FULLY_QUALIFIED_TALKGROUP',
                    'RADIO_ID', 'RADIO_ID_RANGE', 'P25_FULLY_QUALIFIED_RADIO_ID',
                    'STATUS', 'UNIT_STATUS', 'TONES', 'DCS', 'ESN'
                )),
                protocol TEXT,
                value INTEGER,
                min_value INTEGER,
                max_value INTEGER,
                wacn INTEGER,
                p25_system_id INTEGER,
                text_value TEXT,
                numeric_value INTEGER,
                tone_sequence TEXT
            )
            """);
        statement.executeUpdate("""
            CREATE TABLE alias_broadcast_channel (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                alias_id INTEGER NOT NULL REFERENCES alias(id) ON DELETE CASCADE,
                channel_name TEXT NOT NULL CHECK(length(trim(channel_name)) > 0),
                UNIQUE(alias_id, channel_name)
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_alias_talkgroup_value
            ON alias(protocol, value, wacn, p25_system_id, alias_list_id, id)
            WHERE matcher_type IN ('TALKGROUP', 'P25_FULLY_QUALIFIED_TALKGROUP')
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_alias_talkgroup_range
            ON alias(protocol, min_value, max_value, alias_list_id, id)
            WHERE matcher_type = 'TALKGROUP_RANGE'
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_alias_radio_value
            ON alias(protocol, value, wacn, p25_system_id, alias_list_id, id)
            WHERE matcher_type IN ('RADIO_ID', 'P25_FULLY_QUALIFIED_RADIO_ID')
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_alias_radio_range
            ON alias(protocol, min_value, max_value, alias_list_id, id)
            WHERE matcher_type = 'RADIO_ID_RANGE'
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_alias_broadcast_channel_name
            ON alias_broadcast_channel(channel_name)
            """);
        statement.executeUpdate("""
            CREATE VIEW alias_talkgroup AS
            SELECT alias.id AS alias_id,
                   alias.protocol,
                   alias.value,
                   alias.min_value,
                   alias.max_value,
                   alias.wacn,
                   alias.p25_system_id AS system_id,
                   CASE WHEN alias.matcher_type = 'P25_FULLY_QUALIFIED_TALKGROUP' THEN 1 ELSE 0 END AS fully_qualified,
                   CASE WHEN alias.matcher_type = 'TALKGROUP_RANGE' THEN 1 ELSE 0 END AS ranged,
                   alias_list.name AS alias_list_name
            FROM alias
            JOIN alias_list ON alias_list.id = alias.alias_list_id
            WHERE alias.matcher_type IN (
                  'TALKGROUP', 'TALKGROUP_RANGE', 'P25_FULLY_QUALIFIED_TALKGROUP'
            )
            """);
        statement.executeUpdate("""
            CREATE VIEW alias_radio AS
            SELECT alias.id AS alias_id,
                   alias.protocol,
                   alias.value,
                   alias.min_value,
                   alias.max_value,
                   alias.wacn,
                   alias.p25_system_id AS system_id,
                   CASE WHEN alias.matcher_type = 'P25_FULLY_QUALIFIED_RADIO_ID' THEN 1 ELSE 0 END AS fully_qualified,
                   CASE WHEN alias.matcher_type = 'RADIO_ID_RANGE' THEN 1 ELSE 0 END AS ranged,
                   alias_list.name AS alias_list_name
            FROM alias
            JOIN alias_list ON alias_list.id = alias.alias_list_id
            WHERE alias.matcher_type IN (
                  'RADIO_ID', 'RADIO_ID_RANGE', 'P25_FULLY_QUALIFIED_RADIO_ID'
            )
            """);
    }

    private static void replaceP25SiteProjectionSchema(Statement statement) throws Exception
    {
        String resolvedViewSql;

        try(ResultSet resultSet = statement.executeQuery("""
            SELECT sql
            FROM sqlite_schema
            WHERE type = 'view' AND name = 'p25_activity_event_resolved'
            """))
        {
            if(!resultSet.next())
            {
                throw new IllegalStateException("Current resolved P25 activity view is missing");
            }

            resolvedViewSql = resultSet.getString(1);
        }

        String currentProjection = "coalesce(ps.system_id, p25.system_id)";
        String predecessorProjection = "ps.system_id";
        int joinStart = resolvedViewSql.indexOf("LEFT JOIN p25_site_snapshot p25");
        int joinPredicate = resolvedViewSql.indexOf(
            "ON p25.guid = rc.guid AND rc.kind_code = 1 AND rc.protocol_code IN (1, 2)", joinStart);

        if(!resolvedViewSql.contains(currentProjection) || joinStart < 0 || joinPredicate < 0)
        {
            throw new IllegalStateException("Current resolved P25 activity projection is not recognized");
        }

        int joinLineStart = resolvedViewSql.lastIndexOf('\n', joinStart) + 1;
        int joinLineEnd = resolvedViewSql.indexOf('\n', joinPredicate);
        joinLineEnd = joinLineEnd < 0 ? resolvedViewSql.length() : joinLineEnd + 1;
        String predecessorViewSql = (resolvedViewSql.substring(0, joinLineStart) +
            resolvedViewSql.substring(joinLineEnd)).replace(currentProjection, predecessorProjection);

        statement.executeUpdate("DROP VIEW p25_activity_event_resolved");
        statement.executeUpdate("ALTER TABLE p25_site_snapshot DROP COLUMN system_id");
        statement.executeUpdate(
            "ALTER TABLE p25_site_snapshot DROP COLUMN active_rfss_network_connection");
        statement.executeUpdate("ALTER TABLE p25_site_channel_summary DROP COLUMN callsign");
        statement.executeUpdate(predecessorViewSql);
    }

    private static void replaceTrunkedIdentitySchema(Statement statement) throws Exception
    {
        statement.executeUpdate("DROP TABLE trunked_radio_presence_lifecycle");
        statement.executeUpdate("DROP TABLE trunked_radio_site_presence");
        statement.executeUpdate("DROP TABLE trunked_radio_affiliation");
        statement.executeUpdate("DROP TABLE p25_zero_local_fq_talkgroup_summary");
        statement.executeUpdate("DROP TABLE trunked_identity_summary");
        statement.executeUpdate("DROP TABLE trunked_radio_talkgroup_summary");
        statement.executeUpdate("DROP TABLE trunked_identity_scope_context");
        statement.executeUpdate("DROP TABLE trunked_identity_scope");
        statement.executeUpdate("""
            CREATE TABLE IF NOT EXISTS p25_radio_affiliation (
                system_key INTEGER NOT NULL,
                radio_id INTEGER NOT NULL,
                talkgroup_id INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(system_key, radio_id)
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE trunked_identity_scope (
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
            CREATE TABLE trunked_identity_scope_context (
                context_id INTEGER PRIMARY KEY REFERENCES receiver_context(id) ON DELETE CASCADE,
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL
            ) WITHOUT ROWID
            """);
        statement.executeUpdate("""
            CREATE TABLE trunked_identity_summary (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id > 0),
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
                )
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNTS));
        statement.executeUpdate("""
            CREATE TABLE trunked_radio_talkgroup_summary (
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
            CREATE INDEX idx_trunked_identity_scope_context_scope
            ON trunked_identity_scope_context(scope_id, context_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_trunked_identity_scope_kind_last_seen
            ON trunked_identity_summary(scope_id, identity_kind_code, last_seen_ms DESC, identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_trunked_identity_retention
            ON trunked_identity_summary(last_seen_ms, scope_id, identity_kind_code, identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_trunked_radio_talkgroup_reverse
            ON trunked_radio_talkgroup_summary(
                scope_id, talkgroup_id, target_kind_code, last_seen_ms DESC, radio_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX idx_trunked_radio_talkgroup_retention
            ON trunked_radio_talkgroup_summary(
                last_seen_ms, scope_id, radio_id, talkgroup_id, target_kind_code
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_radio_affiliation_talkgroup
            ON p25_radio_affiliation(system_key, talkgroup_id, updated_at_ms DESC, radio_id)
            """);
    }
}
