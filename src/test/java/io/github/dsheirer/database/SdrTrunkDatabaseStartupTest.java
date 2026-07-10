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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdrTrunkDatabaseStartupTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void rejectsIncompleteExistingSchemaWithoutRepairingIt() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);
        }

        assertThrows(java.sql.SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'p25_system'
                """))
        {
            assertFalse(resultSet.next());
        }
    }
}
