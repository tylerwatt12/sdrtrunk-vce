/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Frozen populated format-58 fixture, including exact source references that later upgrades must retain. */
public final class Format58TestDatabase
{
    private Format58TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format57TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format57To58DatabaseMigration().migrate(connection);
            try(var statement = connection.createStatement())
            {
                statement.executeUpdate("INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms," +
                    "action_code,event_type_code,source_call_uuid) SELECT channel_id,radio_system_id,observed_at_ms+1," +
                    "4,7,'8a220f1a-7ddc-4182-8dd0-fb60506aeb51' FROM receiver_activity_event LIMIT 1");
                statement.executeUpdate("UPDATE topic_alert_message SET source_call_uuid=" +
                    "'8a220f1a-7ddc-4182-8dd0-fb60506aeb51'");
            }
            DatabaseFormatCatalog.stamp(connection, 58);
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 58)
                throw new IllegalStateException("Format-58 fixture was not preserved");
        }
        return database;
    }
}
