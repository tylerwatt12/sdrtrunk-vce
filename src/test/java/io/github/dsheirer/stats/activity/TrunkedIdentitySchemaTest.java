/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelTag;
import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrunkedIdentitySchemaTest
{
    private static final long START = 1_700_000_000_000L;

    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsProtocolNeutralScopesWithNoAliasConfigurationCascade() throws Exception
    {
        try(Connection connection = open("scope-shape.sqlite"))
        {
            P25ActivityLogSchema.validate(connection);
            assertEquals(Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION), scalarText(connection, """
                SELECT value FROM database_metadata WHERE key='p25_activity_schema_version'
                """));
            assertTrue(Long.parseLong(scalarText(connection, """
                SELECT value FROM database_metadata
                WHERE key='trunked_logical_call_metrics_started_at_ms'
                """)) > 0);
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM pragma_foreign_key_list('trunked_identity_scope')
                WHERE "from"='alias_list_id'
                """));
        }
    }

    @Test
    void completedLogicalCallsOwnLifetimeIdentityCountersForAllTrunkedProtocols() throws Exception
    {
        try(Connection connection = open("all-protocols.sqlite"))
        {
            P25ActivityLogRecords.ResolvedLogicalCall p25 = p25Call(1, 17,
                List.of(new P25SiteIdentity(0x924, 0x649, 1, 1)));
            P25ActivityLogRecords.ResolvedLogicalCall dmr = contextCall(2, "dmr-site", Protocol.DMR,
                P25ActivityLogRecords.IdentityDomain.STANDARD, 91, 101);
            P25ActivityLogRecords.ResolvedLogicalCall nxdn = contextCall(3, "nxdn-site", Protocol.NXDN,
                P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, 301, 201);

            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, p25));
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, dmr));
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, nxdn));

            assertEquals(3, scalar(connection,
                "SELECT SUM(logical_call_count) FROM trunked_logical_call_bucket"));
            assertEquals(3, scalar(connection,
                "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals(6, scalar(connection,
                "SELECT SUM(logical_call_count) FROM trunked_identity_summary"));
            assertEquals(3, scalar(connection,
                "SELECT SUM(target_logical_call_count) FROM trunked_identity_summary"));
            assertEquals(3, scalar(connection,
                "SELECT SUM(source_logical_call_count) FROM trunked_identity_summary"));
            assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_call_bucket"));
        }
    }

    @Test
    void p25AliasListsRemainSeparateSystemScopes() throws Exception
    {
        try(Connection connection = open("alias-scope.sqlite"))
        {
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, p25Call(1, 11, List.of())));
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, p25Call(2, 12, List.of())));
            assertEquals(2, scalar(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope
                WHERE protocol_code=1 AND scope_kind_code=1
                """));
            assertEquals(2, scalar(connection,
                "SELECT SUM(logical_call_count) FROM trunked_logical_call_bucket"));
        }
    }

    @Test
    void logicalOutputUpdatesTheResolvedScopeWithoutAddingAnotherCall() throws Exception
    {
        try(Connection connection = open("logical-output.sqlite"))
        {
            P25ActivityLogRecords.ResolvedLogicalCall call = p25Call(1, 17, List.of());
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, call));
            assertTrue(P25ActivityLogSchema.applyLogicalCallOutput(connection,
                new P25ActivityLogRecords.LogicalCallOutput(call, P25ActivityLogRecords.CallOutput.RECORDED)));

            assertEquals(1, scalar(connection,
                "SELECT logical_call_count FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection,
                "SELECT recorded_output_count FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection, """
                SELECT recorded_output_count FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=1201
                """));
        }
    }

    @Test
    void resetClearsLogicalSiteAndIdentityFactsButKeepsSchemaValid() throws Exception
    {
        try(Connection connection = open("reset.sqlite"))
        {
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, p25Call(1, 17,
                List.of(new P25SiteIdentity(0x924, 0x649, 1, 1)))));
            assertTrue(P25ActivityLogSchema.resetStats(connection) > 0);
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM trunked_identity_summary"));
            P25ActivityLogSchema.validate(connection);
        }
    }

    @Test
    void createsCleanProtocolNeutralSchemaAndMetricsBoundary() throws Exception
    {
        Path database = database("schema.sqlite");

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.validate(connection);
            assertEquals(Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION), scalarString(connection, """
                SELECT value FROM database_metadata WHERE key='p25_activity_schema_version'
                """));
            assertTrue(Long.parseLong(scalarString(connection, """
                SELECT value FROM database_metadata
                WHERE key='trunked_identity_metrics_started_at_ms'
                """)) > 0);
            assertFalse(objectExists(connection, "table", "p25_talkgroup_summary"));
            assertFalse(objectExists(connection, "table", "p25_radio_summary"));
            assertFalse(objectExists(connection, "table", "p25_radio_talkgroup_summary"));
            assertFalse(objectExists(connection, "table", "p25_radio_affiliation"));
            assertTrue(objectExists(connection, "table", "trunked_radio_affiliation"));
            assertTrue(objectExists(connection, "table", "trunked_radio_site_presence"));
            assertTrue(objectExists(connection, "table", "trunked_radio_presence_lifecycle"));
            assertTrue(objectExists(connection, "table", "p25_zero_local_fq_talkgroup_summary"));
        }
    }

    @Test
    void keepsOrdinaryStableAndAmbiguousP25IdentityEvidenceOnOneBoundedRow() throws Exception
    {
        Path database = activityDatabase("p25-identity-state.sqlite");
        String guid = radresGuid("p25-identity-state");

        try(Connection connection = open(database);
            Statement statement = connection.createStatement())
        {
            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(1_000L, guid, 0x348, "identity", "00-0500", 855_000_000L));
            P25ActivityLogSchema.recordActivity(connection,
                activity(2_000L, guid, "APCO25", 100, 200, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true, 855_000_000L,
                    P25ActivityLogRecords.P25TargetIdentity.ORDINARY), false);
            P25ActivityLogSchema.recordActivity(connection,
                activity(3_000L, guid, "APCO25", 101, 201, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true, 855_000_000L,
                    P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 1_200)), false);
            assertEquals(P25ActivityLogRecords.P25IdentityState.ORDINARY.code(), scalarLong(connection, """
                SELECT p25_identity_state_code FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=100
                """));
            assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED.code(),
                scalarLong(connection, """
                    SELECT p25_identity_state_code FROM trunked_identity_summary
                    WHERE identity_kind_code=1 AND identity_id=101
                    """));
            assertEquals(0xABCDE, scalarLong(connection, """
                SELECT p25_home_wacn FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=101
                """));
            assertEquals(0x321, scalarLong(connection, """
                SELECT p25_home_system_id FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=101
                """));
            assertEquals(1_200, scalarLong(connection, """
                SELECT p25_home_talkgroup_id FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=101
                """));

            P25ActivityLogSchema.recordActivity(connection,
                activity(4_000L, guid, "APCO25", 101, 202, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true, 855_000_000L,
                    P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x322, 1_201)), false);

            assertEquals(P25ActivityLogRecords.P25IdentityState.AMBIGUOUS.code(), scalarLong(connection, """
                SELECT p25_identity_state_code FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=101
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=101
                  AND (p25_home_wacn IS NOT NULL OR p25_home_system_id IS NOT NULL
                       OR p25_home_talkgroup_id IS NOT NULL)
                """));

            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary(
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms
                ) VALUES((SELECT scope_id FROM trunked_identity_scope LIMIT 1), 1, 102, 1, 1)
                """);
            assertEquals(P25ActivityLogRecords.P25IdentityState.UNKNOWN.code(), scalarLong(connection, """
                SELECT p25_identity_state_code FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=102
                """));
        }
    }

    @Test
    void preservesZeroLocalFullyQualifiedTalkgroupsByHomeTupleWithoutCreatingLocalIdentityZero() throws Exception
    {
        assertEquals(P25ActivityLogRecords.P25IdentityState.UNKNOWN,
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 0).state());
        assertEquals(P25ActivityLogRecords.P25IdentityState.UNKNOWN,
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 0xFFFF).state());
        assertEquals(P25ActivityLogRecords.P25IdentityState.UNKNOWN,
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0x100000, 0x321, 1).state());
        assertEquals(P25ActivityLogRecords.P25IdentityState.UNKNOWN,
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x1000, 1).state());

        Path database = activityDatabase("p25-zero-local-fq.sqlite");
        String guid = radresGuid("p25-zero-local-fq");
        P25ActivityLogRecords.P25TargetIdentity first =
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 1_200);
        P25ActivityLogRecords.P25TargetIdentity second =
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x322, 1_200);
        P25ActivityLogRecords.P25TargetIdentity late =
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xBBCDE, 0x321, 1_201);
        P25ActivityLogRecords.P25TargetIdentity midCall =
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xCBCDE, 0x323, 1_202);

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(1_000L, guid, 0x348, "identity", "00-0500", 855_000_000L));
            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(2_000L, guid, P25ActivityLogRecords.Action.CALL, 0, 200,
                    Form.TALKGROUP.name(), List.of(), true, first, List.of()), false);
            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(3_000L, guid, P25ActivityLogRecords.Action.CALL, 0, 201,
                    Form.TALKGROUP.name(), List.of(), true, second, List.of()), false);
            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(4_000L, guid, P25ActivityLogRecords.Action.CALL, 1_200, 202,
                    Form.TALKGROUP.name(), List.of(), true,
                    P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of()), false);
            P25ActivityLogRecords.ResolvedLogicalCall firstCall = resolvedCall(101, 2_000L, guid,
                Protocol.APCO25.name(), P25ActivityLogRecords.IdentityDomain.STANDARD,
                0xBEE00, 0x348, 1, 0, Form.TALKGROUP.name(), List.of(), 200, false, first, List.of());
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, firstCall));
            assertTrue(P25ActivityLogSchema.applyLogicalCallOutput(connection,
                new P25ActivityLogRecords.LogicalCallOutput(firstCall,
                    P25ActivityLogRecords.CallOutput.RECORDED)));

            P25ActivityLogRecords.ActivityEvent unknownStart = activity(5_000L, guid, "APCO25", null, 203,
                null, P25ActivityLogRecords.IdentityDomain.STANDARD, true, 855_000_000L,
                P25ActivityLogRecords.P25TargetIdentity.UNKNOWN);
            P25ActivityLogRecords.ActivityEvent continuation = p25Activity(5_100L, guid,
                P25ActivityLogRecords.Action.CONTINUE, 0, 203, Form.TALKGROUP.name(), List.of(), false,
                late, List.of());
            P25ActivityLogSchema.recordActivity(connection, unknownStart, false);
            P25ActivityLogSchema.recordActivity(connection, continuation, false);
            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection,
                new P25ActivityLogRecords.TrunkedCallAttribution(5_000L, "GUID:" + guid, guid,
                    855_000_000L, 1, 0, Form.TALKGROUP.name(), List.of(), 203, null, null,
                    true, false, false, false, P25ActivityLogRecords.IdentityDomain.STANDARD, late)));
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection,
                resolvedCall(102, 5_000L, guid, Protocol.APCO25.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, 0xBEE00, 0x348, 1, 0,
                    Form.TALKGROUP.name(), List.of(), 203, false, late, List.of())));
            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(5_200L, guid, P25ActivityLogRecords.Action.CONTINUE, 0, 204,
                    Form.TALKGROUP.name(), List.of(), false, midCall, List.of()), false);

            assertEquals(4, scalarLong(connection,
                "SELECT COUNT(*) FROM p25_zero_local_fq_talkgroup_summary"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_identity_summary WHERE identity_id=0"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_radio_talkgroup_summary WHERE talkgroup_id=0"));
            assertEquals(1, scalarLong(connection, """
                SELECT logical_call_count FROM p25_zero_local_fq_talkgroup_summary
                WHERE home_wacn=0xABCDE AND home_system_id=0x321 AND home_talkgroup_id=1200
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT recorded_output_count FROM p25_zero_local_fq_talkgroup_summary
                WHERE home_wacn=0xABCDE AND home_system_id=0x321 AND home_talkgroup_id=1200
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT logical_call_count FROM p25_zero_local_fq_talkgroup_summary
                WHERE home_wacn=0xBBCDE AND home_system_id=0x321 AND home_talkgroup_id=1201
                """), "One resolved logical call must own the lifetime count");
            assertEquals(1, scalarLong(connection, """
                SELECT continue_count FROM p25_zero_local_fq_talkgroup_summary
                WHERE home_wacn=0xCBCDE AND home_system_id=0x323 AND home_talkgroup_id=1202
                """), "An untracked mid-call tuple must remain discoverable");
            assertEquals(0, scalarLong(connection, """
                SELECT logical_call_count FROM p25_zero_local_fq_talkgroup_summary
                WHERE home_wacn=0xCBCDE AND home_system_id=0x323 AND home_talkgroup_id=1202
                """), "A continuation must not invent a logical call count");
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=1200
                """), "The ordinary local talkgroup remains separately discoverable");
            assertEquals(0, scalarLong(connection, """
                SELECT logical_call_count FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=1200
                """), "Receiver observations do not invent logical calls");

            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(6_000L, guid, P25ActivityLogRecords.Action.CALL, 0, 204,
                    Form.TALKGROUP.name(), List.of(), true,
                    P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 0xFFFF),
                    List.of()), false);
            assertEquals(4, scalarLong(connection,
                "SELECT COUNT(*) FROM p25_zero_local_fq_talkgroup_summary"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate("""
                INSERT INTO p25_zero_local_fq_talkgroup_summary(
                    scope_id, home_wacn, home_system_id, home_talkgroup_id, first_seen_ms, last_seen_ms
                ) VALUES((SELECT scope_id FROM trunked_identity_scope LIMIT 1), 0xABCDE, 0x321, 65535, 1, 1)
                """));
        }
    }

    @Test
    void sameCallAttributionRefinesOrdinaryP25IdentityButIndependentEvidenceStillConflicts() throws Exception
    {
        Path database = activityDatabase("p25-same-call-refinement.sqlite");
        String guid = radresGuid("p25-same-call-refinement");
        P25ActivityLogRecords.P25TargetIdentity qualified =
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 1_200);

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(1_000L, guid, 0x348, "identity", "00-0500", 855_000_000L));
            P25ActivityLogRecords.ActivityEvent callStart = p25Activity(2_000L, guid,
                P25ActivityLogRecords.Action.CALL, 100, 200, Form.TALKGROUP.name(), List.of(), true,
                P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of());
            P25ActivityLogRecords.ActivityEvent continuation = p25Activity(2_100L, guid,
                P25ActivityLogRecords.Action.CONTINUE, 100, 200, Form.TALKGROUP.name(), List.of(), false,
                qualified, List.of());
            P25ActivityLogSchema.recordActivity(connection, callStart, false);
            P25ActivityLogSchema.recordActivity(connection, continuation, false);
            assertEquals(P25ActivityLogRecords.P25IdentityState.ORDINARY.code(), scalarLong(connection, """
                SELECT p25_identity_state_code FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=100
                """));

            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection,
                new P25ActivityLogRecords.TrunkedCallAttribution(2_000L, "GUID:" + guid, guid,
                    855_000_000L, 1, 100, Form.TALKGROUP.name(), List.of(), 200, null, null,
                    false, false, false, false, P25ActivityLogRecords.IdentityDomain.STANDARD, qualified)));
            assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED.code(),
                scalarLong(connection, """
                    SELECT p25_identity_state_code FROM trunked_identity_summary
                    WHERE identity_kind_code=1 AND identity_id=100
                    """));
            assertEquals(1_200, scalarLong(connection, """
                SELECT p25_home_talkgroup_id FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=100
                """));

            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(3_000L, guid, P25ActivityLogRecords.Action.CALL, 100, 201,
                    Form.TALKGROUP.name(), List.of(), true,
                    P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x322, 1_201), List.of()),
                false);
            assertEquals(P25ActivityLogRecords.P25IdentityState.AMBIGUOUS.code(), scalarLong(connection, """
                SELECT p25_identity_state_code FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=100
                """));
        }
    }

    @Test
    void resolvedLogicalCallRefinesOnlyAnOrdinaryIdentityFromTheSameCallStart() throws Exception
    {
        Path database = activityDatabase("p25-completed-refinement.sqlite");
        String guid = radresGuid("p25-completed-refinement");
        P25ActivityLogRecords.P25TargetIdentity qualified =
            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(0xABCDE, 0x321, 1_200);

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(1_000L, guid, 0x348, "identity", "00-0500", 855_000_000L));
            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(2_000L, guid, P25ActivityLogRecords.Action.CALL, 110, 210,
                    Form.TALKGROUP.name(), List.of(), true,
                    P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of()), false);
            P25ActivityLogRecords.ResolvedLogicalCall firstCall = p25ResolvedCall(201, 2_000L, guid,
                0x348, 110, 210, qualified);
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, firstCall));
            assertTrue(P25ActivityLogSchema.applyLogicalCallOutput(connection,
                new P25ActivityLogRecords.LogicalCallOutput(firstCall,
                    P25ActivityLogRecords.CallOutput.RECORDED)));
            assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED.code(),
                scalarLong(connection, """
                    SELECT p25_identity_state_code FROM trunked_identity_summary
                    WHERE identity_kind_code=1 AND identity_id=110
                    """));

            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(3_000L, guid, P25ActivityLogRecords.Action.CALL, 111, 211,
                    Form.TALKGROUP.name(), List.of(), true,
                    P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of()), false);
            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(4_000L, guid, P25ActivityLogRecords.Action.CALL, 111, 212,
                    Form.TALKGROUP.name(), List.of(), true,
                    P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of()), false);
            P25ActivityLogRecords.ResolvedLogicalCall secondCall = p25ResolvedCall(202, 4_000L, guid,
                0x348, 111, 212, qualified);
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, secondCall));
            assertTrue(P25ActivityLogSchema.applyLogicalCallOutput(connection,
                new P25ActivityLogRecords.LogicalCallOutput(secondCall,
                    P25ActivityLogRecords.CallOutput.RECORDED)));
            assertEquals(P25ActivityLogRecords.P25IdentityState.AMBIGUOUS.code(), scalarLong(connection, """
                SELECT p25_identity_state_code FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=111
                """));
        }
    }


    @Test
    void projectsQualifiedPatchMembersWithoutGuessingFlattenedMembers() throws Exception
    {
        Path database = activityDatabase("p25-patch-member-identities.sqlite");
        String guid = radresGuid("p25-patch-member-identities");

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(1_000L, guid, 0x348, "identity", "00-0500", 855_000_000L));
            P25ActivityLogSchema.recordActivity(connection,
                p25Activity(2_000L, guid, P25ActivityLogRecords.Action.CALL, 500, 200,
                    Form.PATCH_GROUP.name(), List.of(501, 502, 503), true,
                    P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of(
                        new P25ActivityLogRecords.P25PatchMemberIdentity(501,
                            P25ActivityLogRecords.P25TargetIdentity.ORDINARY),
                        new P25ActivityLogRecords.P25PatchMemberIdentity(502,
                            P25ActivityLogRecords.P25TargetIdentity.fullyQualified(
                                0xABCDE, 0x321, 1_202)))), false);

            assertEquals(P25ActivityLogRecords.P25IdentityState.ORDINARY.code(), scalarLong(connection, """
                SELECT p25_identity_state_code FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=501
                """));
            assertEquals(P25ActivityLogRecords.P25IdentityState.STABLE_FULLY_QUALIFIED.code(),
                scalarLong(connection, """
                    SELECT p25_identity_state_code FROM trunked_identity_summary
                    WHERE identity_kind_code=1 AND identity_id=502
                    """));
            assertEquals(1_202, scalarLong(connection, """
                SELECT p25_home_talkgroup_id FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=502
                """));
            assertEquals(P25ActivityLogRecords.P25IdentityState.UNKNOWN.code(), scalarLong(connection, """
                SELECT p25_identity_state_code FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=503
                """));
        }
    }

    @Test
    void rejectsScopeTablesThatHaveTheRightColumnsButMissingIdentityConstraints() throws Exception
    {
        Path database = database("scope-constraints.sqlite");

        try(Connection connection = open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE trunked_identity_scope");
            statement.executeUpdate("""
                CREATE TABLE trunked_identity_scope (
                    scope_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scope_token TEXT NOT NULL,
                    protocol_code INTEGER NOT NULL,
                    scope_kind_code INTEGER NOT NULL,
                    identity_domain_code INTEGER NOT NULL DEFAULT 0,
                    p25_system_key INTEGER REFERENCES p25_system(system_key) ON DELETE CASCADE,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL
                )
                """);

            assertThrows(SQLException.class, () -> P25ActivityLogSchema.validate(connection));
        }
    }

    @Test
    void lateDmrIdentityAndLogicalOutputsUseTheSameProjection() throws Exception
    {
        Path database = database("late-dmr.sqlite");
        String guid = "dmr-late";
        String contextKey = "GUID:" + guid;
        int talkgroup = 300_956;
        int radio = 15_000_000;

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, guid, "DMR", null, null, null,
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true), false);
            assertEquals(0, identityCount(connection, "dmr:guid:" + guid));

            P25ActivityLogRecords.TrunkedCallAttribution attribution =
                new P25ActivityLogRecords.TrunkedCallAttribution(1_000L, contextKey, guid,
                    451_000_000L, 1, talkgroup, Form.TALKGROUP.name(), List.of(), radio,
                    true, true, false, false, P25ActivityLogRecords.IdentityDomain.STANDARD);
            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, attribution));

            P25ActivityLogRecords.ResolvedLogicalCall call = contextResolvedCall(301, 1_000L, guid,
                Protocol.DMR.name(), P25ActivityLogRecords.IdentityDomain.STANDARD, talkgroup, radio);
            assertTrue(P25ActivityLogSchema.recordResolvedLogicalCall(connection, call));
            assertTrue(P25ActivityLogSchema.applyLogicalCallOutput(connection,
                new P25ActivityLogRecords.LogicalCallOutput(call,
                    P25ActivityLogRecords.CallOutput.RECORDED)));
            assertTrue(P25ActivityLogSchema.applyLogicalCallOutput(connection,
                new P25ActivityLogRecords.LogicalCallOutput(call,
                    P25ActivityLogRecords.CallOutput.STREAMED)));

            assertEquals("dmr:guid:" + guid, scalarString(connection,
                "SELECT scope_token FROM trunked_identity_scope"));
            assertIdentity(connection, TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP, talkgroup,
                1, 0, 1, 1, 1, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, radio);
            assertIdentity(connection, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, radio,
                1, 1, 0, 1, 1, TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP, talkgroup);
            assertEquals(1, scalarLong(connection, """
                SELECT logical_call_count FROM trunked_radio_talkgroup_summary
                WHERE radio_id=15000000 AND talkgroup_id=300956
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT recorded_output_count FROM trunked_radio_talkgroup_summary
                WHERE radio_id=15000000 AND talkgroup_id=300956
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT streamed_output_count FROM trunked_radio_talkgroup_summary
                WHERE radio_id=15000000 AND talkgroup_id=300956
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT logical_call_count FROM trunked_logical_call_identity_bucket
                WHERE identity_role_code=1 AND identity_kind_code=1 AND identity_id=300956
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_logical_call_identity_bucket
                WHERE identity_role_code=1 AND identity_kind_code=0
                """));
        }
    }

    @Test
    void appliesProtocolSpecialAddressRulesWithoutRejectingRealHighDmrIds() throws Exception
    {
        Path database = database("protocol-policy.sqlite");

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, "dmr-real", "DMR", 300_956, 15_000_000, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true), false);
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE scope_id=(SELECT scope_id FROM trunked_identity_scope WHERE scope_token='dmr:guid:dmr-real')
                """));

            P25ActivityLogSchema.recordActivity(connection,
                activity(2_000L, "dmr-special", "DMR", 0xFFFFFF, 0xFFFEC0, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true), false);
            assertEquals(0, identityCount(connection, "dmr:guid:dmr-special"));

            P25ActivityLogSchema.recordActivity(connection,
                activity(3_000L, "nxdn-c", "NXDN", 0xFFFF, 0xFFFF, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C, true), false);
            assertEquals(0, identityCount(connection, "nxdn:guid:nxdn-c"));

            P25ActivityLogSchema.recordActivity(connection,
                activity(4_000L, "nxdn-d", "NXDN", 0xFFFF, 0xFFFF, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, true), false);
            assertEquals(2, identityCount(connection, "nxdn:guid:nxdn-d"));

            assertFalse(TrunkedIdentityPolicy.isDirectoryTalkgroup(TrunkedIdentityPolicy.PROTOCOL_P25,
                P25ActivityLogRecords.IdentityDomain.STANDARD, 0xFFFF));
            assertFalse(TrunkedIdentityPolicy.isDirectoryRadio(TrunkedIdentityPolicy.PROTOCOL_P25,
                P25ActivityLogRecords.IdentityDomain.STANDARD, 0xFFFFFC));
        }
    }

    @Test
    void explicitDmrAndTypeDTalkerAliasesPersistWithoutReclassifyingTheScope() throws Exception
    {
        Path database = database("protocol-talker-alias.sqlite");

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, "dmr-alias", "DMR", 91, 101, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true), false);
            P25ActivityLogSchema.updateTalkerAlias(connection, new P25ActivityLogRecords.TalkerAliasUpdate(
                2_000L, "GUID:dmr-alias", "dmr-alias", null, null, 101, "ENGINE 4",
                P25ActivityLogRecords.IdentityDomain.STANDARD));

            P25ActivityLogSchema.recordActivity(connection,
                activity(3_000L, "nxdn-d-alias", "NXDN", 0x2345, 0x1234, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, true), false);
            P25ActivityLogSchema.updateTalkerAlias(connection, new P25ActivityLogRecords.TalkerAliasUpdate(
                4_000L, "GUID:nxdn-d-alias", "nxdn-d-alias", null, null, 0x1234, "UNIT 12",
                P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D));

            assertEquals("ENGINE 4", scalarString(connection, """
                SELECT summary.last_talker_alias
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id=summary.scope_id
                WHERE scope.scope_token='dmr:guid:dmr-alias'
                  AND summary.identity_kind_code=2 AND summary.identity_id=101
                """));
            assertEquals("UNIT 12", scalarString(connection, """
                SELECT summary.last_talker_alias
                FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id=summary.scope_id
                WHERE scope.scope_token='nxdn:guid:nxdn-d-alias'
                  AND summary.identity_kind_code=2 AND summary.identity_id=4660
                """));
            assertEquals(2, scalarLong(connection, """
                SELECT identity_domain_code FROM trunked_identity_scope
                WHERE scope_token='nxdn:guid:nxdn-d-alias'
                """));
            assertEquals(2, identityCount(connection, "nxdn:guid:nxdn-d-alias"));
        }
    }

    @Test
    void sharesP25ScopeButKeepsDmrAndNxdnContextsIndependent() throws Exception
    {
        Path database = database("scope.sqlite");

        try(Connection connection = open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key,wacn,system_id,first_seen_ms,last_seen_ms)
                VALUES(50, 781824, 840, 1, 1)
                """);
            insertContext(connection, 10, "p25-a", 1, 1, 50);
            insertContext(connection, 11, "p25-b", 1, 2, 50);
            insertContext(connection, 12, "dmr-a", 1, 3, null);
            insertContext(connection, 13, "dmr-b", 1, 3, null);
            insertContext(connection, 14, "nxdn-a", 1, 4, null);

            TrunkedIdentitySchema.ensureScope(connection, 10, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.ensureScope(connection, 11, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.ensureScope(connection, 12, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.ensureScope(connection, 13, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.ensureScope(connection, 14, 1,
                P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C);

            assertEquals(4, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope_context mapping
                JOIN trunked_identity_scope scope ON scope.scope_id=mapping.scope_id
                WHERE scope.scope_token='p25:BEE00:348:alias-list:1'
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(DISTINCT scope_id) FROM trunked_identity_scope_context
                WHERE context_id IN (10,11)
                """));
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(DISTINCT scope_id) FROM trunked_identity_scope_context
                WHERE context_id IN (12,13)
                """));
        }
    }

    @Test
    void retainsHistoricalLinkedP25ScopeWhenItsContextIsRekeyed() throws Exception
    {
        Path database = database("p25-rekey.sqlite");

        try(Connection connection = open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key,wacn,system_id,first_seen_ms,last_seen_ms)
                VALUES(50, 781824, 840, 1, 1),
                      (51, 781824, 841, 1, 1)
                """);
            insertContext(connection, 10, "p25-rekey", 1, 1, 50);
            TrunkedIdentitySchema.Scope original = TrunkedIdentitySchema.ensureScope(connection, 10, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary(
                    scope_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms
                ) VALUES(%d,1,100,1,1)
                """.formatted(original.scopeId()));

            statement.executeUpdate("UPDATE receiver_context SET system_key=51 WHERE id=10");
            TrunkedIdentitySchema.ensureScope(connection, 10, 2,
                P25ActivityLogRecords.IdentityDomain.STANDARD);

            assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope WHERE scope_token='p25:BEE00:348:alias-list:1'
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope WHERE scope_token='p25:BEE00:349:alias-list:1'
                """));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_summary"));
            assertEquals("p25:BEE00:349:alias-list:1", scalarString(connection, """
                SELECT scope.scope_token
                FROM trunked_identity_scope_context mapping
                JOIN trunked_identity_scope scope ON scope.scope_id=mapping.scope_id
                WHERE mapping.context_id=10
                """));
        }
    }

    @Test
    void remapClearsMovedContextProjectionsButKeepsSharedSystemEvidence() throws Exception
    {
        Path database = database("p25-shared-scope-remap.sqlite");

        try(Connection connection = open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key,wacn,system_id,first_seen_ms,last_seen_ms)
                VALUES(50, 781824, 840, 1, 1),
                      (51, 781824, 841, 1, 1)
                """);
            insertContext(connection, 10, "p25-moved", 1, 1, 50);
            insertContext(connection, 11, "p25-retained", 1, 1, 50);
            TrunkedIdentitySchema.Scope originalScope = TrunkedIdentitySchema.ensureScope(connection, 10, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.ensureScope(connection, 11, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary(
                    scope_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms
                ) VALUES(%d,1,100,1,1),(%d,1,101,1,1),(%d,2,200,1,1)
                """.formatted(originalScope.scopeId(), originalScope.scopeId(), originalScope.scopeId()));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(scope_id,radio_id,talkgroup_id,confirmed_at_ms)
                VALUES(%d,200,100,1)
                """.formatted(originalScope.scopeId()));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_site_presence(
                    scope_id,radio_id,context_id,evidence_code,confirmed_at_ms
                ) VALUES(%d,200,10,2,1)
                """.formatted(originalScope.scopeId()));

            statement.executeUpdate("""
                INSERT INTO trunked_signaling_activity_bucket(context_id,bucket_start_ms,grant_count)
                VALUES(10,0,4),(11,0,5)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event(
                    context_id,observed_at_ms,action_code,source_radio_id,target_id,target_kind_code
                ) VALUES(10,1,1,200,100,1),(11,1,1,201,101,1)
                """);

            TrunkedIdentitySchema.ensureScope(connection, 10, 2,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_site_presence WHERE context_id=10
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_signaling_activity_bucket
                WHERE context_id=10 AND grant_count=4
                """));

            statement.executeUpdate("UPDATE receiver_context SET system_key=51 WHERE id=10");
            TrunkedIdentitySchema.ensureScope(connection, 10, 3,
                P25ActivityLogRecords.IdentityDomain.STANDARD);

            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope_context mapping
                JOIN trunked_identity_scope scope ON scope.scope_id=mapping.scope_id
                WHERE mapping.context_id=10 AND scope.scope_token='p25:BEE00:349:alias-list:1'
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope_context mapping
                JOIN trunked_identity_scope scope ON scope.scope_id=mapping.scope_id
                WHERE mapping.context_id=11 AND scope.scope_token='p25:BEE00:348:alias-list:1'
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_site_presence WHERE context_id=10
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_affiliation
                WHERE scope_id=%d AND radio_id=200 AND talkgroup_id=100
                """.formatted(originalScope.scopeId())));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_signaling_activity_bucket
                WHERE context_id=10
                """));
            assertEquals(5, scalarLong(connection, """
                SELECT grant_count FROM trunked_signaling_activity_bucket WHERE context_id=11
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_activity_event WHERE context_id=10
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_activity_event
                WHERE context_id=11 AND source_radio_id=201 AND target_id=101 AND target_kind_code=1
                """));
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary summary
                JOIN trunked_identity_scope scope ON scope.scope_id=summary.scope_id
                WHERE scope.scope_token='p25:BEE00:348:alias-list:1' AND summary.identity_id IN (100,101)
                """));
        }
    }


    @Test
    void clearsAmbiguousNxdnIdentitiesWhenTheConfiguredDomainChanges() throws Exception
    {
        Path database = database("nxdn-domain-change.sqlite");

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, "nxdn-domain", "NXDN", 100, 200, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C, true), true);
            int contextId = (int)scalarLong(connection, """
                SELECT id FROM receiver_context WHERE guid='nxdn-domain'
                """);

            assertEquals(2, identityCount(connection, "nxdn:guid:nxdn-domain"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_radio_talkgroup_summary"));

            P25ActivityLogSchema.recordActivity(connection,
                activity(2_000L, "nxdn-domain", "NXDN", 0xFFF0, 0xFFF1, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, true), true);

            assertEquals(2, scalarLong(connection, """
                SELECT identity_domain_code FROM trunked_identity_scope
                WHERE scope_token='nxdn:guid:nxdn-domain'
                """));
            assertEquals(2_000L, scalarLong(connection, """
                SELECT first_seen_ms FROM trunked_identity_scope
                WHERE scope_token='nxdn:guid:nxdn-domain'
                """));
            assertEquals(2, identityCount(connection, "nxdn:guid:nxdn-domain"));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (100,200)
                """));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_radio_talkgroup_summary"));
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (65520,65521)
                """));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM p25_activity_event"));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_activity_event
                WHERE source_radio_id IS NOT NULL OR target_id IS NOT NULL OR target_kind_code IS NOT NULL
                """));
            assertEquals(0xFFF1, scalarLong(connection, """
                SELECT source_radio_id FROM p25_activity_event WHERE observed_at_ms=2000
                """));
            assertEquals(0xFFF0, scalarLong(connection, """
                SELECT target_id FROM p25_activity_event WHERE observed_at_ms=2000
                """));
        }
    }

    @Test
    void olderNxdnDomainEvidenceCannotReplaceAnAuthoritativeSiteDomain() throws Exception
    {
        Path database = database("nxdn-stale-domain.sqlite");
        String guid = radresGuid("nxdn-stale-domain");

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.ensureTrunkedSiteIdentityScope(connection,
                siteSnapshot(2_000L, guid, TrunkedSiteSchema.PROTOCOL_NXDN, 2, 4));
            P25ActivityLogSchema.recordActivity(connection,
                activity(2_000L, guid, "NXDN", 0xFFF0, 0xFFF1, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, true), true);

            assertNull(P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, guid, "NXDN", 100, 200, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C, true), true));

            assertEquals(2, scalarLong(connection, """
                SELECT identity_domain_code FROM trunked_identity_scope
                WHERE scope_token='nxdn:guid:%s'
                """.formatted(guid)));
            assertEquals(2_000L, scalarLong(connection, """
                SELECT last_seen_ms FROM trunked_identity_scope
                WHERE scope_token='nxdn:guid:%s'
                """.formatted(guid)));
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (65520,65521)
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (100,200)
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT kind_code FROM receiver_context WHERE guid='%s'
                """.formatted(guid)));
            assertEquals(4, scalarLong(connection, """
                SELECT protocol_code FROM receiver_context WHERE guid='%s'
                """.formatted(guid)));
            assertEquals(2_000L, scalarLong(connection, """
                SELECT last_seen_ms FROM receiver_context WHERE guid='%s'
                """.formatted(guid)));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM p25_activity_event"));
        }
    }

    @Test
    void delayedOldProtocolRecordsCannotWriteAfterReceiverReclassification() throws Exception
    {
        Path database = database("stale-protocol-generation.sqlite");
        String guid = "dmr-reclassified-as-nxdn";
        String contextKey = "GUID:" + guid;

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, guid, "DMR", 91, 101, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true), true);
            P25ActivityLogSchema.recordActivity(connection,
                activity(5_000L, guid, "NXDN", 0x2223, 0x1134, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, true), true);

            assertNull(P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, guid, "DMR", 92, 102, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true), true));
            P25ActivityLogRecords.ResolvedLogicalCall staleDmr = contextResolvedCall(401, 1_000L, guid,
                Protocol.DMR.name(), P25ActivityLogRecords.IdentityDomain.STANDARD, 92, 102);
            assertFalse(P25ActivityLogSchema.applyLogicalCallOutput(connection,
                new P25ActivityLogRecords.LogicalCallOutput(staleDmr,
                    P25ActivityLogRecords.CallOutput.RECORDED)));
            assertFalse(P25ActivityLogSchema.applyTrunkedCallAttribution(connection,
                new P25ActivityLogRecords.TrunkedCallAttribution(1_000L, contextKey, guid,
                    451_000_000L, 1, 92, Form.TALKGROUP.name(), List.of(), 102,
                    true, true, true, false, P25ActivityLogRecords.IdentityDomain.STANDARD)));
            P25ActivityLogSchema.updateTalkerAlias(connection,
                new P25ActivityLogRecords.TalkerAliasUpdate(1_000L, contextKey, guid,
                    null, null, 102, "STALE UNIT", P25ActivityLogRecords.IdentityDomain.STANDARD));

            assertEquals(4, scalarLong(connection, """
                SELECT protocol_code FROM receiver_context WHERE guid='dmr-reclassified-as-nxdn'
                """));
            assertEquals(5_000L, scalarLong(connection, """
                SELECT first_seen_ms FROM receiver_context WHERE guid='dmr-reclassified-as-nxdn'
                """));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals("nxdn:guid:" + guid, scalarString(connection,
                "SELECT scope_token FROM trunked_identity_scope"));
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (4404,8739)
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (91,92,101,102) OR last_talker_alias='STALE UNIT'
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_talkgroup_summary
                WHERE radio_id=4404 AND talkgroup_id=8739
                """));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_activity_event
                WHERE observed_at_ms=5000 AND source_radio_id=4404 AND target_id=8739
                """));
        }
    }

    @Test
    void p25RekeyClearsGuidSiteFactsAndRejectsOldGenerationReceiverFacts() throws Exception
    {
        Path database = database("p25-generation-rekey.sqlite");
        String guid = radresGuid("p25-generation-rekey");
        String targetGuid = radresGuid("target-site");

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(1_000L, targetGuid, 0x349, "target", "00-0700", 857_000_000L));
            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(1_000L, guid, 0x348, "old", "00-0500", 855_000_000L));
            P25ActivityLogSchema.upsertGrantedChannelSummary(connection,
                new P25ActivityLogRecords.ChannelFact(1_500L, guid, "00-0509", 855_100_000L,
                    ChannelTag.VOICE, false, 1));

            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(5_000L, guid, 0x349, "new", "00-0600", 856_000_000L));

            P25ActivityLogSchema.upsertGrantedChannelSummary(connection,
                new P25ActivityLogRecords.ChannelFact(1_500L, guid, "00-0509", 855_100_000L,
                    ChannelTag.VOICE, false, 1));
            assertEquals(0x349, scalarLong(connection, """
                SELECT system.system_id
                FROM p25_site_snapshot site
                JOIN p25_system system ON system.system_key=site.system_key
                WHERE site.guid='%s'
                """.formatted(guid)));
            assertEquals(5_000L, scalarLong(connection, """
                SELECT first_seen_ms FROM receiver_context WHERE guid='%s'
                """.formatted(guid)));
            assertEquals(5_000L, scalarLong(connection, """
                SELECT first_seen_ms FROM p25_site_snapshot WHERE guid='%s'
                """.formatted(guid)));
            assertEquals(1, scalarLong(connection, """
                SELECT observation_count FROM p25_site_snapshot WHERE guid='%s'
                """.formatted(guid)));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_site_channel_summary
                WHERE guid='%s' AND downlink_hz=856000000
                """.formatted(guid)));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_site_channel_summary
                WHERE guid='%s' AND channel_key IN ('00-0500','00-0509')
                """.formatted(guid)));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_logical_call_bucket
                """));
        }
    }

    @Test
    void protocolSpecificSiteProjectionFollowsTheCurrentReceiverContext() throws Exception
    {
        Path database = database("site-protocol-routing.sqlite");
        String guid = radresGuid("site-protocol-routing");

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(1_000L, guid, 0x348, "p25-first", "00-0500", 855_000_000L));
            TrunkedSiteSchema.Snapshot dmr =
                siteSnapshot(2_000L, guid, TrunkedSiteSchema.PROTOCOL_DMR, 1, 0);
            assertTrue(TrunkedSiteSchema.upsert(connection, dmr));
            P25ActivityLogSchema.ensureTrunkedSiteIdentityScope(connection, dmr);

            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM p25_site_snapshot WHERE guid='" + guid + "'"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE guid='" + guid + "'"));
            assertEquals(3, scalarLong(connection,
                "SELECT protocol_code FROM receiver_context WHERE guid='" + guid + "'"));

            P25ActivityLogSchema.insertSite(connection,
                p25SiteSnapshot(3_000L, guid, 0x348, "p25-again", "00-0600", 856_000_000L));

            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM p25_site_snapshot WHERE guid='" + guid + "'"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE guid='" + guid + "'"));
            assertEquals(1, scalarLong(connection,
                "SELECT protocol_code FROM receiver_context WHERE guid='" + guid + "'"));
        }
    }

    @Test
    void keepsTrunkedGuidAndConventionalConfigurationOwnershipSeparate() throws Exception
    {
        Path database = database("authoritative-conventional-transition.sqlite");
        String guid = radresGuid("dmr-to-conventional");
        String configurationId = configurationId("dmr-to-conventional");
        String configurationContextKey = "CONFIGURATION:" + configurationId;
        TrunkedSiteSchema.Snapshot site = new TrunkedSiteSchema.Snapshot(
            5_000L, guid, "trunked", TrunkedSiteSchema.PROTOCOL_DMR, 1, 2,
            "Metro DMR", "Downtown", "Aliases", "DMR Tier 3", 10, 20, 30, null, 2,
            null, null, null, 1, 1, 42, 0, null, 451_000_000L, 451_000_000L,
            List.of(new TrunkedSiteSchema.Channel(42, null, 1, 451_000_000L, 456_000_000L,
                TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL)),
            List.of(new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 31, 43, 452_000_000L, 1)));

        try(Connection connection = open(database))
        {
            assertTrue(TrunkedSiteSchema.upsert(connection, site));
            P25ActivityLogSchema.ensureTrunkedSiteIdentityScope(connection, site);
            P25ActivityLogSchema.recordActivity(connection,
                activity(5_000L, guid, "DMR", 91, 101, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true), true);

            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                dmrConventionalCall(1_000L, 2_000L, configurationId, 92, 102));

            assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM receiver_context"));
            assertEquals(1, scalarLong(connection,
                "SELECT kind_code FROM receiver_context WHERE context_key='GUID:" + guid + "'"));
            assertEquals(3, scalarLong(connection,
                "SELECT kind_code FROM receiver_context WHERE context_key='" + configurationContextKey + "'"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_identity_scope_context"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE guid='" + guid + "'"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_channel_summary WHERE guid='" + guid + "'"));
            assertEquals(2, identityCount(connection, "dmr:guid:" + guid));

            P25ActivityLogSchema.recordDmrConventionalCall(connection,
                dmrConventionalCall(6_000L, 7_000L, configurationId, 93, 103));

            assertEquals(1, scalarLong(connection,
                "SELECT kind_code FROM receiver_context WHERE context_key='GUID:" + guid + "'"));
            assertEquals(3, scalarLong(connection,
                "SELECT kind_code FROM receiver_context WHERE context_key='" + configurationContextKey + "'"));
            assertEquals(7_000L, scalarLong(connection,
                "SELECT last_seen_ms FROM receiver_context WHERE context_key='" + configurationContextKey + "'"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_identity_scope_context"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals(2, identityCount(connection, "dmr:guid:" + guid));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE guid='" + guid + "'"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_channel_summary WHERE guid='" + guid + "'"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM p25_activity_event"));
            assertEquals(4, scalarLong(connection,
                "SELECT COUNT(*) FROM conventional_call_identity_bucket"));
            assertTrue(P25ActivityLogSchema.isAuthoritativeTrunkedSiteSnapshot(connection, site));
        }
    }


    @Test
    void logicalOutputCannotReclassifyAnEstablishedNxdnDomain() throws Exception
    {
        Path database = database("nxdn-output-domain-change.sqlite");
        String guid = "nxdn-output-domain";

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, guid, "NXDN", 100, 200, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C, true), true);
            int contextId = (int)scalarLong(connection, """
                SELECT id FROM receiver_context WHERE guid='nxdn-output-domain'
                """);
            P25ActivityLogRecords.ResolvedLogicalCall call = contextResolvedCall(403, 1_000L, guid,
                Protocol.NXDN.name(), P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D, 0xFFF0, 0xFFF1);
            P25ActivityLogRecords.LogicalCallOutput output = new P25ActivityLogRecords.LogicalCallOutput(call,
                P25ActivityLogRecords.CallOutput.RECORDED);

            assertFalse(P25ActivityLogSchema.applyLogicalCallOutput(connection, output));

            assertEquals(1, scalarLong(connection, """
                SELECT identity_domain_code FROM trunked_identity_scope
                WHERE scope_token='nxdn:guid:nxdn-output-domain'
                """));
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (100,200)
                  AND logical_call_count=0 AND recorded_output_count=0
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (65520,65521)
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_logical_call_bucket
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_activity_event
                WHERE context_id=%d AND source_radio_id=200 AND target_id=100
                """.formatted(contextId)));
        }
    }

    @Test
    void lateAttributionCannotReclassifyAnEstablishedNxdnDomain()
        throws Exception
    {
        Path database = database("nxdn-attribution-domain-change.sqlite");
        String guid = "nxdn-attribution-domain";

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, guid, "NXDN", null, null, null,
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C, true, null), true);
            int contextId = (int)scalarLong(connection, """
                SELECT id FROM receiver_context WHERE guid='nxdn-attribution-domain'
                """);
            P25ActivityLogRecords.TrunkedCallAttribution attribution =
                new P25ActivityLogRecords.TrunkedCallAttribution(1_000L, "GUID:" + guid, guid,
                    451_000_000L, 1, 0xFFF0, Form.TALKGROUP.name(), List.of(), 0xFFF1,
                    true, true, true, false, P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D);

            assertFalse(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, attribution));

            assertEquals(1, scalarLong(connection, """
                SELECT identity_domain_code FROM trunked_identity_scope
                WHERE scope_token='nxdn:guid:nxdn-attribution-domain'
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (65520,65521)
                """));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals(0, scalarLong(connection, """
                SELECT coalesce(source_radio_id,0) FROM p25_activity_event WHERE context_id=%d
                """.formatted(contextId)));
            assertEquals(0, scalarLong(connection, """
                SELECT coalesce(target_id,0) FROM p25_activity_event WHERE context_id=%d
                """.formatted(contextId)));
            assertEquals(0, scalarLong(connection, """
                SELECT coalesce(target_kind_code,0) FROM p25_activity_event WHERE context_id=%d
                """.formatted(contextId)));
            assertEquals(0, scalarLong(connection, """
                SELECT encrypted FROM p25_activity_event WHERE context_id=%d
                """.formatted(contextId)));
        }
    }

    @Test
    void unknownFrequencyDetailFallbackRejectsAmbiguousRows() throws Exception
    {
        Path database = database("ambiguous-unknown-frequency-detail.sqlite");
        String guid = "nxdn-ambiguous-detail";

        try(Connection connection = open(database);
            Statement statement = connection.createStatement())
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, guid, "NXDN", null, null, null,
                    P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C, true, null), true);
            statement.executeUpdate("""
                INSERT INTO p25_activity_event(
                    context_id,observed_at_ms,action_code,event_type_code,timeslot,encrypted
                )
                SELECT context_id,observed_at_ms,action_code,event_type_code,timeslot,encrypted
                FROM p25_activity_event
                """);
            P25ActivityLogRecords.TrunkedCallAttribution attribution =
                new P25ActivityLogRecords.TrunkedCallAttribution(1_000L, "GUID:" + guid, guid,
                    451_000_000L, 1, 0xFFF0, Form.TALKGROUP.name(), List.of(), 0xFFF1,
                    true, true, false, false, P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C);

            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, attribution));
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_activity_event
                WHERE source_radio_id IS NULL AND target_id IS NULL AND target_kind_code IS NULL
                """));
        }
    }

    @Test
    void siteMetadataCreatesZeroCallDmrAndNxdnScopes() throws Exception
    {
        Path database = database("zero-call-sites.sqlite");

        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.ensureTrunkedSiteIdentityScope(connection,
                siteSnapshot(radresGuid("dmr-zero"), TrunkedSiteSchema.PROTOCOL_DMR, 1, 0));
            P25ActivityLogSchema.ensureTrunkedSiteIdentityScope(connection,
                siteSnapshot(radresGuid("nxdn-zero"), TrunkedSiteSchema.PROTOCOL_NXDN, 2, 4));

            assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_scope_context"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_summary"));
            assertEquals(2, scalarLong(connection, """
                SELECT SUM(identity_domain_code) FROM trunked_identity_scope
                """));
        }
    }

    @Test
    void unknownSignalingCannotCreateDirectoryRows() throws Exception
    {
        Path database = database("unknown.sqlite");

        try(Connection connection = open(database))
        {
            P25ActivityLogRecords.ActivityEvent unknown = new P25ActivityLogRecords.ActivityEvent(
                1_000L, "GUID:unknown-dmr", "unknown-dmr",
                P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "DMR",
                P25ActivityLogRecords.Action.UNKNOWN, "COMMAND", "123456", "300956",
                Form.TALKGROUP.name(), List.of(), 451_000_000L, null, 1, false,
                null, null, null, null, null, null, null, "Unknown DMR", "DMR", null,
                false, null, null, P25ActivityLogRecords.IdentityDomain.STANDARD);
            P25ActivityLogSchema.recordActivity(connection, unknown, true);
            assertEquals(0, identityCount(connection, "dmr:guid:unknown-dmr"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM p25_activity_event"));
        }
    }

    @Test
    void retentionAndAdmissionBoundsUseIndexedAccessPaths() throws Exception
    {
        Path database = database("retention.sqlite");

        try(Connection connection = open(database);
            Statement statement = connection.createStatement())
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, "retention", "DMR", 100, 200, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true), false);
            P25ActivityLogSchema.recordActivity(connection,
                activity(10_000L, "retention", "DMR", 101, 201, Form.TALKGROUP.name(),
                    P25ActivityLogRecords.IdentityDomain.STANDARD, true), false);
            int scopeId = (int)scalarLong(connection,
                "SELECT scope_id FROM trunked_identity_scope WHERE scope_token='dmr:guid:retention'");
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key,wacn,system_id,first_seen_ms,last_seen_ms)
                VALUES(50, 781824, 840, 1, 1)
                """);
            insertContext(connection, 50, "retention-p25", 1, 1, 50);
            int p25ScopeId = TrunkedIdentitySchema.ensureScope(connection, 50, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD).scopeId();
            statement.executeUpdate("""
                INSERT INTO p25_zero_local_fq_talkgroup_summary(
                    scope_id,home_wacn,home_system_id,home_talkgroup_id,first_seen_ms,last_seen_ms
                ) VALUES
                    (%d, 0xABCDE, 0x321, 1200, 1000, 1000),
                    (%d, 0xABCDE, 0x322, 1201, 10000, 10000)
                """.formatted(p25ScopeId, p25ScopeId));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(scope_id,radio_id,talkgroup_id,confirmed_at_ms)
                VALUES
                    (%d, 300, 1200, 1000),
                    (%d, 301, 1201, 10000)
                """.formatted(p25ScopeId, p25ScopeId));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_site_presence(
                    scope_id,radio_id,context_id,evidence_code,confirmed_at_ms
                ) VALUES
                    (%d, 300, 50, 1, 1000),
                    (%d, 301, 50, 2, 10000)
                """.formatted(p25ScopeId, p25ScopeId));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_presence_lifecycle(scope_id,radio_id,cleared_at_ms)
                VALUES
                    (%d, 302, 1000),
                    (%d, 303, 10000)
                """.formatted(p25ScopeId, p25ScopeId));

            assertTrue(TrunkedIdentitySchema.hasScopeCapacity(connection, "trunked_identity_summary",
                scopeId, 5));
            assertFalse(TrunkedIdentitySchema.hasScopeCapacity(connection, "trunked_identity_summary",
                scopeId, 4));
            assertTrue(TrunkedIdentitySchema.hasScopeCapacity(connection,
                "p25_zero_local_fq_talkgroup_summary", p25ScopeId, 3));
            assertFalse(TrunkedIdentitySchema.hasScopeCapacity(connection,
                "p25_zero_local_fq_talkgroup_summary", p25ScopeId, 2));
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT scope_id, identity_kind_code, identity_id
                FROM trunked_identity_summary INDEXED BY idx_trunked_identity_retention
                WHERE last_seen_ms < 5000
                ORDER BY last_seen_ms, scope_id, identity_kind_code, identity_id
                LIMIT 1000
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT scope_id, radio_id, talkgroup_id, target_kind_code
                FROM trunked_radio_talkgroup_summary INDEXED BY idx_trunked_radio_talkgroup_retention
                WHERE last_seen_ms < 5000
                ORDER BY last_seen_ms, scope_id, radio_id, talkgroup_id, target_kind_code
                LIMIT 1000
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT scope_id, home_wacn, home_system_id, home_talkgroup_id
                FROM p25_zero_local_fq_talkgroup_summary INDEXED BY idx_p25_zero_local_fq_retention
                WHERE last_seen_ms < 5000
                ORDER BY last_seen_ms, scope_id, home_wacn, home_system_id, home_talkgroup_id
                LIMIT 1000
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT scope_id, radio_id
                FROM trunked_radio_affiliation INDEXED BY idx_trunked_radio_affiliation_retention
                WHERE confirmed_at_ms < 5000
                ORDER BY confirmed_at_ms, scope_id, radio_id
                LIMIT 1000
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT scope_id, radio_id
                FROM trunked_radio_presence_lifecycle
                    INDEXED BY idx_trunked_radio_presence_lifecycle_retention
                WHERE cleared_at_ms < 5000
                ORDER BY cleared_at_ms, scope_id, radio_id
                LIMIT 1000
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT scope_id, radio_id
                FROM trunked_radio_site_presence INDEXED BY idx_trunked_radio_site_presence_retention
                WHERE confirmed_at_ms < 5000
                ORDER BY confirmed_at_ms, scope_id, radio_id
                LIMIT 1000
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT radio_id FROM trunked_radio_affiliation
                WHERE scope_id=%d AND talkgroup_id=1201
                ORDER BY confirmed_at_ms DESC, radio_id
                LIMIT 500
                """.formatted(p25ScopeId));
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT radio_id FROM trunked_radio_site_presence
                WHERE context_id=50
                ORDER BY confirmed_at_ms DESC, scope_id, radio_id
                LIMIT 500
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN SELECT 1 FROM trunked_identity_summary
                WHERE scope_id=1 LIMIT 1 OFFSET 99999
                """);

            assertEquals(7, TrunkedIdentitySchema.deleteOlderThan(connection, 5_000L));
            assertEquals(2, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary WHERE last_seen_ms=10000
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_talkgroup_summary WHERE last_seen_ms=10000
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_zero_local_fq_talkgroup_summary WHERE last_seen_ms=10000
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_affiliation WHERE confirmed_at_ms=10000
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_site_presence WHERE confirmed_at_ms=10000
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_presence_lifecycle WHERE cleared_at_ms=10000
                """));

            TrunkedIdentitySchema.reset(connection);
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM p25_zero_local_fq_talkgroup_summary"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_radio_affiliation"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_radio_site_presence"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_radio_presence_lifecycle"));

            try(ResultSet resultSet = statement.executeQuery("PRAGMA quick_check"))
            {
                assertTrue(resultSet.next());
                assertEquals("ok", resultSet.getString(1));
            }
        }
    }


    @Test
    void clearDetachesContextsButRetainsSystemWideP25Facts() throws Exception
    {
        Path database = database("clear.sqlite");

        try(Connection connection = open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_system(system_key,wacn,system_id,first_seen_ms,last_seen_ms)
                VALUES(50, 781824, 840, 1, 1)
                """);
            insertContext(connection, 10, "p25-a", 1, 1, 50);
            insertContext(connection, 11, "p25-b", 1, 2, 50);
            TrunkedIdentitySchema.Scope scope = TrunkedIdentitySchema.ensureScope(connection, 10, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.ensureScope(connection, 11, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            statement.executeUpdate("""
                INSERT INTO p25_zero_local_fq_talkgroup_summary(
                    scope_id,home_wacn,home_system_id,home_talkgroup_id,first_seen_ms,last_seen_ms
                ) VALUES((SELECT scope_id FROM trunked_identity_scope LIMIT 1), 0xABCDE, 0x321, 1200, 1, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(scope_id,radio_id,talkgroup_id,confirmed_at_ms)
                VALUES(%d, 1811524, 56133, 1000)
                """.formatted(scope.scopeId()));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_site_presence(
                    scope_id,radio_id,context_id,evidence_code,confirmed_at_ms
                ) VALUES(%d, 1811524, 10, 2, 1000)
                """.formatted(scope.scopeId()));

            TrunkedIdentitySchema.clearContext(connection, 10);
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM p25_zero_local_fq_talkgroup_summary"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_radio_affiliation"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_radio_site_presence"));
            TrunkedIdentitySchema.clearContext(connection, 11);
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_identity_scope_context"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM p25_zero_local_fq_talkgroup_summary"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_radio_affiliation"));
        }
    }

    @Test
    void validationRejectsChangedIdentityPrimaryKeyForeignKeyAndIndexShapes() throws Exception
    {
        Path primaryKeyDatabase = database("wrong-identity-primary-key.sqlite");

        try(Connection connection = open(primaryKeyDatabase);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("DROP TABLE trunked_identity_scope_context");
            statement.executeUpdate("""
                CREATE TABLE trunked_identity_scope_context (
                    context_id INTEGER NOT NULL REFERENCES receiver_context(id) ON DELETE CASCADE,
                    scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    PRIMARY KEY(scope_id, context_id)
                ) WITHOUT ROWID
                """);
            statement.executeUpdate("""
                CREATE INDEX idx_trunked_identity_scope_context_scope
                ON trunked_identity_scope_context(scope_id, context_id)
                """);
            SQLException exception = assertThrows(SQLException.class,
                () -> P25ActivityLogSchema.validate(connection));
            assertTrue(exception.getMessage().contains("primary key"));
        }

        Path foreignKeyDatabase = database("wrong-identity-foreign-key.sqlite");

        try(Connection connection = open(foreignKeyDatabase);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("DROP TABLE trunked_identity_scope_context");
            statement.executeUpdate("""
                CREATE TABLE trunked_identity_scope_context (
                    context_id INTEGER PRIMARY KEY REFERENCES receiver_context(id) ON DELETE CASCADE,
                    scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id),
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL
                ) WITHOUT ROWID
                """);
            statement.executeUpdate("""
                CREATE INDEX idx_trunked_identity_scope_context_scope
                ON trunked_identity_scope_context(scope_id, context_id)
                """);
            SQLException exception = assertThrows(SQLException.class,
                () -> P25ActivityLogSchema.validate(connection));
            assertTrue(exception.getMessage().contains("foreign keys"));
        }

        Path indexDatabase = database("wrong-identity-index.sqlite");

        try(Connection connection = open(indexDatabase);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_trunked_identity_scope_kind_last_seen");
            statement.executeUpdate("""
                CREATE INDEX idx_trunked_identity_scope_kind_last_seen
                ON trunked_identity_summary(scope_id, identity_kind_code, identity_id, last_seen_ms DESC)
                """);
            SQLException exception = assertThrows(SQLException.class,
                () -> P25ActivityLogSchema.validate(connection));
            assertTrue(exception.getMessage().contains("index"));
        }

        Path zeroLocalIndexDatabase = database("wrong-zero-local-index.sqlite");

        try(Connection connection = open(zeroLocalIndexDatabase);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_p25_zero_local_fq_retention");
            statement.executeUpdate("""
                CREATE INDEX idx_p25_zero_local_fq_retention
                ON p25_zero_local_fq_talkgroup_summary(
                    scope_id, last_seen_ms, home_wacn, home_system_id, home_talkgroup_id
                )
                """);
            SQLException exception = assertThrows(SQLException.class,
                () -> P25ActivityLogSchema.validate(connection));
            assertTrue(exception.getMessage().contains("index"));
        }

        Path presenceIndexDatabase = database("wrong-radio-presence-index.sqlite");

        try(Connection connection = open(presenceIndexDatabase);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_trunked_radio_site_presence_retention");
            statement.executeUpdate("""
                CREATE INDEX idx_trunked_radio_site_presence_retention
                ON trunked_radio_site_presence(scope_id, confirmed_at_ms, radio_id)
                """);
            SQLException exception = assertThrows(SQLException.class,
                () -> P25ActivityLogSchema.validate(connection));
            assertTrue(exception.getMessage().contains("index"));
        }
    }

    @Test
    void prunesOnlyUnconfiguredFactFreeContextsAndRetainsHistoricalScopeBoundaries() throws Exception
    {
        Path database = database("context-pruning.sqlite");
        String configuredGuid = radresGuid("configured-dmr");

        try(Connection connection = open(database);
            Statement statement = connection.createStatement())
        {
            insertContext(connection, 10, configuredGuid, 1, 3, null);
            insertContext(connection, 11, radresGuid("removed-empty-dmr"), 1, 3, null);
            insertContext(connection, 12, radresGuid("removed-history-dmr"), 1, 3, null);
            insertContext(connection, 15, radresGuid("removed-lifecycle-dmr"), 1, 3, null);
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, radres_guid, config_json
                ) VALUES('%s', 'TRUNKED', 0, '%s', '{}')
                """.formatted(configurationId("configured-dmr"), configuredGuid));

            TrunkedIdentitySchema.Scope configured = TrunkedIdentitySchema.ensureScope(connection, 10, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.Scope removedEmpty = TrunkedIdentitySchema.ensureScope(connection, 11, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.Scope removedHistory = TrunkedIdentitySchema.ensureScope(connection, 12, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.Scope removedLifecycle = TrunkedIdentitySchema.ensureScope(connection, 15, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            statement.executeUpdate("""
                INSERT INTO trunked_identity_summary(
                    scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms
                ) VALUES(%d, 1, 100, 1, 1)
                """.formatted(removedHistory.scopeId()));
            statement.executeUpdate("""
                INSERT INTO trunked_radio_presence_lifecycle(scope_id,radio_id,cleared_at_ms)
                VALUES(%d,500,1)
                """.formatted(removedLifecycle.scopeId()));

            statement.executeUpdate("""
                INSERT INTO p25_system(system_key,wacn,system_id,first_seen_ms,last_seen_ms)
                VALUES(50, 781824, 840, 1, 1)
                """);
            insertContext(connection, 13, radresGuid("removed-p25-a"), 1, 1, 50);
            insertContext(connection, 14, radresGuid("removed-p25-b"), 1, 1, 50);
            TrunkedIdentitySchema.Scope shared = TrunkedIdentitySchema.ensureScope(connection, 13, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            TrunkedIdentitySchema.ensureScope(connection, 14, 1,
                P25ActivityLogRecords.IdentityDomain.STANDARD);
            statement.executeUpdate("""
                INSERT INTO p25_zero_local_fq_talkgroup_summary(
                    scope_id, home_wacn, home_system_id, home_talkgroup_id, first_seen_ms, last_seen_ms
                ) VALUES(%d, 0xABCDE, 0x321, 1200, 1, 1)
                """.formatted(shared.scopeId()));

            P25ActivityLogSchema.pruneInactiveTrunkedContexts(connection);

            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM receiver_context WHERE id=10"));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope WHERE scope_id=%d
                """.formatted(configured.scopeId())));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM receiver_context WHERE id=11"));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope WHERE scope_id=%d
                """.formatted(removedEmpty.scopeId())));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM receiver_context WHERE id=12"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM receiver_context WHERE id=15"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM receiver_context WHERE id=13"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM receiver_context WHERE id=14"));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope_context WHERE scope_id=%d
                """.formatted(shared.scopeId())));

            statement.executeUpdate("""
                DELETE FROM trunked_identity_summary
                WHERE scope_id=%d
                """.formatted(removedHistory.scopeId()));
            statement.executeUpdate("""
                DELETE FROM p25_zero_local_fq_talkgroup_summary
                WHERE scope_id=%d
                """.formatted(shared.scopeId()));
            statement.executeUpdate("""
                DELETE FROM trunked_radio_presence_lifecycle
                WHERE scope_id=%d
                """.formatted(removedLifecycle.scopeId()));
            P25ActivityLogSchema.pruneInactiveTrunkedContexts(connection);

            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM receiver_context"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_identity_scope_context"));
            assertEquals(5, scalarLong(connection, "SELECT COUNT(*) FROM trunked_identity_scope"));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_scope
                WHERE scope_token='dmr:guid:%s'
                """.formatted(configuredGuid)));
        }
    }


    private Path database(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT OR IGNORE INTO alias_list(id,name,family,unmatched_talkgroup_record_enabled)
                VALUES(1,'P25-1','P25',0)
                """);
        }

        return database;
    }

    private Path activityDatabase(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            P25ActivityLogSchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            statement.executeUpdate("""
                INSERT OR IGNORE INTO alias_list(id,name,family,unmatched_talkgroup_record_enabled)
                VALUES(1,'P25-1','P25',0)
                """);
        }

        return database;
    }

    private static Connection open(Path database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }

        return connection;
    }

    private static P25ActivityLogRecords.ActivityEvent activity(
        long timestamp, String guid, String protocol, Integer target, Integer source, String targetKind,
        P25ActivityLogRecords.IdentityDomain identityDomain, boolean countedCall)
    {
        return activity(timestamp, guid, protocol, target, source, targetKind, identityDomain, countedCall,
            451_000_000L);
    }

    private static P25ActivityLogRecords.ActivityEvent activity(
        long timestamp, String guid, String protocol, Integer target, Integer source, String targetKind,
        P25ActivityLogRecords.IdentityDomain identityDomain, boolean countedCall, Long frequency)
    {
        return activity(timestamp, guid, protocol, target, source, targetKind, identityDomain, countedCall,
            frequency, P25ActivityLogRecords.P25TargetIdentity.UNKNOWN);
    }

    private static P25ActivityLogRecords.ActivityEvent activity(
        long timestamp, String guid, String protocol, Integer target, Integer source, String targetKind,
        P25ActivityLogRecords.IdentityDomain identityDomain, boolean countedCall, Long frequency,
        P25ActivityLogRecords.P25TargetIdentity p25TargetIdentity)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, protocol, P25ActivityLogRecords.Action.CALL,
            "CALL", source != null ? source.toString() : null, target != null ? target.toString() : null,
            targetKind, List.of(), frequency, null, 1, false, null, null, null, null, null, null, null,
            guid, protocol, null, countedCall, null, null, identityDomain, p25TargetIdentity);
    }

    private static P25ActivityLogRecords.ActivityEvent p25Activity(
        long timestamp, String guid, P25ActivityLogRecords.Action action, int target, int source,
        String targetKind, List<Integer> patchMembers, boolean countedCall,
        P25ActivityLogRecords.P25TargetIdentity p25TargetIdentity,
        List<P25ActivityLogRecords.P25PatchMemberIdentity> p25PatchMemberIdentities)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", action, "CALL_GROUP",
            Integer.toString(source), Integer.toString(target), targetKind, patchMembers, 855_000_000L, null, 1,
            false, null, null, null, null, null, null, null, guid, "APCO25", null, countedCall, null, null,
            P25ActivityLogRecords.IdentityDomain.STANDARD, p25TargetIdentity, p25PatchMemberIdentities);
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall resolvedCall(
        long sequence, long timestamp, String guid, String protocol,
        P25ActivityLogRecords.IdentityDomain identityDomain, Integer wacn, Integer systemId, long aliasListId,
        int destination, String destinationKind, List<Integer> patchMembers, Integer source, boolean encrypted,
        P25ActivityLogRecords.P25TargetIdentity p25TargetIdentity,
        List<P25ActivityLogRecords.P25PatchMemberIdentity> p25PatchMemberIdentities)
    {
        return new P25ActivityLogRecords.ResolvedLogicalCall(new LogicalCallId(31, sequence), timestamp,
            "GUID:" + guid, guid, protocol, identityDomain, wacn, systemId, aliasListId, destination,
            destinationKind, patchMembers, source, encrypted, encrypted ? 0x84 : null, encrypted ? 1 : null,
            p25TargetIdentity, p25PatchMemberIdentities, List.of());
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall contextResolvedCall(
        long sequence, long timestamp, String guid, String protocol,
        P25ActivityLogRecords.IdentityDomain identityDomain, int destination, int source)
    {
        return resolvedCall(sequence, timestamp, guid, protocol, identityDomain, null, null, 0,
            destination, Form.TALKGROUP.name(), List.of(), source, false,
            P25ActivityLogRecords.P25TargetIdentity.UNKNOWN, List.of());
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall p25ResolvedCall(
        long sequence, long timestamp, String guid, int systemId, int destination, int source,
        P25ActivityLogRecords.P25TargetIdentity p25TargetIdentity)
    {
        return resolvedCall(sequence, timestamp, guid, Protocol.APCO25.name(),
            P25ActivityLogRecords.IdentityDomain.STANDARD, 0xBEE00, systemId, 1, destination,
            Form.TALKGROUP.name(), List.of(), source, false, p25TargetIdentity, List.of());
    }

    private static TrunkedSiteSchema.Snapshot siteSnapshot(String guid, int protocol, int variant, int domain)
    {
        return siteSnapshot(1_000L, guid, protocol, variant, domain);
    }

    private static TrunkedSiteSchema.Snapshot siteSnapshot(long observedAt, String guid, int protocol, int variant,
                                                            int domain)
    {
        return new TrunkedSiteSchema.Snapshot(observedAt, guid, "hash", protocol, variant, domain,
            "System", "Site", "Aliases", protocol == TrunkedSiteSchema.PROTOCOL_DMR ? "DMR" : "NXDN",
            1, null, 2, null, null, null, null, null, null, null, null, 0, null,
            451_000_000L, 451_000_000L, List.of(), List.of());
    }

    private static P25ActivityLogRecords.SiteSnapshot p25SiteSnapshot(
        long observedAt, String guid, int systemId, String hash, String channel, long frequency)
    {
        return new P25ActivityLogRecords.SiteSnapshot(observedAt, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, hash, "APCO25", guid, "P25-1", "P25",
            0xBEE00, systemId, 0x293, 1, 2, null, null, false, null, frequency, frequency,
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", channel, frequency,
                null, false, 1)), List.of(), List.of(), List.of(), List.of());
    }

    private static P25ActivityLogRecords.DmrConventionalCall dmrConventionalCall(
        long start, long end, String configurationId, int talkgroup, int source)
    {
        return new P25ActivityLogRecords.DmrConventionalCall(start, end,
            "CONFIGURATION:" + configurationId, null,
            "Conventional DMR", "Aliases", 461_125_000L, 1,
            P25ActivityLogRecords.DmrTargetKind.GROUP, talkgroup, source, null, false);
    }

    private static String configurationId(String fixture)
    {
        return deterministicUuid("configuration:" + fixture);
    }

    private static String radresGuid(String fixture)
    {
        return deterministicUuid("radres:" + fixture);
    }

    private static String deterministicUuid(String fixture)
    {
        return UUID.nameUUIDFromBytes(fixture.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void insertContext(Connection connection, int id, String guid, int kind, int protocol,
                                      Integer systemKey) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO receiver_context(
                id,context_key,guid,kind_code,protocol_code,first_seen_ms,last_seen_ms,system_key,
                alias_list_id,alias_list_name
            ) VALUES(?,?,?,?,?,?,?,?,?,?)
            """))
        {
            statement.setInt(1, id);
            statement.setString(2, "GUID:" + guid);
            statement.setString(3, guid);
            statement.setInt(4, kind);
            statement.setInt(5, protocol);
            statement.setLong(6, 1);
            statement.setLong(7, 1);

            if(systemKey != null)
            {
                statement.setInt(8, systemKey);
            }
            else
            {
                statement.setNull(8, java.sql.Types.INTEGER);
            }

            if(systemKey != null && (protocol == 1 || protocol == 2))
            {
                statement.setLong(9, 1);
                statement.setString(10, "P25-1");
            }
            else
            {
                statement.setNull(9, java.sql.Types.INTEGER);
                statement.setNull(10, java.sql.Types.VARCHAR);
            }

            statement.executeUpdate();
        }
    }

    private static void assertIdentity(Connection connection, int kind, int id, long calls, long sourceCalls,
                                       long targetCalls, long recorded, long streamed, int counterpartKind,
                                       int counterpartId) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT logical_call_count,source_logical_call_count,target_logical_call_count,
                   recorded_output_count,streamed_output_count,
                   last_counterpart_kind_code,last_counterpart_id
            FROM trunked_identity_summary
            WHERE identity_kind_code=? AND identity_id=?
            """))
        {
            statement.setInt(1, kind);
            statement.setInt(2, id);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                assertEquals(calls, resultSet.getLong("logical_call_count"));
                assertEquals(sourceCalls, resultSet.getLong("source_logical_call_count"));
                assertEquals(targetCalls, resultSet.getLong("target_logical_call_count"));
                assertEquals(recorded, resultSet.getLong("recorded_output_count"));
                assertEquals(streamed, resultSet.getLong("streamed_output_count"));
                assertEquals(counterpartKind, resultSet.getInt("last_counterpart_kind_code"));
                assertEquals(counterpartId, resultSet.getInt("last_counterpart_id"));
            }
        }
    }

    private static long identityCount(Connection connection, String scopeToken) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM trunked_identity_summary summary
            JOIN trunked_identity_scope scope ON scope.scope_id=summary.scope_id
            WHERE scope.scope_token=?
            """))
        {
            statement.setString(1, scopeToken);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
    }

    private static long scalarLong(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static String scalarString(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static boolean objectExists(Connection connection, String type, String name) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM sqlite_master WHERE type=? AND name=?
            """))
        {
            statement.setString(1, type);
            statement.setString(2, name);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static void assertIndexedSearch(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            boolean indexedSearch = false;

            while(resultSet.next())
            {
                String detail = resultSet.getString("detail");

                if(detail != null && detail.contains("SEARCH") && !detail.contains("AUTOMATIC"))
                {
                    indexedSearch = true;
                }
            }

            assertTrue(indexedSearch, "Expected indexed search for: " + sql);
        }
    }

    private Connection open(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall p25Call(long sequence, long aliasListId,
                                                                     List<P25SiteIdentity> sites)
    {
        return new P25ActivityLogRecords.ResolvedLogicalCall(new LogicalCallId(1, sequence), START + sequence,
            "GUID:p25", "p25", Protocol.APCO25.name(), P25ActivityLogRecords.IdentityDomain.STANDARD,
            0x924, 0x649, aliasListId, 1201, Form.TALKGROUP.name(), List.of(), 700001, false,
            null, null, P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of(), sites);
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall contextCall(
        long sequence, String guid, Protocol protocol, P25ActivityLogRecords.IdentityDomain domain,
        int destination, int source)
    {
        return new P25ActivityLogRecords.ResolvedLogicalCall(new LogicalCallId(2, sequence), START + sequence,
            "GUID:" + guid, guid, protocol.name(), domain, null, null, 0, destination,
            Form.TALKGROUP.name(), List.of(), source, true, 0x84, 1,
            P25ActivityLogRecords.P25TargetIdentity.UNKNOWN, List.of(), List.of());
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static String scalarText(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
