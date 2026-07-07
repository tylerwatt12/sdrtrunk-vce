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

package io.github.dsheirer.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only SQLite schema validator.
 */
public final class SqliteSchemaValidator
{
    private SqliteSchemaValidator()
    {
    }

    public static void validate(Connection connection, Collection<Table> tables, Collection<String> indexes,
                                Collection<String> views, Collection<Metadata> metadata) throws SQLException
    {
        for(Table table: tables)
        {
            validateTable(connection, table);
        }

        for(String index: indexes)
        {
            validateObject(connection, "index", index);
        }

        for(String view: views)
        {
            validateObject(connection, "view", view);
        }

        for(Metadata entry: metadata)
        {
            validateMetadata(connection, entry);
        }
    }

    private static void validateTable(Connection connection, Table table) throws SQLException
    {
        validateObject(connection, "table", table.name());
        Set<String> columns = columns(connection, table.name());

        for(String column: table.columns())
        {
            if(!columns.contains(column))
            {
                throw new SQLException("SQLite schema is missing column [" + table.name() + "." + column + "]");
            }
        }
    }

    private static void validateObject(Connection connection, String type, String name) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?
            """))
        {
            statement.setString(1, type);
            statement.setString(2, name);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return;
                }
            }
        }

        throw new SQLException("SQLite schema is missing " + type + " [" + name + "]");
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException
    {
        Set<String> columns = new HashSet<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                columns.add(resultSet.getString("name"));
            }
        }

        return columns;
    }

    private static void validateMetadata(Connection connection, Metadata metadata) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT value FROM database_metadata WHERE key = ?
            """))
        {
            statement.setString(1, metadata.key());

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next() && metadata.value().equals(resultSet.getString("value")))
                {
                    return;
                }
            }
        }

        throw new SQLException("SQLite schema metadata [" + metadata.key() + "] is not [" + metadata.value() + "]");
    }

    public record Table(String name, List<String> columns)
    {
        public Table(String name, String... columns)
        {
            this(name, List.of(columns));
        }
    }

    public record Metadata(String key, String value)
    {
    }
}
