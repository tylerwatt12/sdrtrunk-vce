package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** Exact populated format 13, derived only through the immutable preceding migration. */
public final class Format13TestDatabase
{
    public static Path create(Path database) throws Exception
    {
        Format12TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            new Format12To13DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 13);
            populateFormat13Relationships(connection);
            if(DatabaseFormatCatalog.inspect(connection).version() != 13)
            {
                throw new IllegalStateException("Populated format-13 fixture is not exact");
            }
        }
        return database;
    }

    private static void populateFormat13Relationships(Connection connection) throws Exception
    {
        long aliasListId;
        try(PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO alias_list (name, family, unmatched_talkgroup_record_enabled)
            VALUES ('Migration Mixed Case', 'P25', 0)
            """))
        {
            insert.executeUpdate();
        }
        try(PreparedStatement query = connection.prepareStatement(
            "SELECT id FROM alias_list WHERE name='Migration Mixed Case'"); ResultSet rows = query.executeQuery())
        {
            if(!rows.next())
            {
                throw new IllegalStateException("Missing format-13 fixture Alias List");
            }
            aliasListId = rows.getLong(1);
        }

        try(PreparedStatement update = connection.prepareStatement("""
            UPDATE configuration_channel
            SET alias_list_name='migration mixed case',
                config_json=json_set(config_json, '$.aliasListName', 'MIGRATION MIXED CASE')
            WHERE configuration_id='11111111-2222-4333-8444-555555555555'
            """))
        {
            if(update.executeUpdate() != 1)
            {
                throw new IllegalStateException("Missing format-13 fixture channel");
            }
        }

        try(PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO configuration_channel_map (sort_order, name, config_json)
            VALUES (13, 'Retired Format 13 Named Map', '{"name":"Retired Format 13 Named Map"}')
            """))
        {
            insert.executeUpdate();
        }

        long aliasId;
        try(PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO alias (alias_list_id, name, color, record_enabled, matcher_type, protocol, value)
            VALUES (?, 'Migration Dispatch', 0, 0, 'TALKGROUP', 'APCO25', 4242)
            """))
        {
            insert.setLong(1, aliasListId);
            insert.executeUpdate();
        }
        try(PreparedStatement query = connection.prepareStatement(
            "SELECT id FROM alias WHERE name='Migration Dispatch'"); ResultSet rows = query.executeQuery())
        {
            if(!rows.next())
            {
                throw new IllegalStateException("Missing format-13 fixture Alias");
            }
            aliasId = rows.getLong(1);
        }

        insertBroadcast(connection, 0, "Primary Migration Feed", "fixture-primary", 7101);
        insertBroadcast(connection, 1, "Secondary Migration Feed", "fixture-secondary", 7102);
        try(PreparedStatement aliasRoute = connection.prepareStatement("""
                INSERT INTO alias_broadcast_channel (alias_id, channel_name) VALUES (?, ?)
                """);
            PreparedStatement unmatchedRoute = connection.prepareStatement("""
                INSERT INTO alias_list_unmatched_talkgroup_stream (alias_list_id, channel_name) VALUES (?, ?)
                """))
        {
            aliasRoute.setLong(1, aliasId);
            aliasRoute.setString(2, "Primary Migration Feed");
            aliasRoute.executeUpdate();
            unmatchedRoute.setLong(1, aliasListId);
            unmatchedRoute.setString(2, "Secondary Migration Feed");
            unmatchedRoute.executeUpdate();
        }

        try(PreparedStatement policies = connection.prepareStatement("""
            INSERT INTO web_access_policy (capability_id, required_tier, updated_at_ms) VALUES (?, ?, ?)
            """))
        {
            policies.setString(1, "systems");
            policies.setString(2, "USER");
            policies.setLong(3, 1_700_000_000_100L);
            policies.addBatch();
            policies.setString(1, "conventional");
            policies.setString(2, "ADMIN");
            policies.setLong(3, 1_700_000_000_200L);
            policies.addBatch();
            policies.executeBatch();
        }

        populateDerivedHistory(connection, aliasListId);
    }

    private static void populateDerivedHistory(Connection connection, long aliasListId) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key, wacn, system_id, first_seen_ms, last_seen_ms)
                VALUES (700, 781824, 101, 1700000000000, 1700000005000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name, alias_list_name, decoder,
                    first_seen_ms, last_seen_ms, system_key, nac, rfss, site, primary_frequency_hz,
                    current_control_hz, alias_list_id
                ) VALUES
                    (700, 'format-13-site', 'format-13-guid', 1, 1, 'Format 13 Site',
                     'Migration Mixed Case', 'P25_PHASE1', 1700000000000, 1700000005000,
                     700, 293, 1, 2, 851012500, 851012500, %d),
                    (701, 'format-13-conventional', NULL, 2, 1, 'Format 13 Conventional',
                     'Migration Mixed Case', 'P25_CONVENTIONAL', 1700000000000, 1700000005000,
                     NULL, NULL, NULL, NULL, 155550000, NULL, %d)
                """.formatted(aliasListId, aliasListId));
            statement.executeUpdate("""
                INSERT INTO conventional_activity_summary(
                    context_id, frequency_hz, timeslot, first_seen_ms, last_seen_ms, call_count,
                    encrypted_count, recorded_count, streamed_count
                ) VALUES (701, 155550000, -1, 1700000000000, 1700000005000, 5, 2, 3, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO logger_status(key, value, updated_at_ms)
                VALUES ('format-13-derived-status', 'must-reset', 1700000005000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot(
                    guid, first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('format-13-guid', 1700000000000, 1700000005000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality(
                    guid, frequency_hz, bucket_start_ms, observed_at_ms, decode_health_pct,
                    valid_frames, invalid_frames
                ) VALUES ('format-13-guid', 851012500, 1699999200000, 1700000005000, 97.5, 195, 5)
                """);
        }
    }

    private static void insertBroadcast(Connection connection, int sortOrder, String name, String apiKey,
                                        int systemId) throws Exception
    {
        BroadcastConfiguration configuration = new RdioScannerConfiguration();
        configuration.setName(name);
        configuration.setHost("http://127.0.0.1");
        configuration.setPort(3000 + sortOrder);
        configuration.setDelay(1000L * sortOrder);
        configuration.setMaximumRecordingAge(600_000L);
        configuration.setEnabled(sortOrder == 0);
        ((RdioScannerConfiguration)configuration).setApiKey(apiKey);
        ((RdioScannerConfiguration)configuration).setSystemID(systemId);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.valueToTree(configuration);
        payload.remove("configurationId");

        try(PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO configuration_broadcast_stream (
                sort_order, name, server_type, enabled, host, port, delay_ms, maximum_recording_age_ms, config_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            insert.setInt(1, sortOrder);
            insert.setString(2, name);
            insert.setString(3, configuration.getBroadcastServerType().name());
            insert.setInt(4, configuration.isEnabled() ? 1 : 0);
            insert.setString(5, configuration.getHost());
            insert.setInt(6, configuration.getPort());
            insert.setLong(7, configuration.getDelay());
            insert.setLong(8, configuration.getMaximumRecordingAge());
            insert.setString(9, mapper.writeValueAsString(payload));
            insert.executeUpdate();
        }
    }
}
