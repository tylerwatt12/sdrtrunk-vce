/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasActivityProjectionTest
{
    private static final String DMR_CONFIGURATION = "81000000-0000-4000-8000-000000000001";
    private static final String P25_A = "82000000-0000-4000-8000-000000000001";
    private static final String P25_B = "82000000-0000-4000-8000-000000000002";
    private static final String P25_C = "82000000-0000-4000-8000-000000000003";
    private static final String DMR_CONVENTIONAL = "83000000-0000-4000-8000-000000000001";
    private static final String NXDN_CONVENTIONAL = "83000000-0000-4000-8000-000000000002";
    private static final String P25_SYSTEM_KEY = "p25:bee00:348";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void exactAndDuplicateWinnersBeatRangesWithoutDoubleCountingTheCallStart() throws Exception
    {
        try(Connection connection = open("dmr-winners.sqlite"); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(1,'DMR','DMR')");
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value) VALUES
                    (1,1,'Older exact','TALKGROUP','DMR',91,NULL,NULL),
                    (2,1,'Newer exact','TALKGROUP','DMR',91,NULL,NULL),
                    (3,1,'Range','TALKGROUP_RANGE','DMR',NULL,90,100),
                    (4,1,'Radio','RADIO_ID','DMR',501,NULL,NULL)
                """);
            insertConfiguration(statement, DMR_CONFIGURATION, 1, "DMR");

            ReceiverActivityRecords.ActivityEvent callStart = new ReceiverActivityRecords.ActivityEvent(
                1_000, DMR_CONFIGURATION, ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "DMR",
                ReceiverActivityRecords.Action.CALL, "CALL_GROUP", "501", "91", "TALKGROUP", List.of(),
                451_000_000L, null, 1, false, null, null, null, 42, null, null, 1, null, true, null, null,
                TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.UNKNOWN,
                ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), null);
            AliasActivityProjection.recordActivity(connection, callStart);
            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(1, DMR_CONFIGURATION,
                "DMR", 91, "TALKGROUP", 501, List.of(), List.of(), null,
                ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN));
            AliasActivityProjection.recordLogicalCallOutput(connection,
                new ReceiverActivityRecords.LogicalCallOutput(logicalCall(1, DMR_CONFIGURATION, "DMR", 91,
                    "TALKGROUP", 501, List.of(), List.of(), null,
                    ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN),
                    ReceiverActivityRecords.CallOutput.RECORDED));

            ReceiverActivityRecords.ActivityEvent join = new ReceiverActivityRecords.ActivityEvent(
                1_001, DMR_CONFIGURATION, ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "DMR",
                ReceiverActivityRecords.Action.JOIN, "AFFILIATE", "501", "91", "TALKGROUP", List.of(),
                451_000_000L, null, 1, false, null, null, null, 42, null, null, 1, null, false, null, null,
                TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.UNKNOWN,
                ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), null);
            AliasActivityProjection.recordActivity(connection, join);
            ReceiverActivityRecords.ActivityEvent continueEvent = new ReceiverActivityRecords.ActivityEvent(
                2_000, DMR_CONFIGURATION, ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "DMR",
                ReceiverActivityRecords.Action.CONTINUE, "CONTINUE", "501", "91", "TALKGROUP", List.of(),
                451_000_000L, null, 1, false, null, null, null, 42, null, null, 1, null, false, null, null,
                TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.UNKNOWN,
                ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), null);
            AliasActivityProjection.recordActivity(connection, continueEvent);
            ReceiverActivityRecords.ActivityEvent unknownEvent = new ReceiverActivityRecords.ActivityEvent(
                2_500, DMR_CONFIGURATION, ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "DMR",
                ReceiverActivityRecords.Action.UNKNOWN, "UNKNOWN", "501", "91", "TALKGROUP", List.of(),
                451_000_000L, null, 1, false, null, null, null, 42, null, null, 1, null, false, null, null,
                TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.UNKNOWN,
                ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), null);
            AliasActivityProjection.recordActivity(connection, unknownEvent);
            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(2, DMR_CONFIGURATION,
                "DMR", 95, "TALKGROUP", null, List.of(), List.of(), null,
                ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN));

            assertEquals(0, metric(connection, 1, "logical_call_count"));
            assertEquals(1, metric(connection, 2, "logical_call_count"));
            assertEquals(1, metric(connection, 2, "recorded_logical_call_count"));
            assertEquals(1, metric(connection, 2, "join_observation_count"));
            assertEquals(1, metric(connection, 2, "signaling_observation_count"));
            assertEquals(2, metric(connection, 2, "other_signaling_observation_count"));
            assertEquals(2_500, metric(connection, 2, "last_evidence_ms"),
                "CONTINUE and UNKNOWN are retained as other signaling evidence without incrementing signaling");
            long callsBeforeAttribution = metric(connection, 4, "logical_call_count");
            AliasActivityProjection.recordTrunkedAttribution(connection,
                new ReceiverActivityRecords.TrunkedCallAttribution(3_000, DMR_CONFIGURATION, "DMR",
                    451_000_000L, 1, 91, "TALKGROUP", List.of(), 501, null, null,
                    false, true, false, false, TrunkedIdentityDomain.STANDARD, null));
            assertEquals(callsBeforeAttribution, metric(connection, 4, "logical_call_count"),
                "late identity attribution must not count the call a second time");
            assertEquals(3_000, metric(connection, 4, "last_evidence_ms"));
            AliasActivityProjection.recordTrunkedAttribution(connection,
                new ReceiverActivityRecords.TrunkedCallAttribution(3_500, DMR_CONFIGURATION, "DMR",
                    451_000_000L, 1, 91, "PATCH_GROUP", List.of(95), null, null, null,
                    true, false, false, false, TrunkedIdentityDomain.STANDARD, null));
            assertEquals(1, metric(connection, 2, "logical_call_count"));
            assertEquals(1, metric(connection, 3, "logical_call_count"));
            assertEquals(3_500, metric(connection, 3, "last_evidence_ms"),
                "late patch attribution touches each newly known member without recounting it");
            assertEquals(1, metric(connection, 4, "logical_call_count"));
        }
    }

    @Test
    void p25MultisiteConsensusDeduplicatesQualifiedPatchMembersAndCanonicalRadios() throws Exception
    {
        try(Connection connection = open("p25-multisite.sqlite"); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(id,name,family) VALUES(1,'P25 One','P25'),(2,'P25 Two','P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value) VALUES
                    (10,1,'Target','TALKGROUP','APCO25',100,NULL,NULL),
                    (11,1,'Patch member','TALKGROUP','APCO25',200,NULL,NULL),
                    (12,1,'Source','RADIO_ID','APCO25',500,NULL,NULL),
                    (13,1,'Radio range','RADIO_ID_RANGE','APCO25',NULL,600,800),
                    (20,2,'Conflicting target','TALKGROUP','APCO25',100,NULL,NULL)
                """);
            insertConfiguration(statement, P25_A, 1, "P25_PHASE1");
            insertConfiguration(statement, P25_B, 1, "P25_PHASE1");
            insertConfiguration(statement, P25_C, 2, "P25_PHASE1");
            statement.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                    first_seen_ms,last_seen_ms)
                VALUES(82,'p25:bee00:348',1,0,0xBEE00,0x348,1,2)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,
                    radio_system_assigned_at_ms) VALUES
                    (821,'%s',1,2,82,1),(822,'%s',1,2,82,1)
                """.formatted(P25_A, P25_B));

            ReceiverActivityRecords.P25Identity qualifiedMember =
                ReceiverActivityRecords.P25Identity.fullyQualifiedGroup(0xBEE00, 0x999, 200);
            ReceiverActivityRecords.P25PatchMemberIdentity member =
                new ReceiverActivityRecords.P25PatchMemberIdentity(200, qualifiedMember);
            List<ReceiverActivityRecords.P25SiteCallObservation> observations = List.of(
                p25Observation(P25_A, 1, 500, 100, List.of(200), List.of(member)),
                p25Observation(P25_B, 2, 500, 100, List.of(200), List.of(member)),
                p25Observation(P25_C, 3, 500, 100, List.of(200), List.of(member)),
                new ReceiverActivityRecords.P25SiteCallObservation(P25_C,
                    new P25SiteIdentity(0xBEE00, 0x349, 1, 3), 500, 100, "TALKGROUP",
                    ReceiverActivityRecords.P25Identity.ORDINARY,
                    ReceiverActivityRecords.P25Identity.ORDINARY, List.of(200), List.of(member)));
            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(10, P25_A, "APCO25",
                100, "PATCH_GROUP", 500, List.of(200), observations, P25_SYSTEM_KEY,
                ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY));

            assertEquals(1, metric(connection, 10, "logical_call_count"));
            assertEquals(1, metric(connection, 11, "logical_call_count"),
                "the flattened and fully-qualified forms are one patch member");
            assertEquals(1, metric(connection, 12, "logical_call_count"));

            ReceiverActivityRecords.P25Identity sameRadio =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x123, 700_001);
            AliasActivityProjection.recordActivity(connection, p25RadioSignal(700, 701,
                sameRadio, sameRadio, ReceiverActivityRecords.Action.JOIN));
            assertEquals(1, metric(connection, 13, "join_observation_count"),
                "one canonical radio observed as source and destination receives one signaling increment");

            ReceiverActivityRecords.P25Identity otherRadio =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x124, 700_001);
            AliasActivityProjection.recordActivity(connection, p25RadioSignal(700, 700,
                sameRadio, otherRadio, ReceiverActivityRecords.Action.REGISTER));
            assertEquals(2, metric(connection, 13, "register_observation_count"),
                "distinct canonical radios resolving to one range alias each contribute");
            assertEquals(3, metric(connection, 13, "signaling_observation_count"));

            ReceiverActivityRecords.P25Identity canonical =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x123, 700_001);
            List<ReceiverActivityRecords.P25SiteCallObservation> sameCanonical = List.of(
                p25Observation(P25_A, 1, 700, 701, List.of(), List.of()));
            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(11, P25_A, "APCO25",
                701, "RADIO", 700, List.of(), sameCanonical, P25_SYSTEM_KEY, canonical, canonical));
            assertEquals(1, metric(connection, 13, "logical_call_count"),
                "one canonical radio heard under two local addresses is counted once");

            ReceiverActivityRecords.P25Identity source =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x123, 700_001);
            ReceiverActivityRecords.P25Identity destination =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xABCDE, 0x124, 700_001);
            List<ReceiverActivityRecords.P25SiteCallObservation> distinctCanonical = List.of(
                p25Observation(P25_A, 1, 700, 700, List.of(), List.of()));
            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(12, P25_A, "APCO25",
                700, "RADIO", 700, List.of(), distinctCanonical, P25_SYSTEM_KEY, destination, source));
            assertEquals(3, metric(connection, 13, "logical_call_count"),
                "distinct canonical radios that share one range alias each contribute");

            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(13, P25_A, "APCO25",
                100, "TALKGROUP", null, List.of(), List.of(), P25_SYSTEM_KEY,
                ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY));
            assertEquals(2, metric(connection, 10, "logical_call_count"));

            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,
                    radio_system_assigned_at_ms) VALUES(823,'%s',1,2,82,1)
                """.formatted(P25_C));
            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(14, P25_A, "APCO25",
                100, "TALKGROUP", null, List.of(), List.of(), P25_SYSTEM_KEY,
                ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY));
            assertEquals(2, metric(connection, 10, "logical_call_count"),
                "system evidence without site provenance is withheld when assigned Alias Lists disagree");
            assertEquals(0, metric(connection, 20, "logical_call_count"));
        }
    }

    @Test
    void rejectedIncompleteP25SystemDoesNotProjectRawAliasActivity() throws Exception
    {
        try(Connection connection = open("incomplete-p25.sqlite");
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(1,'P25','P25')");
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) VALUES
                    (21,1,'Group','TALKGROUP','APCO25',100),
                    (22,1,'Radio','RADIO_ID','APCO25',500)
                """);
            insertConfiguration(statement, P25_A, 1, "P25_PHASE1");
            ReceiverActivityRecords.ActivityEvent incomplete = new ReceiverActivityRecords.ActivityEvent(
                2_000, P25_A, ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25",
                ReceiverActivityRecords.Action.JOIN, "JOIN", "500", "100", "TALKGROUP", List.of(),
                851_012_500L, null, null, false, null, null, null, null, null, 1, 1, null, false, null, null,
                TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.ORDINARY,
                ReceiverActivityRecords.P25Identity.ORDINARY, List.of(), null);

            assertNull(ReceiverActivitySchema.recordActivity(connection, incomplete, false));
            assertEquals(0, metric(connection, 21, "join_observation_count"));
            assertEquals(0, metric(connection, 22, "join_observation_count"));
        }
    }

    @Test
    void conventionalDmrAndNxdnUseTheirAssignedListAndPreserveExactRangeOutputSemantics() throws Exception
    {
        try(Connection connection = open("conventional-aliases.sqlite");
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(id,name,family) VALUES
                    (1,'DMR Conventional','DMR'),(2,'Other DMR','DMR'),(3,'NXDN Conventional','NXDN')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value) VALUES
                    (31,1,'DMR exact group','TALKGROUP','DMR',91,NULL,NULL),
                    (32,1,'DMR overlapping group range','TALKGROUP_RANGE','DMR',NULL,90,100),
                    (33,1,'DMR source range','RADIO_ID_RANGE','DMR',NULL,500,599),
                    (34,2,'Wrong-list DMR group','TALKGROUP','DMR',91,NULL,NULL),
                    (41,3,'NXDN group range','TALKGROUP_RANGE','NXDN',NULL,400,499),
                    (42,3,'NXDN exact source','RADIO_ID','NXDN',501,NULL,NULL)
                """);
            insertConventionalConfiguration(statement, DMR_CONVENTIONAL, 1, "DMR");
            insertConventionalConfiguration(statement, NXDN_CONVENTIONAL, 3, "NXDN");

            AliasActivityProjection.recordDmrConventionalCall(connection,
                new ReceiverActivityRecords.DmrConventionalCall(1_000, 2_000, DMR_CONVENTIONAL,
                    461_125_000L, 1, ReceiverActivityRecords.DmrTargetKind.GROUP, 91, 501, null, true));
            AliasActivityProjection.recordConventionalCallOutput(connection,
                new ReceiverActivityRecords.ConventionalCallOutput(1_000, DMR_CONVENTIONAL,
                    ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_DMR, "DMR", 461_125_000L, 1,
                    91, "TALKGROUP", List.of(), 501, ReceiverActivityRecords.CallOutput.RECORDED,
                    TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.UNKNOWN, List.of()));

            assertEquals(1, metric(connection, 31, "logical_call_count"));
            assertEquals(1, metric(connection, 31, "recorded_logical_call_count"));
            assertEquals(0, metric(connection, 32, "logical_call_count"), "exact DMR alias wins over range");
            assertEquals(1, metric(connection, 33, "logical_call_count"));
            assertEquals(1, metric(connection, 33, "recorded_logical_call_count"));
            assertEquals(0, metric(connection, 34, "logical_call_count"),
                "another Alias List must not lend its exact match");

            AliasActivityProjection.recordDmrConventionalCall(connection,
                new ReceiverActivityRecords.DmrConventionalCall(1_500, 1_750, DMR_CONVENTIONAL,
                    461_125_000L, 1, ReceiverActivityRecords.DmrTargetKind.PRIVATE, null, 501, 501, false));
            assertEquals(2, metric(connection, 33, "logical_call_count"),
                "a DMR self/private call is one logical identity contribution");

            AliasActivityProjection.recordConventionalCallOutput(connection,
                new ReceiverActivityRecords.ConventionalCallOutput(2_000, DMR_CONVENTIONAL,
                    ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_DMR, "DMR", 461_125_000L, 1,
                    501, "RADIO", List.of(), 501, ReceiverActivityRecords.CallOutput.RECORDED,
                    TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.UNKNOWN, List.of()));
            assertEquals(2, metric(connection, 33, "recorded_logical_call_count"),
                "one radio used as both source and destination receives one output increment");
            AliasActivityProjection.recordConventionalCallOutput(connection,
                new ReceiverActivityRecords.ConventionalCallOutput(2_500, DMR_CONVENTIONAL,
                    ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_DMR, "DMR", 461_125_000L, 1,
                    502, "RADIO", List.of(), 501, ReceiverActivityRecords.CallOutput.RECORDED,
                    TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.UNKNOWN, List.of()));
            assertEquals(4, metric(connection, 33, "recorded_logical_call_count"),
                "two distinct radios resolving to one range alias each contribute");

            AliasActivityProjection.recordNxdnConventionalCall(connection,
                new ReceiverActivityRecords.NxdnConventionalCall(3_000, 4_000, NXDN_CONVENTIONAL,
                    460_025_000L, ReceiverActivityRecords.NxdnTargetKind.GROUP, 450, 501, null, false,
                    TrunkedIdentityDomain.NXDN_TYPE_C));
            AliasActivityProjection.recordConventionalCallOutput(connection,
                new ReceiverActivityRecords.ConventionalCallOutput(3_000, NXDN_CONVENTIONAL,
                    ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_NXDN, "NXDN", 460_025_000L, null,
                    450, "TALKGROUP", List.of(), 501, ReceiverActivityRecords.CallOutput.STREAMED,
                    TrunkedIdentityDomain.NXDN_TYPE_C, ReceiverActivityRecords.P25Identity.UNKNOWN, List.of()));

            assertEquals(1, metric(connection, 41, "logical_call_count"));
            assertEquals(1, metric(connection, 41, "stream_submitted_logical_call_count"));
            assertEquals(1, metric(connection, 42, "logical_call_count"));
            assertEquals(1, metric(connection, 42, "stream_submitted_logical_call_count"));
            AliasActivityProjection.recordNxdnConventionalCall(connection,
                new ReceiverActivityRecords.NxdnConventionalCall(4_500, 4_750, NXDN_CONVENTIONAL,
                    460_025_000L, ReceiverActivityRecords.NxdnTargetKind.PRIVATE, null, 501, 501, false,
                    TrunkedIdentityDomain.NXDN_TYPE_C));
            assertEquals(2, metric(connection, 42, "logical_call_count"),
                "an NXDN self/private call is one logical identity contribution");
        }
    }

    @Test
    void nativeDmrAndNxdnCallsCreditOnlyTheCurrentlyAssignedAliasList() throws Exception
    {
        String dmrOther = "84000000-0000-4000-8000-000000000002";
        String nxdnPrimary = "84000000-0000-4000-8000-000000000003";
        String nxdnOther = "84000000-0000-4000-8000-000000000004";
        try(Connection connection = open("native-list-ownership.sqlite");
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(id,name,family) VALUES
                    (1,'DMR Primary','DMR'),(2,'DMR Other','DMR'),
                    (3,'NXDN Primary','NXDN'),(4,'NXDN Other','NXDN')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) VALUES
                    (51,1,'DMR primary group','TALKGROUP','DMR',91),
                    (52,1,'DMR primary radio','RADIO_ID','DMR',501),
                    (53,2,'DMR other group','TALKGROUP','DMR',91),
                    (54,2,'DMR other radio','RADIO_ID','DMR',501),
                    (55,3,'NXDN primary group','TALKGROUP','NXDN',191),
                    (56,3,'NXDN primary radio','RADIO_ID','NXDN',601),
                    (57,4,'NXDN other group','TALKGROUP','NXDN',191),
                    (58,4,'NXDN other radio','RADIO_ID','NXDN',601)
                """);
            insertConfiguration(statement, DMR_CONFIGURATION, 1, "DMR");
            insertConfiguration(statement, dmrOther, 2, "DMR");
            insertConfiguration(statement, nxdnPrimary, 3, "NXDN");
            insertConfiguration(statement, nxdnOther, 4, "NXDN");

            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(30, DMR_CONFIGURATION,
                "DMR", 91, "TALKGROUP", 501, List.of(), List.of(), null,
                ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN));
            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(31, nxdnPrimary,
                "NXDN", 191, "TALKGROUP", 601, List.of(), List.of(), null,
                ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN));

            assertEquals(1, metric(connection, 51, "logical_call_count"));
            assertEquals(1, metric(connection, 52, "logical_call_count"));
            assertEquals(0, metric(connection, 53, "logical_call_count"));
            assertEquals(0, metric(connection, 54, "logical_call_count"));
            assertEquals(1, metric(connection, 55, "logical_call_count"));
            assertEquals(1, metric(connection, 56, "logical_call_count"));
            assertEquals(0, metric(connection, 57, "logical_call_count"));
            assertEquals(0, metric(connection, 58, "logical_call_count"));
        }
    }

    @Test
    void largeRangeResolverUsesTheListFirstPartialIndex() throws Exception
    {
        try(Connection connection = open("large-range-resolver.sqlite");
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(1,'Large DMR','DMR')");
            statement.executeUpdate("""
                WITH RECURSIVE sequence(value) AS (
                    VALUES(1) UNION ALL SELECT value+1 FROM sequence WHERE value<100000
                )
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,min_value,max_value)
                SELECT value,1,printf('Range %06d',value),'TALKGROUP_RANGE','DMR',
                    value*10,value*10+9 FROM sequence
                """);
            insertConfiguration(statement, DMR_CONFIGURATION, 1, "DMR");

            String rangeSql = AliasActivityProjection.rangeResolverSql(3, 1);
            StringBuilder plan = new StringBuilder();
            try(PreparedStatement explain = connection.prepareStatement("EXPLAIN QUERY PLAN " + rangeSql))
            {
                explain.setLong(1, 1);
                explain.setInt(2, 999_999);
                explain.setInt(3, 999_999);
                try(ResultSet rows = explain.executeQuery())
                {
                    while(rows.next())
                    {
                        plan.append(rows.getString("detail")).append('\n');
                    }
                }
            }
            assertTrue(plan.toString().contains("idx_alias_activity_talkgroup_range"), plan.toString());

            long started = System.nanoTime();
            AliasActivityProjection.recordResolvedLogicalCall(connection, logicalCall(100, DMR_CONFIGURATION,
                "DMR", 999_999, "TALKGROUP", null, List.of(), List.of(), null,
                ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(1, metric(connection, 99_999, "logical_call_count"));
            assertTrue(elapsedMillis < 1_000, "100,000-range winner lookup took " + elapsedMillis + " ms");
            System.out.println("ALIAS_RANGE_RESOLVER elapsed_ms=" + elapsedMillis + " plan=" +
                plan.toString().trim());
        }
    }

    private Connection open(String filename) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mTemporaryFolder.resolve(filename));
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
        }
        return connection;
    }

    private static void insertConfiguration(Statement statement, String configurationId, long aliasListId,
                                            String decoder) throws Exception
    {
        String configJson = "DMR".equals(decoder) || "NXDN".equals(decoder) ?
            "{\"decodeConfiguration\":{\"channelMode\":\"TRUNKED\"}}" : "{}";
        int addressDomainCode = "NXDN".equals(decoder) ? 1 : 0;
        statement.executeUpdate("""
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                alias_list_id,decoder_type,address_domain_code,primary_frequency_hz,config_json)
            VALUES('%s','TRUNKED',0,'System','Site','Control',%d,'%s',%d,851012500,'%s')
            """.formatted(configurationId, aliasListId, decoder, addressDomainCode, configJson));
    }

    private static void insertConventionalConfiguration(Statement statement, String configurationId,
                                                        long aliasListId, String decoder) throws Exception
    {
        int addressDomainCode = "NXDN".equals(decoder) ? 1 : 0;
        statement.executeUpdate("""
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                alias_list_id,decoder_type,address_domain_code,primary_frequency_hz,config_json)
            VALUES('%s','CONVENTIONAL',0,'System','Site','Repeater',%d,'%s',%d,461125000,
                '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
            """.formatted(configurationId, aliasListId, decoder, addressDomainCode));
    }

    private static ReceiverActivityRecords.ResolvedLogicalCall logicalCall(long sequence, String configurationId,
        String protocol, int destination, String destinationKind, Integer source, List<Integer> patchMembers,
        List<ReceiverActivityRecords.P25SiteCallObservation> observations, String systemKey,
        ReceiverActivityRecords.P25Identity targetIdentity, ReceiverActivityRecords.P25Identity sourceIdentity)
    {
        return new ReceiverActivityRecords.ResolvedLogicalCall(new LogicalCallId(9, sequence), 1_000 + sequence,
            configurationId, protocol, TrunkedIdentityDomain.STANDARD,
            "APCO25".equals(protocol) ? 0xBEE00 : null, "APCO25".equals(protocol) ? 0x348 : null,
            destination, destinationKind, patchMembers, source, false, null, null, targetIdentity, sourceIdentity,
            List.of(), observations, systemKey);
    }

    private static ReceiverActivityRecords.P25SiteCallObservation p25Observation(String configurationId, int site,
        Integer source, Integer target, List<Integer> patchMembers,
        List<ReceiverActivityRecords.P25PatchMemberIdentity> memberIdentities)
    {
        return new ReceiverActivityRecords.P25SiteCallObservation(configurationId,
            new P25SiteIdentity(0xBEE00, 0x348, 1, site), source, target, "TALKGROUP",
            ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY,
            patchMembers, memberIdentities);
    }

    private static ReceiverActivityRecords.ActivityEvent p25RadioSignal(int source, int target,
        ReceiverActivityRecords.P25Identity sourceIdentity, ReceiverActivityRecords.P25Identity targetIdentity,
        ReceiverActivityRecords.Action action)
    {
        return new ReceiverActivityRecords.ActivityEvent(2_000, P25_A,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25", action, action.name(),
            Integer.toString(source), Integer.toString(target), "RADIO", List.of(), 851_012_500L,
            null, null, false, null, null, 0xBEE00, 0x348, null, 1, 1, null, false, null, null,
            TrunkedIdentityDomain.STANDARD, targetIdentity, sourceIdentity, List.of(), P25_SYSTEM_KEY);
    }

    private static long metric(Connection connection, long aliasId, String column) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT coalesce(" + column + ",0) FROM alias_activity_summary WHERE alias_id=" + aliasId))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
}
