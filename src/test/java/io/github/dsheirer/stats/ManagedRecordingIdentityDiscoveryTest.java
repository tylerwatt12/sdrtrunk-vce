package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingIdentityDiscoveryTest
{
    @TempDir Path directory;

    @Test
    void searchesCompleteHierarchyBeforePagingAndRetainsSystemAndHomeIdentity() throws Exception
    {
        Path main = directory.resolve("main.sqlite");
        Path catalogPath = directory.resolve("recordings.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(main);
        try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(catalogPath, directory.resolve("audio")))
        {
            try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + main);
                Statement statement = connection.createStatement())
            {
                statement.execute("INSERT INTO alias_list(id,name,family) VALUES(7,'Regional','P25')");
                statement.execute("INSERT INTO alias(alias_list_id,matcher_type,protocol,value,name,description,group_name) " +
                    "VALUES(7,'TALKGROUP','APCO25',1201,'Fire Dispatch','County operations','Fire / EMS')," +
                    "(7,'RADIO_ID','APCO25',401,'Engine configured','First engine','West')");
                statement.execute("INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code," +
                    "p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) " +
                    "VALUES(1,'p25:00001:001',1,0,1,1,1000,2000)," +
                    "(2,'p25:00002:002',1,0,2,2,1000,2000)");
                statement.execute("INSERT INTO radio_system_identity_summary(radio_system_id,identity_kind_code," +
                    "home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,last_talker_alias," +
                    "last_talker_alias_seen_ms) VALUES(1,2,1,1,401,1000,2000,'Engine OTA',2000)," +
                    "(1,2,2,2,401,1000,2000,'Foreign OTA',2000)");
            }
            try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalogPath);
                Statement statement = connection.createStatement())
            {
                statement.execute("INSERT INTO recording_system(id,system_key) VALUES(1,'p25:00001:001')," +
                    "(2,'p25:00002:002')");
                statement.execute("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                    "system_id,alias_list_id,protocol,call_type,voice_type,source_id,source_home_wacn," +
                    "source_home_system,source_home_id,target_id) VALUES" +
                    "(1000,2000,1000,'one.mp3',5,1,7,1,1,1,401,1,1,401,1201)," +
                    "(2000,3000,1000,'two.mp3',5,2,7,1,1,1,401,NULL,NULL,NULL,1201)," +
                    "(3000,4000,1000,'three.mp3',5,1,7,1,1,1,401,2,2,401,1202)," +
                    "(4000,5000,1000,'four.mp3',5,NULL,NULL,5,1,1,NULL,NULL,NULL,NULL,1201)," +
                    "(5000,6000,1000,'five.mp3',5,1,7,1,3,1,900,NULL,NULL,NULL,401)");
                for(int id = 1; id <= 120; id++)
                    statement.execute("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                        "size_bytes,system_id,protocol,call_type,voice_type,target_id) " +
                        "VALUES(1000,2000,1000,'extra-" + id + ".mp3',5,1,1,1,1," + id + ")");
            }
            ManagedRecordingIdentityDiscovery discovery = new ManagedRecordingIdentityDiscovery(main);
            Map<String,Object> first = discovery.search(catalogPath, "talkgroup", null, null, 0L, 10000L, 0, 1, "any");
            assertEquals(1, rows(first).size());
            assertEquals(true, first.get("has_more"));
            assertEquals(1, first.get("next_offset"));
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> groups=(List<Map<String,Object>>)discovery.groups(catalogPath,"talkgroup",
                null,"p25:00001:001",0L,10000L,0,100,"any","name",null).get("groups");
            Map<String,Object> ungrouped=groups.stream().filter(row->"Ungrouped".equals(row.get("label")))
                .findFirst().orElseThrow();
            assertEquals(10,rows(ungrouped).size());
            assertTrue(rows(ungrouped).stream().noneMatch(row->Integer.valueOf(119).equals(row.get("id"))));
            @SuppressWarnings("unchecked")
            var foundGroups=(List<Map<String,Object>>)discovery.groups(catalogPath,"talkgroup","119",
                "p25:00001:001",0L,10000L,0,10,"any","name",null).get("groups");
            assertEquals(119,rows(foundGroups.getFirst()).getFirst().get("id"),
                "Server matching reaches identities outside the first ten choices");
            List<Map<String,Object>> group = rows(discovery.search(catalogPath, "talkgroup", "county operations",
                "p25:00001:001", 0L, 10000L, 0, 100, "any"));
            assertEquals(1, group.size());
            assertEquals("Fire Dispatch", group.getFirst().get("label"));
            assertEquals("Fire / EMS", group.getFirst().get("group"));
            assertEquals("p25:00001:001", group.getFirst().get("system_key"));
            assertEquals(2, rows(discovery.search(catalogPath, "talkgroup", "Fire / EMS", null,
                0L, 10000L, 0, 100, "any")).size());
            assertTrue(rows(discovery.search(catalogPath, "talkgroup", "1201", null,
                0L, 10000L, 0, 100, "any")).stream().noneMatch(row -> row.get("system_key") == null),
                "Analog channel assignments are not digital talkgroups");
            List<Map<String,Object>> radios = rows(discovery.search(catalogPath, "radio", "Engine", null,
                0L, 10000L, 0, 100, "source"));
            assertEquals(2, radios.size());
            Map<String,Object> home = radios.stream().filter(row -> "v1-r-00001-001-401".equals(row.get("identity_key")))
                .findFirst().orElseThrow();
            assertEquals("Engine OTA", home.get("label"));
            assertEquals("Engine configured", home.get("alias"));
            assertEquals(Map.of("id", 401, "system_key", "p25:00001:001", "identity_key", "v1-r-00001-001-401"),
                home.get("selection"));
            Map<String,Object> foreign = rows(discovery.search(catalogPath, "radio", "Foreign OTA", null,
                0L, 10000L, 0, 100, "source")).getFirst();
            assertEquals("v1-r-00002-002-401", foreign.get("identity_key"));
            assertFalse(foreign.containsKey("alias"));
            assertEquals(3, rows(discovery.search(catalogPath, "radio", "401", null,
                0L, 4000L, 0, 100, "source")).size());
            assertEquals(4, rows(discovery.search(catalogPath, "radio", "401", null,
                0L, 10000L, 0, 100, "any")).size());
            assertTrue(rows(discovery.search(catalogPath, "radio", "Engine OTA", null,
                2001L, 10000L, 0, 100, "source")).isEmpty());
        }
    }

    @Test
    void canonicalRadiosCombineWorkingIdsAndCountEachCallOnceWhileNxdnGroupZeroIsDiscoverable() throws Exception
    {
        Path catalogPath = directory.resolve("canonical.sqlite");
        try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(catalogPath, directory.resolve("canonical-audio"));
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalogPath);
            Statement statement = connection.createStatement())
        {
            statement.execute("INSERT INTO recording_system(id,system_key) VALUES(1,'p25:00001:001')");
            statement.execute("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                "system_id,protocol,call_type,voice_type,source_id,source_home_wacn,source_home_system,source_home_id," +
                "target_id,target_home_wacn,target_home_system,target_home_id) VALUES" +
                "(1000,2000,1000,'direct.mp3',5,1,1,3,1,555,1,1,401,556,1,1,401)," +
                "(2000,3000,1000,'group.mp3',5,1,1,1,1,557,1,1,401,1201,NULL,NULL,NULL)," +
                "(3000,4000,1000,'zero.mp3',5,NULL,4,1,1,100,NULL,NULL,NULL,0,NULL,NULL,NULL)," +
                "(4000,5000,1000,'partial.mp3',5,1,1,1,1,401,1,1,NULL,1201,NULL,NULL,NULL)");
            ManagedRecordingIdentityDiscovery discovery = new ManagedRecordingIdentityDiscovery(directory.resolve("absent.sqlite"));
            List<Map<String,Object>> radios = rows(discovery.search(catalogPath, "radio", "401",
                null, 0L, 10000L, 0, 100, "any"));
            assertEquals(1, radios.size());
            Map<String,Object> radio = radios.getFirst();
            assertEquals(401, radio.get("id"));
            assertEquals(3L, radio.get("call_count"));
            assertEquals(List.of(401,555,556,557), radio.get("observed_ids"));
            assertEquals("v1-r-00001-001-401", radio.get("identity_key"));
            assertEquals(1, rows(discovery.search(catalogPath, "radio", "556",
                null, 0L, 10000L, 0, 100, "any")).size());
            List<Map<String,Object>> zero = rows(discovery.search(catalogPath, "talkgroup", "0",
                null, 3000L, 3999L, 0, 100, "any"));
            assertEquals(1, zero.size());
            assertEquals(0, zero.getFirst().get("id"));
        }
    }

    @Test
    void rawRecordedIdentitiesRemainDiscoverableWithoutMainDatabase() throws Exception
    {
        Path catalogPath = directory.resolve("raw.sqlite");
        try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(catalogPath, directory.resolve("raw-audio"));
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalogPath);
            Statement statement = connection.createStatement())
        {
            statement.execute("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                "protocol,call_type,voice_type,source_id,target_id) VALUES(1000,2000,1000,'raw.mp3',5,1,1,1,401,1201)");
            Map<String,Object> row = rows(new ManagedRecordingIdentityDiscovery(directory.resolve("missing.sqlite"))
                .search(catalogPath, "talkgroup", "1201", null, 0L, 10000L, 0, 100, "any")).getFirst();
            assertEquals("1201", row.get("label"));
            assertNull(row.get("system_key"));
            assertEquals(1L, row.get("call_count"));
            assertTrue(((Map<?,?>)row.get("selection")).containsKey("system_key"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> page)
    {
        return (List<Map<String,Object>>)page.get("rows");
    }
}
