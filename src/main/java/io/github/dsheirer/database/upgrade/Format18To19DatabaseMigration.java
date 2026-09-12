/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds an initially empty, bounded history of receiver-status alert occurrences. */
final class Format18To19DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-18-to-19"; }
    @Override public String description() { return "Store a bounded receiver status alert history in SQLite"; }
    @Override public int sourceVersion() { return 18; }
    @Override public int targetVersion() { return 19; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects();
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        return effects();
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSource(connection);
        SdrTrunkDatabaseSchema.createReceiverHealthIncidentTable(connection);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 18)
        {
            throw new SQLException("Expected format 18");
        }
    }

    private static List<DatabaseMigrationEffect> effects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "receiver status alert history", 0,
            "Start an empty bounded history; new alert occurrences are recorded after the upgraded app starts"));
    }
}
