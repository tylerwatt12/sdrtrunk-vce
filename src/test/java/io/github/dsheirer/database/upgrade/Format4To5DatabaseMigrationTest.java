/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import io.github.dsheirer.web.auth.WebPasswordVerifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format4To5DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void migratesPopulatedSyntheticFormat4IntoNormalizedFormat5() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("format-4.sqlite"));

        try(Connection connection = open(database))
        {
            insertOpaqueRetiredChannels(connection);
            Map<String,CredentialSnapshot> legacyCredentials = legacyCredentials(connection);
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 4, preflight.steps().size());
            assertEquals("format-4-to-5", preflight.steps().getFirst().id());
            assertEffect(preflight, DatabaseMigrationEffect.Kind.TRANSFORM, "saved channel identity scalars",
                DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DROP, "retired channel configurations",
                DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.TRANSFORM, "web accounts",
                DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.TRANSFORM, "web access policy overrides",
                DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DEFAULT, "per-user browser preferences",
                DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DEFAULT, "site-settings revision", 1);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DROP, "retired web policy overrides",
                DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DROP, "superseded settings storage",
                DatabaseMigrationEffect.UNKNOWN_COUNT);

            connection.setAutoCommit(false);
            try
            {
                new Format4To5DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 5);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(5, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("3", metadata(connection, "configuration_schema_version"));
            assertEquals("3", metadata(connection, "settings_schema_version"));
            assertEquals("3", scalar(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM web_user WHERE primary_admin=1"));
            assertEquals("admin:ADMIN:1,listener:USER:1,operator:ADMIN:1", scalar(connection, """
                SELECT group_concat(username || ':' || tier || ':' || auth_revision, ',')
                FROM (SELECT * FROM web_user ORDER BY username)
                """));
            assertEquals("dashboard:USER,site-access:USER", scalar(connection, """
                SELECT group_concat(capability_id || ':' || required_tier, ',')
                FROM (SELECT * FROM web_access_policy ORDER BY capability_id)
                """));
            assertEquals(legacyCredentials, normalizedCredentials(connection));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM application_settings WHERE key IN ('web.access.v1', 'web.display.v1')
                """));
            assertEquals("{\"preserved\":true}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='format-4-preserve-sentinel'
                """));
            assertEquals("0:0:1:detailed:125", scalar(connection, """
                SELECT json_extract(preferences_json, '$.presentation.show_encryption_details') || ':' ||
                       json_extract(preferences_json, '$.presentation.show_control_decode_quality') || ':' ||
                       json_extract(preferences_json, '$.presentation.show_voice_decode_quality') || ':' ||
                       json_extract(preferences_json, '$.presentation.decode_quality_display_mode') || ':' ||
                       json_extract(preferences_json, '$.presentation.live_detail_row_limit')
                FROM web_user WHERE username='listener'
                """));
            assertEquals("1:light:normal:0", scalar(connection, """
                SELECT json_extract(preferences_json, '$.version') || ':' ||
                       json_extract(preferences_json, '$.appearance.theme') || ':' ||
                       json_extract(preferences_json, '$.scanner.detail_mode') || ':' ||
                       json_array_length(json_extract(preferences_json, '$.playback.selected_scan_list_ids'))
                FROM web_user WHERE username='admin'
                """));
            assertEquals("1750:1:preserve-me", scalar(connection, """
                SELECT json_extract(settings_json,
                           '$."user/io/github/dsheirer/preference/nowplaying"."traffic.grant.age.out.milliseconds"') || ':' ||
                       json_extract(settings_json,
                           '$."user/io/github/dsheirer/preference/nowplaying"."site.settings.revision"') || ':' ||
                       json_extract(settings_json, '$."user/example".sentinel')
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*) FROM application_settings
                WHERE key='portable_java_preferences_v1' AND (
                    json_type(settings_json,
                        '$."user/io/github/dsheirer/preference/nowplaying"."retain.idle.call.details"') IS NOT NULL
                    OR json_type(settings_json,
                        '$."user/io/github/dsheirer/preference/nowplaying"."clear.voice.decode.quality.on.call.end"')
                        IS NOT NULL)
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM application_settings
                WHERE key='portable_java_preferences_v1' AND (
                    json_type(settings_json,
                        '$."user/io/github/dsheirer/preference/nowplaying"."show.control.decode.quality"') IS NOT NULL
                    OR json_type(settings_json,
                        '$."user/io/github/dsheirer/preference/nowplaying"."show.voice.decode.quality"') IS NOT NULL
                    OR json_type(settings_json,
                        '$."user/io/github/dsheirer/preference/nowplaying"."decode.quality.display.mode"') IS NOT NULL
                    OR json_type(settings_json,
                        '$."user/io/github/dsheirer/preference/nowplaying"."live.detail.matching.row.limit"') IS NOT NULL
                )
                """));
            assertEquals("11111111-2222-4333-8444-555555555555:TRUNKED," +
                "66666666-7777-4888-8999-aaaaaaaaaaaa:CONVENTIONAL", scalar(connection, """
                SELECT group_concat(configuration_id || ':' || channel_kind, ',')
                FROM (SELECT * FROM configuration_channel ORDER BY id)
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE decoder_type='MPT1327' OR source_type='MIXER'
                """));
            assertTrue(authenticates(connection, "admin", "fixture primary password"));
            assertTrue(authenticates(connection, "listener", "fixture listener password"));
            assertTrue(authenticates(connection, "operator", "fixture operator password"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void rollbackRestoresExactFormat4StateAndTheSameSourceCanBeRetried() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("rollback-retry.sqlite"));
        try(Connection connection = open(database))
        {
            insertOpaqueRetiredChannels(connection);
            String sourceFingerprint = SqliteSchemaValidator.fingerprint(connection);
            String sourceMetadata = metadataRows(connection);
            String sourceChannels = configurationRows(connection);
            String sourceSettingsDigest = applicationSettingDigest(connection);

            connection.setAutoCommit(false);
            try
            {
                new Format4To5DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 5);
                assertEquals("5", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
                assertTrue(tableExists(connection, "web_user"));
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(sourceFingerprint, SqliteSchemaValidator.fingerprint(connection));
            assertEquals("4", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(sourceMetadata, metadataRows(connection));
            assertEquals(sourceChannels, configurationRows(connection));
            assertEquals(sourceSettingsDigest, applicationSettingDigest(connection));
            assertFalse(tableExists(connection, "web_user"));
            assertEquals(4, DatabaseFormatCatalog.inspect(connection).version());

            migrateToFormat5(connection);

            assertEquals(5, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("3", scalar(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE decoder_type='MPT1327' OR source_type='MIXER'
                """));
        }
    }

    @Test
    void replacesDuplicateSavedChannelIdentityWithoutDroppingEitherChannel() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("ambiguous.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET config_json=json_set(config_json, '$.configurationId',
                    '11111111-2222-4333-8444-555555555555')
                WHERE id=(SELECT max(id) FROM configuration_channel)
                """);
            DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            migrateToFormat5(connection);
            assertEquals("2", scalar(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals("2", scalar(connection,
                "SELECT COUNT(DISTINCT configuration_id) FROM configuration_channel"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE configuration_id <> json_extract(config_json, '$.configurationId')
                """));
        }
    }

    @Test
    void canonicalizesRecoverableLegacyChannelIdentifiersWithoutReplacingThem() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("recoverable-channel-identifiers.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            String expectedConfigurationId = scalar(connection, """
                SELECT json_extract(config_json, '$.configurationId') FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);
            String expectedRadioResolveId = scalar(connection, """
                SELECT radres_guid FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET config_json=json_set(
                        json_remove(config_json, '$.radresGuid', '$.radioResolveId', '$.radres_guid'),
                        '$.configurationId',
                        '  ' || upper(json_extract(config_json, '$.configurationId')) || '  '),
                    radres_guid='  ' || upper(radres_guid) || '  '
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);

            DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            migrateToFormat5(connection);

            assertEquals(expectedConfigurationId, scalar(connection, """
                SELECT configuration_id FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
            assertEquals(expectedConfigurationId, scalar(connection, """
                SELECT json_extract(config_json, '$.configurationId') FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
            assertEquals(expectedRadioResolveId, scalar(connection, """
                SELECT radres_guid FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
            assertEquals(expectedRadioResolveId, scalar(connection, """
                SELECT json_extract(config_json, '$.radresGuid') FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
        }
    }

    @Test
    void preservesJsonIdentityForTrunkedChannelWithoutCanonicalSiteGuidProjection() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("missing-site-guid.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel SET radres_guid=NULL
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);
            DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            migrateToFormat5(connection);
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE channel_kind='TRUNKED' AND radres_guid IS NOT NULL
                  AND radres_guid=json_extract(config_json, '$.radresGuid')
                """));
        }
    }

    @Test
    void dropsRetiredJsonEvenWhenLegacyScalarsDoNotIdentifyIt() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("retired-json-active-scalars.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET decoder_type=NULL,
                    config_json=json_set(config_json, '$.decodeConfiguration.type', 'decodeConfigMPT1327')
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);
            DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            migrateToFormat5(connection);
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals("0", scalar(connection,
                "SELECT COUNT(*) FROM configuration_channel WHERE decoder_type='MPT1327'"));
        }
    }

    @Test
    void rebuildsActiveChannelScalarProjectionFromJson() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("channel-scalar-mismatch.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel SET decoder_type='AM'
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);
            DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            migrateToFormat5(connection);

            assertEquals("P25_PHASE1", scalar(connection,
                "SELECT decoder_type FROM configuration_channel WHERE id=(SELECT min(id) " +
                    "FROM configuration_channel)"));
            assertEquals("5", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertTrue(tableExists(connection, "web_user"));
        }
    }

    @Test
    void preservesJsonOwnedChannelValuesWhenLegacyProjectionsAreUnusable() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("channel-row-owned-fallback.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            String expectedSystem = scalar(connection, """
                SELECT json_extract(config_json, '$.system') FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);
            String expectedSite = scalar(connection, """
                SELECT json_extract(config_json, '$.site') FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);
            String expectedName = scalar(connection, """
                SELECT json_extract(config_json, '$.name') FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);
            String expectedRadioResolveId = scalar(connection, """
                SELECT json_extract(config_json, '$.radresGuid') FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);

            statement.executeUpdate("""
                UPDATE configuration_channel
                SET system_name=x'0102', site_name=zeroblob(1), name='   ', alias_list_name=zeroblob(2),
                    radres_guid='not-a-uuid', decoder_type='MPT1327', source_type='MIXER',
                    config_json=json_set(config_json, '$.aliasListName', 'Default P25')
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);

            DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            migrateToFormat5(connection);

            assertEquals("2", scalar(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(expectedSystem, scalar(connection, """
                SELECT system_name FROM configuration_channel WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
            assertEquals(expectedSite, scalar(connection, """
                SELECT site_name FROM configuration_channel WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
            assertEquals(expectedName, scalar(connection, """
                SELECT name FROM configuration_channel WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
            assertEquals("Default P25", scalar(connection, """
                SELECT alias_list_name FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
            assertEquals(expectedRadioResolveId, scalar(connection, """
                SELECT radres_guid FROM configuration_channel WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
            assertEquals("P25_PHASE1:TUNER", scalar(connection, """
                SELECT decoder_type || ':' || source_type FROM configuration_channel
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE system_name <> json_extract(config_json, '$.system')
                   OR site_name <> json_extract(config_json, '$.site')
                   OR name <> json_extract(config_json, '$.name')
                   OR alias_list_name <> json_extract(config_json, '$.aliasListName')
                   OR radres_guid <> json_extract(config_json, '$.radresGuid')
                """));
        }
    }

    private static void migrateToFormat5(Connection connection) throws Exception
    {
        connection.setAutoCommit(false);
        try
        {
            new Format4To5DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 5);
            connection.commit();
        }
        catch(Exception exception)
        {
            connection.rollback();
            throw exception;
        }
        finally
        {
            connection.setAutoCommit(true);
        }
    }

    @Test
    void rebuildsInvalidAutoStartScalarsFromChannelJson() throws Exception
    {
        String[] assignments = {
            "auto_start=2",
            "auto_start=0.5",
            "auto_start_order=1.5",
            "auto_start_order=2147483648"
        };

        for(int index = 0; index < assignments.length; index++)
        {
            Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("invalid-auto-start-" + index +
                ".sqlite"));
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                statement.executeUpdate("UPDATE configuration_channel SET " + assignments[index] +
                    " WHERE id=(SELECT min(id) FROM configuration_channel)");
                DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
                migrateToFormat5(connection);
                assertEquals("5", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
                assertEquals("0:0", scalar(connection, """
                    SELECT auto_start || ':' || auto_start_order
                    FROM configuration_channel WHERE id=(SELECT min(id) FROM configuration_channel)
                    """));
            }
        }
    }

    @Test
    void resetsOnlyLegacyWebStateWhenUsersOrPoliciesHaveNoPrimaryAdministrator() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("users-without-primary.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE application_settings
                SET settings_json=json_set(settings_json, '$.primaryAdmin', NULL)
                WHERE key='web.access.v1'
                """);
            DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            migrateToFormat5(connection);
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM web_access_policy"));
            assertEquals("2", scalar(connection, "SELECT COUNT(*) FROM configuration_channel"));
        }
    }

    @Test
    void preservesLegacyAccountsWhenOnlyAccessFormatVersionIsMissing() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("access-without-format-version.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            Map<String,CredentialSnapshot> credentialsBefore = legacyCredentials(connection);
            statement.executeUpdate("""
                UPDATE application_settings
                SET settings_json=json_remove(settings_json, '$.formatVersion')
                WHERE key='web.access.v1'
                """);

            DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection));
            migrateToFormat5(connection);

            assertEquals("3", scalar(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals("2", scalar(connection, "SELECT COUNT(*) FROM web_access_policy"));
            assertEquals(credentialsBefore, normalizedCredentials(connection));
            assertTrue(authenticates(connection, "admin", "fixture primary password"));
        }
    }

    @Test
    void retainsPersonalSettingsWithoutAnAccountForInitialAdministratorSetup() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("settings-without-owner.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE application_settings
                SET settings_json='{"formatVersion":1,"primaryAdmin":null,"users":[],"policyOverrides":{}}'
                WHERE key='web.access.v1'
                """);
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEffect(preflight, DatabaseMigrationEffect.Kind.PRESERVE,
                "initial administrator browser preferences", DatabaseMigrationEffect.UNKNOWN_COUNT);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DROP, "superseded settings storage",
                DatabaseMigrationEffect.UNKNOWN_COUNT);

            connection.setAutoCommit(false);
            try
            {
                DatabaseMigrationChain.migrate(connection);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION),
                metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*) FROM application_settings WHERE key='web.display.v1'
                """));
            assertEquals("false:true:DETAILED:125", scalar(connection, """
                SELECT json_extract(settings_json,
                           '$."user/io/github/dsheirer/preference/nowplaying"."show.control.decode.quality"') || ':' ||
                       json_extract(settings_json,
                           '$."user/io/github/dsheirer/preference/nowplaying"."show.voice.decode.quality"') || ':' ||
                       json_extract(settings_json,
                           '$."user/io/github/dsheirer/preference/nowplaying"."decode.quality.display.mode"') || ':' ||
                       json_extract(settings_json,
                           '$."user/io/github/dsheirer/preference/nowplaying"."live.detail.matching.row.limit"')
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
        }
    }

    private static boolean authenticates(Connection connection, String username, String password) throws Exception
    {
        try(var statement = connection.prepareStatement("""
            SELECT credential_version, password_algorithm, password_iterations, password_derived_key_bits,
                   password_salt, password_hash, password_changed_at_ms, auth_revision
            FROM web_user WHERE username=?
            """))
        {
            statement.setString(1, username);
            try(ResultSet resultSet = statement.executeQuery())
            {
                resultSet.next();
                WebPasswordVerifier credential = new WebPasswordVerifier(resultSet.getInt(1), username,
                    resultSet.getString(2), resultSet.getInt(3), resultSet.getInt(4),
                    Base64.getEncoder().encodeToString(resultSet.getBytes(5)),
                    Base64.getEncoder().encodeToString(resultSet.getBytes(6)), resultSet.getLong(7),
                    resultSet.getLong(8));
                return new Pbkdf2PasswordHasher().verify(credential, username, password.toCharArray());
            }
        }
    }

    private static void insertOpaqueRetiredChannels(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    id, sort_order, system_name, site_name, name, alias_list_name, radres_guid, auto_start,
                    auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                    recording_enabled, event_logging_enabled, config_json
                ) VALUES (77, 9, 'Legacy System', 'Legacy Site', 'Retired MPT', 'Legacy Aliases', 'legacy-guid',
                    1, 4, 'MPT1327', 'TUNER', 451000000, 2, 1, 1,
                    '{"type":"opaque-retired-mpt","payload":"drop without decoding"}')
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    id, sort_order, system_name, site_name, name, alias_list_name, radres_guid, auto_start,
                    auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                    recording_enabled, event_logging_enabled, config_json
                ) VALUES (78, 10, 'Legacy System', 'Audio Input', 'Retired Sound Card', 'Legacy Aliases',
                    'legacy-sound-guid', 1, 5, 'DMR', 'MIXER', NULL, 0, 0, 1,
                    '{"type":"opaque-retired-sound-card","payload":"drop without decoding"}')
                """);
        }
    }

    private static Map<String,CredentialSnapshot> legacyCredentials(Connection connection) throws SQLException
    {
        Map<String,CredentialSnapshot> credentials = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
            WITH access AS (
                SELECT settings_json FROM application_settings WHERE key='web.access.v1'
            ), credentials AS (
                SELECT json_extract(settings_json, '$.primaryAdmin') AS credential FROM access
                UNION ALL
                SELECT json_extract(user.value, '$.credential')
                FROM access, json_each(access.settings_json, '$.users') AS user
            )
            SELECT json_extract(credential, '$.username') AS username,
                   json_extract(credential, '$.version') AS credential_version,
                   json_extract(credential, '$.algorithm') AS algorithm,
                   json_extract(credential, '$.iterations') AS iterations,
                   json_extract(credential, '$.derivedKeyBits') AS derived_key_bits,
                   json_extract(credential, '$.saltBase64') AS salt_base64,
                   json_extract(credential, '$.passwordHashBase64') AS hash_base64,
                   json_extract(credential, '$.passwordChangedAtEpochMillis') AS changed_at_ms,
                   json_extract(credential, '$.credentialVersion') AS auth_revision
            FROM credentials ORDER BY username
            """))
        {
            while(resultSet.next())
            {
                CredentialSnapshot credential = new CredentialSnapshot(resultSet.getInt("credential_version"),
                    resultSet.getString("algorithm"), resultSet.getInt("iterations"),
                    resultSet.getInt("derived_key_bits"), resultSet.getString("salt_base64"),
                    resultSet.getString("hash_base64"), resultSet.getLong("changed_at_ms"),
                    resultSet.getLong("auth_revision"));
                credentials.put(resultSet.getString("username"), credential);
            }
        }
        return Map.copyOf(credentials);
    }

    private static Map<String,CredentialSnapshot> normalizedCredentials(Connection connection) throws SQLException
    {
        Map<String,CredentialSnapshot> credentials = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
            SELECT username, credential_version, password_algorithm, password_iterations,
                   password_derived_key_bits, password_salt, password_hash, password_changed_at_ms, auth_revision
            FROM web_user ORDER BY username
            """))
        {
            while(resultSet.next())
            {
                CredentialSnapshot credential = new CredentialSnapshot(resultSet.getInt("credential_version"),
                    resultSet.getString("password_algorithm"), resultSet.getInt("password_iterations"),
                    resultSet.getInt("password_derived_key_bits"),
                    Base64.getEncoder().encodeToString(resultSet.getBytes("password_salt")),
                    Base64.getEncoder().encodeToString(resultSet.getBytes("password_hash")),
                    resultSet.getLong("password_changed_at_ms"), resultSet.getLong("auth_revision"));
                credentials.put(resultSet.getString("username"), credential);
            }
        }
        return Map.copyOf(credentials);
    }

    private static void assertEffect(DatabaseMigrationChain.PreflightReport report,
                                     DatabaseMigrationEffect.Kind kind, String subject, long count)
    {
        DatabaseMigrationEffect effect = report.steps().getFirst().effects().stream()
            .filter(candidate -> candidate.kind() == kind && candidate.subject().equals(subject))
            .findFirst().orElseThrow();
        assertEquals(count, effect.affectedRows());
    }

    private static Connection open(Path database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static boolean tableExists(Connection connection, String name) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name=?"))
        {
            statement.setString(1, name);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private static String metadata(Connection connection, String key) throws Exception
    {
        try(var statement = connection.prepareStatement("SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static String metadataRows(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT group_concat(row_json, char(10)) FROM (
                SELECT json_array(key, value, updated_at_ms) AS row_json
                FROM database_metadata ORDER BY key
            )
            """);
    }

    private static String configurationRows(Connection connection) throws Exception
    {
        return scalar(connection, """
            SELECT group_concat(row_json, char(10)) FROM (
                SELECT json_array(id, sort_order, system_name, site_name, name, alias_list_name, radres_guid,
                                  auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                                  frequency_count, recording_enabled, event_logging_enabled, config_json) AS row_json
                FROM configuration_channel ORDER BY id
            )
            """);
    }

    private static String applicationSettingDigest(Connection connection) throws Exception
    {
        String rows = scalar(connection, """
            SELECT group_concat(row_json, char(10)) FROM (
                SELECT json_array(key, settings_json, updated_at_ms) AS row_json
                FROM application_settings ORDER BY key
            )
            """);
        byte[] bytes = rows.getBytes(StandardCharsets.UTF_8);
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        finally
        {
            java.util.Arrays.fill(bytes, (byte)0);
        }
    }

    private record CredentialSnapshot(int version, String algorithm, int iterations, int derivedKeyBits,
                                      String saltBase64, String passwordHashBase64,
                                      long passwordChangedAtEpochMillis, long authRevision)
    {
        @Override
        public String toString()
        {
            return "CredentialSnapshot[version=" + version + ", algorithm=" + algorithm + ", iterations=" +
                iterations + ", derivedKeyBits=" + derivedKeyBits + ", salt=<redacted>, verifier=<redacted>, " +
                "passwordChangedAtEpochMillis=" + passwordChangedAtEpochMillis + ", authRevision=" + authRevision +
                "]";
        }
    }
}
