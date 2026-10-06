/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.settings.WebUserPreferences;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebAccessServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void persistsNormalizedUsersAndPoliciesWithoutPlaintext() throws Exception
    {
        Path database = database();
        WebAccessService service = new WebAccessService(database);
        assertFalse(service.isPrimaryAdminConfigured());
        assertTrue(service.accounts().isEmpty());
        assertThrows(IllegalStateException.class, () -> service.createUser("user.one",
            "ordinary user password".toCharArray()));

        char[] adminPassword = "primary admin password".toCharArray();
        WebAccessAccount primary = service.provisionOrResetPrimaryAdmin(adminPassword);
        assertEquals("admin", primary.username());
        assertEquals(AccessTier.ADMIN, primary.tier());
        assertTrue(primary.primaryAdmin());
        assertEquals(1, primary.authRevision());
        assertEquals(primary, service.authenticate("ADMIN", adminPassword).orElseThrow());
        assertEquals(WebPasswordVerifier.PBKDF2_SHA256, verifierAlgorithm(database, "admin"));
        assertFalse(databaseText(database).contains(new String(adminPassword)));

        char[] initialPassword = "ordinary user password".toCharArray();
        WebAccessAccount created = service.createUser(" User.One ", initialPassword);
        assertEquals("user.one", created.username());
        assertEquals(AccessTier.USER, created.tier());
        assertEquals(1, created.authRevision());
        assertEquals(AccessTier.USER, service.authenticate("USER.ONE", initialPassword).orElseThrow().tier());
        assertThrows(IllegalArgumentException.class,
            () -> service.createUser("admin", initialPassword));

        WebAccessSessionManager sessions = new WebAccessSessionManager();
        WebAccessSession beforeReset = sessions.create(created).orElseThrow();
        char[] replacementPassword = "replacement user password".toCharArray();
        WebAccessAccount reset = service.resetUserPassword("user.one", replacementPassword);
        assertEquals(2, reset.authRevision());
        assertEquals(AccessTier.USER, reset.tier());
        assertTrue(sessions.resolve(beforeReset.sessionId(), service).isEmpty());
        assertTrue(service.authenticate("user.one", initialPassword).isEmpty());
        assertEquals(reset, service.authenticate("user.one", replacementPassword).orElseThrow());

        service.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.USER);
        service.setCapabilityTier(WebCapability.WEB_ACCESS, AccessTier.USER);
        assertFalse(service.isAllowed(AccessTier.PUBLIC, WebCapability.CREDITS_VIEW));
        assertTrue(service.isAllowed(AccessTier.USER, WebCapability.CREDITS_VIEW));
        assertFalse(service.isAllowed(AccessTier.USER, WebCapability.ADMIN_ACCESS));
        assertThrows(IllegalArgumentException.class,
            () -> service.setCapabilityTier(WebCapability.TUNER_SPECTRUM_VIEW, AccessTier.USER));

        WebAccessService restarted = new WebAccessService(database);
        assertEquals(2, restarted.accounts().size());
        assertEquals(AccessTier.USER, restarted.requiredTier(WebCapability.DASHBOARD_VIEW));
        assertEquals(AccessTier.USER, restarted.requiredTier(WebCapability.WEB_ACCESS));
        assertEquals(reset, restarted.authenticate("user.one", replacementPassword).orElseThrow());
        assertFalse(databaseText(database).contains(new String(replacementPassword)));

        assertEquals(reset, restarted.deleteUser("user.one"));
        assertTrue(restarted.authenticate("user.one", replacementPassword).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> restarted.deleteUser("admin"));
    }

    @Test
    void preservesAccountCreationAcrossPasswordRecoveryRestartAndPolicyChanges() throws Exception
    {
        Path database = database();
        long createdAt = 1_700_000_000_000L;
        long recoveredAt = createdAt + 60_000;
        WebAccessService initial = serviceAt(database, createdAt);
        WebAccessAccount primary = initial.provisionOrResetPrimaryAdmin("primary admin password".toCharArray());
        WebAccessAccount ordinary = initial.createUser("listener", "ordinary user password".toCharArray());
        assertEquals(createdAt, primary.createdAtEpochMillis());
        assertEquals(createdAt, ordinary.createdAtEpochMillis());

        WebAccessService recovery = serviceAt(database, recoveredAt);
        WebAccessAccount resetPrimary = recovery.provisionOrResetPrimaryAdmin("replacement admin password".toCharArray());
        WebAccessAccount resetOrdinary = recovery.resetUserPassword("listener", "replacement user password".toCharArray());
        assertEquals(primary.createdAtEpochMillis(), resetPrimary.createdAtEpochMillis());
        assertEquals(ordinary.createdAtEpochMillis(), resetOrdinary.createdAtEpochMillis());
        assertEquals(recoveredAt, resetPrimary.passwordChangedAtEpochMillis());
        assertEquals(recoveredAt, resetOrdinary.passwordChangedAtEpochMillis());
        assertEquals(2, resetPrimary.authRevision());
        assertEquals(2, resetOrdinary.authRevision());

        recovery.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.ADMIN);
        WebAccessService restarted = new WebAccessService(database);
        assertEquals(resetPrimary, restarted.primaryAdmin().orElseThrow());
        assertEquals(resetOrdinary, restarted.account("listener").orElseThrow());

        Path newInstall = mTemporaryFolder.resolve("new-install.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(newInstall);
        WebAccessAccount newPrimary = serviceAt(newInstall, recoveredAt)
            .provisionOrResetPrimaryAdmin("primary admin password".toCharArray());
        assertEquals(primary.id(), newPrimary.id());
        assertEquals(primary.username(), newPrimary.username());
        assertNotEquals(primary.createdAtEpochMillis(), newPrimary.createdAtEpochMillis());

        restarted.deleteUser("listener");
        WebAccessAccount recreated = serviceAt(database, recoveredAt)
            .createUser("listener", "ordinary user password".toCharArray());
        assertEquals(ordinary.username(), recreated.username());
        assertNotEquals(ordinary.createdAtEpochMillis(), recreated.createdAtEpochMillis());
    }

    @Test
    void refusesInvalidNormalizedRows() throws Exception
    {
        Path database = database();
        WebAccessService service = new WebAccessService(database);
        service.provisionOrResetPrimaryAdmin("primary admin password".toCharArray());

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE web_user SET auth_revision=0 WHERE username='admin'"))
        {
            assertThrows(java.sql.SQLException.class, statement::executeUpdate);
        }

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO web_access_policy(capability_id, required_tier, updated_at_ms)
                VALUES ('unknown', 'USER', 1)
                """))
        {
            statement.executeUpdate();
        }
        assertThrows(java.sql.SQLException.class, () -> new WebAccessService(database));
    }

    @Test
    void allowsPrimaryAdministratorPlusTheLegacyBoundOfOrdinaryUsers() throws Exception
    {
        Path database = database();
        WebAccessService service = new WebAccessService(database);
        service.provisionOrResetPrimaryAdmin("primary admin password".toCharArray());

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO web_user (
                    username, tier, primary_admin, credential_version, password_algorithm, password_iterations,
                    password_derived_key_bits, password_salt, password_hash, password_changed_at_ms, auth_revision,
                    preferences_json, preferences_revision, created_at_ms, updated_at_ms
                )
                SELECT ?, 'USER', 0, credential_version, password_algorithm, password_iterations,
                       password_derived_key_bits, password_salt, password_hash, password_changed_at_ms, auth_revision,
                       preferences_json, preferences_revision, created_at_ms, updated_at_ms
                FROM web_user WHERE username='admin'
                """))
        {
            for(int index = 0; index < WebAccessService.MAXIMUM_USERS; index++)
            {
                statement.setString(1, "user" + index);
                statement.addBatch();
            }
            statement.executeBatch();
        }

        WebAccessService restarted = new WebAccessService(database);
        assertEquals(WebAccessService.MAXIMUM_USERS + 1, restarted.accounts().size());
        assertThrows(IllegalStateException.class, () -> restarted.createUser("one-too-many",
            "ordinary user password".toCharArray()));
    }

    @Test
    void refusesExhaustedPreferenceRevisionBeforeExecutingSql() throws Exception
    {
        Path database = database();
        WebAccessService access = new WebAccessService(database);
        WebAccessAccount primary = access.provisionOrResetPrimaryAdmin("primary admin password".toCharArray());
        String originalJson;
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement update = connection.prepareStatement("""
                UPDATE web_user SET preferences_revision=? WHERE username='admin'
                """))
        {
            update.setLong(1, Long.MAX_VALUE - 1);
            assertEquals(1, update.executeUpdate());
        }
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement query = connection.prepareStatement("""
                SELECT preferences_json FROM web_user WHERE username='admin'
                """))
        {
            try(ResultSet resultSet = query.executeQuery())
            {
                assertTrue(resultSet.next());
                originalJson = resultSet.getString(1);
            }
        }

        WebUserPreferencesService preferences = new WebUserPreferencesService(database);
        assertThrows(IOException.class,
            () -> preferences.update(primary, Long.MAX_VALUE - 1, WebUserPreferences.defaults()));

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement query = connection.prepareStatement("""
                SELECT typeof(preferences_revision), preferences_revision, preferences_json
                FROM web_user WHERE username='admin'
                """))
        {
            try(ResultSet resultSet = query.executeQuery())
            {
                assertTrue(resultSet.next());
                assertEquals("integer", resultSet.getString(1));
                assertEquals(Long.MAX_VALUE - 1, resultSet.getLong(2));
                assertEquals(originalJson, resultSet.getString(3));
            }
        }
    }

    @Test
    void definesEveryCapabilityAndLocksFixedPolicies()
    {
        assertEquals(19, WebCapability.registry().size());
        for(String id: new String[]{"web-access", "dashboard", "live", "tuner-spectrum", "radio",
            "credits", "csv-export", "call-audio", "recordings", "user-settings", "admin-users",
            "admin-access", "admin-aliases", "admin-streaming", "admin-channels", "admin-tuners", "admin-settings",
            "admin-recordings",
            "receiver-health"})
        {
            assertTrue(WebCapability.fromId(id).isPresent(), id);
        }

        assertEquals(AccessTier.USER, WebCapability.USER_SETTINGS.defaultTier());
        assertFalse(WebCapability.USER_SETTINGS.configurable());
        assertEquals("Scanner and call audio", WebCapability.WEB_AUDIO_LISTEN.displayName());
        assertFalse(WebCapability.TUNER_SPECTRUM_VIEW.configurable());
        assertFalse(WebCapability.ADMIN_USERS.configurable());
        assertFalse(WebCapability.ADMIN_CHANNELS.configurable());
        assertFalse(WebCapability.ADMIN_TUNERS.configurable());
        assertEquals(AccessTier.PUBLIC, WebCapability.RECORDINGS_VIEW.defaultTier());
        assertFalse(WebCapability.ADMIN_RECORDINGS.configurable());
        assertEquals(AccessTier.ADMIN, WebCapability.ADMIN_TUNERS.defaultTier());
        assertTrue(AccessTier.ADMIN.allows(AccessTier.USER));
        assertFalse(AccessTier.PUBLIC.allows(AccessTier.USER));
    }

    private Path database() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        return database;
    }

    private static WebAccessService serviceAt(Path database, long epochMillis) throws Exception
    {
        return new WebAccessService(new WebUserRepository(database), new WebAccessPolicyRepository(database),
            new Pbkdf2PasswordHasher(WebPasswordVerifier.MINIMUM_ITERATIONS, new SecureRandom(),
                Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)));
    }

    private static String verifierAlgorithm(Path database, String username) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT password_algorithm FROM web_user WHERE username=?"))
        {
            statement.setString(1, username);
            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static String databaseText(Path database) throws Exception
    {
        return new String(java.nio.file.Files.readAllBytes(database), java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
