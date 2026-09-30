/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Adds sparse owner/time indexes for encrypted-only retained Activity browsing. */
final class Format29To30DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-29-to-30"; }
    @Override public String description() { return "Index encrypted Activity by owner and time"; }
    @Override public int sourceVersion() { return 29; }
    @Override public int targetVersion() { return 30; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return declaredEffects(true);
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, RepairReport.none(), true, selectedSourceStep);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        RepairReport repairs = inspectRepairs(connection);
        long encryptedEvents = repairs.derived().damagedTables() > 0 ? 0 : countEncryptedEvents(connection);
        return effects(encryptedEvents, repairs, false, true);
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        migrateAndReport(connection, true);
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
        requireSource(connection);
        RepairReport repairs = selectedSourceStep ? repair(connection) : RepairReport.none();
        long encryptedEvents = countEncryptedEvents(connection);
        ReceiverActivitySchema.createEncryptedActivityFilterIndexes(connection);
        return effects(encryptedEvents, repairs, false, selectedSourceStep);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
        if(source.version() != 29)
        {
            throw new SQLException("Migration step format-29-to-30 requires format 29; found " + source.version());
        }
    }

    private static RepairReport inspectRepairs(Connection connection) throws SQLException
    {
        CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
        return new RepairReport(ApplicationDatabaseMigrator.inspectCurrentPortablePreferenceRepairs(connection),
            CurrentDatabaseAdministrativeRepair.inspect(connection, false),
            CurrentDatabaseDerivedStateRepair.inspect(connection),
            CurrentDatabaseBestEffortRepair.inspect(connection));
    }

    private static RepairReport repair(Connection connection) throws SQLException
    {
        CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
        int portablePreferenceResets = ApplicationDatabaseMigrator.repairCurrentPortablePreferences(connection);
        CurrentDatabaseAdministrativeRepair.Inspection administrative =
            CurrentDatabaseAdministrativeRepair.repair(connection, false);
        CurrentDatabaseBestEffortRepair.Inspection configuration =
            CurrentDatabaseBestEffortRepair.repair(connection);
        CurrentDatabaseDerivedStateRepair.Inspection derived =
            CurrentDatabaseDerivedStateRepair.repair(connection);
        return new RepairReport(portablePreferenceResets, administrative, derived, configuration);
    }

    private static long countEncryptedEvents(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT count(*) FROM receiver_activity_event WHERE encrypted = 1"))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static List<DatabaseMigrationEffect> effects(long encryptedEvents, RepairReport repairs,
                                                          boolean declared, boolean includeRepairs)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>();
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "encrypted retained receiver activity events", encryptedEvents,
            "Keep every retained Activity row unchanged while adding sparse encrypted-only owner/time indexes"));
        if(includeRepairs)
        {
            effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "unusable portable preference components",
                declared ? DatabaseMigrationEffect.UNKNOWN_COUNT : repairs.portablePreferenceResets(),
                "Preserve usable entries and remove or reset only malformed, obsolete, or invalid components " +
                    "before the strict current-format stamp"));
            addRepairEffects(effects, CurrentDatabaseAdministrativeRepair.effects(repairs.administrative()),
                declared);
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
        private static RepairReport none()
        {
            return new RepairReport(0, CurrentDatabaseAdministrativeRepair.Inspection.none(),
                CurrentDatabaseDerivedStateRepair.Inspection.none(),
                CurrentDatabaseBestEffortRepair.Inspection.none());
        }
    }
}
