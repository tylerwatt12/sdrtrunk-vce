/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallRelationsServiceTest
{
    @TempDir Path directory;
    static final String A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    static final String B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    @Test
    void exactSplitAndMergedSourcesResolveAfterRestartWithoutDependingOnActivityRetention() throws Exception
    {
        Path file = directory.resolve("catalog.sqlite");
        try(var ignored = new ManagedRecordingCatalog(file, directory)) { }
        addRecording(file, 1, List.of(A, B));
        addRecording(file, 2, List.of(A));
        AtomicInteger activityReads = new AtomicInteger();
        AtomicInteger labelReads = new AtomicInteger();
        var activity = new CallRelationsService.ActivityLookup()
        {
            public String source(long id) { return id == 7 ? A : null; }
            public List<Map<String,Object>> find(List<String> sources)
            {
                activityReads.incrementAndGet();
                assertEquals(List.of(A, B), sources);
                return List.of();
            }
        };
        try(var catalog = new ManagedRecordingCatalog(file, directory))
        {
            var service = new CallRelationsService(activity, () -> catalog, sources -> {
                assertTrue(sources.contains(A));
                return List.of(Map.of("id", "alert-1", "title", "Fire call", "source_call_uuid", A,
                    "action", Map.of("kind", "live")));
            }, rows -> {
                labelReads.incrementAndGet();
                for(var row: rows) row.put("group_alias", "Fire Dispatch");
                return rows;
            });
            var result = service.forRecording(1, true, true);
            assertTrue(rows(result, "activity_rows").isEmpty(), "Pruned Activity does not erase alerts or recordings");
            assertEquals(List.of(1L, 2L), rows(result, "recordings").stream().map(row -> row.get("id")).toList());
            assertEquals("Fire Dispatch", rows(result, "recordings").getFirst().get("group_alias"));
            assertEquals(1, activityReads.get()); assertEquals(1, labelReads.get());
            assertFalse(rows(result, "alerts").getFirst().containsKey("action"));
            assertFalse(result.toString().contains(A)); assertFalse(result.toString().contains(B));
            assertNull(service.forRecording(999, true, true));
            assertTrue(rows(service.forActivity(999, true, true), "recordings").isEmpty());
            var restricted = service.forActivity(7, false, false);
            assertTrue(rows(restricted, "activity_rows").isEmpty());
            assertTrue(rows(restricted, "recordings").isEmpty());
            assertEquals(1, rows(restricted, "alerts").size());
            assertEquals(1, activityReads.get()); assertEquals(1, labelReads.get());
        }
    }

    @Test
    void maximumReceiverCopiesArePartitionedForRecordingLookupAndEveryDatasetIsBounded() throws Exception
    {
        Path file = directory.resolve("many-sources.sqlite");
        try(var ignored = new ManagedRecordingCatalog(file, directory)) { }
        List<String> sources = new ArrayList<>();
        for(int index = 0; index < 128; index++) sources.add(new UUID(0, index + 1).toString());
        addRecording(file, 1, sources);
        var manyRows = new ArrayList<Map<String,Object>>();
        for(int index = 0; index < 105; index++) manyRows.add(Map.of("id", index));
        var activity = new CallRelationsService.ActivityLookup()
        {
            public String source(long id) { return null; }
            public List<Map<String,Object>> find(List<String> found)
            { assertEquals(sources.stream().sorted().toList(), found); return manyRows; }
        };
        try(var catalog = new ManagedRecordingCatalog(file, directory))
        {
            var service = new CallRelationsService(activity, () -> catalog, found -> manyRows, rows -> rows);
            var result = service.forRecording(1, true, true);
            assertEquals(100, rows(result, "activity_rows").size());
            assertEquals(100, rows(result, "alerts").size());
            assertEquals(1, rows(result, "recordings").size());
        }
    }

    static void addRecording(Path file, long id, List<String> references) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                "protocol,call_type,voice_type) VALUES(" + id + ",1000,2000,1000,'" + id + ".mp3',5,1,1,1)");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=call_count+1,total_bytes=total_bytes+5 WHERE id=1");
            try(var insert = connection.prepareStatement("INSERT INTO recording_source VALUES(?,?)"))
            {
                for(String reference: references)
                { insert.setLong(1, id); insert.setString(2, reference); insert.addBatch(); }
                insert.executeBatch();
            }
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> rows(Map<String,Object> result, String key)
    { return (List<Map<String,Object>>)result.get(key); }
}
