package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
            statement.execute("CREATE TABLE radio_system(id INTEGER PRIMARY KEY,protocol_code INTEGER,system_key TEXT," +
                "configuration_id TEXT,p25_wacn INTEGER,p25_system_id INTEGER)");
            statement.execute("CREATE TABLE radio_system_identity_summary(id INTEGER PRIMARY KEY,radio_system_id INTEGER," +
                "identity_kind_code INTEGER,identity_id INTEGER,home_wacn INTEGER,home_system_id INTEGER," +
                "last_talker_alias TEXT,last_talker_alias_seen_ms INTEGER,last_seen_ms INTEGER," +
                "p25_subscriber_identity_id INTEGER)");
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
            statement.execute("INSERT INTO radio_system VALUES(1,1,'p25:00001:001',NULL,1,1)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,1,2,401,1,1," +
                "'Engine One OTA',200,200,NULL)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(2,1,2,401,2,2," +
                "'Wrong Home OTA',300,300,NULL)");
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
            statement.execute("CREATE TABLE radio_system_identity_summary(id INTEGER PRIMARY KEY,radio_system_id INTEGER," +
                "identity_kind_code INTEGER,home_wacn INTEGER,home_system_id INTEGER,identity_id INTEGER," +
                "p25_subscriber_identity_id INTEGER)");
            statement.execute("INSERT INTO configuration_channel VALUES('" + CHANNEL_ID +
                "','Dispatch','Metro','North',NULL)");
            statement.execute("INSERT INTO radio_system VALUES(1,'p25:00001:001',1,1,1,NULL)");
            statement.execute("INSERT INTO receiver_channel VALUES('" + CHANNEL_ID + "',1)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,1,2,2,2,900,NULL)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(2,1,3,1,1,1201,NULL)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(3,1,1,3,3,1301,NULL)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(4,1,2,1,1,500,NULL)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(5,1,1,1,1,1301,NULL)");
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
            statement.execute("CREATE TABLE radio_system_identity_summary(id INTEGER PRIMARY KEY,radio_system_id INTEGER," +
                "identity_kind_code INTEGER,home_wacn INTEGER,home_system_id INTEGER,identity_id INTEGER," +
                "p25_subscriber_identity_id INTEGER)");
            statement.execute("INSERT INTO radio_system VALUES(1,'p25:00001:001',1,1,1)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(1,1,2,1,1,401,NULL)");
            statement.execute("INSERT INTO radio_system VALUES(2,'dmr:channel:" + CHANNEL_ID +
                "',3,NULL,NULL)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(2,2,2,-1,-1,123,NULL)");
            statement.execute("INSERT INTO radio_system_identity_summary VALUES(3,2,1,-1,-1,2401,NULL)");
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

    @Test
    void ordinaryRecordingFindsConsolidatedHomeButPreservesAnUnresolvedLegacyOwner() throws Exception
    {
        Path database = freshRadioDatabase("consolidated-recording.sqlite");
        insertRadio(database, 10, 1, 1, 1, 10_900_077, "Home OTA");
        insertRadio(database, 11, 1, 2, 2, 10_900_077, "Foreign OTA");
        insertRadio(database, 12, 1, 2, 2, 999, "Foreign Only");
        ManagedRecordingLabels labels = new ManagedRecordingLabels(database);
        Map<String,Object> ordinary = Map.of("system_key", "p25:00001:001", "protocol", "APCO25",
            "source_id", 10_900_077);
        Map<String,Object> decorated = labels.decorate(List.of(ordinary)).getFirst();
        assertEquals("Home OTA", decorated.get("source_ota_alias"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-00001-001-10900077").toMap(),
            decorated.get("source_entity_ref"));
        assertEquals(3, ordinary.size(), "recorded raw metadata must not be rewritten");

        insertRadio(database, 13, 1, -1, -1, 10_900_077, null);
        Map<String,Object> ambiguous = labels.decorate(List.of(ordinary)).getFirst();
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-x-x-10900077").toMap(),
            ambiguous.get("source_entity_ref"));
        assertFalse(ambiguous.containsKey("source_ota_alias"),
            "unknown-home recording must not borrow an alias from a different retained owner");

        Map<String,Object> foreign = labels.decorate(List.of(Map.of("system_key", "p25:00001:001",
            "protocol", "APCO25", "source_id", 10_900_077, "source_home_wacn", 2,
            "source_home_system_id", 2, "source_home_id", 10_900_077))).getFirst();
        assertEquals("Foreign OTA", foreign.get("source_ota_alias"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-00002-002-10900077").toMap(),
            foreign.get("source_entity_ref"));
        Map<String,Object> foreignOnly = labels.decorate(List.of(Map.of("system_key", "p25:00001:001",
            "protocol", "APCO25", "source_id", 999))).getFirst();
        assertFalse(foreignOnly.containsKey("source_entity_ref"));
        assertFalse(foreignOnly.containsKey("source_ota_alias"));
    }

    @Test
    void recordingOtaUsesExplicitSubscriberRatherThanADifferentWorkingAddress() throws Exception
    {
        Path database = freshRadioDatabase("working-recording.sqlite");
        insertRadio(database, 20, 1, 1, 1, 401, "Subscriber 401");
        insertRadio(database, 21, 1, 1, 1, 777, "Subscriber 777");
        insertRadio(database, 22, 1, 1, 1, 555, "Local 555");
        ManagedRecordingLabels labels = new ManagedRecordingLabels(database);
        List<Map<String,Object>> calls = List.of(
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25", "source_id", 555,
                "source_home_wacn", 1, "source_home_system_id", 1, "source_home_id", 401),
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25", "source_id", 555,
                "source_home_wacn", 1, "source_home_system_id", 1, "source_home_id", 777),
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25_PHASE2", "source_id", 555),
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25", "source_id", 555,
                "source_home_wacn", 1),
            Map.of("system_key", "p25:00001:001", "protocol", "DMR", "source_id", 555),
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25", "source_id", 0,
                "source_home_wacn", 1, "source_home_system_id", 1, "source_home_id", 401),
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25",
                "source_home_wacn", 1, "source_home_system_id", 1, "source_home_id", 777));
        List<Map<String,Object>> decorated = labels.decorate(calls);
        assertEquals("Subscriber 401", decorated.get(0).get("source_ota_alias"));
        assertEquals("Subscriber 777", decorated.get(1).get("source_ota_alias"));
        assertEquals("Local 555", decorated.get(2).get("source_ota_alias"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-00001-001-401").toMap(),
            decorated.get(0).get("source_entity_ref"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-00001-001-777").toMap(),
            decorated.get(1).get("source_entity_ref"));
        assertEquals(555, decorated.get(0).get("source_id"));
        assertFalse(decorated.get(3).containsKey("source_ota_alias"));
        assertFalse(decorated.get(3).containsKey("source_entity_ref"));
        assertFalse(decorated.get(4).containsKey("source_ota_alias"));
        assertFalse(decorated.get(4).containsKey("source_entity_ref"));
        assertEquals("Subscriber 401", decorated.get(5).get("source_ota_alias"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-00001-001-401").toMap(),
            decorated.get(5).get("source_entity_ref"));
        assertEquals(0, decorated.get(5).get("source_id"));
        assertEquals("Subscriber 777", decorated.get(6).get("source_ota_alias"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-00001-001-777").toMap(),
            decorated.get(6).get("source_entity_ref"));
        assertFalse(decorated.get(6).containsKey("source_id"), "no working/local address may be invented");
    }

    private Path freshRadioDatabase(String filename) throws Exception
    {
        Path database = directory.resolve(filename);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code," +
                "p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) " +
                "VALUES(1,'p25:00001:001',1,0,1,1,1000,2000)");
        }
        return database;
    }

    @Test
    void foreignSameNumberWithoutWorkingProofCannotBorrowConfiguredLocalRadioAlias() throws Exception
    {
        Path database = freshRadioDatabase("foreign-configured-alias.sqlite");
        insertRadio(database, 31, 1, 1, 1, 401, "Home OTA");
        insertRadio(database, 32, 1, 2, 2, 401, "Foreign OTA 401");
        insertRadio(database, 33, 1, 2, 2, 777, "Foreign OTA 777");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("INSERT INTO alias_list(id,name,family) VALUES(7,'Local List','P25')");
            statement.execute("INSERT INTO alias(alias_list_id,matcher_type,protocol,value,name) " +
                "VALUES(7,'RADIO_ID','APCO25',401,'Local configured Alias')");
        }
        ManagedRecordingLabels labels = new ManagedRecordingLabels(database);
        List<Map<String,Object>> metadata = List.of(
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25", "alias_list_id", 7,
                "source_id", 401, "source_home_wacn", 2, "source_home_system_id", 2, "source_home_id", 401),
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25", "alias_list_id", 7,
                "source_id", 401, "source_home_wacn", 1, "source_home_system_id", 1, "source_home_id", 401),
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25", "alias_list_id", 7,
                "source_id", 401, "source_home_wacn", 2, "source_home_system_id", 2, "source_home_id", 777),
            Map.of("system_key", "p25:00001:001", "protocol", "APCO25", "alias_list_id", 7,
                "call_type", "DIRECT", "destination_radio_id", 401, "target_id", 401,
                "target_home_wacn", 2, "target_home_system_id", 2, "target_home_id", 401));
        List<Map<String,Object>> rows = labels.decorate(metadata);
        assertFalse(rows.get(0).containsKey("source_alias"));
        assertEquals("Foreign OTA 401", rows.get(0).get("source_ota_alias"));
        assertEquals("Local configured Alias", rows.get(1).get("source_alias"));
        assertEquals("Home OTA", rows.get(1).get("source_ota_alias"));
        assertEquals("Local configured Alias", rows.get(2).get("source_alias"));
        assertEquals("Foreign OTA 777", rows.get(2).get("source_ota_alias"));
        assertFalse(rows.get(3).containsKey("destination_radio_alias"));
        assertEquals("Foreign OTA 401", rows.get(3).get("destination_radio_ota_alias"));
        assertEquals(401, rows.get(0).get("source_id"));
        insertRadio(database, 34, 1, -1, -1, 401, null);
        Map<String,Object> legacy = labels.decorate(List.of(Map.of("system_key", "p25:00001:001",
            "protocol", "APCO25", "alias_list_id", 7, "source_id", 401))).getFirst();
        assertEquals("Local configured Alias", legacy.get("source_alias"));
        assertEquals(WebEntityRef.radio("p25:00001:001", "v1-r-x-x-401").toMap(), legacy.get("source_entity_ref"));
    }

    private static void insertRadio(Path database, int id, int system, int homeWacn, int homeSystem,
                                    int radio, String ota) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                    home_system_id,identity_id,first_seen_ms,last_seen_ms,last_talker_alias,last_talker_alias_seen_ms)
                VALUES(?,?,2,?,?,?,1000,2000,?,?)
                """))
        {
            statement.setInt(1, id);
            statement.setInt(2, system);
            statement.setInt(3, homeWacn);
            statement.setInt(4, homeSystem);
            statement.setInt(5, radio);
            statement.setString(6, ota);
            if(ota == null) statement.setNull(7, java.sql.Types.INTEGER);
            else statement.setLong(7, 2000);
            statement.executeUpdate();
        }
    }
}
