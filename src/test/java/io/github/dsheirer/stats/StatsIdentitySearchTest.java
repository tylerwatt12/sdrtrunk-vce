/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.ProgressHandler;

class StatsIdentitySearchTest
{
    private Connection mConnection;

    @BeforeEach
    void setUp() throws Exception
    {
        mConnection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try(Statement statement = mConnection.createStatement())
        {
            for(String ddl: List.of(
                "CREATE TABLE radio_system(id INTEGER PRIMARY KEY,system_key TEXT,protocol_code INTEGER," +
                    "address_domain_code INTEGER,configuration_id TEXT)",
                "CREATE TABLE configuration_channel(configuration_id TEXT PRIMARY KEY," +
                    "alias_list_id INTEGER,system_name TEXT)",
                "CREATE TABLE receiver_channel(id INTEGER PRIMARY KEY,radio_system_id INTEGER," +
                    "configuration_id TEXT)",
                "CREATE TABLE radio_system_identity_summary(id INTEGER PRIMARY KEY,radio_system_id INTEGER," +
                    "identity_id INTEGER,home_wacn INTEGER,home_system_id INTEGER," +
                    "p25_subscriber_identity_id INTEGER,last_talker_alias TEXT)",
                "CREATE TABLE alias(id INTEGER PRIMARY KEY,alias_list_id INTEGER,name TEXT,description TEXT," +
                    "group_name TEXT,matcher_type TEXT,protocol TEXT,value INTEGER,min_value INTEGER,max_value INTEGER)",
                "CREATE TABLE alias_p25_subscriber_identity(alias_id INTEGER,p25_subscriber_identity_id INTEGER)",
                "CREATE TABLE p25_site_call_identity_bucket(identity_summary_id INTEGER,channel_id INTEGER," +
                    "observed_local_id INTEGER)",
                "CREATE TABLE receiver_activity_event(id INTEGER PRIMARY KEY,channel_id INTEGER," +
                    "source_identity_summary_id INTEGER,source_observed_local_id INTEGER," +
                    "target_identity_summary_id INTEGER,target_observed_local_id INTEGER)",
                "CREATE TABLE activity_event_identity_member(event_id INTEGER,identity_summary_id INTEGER," +
                    "observed_local_id INTEGER,channel_id INTEGER NOT NULL)",
                "CREATE TABLE trunked_radio_channel_presence(radio_system_id INTEGER,radio_identity_id INTEGER," +
                    "channel_id INTEGER,observed_local_id INTEGER,PRIMARY KEY(radio_system_id,radio_identity_id))",
                "CREATE TABLE trunked_radio_affiliation(radio_system_id INTEGER,radio_identity_id INTEGER," +
                    "talkgroup_identity_id INTEGER,channel_id INTEGER,radio_observed_local_id INTEGER," +
                    "talkgroup_observed_local_id INTEGER,PRIMARY KEY(radio_system_id,radio_identity_id))",
                "CREATE INDEX idx_alias_radio_value ON alias(protocol,value,alias_list_id)",
                "CREATE INDEX idx_alias_radio_range ON alias(protocol,min_value,max_value,alias_list_id)",
                "CREATE INDEX idx_alias_talkgroup_value ON alias(protocol,value,alias_list_id)",
                "CREATE INDEX idx_alias_talkgroup_range ON alias(protocol,min_value,max_value,alias_list_id)",
                "CREATE INDEX idx_p25_site_call_identity_identity ON p25_site_call_identity_bucket(identity_summary_id)",
                "CREATE INDEX idx_p25_site_call_identity_identity_address ON " +
                    "p25_site_call_identity_bucket(identity_summary_id,observed_local_id,channel_id) " +
                    "WHERE observed_local_id>0",
                "CREATE INDEX idx_activity_event_member_identity_event ON activity_event_identity_member(identity_summary_id,event_id)",
                "CREATE INDEX idx_activity_event_member_identity_channel_local ON " +
                    "activity_event_identity_member(identity_summary_id,channel_id,observed_local_id)",
                "CREATE INDEX idx_receiver_activity_event_source_time ON receiver_activity_event(source_identity_summary_id)",
                "CREATE INDEX idx_receiver_activity_event_target_time ON receiver_activity_event(target_identity_summary_id)",
                "CREATE INDEX idx_receiver_activity_event_source_identity_address ON " +
                    "receiver_activity_event(source_identity_summary_id,source_observed_local_id,channel_id) " +
                    "WHERE source_identity_summary_id IS NOT NULL AND source_observed_local_id>0",
                "CREATE INDEX idx_receiver_activity_event_target_identity_address ON " +
                    "receiver_activity_event(target_identity_summary_id,target_observed_local_id,channel_id) " +
                    "WHERE target_identity_summary_id IS NOT NULL AND target_observed_local_id>0",
                "CREATE INDEX idx_receiver_activity_event_id_channel ON receiver_activity_event(id,channel_id)",
                "CREATE INDEX idx_trunked_radio_affiliation_talkgroup ON trunked_radio_affiliation(radio_system_id,talkgroup_identity_id)"))
            {
                statement.execute(ddl);
            }
            statement.executeUpdate("""
                INSERT INTO radio_system VALUES
                    (1,'p25:bee00:001',1,0,'one'),(2,'p25:bee00:002',1,0,'two'),
                    (3,'nxdn-d:channel:three',4,2,'three');
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel VALUES
                    ('one',10,'City Operations'),('one-b',11,'County Operations'),
                    ('two',20,'Metro Home'),('three',30,'NXDN Network');
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel VALUES (1,1,'one'),(11,1,'one-b'),(2,2,'two'),(3,3,'three');
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary VALUES
                    (1001,1,202,0xBEE00,1,NULL,'Unit Alpha'),
                    (1002,1,203,0xBEE00,1,NULL,NULL),
                    (1003,1,204,0xBEE00,1,NULL,NULL),
                    (1004,1,205,0xBEE00,1,NULL,NULL),
                    (1005,1,206,0xBEE00,1,NULL,NULL),
                    (1006,1,300,0xBEE00,1,10,NULL),
                    (1007,1,301,0xBEE00,2,NULL,NULL),
                    (1008,1,302,0xBEE00,1,NULL,NULL),
                    (1009,1,303,0xBEE00,1,NULL,NULL),
                    (1010,1,304,0xBEE00,1,NULL,NULL),
                    (2001,2,202,0xBEE00,2,NULL,NULL),
                    (3001,3,(12 << 11) + 345,0,0,NULL,NULL);
                """);
            statement.executeUpdate("""
                INSERT INTO alias VALUES
                    (1,10,'Engine Twelve','West apparatus','Fire radios','RADIO_ID','APCO25',202,NULL,NULL),
                    (2,10,'Engine Fleet','Regional apparatus','Fire radios','RADIO_ID_RANGE','APCO25_PHASE2',NULL,200,300),
                    (3,10,'Local Assignment',NULL,NULL,'RADIO_ID','APCO25',555,NULL,NULL),
                    (4,10,'Detail Assignment',NULL,NULL,'RADIO_ID','APCO25',666,NULL,NULL),
                    (5,10,'Member Assignment',NULL,NULL,'RADIO_ID','APCO25',888,NULL,NULL),
                    (6,10,'Target Assignment',NULL,NULL,'RADIO_ID','APCO25',777,NULL,NULL),
                    (7,10,'Canonical Officer',NULL,NULL,'P25_SUBSCRIBER_IDENTITY','APCO25',NULL,NULL,NULL),
                    (8,20,'Foreign Unit',NULL,NULL,'RADIO_ID','APCO25',202,NULL,NULL),
                    (9,99,'Unassigned Unit',NULL,NULL,'RADIO_ID','APCO25',202,NULL,NULL),
                    (10,10,'Dispatch',NULL,NULL,'TALKGROUP','APCO25',202,NULL,NULL),
                    (11,10,'Dispatch Fleet',NULL,NULL,'TALKGROUP_RANGE','APCO25',NULL,203,204);
                """);
            statement.executeUpdate("INSERT INTO alias_p25_subscriber_identity VALUES (7,10)");
            statement.executeUpdate("INSERT INTO trunked_radio_channel_presence VALUES " +
                "(1,1003,1,555),(1,1010,2,555)");
            statement.executeUpdate("INSERT INTO receiver_activity_event VALUES " +
                "(1,1,1004,666,NULL,NULL),(2,1,1005,666,NULL,NULL)," +
                "(3,1,NULL,NULL,NULL,NULL),(4,1,NULL,NULL,1009,777)");
            statement.executeUpdate("INSERT INTO activity_event_identity_member VALUES (3,1008,888,1)");
            statement.executeUpdate("INSERT INTO p25_site_call_identity_bucket VALUES (1005,99,999)");
        }
    }

    @AfterEach
    void tearDown() throws Exception
    {
        mConnection.close();
    }

    @Test
    void preservesNamesNumbersRangesAndObservedAddressScope() throws Exception
    {
        assertEquals(List.of(1001L), search(1,"ENGINE TWELVE","RADIO_ID"));
        assertEquals(List.of(1001L), search(1,"west apparatus","RADIO_ID"));
        assertEquals(List.of(1001L,1002L,1005L,1006L), search(1,"fire radios","RADIO_ID"));
        assertEquals(List.of(1001L), search(1,"unit alpha","RADIO_ID"));
        assertEquals(List.of(1001L), search(1,"202","RADIO_ID"));
        assertEquals(List.of(1003L), search(1,"local assignment","RADIO_ID"));
        assertEquals(List.of(1004L), search(1,"detail assignment","RADIO_ID"));
        assertEquals(List.of(1008L), search(1,"member assignment","RADIO_ID"));
        assertEquals(List.of(1009L), search(1,"target assignment","RADIO_ID"));
        assertEquals(List.of(1006L), search(1,"canonical officer","RADIO_ID"));
        assertEquals(List.of(), search(1,"unassigned unit","RADIO_ID"));
        assertEquals(List.of(), search(1,"foreign unit","RADIO_ID"));
        assertEquals(List.of(2001L), search(2,"foreign unit","RADIO_ID"));
        assertEquals(List.of(1001L,1002L,1003L), search(1,"dispatch","TALKGROUP"));
        assertEquals(List.of(1002L,1003L), search(1,"dispatch fleet","TALKGROUP"));
        assertEquals(List.of(3001L), search(3,"12-0345","RADIO_ID"));
    }

    @Test
    void matchesConfiguredAndHomeSystemNamesWithoutRepeatingNamesForEachIdentity() throws Exception
    {
        List<Long> all = List.of(1001L,1002L,1003L,1004L,1005L,1006L,1007L,1008L,1009L,1010L);
        assertEquals(all, search(1,"CITY OPERATIONS","RADIO_ID"));
        assertEquals(all, search(1,"county operations","RADIO_ID"));
        assertEquals(List.of(1007L), search(1,"metro home","RADIO_ID"));
        assertEquals(all, search(1,"City Operations / County Operations","RADIO_ID"),
            "Search must retain the configured-name concatenation shown to users");
        Query query = query(1,"not configured","RADIO_ID");
        List<String> plan = plan(query);
        assertTrue(plan.stream().anyMatch(detail -> detail.contains("MATERIALIZE identity_search_aliases")),
            () -> "Alias text candidates must be materialized: " + plan);
        assertFalse(plan.stream().anyMatch(detail -> detail.contains("SCAN evidence") ||
            detail.contains("SCAN receiver_activity_event")), () -> "Evidence must remain identity indexed: " + plan);
        assertEquals(9, query.parameters().size(),
            "Two native forms, two system-name sets, OTA and three Alias fields are bound once after the owner");
    }

    @Test
    void appliesEscapedFriendlySearchToTheCompleteScopeBeforePaging() throws Exception
    {
        try(Statement statement = mConnection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE identities(value) AS (
                    SELECT 1000 UNION ALL SELECT value+1 FROM identities WHERE value<1099)
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_id,home_wacn,home_system_id)
                    SELECT value+10000,1,value,0xBEE00,1 FROM identities
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) VALUES
                    (100,10,'Needle%_\\tail','RADIO_ID','APCO25',1098),
                    (101,10,'Needle%_\\tail','RADIO_ID','APCO25',1099),
                    (102,10,'NeedleABCtail','RADIO_ID','APCO25',1097)
                """);
        }
        Query query = query(1,"needle%_\\tail","RADIO_ID");
        assertEquals(List.of(11098L,11099L), execute(query));
        Query secondPage = new Query(query.sql()+" LIMIT ? OFFSET ?",
            new ArrayList<>(query.parameters()));
        secondPage.parameters().add(1);
        secondPage.parameters().add(1);
        assertEquals(List.of(11099L), execute(secondPage),
            "A friendly match beyond the first native-ID page must remain findable");
    }

    @Test
    void memberEvidenceUsesStoredChannelWithoutJoiningParentEvents() throws Exception
    {
        try(Statement statement = mConnection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE unrelated(value) AS (
                    SELECT 1 UNION ALL SELECT value+1 FROM unrelated WHERE value<10000)
                INSERT INTO receiver_activity_event(id,channel_id)
                    SELECT value+10000,1 FROM unrelated
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member(event_id,identity_summary_id,observed_local_id,channel_id)
                    SELECT id,2001,888,channel_id FROM receiver_activity_event WHERE id>10000
                """);
            statement.execute("CREATE INDEX idx_receiver_activity_event_channel_action_time " +
                "ON receiver_activity_event(channel_id,id)");
            statement.execute("ANALYZE");
        }
        Query query = query(1,"member assignment","RADIO_ID");
        assertEquals(List.of(1008L),execute(query),
            "Unrelated channel history must not supply the requested identity's Alias evidence");
        List<String> plan = plan(query);
        assertTrue(plan.stream().anyMatch(detail -> detail.contains(
                "SEARCH evidence USING COVERING INDEX idx_activity_event_member_identity_channel_local " +
                    "(identity_summary_id=?)")),
            () -> "Member Alias evidence must seek its stored channel through the covering index: " + plan);
        assertFalse(plan.stream().anyMatch(detail -> detail.contains("SEARCH event USING") ||
                detail.contains("SCAN event")),
            () -> "Member Alias evidence must not join retained parent events: " + plan);
    }

    @Test
    void wrongIdentityKindAliasDoesNotReadHistoricalEvidence() throws Exception
    {
        long[] virtualMachineSteps = {0};
        ProgressHandler counter = new ProgressHandler()
        {
            @Override
            protected int progress()
            {
                virtualMachineSteps[0]++;
                return 0;
            }
        };
        ProgressHandler.setHandler(mConnection,1,counter);
        try
        {
            assertEquals(List.of(),search(1,"dispatch","RADIO_ID"));
            long stepsWithoutHistory = virtualMachineSteps[0];
            ProgressHandler.clearHandler(mConnection);
            try(Statement statement = mConnection.createStatement())
            {
                statement.executeUpdate("""
                    WITH RECURSIVE observations(value) AS (
                        SELECT 1 UNION ALL SELECT value+1 FROM observations WHERE value<20000)
                    INSERT INTO p25_site_call_identity_bucket(identity_summary_id,channel_id,observed_local_id)
                        SELECT 1002,1,90000 FROM observations
                    """);
            }
            virtualMachineSteps[0] = 0;
            ProgressHandler.setHandler(mConnection,1,counter);
            assertEquals(List.of(),search(1,"dispatch","RADIO_ID"));
            assertEquals(stepsWithoutHistory,virtualMachineSteps[0],
                "A matching group Alias must not make radio search read added historical observations");
        }
        finally
        {
            ProgressHandler.clearHandler(mConnection);
        }
    }

    @Test
    void compactEvidenceSuppressesDetailedHistoryBeforeScanningEvents() throws Exception
    {
        long[] virtualMachineSteps = {0};
        ProgressHandler counter = new ProgressHandler()
        {
            @Override
            protected int progress()
            {
                virtualMachineSteps[0]++;
                return 0;
            }
        };
        ProgressHandler.setHandler(mConnection,1,counter);
        try
        {
            assertEquals(List.of(1004L),search(1,"detail assignment","RADIO_ID"));
            long stepsWithoutHistory = virtualMachineSteps[0];
            ProgressHandler.clearHandler(mConnection);
            try(Statement statement = mConnection.createStatement())
            {
                // Identity 1005 has raw compact evidence on an unavailable channel. It still
                // suppresses detail, even though that compact evidence cannot match an Alias.
                statement.executeUpdate("""
                    WITH RECURSIVE events(value) AS (
                        SELECT 1 UNION ALL SELECT value+1 FROM events WHERE value<100000)
                    INSERT INTO receiver_activity_event(id,channel_id,
                        source_identity_summary_id,source_observed_local_id,
                        target_identity_summary_id,target_observed_local_id)
                    SELECT value+10000,1,1005,666,1005,666 FROM events
                    """);
            }
            virtualMachineSteps[0] = 0;
            ProgressHandler.setHandler(mConnection,1,counter);
            assertEquals(List.of(1004L),search(1,"detail assignment","RADIO_ID"));
            assertEquals(stepsWithoutHistory,virtualMachineSteps[0],
                "Raw compact evidence must suppress both detailed roles before retained events are read");
        }
        finally
        {
            ProgressHandler.clearHandler(mConnection);
        }
    }

    @Test
    void canonicalOnlyAliasDoesNotReadLocalMatcherEvidence() throws Exception
    {
        long[] virtualMachineSteps = {0};
        ProgressHandler counter = new ProgressHandler()
        {
            @Override
            protected int progress()
            {
                virtualMachineSteps[0]++;
                return 0;
            }
        };
        ProgressHandler.setHandler(mConnection,1,counter);
        try
        {
            assertEquals(List.of(1006L),search(1,"canonical officer","RADIO_ID"));
            long stepsWithoutHistory = virtualMachineSteps[0];
            ProgressHandler.clearHandler(mConnection);
            try(Statement statement = mConnection.createStatement())
            {
                statement.executeUpdate("""
                    WITH RECURSIVE observations(value) AS (
                        SELECT 1 UNION ALL SELECT value+1 FROM observations WHERE value<100000)
                    INSERT INTO p25_site_call_identity_bucket(identity_summary_id,channel_id,observed_local_id)
                        SELECT 1002,1,0 FROM observations
                    """);
            }
            virtualMachineSteps[0] = 0;
            ProgressHandler.setHandler(mConnection,1,counter);
            assertEquals(List.of(1006L),search(1,"canonical officer","RADIO_ID"));
            assertEquals(stepsWithoutHistory,virtualMachineSteps[0],
                "Canonical-only named candidates must not visit exact or ranged local address evidence");
        }
        finally
        {
            ProgressHandler.clearHandler(mConnection);
        }
    }

    @Test
    void bucketAliasesSeekObservedAddressBeforeReadingUnrelatedHistory() throws Exception
    {
        try(Statement statement = mConnection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE identities(value) AS (
                    SELECT 1 UNION ALL SELECT value+1 FROM identities WHERE value<200)
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_id,home_wacn,home_system_id)
                    SELECT value+50000,1,value+10000,0xBEE00,1 FROM identities
                """);
        }
        long[] virtualMachineSteps = {0};
        ProgressHandler counter = new ProgressHandler()
        {
            @Override
            protected int progress()
            {
                virtualMachineSteps[0]++;
                return 0;
            }
        };
        ProgressHandler.setHandler(mConnection,1,counter);
        try
        {
            assertEquals(List.of(1001L),search(1,"engine twelve","RADIO_ID"));
            long stepsWithoutHistory = virtualMachineSteps[0];
            ProgressHandler.clearHandler(mConnection);
            try(Statement statement = mConnection.createStatement())
            {
                statement.executeUpdate("""
                    WITH RECURSIVE observations(value) AS (
                        SELECT 1 UNION ALL SELECT value+1 FROM observations WHERE value<2200)
                    INSERT INTO p25_site_call_identity_bucket(identity_summary_id,channel_id,observed_local_id)
                        SELECT summary.id,1,90000 FROM radio_system_identity_summary summary
                            CROSS JOIN observations WHERE summary.id>50000
                    """);
            }
            virtualMachineSteps[0] = 0;
            ProgressHandler.setHandler(mConnection,1,counter);
            assertEquals(List.of(1001L),search(1,"engine twelve","RADIO_ID"));
            assertTrue(virtualMachineSteps[0]<stepsWithoutHistory*2,
                () -> "440,000 unrelated observed addresses must not be scanned for a named Alias; " +
                    "before="+stepsWithoutHistory+", after="+virtualMachineSteps[0]);
        }
        finally
        {
            ProgressHandler.clearHandler(mConnection);
        }
        List<String> exactPlan = plan(query(1,"engine twelve","RADIO_ID"));
        assertTrue(exactPlan.stream().anyMatch(detail -> detail.contains(
                "USING COVERING INDEX idx_p25_site_call_identity_identity_address " +
                    "(identity_summary_id=? AND observed_local_id=?)")),
            () -> "Exact Alias lookup must seek identity and observed address: " + exactPlan);
        List<String> rangePlan = plan(query(1,"engine fleet","RADIO_ID"));
        assertTrue(rangePlan.stream().anyMatch(detail -> detail.contains(
                "USING COVERING INDEX idx_p25_site_call_identity_identity_address " +
                    "(identity_summary_id=? AND observed_local_id>? AND observed_local_id<?)")),
            () -> "Ranged Alias lookup must seek its observed-address bounds: " + rangePlan);
    }

    @Test
    void detailAliasesSeekObservedAddressBeforeReadingUnrelatedHistory() throws Exception
    {
        try(Statement statement = mConnection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE identities(value) AS (
                    SELECT 1 UNION ALL SELECT value+1 FROM identities WHERE value<200)
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_id,home_wacn,home_system_id)
                    SELECT value+50000,1,value+10000,0xBEE00,1 FROM identities
                """);
        }
        long[] virtualMachineSteps = {0};
        ProgressHandler counter = new ProgressHandler()
        {
            @Override
            protected int progress()
            {
                virtualMachineSteps[0]++;
                return 0;
            }
        };
        ProgressHandler.setHandler(mConnection,1,counter);
        try
        {
            assertEquals(List.of(1001L),search(1,"engine twelve","RADIO_ID"));
            long stepsWithoutHistory = virtualMachineSteps[0];
            ProgressHandler.clearHandler(mConnection);
            try(Statement statement = mConnection.createStatement())
            {
                statement.executeUpdate("""
                    WITH RECURSIVE observations(value) AS (
                        SELECT 1 UNION ALL SELECT value+1 FROM observations WHERE value<2200)
                    INSERT INTO receiver_activity_event(id,channel_id,source_identity_summary_id,
                        source_observed_local_id,target_identity_summary_id,target_observed_local_id)
                        SELECT (summary.id-50000)*3000+observations.value+50000,1,summary.id,90000,
                            summary.id,90000 FROM radio_system_identity_summary summary
                            CROSS JOIN observations WHERE summary.id>50000
                    """);
            }
            virtualMachineSteps[0] = 0;
            ProgressHandler.setHandler(mConnection,1,counter);
            assertEquals(List.of(1001L),search(1,"engine twelve","RADIO_ID"));
            assertTrue(virtualMachineSteps[0]<stepsWithoutHistory*2,
                () -> "440,000 unrelated addresses in both event roles must not be scanned for a named Alias; " +
                    "before="+stepsWithoutHistory+", after="+virtualMachineSteps[0]);
        }
        finally
        {
            ProgressHandler.clearHandler(mConnection);
        }
        List<String> exactPlan = plan(query(1,"engine twelve","RADIO_ID"));
        List<String> rangePlan = plan(query(1,"engine fleet","RADIO_ID"));
        for(String role: List.of("source","target"))
        {
            String index = "USING COVERING INDEX idx_receiver_activity_event_"+role+"_identity_address ";
            String columns = role+"_identity_summary_id=? AND "+role+"_observed_local_id";
            assertTrue(exactPlan.stream().anyMatch(detail -> detail.contains(index+"("+columns+"=?)")),
                () -> "Exact Alias lookup must seek identity and observed address for "+role+": "+exactPlan);
            assertTrue(rangePlan.stream().anyMatch(detail -> detail.contains(index+"("+columns+">? AND "+
                    role+"_observed_local_id<?)")),
                () -> "Ranged Alias lookup must seek observed-address bounds for "+role+": "+rangePlan);
        }
    }

    @Test
    void configuredAliasesPreserveDuplicateNullAndWrongOwnerMatches() throws Exception
    {
        try(Statement statement = mConnection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias VALUES
                    (200,11,'Engine Twelve','West apparatus','Fire radios','RADIO_ID','APCO25_PHASE2',202,NULL,NULL),
                    (201,10,NULL,'Nullable details','Nullable group','RADIO_ID','APCO25',203,NULL,NULL),
                    (202,20,'Other owner',NULL,NULL,'RADIO_ID','APCO25',203,NULL,NULL),
                    (203,10,'Wrong protocol',NULL,NULL,'RADIO_ID','DMR',203,NULL,NULL),
                    (204,NULL,'Unassigned null list',NULL,NULL,'RADIO_ID','APCO25',203,NULL,NULL),
                    (205,10,'Suppressed native',NULL,NULL,'RADIO_ID','APCO25',204,NULL,NULL),
                    (206,99,'Unassigned canonical',NULL,NULL,'P25_SUBSCRIBER_IDENTITY','APCO25',NULL,NULL,NULL),
                    (207,10,'Foreign member',NULL,NULL,'RADIO_ID','APCO25',889,NULL,NULL)
                """);
            statement.executeUpdate("INSERT INTO alias_p25_subscriber_identity VALUES (206,10)");
            statement.executeUpdate("INSERT INTO receiver_activity_event(id,channel_id) VALUES (10,2)");
            statement.executeUpdate("INSERT INTO activity_event_identity_member VALUES (10,1002,889,2)");
        }
        assertEquals(List.of(1001L),search(1,"engine twelve","RADIO_ID"),
            "Equivalent named candidates from two assigned lists must not duplicate an identity");
        assertEquals(List.of(1002L),search(1,"nullable details","RADIO_ID"));
        assertEquals(List.of(1002L),search(1,"nullable group","RADIO_ID"));
        assertEquals(List.of(),search(1,"other owner","RADIO_ID"));
        assertEquals(List.of(),search(1,"wrong protocol","RADIO_ID"));
        assertEquals(List.of(),search(1,"unassigned null list","RADIO_ID"));
        assertEquals(List.of(),search(1,"unassigned canonical","RADIO_ID"));
        assertEquals(List.of(),search(1,"foreign member","RADIO_ID"),
            "Stored member channels must retain saved-channel system ownership");
        assertEquals(List.of(),search(1,"suppressed native","RADIO_ID"),
            "A cheap native match must still pass the same observed-address namespace test");
        assertEquals(List.of(1003L),search(1,"local assignment","RADIO_ID"));
    }

    private List<Long> search(long systemId, String search, String matcher) throws Exception
    {
        return execute(query(systemId,search,matcher));
    }

    private Query query(long systemId, String search, String matcher)
    {
        StringBuilder sql = new StringBuilder("SELECT summary.id FROM radio_system_identity_summary summary " +
            "JOIN radio_system system ON system.id=summary.radio_system_id WHERE system.id=?");
        List<Object> parameters = new ArrayList<>(List.of(systemId));
        StatsIdentitySearch.append(sql,parameters,search,matcher,"RADIO_ID".equals(matcher));
        sql.append(" ORDER BY summary.id");
        return new Query(sql.toString(),parameters);
    }

    private List<Long> execute(Query query) throws Exception
    {
        try(PreparedStatement statement = prepare(query, ""))
        {
            List<Long> rows = new ArrayList<>();
            try(ResultSet results = statement.executeQuery())
            {
                while(results.next()) rows.add(results.getLong(1));
            }
            return rows;
        }
    }

    private List<String> plan(Query query) throws Exception
    {
        try(PreparedStatement statement = prepare(query, "EXPLAIN QUERY PLAN "))
        {
            List<String> rows = new ArrayList<>();
            try(ResultSet results = statement.executeQuery())
            {
                while(results.next()) rows.add(results.getString("detail"));
            }
            return rows;
        }
    }

    private PreparedStatement prepare(Query query, String prefix) throws Exception
    {
        PreparedStatement statement = mConnection.prepareStatement(prefix+query.sql());
        for(int index=0; index<query.parameters().size(); index++)
            statement.setObject(index+1,query.parameters().get(index));
        return statement;
    }

    private record Query(String sql, List<Object> parameters) {}
}
