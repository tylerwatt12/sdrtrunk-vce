/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdrTrunkTestDatabaseTest
{
    @TempDir Path mTemporary;

    @Test
    void currentTemplatesCreateIndependentExactPairs() throws Exception
    {
        Path first = SdrTrunkTestDatabase.create(mTemporary.resolve("first.sqlite"));
        Path second = SdrTrunkTestDatabase.create(mTemporary.resolve("second.sqlite"));
        SdrTrunkDatabaseStartup.validateGlobalDatabase(first);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(second);
        assertNotEquals(pairId(first), pairId(second), "Separate test profiles must not accept each other's history");
        for(Path configuration: new Path[]{first, second})
        {
            assertEquals(pairId(configuration), pairId(ActivityDatabasePath.forConfiguration(configuration)));
        }
    }

    @Test
    void currentTemplatesRejectAnUnrelatedHistoryFile() throws Exception
    {
        Path first = SdrTrunkTestDatabase.create(mTemporary.resolve("first.sqlite"));
        Path second = SdrTrunkTestDatabase.create(mTemporary.resolve("second.sqlite"));
        Files.copy(ActivityDatabasePath.forConfiguration(second), ActivityDatabasePath.forConfiguration(first),
            StandardCopyOption.REPLACE_EXISTING);
        assertThrows(SQLException.class, () -> ConfigurationHistoryDatabase.validatePair(first));
    }

    @Test
    void existingCompanionIsPreservedWithoutCreatingConfiguration() throws Exception
    {
        Path configuration = mTemporary.resolve("existing.sqlite");
        Path activity = ActivityDatabasePath.forConfiguration(configuration);
        Files.writeString(activity, "Existing test-owned file");
        assertThrows(FileAlreadyExistsException.class, () -> SdrTrunkTestDatabase.create(configuration));
        assertEquals("Existing test-owned file", Files.readString(activity));
        assertFalse(Files.exists(configuration));
    }

    private static String pairId(Path path) throws Exception
    {
        try(Connection connection = ConfigurationHistoryDatabase.openReadOnly(path))
        {
            return ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY);
        }
    }
}
