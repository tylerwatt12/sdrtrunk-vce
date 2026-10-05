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
    private static final String CHANNEL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

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
        assertEquals("Dispatch", site.getFirst().get("label"));
        assertEquals("North Site · Metro System", site.getFirst().get("detail"));
        assertEquals(4, site.getFirst().get("rfss"));
        assertEquals(9, site.getFirst().get("site_id"));
        assertEquals(7L, labels.suggestions("Fire", "talkgroup", 20).getFirst().get("alias_list_id"));
        Map<String,Object> range = labels.suggestions("Tac Range", "talkgroup", 20).getFirst();
        assertEquals("talkgroup_range", range.get("kind"));
        assertEquals(1300, range.get("min_id"));
        assertEquals(1399, range.get("max_id"));
        assertTrue(labels.matchingIdentityIds("Tac Range", 200).contains(1350));
        assertEquals("Metro System", labels.suggestions("Metro", "system", 20).getFirst().get("label"));
        Map<String,Object> missingOrigin = labels.decorate(List.of(Map.of("system_key", "p25:00001:001",
            "source_home_wacn", 1, "source_home_system_id", 1))).getFirst();
        assertEquals("Metro System", missingOrigin.get("system_name"));
        assertEquals("Metro System", missingOrigin.get("source_home_system_name"));
        assertEquals("p25:00001:001", ((Map<?,?>)missingOrigin.get("source_home_system")).get("key"));

        long largeAliasListId = 2_147_483_648L;
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE alias_list SET id=" + largeAliasListId + " WHERE id=7");
            statement.executeUpdate("UPDATE alias SET alias_list_id=" + largeAliasListId);
            statement.executeUpdate("UPDATE configuration_channel SET alias_list_id=" + largeAliasListId);
        }
        Map<String,Object> largeListCall = labels.decorate(List.of(call)).getFirst();
        assertEquals("Fire Dispatch", largeListCall.get("group_alias"));
        assertEquals("Engine 1", largeListCall.get("source_alias"));
        Map<String,Object> withoutChannel = labels.decorate(List.of(Map.of(
            "alias_list_id", largeAliasListId, "protocol", "APCO25", "talkgroup_id", 1201))).getFirst();
        assertEquals("Fire Dispatch", withoutChannel.get("group_alias"));
    }

    @Test
    void navigationReferencesRequireCurrentDestinationsAndUseCanonicalP25HomeIdentities() throws Exception
    {
        Path database = directory.resolve("navigation.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE configuration_channel(configuration_id TEXT PRIMARY KEY,name TEXT," +
                "system_name TEXT,site_name TEXT,alias_list_id INTEGER)");
            statement.execute("CREATE TABLE radio_system(id INTEGER PRIMARY KEY,system_key TEXT," +
                "protocol_code INTEGER,p25_wacn INTEGER,p25_system_id INTEGER,configuration_id TEXT)");
            statement.execute("CREATE TABLE receiver_channel(configuration_id TEXT,radio_system_id INTEGER)");
            statement.execute("CREATE TABLE radio_system_identity_summary(radio_system_id INTEGER," +
                "identity_kind_code INTEGER,home_wacn INTEGER,home_system_id INTEGER,identity_id INTEGER)");
            statement.execute("INSERT INTO configuration_channel VALUES('" + CHANNEL_ID +
                "','Dispatch','Metro','North',NULL)");
            statement.execute("INSERT INTO radio_system VALUES(1,'p25:00001:001',1,1,1,NULL)");
            statement.execute("INSERT INTO receiver_channel VALUES('" + CHANNEL_ID + "',1)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,2,2,2,900)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,3,1,1,1201)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,1,3,3,1301)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,2,1,1,500)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,1,1,1,1301)");
        }

        Map<String,Object> patch = new LinkedHashMap<>();
        patch.put("channel_id", CHANNEL_ID);
        patch.put("system_key", "p25:00001:001");
        patch.put("protocol", "APCO25");
        patch.put("call_type", "PATCH");
        patch.put("source_id", 401);
        patch.put("source_home_wacn", 2);
        patch.put("source_home_system_id", 2);
        patch.put("source_home_id", 900);
        patch.put("talkgroup_id", 1201);
        patch.put("target_id", 1201);
        patch.put("patch_members", List.of(Map.of("kind", "talkgroup", "id", 1310,
            "home_wacn", 3, "home_system_id", 3, "home_identity_id", 1301)));
        Map<String,Object> direct = new LinkedHashMap<>(Map.of("system_key", "p25:00001:001",
            "protocol", "APCO25_PHASE2", "call_type", "DIRECT", "target_id", 500,
            "destination_radio_id", 500, "source_id", 999));
        Map<String,Object> group = new LinkedHashMap<>(Map.of("system_key", "p25:00001:001",
            "protocol", "APCO25", "call_type", "GROUP", "target_id", 1301, "talkgroup_id", 1301));
        List<Map<String,Object>> decorated = new ManagedRecordingLabels(database)
            .decorate(List.of(patch, direct, group));

        assertEquals(WebEntityRef.channel(CHANNEL_ID).toMap(),
            decorated.get(0).get("channel_entity_ref"));
        assertEquals(WebEntityRef.radioSystem("p25:00001:001").toMap(),
            decorated.get(0).get("radio_system_entity_ref"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-00002-002-900").toMap(),
            decorated.get(0).get("source_entity_ref"));
        assertEquals(WebEntityRef.patchGroup("p25:00001:001", "v1-p-00001-001-1201").toMap(),
            decorated.get(0).get("target_entity_ref"));
        @SuppressWarnings("unchecked")
        Map<String,Object> member = ((List<Map<String,Object>>)decorated.get(0).get("patch_members")).getFirst();
        assertEquals(WebEntityRef.talkgroup("p25:00001:001", "v1-g-00003-003-1301").toMap(),
            member.get("entity_ref"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-00001-001-500").toMap(),
            decorated.get(1).get("target_entity_ref"));
        assertFalse(decorated.get(1).containsKey("source_entity_ref"));
        assertEquals(WebEntityRef.talkgroup("p25:00001:001", "v1-g-00001-001-1301").toMap(),
            decorated.get(2).get("target_entity_ref"));
    }

    @Test
    void missingOrIncompatibleDestinationsStayPlainText() throws Exception
    {
        Path database = directory.resolve("missing-navigation.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE configuration_channel(configuration_id TEXT PRIMARY KEY,name TEXT," +
                "system_name TEXT,site_name TEXT,alias_list_id INTEGER)");
            statement.execute("CREATE TABLE radio_system(id INTEGER PRIMARY KEY,system_key TEXT," +
                "protocol_code INTEGER,p25_wacn INTEGER,p25_system_id INTEGER)");
            statement.execute("CREATE TABLE radio_system_identity_summary(radio_system_id INTEGER," +
                "identity_kind_code INTEGER,home_wacn INTEGER,home_system_id INTEGER,identity_id INTEGER)");
            statement.execute("INSERT INTO radio_system VALUES(1,'p25:00001:001',1,1,1)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,2,1,1,401)");
            statement.execute("INSERT INTO radio_system VALUES(2,'dmr:channel:" + CHANNEL_ID +
                "',3,NULL,NULL)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(2,2,-1,-1,123)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(2,1,-1,-1,2401)");
        }
        Map<String,Object> missing = new LinkedHashMap<>(Map.of("channel_id", CHANNEL_ID,
            "system_key", "p25:00001:001", "protocol", "DMR", "call_type", "DIRECT",
            "source_id", 401, "target_id", 500));
        Map<String,Object> incompleteHome = new LinkedHashMap<>(Map.of("system_key", "p25:00001:001",
            "protocol", "APCO25", "source_id", 401, "source_home_wacn", 2));
        Map<String,Object> unknownSystem = new LinkedHashMap<>(Map.of("system_key", "p25:00002:002",
            "protocol", "APCO25", "source_id", 401));
        Map<String,Object> dmr = new LinkedHashMap<>(Map.of("system_key", "dmr:channel:" + CHANNEL_ID,
            "protocol", "DMR", "call_type", "GROUP", "source_id", 123, "target_id", 2401,
            "talkgroup_id", 2401));
        Map<String,Object> ordinaryP25 = new LinkedHashMap<>(Map.of("system_key", "p25:00001:001",
            "protocol", "APCO25", "source_id", 401));
        List<Map<String,Object>> rows = new ManagedRecordingLabels(database)
            .decorate(List.of(missing, incompleteHome, unknownSystem, dmr, ordinaryP25));

        assertEquals(WebEntityRef.radioSystem("p25:00001:001").toMap(),
            rows.get(0).get("radio_system_entity_ref"));
        assertFalse(rows.get(0).containsKey("channel_entity_ref"));
        assertFalse(rows.get(0).containsKey("source_entity_ref"));
        assertFalse(rows.get(0).containsKey("target_entity_ref"));
        assertFalse(rows.get(1).containsKey("source_entity_ref"));
        assertFalse(rows.get(2).containsKey("radio_system_entity_ref"));
        assertEquals(WebEntityRef.radio("dmr:channel:" + CHANNEL_ID, "v1-r-x-x-123").toMap(),
            rows.get(3).get("source_entity_ref"));
        assertEquals(WebEntityRef.talkgroup("dmr:channel:" + CHANNEL_ID, "v1-g-x-x-2401").toMap(),
            rows.get(3).get("target_entity_ref"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-00001-001-401").toMap(),
            rows.get(4).get("source_entity_ref"));
    }
}
