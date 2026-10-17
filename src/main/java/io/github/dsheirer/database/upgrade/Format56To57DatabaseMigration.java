/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.util.List;

/** Versions additional nominal analog paging identities without rewriting retained tone evidence. */
final class Format56To57DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-56-to-57"; }
    @Override public String description() { return "Support additional analog paging tone frequencies"; }
    @Override public int sourceVersion() { return 56; }
    @Override public int targetVersion() { return 57; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "configuration and receiver history", 0,
            "Preserve every setting, call, tone fact, counter, relationship and allocator; admit additional prospective analog nominal frequencies without renormalizing history"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection)
    {
        // Method 4 remains analog PCM nominal single tones/DTMF. Only its admitted single-tone set expands.
        // The shared transaction runner stamps the new semantic contract with the unchanged exact DDL.
    }
}
