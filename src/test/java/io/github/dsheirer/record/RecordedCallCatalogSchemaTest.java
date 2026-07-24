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
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordedCallCatalogSchemaTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void newDatabaseCreatesAndValidatesCatalog() throws Exception
    {
        Path database = mTemporaryFolder.resolve("new.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            RecordedCallCatalogSchema.validate(connection);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA quick_check"))
            {
                assertTrue(resultSet.next());
                assertEquals("ok", resultSet.getString(1));
            }

            assertEquals(Integer.toString(RecordedCallCatalogSchema.SCHEMA_VERSION),
                metadata(connection, RecordedCallCatalogSchema.SCHEMA_VERSION_KEY));

            List<String> catalogTables = new ArrayList<>();

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT name FROM sqlite_master
                    WHERE type = 'table' AND name LIKE 'recorded_call%'
                    ORDER BY name
                    """))
            {
                while(resultSet.next())
                {
                    catalogTables.add(resultSet.getString(1));
                }
            }

            assertEquals(List.of("recorded_call", "recorded_call_bucket"), catalogTables,
                "the retained-call catalog has exactly the two approved tables");
        }
    }

    @Test
    void existingSchemaIsValidatedWithoutRuntimeRepair() throws Exception
    {
        Path database = mTemporaryFolder.resolve("existing.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);
            P25ActivityLogSchema.create(connection);
            TrunkedSiteSchema.create(connection);
        }

        assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'recorded_call'
                """))
        {
            assertFalse(resultSet.next(), "validation must not create the missing catalog");
        }
    }

    @Test
    void catalogValidationRejectsAnyThirdCatalogTable() throws Exception
    {
        Path database = mTemporaryFolder.resolve("extra.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE recorded_call_extra(id INTEGER PRIMARY KEY)");
            assertThrows(java.sql.SQLException.class, () -> RecordedCallCatalogSchema.validate(connection));
        }
    }

    private static String metadata(Connection connection, String key) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT value FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }
}
