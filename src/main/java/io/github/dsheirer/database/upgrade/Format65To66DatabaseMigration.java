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

/** Adds the account-owned recording click action without rewriting retained Activity. */
final class Format65To66DatabaseMigration extends WebUserPreferenceMigration
{
    Format65To66DatabaseMigration() { super(65, 14); }

    @Override public String description() { return "Remember personal recording click action"; }
    @Override protected String subject() { return "personal recording click action"; }
    @Override protected String preservationDetail()
    {
        return "Preserve usable version-13 personal settings, add queue as the recording click action, and increment " +
            "each preference revision once; preserve account timestamps, credentials and retained history";
    }
    @Override protected String layoutDetail()
    {
        return "Remove only enough cached table layouts to fit the recording click action; preserve all non-layout " +
            "personal settings, accounts, credentials, receiver configuration and retained history";
    }
    @Override protected boolean preserveAccountTimestamps() { return true; }
    @Override protected void validatePreferences(String source) throws IOException
    {
        Format65WebUserPreferencesCodec.validate(source);
    }
    @Override protected Converted convertPreferences(String source) throws IOException
    {
        var migrated = Format66WebUserPreferencesCodec.migrateFromFormat65(source);
        return new Converted(migrated.json(), migrated.resetLayouts());
    }
    @Override protected String defaultPreferences() throws IOException
    {
        return Format66WebUserPreferencesCodec.defaults();
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
            throw new SQLException("Recording click action migration requires a recognized database role");
        // The preceding split owns this unactivated extraction until both roles pass final validation.
        var stage = ActivityDatabasePath.configurationStage(Format62To63DatabaseMigration.databasePath(connection));
        if(!java.nio.file.Files.isRegularFile(stage) || java.nio.file.Files.isSymbolicLink(stage))
            throw new SQLException("The configuration extraction for recording click action is missing");
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + stage))
        {
            configuration.setAutoCommit(false);
            if(DatabaseFormatCatalog.inspect(configuration).version() != 65)
                throw new SQLException("Recording click action requires exact format-65 configuration");
            var effects = super.migrateSourceAndReport(configuration, false);
            DatabaseFormatCatalog.stamp(configuration, 66);
            if(DatabaseFormatCatalog.inspect(configuration).version() != 66)
                throw new SQLException("Recording click action did not reach exact format-66 configuration");
            configuration.commit();
            return effects;
        }
    }
}
