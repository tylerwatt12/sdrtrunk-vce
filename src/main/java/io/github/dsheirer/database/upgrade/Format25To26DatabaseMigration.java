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
    private static final String EVENT_COLUMNS = "id, channel_id, radio_system_id, observed_at_ms, action_code, " +
        "event_type_code, source_observed_local_id, target_observed_local_id, target_kind_code, " +
        "source_identity_summary_id, source_identity_kind_code, target_identity_summary_id, frequency_hz, " +
        "lcn_band, lcn_number, timeslot, encrypted, encryption_algorithm_id, encryption_key_id, observed_nac, " +
        "observed_rfss, observed_site";
    private static final String CONVENTIONAL_SUMMARY_COLUMNS =
        "channel_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, acknowledge_count, active_count, " +
        "busy_count, call_count, check_count, check_ack_count, continue_count, data_count, denial_count, " +
        "emergency_count, gps_count, grant_count, join_count, logout_count, page_count, patch_count, " +
        "patch_cancel_count, patch_create_count, queued_count, register_count, request_count, status_count, " +
        "unknown_count, last_event_type_code, encrypted_count, recorded_count, streamed_count";

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
            activityEffects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT,
                DatabaseMigrationEffect.UNKNOWN_COUNT);
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
        RepairReport repairs = selectedSourceStep ? repair(connection) : RepairReport.none();
        long events = count(connection, "receiver_activity_event");
        long identityMembers = count(connection, "activity_event_identity_member");
        long conventionalSummaries = count(connection, "conventional_activity_summary");
        rebuildActivityTables(connection);
        return selectedSourceStep ? effects(events, identityMembers, conventionalSummaries, repairs, false) :
            activityEffects(events, identityMembers, conventionalSummaries);
    }

    private static void rebuildActivityTables(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP VIEW receiver_activity_event_resolved");
            ReceiverActivitySchema.createFormat26ReceiverActivityEventMigrationTable(connection);
            ReceiverActivitySchema.createFormat26ConventionalActivitySummaryMigrationTable(connection);
            statement.executeUpdate("INSERT INTO receiver_activity_event_format26 (" + EVENT_COLUMNS + ") " +
                "SELECT " + EVENT_COLUMNS + " FROM receiver_activity_event");
            statement.executeUpdate("INSERT INTO conventional_activity_summary_format26 (" +
                CONVENTIONAL_SUMMARY_COLUMNS + ") SELECT " + CONVENTIONAL_SUMMARY_COLUMNS +
                " FROM conventional_activity_summary");
            statement.executeUpdate("DROP TABLE receiver_activity_event");
            statement.executeUpdate(
                "ALTER TABLE receiver_activity_event_format26 RENAME TO receiver_activity_event");
            statement.executeUpdate("DROP TABLE conventional_activity_summary");
            statement.executeUpdate("ALTER TABLE conventional_activity_summary_format26 " +
                "RENAME TO conventional_activity_summary");
            ReceiverActivitySchema.createFormat26IndexesAndViews(connection);
        }
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
