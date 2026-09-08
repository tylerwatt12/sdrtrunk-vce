/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentDatabaseAdministrativeRepairTest
{
    private static final long TEST_TIME = 1_000;

    @TempDir
    Path mTemporaryFolder;

    @Test
    void retainsOnlyTheBoundedUsableWebAccountSet() throws Exception
    {
        Path database = mTemporaryFolder.resolve("bounded-web-users.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        int excessUsers = 32;
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            insertUser(connection, 1, "admin", "ADMIN", true);
            for(int index = 0; index < WebAccessService.MAXIMUM_USERS + excessUsers; index++)
            {
                insertUser(connection, index + 2L, "user-" + index, "USER", false);
            }
            connection.commit();

            CurrentDatabaseAdministrativeRepair.Inspection inspection =
                CurrentDatabaseAdministrativeRepair.inspect(connection);
            assertEquals(excessUsers, inspection.droppedSecondaryWebAccounts());

            CurrentDatabaseAdministrativeRepair.repair(connection);
            assertEquals(WebAccessService.MAXIMUM_USERS + 1L,
                count(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM web_user WHERE primary_admin=1"));
        }
    }

    @Test
    void preservesCredentialsAndPreferencesWhenOnlyAccountBookkeepingIsDamaged() throws Exception
    {
        Path database = mTemporaryFolder.resolve("repairable-web-account-bookkeeping.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            insertUser(connection, 1, "admin", "ADMIN", true);
            String verifier = text(connection,
                "SELECT hex(password_salt) || ':' || hex(password_hash) FROM web_user WHERE id=1");
            String preferences = text(connection, "SELECT preferences_json FROM web_user WHERE id=1");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "UPDATE web_user SET password_changed_at_ms=0, auth_revision=9223372036854775807, " +
                "created_at_ms='bad', preferences_revision=0, updated_at_ms=0 WHERE id=1");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            CurrentDatabaseAdministrativeRepair.Inspection inspection =
                CurrentDatabaseAdministrativeRepair.inspect(connection);
            assertEquals(0, inspection.resetWebAccounts());
            assertEquals(1, inspection.defaultedWebPreferences());

            CurrentDatabaseAdministrativeRepair.repair(connection);
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM web_user WHERE id=1 AND primary_admin=1"));
            assertEquals(verifier, text(connection,
                "SELECT hex(password_salt) || ':' || hex(password_hash) FROM web_user WHERE id=1"));
            assertEquals(preferences, text(connection, "SELECT preferences_json FROM web_user WHERE id=1"));
            assertEquals(1, count(connection, "SELECT auth_revision FROM web_user WHERE id=1"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM web_user WHERE id=1 AND " +
                "password_changed_at_ms>0 AND created_at_ms>0 AND preferences_revision>0 AND updated_at_ms>0"));
            Format5WebStateValidator.validate(connection);
        }
    }

    @Test
    void preservesValidAdministrativePayloadsWhenOnlyTheirTimestampsAreDamaged() throws Exception
    {
        Path database = mTemporaryFolder.resolve("repairable-administrative-timestamps.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "INSERT INTO application_settings(key, settings_json, updated_at_ms) " +
                "VALUES ('keep-setting', '{\"keep\":true}', 0)");
            execute(connection, "INSERT INTO application_icons(key, icons_json, updated_at_ms) " +
                "VALUES ('keep-icons', '{}', 0)");
            execute(connection, "INSERT INTO database_metadata(key, value, updated_at_ms) " +
                "VALUES ('keep-metadata', 'keep', 0)");
            execute(connection, "UPDATE database_metadata SET updated_at_ms=0 " +
                "WHERE key='database_format_version'");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.inspectForMigration(connection).version());
            CurrentDatabaseAdministrativeRepair.Inspection inspection =
                CurrentDatabaseAdministrativeRepair.inspect(connection);
            assertEquals(4, inspection.defaultedAdministrativeTimestamps());

            CurrentDatabaseAdministrativeRepair.repair(connection);
            assertEquals("{\"keep\":true}", text(connection,
                "SELECT settings_json FROM application_settings WHERE key='keep-setting'"));
            assertEquals("{}", text(connection,
                "SELECT icons_json FROM application_icons WHERE key='keep-icons'"));
            assertEquals("keep", text(connection,
                "SELECT value FROM database_metadata WHERE key='keep-metadata'"));
            assertEquals(4, count(connection, "SELECT " +
                "(SELECT COUNT(*) FROM application_settings WHERE key='keep-setting' AND updated_at_ms>0) + " +
                "(SELECT COUNT(*) FROM application_icons WHERE key='keep-icons' AND updated_at_ms>0) + " +
                "(SELECT COUNT(*) FROM database_metadata WHERE key='keep-metadata' AND updated_at_ms>0) + " +
                "(SELECT COUNT(*) FROM database_metadata WHERE key='database_format_version' AND updated_at_ms>0)"));
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    @Test
    void clearsInitializationMarkerWhenMalformedDefaultIconKeyIsDiscarded() throws Exception
    {
        Path database = mTemporaryFolder.resolve("malformed-default-icon-key.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            execute(connection, "DELETE FROM application_icons");
            execute(connection, "DELETE FROM database_metadata WHERE key='icon_config_initialized'");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "INSERT INTO application_icons(key, icons_json, updated_at_ms) " +
                "VALUES (CAST('default' AS BLOB), '{}', 1)");
            execute(connection, "INSERT INTO database_metadata(key, value, updated_at_ms) " +
                "VALUES ('icon_config_initialized', 'true', 1)");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            CurrentDatabaseAdministrativeRepair.Inspection inspection =
                CurrentDatabaseAdministrativeRepair.inspect(connection);
            assertEquals(1, inspection.droppedApplicationIcons());
            assertEquals(1, inspection.clearedIconInitializedMarker());

            CurrentDatabaseAdministrativeRepair.repair(connection);
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM application_icons"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM database_metadata " +
                "WHERE key='icon_config_initialized'"));
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    private static void insertUser(Connection connection, long id, String username, String tier, boolean primary)
        throws Exception
    {
        byte[] salt = new byte[32];
        byte[] hash = new byte[32];
        Arrays.fill(salt, (byte)1);
        Arrays.fill(hash, (byte)2);
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO web_user (
                id, username, tier, primary_admin, credential_version, password_algorithm, password_iterations,
                password_derived_key_bits, password_salt, password_hash, password_changed_at_ms, auth_revision,
                preferences_json, preferences_revision, created_at_ms, updated_at_ms
            ) VALUES (?, ?, ?, ?, 1, 'PBKDF2WithHmacSHA256', 600000, 256, ?, ?, ?, 1, ?, 1, ?, ?)
            """))
        {
            statement.setLong(1, id);
            statement.setString(2, username);
            statement.setString(3, tier);
            statement.setInt(4, primary ? 1 : 0);
            statement.setBytes(5, salt);
            statement.setBytes(6, hash);
            statement.setLong(7, TEST_TIME);
            statement.setString(8, WebUserPreferencesCodec.encode(WebUserPreferences.defaults()));
            statement.setLong(9, TEST_TIME);
            statement.setLong(10, TEST_TIME);
            statement.executeUpdate();
        }
        finally
        {
            Arrays.fill(salt, (byte)0);
            Arrays.fill(hash, (byte)0);
        }
    }

    private static long count(Connection connection, String sql) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rows = statement.executeQuery())
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static String text(Connection connection, String sql) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rows = statement.executeQuery())
        {
            if(!rows.next())
            {
                throw new AssertionError("Query returned no row: " + sql);
            }
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
