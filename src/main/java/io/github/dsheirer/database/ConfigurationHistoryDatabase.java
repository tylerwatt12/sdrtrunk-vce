/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import io.github.dsheirer.database.upgrade.DatabaseFormatCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteConfig;

/** Exact admission of the two files which make one portable receiver profile. Never creates or repairs a file. */
public final class ConfigurationHistoryDatabase
{
    // Both fingerprints are frozen by format 63. A role cannot substitute for its exact schema.
    public static final String CONFIGURATION_FINGERPRINT = "fb1754c8bf00abc163ad8955f6452151d88477768f7848fd8e71f25fdc6d93d1";
    public static final String HISTORY_FINGERPRINT = "a359803e101642d77f5ce5591ea2e493bde753696c8ee9e6c0180f5995717d3d";
    private ConfigurationHistoryDatabase()
    {
    }

    public static Path companionPath(Path configuration)
    {
        return ActivityDatabasePath.forConfiguration(configuration);
    }

    public static boolean isPairedConfiguration(Connection connection) throws SQLException
    {
        return ConfigurationHistorySchema.CONFIGURATION_ROLE.equals(
            ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY));
    }

    public static boolean isPairedConfiguration(Path path) throws IOException, SQLException
    {
        if(!Files.isRegularFile(path))
        {
            throw new IOException("Configuration database is missing");
        }
        try(Connection connection = openReadOnly(path))
        {
            return isPairedConfiguration(connection);
        }
    }

    public static void validateRole(Connection connection, String role) throws SQLException
    {
        String actualRole = ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.ROLE_KEY);
        String pairId = ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY);
        if(!role.equals(actualRole) || pairId == null || !pairId.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        {
            throw new SQLException("The configuration/activity database identity is missing or invalid");
        }
        String expected = role.equals(ConfigurationHistorySchema.CONFIGURATION_ROLE) ?
            CONFIGURATION_FINGERPRINT : HISTORY_FINGERPRINT;
        if(!expected.equals(SqliteSchemaValidator.fingerprint(connection)))
        {
            throw new SQLException("The " + role + " database does not have the exact format-63 schema");
        }
        if(!"63".equals(ConfigurationHistorySchema.metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY)))
        {
            throw new SQLException("The " + role + " database format marker is missing or incompatible");
        }
        if(role.equals(ConfigurationHistorySchema.HISTORY_ROLE))
        {
            io.github.dsheirer.stats.activity.ActivityMessageCatalog.validate(connection);
            for(String key: java.util.List.of("conventional_call_output_metrics_started_at_ms",
                "trunked_logical_call_metrics_started_at_ms", "radio_system_metrics_started_at_ms"))
            {
                String value = ConfigurationHistorySchema.metadata(connection, key);
                try
                {
                    if(value == null || Long.parseLong(value) <= 0 || !Long.toString(Long.parseLong(value)).equals(value))
                    {
                        throw new NumberFormatException();
                    }
                }
                catch(NumberFormatException invalid)
                {
                    throw new SQLException("The activity database is missing a valid collection boundary", invalid);
                }
            }
        }
    }

    public static void validatePair(Path configuration) throws IOException, SQLException
    {
        validatePair(configuration, true);
    }

    /** Migration/snapshot admission preserves repairable configuration content while requiring exact paired roles. */
    public static void validatePairIdentity(Path configuration) throws IOException, SQLException
    {
        validatePair(configuration, false);
    }

    private static void validatePair(Path configuration, boolean validateConfigurationContent)
        throws IOException, SQLException
    {
        requireNoPendingSplit(configuration);
        Path history = companionPath(configuration);
        if(Files.isSymbolicLink(configuration) || Files.isSymbolicLink(history) ||
            !Files.isRegularFile(configuration) || !Files.isRegularFile(history))
        {
            throw new IOException("A complete configuration/activity database pair is required. " +
                "Restore both matching files or complete the Application Migrator update.");
        }
        try(Connection config = openReadOnly(configuration); Connection activity = openReadOnly(history))
        {
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(config);
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(activity);
            validateRole(config, ConfigurationHistorySchema.CONFIGURATION_ROLE);
            validateRole(activity, ConfigurationHistorySchema.HISTORY_ROLE);
            if(!ConfigurationHistorySchema.metadata(config, ConfigurationHistorySchema.PAIR_ID_KEY).equals(
                ConfigurationHistorySchema.metadata(activity, ConfigurationHistorySchema.PAIR_ID_KEY)))
            {
                throw new SQLException("The configuration and activity databases belong to different profiles");
            }
            if(validateConfigurationContent)
            {
                DatabaseFormatCatalog.requireCurrent(config);
            }
        }
    }

    public static void requireNoPendingSplit(Path configuration) throws IOException
    {
        if(Files.exists(ActivityDatabasePath.splitReceipt(configuration)))
        {
            throw new IOException("The configuration/activity database update was interrupted. " +
                "Complete it with the Application Migrator before starting the receiver.");
        }
    }

    public static Connection openReadOnly(Path database) throws SQLException
    {
        SQLiteConfig options = new SQLiteConfig();
        options.setReadOnly(true);
        options.setBusyTimeout(SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database, options.toProperties());
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        catch(SQLException failure)
        {
            connection.close();
            throw failure;
        }
        return connection;
    }
}
