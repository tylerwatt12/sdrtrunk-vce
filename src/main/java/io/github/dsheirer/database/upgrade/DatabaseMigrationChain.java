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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Strict linear runner for the complete adjacent migration chain. */
public final class DatabaseMigrationChain
{
    private static final List<DatabaseMigrationStep> ORDERED_STEPS = List.of(
        new Format1To2DatabaseMigration(), new Format2To3DatabaseMigration(),
        new Format3To4DatabaseMigration(), new Format4To5DatabaseMigration());
    private static final Map<Integer,DatabaseMigrationStep> STEPS = ORDERED_STEPS.stream().collect(
        Collectors.toUnmodifiableMap(DatabaseMigrationStep::sourceVersion, Function.identity()));

    private DatabaseMigrationChain()
    {
    }

    /**
     * Re-inspects and validates the selected source without writing it. This is suitable for parent-process
     * preflight before the staged child process is launched.
     */
    public static PreflightReport validateSource(Connection connection,
                                                 DatabaseFormatCatalog.DetectedFormat expected) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat actual = DatabaseFormatCatalog.inspect(connection);

        if(actual.version() != expected.version() || !actual.id().equals(expected.id()) ||
            actual.markerPresent() != expected.markerPresent())
        {
            throw new SQLException("SQLite database changed after inspection: expected [" + expected.id() +
                ", marker=" + expected.markerPresent() + "] but found [" + actual.id() + ", marker=" +
                actual.markerPresent() + "]");
        }

        List<StepPreflight> steps = new ArrayList<>();
        int version = actual.version();
        boolean sourceLayoutAvailable = true;

        while(version < DatabaseFormatCatalog.CURRENT_VERSION)
        {
            DatabaseMigrationStep step = requireStep(version);
            requireAdjacent(step);
            List<DatabaseMigrationEffect> effects = sourceLayoutAvailable ? step.validateSource(connection) :
                step.declaredEffects();
            steps.add(new StepPreflight(step.id(), step.description(), step.sourceVersion(), step.targetVersion(),
                List.copyOf(effects)));
            version = step.targetVersion();
            sourceLayoutAvailable = false;
        }

        if(!actual.markerPresent() && actual.version() == DatabaseFormatCatalog.CURRENT_VERSION)
        {
            steps.add(markerAdoptionPreflight(actual.version()));
        }

        return new PreflightReport(actual, DatabaseFormatCatalog.current(), List.copyOf(steps));
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
        DatabaseFormatCatalog.DetectedFormat source = DatabaseFormatCatalog.inspect(connection);
        List<StepReport> reports = new ArrayList<>();
        DatabaseFormatCatalog.DetectedFormat detected = source;

        while(detected.version() < DatabaseFormatCatalog.CURRENT_VERSION)
        {
            DatabaseMigrationStep step = requireStep(detected.version());
            requireAdjacent(step);
            List<DatabaseMigrationEffect> effects = List.copyOf(step.validateSource(connection));
            step.migrate(connection);
            DatabaseFormatCatalog.stamp(connection, step.targetVersion());
            DatabaseFormatCatalog.DetectedFormat target = DatabaseFormatCatalog.inspect(connection);

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
            DatabaseFormatCatalog.stamp(connection, detected.version());
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

            long transformed = effectCount(DatabaseMigrationEffect.Kind.TRANSFORM) +
                effectCount(DatabaseMigrationEffect.Kind.DEFAULT);
            long reset = effectCount(DatabaseMigrationEffect.Kind.RESET);
            long dropped = effectCount(DatabaseMigrationEffect.Kind.DROP);
            return "Migrated database format " + source.version() + " [" + source.id() + "] to " +
                target.version() + " [" + target.id() + "] through " + steps.size() +
                " step(s): transformed/defaulted " + transformed + ", reset " + reset + ", and dropped " +
                dropped + " counted row(s).";
        }

        private long effectCount(DatabaseMigrationEffect.Kind kind)
        {
            return steps.stream().flatMap(step -> step.effects().stream()).filter(effect -> effect.kind() == kind)
                .mapToLong(DatabaseMigrationEffect::affectedRows).sum();
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
