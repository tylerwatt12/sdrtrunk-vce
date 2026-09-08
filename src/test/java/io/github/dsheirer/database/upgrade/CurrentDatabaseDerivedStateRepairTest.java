/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentDatabaseDerivedStateRepairTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CHANNEL_ROW_OWNED_JSON_FIELDS = List.of(
        "configurationId", "system", "site", "name", "aliasListId", "aliasListName", "radioResolveId",
        "radresGuid", "radres_guid", "autoStart", "enabled", "autoStartOrder", "order", "channelType");

    @TempDir
    Path mTemporaryFolder;

    @Test
    void resetsTheWholeDerivedComponentForOneCheckViolationAndPreservesTheChannel() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-derived-check.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String configurationId;

        try(Connection connection = open(database))
        {
            assertEquals(42, CurrentDatabaseDerivedStateRepair.REPRODUCIBLE_TABLES.size());
            for(String table: CurrentDatabaseDerivedStateRepair.REPRODUCIBLE_TABLES)
            {
                assertEquals(1, number(connection,
                    "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name='" + table + "'"), table);
            }
            configurationId = insertValidChannel(connection, "Preserve this channel");
            try(var insert = connection.prepareStatement("""
                INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
                VALUES (?, 1, 1)
                """))
            {
                insert.setString(1, configurationId);
                assertEquals(1, insert.executeUpdate());
            }
            execute(connection, """
                INSERT INTO statistics_status(key, value, updated_at_ms)
                VALUES ('preserved-only-when-valid', 'value', 1)
                """);
            execute(connection, """
                UPDATE database_metadata SET value='1', updated_at_ms=1
                WHERE key IN (
                    'conventional_call_output_metrics_started_at_ms',
                    'trunked_logical_call_metrics_started_at_ms',
                    'radio_system_metrics_started_at_ms')
                """);
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "UPDATE receiver_channel SET first_seen_ms=0 WHERE configuration_id='" +
                configurationId + "'");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");

            CurrentDatabaseDerivedStateRepair.Inspection inspection =
                CurrentDatabaseDerivedStateRepair.inspect(connection);
            assertTrue(inspection.requiresRepair());
            assertEquals(1, inspection.damagedTables());
            assertEquals(2, inspection.resetRows());
            assertEquals(2,
                CurrentDatabaseDerivedStateRepair.preflight(inspection).effects().getFirst().affectedRows());

            CurrentDatabaseDerivedStateRepair.Inspection repaired =
                CurrentDatabaseDerivedStateRepair.repair(connection);
            assertEquals(2, repaired.resetRows());
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM receiver_channel"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM statistics_status"));
            assertEquals(0, number(connection,
                "SELECT COUNT(*) FROM sqlite_sequence WHERE name='receiver_channel'"));
            assertEquals(3, number(connection, """
                SELECT COUNT(*) FROM database_metadata
                WHERE key IN (
                    'conventional_call_output_metrics_started_at_ms',
                    'trunked_logical_call_metrics_started_at_ms',
                    'radio_system_metrics_started_at_ms')
                  AND CAST(value AS INTEGER) > 1 AND updated_at_ms > 1
                """));
            assertEquals("ok", text(connection, "PRAGMA quick_check"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals(0, CurrentDatabaseDerivedStateRepair.inspect(connection).resetRows());
            new ConfigurationRepository(database).load(connection);
        }
    }

    @Test
    void resetsDerivedStateAndEveryDerivedAllocatorWhenOneSequenceIsExhausted() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-derived-exhausted-sequence.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database))
        {
            String configurationId = insertValidChannel(connection, "Preserve channel across allocator reset");
            try(var insert = connection.prepareStatement("""
                INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
                VALUES (?, 1, 1)
                """))
            {
                insert.setString(1, configurationId);
                assertEquals(1, insert.executeUpdate());
            }
            execute(connection, """
                INSERT INTO sqlite_sequence(name, seq) VALUES
                    ('radio_system', 10),
                    ('radio_system_identity_summary', 11),
                    ('receiver_activity_event', 12)
                """);
            execute(connection, "UPDATE sqlite_sequence SET seq=" + Long.MAX_VALUE +
                " WHERE name='receiver_channel'");

            CurrentDatabaseDerivedStateRepair.Inspection inspection =
                CurrentDatabaseDerivedStateRepair.inspect(connection);
            assertTrue(inspection.requiresRepair());
            assertEquals(1, inspection.damagedTables());
            assertEquals(1, inspection.resetRows());

            CurrentDatabaseDerivedStateRepair.repair(connection);

            assertEquals(1, number(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM receiver_channel"));
            assertEquals(0, number(connection, """
                SELECT COUNT(*) FROM sqlite_sequence
                WHERE name IN ('radio_system', 'radio_system_identity_summary',
                               'receiver_channel', 'receiver_activity_event')
                """));
            assertEquals(0, CurrentDatabaseDerivedStateRepair.inspect(connection).damagedTables());

            try(var insert = connection.prepareStatement("""
                INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
                VALUES (?, 2, 2)
                """))
            {
                insert.setString(1, configurationId);
                assertEquals(1, insert.executeUpdate());
            }
            assertEquals(1, number(connection,
                "SELECT id FROM receiver_channel WHERE configuration_id='" + configurationId + "'"));
        }
    }

    @Test
    void resetsDerivedStateForAnOrphanWithoutAdmittingUnknownForeignKeyDamage() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-derived-foreign-key.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String missingConfiguration = UUID.randomUUID().toString();

        try(Connection connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, """
                INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
                VALUES ('%s', 1, 1)
                """.formatted(missingConfiguration));
            execute(connection, "PRAGMA foreign_keys=ON");

            CurrentDatabaseBestEffortRepair.requireOnlyRepairableForeignKeys(connection);
            CurrentDatabaseDerivedStateRepair.Inspection inspection =
                CurrentDatabaseDerivedStateRepair.inspect(connection);
            assertEquals(1, inspection.damagedTables());
            assertEquals(1, inspection.resetRows());

            CurrentDatabaseDerivedStateRepair.repair(connection);
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM receiver_channel"));
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void repairsOnlyInvalidMetricBoundariesWithoutClearingHealthyDerivedState() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-derived-boundaries.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database))
        {
            execute(connection, """
                INSERT INTO statistics_status(key, value, updated_at_ms)
                VALUES ('keep-healthy-derived-state', 'value', 1)
                """);
            execute(connection, """
                UPDATE database_metadata SET value='777', updated_at_ms=888
                WHERE key='radio_system_metrics_started_at_ms'
                """);
            execute(connection, """
                UPDATE database_metadata SET value='0', updated_at_ms=1
                WHERE key='conventional_call_output_metrics_started_at_ms'
                """);
            execute(connection, """
                DELETE FROM database_metadata
                WHERE key='trunked_logical_call_metrics_started_at_ms'
                """);

            CurrentDatabaseDerivedStateRepair.Inspection inspection =
                CurrentDatabaseDerivedStateRepair.inspect(connection);
            assertTrue(inspection.requiresRepair());
            assertEquals(0, inspection.damagedTables());
            assertEquals(0, inspection.resetRows());
            assertEquals(2, inspection.invalidMetricBoundaryKeys().size());
            assertEquals(2,
                CurrentDatabaseDerivedStateRepair.preflight(inspection).effects().get(1).affectedRows());

            CurrentDatabaseDerivedStateRepair.repair(connection);
            assertEquals(1, number(connection,
                "SELECT COUNT(*) FROM statistics_status WHERE key='keep-healthy-derived-state'"));
            assertEquals("777", text(connection, """
                SELECT value FROM database_metadata
                WHERE key='radio_system_metrics_started_at_ms'
                """));
            assertEquals(2, number(connection, """
                SELECT COUNT(*) FROM database_metadata
                WHERE key IN (
                    'conventional_call_output_metrics_started_at_ms',
                    'trunked_logical_call_metrics_started_at_ms')
                  AND CAST(value AS INTEGER) > 1 AND updated_at_ms > 1
                """));
            assertEquals(0,
                CurrentDatabaseDerivedStateRepair.inspect(connection).invalidMetricBoundaryKeys().size());
        }
    }

    private static String insertValidChannel(Connection connection, String name) throws Exception
    {
        Channel channel = new Channel(name);
        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(DecoderType.NBFM));
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(155_250_000L);
        channel.setSourceConfiguration(source);
        ObjectNode payload = MAPPER.valueToTree(channel);
        payload.remove(CHANNEL_ROW_OWNED_JSON_FIELDS);
        try(var insert = connection.prepareStatement("""
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, name, auto_start, decoder_type,
                address_domain_code, primary_frequency_hz, config_json
            ) VALUES (?, 'CONVENTIONAL', 0, ?, 0, 'NBFM', 0, 155250000, ?)
            """))
        {
            insert.setString(1, channel.getConfigurationId());
            insert.setString(2, name);
            insert.setString(3, MAPPER.writeValueAsString(payload));
            assertEquals(1, insert.executeUpdate());
        }
        return channel.getConfigurationId();
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
