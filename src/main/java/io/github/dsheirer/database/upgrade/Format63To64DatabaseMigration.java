/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.settings.ReceiverNicknameSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Adds bounded configuration only; retained Activity needs neither a write nor an independent commit. */
final class Format63To64DatabaseMigration implements DatabaseMigrationStep
{
    public String id() { return "receiver-nicknames-v1"; }
    public String description() { return "Add optional receiver nicknames"; }
    public int sourceVersion() { return 63; }
    public int targetVersion() { return 64; }

    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(effect(DatabaseMigrationEffect.UNKNOWN_COUNT));
    }

    public List<DatabaseMigrationEffect> inspectSource(Connection connection)
    {
        return declaredEffects();
    }

    public void migrateSource(Connection connection) throws SQLException
    {
        migrateAndCount(connection);
    }

    public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        return List.of(effect(migrateAndCount(connection)));
    }

    private int migrateAndCount(Connection connection) throws SQLException
    {
        if(ConfigurationHistoryDatabase.isPairedConfiguration(connection))
            return ReceiverNicknameSchema.initializeMissing(connection);
        if(!ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Receiver nickname migration requires a recognized database role");
        // A preceding 62->63 step owns this still-unactivated extraction. It is disposable until the original
        // history transaction commits, so upgrading its configuration adds no cross-WAL commit dependency.
        var stage = ActivityDatabasePath.configurationStage(Format62To63DatabaseMigration.databasePath(connection));
        if(!java.nio.file.Files.isRegularFile(stage) || java.nio.file.Files.isSymbolicLink(stage))
            throw new SQLException("The configuration extraction for receiver nicknames is missing");
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + stage))
        {
            configuration.setAutoCommit(false);
            int created = ReceiverNicknameSchema.initializeMissing(configuration);
            DatabaseFormatCatalog.stamp(configuration, targetVersion());
            if(DatabaseFormatCatalog.inspect(configuration).version() != 64)
                throw new SQLException("Receiver nickname migration did not reach exact format 64");
            configuration.commit();
            return created;
        }
    }

    private static DatabaseMigrationEffect effect(long count)
    {
        return new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "receiver nickname settings", count,
            "Begin with no receiver nicknames; preserve usable existing names and every other setting and retained row");
    }
}
