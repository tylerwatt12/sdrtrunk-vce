/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Makes the fixed primary administrator the only ADMIN-tier web account. */
final class Format26To27DatabaseMigration implements DatabaseMigrationStep
{
    private static final String WEB_USER_COLUMNS = "id, username, tier, primary_admin, credential_version, " +
        "password_algorithm, password_iterations, password_derived_key_bits, password_salt, password_hash, " +
        "password_changed_at_ms, auth_revision, preferences_json, preferences_revision, created_at_ms, " +
        "updated_at_ms";

    @Override public String id() { return "format-26-to-27"; }
    @Override public String description() { return "Keep one administrator account"; }
    @Override public int sourceVersion() { return 26; }
    @Override public int targetVersion() { return 27; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return declaredEffects(true);
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, RepairReport.declared(), true, selectedSourceStep);
    }

    @Override
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        RepairReport repairs = inspectRepairs(connection);
        return effects(convertedAccountCount(connection, repairs.administrative()), repairs, false, true);
    }

    @Override
    public void migrateSource(Connection connection) throws SQLException
    {
        migrateSourceAndReport(connection, true);
    }

    @Override
    public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection) throws SQLException
    {
        return migrateSourceAndReport(connection, true);
    }

    @Override
    public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        RepairReport repairs = selectedSourceStep ? repair(connection) : RepairReport.none();
        long convertedAccounts = count(connection,
            "SELECT COUNT(*) FROM web_user WHERE primary_admin=0 AND tier='ADMIN'");
        rebuildWebUsers(connection);
        return effects(convertedAccounts, repairs, false, selectedSourceStep);
    }

    private static void rebuildWebUsers(Connection connection) throws SQLException
    {
        long sourceSequence = count(connection,
            "SELECT coalesce(max(seq), 0) FROM sqlite_sequence WHERE name='web_user'");
        SdrTrunkDatabaseSchema.createFormat27WebUserMigrationTable(connection);
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO web_user_format27 (" + WEB_USER_COLUMNS + ") SELECT " +
                "id, username, CASE WHEN primary_admin=0 THEN 'USER' ELSE tier END, primary_admin, " +
                "credential_version, password_algorithm, password_iterations, password_derived_key_bits, " +
                "password_salt, password_hash, password_changed_at_ms, auth_revision, preferences_json, " +
                "preferences_revision, created_at_ms, updated_at_ms FROM web_user ORDER BY id");
            statement.executeUpdate("DROP TABLE web_user");
            statement.executeUpdate("ALTER TABLE web_user_format27 RENAME TO web_user");
        }
        SdrTrunkDatabaseSchema.createWebUserPrimaryAdminIndex(connection);
        restoreSequence(connection, sourceSequence);
    }

    private static void restoreSequence(Connection connection, long sourceSequence) throws SQLException
    {
        long maximumId = count(connection, "SELECT coalesce(max(id), 0) FROM web_user");
        long sequence = Math.max(sourceSequence, maximumId);
        try(PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM sqlite_sequence WHERE name IN ('web_user', 'web_user_format27')");
            PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sqlite_sequence(name, seq) VALUES ('web_user', ?)"))
        {
            delete.executeUpdate();
            if(sequence > 0)
            {
                insert.setLong(1, sequence);
                insert.executeUpdate();
            }
        }
    }

    private static long convertedAccountCount(Connection connection,
                                               CurrentDatabaseAdministrativeRepair.Inspection administrative)
        throws SQLException
    {
        long count = 0;
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT rowid FROM web_user WHERE primary_admin=0 AND tier='ADMIN'
            """))
        {
            while(rows.next())
            {
                if(administrative.retainsWebUserRow(rows.getLong(1)))
                {
                    count++;
                }
            }
        }
        return count;
    }

    private static RepairReport inspectRepairs(Connection connection) throws SQLException
    {
        CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
        return new RepairReport(ApplicationDatabaseMigrator.inspectCurrentPortablePreferenceRepairs(connection),
            CurrentDatabaseAdministrativeRepair.inspect(connection),
            CurrentDatabaseDerivedStateRepair.inspect(connection),
            CurrentDatabaseBestEffortRepair.inspect(connection));
    }

    private static RepairReport repair(Connection connection) throws SQLException
    {
        CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
        int portablePreferenceResets = ApplicationDatabaseMigrator.repairCurrentPortablePreferences(connection);
        CurrentDatabaseAdministrativeRepair.Inspection administrative =
            CurrentDatabaseAdministrativeRepair.repair(connection);
        CurrentDatabaseBestEffortRepair.Inspection configuration =
            CurrentDatabaseBestEffortRepair.repair(connection);
        CurrentDatabaseDerivedStateRepair.Inspection derived =
            CurrentDatabaseDerivedStateRepair.repair(connection);
        return new RepairReport(portablePreferenceResets, administrative, derived, configuration);
    }


    private static long count(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            if(!rows.next())
            {
                throw new SQLException("Format-26 account migration count returned no result");
            }
            return rows.getLong(1);
        }
    }

    private static List<DatabaseMigrationEffect> effects(long convertedAccounts, RepairReport repairs,
                                                          boolean declared, boolean includeRepairs)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>();
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "ordinary administrator accounts", convertedAccounts,
            "Change each non-primary ADMIN account to USER while preserving its credential, preferences, " +
                "timestamps, and revisions"));
        if(includeRepairs)
        {
            effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "unusable portable preference components",
                declared ? DatabaseMigrationEffect.UNKNOWN_COUNT : repairs.portablePreferenceResets(),
                "Preserve usable entries and remove or reset only malformed, obsolete, or invalid components " +
                    "before the strict current-format stamp"));
            addRepairEffects(effects, CurrentDatabaseAdministrativeRepair.effects(repairs.administrative()), declared);
            addRepairEffects(effects, CurrentDatabaseBestEffortRepair.effects(repairs.configuration()), declared);
            addRepairEffects(effects, CurrentDatabaseDerivedStateRepair.effects(repairs.derived()), declared);
        }
        return List.copyOf(effects);
    }

    private static void addRepairEffects(List<DatabaseMigrationEffect> target,
                                         List<DatabaseMigrationEffect> repairs, boolean declared)
    {
        for(DatabaseMigrationEffect repair: repairs)
        {
            target.add(declared ? new DatabaseMigrationEffect(repair.kind(), repair.subject(),
                DatabaseMigrationEffect.UNKNOWN_COUNT, repair.detail()) : repair);
        }
    }

    private record RepairReport(int portablePreferenceResets,
                                CurrentDatabaseAdministrativeRepair.Inspection administrative,
                                CurrentDatabaseDerivedStateRepair.Inspection derived,
                                CurrentDatabaseBestEffortRepair.Inspection configuration)
    {
        private static RepairReport declared()
        {
            return none();
        }

        private static RepairReport none()
        {
            return new RepairReport(0, CurrentDatabaseAdministrativeRepair.Inspection.none(),
                CurrentDatabaseDerivedStateRepair.Inspection.none(),
                CurrentDatabaseBestEffortRepair.Inspection.none());
        }
    }
}
