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
    private AliasActivitySummaryMaintenance()
    {
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
                alias_id, alias_list_id, protocol_code, metrics_state, updated_at_ms
            )
            SELECT alias_id, alias_list_id, protocol_code,
                   CASE WHEN protocol_code = 0 THEN 'unsupported' ELSE 'not_collected' END,
                   ?
            FROM (
                SELECT id AS alias_id, alias_list_id,
                       CASE
                           WHEN matcher_type IN (
                               'TALKGROUP', 'TALKGROUP_RANGE', 'RADIO_ID', 'RADIO_ID_RANGE'
                           ) THEN CASE protocol
                               WHEN 'APCO25' THEN 1
                               WHEN 'APCO25_PHASE2' THEN 1
                               WHEN 'DMR' THEN 3
                               WHEN 'NXDN' THEN 4
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
            statement.executeUpdate();
        }
        return deleted;
    }
}
