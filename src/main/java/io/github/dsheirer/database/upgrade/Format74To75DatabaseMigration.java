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

/** Adds the explicitly decoded regroup type without inferring historical patch or simulselect meaning. */
final class Format74To75DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "p25-regroup-types-v1"; }
    public String description() { return "Retain patch and simulselect grouping types"; }
    public int sourceVersion() { return 74; }
    public int targetVersion() { return 75; }

    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "existing patches and statistics", 0,
            "Preserve every patch, member, counter, setting and recording; historical regroup types remain unknown"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "new P25 regroup observations", 1,
                "Store the decoded patch or simulselect type on its existing site patch and summary"));
    }

    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }

    public void migrateSource(Connection connection) throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        if(!configurationOwner && !ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Regroup type migration requires a recognized database role");
        String companion = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE :
            ConfigurationHistorySchema.CONFIGURATION_ROLE;
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role,
            SqliteSchemaValidator.fingerprint(connection, companion), companion);
        if(!"74".equals(ConfigurationHistorySchema.metadata(connection, companion,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, companion, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("Regroup type migration requires its exact matching format-74 companion");

        ReceiverActivitySchema.addPatchRegroupType(connection, configurationOwner ? "activity" : "main");
        try(var update = connection.prepareStatement("UPDATE " + companion +
            ".database_metadata SET value='75',updated_at_ms=? WHERE key=? AND value='74'"))
        {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1)
                throw new SQLException("The companion format marker changed during regroup type migration");
        }
    }
}
