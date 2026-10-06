/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Widens Alias List names without changing any existing configuration or relationship. */
final class Format43To44DatabaseMigration implements DatabaseMigrationStep
{
    private static final String COLUMNS = "id, name, family, unmatched_talkgroup_record_enabled, new_alias_record_enabled";

    @Override public String id() { return "format-43-to-44"; }
    @Override public String description() { return "Allow longer Alias List names"; }
    @Override public int sourceVersion() { return 43; }
    @Override public int targetVersion() { return 44; }

    @Override public List<DatabaseMigrationEffect> declaredEffects() { return declaredEffects(true); }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return effects(CurrentDatabaseBestEffortRepair.Inspection.none(), true, selectedSourceStep);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        return effects(CurrentDatabaseBestEffortRepair.inspect(connection), false, true);
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        migrateAndReport(connection, true);
    }

    @Override
    public List<DatabaseMigrationEffect> migrateAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        requireSource(connection);
        if(scalar(connection, "PRAGMA foreign_keys") != 0)
        {
            throw new SQLException("Alias List migration requires the Application Migrator foreign-key boundary");
        }
        CurrentDatabaseBestEffortRepair.Inspection repairs = selectedSourceStep ?
            CurrentDatabaseBestEffortRepair.repair(connection) : CurrentDatabaseBestEffortRepair.Inspection.none();
        long sequence = scalar(connection,
            "SELECT coalesce(max(seq), 0) FROM sqlite_sequence WHERE name='alias_list'");
        boolean sequencePresent = scalar(connection,
            "SELECT count(*) FROM sqlite_sequence WHERE name='alias_list'") > 0;
        long count = scalar(connection, "SELECT count(*) FROM alias_list");
        long legacyAlterTable = scalar(connection, "PRAGMA legacy_alter_table");
        long ignoreChecks = scalar(connection, "PRAGMA ignore_check_constraints");
        try(Statement statement = connection.createStatement())
        {
            // Keep every child foreign key pointed at alias_list while its parent is replaced offline.
            statement.execute("PRAGMA legacy_alter_table=ON");
            // Intermediate historical sources retain independently repairable values for the runner's final
            // bounded component repair. Replacing this parent must not reject them before that shared policy runs.
            statement.execute("PRAGMA ignore_check_constraints=ON");
            try
            {
                statement.executeUpdate("ALTER TABLE alias_list RENAME TO format43_alias_list");
                SdrTrunkDatabaseSchema.createFormat44AliasListTable(connection);
                statement.executeUpdate("INSERT INTO alias_list(" + COLUMNS + ") SELECT " + COLUMNS +
                    " FROM format43_alias_list ORDER BY id");
                if(scalar(connection, "SELECT count(*) FROM alias_list") != count)
                {
                    throw new SQLException("Alias List migration changed the retained row count");
                }
                statement.executeUpdate("DROP TABLE format43_alias_list");
            }
            finally
            {
                statement.execute("PRAGMA legacy_alter_table=" + legacyAlterTable);
                statement.execute("PRAGMA ignore_check_constraints=" + ignoreChecks);
            }
        }
        sequence = Math.max(sequence, scalar(connection, "SELECT coalesce(max(id), 0) FROM alias_list"));
        try(PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM sqlite_sequence WHERE name IN ('alias_list', 'format43_alias_list')");
            PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sqlite_sequence(name, seq) VALUES ('alias_list', ?)"))
        {
            delete.executeUpdate();
            if(sequencePresent || sequence > 0)
            {
                insert.setLong(1, sequence);
                insert.executeUpdate();
            }
        }
        return effects(repairs, false, selectedSourceStep);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 43)
        {
            throw new SQLException("Expected format 43");
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            if(!rows.next()) throw new SQLException("Alias List migration count returned no result");
            return rows.getLong(1);
        }
    }

    private static List<DatabaseMigrationEffect> effects(CurrentDatabaseBestEffortRepair.Inspection repairs,
                                                        boolean declared, boolean includeRepairs)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>();
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "Alias List name length constraint", 1,
            "Allow up to 128 characters while preserving every existing name, ID, Alias, channel assignment, " +
                "recording and streaming policy, scan-list route, retained row, credential, preference and allocator"));
        if(includeRepairs)
        {
            for(DatabaseMigrationEffect effect: CurrentDatabaseBestEffortRepair.effects(repairs))
            {
                effects.add(declared ? new DatabaseMigrationEffect(effect.kind(), effect.subject(),
                    DatabaseMigrationEffect.UNKNOWN_COUNT, effect.detail()) : effect);
            }
        }
        return List.copyOf(effects);
    }
}
