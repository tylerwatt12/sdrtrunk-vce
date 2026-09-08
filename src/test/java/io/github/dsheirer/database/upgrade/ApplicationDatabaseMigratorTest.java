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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.InitialAdminSetup;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.mpt1327.DecodeConfigMPT1327;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationDatabaseMigratorTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void validatesExactCurrentStagedDatabaseWithoutChangingSchema() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

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
    void repairsWrongShapePortablePreferencesInCurrentStagedDatabase() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
            "COMPLETED STEP: 15 -> 15 [repair-portable-preferences]"), result::output);
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
    void repairsPreferencesBeforeAdoptingAMissingCurrentMarker() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '[]', 1)
                """);
            statement.executeUpdate("""
                UPDATE application_settings SET settings_json='{}' WHERE key='setup_wizard'
                """);
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='database_format_version'");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        int completedRepair = result.output().indexOf(
            "COMPLETED STEP: 15 -> 15 [repair-portable-preferences]");
        int completedAdministrative = result.output().indexOf(
            "COMPLETED STEP: 15 -> 15 [repair-current-administrative-state]");
        int completedAdoption = result.output().indexOf(
            "COMPLETED STEP: 15 -> 15 [adopt-global-format-marker]");
        assertTrue(completedRepair >= 0 && completedAdministrative > completedRepair &&
            completedAdoption > completedAdministrative, result::output);
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION),
            metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        assertEquals("{}", scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
    }

    @Test
    void repairsDamagedCurrentAdministrativeComponentsIndependently() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
                UPDATE database_metadata SET value='0'
                WHERE key='conventional_call_output_metrics_started_at_ms'
                """);
            statement.executeUpdate("""
                DELETE FROM database_metadata WHERE key='trunked_logical_call_metrics_started_at_ms'
                """);
            statement.executeUpdate("""
                UPDATE database_metadata SET value='not-a-time'
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
            "COMPLETED STEP: 15 -> 15 [repair-current-administrative-state]"), result::output);
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
        assertEquals("6", scalar(database, """
            SELECT json_extract(preferences_json, '$.version') FROM web_user WHERE username='admin'
            """));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM web_access_policy"));
    }

    @Test
    void resetsOnlyTheWebAccessComponentWhenThePrimaryCredentialIsUnusable() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
            "COMPLETED STEP: 15 -> 15 [repair-current-configuration-relationships]"), result::output);
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
    void resetsDamagedCurrentDerivedStateWithoutDiscardingAdministratorAliases() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
            "COMPLETED STEP: 15 -> 15 [reset-damaged-current-derived-state]"), result::output);
        assertTrue(result.output().contains(
            "RESET bounded receiver activity and statistics rows: 2 row(s)"), result::output);
        assertTrue(result.output().contains(
            "DEFAULT receiver metric collection boundaries: 3 row(s)"), result::output);
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM alias WHERE id=18400"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM receiver_channel"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM statistics_status"));
        assertEquals("3", scalar(database, """
            SELECT COUNT(*) FROM database_metadata
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
    void skipsMalformedCurrentChannelsAndProvidersWithoutLosingUsableConfiguration() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
                    id, configuration_id, channel_kind, sort_order, name, auto_start, decoder_type,
                    address_domain_code, config_json
                ) VALUES (17502, '%s', 'TRUNKED', 0, 'Broken saved channel', 0, 'P25_PHASE1', 0, '{}')
                """.formatted(invalidChannelId));
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
            "COMPLETED STEP: 15 -> 15 [reset-damaged-current-derived-state]");
        int completedConfiguration = result.output().indexOf(
            "COMPLETED STEP: 15 -> 15 [repair-current-configuration-relationships]");
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
    void keepsCurrentChannelButClearsAnIncompatibleAliasListAssignment() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
        assertEquals("1", scalar(database, "SELECT alias_list_id IS NULL FROM configuration_channel WHERE " +
            "configuration_id='" + configurationId + "'"));
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
    void adoptsMarkerForExactMarkerlessCurrentLayout() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database); var statement = connection.prepareStatement(
            "DELETE FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            assertEquals(1, statement.executeUpdate());
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("COMPLETED STEP: " + DatabaseFormatCatalog.CURRENT_VERSION + " -> " +
            DatabaseFormatCatalog.CURRENT_VERSION + " [adopt-global-format-marker]"), result.output());
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION),
            metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
    }

    @Test
    void repairsRetiredMetadataBeforeAdoptingAMissingCurrentMarker() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

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

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("DROP malformed non-structural metadata: 1 row(s)"), result::output);
        assertTrue(result.output().contains("COMPLETED STEP: " + DatabaseFormatCatalog.CURRENT_VERSION + " -> " +
            DatabaseFormatCatalog.CURRENT_VERSION + " [adopt-global-format-marker]"), result::output);
        assertNull(metadata(database, "alias_schema_version"));
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION),
            metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
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
            result.output().indexOf("COMPLETED STEP: 15 -> 15 [repair-portable-preferences]"), result::output);
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

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
        Path database = Format1TestDatabase.create(newStagedDatabase());
        char[] adminPassword = "format-one-admin-secret".toCharArray();
        char[] listenerPassword = "format-one-listener-secret".toCharArray();
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
    void migratesExactPublishedAlpha8Alpha9Alpha10LayoutThroughEveryAdjacentStep() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());
        insertAlpha9MigrationCases(database);
        Path sourceRoot = mTemporaryFolder.resolve("alpha9-source").toAbsolutePath();
        Path targetRoot = mTemporaryFolder.resolve("alpha10-target").toAbsolutePath();

        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms)
            VALUES ('portable_java_preferences_v1', ?, 1)
            """))
        {
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(java.util.Map.of("directories",
                java.util.Map.of("directory.recording", sourceRoot.resolve("recordings").toString()))));
            statement.executeUpdate();
        }

        CommandResult result = run(database, sourceRoot, targetRoot);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("COMPLETED STEP: 1 -> 2 [format-1-to-2]"));
        assertTrue(result.output().contains("TRANSFORM unmatched-talkgroup catch-all aliases: 4 row(s)"));
        assertTrue(result.output().contains("DROP retired fully-qualified talkgroup aliases: 1 row(s)"));
        assertTrue(result.output().contains("DROP retired fully-qualified radio aliases: 1 row(s)"));
        assertTrue(result.output().contains(
            "DROP broadcast routes attached to retired fully-qualified aliases: 2 row(s)"));
        assertTrue(result.output().contains("RESET P25 affiliation history: 3 row(s)"));
        assertTrue(result.output().contains("live P25 traffic rebuilds current state"));
        assertTrue(result.output().contains("COMPLETED STEP: 2 -> 3 [format-2-to-3]"));
        assertTrue(result.output().contains("COMPLETED STEP: 3 -> 4 [format-3-to-4]"));
        assertTrue(result.output().contains("COMPLETED STEP: 4 -> 5 [format-4-to-5]"));
        assertTrue(result.output().contains("COMPLETED STEP: 5 -> 6 [format-5-to-6]"));
        assertTrue(result.output().contains("COMPLETED STEP: 6 -> 7 [format-6-to-7]"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            assertNull(metadata(connection, "alias_schema_version"));
            assertNull(metadata(connection, "p25_activity_schema_version"));
            assertEquals("Default:1:1", scalar(connection, """
                SELECT name || ':' || published || ':' || is_default
                FROM scan_list
                """));
            assertEquals("1:1|2:0|5:1|6:0|7:1", scalar(connection, """
                SELECT group_concat(policy, '|')
                FROM (
                    SELECT id || ':' || unmatched_talkgroup_record_enabled AS policy
                    FROM alias_list
                    WHERE id IN (1, 2, 5, 6, 7)
                    ORDER BY id
                )
                """));
            assertEquals("Default P25:P25|Default DMR:DMR|Default NXDN:NXDN|Default Analog:NBFM",
                scalar(connection, """
                    SELECT group_concat(value, '|')
                    FROM (
                        SELECT name || ':' || family AS value
                        FROM alias_list
                        WHERE name IN ('Default P25', 'Default DMR', 'Default NXDN', 'Default Analog')
                        ORDER BY id
                    )
                    """));
            assertEquals("103|104|105|106|107|111|112|113", scalar(connection, """
                SELECT group_concat(alias_id, '|')
                FROM (
                    SELECT alias_id FROM alias_scan_list_membership ORDER BY alias_id
                )
                """));
            assertEquals("5|6|7|12|13|14", scalar(connection, """
                SELECT group_concat(alias_list_id, '|')
                FROM (
                    SELECT alias_list_id
                    FROM alias_list_unmatched_talkgroup_scan_list_membership
                    ORDER BY alias_list_id
                )
                """));
            assertEquals("Safe Stream", scalar(connection, """
                SELECT json_extract(provider.config_json, '$.name')
                FROM alias_list_unmatched_talkgroup_stream route
                JOIN configuration_broadcast_stream provider
                  ON provider.configuration_id=route.broadcast_configuration_id
                WHERE route.alias_list_id=1
                """));
            assertEquals("5:Phase 2 Stream|6:DMR Stream|7:NXDN Stream", scalar(connection, """
                SELECT group_concat(route, '|')
                FROM (
                    SELECT unmatched.alias_list_id || ':' ||
                           json_extract(provider.config_json, '$.name') AS route
                    FROM alias_list_unmatched_talkgroup_stream unmatched
                    JOIN configuration_broadcast_stream provider
                      ON provider.configuration_id=unmatched.broadcast_configuration_id
                    WHERE unmatched.alias_list_id IN (5, 6, 7)
                    ORDER BY unmatched.alias_list_id
                )
                """));
            assertEquals("9", scalar(connection, "SELECT COUNT(*) FROM alias"));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM alias
                WHERE matcher_type IN (
                    'P25_FULLY_QUALIFIED_TALKGROUP', 'P25_FULLY_QUALIFIED_RADIO_ID'
                )
                """));
            assertEquals("Keep this appearance", scalar(connection,
                "SELECT description FROM alias WHERE id=104"));
            assertEquals("Keep Group|7|Keep Icon", scalar(connection, """
                SELECT (SELECT group_name FROM alias WHERE id=111) || '|' ||
                       (SELECT color FROM alias WHERE id=112) || '|' ||
                       (SELECT icon_name FROM alias WHERE id=113)
                """));
            assertEquals("3", scalar(connection, """
                SELECT COUNT(*) FROM alias WHERE id IN (105, 106, 107)
                """));
            assertEquals("1", scalar(connection, """
                SELECT COUNT(*) FROM alias_broadcast_channel route
                JOIN configuration_broadcast_stream provider
                  ON provider.configuration_id=route.broadcast_configuration_id
                WHERE route.id=203 AND route.alias_id=103
                  AND json_extract(provider.config_json, '$.name')='Retained Stream'
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM alias_broadcast_channel
                WHERE id IN (201, 202)
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM sqlite_schema WHERE name IN (
                    'trunked_identity_scope', 'trunked_identity_scope_context',
                    'trunked_identity_summary', 'trunked_radio_talkgroup_summary',
                    'trunked_radio_presence_lifecycle',
                    'p25_zero_local_fq_talkgroup_summary',
                    'receiver_context', 'p25_control_channel_quality'
                )
                """));
            assertEquals("0", scalar(connection, """
                SELECT
                    (SELECT COUNT(*) FROM radio_system) +
                    (SELECT COUNT(*) FROM receiver_channel) +
                    (SELECT COUNT(*) FROM radio_system_identity_summary) +
                    (SELECT COUNT(*) FROM trunked_radio_group_summary) +
                    (SELECT COUNT(*) FROM trunked_radio_affiliation) +
                    (SELECT COUNT(*) FROM trunked_radio_channel_presence) +
                    (SELECT COUNT(*) FROM trunked_radio_channel_presence_clear) +
                    (SELECT COUNT(*) FROM p25_site_snapshot) +
                    (SELECT COUNT(*) FROM trunked_control_channel_quality)
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM sqlite_schema
                WHERE name IN ('p25_radio_affiliation', 'idx_p25_radio_affiliation_talkgroup')
                """));
            assertNull(metadata(connection, "trunked_identity_metrics_started_at_ms"));
            assertTrue(Long.parseLong(metadata(connection, "radio_system_metrics_started_at_ms")) > 0);
            assertEquals("Preserved Channel", scalar(connection,
                "SELECT name FROM configuration_channel WHERE id=77"));
            assertEquals("78:Default P25|79:Default P25|80:Default P25|81:Default DMR|" +
                "82:Default NXDN|83:Default Analog|84:Default Analog", scalar(connection, """
                SELECT group_concat(value, '|')
                FROM (
                    SELECT channel.id || ':' || COALESCE(list.name, 'NULL') AS value
                    FROM configuration_channel channel
                    LEFT JOIN alias_list list ON list.id=channel.alias_list_id
                    WHERE channel.id BETWEEN 78 AND 85
                    ORDER BY channel.id
                )
                """));
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE json_type(config_json, '$.aliasListName') IS NOT NULL
                """));
            assertEquals("0", scalar(connection,
                "SELECT COUNT(*) FROM configuration_channel WHERE id=85"));
            assertEquals("{\"preserved\":true}", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='migration-sentinel'
                """));
            JsonNode preferences = OBJECT_MAPPER.readTree(scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(targetRoot.resolve("recordings").toString(),
                preferences.path("directories").path("directory.recording").asText());
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));

            long insertedId;
            try(ResultSet resultSet = statement.executeQuery("""
                INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
                VALUES (1, 'Sequence Check', 'TALKGROUP', 'APCO25', 99)
                RETURNING id
                """))
            {
                assertTrue(resultSet.next());
                insertedId = resultSet.getLong(1);
            }
            assertTrue(insertedId > 900, "Retired high-water alias IDs must not be reused");

            long insertedSystemId;
            try(ResultSet resultSet = statement.executeQuery("""
                INSERT INTO radio_system(
                    system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms
                ) VALUES ('p25:bee00:348', 1, 0, 781824, 840, 10000, 10000)
                RETURNING id
                """))
            {
                assertTrue(resultSet.next());
                insertedSystemId = resultSet.getLong(1);
            }
            assertTrue(insertedSystemId > 0, "A new radio system must be writable after the reset");
        }

        Path exactCurrent = mTemporaryFolder.resolve("exact-current.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(exactCurrent);
        try(Connection migrated = open(database); Connection current = open(exactCurrent))
        {
            assertEquals(SqliteSchemaValidator.fingerprint(current), SqliteSchemaValidator.fingerprint(migrated),
                "The complete Alpha 8/Alpha 9/Alpha 10 chain must produce the exact current schema, not a " +
                    "compatibility layout");
        }
    }

    @Test
    void refusesAlpha9VersionStampsOnTheWrongSchema() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE unexpected_alpha9_object(id INTEGER PRIMARY KEY)");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("Unrecognized SQLite database schema fingerprint"));
        try(Connection connection = open(database))
        {
            assertEquals("4", metadata(connection, "alias_schema_version"));
            assertEquals("24", metadata(connection, "p25_activity_schema_version"));
        }
    }

    @Test
    void alpha9MigrationPreservesWrongFamilyFactoryNameCollisionUnderAUniqueName() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family)
                VALUES (1, 'Default P25', 'DMR')
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
    void dropsLegacyP25QualifiersWhileRetainingTheAlias() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id, name, family) VALUES (1, 'Qualified', 'P25')");
            statement.executeUpdate("""
                INSERT INTO alias(
                    id, alias_list_id, name, matcher_type, protocol, value, wacn, p25_system_id
                ) VALUES (1, 1, 'Unexpected Qualifier', 'TALKGROUP', 'APCO25', 43, 781824, 840)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("DROP legacy P25 qualifier values: 1 row(s)"));
        assertEquals("1", scalar(database, """
            SELECT COUNT(*) FROM alias
            WHERE id=1 AND name='Unexpected Qualifier' AND matcher_type='TALKGROUP'
              AND protocol='APCO25' AND value=43
            """));
    }

    @Test
    void alpha9MigrationAssignsDefaultsToBlankAliasListNames() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());
        insertAlpha9MigrationCases(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
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
    void resetsAlpha9AffiliationHistoryWithoutRequiringIdentityScopes() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (70, 781824, 840, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (70, 1800001, 43, 2000)
                """);
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("RESET P25 affiliation history: 1 row(s)"));
        assertTrue(result.error().isEmpty());
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM radio_system"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM trunked_radio_affiliation"));
        assertEquals("0", scalar(database, """
            SELECT COUNT(*) FROM sqlite_schema
            WHERE name IN (
                'p25_system', 'p25_radio_affiliation', 'idx_p25_radio_affiliation_talkgroup',
                'trunked_identity_scope', 'trunked_identity_summary'
            )
            """));
        assertEquals("ok", scalar(database, "PRAGMA quick_check"));
    }

    @Test
    void rebasesPortableDirectoriesWithoutChangingSchema() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
                    "directory.application.logs", source.resolve("logs").toString(),
                    "path.jmbe.library.primary", source.resolve("jmbe/jmbe.jar").toString(),
                    "path.voice.decryption.module.primary", source.resolve("modules/voice.jar").toString(),
                    "path.jmbe.library.external", external.toString(),
                    "path.voice.decryption.module.relative", "modules/relative.jar",
                    "directory.streaming", similarlyNamedSibling.toString(),
                    "unrecognized.absolute.path", unrecognizedInsideSource.toString()))));
            statement.executeUpdate();
        }

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("TRANSFORM portable directory preferences: 4 preference component(s)"));

        try(Connection connection = open(database))
        {
            JsonNode settings = OBJECT_MAPPER.readTree(scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(target.resolve("recordings").toString(),
                settings.path("directories").path("directory.recording").asText());
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
    void dropsOnlyAPathWhoseLongerRelocationWouldOverflowPortablePreferences() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_trunked_radio_group_reverse");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("Unrecognized SQLite database schema fingerprint"), result::error);

        try(Connection connection = open(database))
        {
            assertEquals("0", scalar(connection, """
                SELECT COUNT(*) FROM sqlite_schema
                WHERE type='index' AND name='idx_trunked_radio_group_reverse'
                """));
        }
    }

    @Test
    void refusesIntermediateP25Version25WithoutChangingIt() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());

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
            WHERE type='table' AND name='p25_radio_affiliation'
            """));
    }

    @Test
    void malformedCurrentPortablePreferencesAreResetWithoutBlockingMigration() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
            "COMPLETED STEP: 15 -> 15 [repair-portable-preferences]"), result::output);
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
    void alpha9MalformedPortablePreferencesDoNotBlockTheReleaseMigration() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());
        insertAlpha9MigrationCases(database);
        Path source = mTemporaryFolder.resolve("alpha9-rollback-source").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("alpha9-rollback-target").toAbsolutePath();

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
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
            assertEquals("9", scalar(connection, "SELECT COUNT(*) FROM alias"));
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(liveDatabase);
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(liveDatabase);
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(liveDatabase);
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

    private static void insertAlpha9MigrationCases(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family) VALUES
                    (1, 'Safe', 'P25'),
                    (2, 'Styled', 'P25'),
                    (3, 'Ambiguous', 'P25'),
                    (4, 'Stream As', 'P25'),
                    (5, 'Phase 2', 'P25'),
                    (6, 'DMR', 'DMR'),
                    (7, 'NXDN', 'NXDN'),
                    (8, 'Group Styled', 'P25'),
                    (9, 'Color Styled', 'P25'),
                    (10, 'Icon Styled', 'P25'),
                    (11, 'Default P25', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(
                    id, alias_list_id, name, description, color, record_enabled, priority,
                    matcher_type, protocol, value, min_value, max_value, wacn, p25_system_id
                ) VALUES
                    (100, 1, 'Safe Catchall', NULL, 0, 1, -1,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (800, 1, 'Retired FQ Talkgroup', NULL, 0, 0, 10,
                        'P25_FULLY_QUALIFIED_TALKGROUP', 'APCO25', 42, NULL, NULL, 781824, 840),
                    (900, 1, 'Retired FQ Radio', NULL, 0, 0, 10,
                        'P25_FULLY_QUALIFIED_RADIO_ID', 'APCO25', 1800001, NULL, NULL, 781824, 840),
                    (103, 1, 'Retained Talkgroup', NULL, 0, 1, 5,
                        'TALKGROUP', 'APCO25', 43, NULL, NULL, NULL, NULL),
                    (114, 2, 'Do Not Listen', NULL, 0, 0, -1,
                        'TALKGROUP', 'APCO25', 44, NULL, NULL, NULL, NULL),
                    (104, 2, 'Styled Catchall', 'Keep this appearance', 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (105, 3, 'Ambiguous A', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (106, 3, 'Ambiguous B', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25_PHASE2', NULL, 1, 65535, NULL, NULL),
                    (107, 4, 'Stream As Catchall', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (108, 5, 'Phase 2 Catchall', NULL, 0, 1, 4,
                        'TALKGROUP_RANGE', 'APCO25_PHASE2', NULL, 1, 65535, NULL, NULL),
                    (109, 6, 'DMR Catchall', NULL, 0, 0, 6,
                        'TALKGROUP_RANGE', 'DMR', NULL, 1, 16777215, NULL, NULL),
                    (110, 7, 'NXDN Catchall', NULL, 0, 1, 9,
                        'TALKGROUP_RANGE', 'NXDN', NULL, 1, 65535, NULL, NULL),
                    (111, 8, 'Group Catchall', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (112, 9, 'Color Catchall', NULL, 7, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL),
                    (113, 10, 'Icon Catchall', NULL, 0, 1, 8,
                        'TALKGROUP_RANGE', 'APCO25', NULL, 0, 65535, NULL, NULL)
                """);
            statement.executeUpdate("UPDATE alias SET stream_as_talkgroup=999 WHERE id=107");
            statement.executeUpdate("UPDATE alias SET group_name='Keep Group' WHERE id=111");
            statement.executeUpdate("UPDATE alias SET icon_name='Keep Icon' WHERE id=113");
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(id, alias_id, channel_name) VALUES
                    (200, 100, 'Safe Stream'),
                    (201, 800, 'Retired Talkgroup Stream'),
                    (202, 900, 'Retired Radio Stream'),
                    (203, 103, 'Retained Stream'),
                    (204, 108, 'Phase 2 Stream'),
                    (205, 109, 'DMR Stream'),
                    (206, 110, 'NXDN Stream')
                """);
            insertBroadcastProvider(connection, 0, "Safe Stream", 7200);
            insertBroadcastProvider(connection, 1, "Retained Stream", 7201);
            insertBroadcastProvider(connection, 2, "Phase 2 Stream", 7202);
            insertBroadcastProvider(connection, 3, "DMR Stream", 7203);
            insertBroadcastProvider(connection, 4, "NXDN Stream", 7204);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    id, sort_order, system_name, site_name, name, alias_list_name,
                    decoder_type, source_type, primary_frequency_hz, frequency_count, config_json
                ) VALUES
                    (77, 1, 'Preserved System', 'Preserved Site', 'Preserved Channel', 'Safe',
                        'P25_PHASE1', 'TUNER', 851012500, 1, '{"preserved":true}'),
                    (78, 2, 'P25', 'Phase 1', 'P25 Phase 1', NULL,
                        'P25_PHASE1', 'TUNER', 851012500, 1, '{}'),
                    (79, 3, 'P25', 'Phase 2', 'P25 Phase 2', NULL,
                        'P25_PHASE2', 'TUNER', 851012500, 1, '{}'),
                    (80, 4, 'P25', 'Conventional', 'P25 Conventional', NULL,
                        'P25_CONVENTIONAL', 'TUNER', 155000000, 1, '{}'),
                    (81, 5, 'DMR', 'Trunked', 'DMR', NULL,
                        'DMR', 'TUNER', 451000000, 1, '{}'),
                    (82, 6, 'NXDN', 'Trunked', 'NXDN', NULL,
                        'NXDN', 'TUNER', 452000000, 1, '{}'),
                    (83, 7, 'Analog', 'NBFM', 'NBFM', NULL,
                        'NBFM', 'TUNER', 453000000, 1, '{}'),
                    (84, 8, 'Analog', 'AM', 'AM', NULL,
                        'AM', 'TUNER', 118500000, 1, '{}'),
                    (85, 9, 'Retired', 'MPT', 'MPT', NULL,
                        'MPT1327', 'TUNER', 454000000, 1, '{}')
                """);
            updateChannelJson(connection, 77, "Preserved Channel", "Preserved System", "Preserved Site", "Safe",
                DecoderType.P25_PHASE1, 851012500);
            updateChannelJson(connection, 78, "P25 Phase 1", "P25", "Phase 1", null,
                DecoderType.P25_PHASE1, 851012500);
            updateChannelJson(connection, 79, "P25 Phase 2", "P25", "Phase 2", null,
                DecoderType.P25_PHASE2, 851012500);
            updateChannelJson(connection, 80, "P25 Conventional", "P25", "Conventional", null,
                DecoderType.P25_CONVENTIONAL, 155000000);
            updateChannelJson(connection, 81, "DMR", "DMR", "Trunked", null,
                DecoderType.DMR, 451000000);
            updateChannelJson(connection, 82, "NXDN", "NXDN", "Trunked", null,
                DecoderType.NXDN, 452000000);
            updateChannelJson(connection, 83, "NBFM", "Analog", "NBFM", null,
                DecoderType.NBFM, 453000000);
            updateChannelJson(connection, 84, "AM", "Analog", "AM", null,
                DecoderType.AM, 118500000);
            updateChannelJson(connection, 85, "MPT", "Retired", "MPT", null,
                DecoderType.MPT1327, 454000000);
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET radres_guid = CASE id
                    WHEN 77 THEN '00000000-0000-4000-8000-000000000077'
                    WHEN 78 THEN '00000000-0000-4000-8000-000000000078'
                    WHEN 79 THEN '00000000-0000-4000-8000-000000000079'
                    WHEN 82 THEN '00000000-0000-4000-8000-000000000082'
                    WHEN 85 THEN '00000000-0000-4000-8000-000000000085'
                END
                WHERE id IN (77, 78, 79, 82, 85)
                """);
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('migration-sentinel', '{"preserved":true}', 1000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality(
                    guid, frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs,
                    decode_health_pct, valid_frames, invalid_frames, corrected_bits,
                    sync_loss_bits, dropped_bits, last_valid_decode_ms
                ) VALUES (
                    'preserved-quality', 851012500, 1000, 2000, -72.5,
                    92.5, 100, 3, 4, 5, 6, 1900
                )
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot(
                    guid, first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('preserved-site', 1000, 2000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel(
                    guid, channel_key, descriptor, callsign, confirmed_at_ms
                ) VALUES ('preserved-site', '12-345', '12-345', 'PRESERVED', 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary(
                    guid, channel_key, descriptor, first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('preserved-site', '12-345', '12-345', 1000, 2000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (50, 781824, 840, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name,
                    decoder, first_seen_ms, last_seen_ms, system_key, nac, rfss, site,
                    primary_frequency_hz, current_control_hz
                ) VALUES (
                    50, 'preserved-context', 'preserved-guid', 1, 1, 'Preserved Channel', 'Safe',
                    'P25-1', 1000, 9000, 50, 801, 1, 2, 851012500, 851012500
                )
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope(
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES (50, 'p25:BEE00:348', 1, 1, 0, 50, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context(context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES (50, 50, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                    first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES
                    (900, 'preserved-dmr', 'preserved-dmr-guid', 1, 3, 'Preserved DMR', 'DMR',
                        1000, 9000, 451000000),
                    (901, 'preserved-nxdn', 'preserved-nxdn-guid', 1, 4, 'Preserved NXDN', 'NXDN',
                        1000, 9000, 452000000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope(
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    p25_system_key, first_seen_ms, last_seen_ms
                ) VALUES
                    (900, 'context:dmr:preserved', 3, 2, 0, NULL, 1000, 9000),
                    (901, 'context:nxdn:preserved', 4, 2, 1, NULL, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context(context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES (900, 900, 1000, 9000), (901, 901, 1000, 9000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary(
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, call_count
                ) VALUES
                    (50, 1, 43, 1000, 9000, 7),
                    (50, 1, 999, 1000, 9000, 2),
                    (900, 1, 321, 1000, 9000, 4),
                    (900, 2, 765432, 1000, 9000, 4),
                    (901, 1, 432, 1000, 9000, 3),
                    (901, 2, 1234, 1000, 9000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_talkgroup_summary(
                    scope_id, radio_id, talkgroup_id, target_kind_code,
                    first_seen_ms, last_seen_ms, call_count
                ) VALUES
                    (50, 1800001, 43, 1, 1000, 9000, 4),
                    (900, 765432, 321, 1, 1000, 9000, 4),
                    (901, 1234, 432, 1, 1000, 9000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_radio_affiliation(system_key, radio_id, talkgroup_id, updated_at_ms)
                VALUES (50, 1800001, 43, 8000),
                       (50, 1800002, 44, 8500),
                       (50, 1800003, 43, 9000)
                """);
            statement.executeUpdate("""
                UPDATE database_metadata SET value='1234'
                WHERE key='trunked_identity_metrics_started_at_ms'
                """);
        }
    }

    private static void updateChannelJson(Connection connection, int id, String name, String system, String site,
                                          String aliasListName, DecoderType decoderType, long frequency)
        throws Exception
    {
        try(var statement = connection.prepareStatement(
            "UPDATE configuration_channel SET config_json=? WHERE id=?"))
        {
            statement.setString(1, channelJson(name, system, site, aliasListName, decoderType, frequency));
            statement.setInt(2, id);
            assertEquals(1, statement.executeUpdate());
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

    private static void insertBroadcastProvider(Connection connection, int sortOrder, String name, int systemId)
        throws Exception
    {
        BroadcastConfiguration configuration = new RdioScannerConfiguration();
        configuration.setName(name);
        configuration.setHost("http://127.0.0.1");
        configuration.setPort(3200 + sortOrder);
        configuration.setEnabled(true);
        ((RdioScannerConfiguration)configuration).setApiKey("format-chain-fixture-" + sortOrder);
        ((RdioScannerConfiguration)configuration).setSystemID(systemId);
        ObjectNode payload = OBJECT_MAPPER.valueToTree(configuration);
        payload.remove("configurationId");

        try(var insert = connection.prepareStatement("""
            INSERT INTO configuration_broadcast_stream (
                sort_order, name, server_type, enabled, host, port, delay_ms,
                maximum_recording_age_ms, config_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            insert.setInt(1, sortOrder);
            insert.setString(2, name);
            insert.setString(3, configuration.getBroadcastServerType().name());
            insert.setInt(4, 1);
            insert.setString(5, configuration.getHost());
            insert.setInt(6, configuration.getPort());
            insert.setLong(7, configuration.getDelay());
            insert.setLong(8, configuration.getMaximumRecordingAge());
            insert.setString(9, OBJECT_MAPPER.writeValueAsString(payload));
            insert.executeUpdate();
        }
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
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

    private record CommandResult(int exitCode, String output, String error)
    {
    }
}
