/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.database.ActivityDatabasePath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Test-only seeding connection: history is main and configuration is writable solely in disposable fixtures. */
final class StatsPairedTestDatabase
{
    private StatsPairedTestDatabase() {}

    /** Assign deterministic seeded counters to their configured alias identities without changing fixture counts. */
    static void alignAliasSummaryIdentities(Connection connection) throws Exception
    {
        try(var statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE alias_activity_summary SET
                    activity_identity=(SELECT activity_identity FROM configuration.alias WHERE id=alias_id),
                    alias_name=coalesce((SELECT name FROM configuration.alias WHERE id=alias_id),''),
                    alias_list_name=coalesce((SELECT list.name FROM configuration.alias configured
                        JOIN configuration.alias_list list ON list.id=configured.alias_list_id
                        WHERE configured.id=alias_id),'')
                WHERE EXISTS(SELECT 1 FROM configuration.alias WHERE id=alias_id)
                """);
        }
    }

    static Connection open(Path configuration) throws Exception
    {
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        if(!Files.isRegularFile(configuration) || !Files.isRegularFile(history))
            throw new IllegalArgumentException("A current isolated configuration/activity fixture is required");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + history);
        try
        {
            try(var attach = connection.prepareStatement("ATTACH DATABASE ? AS configuration"))
            { attach.setString(1, configuration.toString()); attach.execute(); }
            StatsHistoryContext.installSubscriberContext(connection);
            return connection;
        }
        catch(Exception failure)
        {
            try { connection.close(); } catch(Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
}
