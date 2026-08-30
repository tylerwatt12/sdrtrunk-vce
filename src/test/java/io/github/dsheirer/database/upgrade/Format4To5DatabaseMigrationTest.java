/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            assertEquals(5, preflight.steps().size());
            assertEquals("format-4-to-5", preflight.steps().getFirst().id());
            assertEffect(preflight, DatabaseMigrationEffect.Kind.TRANSFORM, "saved channel identity scalars", 2);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DROP, "retired channel configurations", 2);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.TRANSFORM, "web accounts", 3);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.TRANSFORM, "web access policy overrides", 2);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DEFAULT, "per-user browser preferences", 3);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DEFAULT, "site-settings revision", 1);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DROP, "retired web policy overrides", 2);
            assertEffect(preflight, DatabaseMigrationEffect.Kind.DROP, "superseded settings storage", 6);

            connection.setAutoCommit(false);
            try
            {
                DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
                assertEquals(4, report.source().version());
                assertEquals(9, report.target().version());
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

            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
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
            assertEquals("4:light:normal:0:1:4:0", scalar(connection, """
                SELECT json_extract(preferences_json, '$.version') || ':' ||
                       json_extract(preferences_json, '$.appearance.theme') || ':' ||
                       json_extract(preferences_json, '$.scanner.detail_mode') || ':' ||
                       json_array_length(json_extract(preferences_json, '$.playback.selected_scan_list_ids')) || ':' ||
                       json_extract(preferences_json, '$.playback.conversation_grouping') || ':' ||
                       json_extract(preferences_json, '$.playback.conversation_burst_limit') || ':' ||
                       json_array_length(json_extract(preferences_json, '$.health_alerts.disabled_codes'))
                FROM web_user WHERE username='admin'
                """));
            assertEquals("0:1:0", scalar(connection, """
                SELECT json_extract(preferences_json,
                           '$.presentation.show_only_active_trunked_channels') || ':' ||
                       json_extract(preferences_json,
                           '$.presentation.retain_last_call_on_idle_rows') || ':' ||
                       json_extract(preferences_json,
                           '$.presentation.clear_voice_quality_when_idle')
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
            assertEquals("0", scalar(connection, """
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
                DatabaseMigrationChain.MigrationReport rolledBack = DatabaseMigrationChain.migrate(connection);
                assertEquals(9, rolledBack.target().version());
                assertEquals("9", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
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

            connection.setAutoCommit(false);
            try
            {
                DatabaseMigrationChain.MigrationReport retried = DatabaseMigrationChain.migrate(connection);
                assertEquals(9, retried.target().version());
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

            assertEquals(9, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals("3", scalar(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE decoder_type='MPT1327' OR source_type='MIXER'
                """));
        }
    }

    @Test
    void refusesAmbiguousFormat4WithoutChangingIt() throws Exception
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
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection)));
            assertTrue(exception.getMessage().contains("Duplicate saved channel configurationId"));
            assertEquals("4", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertFalse(tableExists(connection, "web_user"));
            assertEquals("2", scalar(connection, "SELECT COUNT(*) FROM configuration_channel"));
        }
    }

    @Test
    void refusesTrunkedChannelWithoutCanonicalSiteGuid() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("missing-site-guid.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel SET radres_guid=NULL
                WHERE id=(SELECT min(id) FROM configuration_channel)
                """);
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection)));
            assertTrue(exception.getMessage().contains("trunked but has no RadioReference site GUID"));
            assertEquals("4", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertFalse(tableExists(connection, "web_user"));
        }
    }

    @Test
    void refusesRetiredJsonWhenExactScalarsDoNotMarkTheRowForRemoval() throws Exception
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
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection)));
            assertTrue(exception.getMessage().contains("is not an active supported channel"), exception::getMessage);
            assertEquals("4", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertFalse(tableExists(connection, "web_user"));
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
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            connection.setAutoCommit(true);

            assertEquals("P25_PHASE1", scalar(connection,
                "SELECT decoder_type FROM configuration_channel WHERE id=(SELECT min(id) " +
                    "FROM configuration_channel)"));
            assertEquals("9", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertTrue(tableExists(connection, "web_user"));
        }
    }

    @Test
    void refusesInvalidAutoStartScalarsBeforeMutation() throws Exception
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
                SQLException exception = assertThrows(SQLException.class,
                    () -> DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection)));
                assertTrue(exception.getMessage().contains("auto_start"), exception::getMessage);
                assertEquals("4", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
                assertFalse(tableExists(connection, "web_user"));
            }
        }
    }

    @Test
    void refusesLegacyUsersOrPoliciesWithoutPrimaryAdministrator() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("users-without-primary.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE application_settings
                SET settings_json=json_set(settings_json, '$.primaryAdmin', NULL)
                WHERE key='web.access.v1'
                """);
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection)));
            assertTrue(exception.getMessage().contains(
                "Legacy users or policies exist without the primary administrator"), exception::getMessage);
            assertEquals("4", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertFalse(tableExists(connection, "web_user"));
        }
    }

    @Test
    void refusesPersonalSettingsWithoutAnAccountOwnerAndLeavesSourceUnchanged() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("settings-without-owner.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE application_settings
                SET settings_json='{"formatVersion":1,"primaryAdmin":null,"users":[],"policyOverrides":{}}'
                WHERE key='web.access.v1'
                """);
            String displayBefore = scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='web.display.v1'
                """);
            SQLException exception = assertThrows(SQLException.class,
                () -> DatabaseMigrationChain.validateSource(connection, DatabaseFormatCatalog.inspect(connection)));
            assertTrue(exception.getMessage().contains(
                "Personal web settings exist without an account to own them"), exception::getMessage);
            assertTrue(exception.getMessage().contains(
                "create the primary administrator in the old build before migrating"), exception::getMessage);
            assertEquals("4", metadata(connection, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(displayBefore, scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='web.display.v1'
                """));
            assertFalse(tableExists(connection, "web_user"));
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
