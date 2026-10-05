/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.Function;

/** Regression coverage for the single bounded routine-retention path. */
class ReceiverActivityRetentionTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void interruptedDeletePreservesCommittedTasksAndReleasesPreferencesWriter(@TempDir Path temporary)
        throws Exception
    {
        String databaseUrl = "jdbc:sqlite:" + temporary.resolve("retention.sqlite");
        try(Connection connection = open(databaseUrl);
            Connection preferencesConnection = DriverManager.getConnection(databaseUrl))
        {
            seedChannel(connection);
            seedUser(connection);
            execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                VALUES (1, 1, 4)
                """);
            execute(connection, """
                INSERT INTO conventional_activity_bucket(channel_id, frequency_hz, timeslot, bucket_start_ms)
                VALUES (1, 450000001, 1, 1), (1, 450000002, 1, 1), (1, 450000003, 1, 1)
                """);
            execute(connection, """
                INSERT INTO p25_site_snapshot(channel_id, first_seen_ms, last_seen_ms)
                VALUES (1, 1, 10000000)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel_summary(
                    channel_id, channel_key, first_seen_ms, last_seen_ms, observation_count)
                VALUES (1, '1-1', 1, 1, 1)
                """);
            execute(connection, "CREATE TABLE retention_test_work(value INTEGER NOT NULL)");
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 4000)
                INSERT INTO retention_test_work SELECT value FROM n
                """);

            AtomicLong nanoTime = new AtomicLong();
            AtomicInteger triggeredDeletes = new AtomicInteger();
            Function.create(connection, "retention_test_expire", new Function()
            {
                @Override
                protected void xFunc() throws SQLException
                {
                    if(triggeredDeletes.incrementAndGet() >= 2)
                    {
                        nanoTime.set(ReceiverActivityRetention.MAXIMUM_TASK_NANOSECONDS + 1);
                    }
                    result(0);
                }
            });
            execute(connection, """
                CREATE TEMP TRIGGER retention_test_slow_delete BEFORE DELETE ON conventional_activity_bucket
                BEGIN
                    SELECT retention_test_expire();
                    SELECT sum(value) FROM retention_test_work;
                END
                """);
            ReceiverActivitySchema.updateStatus(connection, ReceiverActivityRetention.CURSOR_STATUS_KEY, "1");

            ReceiverActivityRetention.Pass interrupted = ReceiverActivityRetention.runPass(connection,
                3_700_000, nanoTime::get);

            assertEquals(2, triggeredDeletes.get(), "the second row interrupts an already-started DELETE");
            assertEquals(1, interrupted.deletedRows(), "the earlier event task remains committed");
            assertTrue(interrupted.moreWorkLikely());
            assertEquals(9, interrupted.nextCursor(), "resume after the interrupted conventional bucket task");
            assertEquals(interrupted.nextCursor(), ReceiverActivitySchema.readStatusLong(connection,
                ReceiverActivityRetention.CURSOR_STATUS_KEY));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(3, scalar(connection, "SELECT count(*) FROM conventional_activity_bucket"),
                "interruption rolls back the whole current statement, including its first deleted row");
            assertTrue(connection.getAutoCommit());

            // This UPDATE crosses the progress-callback threshold while the clock still exceeds the deadline.
            execute(connection, """
                UPDATE web_user
                SET preferences_json=json_object('retentionTest', (SELECT sum(value) FROM retention_test_work)),
                    preferences_revision=preferences_revision+1, updated_at_ms=2
                WHERE username='retention-test'
                """);
            assertEquals(2, scalar(connection, "SELECT preferences_revision FROM web_user"),
                "the progress handler must be cleared before the connection is reused");
            execute(preferencesConnection, """
                UPDATE web_user SET preferences_json='{"theme":"dark"}',
                    preferences_revision=preferences_revision+1, updated_at_ms=3
                WHERE username='retention-test'
                """);
            assertEquals(3, scalar(connection, "SELECT preferences_revision FROM web_user"),
                "the interrupted statement must release the database writer lock");

            nanoTime.set(0);
            ReceiverActivityRetention.Pass following = ReceiverActivityRetention.runPass(connection,
                3_700_000, nanoTime::get);
            assertEquals(1, following.deletedRows(), "the next pass reaches the later P25 task first");
            assertTrue(following.moreWorkLikely());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM p25_site_channel_summary"));
            assertEquals(3, scalar(connection, "SELECT count(*) FROM conventional_activity_bucket"));

            execute(connection, "DROP TRIGGER retention_test_slow_delete");
            ReceiverActivityRetention.Pass drained = ReceiverActivityRetention.runPass(connection,
                3_700_000, () -> 0L);
            assertEquals(3, drained.deletedRows());
            assertFalse(drained.moreWorkLikely());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM conventional_activity_bucket"));
        }
    }

    @Test
    void callerTransactionIsRejectedBeforeRetentionWritesOrCommits(@TempDir Path temporary) throws Exception
    {
        String databaseUrl = "jdbc:sqlite:" + temporary.resolve("transaction.sqlite");
        try(Connection connection = open(databaseUrl);
            Connection observer = DriverManager.getConnection(databaseUrl))
        {
            seedChannel(connection);
            seedUser(connection);
            execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                VALUES (1, 1, 4)
                """);
            ReceiverActivitySchema.updateStatus(connection, ReceiverActivityRetention.CURSOR_STATUS_KEY, "1");
            connection.setAutoCommit(false);
            execute(connection, "UPDATE web_user SET preferences_revision=2 WHERE username='retention-test'");

            assertThrows(SQLException.class, () -> ReceiverActivityRetention.runPass(connection, 3_700_000));
            assertFalse(connection.getAutoCommit());
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, ReceiverActivitySchema.readStatusLong(connection,
                ReceiverActivityRetention.CURSOR_STATUS_KEY));
            assertEquals(2, scalar(connection, "SELECT preferences_revision FROM web_user"),
                "retention must preserve the caller's pending writes");
            assertEquals(1, scalar(observer, "SELECT preferences_revision FROM web_user"),
                "retention must not commit the caller's transaction");

            connection.rollback();
            assertEquals(1, scalar(connection, "SELECT preferences_revision FROM web_user"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void passHasOneGlobalCapRotatesAndEventuallyDrainsEveryBacklog() throws Exception
    {
        try(Connection connection = open())
        {
            seedChannel(connection);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 300)
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                SELECT 1, value, 4 FROM n
                """);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 300)
                INSERT INTO conventional_activity_bucket(channel_id, frequency_hz, timeslot, bucket_start_ms)
                SELECT 1, 450000000 + value, 1, value FROM n
                """);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 300)
                INSERT INTO dmr_conventional_talkgroup_summary(
                    channel_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms, last_seen_ms, call_count)
                SELECT 1, 460000000 + value, 1, value, 1, 1, 1 FROM n
                """);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 300)
                INSERT INTO dmr_conventional_radio_summary(
                    channel_id, frequency_hz, timeslot, radio_id, first_seen_ms, last_seen_ms,
                    call_count, source_call_count)
                SELECT 1, 470000000 + value, 1, value, 1, 1, 1, 1 FROM n
                """);

            ReceiverActivityRetention.Pass first = ReceiverActivityRetention.runPass(connection, 3_700_000);
            assertEquals(ReceiverActivityRetention.MAXIMUM_ROWS_PER_PASS, first.deletedRows());
            assertTrue(first.moreWorkLikely());
            assertTrue(first.tasksVisited() < ReceiverActivityRetention.taskCount());
            assertEquals(first.nextCursor(), ReceiverActivitySchema.readStatusLong(connection,
                ReceiverActivityRetention.CURSOR_STATUS_KEY));
            assertTrue(scalar(connection, "SELECT count(*) FROM receiver_activity_event") < 300);
            assertTrue(scalar(connection, "SELECT count(*) FROM conventional_activity_bucket") < 300);
            assertTrue(scalar(connection, "SELECT count(*) FROM dmr_conventional_talkgroup_summary") < 300);
            assertTrue(scalar(connection, "SELECT count(*) FROM dmr_conventional_radio_summary") < 300);

            ReceiverActivityRetention.Pass second = ReceiverActivityRetention.runPass(connection, 3_700_000);
            assertTrue(second.deletedRows() > 0);
            ReceiverActivityRetention.Pass third = ReceiverActivityRetention.runPass(connection, 3_700_000);
            assertEquals(0, third.deletedRows());
            assertFalse(third.moreWorkLikely());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM conventional_activity_bucket"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM dmr_conventional_talkgroup_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM dmr_conventional_radio_summary"));
        }
    }

    @Test
    void expiredP25ParentSurvivesWhileAnyRetainedChildIsCurrent() throws Exception
    {
        try(Connection connection = open())
        {
            seedChannel(connection);
            execute(connection, """
                INSERT INTO p25_site_snapshot(channel_id, first_seen_ms, last_seen_ms)
                VALUES (1, 1, 1)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel_summary(
                    channel_id, channel_key, first_seen_ms, last_seen_ms, observation_count)
                VALUES (1, '1-1', 1, 10000, 1)
                """);

            ReceiverActivityRetention.runPass(connection, 5_000);
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_site_snapshot"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_site_channel_summary"));

            execute(connection, "UPDATE p25_site_channel_summary SET last_seen_ms=1");
            ReceiverActivityRetention.runPass(connection, 5_000);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM p25_site_channel_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM p25_site_snapshot"));
        }
    }

    @Test
    void boundedFollowUpPassesConvergeWhileExpiredRowsContinueArriving() throws Exception
    {
        try(Connection connection = open())
        {
            seedChannel(connection);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 1000)
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                SELECT 1, value, 4 FROM n
                """);

            for(int pass = 0; pass < 8; pass++)
            {
                execute(connection, """
                    WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 10)
                    INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code)
                    SELECT 1, 1001 + value, 4 FROM n
                    """);
                ReceiverActivityRetention.runPass(connection, 3_700_000);
            }

            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void orphanAndLearnedSiteProbesAreSystemScopedAndIndexBacked() throws Exception
    {
        try(Connection connection = open())
        {
            seedChannel(connection);
            execute(connection, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 1000)
                INSERT INTO radio_system(
                    id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms)
                SELECT value, printf('p25:%05x:%03x', 1, value), 1, 0, 1, value, 1, 1 FROM n
                """);
            execute(connection, """
                INSERT INTO p25_learned_site(
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms)
                SELECT id, id, id % 256, (id / 256) % 256, 1, 1 FROM radio_system
                """);
            execute(connection, """
                INSERT INTO receiver_activity_event(channel_id, radio_system_id, observed_at_ms, action_code)
                SELECT 1, id, id, 4 FROM radio_system
                """);
            execute(connection, """
                INSERT INTO trunked_signaling_activity_bucket(channel_id, radio_system_id, bucket_start_ms)
                SELECT 1, id, id FROM radio_system
                """);
            execute(connection, "ANALYZE");
            String orphanPlan = queryPlan(connection, """
                SELECT system.id
                FROM radio_system system
                WHERE NOT EXISTS (SELECT 1 FROM receiver_channel WHERE radio_system_id = system.id)
                  AND NOT EXISTS (SELECT 1 FROM receiver_activity_event WHERE radio_system_id = system.id)
                  AND NOT EXISTS (
                      SELECT 1 FROM trunked_signaling_activity_bucket WHERE radio_system_id = system.id)
                ORDER BY system.id LIMIT 64
                """);
            assertTrue(orphanPlan.contains("idx_receiver_channel_radio_system"), orphanPlan);
            assertTrue(orphanPlan.contains(
                "SEARCH receiver_activity_event USING COVERING INDEX idx_receiver_activity_event_system_"),
                orphanPlan);
            assertTrue(orphanPlan.contains("idx_trunked_signaling_activity_system"), orphanPlan);

            String learnedPlan = queryPlan(connection, """
                SELECT site.learned_site_id
                FROM p25_learned_site site INDEXED BY idx_p25_learned_site_retention
                WHERE site.last_seen_ms < 5000
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_call_bucket fact
                      WHERE fact.radio_system_id = site.radio_system_id
                        AND fact.learned_site_id = site.learned_site_id)
                  AND NOT EXISTS (
                      SELECT 1 FROM p25_site_call_identity_bucket fact
                      WHERE fact.radio_system_id = site.radio_system_id
                        AND fact.learned_site_id = site.learned_site_id)
                ORDER BY site.last_seen_ms, site.radio_system_id, site.learned_site_id LIMIT 64
                """);
            assertTrue(learnedPlan.contains("idx_p25_learned_site_retention"), learnedPlan);
            assertEquals(2, occurrences(learnedPlan, "SEARCH fact"), learnedPlan);
            assertTrue(learnedPlan.contains("radio_system_id=? AND learned_site_id=?"), learnedPlan);
            assertTrue(learnedPlan.contains("learned_site_id=? AND radio_system_id=?"), learnedPlan);
        }
    }

    @Test
    void oldWuidObservationsAreRetainedByTimeAndKeepCanonicalSubscribersReachable() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection, """
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    alias_list_id, auto_start, decoder_type, primary_frequency_hz, config_json)
                VALUES ('%s', 'TRUNKED', 0, 'P25', 'Site', 'Control',
                    (SELECT id FROM alias_list WHERE family='P25' LIMIT 1), 0, 'P25_PHASE1', 851012500, '{}')
                """.formatted(CONFIGURATION_ID));
            execute(connection, """
                INSERT INTO radio_system(
                    id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms)
                VALUES (1, 'p25:bee00:3a9', 1, 0, 0xBEE00, 0x3A9, 1, 1)
                """);
            execute(connection, """
                INSERT INTO receiver_channel(
                    id, configuration_id, first_seen_ms, last_seen_ms, radio_system_id,
                    radio_system_assigned_at_ms)
                VALUES (1, '%s', 1, 1, 1, 1)
                """.formatted(CONFIGURATION_ID));
            execute(connection, """
                INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id)
                VALUES (1,0xABCDE,0x321,9001),(2,0xABCDE,0x321,9002),(3,0xABCDE,0x321,9003)
                """);
            execute(connection, """
                INSERT INTO p25_wuid_assignment_observation_summary(
                    radio_system_id,working_id,p25_subscriber_identity_id,
                    first_observed_ms,last_observed_ms,last_registration_ms,last_affiliation_ms,
                    registration_count,affiliation_count,last_evidence_code,last_channel_id)
                VALUES (1,123,1,1,1,1,NULL,1,0,1,1)
                """);
            execute(connection, """
                INSERT INTO alias(alias_list_id,name,matcher_type)
                SELECT id,'Explicit canonical subscriber','P25_SUBSCRIBER_IDENTITY'
                FROM alias_list WHERE family='P25' LIMIT 1
                """);
            execute(connection, """
                INSERT INTO alias_p25_subscriber_identity(alias_id,p25_subscriber_identity_id)
                SELECT id,2 FROM alias WHERE name='Explicit canonical subscriber'
                """);

            ReceiverActivityRetention.runPass(connection, 0);
            assertEquals(1, scalar(connection,
                "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM p25_subscriber_identity"),
                "the observation retains subscriber 1, the Alias retains subscriber 2, and orphan 3 is removed");

            String systemHistoryPlan = queryPlan(connection, """
                SELECT working_id FROM p25_wuid_assignment_observation_summary
                WHERE radio_system_id=1 ORDER BY last_observed_ms DESC,working_id LIMIT 100
                """);
            assertTrue(systemHistoryPlan.contains("idx_p25_wuid_assignment_observation_system_time"),
                systemHistoryPlan);
            String subscriberHistoryPlan = queryPlan(connection, """
                SELECT working_id FROM p25_wuid_assignment_observation_summary
                WHERE p25_subscriber_identity_id=1 ORDER BY last_observed_ms DESC LIMIT 100
                """);
            assertTrue(subscriberHistoryPlan.contains("idx_p25_wuid_assignment_observation_subscriber"),
                subscriberHistoryPlan);
            String historyRetentionPlan = queryPlan(connection, """
                SELECT observation.radio_system_id,observation.working_id,
                    observation.p25_subscriber_identity_id
                FROM p25_wuid_assignment_observation_summary observation
                    INDEXED BY idx_p25_wuid_assignment_observation_retention
                WHERE observation.last_observed_ms<2
                ORDER BY observation.last_observed_ms,observation.radio_system_id,
                    observation.working_id,observation.p25_subscriber_identity_id
                LIMIT 100
                """);
            assertTrue(historyRetentionPlan.contains("idx_p25_wuid_assignment_observation_retention"),
                historyRetentionPlan);
            String orphanPlan = queryPlan(connection, """
                SELECT subscriber.id
                FROM p25_subscriber_identity subscriber
                WHERE NOT EXISTS (SELECT 1 FROM radio_system_identity_summary identity
                    WHERE identity.p25_subscriber_identity_id=subscriber.id)
                  AND NOT EXISTS (SELECT 1 FROM p25_wuid_assignment_observation_summary history
                    WHERE history.p25_subscriber_identity_id=subscriber.id)
                  AND NOT EXISTS (SELECT 1 FROM alias_p25_subscriber_identity alias_identity
                    WHERE alias_identity.p25_subscriber_identity_id=subscriber.id)
                ORDER BY subscriber.id LIMIT 100
                """);
            assertTrue(orphanPlan.contains("idx_radio_system_identity_p25_subscriber"), orphanPlan);
            assertTrue(orphanPlan.contains("idx_p25_wuid_assignment_observation_subscriber"), orphanPlan);
            assertTrue(orphanPlan.contains("idx_alias_p25_subscriber_identity"), orphanPlan);

            ReceiverActivityRetention.runPass(connection, 2);
            assertEquals(0, scalar(connection,
                "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_subscriber_identity"),
                "time retention removes the old observation and its now-unreferenced subscriber only");
        }
    }

    private static Connection open() throws Exception
    {
        return open("jdbc:sqlite::memory:");
    }

    private static Connection open(String databaseUrl) throws Exception
    {
        Connection connection = DriverManager.getConnection(databaseUrl);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        SdrTrunkDatabaseSchema.create(connection);
        SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        return connection;
    }

    private static void seedUser(Connection connection) throws Exception
    {
        execute(connection, """
            INSERT INTO web_user(username,tier,primary_admin,credential_version,password_algorithm,
                password_iterations,password_derived_key_bits,password_salt,password_hash,
                password_changed_at_ms,auth_revision,preferences_json,preferences_revision,created_at_ms,updated_at_ms)
            VALUES ('retention-test','USER',0,1,'PBKDF2WithHmacSHA256',600000,256,
                zeroblob(16),zeroblob(32),1,1,'{}',1,1,1)
            """);
    }

    private static void seedChannel(Connection connection) throws Exception
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                alias_list_id, radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'CONVENTIONAL', 0, 'Test', 'Test', 'Test',
                    (SELECT id FROM alias_list WHERE family='DMR' LIMIT 1), NULL, 0, 'DMR', 460000000,
                    '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
            """.formatted(CONFIGURATION_ID));
        execute(connection, """
            INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
            VALUES (1, '%s', 1, 1)
            """.formatted(CONFIGURATION_ID));
    }

    private static String queryPlan(Connection connection, String sql) throws Exception
    {
        StringBuilder plan = new StringBuilder();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("EXPLAIN QUERY PLAN " + sql))
        {
            while(resultSet.next())
            {
                plan.append(resultSet.getString("detail")).append('\n');
            }
        }
        return plan.toString();
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static int occurrences(String value, String search)
    {
        return (value.length() - value.replace(search, "").length()) / search.length();
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }
}
