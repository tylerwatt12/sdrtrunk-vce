/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Consolidates matching P25 home-system owners without introducing another identity relationship. */
final class Format44To45DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-44-to-45"; }
    @Override public String description() { return "Consolidate matching P25 radio identities"; }
    @Override public int sourceVersion() { return 44; }
    @Override public int targetVersion() { return 45; }

    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT,
            DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        // Preflight does not scan retained history; exact consolidation counts come from the single real migration.
        return declaredEffects();
    }

    @Override public void migrate(Connection connection) throws SQLException
    {
        migrateAndReport(connection);
    }

    @Override public List<DatabaseMigrationEffect> migrateAndReport(Connection connection) throws SQLException
    {
        requireSource(connection);
        var result = ReceiverActivitySchema.consolidateP25HomeRadioIdentities(connection);
        ReceiverActivitySchema.rebuildP25HomeIdentityKeyView(connection);
        return effects(result.promotedIdentities(), result.mergedIdentities(), result.skippedAmbiguousIdentities());
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 44)
        {
            throw new SQLException("Expected format 44");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long promoted, long merged, long ambiguous)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "ordinary P25 radio identities normalized", promoted,
                "Use the exact serving WACN and System ID without inventing a canonical subscriber association"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "matching P25 home-system radio identities consolidated", merged,
                "Keep one owner for Activity, names, talkgroups and outputs; preserve accepted historical counter credits, system totals, retained history and allocator high-water marks without guessing unrecorded call overlap"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "ambiguous P25 radio identities retained separately", ambiguous,
                "Preserve uncertain historical ownership when retained foreign Working-ID evidence prevents a safe match"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "resolved Activity identity-key view", 1,
                "Use the stored home identity for consolidated radio owners while preserving uncertain historical local keys"));
    }
}
