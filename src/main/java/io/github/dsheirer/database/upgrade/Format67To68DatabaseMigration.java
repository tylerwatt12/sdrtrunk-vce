/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Converts one outbound connection into a stable, bounded destination list without changing its choices. */
final class Format67To68DatabaseMigration implements DatabaseMigrationStep
{
    private static final ObjectMapper JSON = new ObjectMapper();
    public String id() { return "remote-link-destinations-v1"; }
    public String description() { return "Support independent Remote Link destinations"; }
    public int sourceVersion() { return 67; }
    public int targetVersion() { return 68; }
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    public void migrateSource(Connection connection) throws SQLException { migrateSourceAndReport(connection, false); }
    public List<DatabaseMigrationEffect> migrateSourceAndReport(Connection connection, boolean selectedSourceStep)
        throws SQLException
    {
        if(ConfigurationHistoryDatabase.isPairedConfiguration(connection)) return effects(convert(connection));
        if(!ConfigurationHistorySchema.HISTORY_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY)))
            throw new SQLException("Remote Link destination migration requires a recognized database role");
        var stage = ActivityDatabasePath.configurationStage(Format62To63DatabaseMigration.databasePath(connection));
        if(!java.nio.file.Files.isRegularFile(stage) || java.nio.file.Files.isSymbolicLink(stage))
            throw new SQLException("The configuration extraction for Remote Link destinations is missing");
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + stage))
        {
            configuration.setAutoCommit(false);
            if(DatabaseFormatCatalog.inspect(configuration).version() != 67)
                throw new SQLException("Remote Link destination migration requires exact format-67 configuration");
            long converted = convert(configuration);
            DatabaseFormatCatalog.stamp(configuration, targetVersion());
            DatabaseFormatCatalog.requireCurrent(configuration);
            configuration.commit();
            return effects(converted);
        }
    }

    private static int convert(Connection connection) throws SQLException
    {
        try(var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key=?"))
        {
            query.setString(1, ReceiverSettingsSchema.REMOTE_LINKS_KEY);
            try(var row = query.executeQuery())
            {
                if(!row.next()) throw new SQLException("Remote Links settings are missing");
                String source = row.getString(1);
                Format67ReceiverSettingsSchema.validateJson(ReceiverSettingsSchema.REMOTE_LINKS_KEY, source);
                var settings = Format67ReceiverSettingsSchema.readRemote(source);
                String converted = JSON.writeValueAsString(settings);
                ReceiverSettingsSchema.validateJson(ReceiverSettingsSchema.REMOTE_LINKS_KEY, converted);
                try(var update = connection.prepareStatement("UPDATE application_settings SET settings_json=? WHERE key=?"))
                {
                    update.setString(1, converted);
                    update.setString(2, ReceiverSettingsSchema.REMOTE_LINKS_KEY);
                    if(update.executeUpdate() != 1) throw new SQLException("Remote Links settings could not be converted");
                }
                return settings.destinations().size();
            }
        }
        catch(java.io.IOException | RuntimeException failure)
        {
            // Mapping failures may contain credential material; never expose the cause or input.
            throw new SQLException("Remote Links settings could not be converted safely");
        }
    }
    private static List<DatabaseMigrationEffect> effects(long count)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "Remote Link destinations", count,
            "Retain the existing connection as one stable destination, preserving its host, credentials, enabled state " +
                "and selected feeds; preserve every other setting, account and retained observation"));
    }
}
