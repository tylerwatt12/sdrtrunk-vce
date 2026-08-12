/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Regression coverage for the hard live-state bounds exposed to browser subscribers. */
class StatsLiveServiceBoundsTest
{
    @Test
    void capsRowsWithinEachActivityTableAndReportsTheOriginalCount()
    {
        StatsLiveService service = new StatsLiveService(null, null);
        int total = StatsLiveService.MAXIMUM_ROWS_PER_TABLE + 17;
        List<ChannelActivitySnapshot.Row> rows = IntStream.range(0, total)
            .mapToObj(index -> activityRow("row-" + index))
            .toList();

        try
        {
            service.process(activity("bounded-table", rows));
            Map<String,Object> table = tables(service).getFirst();
            List<Map<String,Object>> boundedRows = rows(table);
            assertEquals(StatsLiveService.MAXIMUM_ROWS_PER_TABLE, boundedRows.size());
            assertEquals(total, table.get("rows_total"));
            assertEquals(true, table.get("rows_truncated"));
            assertEquals("row-0", boundedRows.getFirst().get("key"));
            assertEquals("row-" + (StatsLiveService.MAXIMUM_ROWS_PER_TABLE - 1),
                boundedRows.getLast().get("key"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void projectsProtocolNeutralHoverDetails()
    {
        StatsLiveService service = new StatsLiveService(null, null);
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("row", "Dispatch", null, "CALL",
            List.of("VOICE"), "0-101", 851_012_500L, "WPFF205", -22.5, null, 0L, 0L, 0L, 0L, 0L,
            0L, null, 2, "1201", "RADIO", "Engine 1", "Portable 12", "Engine 1 · TA: Portable 12",
            "4400", "TALKGROUP", "Fire Dispatch", "P25_PHASE1", null);
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("site", "Live", "County", "Downtown",
            "Primary", null, null, false, true,
            List.of(new ChannelActivitySnapshot.IdentifierField("System", "WACN", "BEE00"),
                new ChannelActivitySnapshot.IdentifierField("Site", "NAC", "343")), List.of(row));

        try
        {
            service.process(new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot));
            Map<String,Object> table = tables(service).getFirst();
            List<Map<String,Object>> identifiers = (List<Map<String,Object>>)table.get("identifiers");
            Map<String,Object> projected = rows(table).getFirst();
            assertEquals("BEE00", identifiers.getFirst().get("value"));
            assertEquals("RADIO", projected.get("source_form"));
            assertEquals("TALKGROUP", projected.get("target_form"));
            assertEquals("WPFF205", projected.get("callsign"));
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void capsTheAuthoritativeSnapshotWithoutBrowserOwnedCache() throws Exception
    {
        StatsLiveService service = new StatsLiveService(null, null);

        try
        {
            service.start();

            for(int index = 0; index < StatsLiveService.MAXIMUM_LIVE_TABLES; index++)
            {
                String tableId = "table-%03d".formatted(index);
                service.process(activity(tableId, List.of(activityRow("row-" + index))));
            }

            try(StatsLiveEventHub.Subscription subscription = service.subscribeSystems())
            {
                service.process(activity("table-%03d".formatted(StatsLiveService.MAXIMUM_LIVE_TABLES),
                    List.of(activityRow("omitted"))));
                StatsLiveEventHub.LiveEvent update = subscription.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("activity_table", update.name());

                Map<String,Object> snapshot = service.snapshot();
                List<Map<String,Object>> tables = tables(snapshot);
                assertEquals(StatsLiveService.MAXIMUM_LIVE_TABLES, tables.size());
                assertEquals(StatsLiveService.MAXIMUM_LIVE_TABLES, snapshot.get("table_limit"));
                assertEquals(1, snapshot.get("tables_omitted_at_least"));
                assertEquals(true, snapshot.get("truncated"));
                assertEquals("table-000", tables.getFirst().get("table_id"));
                assertEquals("table-%03d".formatted(StatsLiveService.MAXIMUM_LIVE_TABLES - 1),
                    tables.getLast().get("table_id"));
                assertFalse(tables.stream().anyMatch(table ->
                    ("table-%03d".formatted(StatsLiveService.MAXIMUM_LIVE_TABLES)).equals(table.get("table_id"))));

                long revision = ((Number)snapshot.get("revision")).longValue();
                service.process(activity("table-000", List.of(activityRow("updated"))));
                assertEquals(revision + 1, ((Number)service.snapshot().get("revision")).longValue(),
                    "the authoritative truncation snapshot must preserve contiguous revisions");
                assertEquals("updated", rows(tables(service).getFirst()).getFirst().get("key"));
            }

            assertEquals(StatsLiveService.MAXIMUM_LIVE_TABLES, tables(service).size(),
                "browser disconnect must not change authoritative activity state");
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void enforcesOneGlobalRowAndEncodedByteBudgetAndReusesTheEncodedSnapshot() throws Exception
    {
        StatsLiveService service = new StatsLiveService(null, null);
        List<ChannelActivitySnapshot.Row> rows = IntStream.range(0, StatsLiveService.MAXIMUM_ROWS_PER_TABLE)
            .mapToObj(index -> activityRow("x".repeat(2_000) + index))
            .toList();
        int tableCount = StatsLiveService.MAXIMUM_TOTAL_LIVE_ROWS / StatsLiveService.MAXIMUM_ROWS_PER_TABLE + 2;

        try
        {
            for(int index = 0; index < tableCount; index++)
            {
                service.process(activity("global-" + index, rows));
            }

            Map<String,Object> snapshot = service.snapshot();
            assertEquals("System", tables(service).getFirst().get("system_name"));
            assertEquals("Site", tables(service).getFirst().get("site_name"));
            assertEquals(StatsLiveService.MAXIMUM_TOTAL_LIVE_ROWS, snapshot.get("rows_included"));
            assertEquals((long)tableCount * StatsLiveService.MAXIMUM_ROWS_PER_TABLE,
                snapshot.get("rows_total"));
            assertEquals(2L * StatsLiveService.MAXIMUM_ROWS_PER_TABLE, snapshot.get("rows_omitted"));
            assertEquals(true, snapshot.get("truncated"));

            byte[] first = service.encodedSnapshot();
            byte[] second = service.encodedSnapshot();
            assertTrue(first.length <= StatsLiveService.MAXIMUM_SYSTEM_SNAPSHOT_BYTES);
            assertSame(first, second, "unchanged subscribers should share one encoded snapshot");
        }
        finally
        {
            service.close();
        }
    }

    private static ChannelActivityEvent activity(String tableId, List<ChannelActivitySnapshot.Row> rows)
    {
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot(tableId, "Live", "System", "Site",
            "Control", null, null, false, true, List.of(), rows);
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

    private static ChannelActivitySnapshot.Row activityRow(String key)
    {
        return new ChannelActivitySnapshot.Row(key, "Control", null, "ACTIVE", List.of("CONTROL"), "1",
            451_000_000L, null, -25.5, 98.0, 1_000L, 1L, 0L, 0L, 0L, 0L, null, null, null,
            null, null, null, null, null, null, null, "DMR", null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> tables(StatsLiveService service)
    {
        return tables(service.snapshot());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> tables(Map<String,Object> snapshot)
    {
        return (List<Map<String,Object>>)snapshot.get("tables");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> table)
    {
        return (List<Map<String,Object>>)table.get("rows");
    }

}
