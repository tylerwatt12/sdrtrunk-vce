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
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try
            {
                new AliasDatabaseStore(mDatabasePath).replaceAliases(connection, state.getAliases(),
                    state.getAliasListDefinitions());
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

}
