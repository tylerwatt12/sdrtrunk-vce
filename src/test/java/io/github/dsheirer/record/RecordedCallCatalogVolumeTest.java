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
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Representative-volume guard for retained-call website queries.
 *
 * <p>Normal CI inserts one million rows so generated public search SQL is always checked at the requested scale.
 * The row count can be raised further with {@code -Dsdrtrunk.recordedCallCatalog.testRows=N}.</p>
 */
class RecordedCallCatalogVolumeTest
{
    private static final int DEFAULT_ROWS = 1_000_000;
    private static final int ROWS =
        Math.max(DEFAULT_ROWS, Integer.getInteger("sdrtrunk.recordedCallCatalog.testRows", DEFAULT_ROWS));
    private static final int BUCKETS = 100;
    private static final long START = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void allWebsiteSearchesStayIndexBackedAtRepresentativeVolume() throws Exception
    {
        Path database = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            insertVolume(connection);

            try(Statement statement = connection.createStatement())
            {
                statement.execute("ANALYZE");
            }

            long end = START + ROWS + 1L;
            RecordedCallCatalogSearch broadDuration = search(null, null, null, null, null,
                0, 10_000, null);
            assertGeneratedPlanUses(connection, broadDuration, "PRIMARY KEY");

            RecordedCallCatalogSearch selectiveDuration = search(null, null, null, null, null,
                1_000, 1_100, null);
            assertGeneratedPlanUses(connection, selectiveDuration,
                RecordedCallCatalogSchema.DURATION_TIME_INDEX);

            int forwardRow = ROWS / 2;
            RecordedCallCatalogSearch.Cursor forwardCursor = RecordedCallCatalogSearch.Cursor.create(
                START + forwardRow, new AudioCallId(forwardRow / 10_000L, forwardRow, 0));
            assertGeneratedForwardPlanUses(connection, broadDuration, forwardCursor, "PRIMARY KEY");
            assertGeneratedForwardPlanUses(connection, selectiveDuration, forwardCursor, "PRIMARY KEY");
            assertGeneratedForwardPlanUses(connection, search("system-0", null, null, null, null,
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                forwardCursor, "PRIMARY KEY");
            assertGeneratedForwardPlanUses(connection, search("system-0", "site-0", null, null, null,
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                forwardCursor, "PRIMARY KEY");
            assertGeneratedForwardPlanUses(connection, search("system-0", null, "talkgroup-0", null, null,
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                forwardCursor, "PRIMARY KEY");
            assertGeneratedForwardPlanUses(connection, search(null, "site-0", null, "channel-0", null,
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                forwardCursor, "PRIMARY KEY");
            assertGeneratedForwardPlanUses(connection, search(null, null, null, null, "radio-0",
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                forwardCursor, "PRIMARY KEY");

            assertGeneratedPlanUses(connection, search("system-0", null, null, null, null,
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                RecordedCallCatalogSchema.BUCKET_SYSTEM_INDEX, RecordedCallCatalogSchema.BUCKET_TIME_INDEX);
            assertGeneratedPlanUses(connection, search("system-0", "site-0", null, null, null,
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                RecordedCallCatalogSchema.BUCKET_SYSTEM_SITE_INDEX,
                RecordedCallCatalogSchema.BUCKET_TIME_INDEX);
            assertGeneratedPlanUses(connection, search("system-0", null, "talkgroup-0", null, null,
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                RecordedCallCatalogSchema.BUCKET_TALKGROUP_INDEX,
                RecordedCallCatalogSchema.BUCKET_TIME_INDEX);
            assertGeneratedPlanUses(connection, search(null, "site-0", null, "channel-0", null,
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                RecordedCallCatalogSchema.BUCKET_SITE_CHANNEL_INDEX,
                RecordedCallCatalogSchema.BUCKET_TIME_INDEX);
            assertGeneratedPlanUses(connection, search(null, null, null, null, "radio-0",
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, null),
                RecordedCallCatalogSchema.RADIO_TIME_INDEX);
            assertGeneratedPlanUses(connection, search(null, null, null, null, null,
                    0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS,
                    RecordedCallCatalogSearch.Cursor.create(end - 2,
                        new AudioCallId((ROWS - 1L) / 10_000, ROWS - 1L, 0))),
                "PRIMARY KEY");
            String retentionPlan = explain(connection, RecordedCallCatalogStore.RETENTION_AFTER_SQL,
                START + ROWS - 1_000L, (ROWS - 1_000L) / 10_000, ROWS - 1_000L, 0, 251);
            assertTrue(retentionPlan.contains("SEARCH c USING PRIMARY KEY"), retentionPlan);
            assertFalse(retentionPlan.contains("SCAN c"), retentionPlan);
            assertFalse(retentionPlan.contains("USE TEMP B-TREE"), retentionPlan);

            assertPlanUses(connection, """
                SELECT 1 FROM recorded_call INDEXED BY idx_recorded_call_bucket_time
                WHERE bucket_id = ? AND completed_at_ms >= ? LIMIT 1
                """, RecordedCallCatalogSchema.BUCKET_TIME_INDEX, 1L, START);
            assertPlanUses(connection, """
                SELECT id FROM recorded_call_bucket INDEXED BY idx_recorded_call_bucket_system
                WHERE system_key = ? AND day_utc >= ?
                """, RecordedCallCatalogSchema.BUCKET_SYSTEM_INDEX, "system-0",
                java.time.LocalDate.of(2026, 7, 1).toEpochDay());
            assertPlanUses(connection, """
                SELECT id FROM recorded_call_bucket INDEXED BY idx_recorded_call_bucket_site
                WHERE site_key = ? AND day_utc >= ?
                """, RecordedCallCatalogSchema.BUCKET_SITE_INDEX, "site-0",
                java.time.LocalDate.of(2026, 7, 1).toEpochDay());
            assertPlanUses(connection, """
                SELECT id FROM recorded_call_bucket INDEXED BY idx_recorded_call_bucket_channel
                WHERE channel_key = ? AND day_utc >= ?
                """, RecordedCallCatalogSchema.BUCKET_CHANNEL_INDEX, "channel-0",
                java.time.LocalDate.of(2026, 7, 1).toEpochDay());
            assertPlanUses(connection, """
                SELECT id FROM recorded_call_bucket INDEXED BY idx_recorded_call_bucket_talkgroup
                WHERE system_key = ? AND talkgroup_key = ?
                """, RecordedCallCatalogSchema.BUCKET_TALKGROUP_INDEX, "system-0", "talkgroup-0");
            assertPlanUses(connection, """
                SELECT id FROM recorded_call_bucket INDEXED BY idx_recorded_call_bucket_talkgroup_value
                WHERE talkgroup_key = ? AND day_utc >= ?
                """, RecordedCallCatalogSchema.BUCKET_TALKGROUP_VALUE_INDEX, "talkgroup-0",
                java.time.LocalDate.of(2026, 7, 1).toEpochDay());
            RecordedCallCatalogStore store = new RecordedCallCatalogStore(mTemporaryFolder.resolve("recordings"));
            RecordedCallCatalogPage page = store.search(connection,
                new RecordedCallCatalogSearch("system-0", null, null, null, null,
                    START, end, 0, 10_000, RecordedCallCatalogSearch.MAXIMUM_PAGE_SIZE, null));
            assertEquals(RecordedCallCatalogSearch.MAXIMUM_PAGE_SIZE, page.calls().size());
            assertTrue(page.nextCursor() != null);
            assertEquals(RecordedCallCatalogSearch.MAXIMUM_PAGE_SIZE,
                store.search(connection, selectiveDuration).calls().size());
            assertEquals(RecordedCallCatalogSearch.MAXIMUM_PAGE_SIZE,
                store.search(connection, broadDuration).calls().size());
            RecordedCallCatalogPage forward = store.searchForward(connection, broadDuration, forwardCursor);
            assertEquals(RecordedCallCatalogSearch.MAXIMUM_PAGE_SIZE, forward.calls().size());
            assertTrue(forward.nextCursor() != null);
            assertEquals(START + forwardRow + 1, forward.calls().getFirst().completedAtMs());

            List<String> batchIds = new ArrayList<>(RecordedCallCatalogStore.MAXIMUM_BATCH_SIZE);

            for(int offset = 0; offset < RecordedCallCatalogStore.MAXIMUM_BATCH_SIZE; offset++)
            {
                int row = ROWS - 1 - offset;
                batchIds.add(RecordedCallCatalogTokens.callId(START + row,
                    new AudioCallId(row / 10_000L, row, 0)));
            }

            RecordedCallCatalogStore.SearchStatement batchStatement =
                RecordedCallCatalogStore.buildBatchResolveStatement(batchIds);
            assertEquals(801, batchStatement.parameters().size());
            assertTrue(batchStatement.parameters().size() <= 999);
            String batchPlan =
                explain(connection, batchStatement.sql(), batchStatement.parameters().toArray());
            assertTrue(batchPlan.contains("SEARCH c USING PRIMARY KEY"), batchPlan);
            assertFalse(batchPlan.contains("SCAN c"), batchPlan);
            List<Optional<RecordedCallCatalogMetadata>> batch = store.resolveCalls(connection, batchIds);
            assertEquals(batchIds, batch.stream().map(Optional::orElseThrow)
                .map(RecordedCallCatalogMetadata::id).toList());

            assertEquals(10, store.listIdentities(connection, RecordedCallIdentityKind.SYSTEM, "", "", 20).size());
            assertEquals(10, store.listIdentities(connection, RecordedCallIdentityKind.SITE, "system-0", "", 20)
                .size());

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA quick_check"))
            {
                assertTrue(resultSet.next());
                assertEquals("ok", resultSet.getString(1));
            }

            try(Statement statement = connection.createStatement())
            {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        }

        long maximumBytes = Math.max(128L * 1024 * 1024, Math.multiplyExact((long)ROWS, 800L));
        assertTrue(Files.size(database) < maximumBytes,
            ROWS + " compact calls and all website indexes exceeded the per-row storage guard");
    }

    private static RecordedCallCatalogSearch search(String system, String site, String talkgroup, String channel,
                                                    String radio, long minimumDuration, long maximumDuration,
                                                    RecordedCallCatalogSearch.Cursor cursor)
    {
        return new RecordedCallCatalogSearch(system, site, talkgroup, channel, radio,
            START, START + ROWS + 1L, minimumDuration, maximumDuration,
            RecordedCallCatalogSearch.MAXIMUM_PAGE_SIZE, cursor);
    }

    private static void assertGeneratedPlanUses(Connection connection, RecordedCallCatalogSearch search,
                                                String... expected) throws Exception
    {
        RecordedCallCatalogStore.SearchStatement statement =
            RecordedCallCatalogStore.buildSearchStatement(search);
        String plan = explain(connection, statement.sql(), statement.parameters().toArray());
        assertTrue(plan.contains("SEARCH"), plan);
        assertFalse(plan.contains("SCAN c"), plan);

        for(String index: expected)
        {
            assertTrue(plan.contains(index), plan);
        }
    }

    private static void assertGeneratedForwardPlanUses(Connection connection, RecordedCallCatalogSearch search,
                                                       RecordedCallCatalogSearch.Cursor after,
                                                       String expected) throws Exception
    {
        RecordedCallCatalogStore.SearchStatement statement =
            RecordedCallCatalogStore.buildForwardSearchStatement(search, after);
        String plan = explain(connection, statement.sql(), statement.parameters().toArray());
        assertTrue(plan.contains("SEARCH c"), plan);
        assertTrue(plan.contains(expected), plan);
        assertFalse(plan.contains("SCAN c"), plan);
        assertFalse(plan.contains("USE TEMP B-TREE"), plan);
    }

    private static void insertVolume(Connection connection) throws Exception
    {
        connection.setAutoCommit(false);

        try(PreparedStatement bucket = connection.prepareStatement("""
            INSERT INTO recorded_call_bucket(
                id, day_utc, relative_directory, system_key, system_label, site_key, site_label,
                channel_key, channel_label, talkgroup_key, talkgroup_label
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            for(int value = 0; value < BUCKETS; value++)
            {
                int system = value % 10;
                bucket.setLong(1, value + 1L);
                bucket.setLong(2, java.time.LocalDate.of(2026, 7, 1).toEpochDay());
                bucket.setString(3, "calls/v1/2026/07/01/system-" + system + "~aaaaaaaaaaaa/site-" + value +
                    "~bbbbbbbbbbbb/channel-" + value + "~cccccccccccc/talkgroup-" + value + "~dddddddddddd");
                bucket.setString(4, "system-" + system);
                bucket.setString(5, "System " + system);
                bucket.setString(6, "site-" + value);
                bucket.setString(7, "Site " + value);
                bucket.setString(8, "channel-" + value);
                bucket.setString(9, "Channel " + value);
                bucket.setString(10, "talkgroup-" + value);
                bucket.setString(11, "Talkgroup " + value);
                bucket.addBatch();
            }

            bucket.executeBatch();
        }

        try(PreparedStatement call = connection.prepareStatement("""
            INSERT INTO recorded_call(
                producer_id, call_sequence, timeslot, completed_at_ms, start_at_ms, duration_ms, byte_size,
                format_code, flags, bucket_id, source_radio_key
            ) VALUES (?, ?, 0, ?, ?, ?, 1024, 1, 2, ?, ?)
            """))
        {
            for(int row = 0; row < ROWS; row++)
            {
                int bucket = row % BUCKETS;
                long completed = START + row;
                long duration = 250L + row % 10_000;
                call.setLong(1, row / 10_000);
                call.setLong(2, row);
                call.setLong(3, completed);
                call.setLong(4, completed - duration);
                call.setLong(5, duration);
                call.setLong(6, bucket + 1L);
                call.setString(7, "radio-" + bucket);
                call.addBatch();

                if((row + 1) % 1_000 == 0)
                {
                    call.executeBatch();
                }
            }

            call.executeBatch();
        }

        connection.commit();
        connection.setAutoCommit(true);
    }

    private static void assertPlanUses(Connection connection, String sql, String expected, Object... parameters)
        throws Exception
    {
        assertPlanUsesAll(connection, sql, new String[]{expected}, parameters);
    }

    private static void assertPlanUsesAll(Connection connection, String sql, String[] expected,
                                          Object... parameters) throws Exception
    {
        String plan = explain(connection, sql, parameters);
        assertTrue(plan.contains("SEARCH"), plan);

        for(String index: expected)
        {
            assertTrue(plan.contains(index), plan);
        }
    }

    private static String explain(Connection connection, String sql, Object... parameters) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
        {
            for(int index = 0; index < parameters.length; index++)
            {
                statement.setObject(index + 1, parameters[index]);
            }

            StringBuilder plan = new StringBuilder();

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    plan.append(resultSet.getString("detail")).append('\n');
                }
            }

            return plan.toString();
        }
    }
}
