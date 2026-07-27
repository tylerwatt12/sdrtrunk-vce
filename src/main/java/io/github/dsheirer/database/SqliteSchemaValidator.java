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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    /**
     * Validates the complete stored SQL definition for schema objects whose physical contract matters.  SQLite
     * removes {@code IF NOT EXISTS} when it stores a definition, so that clause and insignificant whitespace are
     * canonicalized before comparison.  No schema statements are executed.
     */
    public static void validateDefinitions(Connection connection, Collection<Definition> definitions)
        throws SQLException
    {
        for(Definition definition: definitions)
        {
            String actual = objectSql(connection, definition.type(), definition.name());
            String expected = canonicalSql(definition.sql());

            if(!expected.equals(canonicalSql(actual)))
            {
                throw new SQLException("SQLite " + definition.type() + " [" + definition.name() +
                    "] does not match its current-schema definition");
            }
        }
    }

    private static void validateTable(Connection connection, Table table) throws SQLException
    {
        validateObject(connection, "table", table.name());
        List<String> columns = columns(connection, table.name());

        if(!columns.equals(table.columns()))
        {
            throw new SQLException("SQLite table [" + table.name() + "] has columns " + columns +
                "; expected exactly " + table.columns());
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

    private static String objectSql(Connection connection, String type, String name) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT sql FROM sqlite_master WHERE type = ? AND name = ?
            """))
        {
            statement.setString(1, type);
            statement.setString(2, name);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next() && resultSet.getString("sql") != null)
                {
                    return resultSet.getString("sql");
                }
            }
        }

        throw new SQLException("SQLite schema is missing " + type + " [" + name + "]");
    }

    private static String canonicalSql(String sql)
    {
        if(sql == null)
        {
            return "";
        }

        return sql.trim()
            .replaceFirst(
                "(?i)^CREATE\\s+((?:UNIQUE\\s+)?(?:TABLE|INDEX|VIEW|TRIGGER))\\s+IF\\s+NOT\\s+EXISTS\\s+",
                "CREATE $1 ")
            .replaceAll("\\s+", " ")
            .replaceAll("\\s*([(),=])\\s*", "$1")
            .replaceFirst(";\\s*$", "");
    }

    private static List<String> columns(Connection connection, String table) throws SQLException
    {
        List<String> columns = new ArrayList<>();

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

    public record Definition(String type, String name, String sql)
    {
        public Definition
        {
            if(!List.of("table", "index", "view", "trigger").contains(type))
            {
                throw new IllegalArgumentException("Unsupported SQLite schema object type: " + type);
            }
            if(name == null || name.isBlank() || sql == null || sql.isBlank())
            {
                throw new IllegalArgumentException("SQLite schema definition requires a name and SQL");
            }
        }
    }
}
