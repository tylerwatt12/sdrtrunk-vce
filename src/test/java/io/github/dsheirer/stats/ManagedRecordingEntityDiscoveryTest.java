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

class ManagedRecordingEntityDiscoveryTest
{
    @TempDir Path directory;
    private static final String A="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String B="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String C="cccccccc-cccc-cccc-cccc-cccccccccccc";

    @Test
    void browsersUseActualCallsSortBeforePagingAndRetainHeardAtAndChannelSiteSemantics() throws Exception
    {
        Path main=directory.resolve("main.sqlite"), catalogPath=directory.resolve("recordings.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(main);
        try(ManagedRecordingCatalog catalog=new ManagedRecordingCatalog(catalogPath,directory.resolve("audio")))
        {
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+main);var statement=connection.createStatement())
            {
                statement.execute("INSERT INTO alias_list(id,name,family) VALUES(7,'P25','P25'),(8,'DMR','DMR'),(9,'Analog','NBFM')");
                for(String[] row: List.of(new String[]{A,"Metro Control","Metro","North","P25_PHASE1"},
                    new String[]{B,"Utilities","Utility system","West","DMR"},
                    new String[]{C,"Analog local","Local services","East","NBFM"}))
                    statement.execute("INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,"+
                        "system_name,site_name,decoder_type,alias_list_id,primary_frequency_hz,config_json) VALUES('"+row[0]+
                        "','"+(row[4].equals("P25_PHASE1") ? "TRUNKED" : "CONVENTIONAL")+"',0,'"+row[1]+"','"+row[2]+"','"+row[3]+
                        "','"+row[4]+"',"+(row[4].equals("DMR") ? 8 : row[4].equals("NBFM") ? 9 : 7)+",851000000,'"+(row[4].equals("DMR") ? "{\"decodeConfiguration\":{\"channelMode\":\"CONVENTIONAL\"}}" : "{}")+"')");
                statement.execute("INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,"+
                    "first_seen_ms,last_seen_ms) VALUES(1,'p25:00001:001',1,0,1,1,1000,2000)");
                statement.execute("INSERT INTO radio_system(id,system_key,configuration_id,protocol_code,address_domain_code,"+
                    "first_seen_ms,last_seen_ms) VALUES(2,'dmr:channel:"+B+"','"+B+"',3,0,1000,2000)");
                statement.execute("INSERT INTO receiver_channel(id,configuration_id,radio_system_id,radio_system_assigned_at_ms,first_seen_ms,last_seen_ms) "+
                    "VALUES(1,'"+A+"',1,1000,1000,2000),(2,'"+B+"',2,1000,1000,2000)");
                statement.execute("INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,protocol,rfss,site) "+
                    "VALUES(1,1000,2000,'APCO25',1,1)");
            }
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+catalogPath);var statement=connection.createStatement())
            {
                statement.execute("INSERT INTO recording_system(id,system_key) VALUES(1,'p25:00001:001'),(2,'dmr:channel:"+B+"')");
                statement.execute("INSERT INTO recording_channel(id,channel_uuid) VALUES(1,'"+A+"'),(2,'"+B+"'),(3,'"+C+"')");
                statement.execute("INSERT INTO recording_site(id,wacn,system_id,rfss,site_id) VALUES(1,1,1,1,1),(2,2,2,2,2)");
                statement.execute("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes,system_id,"+
                    "channel_id,protocol,call_type,voice_type,winner_site_id) VALUES"+
                    "(1,1000,2000,1000,'1.mp3',5,1,1,1,2,1,1),(2,2000,3000,1000,'2.mp3',5,1,1,2,1,1,1),"+
                    "(3,3000,4000,1000,'3.mp3',5,2,2,3,1,1,NULL),(4,4000,5000,1000,'4.mp3',5,NULL,3,5,0,1,NULL),"+
                    "(5,5000,6000,1000,'5.mp3',5,NULL,1,1,1,1,1),(6,6000,7000,1000,'6.mp3',5,NULL,3,6,0,1,NULL),"+
                    "(7,7000,8000,1000,'7.mp3',5,NULL,1,1,1,1,NULL)");
                statement.execute("INSERT INTO recording_call_site(call_id,site_id,start_ms) VALUES"+
                    "(1,1,1000),(1,2,1000),(2,1,2000),(5,1,5000),(7,1,7000),(7,2,7000)");
                statement.execute("INSERT INTO recording_patch_member VALUES(1,1,10,-1,-1,-1,1000),(1,1,11,-1,-1,-1,1000)");
            }
            ManagedRecordingEntityDiscovery discovery=new ManagedRecordingEntityDiscovery(main);
            var systems=rows(discovery.search(catalogPath,"system",null,null,0L,10000L,0,100,"calls"));
            assertEquals(2,systems.size());
            assertEquals("p25:00001:001",systems.getFirst().get("id"));
            assertEquals(3L,systems.getFirst().get("call_count"),"Patches count as one call and conflicting sites prove no owner");
            assertEquals("Metro",systems.getFirst().get("label"));
            assertEquals(Map.of("system_key","p25:00001:001"),systems.getFirst().get("selection"));
            var channels=rows(discovery.search(catalogPath,"channel",null,null,0L,10000L,0,1,"calls"));
            assertEquals(A,channels.getFirst().get("id"));
            assertEquals(4L,channels.getFirst().get("call_count"));
            assertEquals(C,rows(discovery.search(catalogPath,"channel",null,null,0L,10000L,0,1,"name")).getFirst().get("id"));
            assertEquals(A,rows(discovery.search(catalogPath,"channel",null,null,0L,10000L,0,1,"recent")).getFirst().get("id"));
            assertEquals(B,rows(discovery.search(catalogPath,"channel","Utilities",null,0L,10000L,0,1,"name")).getFirst().get("id"));
            var analog=rows(discovery.search(catalogPath,"channel","East",null,0L,10000L,0,100,"calls")).getFirst();
            assertEquals(List.of("AM","NBFM"),analog.get("protocols"));
            assertEquals(Map.of("channel_id",C),analog.get("selection"));
            var sites=rows(discovery.search(catalogPath,"site",null,null,0L,10000L,0,100,"calls"));
            assertEquals(4,sites.size());
            var north=sites.stream().filter(row->"1:1:1:1".equals(row.get("id"))).findFirst().orElseThrow();
            assertEquals(4L,north.get("call_count")); assertEquals("North",north.get("label"));
            assertEquals("heard_at",north.get("count_kind"));
            assertEquals(Map.of("wacn",1,"sysid",1,"rfss",1,"site_id",1),north.get("selection"));
            var east=sites.stream().filter(row->C.equals(row.get("id"))).findFirst().orElseThrow();
            assertEquals("East",east.get("label")); assertEquals("calls",east.get("count_kind"));
            assertEquals(2L,east.get("call_count")); assertEquals(Map.of("channel_id",C),east.get("selection"));
            assertEquals(3L,rows(discovery.search(catalogPath,"channel",null,"p25:00001:001",0L,10000L,0,100,"calls"))
                .getFirst().get("call_count"));
            assertEquals(2L,rows(discovery.search(catalogPath,"channel",null,null,3500L,6500L,0,100,"calls"))
                .getFirst().get("call_count"));
            assertTrue(rows(discovery.search(catalogPath,"system",null,null,8000L,9000L,0,100,"calls")).isEmpty());
            assertTrue(rows(discovery.search(catalogPath,"channel","6000",null,0L,10000L,0,100,"calls")).isEmpty(),
                "Counts and timestamps are not channel search terms");
        }
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> page)
    { return (List<Map<String,Object>>)page.get("rows"); }
}
