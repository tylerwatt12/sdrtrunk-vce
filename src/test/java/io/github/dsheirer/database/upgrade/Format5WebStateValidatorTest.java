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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format5WebStateValidatorTest
{
    private static final long TEST_TIME = 1_000;

    @TempDir
    Path mTemporaryFolder;

    @Test
    void acceptsFreshSetupStateAndPrimaryPlusMaximumOrdinaryUsers() throws Exception
    {
        Path fresh = Format6TestDatabase.create(mTemporaryFolder.resolve("fresh.sqlite"));
        assertAcceptedByBothPaths(fresh);

        Path full = Format6TestDatabase.create(mTemporaryFolder.resolve("full.sqlite"));
        try(Connection connection = open(full))
        {
            connection.setAutoCommit(false);
            insertUser(connection, 1, "admin", "ADMIN", true);
            for(int index = 0; index < WebAccessService.MAXIMUM_USERS; index++)
            {
                insertUser(connection, index + 2L, "user-" + index, "USER", false);
            }
            connection.commit();
        }

        assertAcceptedByBothPaths(full);
    }

    @Test
    void rejectsOrdinaryUsersAndPoliciesWithoutPrimaryAdministrator() throws Exception
    {
        Path userWithoutPrimary = Format6TestDatabase.create(mTemporaryFolder.resolve("orphan-user.sqlite"));
        try(Connection connection = open(userWithoutPrimary))
        {
            insertUser(connection, 1, "listener", "USER", false);
        }
        assertRejectedByBothPaths(userWithoutPrimary, "without the primary administrator");

        Path policyWithoutPrimary = Format6TestDatabase.create(mTemporaryFolder.resolve("orphan-policy.sqlite"));
        try(Connection connection = open(policyWithoutPrimary))
        {
            insertPolicy(connection, "dashboard", "USER");
        }
        assertRejectedByBothPaths(policyWithoutPrimary, "without the primary administrator");
    }

    @Test
    void rejectsMoreThanMaximumOrdinaryUsers() throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve("too-many-users.sqlite"));
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            insertUser(connection, 1, "admin", "ADMIN", true);
            for(int index = 0; index <= WebAccessService.MAXIMUM_USERS; index++)
            {
                insertUser(connection, index + 2L, "user-" + index, "USER", false);
            }
            connection.commit();
        }

        assertRejectedByBothPaths(database, "ordinary user count exceeds " + WebAccessService.MAXIMUM_USERS);
    }

    @Test
    void rejectsRuntimeInvalidUsernameAndNonpositiveIdentifier() throws Exception
    {
        Path username = Format6TestDatabase.create(mTemporaryFolder.resolve("invalid-username.sqlite"));
        try(Connection connection = open(username))
        {
            insertUser(connection, 1, "admin", "ADMIN", true);
            insertUser(connection, 2, "bad user", "USER", false);
        }
        assertRejectedByBothPaths(username, "invalid username or tier");

        Path identifier = Format6TestDatabase.create(mTemporaryFolder.resolve("invalid-id.sqlite"));
        try(Connection connection = open(identifier))
        {
            insertUser(connection, -1, "admin", "ADMIN", true);
        }
        assertRejectedByBothPaths(identifier, "account identifier must be positive");
    }

    @Test
    void rejectsCredentialStorageThatSQLiteColumnChecksAdmit() throws Exception
    {
        Path textSalt = Format6TestDatabase.create(mTemporaryFolder.resolve("text-salt.sqlite"));
        try(Connection connection = open(textSalt); Statement statement = connection.createStatement())
        {
            insertUser(connection, 1, "admin", "ADMIN", true);
            statement.executeUpdate("UPDATE web_user SET password_salt='0123456789abcdef' WHERE id=1");
        }
        assertRejectedByBothPaths(textSalt, "password salt must use SQLite blob storage");

        Path fractionalIterations = Format6TestDatabase.create(mTemporaryFolder.resolve("fractional-iterations.sqlite"));
        try(Connection connection = open(fractionalIterations); Statement statement = connection.createStatement())
        {
            insertUser(connection, 1, "admin", "ADMIN", true);
            statement.executeUpdate("UPDATE web_user SET password_iterations=600000.5 WHERE id=1");
        }
        assertRejectedByBothPaths(fractionalIterations, "password work factor must use SQLite integer storage");
    }

    @Test
    void rejectsNonpositiveRevisionAndTimestampValuesWhenChecksWereBypassed() throws Exception
    {
        assertUserPositiveValueRejected("zero-preference-revision.sqlite", "preferences_revision",
            "preference revision must be positive");
        assertUserPositiveValueRejected("zero-created-at.sqlite", "created_at_ms",
            "account creation time must be positive");
        assertUserPositiveValueRejected("zero-updated-at.sqlite", "updated_at_ms",
            "account update time must be positive");

        Path policy = Format6TestDatabase.create(mTemporaryFolder.resolve("zero-policy-updated-at.sqlite"));
        try(Connection connection = open(policy); Statement statement = connection.createStatement())
        {
            insertUser(connection, 1, "admin", "ADMIN", true);
            insertPolicy(connection, "dashboard", "USER");
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE web_access_policy SET updated_at_ms=0");
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }
        assertRejectedByBothPaths(policy, "access-policy update time must be positive");
    }

    @Test
    void rejectsJsonThatDoesNotDecodeAsTypedPreferences() throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve("untyped-preferences.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            insertUser(connection, 1, "admin", "ADMIN", true);
            statement.executeUpdate("UPDATE web_user SET preferences_json='{}' WHERE id=1");
        }

        assertRejectedByBothPaths(database, "invalid typed preference document");
    }

    @Test
    void validatesStrictPortableSiteSettingsThroughStartupAndCatalogPaths() throws Exception
    {
        Path valid = Format6TestDatabase.create(mTemporaryFolder.resolve("valid-site-settings.sqlite"));
        putPortablePreferences(valid, """
            {"user/io/github/dsheirer/preference/nowplaying":{
                "site.settings.revision":"1",
                "retain.idle.call.details":"true",
                "clear.voice.decode.quality.on.call.end":"false",
                "traffic.grant.age.out.milliseconds":"15000",
                "unrelated.setting":"preserved"
            },"user/example":{"sentinel":"preserved"}}
            """);
        assertAcceptedByBothPaths(valid);

        assertPortablePreferencesRejected("site-setting-without-revision.sqlite", """
            {"user/io/github/dsheirer/preference/nowplaying":{"retain.idle.call.details":"true"}}
            """, "without their revision");
        assertPortablePreferencesRejected("invalid-site-revision.sqlite", """
            {"user/io/github/dsheirer/preference/nowplaying":{"site.settings.revision":"0"}}
            """, "revision must be positive and incrementable");
        assertPortablePreferencesRejected("invalid-site-boolean.sqlite", """
            {"user/io/github/dsheirer/preference/nowplaying":{
                "site.settings.revision":"1","retain.idle.call.details":"TRUE"}}
            """, "must be true or false");
        assertPortablePreferencesRejected("invalid-site-age-out.sqlite", """
            {"user/io/github/dsheirer/preference/nowplaying":{
                "site.settings.revision":"1","traffic.grant.age.out.milliseconds":"15001"}}
            """, "outside its supported range");
        assertPortablePreferencesRejected("nontext-portable-preference.sqlite", """
            {"user/io/github/dsheirer/preference/nowplaying":{"site.settings.revision":1}}
            """, "portable preference value must be text");
        assertPortablePreferencesRejected("duplicate-portable-preference.sqlite", """
            {"user/io/github/dsheirer/preference/nowplaying":{},
             "user/io/github/dsheirer/preference/nowplaying":{}}
            """, "not strict JSON");
    }

    @Test
    void rejectsExhaustedUserPreferenceRevision() throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve("exhausted-preference-revision.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            insertUser(connection, 1, "admin", "ADMIN", true);
            statement.executeUpdate("UPDATE web_user SET preferences_revision=9223372036854775807 WHERE id=1");
        }
        assertRejectedByBothPaths(database, "preference revision must be positive and incrementable");
    }

    @Test
    void rejectsUnknownFixedAndDefaultAccessPolicyRows() throws Exception
    {
        assertPolicyRejected("unknown-policy.sqlite", "unknown-feature", "USER", "unknown access-policy capability");
        assertPolicyRejected("fixed-policy.sqlite", "admin-users", "USER",
            "fixed access-policy capability is persisted");
        assertPolicyRejected("default-policy.sqlite", "dashboard", "PUBLIC",
            "default access-policy capability is redundantly persisted");
    }

    private void assertPolicyRejected(String filename, String capability, String tier, String message) throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve(filename));
        try(Connection connection = open(database))
        {
            insertUser(connection, 1, "admin", "ADMIN", true);
            insertPolicy(connection, capability, tier);
        }
        assertRejectedByBothPaths(database, message);
    }

    private void assertUserPositiveValueRejected(String filename, String column, String message) throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve(filename));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            insertUser(connection, 1, "admin", "ADMIN", true);
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE web_user SET " + column + "=0");
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }
        assertRejectedByBothPaths(database, message);
    }

    private void assertPortablePreferencesRejected(String filename, String json, String message) throws Exception
    {
        Path database = Format6TestDatabase.create(mTemporaryFolder.resolve(filename));
        putPortablePreferences(database, json);
        assertRejectedByBothPaths(database, message);
    }

    private static void putPortablePreferences(Path database, String json) throws Exception
    {
        try(Connection connection = open(database); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms)
            VALUES ('portable_java_preferences_v1', ?, ?)
            ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json,
                updated_at_ms=excluded.updated_at_ms
            """))
        {
            statement.setString(1, json);
            statement.setLong(2, TEST_TIME);
            statement.executeUpdate();
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

    private static void insertPolicy(Connection connection, String capability, String tier) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO web_access_policy(capability_id, required_tier, updated_at_ms) VALUES (?, ?, ?)
            """))
        {
            statement.setString(1, capability);
            statement.setString(2, tier);
            statement.setLong(3, TEST_TIME);
            statement.executeUpdate();
        }
    }

    private static void assertAcceptedByBothPaths(Path database) throws Exception
    {
        try(Connection connection = open(database))
        {
            assertDoesNotThrow(() -> SdrTrunkDatabaseSchema.validate(connection));
            assertDoesNotThrow(() -> DatabaseFormatCatalog.inspect(connection));
        }
    }

    private static void assertRejectedByBothPaths(Path database, String expectedMessage) throws Exception
    {
        try(Connection connection = open(database))
        {
            SQLException startup = assertThrows(SQLException.class,
                () -> SdrTrunkDatabaseSchema.validate(connection));
            assertTrue(startup.getMessage().contains(expectedMessage), startup::getMessage);

            SQLException catalog = assertThrows(SQLException.class,
                () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(catalog.getMessage().contains(expectedMessage), catalog::getMessage);
        }
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
