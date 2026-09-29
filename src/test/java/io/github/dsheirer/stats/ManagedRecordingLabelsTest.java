package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingLabelsTest
{
    @TempDir Path directory;

    @Test
    void currentNamesAndLatestHomeScopedOtaDecorateOldCallWithoutCopyingLabels() throws Exception
    {
        Path database = directory.resolve("main.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE configuration_channel(configuration_id TEXT PRIMARY KEY,name TEXT," +
                "system_name TEXT,site_name TEXT,alias_list_id INTEGER,channel_kind TEXT)");
            statement.execute("CREATE TABLE alias_list(id INTEGER PRIMARY KEY,name TEXT)");
            statement.execute("CREATE TABLE alias(id INTEGER PRIMARY KEY,alias_list_id INTEGER," +
                "matcher_type TEXT,protocol TEXT,value INTEGER,min_value INTEGER,max_value INTEGER," +
                "name TEXT,description TEXT,group_name TEXT)");
            statement.execute("CREATE TABLE radio_system(id INTEGER PRIMARY KEY,system_key TEXT," +
                "configuration_id TEXT,p25_wacn INTEGER,p25_system_id INTEGER)");
            statement.execute("CREATE TABLE radio_system_identity_summary(radio_system_id INTEGER," +
                "identity_kind_code INTEGER,identity_id INTEGER,home_wacn INTEGER,home_system_id INTEGER," +
                "last_talker_alias TEXT,last_talker_alias_seen_ms INTEGER,last_seen_ms INTEGER)");
            statement.execute("CREATE INDEX idx_radio_system_identity_last_seen ON " +
                "radio_system_identity_summary(radio_system_id,identity_kind_code,last_seen_ms DESC," +
                "home_wacn,home_system_id,identity_id)");
            statement.execute("CREATE TABLE receiver_channel(id INTEGER PRIMARY KEY," +
                "configuration_id TEXT,radio_system_id INTEGER)");
            statement.execute("CREATE TABLE p25_site_snapshot(channel_id INTEGER,rfss INTEGER,site INTEGER)");
            statement.execute("INSERT INTO configuration_channel VALUES('site-1','Dispatch'," +
                "'Metro System','North Site',7,'TRUNKED')");
            statement.execute("INSERT INTO alias_list VALUES(7,'Default P25')");
            statement.execute("INSERT INTO alias VALUES(1,7,'TALKGROUP','APCO25',1201,NULL,NULL," +
                "'Fire Dispatch','County fire','Fire')");
            statement.execute("INSERT INTO alias VALUES(2,7,'RADIO_ID','APCO25',401,NULL,NULL," +
                "'Engine 1','First engine','West')");
            statement.execute("INSERT INTO alias VALUES(3,7,'TALKGROUP_RANGE','APCO25',NULL,1300,1399," +
                "'Fire Tac Range','All tac channels','Fire')");
            statement.execute("INSERT INTO radio_system VALUES(1,'p25:00001:001',NULL,1,1)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,2,401,1,1," +
                "'Engine One OTA',200,200)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,2,401,2,2," +
                "'Wrong Home OTA',300,300)");
            statement.execute("INSERT INTO receiver_channel VALUES(11,'site-1',1)");
            statement.execute("INSERT INTO p25_site_snapshot VALUES(11,4,9)");
        }

        ManagedRecordingLabels labels = new ManagedRecordingLabels(database);
        Map<String,Object> call = new LinkedHashMap<>(Map.of(
            "id", 99L,
            "channel_id", "site-1",
            "system_key", "p25:00001:001",
            "protocol", "APCO25",
            "talkgroup_id", 1201,
            "source_id", 401,
            "source_home_wacn", 1,
            "source_home_system_id", 1));
        Map<String,Object> decorated = labels.decorate(List.of(call)).getFirst();
        assertEquals("Metro System", decorated.get("system_name"));
        assertEquals("North Site", decorated.get("site_name"));
        assertEquals("Fire Dispatch", decorated.get("group_alias"));
        assertEquals("County fire", decorated.get("group_description"));
        assertEquals("Fire", decorated.get("group_group"));
        assertEquals("Engine 1", decorated.get("source_alias"));
        assertEquals("Engine One OTA", decorated.get("source_ota_alias"));
        assertFalse(decorated.toString().contains("Wrong Home OTA"));

        assertTrue(labels.matchingIdentityIds("Engine One OTA", "p25:00001:001", 200).contains(401));
        assertTrue(labels.matchingIdentityIds("Engine One OTA", 200).contains(401));
        assertTrue(labels.suggestions("Engine One", "radio", "p25:00001:001", 20).stream()
            .anyMatch(row -> "Engine One OTA".equals(row.get("label"))));
        List<Map<String,Object>> site = labels.suggestions("North", "site", 20);
        assertEquals(1, site.size());
        assertEquals(4, site.getFirst().get("rfss"));
        assertEquals(9, site.getFirst().get("site_id"));
        assertEquals(7L, labels.suggestions("Fire", "talkgroup", 20).getFirst().get("alias_list_id"));
        Map<String,Object> range = labels.suggestions("Tac Range", "talkgroup", 20).getFirst();
        assertEquals("talkgroup_range", range.get("kind"));
        assertEquals(1300, range.get("min_id"));
        assertEquals(1399, range.get("max_id"));
        assertTrue(labels.matchingIdentityIds("Tac Range", 200).contains(1350));
        assertEquals("Metro System", labels.suggestions("Metro", "system", 20).getFirst().get("label"));
    }
}
