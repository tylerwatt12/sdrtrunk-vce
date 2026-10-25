package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationHistoryAliasTest
{
    @Test
    void activeAssignmentCountsExcludeRemovedChannelsAndDeletedListsKeepOnlyHistoricalLabels(@TempDir Path temporary)
        throws Exception
    {
        Path configuration = temporary.resolve("sdrtrunk.sqlite");
        io.github.dsheirer.database.SdrTrunkTestDatabase.create(configuration);
        String channelId = "123e4567-e89b-42d3-a456-426614174111";
        long listId;
        try(Connection config = SdrTrunkDatabase.open(configuration);
            Connection history = SdrTrunkDatabase.openHistory(configuration))
        {
            try(Statement statement = config.createStatement(); ResultSet row = statement.executeQuery(
                "SELECT id FROM alias_list WHERE family='NBFM' LIMIT 1"))
            { assertTrue(row.next()); listId = row.getLong(1); }
            try(Statement statement = config.createStatement())
            {
                statement.execute("INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order," +
                    "name,alias_list_id,decoder_type,config_json) VALUES('"+channelId+"','CONVENTIONAL',0," +
                    "'Retained channel',"+listId+",'NBFM','{}')");
            }
            try(Statement statement = history.createStatement())
            {
                statement.execute("INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms,name," +
                    "channel_kind,decoder_type,alias_list_id,alias_list_name) VALUES('"+channelId+"',1,1," +
                    "'Retained channel','CONVENTIONAL','NBFM',"+listId+",'Captured alias list')");
            }
            try(Statement statement = config.createStatement())
            { statement.execute("DELETE FROM configuration_channel WHERE configuration_id='"+channelId+"'"); }
            StatsWebDatabase database = new StatsWebDatabase(new UserPreferences(),configuration);
            StatsRequest request = new StatsRequest(Map.of());
            assertEquals(0,((Number)database.publicIdentityOverview(listId,request).get("correlated_channel_count")).longValue());
            try(Connection reader = SdrTrunkDatabase.openHistoryRead(configuration))
            {
                Map<String,Object> list = new StatsAliasCatalog(new StatsAliasResolver()).aliasLists(reader,request)
                    .get("rows") instanceof List<?> rows ? (Map<String,Object>)rows.stream()
                        .filter(item -> ((Number)((Map<?,?>)item).get("alias_list_id")).longValue()==listId)
                        .findFirst().orElseThrow() : Map.of();
                assertEquals(0,((Number)list.get("assigned_channel_count")).longValue());
            }
            try(Statement statement = config.createStatement()) { statement.execute("DELETE FROM alias_list WHERE id="+listId); }
            try(Connection reader = SdrTrunkDatabase.openHistoryRead(configuration))
            {
                var retained = new WebConfiguredEntityRepository().requireChannel(reader,channelId);
                assertNull(retained.aliasListId());
                assertEquals("Captured alias list",retained.aliasListName());
            }
        }
    }

    @Test
    void removedAliasKeepsCapturedLabelsAndAnExplicitCleanupTarget(@TempDir Path temporary) throws Exception
    {
        Path configuration = temporary.resolve("sdrtrunk.sqlite");
        io.github.dsheirer.database.SdrTrunkTestDatabase.create(configuration);
        try(Connection config = SdrTrunkDatabase.open(configuration);
            Connection history = SdrTrunkDatabase.openHistory(configuration))
        {
            try(Statement statement = config.createStatement())
            {
                statement.execute("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                    "SELECT 100,id,'Removed dispatcher','TALKGROUP','APCO25',1 FROM alias_list WHERE family='P25' LIMIT 1");
            }
            AliasActivitySummaryMaintenance.ensureCurrent(history, 100, 1, 10);
            String listName;
            try(Statement statement = history.createStatement(); ResultSet row = statement.executeQuery(
                "SELECT alias_list_name FROM alias_activity_summary WHERE alias_id=100"))
            { assertTrue(row.next()); listName = row.getString(1); }
            try(Statement statement = config.createStatement()) { statement.execute("DELETE FROM alias WHERE id=100"); }
            RetainedStatisticsCatalog catalog = new RetainedStatisticsCatalog(configuration);
            var page = catalog.results("alias_activity", null, "alias_activity", null, "Removed dispatcher", 10, 0);
            assertEquals(1, page.totalCount());
            assertEquals("Removed dispatcher", page.rows().getFirst().get("label"));
            assertEquals(listName, page.rows().getFirst().get("alias_list_name"));
            var target = new io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData(
                "alias_activity", null, null, null, "alias_activity", "100", List.of("summary"));
            assertEquals("Alias Activity · Removed dispatcher", catalog.jobLabel(target));
            assertEquals(1, catalog.preview(target).rowsTotal());
        }
    }

    @Test
    void pairedStoreServesTheSharedCatalogAndActivityReadModels(@TempDir Path temporary) throws Exception
    {
        Path configuration=temporary.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(configuration);
        long listId;
        try(Connection config=SdrTrunkDatabase.open(configuration);
            Statement statement=config.createStatement();ResultSet list=statement.executeQuery(
                "SELECT id FROM alias_list WHERE family='P25' LIMIT 1"))
        { assertTrue(list.next());listId=list.getLong(1); }
        StatsWebDatabase database=new StatsWebDatabase(new UserPreferences(),configuration);
        StatsRequest request=new StatsRequest(Map.of());
        assertTrue(database.status().containsKey("database_exists"));
        database.dashboard();
        database.aliasLists(request);
        database.aliases(request);
        database.publicIdentityLists(request);
        database.publicIdentityOverview(listId,request);
        database.publicIdentityAliases(listId,request);
        database.observedGroupIdentities(listId,request);
        database.channelDirectory(request);
        database.identityDirectory(request);
        database.radioSystemDirectory(request);
        database.activity(request);
        database.webEntityNavigationSnapshot();
        TopicAlertOptions.read(configuration);
        new RetainedStatisticsCatalog(configuration).sources("saved_channel",null,100,0);
        new RetainedStatisticsCatalog(configuration).sources("radio_system",null,100,0);
    }

    @Test
    void canonicalAliasesMatchNaturalIdentityAcrossIndependentDictionaryKeys(@TempDir Path temporary)
        throws Exception
    {
        Path configuration = temporary.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(configuration);
        long listId;
        try(Connection config = SdrTrunkDatabase.open(configuration); Statement statement = config.createStatement())
        {
            try(ResultSet list = statement.executeQuery("SELECT id FROM alias_list WHERE family='P25' LIMIT 1"))
            { assertTrue(list.next()); listId=list.getLong(1); }
            statement.execute("INSERT INTO p25_subscriber_identity VALUES(7,0xABCDE,0x321,1001)");
            statement.execute("INSERT INTO alias(id,alias_list_id,name,matcher_type,activity_reset_ms) " +
                "VALUES(100,"+listId+",'Canonical subscriber','P25_SUBSCRIBER_IDENTITY',1)");
            statement.execute("INSERT INTO alias_p25_subscriber_identity VALUES(100,7)");
        }
        try(Connection history = SdrTrunkDatabase.openHistory(configuration); Statement statement=history.createStatement())
        {
            statement.execute("INSERT INTO p25_subscriber_identity VALUES(7,0xABCDE,0x321,2002),(9,0xABCDE,0x321,1001)");
        }
        try(Connection reader=SdrTrunkDatabase.openHistoryRead(configuration))
        {
            Map<String,Object> matching = new LinkedHashMap<>(Map.of(
                "p25_subscriber_identity_id",9L,"observing_list",listId));
            Map<String,Object> collidingKey = new LinkedHashMap<>(Map.of(
                "p25_subscriber_identity_id",7L,"observing_list",listId));
            new StatsAliasResolver().enrichIssiSubscriberAliases(reader,
                new ArrayList<>(List.of(matching,collidingKey)),"identity_summary_id","working_id",
                "observing_list","alias_");
            assertEquals("Canonical subscriber", matching.get("alias_name"));
            assertEquals(100L, matching.get("alias_id"));
            assertNull(collidingKey.get("alias_name"), "equal private integer keys cannot match different subscribers");
        }
        io.github.dsheirer.stats.activity.ReceiverActivityMaintenance.run(configuration,7,
            io.github.dsheirer.stats.activity.ReceiverActivityMaintenance.Operation.RESET_STATS);
        try(Connection reader=SdrTrunkDatabase.openHistoryRead(configuration);
            Statement statement=reader.createStatement();ResultSet rows=statement.executeQuery(
                "SELECT id FROM p25_subscriber_identity ORDER BY id"))
        {
            assertTrue(rows.next());assertEquals(9,rows.getLong(1));
            assertTrue(!rows.next(),"reset preserves the configured natural tuple, rather than a colliding private key");
        }
    }

    @Test
    void matcherIdentityHidesOldCountersAndResetsOnlyChangedAlias(@TempDir Path temporary) throws Exception
    {
        Path configuration=temporary.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(configuration);
        try(Connection config=SdrTrunkDatabase.open(configuration);
            Connection history=SdrTrunkDatabase.openHistory(configuration))
        {
            try(Statement statement=config.createStatement())
            {
                statement.execute("""
                    INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,activity_reset_ms)
                    SELECT 100,id,'Changed','TALKGROUP','APCO25',1001,1 FROM alias_list WHERE family='P25' LIMIT 1
                    """);
                statement.execute("""
                    INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,activity_reset_ms)
                    SELECT 101,id,'Unaffected','TALKGROUP','APCO25',1002,1 FROM alias_list WHERE family='P25' LIMIT 1
                    """);
            }
            AliasActivitySummaryMaintenance.ensureCurrent(history,100,1,10);
            AliasActivitySummaryMaintenance.ensureCurrent(history,101,1,10);
            try(Statement statement=history.createStatement())
            { statement.execute("UPDATE alias_activity_summary SET logical_call_count=55,metrics_state='observed'"); }
            try(Statement statement=config.createStatement())
            {
                statement.execute("UPDATE alias SET activity_identity='123e4567-e89b-42d3-a456-426614174999'," +
                    "activity_reset_ms=20,value=2001 WHERE id=100");
            }
            try(Connection reader=SdrTrunkDatabase.openHistoryRead(configuration);
                Statement statement=reader.createStatement(); ResultSet rows=statement.executeQuery("""
                    SELECT configured.id,summary.logical_call_count
                    FROM alias configured LEFT JOIN alias_activity_summary summary
                      ON summary.alias_id=configured.id AND summary.activity_identity=configured.activity_identity
                    WHERE configured.id IN (100,101) ORDER BY configured.id
                    """))
            {
                assertTrue(rows.next()); assertNull(rows.getObject(2));
                assertTrue(rows.next()); assertEquals(55,rows.getLong(2));
            }
            AliasActivitySummaryMaintenance.ensureCurrent(history,100,1,30);
            try(Statement statement=history.createStatement(); ResultSet row=statement.executeQuery(
                "SELECT logical_call_count,last_reset_ms FROM alias_activity_summary WHERE alias_id=100"))
            {
                assertTrue(row.next()); assertNull(row.getObject(1)); assertEquals(20,row.getLong(2));
            }
            try(Statement statement=config.createStatement())
            { statement.execute("UPDATE alias SET name='Renamed' WHERE id=101"); }
            AliasActivitySummaryMaintenance.ensureCurrent(history,101,1,40);
            try(Statement statement=history.createStatement(); ResultSet row=statement.executeQuery(
                "SELECT logical_call_count,alias_name FROM alias_activity_summary WHERE alias_id=101"))
            {
                assertTrue(row.next()); assertEquals(55,row.getLong(1)); assertEquals("Renamed",row.getString(2));
            }
        }
    }
}
