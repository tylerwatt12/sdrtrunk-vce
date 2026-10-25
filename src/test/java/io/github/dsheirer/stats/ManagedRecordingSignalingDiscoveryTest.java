package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingSignalingDiscoveryTest
{
    @TempDir Path directory;

    @Test
    void auxiliaryBrowsersKeepNamespacesRolesActualCountsAliasesAndPrimaryAudioProtocol() throws Exception
    {
        Path main=directory.resolve("main.sqlite"), catalogPath=directory.resolve("recordings.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(main);
        try(ManagedRecordingCatalog catalog=new ManagedRecordingCatalog(catalogPath,directory.resolve("audio")))
        {
            try(var connection=StatsPairedTestDatabase.open(main);var statement=connection.createStatement())
            {
                statement.execute("INSERT INTO alias_list(id,name,family) VALUES(7,'Signaling','NBFM')");
                statement.execute("INSERT INTO alias(alias_list_id,matcher_type,protocol,value,name,description,group_name) VALUES"+
                    "(7,'TALKGROUP','MDC1200',77,'Engine MDC','Unit ANI','Fire'),"+
                    "(7,'TALKGROUP','FLEETSYNC',77,'Fleet source','Fleet ANI','Fleet')");
                statement.execute("INSERT INTO alias(alias_list_id,matcher_type,text_value,name) "+
                    "VALUES(7,'ESN','aBc123','Old transponder'),(7,'ESN','ABC123','Transponder'),"+
                    "(7,'ESN','xyz*','Literal ESN wildcard')");
            }
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+catalogPath);var statement=connection.createStatement())
            {
                statement.execute("INSERT INTO recording_system(id,system_key) VALUES(1,'p25:00001:001')");
                statement.execute("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes,alias_list_id,"+
                    "system_id,protocol,call_type,voice_type,source_id,target_id) VALUES"+
                    "(1,1000,2000,1000,'1.mp3',5,7,NULL,5,0,1,77,NULL),(2,2000,3000,1000,'2.mp3',5,7,NULL,6,0,1,77,NULL),"+
                    "(3,3000,4000,1000,'3.mp3',5,7,1,1,1,1,77,101),(4,4000,5000,1000,'4.mp3',5,7,NULL,5,0,1,77,NULL)");
                statement.execute("INSERT INTO recording_identifier VALUES"+
                    "(1,'MDC1200','radio','source','77',1000),(1,'MDC1200','radio','destination','77',1000),"+
                    "(1,'FLEETSYNC','radio','source','77',1000),(1,'DCS','tone','other','D023',1000),"+
                    "(2,'TAIT1200','radio','destination','UNIT-A',2000),(2,'LOJACK','radio','destination','ABC123',2000),"+
                    "(2,'LOJACK','function','other','TEST',2000),(3,'MDC1200','radio','source','77',3000),"+
                    "(4,'LOJACK','radio','other','XYZ9',4000)");
            }
            ManagedRecordingIdentityDiscovery discovery=new ManagedRecordingIdentityDiscovery(main);
            var all=rows(discovery.search(catalogPath,"radio",null,null,0L,10000L,0,100,"any","calls"));
            var mdc=all.stream().filter(row->"MDC1200".equals(row.get("protocol"))&&"77".equals(row.get("value"))&&row.get("system_key")==null)
                .findFirst().orElseThrow();
            assertEquals(1L,mdc.get("call_count"),"Source and destination facts from the same call count once");
            assertEquals("Engine MDC",mdc.get("label")); assertEquals(List.of("NBFM"),mdc.get("primary_protocols"));
            assertEquals(2,rows(discovery.search(catalogPath,"radio","Unit ANI",null,0L,10000L,0,100,"any","name")).size());
            assertEquals(2,rows(discovery.search(catalogPath,"radio","Engine MDC",null,0L,10000L,0,100,"any","name")).size());
            var source=rows(discovery.search(catalogPath,"radio",null,null,0L,10000L,0,100,"source","calls"));
            assertTrue(source.stream().noneMatch(row->"TAIT1200".equals(row.get("protocol"))));
            assertTrue(source.stream().noneMatch(row->"LOJACK".equals(row.get("protocol"))));
            assertTrue(all.stream().noneMatch(row->"DCS".equals(row.get("protocol"))));
            assertTrue(all.stream().noneMatch(row->"TEST".equals(row.get("value"))));
            var lojack=all.stream().filter(row->"LOJACK".equals(row.get("protocol"))).findFirst().orElseThrow();
            assertEquals("Transponder",lojack.get("label"),"Existing ESN aliases match case-insensitively and the last saved duplicate wins");
            assertEquals("XYZ9",all.stream().filter(row->"XYZ9".equals(row.get("value"))).findFirst().orElseThrow()
                .get("label"),"Runtime ESN lookup is literal and case-insensitive, not a prefix wildcard search");
            var tait=all.stream().filter(row->"TAIT1200".equals(row.get("protocol"))).findFirst().orElseThrow();
            assertEquals("UNIT-A",tait.get("label"));
            assertEquals("UNIT-A",((Map<?,?>)tait.get("selection")).get("value"));
            assertNull(((Map<?,?>)tait.get("selection")).get("id"));
            assertEquals(1L,all.stream().filter(row->"NBFM".equals(row.get("protocol"))&&row.get("value")==null)
                .findFirst().orElseThrow().get("call_count"),"Historical untyped source numbers remain available");
            assertEquals(1,rows(discovery.search(catalogPath,"radio",null,"p25:00001:001",0L,10000L,0,100,"source","calls"))
                .stream().filter(row->row.get("value")==null).count(),"Unrelated auxiliary facts do not replace a native source radio");
            assertTrue(rows(discovery.search(catalogPath,"radio",null,null,5000L,6000L,0,100,"any","calls")).isEmpty());
        }
    }

    @Test
    void numericUnknownReceiverChoicesSeparateProtocolsAndSortBeforePagination() throws Exception
    {
        Path catalogPath=directory.resolve("namespaces.sqlite");
        try(ManagedRecordingCatalog catalog=new ManagedRecordingCatalog(catalogPath,directory.resolve("namespaces-audio"));
            var connection=DriverManager.getConnection("jdbc:sqlite:"+catalogPath);var statement=connection.createStatement())
        {
            statement.execute("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes,protocol,"+
                "call_type,voice_type,source_id,target_id) VALUES"+
                "(1000,2000,1000,'1.mp3',5,3,1,1,77,10),(2000,3000,1000,'2.mp3',5,3,1,1,77,10),"+
                "(3000,4000,1000,'3.mp3',5,4,1,1,77,10),(4000,5000,1000,'4.mp3',5,4,1,1,88,0)");
            ManagedRecordingIdentityDiscovery discovery=new ManagedRecordingIdentityDiscovery(directory.resolve("absent.sqlite"));
            var calls=rows(discovery.search(catalogPath,"talkgroup",null,null,0L,10000L,0,1,"any","calls"));
            assertEquals("DMR",calls.getFirst().get("protocol")); assertEquals(2L,calls.getFirst().get("call_count"));
            var recent=rows(discovery.search(catalogPath,"talkgroup",null,null,0L,10000L,0,1,"any","recent"));
            assertEquals(0,recent.getFirst().get("id"));
            assertEquals("NXDN",((Map<?,?>)recent.getFirst().get("selection")).get("protocol"));
            var radios=rows(discovery.search(catalogPath,"radio","77",null,0L,10000L,0,100,"source","calls"));
            assertEquals(2,radios.size());
            assertEquals("DMR",((Map<?,?>)radios.getFirst().get("selection")).get("protocol"));
            assertEquals("NXDN",((Map<?,?>)radios.getLast().get("selection")).get("protocol"));
        }
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> page)
    { return (List<Map<String,Object>>)page.get("rows"); }
}
