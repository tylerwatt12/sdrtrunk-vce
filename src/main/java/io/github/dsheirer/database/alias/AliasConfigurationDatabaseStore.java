/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.database.alias;

import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/** SQLite transaction boundary owned only by Alias, Alias List, and scan-list administration. */
public final class AliasConfigurationDatabaseStore
{
    private final Path mDatabasePath;

    public AliasConfigurationDatabaseStore(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    public AliasConfigurationSnapshot load() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            AliasDatabaseStore aliasStore = new AliasDatabaseStore(mDatabasePath);
            List<AliasListDefinition> definitions = aliasStore.loadAliasListDefinitions(connection);
            return new AliasConfigurationSnapshot(definitions, aliasStore.loadAliases(connection, definitions),
                new ScanListDatabaseStore(mDatabasePath).loadConfiguration(connection));
        }
    }

    /**
     * Commits an isolated Alias configuration and returns the committed copy. Ordinary Alias commands never rewrite
     * channel or broadcast rows. Alias-list deletion may clear only the matching channel assignment column.
     */
    public AliasConfigurationSnapshot commit(AliasConfigurationSnapshot proposed,
                                             Collection<String> removedAliasListNames)
        throws IOException, SQLException
    {
        return commitInternal(proposed, removedAliasListNames, null, null, null);
    }

    /**
     * Commits Alias-owned state and one broadcast stream rename in the same transaction. The supplied configurations
     * retain the old live name until this transaction succeeds.
     */
    public AliasConfigurationSnapshot commitWithBroadcastConfigurationRename(
        AliasConfigurationSnapshot proposed, Collection<String> removedAliasListNames,
        List<BroadcastConfiguration> broadcastConfigurations, String previousName, String updatedName)
        throws IOException, SQLException
    {
        if(broadcastConfigurations == null)
        {
            throw new IllegalArgumentException("Broadcast configurations cannot be null");
        }
        if(previousName == null || previousName.isBlank() || updatedName == null || updatedName.isBlank())
        {
            throw new IllegalArgumentException("Broadcast rename names must be nonblank");
        }
        return commitInternal(proposed, removedAliasListNames, List.copyOf(broadcastConfigurations),
            previousName, updatedName);
    }

    private AliasConfigurationSnapshot commitInternal(AliasConfigurationSnapshot proposed,
                                                      Collection<String> removedAliasListNames,
                                                      List<BroadcastConfiguration> broadcastConfigurations,
                                                      String previousBroadcastName,
                                                      String updatedBroadcastName)
        throws IOException, SQLException
    {
        AliasConfigurationSnapshot committed = AliasConfigurationSnapshot.detachedCopyOf(proposed);
        AliasConfigurationSnapshot canonical;

        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            connection.setAutoCommit(false);

            try
            {
                AliasDatabaseStore aliasStore = new AliasDatabaseStore(mDatabasePath);
                ScanListDatabaseStore scanListStore = new ScanListDatabaseStore(mDatabasePath);
                aliasStore.replaceAliases(connection, committed.aliases(), committed.definitions());
                scanListStore.replaceConfiguration(connection, committed.scanLists());
                ConfigurationDatabaseStore configurationStore = new ConfigurationDatabaseStore(mDatabasePath);
                configurationStore.clearAliasListAssignments(connection, removedAliasListNames);
                if(broadcastConfigurations != null)
                {
                    configurationStore.replaceBroadcastConfigurationsWithRename(connection,
                        broadcastConfigurations, previousBroadcastName, updatedBroadcastName);
                }

                List<AliasListDefinition> definitions = aliasStore.loadAliasListDefinitions(connection);
                canonical = new AliasConfigurationSnapshot(definitions,
                    aliasStore.loadAliases(connection, definitions), scanListStore.loadConfiguration(connection));
                connection.commit();
            }
            catch(IOException | SQLException | RuntimeException | Error exception)
            {
                try
                {
                    connection.rollback();
                }
                catch(SQLException rollbackException)
                {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }

        return canonical;
    }
}
