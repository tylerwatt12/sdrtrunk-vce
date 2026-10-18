/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.audio.call.SourceCallReference;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityRecordingAvailabilityTest
{
    @TempDir Path directory;
    private static final String A = CallRelationsServiceTest.A;
    private static final String B = CallRelationsServiceTest.B;

    @Test
    void currentCatalogPresenceControlsAllExactReceiverCopiesIndependentlyOfDuration() throws Exception
    {
        Path file = directory.resolve("recordings.sqlite");
        try(var catalog = new ManagedRecordingCatalog(file, directory))
        {
            CallRelationsServiceTest.addRecording(file, 1, List.of(A));
            CallRelationsServiceTest.addRecording(file, 2, List.of(A));
            var availability = new ActivityRecordingAvailability(ids -> Map.of(7L, A, 8L, A, 9L, B),
                catalog::recordingIdsBySourceCallReferences);
            var page = page(
                Map.of("id", 7L, "protocol", "p25", "channel_name", "North"),
                Map.of("id", 8L, "protocol", "dmr", "channel_name", "South", "duration_ms", 2000L),
                Map.of("id", 9L, "protocol", "nxdn", "duration_ms", 4000L),
                Map.of("id", 10L, "protocol", "nbfm", "duration_ms", 5000L));
            availability.enrich(page, true);
            assertEquals(List.of(true, true, false, false), flags(page));
            assertFalse(rows(page).getFirst().containsKey("duration_ms"),
                "A recording remains playable when its Activity length is unknown");

            assertTrue(catalog.delete(1));
            availability.enrich(page, true);
            assertEquals(List.of(true, true, false, false), flags(page),
                "A surviving split part still makes both exact receiver copies playable");
            assertTrue(catalog.delete(2));
            availability.enrich(page, true);
            assertEquals(List.of(false, false, false, false), flags(page),
                "The next page refresh reflects recording cleanup without updating Activity storage");
            String json = new ObjectMapper().writeValueAsString(page);
            assertFalse(json.contains(A));
            assertFalse(json.contains(B));
            assertFalse(json.contains("source_call_uuid"));
        }
    }

    @Test
    void oneFullActivityPageUsesTwoBoundedCatalogReadsAndOneSourceRead()
    {
        var sources = new LinkedHashMap<Long,String>();
        var activity = new ArrayList<Map<String,Object>>();
        for(int index = 0; index < 200; index++)
        {
            long id = index + 1;
            sources.put(id, SourceCallReference.fromToken("availability:" + id));
            activity.add(Map.of("id", id));
        }
        var sourceReads = new AtomicInteger();
        var catalogReads = new AtomicInteger();
        var requestedSources = new ArrayList<String>();
        var availability = new ActivityRecordingAvailability(ids -> {
            sourceReads.incrementAndGet();
            assertEquals(IntStream.rangeClosed(1, 200).mapToObj(Long::valueOf).toList(), ids);
            return sources;
        }, (references, limit) -> {
            catalogReads.incrementAndGet();
            assertEquals(100, references.size());
            assertEquals(1, limit, "Availability needs one matching recording rather than all split parts");
            requestedSources.addAll(references);
            var matches = new LinkedHashMap<String,List<Long>>();
            for(String reference: references) matches.put(reference, List.of(1L));
            return matches;
        });
        Map<String,Object> page = new LinkedHashMap<>(Map.of("rows", activity, "has_more", true));
        availability.enrich(page, true);
        assertEquals(1, sourceReads.get());
        assertEquals(2, catalogReads.get());
        assertEquals(200, requestedSources.stream().distinct().count());
        assertEquals(200, flags(page).stream().filter(Boolean::booleanValue).count());
        assertEquals(true, page.get("has_more"));
    }

    @Test
    void sharedReferencesAreResolvedOnceAndMissingReferencesDoNotReadTheCatalog()
    {
        var catalogReads = new AtomicInteger();
        var availability = new ActivityRecordingAvailability(ids -> Map.of(7L, A, 8L, A), (sources, limit) -> {
            catalogReads.incrementAndGet();
            assertEquals(List.of(A), sources);
            return Map.of(A, List.of(1L));
        });
        var page = page(Map.of("id", 7L), Map.of("id", 8L), Map.of("id", 9L));
        availability.enrich(page, true);
        assertEquals(List.of(true, true, false), flags(page));
        assertEquals(1, catalogReads.get());
        var unlinked = new ActivityRecordingAvailability(ids -> Map.of(), (sources, limit) -> {
            fail("Unlinked historical Activity must not query the recording catalog");
            return Map.of();
        });
        unlinked.enrich(page, true);
        assertEquals(List.of(false, false, false), flags(page));
    }

    @Test
    void absentCatalogAndLookupFailuresLeaveEveryActivityRowAvailableWithoutPlayback()
    {
        var page = page(Map.of("id", 7L, "duration_ms", 500L, "has_recording", true));
        var unavailable = new ActivityRecordingAvailability(ids -> Map.of(7L, A), (sources, limit) -> {
            throw new SQLException("private-catalog-location " + A);
        });
        unavailable.enrich(page, true);
        assertEquals(List.of(false), flags(page));
        assertEquals(500L, rows(page).getFirst().get("duration_ms"));
        var missingSources = new ActivityRecordingAvailability(ids -> {
            throw new SQLException("private-activity-location " + A);
        }, (sources, limit) -> {
            fail("A failed source lookup must not read recordings");
            return Map.of();
        });
        assertDoesNotThrow(() -> missingSources.enrich(page, true));
        assertEquals(List.of(false), flags(page));
        var empty = new ActivityRecordingAvailability(ids -> Map.of(7L, A), (sources, limit) -> Map.of());
        empty.enrich(page, true);
        assertEquals(List.of(false), flags(page));
    }

    @SafeVarargs
    private static Map<String,Object> page(Map<String,Object>... rows)
    { return new LinkedHashMap<>(Map.of("rows", List.of(rows))); }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> page)
    { return (List<Map<String,Object>>)page.get("rows"); }

    private static List<Boolean> flags(Map<String,Object> page)
    { return rows(page).stream().map(row -> (Boolean)row.get("has_recording")).toList(); }
}
