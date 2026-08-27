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
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Validates the format-6 identity used by configured conventional receiver contexts. */
public final class Format6ReceiverContextValidator
{
    private static final String CONFIGURATION_PREFIX = "CONFIGURATION:";

    private Format6ReceiverContextValidator()
    {
    }

    public static void validate(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT config.configuration_id, config.radres_guid,
                   context.id, context.context_key, context.guid, context.kind_code
            FROM configuration_channel config
            JOIN receiver_context context
              ON context.context_key = 'CONFIGURATION:' || config.configuration_id
              OR (
                    config.radres_guid IS NOT NULL
                AND length(config.radres_guid) > 0
                AND lower(context.guid) = config.radres_guid
              )
            WHERE config.channel_kind = 'CONVENTIONAL'
            ORDER BY config.configuration_id, context.id
            """); ResultSet resultSet = statement.executeQuery())
        {
            String previousConfigurationId = null;

            while(resultSet.next())
            {
                String configurationId = resultSet.getString("configuration_id");
                String radresGuid = resultSet.getString("radres_guid");
                long contextId = resultSet.getLong("id");
                String contextKey = resultSet.getString("context_key");
                String contextGuid = resultSet.getString("guid");
                int kindCode = resultSet.getInt("kind_code");

                if(configurationId.equals(previousConfigurationId))
                {
                    throw new SQLException("configured conventional channel " + configurationId +
                        " resolves to more than one receiver context");
                }
                previousConfigurationId = configurationId;

                String expectedKey = CONFIGURATION_PREFIX + configurationId;
                if(!expectedKey.equals(contextKey))
                {
                    throw new SQLException("receiver context " + contextId + " for configured conventional channel " +
                        configurationId + " uses noncanonical key " + contextKey);
                }

                if(kindCode != 2 && kindCode != 3 && kindCode != 4 && kindCode != 10)
                {
                    throw new SQLException("receiver context " + contextId + " for configured conventional channel " +
                        configurationId + " has nonconventional kind " + kindCode);
                }

                if(radresGuid != null && !radresGuid.isEmpty() && !radresGuid.equals(contextGuid))
                {
                    throw new SQLException("receiver context " + contextId + " for configured conventional channel " +
                        configurationId + " does not preserve its exact RadioResolve GUID");
                }
            }
        }
    }
}
