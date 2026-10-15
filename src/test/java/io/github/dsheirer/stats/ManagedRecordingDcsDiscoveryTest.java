package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingDcsDiscoveryTest
{
    @TempDir Path directory;

    @Test
    void countsCapturedAndLegacyCodesOnceAndKeepsInversionTimeAndReceiverScope() throws Exception
    {
        Path db=directory.resolve("recordings.sqlite");
        try(ManagedRecordingCatalog catalog=new ManagedRecordingCatalog(db,directory.resolve("audio"));
            var connection=DriverManager.getConnection("jdbc:sqlite:"+db);var statement=connection.createStatement())
        {
            statement.execute("INSERT INTO recording_system VALUES(1,'p25:00001:001')");
            statement.execute("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes,"+
                "protocol,call_type,voice_type,system_id,tone_kind,tone) VALUES"+
                "(1,1000,2000,1000,'1.mp3',5,5,0,1,NULL,1,'DCS-023'),"+
                "(2,2000,3000,1000,'2.mp3',5,5,0,1,NULL,1,'023'),"+
                "(3,3000,4000,1000,'3.mp3',5,5,0,1,NULL,1,'DCS-023 Inverted'),"+
                "(4,4000,5000,1000,'4.mp3',5,1,1,1,1,NULL,NULL),"+
                "(5,5000,6000,1000,'5.mp3',5,5,0,1,NULL,1,' N023 ')");
            statement.execute("INSERT INTO recording_identifier VALUES(1,'DCS','tone','other','N023',1000),"+
                "(3,'DCS','tone','other','I023',3000),(4,'DCS','tone','other','N023',4000),"+
                "(5,'DCS','tone','other','DCS-023',5000)");
            var discovery=new ManagedRecordingEntityDiscovery(directory.resolve("missing.sqlite"));
            var rows=rows(discovery.search(db,"dcs",null,null,0L,10000L,0,100,"calls"));
            assertEquals(2,rows.size());
            assertEquals("N023",rows.getFirst().get("id"));
            assertEquals(3L,rows.getFirst().get("call_count"));
            assertEquals(Map.of("dcs_code","N023"),rows.getFirst().get("selection"));
            assertEquals("I023",rows(discovery.search(db,"dcs","inverted",null,0L,10000L,0,100,"name"))
                .getFirst().get("id"));
            assertEquals(1L,rows(discovery.search(db,"dcs",null,"p25:00001:001",0L,10000L,0,100,"calls"))
                .getFirst().get("call_count"));
            assertTrue(rows(discovery.search(db,"dcs",null,null,6000L,10000L,0,100,"calls")).isEmpty());
        }
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> page)
    { return (List<Map<String,Object>>)page.get("rows"); }
}
