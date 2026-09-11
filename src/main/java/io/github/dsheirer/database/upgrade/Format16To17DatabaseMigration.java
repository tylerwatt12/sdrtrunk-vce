/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Adds bounded Alias Editor list and name-sort indexes without changing administrator-owned rows. */
final class Format16To17DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-16-to-17"; }
    @Override public String description() { return "Index large Alias Lists for bounded web browsing and sorting"; }
    @Override public int sourceVersion() { return 16; }
    @Override public int targetVersion() { return 17; }

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
        SdrTrunkDatabaseSchema.createAliasCatalogIndexes(connection);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 16)
        {
            throw new SQLException("Expected format 16");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long aliases)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "administrator-owned aliases", aliases,
            "Keep every Alias unchanged while adding bounded list and name-sort indexes"));
    }
}
