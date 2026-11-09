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

/** Uses the existing rowid suffix for equal-time ordering in nine Activity indexes. */
final class Format72To73DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "activity-index-rowid-order-v1"; }
    public String description() { return "Rebuild nine smaller Activity indexes"; }
    public int sourceVersion() { return 72; }
    public int targetVersion() { return 73; }

    private static List<DatabaseMigrationEffect> effects(long count)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "Rebuilt Activity indexes", count,
            "Preserve every stored event, relationship, learned record, counter, setting and recording. " +
                "Rebuilding the indexes reads retained Activity and needs temporary disk space."));
    }

    public List<DatabaseMigrationEffect> declaredEffects() { return effects(9); }
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    public void migrateSource(Connection connection) throws SQLException { migrateSourceAndReport(connection); }

    @Override
    public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection) throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        String companion = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE :
            ConfigurationHistorySchema.CONFIGURATION_ROLE;
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role,
            SqliteSchemaValidator.fingerprint(connection, companion), companion);
        if(!Integer.toString(sourceVersion()).equals(ConfigurationHistorySchema.metadata(connection, companion,
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, companion, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("Activity index migration requires its matching format-72 companion");

        // The runner stamps main. The companion advances in the same transaction, including on the legacy split path.
        String activity = configurationOwner ? "activity" : "main";
        int rebuilt = ReceiverActivitySchema.migrateFormat72EventTimeIndexes(connection, activity);
        if(configurationOwner)
        {
            String fingerprint = SqliteSchemaValidator.fingerprint(connection, activity);
            if(!ConfigurationHistoryDatabase.HISTORY_FINGERPRINT.equals(fingerprint))
                throw new SQLException("Activity index migration did not reach the exact target schema: " + fingerprint);
        }
        try(var update = connection.prepareStatement("UPDATE " + companion +
            ".database_metadata SET value=?,updated_at_ms=? WHERE key=? AND value=?"))
        {
            update.setString(1, Integer.toString(targetVersion()));
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            update.setString(4, Integer.toString(sourceVersion()));
            if(update.executeUpdate() != 1)
                throw new SQLException("The companion format marker changed during Activity index migration");
        }
        return effects(rebuilt);
    }
}
