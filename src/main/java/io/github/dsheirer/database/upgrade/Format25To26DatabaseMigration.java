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
import java.util.ArrayList;
import java.util.List;

/** Retains exact P25 radio inhibit and uninhibit commands and acknowledgements in Activity history. */
final class Format25To26DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-25-to-26"; }
    @Override public String description()
    {
        return "Retain exact P25 radio inhibit and uninhibit activity";
    }
    @Override public int sourceVersion() { return 25; }
    @Override public int targetVersion() { return 26; }

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
                DatabaseMigrationEffect.UNKNOWN_COUNT, RepairReport.declared(), true) :
            activityEffects(0, 0, 0);
    }

    @Override
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        RepairReport repairs = inspectRepairs(connection);
        boolean resetsDerivedState = repairs.derived().damagedTables() > 0;
        return effects(resetsDerivedState ? 0 : count(connection, "receiver_activity_event"),
            resetsDerivedState ? 0 : count(connection, "activity_event_identity_member"),
            resetsDerivedState ? 0 : count(connection, "conventional_activity_summary"), repairs, false);
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
        ApplicationMigrationTransaction.requireActive(connection);
        RepairReport repairs = selectedSourceStep ? repair(connection) : RepairReport.none();
        // Owned/intermediate sources preserve every row without counting untouched retained history.
        long events = selectedSourceStep ? count(connection, "receiver_activity_event") : 0;
        long identityMembers = selectedSourceStep ? count(connection, "activity_event_identity_member") : 0;
        long conventionalSummaries = selectedSourceStep ? count(connection, "conventional_activity_summary") : 0;
        ReceiverActivitySchema.upgradeConstraintDomainsFrom25(connection);
        return selectedSourceStep ? effects(events, identityMembers, conventionalSummaries, repairs, false) :
            activityEffects(events, identityMembers, conventionalSummaries);
    }

    private static RepairReport inspectRepairs(Connection connection) throws SQLException
    {
        CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
        return new RepairReport(ApplicationDatabaseMigrator.inspectCurrentPortablePreferenceRepairs(connection),
            CurrentDatabaseAdministrativeRepair.inspect(connection),
            CurrentDatabaseDerivedStateRepair.inspectHistorical(connection, 25),
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
            CurrentDatabaseDerivedStateRepair.discardDamagedHistoricalState(connection, 25);
        return new RepairReport(portablePreferenceResets, administrative, derived, configuration);
    }


    private static long count(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static List<DatabaseMigrationEffect> effects(long events, long identityMembers,
                                                          long conventionalSummaries, RepairReport repairs,
                                                          boolean declared)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>(
            activityEffects(events, identityMembers, conventionalSummaries));
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

    private static List<DatabaseMigrationEffect> activityEffects(long events, long identityMembers,
                                                                  long conventionalSummaries)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "retained receiver activity events", events,
                "Keep every retained Activity event and event ID while allowing four exact radio-control types"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "Activity event identity-member relationships", identityMembers,
                "Keep every patch-member relationship attached to its original Activity event ID"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "conventional Activity summaries", conventionalSummaries,
                "Keep every compact conventional total while allowing the four exact radio-control types"));
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
