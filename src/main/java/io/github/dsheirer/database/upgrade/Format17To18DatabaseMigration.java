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

/** Allows the shared detailed-activity site field to retain valid DMR and NXDN site identifiers. */
final class Format17To18DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-17-to-18"; }
    @Override public String description() { return "Retain full DMR and NXDN site identifiers in activity history"; }
    @Override public int sourceVersion() { return 17; }
    @Override public int targetVersion() { return 18; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return selectedSourceStep ? declaredEffects() : effects(0);
    }

    @Override
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM receiver_activity_event"))
        {
            return effects(rows.next() ? rows.getLong(1) : 0);
        }
    }

    @Override
    public void migrateSource(Connection connection) throws SQLException
    {
        ReceiverActivitySchema.upgradeConstraintDomainsFrom17(connection);
    }

    @Override public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection,
        boolean selectedSourceStep) throws SQLException
    {
        if(selectedSourceStep) return DatabaseMigrationStep.super.migrateSourceAndReport(connection);
        migrateSource(connection);
        // No retained rows are affected; owned upgrades need no history-wide count to report preservation.
        return effects(0);
    }

    private static List<DatabaseMigrationEffect> effects(long activityEvents)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "detailed receiver activity history", activityEvents,
            "Keep existing history while accepting the full valid DMR and NXDN site range"));
    }
}
