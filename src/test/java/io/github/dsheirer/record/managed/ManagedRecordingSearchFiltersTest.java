/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Member;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Selection;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.SearchFilter;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Site;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingSearchFiltersTest
{
    private static final String A = "p25:00001:001", B = "p25:00002:002";
    @TempDir Path temporary;

    @Test
    void selectionsAreOrWithinTheirKindAndAndAcrossKindsWithoutCrossingSystems() throws Exception
    {
        Path db = temporary.resolve("selections.sqlite");
        try(ManagedRecordingStore store = new ManagedRecordingStore(db,temporary.resolve("audio")))
        {
            store.insert(call(1000,A,10,101,1,List.of(),List.of()));
            store.insert(call(2000,A,20,102,1,List.of(),List.of()));
            store.insert(call(3000,B,10,101,1,List.of(),List.of()));
            store.insert(call(4000,null,10,101,1,List.of(),List.of()));
            store.insert(call(5000,null,10,101,1,List.of(new Site(1,1,1,1)),List.of()));
            store.insert(call(6000,A,10,101,3,List.of(),List.of()));
            store.insert(call(7000,A,10,999,2,List.of(),List.of(new Member("talkgroup",101,null,null,null))));
            List<Selection> groups = List.of(new Selection(101,A,null),new Selection(102,A,null));
            assertEquals(List.of(7000L,5000L,2000L,1000L),starts(store,base().talkgroupSelections(groups).build()));
            assertEquals(List.of(7000L,5000L,1000L),starts(store,base().talkgroupSelections(groups)
                .radioSelections(List.of(new Selection(10,A,null))).radioRole("source").build()));
            assertEquals(List.of(4000L),starts(store,base()
                .talkgroupSelections(List.of(new Selection(101,null,null))).build()));
            assertEquals(List.of(4000L,3000L),starts(store,base()
                .talkgroupSelections(List.of(new Selection(101,null,null),new Selection(101,B,null))).build()));
            assertEquals(List.of(7000L,5000L,4000L,3000L,1000L),starts(store,base().talkgroupId(101)
                .anyIdentityIds(List.of(101,102)).build()));
            assertTrue(starts(store,base().talkgroupSelections(groups).sourceId(999).build()).isEmpty());
        }
    }

    @Test
    void pickerTalkgroupsExcludeAnalogAssignedTargetsWhileLegacyNumericFilterIsPreserved() throws Exception
    {
        Path db = temporary.resolve("analog-selection.sqlite");
        try(ManagedRecordingStore store = new ManagedRecordingStore(db,temporary.resolve("audio")))
        {
            store.insert(call(1000,null,10,101,1,List.of(),List.of()));
            store.insert(call(2000,null,10,101,1,List.of(),List.of()));
            try(var c = DriverManager.getConnection("jdbc:sqlite:"+db); var s = c.createStatement())
            {
                s.executeUpdate("UPDATE recording_call SET protocol=5 WHERE start_ms=2000");
            }
            assertEquals(List.of(1000L),starts(store,base()
                .talkgroupSelections(List.of(new Selection(101,null,null))).build()));
            assertEquals(List.of(2000L,1000L),starts(store,base().talkgroupId(101).build()));
        }
    }

    @Test
    void qualifiedSourcesFollowCanonicalOwnersAcrossWorkingIdsAndPreserveRoleAndHome() throws Exception
    {
        Path db = temporary.resolve("home.sqlite");
        try(ManagedRecordingStore store = new ManagedRecordingStore(db,temporary.resolve("audio")))
        {
            store.insert(call(1000,A,501,101,1,List.of(),List.of()));
            store.insert(call(2000,A,502,102,1,List.of(),List.of()));
            store.insert(call(3000,A,20,503,3,List.of(),List.of()));
            store.insert(call(4000,A,20,999,2,List.of(),List.of(new Member("radio",504,2,2,77),
                new Member("radio",505,2,2,77))));
            store.insert(call(5000,B,501,101,1,List.of(),List.of()));
            store.insert(call(6000,A,501,101,1,List.of(),List.of()));
            store.insert(call(7000,null,501,101,1,List.of(),List.of()));
            store.insert(call(8000,A,77,101,1,List.of(),List.of()));
            store.insert(call(9000,A,888,101,1,List.of(),List.of()));
            try(var c = DriverManager.getConnection("jdbc:sqlite:"+db); var s = c.createStatement())
            {
                s.executeUpdate("UPDATE recording_call SET source_home_wacn=2,source_home_system=2,source_home_id=77 " +
                    "WHERE start_ms IN(1000,2000,5000,7000)");
                s.executeUpdate("UPDATE recording_call SET target_home_wacn=2,target_home_system=2,target_home_id=77 WHERE start_ms=3000");
                s.executeUpdate("UPDATE recording_call SET source_home_wacn=1,source_home_system=1,source_home_id=77 WHERE start_ms=9000");
            }
            Selection owner = new Selection(501,A,"v1-r-00002-002-77");
            assertEquals(List.of(2000L,1000L),starts(store,base().radioSelections(List.of(owner)).radioRole("source").build()));
            assertEquals(List.of(4000L,3000L,2000L,1000L),starts(store,base().radioSelections(List.of(owner)).build()));
            assertEquals(List.of(6000L),starts(store,base().radioSelections(List.of(new Selection(501,A,null))).build()));
            assertEquals(List.of(7000L),starts(store,base().radioSelections(List.of(new Selection(77,null,"v1-r-00002-002-77"))).build()));
            assertEquals(List.of(9000L),starts(store,base().radioSelections(List.of(new Selection(77,A,"v1-r-00001-001-77"))).build()));
            assertEquals(List.of(8000L),starts(store,base().radioSelections(List.of(new Selection(77,A,null))).build()));
            assertEquals(List.of(9000L,8000L),starts(store,base().systemKey(A).radioIdentityMatch(
                new ManagedRecordingCatalog.IdentityNameMatch(77,A,1,1,true)).build()));
            assertEquals(List.of(1000L),starts(store,base().radioSelections(List.of(owner)).talkgroupId(101).build()));
            assertEquals(List.of(3000L),starts(store,base().sourceId(503).build()));
            assertTrue(starts(store,base().sourceId(503).radioRole("source").build()).isEmpty());
            for(boolean ascending: List.of(false,true))
            {
                List<Long> found = new ArrayList<>();
                String cursor = null;
                do
                {
                    var page = store.search(base().radioSelections(List.of(owner)).limit(1)
                        .sortAscending(ascending).cursor(cursor).build());
                    found.addAll(page.calls().stream().map(call -> call.startMs()).toList());
                    cursor = page.nextCursor();
                }
                while(cursor != null);
                assertEquals(ascending ? List.of(1000L,2000L,3000L,4000L) : List.of(4000L,3000L,2000L,1000L),found);
            }
        }
    }

    @Test
    void toneFiltersUseStoredEvidenceToleranceAndConsecutiveOrderAndCombineWithOtherFilters() throws Exception
    {
        Path db = temporary.resolve("tones.sqlite");
        try(ManagedRecordingStore store = new ManagedRecordingStore(db,temporary.resolve("audio")))
        {
            for(int i=1;i<=4;i++) store.insert(call(i*1000L,A,i==3 ? 20 : 10,101,1,List.of(),List.of()));
            try(var c = DriverManager.getConnection("jdbc:sqlite:"+db); var s = c.createStatement())
            {
                s.executeUpdate("INSERT INTO recording_tone_sequence VALUES(1,1,1,1,1000,1000,1500,2)," +
                    "(2,1,1,1,2000,2000,2500,2),(3,1,1,1,3000,3000,3500,1)");
                s.executeUpdate("INSERT INTO recording_tone VALUES(1,0,601000,NULL,NULL,0,200,NULL)," +
                    "(1,1,901000,NULL,NULL,200,200,NULL),(2,0,900000,NULL,NULL,0,200,NULL)," +
                    "(2,1,600000,NULL,NULL,200,200,NULL),(3,0,700000,600000,NULL,0,200,NULL)");
            }
            assertEquals(List.of(3000L,2000L,1000L),starts(store,base().pagingTones("detected").build()));
            assertEquals(List.of(3000L,2000L,1000L),starts(store,base().toneFrequencyHz(600.).toneToleranceHz(2.).build()));
            assertEquals(List.of(1000L),starts(store,base().toneFrequencyHz(600.).toneThenHz(900.).toneToleranceHz(2.).build()));
            assertEquals(List.of(2000L),starts(store,base().toneFrequencyHz(900.).toneThenHz(600.).toneToleranceHz(0.).build()));
            assertTrue(store.storeTranscript(1,"dispatch one",2000));
            assertEquals(List.of(1000L),starts(store,base().pagingTones("detected").transcript("dispatch")
                .radioRole("source").radioSelections(List.of(new Selection(10,A,null)))
                .talkgroupSelections(List.of(new Selection(101,A,null))).minDurationMs(500L).maxDurationMs(1500L)
                .protocol("APCO25").callType("GROUP").voiceType("CLEAR").frequencyHz(851012500L).build()));
            var query = store.singleIdentityCandidateQueries(base().pagingTones("detected").build()).getFirst();
            String plan = plan(db,query);
            assertTrue(plan.contains("SCAN tone_driver"),plan);
            assertTrue(plan.contains("SEARCH c USING INTEGER PRIMARY KEY"),plan);
            assertFalse(plan.contains("SCAN c"),plan);
        }
    }

    @Test
    void orderedIndexStreamsKeepMultiSelectionsAndCursorBoundsOverWideArchives() throws Exception
    {
        Path db = temporary.resolve("wide.sqlite");
        try(ManagedRecordingStore store = new ManagedRecordingStore(db,temporary.resolve("audio")))
        {
            store.insert(call(1000,A,10,101,1,List.of(),List.of()));
            try(var c = DriverManager.getConnection("jdbc:sqlite:"+db); var s = c.createStatement())
            {
                s.executeUpdate("WITH RECURSIVE n(x) AS (VALUES(2) UNION ALL SELECT x+1 FROM n WHERE x<50000) " +
                    "INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes,system_id,protocol,call_type,voice_type," +
                    "source_id,target_id,source_home_wacn,source_home_system,source_home_id) " +
                    "SELECT x*1000,x*1000+1000,1000,'wide-'||x,5,1,1,1,1,10,100+(x%3),2,2,77 FROM n");
            }
            List<Selection> groups = List.of(new Selection(101,A,null),new Selection(102,A,null));
            var firstFilter = SearchFilter.builder().fromMs(0L).toMs(50_000_000L).talkgroupSelections(groups).limit(3).build();
            var first = store.search(firstFilter);
            assertEquals(List.of(50_000_000L,49_999_000L,49_997_000L),first.calls().stream().map(c -> c.startMs()).toList());
            var secondFilter = SearchFilter.builder().fromMs(0L).toMs(50_000_000L).talkgroupSelections(groups)
                .limit(3).cursor(first.nextCursor()).build();
            assertEquals(List.of(49_996_000L,49_994_000L,49_993_000L),starts(store,secondFilter));
            for(var filter: List.of(firstFilter,secondFilter,SearchFilter.builder().fromMs(0L).toMs(50_000_000L)
                .radioSelections(List.of(new Selection(10,A,"v1-r-00002-002-77"))).radioRole("source").build(),
                base().anyIdentityIds(List.of(10,101,102)).build(),base().talkgroupMin(100).talkgroupMax(102).build(),
                base().voiceType("ENCRYPTED").build(),base().protocol("APCO25").build(),base().callType("DIRECT").build()))
            {
                for(var query: store.singleIdentityCandidateQueries(filter))
                {
                    String plan = plan(db,query);
                    assertFalse(plan.contains("USE TEMP B-TREE"),plan);
                    assertFalse(plan.contains("SCAN c"),plan);
                    assertTrue(query.sql().endsWith("LIMIT ?"));
                    if(filter.cursor != null)
                    {
                        assertTrue(query.sql().contains("<=?"),query.sql());
                        assertTrue(query.sql().contains(")<(?,?)"),query.sql());
                    }
                }
            }
        }
    }

    @Test
    void partialSiteSearchesMergeBoundedOrderedStreamsWithoutDuplicatingMultiSiteCalls() throws Exception
    {
        Path db = temporary.resolve("sites.sqlite");
        Site a = new Site(1,1,1,1), b = new Site(1,1,1,2), other = new Site(2,2,1,1);
        try(ManagedRecordingStore store = new ManagedRecordingStore(db,temporary.resolve("audio")))
        {
            store.insert(call(1000,A,10,101,1,List.of(a,b),List.of()));
            store.insert(call(2000,A,10,101,1,List.of(a),List.of()));
            store.insert(call(3000,B,10,101,1,List.of(other),List.of()));
            store.insert(call(4000,A,10,101,1,List.of(b),List.of()));
            var firstFilter = base().wacn(1).limit(2).build();
            var first = store.search(firstFilter);
            assertEquals(List.of(4000L,2000L),first.calls().stream().map(call -> call.startMs()).toList());
            assertEquals(List.of(1000L),starts(store,base().wacn(1).limit(2).cursor(first.nextCursor()).build()));
            assertEquals(List.of(),starts(store,base().wacn(3).build()));
            for(var query:store.singleIdentityCandidateQueries(firstFilter))
            {
                String plan = plan(db,query);
                assertTrue(plan.contains("idx_recording_call_site_lookup"),plan);
                assertFalse(plan.contains("USE TEMP B-TREE"),plan);
                assertFalse(plan.contains("SCAN c"),plan);
            }
            for(var query:store.singleIdentityCandidateQueries(base().systemKey(A).build()))
            {
                String plan = plan(db,query);
                assertTrue(plan.contains(query.sql().contains("INDEXED BY idx_recording_call_unowned_time") ?
                    "idx_recording_call_unowned_time" : "idx_recording_call_system_time"),plan);
                assertFalse(plan.contains("USE TEMP B-TREE"),plan);
            }
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+db); var s=c.createStatement())
            {
                s.executeUpdate("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<129) " +
                    "INSERT INTO recording_site(wacn,system_id,rfss,site_id) SELECT 1,1,2,x FROM n");
                s.executeUpdate("INSERT INTO recording_call_site SELECT 3,id,3000 FROM recording_site " +
                    "WHERE wacn=1 AND system_id=1 AND rfss=2 AND site_id=129");
            }
            assertEquals(List.of(4000L,3000L,2000L,1000L),starts(store,base().wacn(1).build()));
            var fallback = store.singleIdentityCandidateQueries(base().wacn(1).build());
            assertEquals(1,fallback.size());
            assertTrue(fallback.getFirst().sql().contains("idx_recording_call_time"));
        }
    }

    @Test
    void maximumPickerSelectionsKeepEachDriverStatementCompactAndPreserveTheOtherIdentityKind() throws Exception
    {
        Path db = temporary.resolve("maximum-selections.sqlite");
        try(ManagedRecordingStore store = new ManagedRecordingStore(db,temporary.resolve("audio")))
        {
            List<Selection> rawGroups = new ArrayList<>(), groups = new ArrayList<>(), radios = new ArrayList<>();
            for(int i=1;i<=100;i++)
            {
                rawGroups.add(new Selection(i,A,null));
                groups.add(new Selection(i,A,"v1-g-00001-001-"+i));
                radios.add(new Selection(i,A,"v1-r-00001-001-"+i));
            }
            var rawFilter = base().talkgroupSelections(rawGroups).build();
            var rawQueries = store.singleIdentityCandidateQueries(rawFilter);
            assertEquals(200,rawQueries.size());
            for(var query:rawQueries)
            {
                assertTrue(query.parameters().size()<40,"A raw driver should contain just its one scoped choice");
                assertTrue(query.sql().length()<7000);
            }
            store.insert(call(1000,A,500,600,1,List.of(),List.of()));
            store.insert(call(2000,A,501,601,1,List.of(),List.of()));
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var statement=c.createStatement())
            {
                statement.executeUpdate("UPDATE recording_call SET source_home_wacn=1,source_home_system=1," +
                    "source_home_id=1,target_home_wacn=1,target_home_system=1,target_home_id=2 WHERE start_ms=1000");
                statement.executeUpdate("UPDATE recording_call SET source_home_wacn=1,source_home_system=1," +
                    "source_home_id=101,target_home_wacn=1,target_home_system=1,target_home_id=2 WHERE start_ms=2000");
            }
            var combined = base().talkgroupSelections(groups).radioSelections(radios).build();
            var combinedQueries = store.singleIdentityCandidateQueries(combined);
            assertEquals(400,combinedQueries.size());
            long sqlBytes=0;
            for(var query:combinedQueries)
            {
                assertTrue(query.parameters().size()<1000,"Opposite-kind choices use compact canonical tuple membership");
                assertTrue(query.sql().length()<15000);
                sqlBytes+=query.sql().length();
            }
            assertTrue(sqlBytes<6_000_000,"Building the maximum query must not repeat both 100-choice OR trees");
            assertEquals(List.of(1000L),starts(store,combined));
            assertEquals(List.of(),starts(store,base().talkgroupSelections(rawGroups).build()));
        }
    }

    @Test
    void cancelledSearchesAndInvalidInputsAreBounded() throws Exception
    {
        try(ManagedRecordingStore store = new ManagedRecordingStore(temporary.resolve("cancel.sqlite"),temporary.resolve("audio")))
        {
            Thread.currentThread().interrupt();
            try { assertThrows(SQLException.class,() -> store.search(base().build())); }
            finally { Thread.interrupted(); }
        }
        assertThrows(IllegalArgumentException.class,() -> base().toneFrequencyHz(Double.NaN).build());
        assertThrows(IllegalArgumentException.class,() -> base().toneThenHz(900.).build());
        assertThrows(IllegalArgumentException.class,() -> base().toneToleranceHz(1001.).build());
        assertThrows(IllegalArgumentException.class,() -> base().pagingTones("none").build());
        assertThrows(IllegalArgumentException.class,() -> base().radioRole("destination").build());
        assertThrows(IllegalArgumentException.class,() -> base().radioSelections(List.of(new Selection(1,A,"v1-g-00001-001-1"))).build());
        assertThrows(IllegalArgumentException.class,() -> base().talkgroupSelections(java.util.Collections.nCopies(101,new Selection(1,A,null))).build());
    }

    private static SearchFilter.Builder base() { return SearchFilter.builder().fromMs(0L).toMs(10000L); }
    private static List<Long> starts(ManagedRecordingStore store, SearchFilter filter) throws SQLException
    { return store.search(filter).calls().stream().map(call -> call.startMs()).toList(); }
    private static ManagedRecordingMetadata call(long start,String system,int source,int target,int type,List<Site> sites,List<Member> members)
    {
        return new ManagedRecordingMetadata(start,start+1000,1000,5,start+".mp3",system,"channel-a",7,1,type,1,
            source,null,null,null,target,null,null,null,851012500L,1,0x293,null,null,null,sites,members,null);
    }
    private static String plan(Path db,ManagedRecordingStore.CandidateQuery query) throws Exception
    {
        StringBuilder result = new StringBuilder();
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db); var s=c.prepareStatement("EXPLAIN QUERY PLAN "+query.sql()))
        {
            int i=1; for(Object value:query.parameters()) s.setObject(i++,value);
            try(var rows=s.executeQuery()) { while(rows.next()) result.append(rows.getString("detail")).append('\n'); }
        }
        return result.toString();
    }
}
