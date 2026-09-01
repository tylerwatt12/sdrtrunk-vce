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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Atomically persists the complete administrator-owned configuration snapshot.
 */
public class ConfigurationSnapshotDatabaseStore
{
    private final Path mDatabasePath;

    public ConfigurationSnapshotDatabaseStore(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    public void replace(ConfigurationState state) throws IOException, SQLException
    {
        if(state == null)
        {
            throw new IllegalArgumentException("Configuration snapshot cannot be null");
        }

        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try
            {
                ScanListDatabaseStore scanListStore = new ScanListDatabaseStore(mDatabasePath);
                ScanListConfiguration currentScanLists = state.getScanListConfiguration() != null ?
                    state.getScanListConfiguration() : scanListStore.loadConfiguration(connection);
                new AliasDatabaseStore(mDatabasePath).replaceAliases(connection, state.getAliases(),
                    state.getAliasListDefinitions());
                ScanListConfiguration projected = projectDefaultMembership(currentScanLists, state);
                scanListStore.replaceConfiguration(connection, projected);
                state.setScanListConfiguration(projected);
                new ConfigurationDatabaseStore(mDatabasePath).replaceConfigurationState(connection, state);
                connection.commit();
            }
            catch(IOException | SQLException | RuntimeException e)
            {
                try
                {
                    connection.rollback();
                }
                catch(SQLException rollbackException)
                {
                    e.addSuppressed(rollbackException);
                }

                throw e;
            }
            finally
            {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    /**
     * Projects Swing's Listen flag onto Default while retaining every non-default (hidden in Alpha) membership.
     * Owners removed by an Alias snapshot are pruned explicitly instead of relying on SQLite cascades.
     */
    public static ScanListConfiguration projectDefaultMembership(ScanListConfiguration current,
                                                                  ConfigurationState state)
    {
        ScanList defaultScanList = current.defaultScanList();
        long defaultId = defaultScanList.getId();
        Map<Long,Set<Long>> aliases = new LinkedHashMap<>();

        for(Alias alias: state.getAliases())
        {
            if(alias == null || alias.getId() <= Alias.UNASSIGNED_ID)
            {
                throw new IllegalStateException("Alias persistence did not assign a durable ID");
            }

            Set<Long> memberships = new LinkedHashSet<>(current.scanListIdsForAlias(alias.getId()));
            if(alias.isListen())
            {
                memberships.add(defaultId);
            }
            else
            {
                memberships.remove(defaultId);
            }
            if(!memberships.isEmpty())
            {
                aliases.put(alias.getId(), memberships);
            }
        }

        Map<Long,Set<Long>> unmatched = new LinkedHashMap<>();
        for(AliasListDefinition definition: state.getAliasListDefinitions())
        {
            if(definition == null || definition.getId() <= AliasListDefinition.UNASSIGNED_ID)
            {
                throw new IllegalStateException("Alias List persistence did not assign a durable ID");
            }

            Set<Long> memberships = new LinkedHashSet<>(
                current.scanListIdsForUnmatchedTalkgroups(definition.getId()));
            if(definition.isListenToUnmatchedTalkgroups())
            {
                memberships.add(defaultId);
            }
            else
            {
                memberships.remove(defaultId);
            }
            if(!memberships.isEmpty())
            {
                unmatched.put(definition.getId(), memberships);
            }
        }

        return new ScanListConfiguration(current.scanLists(), aliases, unmatched);
    }

}
