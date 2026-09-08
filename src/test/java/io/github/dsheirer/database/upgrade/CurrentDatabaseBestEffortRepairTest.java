/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ConfigurationChannelProjection;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentDatabaseBestEffortRepairTest
{
    private static final int CONFIGURATION_JSON_LIMIT = 4_194_304;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CHANNEL_ROW_OWNED_JSON_FIELDS = List.of(
        "configurationId", "system", "site", "name", "aliasListId", "aliasListName", "radioResolveId",
        "radresGuid", "radres_guid", "autoStart", "enabled", "autoStartOrder", "order", "channelType");

    @TempDir
    Path mTemporaryFolder;

    @Test
    void currentFormatInspectionRunsWithSqliteWritesDisabled() throws Exception
    {
        Path database = mTemporaryFolder.resolve("query-only-current-inspection.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA query_only=ON");
            assertFalse(CurrentDatabaseBestEffortRepair.inspect(connection).requiresRepair());
        }
    }

    @Test
    void rejectsOversizedCurrentChannelAndProviderDocumentsBeforeJacksonParsing() throws Exception
    {
        Path database = mTemporaryFolder.resolve("oversized-current-configuration.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String oversizedPadding = "x".repeat(CONFIGURATION_JSON_LIMIT);

        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=ON");

            Channel channel = new Channel("Oversized but otherwise valid channel");
            channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(DecoderType.NBFM));
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(155_250_000L);
            channel.setSourceConfiguration(source);
            ObjectNode channelPayload = MAPPER.valueToTree(channel);
            channelPayload.remove(CHANNEL_ROW_OWNED_JSON_FIELDS);
            channelPayload.put("ignoredPadding", oversizedPadding);
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, name, auto_start, decoder_type,
                    address_domain_code, primary_frequency_hz, config_json
                ) VALUES (?, 'CONVENTIONAL', 0, ?, 0, 'NBFM', 0, 155250000, ?)
                """))
            {
                insert.setString(1, channel.getConfigurationId());
                insert.setString(2, channel.getName());
                insert.setString(3, MAPPER.writeValueAsString(channelPayload));
                assertEquals(1, insert.executeUpdate());
            }
            try(var insert = connection.prepareStatement("""
                INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
                VALUES (?, 1, 1)
                """))
            {
                insert.setString(1, channel.getConfigurationId());
                assertEquals(1, insert.executeUpdate());
            }

            RdioScannerConfiguration provider = new RdioScannerConfiguration();
            provider.setName("Oversized but otherwise valid provider");
            provider.setHost("http://127.0.0.1");
            provider.setPort(3000);
            provider.setApiKey("oversized-payload-test");
            provider.setSystemID(1);
            ObjectNode providerPayload = MAPPER.valueToTree(provider);
            providerPayload.remove("configurationId");
            providerPayload.put("ignoredPadding", oversizedPadding);
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream(configuration_id, sort_order, config_json)
                VALUES (?, 0, ?)
                """))
            {
                insert.setString(1, provider.getConfigurationId());
                insert.setString(2, MAPPER.writeValueAsString(providerPayload));
                assertEquals(1, insert.executeUpdate());
            }

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(1, inspection.droppedChannels());
            assertEquals(1, inspection.droppedBroadcastProviders());
            CurrentDatabaseBestEffortRepair.repair(connection);
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM receiver_channel"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
        }
    }

    @Test
    void dropsOnlyCurrentAliasesWithMissingOwnersOrInvalidMatchers() throws Exception
    {
        Path database = mTemporaryFolder.resolve("invalid-current-aliases.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=OFF");
            long p25List = number(connection, "SELECT id FROM alias_list WHERE family='P25' LIMIT 1");
            long nbfmList = number(connection, "SELECT id FROM alias_list WHERE family='NBFM' LIMIT 1");
            long scanList = number(connection, "SELECT id FROM scan_list ORDER BY id LIMIT 1");
            try(var insert = connection.prepareStatement("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value, text_value,
                                  tone_sequence)
                VALUES
                    (99001, ?, 'Keep this Alias', 'TALKGROUP', 'APCO25', 1, NULL, NULL),
                    (99002, ?, 'Wrong family', 'DCS', NULL, NULL, 'N023', NULL),
                    (99003, ?, 'Unknown DCS', 'DCS', NULL, NULL, 'NOT_A_DCS', NULL),
                    (99004, ?, 'Bad tone', 'TONES', NULL, NULL, NULL, 'NOT_A_TONE:1'),
                    (99005, 999999, 'Missing owner', 'TALKGROUP', 'APCO25', 2, NULL, NULL)
                """))
            {
                insert.setLong(1, p25List);
                insert.setLong(2, p25List);
                insert.setLong(3, nbfmList);
                insert.setLong(4, p25List);
                assertEquals(5, insert.executeUpdate());
            }
            try(var insert = connection.prepareStatement("""
                INSERT INTO alias_scan_list_membership(alias_id, scan_list_id)
                VALUES (99001, ?), (99002, ?), (99003, ?), (99004, ?), (99005, ?)
                """))
            {
                for(int parameter = 1; parameter <= 5; parameter++)
                {
                    insert.setLong(parameter, scanList);
                }
                assertEquals(5, insert.executeUpdate());
            }
            execute(connection, "PRAGMA foreign_keys=ON");

            CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(4, inspection.droppedAliases());
            assertEquals(4, effect(CurrentDatabaseBestEffortRepair.effects(inspection),
                DatabaseMigrationEffect.Kind.DROP, "unusable Alias rows").affectedRows());

            CurrentDatabaseBestEffortRepair.repair(connection);
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias WHERE id BETWEEN 99001 AND 99005"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership " +
                "WHERE alias_id BETWEEN 99001 AND 99005"));
            assertEquals("Keep this Alias", text(connection, "SELECT name FROM alias WHERE id=99001"));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void defaultsMalformedOptionalAliasFieldsWithoutDroppingTheAlias() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-optional-alias-field-repair.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=ON");
            long p25List = number(connection,
                "SELECT id FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1");
            long scanList = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, description, group_name, color, icon_name,
                                  stream_as_talkgroup, record_enabled, matcher_type, protocol, value)
                VALUES
                    (97501, %d, 'Repair optional fields', 'description', 'group', 123, 'icon',
                     456, 1, 'TALKGROUP', 'APCO25', 201),
                    (97502, %d, 'Keep optional fields', 'keep description', 'keep group', 321, 'keep icon',
                     654, 1, 'TALKGROUP', 'APCO25', 202)
                """.formatted(p25List, p25List));
            execute(connection, "INSERT INTO alias_scan_list_membership(alias_id, scan_list_id) VALUES " +
                "(97501, " + scanList + ")");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, """
                UPDATE alias SET description=X'01', group_name=X'02', color=1.5, icon_name=X'03',
                                 stream_as_talkgroup=0, record_enabled=2
                WHERE id=97501
                """);
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(0, inspection.droppedAliases());
            assertEquals(1, inspection.defaultedAliasRows());
            assertEquals(1, effect(CurrentDatabaseBestEffortRepair.effects(inspection),
                DatabaseMigrationEffect.Kind.DEFAULT,
                "Aliases with recoverable values").affectedRows());

            CurrentDatabaseBestEffortRepair.repair(connection);

            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias WHERE id=97501"));
            assertEquals(1, number(connection, """
                SELECT description IS NULL AND group_name IS NULL AND color=0 AND icon_name IS NULL
                       AND stream_as_talkgroup IS NULL AND record_enabled=0
                FROM alias WHERE id=97501
                """));
            assertEquals("keep description|keep group|321|keep icon|654|1", text(connection, """
                SELECT description || '|' || group_name || '|' || color || '|' || icon_name || '|' ||
                       stream_as_talkgroup || '|' || record_enabled FROM alias WHERE id=97502
                """));
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM alias_scan_list_membership WHERE alias_id=97501"));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void repairsListsIndependentlyAndSeparatesNamesThatCollideAfterTrimming() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-list-row-repair.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=ON");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            long defaultScanList = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            long p25List = number(connection, "SELECT id FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1");
            execute(connection, """
                INSERT INTO alias_list(id, name, family, unmatched_talkgroup_record_enabled) VALUES
                    (98001, 'Dispatch', 'P25', 0),
                    (98002, ' Dispatch ', 'P25', 99),
                    (98003, 'Broken family', 'NOT_A_FAMILY', 0)
                """);
            execute(connection, """
                INSERT INTO scan_list(id, sort_order, name, description, published, is_default) VALUES
                    (98101, 10, 'Operations', NULL, 1, 0),
                    (98102, -9, ' Operations ', '   ', 77, 77),
                    (98103, 20, '   ', NULL, 1, 0)
                """);
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value) VALUES
                    (98201, 98003, 'Owned by broken list', 'TALKGROUP', 'APCO25', 101),
                    (98202, %d, 'Independent Alias', 'TALKGROUP', 'APCO25', 102)
                """.formatted(p25List));
            execute(connection, """
                INSERT INTO alias_scan_list_membership(alias_id, scan_list_id) VALUES
                    (98201, %d), (98202, 98103)
                """.formatted(defaultScanList));
            execute(connection, """
                INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership(alias_list_id, scan_list_id)
                VALUES (98003, %d)
                """.formatted(defaultScanList));
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(1, inspection.droppedAliasLists());
            assertEquals(0, inspection.droppedScanLists());
            assertEquals(1, inspection.droppedAliases());
            assertEquals(2, inspection.droppedRelationships());
            assertEquals(1, inspection.renamedAliasLists());
            assertEquals(1, inspection.defaultedAliasListPolicies());
            assertEquals(2, inspection.renamedScanLists());
            assertEquals(2, inspection.defaultedScanLists());
            assertEquals(0, inspection.repairedDefaultScanList());

            CurrentDatabaseBestEffortRepair.repair(connection);
            assertEquals("Dispatch|Dispatch (2)", text(connection, """
                SELECT group_concat(name, '|') FROM (SELECT name FROM alias_list WHERE id IN (98001, 98002)
                                                     ORDER BY id)
                """));
            assertEquals(0, number(connection, "SELECT unmatched_talkgroup_record_enabled FROM alias_list " +
                "WHERE id=98002"));
            assertEquals("Operations|Operations (2)", text(connection, """
                SELECT group_concat(name, '|') FROM (SELECT name FROM scan_list WHERE id IN (98101, 98102)
                                                     ORDER BY id)
                """));
            assertEquals(1, number(connection, "SELECT published FROM scan_list WHERE id=98102"));
            assertEquals(0, number(connection, "SELECT is_default FROM scan_list WHERE id=98102"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias_list WHERE id=98003"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias WHERE id=98202"));
            assertEquals("Recovered Scan List 98103", text(connection,
                "SELECT name FROM scan_list WHERE id=98103"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership " +
                "WHERE alias_id=98202 AND scan_list_id=98103"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM scan_list WHERE is_default=1 AND published=1"));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void recoversUnicodeBlankCurrentAliasListNameWithoutBlockingOtherConfiguration() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-unicode-blank-alias-list.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=ON");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, """
                INSERT INTO alias_list(id, name, family, unmatched_talkgroup_record_enabled)
                VALUES (983, replace(printf('%025d', 0), '0', char(8195)) || 'x', 'P25', 0)
                """);
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (984, 983, 'Preserved Unicode-blank owner', 'TALKGROUP', 'APCO25', 103)
                """);
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(1, inspection.renamedAliasLists());

            CurrentDatabaseBestEffortRepair.repair(connection);
            assertEquals("Recovered 983", text(connection,
                "SELECT name FROM alias_list WHERE id=983"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias WHERE id=984"));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void createsOneDefaultScanListWhenEverySavedScanListIsUnusable() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-all-scan-lists-unusable.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=ON");
            long p25List = number(connection,
                "SELECT id FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1");
            long originalDefault = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (99002, %d, 'Recover my membership', 'TALKGROUP', 'APCO25', 123)
                """.formatted(p25List));
            execute(connection, "INSERT INTO alias_scan_list_membership(alias_id, scan_list_id) VALUES " +
                "(99002, " + originalDefault + ")");
            long aliasOwners = number(connection,
                "SELECT COUNT(DISTINCT alias_id) FROM alias_scan_list_membership");
            long aliasListOwners = number(connection, "SELECT COUNT(DISTINCT alias_list_id) FROM " +
                "alias_list_unmatched_talkgroup_scan_list_membership");
            assertTrue(aliasListOwners > 0, "The factory Alias Lists should begin routed to Default");
            execute(connection, "UPDATE sqlite_sequence SET seq=" + Long.MAX_VALUE + " WHERE name='scan_list'");
            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, "UPDATE scan_list SET id=9007199254740991");
            execute(connection, "PRAGMA foreign_keys=ON");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(1, inspection.droppedScanLists());
            assertEquals(1, inspection.repairedDefaultScanList());
            assertEquals(aliasOwners + aliasListOwners, inspection.remappedScanListMemberships());
            assertEquals(0, inspection.droppedRelationships());

            CurrentDatabaseBestEffortRepair.repair(connection);
            assertEquals("Default", text(connection, "SELECT name FROM scan_list WHERE is_default=1"));
            assertEquals(1, number(connection, "SELECT published FROM scan_list WHERE is_default=1"));
            assertEquals(1, number(connection, """
                SELECT COUNT(*) FROM alias_scan_list_membership membership
                JOIN scan_list target ON target.id=membership.scan_list_id AND target.is_default=1
                WHERE membership.alias_id=99002
                """));
            assertEquals(aliasOwners, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership"));
            assertEquals(aliasListOwners, number(connection,
                "SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership"));
            assertEquals(aliasListOwners, number(connection, """
                SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership membership
                JOIN scan_list target ON target.id=membership.scan_list_id AND target.is_default=1
                """));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void remapsOnlyDiscardedDefaultMembershipsWhenAnUnrelatedScanListSurvives() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-discarded-default-membership-recovery.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=ON");
            long p25List = number(connection,
                "SELECT id FROM alias_list WHERE family='P25' ORDER BY id LIMIT 1");
            long oldDefault = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            execute(connection, "INSERT INTO scan_list(sort_order, name, published, is_default) " +
                "VALUES (1, 'Healthy custom list', 1, 0)");
            long customList = number(connection, "SELECT id FROM scan_list WHERE name='Healthy custom list'");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value) VALUES
                    (97301, %d, 'Discarded Default owner', 'TALKGROUP', 'APCO25', 401),
                    (97302, %d, 'Custom-only owner', 'TALKGROUP', 'APCO25', 402)
                """.formatted(p25List, p25List));
            execute(connection, "INSERT INTO alias_scan_list_membership(alias_id, scan_list_id) VALUES " +
                "(97301, " + oldDefault + "), (97302, " + customList + ")");
            long recoverableRows = number(connection, """
                SELECT
                    (SELECT COUNT(*) FROM alias_scan_list_membership WHERE scan_list_id=%d) +
                    (SELECT COUNT(*) FROM alias_list_unmatched_talkgroup_scan_list_membership
                     WHERE scan_list_id=%d)
                """.formatted(oldDefault, oldDefault));
            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, "UPDATE scan_list SET id=9007199254740991 WHERE id=" + oldDefault);
            execute(connection, "PRAGMA foreign_keys=ON");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(recoverableRows, inspection.remappedScanListMemberships());

            CurrentDatabaseBestEffortRepair.repair(connection);

            long newDefault = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership " +
                "WHERE alias_id=97301 AND scan_list_id=" + newDefault));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership " +
                "WHERE alias_id=97302 AND scan_list_id=" + customList));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership " +
                "WHERE alias_id=97302 AND scan_list_id=" + newDefault));
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM scan_list WHERE id=" + customList + " AND is_default=0"));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void repairsProviderBookkeepingWithoutDroppingItsRoutes() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-provider-local-repair.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            RdioScannerConfiguration provider = new RdioScannerConfiguration();
            provider.setName(" ");
            provider.setHost("http://127.0.0.1");
            provider.setPort(3000);
            provider.setApiKey("provider-local-repair");
            provider.setSystemID(1);
            ObjectNode payload = MAPPER.valueToTree(provider);
            payload.put("configurationId", provider.getConfigurationId());
            payload.put("aliasListName", "obsolete duplicate");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream(configuration_id, sort_order, config_json)
                VALUES (?, -8, ?)
                """))
            {
                insert.setString(1, provider.getConfigurationId());
                insert.setString(2, MAPPER.writeValueAsString(payload));
                assertEquals(1, insert.executeUpdate());
            }
            long aliasListId = number(connection, "SELECT id FROM alias_list WHERE family='P25' LIMIT 1");
            long aliasId = 97391;
            execute(connection, "INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value) " +
                "VALUES (" + aliasId + ", " + aliasListId + ", 'Provider route owner', " +
                "'TALKGROUP', 'APCO25', 391)");
            execute(connection, "INSERT INTO alias_broadcast_channel(alias_id, broadcast_configuration_id) " +
                "VALUES (" + aliasId + ", '" + provider.getConfigurationId() + "')");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(0, inspection.droppedBroadcastProviders());
            assertEquals(1, inspection.repairedBroadcastProviders());
            CurrentDatabaseBestEffortRepair.repair(connection);

            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_broadcast_channel"));
            assertEquals(1, number(connection, "SELECT sort_order >= 0 FROM configuration_broadcast_stream"));
            assertEquals(1, number(connection, """
                SELECT json_extract(config_json, '$.configurationId') IS NULL
                       AND json_extract(config_json, '$.aliasListName') IS NULL
                       AND trim(json_extract(config_json, '$.name')) <> ''
                FROM configuration_broadcast_stream
                """));
        }
    }

    @Test
    void canonicalizesRecoverableCurrentConfigurationIdentifiersWithoutDroppingRoutes() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-configuration-uuid-repair.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            Channel channel = new Channel("Keep uppercase-ID channel");
            channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(DecoderType.NBFM));
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(155_475_000L);
            channel.setSourceConfiguration(source);
            String channelId = channel.getConfigurationId();
            String radioResolveId = "12345678-1234-4abc-8def-1234567890ab";
            ObjectNode channelPayload = MAPPER.valueToTree(channel);
            channelPayload.remove(CHANNEL_ROW_OWNED_JSON_FIELDS);
            ConfigurationChannelProjection projection = ConfigurationChannelProjection.from(channel);

            RdioScannerConfiguration provider = new RdioScannerConfiguration();
            provider.setName("Keep uppercase-ID provider");
            provider.setHost("http://127.0.0.1");
            provider.setPort(3000);
            provider.setApiKey("uppercase-provider-id-test");
            provider.setSystemID(1);
            String providerId = provider.getConfigurationId();
            ObjectNode providerPayload = MAPPER.valueToTree(provider);
            providerPayload.remove("configurationId");

            long aliasListId = number(connection, "SELECT id FROM alias_list WHERE family='P25' LIMIT 1");
            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, name, radioresolve_id, auto_start,
                    decoder_type, address_domain_code, primary_frequency_hz, config_json
                ) VALUES (?, 'CONVENTIONAL', 0, ?, ?, 0, ?, ?, ?, ?)
                """))
            {
                insert.setString(1, channelId.toUpperCase(java.util.Locale.ROOT));
                insert.setString(2, channel.getName());
                insert.setString(3, radioResolveId.toUpperCase(java.util.Locale.ROOT));
                insert.setString(4, projection.decoderType());
                insert.setInt(5, projection.addressDomainCode());
                insert.setLong(6, projection.primaryFrequencyHz());
                insert.setString(7, MAPPER.writeValueAsString(channelPayload));
                assertEquals(1, insert.executeUpdate());
            }
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream(configuration_id, sort_order, config_json)
                VALUES (?, 0, ?)
                """))
            {
                insert.setString(1, providerId.toUpperCase(java.util.Locale.ROOT));
                insert.setString(2, MAPPER.writeValueAsString(providerPayload));
                assertEquals(1, insert.executeUpdate());
            }
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (97392, %d, 'Uppercase route owner', 'TALKGROUP', 'APCO25', 392)
                """.formatted(aliasListId));
            execute(connection, """
                INSERT INTO alias_broadcast_channel(alias_id, broadcast_configuration_id)
                VALUES (97392, '%s')
                """.formatted(providerId.toUpperCase(java.util.Locale.ROOT)));
            execute(connection, """
                INSERT INTO alias_list_unmatched_talkgroup_stream(alias_list_id, broadcast_configuration_id)
                VALUES (%d, '%s')
                """.formatted(aliasListId, providerId.toUpperCase(java.util.Locale.ROOT)));
            execute(connection, "PRAGMA ignore_check_constraints=OFF");
            execute(connection, "PRAGMA foreign_keys=ON");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(0, inspection.droppedChannels());
            assertEquals(1, inspection.repairedChannels());
            assertEquals(0, inspection.droppedBroadcastProviders());
            assertEquals(1, inspection.repairedBroadcastProviders());
            assertEquals(2, inspection.repairedBroadcastRoutes());
            assertEquals(0, inspection.droppedRelationships());

            connection.setAutoCommit(false);
            CurrentDatabaseBestEffortRepair.repair(connection);
            connection.commit();
            connection.setAutoCommit(true);

            assertEquals(channelId, text(connection, "SELECT configuration_id FROM configuration_channel"));
            assertEquals(radioResolveId, text(connection, "SELECT radioresolve_id FROM configuration_channel"));
            assertEquals(providerId,
                text(connection, "SELECT configuration_id FROM configuration_broadcast_stream"));
            assertEquals(providerId,
                text(connection, "SELECT broadcast_configuration_id FROM alias_broadcast_channel"));
            assertEquals(providerId, text(connection,
                "SELECT broadcast_configuration_id FROM alias_list_unmatched_talkgroup_stream"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            DatabaseFormatCatalog.requireCurrent(connection);
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void separatesCanonicalUuidCollisionsBeforeRepairingProvidersChannelsAndRoutes() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-configuration-uuid-collisions.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            Channel channel = new Channel("Keep duplicate-spelling channels");
            channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(DecoderType.NBFM));
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(155_500_000L);
            channel.setSourceConfiguration(source);
            String sharedChannelId = channel.getConfigurationId();
            ObjectNode channelPayload = MAPPER.valueToTree(channel);
            channelPayload.remove(CHANNEL_ROW_OWNED_JSON_FIELDS);
            ConfigurationChannelProjection projection = ConfigurationChannelProjection.from(channel);

            RdioScannerConfiguration provider = new RdioScannerConfiguration();
            provider.setName("Keep duplicate-spelling providers");
            provider.setHost("http://127.0.0.1");
            provider.setPort(3000);
            provider.setApiKey("duplicate-provider-id-test");
            provider.setSystemID(1);
            String sharedProviderId = provider.getConfigurationId();
            ObjectNode providerPayload = MAPPER.valueToTree(provider);
            providerPayload.remove("configurationId");
            long aliasListId = number(connection, "SELECT id FROM alias_list WHERE family='P25' LIMIT 1");

            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    id, configuration_id, channel_kind, sort_order, name, auto_start,
                    decoder_type, address_domain_code, primary_frequency_hz, config_json
                ) VALUES (?, ?, 'CONVENTIONAL', ?, ?, 0, ?, ?, ?, ?)
                """))
            {
                for(int index = 0; index < 2; index++)
                {
                    insert.setLong(1, 97501 + index);
                    insert.setString(2, index == 0 ?
                        sharedChannelId.toUpperCase(java.util.Locale.ROOT) : sharedChannelId);
                    insert.setInt(3, index);
                    insert.setString(4, channel.getName());
                    insert.setString(5, projection.decoderType());
                    insert.setInt(6, projection.addressDomainCode());
                    insert.setLong(7, projection.primaryFrequencyHz());
                    insert.setString(8, MAPPER.writeValueAsString(channelPayload));
                    insert.addBatch();
                }
                assertEquals(2, insert.executeBatch().length);
            }
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream(id, configuration_id, sort_order, config_json)
                VALUES (?, ?, ?, ?)
                """))
            {
                for(int index = 0; index < 2; index++)
                {
                    insert.setLong(1, 97511 + index);
                    insert.setString(2, index == 0 ?
                        sharedProviderId.toUpperCase(java.util.Locale.ROOT) : sharedProviderId);
                    insert.setInt(3, index);
                    insert.setString(4, MAPPER.writeValueAsString(providerPayload));
                    insert.addBatch();
                }
                assertEquals(2, insert.executeBatch().length);
            }
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value) VALUES
                    (97521, %1$d, 'Uppercase duplicate route', 'TALKGROUP', 'APCO25', 521),
                    (97522, %1$d, 'Lowercase duplicate route', 'TALKGROUP', 'APCO25', 522)
                """.formatted(aliasListId));
            execute(connection, """
                INSERT INTO alias_broadcast_channel(id, alias_id, broadcast_configuration_id) VALUES
                    (97531, 97521, '%1$s'),
                    (97532, 97522, '%2$s')
                """.formatted(sharedProviderId.toUpperCase(java.util.Locale.ROOT), sharedProviderId));
            execute(connection, "PRAGMA ignore_check_constraints=OFF");
            execute(connection, "PRAGMA foreign_keys=ON");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(2, inspection.repairedChannels());
            assertEquals(2, inspection.repairedBroadcastProviders());
            assertEquals(2, inspection.repairedBroadcastRoutes());
            assertEquals(0, inspection.droppedRelationships());

            connection.setAutoCommit(false);
            CurrentDatabaseBestEffortRepair.repair(connection);
            connection.commit();
            connection.setAutoCommit(true);

            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(2, number(connection,
                "SELECT COUNT(DISTINCT configuration_id) FROM configuration_channel"));
            assertEquals(2, number(connection,
                "SELECT COUNT(*) FROM configuration_channel WHERE configuration_id=lower(configuration_id)"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM configuration_broadcast_stream"));
            assertEquals(2, number(connection,
                "SELECT COUNT(DISTINCT configuration_id) FROM configuration_broadcast_stream"));
            assertEquals(2, number(connection, """
                SELECT COUNT(*) FROM alias_broadcast_channel route
                JOIN configuration_broadcast_stream provider
                  ON provider.configuration_id=route.broadcast_configuration_id
                WHERE route.broadcast_configuration_id=lower(route.broadcast_configuration_id)
                """));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            DatabaseFormatCatalog.requireCurrent(connection);
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void repairsBlankAliasNamesAndInactiveMatcherColumnsInPlace() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-alias-canonical-repair.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            long p25List = number(connection, "SELECT id FROM alias_list WHERE family='P25' LIMIT 1");
            long defaultScanList = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value, min_value, max_value,
                                  text_value)
                VALUES (97401, %d, '   ', 'TALKGROUP', 'APCO25', 501, 1, 2, 'stale')
                """.formatted(p25List));
            execute(connection, "INSERT INTO alias_scan_list_membership(alias_id, scan_list_id) VALUES " +
                "(97401, " + defaultScanList + ")");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(0, inspection.droppedAliases());
            assertEquals(1, inspection.defaultedAliasRows());
            CurrentDatabaseBestEffortRepair.repair(connection);

            assertEquals("Recovered Alias 97401", text(connection, "SELECT name FROM alias WHERE id=97401"));
            assertEquals(1, number(connection, """
                SELECT min_value IS NULL AND max_value IS NULL AND text_value IS NULL
                FROM alias WHERE id=97401
                """));
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM alias_scan_list_membership WHERE alias_id=97401"));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void clearsWrongStorageFromInactiveAliasMatcherFields() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-alias-inactive-storage-repair.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            long p25List = number(connection, "SELECT id FROM alias_list WHERE family='P25' LIMIT 1");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, """
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, numeric_value, text_value,
                                  tone_sequence)
                VALUES (97402, %d, 'Keep status Alias', 'STATUS', 42, 1, 99, X'01')
                """.formatted(p25List));
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(0, inspection.droppedAliases());
            assertEquals(1, inspection.defaultedAliasRows());

            CurrentDatabaseBestEffortRepair.repair(connection);
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias WHERE id=97402"));
            assertEquals(1, number(connection, """
                SELECT protocol IS NULL AND numeric_value=1 AND text_value IS NULL AND tone_sequence IS NULL
                FROM alias WHERE id=97402
                """));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void dropsOnlyMalformedOptionalChannelSubdocuments() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-channel-optional-component-repair.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            Channel channel = new Channel("Keep core channel");
            channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(DecoderType.NBFM));
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(155_350_000L);
            channel.setSourceConfiguration(source);
            ObjectNode payload = MAPPER.valueToTree(channel);
            payload.remove(CHANNEL_ROW_OWNED_JSON_FIELDS);
            ObjectNode invalidRecord = MAPPER.createObjectNode();
            invalidRecord.putArray("recorders").add("NOT_A_RECORDER");
            payload.set("recordConfiguration", invalidRecord);
            ConfigurationChannelProjection projection = ConfigurationChannelProjection.from(channel);
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, system_name, site_name, name, auto_start,
                    decoder_type, address_domain_code, primary_frequency_hz, config_json
                ) VALUES (?, 'CONVENTIONAL', 0, ?, ?, ?, 0, ?, ?, ?, ?)
                """))
            {
                insert.setString(1, channel.getConfigurationId());
                insert.setString(2, channel.getSystem());
                insert.setString(3, channel.getSite());
                insert.setString(4, channel.getName());
                insert.setString(5, projection.decoderType());
                insert.setInt(6, projection.addressDomainCode());
                insert.setLong(7, projection.primaryFrequencyHz());
                insert.setString(8, MAPPER.writeValueAsString(payload));
                assertEquals(1, insert.executeUpdate());
            }

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(0, inspection.droppedChannels());
            assertEquals(1, inspection.repairedChannels());
            CurrentDatabaseBestEffortRepair.repair(connection);
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(1, number(connection, """
                SELECT json_extract(config_json, '$.recordConfiguration') IS NULL
                FROM configuration_channel
                """));
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void remapsAnInvalidScanListTargetToTheExistingDefault() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-existing-default-membership-repair.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            long p25List = number(connection, "SELECT id FROM alias_list WHERE family='P25' LIMIT 1");
            long defaultScanList = number(connection, "SELECT id FROM scan_list WHERE is_default=1");
            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "INSERT INTO scan_list(id, sort_order, name, published, is_default) " +
                "VALUES (9007199254740991, 5, 'Unusable target', 1, 0)");
            execute(connection, "INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value) " +
                "VALUES (97411, " + p25List + ", 'Route me', 'TALKGROUP', 'APCO25', 511)");
            execute(connection, "INSERT INTO alias_scan_list_membership(alias_id, scan_list_id) " +
                "VALUES (97411, 9007199254740991)");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");
            execute(connection, "PRAGMA foreign_keys=ON");

            CurrentDatabaseBestEffortRepair.Inspection inspection =
                CurrentDatabaseBestEffortRepair.inspect(connection);
            assertEquals(1, inspection.remappedScanListMemberships());
            CurrentDatabaseBestEffortRepair.repair(connection);
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM alias_scan_list_membership WHERE alias_id=97411 " +
                "AND scan_list_id=" + defaultScanList));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM scan_list WHERE id=9007199254740991"));
        }
    }

    private static DatabaseMigrationEffect effect(List<DatabaseMigrationEffect> effects,
                                                  DatabaseMigrationEffect.Kind kind, String subject)
    {
        return effects.stream().filter(effect -> effect.kind() == kind && effect.subject().equals(subject))
            .findFirst().orElseThrow();
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }

    private static long number(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
