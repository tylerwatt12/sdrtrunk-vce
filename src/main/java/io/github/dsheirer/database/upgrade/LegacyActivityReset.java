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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Clears a caller-classified set of reproducible receiver-activity tables in foreign-key-safe order. */
final class LegacyActivityReset
{
    /** Receiver-derived tables present before the logical-call format-4 boundary. */
    static final List<String> PRE_LOGICAL_CALL_TABLES = List.of(
        "activity_event_talkgroup_member",
        "call_identity_bucket",
        "conventional_activity_bucket",
        "conventional_activity_summary",
        "dmr_conventional_radio_summary",
        "dmr_conventional_talkgroup_summary",
        "logger_status",
        "p25_activity_event",
        "p25_control_channel_quality",
        "p25_foreign_system_band",
        "p25_foreign_system_band_summary",
        "p25_site_activity_bucket",
        "p25_site_channel",
        "p25_site_channel_summary",
        "p25_site_channel_tag",
        "p25_site_channel_tag_summary",
        "p25_site_frequency_band",
        "p25_site_frequency_band_summary",
        "p25_site_frequency_summary",
        "p25_site_neighbor",
        "p25_site_neighbor_summary",
        "p25_site_patch_group",
        "p25_site_patch_group_radio",
        "p25_site_patch_group_radio_summary",
        "p25_site_patch_group_summary",
        "p25_site_patch_group_talkgroup",
        "p25_site_patch_group_talkgroup_summary",
        "p25_site_snapshot",
        "p25_site_talkgroup_bucket",
        "p25_system",
        "p25_zero_local_fq_talkgroup_summary",
        "receiver_context",
        "trunked_identity_scope",
        "trunked_identity_scope_context",
        "trunked_identity_summary",
        "trunked_radio_affiliation",
        "trunked_radio_presence_lifecycle",
        "trunked_radio_site_presence",
        "trunked_radio_talkgroup_summary",
        "trunked_site_channel_summary",
        "trunked_site_neighbor_summary",
        "trunked_site_snapshot");

    /** Receiver-derived tables present from the logical-call format-4 boundary through format 14. */
    static final List<String> LOGICAL_CALL_TABLES = List.of(
        "activity_event_talkgroup_member",
        "conventional_activity_bucket",
        "conventional_activity_summary",
        "conventional_call_identity_bucket",
        "dmr_conventional_radio_summary",
        "dmr_conventional_talkgroup_summary",
        "logger_status",
        "p25_activity_event",
        "p25_control_channel_quality",
        "p25_foreign_system_band",
        "p25_foreign_system_band_summary",
        "p25_learned_site",
        "p25_site_call_bucket",
        "p25_site_call_identity_bucket",
        "p25_site_channel",
        "p25_site_channel_summary",
        "p25_site_channel_tag",
        "p25_site_channel_tag_summary",
        "p25_site_frequency_band",
        "p25_site_frequency_band_summary",
        "p25_site_neighbor",
        "p25_site_neighbor_summary",
        "p25_site_patch_group",
        "p25_site_patch_group_radio",
        "p25_site_patch_group_radio_summary",
        "p25_site_patch_group_summary",
        "p25_site_patch_group_talkgroup",
        "p25_site_patch_group_talkgroup_summary",
        "p25_site_snapshot",
        "p25_system",
        "p25_zero_local_fq_talkgroup_summary",
        "receiver_context",
        "trunked_identity_scope",
        "trunked_identity_scope_context",
        "trunked_identity_summary",
        "trunked_logical_call_bucket",
        "trunked_logical_call_identity_bucket",
        "trunked_radio_affiliation",
        "trunked_radio_presence_lifecycle",
        "trunked_radio_site_presence",
        "trunked_radio_talkgroup_summary",
        "trunked_signaling_activity_bucket",
        "trunked_site_channel_summary",
        "trunked_site_neighbor_summary",
        "trunked_site_snapshot");

    private LegacyActivityReset()
    {
    }

    static long count(Connection connection, List<String> tables) throws SQLException
    {
        long total = 0;
        try(Statement statement = connection.createStatement())
        {
            for(String table: tables)
            {
                try(ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + identifier(table)))
                {
                    if(!rows.next())
                    {
                        throw new SQLException("Unable to count legacy activity table " + table);
                    }
                    total = Math.addExact(total, rows.getLong(1));
                }
            }
        }
        return total;
    }

    static void clear(Connection connection, List<String> tables) throws SQLException
    {
        Set<String> remaining = new LinkedHashSet<>(tables);
        if(remaining.size() != tables.size())
        {
            throw new SQLException("Receiver activity reset contains a duplicate table classification");
        }

        Map<String,Set<String>> referencedParents = new HashMap<>();
        for(String table: remaining)
        {
            Set<String> parents = new HashSet<>();
            try(Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA foreign_key_list(" + identifier(table) + ")"))
            {
                while(rows.next())
                {
                    String parent = rows.getString("table");
                    if(remaining.contains(parent))
                    {
                        parents.add(parent);
                    }
                }
            }
            referencedParents.put(table, Set.copyOf(parents));
        }

        List<String> deletionOrder = new ArrayList<>(tables.size());
        while(!remaining.isEmpty())
        {
            String next = remaining.stream().filter(candidate -> remaining.stream().noneMatch(other ->
                !candidate.equals(other) && referencedParents.getOrDefault(other, Set.of()).contains(candidate)))
                .sorted().findFirst().orElseThrow(() ->
                    new SQLException("Receiver activity schema contains a foreign-key cycle"));
            deletionOrder.add(next);
            remaining.remove(next);
        }

        try(Statement statement = connection.createStatement())
        {
            for(String table: deletionOrder)
            {
                statement.executeUpdate("DELETE FROM " + identifier(table));
            }
        }

        if(tableExists(connection, "sqlite_sequence"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM sqlite_sequence WHERE name=?"))
            {
                for(String table: tables)
                {
                    statement.setString(1, table);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name=?"))
        {
            statement.setString(1, table);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() && rows.getLong(1) == 1;
            }
        }
    }

    private static String identifier(String value) throws SQLException
    {
        if(value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*"))
        {
            throw new SQLException("Unsafe SQLite identifier: " + value);
        }
        return value;
    }
}
