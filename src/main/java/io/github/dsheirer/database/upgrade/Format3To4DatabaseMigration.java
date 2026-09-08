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

    /** Format-3 physical receiver-leg tables replaced by the logical-call format-4 schema. */
    private static final List<String> REPLACED_PHYSICAL_CALL_TABLES = List.of(
        "call_identity_bucket",
        "p25_site_frequency_summary",
        "p25_site_talkgroup_bucket",
        "p25_site_activity_bucket"
    );

    /** Format-3 identity tables replaced by the format-4 schema. */
    private static final List<String> REPLACED_IDENTITY_TABLES_IN_DROP_ORDER = List.of(
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
        return "Add logical-call and P25 site-observation statistics at a clean activity boundary";
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
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "receiver-derived activity and counters", unknown,
                "Restart calls, signaling, identities, site observations, and quality history from zero")
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
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "receiver-derived activity and counters",
                LegacyActivityReset.count(connection, LegacyActivityReset.PRE_LOGICAL_CALL_TABLES),
                "Restart calls, signaling, identities, site observations, and quality history from zero")
        );
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        LegacyActivityReset.clear(connection, LegacyActivityReset.PRE_LOGICAL_CALL_TABLES);

        try(Statement statement = connection.createStatement())
        {
            for(String table: REPLACED_IDENTITY_TABLES_IN_DROP_ORDER)
            {
                statement.executeUpdate("DROP TABLE " + table);
            }

            //Format-4 fresh DDL deliberately places this new nullable field last so ALTER produces the exact target.
            statement.executeUpdate("ALTER TABLE receiver_context ADD COLUMN alias_list_id INTEGER");
            Format4SchemaSql.create(statement);

            for(String table: REPLACED_PHYSICAL_CALL_TABLES)
            {
                statement.executeUpdate("DROP TABLE " + table);
            }
        }

        long now = System.currentTimeMillis();
        deleteMetadata(connection, OLD_P25_OUTPUT_BOUNDARY_KEY);
        deleteMetadata(connection, OLD_ALL_MODE_OUTPUT_BOUNDARY_KEY);
        setMetadata(connection, P25_SCHEMA_VERSION_KEY, "28", now);
        setMetadata(connection, CONVENTIONAL_OUTPUT_BOUNDARY_KEY, Long.toString(now), now);
        setMetadata(connection, TRUNKED_IDENTITY_BOUNDARY_KEY, Long.toString(now), now);
        setMetadata(connection, LOGICAL_CALL_BOUNDARY_KEY, Long.toString(now), now);
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspectForMigration(connection);

        if(detected.version() != 3)
        {
            throw new SQLException("Migration step format-3-to-4 requires exact source format 3; found " +
                detected.version() + " [" + detected.id() + "]");
        }
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
