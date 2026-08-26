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

import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Exact shared v0.6.2 Alpha 8/Alpha 9/Alpha 10 layout to current-main database transition.
 *
 * <p>This runs only against the application's backed-up staged copy. It consolidates the complete public-release
 * boundary directly, without accepting or creating any intermediate development schema. Alpha 8, Alpha 9, and Alpha
 * 10 shipped the same schema fingerprint and no release-provenance marker, so an otherwise exact database from any of
 * those releases is intentionally handled by this one structural gate. Administrator-owned configuration is
 * retained, while all derived statistics are discarded and recreated in the current empty layout.</p>
 */
final class Alpha9DatabaseMigration
{
    static final int ALIAS_VERSION = 4;
    static final int P25_VERSION = 24;
    static final int TRUNKED_SITE_VERSION = 2;
    static final int DMR_VERSION = 1;
    static final String SOURCE_SCHEMA_FINGERPRINT =
        "ef9197c7cee7261cdda03a395b6552754f3607f6c0053acbe21c273e4242ce3a";

    private static final Map<String,String> SOURCE_METADATA = Map.of(
        "alias_schema_version", Integer.toString(ALIAS_VERSION),
        "configuration_schema_version", "2",
        "settings_schema_version", "2",
        "icon_schema_version", "2",
        "p25_activity_schema_version", Integer.toString(P25_VERSION),
        "trunked_site_schema_version", Integer.toString(TRUNKED_SITE_VERSION),
        "dmr_activity_schema_version", Integer.toString(DMR_VERSION));
    private static final String FULLY_QUALIFIED_TALKGROUP = "P25_FULLY_QUALIFIED_TALKGROUP";
    private static final String FULLY_QUALIFIED_RADIO = "P25_FULLY_QUALIFIED_RADIO_ID";
    private static final String CALL_OUTPUT_METRICS_STARTED_AT_KEY =
        "p25_call_output_metrics_started_at_ms";
    private static final String ALL_MODE_CALL_OUTPUT_METRICS_STARTED_AT_KEY =
        "all_mode_call_output_metrics_started_at_ms";
    private static final List<String> STATISTICS_TABLES_IN_DROP_ORDER = List.of(
        "activity_event_talkgroup_member",
        "dmr_conventional_radio_summary",
        "dmr_conventional_talkgroup_summary",
        "call_identity_bucket",
        "trunked_identity_scope_context",
        "trunked_identity_summary",
        "trunked_radio_talkgroup_summary",
        "p25_radio_affiliation",
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
        "p25_site_frequency_summary",
        "p25_site_talkgroup_bucket",
        "p25_site_activity_bucket",
        "conventional_activity_bucket",
        "conventional_activity_summary",
        "p25_control_channel_quality",
        "logger_status",
        "trunked_identity_scope",
        "p25_site_snapshot",
        "trunked_site_snapshot",
        "receiver_context",
        "p25_system"
    );
    private static final List<String> STATISTICS_METADATA_KEYS = List.of(
        "p25_activity_schema_version",
        CALL_OUTPUT_METRICS_STARTED_AT_KEY,
        ALL_MODE_CALL_OUTPUT_METRICS_STARTED_AT_KEY,
        P25ActivityLogSchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY,
        P25ActivityLogSchema.TRUNKED_IDENTITY_METRICS_STARTED_AT_KEY,
        P25ActivityLogSchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY,
        DmrActivitySchema.SCHEMA_VERSION_KEY,
        TrunkedSiteSchema.SCHEMA_VERSION_KEY
    );

    private Alpha9DatabaseMigration()
    {
    }

    static void validateSource(Connection connection) throws SQLException
    {
        for(Map.Entry<String,String> metadata: SOURCE_METADATA.entrySet())
        {
            String actual = metadata(connection, metadata.getKey());

            if(!metadata.getValue().equals(actual))
            {
                throw new SQLException("Predecessor database metadata [" + metadata.getKey() + "] is " + actual +
                    "; expected " + metadata.getValue());
            }
        }

        requirePositiveMetadata(connection, CALL_OUTPUT_METRICS_STARTED_AT_KEY);
        requirePositiveMetadata(connection, ALL_MODE_CALL_OUTPUT_METRICS_STARTED_AT_KEY);
        requirePositiveMetadata(connection, P25ActivityLogSchema.TRUNKED_IDENTITY_METRICS_STARTED_AT_KEY);

        String fingerprint = SqliteSchemaValidator.fingerprint(connection);

        if(!SOURCE_SCHEMA_FINGERPRINT.equals(fingerprint))
        {
            throw new SQLException("SQLite database is not the exact shared v0.6.2 Alpha 8/Alpha 9/Alpha 10 " +
                "schema layout (" + fingerprint + ")");
        }
    }

    static Summary migrate(Connection connection) throws SQLException
    {
        int removedTalkgroups = matcherCount(connection, FULLY_QUALIFIED_TALKGROUP);
        int removedRadios = matcherCount(connection, FULLY_QUALIFIED_RADIO);
        int convertedCatchalls = migrateAliases(connection);
        resetStatistics(connection);
        return new Summary(convertedCatchalls, removedTalkgroups, removedRadios);
    }

    private static int migrateAliases(Connection connection) throws SQLException
    {
        int convertedCatchalls;

        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TEMP TABLE alpha9_alias_conversion_candidate (
                    alias_id INTEGER PRIMARY KEY,
                    alias_list_id INTEGER NOT NULL UNIQUE
                )
                """);
            statement.executeUpdate("""
                INSERT INTO alpha9_alias_conversion_candidate (alias_id, alias_list_id)
                SELECT candidate.id, candidate.alias_list_id
                FROM alias AS candidate
                JOIN alias_list AS owning_list ON owning_list.id = candidate.alias_list_id
                WHERE candidate.matcher_type = 'TALKGROUP_RANGE'
                  AND candidate.stream_as_talkgroup IS NULL
                  AND length(trim(COALESCE(candidate.description, ''))) = 0
                  AND length(trim(COALESCE(candidate.group_name, ''))) = 0
                  AND candidate.color = 0
                  AND length(trim(COALESCE(candidate.icon_name, ''))) = 0
                  AND candidate.min_value IN (0, 1)
                  AND (
                      (owning_list.family = 'P25'
                       AND candidate.protocol IN ('APCO25', 'APCO25_PHASE2')
                       AND candidate.max_value = 65535)
                   OR (owning_list.family = 'DMR'
                       AND candidate.protocol = 'DMR'
                       AND candidate.max_value = 16777215)
                   OR (owning_list.family = 'NXDN'
                       AND candidate.protocol = 'NXDN'
                       AND candidate.max_value = 65535)
                  )
                  AND 1 = (
                      SELECT COUNT(*)
                      FROM alias AS possible
                      WHERE possible.alias_list_id = candidate.alias_list_id
                        AND possible.matcher_type = 'TALKGROUP_RANGE'
                        AND possible.min_value IN (0, 1)
                        AND (
                            (owning_list.family = 'P25'
                             AND possible.protocol IN ('APCO25', 'APCO25_PHASE2')
                             AND possible.max_value = 65535)
                         OR (owning_list.family = 'DMR'
                             AND possible.protocol = 'DMR'
                             AND possible.max_value = 16777215)
                         OR (owning_list.family = 'NXDN'
                             AND possible.protocol = 'NXDN'
                             AND possible.max_value = 65535)
                        )
                  )
                """);
            convertedCatchalls = scalar(connection,
                "SELECT COUNT(*) FROM alpha9_alias_conversion_candidate");
            statement.executeUpdate("""
                CREATE TEMP TABLE alpha9_alias_sequence (
                    name TEXT PRIMARY KEY,
                    seq INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                INSERT INTO alpha9_alias_sequence(name, seq)
                SELECT name, seq FROM sqlite_sequence
                WHERE name IN ('alias_list', 'alias', 'alias_broadcast_channel')
                """);

            statement.executeUpdate("DROP VIEW alias_talkgroup");
            statement.executeUpdate("DROP VIEW alias_radio");
            statement.executeUpdate("DROP INDEX idx_alias_talkgroup_value");
            statement.executeUpdate("DROP INDEX idx_alias_talkgroup_range");
            statement.executeUpdate("DROP INDEX idx_alias_radio_value");
            statement.executeUpdate("DROP INDEX idx_alias_radio_range");
            statement.executeUpdate("DROP INDEX idx_alias_broadcast_channel_name");
            statement.executeUpdate(
                "ALTER TABLE alias_broadcast_channel RENAME TO alpha9_alias_broadcast_channel");
            statement.executeUpdate("ALTER TABLE alias RENAME TO alpha9_alias");
            statement.executeUpdate("ALTER TABLE alias_list RENAME TO alpha9_alias_list");
        }

        SdrTrunkDatabaseSchema.create(connection);

        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list (
                    id, name, family, unmatched_talkgroup_record_enabled
                )
                SELECT source.id,
                       source.name,
                       source.family,
                       COALESCE(candidate.record_enabled, 0)
                FROM alpha9_alias_list AS source
                LEFT JOIN alpha9_alias_conversion_candidate AS conversion
                       ON conversion.alias_list_id = source.id
                LEFT JOIN alpha9_alias AS candidate ON candidate.id = conversion.alias_id
                ORDER BY source.id
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list_unmatched_talkgroup_stream (alias_list_id, channel_name)
                SELECT conversion.alias_list_id, route.channel_name
                FROM alpha9_alias_conversion_candidate AS conversion
                JOIN alpha9_alias_broadcast_channel AS route ON route.alias_id = conversion.alias_id
                ORDER BY conversion.alias_list_id, route.id
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, description, group_name, color, icon_name,
                    stream_as_talkgroup, record_enabled, matcher_type, protocol,
                    value, min_value, max_value, text_value, numeric_value, tone_sequence
                )
                SELECT source.id, source.alias_list_id, source.name, source.description, source.group_name,
                       source.color, source.icon_name, source.stream_as_talkgroup, source.record_enabled,
                       source.matcher_type, source.protocol, source.value, source.min_value,
                       source.max_value, source.text_value, source.numeric_value, source.tone_sequence
                FROM alpha9_alias AS source
                LEFT JOIN alpha9_alias_conversion_candidate AS conversion ON conversion.alias_id = source.id
                WHERE conversion.alias_id IS NULL
                  AND source.matcher_type NOT IN (
                      'P25_FULLY_QUALIFIED_TALKGROUP',
                      'P25_FULLY_QUALIFIED_RADIO_ID'
                  )
                ORDER BY source.id
                """);
            statement.executeUpdate("""
                INSERT INTO alias_scan_list_membership (alias_id, scan_list_id)
                SELECT source.id, default_list.id
                FROM alpha9_alias AS source
                JOIN alias AS retained ON retained.id = source.id
                CROSS JOIN scan_list AS default_list
                WHERE default_list.is_default = 1
                  AND COALESCE(source.priority, 100) <> -1
                ORDER BY source.id
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership (
                    alias_list_id, scan_list_id
                )
                SELECT conversion.alias_list_id, default_list.id
                FROM alpha9_alias_conversion_candidate AS conversion
                JOIN alpha9_alias AS source ON source.id = conversion.alias_id
                CROSS JOIN scan_list AS default_list
                WHERE default_list.is_default = 1
                  AND COALESCE(source.priority, 100) <> -1
                ORDER BY conversion.alias_list_id
                """);
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel (id, alias_id, channel_name)
                SELECT route.id, route.alias_id, route.channel_name
                FROM alpha9_alias_broadcast_channel AS route
                JOIN alias AS retained ON retained.id = route.alias_id
                ORDER BY route.id
                """);

            statement.executeUpdate("DROP TABLE alpha9_alias_broadcast_channel");
            statement.executeUpdate("DROP TABLE alpha9_alias");
            statement.executeUpdate("DROP TABLE alpha9_alias_list");
            restoreAliasSequences(statement);
        }

        SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        assignDefaultAliasListsToUnassignedChannels(connection);

        return convertedCatchalls;
    }

    /**
     * Alpha 9 played otherwise eligible calls even when a channel had no Alias List. Assigning the compatible
     * factory list preserves that behavior through the new explicit scan-list delivery model.
     */
    private static void assignDefaultAliasListsToUnassignedChannels(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE configuration_channel
            SET alias_list_name = ?,
                config_json = json_set(config_json, '$.aliasListName', ?)
            WHERE (alias_list_name IS NULL OR trim(alias_list_name) = '')
              AND decoder_type = ?
            """))
        {
            for(DecoderType decoderType: DecoderType.values())
            {
                AliasListFamily family = AliasListFamily.from(decoderType);

                if(family != null)
                {
                    String name = family.getDefaultAliasListName();
                    statement.setString(1, name);
                    statement.setString(2, name);
                    statement.setString(3, decoderType.name());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static void restoreAliasSequences(Statement statement) throws SQLException
    {
        for(String table: new String[] {"alias_list", "alias", "alias_broadcast_channel"})
        {
            statement.executeUpdate("""
                UPDATE sqlite_sequence
                SET seq = max(seq, COALESCE(
                    (SELECT seq FROM alpha9_alias_sequence WHERE name = '%s'), seq))
                WHERE name = '%s'
                """.formatted(table, table));
            statement.executeUpdate("""
                INSERT INTO sqlite_sequence(name, seq)
                SELECT name, seq FROM alpha9_alias_sequence source
                WHERE source.name = '%s'
                  AND NOT EXISTS (SELECT 1 FROM sqlite_sequence target WHERE target.name = '%s')
                """.formatted(table, table));
        }
    }

    /**
     * Statistics are derived receiver state, not administrator-owned configuration. The published predecessor and
     * current schemas model calls and learned identities differently, so the release migration deliberately rebuilds
     * all three statistics domains empty instead of translating partial or misleading history.
     */
    private static void resetStatistics(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP VIEW p25_activity_event_resolved");

            for(String table: STATISTICS_TABLES_IN_DROP_ORDER)
            {
                statement.executeUpdate("DROP TABLE " + table);
            }
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM database_metadata WHERE key = ?"))
        {
            for(String key: STATISTICS_METADATA_KEYS)
            {
                statement.setString(1, key);
                statement.addBatch();
            }
            statement.executeBatch();
        }

        P25ActivityLogSchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
    }

    private static int matcherCount(Connection connection, String matcher) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM alias WHERE matcher_type=?"))
        {
            statement.setString(1, matcher);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException
    {
        return Math.toIntExact(scalarLong(connection, sql));
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static String metadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement =
                connection.prepareStatement("SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static void requirePositiveMetadata(Connection connection, String key) throws SQLException
    {
        String value = metadata(connection, key);

        try
        {
            if(value == null || Long.parseLong(value) <= 0)
            {
                throw new SQLException("Predecessor database metadata [" + key + "] must be a positive timestamp");
            }
        }
        catch(NumberFormatException e)
        {
            throw new SQLException("Predecessor database metadata [" + key + "] is not a valid timestamp", e);
        }
    }

    record Summary(int convertedCatchalls, int removedFullyQualifiedTalkgroups,
                   int removedFullyQualifiedRadios)
    {
        String releaseSummary()
        {
            return "Alpha 8/Alpha 9/Alpha 10 layout migration: converted " + convertedCatchalls +
                " unmatched-talkgroup catch-all alias(es), removed " + removedFullyQualifiedTalkgroups +
                " retired fully-qualified talkgroup alias(es) and " + removedFullyQualifiedRadios +
                " retired fully-qualified radio alias(es). Statistics, activity history, learned site state, " +
                "affiliations, and presence were reset; configuration, supported aliases, channels, streams, " +
                "and settings were preserved.";
        }
    }
}
