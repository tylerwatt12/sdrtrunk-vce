/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.SearchFilter;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingTranscriptSearchTest
{
    @TempDir Path temporary;

    @Test
    void indexedWordsPhrasesAndBooleanQueriesRespectScopeAndChronologicalCursors() throws Exception
    {
        Path db = temporary.resolve("text.sqlite");
        try(ManagedRecordingStore store = new ManagedRecordingStore(db,temporary.resolve("audio")))
        {
            for(int i=1;i<=6;i++) store.insert(call(i*1000L));
            List<String> text=List.of("Fire alarm at central station","firefighter medical at station",
                "Medical response alarm fire","FIRE alarm near school","");
            for(int i=0;i<text.size();i++) assertTrue(store.storeTranscript(i+1,text.get(i),9000));
            assertEquals(List.of(4000L,3000L,1000L),starts(store,base().transcript("fire alarm").build()));
            assertEquals(List.of(4000L,1000L),starts(store,base().transcript("\"fire alarm\"").build()));
            assertEquals(List.of(4000L,3000L,2000L,1000L),starts(store,base().transcript("fire OR medical").build()));
            assertEquals(List.of(4000L,3000L,1000L),starts(store,base().transcript("fire AND (alarm OR dispatch)").build()));
            assertEquals(List.of(3000L),starts(store,base().fromMs(2000L).toMs(3000L).transcript("fire").build()));
            assertEquals(List.of(4000L,3000L,2000L,1000L),starts(store,base().hasTranscript(true).build()));
            assertEquals(List.of(6000L,5000L),starts(store,base().hasTranscript(false).build()));
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.prepareStatement(
                "UPDATE recording_transcript SET text=? WHERE call_id=5"))
            {
                s.setString(1," \t\n\u2003\u3000 ");s.executeUpdate();
            }
            assertEquals(List.of(4000L,3000L,2000L,1000L),starts(store,base().hasTranscript(true).build()));
            assertEquals(List.of(6000L,5000L),starts(store,base().hasTranscript(false).build()));
            assertTrue(starts(store,base().transcript("fire").hasTranscript(false).build()).isEmpty());
            var first=store.search(base().transcript("fire").talkgroupId(101).limit(1).build());
            assertEquals(4000,first.calls().getFirst().startMs());
            var second=store.search(base().transcript("fire").talkgroupId(101).limit(1).cursor(first.nextCursor()).build());
            assertEquals(3000,second.calls().getFirst().startMs());
            var last=store.search(base().transcript("fire").talkgroupId(101).limit(1).cursor(second.nextCursor()).build());
            assertEquals(1000,last.calls().getFirst().startMs()); assertNull(last.nextCursor());
            var ascending=store.search(base().transcript("fire").sortAscending(true).limit(1).build());
            assertEquals(1000,ascending.calls().getFirst().startMs());
            assertEquals(List.of(3000L,4000L),starts(store,base().transcript("fire").sortAscending(true)
                .cursor(ascending.nextCursor()).build()));
            var queries=store.singleIdentityCandidateQueries(base().transcript("fire").build());
            assertEquals(1,queries.size());
            var query=queries.getFirst();
            assertTrue(query.sql().contains("recording_transcript_fts MATCH ?"));
            assertFalse(query.sql().contains("instr("));
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+db);
                var statement=connection.prepareStatement("EXPLAIN QUERY PLAN "+query.sql()))
            {
                int p=1;for(Object value:query.parameters()) statement.setObject(p++,value);
                StringBuilder plan=new StringBuilder();
                try(var rows=statement.executeQuery()) { while(rows.next()) plan.append(rows.getString(4)).append('\n'); }
                assertTrue(plan.toString().contains("VIRTUAL TABLE INDEX") && plan.toString().contains(":M"),plan.toString());
            }
        }
    }

    @Test
    void malformedOrExcessiveSyntaxIsRejectedBeforeSQLiteAndTextCannotBecomeSqlOrFtsOperators()
    {
        for(String invalid:List.of("AND fire","fire OR","()","(fire","\"fire","fire AND OR alarm",
            "fire*","title:fire",")))","\"\"","x".repeat(241),"a ".repeat(33),"(".repeat(9)+"fire"+")".repeat(9)))
            assertThrows(IllegalArgumentException.class,()->base().transcript(invalid).build(),invalid);
        assertEquals("(\"fire\" AND \"alarm\")",ManagedRecordingTranscriptQuery.compile("fire alarm"));
        assertEquals("\"100%_\"",ManagedRecordingTranscriptQuery.compile("100%_"));
        assertEquals("(\"fire'\" OR \"1=1\")",ManagedRecordingTranscriptQuery.compile("fire' OR 1=1"));
    }

    private static SearchFilter.Builder base() { return SearchFilter.builder().fromMs(0L).toMs(10000L); }
    private static List<Long> starts(ManagedRecordingStore store,SearchFilter filter) throws Exception
    { return store.search(filter).calls().stream().map(ManagedRecordingCatalog.RecordingCall::startMs).toList(); }
    private static ManagedRecordingMetadata call(long start)
    { return new ManagedRecordingMetadata(start,start+1000,1000,5,start+".mp3","p25:00001:001","channel-a",7,1,1,1,
        10,null,null,null,101,null,null,null,851012500L,1,0x293,null,null,null,List.of(),List.of(),null); }
}
