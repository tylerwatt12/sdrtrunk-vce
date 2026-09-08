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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Deterministically restores the single Default scan-list invariant in legacy formats. */
final class LegacyDefaultScanListRepair
{
    private LegacyDefaultScanListRepair()
    {
    }

    static long repairCount(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM scan_list WHERE is_default=1"))
        {
            return rows.next() && rows.getLong(1) == 1 ? 0 : 1;
        }
    }

    static void ensureOneDefault(Connection connection) throws SQLException
    {
        long selectedId = 0;
        long firstId = 0;
        long firstDefaultId = 0;
        long namedDefaultId = 0;
        int defaults = 0;

        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT id, name, is_default FROM scan_list ORDER BY id"))
        {
            while(rows.next())
            {
                long id = rows.getLong("id");
                if(firstId == 0)
                {
                    firstId = id;
                }
                if(rows.getInt("is_default") == 1)
                {
                    defaults++;
                    if(firstDefaultId == 0)
                    {
                        firstDefaultId = id;
                    }
                }
                if(namedDefaultId == 0 && "Default".equalsIgnoreCase(rows.getString("name")))
                {
                    namedDefaultId = id;
                }
            }
        }

        if(defaults == 1)
        {
            return;
        }

        if(firstId == 0)
        {
            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    INSERT INTO scan_list(sort_order, name, description, published, is_default)
                    VALUES (0, 'Default', NULL, 1, 1)
                    """);
            }
            return;
        }

        selectedId = namedDefaultId != 0 ? namedDefaultId :
            (firstDefaultId != 0 ? firstDefaultId : firstId);
        try(var clear = connection.prepareStatement("UPDATE scan_list SET is_default=0 WHERE is_default<>0");
            var select = connection.prepareStatement(
                "UPDATE scan_list SET published=1, is_default=1 WHERE id=?"))
        {
            clear.executeUpdate();
            select.setLong(1, selectedId);
            if(select.executeUpdate() != 1)
            {
                throw new SQLException("Unable to restore the Default scan list");
            }
        }
    }
}
