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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Immutable exact global-format 3 to 4 migration. */
final class Format3To4DatabaseMigration implements DatabaseMigrationStep
{
    private static final List<String> CONFIGURATION_TABLES = List.of(
        "alias_list", "alias", "scan_list", "alias_scan_list_membership",
        "alias_list_unmatched_talkgroup_scan_list_membership", "alias_broadcast_channel",
        "alias_list_unmatched_talkgroup_stream", "configuration_channel", "configuration_channel_map",
        "configuration_broadcast_stream", "application_settings", "application_icons");

    /** Receiver-derived tables whose format-3 definitions and semantics remain exact in format 4. */
    private static final List<String> PRESERVED_DERIVED_TABLES = List.of(
        "activity_event_talkgroup_member",
        "dmr_conventional_radio_summary",
        "dmr_conventional_talkgroup_summary",
        "trunked_site_channel_summary",
        "trunked_site_neighbor_summary",
        "p25_activity_event",
        "p25_site_channel_tag",
        "p25_site_channel",
        "p25_site_channel_tag_summary",
        "p25_site_channel_summary",
        "p25_site_frequency_band",
        "p25_site_frequency_band_summary",
        "p25_foreign_system_band",
        "p25_foreign_system_band_summary",
        "p25_site_neighbor",
        "p25_site_neighbor_summary",
        "p25_site_patch_group_radio",
        "p25_site_patch_group_talkgroup",
        "p25_site_patch_group",
        "p25_site_patch_group_radio_summary",
        "p25_site_patch_group_talkgroup_summary",
        "p25_site_patch_group_summary",
        "conventional_activity_bucket",
        "conventional_activity_summary",
        "p25_control_channel_quality",
        "logger_status",
        "p25_site_snapshot",
        "trunked_site_snapshot",
        "receiver_context",
        "p25_system"
    );

    /** Format-3 physical receiver-leg projections which cannot be translated into logical-call observations. */
    private static final List<String> PHYSICAL_CALL_TABLES = List.of(
        "call_identity_bucket",
        "p25_site_frequency_summary",
        "p25_site_talkgroup_bucket",
        "p25_site_activity_bucket"
    );

    /** Format-3 identity evidence whose scope and call-counter meanings change in format 4. */
    private static final List<String> IDENTITY_TABLES_IN_DROP_ORDER = List.of(
        "trunked_radio_site_presence",
        "trunked_radio_presence_lifecycle",
        "trunked_radio_affiliation",
        "trunked_identity_scope_context",
        "trunked_identity_summary",
        "p25_zero_local_fq_talkgroup_summary",
        "trunked_radio_talkgroup_summary",
        "trunked_identity_scope"
    );

    private static final String P25_SCHEMA_VERSION_KEY = "p25_activity_schema_version";
    private static final String OLD_P25_OUTPUT_BOUNDARY_KEY = "p25_call_output_metrics_started_at_ms";
    private static final String OLD_ALL_MODE_OUTPUT_BOUNDARY_KEY = "all_mode_call_output_metrics_started_at_ms";
    private static final String CONVENTIONAL_OUTPUT_BOUNDARY_KEY =
        "conventional_call_output_metrics_started_at_ms";
    private static final String TRUNKED_IDENTITY_BOUNDARY_KEY = "trunked_identity_metrics_started_at_ms";
    private static final String LOGICAL_CALL_BOUNDARY_KEY = "trunked_logical_call_metrics_started_at_ms";

    @Override
    public String id()
    {
        return "format-3-to-4";
    }

    @Override
    public String description()
    {
        return "Add logical-call and P25 site-observation statistics while preserving compatible history";
    }

    @Override
    public int sourceVersion()
    {
        return 3;
    }

    @Override
    public int targetVersion()
    {
        return 4;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        long unknown = DatabaseMigrationEffect.UNKNOWN_COUNT;
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "administrator configuration", unknown,
                "Preserve aliases, scan lists, channels, streams, settings, and icons"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "compatible receiver history", unknown,
                "Preserve detailed P25 activity, site topology, control quality, conventional and DMR summaries, " +
                    "trunked-site summaries, systems, and receiver contexts"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "receiver-context Alias List identities", unknown,
                "Add alias_list_id and recover it from the exact case-insensitive Alias List name"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "conventional call-identity buckets", unknown,
                "Copy nontrunked rows unchanged into the renamed format-4 conventional identity table"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "trunked non-CALL signaling buckets", unknown,
                "Copy all non-CALL action counters unchanged into the format-4 signaling table"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "physical receiver-leg call projections", unknown,
                "Reset legacy frequency, talkgroup, and physical CALL measures before starting logical-call " +
                    "and site-observation summaries"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "trunked identity evidence", unknown,
                "Reset identity scopes, summaries, relationships, affiliations, and presence because format 4 " +
                    "changes scope ownership and call-counter semantics")
        );
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "administrator configuration", rowCount(connection, CONFIGURATION_TABLES),
                "Preserve aliases, scan lists, channels, streams, settings, and icons"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "compatible receiver history", rowCount(connection, PRESERVED_DERIVED_TABLES),
                "Preserve detailed P25 activity, site topology, control quality, conventional and DMR summaries, " +
                    "trunked-site summaries, systems, and receiver contexts"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "receiver-context Alias List identities", recoverableAliasListCount(connection),
                "Add alias_list_id and recover it from the exact case-insensitive Alias List name"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "conventional call-identity buckets", conventionalCallIdentityCount(connection),
                "Copy nontrunked rows unchanged into the renamed format-4 conventional identity table"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "trunked non-CALL signaling buckets", scalarLong(connection,
                    "SELECT COUNT(*) FROM p25_site_activity_bucket"),
                "Copy all non-CALL action counters unchanged into the format-4 signaling table"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "physical receiver-leg call projections", physicalCallResetCount(connection),
                "Reset trunked call-identity rows, legacy frequency/talkgroup rows, and only the physical CALL " +
                    "measures in site-activity rows whose non-CALL signaling is copied"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "trunked identity evidence", rowCount(connection, IDENTITY_TABLES_IN_DROP_ORDER),
                "Reset identity scopes, summaries, relationships, affiliations, and presence because format 4 " +
                    "changes scope ownership and call-counter semantics")
        );
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);

        try(Statement statement = connection.createStatement())
        {
            for(String table: IDENTITY_TABLES_IN_DROP_ORDER)
            {
                statement.executeUpdate("DROP TABLE " + table);
            }

            //Format-4 fresh DDL deliberately places this new nullable field last so ALTER produces the exact target.
            statement.executeUpdate("ALTER TABLE receiver_context ADD COLUMN alias_list_id INTEGER");
            statement.executeUpdate("""
                UPDATE receiver_context
                SET alias_list_id = (
                    SELECT alias_list.id
                    FROM alias_list
                    WHERE alias_list.name = receiver_context.alias_list_name COLLATE NOCASE
                )
                WHERE alias_list_name IS NOT NULL
                  AND length(trim(alias_list_name)) > 0
                  AND 1 = (
                      SELECT COUNT(*)
                      FROM alias_list
                      WHERE alias_list.name = receiver_context.alias_list_name COLLATE NOCASE
                  )
                """);
            Format4SchemaSql.create(statement);
            statement.executeUpdate("""
                INSERT INTO conventional_call_identity_bucket(
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id,
                    call_count, encrypted_count, recorded_count, streamed_count
                )
                SELECT source.context_id, source.bucket_start_ms, source.identity_role_code,
                       source.identity_kind_code, source.identity_id, source.call_count,
                       source.encrypted_count, source.recorded_count, source.streamed_count
                FROM call_identity_bucket AS source
                JOIN receiver_context AS context ON context.id = source.context_id
                WHERE context.kind_code <> 1
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_signaling_activity_bucket(
                    context_id, bucket_start_ms,
                    acknowledge_count, active_count, busy_count, check_count, check_ack_count,
                    continue_count, data_count, denial_count, emergency_count, gps_count, grant_count,
                    join_count, logout_count, page_count, patch_count, patch_cancel_count,
                    patch_create_count, queued_count, register_count, request_count, status_count, unknown_count
                )
                SELECT context_id, bucket_start_ms,
                       acknowledge_count, active_count, busy_count, check_count, check_ack_count,
                       continue_count, data_count, denial_count, emergency_count, gps_count, grant_count,
                       join_count, logout_count, page_count, patch_count, patch_cancel_count,
                       patch_create_count, queued_count, register_count, request_count, status_count, unknown_count
                FROM p25_site_activity_bucket
                """);

            for(String table: PHYSICAL_CALL_TABLES)
            {
                statement.executeUpdate("DROP TABLE " + table);
            }
        }

        long conventionalBoundary = Math.max(
            positiveLongMetadata(connection, OLD_P25_OUTPUT_BOUNDARY_KEY),
            positiveLongMetadata(connection, OLD_ALL_MODE_OUTPUT_BOUNDARY_KEY));
        long now = System.currentTimeMillis();
        deleteMetadata(connection, OLD_P25_OUTPUT_BOUNDARY_KEY);
        deleteMetadata(connection, OLD_ALL_MODE_OUTPUT_BOUNDARY_KEY);
        setMetadata(connection, P25_SCHEMA_VERSION_KEY, "28", now);
        setMetadata(connection, CONVENTIONAL_OUTPUT_BOUNDARY_KEY, Long.toString(conventionalBoundary), now);
        setMetadata(connection, TRUNKED_IDENTITY_BOUNDARY_KEY, Long.toString(now), now);
        setMetadata(connection, LOGICAL_CALL_BOUNDARY_KEY, Long.toString(now), now);
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);

        if(detected.version() != 3)
        {
            throw new SQLException("Migration step format-3-to-4 requires exact source format 3; found " +
                detected.version() + " [" + detected.id() + "]");
        }
    }

    private static long recoverableAliasListCount(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COUNT(*)
            FROM receiver_context AS context
            WHERE context.alias_list_name IS NOT NULL
              AND length(trim(context.alias_list_name)) > 0
              AND 1 = (
                  SELECT COUNT(*)
                  FROM alias_list
                  WHERE alias_list.name = context.alias_list_name COLLATE NOCASE
              )
            """);
    }

    private static long conventionalCallIdentityCount(Connection connection) throws SQLException
    {
        return scalarLong(connection, """
            SELECT COUNT(*)
            FROM call_identity_bucket AS source
            JOIN receiver_context AS context ON context.id = source.context_id
            WHERE context.kind_code <> 1
            """);
    }

    private static long physicalCallResetCount(Connection connection) throws SQLException
    {
        return Math.addExact(
            scalarLong(connection, """
                SELECT COUNT(*)
                FROM call_identity_bucket AS source
                JOIN receiver_context AS context ON context.id = source.context_id
                WHERE context.kind_code = 1
                """),
            rowCount(connection, List.of(
                "p25_site_frequency_summary", "p25_site_talkgroup_bucket", "p25_site_activity_bucket")));
    }

    private static long rowCount(Connection connection, List<String> tables) throws SQLException
    {
        long count = 0;

        for(String table: tables)
        {
            count = Math.addExact(count, scalarLong(connection, "SELECT COUNT(*) FROM " + table));
        }

        return count;
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static long positiveLongMetadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT value FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    String value = resultSet.getString(1);

                    try
                    {
                        long parsed = Long.parseLong(value);
                        if(parsed > 0)
                        {
                            return parsed;
                        }
                    }
                    catch(NumberFormatException e)
                    {
                        throw new SQLException("Format 3 metadata [" + key + "] is not a positive timestamp", e);
                    }
                }
            }
        }

        throw new SQLException("Format 3 metadata [" + key + "] is missing or not a positive timestamp");
    }

    private static void deleteMetadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, key);
            statement.executeUpdate();
        }
    }

    private static void setMetadata(Connection connection, String key, String value, long now) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata(key, value, updated_at_ms)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
                value = excluded.value,
                updated_at_ms = excluded.updated_at_ms
            """))
        {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }
}
