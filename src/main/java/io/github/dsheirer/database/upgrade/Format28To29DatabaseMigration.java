/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.gui.setup.SetupProgress;
import io.github.dsheirer.gui.setup.SetupStep;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Adds the recording-mode setup step while keeping existing recording preferences untouched. */
final class Format28To29DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-28-to-29"; }
    @Override public String description() { return "Add recording-mode setup choice"; }
    @Override public int sourceVersion() { return 28; }
    @Override public int targetVersion() { return 29; }

    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return declaredEffects(true);
    }

    @Override public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, RepairReport.declared(), selectedSourceStep, true);
    }

    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        long invalid = validLegacyProgress(connection) ? 0 : 1;
        RepairReport repairs = inspectRepairs(connection);
        List<DatabaseMigrationEffect> effects = effects(invalid, repairs, true, false);
        //The conversion accounts for legacy setup damage. Administrative repair sees the converted valid record.
        if(invalid == 0 || repairs.administrative().resetWebAccounts() > 0) return effects;
        return effects.stream().filter(effect -> !effect.subject().equals("unusable setup progress")).toList();
    }

    @Override public void migrateSource(Connection connection) throws SQLException
    {
        migrateSourceAndReport(connection, true);
    }

    @Override public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection,
                                                                     boolean selectedSourceStep) throws SQLException
    {
        boolean valid = validLegacyProgress(connection);
        SetupProgress progress = valid ? SetupProgress.readLegacy(connection) : SetupProgress.replacementReview();
        progress.set(SetupStep.RECORDINGS, SetupProgress.State.CARRIED_OVER);
        SetupProgress.write(connection, progress);
        RepairReport repairs = selectedSourceStep ? repair(connection) : RepairReport.declared();
        return effects(valid ? 0 : 1, repairs, selectedSourceStep, false);
    }

    private static boolean validLegacyProgress(Connection connection)
    {
        try
        {
            SetupProgress.readLegacy(connection);
            return true;
        }
        catch(SQLException ignored)
        {
            return false;
        }
    }

    private static RepairReport inspectRepairs(Connection connection) throws SQLException
    {
        CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
        //Inspect the old shape without changing it. Conversion is counted separately above.
        return new RepairReport(ApplicationDatabaseMigrator.inspectCurrentPortablePreferenceRepairs(connection),
            CurrentDatabaseAdministrativeRepair.inspect(connection, true),
            CurrentDatabaseDerivedStateRepair.inspect(connection),
            CurrentDatabaseBestEffortRepair.inspect(connection));
    }

    private static RepairReport repair(Connection connection) throws SQLException
    {
        CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
        int preferences = ApplicationDatabaseMigrator.repairCurrentPortablePreferences(connection);
        CurrentDatabaseAdministrativeRepair.Inspection administrative =
            CurrentDatabaseAdministrativeRepair.repair(connection, false);
        CurrentDatabaseBestEffortRepair.Inspection configuration = CurrentDatabaseBestEffortRepair.repair(connection);
        CurrentDatabaseDerivedStateRepair.Inspection derived = CurrentDatabaseDerivedStateRepair.repair(connection);
        return new RepairReport(preferences, administrative, derived, configuration);
    }

    private static List<DatabaseMigrationEffect> effects(long invalidProgress, RepairReport repairs,
                                                          boolean selectedSourceStep, boolean declared)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>();
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "setup wizard progress",
            invalidProgress == DatabaseMigrationEffect.UNKNOWN_COUNT ? DatabaseMigrationEffect.UNKNOWN_COUNT :
                invalidProgress == 0 ? 1 : 0,
            "Retain existing step states and carry over Recordings; leave the saved recording mode unchanged"));
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "unusable legacy setup progress", invalidProgress,
            "Only missing or malformed progress is replaced with a bounded destination review state"));
        if(selectedSourceStep)
        {
            effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "unusable portable preference components",
                declared ? DatabaseMigrationEffect.UNKNOWN_COUNT : repairs.portablePreferenceResets(),
                "Preserve usable preferences and reset only malformed components"));
            addRepairs(effects, CurrentDatabaseAdministrativeRepair.effects(repairs.administrative()), declared);
            addRepairs(effects, CurrentDatabaseBestEffortRepair.effects(repairs.configuration()), declared);
            addRepairs(effects, CurrentDatabaseDerivedStateRepair.effects(repairs.derived()), declared);
        }
        return List.copyOf(effects);
    }

    private static void addRepairs(List<DatabaseMigrationEffect> target, List<DatabaseMigrationEffect> repairs,
                                   boolean declared)
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
        static RepairReport declared()
        {
            return new RepairReport(0, CurrentDatabaseAdministrativeRepair.Inspection.none(),
                CurrentDatabaseDerivedStateRepair.Inspection.none(), CurrentDatabaseBestEffortRepair.Inspection.none());
        }
    }
}
