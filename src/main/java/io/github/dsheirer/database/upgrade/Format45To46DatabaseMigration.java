/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.util.List;

/** Establishes nullable confirmed discovery identity in the existing saved-channel document. */
final class Format45To46DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-45-to-46"; }
    @Override public String description() { return "Keep confirmed discovery identity with its saved channel"; }
    @Override public int sourceVersion() { return 45; }
    @Override public int targetVersion() { return 46; }

    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "saved channels and receiver configuration", 0,
            "Preserve existing documents exactly; absent DMR or NXDN discovery identity stays unknown without history backfill"));
    }

    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection)
    {
        return declaredEffects();
    }

    @Override public void migrateSource(Connection connection)
    {
        //Prospective optional document field only. The shared runner stamps the exact unchanged target schema.
    }
}
