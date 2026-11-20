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

/** Allows conventional NXDN Null Group calls to retain talkgroup identity zero in compact statistics. */
final class Format21To22DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-21-to-22"; }
    @Override public String description() { return "Track NXDN Null Group calls as a distinct destination"; }
    @Override public int sourceVersion() { return 21; }
    @Override public int targetVersion() { return 22; }

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
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM conventional_call_identity_bucket"))
        {
            return effects(rows.next() ? rows.getLong(1) : 0);
        }
    }

    @Override
    public void migrateSource(Connection connection) throws SQLException
    {
        ReceiverActivitySchema.upgradeConstraintDomainsFrom21(connection);
    }

    @Override public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection,
        boolean selectedSourceStep) throws SQLException
    {
        if(selectedSourceStep) return DatabaseMigrationStep.super.migrateSourceAndReport(connection);
        migrateSource(connection);
        return effects(0);
    }

    private static List<DatabaseMigrationEffect> effects(long rows)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "conventional call identity statistics", rows,
            "Keep every retained identity counter while allowing future NXDN Null Group totals"));
    }
}
