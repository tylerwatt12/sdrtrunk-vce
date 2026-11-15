/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Adds optional raw denial evidence; previously saved Activity has no reason to reconstruct. */
final class Format73To74DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "p25-denial-reasons-v1"; }
    public String description() { return "Retain raw P25 denial reasons and vendor-specific selection"; }
    public int sourceVersion() { return 73; }
    public int targetVersion() { return 74; }

    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "existing Activity and statistics", 0,
            "Preserve every Activity entry, counter, setting and recording; historical denial reasons remain unknown"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "new P25 denial Activity", 1,
                "Store the raw reason code and vendor-specific flag on its existing Activity entry"));
    }

    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }

    public void migrateSource(Connection connection) throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        if(!configurationOwner && !ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Denial reason migration requires a recognized database role");
        String companion = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE :
            ConfigurationHistorySchema.CONFIGURATION_ROLE;
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role,
            SqliteSchemaValidator.fingerprint(connection, companion), companion);
        if(!"73".equals(ConfigurationHistorySchema.metadata(connection, companion,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, companion, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("Denial reason migration requires its exact matching format-73 companion");

        ReceiverActivitySchema.addDenialReasons(connection, configurationOwner ? "activity" : "main");
        try(var update = connection.prepareStatement("UPDATE " + companion +
            ".database_metadata SET value='74',updated_at_ms=? WHERE key=? AND value='73'"))
        {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1)
                throw new SQLException("The companion format marker changed during denial reason migration");
        }
    }
}
