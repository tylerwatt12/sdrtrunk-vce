/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.AliasActivitySummaryMaintenance;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Adds and populates the durable, indexed Alias Activity summary. */
final class Format19To20DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-19-to-20"; }
    @Override public String description() { return "Persist indexed Alias Activity summaries for complete-list queries"; }
    @Override public int sourceVersion() { return 19; }
    @Override public int targetVersion() { return 20; }

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
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM alias"))
        {
            return effects(rows.next() ? rows.getLong(1) : 0);
        }
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSource(connection);
        SdrTrunkDatabaseSchema.createAliasActivitySummary(connection);
        AliasActivitySummaryMaintenance.rebuildAll(connection);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 19)
        {
            throw new SQLException("Expected format 19");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long aliases)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "administrator-owned aliases and retained Alias Activity", aliases,
            "Keep every Alias and project its retained activity into the durable indexed summary"));
    }
}
