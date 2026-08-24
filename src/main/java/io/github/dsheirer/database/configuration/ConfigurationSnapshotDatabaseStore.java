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

import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

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
            connection.setAutoCommit(false);

            try
            {
                ScanListDatabaseStore scanListStore = new ScanListDatabaseStore(mDatabasePath);
                ScanListConfiguration scanListConfiguration = state.getScanListConfiguration() != null ?
                    state.getScanListConfiguration() : scanListStore.loadConfiguration(connection);
                new AliasDatabaseStore(mDatabasePath).replaceAliases(connection, state.getAliases(),
                    state.getAliasListDefinitions());
                scanListConfiguration = applyLegacyListenIntent(state, scanListConfiguration);
                scanListStore.replaceConfiguration(connection, scanListConfiguration);
                new ConfigurationDatabaseStore(mDatabasePath).replaceConfigurationState(connection, state);
                connection.commit();
            }
            catch(IOException | SQLException | RuntimeException | Error e)
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
        }
    }

    private static ScanListConfiguration applyLegacyListenIntent(ConfigurationState state,
                                                                   ScanListConfiguration configuration)
    {
        Map<Long,Set<Long>> aliases = mutable(configuration.aliasMemberships());
        Map<Long,Set<Long>> unmatched = mutable(configuration.unmatchedAliasListMemberships());
        ScanList defaultScanList = configuration.defaultScanList();

        state.getAliases().forEach(alias -> state.getLegacyAliasListenEnabled(alias).ifPresent(enabled ->
        {
            if(enabled)
            {
                aliases.put(alias.getId(), Set.of(defaultScanList.getId()));
            }
            else
            {
                aliases.remove(alias.getId());
            }
        }));
        state.getAliasListDefinitions().forEach(definition ->
            state.getLegacyAliasListListenEnabled(definition).ifPresent(enabled ->
            {
                if(enabled)
                {
                    unmatched.put(definition.getId(), Set.of(defaultScanList.getId()));
                }
                else
                {
                    unmatched.remove(definition.getId());
                }
            }));
        return new ScanListConfiguration(configuration.scanLists(), aliases, unmatched);
    }

    private static Map<Long,Set<Long>> mutable(Map<Long,Set<Long>> source)
    {
        Map<Long,Set<Long>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new LinkedHashSet<>(value)));
        return copy;
    }

}
