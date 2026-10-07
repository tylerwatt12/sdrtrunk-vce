/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats.activity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Exact serving-system identity lookup shared by browsing and maintenance. */
public final class RadioSystemIdentityLookup
{
    private RadioSystemIdentityLookup() {}

    public static Long find(Connection connection, long radioSystemId, int kind, int homeWacn,
                            int homeSystemId, int identityId) throws SQLException
    {
        boolean localRadio = kind == 2 && homeWacn == -1 && homeSystemId == -1;
        String sql = localRadio ? """
            SELECT summary.id
            FROM radio_system_identity_summary summary
            JOIN radio_system system ON system.id=summary.radio_system_id
            WHERE summary.radio_system_id=? AND summary.identity_kind_code=2 AND summary.identity_id=?
              AND ((summary.home_wacn=system.p25_wacn AND summary.home_system_id=system.p25_system_id)
                OR (summary.home_wacn=-1 AND summary.home_system_id=-1
                    AND summary.p25_subscriber_identity_id IS NULL))
            ORDER BY CASE WHEN summary.home_wacn=-1 THEN 0 ELSE 1 END, summary.id
            LIMIT 1
            """ : """
            SELECT id FROM radio_system_identity_summary
            WHERE radio_system_id=? AND identity_kind_code=? AND home_wacn=?
              AND home_system_id=? AND identity_id=?
            """;
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, radioSystemId);
            if(localRadio)
            {
                statement.setInt(2, identityId);
            }
            else
            {
                statement.setInt(2, kind);
                statement.setInt(3, homeWacn);
                statement.setInt(4, homeSystemId);
                statement.setInt(5, identityId);
            }
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }
}
