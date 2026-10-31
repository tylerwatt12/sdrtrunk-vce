/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.SearchFilter;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Selection;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingQualityTest
{
    private static final String SYSTEM = "p25:00001:001";
    @TempDir Path temporary;

    @Test
    void roundTripsFullCountersAndKeepsUnknownSeparateFromMeasuredZero() throws Exception
    {
        Path database = temporary.resolve("quality.sqlite");
        try(var store = new ManagedRecordingStore(database, temporary.resolve("audio")))
        {
            store.insert(call(1000, null));
            store.insert(call(2000, VoiceCallQuality.EMPTY));
            store.insert(call(3000, new VoiceCallQuality(0, 2, 3, 5, 0, 0)));
            var measured = new VoiceCallQuality(50, 10, 20, 20, 7, 12000);
            store.insert(call(4000, measured));
            store.insert(call(5000, new VoiceCallQuality(0, 0, 0, 0, 0, 100)));
            assertNull(store.find(1).voiceCallQuality());
            assertNull(store.find(2).voiceCallQuality());
            assertFalse(store.find(1).toMap().containsKey("vc_quality_pct"));
            assertFalse(store.find(2).toMap().containsKey("vc_decoded_frames"));
            var zero = store.find(3).toMap();
            assertEquals(0.0, zero.get("vc_quality_pct"));
            assertEquals(0L, zero.get("vc_decoded_frames"));
            assertEquals(2L, zero.get("vc_repeated_frames"));
            assertEquals(3L, zero.get("vc_concealed_frames"));
            assertEquals(5L, zero.get("vc_missing_frames"));
            assertFalse(zero.containsKey("vc_fec_errors"));
            assertEquals(measured, store.find(4).voiceCallQuality());
            var complete = store.find(4).toMap();
            assertEquals(50.0, complete.get("vc_quality_pct"));
            assertEquals(7L, complete.get("vc_fec_errors"));
            assertEquals(12000L, complete.get("vc_fec_protected_bits"));
            var fecOnly = store.find(5).toMap();
            assertFalse(fecOnly.containsKey("vc_quality_pct"));
            assertFalse(fecOnly.containsKey("vc_missing_frames"));
            assertEquals(0L, fecOnly.get("vc_fec_errors"));
            assertEquals(100L, fecOnly.get("vc_fec_protected_bits"));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT voice_decoded_frames,voice_repeated_frames," +
                    "voice_concealed_frames,voice_missing_frames,voice_fec_errors,voice_fec_protected_bits " +
                    "FROM recording_call ORDER BY id"))
            {
                for(int row = 0; row < 2; row++)
                {
                    assertTrue(rows.next());
                    for(int field = 1; field <= 6; field++) assertNull(rows.getObject(field));
                }
                assertTrue(rows.next());
                assertEquals(0L, rows.getLong(1));
                assertNull(rows.getObject(5));
                assertNull(rows.getObject(6));
            }
        }
    }

    @Test
    void inclusiveQualityRangesFilterBeforePaginationAndCombineWithIdentities() throws Exception
    {
        try(var store = new ManagedRecordingStore(temporary.resolve("search.sqlite"), temporary.resolve("audio")))
        {
            store.insert(call(1000, null));
            store.insert(call(2000, new VoiceCallQuality(0, 2, 3, 5, 0, 0)));
            store.insert(call(3000, new VoiceCallQuality(5, 2, 1, 2, 0, 100)));
            store.insert(call(4000, new VoiceCallQuality(10, 0, 0, 0, 5, 100)));
            store.insert(call(5000, new VoiceCallQuality(0, 0, 0, 0, 1, 100)));
            assertEquals(List.of(4000L,3000L,2000L), starts(store, base().hasQuality(true).build()));
            assertEquals(List.of(5000L,1000L), starts(store, base().hasQuality(false).build()));
            assertEquals(List.of(2000L), starts(store, base().maxQualityPercent(0.0).build()));
            assertEquals(List.of(4000L), starts(store, base().minQualityPercent(100.0).build()));
            assertEquals(List.of(3000L), starts(store,
                base().minQualityPercent(50.0).maxQualityPercent(50.0).build()));
            assertEquals(List.of(4000L,3000L,2000L), starts(store,
                base().minQualityPercent(0.0).maxQualityPercent(100.0).build()));
            assertEquals(List.of(4000L,3000L), starts(store,
                base().minQualityPercent(50.0).talkgroupSelections(List.of(new Selection(101,SYSTEM,null)))
                    .radioSelections(List.of(new Selection(10,SYSTEM,null))).radioRole("source").build()));
            for(boolean ascending: List.of(false, true))
            {
                List<Long> found = new ArrayList<>();
                String cursor = null;
                do
                {
                    var page = store.search(base().minQualityPercent(0.0).maxQualityPercent(50.0)
                        .sortAscending(ascending).limit(1).cursor(cursor).build());
                    found.addAll(page.calls().stream().map(call -> call.startMs()).toList());
                    cursor = page.nextCursor();
                }
                while(cursor != null);
                assertEquals(ascending ? List.of(2000L,3000L) : List.of(3000L,2000L), found);
            }
        }
    }

    @Test
    void qualitySearchKeepsExistingBoundedTimeIndexOnFiftyThousandRows() throws Exception
    {
        Path database = temporary.resolve("plans.sqlite");
        try(var store = new ManagedRecordingStore(database, temporary.resolve("audio")))
        {
            store.insert(call(1000, null));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement())
            {
                statement.executeUpdate("WITH RECURSIVE n(x) AS(VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<50000) " +
                    "INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes,system_id," +
                    "channel_id,protocol,call_type,voice_type,source_id,target_id,voice_decoded_frames," +
                    "voice_repeated_frames,voice_concealed_frames,voice_missing_frames) " +
                    "SELECT 10000+x*1000,11000+x*1000,1000,'populated-'||x||'.mp3',5,c.system_id,c.channel_id," +
                    "1,1,1,10,101,CASE WHEN x%2=0 THEN 100 ELSE 50 END,0,0,CASE WHEN x%2=0 THEN 0 ELSE 50 END " +
                    "FROM n CROSS JOIN recording_call c WHERE c.id=1");
            }
            var filter = base().fromMs(25_000_000L).toMs(30_000_000L).hasQuality(true)
                .minQualityPercent(90.0).limit(3).build();
            assertEquals(List.of(30_000_000L,29_998_000L,29_996_000L), starts(store, filter));
            for(var query: store.singleIdentityCandidateQueries(filter))
            {
                StringBuilder plan = new StringBuilder();
                try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                    var statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + query.sql()))
                {
                    int parameter = 1;
                    for(Object value: query.parameters()) statement.setObject(parameter++,value);
                    try(var rows = statement.executeQuery())
                    {
                        while(rows.next()) plan.append(rows.getString("detail")).append('\n');
                    }
                }
                String detail = plan.toString();
                assertTrue(detail.contains("SEARCH c USING INDEX idx_recording_call_time"), detail);
                assertFalse(detail.contains("SCAN c"), detail);
                assertFalse(detail.contains("USE TEMP B-TREE"), detail);
            }
        }
    }

    @Test
    void rejectsInvalidOrContradictoryRanges()
    {
        for(double value: List.of(-0.1,100.1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))
        {
            assertThrows(IllegalArgumentException.class, () -> base().minQualityPercent(value).build());
            assertThrows(IllegalArgumentException.class, () -> base().maxQualityPercent(value).build());
        }
        assertThrows(IllegalArgumentException.class,
            () -> base().minQualityPercent(80.0).maxQualityPercent(70.0).build());
        assertThrows(IllegalArgumentException.class,
            () -> base().hasQuality(false).minQualityPercent(0.0).build());
        assertThrows(IllegalArgumentException.class,
            () -> base().hasQuality(false).maxQualityPercent(100.0).build());
        assertDoesNotThrow(() -> base().hasQuality(true).minQualityPercent(0.0).maxQualityPercent(100.0).build());
    }

    private static SearchFilter.Builder base()
    {
        return SearchFilter.builder().fromMs(0L).toMs(10_000L).limit(100);
    }

    private static List<Long> starts(ManagedRecordingStore store, SearchFilter filter) throws Exception
    {
        return store.search(filter).calls().stream().map(call -> call.startMs()).toList();
    }

    private static ManagedRecordingMetadata call(long start, VoiceCallQuality quality)
    {
        return new ManagedRecordingMetadata(start,start+1000,1000,5,start+".mp3",SYSTEM,"channel-a",7,1,1,1,
            10,null,null,null,101,null,null,null,851012500L,1,0x293,null,null,null,List.of(),List.of(),null,
            List.of(),List.of(),quality);
    }
}
