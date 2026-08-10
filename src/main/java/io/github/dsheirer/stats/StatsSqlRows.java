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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Last-resort row materialization ceiling for v1 read models. Endpoint SQL is expected to use substantially smaller
 * limits; this boundary prevents an accidentally uncapped query from exhausting the heap.
 */
final class StatsSqlRows
{
    static final int MAXIMUM_MATERIALIZED_ROWS = 20_000;

    private StatsSqlRows()
    {
    }

    static List<Map<String,Object>> queryRows(Connection connection, String sql, Object... parameters)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            for(int x = 0; x < parameters.length; x++)
            {
                statement.setObject(x + 1, parameters[x]);
            }

            try(ResultSet resultSet = statement.executeQuery())
            {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                List<Map<String,Object>> rows = new ArrayList<>();

                while(resultSet.next())
                {
                    if(rows.size() >= MAXIMUM_MATERIALIZED_ROWS)
                    {
                        throw new StatsApiException(413, "query_result_too_large",
                            "Query result exceeded the server safety limit");
                    }

                    Map<String,Object> row = new LinkedHashMap<>();

                    for(int column = 1; column <= columnCount; column++)
                    {
                        row.put(metaData.getColumnLabel(column), resultSet.getObject(column));
                    }

                    rows.add(row);
                }

                return rows;
            }
        }
    }
}
