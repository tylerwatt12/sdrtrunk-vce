/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Versions saved reporting choices without rewriting provider payloads or retained receiver history. */
final class Format77To78DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "broadcastify-health-reporting-v1"; }
    public String description() { return "Preserve saved Broadcastify health reporting choices"; }
    public int sourceVersion() { return 77; }
    public int targetVersion() { return 78; }

    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "saved configuration and receiver history", 0,
                "Preserve every provider payload, explicit reporting choice, setting, event, index and recording"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "Broadcastify health reporting preference", 1,
                "Version the saved choice for both Calls providers; an absent choice remains enabled, and explicit " +
                    "false or true remains unchanged without rewriting JSON"));
    }

    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }

    public void migrateSource(Connection connection) throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        if(!configurationOwner && !ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Broadcastify preference migration requires a recognized database role");

        // The owned pair or the earlier split step already attached its companion before BEGIN. The chain stamps
        // the owning marker after this returns; both marker writes share the same transaction and no JSON changes.
        String companion = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE :
            ConfigurationHistorySchema.CONFIGURATION_ROLE;
        String fingerprint = SqliteSchemaValidator.fingerprint(connection, companion);
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role, fingerprint, companion);
        if(!"77".equals(ConfigurationHistorySchema.metadata(connection, companion,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, companion, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("Broadcastify preference migration requires its exact matching format-77 companion");

        try(var update = connection.prepareStatement("UPDATE " + companion +
            ".database_metadata SET value='78',updated_at_ms=? WHERE key=? AND value='77'"))
        {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1)
                throw new SQLException("The companion format marker changed during Broadcastify preference migration");
        }
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role, fingerprint, companion);
    }
}
