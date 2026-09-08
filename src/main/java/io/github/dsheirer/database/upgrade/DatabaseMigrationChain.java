/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Linear staged runner for the complete adjacent migration chain. */
public final class DatabaseMigrationChain
{
    private static final List<DatabaseMigrationStep> ORDERED_STEPS = List.of(
        new Format1To2DatabaseMigration(), new Format2To3DatabaseMigration(),
        new Format3To4DatabaseMigration(), new Format4To5DatabaseMigration(),
        new Format5To6DatabaseMigration(), new Format6To7DatabaseMigration(),
        new Format7To8DatabaseMigration(), new Format8To9DatabaseMigration(),
        new Format9To10DatabaseMigration(), new Format10To11DatabaseMigration(), new Format11To12DatabaseMigration(),
        new Format12To13DatabaseMigration(), new Format13To14DatabaseMigration(),
        new Format14To15DatabaseMigration());
    private static final Map<Integer,DatabaseMigrationStep> STEPS = ORDERED_STEPS.stream().collect(
        Collectors.toUnmodifiableMap(DatabaseMigrationStep::sourceVersion, Function.identity()));

    private DatabaseMigrationChain()
    {
    }

    /** Re-inspects the selected source and returns the immutable declared plan without modifying it. */
    public static PreflightReport validateSource(Connection connection,
                                                 DatabaseFormatCatalog.DetectedFormat expected) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat actual = DatabaseFormatCatalog.inspectForMigration(connection);

        if(actual.version() != expected.version() || !actual.id().equals(expected.id()) ||
            actual.markerPresent() != expected.markerPresent())
        {
            throw new SQLException("SQLite database changed after inspection: expected [" + expected.id() +
                ", marker=" + expected.markerPresent() + "] but found [" + actual.id() + ", marker=" +
                actual.markerPresent() + "]");
        }

        return plan(actual);
    }

    /** Builds the ordered plan from the checked catalog identity without scanning every source subsystem. */
    static PreflightReport plan(DatabaseFormatCatalog.DetectedFormat source) throws SQLException
    {
        List<StepPreflight> steps = new ArrayList<>();
        int version = source.version();

        while(version < DatabaseFormatCatalog.CURRENT_VERSION)
        {
            DatabaseMigrationStep step = requireStep(version);
            requireAdjacent(step);
            steps.add(new StepPreflight(step.id(), step.description(), step.sourceVersion(), step.targetVersion(),
                List.copyOf(step.declaredEffects())));
            version = step.targetVersion();
        }

        if(!source.markerPresent() && source.version() == DatabaseFormatCatalog.CURRENT_VERSION)
        {
            steps.add(markerAdoptionPreflight(source.version()));
        }

        return new PreflightReport(source, DatabaseFormatCatalog.current(), List.copyOf(steps));
    }

    /** Adds the bounded, same-schema preference sanitizer run by the Application Migrator when it may be needed. */
    static PreflightReport planForApplicationMigration(Connection connection,
                                                        DatabaseFormatCatalog.DetectedFormat source)
        throws SQLException
    {
        PreflightReport schemaPlan = plan(source);
        List<StepPreflight> steps = new ArrayList<>();
        StepPreflight preferenceRepair = new StepPreflight("repair-portable-preferences",
            "Validate and independently repair portable preference components",
            DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.CURRENT_VERSION,
            List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "unusable portable preference components", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Preserve usable entries and remove or reset only malformed, obsolete, or invalid components")));

        SqliteIdentityRepair.Inspection identityRepair = SqliteIdentityRepair.inspect(connection);
        if(source.version() < DatabaseFormatCatalog.CURRENT_VERSION && identityRepair.requiresRepair())
        {
            //Allocator damage must be repaired before historical steps create factory rows.
            steps.add(SqliteIdentityRepair.preflight(identityRepair, source.version()));
        }

        if(source.version() == DatabaseFormatCatalog.CURRENT_VERSION && currentPortablePreferencesNeedRepair(connection))
        {
            //Current-format preference repair runs before strict marker adoption.
            steps.add(preferenceRepair);
        }
        if(source.version() == DatabaseFormatCatalog.CURRENT_VERSION)
        {
            CurrentDatabaseAdministrativeRepair.Inspection administrativeRepair =
                CurrentDatabaseAdministrativeRepair.inspect(connection);
            if(administrativeRepair.requiresRepair())
            {
                steps.add(CurrentDatabaseAdministrativeRepair.preflight(administrativeRepair));
            }
            CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
            CurrentDatabaseDerivedStateRepair.Inspection derivedStateRepair =
                CurrentDatabaseDerivedStateRepair.inspect(connection);
            if(derivedStateRepair.requiresRepair())
            {
                steps.add(CurrentDatabaseDerivedStateRepair.preflight(derivedStateRepair));
            }
            CurrentDatabaseBestEffortRepair.Inspection currentRepair =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            if(currentRepair.requiresRepair())
            {
                steps.add(CurrentDatabaseBestEffortRepair.preflight(currentRepair));
            }
            if(identityRepair.requiresRepair())
            {
                //Current component repair first isolates JSON-unsafe owners; allocator repair then uses the retained
                //maximum. Keep the displayed plan in the same order as execution.
                steps.add(SqliteIdentityRepair.preflight(identityRepair, source.version()));
            }
        }
        steps.addAll(schemaPlan.steps());
        if(source.version() < DatabaseFormatCatalog.CURRENT_VERSION)
        {
            //Historical preference layouts reach current semantics through the chain before this final sanitizer.
            steps.add(preferenceRepair);
        }
        return new PreflightReport(schemaPlan.source(), schemaPlan.target(), List.copyOf(steps));
    }

    private static boolean currentPortablePreferencesNeedRepair(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            """
            SELECT CASE WHEN typeof(settings_json)='text'
                              AND length(CAST(settings_json AS BLOB)) <= 4194304
                        THEN settings_json END AS settings_json,
                   updated_at_ms, typeof(settings_json), typeof(updated_at_ms),
                   length(CAST(settings_json AS BLOB)) AS settings_json_bytes
            FROM application_settings WHERE key='portable_java_preferences_v1'
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            if(!resultSet.next())
            {
                return false;
            }

            String json = resultSet.getString(1);
            if(!"text".equals(resultSet.getString(3)) || !"integer".equals(resultSet.getString(4)) ||
                resultSet.getLong(2) <= 0 || resultSet.getLong("settings_json_bytes") > 4_194_304)
            {
                return true;
            }
            try
            {
                Format5WebStateValidator.validateCurrentPortablePreferences(json);
                return false;
            }
            catch(SQLException ignored)
            {
                //The reason may contain a preference key, so expose only the value-free declared repair action.
                return true;
            }
        }
    }

    /** Ordered immutable adjacent-step manifest. */
    public static List<StepDescriptor> steps()
    {
        return ORDERED_STEPS.stream().map(step -> new StepDescriptor(step.id(), step.description(),
            step.sourceVersion(), step.targetVersion(), List.copyOf(step.declaredEffects()))).toList();
    }

    /** Runs every required adjacent step on the caller-provided staged connection. */
    public static MigrationReport migrate(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspectForMigration(connection);
        List<StepReport> reports = new ArrayList<>();
        DatabaseFormatCatalog.DetectedFormat detected = source;

        while(detected.version() < DatabaseFormatCatalog.CURRENT_VERSION)
        {
            DatabaseMigrationStep step = requireStep(detected.version());
            requireAdjacent(step);
            List<DatabaseMigrationEffect> effects = List.copyOf(step.migrateAndReport(connection));
            requireObservedCounts(step, effects);
            DatabaseFormatCatalog.stampForMigration(connection, step.targetVersion());
            DatabaseFormatCatalog.DetectedFormat target = DatabaseFormatCatalog.inspectForMigration(connection);

            if(target.version() != step.targetVersion() || !target.markerPresent())
            {
                throw new SQLException("Migration step [" + step.id() + "] did not produce exact target format " +
                    step.targetVersion());
            }

            reports.add(new StepReport(step.id(), step.description(), step.sourceVersion(), step.targetVersion(),
                effects));
            detected = target;
        }

        if(!detected.markerPresent())
        {
            DatabaseFormatCatalog.stampForMigration(connection, detected.version());
            detected = DatabaseFormatCatalog.requireCurrent(connection);
            StepPreflight adoption = markerAdoptionPreflight(detected.version());
            reports.add(new StepReport(adoption.id(), adoption.description(), adoption.sourceVersion(),
                adoption.targetVersion(), adoption.effects()));
        }

        DatabaseFormatCatalog.requireCurrent(connection);
        return new MigrationReport(source, detected, List.copyOf(reports));
    }

    private static DatabaseMigrationStep requireStep(int sourceVersion) throws SQLException
    {
        DatabaseMigrationStep step = STEPS.get(sourceVersion);

        if(step == null)
        {
            throw new SQLException("No adjacent database migration step registered from format " + sourceVersion);
        }

        return step;
    }

    private static void requireAdjacent(DatabaseMigrationStep step) throws SQLException
    {
        if(step.targetVersion() != step.sourceVersion() + 1)
        {
            throw new SQLException("Database migration step [" + step.id() + "] is not adjacent: " +
                step.sourceVersion() + " -> " + step.targetVersion());
        }
    }

    private static void requireObservedCounts(DatabaseMigrationStep step, List<DatabaseMigrationEffect> effects)
        throws SQLException
    {
        for(DatabaseMigrationEffect effect: effects)
        {
            if(effect.affectedRows() < 0)
            {
                throw new SQLException("Migration step [" + step.id() + "] did not report an exact completion " +
                    "count for " + effect.subject());
            }
        }
    }

    private static StepPreflight markerAdoptionPreflight(int version)
    {
        return new StepPreflight("adopt-global-format-marker", "Adopt the authoritative whole-file format marker",
            version, version, List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                DatabaseFormatCatalog.FORMAT_VERSION_KEY, 1,
                "Record the already-recognized exact legacy layout as global format " + version)));
    }

    public record PreflightReport(DatabaseFormatCatalog.DetectedFormat source,
                                  DatabaseFormatCatalog.FormatDescriptor target, List<StepPreflight> steps)
    {
        public boolean requiresMigration()
        {
            return source.requiresMigration() || !steps.isEmpty();
        }
    }

    public record StepPreflight(String id, String description, int sourceVersion, int targetVersion,
                                List<DatabaseMigrationEffect> effects)
    {
    }

    public record MigrationReport(DatabaseFormatCatalog.DetectedFormat source,
                                  DatabaseFormatCatalog.DetectedFormat target, List<StepReport> steps)
    {
        public String releaseSummary()
        {
            if(steps.isEmpty())
            {
                return "Database is already at current format " + target.version() + ".";
            }

            return "Migrated database format " + source.version() + " [" + source.id() + "] to " +
                target.version() + " [" + target.id() + "] through " + steps.size() +
                " step(s). See the itemized completion counts above.";
        }
    }

    public record StepReport(String id, String description, int sourceVersion, int targetVersion,
                             List<DatabaseMigrationEffect> effects)
    {
    }

    public record StepDescriptor(String id, String description, int sourceVersion, int targetVersion,
                                 List<DatabaseMigrationEffect> declaredEffects)
    {
    }
}
