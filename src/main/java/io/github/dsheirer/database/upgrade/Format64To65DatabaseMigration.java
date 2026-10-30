/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Adds account-owned call display retention without changing the exact retained Activity contract. */
final class Format64To65DatabaseMigration extends WebUserPreferenceMigration
{
    Format64To65DatabaseMigration() { super(64, 13); }

    @Override public String description() { return "Add personal call display hangtime"; }
    @Override protected String subject() { return "personal call display hangtime"; }
    @Override protected String preservationDetail()
    {
        return "Preserve usable version-12 personal settings, add a five-second call display hangtime, and increment " +
            "each preference revision once; preserve account timestamps, credentials and retained history";
    }
    @Override protected String layoutDetail()
    {
        return "Remove only enough cached table layouts to fit the hangtime choice; preserve all non-layout personal " +
            "settings, accounts, credentials, receiver configuration and retained history";
    }
    @Override protected boolean preserveAccountTimestamps() { return true; }
    @Override protected void validatePreferences(String source) throws IOException
    {
        Format62WebUserPreferencesCodec.validate(source);
    }
    @Override protected Converted convertPreferences(String source) throws IOException
    {
        var migrated = Format65WebUserPreferencesCodec.migrateFromFormat64(source);
        return new Converted(migrated.json(), migrated.resetLayouts());
    }
    @Override protected String defaultPreferences() throws IOException
    {
        return Format65WebUserPreferencesCodec.defaults();
    }
    @Override public List<DatabaseMigrationEffect> declaredEffects() { return super.declaredEffects(false); }
    @Override public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep)
    {
        return super.declaredEffects(false);
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }

    @Override public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        if(ConfigurationHistoryDatabase.isPairedConfiguration(connection))
            return super.migrateSourceAndReport(connection, false);
        if(!ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Call display hangtime migration requires a recognized database role");
        // A preceding split owns this unactivated extraction. Its independent commit cannot expose a partial pair.
        var stage = ActivityDatabasePath.configurationStage(Format62To63DatabaseMigration.databasePath(connection));
        if(!java.nio.file.Files.isRegularFile(stage) || java.nio.file.Files.isSymbolicLink(stage))
            throw new SQLException("The configuration extraction for personal playback settings is missing");
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + stage))
        {
            configuration.setAutoCommit(false);
            if(DatabaseFormatCatalog.inspect(configuration).version() != 64)
                throw new SQLException("Personal playback settings require exact format-64 configuration");
            var effects = super.migrateSourceAndReport(configuration, false);
            DatabaseFormatCatalog.stamp(configuration, 65);
            DatabaseFormatCatalog.requireCurrent(configuration);
            configuration.commit();
            return effects;
        }
    }
}
