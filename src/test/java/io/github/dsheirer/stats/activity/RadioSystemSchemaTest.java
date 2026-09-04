/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Exact constraints and bounded lifecycle coverage for current radio-system summaries. */
class RadioSystemSchemaTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void rejectsInvalidSystemAndSummaryFacts() throws Exception
    {
        try(Connection connection = open())
        {
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO radio_system(system_key, protocol_code, address_domain_code,
                    first_seen_ms, last_seen_ms)
                VALUES ('dmr:channel:123e4567-e89b-42d3-a456-426614174000', 3, 0, 0, 1)
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO radio_system(system_key, protocol_code, address_domain_code,
                    p25_wacn, p25_system_id, first_seen_ms, last_seen_ms)
                VALUES ('p25:bee00:3aa', 1, 0, 781824, 937, 1000, 1000)
                """));

            long radioSystemId = seedSystem(connection);
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO radio_system_identity_summary(
                    radio_system_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms)
                VALUES (%d, 1, 91, 0, 1000)
                """.formatted(radioSystemId)));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO radio_system_identity_summary(
                    radio_system_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms,
                    logical_call_count)
                VALUES (%d, 1, 91, 1000, 1000, -1)
                """.formatted(radioSystemId)));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_radio_affiliation(radio_system_id, radio_id, talkgroup_id, confirmed_at_ms)
                VALUES (%d, 101, 91, 0)
                """.formatted(radioSystemId)));
        }
    }

    @Test
    void retentionDeletesExpiredFactsWithoutUnboundedSystemGrowth() throws Exception
    {
        try(Connection connection = open())
        {
            long radioSystemId = seedSystem(connection);
            execute(connection, """
                INSERT INTO radio_system_identity_summary(
                    radio_system_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms)
                VALUES (%d, 1, 91, 1000, 1000)
                """.formatted(radioSystemId));
            execute(connection, """
                INSERT INTO trunked_radio_talkgroup_summary(
                    radio_system_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms)
                VALUES (%d, 101, 91, 1, 1000, 1000)
                """.formatted(radioSystemId));

            assertTrue(ReceiverActivitySchema.deleteOlderThan(connection, 2_000) >= 2);
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM radio_system_identity_summary WHERE radio_system_id=" + radioSystemId));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM trunked_radio_talkgroup_summary WHERE radio_system_id=" + radioSystemId));
            //The configured channel still owns this provisional DMR system, so cleanup keeps exactly one owner row.
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE id=" + radioSystemId));
        }
    }

    @Test
    void configuredSummaryCapsRemainFinite()
    {
        assertTrue(RadioSystemSchema.MAX_IDENTITIES_PER_SYSTEM > 0);
        assertTrue(RadioSystemSchema.MAX_ZERO_LOCAL_FQ_TALKGROUPS_PER_SYSTEM > 0);
        assertTrue(RadioSystemSchema.MAX_RELATIONSHIPS_PER_SYSTEM > 0);
    }

    private static Connection open() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        SdrTrunkDatabaseSchema.create(connection);
        ReceiverActivitySchema.create(connection);
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json
            ) VALUES (
                '%s', 'TRUNKED', 0, 'DMR system', 'DMR site', 'Control',
                'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 0, 'DMR', 461125000, '{}'
            )
            """.formatted(CONFIGURATION_ID));
        return connection;
    }

    private static long seedSystem(Connection connection) throws Exception
    {
        execute(connection, """
            INSERT INTO radio_system(system_key, protocol_code, address_domain_code, first_seen_ms, last_seen_ms)
            VALUES ('dmr:channel:%s', 3, 0, 1000, 1000)
            """.formatted(CONFIGURATION_ID));
        long systemId = scalar(connection, "SELECT id FROM radio_system");
        execute(connection, """
            INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms, radio_system_id)
            VALUES ('%s', 1000, 1000, %d)
            """.formatted(CONFIGURATION_ID, systemId));
        return systemId;
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
