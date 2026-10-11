/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.upgrade.DatabaseMigrationChain;
import io.github.dsheirer.database.upgrade.Format46TestDatabase;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P25WuidTimingPersistenceTest
{
    @TempDir Path mTemporaryFolder;
    private static final long SYSTEM = 5_000_000_000L;
    private static final long CHANNEL = 5_000_000_000L;
    private static final P25SubscriberIdentity SUBSCRIBER = new P25SubscriberIdentity(0xABCDE, 0x123, 12345);
    private static final RadioSystemSchema.RadioSystem RADIO_SYSTEM = new RadioSystemSchema.RadioSystem(
        SYSTEM, 1, TrunkedIdentityDomain.STANDARD, "p25:bee00:3a9", 0xBEE00, 0x3A9,
        null, null, null, null, 1000);

    @Test
    void timingEvidenceAndSourceAlwaysComeFromOneNewestWinningObservation() throws Exception
    {
        Path database = Format46TestDatabase.create(mTemporaryFolder.resolve("timing.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            observe(connection, 20_000, CHANNEL + 1, ReceiverActivityRecords.RadioPresenceEvidence.AFFILIATION,
                240, 15_000_000);
            assertEquals(List.of("20000", "2", "5000000001", "240", "15000000"), latest(connection));

            // Older evidence may add a historical count, but cannot supply any field of the latest observation.
            observe(connection, 10_000, CHANNEL, ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION,
                0, Long.MAX_VALUE);
            assertEquals(List.of("20000", "2", "5000000001", "240", "15000000"), latest(connection));

            // Existing deterministic equal-time evidence and source priority also chooses the timing fields.
            observe(connection, 20_000, CHANNEL + 1, ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION,
                300, 18_000_000);
            assertEquals(List.of("20000", "1", "5000000001", "300", "18000000"), latest(connection));
            observe(connection, 20_000, CHANNEL, ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION,
                480, 29_000_000);
            assertEquals(List.of("20000", "1", "5000000000", "480", "29000000"), latest(connection));
            observe(connection, 20_000, CHANNEL + 1, ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION,
                600, 36_000_000);
            assertEquals(List.of("20000", "1", "5000000000", "480", "29000000"), latest(connection));

            observe(connection, 30_000, CHANNEL + 1, ReceiverActivityRecords.RadioPresenceEvidence.AFFILIATION,
                null, 16_230_000);
            assertEquals(java.util.Arrays.asList("30000", "2", "5000000001", null, "16230000"), latest(connection),
                "Fallback expiry is retained without inventing an advertised lifetime");
            observe(connection, 40_000, CHANNEL, ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION,
                0, Long.MAX_VALUE);
            assertEquals(List.of("40000", "1", "5000000000", "0", Long.toString(Long.MAX_VALUE)), latest(connection));

            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT registration_count,affiliation_count,first_observed_ms,last_registration_ms,last_affiliation_ms
                FROM p25_wuid_assignment_observation_summary WHERE working_id=777
                """))
            {
                assertTrue(rows.next());
                assertEquals(5, rows.getInt(1));
                assertEquals(2, rows.getInt(2));
                assertEquals(10_000, rows.getLong(3));
                assertEquals(40_000, rows.getLong(4));
                assertEquals(30_000, rows.getLong(5));
            }
            ReceiverActivitySchema.validate(connection);
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
            connection.commit();
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(List.of("40000", "1", "5000000000", "0", Long.toString(Long.MAX_VALUE)), latest(connection),
                "Closing and reopening retains history without a live assignment registry");
        }
    }

    @Test
    void ownerAndRetentionReadsStillUseExistingIndexes() throws Exception
    {
        Path database = Format46TestDatabase.create(mTemporaryFolder.resolve("plans.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            assertTrue(scalar(connection, """
                EXPLAIN QUERY PLAN SELECT working_id,last_advertised_lease_minutes,last_expires_at_ms
                FROM p25_wuid_assignment_observation_summary
                WHERE radio_system_id=5000000000 ORDER BY last_observed_ms DESC LIMIT 50
                """).contains("idx_p25_wuid_assignment_observation_system_time"));
            assertTrue(scalar(connection, """
                EXPLAIN QUERY PLAN SELECT radio_system_id,working_id,p25_subscriber_identity_id
                FROM p25_wuid_assignment_observation_summary WHERE last_observed_ms<10000
                ORDER BY last_observed_ms LIMIT 256
                """).contains("idx_p25_wuid_assignment_observation_retention"));
        }
    }

    private static void observe(Connection connection, long observedAt, long channel,
                                ReceiverActivityRecords.RadioPresenceEvidence evidence,
                                Integer advertised, long expiry) throws Exception
    {
        assertTrue(RadioSystemSchema.recordP25WuidObservation(connection, RADIO_SYSTEM, channel, observedAt,
            new ReceiverActivityRecords.P25WuidObservation(0xBEE00, 0x3A9, 777, SUBSCRIBER, expiry, evidence,
                advertised), 500_000));
    }

    private static List<String> latest(Connection connection) throws Exception
    {
        List<String> values = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT last_observed_ms,last_evidence_code,last_channel_id,last_advertised_lease_minutes,last_expires_at_ms
            FROM p25_wuid_assignment_observation_summary WHERE working_id=777
            """))
        {
            assertTrue(rows.next());
            for(int column = 1; column <= 5; column++) values.add(rows.getString(column));
            assertFalse(rows.next());
        }
        return values;
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getString(sql.startsWith("EXPLAIN") ? 4 : 1);
        }
    }
}
