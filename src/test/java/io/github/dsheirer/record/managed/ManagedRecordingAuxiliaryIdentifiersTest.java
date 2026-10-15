/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.AuxiliaryIdentifier;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.Selection;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.SearchFilter;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingAuxiliaryIdentifiersTest
{
    @TempDir Path directory;
    private static final String A="channel:aux-a",B="channel:aux-b";
    private static AuxiliaryIdentifier radio(String protocol,String role,String value)
    { return new AuxiliaryIdentifier(protocol,"radio",role,value); }
    private static Selection selected(String system,String protocol,String value)
    { return new Selection(null,system,null,protocol,value); }
    private static SearchFilter.Builder base() { return SearchFilter.builder().fromMs(0L).toMs(10000L); }
    private static List<Long> starts(ManagedRecordingStore store,SearchFilter filter) throws Exception
    { return store.search(filter).calls().stream().map(call->call.startMs()).toList(); }
    private static ManagedRecordingMetadata call(long start,String system,int protocol,Integer toneKind,String tone,
                                                  List<AuxiliaryIdentifier> identifiers)
    {
        return new ManagedRecordingMetadata(start,start+1000,1000,5,start+".mp3",system,"channel-a",7,
            protocol,0,1,123,null,null,null,null,null,null,null,851012500L,null,null,toneKind,tone,
            null,List.of(),List.of(),null,identifiers);
    }

    @Test
    void typedRadioSelectionsKeepNamespaceRoleSystemAndExactStringValues() throws Exception
    {
        Path db=directory.resolve("auxiliary.sqlite");
        try(ManagedRecordingStore store=new ManagedRecordingStore(db,directory.resolve("audio")))
        {
            store.insert(call(1000,A,5,null,null,List.of(radio("MDC1200","source","123"),radio("MDC1200","destination","123"))));
            store.insert(call(2000,A,5,null,null,List.of(radio("FLEETSYNC","source","123"))));
            store.insert(call(3000,A,5,null,null,List.of(radio("MDC1200","destination","123"))));
            store.insert(call(4000,B,5,null,null,List.of(radio("MDC1200","source","123"))));
            store.insert(call(5000,null,5,null,null,List.of(radio("TAIT1200","source","007A"))));
            store.insert(call(6000,A,5,null,null,List.of()));
            var mdc=selected(A,"MDC1200","123");
            assertEquals(List.of(1000L),starts(store,base().radioSelections(List.of(mdc)).radioRole("source").build()));
            assertEquals(List.of(3000L,1000L),starts(store,base().radioSelections(List.of(mdc)).build()));
            assertEquals(List.of(3000L,2000L,1000L),starts(store,base().radioSelections(List.of(mdc,
                selected(A,"FLEETSYNC","123"))).build()));
            assertEquals(List.of(5000L),starts(store,base().radioSelections(List.of(selected(null,"TAIT1200","007A"))).build()));
            assertTrue(starts(store,base().radioSelections(List.of(selected(null,"TAIT1200","007a"))).build()).isEmpty());
            assertTrue(starts(store,base().radioSelections(List.of(selected(null,"MDC1200","123"))).build()).isEmpty());
            assertEquals(List.of(1000L),starts(store,base().radioSelections(List.of(mdc)).radioRole("source")
                .sourceId(123).protocol("NBFM").frequencyHz(851012500L).channelId("channel-a")
                .minDurationMs(1000L).maxDurationMs(1000L).build()));
            var first=store.search(base().radioSelections(List.of(mdc)).limit(1).build());
            assertEquals(3000L,first.calls().getFirst().startMs());
            assertEquals(List.of(1000L),starts(store,base().radioSelections(List.of(mdc)).limit(1).cursor(first.nextCursor()).build()));
            var restored=store.find(1L);
            assertEquals("NBFM",restored.protocol());
            assertEquals(123,restored.sourceId());
            assertEquals(2,restored.auxiliaryIdentifiers().size());
            assertTrue(restored.toMap().containsKey("auxiliary_identifiers"));
            for(var query:store.singleIdentityCandidateQueries(base().radioSelections(List.of(mdc)).build()))
            {
                try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var statement=c.prepareStatement("EXPLAIN QUERY PLAN "+query.sql()))
                {
                    int i=1;for(Object value:query.parameters())statement.setObject(i++,value);
                    StringBuilder details=new StringBuilder();try(var rows=statement.executeQuery())
                    {while(rows.next())details.append(rows.getString("detail")).append('\n');}
                    assertTrue(details.toString().contains("idx_recording_identifier_lookup"),details.toString());
                    assertFalse(details.toString().contains("USE TEMP B-TREE"),details.toString());
                }
            }
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var statement=c.createStatement())
            {
                statement.execute("PRAGMA foreign_keys=ON");statement.executeUpdate("DELETE FROM recording_call WHERE id=1");
                try(var rows=statement.executeQuery("SELECT count(*) FROM recording_identifier WHERE call_id=1"))
                {assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            }
        }
    }

    @Test
    void numericPickerNamespacesSeparateUnknownSystemsAndAChannelsProtocolHistory() throws Exception
    {
        try(ManagedRecordingStore store=new ManagedRecordingStore(directory.resolve("namespaces.sqlite"),directory.resolve("audio")))
        {
            int[] protocols={3,4,1,2,3,4,4,3};
            for(int i=0;i<protocols.length;i++)
                store.insert(new ManagedRecordingMetadata((i+1)*1000L,(i+2)*1000L,1000,5,i+".mp3",i<4?null:A,
                    "same-saved-channel",7,protocols[i],1,1,i==6?999:123,null,null,null,i==6?0:456,null,null,null,
                    851012500L,null,null,null,null,null,List.of(),List.of(),null));
            var unknownDmr=new Selection(123,null,null,"DMR",null);
            var unknownNxdn=new Selection(123,null,null,"NXDN",null);
            var unknownP25=new Selection(123,null,null,"APCO25_PHASE2",null);
            assertEquals("APCO25",unknownP25.protocol());
            assertEquals(List.of(1000L),starts(store,base().radioSelections(List.of(unknownDmr)).build()));
            assertEquals(List.of(2000L),starts(store,base().radioSelections(List.of(unknownNxdn)).build()));
            assertEquals(List.of(4000L,3000L),starts(store,base().radioSelections(List.of(unknownP25)).build()));
            assertEquals(List.of(2000L,1000L),starts(store,base().radioSelections(List.of(unknownDmr,unknownNxdn)).build()));
            assertEquals(List.of(6000L),starts(store,base().radioSelections(List.of(new Selection(123,A,null,"NXDN",null))).limit(1).build()));
            assertEquals(List.of(8000L,5000L),starts(store,base().radioSelections(List.of(new Selection(123,A,null,"DMR",null))).build()));
            assertEquals(List.of(1000L),starts(store,base().talkgroupSelections(List.of(new Selection(456,null,null,"DMR",null))).build()));
            assertEquals(List.of(7000L),starts(store,base().talkgroupSelections(List.of(new Selection(0,A,null,"NXDN",null))).build()));
            assertEquals(List.of(4000L,3000L,2000L,1000L),starts(store,base().radioSelections(List.of(new Selection(123,null,null))).build()));
        }
        assertThrows(IllegalArgumentException.class,()->base().talkgroupSelections(List.of(new Selection(0,null,null,"APCO25",null))).build());
        assertThrows(IllegalArgumentException.class,()->base().radioSelections(List.of(new Selection(0,null,null,"NXDN",null))).build());
    }

    @Test
    void auxiliaryProtocolFiltersMatchExplicitFactsAndPreservedDcsEvidenceWithoutReplacingAudio() throws Exception
    {
        try(ManagedRecordingStore store=new ManagedRecordingStore(directory.resolve("protocols.sqlite"),directory.resolve("audio")))
        {
            store.insert(call(1000,A,5,null,null,List.of(radio("MDC1200","source","123"))));
            store.insert(call(2000,A,5,null,null,List.of(radio("FLEETSYNC","source","123"))));
            store.insert(call(3000,A,5,null,null,List.of(new AuxiliaryIdentifier("DCS","tone","source","N023"))));
            store.insert(call(4000,A,7,null,null,List.of()));
            store.insert(call(5000,A,5,1,"DCS-023",List.of()));
            store.insert(call(6000,A,0,null,null,List.of()));
            assertEquals(List.of(5000L,4000L,3000L),starts(store,base().protocol("DCS").build()));
            assertEquals(List.of(5000L,3000L,2000L,1000L),starts(store,base().protocol("NBFM").build()));
            assertEquals(List.of(1000L),starts(store,base().protocol("MDC1200").build()));
            assertEquals(List.of(2000L),starts(store,base().protocol("FLEETSYNC").build()));
            assertEquals(List.of(6000L),starts(store,base().protocol("UNKNOWN").build()));
            assertTrue(starts(store,base().protocol("TAIT1200").build()).isEmpty());
            assertTrue(starts(store,base().protocol("LOJACK").build()).isEmpty());
        }
        for(String value:List.of("ARS","LRRP","MPT1327","IPV4","UDP","CELLOCATOR","bogus"))
            assertThrows(IllegalArgumentException.class,()->base().protocol(value).build(),value);
        for(String value:List.of("DCS","FLEETSYNC","MDC1200","LOJACK","TAIT1200"))
            assertTrue(ManagedRecordingCatalog.protocolFilterCode(value)>=7);
        assertThrows(IllegalArgumentException.class,()->new Selection(null,A,null,"DCS","N023"));
        assertThrows(IllegalArgumentException.class,()->new Selection(123,A,null,"MDC1200","123"));
        assertThrows(IllegalArgumentException.class,()->base().talkgroupSelections(List.of(selected(A,"MDC1200","123"))).build());
        assertThrows(IllegalArgumentException.class,()->new AuxiliaryIdentifier("TAIT1200","radio","source","bad\nvalue"));
    }
}
