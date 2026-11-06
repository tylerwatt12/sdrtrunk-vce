/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Removes two bounded personal switches; retained Activity needs no conversion. */
final class Format71To72DatabaseMigration extends WebUserPreferenceMigration
{
    Format71To72DatabaseMigration() { super(71, 16); }
    @Override public String description() { return "Use the Decode % column for Live quality visibility"; }
    @Override protected String subject() { return "retired personal decode-quality switches"; }
    @Override protected String preservationDetail()
    {
        return "Remove the separate control and voice decode-quality switches, preserve every other personal choice, " +
            "and increment each preference revision once; preserve account timestamps, credentials and retained history";
    }
    @Override protected String layoutDetail() { return "Preserve every saved table layout; the smaller document needs no trimming"; }
    @Override protected boolean preserveAccountTimestamps() { return true; }
    @Override protected void validatePreferences(String source) throws IOException { Format67WebUserPreferencesCodec.validate(source); }
    @Override protected Converted convertPreferences(String source) throws IOException
    {
        return new Converted(Format72WebUserPreferencesCodec.migrateFromFormat71(source), 0);
    }
    @Override protected String defaultPreferences() throws IOException { return Format72WebUserPreferencesCodec.defaults(); }
    @Override public List<DatabaseMigrationEffect> declaredEffects() { return super.declaredEffects(false); }
    @Override public List<DatabaseMigrationEffect> declaredEffects(boolean selectedSourceStep) { return declaredEffects(); }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }

    @Override public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        ApplicationMigrationTransaction.requireActive(connection);
        boolean configurationOwner = ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        if(!configurationOwner && !ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Live presentation migration requires a recognized database role");
        // On the older monolith path, its extracted configuration is attached by the preceding paired steps.
        // web_user exists only in configuration, so the shared bounded conversion resolves the same owner.
        if(!configurationOwner && !"71".equals(ConfigurationHistorySchema.metadata(connection, "configuration",
            DatabaseFormatCatalog.FORMAT_VERSION_KEY)))
            throw new SQLException("Live presentation migration requires exact format-71 configuration");
        String companion = configurationOwner ? "activity" : "configuration";
        String role = configurationOwner ? ConfigurationHistorySchema.HISTORY_ROLE : ConfigurationHistorySchema.CONFIGURATION_ROLE;
        ConfigurationHistoryDatabase.validateAdmittedRole(connection, role,
            io.github.dsheirer.database.SqliteSchemaValidator.fingerprint(connection, companion), companion);
        if(!"71".equals(ConfigurationHistorySchema.metadata(connection, companion, DatabaseFormatCatalog.FORMAT_VERSION_KEY)) ||
            !java.util.Objects.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(connection, companion, ConfigurationHistorySchema.PAIR_ID_KEY)))
            throw new SQLException("Live presentation migration requires its matching format-71 companion");
        var effects = super.migrateSourceAndReport(connection, false);
        try(var update = connection.prepareStatement("UPDATE " + companion +
            ".database_metadata SET value='72',updated_at_ms=? WHERE key=? AND value='71'"))
        {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            if(update.executeUpdate() != 1) throw new SQLException("Companion format changed during Live presentation migration");
        }
        return effects;
    }
}
