/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Adds an opt-in audio preference only to bounded saved-channel configuration. */
final class Format76To77DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "duplicate-audio-start-order-v1"; }
    public String description() { return "Add per-channel auto-start-order audio preference"; }
    public int sourceVersion() { return 76; }
    public int targetVersion() { return 77; }

    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "configuration and receiver history", 0,
                "Preserve channels, auto-start positions, settings, recordings and every retained event and index"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "channel audio-order preference", 0,
                "Begin disabled on every existing channel; scan only saved channel configuration to add its check"));
    }

    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }

    public void migrateSource(Connection connection) throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        if(!configurationOwner && !ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Audio-order preference migration requires a recognized database role");
        String companion = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE :
            ConfigurationHistorySchema.CONFIGURATION_ROLE;
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role,
            SqliteSchemaValidator.fingerprint(connection, companion), companion);
        if(!"76".equals(ConfigurationHistorySchema.metadata(connection, companion,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, companion, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("Audio-order preference requires its exact matching format-76 companion");

        // ADD COLUMN preserves the configuration table and indexes. Its boolean CHECK visits only channel rows.
        // A pre-63 monolith completing the same adjacent chain has its configuration attached at this point.
        ConfigurationHistorySchema.addAudioOrderPreference(connection, configurationOwner ? "main" : "configuration");
        try(var update = connection.prepareStatement("UPDATE " + companion +
            ".database_metadata SET value='77',updated_at_ms=? WHERE key=? AND value='76'"))
        {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1)
                throw new SQLException("The companion format marker changed during audio-order preference migration");
        }
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role,
            SqliteSchemaValidator.fingerprint(connection, companion), companion);
    }
}
