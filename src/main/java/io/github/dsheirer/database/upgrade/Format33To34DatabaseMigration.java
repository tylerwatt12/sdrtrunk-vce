/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Establishes corrected prospective receiver evidence semantics without guessing historical observations. */
final class Format33To34DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-33-to-34"; }
    @Override public String description() { return "Improve receiver identity and observation accounting"; }
    @Override public int sourceVersion() { return 33; }
    @Override public int targetVersion() { return 34; }

    @Override public List<DatabaseMigrationEffect> declaredEffects() { return effects(); }

    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        return effects();
    }

    @Override public void migrateSource(Connection connection) throws SQLException
    {
        // Historical snapshots discarded reception provenance, and outputs lack an exact late-source call link.
        // Preserve these rows rather than fabricating a reconstruction or clearing unrelated history.
    }


    private static List<DatabaseMigrationEffect> effects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "receiver observation accounting", 1,
            "Use corrected identity types, late conventional attribution, and fresh site-fact evidence for new " +
                "observations. Preserve all existing configuration and history; historical counts and timestamps " +
                "are not reconstructed from incomplete evidence."));
    }
}
