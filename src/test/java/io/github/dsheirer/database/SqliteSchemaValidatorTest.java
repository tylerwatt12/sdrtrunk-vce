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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqliteSchemaValidatorTest
{
    @Test
    void rejectsUnexpectedColumnsInsteadOfTreatingThemAsCurrentSchema() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT)");
            SqliteSchemaValidator.Table expected =
                new SqliteSchemaValidator.Table("sample", "id", "value");

            assertDoesNotThrow(() ->
                SqliteSchemaValidator.validate(connection, List.of(expected), List.of(), List.of(), List.of()));

            statement.executeUpdate("ALTER TABLE sample ADD COLUMN unexpected TEXT");

            assertThrows(SQLException.class, () ->
                SqliteSchemaValidator.validate(connection, List.of(expected), List.of(), List.of(), List.of()));
        }
    }

    @Test
    void comparesPhysicalObjectDefinitionsWithoutExecutingDdl() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE strict_sample (id TEXT PRIMARY KEY, value TEXT NOT NULL)");

            SqliteSchemaValidator.Definition expected = new SqliteSchemaValidator.Definition(
                "table", "strict_sample",
                "CREATE TABLE IF NOT EXISTS strict_sample (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");

            assertThrows(SQLException.class,
                () -> SqliteSchemaValidator.validateDefinitions(connection, List.of(expected)));

            SqliteSchemaValidator.Definition actual = new SqliteSchemaValidator.Definition(
                "table", "strict_sample",
                "CREATE TABLE IF NOT EXISTS strict_sample (id TEXT PRIMARY KEY, value TEXT NOT NULL)");

            assertDoesNotThrow(() ->
                SqliteSchemaValidator.validateDefinitions(connection, List.of(actual)));
        }
    }
}
