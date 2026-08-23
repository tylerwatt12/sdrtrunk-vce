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

package io.github.dsheirer.database.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationSnapshotDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void rollsBackTheWholeDatabaseSnapshotWhenAWriteFails() throws Exception
    {
        Path database = mTemporaryFolder.resolve("snapshot-rollback.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        int aliasCountBefore;
        int aliasListCountBefore;

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT (SELECT COUNT(*) FROM alias), (SELECT COUNT(*) FROM alias_list)"))
        {
            assertTrue(resultSet.next());
            aliasCountBefore = resultSet.getInt(1);
            aliasListCountBefore = resultSet.getInt(2);
        }

        AliasListDefinition definition =
            new AliasListDefinition("County P25", AliasListFamily.P25);
        Alias alias = new Alias("Dispatch");
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1001));

        ConfigurationState state = new ConfigurationState();
        state.setAliasListDefinitions(List.of(definition));
        state.setAliases(List.of(alias));
        ScanListConfiguration currentScanLists = new ScanListDatabaseStore(database).loadConfiguration();
        state.setScanListConfiguration(new ScanListConfiguration(currentScanLists.scanLists(), Map.of(), Map.of()));

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE configuration_channel");
        }

        assertThrows(SQLException.class, () -> new ConfigurationSnapshotDatabaseStore(database).replace(state));

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery(
                "SELECT (SELECT COUNT(*) FROM alias), (SELECT COUNT(*) FROM alias_list)"))
            {
                assertTrue(resultSet.next());
                assertEquals(aliasCountBefore, resultSet.getInt(1));
                assertEquals(aliasListCountBefore, resultSet.getInt(2));
            }
        }
    }
}
