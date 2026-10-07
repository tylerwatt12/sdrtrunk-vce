/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Establishes the persisted meaning of an explicitly configured remote P25 source without rewriting existing rows.
 */
final class Format27To28DatabaseMigration implements DatabaseMigrationStep
{
    static final int SOURCE_VERSION = 27;

    @Override public String id() { return "format-27-to-28"; }
    @Override public String description() { return "Add saved remote P25 source identity"; }
    @Override public int sourceVersion() { return SOURCE_VERSION; }
    @Override public int targetVersion() { return 28; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return declaredEffects(true);
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return selectedSourceStep ?
            effects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT,
                RepairReport.declared(), true) :
            semanticEffects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        return effects(count(connection, "configuration_channel"), count(connection, "application_settings"),
            inspectRepairs(connection), false);
    }

    @Override
    public void migrateSource(Connection connection) throws SQLException
    {
        repair(connection);
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
        long channels = count(connection, "configuration_channel");
        long settings = count(connection, "application_settings");
        return selectedSourceStep ? effects(channels, settings, repair(connection), false) :
            semanticEffects(channels, settings);
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

    private static List<DatabaseMigrationEffect> effects(long channels, long settings, RepairReport repairs,
                                                          boolean declared)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>(semanticEffects(channels, settings));
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
            "unusable portable preference components",
            declared ? DatabaseMigrationEffect.UNKNOWN_COUNT : repairs.portablePreferenceResets(),
            "Preserve usable entries and remove or reset only malformed, obsolete, or invalid components before " +
                "the strict current-format stamp"));
        addRepairEffects(effects, CurrentDatabaseAdministrativeRepair.effects(repairs.administrative()), declared);
        addRepairEffects(effects, CurrentDatabaseBestEffortRepair.effects(repairs.configuration()), declared);
        addRepairEffects(effects, CurrentDatabaseDerivedStateRepair.effects(repairs.derived()), declared);
        return List.copyOf(effects);
    }

    private static List<DatabaseMigrationEffect> semanticEffects(long channels, long settings)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE, "saved channel configurations",
                channels, "Preserve every usable saved channel JSON document unchanged"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE, "application settings", settings,
                "Preserve every usable application setting unchanged"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "remote P25 source assignments", 0,
                "Keep existing channels local and create no sender or feed identity by inference"));
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


    private static long count(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private record RepairReport(int portablePreferenceResets,
                                CurrentDatabaseAdministrativeRepair.Inspection administrative,
                                CurrentDatabaseDerivedStateRepair.Inspection derived,
                                CurrentDatabaseBestEffortRepair.Inspection configuration)
    {
        private static RepairReport declared()
        {
            return new RepairReport(0, CurrentDatabaseAdministrativeRepair.Inspection.none(),
                CurrentDatabaseDerivedStateRepair.Inspection.none(),
                CurrentDatabaseBestEffortRepair.Inspection.none());
        }
    }
}
