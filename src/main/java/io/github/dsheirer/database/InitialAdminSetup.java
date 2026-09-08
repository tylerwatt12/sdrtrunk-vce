/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database;

import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.settings.WebUserPreferences;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Persistent completion state for the administrator-password step of a new portable installation.
 *
 * <p>The marker is intentionally absent from installations created by older releases.  Those existing profiles are
 * not retroactively forced through setup.  New databases receive the marker in the schema-creation path before the
 * staged database is installed, so cancelling or interrupting setup cannot turn an incomplete profile into an
 * apparently complete one.
 * </p>
 */
public final class InitialAdminSetup
{
    static final String METADATA_KEY = "initial_admin_setup";
    private static final String REQUIRED = "required";
    private static final String COMPLETE = "complete";

    private InitialAdminSetup()
    {
    }

    /**
     * Called only by the global database creator while it still owns the creation connection.
     */
    static void markRequired(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseStartup.setMetadata(connection, METADATA_KEY, REQUIRED);
    }

    /**
     * Marks a copied/imported profile as a new installation.  An existing administrator is preserved and satisfies
     * the requirement; otherwise setup remains pending.
     */
    public static void initializeNewProfile(Path databasePath) throws IOException, SQLException
    {
        if(readState(databasePath) == null)
        {
            writeState(databasePath, new WebAccessService(databasePath).isPrimaryAdminConfigured() ?
                COMPLETE : REQUIRED);
        }
    }

    /**
     * Keeps the grandfathered marker absence of an existing installation during an in-place staged migration.
     * Imports deliberately do not call this method because they create a new destination profile.
     */
    public static void preserveGrandfatheredAbsence(Path existingDatabase, Path stagedDatabase)
        throws IOException, SQLException
    {
        if(readState(existingDatabase) == null && !hasStoredWebAccounts(existingDatabase))
        {
            try(Connection connection = SdrTrunkDatabase.open(stagedDatabase);
                PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM database_metadata WHERE key = ?"))
            {
                statement.setString(1, METADATA_KEY);
                statement.executeUpdate();
            }
        }
    }

    private static boolean hasStoredWebAccounts(Path databasePath) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(databasePath);
            PreparedStatement table = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name='web_user'"))
        {
            try(ResultSet rows = table.executeQuery())
            {
                if(!rows.next() || rows.getLong(1) == 0)
                {
                    return false;
                }
            }
            try(PreparedStatement users = connection.prepareStatement("SELECT COUNT(*) FROM web_user");
                ResultSet rows = users.executeQuery())
            {
                return rows.next() && rows.getLong(1) > 0;
            }
        }
    }

    /**
     * Indicates whether startup must collect an administrator password.  If a prior attempt persisted the credential
     * but was interrupted before updating the marker, the credential is authoritative and setup is completed here.
     */
    public static boolean isPasswordRequired(Path databasePath) throws IOException, SQLException
    {
        String state = readState(databasePath);

        if(state == null || COMPLETE.equals(state))
        {
            return false;
        }

        if(!REQUIRED.equals(state))
        {
            throw new IOException("Unsupported initial administrator setup state");
        }

        if(new WebAccessService(databasePath).isPrimaryAdminConfigured())
        {
            InitialAdminPreferenceHandoff.clear(databasePath);
            writeState(databasePath, COMPLETE);
            return false;
        }

        return true;
    }

    public static void provision(Path databasePath, char[] password) throws IOException, SQLException
    {
        WebUserPreferences initialPreferences = InitialAdminPreferenceHandoff.read(databasePath)
            .orElseGet(WebUserPreferences::defaults);
        new WebAccessService(databasePath).provisionOrResetPrimaryAdmin(password, initialPreferences);
        InitialAdminPreferenceHandoff.clear(databasePath);
        writeState(databasePath, COMPLETE);
    }

    static String readState(Path databasePath) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(databasePath);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, METADATA_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static void writeState(Path databasePath, String state) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(databasePath))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, METADATA_KEY, state);
        }
    }
}
