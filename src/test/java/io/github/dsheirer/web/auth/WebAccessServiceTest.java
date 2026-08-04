/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebAccessServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void persistsPrimaryAdminUsersAndPoliciesWithoutPlaintext() throws Exception
    {
        Path database = database();
        WebAccessService service = new WebAccessService(database);
        assertFalse(service.isPrimaryAdminConfigured());
        assertTrue(service.accounts().isEmpty());
        assertEquals(AccessTier.PUBLIC, service.requiredTier(WebCapability.DASHBOARD_VIEW));
        assertFalse(service.isAllowed(AccessTier.ADMIN, "unknown-capability"));
        assertThrows(IllegalStateException.class, () -> service.createUser("user.one",
            "ordinary user password".toCharArray(), AccessTier.USER));
        assertThrows(IllegalStateException.class,
            () -> service.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.USER));

        char[] adminPassword = "primary admin password".toCharArray();
        WebAccessAccount primary = service.provisionOrResetPrimaryAdmin(adminPassword);
        assertEquals("admin", primary.username());
        assertEquals(AccessTier.ADMIN, primary.tier());
        assertTrue(primary.primaryAdmin());
        assertEquals(1, primary.credentialVersion());
        assertEquals(primary, service.authenticate("ADMIN", adminPassword).orElseThrow());
        assertTrue(settingJson(database).contains(WebAdminCredential.PBKDF2_SHA256));
        assertFalse(settingJson(database).contains(new String(adminPassword)));

        char[] initialPassword = "ordinary user password".toCharArray();
        WebAccessAccount created = service.createUser(" User.One ", initialPassword, AccessTier.USER);
        assertEquals("user.one", created.username());
        assertEquals(1, created.credentialVersion());
        assertEquals(AccessTier.USER, service.authenticate("USER.ONE", initialPassword).orElseThrow().tier());
        assertThrows(IllegalArgumentException.class,
            () -> service.createUser("admin", initialPassword, AccessTier.ADMIN));
        assertThrows(IllegalArgumentException.class,
            () -> service.createUser("guest", initialPassword, AccessTier.PUBLIC));

        WebAccessSessionManager sessions = new WebAccessSessionManager();
        WebAccessSession beforePromotion = sessions.create(created).orElseThrow();
        WebAccessAccount promoted = service.changeUserTier("user.one", AccessTier.ADMIN);
        assertEquals(AccessTier.ADMIN, promoted.tier());
        assertEquals(2, promoted.credentialVersion());
        assertTrue(sessions.resolve(beforePromotion.sessionId(), service).isEmpty());

        WebAccessSession beforeReset = sessions.create(promoted).orElseThrow();
        char[] replacementPassword = "replacement user password".toCharArray();
        WebAccessAccount reset = service.resetUserPassword("user.one", replacementPassword);
        assertEquals(3, reset.credentialVersion());
        assertTrue(sessions.resolve(beforeReset.sessionId(), service).isEmpty());
        assertTrue(service.authenticate("user.one", initialPassword).isEmpty());
        assertEquals(reset, service.authenticate("user.one", replacementPassword).orElseThrow());

        WebAccessService.CapabilityPolicy policy =
            service.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.USER);
        assertEquals(AccessTier.USER, policy.requiredTier());
        assertFalse(service.isAllowed(AccessTier.PUBLIC, WebCapability.DASHBOARD_VIEW));
        assertTrue(service.isAllowed(AccessTier.USER, WebCapability.DASHBOARD_VIEW));
        assertThrows(IllegalArgumentException.class,
            () -> service.setCapabilityTier(WebCapability.ADMIN_USERS, AccessTier.PUBLIC));

        WebAccessService restarted = new WebAccessService(database);
        assertEquals(2, restarted.accounts().size());
        assertEquals(AccessTier.USER, restarted.requiredTier(WebCapability.DASHBOARD_VIEW));
        assertEquals(reset, restarted.authenticate("user.one", replacementPassword).orElseThrow());
        assertFalse(settingJson(database).contains(new String(replacementPassword)));

        WebAccessAccount deleted = restarted.deleteUser("user.one");
        assertEquals(reset, deleted);
        assertTrue(restarted.authenticate("user.one", replacementPassword).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> restarted.deleteUser("admin"));
    }

    @Test
    void rejectsUnsupportedOrUnboundedPersistedDocuments() throws Exception
    {
        Path database = database();
        writeSetting(database, """
            {"formatVersion":99,"primaryAdmin":null,"users":[],"policyOverrides":{}}
            """);
        assertThrows(UnreadableWebAccessConfigurationException.class, () -> new WebAccessService(database));

        writeSetting(database, """
            {"formatVersion":1,"primaryAdmin":null,"users":[],"policyOverrides":{"unknown": "PUBLIC"}}
            """);
        assertThrows(UnreadableWebAccessConfigurationException.class, () -> new WebAccessService(database));

        assertThrows(IllegalArgumentException.class,
            () -> new WebAccessConfiguration(1, null,
                java.util.Collections.nCopies(WebAccessService.MAXIMUM_USERS + 1, null), java.util.Map.of()));
    }

    @Test
    void definesEveryExistingCapabilityAndLocksAdminPolicies()
    {
        assertEquals(11, WebCapability.registry().size());

        for(String id: new String[]{"dashboard", "live", "systems", "conventional", "aliases", "credits",
            "csv-export", "call-audio", "admin-users", "admin-access", "admin-aliases"})
        {
            assertTrue(WebCapability.fromId(id).isPresent(), id);
        }

        assertEquals(AccessTier.PUBLIC, WebCapability.CREDITS_VIEW.defaultTier());
        assertEquals(AccessTier.ADMIN, WebCapability.ADMIN_USERS.defaultTier());
        assertFalse(WebCapability.ADMIN_USERS.configurable());
        assertFalse(WebCapability.ADMIN_ACCESS.configurable());
        assertFalse(WebCapability.ADMIN_ALIASES.configurable());
        assertTrue(AccessTier.ADMIN.allows(AccessTier.USER));
        assertFalse(AccessTier.PUBLIC.allows(AccessTier.USER));
    }

    private Path database() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        return database;
    }

    private static String settingJson(Path database) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT settings_json FROM application_settings WHERE key = ?"))
        {
            statement.setString(1, WebAccessService.KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                String json = resultSet.getString(1);
                assertNotNull(json);
                return json;
            }
        }
    }

    private static void writeSetting(Path database, String json) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms) VALUES (?, ?, 1)
                ON CONFLICT(key) DO UPDATE SET settings_json = excluded.settings_json
                """))
        {
            statement.setString(1, WebAccessService.KEY);
            statement.setString(2, json);
            statement.executeUpdate();
        }
    }
}
