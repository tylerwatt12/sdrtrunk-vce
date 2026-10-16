/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ToneActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Prospective call annotations and analog waveform evidence; historical standalone tone rows remain intact. */
final class Format54To55DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-54-to-55"; }
    @Override public String description() { return "Attach detected tones to their original calls"; }
    @Override public int sourceVersion() { return 54; }
    @Override public int targetVersion() { return 55; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "configuration, Activity and tone evidence", 0,
            "Preserve every setting, call classification, counter and historical standalone tone row without guessed associations"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "prospective analog tone evidence", 1,
                "Store new analog single-tone and DTMF facts on their exact original call; retain disabled CTCSS for channels without a selection"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException { ToneActivitySchema.upgradeFrom54(connection); }
}
