/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWebAdminProvisioningServiceTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void repairsOnlyAnUnreadableCredential() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms) VALUES (?, '{}', 1)
                """))
        {
            statement.setString(1, WebAdminCredentialStore.SETTING_KEY);
            statement.executeUpdate();
        }

        LocalWebAdminProvisioningService service = new LocalWebAdminProvisioningService(database);
        assertEquals(LocalWebAdminProvisioningService.State.UNREADABLE, service.inspect().state());
        char[] password = "a local recovery password".toCharArray();
        SingleAdminAuthenticationService.CredentialMetadata metadata =
            service.repairUnreadable("admin", password);

        assertEquals("admin", metadata.username());
        assertEquals(1, metadata.authGeneration());
        assertEquals(LocalWebAdminProvisioningService.State.CONFIGURED, service.inspect().state());
        assertThrows(IllegalStateException.class, () -> service.repairUnreadable("admin", password));

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT settings_json FROM application_settings WHERE key = ?"))
        {
            statement.setString(1, WebAdminCredentialStore.SETTING_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                String json = resultSet.getString(1);
                assertTrue(json.contains("PBKDF2WithHmacSHA256"));
                assertTrue(!json.contains("a local recovery password"));
            }
        }
    }

    @Test
    void treatsLiteralNullCredentialAsUnreadableAndRepairable() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("null-credential.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms) VALUES (?, 'null', 1)
                """))
        {
            statement.setString(1, WebAdminCredentialStore.SETTING_KEY);
            statement.executeUpdate();
        }

        LocalWebAdminProvisioningService service = new LocalWebAdminProvisioningService(database);
        assertEquals(LocalWebAdminProvisioningService.State.UNREADABLE, service.inspect().state());
        assertEquals("admin", service.repairUnreadable("admin", "another recovery password".toCharArray())
            .username());
        assertEquals(LocalWebAdminProvisioningService.State.CONFIGURED, service.inspect().state());
    }
}
