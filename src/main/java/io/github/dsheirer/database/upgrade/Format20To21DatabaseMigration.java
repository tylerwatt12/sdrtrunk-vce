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

/** Splits the former shared Alias List Defaults into independent unknown-call and new-Alias behavior. */
final class Format20To21DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-20-to-21"; }
    @Override public String description() { return "Split unknown-call and new-Alias defaults without changing behavior"; }
    @Override public int sourceVersion() { return 20; }
    @Override public int targetVersion() { return 21; }

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
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM alias_list"))
        {
            return effects(rows.next() ? rows.getLong(1) : 0);
        }
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSource(connection);
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                ALTER TABLE alias_list ADD COLUMN new_alias_record_enabled INTEGER NOT NULL DEFAULT 0 CHECK(
                    typeof(new_alias_record_enabled) = 'integer' AND new_alias_record_enabled IN (0, 1)
                )
                """);
            statement.executeUpdate("""
                UPDATE alias_list
                SET new_alias_record_enabled = unmatched_talkgroup_record_enabled
                """);
        }

        SdrTrunkDatabaseSchema.createNewAliasBehaviorTables(connection);
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list_new_alias_stream(alias_list_id, broadcast_configuration_id)
                SELECT alias_list_id, broadcast_configuration_id
                FROM alias_list_unmatched_talkgroup_stream
                ORDER BY id
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list_new_alias_scan_list_membership(alias_list_id, scan_list_id)
                SELECT alias_list_id, scan_list_id
                FROM alias_list_unmatched_talkgroup_scan_list_membership
                ORDER BY alias_list_id, scan_list_id
                """);
        }
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 20)
        {
            throw new SQLException("Expected format 20");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long aliasLists)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "administrator-owned Alias List Defaults", aliasLists,
            "Copy every existing recording, scan-list, and streaming choice into both behavior tabs"));
    }
}
