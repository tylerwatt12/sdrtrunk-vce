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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Maintenance entry points for the durable Alias Activity read model. Runtime requests never rebuild this
 * projection; subsequent observations are maintained by the background statistics writer.
 */
public final class AliasActivitySummaryMaintenance
{
    private static final String[] COUNTERS = {
        "logical_call_count", "recorded_logical_call_count", "stream_submitted_logical_call_count",
        "encrypted_logical_call_count", "grant_observation_count", "join_observation_count",
        "emergency_observation_count", "register_observation_count", "logout_observation_count",
        "denial_observation_count", "data_observation_count", "other_signaling_observation_count",
        "signaling_observation_count", "first_evidence_ms", "last_evidence_ms"
    };

    private AliasActivitySummaryMaintenance()
    {
    }

    /**
     * Establishes the current Alias counter identity on the history writer. Configuration saves never write here.
     * A matcher replacement resets only this Alias, while renames, routes and list moves preserve its counters.
     */
    public static void ensureCurrent(Connection connection, long aliasId, int protocol, long updatedAt)
        throws SQLException
    {
        String changed = "alias_activity_summary.activity_identity<>excluded.activity_identity";
        StringBuilder sql = new StringBuilder("""
            INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code,metrics_state,updated_at_ms,
                activity_identity,alias_name,alias_list_name,last_reset_ms)
            SELECT configured.id,configured.alias_list_id,?,CASE WHEN ?=0 THEN 'unsupported' ELSE 'not_collected' END,
                ?,configured.activity_identity,
                coalesce(configured.name,''),coalesce(list.name,''),configured.activity_reset_ms
            FROM alias configured JOIN alias_list list ON list.id=configured.alias_list_id WHERE configured.id=?
            ON CONFLICT(alias_id) DO UPDATE SET
                alias_list_id=excluded.alias_list_id,protocol_code=excluded.protocol_code,
                activity_identity=excluded.activity_identity,alias_name=excluded.alias_name,
                alias_list_name=excluded.alias_list_name,
                metrics_state=CASE WHEN $changed THEN excluded.metrics_state ELSE metrics_state END,
                last_reset_ms=CASE WHEN $changed THEN excluded.last_reset_ms ELSE
                    nullif(max(coalesce(last_reset_ms,0),coalesce(excluded.last_reset_ms,0)),0) END,
                updated_at_ms=excluded.updated_at_ms
            """.replace("$changed", changed));
        for(String counter: COUNTERS)
        {
            sql.append(", ").append(counter).append("=CASE WHEN ").append(changed)
                .append(" THEN NULL ELSE ").append(counter).append(" END");
        }
        sql.append(" WHERE ").append(changed)
            .append(" OR alias_activity_summary.alias_list_id<>excluded.alias_list_id")
            .append(" OR alias_activity_summary.alias_name<>excluded.alias_name")
            .append(" OR alias_activity_summary.alias_list_name<>excluded.alias_list_name")
            .append(" OR coalesce(alias_activity_summary.last_reset_ms,0)<coalesce(excluded.last_reset_ms,0)");
        try(PreparedStatement statement = connection.prepareStatement(sql.toString()))
        {
            statement.setInt(1, protocol);
            statement.setInt(2, protocol);
            statement.setLong(3, updatedAt);
            statement.setLong(4, aliasId);
            statement.executeUpdate();
        }
    }

    /** Rebuilds all Alias Activity summary rows inside the caller-owned migration transaction. */
    public static void rebuildAll(Connection connection) throws SQLException
    {
        Objects.requireNonNull(connection, "Connection cannot be null");
        new StatsAliasCatalog(new StatsAliasResolver()).rebuildAllActivitySummaries(connection);
    }

    /**
     * Clears every observed value and restores exactly one configuration-derived summary row per Alias.
     *
     * <p>This is used after a deliberate statistics reset or a same-format derived-state repair.  Those operations
     * discard the retained activity evidence at the same boundary, so rebuilding from evidence would be both wasted
     * work and vulnerable to accidentally carrying pre-reset counters forward.</p>
     *
     * @return number of summary rows discarded before reseeding
     */
    public static int resetAll(Connection connection) throws SQLException
    {
        Objects.requireNonNull(connection, "Connection cannot be null");
        int deleted;
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM alias_activity_summary"))
        {
            deleted = statement.executeUpdate();
        }

        long updatedAt = Math.max(1L, System.currentTimeMillis());
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_activity_summary(
                alias_id, alias_list_id, protocol_code, metrics_state, updated_at_ms, last_reset_ms
            )
            SELECT alias_id, alias_list_id, protocol_code,
                   CASE WHEN protocol_code = 0 THEN 'unsupported' ELSE 'not_collected' END,
                   ?, ?
            FROM (
                SELECT id AS alias_id, alias_list_id,
                       CASE
                           WHEN matcher_type = 'P25_SUBSCRIBER_IDENTITY' THEN 1
                           WHEN matcher_type IN (
                               'TALKGROUP', 'TALKGROUP_RANGE', 'RADIO_ID', 'RADIO_ID_RANGE'
                           ) THEN CASE protocol
                               WHEN 'APCO25' THEN 1
                               WHEN 'APCO25_PHASE2' THEN 1
                               WHEN 'DMR' THEN 3
                               WHEN 'NXDN' THEN 4
                               WHEN 'NBFM' THEN CASE WHEN matcher_type IN ('TALKGROUP', 'TALKGROUP_RANGE') THEN 10 ELSE 0 END
                               WHEN 'AM' THEN CASE WHEN matcher_type IN ('TALKGROUP', 'TALKGROUP_RANGE') THEN 11 ELSE 0 END
                               ELSE 0
                           END
                           ELSE 0
                       END AS protocol_code
                FROM alias
            ) configured_alias
            ORDER BY alias_id
            """))
        {
            statement.setLong(1, updatedAt);
            statement.setLong(2, updatedAt);
            statement.executeUpdate();
        }
        // Frozen adjacent migrations also use this method before Alias counter identities exist.
        // The current runtime always has this explicit contract and seeds its history-owned context.
        try(PreparedStatement columns = connection.prepareStatement(
            "SELECT 1 FROM pragma_table_info('alias') WHERE name='activity_identity'");
            java.sql.ResultSet result = columns.executeQuery())
        {
            if(result.next())
            {
                try(PreparedStatement update = connection.prepareStatement("""
                    UPDATE alias_activity_summary SET
                        activity_identity=(SELECT activity_identity FROM alias WHERE id=alias_id),
                        alias_name=coalesce((SELECT name FROM alias WHERE id=alias_id),''),
                        alias_list_name=coalesce((SELECT list.name FROM alias configured
                            JOIN alias_list list ON list.id=configured.alias_list_id WHERE configured.id=alias_id),'')
                    """))
                {
                    update.executeUpdate();
                }
            }
        }
        return deleted;
    }
}
