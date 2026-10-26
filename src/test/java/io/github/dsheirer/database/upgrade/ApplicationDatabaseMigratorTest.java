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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.InitialAdminSetup;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.mpt1327.DecodeConfigMPT1327;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.settings.WebUserPreferences;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("platform-filesystem")
class ApplicationDatabaseMigratorTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void validatesExactCurrentStagedDatabaseWithoutChangingSchema() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("Database is already at current format"));
        assertFalse(result.output().contains("Updating the staged database"));
        assertTrue(result.output().contains("Portable preference components repaired or reset: 0"));
        assertFalse(result.output().contains(database.toString()), result::output);
        assertTrue(result.error().isEmpty());

        try(Connection connection = open(database))
        {
            assertNull(metadata(connection, "alias_schema_version"));
            assertNull(metadata(connection, "configuration_schema_version"));
            assertNull(metadata(connection, "settings_schema_version"));
            assertNull(metadata(connection, "icon_schema_version"));
            assertNull(metadata(connection, "p25_activity_schema_version"));
            assertNull(metadata(connection, "trunked_site_schema_version"));
            assertNull(metadata(connection, "dmr_activity_schema_version"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void refusesMarkerlessFormat27And28SharedLayout() throws Exception
    {
        Path database = newStagedDatabase();
        Format29TestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertEquals(1, statement.executeUpdate(
                "DELETE FROM database_metadata WHERE key='database_format_version'"));
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode(), result.error());
        assertTrue(result.error().contains("ambiguous across formats [27, 28, 29]"), result::error);
        assertFalse(result.output().contains("adopt-global-format-marker"), result::output);
        assertNull(metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
    }

    @Test
    void repairsWrongShapePortablePreferencesInCurrentStagedDatabase() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '[]', 1)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            "RESET unusable portable preference components: 1 preference component(s)"), result::output);
        assertEquals("{}", scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        assertEquals("ok", scalar(database, "PRAGMA quick_check"));
    }

    @Test
    void normalizesCurrentPortablePreferenceStorageMetadata() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '{"valid/node":{"keep":"yes"}}', 0)
                """);
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            "RESET unusable portable preference components: 1 preference component(s)"), result::output);
        assertEquals("yes", scalar(database, """
            SELECT json_extract(settings_json, '$."valid/node".keep')
            FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        assertEquals("1", scalar(database, """
            SELECT typeof(settings_json)='text' AND typeof(updated_at_ms)='integer' AND updated_at_ms > 0
            FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
    }

    @Test
    void repairsPortablePreferenceComponentsIndependently() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1',
                    '{"user/io/github/dsheirer/preference/nowplaying":{"sentinel":"keep",' ||
                    '"receiver.settings.revision":"0","traffic.grant.age.out.milliseconds":"99999",' ||
                    '"retain.idle.call.details":"true"},' ||
                    '"valid/node":{"keep":"yes","bad":7,' ||
                    '"stats.web.call.maximum.listeners":"12"},"bad/node":[]}', 1)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            completedCurrentStep("repair-portable-preferences")), result::output);
        assertTrue(result.output().contains(
            "RESET unusable portable preference components: 6 preference component(s)"), result::output);
        assertEquals("keep", scalar(database, """
            SELECT json_extract(settings_json,
                '$."user/io/github/dsheirer/preference/nowplaying".sentinel')
            FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        assertEquals("yes", scalar(database, """
            SELECT json_extract(settings_json, '$."valid/node".keep')
            FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        assertEquals("0", scalar(database, """
            SELECT json_type(settings_json, '$."bad/node"') IS NOT NULL OR
                   json_type(settings_json, '$."valid/node".bad') IS NOT NULL OR
                   json_type(settings_json,
                       '$."user/io/github/dsheirer/preference/nowplaying"."receiver.settings.revision"') IS NOT NULL
            FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    @Test
    void repairsPreferencesBeforeCurrentAdministrativeState() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        new WebAccessService(database).provisionOrResetPrimaryAdmin("current-format-test".toCharArray());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '[]', 1)
                """);
            statement.executeUpdate("""
                UPDATE application_settings SET settings_json='{}' WHERE key='setup_wizard'
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        int completedRepair = result.output().indexOf(
            completedCurrentStep("repair-portable-preferences"));
        int completedAdministrative = result.output().indexOf(
            completedCurrentStep("repair-current-administrative-state"));
        assertTrue(completedRepair >= 0 && completedAdministrative > completedRepair, result::output);
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION),
            metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        assertEquals("{}", scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
    }

    @Test
    void repairsDirectFormat25StateInsideTheFormat26MigrationStep() throws Exception
    {
        Path database = newStagedDatabase();
        Format25TestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '[]', 1)
                ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json,
                                               updated_at_ms=excluded.updated_at_ms
                """);
            statement.executeUpdate("""
                UPDATE application_settings SET settings_json='{}' WHERE key='setup_wizard'
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            "COMPLETED STEP: 25 -> 26 [format-25-to-26]"), result::output);
        assertTrue(result.output().contains(
            "COMPLETED STEP: 26 -> 27 [format-26-to-27]"), result::output);
        assertTrue(result.output().contains(
            "RESET unusable portable preference components: 1 row(s)"), result::output);
        assertTrue(result.output().contains("DEFAULT unusable setup progress: 1 row(s)"), result::output);
        assertFalse(result.output().contains(
            completedCurrentStep("repair-current-administrative-state")), result::output);
        assertEquals("{}", scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        assertEquals("1", scalar(database, """
            SELECT json_extract(settings_json, '$.imported')
            FROM application_settings WHERE key='setup_wizard'
            """));
        try(Connection connection = open(database))
        {
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void repairsDamagedCurrentAdministrativeComponentsIndependently() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("""
                UPDATE application_settings SET settings_json='{}', updated_at_ms=0
                WHERE key='setup_wizard'
                """);
            statement.executeUpdate("""
                UPDATE application_settings SET settings_json='{}'
                WHERE key='spectrum_snap_country'
                """);
            statement.executeUpdate("""
                UPDATE activity.database_metadata SET value='0'
                WHERE key='conventional_call_output_metrics_started_at_ms'
                """);
            statement.executeUpdate("""
                DELETE FROM activity.database_metadata WHERE key='trunked_logical_call_metrics_started_at_ms'
                """);
            statement.executeUpdate("""
                UPDATE activity.database_metadata SET value='not-a-time'
                WHERE key='radio_system_metrics_started_at_ms'
                """);
            statement.executeUpdate("UPDATE scan_list SET is_default=0");
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('broken-opaque-setting', 'not-json', 0)
                """);
            statement.executeUpdate("""
                INSERT OR REPLACE INTO application_icons(key, icons_json, updated_at_ms)
                VALUES ('default', '[]', 0)
                """);
            statement.executeUpdate("""
                INSERT OR REPLACE INTO database_metadata(key, value, updated_at_ms)
                VALUES ('icon_config_initialized', 'true', 1),
                       ('broken-opaque-metadata', x'00', 0)
                """);
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            completedCurrentStep("repair-current-administrative-state")), result::output);
        assertTrue(result.output().contains("DEFAULT unusable setup progress: 1 row(s)"), result::output);
        assertTrue(result.output().contains("DEFAULT unusable spectrum-snap settings: 1 row(s)"), result::output);
        assertTrue(result.output().contains(
            "DEFAULT receiver metric collection boundaries: 3 row(s)"), result::output);
        assertTrue(result.output().contains(
            "DEFAULT Default scan-list selection after configuration repair: 1 row(s)"), result::output);
        assertTrue(result.output().contains("DROP malformed opaque application settings: 1 row(s)"), result::output);
        assertTrue(result.output().contains("DROP malformed application icon sets: 1 row(s)"), result::output);
        assertTrue(result.output().contains("DROP malformed non-structural metadata: 1 row(s)"), result::output);
        assertTrue(result.output().contains(
            "DEFAULT icon initialization marker after unusable default icons: 1 row(s)"), result::output);
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM scan_list WHERE is_default=1"));
        assertEquals("0", scalar(database, """
            SELECT COUNT(*) FROM application_settings WHERE key='broken-opaque-setting'
            """));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM application_icons WHERE key='default'"));
        assertEquals("0", scalar(database, """
            SELECT COUNT(*) FROM database_metadata
            WHERE key IN ('icon_config_initialized', 'broken-opaque-metadata')
            """));
        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
        }
        assertEquals("ok", scalar(database, "PRAGMA quick_check"));
    }

    @Test
    void defaultsBrokenWebPreferencesWithoutDiscardingTheCredential() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        char[] password = "preserve this verifier".toCharArray();
        new WebAccessService(database).provisionOrResetPrimaryAdmin(password);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("""
                UPDATE web_user SET preferences_json='{}', preferences_revision=0, updated_at_ms=0
                WHERE username='admin'
                """);
            statement.executeUpdate("""
                INSERT INTO web_access_policy(capability_id, required_tier, updated_at_ms)
                VALUES ('unknown-current-capability', 'USER', 1)
                """);
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("DEFAULT recoverable web-user state: 1 row(s)"), result::output);
        assertTrue(result.output().contains("DROP unusable web access policy overrides: 1 row(s)"), result::output);
        assertTrue(new WebAccessService(database).authenticate("admin", password).isPresent());
        assertEquals(Integer.toString(WebUserPreferences.CURRENT_VERSION), scalar(database, """
            SELECT json_extract(preferences_json, '$.version') FROM web_user WHERE username='admin'
            """));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM web_access_policy"));
    }

    @Test
    void resetsOnlyTheWebAccessComponentWhenThePrimaryCredentialIsUnusable() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        new WebAccessService(database).provisionOrResetPrimaryAdmin("broken verifier".toCharArray());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE web_user SET password_hash=x'01' WHERE username='admin'");
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            "RESET web accounts with no usable primary administrator: 1 row(s)"), result::output);
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM web_user"));
        assertEquals("required", scalar(database, """
            SELECT value FROM database_metadata WHERE key='initial_admin_setup'
            """));
        assertEquals("0", scalar(database, """
            SELECT json_extract(settings_json, '$.complete')
            FROM application_settings WHERE key='setup_wizard'
            """));
        assertEquals("ok", scalar(database, "PRAGMA quick_check"));
    }

    @Test
    void dropsOnlyCurrentAliasRoutesWhoseBroadcastProviderIsMissing() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        String providerId;
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            long aliasListId = Long.parseLong(scalar(connection,
                "SELECT id FROM alias_list ORDER BY id LIMIT 1"));
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (16478, %d, 'New Configuration(2)', 'TALKGROUP', 'APCO25', 4321)
                """.formatted(aliasListId));

            RdioScannerConfiguration provider = new RdioScannerConfiguration();
            provider.setName("Preserved Provider");
            provider.setHost("http://127.0.0.1");
            provider.setPort(3000);
            provider.setEnabled(true);
            provider.setApiKey("current-route-fixture");
            provider.setSystemID(1);
            providerId = provider.getConfigurationId();
            ObjectNode payload = OBJECT_MAPPER.valueToTree(provider);
            payload.remove("configurationId");
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream(configuration_id, sort_order, config_json)
                VALUES (?, 0, ?)
                """))
            {
                insert.setString(1, providerId);
                insert.setString(2, OBJECT_MAPPER.writeValueAsString(payload));
                insert.executeUpdate();
            }

            statement.execute("PRAGMA foreign_keys=OFF");
            try(var insert = connection.prepareStatement("""
                INSERT INTO alias_broadcast_channel(id, alias_id, broadcast_configuration_id)
                VALUES (16478, 16478, ?),
                       (16479, 16478, '00000000-0000-0000-0000-000000000001')
                """))
            {
                insert.setString(1, providerId);
                insert.executeUpdate();
            }
            statement.execute("PRAGMA foreign_keys=ON");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            completedCurrentStep("repair-current-configuration-relationships")), result::output);
        assertTrue(result.output().contains(
            "DROP orphaned stream and scan-list relationship rows: 1 row(s)"), result::output);
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM alias WHERE id=16478"));
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM configuration_broadcast_stream WHERE " +
            "configuration_id='" + providerId + "'"));
        assertEquals(providerId, scalar(database, """
            SELECT broadcast_configuration_id FROM alias_broadcast_channel WHERE id=16478
            """));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM alias_broadcast_channel WHERE id=16479"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    @Test
    void strictWalCurrentPairRollsBackBothStoresWhenFinalHistoryAdmissionFails() throws Exception
    {
        Path database = newStagedDatabase();
        CurrentFormatTestDatabase.create(database);
        String channelsBefore;
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertEquals("wal", scalar(connection, "PRAGMA main.journal_mode=WAL"));
            assertEquals("wal", scalar(connection, "PRAGMA activity.journal_mode=WAL"));
            statement.executeUpdate("INSERT INTO activity.sqlite_sequence(name,seq) SELECT 'receiver_activity_event',-1 " +
                "WHERE NOT EXISTS(SELECT 1 FROM activity.sqlite_sequence WHERE name='receiver_activity_event')");
            statement.executeUpdate("UPDATE activity.sqlite_sequence SET seq=-1 WHERE name='receiver_activity_event'");
            statement.executeUpdate("UPDATE application_settings SET settings_json='{}' WHERE key='setup_wizard'");
            // Stable catalogue damage cannot be guessed; it must abort after the reset's transaction writes.
            statement.executeUpdate("UPDATE activity.activity_message_type SET name='ALTERED' " +
                "WHERE definition_kind='ACTION' AND code=12");
            channelsBefore = scalar(connection, "SELECT count(*) FROM receiver_channel");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode(), result::error);
        assertTrue(result.error().contains("changed stable identities"), result::error);
        assertEquals("{}", scalar(database, "SELECT settings_json FROM application_settings WHERE key='setup_wizard'"),
            "The independent configuration repair must roll back with history");
        assertEquals(channelsBefore, scalar(database, "SELECT count(*) FROM receiver_channel"));
        assertEquals("-1", scalar(database, "SELECT seq FROM activity.sqlite_sequence WHERE name='receiver_activity_event'"));
        assertEquals("ALTERED", scalar(database, "SELECT name FROM activity_message_type WHERE definition_kind='ACTION' AND code=12"));
        assertEquals("delete", scalar(database, "PRAGMA main.journal_mode"));
        assertEquals("delete", scalar(database, "PRAGMA activity.journal_mode"));
    }

    @Test
    void strictCurrentValidationUsesTheIndependentHistorySubscriberDictionary() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(7001,1,1,123)");
            statement.executeUpdate("INSERT INTO activity.p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(7001,703710,291,9001)");
            statement.executeUpdate("INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id," +
                "first_seen_ms,last_seen_ms) VALUES(500,'p25:abcde:123',1,703710,291,1000,1000)");
            statement.executeUpdate("INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code," +
                "home_wacn,home_system_id,identity_id,p25_subscriber_identity_id,first_seen_ms,last_seen_ms) " +
                "VALUES(501,500,2,703710,291,9001,7001,1000,1000)");
        }
        CommandResult valid = run(database);
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, valid.exitCode(), valid::error);
        assertFalse(valid.output().contains(completedCurrentStep("reset-damaged-current-derived-state")), valid::output);
        assertEquals("1", scalar(database, "SELECT count(*) FROM radio_system_identity_summary WHERE id=501"));

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            // A locally valid dictionary row that disagrees with its retained owner remains a strict failure.
            statement.executeUpdate("UPDATE activity.p25_subscriber_identity SET subscriber_id=9002 WHERE id=7001");
        }
        byte[] configurationBefore = Files.readAllBytes(database);
        byte[] activityBefore = Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
        CommandResult invalid = run(database);
        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, invalid.exitCode(), invalid::error);
        assertTrue(invalid.error().contains("different canonical P25 subscriber"), invalid::error);
        assertArrayEquals(configurationBefore, Files.readAllBytes(database));
        assertArrayEquals(activityBefore, Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
    }

    @Test
    void rejectsHardLinkedCurrentActivityBeforeRepairingEitherStagedFileWhenLinkCountsAreAvailable() throws Exception
    {
        Path source = mTemporaryFolder.resolve("linked-source/sdrtrunk.sqlite");
        Files.createDirectories(source.getParent());
        SdrTrunkTestDatabase.create(source);
        Path stage = newStagedDatabase();
        Files.copy(source, stage);
        try
        {
            Files.createLink(ActivityDatabasePath.forConfiguration(stage), ActivityDatabasePath.forConfiguration(source));
            Object links = Files.getAttribute(ActivityDatabasePath.forConfiguration(stage), "unix:nlink",
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
            Assumptions.assumeTrue(links instanceof Number && ((Number)links).longValue() > 1,
                "Filesystem link counts are unavailable");
        }
        catch(UnsupportedOperationException | IllegalArgumentException | java.io.IOException | SecurityException exception)
        {
            Assumptions.abort("Filesystem hard-link counts are unavailable");
        }
        byte[] configurationBefore = Files.readAllBytes(stage);
        byte[] activityBefore = Files.readAllBytes(ActivityDatabasePath.forConfiguration(source));

        CommandResult result = run(stage);

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode(), result::error);
        assertArrayEquals(configurationBefore, Files.readAllBytes(stage));
        assertArrayEquals(activityBefore, Files.readAllBytes(ActivityDatabasePath.forConfiguration(source)));
    }

    @Test
    void strictCurrentCopyRepairsHistoryWithoutChangingItsExternalSourcePair() throws Exception
    {
        Path source = mTemporaryFolder.resolve("external/sdrtrunk.sqlite");
        Files.createDirectories(source.getParent());
        CurrentFormatTestDatabase.create(source);
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            assertEquals("wal", scalar(connection, "PRAGMA main.journal_mode=WAL"));
            assertEquals("wal", scalar(connection, "PRAGMA activity.journal_mode=WAL"));
            statement.executeUpdate("INSERT INTO activity.sqlite_sequence(name,seq) SELECT 'receiver_activity_event',-1 " +
                "WHERE NOT EXISTS(SELECT 1 FROM activity.sqlite_sequence WHERE name='receiver_activity_event')");
            statement.executeUpdate("UPDATE activity.sqlite_sequence SET seq=-1 WHERE name='receiver_activity_event'");
        }
        byte[] configurationBefore = Files.readAllBytes(source);
        Path sourceActivity = ActivityDatabasePath.forConfiguration(source);
        byte[] activityBefore = Files.readAllBytes(sourceActivity);
        String configurationFingerprint = new ConfigurationRepository(source).fingerprint();
        Path stage = newStagedDatabase();
        Files.copy(source, stage);
        Files.copy(sourceActivity, ActivityDatabasePath.forConfiguration(stage));

        CommandResult result = run(stage);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result::error);
        assertTrue(result.output().contains(completedCurrentStep("reset-damaged-current-derived-state")), result::output);
        assertEquals(configurationFingerprint, new ConfigurationRepository(stage).fingerprint());
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(stage);
        assertEquals("0", scalar(stage, "SELECT count(*) FROM receiver_activity_event"));
        assertEquals("delete", scalar(stage, "PRAGMA main.journal_mode"));
        assertEquals("delete", scalar(stage, "PRAGMA activity.journal_mode"));
        SdrTrunkDatabaseStartup.validateGlobalDatabase(stage);
        assertArrayEquals(configurationBefore, Files.readAllBytes(source));
        assertArrayEquals(activityBefore, Files.readAllBytes(sourceActivity));
    }

    @Test
    void resetsDamagedCurrentDerivedStateWithoutDiscardingAdministratorAliases() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        String missingConfiguration = UUID.randomUUID().toString();
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            long aliasListId = Long.parseLong(scalar(connection,
                "SELECT id FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1"));
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (18400, %d, 'Preserve this Alias', 'TALKGROUP', 'APCO25', 18400)
                """.formatted(aliasListId));

            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("""
                INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
                VALUES ('%s', 1, 1)
                """.formatted(missingConfiguration));
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("""
                INSERT INTO statistics_status(key, value, updated_at_ms)
                VALUES ('damaged-derived-status', 'discard', 0)
                """);
            statement.execute("PRAGMA ignore_check_constraints=OFF");
            statement.execute("PRAGMA foreign_keys=ON");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            completedCurrentStep("reset-damaged-current-derived-state")), result::output);
        assertTrue(result.output().contains(
            "RESET bounded receiver activity and statistics rows: 2 row(s)"), result::output);
        assertTrue(result.output().contains(
            "DEFAULT receiver metric collection boundaries: 3 row(s)"), result::output);
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM alias WHERE id=18400"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM receiver_channel"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM statistics_status"));
        assertEquals("3", scalar(database, """
            SELECT COUNT(*) FROM activity.database_metadata
            WHERE key IN (
                'conventional_call_output_metrics_started_at_ms',
                'trunked_logical_call_metrics_started_at_ms',
                'radio_system_metrics_started_at_ms')
              AND CAST(value AS INTEGER) > 0 AND updated_at_ms > 0
            """));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        assertEquals("ok", scalar(database, "PRAGMA quick_check"));
        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    @Test
    void preservesValidAnalogAliasSummariesAndRepairsTheirProtocolDrift() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            long analogList = Long.parseLong(scalar(connection,
                "SELECT id FROM alias_list WHERE family='NBFM' LIMIT 1"));
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value, min_value, max_value)
                VALUES (18501, %1$d, 'FM exact', 'TALKGROUP', 'NBFM', 17, NULL, NULL),
                       (18502, %1$d, 'FM range', 'TALKGROUP_RANGE', 'NBFM', NULL, 20, 30),
                       (18503, %1$d, 'AM exact', 'TALKGROUP', 'AM', 18, NULL, NULL),
                       (18504, %1$d, 'AM range', 'TALKGROUP_RANGE', 'AM', NULL, 40, 50),
                       (18505, %1$d, 'Fleetsync without activity metrics', 'TALKGROUP', 'FLEETSYNC', 8193, NULL, NULL),
                       (18506, %1$d, 'MDC without activity metrics', 'TALKGROUP_RANGE', 'MDC1200', NULL, 60, 70)
                """.formatted(analogList));
            io.github.dsheirer.stats.AliasActivitySummaryMaintenance.resetAll(connection);
            statement.executeUpdate("""
                INSERT INTO statistics_status(key, value, updated_at_ms) VALUES ('keep-healthy-activity', 'keep', 1)
                """);
        }
        String fingerprint = new ConfigurationRepository(database).fingerprint();

        CommandResult healthy = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, healthy.exitCode(), healthy.error());
        assertFalse(healthy.output().contains(completedCurrentStep("reset-damaged-current-derived-state")),
            healthy::output);
        assertEquals(fingerprint, new ConfigurationRepository(database).fingerprint(), healthy::output);
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM statistics_status WHERE key='keep-healthy-activity'"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertFalse(CurrentDatabaseDerivedStateRepair.inspect(connection).requiresRepair());
            statement.executeUpdate("""
                UPDATE alias_activity_summary SET protocol_code=0, metrics_state='unsupported' WHERE alias_id=18502
                """);
        }

        CommandResult repaired = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, repaired.exitCode(), repaired.error());
        assertTrue(repaired.output().contains(completedCurrentStep("reset-damaged-current-derived-state")),
            repaired::output);
        assertEquals("2", scalar(database, """
            SELECT COUNT(*) FROM alias_activity_summary
            WHERE alias_id IN (18501,18502) AND protocol_code=10 AND metrics_state='not_collected'
            """));
        assertEquals("2", scalar(database, """
            SELECT COUNT(*) FROM alias_activity_summary
            WHERE alias_id IN (18503,18504) AND protocol_code=11 AND metrics_state='not_collected'
            """));
        assertEquals("2", scalar(database, """
            SELECT COUNT(*) FROM alias_activity_summary
            WHERE alias_id IN (18505,18506) AND protocol_code=0 AND metrics_state='unsupported'
            """));
        assertEquals(fingerprint, new ConfigurationRepository(database).fingerprint());
        try(Connection connection = open(database))
        {
            assertFalse(CurrentDatabaseDerivedStateRepair.inspect(connection).requiresRepair());
        }
    }

    @Test
    void skipsMalformedCurrentChannelsAndProvidersWithoutLosingUsableConfiguration() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        String validProviderId;
        String invalidProviderId = "00000000-0000-4000-8000-000000017501";
        String invalidChannelId = "00000000-0000-4000-8000-000000017502";
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            long aliasListId = Long.parseLong(scalar(connection,
                "SELECT id FROM alias_list ORDER BY id LIMIT 1"));
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (17500, %d, 'Keep this Alias', 'TALKGROUP', 'APCO25', 17500)
                """.formatted(aliasListId));

            RdioScannerConfiguration validProvider = new RdioScannerConfiguration();
            validProvider.setName("Keep this provider");
            validProvider.setHost("http://127.0.0.1");
            validProvider.setPort(3000);
            validProvider.setEnabled(true);
            validProvider.setApiKey("must-not-appear-in-migration-output");
            validProvider.setSystemID(1);
            validProviderId = validProvider.getConfigurationId();
            ObjectNode validPayload = OBJECT_MAPPER.valueToTree(validProvider);
            validPayload.remove("configurationId");
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream(id, configuration_id, sort_order, config_json)
                VALUES (17500, ?, 0, ?), (17501, ?, 1, '{}')
                """))
            {
                insert.setString(1, validProviderId);
                insert.setString(2, OBJECT_MAPPER.writeValueAsString(validPayload));
                insert.setString(3, invalidProviderId);
                insert.executeUpdate();
            }
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(id, alias_id, broadcast_configuration_id) VALUES
                    (17500, 17500, '%s'),
                    (17501, 17500, '%s')
                """.formatted(validProviderId, invalidProviderId));
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    id, configuration_id, channel_kind, sort_order, name, alias_list_id, auto_start, decoder_type,
                    address_domain_code, config_json
                ) VALUES (17502, '%s', 'TRUNKED', 0, 'Broken saved channel', %d, 0, 'P25_PHASE1', 0, '{}')
                """.formatted(invalidChannelId, aliasListId));
            statement.executeUpdate("""
                INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
                VALUES ('%s', 1, 1)
                """.formatted(invalidChannelId));
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("""
                UPDATE receiver_channel SET first_seen_ms=0 WHERE configuration_id='%s'
                """.formatted(invalidChannelId));
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("DROP unusable saved channel rows: 1 row(s)"), result::output);
        assertTrue(result.output().contains("DROP unusable broadcast provider rows: 1 row(s)"), result::output);
        assertTrue(result.output().contains(
            "DROP orphaned stream and scan-list relationship rows: 1 row(s)"), result::output);
        int completedDerived = result.output().indexOf(
            completedCurrentStep("reset-damaged-current-derived-state"));
        int completedConfiguration = result.output().indexOf(
            completedCurrentStep("repair-current-configuration-relationships"));
        assertTrue(completedDerived >= 0 && completedConfiguration > completedDerived, result::output);
        assertTrue(result.output().contains(
            "RESET bounded receiver activity and statistics rows: 1 row(s)"), result::output);
        assertFalse(result.output().contains("must-not-appear-in-migration-output"), result::output);
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM alias WHERE id=17500"));
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM configuration_broadcast_stream WHERE " +
            "configuration_id='" + validProviderId + "'"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM configuration_broadcast_stream WHERE " +
            "configuration_id='" + invalidProviderId + "'"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM configuration_channel WHERE " +
            "configuration_id='" + invalidChannelId + "'"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM receiver_channel"));
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM alias_broadcast_channel WHERE id=17500"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM alias_broadcast_channel WHERE id=17501"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
    }

    @Test
    void repairsCurrentAliasAndScanListNamesThatCollideAfterTrimming() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family, unmatched_talkgroup_record_enabled) VALUES
                    (17600, 'Dispatch Repair', 'P25', 0),
                    (17601, ' Dispatch Repair ', 'P25', 0)
                """);
            statement.executeUpdate("""
                INSERT INTO scan_list(id, sort_order, name, description, published, is_default) VALUES
                    (17600, 100, 'Operations Repair', NULL, 1, 0),
                    (17601, 101, ' Operations Repair ', NULL, 1, 0)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            "DEFAULT Alias List names separated after normalization: 1 row(s)"), result::output);
        assertTrue(result.output().contains(
            "DEFAULT scan-list names separated after normalization: 1 row(s)"), result::output);
        assertEquals("Dispatch Repair|Dispatch Repair (2)", scalar(database, """
            SELECT group_concat(name, '|') FROM
                (SELECT name FROM alias_list WHERE id IN (17600, 17601) ORDER BY id)
            """));
        assertEquals("Operations Repair|Operations Repair (2)", scalar(database, """
            SELECT group_concat(name, '|') FROM
                (SELECT name FROM scan_list WHERE id IN (17600, 17601) ORDER BY id)
            """));
        try(Connection connection = open(database))
        {
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void keepsCurrentChannelAndReassignsAnIncompatibleAliasListAssignment() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        String configurationId;
        try(Connection connection = open(database))
        {
            long p25AliasListId = Long.parseLong(scalar(connection,
                "SELECT id FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1"));
            Channel channel = new Channel("Keep this NBFM channel");
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(155_250_000L);
            channel.setSourceConfiguration(source);
            configurationId = channel.getConfigurationId();
            ObjectNode payload = OBJECT_MAPPER.valueToTree(channel);
            payload.remove(java.util.List.of("configurationId", "system", "site", "name", "aliasListId",
                "aliasListName", "radioResolveId", "radresGuid", "radres_guid", "autoStart", "enabled",
                "autoStartOrder", "order", "channelType"));
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, name, alias_list_id, auto_start, decoder_type,
                    address_domain_code, primary_frequency_hz, config_json
                ) VALUES (?, 'CONVENTIONAL', 0, 'Keep this NBFM channel', ?, 0, 'NBFM', 0, 155250000, ?)
                """))
            {
                insert.setString(1, configurationId);
                insert.setLong(2, p25AliasListId);
                insert.setString(3, OBJECT_MAPPER.writeValueAsString(payload));
                insert.executeUpdate();
            }
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("DEFAULT channels with an unusable Alias List: 1 row(s)"),
            result::output);
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM configuration_channel WHERE configuration_id='" +
            configurationId + "'"));
        assertEquals("NBFM", scalar(database, """
            SELECT list.family
            FROM configuration_channel channel
            JOIN alias_list list ON list.id=channel.alias_list_id
            WHERE channel.configuration_id='%s'
            """.formatted(configurationId)));
    }

    @Test
    void refusesMarkerlessHistoricalLayoutWhenItsSemanticFormatIsAmbiguous() throws Exception
    {
        Path database = newStagedDatabase();
        Format13TestDatabase.create(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement();
            var deleteMarker = connection.prepareStatement("DELETE FROM database_metadata WHERE key=?"))
        {
            //Without a persisted preference document, the shared historical DDL has no safe semantic-version clue.
            statement.executeUpdate("DELETE FROM web_access_policy");
            statement.executeUpdate("DELETE FROM web_user");
            deleteMarker.setString(1, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            assertEquals(1, deleteMarker.executeUpdate());
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode(), result.error());
        assertTrue(result.error().contains("ambiguous across formats [6, 7, 8, 9, 10, 11, 12, 13]"), result.error());
        assertFalse(result.output().contains("adopt-global-format-marker"));
        assertFalse(result.output().contains("format-1-to-2"));
        assertFalse(result.output().contains("format-2-to-3"));
        assertNull(metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
    }

    @Test
    void refusesMarkerlessFormat24And25SharedLayout() throws Exception
    {
        Path database = newStagedDatabase();
        Format25TestDatabase.create(database);

        try(Connection connection = open(database); var statement = connection.prepareStatement(
            "DELETE FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            assertEquals(1, statement.executeUpdate());
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode(), result.error());
        assertTrue(result.error().contains("ambiguous across formats [24, 25]"), result::error);
        assertFalse(result.output().contains("adopt-global-format-marker"), result::output);
        assertNull(metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
    }

    @Test
    void doesNotUseRepairableMetadataToGuessAMissingFormat24Or25Marker() throws Exception
    {
        Path database = newStagedDatabase();
        Format25TestDatabase.create(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertEquals(1, statement.executeUpdate("""
                DELETE FROM database_metadata WHERE key='database_format_version'
                """));
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO database_metadata(key, value, updated_at_ms)
                VALUES ('alias_schema_version', '6', 1)
                """));
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode(), result.error());
        assertTrue(result.error().contains("ambiguous across formats [24, 25]"), result::error);
        assertFalse(result.output().contains("malformed non-structural metadata"), result::output);
        assertEquals("6", metadata(database, "alias_schema_version"));
        assertNull(metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
    }

    @Test
    void migratesExactPublishedFormat2NightlyToCurrent() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertFalse(result.output().contains("format-1-to-2"));
        assertTrue(result.output().contains("COMPLETED STEP: 2 -> 3 [format-2-to-3]"));
        assertTrue(result.output().indexOf("COMPLETED STEP: 2 -> 3 [format-2-to-3]") <
            result.output().indexOf(completedCurrentStep("repair-portable-preferences")), result::output);
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION),
            metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));

        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test
    void format2MigrationRecoversMatchingJsonAliasListBeforeFactoryDefaults() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(name, family) VALUES ('County P25', 'P25')");
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    id, sort_order, name, alias_list_name, radres_guid, decoder_type, source_type,
                    primary_frequency_hz, frequency_count, config_json
                ) VALUES (1, 1, 'County Control', NULL,
                    '00000000-0000-4000-8000-000000000001', 'P25_PHASE1', 'TUNER', 851012500, 1, ?)
                """))
            {
                insert.setString(1, channelJson("County Control", "County", "Simulcast", "County P25",
                    DecoderType.P25_PHASE1, 851012500));
                insert.executeUpdate();
            }
            assertEquals(1, new Format2To3DatabaseMigration().validateSource(connection).stream()
                .filter(effect -> effect.subject().equals("saved channel Alias List projections"))
                .findFirst().orElseThrow().affectedRows());
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertEquals("County P25", scalar(database, """
            SELECT list.name
            FROM configuration_channel channel
            JOIN alias_list list ON list.id=channel.alias_list_id
            WHERE channel.name='County Control'
            """));
    }

    @Test
    void historicalAccountCheckDamageDoesNotBlockIndependentChannelMigration() throws Exception
    {
        Path database = Format11TestDatabase.create(newStagedDatabase());
        String preservedChannelName = scalar(database, """
            SELECT name FROM configuration_channel
            WHERE name IS NOT NULL ORDER BY id LIMIT 1
            """);
        assertTrue(preservedChannelName != null && !preservedChannelName.isBlank());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            assertEquals(1, statement.executeUpdate(
                "UPDATE web_user SET tier='BROKEN' WHERE username='listener'"));
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM configuration_channel WHERE name='" +
            preservedChannelName.replace("'", "''") + "'"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM web_user WHERE username='listener'"));
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM web_user WHERE primary_admin=1"));
        assertEquals("ok", scalar(database, "PRAGMA quick_check"));
    }

    @Test
    void leavesReusableFreePagesInsteadOfRunningRiskyMigrationTimeCompaction() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.executeUpdate("""
                WITH RECURSIVE sequence(value) AS (
                    VALUES(1)
                    UNION ALL SELECT value + 1 FROM sequence WHERE value < 5000
                )
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                SELECT 'temporary-bloat-' || value,
                       json_object('padding', printf('%01000d', 0)), value
                FROM sequence
                """);
            statement.executeUpdate("DELETE FROM application_settings WHERE key LIKE 'temporary-bloat-%'");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertFalse(result.output().contains("Compacting the migrated staged database."));
        assertTrue(Long.parseLong(scalar(database, "PRAGMA freelist_count")) > 0,
            "Freed pages should remain reusable without requiring temporary VACUUM space");
        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void migratesPublishedAlphaPreferencesWithoutAnAdministratorAndAssignsThemDuringSetup() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms) VALUES
                    ('web.display.v1',
                     '{"format_version":1,"show_encryption_details":false}', 1),
                    ('portable_java_preferences_v1',
                     '{"user/io/github/dsheirer/preference/nowplaying":{' ||
                     '"show.control.decode.quality":"false",' ||
                     '"show.voice.decode.quality":"true",' ||
                     '"decode.quality.display.mode":"DETAILED",' ||
                     '"live.detail.matching.row.limit":"125"},' ||
                     '"user/example":{"sentinel":"preserve-me"}}', 1)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            "PRESERVE initial administrator browser preferences: 5 row(s)"), result.output());
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM web_user"));
        assertEquals("1", scalar(database,
            "SELECT COUNT(*) FROM application_settings WHERE key='web.display.v1'"));

        InitialAdminSetup.initializeNewProfile(database);
        assertTrue(InitialAdminSetup.isPasswordRequired(database));
        char[] password = "migrated alpha administrator".toCharArray();
        InitialAdminSetup.provision(database, password);

        assertEquals("complete", scalar(database, """
            SELECT value FROM database_metadata WHERE key='initial_admin_setup'
            """));
        assertTrue(new WebAccessService(database).authenticate("admin", password).isPresent());
        assertEquals("0:0:1:detailed:125", scalar(database, """
            SELECT json_extract(preferences_json, '$.presentation.show_encryption_details') || ':' ||
                   json_extract(preferences_json, '$.presentation.show_control_decode_quality') || ':' ||
                   json_extract(preferences_json, '$.presentation.show_voice_decode_quality') || ':' ||
                   json_extract(preferences_json, '$.presentation.decode_quality_display_mode') || ':' ||
                   json_extract(preferences_json, '$.presentation.live_detail_row_limit')
            FROM web_user WHERE username='admin'
            """));
        assertEquals("0", scalar(database,
            "SELECT COUNT(*) FROM application_settings WHERE key='web.display.v1'"));
        assertEquals("preserve-me", scalar(database, """
            SELECT json_extract(settings_json, '$."user/example".sentinel')
            FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        assertEquals("0", scalar(database, """
            SELECT COUNT(*) FROM application_settings
            WHERE key='portable_java_preferences_v1' AND (
                json_type(settings_json,
                    '$."user/io/github/dsheirer/preference/nowplaying"."show.control.decode.quality"') IS NOT NULL
                OR json_type(settings_json,
                    '$."user/io/github/dsheirer/preference/nowplaying"."show.voice.decode.quality"') IS NOT NULL
                OR json_type(settings_json,
                    '$."user/io/github/dsheirer/preference/nowplaying"."decode.quality.display.mode"') IS NOT NULL
                OR json_type(settings_json,
                    '$."user/io/github/dsheirer/preference/nowplaying"."live.detail.matching.row.limit"') IS NOT NULL)
            """));
    }

    @Test
    void preservesNoncanonicalSameFamilyFactoryNameAndUsesItsStoredSpelling() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE alias_list SET name='default p25' WHERE name='Default P25'");
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    id, sort_order, name, radres_guid, decoder_type, source_type, primary_frequency_hz,
                    frequency_count, config_json
                ) VALUES (
                    1, 1, 'Blank P25', '00000000-0000-4000-8000-000000000001',
                    'P25_PHASE1', 'TUNER', 851012500, 1, ?
                )
                """))
            {
                insert.setString(1, channelJson("Blank P25", null, null, null,
                    DecoderType.P25_PHASE1, 851012500));
                insert.executeUpdate();
            }
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertEquals("default p25", scalar(database,
            "SELECT name FROM alias_list WHERE name='Default P25' COLLATE NOCASE"));
        assertEquals("default p25", scalar(database, """
            SELECT list.name
            FROM configuration_channel channel
            JOIN alias_list list ON list.id=channel.alias_list_id
            WHERE channel.id=1
            """));
        assertEquals("0", scalar(database, """
            SELECT COUNT(*) FROM configuration_channel
            WHERE id=1 AND json_type(config_json, '$.aliasListName') IS NOT NULL
            """));
        assertNull(metadata(database, "p25_activity_schema_version"));
    }

    @Test
    void preservesExistingCanonicalAliasListRoutingInsteadOfRepurposingIt() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                DELETE FROM alias_list_unmatched_talkgroup_scan_list_membership
                WHERE alias_list_id=(SELECT id FROM alias_list WHERE name='Default P25')
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertEquals("0", scalar(database, """
            SELECT COUNT(*)
            FROM alias_list_unmatched_talkgroup_scan_list_membership
            WHERE alias_list_id=(SELECT id FROM alias_list WHERE name='Default P25')
            """));
        assertNull(metadata(database, "p25_activity_schema_version"));
    }

    @Test
    void preservesOpaqueWebCredentialsWithoutReportingSecrets() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        char[] adminPassword = "format-two-admin-secret".toCharArray();
        char[] listenerPassword = "format-two-listener-secret".toCharArray();
        LegacyWebAccessTestData.storePrimaryAdminAndUser(database, adminPassword, "listener",
            listenerPassword, AccessTier.USER);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        WebAccessService migratedAccess = new WebAccessService(database);
        assertEquals(AccessTier.ADMIN,
            migratedAccess.authenticate("admin", adminPassword).orElseThrow().tier());
        assertEquals(AccessTier.USER,
            migratedAccess.authenticate("listener", listenerPassword).orElseThrow().tier());
        assertFalse(result.output().contains(new String(adminPassword)));
        assertFalse(result.output().contains(new String(listenerPassword)));
        assertFalse(result.error().contains(new String(adminPassword)));
        assertFalse(result.error().contains(new String(listenerPassword)));
    }

    @Test
    void migratesMarkedLatestAlphaFormat2AndThenLeavesCurrentUnchanged() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            DatabaseFormatCatalog.stamp(connection, 2);
            statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(100,1,'Retained Alpha Alias','TALKGROUP','APCO25',43)");
            insertFormat2Channel(connection, 77, "Retained Alpha Channel", DecoderType.P25_PHASE1, 851012500);
            assertTrue(DatabaseFormatCatalog.inspect(connection).markerPresent());
        }
        CommandResult result = run(database);
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertFalse(result.output().contains("format-1-to-2"));
        assertTrue(result.output().contains("COMPLETED STEP: 2 -> 3 [format-2-to-3]"));
        assertEquals("Retained Alpha Alias", scalar(database, "SELECT name FROM alias WHERE id=100"));
        assertEquals("Retained Alpha Channel:851012500", scalar(database,
            "SELECT name || ':' || primary_frequency_hz FROM configuration_channel WHERE id=77"));
        assertEquals("{\"preserved\":true}", scalar(database,
            "SELECT settings_json FROM application_settings WHERE key='format-3-preserve-sentinel'"));
        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        }
        byte[] current = Files.readAllBytes(database);
        CommandResult unchanged = run(database);
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, unchanged.exitCode(), unchanged.error());
        assertTrue(unchanged.output().contains("Database is already at current format"));
        assertArrayEquals(current, Files.readAllBytes(database));
    }

    @Test
    void refusesRetiredFormat1WithoutChangingTheSelectedDatabase() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());
        byte[] original = Files.readAllBytes(database);
        CommandResult result = run(database);
        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode(), result.error());
        assertTrue(result.error().contains("predates Alpha 12"), result::error);
        assertTrue(result.error().contains("Open it in Alpha 12"), result::error);
        assertFalse(result.output().contains("COMPLETED STEP"), result::output);
        assertArrayEquals(original, Files.readAllBytes(database));
    }

    @Test
    void refusesLatestAlphaVersionStampsOnTheWrongSchema() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE unexpected_alpha12_object(id INTEGER PRIMARY KEY)");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("Unrecognized SQLite database schema fingerprint"));
        try(Connection connection = open(database))
        {
            assertEquals("6", metadata(connection, "alias_schema_version"));
            assertEquals("26", metadata(connection, "p25_activity_schema_version"));
        }
    }

    @Test
    void format2MigrationPreservesWrongFamilyFactoryNameCollisionUnderAUniqueName() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE alias_list SET family='DMR' WHERE id=1
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertNull(metadata(database, "alias_schema_version"));
        assertEquals("Default P25 (DMR):DMR|Default P25:P25", scalar(database, """
            SELECT group_concat(name || ':' || family, '|')
            FROM (SELECT name, family FROM alias_list WHERE name LIKE 'Default P25%' ORDER BY id)
            """));
        assertTrue(result.output().contains("custom Alias Lists using factory names"));
    }

    @Test
    void format2MigrationAssignsDefaultsToBlankAliasListNames() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            insertFormat2Channel(connection, 78, "P25 Phase 1", DecoderType.P25_PHASE1, 851012500);
            insertFormat2Channel(connection, 81, "DMR", DecoderType.DMR, 451000000);
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET alias_list_name = '', config_json = json_set(config_json, '$.aliasListName', '')
                WHERE id = 78
                """);
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET alias_list_name = '   ', config_json = json_set(config_json, '$.aliasListName', '   ')
                WHERE id = 81
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertEquals("78:Default P25|81:Default DMR", scalar(database, """
            SELECT group_concat(value, '|')
            FROM (
                SELECT channel.id || ':' || list.name AS value
                FROM configuration_channel channel
                JOIN alias_list list ON list.id=channel.alias_list_id
                WHERE channel.id IN (78, 81)
                ORDER BY channel.id
            )
            """));
        assertEquals("0", scalar(database, """
            SELECT COUNT(*) FROM configuration_channel
            WHERE id IN (78, 81) AND json_type(config_json, '$.aliasListName') IS NOT NULL
            """));
    }

    @Test
    void rebasesPortableDirectoriesWithoutChangingSchema() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        Path source = mTemporaryFolder.resolve("source-data").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("target-data").toAbsolutePath();

        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms)
            VALUES ('portable_java_preferences_v1', ?, 1)
            """))
        {
            Path external = mTemporaryFolder.resolve("external-library.jar").toAbsolutePath();
            Path similarlyNamedSibling = mTemporaryFolder.resolve("source-data-other/streams").toAbsolutePath();
            Path unrecognizedInsideSource = source.resolve("private/leave-alone.txt");
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(java.util.Map.of("directories",
                java.util.Map.of("directory.recording", source.resolve("recordings").toString(),
                    "directory.recording.managed", source.resolve("recordings-managed").toString(),
                    "directory.application.logs", source.resolve("logs").toString(),
                    "path.jmbe.library.primary", source.resolve("jmbe/jmbe.jar").toString(),
                    "path.voice.decryption.module.primary", source.resolve("modules/voice.jar").toString(),
                    "path.jmbe.library.external", external.toString(),
                    "path.voice.decryption.module.relative", "modules/relative.jar",
                    "directory.streaming", similarlyNamedSibling.toString(),
                    "unrecognized.absolute.path", unrecognizedInsideSource.toString()),
                "external", java.util.Map.of("directory.recording.managed",
                    mTemporaryFolder.resolve("external-recordings-managed").toAbsolutePath().toString()))));
            statement.executeUpdate();
        }

        CommandResult result = runArguments(database.toString(), source.toString(), target.toString(),
            "--complete-profile-copy");

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("TRANSFORM portable directory preferences: 5 preference component(s)"));

        try(Connection connection = open(database))
        {
            JsonNode settings = OBJECT_MAPPER.readTree(scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(target.resolve("recordings").toString(),
                settings.path("directories").path("directory.recording").asText());
            assertEquals(target.resolve("recordings-managed").toString(),
                settings.path("directories").path("directory.recording.managed").asText());
            assertEquals(mTemporaryFolder.resolve("external-recordings-managed").toAbsolutePath().toString(),
                settings.path("external").path("directory.recording.managed").asText());
            assertEquals(target.resolve("logs").toString(),
                settings.path("directories").path("directory.application.logs").asText());
            assertEquals(target.resolve("jmbe/jmbe.jar").toString(),
                settings.path("directories").path("path.jmbe.library.primary").asText());
            assertEquals(target.resolve("modules/voice.jar").toString(),
                settings.path("directories").path("path.voice.decryption.module.primary").asText());
            assertEquals(mTemporaryFolder.resolve("external-library.jar").toAbsolutePath().toString(),
                settings.path("directories").path("path.jmbe.library.external").asText());
            assertEquals("modules/relative.jar",
                settings.path("directories").path("path.voice.decryption.module.relative").asText());
            assertEquals(mTemporaryFolder.resolve("source-data-other/streams").toAbsolutePath().toString(),
                settings.path("directories").path("directory.streaming").asText());
            assertEquals(source.resolve("private/leave-alone.txt").toString(),
                settings.path("directories").path("unrecognized.absolute.path").asText());
            assertNull(metadata(connection, "dmr_activity_schema_version"));
        }
    }

    @Test
    void rebasesOnlyRecordingTunerFilesInsideCopiedProfile() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        Path source = mTemporaryFolder.resolve("source-data").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("target-data").toAbsolutePath();
        ObjectNode settings = OBJECT_MAPPER.createObjectNode();
        settings.putArray("disabledTuners").addObject().put("id", "preserved-disabled-tuner");
        var configurations = settings.putArray("tunerConfigurations");
        configurations.addObject().put("type", "recordingTunerConfiguration").put("uniqueID", "owned")
            .put("frequency", 851_000_000).put("path", source.resolve("recording_tuners/../recording_tuners/owned.wav").toString())
            .put("customSetting", "preserved");
        configurations.addObject().put("type", "recordingTunerConfiguration").put("uniqueID", "external")
            .put("path", mTemporaryFolder.resolve("source-data-other/external.wav").toAbsolutePath().toString());
        configurations.addObject().put("type", "recordingTunerConfiguration").put("uniqueID", "relative")
            .put("path", "recording_tuners/relative.wav");
        configurations.addObject().put("type", "unrelatedTunerConfiguration").put("uniqueID", "other")
            .put("path", source.resolve("leave-alone.wav").toString());
        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms) VALUES ('tuner.settings', ?, 1)
            """))
        {
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(settings));
            statement.executeUpdate();
        }
        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms)
            VALUES ('portable_java_preferences_v1', ?, 1)
            """))
        {
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(java.util.Map.of("directories",
                java.util.Map.of("directory.recording.managed", source.resolve("recordings-managed").toString()))));
            statement.executeUpdate();
        }

        CommandResult ordinaryImport = run(database, source, target);
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, ordinaryImport.exitCode(), ordinaryImport.error());
        assertEquals(settings, OBJECT_MAPPER.readTree(scalar(database,
            "SELECT settings_json FROM application_settings WHERE key='tuner.settings'")));
        assertEquals(source.resolve("recordings-managed").toString(), OBJECT_MAPPER.readTree(scalar(database,
            "SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'"))
            .path("directories").path("directory.recording.managed").asText());

        CommandResult result = runArguments(database.toString(), source.toString(), target.toString(),
            "--complete-profile-copy");

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("TRANSFORM recording tuner paths: 1 path(s)"), result::output);
        assertEquals(target.resolve("recordings-managed").toString(), OBJECT_MAPPER.readTree(scalar(database,
            "SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'"))
            .path("directories").path("directory.recording.managed").asText());
        ((ObjectNode)configurations.get(0)).put("path", target.resolve("recording_tuners/owned.wav").toString());
        assertEquals(settings, OBJECT_MAPPER.readTree(scalar(database,
            "SELECT settings_json FROM application_settings WHERE key='tuner.settings'")));
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION), scalar(database,
            "SELECT value FROM database_metadata WHERE key='database_format_version'"));
        CommandResult unchanged = run(database, target, target);
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, unchanged.exitCode(), unchanged.error());
        assertFalse(unchanged.output().contains("TRANSFORM recording tuner paths"));
        assertEquals(settings, OBJECT_MAPPER.readTree(scalar(database,
            "SELECT settings_json FROM application_settings WHERE key='tuner.settings'")));
    }

    @Test
    void dropsOnlyAPathWhoseLongerRelocationWouldOverflowPortablePreferences() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        Path source = mTemporaryFolder.resolve("source").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("target".repeat(512)).toAbsolutePath();

        int maximumBytes = 4_194_304;
        String emptyJson = OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
            "directories", java.util.Map.of("directory.recording", source.resolve("recordings").toString()),
            "preserved", java.util.Map.of("large.preference", "")));
        int preservedLength = maximumBytes - emptyJson.getBytes(StandardCharsets.UTF_8).length - 128;
        String json = OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
            "directories", java.util.Map.of("directory.recording", source.resolve("recordings").toString()),
            "preserved", java.util.Map.of("large.preference", "x".repeat(preservedLength))));
        assertEquals(maximumBytes - 128, json.getBytes(StandardCharsets.UTF_8).length);
        String relocatedJson = OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
            "directories", java.util.Map.of("directory.recording", target.resolve("recordings").toString()),
            "preserved", java.util.Map.of("large.preference", "x".repeat(preservedLength))));
        assertTrue(relocatedJson.getBytes(StandardCharsets.UTF_8).length > maximumBytes);
        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms)
            VALUES ('portable_java_preferences_v1', ?, 1)
            """))
        {
            statement.setString(1, json);
            statement.executeUpdate();
        }

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("RESET unusable portable preference components: 1 preference component(s)"),
            result::output);
        JsonNode settings = OBJECT_MAPPER.readTree(scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        assertFalse(settings.path("directories").has("directory.recording"));
        assertEquals(preservedLength, settings.path("preserved").path("large.preference").asText().length());
    }

    @Test
    void refusesIncompleteCurrentSchemaWithoutRepairingIt() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX activity.idx_trunked_radio_group_reverse");
        }

        CommandResult result = run(database);

        assertTrue(result.exitCode() != ApplicationDatabaseMigrator.EXIT_SUCCESS);
        assertTrue(result.error().contains("exact format-63 schema"), result::error);

        try(Connection connection = open(database))
        {
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM activity.sqlite_schema
                WHERE type='index' AND name='idx_trunked_radio_group_reverse'
                """));
        }
    }

    @Test
    void refusesIntermediateP25Version25WithoutChangingIt() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, "p25_activity_schema_version", "25");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("mixed or partially migrated"));
        assertEquals("25", metadata(database, "p25_activity_schema_version"));
        assertEquals("1", scalar(database, """
            SELECT COUNT(*) FROM sqlite_schema
            WHERE type='table' AND name='trunked_radio_affiliation'
            """));
    }

    @Test
    void malformedCurrentPortablePreferencesAreResetWithoutBlockingMigration() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkTestDatabase.create(database);
        Path source = mTemporaryFolder.resolve("source-data").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("target-data").toAbsolutePath();

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '{invalid', 1)
                """);
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains(
            completedCurrentStep("repair-portable-preferences")), result::output);
        assertTrue(result.output().contains(
            "RESET unusable portable preference components: 1 preference component(s)"), result::output);

        try(Connection connection = open(database))
        {
            assertEquals("{}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertNull(metadata(connection, "dmr_activity_schema_version"));
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    @Test
    void format2MalformedPortablePreferencesDoNotBlockTheReleaseMigration() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        Path source = mTemporaryFolder.resolve("format2-rollback-source").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("format2-rollback-target").toAbsolutePath();

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(100,1,'Retained With Malformed Preference','TALKGROUP','APCO25',43)");
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '{invalid', 1)
                """);
        }

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("RESET unusable legacy web/settings state: 1 row(s)"));

        try(Connection connection = open(database))
        {
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION,
                DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals("Retained With Malformed Preference", scalar(connection,
                "SELECT name FROM alias WHERE id=100"));
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM alias"));
            assertEquals("1", scalar(connection, """
                SELECT json_valid(settings_json) FROM application_settings
                WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM application_settings
                WHERE key='portable_java_preferences_v1' AND settings_json='{invalid'
                """));
        }
    }

    @Test
    void refusesAValidLiveDatabasePathWithoutOpeningItForMigration() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("live-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        byte[] before = Files.readAllBytes(database);

        CommandResult result = runArguments(database.toString());

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("accepts only an application-created staged database"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(database)));
    }

    @Test
    void refusesAStagedLookingSymbolicLinkToALiveDatabase() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("live-symlink-data");
        Path liveDatabase = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(liveDatabase);
        byte[] before = Files.readAllBytes(liveDatabase);
        Path stagedLink = newStagedDatabase();

        try
        {
            Files.createSymbolicLink(stagedLink, liveDatabase);
        }
        catch(UnsupportedOperationException | IllegalArgumentException | java.io.IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        CommandResult result = run(stagedLink);

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("must not be a symbolic link"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(liveDatabase)));
    }

    @Test
    void refusesAStagedLookingAncestorSymlinkToALiveDataRoot() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("live-ancestor-data");
        Path liveDatabase = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(liveDatabase);
        byte[] before = Files.readAllBytes(liveDatabase);
        Path deceptiveStageRoot = mTemporaryFolder.resolve(".live-ancestor-data.migration-" + UUID.randomUUID());

        try
        {
            Files.createSymbolicLink(deceptiveStageRoot, dataRoot);
        }
        catch(UnsupportedOperationException | java.io.IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        Path disguisedLiveDatabase = deceptiveStageRoot.resolve("database")
            .resolve(SdrTrunkDatabasePath.DATABASE_FILENAME);
        CommandResult result = run(disguisedLiveDatabase);

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("resolves outside an application stage"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(liveDatabase)));
    }

    @Test
    void refusesAStagedLookingHardLinkToALiveDatabaseWhenLinkCountsAreAvailable() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("live-hard-link-data");
        Path liveDatabase = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(liveDatabase);
        byte[] before = Files.readAllBytes(liveDatabase);
        Path stagedLink = newStagedDatabase();

        try
        {
            Files.createLink(stagedLink, liveDatabase);
            Object links = Files.getAttribute(stagedLink, "unix:nlink", java.nio.file.LinkOption.NOFOLLOW_LINKS);
            Assumptions.assumeTrue(links instanceof Number && ((Number)links).longValue() > 1,
                "Filesystem link counts are unavailable");
        }
        catch(UnsupportedOperationException | IllegalArgumentException | java.io.IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Hard links are unavailable: " + e.getMessage());
        }

        CommandResult result = run(stagedLink);

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("multiple filesystem links"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(liveDatabase)));
    }

    @Test
    void reportsUsageAndMissingInputWithStableExitCodes()
    {
        CommandResult help = runArguments("--help");
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, help.exitCode());
        assertTrue(help.output().contains("Usage:"));

        CommandResult missing = runArguments();
        assertEquals(ApplicationDatabaseMigrator.EXIT_USAGE, missing.exitCode());
        assertTrue(missing.error().contains("staged database path"));
    }

    @Test
    void reportsCorruptSqliteAsMigrationFailureInsteadOfUnsupportedFormat() throws Exception
    {
        Path database = newStagedDatabase();
        byte[] corrupt = "not a sqlite database".getBytes(StandardCharsets.UTF_8);
        Files.write(database, corrupt);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("Database migration failed"));
        assertTrue(java.util.Arrays.equals(corrupt, Files.readAllBytes(database)));
    }

    private Path newStagedDatabase() throws Exception
    {
        Path database = mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
        Files.createDirectories(database.getParent());
        return database;
    }

    private CommandResult run(Path database)
    {
        return runArguments(database.toString());
    }

    private CommandResult run(Path database, Path source, Path target)
    {
        return runArguments(database.toString(), source.toString(), target.toString());
    }

    private CommandResult runArguments(String... arguments)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode = ApplicationDatabaseMigrator.run(arguments, new PrintStream(output), new PrintStream(error));
        return new CommandResult(exitCode, output.toString(StandardCharsets.UTF_8),
            error.toString(StandardCharsets.UTF_8));
    }

    private static void insertFormat2Channel(Connection connection, int id, String name,
                                             DecoderType decoderType, long frequency) throws Exception
    {
        try(var insert = connection.prepareStatement("""
            INSERT INTO configuration_channel(id,sort_order,name,radres_guid,decoder_type,source_type,
                primary_frequency_hz,frequency_count,config_json)
            VALUES(?,?,?,?,?,'TUNER',?,1,?)
            """))
        {
            insert.setInt(1, id);
            insert.setInt(2, id);
            insert.setString(3, name);
            insert.setString(4, "00000000-0000-4000-8000-%012d".formatted(id));
            insert.setString(5, decoderType.name());
            insert.setLong(6, frequency);
            insert.setString(7, channelJson(name, "Alpha System", "Alpha Site", null, decoderType, frequency));
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static String channelJson(String name, String system, String site, String aliasListName,
                                      DecoderType decoderType, long frequency) throws Exception
    {
        Channel channel = new Channel(name);
        channel.setSystem(system);
        channel.setSite(site);
        channel.setAliasListName(aliasListName);
        channel.setDecodeConfiguration(decoderType == DecoderType.MPT1327 ? new DecodeConfigMPT1327() :
            DecoderFactory.getDecodeConfiguration(decoderType));
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(frequency);
        channel.setSourceConfiguration(source);
        return OBJECT_MAPPER.writeValueAsString(channel);
    }

    private static Connection open(Path database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        Path activity = ActivityDatabasePath.forConfiguration(database);
        if(java.nio.file.Files.isRegularFile(activity))
        {
            try(var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
            {
                attach.setString(1, activity.toString());
                attach.execute();
            }
        }
        return connection;
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

    private static String metadata(Path database, String key) throws Exception
    {
        try(Connection connection = open(database))
        {
            return metadata(connection, key);
        }
    }

    private static String scalar(Path database, String sql) throws Exception
    {
        try(Connection connection = open(database))
        {
            return scalar(connection, sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static String completedCurrentStep(String stepId)
    {
        return "COMPLETED STEP: " + DatabaseFormatCatalog.CURRENT_VERSION + " -> " +
            DatabaseFormatCatalog.CURRENT_VERSION + " [" + stepId + "]";
    }

    private record CommandResult(int exitCode, String output, String error)
    {
    }
}
