/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Adds owner-first action, event-type, and conventional-ID indexes for bounded Activity filtering. */
final class Format23To24DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-23-to-24"; }
    @Override public String description()
    {
        return "Index retained Activity actions, event types, and conventional IDs by owner and time";
    }
    @Override public int sourceVersion() { return 23; }
    @Override public int targetVersion() { return 24; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM receiver_activity_event"))
        {
            return effects(rows.next() ? rows.getLong(1) : 0);
        }
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSource(connection);
        ReceiverActivitySchema.createActivityFilterIndexes(connection);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 23)
        {
            throw new SQLException("Expected format 23");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long events)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "retained receiver activity events", events,
            "Keep every retained Activity row unchanged while adding reproducible owner/action/time and " +
                "owner/event-type/time indexes plus saved-channel source/target-ID/time indexes"));
    }
}
