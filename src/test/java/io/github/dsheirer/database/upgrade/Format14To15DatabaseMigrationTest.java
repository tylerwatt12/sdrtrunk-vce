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
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
            List<DatabaseMigrationEffect> preflight = new Format14To15DatabaseMigration().validateSource(connection);
            assertEquals(6, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "receiver activity and call history").affectedRows());
            assertEquals(1, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "learned site observations").affectedRows());
            assertEquals(1, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "signal-quality observations").affectedRows());
            assertEquals(4, effect(preflight, DatabaseMigrationEffect.Kind.RESET,
                "radio-system and receiver-channel identity cache").affectedRows());
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
    void refusesUnresolvedOrMismatchedAliasListsWithoutWriting() throws Exception
    {
        assertRefused("unresolved-alias.sqlite", """
            UPDATE configuration_channel
            SET alias_list_name='Missing Alias',
                config_json=json_set(config_json, '$.aliasListName', 'Missing Alias')
            WHERE configuration_id='%s'
            """.formatted(MIXED_CASE_CHANNEL), "missing Alias List");
        assertRefused("mismatched-alias.sqlite", """
            UPDATE configuration_channel
            SET config_json=json_set(config_json, '$.aliasListName', 'Default P25')
            WHERE configuration_id='%s'
            """.formatted(MIXED_CASE_CHANNEL), "does not match");
        assertRefused("mismatched-alias-id.sqlite", """
            UPDATE configuration_channel
            SET config_json=json_set(config_json, '$.aliasListId', 999999)
            WHERE configuration_id='%s'
            """.formatted(MIXED_CASE_CHANNEL), "Alias List ID row relationship");
    }

    @Test
    void refusesConflictingAutoStartAliasesWithoutWriting() throws Exception
    {
        assertRefused("conflicting-auto-start.sqlite", """
            UPDATE configuration_channel
            SET auto_start=1,
                config_json=json_set(config_json, '$.autoStart', json('true'), '$.enabled', json('false'))
            WHERE configuration_id='%s'
            """.formatted(MIXED_CASE_CHANNEL), "JSON field enabled does not match");
        assertRefused("conflicting-auto-start-order.sqlite", """
            UPDATE configuration_channel
            SET auto_start_order=1,
                config_json=json_set(config_json, '$.autoStartOrder', 1, '$.order', 2)
            WHERE configuration_id='%s'
            """.formatted(MIXED_CASE_CHANNEL), "JSON field order does not match");
    }

    @Test
    void refusesAdministratorRowsThatCannotSatisfyTheStrictFormat15Schema() throws Exception
    {
        assertRefused("invalid-metadata-time.sqlite", """
            UPDATE database_metadata SET updated_at_ms=0 WHERE key='database_format_version'
            """, "update time must be a positive integer");
        assertRefused("invalid-application-setting.sqlite", """
            UPDATE application_settings SET settings_json='not-json'
            WHERE key='format-4-preserve-sentinel'
            """, "is not valid JSON");
        assertRefused("invalid-icon-document.sqlite", """
            INSERT OR REPLACE INTO application_icons(key, icons_json, updated_at_ms)
            VALUES ('invalid-icons', '[]', 1700000000000)
            """, "must be a JSON object");
        assertRefused("missing-default-scan-list.sqlite", """
            UPDATE scan_list SET is_default=0 WHERE is_default=1
            """, "exactly one Default scan list");
        assertRefused("fractional-scan-order.sqlite", """
            UPDATE scan_list SET sort_order=1.5 WHERE is_default=1
            """, "sort_order is not stored as an integer");
        assertRefused("fractional-alias-color.sqlite", """
            UPDATE alias SET color=1.5 WHERE name='Migration Dispatch'
            """, "color is not stored as an integer");
        assertRefused("oversized-alias-list-name.sqlite", """
            UPDATE alias_list SET name='12345678901234567890123456' WHERE name='Migration Mixed Case'
            """, "name outside 1 through 25 characters");
        assertRefused("missing-alias-name.sqlite", """
            UPDATE alias SET name=NULL WHERE name='Migration Dispatch'
            """, "cannot be null");
        assertRefused("mixed-alias-payload.sqlite", """
            UPDATE alias SET min_value=1 WHERE name='Migration Dispatch'
            """, "matcher fields do not match its type");
        assertRefused("oversized-status-value.sqlite", """
            UPDATE alias
            SET matcher_type='STATUS', protocol=NULL, value=NULL, min_value=NULL, max_value=NULL,
                text_value=NULL, numeric_value=256, tone_sequence=NULL
            WHERE name='Migration Dispatch'
            """, "status value is outside 0 through 255");
        assertRefused("blank-esn-value.sqlite", """
            UPDATE alias
            SET matcher_type='ESN', protocol=NULL, value=NULL, min_value=NULL, max_value=NULL,
                text_value=' ', numeric_value=NULL, tone_sequence=NULL
            WHERE name='Migration Dispatch'
            """, "matcher fields do not match its type");
        assertRefused("negative-channel-order.sqlite", """
            UPDATE configuration_channel SET sort_order=-1 WHERE configuration_id='%s'
            """.formatted(MIXED_CASE_CHANNEL), "negative sort order");
        assertRefused("text-password-salt.sqlite", """
            UPDATE web_user SET password_salt=CAST('0123456789abcdef' AS TEXT) WHERE primary_admin=1
            """, "password salt must use SQLite blob storage");
        assertRefused("invalid-username-characters.sqlite", """
            INSERT INTO web_user (
                username, tier, primary_admin, credential_version, password_algorithm,
                password_iterations, password_derived_key_bits, password_salt, password_hash,
                password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                created_at_ms, updated_at_ms
            )
            SELECT '!invalid', 'USER', 0, credential_version, password_algorithm,
                   password_iterations, password_derived_key_bits, password_salt, password_hash,
                   password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                   created_at_ms, updated_at_ms
            FROM web_user WHERE primary_admin=1
            """, "has an invalid username");
        assertRefused("fractional-account-time.sqlite", """
            UPDATE web_user SET created_at_ms=1.5 WHERE primary_admin=1
            """, "account creation time must use SQLite integer storage");
        assertRefused("fractional-policy-time.sqlite", """
            UPDATE web_access_policy SET updated_at_ms=1700000000000.5 WHERE capability_id='dashboard'
            """, "access-policy update time must use SQLite integer storage");
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
    void refusesFutureReceiverSettingsRevisionInTheFormat14Source() throws Exception
    {
        assertRefused("mixed-receiver-settings-revision.sqlite", """
            UPDATE application_settings
            SET settings_json=json_set(settings_json,
                '$."user/io/github/dsheirer/preference/nowplaying"."receiver.settings.revision"', '7')
            WHERE key='portable_java_preferences_v1'
            """, "unexpected settings revision key");
    }

    @Test
    void refusesUnresolvedOrAmbiguousNameBasedBroadcastRoutesWithoutWriting() throws Exception
    {
        assertRefused("missing-provider.sqlite", """
            UPDATE alias_broadcast_channel SET channel_name='Missing Provider'
            """, "missing broadcast provider");
        assertRefused("ambiguous-provider.sqlite", """
            UPDATE configuration_broadcast_stream
            SET name='Primary Migration Feed',
                config_json=json_set(config_json, '$.name', 'Primary Migration Feed')
            WHERE name='Secondary Migration Feed'
            """, "ambiguous broadcast provider");
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


    private void assertRefused(String filename, String mutation, String message) throws Exception
    {
        Path database = Format14TestDatabase.create(mTemporaryFolder.resolve(filename));
        try(Connection connection = open(database))
        {
            execute(connection, mutation);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            SQLException exception = assertThrows(SQLException.class,
                () -> new Format14To15DatabaseMigration().validateSource(connection), filename);
            assertTrue(exception.getMessage().contains(message), filename + ": " + exception.getMessage());
            assertThrows(SQLException.class, () -> new Format14To15DatabaseMigration().migrate(connection), filename);
            assertEquals("14", scalar(connection, """
                SELECT value FROM database_metadata WHERE key='database_format_version'
                """));
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
            assertTrue(columns(connection, "configuration_channel").contains("alias_list_name"));
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
        return effects.stream().filter(effect -> effect.kind() == kind && effect.subject().equals(subject))
            .findFirst().orElseThrow();
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
