/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.module.decode.dcs.DCSCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Browses actual captured DCS facts, including the explicit legacy tone field, without inventing old identifiers. */
final class ManagedRecordingDcsDiscovery
{
    private ManagedRecordingDcsDiscovery() {}

    static List<Map<String,Object>> rows(Connection connection, String systemKey, Long fromMs, Long toMs,
                                         String query, long deadline) throws SQLException
    {
        String sql = ManagedRecordingEntityDiscovery.scopedCallsSql("""
            , captured AS (
                SELECT c.call_id,c.start_ms,identifier.value,0 AS legacy FROM scoped c
                    JOIN recordings.recording_identifier identifier ON identifier.call_id=c.call_id
                    WHERE identifier.protocol='DCS' AND identifier.kind='tone'
                UNION ALL SELECT call_id,start_ms,tone,1 FROM scoped WHERE tone_kind=1 AND tone IS NOT NULL
            ), normalized AS (
                SELECT call_id,start_ms,CASE
                    WHEN value GLOB '[NI][0-7][0-7][0-7]' THEN value
                    WHEN legacy=1 AND value GLOB '[0-7][0-7][0-7]' THEN 'N'||value
                    WHEN legacy=1 AND value GLOB 'DCS-[0-7][0-7][0-7] Inverted' THEN 'I'||substr(value,5,3)
                    WHEN legacy=1 AND value GLOB 'DCS-[0-7][0-7][0-7]' THEN 'N'||substr(value,5,3)
                    END AS code FROM captured
            ) SELECT code,count(DISTINCT call_id) AS calls,max(start_ms) AS last_ms
                FROM normalized WHERE code IS NOT NULL GROUP BY code
            """);
        List<Map<String,Object>> rows = new ArrayList<>();
        String match = query != null ? query.toLowerCase(Locale.ROOT) : null;
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1,fromMs != null ? fromMs : 0);
            statement.setLong(2,toMs != null ? toMs : Long.MAX_VALUE);
            statement.setString(3,systemKey); statement.setString(4,systemKey);
            try(ResultSet values = statement.executeQuery())
            {
                while(values.next())
                {
                    if(System.nanoTime() >= deadline)
                        throw new SQLException("Recording browser exceeded its time limit","",9);
                    DCSCode code;
                    try { code=DCSCode.valueOf(values.getString("code")); }
                    catch(IllegalArgumentException exception) { continue; }
                    if(code==DCSCode.UNKNOWN) continue;
                    String label=code.toString();
                    String terms=(code.name()+" "+label+" "+(code.isInverted() ? "inverted" : "normal"))
                        .toLowerCase(Locale.ROOT);
                    if(match != null && !terms.contains(match)) continue;
                    rows.add(Map.of("kind","dcs","id",code.name(),"value",code.name(),"label",label,
                        "protocol","DCS","call_count",values.getLong("calls"),
                        "last_recorded_ms",values.getLong("last_ms"),"selection",Map.of("dcs_code",code.name())));
                }
            }
        }
        return rows;
    }
}
