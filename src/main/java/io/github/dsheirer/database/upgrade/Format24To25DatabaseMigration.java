/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Introduces opt-in encrypted traffic-channel suppression without rewriting existing channel configuration. */
final class Format24To25DatabaseMigration implements DatabaseMigrationStep
{
    static final int SOURCE_VERSION = 24;

    @Override
    public String id()
    {
        return "format-24-to-25";
    }

    @Override
    public String description()
    {
        return "Add opt-in encrypted traffic-channel suppression";
    }

    @Override
    public int sourceVersion()
    {
        return SOURCE_VERSION;
    }

    @Override
    public int targetVersion()
    {
        return 25;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return declaredEffects(true);
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return selectedSourceStep ?
            effects(DatabaseMigrationEffect.UNKNOWN_COUNT, RepairReport.declared(), true) :
            semanticEffects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        return effects(configurationRowCount(connection), inspectRepairs(connection), false);
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        repair(connection);
    }

    @Override
    public List<DatabaseMigrationEffect> migrateAndReport(Connection connection) throws SQLException
    {
        return migrateAndReport(connection, true);
    }

    @Override
    public List<DatabaseMigrationEffect> migrateAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        requireSourceFormat(connection);
        long configurationRows = configurationRowCount(connection);
        return selectedSourceStep ? effects(configurationRows, repair(connection), false) :
            semanticEffects(configurationRows);
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
        //Historical migrations temporarily disable foreign keys. Repair configuration first so that any receiver
        //rows orphaned by a dropped channel are detected and cleared by the following derived-state sweep.
        CurrentDatabaseDerivedStateRepair.Inspection derived =
            CurrentDatabaseDerivedStateRepair.repair(connection);
        return new RepairReport(portablePreferenceResets, administrative, derived, configuration);
    }

    private static List<DatabaseMigrationEffect> effects(long configurationRows, RepairReport repairs,
                                                          boolean declared)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>(semanticEffects(configurationRows));
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

    private static List<DatabaseMigrationEffect> semanticEffects(long configurationRows)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "saved channel configurations", configurationRows,
            "Preserve every usable saved channel JSON document unchanged except for any independently itemized " +
                "bounded repair"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "encrypted traffic-channel suppression", 0,
                "Treat an absent per-channel ignoreEncryptedCalls setting as disabled; preserve any explicit " +
                    "existing NXDN selection"));
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

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspectForMigration(connection);
        if(detected.version() != SOURCE_VERSION)
        {
            throw new SQLException("Migration step format-24-to-25 requires exact source format 24; found " +
                detected.version() + " [" + detected.id() + "]");
        }
    }

    private static long configurationRowCount(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM configuration_channel"))
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
