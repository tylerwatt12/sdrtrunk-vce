/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.web.auth.WebAccessService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdrTrunkDatabaseBootstrapAdminSetupTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void freshHeadlessSetupCannotStartUntilPasswordIsSavedAndCanResume() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("fresh");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);

        IOException failure = assertThrows(IOException.class,
            () -> SdrTrunkDatabaseBootstrap.run(new String[]{"--fresh"}, dataRoot, true));

        assertTrue(failure.getMessage().contains("--admin-password-file"));
        assertTrue(Files.isRegularFile(database));
        assertEquals("required", InitialAdminSetup.readState(database));
        assertFalse(new WebAccessService(database).isPrimaryAdminConfigured());
        assertFalse(Files.exists(dataRoot.resolve("vault/encryption-key-vault.sqlite")));

        Path passwordFile = mTemporaryFolder.resolve("admin-password.txt");
        Files.writeString(passwordFile, "short\n");
        IOException invalid = assertThrows(IOException.class, () -> SdrTrunkDatabaseBootstrap.run(
            new String[]{"--admin-password-file", passwordFile.toString()}, dataRoot, true));
        assertTrue(invalid.getMessage().contains("7-256"));
        assertEquals("required", InitialAdminSetup.readState(database));
        assertFalse(new WebAccessService(database).isPrimaryAdminConfigured());

        String plaintext = "initial setup password";
        Files.writeString(passwordFile, plaintext + "\n");
        SdrTrunkDatabaseBootstrap.BootstrapResult result = SdrTrunkDatabaseBootstrap.run(
            new String[]{"--admin-password-file", passwordFile.toString()}, dataRoot, true);

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertEquals("complete", InitialAdminSetup.readState(database));
        WebAccessService access = new WebAccessService(database);
        assertTrue(access.isPrimaryAdminConfigured());
        assertTrue(access.authenticate("admin", plaintext.toCharArray()).isPresent());
        assertFalse(new String(Files.readAllBytes(database), StandardCharsets.ISO_8859_1).contains(plaintext));
        assertTrue(Files.isRegularFile(dataRoot.resolve("vault/encryption-key-vault.sqlite")));
    }

    @Test
    void existingProfileWithoutNewInstallMarkerIsNotRetroactivelyBlocked() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("grandfathered");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, InitialAdminSetup.METADATA_KEY);
            statement.executeUpdate();
        }

        SdrTrunkDatabaseBootstrap.BootstrapResult result =
            SdrTrunkDatabaseBootstrap.run(new String[0], dataRoot, true);

        assertTrue(result.startApplication());
        assertFalse(new WebAccessService(database).isPrimaryAdminConfigured());
        assertNull(InitialAdminSetup.readState(database));
    }

    @Test
    void credentialPersistedBeforeMarkerUpdateCompletesWithoutPasswordReset() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder.resolve("interrupted"));
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        char[] password = "already persisted password".toCharArray();
        new WebAccessService(database).provisionOrResetPrimaryAdmin(password);

        assertFalse(InitialAdminSetup.isPasswordRequired(database));
        assertEquals("complete", InitialAdminSetup.readState(database));
        assertTrue(new WebAccessService(database).authenticate("admin", password).isPresent());
    }
}
