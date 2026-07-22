/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebAdminCredentialStoreTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void upsertsOneImmediateVersionedApplicationSetting() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        List<SchemaObject> schemaBefore = readSchema(database);
        int schemaVersionBefore = readSchemaVersion(database);
        WebAdminCredentialStore store = new WebAdminCredentialStore(database);
        assertFalse(store.load().isPresent());
        Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher(WebAdminCredential.MINIMUM_ITERATIONS,
            new SecureRandom(), Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC));
        WebAdminCredential first = hasher.createCredential("admin", "a sufficiently long password".toCharArray(), 1);
        WebAdminCredential second = hasher.createCredential("admin", "a different long password".toCharArray(), 2);

        store.save(first);
        assertEquals(first, store.load().orElseThrow());
        store.save(second);
        assertEquals(second, store.load().orElseThrow());
        assertEquals(schemaBefore, readSchema(database));
        assertEquals(schemaVersionBefore, readSchemaVersion(database));

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM application_settings"))
        {
            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
            }
        }

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT settings_json FROM application_settings WHERE key = ?"))
        {
            statement.setString(1, WebAdminCredentialStore.SETTING_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                assertFalse(resultSet.getString(1).contains("a different long password"));
            }
        }
    }

    @Test
    void malformedStoredCredentialFailsClosed() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("malformed.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms) VALUES (?, '{}', 1)
                """))
        {
            statement.setString(1, WebAdminCredentialStore.SETTING_KEY);
            statement.executeUpdate();
        }

        assertThrows(IOException.class, () -> new WebAdminCredentialStore(database).load());
    }

    private static List<SchemaObject> readSchema(Path database) throws Exception
    {
        List<SchemaObject> schema = new ArrayList<>();

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                SELECT type, name, tbl_name, sql
                FROM sqlite_master
                WHERE name NOT LIKE 'sqlite_%'
                ORDER BY type, name
                """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                schema.add(new SchemaObject(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3),
                    resultSet.getString(4)));
            }
        }

        return schema;
    }

    private static int readSchemaVersion(Path database) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("PRAGMA schema_version");
            ResultSet resultSet = statement.executeQuery())
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private record SchemaObject(String type, String name, String tableName, String sql)
    {
    }
}
