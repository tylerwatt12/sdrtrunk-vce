/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.InitialAdminSetup;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.gui.setup.SetupProgress;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.settings.SpectrumSnapSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format14To15DatabaseMigrationTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MIXED_CASE_CHANNEL = "11111111-2222-4333-8444-555555555555";
    private static final String CONVENTIONAL_CHANNEL = "66666666-7777-4888-8999-aaaaaaaaaaaa";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void replacesDuplicateAndNameBasedStateWithStableRelationships() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("format14.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_broadcast_stream
                SET config_json=json_set(config_json, '$.aliasListName', 'Stale display name')
                WHERE name='Primary Migration Feed'
                """);
            List<DatabaseMigrationEffect> preflight = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(6, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "receiver activity and call history").affectedRows());
            assertEquals(1, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "learned site observations").affectedRows());
            assertEquals(1, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "signal-quality observations").affectedRows());
            assertEquals(4, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "radio-system and receiver-channel identity cache").affectedRows());
            assertEquals(3, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "activity metric boundaries").affectedRows());
            assertEquals(1, effect(preflight, DatabaseMigrationEffect.Kind.DROP,
                "retired named Channel Maps").affectedRows());
            assertEquals(3, effect(preflight, DatabaseMigrationEffect.Kind.TRANSFORM,
                "web access policy names").affectedRows());
            assertEquals(1, effect(preflight, DatabaseMigrationEffect.Kind.TRANSFORM,
                "receiver-settings revision").affectedRows());
            Map<Long,Preference> beforePreferences = preferences(connection);
            Map<Long,Credential> beforeCredentials = credentials(connection);
            Map<String,Policy> beforePolicies = policies(connection);
            Map<String,ApplicationSetting> beforeApplicationSettings = applicationSettings(connection);
            assertFalse(beforePreferences.isEmpty());
            assertTrue(beforePolicies.containsKey("site-access"));
            String preservedSentinel = scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='format-4-preserve-sentinel'
                """);
            String radioResolve = scalar(connection, """
                SELECT radres_guid FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL));
            long conventionalAliasListId = number(connection, """
                SELECT list.id FROM configuration_channel channel
                JOIN alias_list list ON list.name=channel.alias_list_name COLLATE NOCASE
                WHERE channel.configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL));

            connection.setAutoCommit(false);
            try
            {
                DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
                assertEquals(15, report.target().version());
                assertEquals("format-14-to-15", report.steps().getLast().id());
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

            assertEquals(Set.of("id", "configuration_id", "channel_kind", "sort_order", "system_name",
                "site_name", "name", "alias_list_id", "radioresolve_id", "auto_start", "auto_start_order",
                "decoder_type", "address_domain_code", "primary_frequency_hz", "config_json"), columns(connection,
                "configuration_channel"));
            assertEquals("Migration Mixed Case", scalar(connection, """
                SELECT list.name FROM configuration_channel channel
                JOIN alias_list list ON list.id=channel.alias_list_id
                WHERE channel.configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals(conventionalAliasListId, number(connection, """
                SELECT alias_list_id FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL)));
            assertEquals(radioResolve, scalar(connection, """
                SELECT radioresolve_id FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertTrue(columns(connection, "alias_talkgroup").contains("alias_list_id"));
            assertFalse(columns(connection, "alias_talkgroup").contains("alias_list_name"));
            assertTrue(columns(connection, "alias_radio").contains("alias_list_id"));
            assertFalse(columns(connection, "alias_radio").contains("alias_list_name"));
            assertEquals(0, number(connection, """
                SELECT COUNT(*) FROM sqlite_master
                WHERE name IN ('configuration_channel_map', 'idx_configuration_channel_map_sort')
                """));

            for(String payload: strings(connection, "SELECT config_json FROM configuration_channel ORDER BY id"))
            {
                JsonNode json = MAPPER.readTree(payload);
                for(String removed: List.of("configurationId", "system", "site", "name", "aliasListName", "aliasListId",
                    "radioResolveId", "radresGuid", "radres_guid", "autoStart", "enabled", "autoStartOrder",
                    "order", "channelType"))
                {
                    assertFalse(json.has(removed), removed);
                }
            }

            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals(2, number(connection, """
                SELECT COUNT(*) FROM configuration_broadcast_stream
                WHERE length(configuration_id)=36 AND configuration_id=lower(configuration_id)
                """));
            assertEquals(0, number(connection, """
                SELECT COUNT(*) FROM configuration_broadcast_stream
                WHERE json_type(config_json, '$.configurationId') IS NOT NULL
                """));
            assertEquals(0, number(connection, """
                SELECT COUNT(*) FROM configuration_broadcast_stream
                WHERE json_type(config_json, '$.aliasListName') IS NOT NULL
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                UPDATE configuration_broadcast_stream
                SET config_json=json_set(config_json, '$.aliasListName', 'Duplicate display name')
                WHERE json_extract(config_json, '$.name')='Primary Migration Feed'
                """));
            assertEquals(1, number(connection, """
                SELECT COUNT(*) FROM alias_broadcast_channel route
                JOIN configuration_broadcast_stream stream
                  ON stream.configuration_id=route.broadcast_configuration_id
                WHERE json_extract(stream.config_json, '$.name')='Primary Migration Feed'
                """));
            assertEquals(1, number(connection, """
                SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream route
                JOIN configuration_broadcast_stream stream
                  ON stream.configuration_id=route.broadcast_configuration_id
                WHERE json_extract(stream.config_json, '$.name')='Secondary Migration Feed'
                """));

            String routedProviderId = scalar(connection,
                "SELECT broadcast_configuration_id FROM alias_broadcast_channel");
            execute(connection, """
                UPDATE configuration_broadcast_stream
                SET config_json=json_set(config_json, '$.name', 'Renamed After Migration')
                WHERE configuration_id='%s'
                """.formatted(routedProviderId));
            assertEquals(routedProviderId, scalar(connection,
                "SELECT broadcast_configuration_id FROM alias_broadcast_channel"));
            assertEquals("Renamed After Migration", scalar(connection, """
                SELECT json_extract(stream.config_json, '$.name')
                FROM alias_broadcast_channel route
                JOIN configuration_broadcast_stream stream
                  ON stream.configuration_id=route.broadcast_configuration_id
                """));

            Map<Long,Preference> afterPreferences = preferences(connection);
            assertEquals(beforeCredentials, credentials(connection));
            assertPreferencesMigrated(beforePreferences, afterPreferences);
            assertEquals(expectedPolicies(beforePolicies), policies(connection));
            assertEquals(beforePolicies.get("site-access"), policies(connection).get("web-access"));
            assertApplicationSettingsMigrated(beforeApplicationSettings, applicationSettings(connection));
            assertEquals(preservedSentinel, scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='format-4-preserve-sentinel'
                """));

            assertEquals(0, number(connection, """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type IN ('table', 'view') AND name IN (
                    'p25_system', 'receiver_context', 'p25_activity_event',
                    'p25_activity_event_resolved', 'p25_control_channel_quality',
                    'trunked_radio_site_presence', 'trunked_radio_talkgroup_summary',
                    'trunked_identity_scope', 'trunked_identity_scope_context',
                    'trunked_identity_summary', 'logger_status'
                )
                """));
            for(String table: List.of("radio_system", "receiver_channel", "receiver_activity_event",
                "conventional_activity_summary", "p25_site_snapshot", "trunked_control_channel_quality",
                "dmr_conventional_talkgroup_summary", "dmr_conventional_radio_summary",
                "trunked_site_snapshot", "trunked_radio_channel_presence",
                "trunked_radio_channel_presence_clear", "trunked_radio_group_summary", "statistics_status"))
            {
                assertEquals(0, number(connection, "SELECT COUNT(*) FROM " + table), table);
            }
            assertTrue(columns(connection, "radio_system").contains("system_key"));
            assertTrue(columns(connection, "radio_system").containsAll(Set.of(
                "dmr_model_code", "dmr_network_id", "nxdn_location_category_code", "nxdn_system_id")));
            Set<String> observedSiteColumns = columns(connection, "trunked_site_snapshot");
            assertTrue(observedSiteColumns.containsAll(Set.of("observed_location_category_code",
                "observed_network_id", "observed_system_id", "observed_site_id", "observed_ran",
                "observed_model_code")));
            assertFalse(observedSiteColumns.contains("identity_domain_code"));
            assertFalse(observedSiteColumns.contains("network_id"));
            assertFalse(observedSiteColumns.contains("system_id"));
            assertFalse(observedSiteColumns.contains("site_id"));
            assertFalse(observedSiteColumns.contains("ran"));
            assertFalse(observedSiteColumns.contains("model_code"));
            assertThrows(SQLException.class, () -> execute(connection, """
                UPDATE configuration_channel SET decoder_type=NULL
                WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL)));
            assertTrue(columns(connection, "trunked_radio_channel_presence_clear").contains("observed_local_id"));
            assertFalse(columns(connection, "trunked_radio_channel_presence_clear").contains(
                "last_observed_local_id"));

            for(String key: List.of("alias_schema_version", "configuration_schema_version",
                "settings_schema_version", "icon_schema_version", "p25_activity_schema_version",
                "trunked_site_schema_version", "dmr_activity_schema_version",
                "trunked_identity_metrics_started_at_ms"))
            {
                assertEquals(0, number(connection,
                    "SELECT COUNT(*) FROM database_metadata WHERE key='" + key + "'"), key);
            }
            for(String key: List.of("conventional_call_output_metrics_started_at_ms",
                "trunked_logical_call_metrics_started_at_ms", "radio_system_metrics_started_at_ms"))
            {
                assertTrue(Long.parseLong(scalar(connection,
                    "SELECT value FROM database_metadata WHERE key='" + key + "'")) > 0, key);
            }
            assertEquals(3, number(connection, """
                SELECT COUNT(*) FROM database_metadata
                WHERE key IN (
                    'conventional_call_output_metrics_started_at_ms',
                    'trunked_logical_call_metrics_started_at_ms',
                    'radio_system_metrics_started_at_ms'
                )
                """));
            assertEquals(1, number(connection, """
                SELECT COUNT(DISTINCT value) FROM database_metadata
                WHERE key IN (
                    'conventional_call_output_metrics_started_at_ms',
                    'trunked_logical_call_metrics_started_at_ms',
                    'radio_system_metrics_started_at_ms'
                )
                """));
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        }
        var migratedConfiguration = new ConfigurationRepository(database).load();
        assertEquals(2, migratedConfiguration.channels().size());
        assertEquals(2, migratedConfiguration.broadcastConfigurations().size());
        assertEquals("Migration Mixed Case", migratedConfiguration.channels().stream()
            .filter(channel -> MIXED_CASE_CHANNEL.equals(channel.getConfigurationId()))
            .findFirst().orElseThrow().getAliasListName());
        assertEquals("Renamed After Migration", migratedConfiguration.broadcastConfigurations().stream()
            .filter(configuration -> configuration.getConfigurationId() != null)
            .filter(configuration -> "Renamed After Migration".equals(configuration.getName()))
            .findFirst().orElseThrow().getName());
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test
    void storesMissingRadioResolveIdentityAsNull() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("format14-blank-radioresolve.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_channel
                SET radres_guid='   ',
                    config_json=json_set(config_json, '$.radresGuid', '   ')
                WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL));
            execute(connection, """
                UPDATE receiver_context SET guid='   '
                WHERE context_key='CONFIGURATION:%s'
                """.formatted(CONVENTIONAL_CHANNEL));

            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertNull(nullableScalar(connection, """
                SELECT radioresolve_id FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL)));
            connection.rollback();
        }
    }

    @Test
    void makesLegacyDmrAndNxdnChannelModesExplicit() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("format14-legacy-channel-modes.sqlite"));
        try(Connection connection = open(database))
        {
            String mappedDmr = "10000000-0000-4000-8000-000000000001";
            String conventionalDmr = "10000000-0000-4000-8000-000000000002";
            String nxdn = "10000000-0000-4000-8000-000000000003";
            String explicitConventionalDmr = "10000000-0000-4000-8000-000000000004";
            insertLegacyModeChannel(connection, mappedDmr, "Legacy mapped DMR", "DMR", "TRUNKED",
                "20000000-0000-4000-8000-000000000001", 20, dmrDecoder(false, 451_012_500L));
            insertLegacyModeChannel(connection, conventionalDmr, "Legacy conventional DMR", "DMR",
                "CONVENTIONAL", null, 21, dmrDecoder(false, 0));
            insertLegacyModeChannel(connection, nxdn, "Legacy NXDN", "NXDN", "TRUNKED",
                "20000000-0000-4000-8000-000000000003", 22, nxdnDecoderWithoutMode());
            insertLegacyModeChannel(connection, explicitConventionalDmr, "Explicit conventional DMR", "DMR",
                "CONVENTIONAL", null, 23, dmrDecoder(true, 451_012_500L));

            connection.setAutoCommit(false);
            try
            {
                new Format14To15DatabaseMigration().migrate(connection);
                assertEquals("TRUNKED", channelMode(connection, mappedDmr));
                assertEquals("CONVENTIONAL", channelMode(connection, conventionalDmr));
                assertEquals("TRUNKED", channelMode(connection, nxdn));
                assertEquals("CONVENTIONAL", channelMode(connection, explicitConventionalDmr));
                assertThrows(SQLException.class, () -> execute(connection, """
                    UPDATE configuration_channel
                    SET config_json=json_remove(config_json, '$.decodeConfiguration.channelMode')
                    WHERE configuration_id='%s'
                    """.formatted(mappedDmr)));
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }
        }
    }

    @Test
    void preservesChannelWithoutAliasListRelationship() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("format14-no-channel-alias-list.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_channel
                SET alias_list_name=NULL,
                    config_json=json_remove(config_json, '$.aliasListName')
                WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL));

            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertNull(nullableScalar(connection, """
                SELECT alias_list_id FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL)));
            connection.rollback();
        }
    }

    @Test
    void convertsTheFormat14DefaultZeroTunerFrequencyToNull() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("format14-zero-frequency.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_channel
                SET primary_frequency_hz=0,
                    config_json=json_set(config_json, '$.sourceConfiguration.frequency', 0)
                WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL));

            new Format14To15DatabaseMigration().validateSource(connection);
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertNull(nullableScalar(connection, """
                SELECT primary_frequency_hz FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL)));
            assertEquals(0, number(connection, """
                SELECT json_extract(config_json, '$.sourceConfiguration.frequency')
                FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL)));
            connection.rollback();
        }
    }

    @Test
    void derivesMultipleFrequencyProjectionFromJsonWithoutTrustingStaleColumns() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("format14-zero-multiple-frequency.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_channel
                SET source_type='RECORDING', primary_frequency_hz=123, frequency_count=99,
                    config_json=json_set(
                        json_remove(config_json, '$.sourceConfiguration.frequency'),
                        '$.sourceConfiguration.type', 'sourceConfigTunerMultipleFrequency',
                        '$.sourceConfiguration.sourceType', 'TUNER_MULTIPLE_FREQUENCIES',
                        '$.sourceConfiguration.frequencies', json('[]'))
                WHERE configuration_id='%s'
                """.formatted(CONVENTIONAL_CHANNEL));
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_channel WHERE configuration_id='" +
                CONVENTIONAL_CHANNEL + "'"));
            assertNull(nullableScalar(connection, "SELECT primary_frequency_hz FROM configuration_channel " +
                "WHERE configuration_id='" + CONVENTIONAL_CHANNEL + "'"));
            connection.rollback();
        }
    }

    @Test
    void countsAndResetsLegacyDmrAndNxdnDerivedRows() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("format14-dmr-nxdn-history.sqlite"));
        try(Connection connection = open(database))
        {
            populateLegacyDmrAndNxdnDerivedHistory(connection);

            List<DatabaseMigrationEffect> preflight = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(10, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "receiver activity and call history").affectedRows());
            assertEquals(5, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "learned site observations").affectedRows());
            assertEquals(13, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "radio-system and receiver-channel identity cache").affectedRows());

            connection.setAutoCommit(false);
            try
            {
                new Format14To15DatabaseMigration().migrate(connection);
                assertEquals(0, number(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
                assertEquals(0, number(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
                assertEquals(0, number(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));
                assertEquals(0, number(connection, "SELECT COUNT(*) FROM dmr_conventional_talkgroup_summary"));
                assertEquals(0, number(connection, "SELECT COUNT(*) FROM dmr_conventional_radio_summary"));
                assertEquals(0, number(connection, "SELECT COUNT(*) FROM trunked_radio_group_summary"));
                assertEquals(0, number(connection, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type='table' AND name IN (
                        'receiver_context', 'trunked_identity_scope', 'trunked_identity_scope_context',
                        'trunked_identity_summary', 'trunked_radio_talkgroup_summary'
                    )
                    """));
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }
        }
    }

    @Test
    void preflightIsReadOnlyAndRollbackRestoresExactFormat14() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));
        try(Connection connection = open(database))
        {
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            String channel = scalar(connection, """
                SELECT config_json FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL));
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().validateSource(connection);
            assertFalse(effects.isEmpty());
            assertEquals(14, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(channel, scalar(connection, """
                SELECT config_json FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));

            connection.setAutoCommit(false);
            try
            {
                new Format14To15DatabaseMigration().migrate(connection);
                assertFalse(columns(connection, "configuration_channel").contains("alias_list_name"));
                assertTrue(columns(connection, "configuration_channel").contains("alias_list_id"));
                String firstBoundary = scalar(connection, """
                    SELECT value FROM database_metadata WHERE key='radio_system_metrics_started_at_ms'
                    """);
                connection.rollback();
                new Format14To15DatabaseMigration().migrate(connection);
                assertEquals(firstBoundary, scalar(connection, """
                    SELECT value FROM database_metadata WHERE key='radio_system_metrics_started_at_ms'
                    """));
                connection.rollback();
            }
            finally
            {
                connection.setAutoCommit(true);
            }
            assertEquals(14, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(channel, scalar(connection, """
                SELECT config_json FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_channel_map"));
        }
    }

    @Test
    void preservesRowAutoStartWhenLegacyJsonOmitsTheBoolean() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("missing-json-auto-start.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_channel
                SET auto_start=1,
                    auto_start_order=1,
                    config_json=json_remove(
                        json_set(config_json, '$.autoStartOrder', 1),
                        '$.autoStart', '$.enabled', '$.order')
                WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL));

            new Format14To15DatabaseMigration().validateSource(connection);
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertEquals(1, number(connection, """
                SELECT auto_start FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals(1, number(connection, """
                SELECT auto_start_order FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            JsonNode migrated = MAPPER.readTree(scalar(connection, """
                SELECT config_json FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertFalse(migrated.has("autoStart"));
            assertFalse(migrated.has("enabled"));
            assertFalse(migrated.has("autoStartOrder"));
            assertFalse(migrated.has("order"));
            connection.rollback();
        }
    }

    @Test
    void ignoresMalformedLegacyJsonCopiesOfRelationalChannelFields() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("malformed-row-owned-json.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_channel
                SET system_name='Authoritative Malformed-JSON System',
                    auto_start=1,
                    auto_start_order=4,
                    config_json=json_set(config_json,
                        '$.system', json('{}'),
                        '$.autoStart', 'not-a-boolean',
                        '$.autoStartOrder', json('[]'))
                WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL));

            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
        }

        var channel = new ConfigurationRepository(database).load().channels().stream()
            .filter(candidate -> MIXED_CASE_CHANNEL.equals(candidate.getConfigurationId()))
            .findFirst().orElseThrow();
        assertEquals("Authoritative Malformed-JSON System", channel.getSystem());
        assertTrue(channel.getAutoStart());
        assertEquals(4, channel.getAutoStartOrder());

        try(Connection connection = open(database))
        {
            JsonNode migrated = MAPPER.readTree(scalar(connection, """
                SELECT config_json FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertFalse(migrated.has("system"));
            assertFalse(migrated.has("autoStart"));
            assertFalse(migrated.has("autoStartOrder"));
        }
    }

    @Test
    void ignoresConflictingLegacyJsonCopiesOfRelationalChannelFields() throws Exception
    {
        String authoritativeRadioResolveId = "30000000-0000-4000-8000-000000000001";
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("stale-row-owned-json.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_channel
                SET system_name='Authoritative System',
                    site_name='Authoritative Site',
                    name='Authoritative Channel',
                    alias_list_name='migration mixed case',
                    radres_guid='%s',
                    auto_start=0,
                    auto_start_order=7,
                    config_json=json_set(config_json,
                        '$.system', 'Stale System',
                        '$.site', 'Stale Site',
                        '$.name', 'Stale Channel',
                        '$.aliasListName', 'Default P25',
                        '$.aliasListId', 999999,
                        '$.radioResolveId', '30000000-0000-4000-8000-000000000002',
                        '$.radresGuid', '30000000-0000-4000-8000-000000000003',
                        '$.radres_guid', '30000000-0000-4000-8000-000000000004',
                        '$.autoStart', json('true'),
                        '$.enabled', json('true'),
                        '$.autoStartOrder', 98,
                        '$.order', 99,
                        '$.channelType', 'TRAFFIC')
                WHERE configuration_id='%s'
                """.formatted(authoritativeRadioResolveId, MIXED_CASE_CHANNEL));

            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
        }

        try(Connection connection = open(database))
        {
            assertEquals("Authoritative System", scalar(connection, """
                SELECT system_name FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals("Authoritative Site", scalar(connection, """
                SELECT site_name FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals("Authoritative Channel", scalar(connection, """
                SELECT name FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals("Migration Mixed Case", scalar(connection, """
                SELECT list.name FROM configuration_channel channel
                JOIN alias_list list ON list.id=channel.alias_list_id
                WHERE channel.configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals(authoritativeRadioResolveId, scalar(connection, """
                SELECT radioresolve_id FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals(0, number(connection, """
                SELECT auto_start FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals(7, number(connection, """
                SELECT auto_start_order FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));

            JsonNode migrated = MAPPER.readTree(scalar(connection, """
                SELECT config_json FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            for(String removed: List.of("system", "site", "name", "aliasListName", "aliasListId",
                "radioResolveId", "radresGuid", "radres_guid", "autoStart", "enabled", "autoStartOrder",
                "order", "channelType"))
            {
                assertFalse(migrated.has(removed), removed);
            }
        }

        var channel = new ConfigurationRepository(database).load().channels().stream()
            .filter(candidate -> MIXED_CASE_CHANNEL.equals(candidate.getConfigurationId()))
            .findFirst().orElseThrow();
        assertEquals("Authoritative System", channel.getSystem());
        assertEquals("Authoritative Site", channel.getSite());
        assertEquals("Authoritative Channel", channel.getName());
        assertEquals("Migration Mixed Case", channel.getAliasListName());
        assertEquals(authoritativeRadioResolveId, channel.getRadioResolveId());
        assertFalse(channel.getAutoStart());
        assertEquals(7, channel.getAutoStartOrder());
    }

    @Test
    void repairsChannelIdentityAndDerivesKindAndProjectionFromJson() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("mismatched-channel-projections.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_channel
                SET channel_kind='CONVENTIONAL', source_type='RECORDING', decoder_type='AM',
                    primary_frequency_hz=1, frequency_count=99,
                    config_json=json_set(config_json, '$.configurationId',
                        '40000000-0000-4000-8000-000000000001')
                WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL));
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertEquals("TRUNKED", scalar(connection, "SELECT channel_kind FROM configuration_channel WHERE " +
                "configuration_id='" + MIXED_CASE_CHANNEL + "'"));
            assertEquals("P25_PHASE1", scalar(connection, "SELECT decoder_type FROM configuration_channel WHERE " +
                "configuration_id='" + MIXED_CASE_CHANNEL + "'"));
            connection.rollback();
        }
    }

    @Test
    void separatesFormat14ListNamesThatCollideAfterRuntimeNormalization() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("format14-normalized-list-names.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                INSERT INTO alias_list(id, name, family, unmatched_talkgroup_record_enabled) VALUES
                    (99001, 'Dispatch Repair', 'P25', 0),
                    (99002, ' Dispatch Repair ', 'P25', 0)
                """);
            execute(connection, """
                INSERT INTO scan_list(id, sort_order, name, description, published, is_default) VALUES
                    (99101, 100, 'Operations Repair', NULL, 1, 0),
                    (99102, 101, ' Operations Repair ', NULL, 1, 0)
                """);

            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects =
                new Format14To15DatabaseMigration().migrateAndReport(connection);
            connection.commit();
            assertTrue(effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows() >= 2);

            assertEquals("Dispatch Repair|Dispatch Repair (2)", scalar(connection, """
                SELECT group_concat(name, '|') FROM
                    (SELECT name FROM alias_list WHERE id IN (99001, 99002) ORDER BY id)
                """));
            assertEquals("Operations Repair|Operations Repair (2)", scalar(connection, """
                SELECT group_concat(name, '|') FROM
                    (SELECT name FROM scan_list WHERE id IN (99101, 99102) ORDER BY id)
                """));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void recoversUnicodeBlankFormat14AliasListNameWithoutBlockingTheMigration() throws Exception
    {
        Path database = Format14TestDatabase.create(
            mTemporaryFolder.resolve("format14-unicode-blank-alias-list.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                INSERT INTO alias_list(id, name, family, unmatched_talkgroup_record_enabled)
                VALUES (981, replace(printf('%025d', 0), '0', char(8195)) || 'x', 'P25', 0)
                """);
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (982, 981, 'Preserved Unicode-blank owner', 'TALKGROUP', 'APCO25', 104)
                """);

            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects =
                new Format14To15DatabaseMigration().migrateAndReport(connection);
            DatabaseFormatCatalog.stampForMigration(connection, DatabaseFormatCatalog.CURRENT_VERSION);
            connection.commit();

            assertTrue(effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows() >= 1);
            assertEquals("Recovered 981", scalar(connection,
                "SELECT name FROM alias_list WHERE id=981"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias WHERE id=982"));
            DatabaseFormatCatalog.requireCurrent(connection);
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void defaultsMalformedOptionalAliasFieldsWithoutDroppingTheFormat14Alias() throws Exception
    {
        Path database = Format14TestDatabase.create(
            mTemporaryFolder.resolve("format14-optional-alias-field-repair.sqlite"));
        try(Connection connection = open(database))
        {
            long p25List = number(connection,
                "SELECT id FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1");
            long scanList = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, description, group_name, color, icon_name,
                                  stream_as_talkgroup, record_enabled, matcher_type, protocol, value)
                VALUES
                    (97401, %d, 'Repair format-14 optional fields', 'description', 'group', 123, 'icon',
                     456, 1, 'TALKGROUP', 'APCO25', 301),
                    (97402, %d, 'Keep format-14 optional fields', 'keep description', 'keep group', 321,
                     'keep icon', 654, 1, 'TALKGROUP', 'APCO25', 302)
                """.formatted(p25List, p25List));
            execute(connection, "INSERT INTO alias_scan_list_membership(alias_id, scan_list_id) VALUES " +
                "(97401, " + scanList + ")");
            List<DatabaseMigrationEffect> baseline = new Format14To15DatabaseMigration().validateSource(connection);
            long baselineDefaults = effect(baseline, DatabaseMigrationEffect.Kind.DEFAULT,
                "Aliases with defaulted optional fields").affectedRows();
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, """
                UPDATE alias SET description=X'01', group_name=X'02', color=1.5, icon_name=X'03',
                                 stream_as_talkgroup=0, record_enabled=2
                WHERE id=97401
                """);
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects =
                new Format14To15DatabaseMigration().migrateAndReport(connection);

            assertEquals(baselineDefaults + 1, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "Aliases with defaulted optional fields").affectedRows());
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias WHERE id=97401"));
            assertEquals(1, number(connection, """
                SELECT description IS NULL AND group_name IS NULL AND color=0 AND icon_name IS NULL
                       AND stream_as_talkgroup IS NULL AND record_enabled=0
                FROM alias WHERE id=97401
                """));
            assertEquals("keep description|keep group|321|keep icon|654|1", scalar(connection, """
                SELECT description || '|' || group_name || '|' || color || '|' || icon_name || '|' ||
                       stream_as_talkgroup || '|' || record_enabled FROM alias WHERE id=97402
                """));
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM alias_scan_list_membership WHERE alias_id=97401"));
            new ConfigurationRepository(database).load(connection);
            connection.rollback();
        }
    }

    @Test
    void defaultsOneOversizedOptionalAliasTextFieldWithoutTouchingSiblingAliases() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("oversized-alias-text.sqlite"));
        try(Connection connection = open(database))
        {
            Format14To15DatabaseMigration migration = new Format14To15DatabaseMigration();
            List<DatabaseMigrationEffect> baseline = migration.validateSource(connection);
            long baselineDrops = effect(baseline, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows();
            long baselineAliasDefaults = effect(baseline, DatabaseMigrationEffect.Kind.DEFAULT,
                "Aliases with defaulted optional fields").affectedRows();
            long sourceAliases = number(connection, "SELECT COUNT(*) FROM alias");
            execute(connection, """
                UPDATE alias SET description=hex(zeroblob(2097153)) WHERE name='Migration Dispatch'
                """);

            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = migration.migrateAndReport(connection);
            assertEquals(baselineDrops, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows());
            assertEquals(baselineAliasDefaults + 1, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "Aliases with defaulted optional fields").affectedRows());
            assertEquals(sourceAliases, number(connection, "SELECT COUNT(*) FROM alias"));
            assertEquals(1, number(connection, "SELECT description IS NULL FROM alias " +
                "WHERE name='Migration Dispatch'"));
            new ConfigurationRepository(database).load(connection);
            connection.rollback();
        }
    }

    @Test
    void boundsOversizedLegacyScalarsWithoutLosingIndependentConfiguration() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("oversized-legacy-scalars.sqlite"));
        long baselineDefaults;
        long baselineConfigurationDrops;
        long baselineRelationshipDrops;
        long sourceChannels;
        long sourceProviders;
        long sourceAliasRoutes;
        long sourceUnmatchedRoutes;
        try(Connection connection = open(database))
        {
            List<DatabaseMigrationEffect> baseline = new Format14To15DatabaseMigration().validateSource(connection);
            baselineDefaults = effect(baseline, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows();
            baselineConfigurationDrops = effect(baseline, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows();
            baselineRelationshipDrops = effect(baseline, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows();
            sourceChannels = number(connection, "SELECT COUNT(*) FROM configuration_channel");
            sourceProviders = number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream");
            sourceAliasRoutes = number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel");
            sourceUnmatchedRoutes = number(connection,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream");
        }

        mutateIgnoringCheckConstraints(database, """
            UPDATE configuration_channel
            SET system_name='OVERSIZED_CHANNEL_SENTINEL-' || hex(zeroblob(2097152))
            WHERE configuration_id='%s';
            UPDATE configuration_broadcast_stream
            SET name='OVERSIZED_PROVIDER_SENTINEL-' || hex(zeroblob(2097152))
            WHERE json_extract(config_json, '$.name')='Primary Migration Feed';
            UPDATE alias_broadcast_channel
            SET channel_name='OVERSIZED_ROUTE_SENTINEL-' || hex(zeroblob(2097152));
            """.formatted(MIXED_CASE_CHANNEL));

        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects =
                new Format14To15DatabaseMigration().migrateAndReport(connection);

            assertTrue(effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows() >= baselineDefaults + 1);
            assertEquals(baselineConfigurationDrops, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows());
            assertEquals(baselineRelationshipDrops + sourceAliasRoutes,
                effect(effects, DatabaseMigrationEffect.Kind.DROP,
                    "orphaned legacy relationships").affectedRows());
            assertEquals(sourceChannels, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertNull(nullableScalar(connection, """
                SELECT system_name FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            assertEquals(sourceProviders,
                number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals(1, number(connection, """
                SELECT COUNT(*) FROM configuration_broadcast_stream
                WHERE json_extract(config_json, '$.name')='Primary Migration Feed'
                """));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel"));
            assertEquals(sourceUnmatchedRoutes,
                number(connection, "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream"));

            String report = effects.toString();
            assertFalse(report.contains("OVERSIZED_CHANNEL_SENTINEL"));
            assertFalse(report.contains("OVERSIZED_PROVIDER_SENTINEL"));
            assertFalse(report.contains("OVERSIZED_ROUTE_SENTINEL"));
            assertTrue(report.length() < 20_000, report);
            new ConfigurationRepository(database).load(connection);
            connection.rollback();
        }
    }

    @Test
    void retainsOnlyTheBoundedWebUserSetWhenFormat14ContainsExcessAccounts() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("excess-format14-users.sqlite"));
        try(Connection connection = open(database))
        {
            int addedUsers = 300;
            long originalUsers = number(connection, "SELECT COUNT(*) FROM web_user");
            Format14To15DatabaseMigration migration = new Format14To15DatabaseMigration();
            long baselineDrops = effect(migration.validateSource(connection), DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows();
            execute(connection, """
                WITH RECURSIVE sequence(value) AS (
                    SELECT 1 UNION ALL SELECT value + 1 FROM sequence WHERE value < 300
                )
                INSERT INTO web_user (
                    id, username, tier, primary_admin, credential_version, password_algorithm,
                    password_iterations, password_derived_key_bits, password_salt, password_hash,
                    password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                    created_at_ms, updated_at_ms
                )
                SELECT 1000 + sequence.value, 'bulk-user-' || sequence.value, 'USER', 0,
                       credential_version, password_algorithm, password_iterations,
                       password_derived_key_bits, password_salt, password_hash,
                       password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                       created_at_ms, updated_at_ms
                FROM web_user primary_user CROSS JOIN sequence
                WHERE primary_user.primary_admin=1
                """);

            long expectedDrops = Math.max(0,
                originalUsers + addedUsers - (WebAccessService.MAXIMUM_USERS + 1L));
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = migration.migrateAndReport(connection);
            assertEquals(baselineDrops + expectedDrops, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows());
            assertEquals(WebAccessService.MAXIMUM_USERS + 1L,
                number(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM web_user WHERE primary_admin=1"));
            Format5WebStateValidator.validate(connection);
            connection.rollback();
        }
    }

    @Test
    void defaultsMalformedAuthoritativeAutoStartScalars() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("invalid-channel-scalars.sqlite"));
        mutateIgnoringCheckConstraints(database, """
            UPDATE configuration_channel SET auto_start=2, auto_start_order=1.5
            WHERE configuration_id='%s'
            """.formatted(MIXED_CASE_CHANNEL));
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().migrateAndReport(connection);
            assertTrue(effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows() >= 1);
            assertEquals(0, number(connection, "SELECT auto_start FROM configuration_channel WHERE " +
                "configuration_id='" + MIXED_CASE_CHANNEL + "'"));
            assertNull(nullableScalar(connection, "SELECT auto_start_order FROM configuration_channel WHERE " +
                "configuration_id='" + MIXED_CASE_CHANNEL + "'"));
            connection.rollback();
        }
    }

    @Test
    void rebasesAnExhaustedLegacyPreferenceRevisionWithoutDiscardingTheAccount() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("exhausted-preference-revision.sqlite"));
        try(Connection connection = open(database))
        {
            Format14To15DatabaseMigration migration = new Format14To15DatabaseMigration();
            long baselineDefaults = effect(migration.validateSource(connection), DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable web account values").affectedRows();
            execute(connection, "UPDATE web_user SET preferences_revision=" + (Long.MAX_VALUE - 1) +
                " WHERE primary_admin=1");
            connection.setAutoCommit(false);

            List<DatabaseMigrationEffect> effects = migration.migrateAndReport(connection);

            assertEquals(baselineDefaults + 1, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable web account values").affectedRows());
            assertEquals(1, number(connection,
                "SELECT preferences_revision FROM web_user WHERE primary_admin=1"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM web_user WHERE primary_admin=1"));
            Format5WebStateValidator.validate(connection);
            connection.rollback();
        }
    }

    @Test
    void migratesIndependentComponentsAcrossMixedDefects() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("mixed-defects.sqlite"));
        mutateIgnoringCheckConstraints(database, """
            UPDATE application_settings SET settings_json='not-json'
            WHERE key='format-4-preserve-sentinel';
            INSERT OR REPLACE INTO application_icons(key, icons_json, updated_at_ms)
            VALUES ('invalid-icons', '[]', 1700000000000);
            INSERT OR REPLACE INTO application_icons(key, icons_json, updated_at_ms)
            VALUES ('default', '{"icons":{}}', 1700000000000);
            INSERT OR REPLACE INTO database_metadata(key, value, updated_at_ms)
            VALUES ('icon_config_initialized', 'true', 1700000000000);
            UPDATE alias SET min_value=1 WHERE name='Migration Dispatch';
            UPDATE configuration_channel SET config_json='{' WHERE configuration_id='%s';
            UPDATE configuration_broadcast_stream SET config_json='{' WHERE name='Primary Migration Feed';
            UPDATE web_user SET preferences_json='not-json', preferences_revision=0 WHERE primary_admin=1;
            UPDATE scan_list SET sort_order=-1, is_default=0 WHERE is_default=1;
            UPDATE sqlite_sequence SET seq=-100 WHERE name='alias';
            """.formatted(MIXED_CASE_CHANNEL));

        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().migrateAndReport(connection);
            assertEquals(6, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows());
            assertEquals(5, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows());
            assertTrue(effects.stream().allMatch(candidate -> candidate.affectedRows() >= 0));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias WHERE name='Migration Dispatch' " +
                "AND min_value IS NULL"));
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM application_settings WHERE key='format-4-preserve-sentinel'"));
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM application_icons WHERE key='invalid-icons'"));
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM application_icons WHERE key='default'"));
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM database_metadata WHERE key='icon_config_initialized'"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM scan_list WHERE is_default=1 AND published=1"));
            assertEquals(6, number(connection,
                "SELECT json_extract(preferences_json, '$.version') FROM web_user WHERE primary_admin=1"));
            assertEquals(number(connection, "SELECT coalesce(max(id), 0) FROM alias"),
                number(connection, "SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name='alias'), 0)"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            connection.rollback();
        }
    }

    @Test
    void preservesValidCoreRowsWhenOnlyTheirUpdateTimestampIsMalformed() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("core-timestamp-salvage.sqlite"));
        Format14To15DatabaseMigration migration = new Format14To15DatabaseMigration();
        long baselineDefaults;
        try(Connection connection = open(database))
        {
            baselineDefaults = effect(migration.validateSource(connection), DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable application settings and metadata").affectedRows();
        }

        String iconJson = "{\"icons\":[]}";
        mutateIgnoringCheckConstraints(database, """
            INSERT OR REPLACE INTO database_metadata(key, value, updated_at_ms)
            VALUES ('timestamp-salvage-metadata', 'preserved', 0);
            UPDATE application_settings SET updated_at_ms=0
            WHERE key='format-4-preserve-sentinel';
            INSERT OR REPLACE INTO application_icons(key, icons_json, updated_at_ms)
            VALUES ('default', '%s', 0);
            """.formatted(iconJson));

        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = migration.migrateAndReport(connection);

            assertEquals(baselineDefaults + 3, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable application settings and metadata").affectedRows());
            assertEquals("preserved", scalar(connection, """
                SELECT value FROM database_metadata WHERE key='timestamp-salvage-metadata'
                """));
            assertEquals(1, number(connection, """
                SELECT updated_at_ms FROM database_metadata WHERE key='timestamp-salvage-metadata'
                """));
            assertEquals(1, number(connection, """
                SELECT updated_at_ms FROM application_settings WHERE key='format-4-preserve-sentinel'
                """));
            assertEquals(iconJson, scalar(connection,
                "SELECT icons_json FROM application_icons WHERE key='default'"));
            assertEquals(1, number(connection,
                "SELECT updated_at_ms FROM application_icons WHERE key='default'"));
            connection.rollback();
        }
    }

    @Test
    void salvagesPortablePreferencesWhenOnlyTheirUpdateTimestampIsMalformed() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("portable-timestamp-salvage.sqlite"));
        mutateIgnoringCheckConstraints(database, """
            UPDATE application_settings SET updated_at_ms=0
            WHERE key='portable_java_preferences_v1'
            """);

        try(Connection connection = open(database))
        {
            assertEquals(1, number(connection, """
                SELECT COUNT(*) FROM application_settings
                WHERE key='portable_java_preferences_v1' AND updated_at_ms=0
                """));
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertEquals(1, number(connection, """
                SELECT COUNT(*) FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(1, number(connection, """
                SELECT updated_at_ms FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            Format5WebStateValidator.validateCurrentPortablePreferences(scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            connection.rollback();
        }
    }

    @Test
    void preservesAutoincrementHighWaterBeyondRetainedRows() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("sequence-high-water.sqlite"));
        try(Connection connection = open(database))
        {
            long baselineDefaults = effect(new Format14To15DatabaseMigration().validateSource(connection),
                DatabaseMigrationEffect.Kind.DEFAULT, "recoverable configuration values").affectedRows();
            execute(connection, "UPDATE sqlite_sequence SET seq=900 WHERE name='alias'");
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects =
                new Format14To15DatabaseMigration().migrateAndReport(connection);

            assertEquals(baselineDefaults, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows());
            assertEquals(900, number(connection, "SELECT seq FROM sqlite_sequence WHERE name='alias'"));
            assertEquals(901, insertAliasSequenceProbe(connection, "Post-migration high-water probe"));
            connection.rollback();
        }
    }

    @Test
    void defaultsMissingLowAndExhaustedAutoincrementSequences() throws Exception
    {
        for(long sourceSequence: List.of(0L, Long.MAX_VALUE))
        {
            Path database = Format14TestDatabase.create(
                mTemporaryFolder.resolve("sequence-repair-" + sourceSequence + ".sqlite"));
            try(Connection connection = open(database))
            {
                long baselineDefaults = effect(new Format14To15DatabaseMigration().validateSource(connection),
                    DatabaseMigrationEffect.Kind.DEFAULT, "recoverable configuration values").affectedRows();
                execute(connection, "UPDATE sqlite_sequence SET seq=" + sourceSequence + " WHERE name='alias'");
                connection.setAutoCommit(false);
                List<DatabaseMigrationEffect> effects =
                    new Format14To15DatabaseMigration().migrateAndReport(connection);

                assertEquals(baselineDefaults + 1, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                    "recoverable configuration values").affectedRows());
                long retainedMaximum = number(connection, "SELECT max(id) FROM alias");
                assertEquals(retainedMaximum,
                    number(connection, "SELECT seq FROM sqlite_sequence WHERE name='alias'"));
                assertEquals(retainedMaximum + 1,
                    insertAliasSequenceProbe(connection, "Post-migration repaired-sequence probe"));
                connection.rollback();
            }
        }
    }

    @Test
    void allocatesGeneratedDefaultScanListAboveSourceHighWater() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("generated-default-high-water.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, "DELETE FROM scan_list");
            execute(connection, "UPDATE sqlite_sequence SET seq=900 WHERE name='scan_list'");
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertEquals(901, number(connection, "SELECT id FROM scan_list WHERE is_default=1"));
            assertEquals(901, number(connection, "SELECT seq FROM sqlite_sequence WHERE name='scan_list'"));
            connection.rollback();
        }
    }

    @Test
    void recoversSurvivingMembershipOwnersWhenEveryFormat14ScanListIsUnusable() throws Exception
    {
        Path database = Format14TestDatabase.create(
            mTemporaryFolder.resolve("generated-default-membership-recovery.sqlite"));
        try(Connection connection = open(database))
        {
            long scanListId = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            long aliasId = number(connection, "SELECT id FROM alias ORDER BY id LIMIT 1");
            long aliasListId = number(connection, "SELECT id FROM alias_list ORDER BY id LIMIT 1");
            execute(connection, "INSERT OR IGNORE INTO alias_scan_list_membership(alias_id, scan_list_id) VALUES (" +
                aliasId + ", " + scanListId + ")");
            execute(connection, "INSERT OR IGNORE INTO alias_list_unmatched_talkgroup_scan_list_membership" +
                "(alias_list_id, scan_list_id) VALUES (" + aliasListId + ", " + scanListId + ")");

            long sourceRows = number(connection, """
                SELECT
                    (SELECT COUNT(*) FROM alias_scan_list_membership membership
                     JOIN alias owner ON owner.id=membership.alias_id) +
                    (SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership membership
                     JOIN alias_list owner ON owner.id=membership.alias_list_id)
                """);
            long aliasOwners = number(connection, """
                SELECT COUNT(DISTINCT membership.alias_id)
                FROM alias_scan_list_membership membership JOIN alias owner ON owner.id=membership.alias_id
                """);
            long aliasListOwners = number(connection, """
                SELECT COUNT(DISTINCT membership.alias_list_id)
                FROM alias_list_unmatched_talkgroup_scan_list_membership membership
                JOIN alias_list owner ON owner.id=membership.alias_list_id
                """);
            assertTrue(sourceRows > 0);

            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "UPDATE scan_list SET id=9007199254740991 + id");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");
            execute(connection, "PRAGMA foreign_keys=ON");

            List<DatabaseMigrationEffect> planned = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(sourceRows, effect(planned, DatabaseMigrationEffect.Kind.TRANSFORM,
                "scan-list memberships recovered into Default").affectedRows());

            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertEquals(1, number(connection, "SELECT COUNT(*) FROM scan_list WHERE is_default=1 AND published=1"));
            assertEquals(aliasOwners, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership"));
            assertEquals(aliasOwners, number(connection, """
                SELECT COUNT(*) FROM alias_scan_list_membership membership
                JOIN scan_list target ON target.id=membership.scan_list_id AND target.is_default=1
                """));
            assertEquals(aliasListOwners, number(connection,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership"));
            assertEquals(aliasListOwners, number(connection, """
                SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership membership
                JOIN scan_list target ON target.id=membership.scan_list_id AND target.is_default=1
                """));
            new ConfigurationRepository(database).load(connection);
            connection.rollback();
        }
    }

    @Test
    void remapsOnlyDiscardedFormat14DefaultMembershipsWhenAHealthyCustomListSurvives() throws Exception
    {
        Path database = Format14TestDatabase.create(
            mTemporaryFolder.resolve("format14-discarded-default-membership-recovery.sqlite"));
        try(Connection connection = open(database))
        {
            long oldDefault = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            execute(connection, "INSERT INTO scan_list(sort_order, name, published, is_default) " +
                "VALUES (1, 'Healthy custom list', 1, 0)");
            long customList = number(connection, "SELECT id FROM scan_list WHERE name='Healthy custom list'");
            long oldDefaultOwner = number(connection, "SELECT id FROM alias ORDER BY id LIMIT 1");
            long owningList = number(connection, "SELECT alias_list_id FROM alias WHERE id=" + oldDefaultOwner);
            execute(connection, "INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value) VALUES (" +
                owningList + ", 'Healthy custom-list owner', 'TALKGROUP', 'APCO25', 65432)");
            long customOnlyOwner = number(connection,
                "SELECT id FROM alias WHERE name='Healthy custom-list owner'");
            execute(connection, "DELETE FROM alias_scan_list_membership WHERE alias_id IN (" +
                oldDefaultOwner + ", " + customOnlyOwner + ")");
            execute(connection, "INSERT INTO alias_scan_list_membership(alias_id, scan_list_id) VALUES (" +
                oldDefaultOwner + ", " + oldDefault + "), (" + customOnlyOwner + ", " + customList + ")");
            long recoverableRows = number(connection, """
                SELECT
                    (SELECT COUNT(*) FROM alias_scan_list_membership WHERE scan_list_id=%d) +
                    (SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership
                     WHERE scan_list_id=%d)
                """.formatted(oldDefault, oldDefault));
            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "UPDATE scan_list SET id=9007199254740991 WHERE id=" + oldDefault);
            execute(connection, "PRAGMA ignore_check_constraints=OFF");
            execute(connection, "PRAGMA foreign_keys=ON");

            List<DatabaseMigrationEffect> planned = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(recoverableRows, effect(planned, DatabaseMigrationEffect.Kind.TRANSFORM,
                "scan-list memberships recovered into Default").affectedRows());

            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            long newDefault = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership WHERE alias_id=" +
                oldDefaultOwner + " AND scan_list_id=" + newDefault));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership WHERE alias_id=" +
                customOnlyOwner + " AND scan_list_id=" + customList));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership WHERE alias_id=" +
                customOnlyOwner + " AND scan_list_id=" + newDefault));
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM scan_list WHERE id=" + customList + " AND is_default=0"));
            new ConfigurationRepository(database).load(connection);
            connection.rollback();
        }
    }

    @Test
    void defaultsMissingOrMalformedRequiredSettingsIndependently() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("required-setting-repair.sqlite"));
        try(Connection connection = open(database))
        {
            Format14To15DatabaseMigration migration = new Format14To15DatabaseMigration();
            List<DatabaseMigrationEffect> baseline = migration.validateSource(connection);
            long baselineDrops = effect(baseline, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows();
            long baselineDefaults = effect(baseline, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows();
            execute(connection, "UPDATE application_settings SET settings_json='{}' WHERE key='setup_wizard'");
            execute(connection, "DELETE FROM application_settings WHERE key='spectrum_snap_country'");

            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = migration.migrateAndReport(connection);
            assertEquals(baselineDrops + 1, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows());
            assertEquals(baselineDefaults + 2, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows());
            assertTrue(SetupProgress.read(connection).isImported());
            assertEquals(SpectrumSnapSettings.defaults(), SpectrumSnapSettings.read(connection));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            connection.rollback();
        }
    }

    @Test
    void preservesCredentialsAndSettingsWhileMigratingPreferencesAndPolicyNames() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("web-state-preservation.sqlite"));
        try(Connection connection = open(database))
        {
            Map<Long,Credential> beforeCredentials = credentials(connection);
            Map<Long,Preference> beforePreferences = preferences(connection);
            Map<String,Policy> beforePolicies = policies(connection);
            Map<String,ApplicationSetting> beforeSettings = applicationSettings(connection);
            assertFalse(beforeCredentials.isEmpty());

            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertEquals(beforeCredentials, credentials(connection));
            assertPreferencesMigrated(beforePreferences, preferences(connection));
            assertEquals(expectedPolicies(beforePolicies), policies(connection));
            assertApplicationSettingsMigrated(beforeSettings, applicationSettings(connection));
            Format5WebStateValidator.validate(connection);
            connection.rollback();
        }
    }

    @Test
    void preservesFormat14CredentialsWhenOnlyCredentialBookkeepingIsDamaged() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("credential-bookkeeping-repair.sqlite"));
        try(Connection connection = open(database))
        {
            Format14To15DatabaseMigration migration = new Format14To15DatabaseMigration();
            long baselineDefaults = effect(migration.validateSource(connection), DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable web account values").affectedRows();
            long sourceUsers = number(connection, "SELECT COUNT(*) FROM web_user");
            String verifier = scalar(connection, "SELECT hex(password_salt) || ':' || hex(password_hash) " +
                "FROM web_user WHERE primary_admin=1");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "UPDATE web_user SET password_changed_at_ms=0, " +
                "auth_revision=9223372036854775807, created_at_ms=0, updated_at_ms=0 WHERE primary_admin=1");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = migration.migrateAndReport(connection);

            assertEquals(sourceUsers, number(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals(verifier, scalar(connection, "SELECT hex(password_salt) || ':' || hex(password_hash) " +
                "FROM web_user WHERE primary_admin=1"));
            assertEquals(1, number(connection, "SELECT auth_revision FROM web_user WHERE primary_admin=1"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM web_user WHERE primary_admin=1 AND " +
                "password_changed_at_ms>0 AND created_at_ms>0 AND updated_at_ms>=created_at_ms"));
            assertEquals(baselineDefaults + 1, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable web account values").affectedRows());
            Format5WebStateValidator.validate(connection);
            connection.rollback();
        }
    }

    @Test
    void resetsOnlyWebAuthenticationWhenThePrimaryCredentialIsUnusable() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("unusable-primary.sqlite"));
        mutateIgnoringCheckConstraints(database, """
            UPDATE web_user SET password_salt=CAST('not-a-valid-salt' AS TEXT) WHERE primary_admin=1
            """);
        try(Connection connection = open(database))
        {
            long sourceUsers = number(connection, "SELECT COUNT(*) FROM web_user");
            long sourcePolicies = number(connection, "SELECT COUNT(*) FROM web_access_policy");
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().migrateAndReport(connection);
            assertEquals(sourceUsers + sourcePolicies, effect(effects, DatabaseMigrationEffect.Kind.RESET,
                "web authentication").affectedRows());
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM web_access_policy"));
            assertEquals("required", scalar(connection,
                "SELECT value FROM database_metadata WHERE key='initial_admin_setup'"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            Format5WebStateValidator.validate(connection);
            connection.rollback();
        }
    }

    @Test
    void dropsUnsupportedInitialAdminMarkerWhilePreservingValidCredentials() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("invalid-admin-marker.sqlite"));
        try(Connection connection = open(database))
        {
            Format14To15DatabaseMigration migration = new Format14To15DatabaseMigration();
            long baselineDrops = effect(migration.validateSource(connection), DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows();
            long users = number(connection, "SELECT COUNT(*) FROM web_user");
            execute(connection, """
                INSERT OR REPLACE INTO database_metadata(key, value, updated_at_ms)
                VALUES ('initial_admin_setup', 'unsupported-state', 1700000000000)
                """);
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = migration.migrateAndReport(connection);

            assertEquals(baselineDrops + 1, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows());
            assertEquals(users, number(connection, "SELECT COUNT(*) FROM web_user"));
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM database_metadata WHERE key='initial_admin_setup'"));
            Format5WebStateValidator.validate(connection);
            DatabaseFormatCatalog.stamp(connection, 15);
            connection.commit();
        }
        assertFalse(InitialAdminSetup.isPasswordRequired(database));
    }

    @Test
    void mapsWebPoliciesWithoutPersistingPublicDefaults() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("web-policy-mapping.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE web_access_policy
                SET required_tier='USER', updated_at_ms=1700000000300
                WHERE capability_id='site-access'
                """);
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertEquals(new Policy("USER", 1_700_000_000_300L), policies(connection).get("web-access"));
            assertEquals(new Policy("ADMIN", 1_700_000_000_200L), policies(connection).get("radio"));
            assertFalse(policies(connection).containsKey("site-access"));
            assertFalse(policies(connection).containsKey("systems"));
            assertFalse(policies(connection).containsKey("conventional"));
            connection.rollback();
        }

        Path onePublic = Format14TestDatabase.create(mTemporaryFolder.resolve("one-public-policy.sqlite"));
        try(Connection connection = open(onePublic))
        {
            execute(connection, "DELETE FROM web_access_policy WHERE capability_id='conventional'");
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertEquals(new Policy("USER", 1_700_000_000_100L), policies(connection).get("radio"));
            connection.rollback();
        }

        Path bothPublic = Format14TestDatabase.create(mTemporaryFolder.resolve("both-public-policy.sqlite"));
        try(Connection connection = open(bothPublic))
        {
            execute(connection, "DELETE FROM web_access_policy WHERE capability_id IN ('systems', 'conventional')");
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertFalse(policies(connection).containsKey("radio"));
            connection.rollback();
        }
    }

    @Test
    void acceptsAlreadyRenamedReceiverSettingsRevision() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("mixed-receiver-settings-revision.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE application_settings
                SET settings_json=json_set(settings_json,
                    '$."user/io/github/dsheirer/preference/nowplaying"."receiver.settings.revision"', '7')
                WHERE key='portable_java_preferences_v1'
                """);
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertEquals("7", scalar(connection, """
                SELECT json_extract(settings_json,
                    '$."user/io/github/dsheirer/preference/nowplaying"."receiver.settings.revision"')
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            connection.rollback();
        }
    }

    @Test
    void defaultsAnExhaustedAlreadyRenamedReceiverSettingsRevision() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("exhausted-receiver-settings.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE application_settings
                SET settings_json=json_set(
                    json_remove(settings_json,
                        '$."user/io/github/dsheirer/preference/nowplaying"."site.settings.revision"'),
                    '$."user/io/github/dsheirer/preference/nowplaying"."receiver.settings.revision"', '%d',
                    '$."user/io/github/dsheirer/preference/nowplaying"."migration-sentinel"', 'preserved')
                WHERE key='portable_java_preferences_v1'
                """.formatted(Long.MAX_VALUE - 1));
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            String migrated = scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """);
            Format5WebStateValidator.validateCurrentPortablePreferences(migrated);
            JsonNode nowPlaying = MAPPER.readTree(migrated)
                .get("user/io/github/dsheirer/preference/nowplaying");
            assertEquals("preserved", nowPlaying.get("migration-sentinel").textValue());
            assertFalse(nowPlaying.has("receiver.settings.revision"));
            connection.rollback();
        }
    }

    @Test
    void salvagesPortablePreferenceNodesAndValuesIndependently() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("mixed-portable-preferences.sqlite"));
        try(Connection connection = open(database))
        {
            long baselineDefaults = effect(new Format14To15DatabaseMigration().validateSource(connection),
                DatabaseMigrationEffect.Kind.DEFAULT, "recoverable configuration values").affectedRows();
            execute(connection, """
                UPDATE application_settings SET settings_json=
                    '{"user/io/github/dsheirer/preference/nowplaying":{"sentinel":"keep",' ||
                    '"site.settings.revision":"7","retain.idle.call.details":"true",' ||
                    '"traffic.grant.age.out.milliseconds":"99999"},' ||
                    '"valid/node":{"keep":"yes","bad":7,' ||
                    '"stats.web.call.maximum.listeners":"12"},"bad/node":[]}'
                WHERE key='portable_java_preferences_v1'
                """);

            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(baselineDefaults + 1, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows());

            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            assertEquals("keep", scalar(connection, """
                SELECT json_extract(settings_json,
                    '$."user/io/github/dsheirer/preference/nowplaying".sentinel')
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("yes", scalar(connection, """
                SELECT json_extract(settings_json, '$."valid/node".keep')
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(0, number(connection, """
                SELECT json_type(settings_json, '$."bad/node"') IS NOT NULL OR
                       json_type(settings_json, '$."valid/node".bad') IS NOT NULL OR
                       json_type(settings_json,
                         '$."user/io/github/dsheirer/preference/nowplaying"."retain.idle.call.details"') IS NOT NULL
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            DatabaseFormatCatalog.requireCurrent(connection);
            connection.rollback();
        }
    }

    @Test
    void canonicalizesDuplicatePortablePreferenceKeysInsteadOfFailingTheChain() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("duplicate-portable-preferences.sqlite"));
        try(Connection connection = open(database))
        {
            long baselineDefaults = effect(new Format14To15DatabaseMigration().validateSource(connection),
                DatabaseMigrationEffect.Kind.DEFAULT, "recoverable configuration values").affectedRows();
            execute(connection, """
                UPDATE application_settings
                SET settings_json='{"valid/node":{"keep":"first","keep":"second"}}'
                WHERE key='portable_java_preferences_v1'
                """);

            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(baselineDefaults + 1, effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable configuration values").affectedRows());

            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            assertEquals("second", scalar(connection, """
                SELECT json_extract(settings_json, '$."valid/node".keep')
                FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            DatabaseFormatCatalog.requireCurrent(connection);
            connection.rollback();
        }
    }

    @Test
    void dropsAndReportsBroadcastRoutesWhoseProvidersNoLongerExist() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("missing-provider.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE alias_broadcast_channel SET channel_name='Missing Provider'
                """);
            execute(connection, """
                UPDATE alias_list_unmatched_talkgroup_stream SET channel_name='Missing Provider'
                """);

            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(2, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows());
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel"));
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream"));

            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel"));
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            connection.rollback();
        }
    }

    @Test
    void dropsNonTextBroadcastRouteNamesWithoutBlockingIndependentComponents() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("non-text-provider-route.sqlite"));
        mutateIgnoringCheckConstraints(database, """
            UPDATE alias_broadcast_channel SET channel_name=X'00';
            UPDATE alias_list_unmatched_talkgroup_stream SET channel_name=X'01';
            """);
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().migrateAndReport(connection);
            assertEquals(2, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows());
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel"));
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            connection.rollback();
        }
    }

    @Test
    void clearsAndReportsSavedChannelRelationshipToMissingAliasList() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("missing-channel-alias-list.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_channel
                SET alias_list_name='Removed Alias List',
                    config_json=json_set(config_json, '$.aliasListName', 'Removed Alias List')
                WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL));

            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(1, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows());

            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertNull(nullableScalar(connection, """
                SELECT alias_list_id FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(MIXED_CASE_CHANNEL)));
            connection.rollback();
        }
    }

    @Test
    void dropsAndReportsRelationshipsWhoseOwnerRowsNoLongerExist() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("missing-relationship-owners.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, """
                INSERT INTO alias_scan_list_membership(alias_id, scan_list_id)
                SELECT 999999, id FROM scan_list ORDER BY id LIMIT 1
                """);
            execute(connection, """
                INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership(alias_list_id, scan_list_id)
                SELECT 999999, id FROM scan_list ORDER BY id LIMIT 1
                """);
            execute(connection, """
                INSERT INTO alias_broadcast_channel(alias_id, channel_name)
                VALUES (999999, 'Primary Migration Feed')
                """);
            execute(connection, """
                INSERT INTO alias_list_unmatched_talkgroup_stream(alias_list_id, channel_name)
                VALUES (999999, 'Primary Migration Feed')
                """);
        }

        try(Connection connection = open(database))
        {
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(4, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows());

            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM alias_scan_list_membership WHERE alias_id=999999"));
            assertEquals(0, number(connection, """
                SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership
                WHERE alias_list_id=999999
                """));
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM alias_broadcast_channel WHERE alias_id=999999"));
            assertEquals(0, number(connection, """
                SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream WHERE alias_list_id=999999
                """));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            connection.rollback();
        }
    }

    @Test
    void dropsAmbiguousNameBasedBroadcastRoutesAndPreservesProviders() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("ambiguous-provider.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_broadcast_stream
                SET name='Primary Migration Feed',
                    config_json=json_set(config_json, '$.name', 'Primary Migration Feed')
                WHERE name='Secondary Migration Feed'
                """);
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().migrateAndReport(connection);
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel"));
            assertTrue(effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows() >= 1);
            connection.rollback();
        }
    }

    @Test
    void replacesMalformedProviderIdentityDeterministically() throws Exception
    {
        String mutation = """
            UPDATE configuration_broadcast_stream
            SET config_json=json_set(config_json, '$.configurationId', 'NOT-A-UUID')
            WHERE name='Primary Migration Feed'
            """;
        Map<String,String> first = migratedProviderIds("malformed-provider-first.sqlite", mutation);
        Map<String,String> second = migratedProviderIds("malformed-provider-second.sqlite", mutation);
        assertEquals(first, second);
        assertCanonicalUniqueProviderIds(first);
    }

    @Test
    void replacesDuplicateProviderIdentityDeterministically() throws Exception
    {
        String duplicate = "12345678-1234-4234-8234-123456789abc";
        String mutation = """
            UPDATE configuration_broadcast_stream
            SET config_json=json_set(config_json, '$.configurationId', '%s')
            """.formatted(duplicate);
        Map<String,String> first = migratedProviderIds("duplicate-provider-first.sqlite", mutation);
        Map<String,String> second = migratedProviderIds("duplicate-provider-second.sqlite", mutation);
        assertEquals(first, second);
        assertCanonicalUniqueProviderIds(first);
        assertFalse(first.containsValue(duplicate));
    }

    @Test
    void canonicalizesTheExactLegacyRadioResolveSubtype() throws Exception
    {
        Path database = Format14TestDatabase.create(
            mTemporaryFolder.resolve("legacy-radioresolve-provider.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_broadcast_stream
                SET server_type='RADIORESOLVE',
                    config_json=json_set(config_json, '$.type', 'RADIORESOLVE')
                WHERE name='Primary Migration Feed'
                """);
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            assertEquals("RadioResolveConfiguration", scalar(connection, """
                SELECT json_extract(config_json, '$.type')
                FROM configuration_broadcast_stream
                WHERE json_extract(config_json, '$.name')='Primary Migration Feed'
                """));
            connection.rollback();
        }
    }

    @Test
    void derivesProviderNameFromJsonInsteadOfStaleRelationalProjection() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("stale-provider-projection.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_broadcast_stream SET name='Stale relational provider name'
                WHERE name='Primary Migration Feed'
                """);
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertEquals(1, number(connection, """
                SELECT COUNT(*) FROM configuration_broadcast_stream
                WHERE json_extract(config_json, '$.name')='Primary Migration Feed'
                """));
            assertEquals(1, number(connection, """
                SELECT COUNT(*) FROM alias_broadcast_channel route
                JOIN configuration_broadcast_stream provider
                  ON provider.configuration_id=route.broadcast_configuration_id
                WHERE json_extract(provider.config_json, '$.name')='Primary Migration Feed'
                """));
            connection.rollback();
        }
    }

    @Test
    void coalescesLegacyRouteNamesThatResolveToTheSameProviderIdentity() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("duplicate-resolved-routes.sqlite"));
        try(Connection connection = open(database))
        {
            long aliasRoutes = number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel");
            long unmatchedRoutes = number(connection,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream");
            execute(connection, """
                UPDATE configuration_broadcast_stream
                SET name=CASE name
                    WHEN 'Primary Migration Feed' THEN 'Stale primary provider name'
                    WHEN 'Secondary Migration Feed' THEN 'Stale secondary provider name'
                END
                WHERE name IN ('Primary Migration Feed', 'Secondary Migration Feed')
                """);
            execute(connection, """
                INSERT INTO alias_broadcast_channel(alias_id, channel_name)
                SELECT alias_id, 'Stale primary provider name'
                FROM alias_broadcast_channel
                WHERE channel_name='Primary Migration Feed'
                LIMIT 1
                """);
            execute(connection, """
                INSERT INTO alias_list_unmatched_talkgroup_stream(alias_list_id, channel_name)
                SELECT alias_list_id, 'Stale secondary provider name'
                FROM alias_list_unmatched_talkgroup_stream
                WHERE channel_name='Secondary Migration Feed'
                LIMIT 1
                """);

            List<DatabaseMigrationEffect> preflight = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(2, effect(preflight, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows());

            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);

            assertEquals(aliasRoutes, number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel"));
            assertEquals(unmatchedRoutes, number(connection,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            connection.rollback();
        }
    }

    @Test
    void skipsMalformedProviderWithoutDroppingValidChannels() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("case-folded-radioresolve-provider.sqlite"));
        try(Connection connection = open(database))
        {
            execute(connection, """
                UPDATE configuration_broadcast_stream
                SET server_type='RADIORESOLVE',
                    config_json=json_set(config_json, '$.type', 'radioresolve')
                WHERE name='Primary Migration Feed'
                """);
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().migrateAndReport(connection);
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(1, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows());
            connection.rollback();
        }
    }

    @Test
    void usesTheLegacyProviderNameWhenJsonNameIsMissingAndKeepsItsRoutes() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("provider-scalar-name-route.sqlite"));
        try(Connection connection = open(database))
        {
            long sourceRoutes = number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel " +
                "WHERE channel_name='Primary Migration Feed'");
            assertTrue(sourceRoutes > 0);
            execute(connection, "UPDATE configuration_broadcast_stream " +
                "SET config_json=json_remove(config_json, '$.name') WHERE name='Primary Migration Feed'");

            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects =
                new Format14To15DatabaseMigration().migrateAndReport(connection);

            assertTrue(effect(effects, DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable broadcast provider values").affectedRows() >= 1);
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream " +
                "WHERE json_extract(config_json, '$.name')='Primary Migration Feed'"));
            assertEquals(sourceRoutes, number(connection, """
                SELECT COUNT(*) FROM alias_broadcast_channel route
                JOIN configuration_broadcast_stream provider
                  ON provider.configuration_id=route.broadcast_configuration_id
                WHERE json_extract(provider.config_json, '$.name')='Primary Migration Feed'
                """));
            connection.rollback();
        }
    }

    @Test
    void skipsChannelMissingItsTypedDecoderAndSourceInsteadOfInventingNbfmDefaults() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("missing-channel-components.sqlite"));
        try(Connection connection = open(database))
        {
            long before = number(connection, "SELECT COUNT(*) FROM configuration_channel");
            execute(connection, """
                UPDATE configuration_channel SET config_json='{}'
                WHERE id=(SELECT MIN(id) FROM configuration_channel)
                """);
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().migrateAndReport(connection);

            assertEquals(before - 1, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(1, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows());
            assertEquals(0, number(connection, """
                SELECT COUNT(*) FROM configuration_channel
                WHERE decoder_type='NBFM' AND name NOT LIKE '%NBFM%'
                """));
            connection.rollback();
        }
    }

    @Test
    void clearsAnIncompatibleLegacyAliasListInsteadOfFailingTheWholeConfiguration() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("incompatible-channel-list.sqlite"));
        try(Connection connection = open(database))
        {
            String configurationId;
            String incompatibleAliasList;
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT channel.configuration_id, target.name
                FROM configuration_channel channel
                CROSS JOIN alias_list target
                WHERE target.family <> CASE
                    WHEN channel.decoder_type IN ('P25_PHASE1', 'P25_PHASE2', 'P25_CONVENTIONAL') THEN 'P25'
                    WHEN channel.decoder_type='DMR' THEN 'DMR'
                    WHEN channel.decoder_type='NXDN' THEN 'NXDN'
                    WHEN channel.decoder_type IN ('AM', 'NBFM') THEN 'NBFM'
                END
                ORDER BY channel.id, target.id
                LIMIT 1
                """))
            {
                assertTrue(rows.next(), "The format-14 fixture needs a channel and an incompatible Alias List");
                configurationId = rows.getString(1);
                incompatibleAliasList = rows.getString(2);
            }
            try(var update = connection.prepareStatement("""
                UPDATE configuration_channel SET alias_list_name=? WHERE configuration_id=?
                """))
            {
                update.setString(1, incompatibleAliasList);
                update.setString(2, configurationId);
                assertEquals(1, update.executeUpdate());
            }
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = new Format14To15DatabaseMigration().migrateAndReport(connection);

            assertEquals(1, number(connection, """
                SELECT alias_list_id IS NULL FROM configuration_channel WHERE configuration_id='%s'
                """.formatted(configurationId)));
            assertTrue(effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows() >= 1);
            new ConfigurationRepository(database).load(connection);
            connection.rollback();
        }
    }

    @Test
    void skipsExhaustedProviderRowIdAndItsDependentRoute() throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve("exhausted-provider-row-id.sqlite"));
        try(Connection connection = open(database))
        {
            Format14To15DatabaseMigration migration = new Format14To15DatabaseMigration();
            List<DatabaseMigrationEffect> baseline = migration.validateSource(connection);
            long baselineDrops = effect(baseline, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows();
            long baselineOrphans = effect(baseline, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows();
            execute(connection, """
                UPDATE configuration_broadcast_stream SET id=9223372036854775807
                WHERE name='Primary Migration Feed'
                """);
            connection.setAutoCommit(false);
            List<DatabaseMigrationEffect> effects = migration.migrateAndReport(connection);

            assertEquals(baselineDrops + 1, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "unusable administrator-owned configuration").affectedRows());
            assertEquals(baselineOrphans + 1, effect(effects, DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships").affectedRows());
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel"));
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_stream"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            connection.rollback();
        }
    }

    @Test
    void preservesUniqueProviderIdentityWhenItCollidesWithGeneratedCandidate() throws Exception
    {
        String generatedForSecondRow = UUID.nameUUIDFromBytes(
            "sdrtrunk-vce:format-14:broadcast-provider:2".getBytes(StandardCharsets.UTF_8)).toString();
        String mutation = """
            UPDATE configuration_broadcast_stream
            SET config_json=json_set(config_json, '$.configurationId', '%s')
            WHERE name='Primary Migration Feed'
            """.formatted(generatedForSecondRow);
        Map<String,String> first = migratedProviderIds("provider-collision-first.sqlite", mutation);
        Map<String,String> second = migratedProviderIds("provider-collision-second.sqlite", mutation);
        assertEquals(first, second);
        assertCanonicalUniqueProviderIds(first);
        assertEquals(generatedForSecondRow, first.get("Primary Migration Feed"));
        assertFalse(generatedForSecondRow.equals(first.get("Secondary Migration Feed")));
    }

    @Test
    void preservesCanonicalProviderIdentityAndGeneratesMissingIdentityDeterministically() throws Exception
    {
        String preserved = "dddddddd-eeee-4fff-8aaa-bbbbbbbbbbbb";
        Path first = Format14TestDatabase.create(mTemporaryFolder.resolve("provider-first.sqlite"));
        Path second = Format14TestDatabase.create(mTemporaryFolder.resolve("provider-second.sqlite"));
        Map<String,String> firstIds;
        try(Connection connection = open(first))
        {
            execute(connection, """
                UPDATE configuration_broadcast_stream
                SET config_json=json_set(config_json, '$.configurationId', '%s')
                WHERE name='Primary Migration Feed'
                """.formatted(preserved));
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            firstIds = providerIds(connection);
            connection.rollback();
        }
        try(Connection connection = open(second))
        {
            execute(connection, """
                UPDATE configuration_broadcast_stream
                SET config_json=json_set(config_json, '$.configurationId', '%s')
                WHERE name='Primary Migration Feed'
                """.formatted(preserved));
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            Map<String,String> secondIds = providerIds(connection);
            assertEquals(firstIds, secondIds);
            connection.rollback();
        }
        assertEquals(preserved, firstIds.get("Primary Migration Feed"));
        assertCanonicalUniqueProviderIds(firstIds);
    }

    private static void mutateIgnoringCheckConstraints(Path database, String mutation) throws Exception
    {
        try(Connection connection = open(database); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            for(String sql: mutation.split(";"))
            {
                if(!sql.isBlank())
                {
                    statement.executeUpdate(sql);
                }
            }
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }
    }

    private static long insertAliasSequenceProbe(Connection connection, String name) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias(
                alias_list_id, name, description, group_name, color, icon_name, stream_as_talkgroup,
                record_enabled, matcher_type, protocol, value, min_value, max_value, text_value,
                numeric_value, tone_sequence
            )
            SELECT alias_list_id, ?, description, group_name, color, icon_name, stream_as_talkgroup,
                   record_enabled, matcher_type, protocol, value, min_value, max_value, text_value,
                   numeric_value, tone_sequence
            FROM alias ORDER BY id LIMIT 1
            """))
        {
            statement.setString(1, name);
            assertEquals(1, statement.executeUpdate());
        }
        try(PreparedStatement statement = connection.prepareStatement("SELECT id FROM alias WHERE name=?"))
        {
            statement.setString(1, name);
            try(ResultSet rows = statement.executeQuery())
            {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private Map<String,String> migratedProviderIds(String filename, String mutation) throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve(filename));
        try(Connection connection = open(database))
        {
            execute(connection, mutation);
            connection.setAutoCommit(false);
            new Format14To15DatabaseMigration().migrate(connection);
            Map<String,String> result = providerIds(connection);
            connection.rollback();
            return result;
        }
    }

    private static void insertLegacyModeChannel(Connection connection, String configurationId, String name,
                                                String decoderType, String channelKind, String radioResolveId,
                                                int sortOrder, JsonNode decoderJson) throws Exception
    {
        ObjectNode payload = (ObjectNode)MAPPER.readTree(scalar(connection, """
            SELECT config_json FROM configuration_channel WHERE configuration_id='%s'
            """.formatted(CONVENTIONAL_CHANNEL)));
        payload.put("configurationId", configurationId);
        payload.put("system", name + " System");
        payload.put("site", name + " Site");
        payload.put("name", name);
        payload.remove(List.of("aliasListId", "aliasListName", "radioResolveId", "radresGuid", "radres_guid"));
        if(radioResolveId != null)
        {
            payload.put("radresGuid", radioResolveId);
        }
        payload.set("decodeConfiguration", decoderJson);

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_name,
                radres_guid, auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                frequency_count, recording_enabled, event_logging_enabled, config_json
            )
            SELECT ?, ?, ?, ?, ?, ?, NULL, ?, auto_start, auto_start_order, ?, source_type,
                   primary_frequency_hz, frequency_count, recording_enabled, event_logging_enabled, ?
            FROM configuration_channel
            WHERE configuration_id=?
            """))
        {
            statement.setString(1, configurationId);
            statement.setString(2, channelKind);
            statement.setInt(3, sortOrder);
            statement.setString(4, name + " System");
            statement.setString(5, name + " Site");
            statement.setString(6, name);
            statement.setString(7, radioResolveId);
            statement.setString(8, decoderType);
            statement.setString(9, MAPPER.writeValueAsString(payload));
            statement.setString(10, CONVENTIONAL_CHANNEL);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void populateLegacyDmrAndNxdnDerivedHistory(Connection connection) throws Exception
    {
        try(var statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name, decoder,
                    first_seen_ms, last_seen_ms, primary_frequency_hz, current_control_hz
                ) VALUES
                    (702, 'legacy-dmr-tier3', 'legacy-dmr-site', 1, 3, 'Legacy DMR Tier III', 'DMR',
                     1700000000000, 1700000005000, 451012500, 451012500),
                    (703, 'legacy-nxdn-type-c', 'legacy-nxdn-site', 1, 4, 'Legacy NXDN Type-C', 'NXDN',
                     1700000000000, 1700000005000, 452012500, 452012500),
                    (704, 'legacy-dmr-conventional', NULL, 3, 3, 'Legacy DMR Conventional', 'DMR',
                     1700000000000, 1700000005000, 453012500, NULL)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope(
                    scope_id, scope_token, protocol_code, scope_kind_code, identity_domain_code,
                    first_seen_ms, last_seen_ms
                ) VALUES
                    (702, 'legacy-dmr-scope', 3, 2, 0, 1700000000000, 1700000005000),
                    (703, 'legacy-nxdn-scope', 4, 2, 1, 1700000000000, 1700000005000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_scope_context(context_id, scope_id, first_seen_ms, last_seen_ms)
                VALUES
                    (702, 702, 1700000000000, 1700000005000),
                    (703, 703, 1700000000000, 1700000005000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary(
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    logical_call_count
                ) VALUES
                    (702, 1, 1201, 1700000000000, 1700000005000, 2),
                    (703, 1, 1301, 1700000000000, 1700000005000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_talkgroup_summary(
                    scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms,
                    logical_call_count
                ) VALUES
                    (702, 2201, 1201, 1, 1700000000000, 1700000005000, 2),
                    (703, 2301, 1301, 1, 1700000000000, 1700000005000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_talkgroup_summary(
                    context_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms,
                    call_count, encrypted_count, last_source_radio_id
                ) VALUES (704, 453012500, 1, 1401, 1700000000000, 1700000005000, 2, 1, 2401)
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_radio_summary(
                    context_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms,
                    call_count, source_call_count, group_call_count, encrypted_count, last_talkgroup_id
                ) VALUES (704, 453012500, 1, 2401, 1700000000000, 1700000005000, 2, 2, 2, 1, 1401)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot(
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code,
                    network_id, system_id, site_id, ran, model_code,
                    primary_frequency_hz, current_control_hz, first_seen_ms, last_seen_ms
                ) VALUES
                    ('legacy-dmr-site', '%s', 3, 1, 0, 0, NULL, 1, NULL, 2,
                     451012500, 451012500, 1700000000000, 1700000005000),
                    ('legacy-nxdn-site', '%s', 4, 1, 1, 4, 303, 1, 1, NULL,
                     452012500, 452012500, 1700000000000, 1700000005000)
                """.formatted("d".repeat(64), "e".repeat(64)));
            statement.executeUpdate("""
                INSERT INTO trunked_site_channel_summary(
                    guid, channel_number, inbound_channel_number, timeslot, frequency_hz, role_flags,
                    first_seen_ms, last_seen_ms
                ) VALUES ('legacy-dmr-site', 1, 1, 1, 451012500, 1, 1700000000000, 1700000005000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_neighbor_summary(
                    guid, variant_code, identity_domain_code, network_id, system_id, site_id,
                    channel_number, frequency_hz, status_flags, first_seen_ms, last_seen_ms
                ) VALUES ('legacy-nxdn-site', 1, 1, 4, 303, 2, 2, 452025000, 1,
                          1700000000000, 1700000005000)
                """);
        }
    }

    private static JsonNode dmrDecoder(boolean keepExplicitMode, long downlinkFrequency)
    {
        DecodeConfigDMR decoder = new DecodeConfigDMR();
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(12);
        mapping.setDownlinkFrequency(downlinkFrequency);
        decoder.setTimeslotMap(List.of(mapping));
        ObjectNode json = MAPPER.valueToTree(decoder);
        if(!keepExplicitMode)
        {
            json.remove("channelMode");
        }
        return json;
    }

    private static JsonNode nxdnDecoderWithoutMode()
    {
        ObjectNode json = MAPPER.valueToTree(new DecodeConfigNXDN());
        json.remove("channelMode");
        return json;
    }

    private static String channelMode(Connection connection, String configurationId) throws Exception
    {
        return scalar(connection, """
            SELECT json_extract(config_json, '$.decodeConfiguration.channelMode')
            FROM configuration_channel
            WHERE configuration_id='%s'
            """.formatted(configurationId));
    }

    private static void assertCanonicalUniqueProviderIds(Map<String,String> providerIds)
    {
        assertEquals(providerIds.size(), Set.copyOf(providerIds.values()).size());
        for(String id: providerIds.values())
        {
            assertEquals(id, UUID.fromString(id).toString());
        }
    }

    private static DatabaseMigrationEffect effect(List<DatabaseMigrationEffect> effects,
                                                   DatabaseMigrationEffect.Kind kind, String subject)
    {
        DatabaseMigrationEffect exact = effects.stream()
            .filter(effect -> effect.kind() == kind && effect.subject().equals(subject))
            .findFirst().orElse(null);
        if(exact != null)
        {
            return exact;
        }

        Set<String> splitSubjects;
        if(kind == DatabaseMigrationEffect.Kind.DROP &&
            "unusable administrator-owned configuration".equals(subject))
        {
            splitSubjects = Set.of("unusable application settings, icons, and metadata",
                "unusable Alias, Alias List, and scan-list rows", "unusable saved channel rows",
                "unusable broadcast provider rows", "unusable web accounts and access policies");
        }
        else if(kind == DatabaseMigrationEffect.Kind.DEFAULT && "recoverable configuration values".equals(subject))
        {
            splitSubjects = Set.of("recoverable application settings and metadata",
                "recoverable Alias List and scan-list values", "recoverable saved channel values",
                "recoverable broadcast provider values", "recoverable web account values",
                "Aliases with defaulted optional fields", "SQLite identity high-water marks");
        }
        else
        {
            throw new java.util.NoSuchElementException("Missing migration effect: " + kind + " " + subject);
        }

        long affectedRows = effects.stream()
            .filter(effect -> effect.kind() == kind && splitSubjects.contains(effect.subject()))
            .mapToLong(DatabaseMigrationEffect::affectedRows)
            .sum();
        return new DatabaseMigrationEffect(kind, subject, affectedRows,
            "Test-only aggregate of component-specific migration effects");
    }

    private static Map<Long,Preference> preferences(Connection connection) throws Exception
    {
        Map<Long,Preference> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("""
            SELECT id, preferences_json, preferences_revision, updated_at_ms FROM web_user ORDER BY id
            """))
        {
            while(rows.next())
            {
                result.put(rows.getLong(1), new Preference(rows.getString(2), rows.getLong(3), rows.getLong(4)));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<Long,Credential> credentials(Connection connection) throws Exception
    {
        Map<Long,Credential> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("""
            SELECT id, username, tier, primary_admin, credential_version, password_algorithm,
                   password_iterations, password_derived_key_bits, hex(password_salt), hex(password_hash),
                   password_changed_at_ms, auth_revision, created_at_ms
            FROM web_user
            ORDER BY id
            """))
        {
            while(rows.next())
            {
                long id = rows.getLong(1);
                result.put(id, new Credential(rows.getString(2), rows.getString(3), rows.getInt(4),
                    rows.getInt(5), rows.getString(6), rows.getLong(7), rows.getLong(8), rows.getString(9),
                    rows.getString(10), rows.getLong(11), rows.getLong(12), rows.getLong(13)));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String,Policy> policies(Connection connection) throws Exception
    {
        Map<String,Policy> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("""
            SELECT capability_id, required_tier, updated_at_ms FROM web_access_policy ORDER BY capability_id
            """))
        {
            while(rows.next())
            {
                result.put(rows.getString(1), new Policy(rows.getString(2), rows.getLong(3)));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String,Policy> expectedPolicies(Map<String,Policy> source)
    {
        Map<String,Policy> expected = new LinkedHashMap<>(source);
        Policy siteAccess = expected.remove("site-access");
        Policy systems = expected.remove("systems");
        Policy conventional = expected.remove("conventional");
        if(siteAccess != null)
        {
            expected.put("web-access", siteAccess);
        }

        Policy restrictive = moreRestrictive(systems, conventional);
        if(restrictive != null)
        {
            long updatedAt = Math.max(systems != null ? systems.updatedAtMs() : 0,
                conventional != null ? conventional.updatedAtMs() : 0);
            expected.put("radio", new Policy(restrictive.requiredTier(), updatedAt));
        }
        return Map.copyOf(expected);
    }

    private static Policy moreRestrictive(Policy first, Policy second)
    {
        if(first == null)
        {
            return second;
        }
        if(second == null)
        {
            return first;
        }
        return accessRank(first.requiredTier()) >= accessRank(second.requiredTier()) ? first : second;
    }

    private static int accessRank(String tier)
    {
        return switch(tier)
        {
            case "PUBLIC" -> 0;
            case "USER" -> 1;
            case "ADMIN" -> 2;
            default -> throw new IllegalArgumentException("Unknown access tier " + tier);
        };
    }

    private static void assertPreferencesMigrated(Map<Long,Preference> before, Map<Long,Preference> after)
        throws Exception
    {
        assertEquals(before.keySet(), after.keySet());
        for(Map.Entry<Long,Preference> entry: before.entrySet())
        {
            Preference prior = entry.getValue();
            Preference current = after.get(entry.getKey());
            assertEquals(Format14WebUserPreferencesCodec.migrate(prior.json()), current.json());
            assertEquals(prior.revision() + 1, current.revision());
            assertTrue(current.updatedAtMs() >= prior.updatedAtMs());
        }
    }

    private static Map<String,ApplicationSetting> applicationSettings(Connection connection) throws Exception
    {
        Map<String,ApplicationSetting> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("""
            SELECT key, settings_json, updated_at_ms FROM application_settings ORDER BY key
            """))
        {
            while(rows.next())
            {
                result.put(rows.getString(1), new ApplicationSetting(rows.getString(2), rows.getLong(3)));
            }
        }
        return Map.copyOf(result);
    }

    private static void assertApplicationSettingsMigrated(Map<String,ApplicationSetting> before,
                                                          Map<String,ApplicationSetting> after) throws Exception
    {
        assertEquals(before.keySet(), after.keySet());
        for(Map.Entry<String,ApplicationSetting> entry: before.entrySet())
        {
            ApplicationSetting expected = entry.getValue();
            ApplicationSetting actual = after.get(entry.getKey());
            assertEquals(expected.updatedAtMs(), actual.updatedAtMs(), entry.getKey());
            if(!"portable_java_preferences_v1".equals(entry.getKey()))
            {
                assertEquals(expected.json(), actual.json(), entry.getKey());
                continue;
            }

            JsonNode source = MAPPER.readTree(expected.json());
            JsonNode target = MAPPER.readTree(actual.json());
            JsonNode sourceNowPlaying = source.path("user/io/github/dsheirer/preference/nowplaying");
            JsonNode targetNowPlaying = target.path("user/io/github/dsheirer/preference/nowplaying");
            if(sourceNowPlaying.has("site.settings.revision"))
            {
                assertEquals(sourceNowPlaying.get("site.settings.revision"),
                    targetNowPlaying.get("receiver.settings.revision"));
                ((com.fasterxml.jackson.databind.node.ObjectNode)sourceNowPlaying)
                    .set("receiver.settings.revision", sourceNowPlaying.get("site.settings.revision"));
                ((com.fasterxml.jackson.databind.node.ObjectNode)sourceNowPlaying).remove("site.settings.revision");
            }
            assertEquals(source, target);
            assertFalse(targetNowPlaying.has("site.settings.revision"));
        }
    }

    private static Map<String,String> providerIds(Connection connection) throws Exception
    {
        Map<String,String> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("""
            SELECT json_extract(config_json, '$.name'), configuration_id
            FROM configuration_broadcast_stream ORDER BY id
            """))
        {
            while(rows.next())
            {
                result.put(rows.getString(1), rows.getString(2));
            }
        }
        return Map.copyOf(result);
    }

    private static Set<String> columns(Connection connection, String table) throws Exception
    {
        Set<String> result = new java.util.LinkedHashSet<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "PRAGMA table_info(" + table + ")"))
        {
            while(rows.next())
            {
                result.add(rows.getString("name"));
            }
        }
        return Set.copyOf(result);
    }

    private static List<String> strings(Connection connection, String sql) throws Exception
    {
        List<String> result = new java.util.ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        {
            while(rows.next())
            {
                result.add(rows.getString(1));
            }
        }
        return List.copyOf(result);
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        String value = nullableScalar(connection, sql);
        if(value == null)
        {
            throw new AssertionError("Query returned null: " + sql);
        }
        return value;
    }

    private static String nullableScalar(Connection connection, String sql) throws Exception
    {
        try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            if(!rows.next())
            {
                throw new AssertionError("Query returned no row: " + sql);
            }
            return rows.getString(1);
        }
    }

    private static long number(Connection connection, String sql) throws Exception
    {
        try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            if(!rows.next())
            {
                throw new AssertionError("Query returned no row: " + sql);
            }
            return rows.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(var statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static Connection open(Path database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private record Preference(String json, long revision, long updatedAtMs)
    {
    }

    private record Credential(String username, String tier, int primaryAdmin, int credentialVersion,
                              String algorithm, long iterations, long derivedKeyBits, String saltHex,
                              String verifierHex, long passwordChangedAtMs, long authRevision, long createdAtMs)
    {
    }

    private record Policy(String requiredTier, long updatedAtMs)
    {
    }

    private record ApplicationSetting(String json, long updatedAtMs)
    {
    }
}
