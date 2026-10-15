/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Versions profile-scoped legacy JSON import without guessing which existing SQLite choices are defaults. */
final class Format53To54DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-53-to-54"; }
    @Override public String description() { return "Preserve legacy receiver settings during profile updates"; }
    @Override public int sourceVersion() { return 53; }
    @Override public int targetVersion() { return 54; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "existing SQLite receiver settings and credentials", 0,
            "Preserve every saved database choice; recovery of an earlier default requires an explicit reviewed selection"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "profile-scoped legacy settings import", 1,
                "Import missing pre-format-53 receiver settings only from an owned profile or selected folder; leave original files unchanged"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException {}
}
